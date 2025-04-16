package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Component
public class CacheClient {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public void set(String key, Object value, Long time, TimeUnit unit) {
        String json = JSONUtil.toJsonStr(value);
        stringRedisTemplate.opsForValue().set(key, json, time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit seconds) {
        //封装逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(time));
        //写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <R, ID> R get(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        // 1. 查 Redis cache
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);

        // 2. 判断 cache 是否命中
        if (StrUtil.isNotBlank(json)) {
            // 命中有效数据，返回成功
            return JSONUtil.toBean(json, type);
        }
        if (json != null) {
            // 命中无效数据，返回错误
            return null;
        }

        // 3. cache 未命中，查数据库
        R r = dbFallback.apply(id);

        // 4. 判断 数据库 是否命中
        if (r == null) {
            // 未命中，写入一个空对象（应对缓存穿透），报错
            this.set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        // 5. 命中，将数据写入 cache
        this.set(key, r, time, unit);
        return r;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    public <R, ID> R getWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        // 1. 查 Redis cache
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);

        // 2. 判断 cache 是否命中
        if (StrUtil.isBlank(json)) {
            // 3. 未命中，直接结束 (热点数据，在一开始手动添加到 Redis 中）
            return null;
        }
        // 4. 命中，先反序列化 JSON 为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();

        // 5. 判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 5.1 未过期，直接返回数据
            return r;
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
                    // 查数据库
                    R newR = dbFallback.apply(id);
                    // 更新缓存
                    this.setWithLogicalExpire(key, newR, time, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unLock(lockKey);
                }
            });
        }

        // 6.4 获取锁失败，直接返回数据（过期数据）
        return r;
    }

    public boolean tryLock(String key, Long timeout) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", timeout, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    public void unLock(String key) {
        stringRedisTemplate.delete(key);
    }
}
