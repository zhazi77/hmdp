package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.io.Serializable;
import java.time.LocalDateTime;
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

    @Override
    public Result queryById(Long id) {
        // v1: 通过缓存空对象应对缓存穿透
        // Shop shop = queryWithPassThrough(id);

        // v2.1: 通过互斥锁应对缓存击穿，通过缓存空对象应对缓存穿透
        // Shop shop = queryWithMutex(id);

        // v2.2: 通过互斥锁应对缓存击穿，通过缓存空对象应对缓存穿透
        Shop shop = queryWithLogicalExpire(id);

        if (shop == null) {
            return Result.fail("店铺不存在!");
        }
        return Result.ok(shop);
    }

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
            boolean isLock = tryLock(lockKey, LOCK_SHOP_TTL);
            if (!isLock) {
                // 3.3 获取锁失败，休眠并重试
                Thread.sleep(50);
                // TODO: 看看能不能不使用递归
                return queryWithMutex(id);
            }

            // 3.4 获取锁成功，查数据库，更新缓存
            shop = getById(id);
            // 模拟重建延迟
            Thread.sleep(200);
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
            unLock(lockKey);
        }

        return shop;
    }

    public Shop queryWithPassThrough(Long id) {
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

        // 3. cache 未命中，查数据库
        Shop shop = getById(id);

        // 4. 判断 数据库 是否命中
        if (shop == null) {
            // 未命中，写入一个空对象（应对缓存穿透），报错
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        // 5. 命中，将数据写入 cache
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop));
        stringRedisTemplate.expire(key, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }

    // TODO: 补充线程池相关的知识
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public Shop queryWithLogicalExpire(Long id) {
        // 1. 查 Redis cache
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 2. 判断 cache 是否命中
        if (StrUtil.isBlank(shopJson)) {
            // 3. 未命中，直接结束 (热点数据，在一开始手动添加到 Redis 中）
            return null;
        }
        // 4. 命中，先反序列化 JSON 为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();

        // 5. 判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 5.1 未过期，直接返回店铺信息
            return shop;
        }

        // 5.2 过期，需要缓存重建

        // 6. 缓存重建
        // 6.1 尝试获取锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey, LOCK_SHOP_TTL);

        // 6.2 判断是否获取成功
        if (isLock) {
            // 6.3 获取锁成功，开启独立线程重建缓存
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 重建缓存
                    this.saveShop2Redis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unLock(lockKey);
                }
            });
        }

        // 6.4 获取锁失败，直接返回店铺信息（过期数据）
        return shop;
    }

    private boolean tryLock(String key, Long timeout) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", timeout, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }

    public void saveShop2Redis(Long id, Long expireSecond) throws InterruptedException {
        // 1. 查询店铺数据
        Shop shop = getById(id);
        // 模拟查询延迟
        Thread.sleep(200);

        // 2. 封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSecond));

        // 3. 写入 Redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
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
}
