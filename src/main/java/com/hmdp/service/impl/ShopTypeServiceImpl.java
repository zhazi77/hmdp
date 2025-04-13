package com.hmdp.service.impl;

import ch.qos.logback.core.util.TimeUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryTypeList() {
        // 1. 查 Redis cache
        String key = RedisConstants.CACHE_SHOPTYPE_KEY;
        List<String> shopTypeJsonList = stringRedisTemplate.opsForList().range(key, 0, -1);

        // 2. 判断 cache 是否命中
        if (shopTypeJsonList != null && !shopTypeJsonList.isEmpty()) {
            // 命中
            List<ShopType> shopTypeList = shopTypeJsonList.stream() // 将JsonList转换为一个Stream流
                    .map(json -> {
                        return JSONUtil.toBean(json, ShopType.class);
                    })
                    .collect(Collectors.toList()); // 将Stream中的元素收集到一个新的List中
            return Result.ok(shopTypeList);
        }

        // 3. cache 未命中，查数据库
        List<ShopType> shopTypeList = query().orderByAsc("sort").list();

        // 4. 判断 数据库 是否命中
        if (shopTypeList == null || shopTypeList.isEmpty()) {
            // 未命中，报错
            return Result.fail("店铺类型不存在!");
        }

        // 5. 命中，将数据写入 cache，注意保持顺序
        stringRedisTemplate.opsForList().rightPushAll(key, shopTypeList.stream()
                .map(JSONUtil::toJsonStr)
                .collect(Collectors.toList()));
        return Result.ok(shopTypeList);
    }
}
