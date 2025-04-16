package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final int COUNT_BITS = 32;
    private static final long BEGIN_TIMESTAMP = 1744588800L;
    public long nextId(String keyPrefix) {
        // 1. 生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timeStamp = nowSecond - BEGIN_TIMESTAMP;

        // 2. 生成序列号（使用 Redis 自增）
        // 2.1 获取当前日期，精确到天（按年、月、日分隔，便于后续统计）
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // 2.2 通过 Redis 自增生成序列号
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);

        // 3. 拼接得到全局 ID
        return timeStamp << COUNT_BITS | count;
    }

    // public static void main(String[] args) {
    //     // 打印当天开始时间
    //     LocalDateTime time = LocalDateTime.of(2025, 4, 14, 0, 0, 0);
    //     // 转化为 UTC 时区，然后保存为 Unix 时间戳（Epoch时间）
    //     long second = time.toEpochSecond(ZoneOffset.UTC);
    //     System.out.println("second = " + second);
    // }
}
