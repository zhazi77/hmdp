package com.hmdp;

import com.hmdp.utils.SimpleRedisLock;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

@Slf4j
@SpringBootTest
public class RedissonTest {

    @Resource
    private RedissonClient redissonClient1;
    @Resource
    private RedissonClient redissonClient2;
    @Resource
    private RedissonClient redissonClient3;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private RLock lock;

    // private SimpleRedisLock lock;

    @BeforeEach
    void setUp() {
        RLock lock1 = redissonClient1.getLock("order");
        RLock lock2 = redissonClient2.getLock("order");
        RLock lock3 = redissonClient3.getLock("order");
        // 创建联锁
        lock = redissonClient1.getMultiLock(lock1, lock2, lock3);

        // lock = new SimpleRedisLock("order", stringRedisTemplate);
    }

    @Test
    void method1() {
        boolean isLock = lock.tryLock();
        if (!isLock) {
            log.error("1...获取锁失败");
            return ;
        }
        try {
            log.info("1...获取锁成功");
            method2();
            log.info("1...开始执行业务");
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            log.warn("1...开始释放锁");
            lock.unlock();
        }
    }

    void method2() throws InterruptedException {
        boolean isLock = lock.tryLock(1L, TimeUnit.SECONDS);
        if (!isLock) {
            log.error("2...获取锁失败");
            return ;
        }
        try {
            log.info("2...获取锁成功");
            log.info("2...开始执行业务");
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            log.warn("2...开始释放锁");
            lock.unlock();
        }
    }
}
