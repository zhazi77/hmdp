package com.hmdp.kafka;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.redisson.api.RLock;

import javax.annotation.Resource;

// NOTE: 默认情况下，如果要写入的主题不存在的话，会自动创建。
@Component
@Slf4j
// 标记一个方法或类作为 Kafka 消息的消费者，监听指定的 Kafka Topic 并处理消息。
@KafkaListener(topics = "voucher-orders", groupId = "voucher-order-group")
public class VoucherOrderConsumer {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private RedissonClient redissonClient1;

    // 当同一个 Topic 可能接收多种类型的消息（如 JSON 反序列化后的不同 Java 对象）时，通过 @KafkaHandler 实现基于类型的路由。
    @KafkaHandler
    public void handleVoucherOrder(VoucherOrder voucherOrder){
        // 1. 获取用户ID
        Long userId =  voucherOrder.getUserId();

        // 2. 创建分布式锁对象
        RLock lock = redissonClient1.getLock("lock:order:" + userId);

        // 3. 尝试获取锁
        boolean locked = lock.tryLock();
        if (!locked) {
            log.error("不允许重复下单！");
            return;
        }

        try {
            // 4. 处理订单创建逻辑
            voucherOrderService.createVoucherOrder(voucherOrder);
        } catch (Exception e) {
            log.error("处理订单异常", e);
        } finally {
            lock.unlock();
        }
    }
}
