package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.config.Config;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    @Bean
    public RedissonClient redissonClient1() {
        // 配置 Redisson
        Config config = new Config();
        config.useSingleServer().setAddress("redis://127.0.0.1:6379");
        return Redisson.create(config);
    }

    @Bean
    public RedissonClient redissonClient2() {
        // 配置 Redisson
        Config config = new Config();
        config.useSingleServer().setAddress("redis://127.0.0.1:6380");
        return Redisson.create(config);
    }

    @Bean
    public RedissonClient redissonClient3() {
        // 配置 Redisson
        Config config = new Config();
        config.useSingleServer().setAddress("redis://127.0.0.1:6381");
        return Redisson.create(config);
    }
}
