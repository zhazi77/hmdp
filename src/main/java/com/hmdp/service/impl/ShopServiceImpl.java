package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Resource
    CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        // v1: 通过缓存空对象应对缓存穿透
        // Shop shop = cacheClient.get(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // v2.1: 通过互斥锁应对缓存击穿，通过缓存空对象应对缓存穿透
        // Shop shop = queryWithMutex(id);

        // v2.2: 通过互斥锁应对缓存击穿，通过缓存空对象应对缓存穿透
        Shop shop = cacheClient
                .getWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.SECONDS);

        if (shop == null) {
            return Result.fail("店铺不存在!");
        }
        return Result.ok(shop);
    }

    // TODO: 一并放到 CacheClient 中
    public Shop queryWithMutex(Long id) {
        // 1. 查 Redis cache
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 2. 判断 cache 是否命中
        if (StrUtil.isNotBlank(shopJson)) {
            // 命中有效数据，返回成功
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        if (shopJson != null) {
            // 命中无效数据，返回错误
            return null;
        }

        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            // 3. 未命中，缓存重建
            // 3.1 尝试获取锁
            // 3.2 判断是否获取成功
            boolean isLock = cacheClient.tryLock(lockKey, LOCK_SHOP_TTL);
            if (!isLock) {
                // 3.3 获取锁失败，休眠并重试
                Thread.sleep(50);
                // TODO: 看看能不能不使用递归
                return queryWithMutex(id);
            }

            // 3.4 获取锁成功，查数据库，更新缓存
            shop = getById(id);
            // 判断 数据库 是否命中
            if (shop == null) {
                // 4. 未命中，写入一个空对象（应对缓存穿透），报错
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 5. 命中，将数据写入 cache
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            // 6. 释放锁
            cacheClient.unLock(lockKey);
        }

        return shop;
    }

    @Override
    @Transactional
    public Result updateShop(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空!");
        }

        // 1. 更新数据库
        updateById(shop);

        // 2. 删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 1. 判断是否需要根据坐标查询
        if (x == null || y == null) {
            // 根据类型分页查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }

        // 2. 计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        // 3. 依据坐标查询 Redis, 依据距离排序、分页。返回shopId集合,distance
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs().includeDistance().limit(end)
                );
        // 4. 解析出 id
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }

        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        // NOTE: 尽量把数据库查询合并到一起
        List<Long> ids = new ArrayList<>(end - from);
        Map<String, Double> idDistanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(geoResult -> {
            // 4.1 获取店铺id (String)
            String shopIdStr = geoResult.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            // 4.2 获取店铺距离
            Double distance = geoResult.getDistance().getValue();
            idDistanceMap.put(shopIdStr, distance);
        });

        if (list.size() <= from) {
            return Result.ok(Collections.emptyList());
        }

        // 5. 根据 id 查询 shop
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + StrUtil.join(",", ids) + ")").list();
        shops.forEach(shop -> {
            shop.setDistance(idDistanceMap.get(shop.getId().toString()));
        });

        return Result.ok(shops);
    }
}
