package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient1;
    /**
     * 自己注入自己为了获取代理对象 @Lazy 延迟注入 避免形成循环依赖
     */
    @Resource
    @Lazy
    private IVoucherOrderService voucherOrderService;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.execute(new VoucherOrderHandler());
    }


    private class VoucherOrderHandler implements Runnable {
        String queueName = "stream.orders";
        @Override
        public void run() {
            while (true) {
                try {
                    // 1. 获取消息队列中的订单信息, XREADGROUP key GROUP g1 c1 COUNT 1 BLOCK 2000 STREAM stream.orders >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    // 2. 判断消息是否获取成功
                    if (list == null || list.isEmpty()) {
                        // 2.1. 获取失败，说明没有消息, continue
                        continue;
                    }
                    // 3. 解析订单
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    // 4. 获取成功，创建订单
                    handleVoucherOrder(voucherOrder);
                    // 5. ACK 确认消息 SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while (true) {
                try {
                    // 1. 获取 pending-list 中的订单信息, XREADGROUP key GROUP g1 c1 COUNT 1 STREAM stream.orders 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    // 2. 判断消息是否获取成功
                    if (list == null || list.isEmpty()) {
                        // 2.1. 获取失败，说明 pending-list 消息处理完毕, 回到消息队列的处理
                        break;
                    }
                    // 3. 重新消费（解析订单）
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    // 4. 获取成功，创建订单
                    handleVoucherOrder(voucherOrder);
                    // 5. ACK 确认消息 SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理 peding-list 异常", e);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }
        }

//    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
//    private class VoucherOrderHandler implements Runnable {
//        @Override
//        public void run() {
//            while (true) {
//                try {
//                    // 1. 获取队列中的订单信息
//                    VoucherOrder voucherOrder = orderTasks.take();
//                    // 2. 创建订单
//                    handleVoucherOrder(voucherOrder);
//                } catch (Exception e) {
//                    log.error("处理订单异常", e);
//                }
//            }
//        }

        private void handleVoucherOrder(VoucherOrder voucherOrder) {
            // 1. 获取用户 Id
            Long userId = voucherOrder.getUserId();
            // 2. 创建锁对象
            RLock lock = redissonClient1.getLock("lock:order:" + userId);
            // 3. 获取锁
            boolean isLock = lock.tryLock();
            // 4. 判断获取锁是否成功
            if (!isLock) {
                // 4.1 获取失败，记录
                log.error("获取锁失败");
                return ;
            }
            try {
                voucherOrderService.createVoucherOrder(voucherOrder);
            } catch (IllegalStateException e) {
                throw new RuntimeException(e);
            } finally {
                lock.unlock();
            }
        }
    }

    // private IVoucherOrderService proxy;
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1. 执行 Lua 脚本
        Long userId = UserHolder.getUser().getId();
        Long orderId = redisIdWorker.nextId("order");
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                orderId.toString());
        // 2. 判断返回值是否为 0 （是否有购买资格）
        int r = result.intValue();
        if (r != 0) {
            // 2.1. 无资格，返回
            return Result.fail(r == 1  ? "库存不足" : "请勿重复下单");
        }

        // 3. 获取代理对象
        // TODO: 使用代理对象的方案在最后一条数据时总是会有问题，为什么？
        // proxy = (IVoucherOrderService) AopContext.currentProxy();

        // 4. 返回订单 Id
        return Result.ok(orderId);
    }

//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        // 1. 执行 Lua 脚本
//        Long userId = UserHolder.getUser().getId();
//        Long result = stringRedisTemplate.execute(
//                SECKILL_SCRIPT,
//                Collections.emptyList(),
//                voucherId.toString(),
//                userId.toString());
//        // 2. 判断返回值是否为 0 （是否有购买资格）
//        int r = result.intValue();
//        if (r != 0) {
//            // 2.1. 无资格，返回
//            return Result.fail(r == 1  ? "库存不足" : "请勿重复下单");
//        }
//
//        // 2.2. 有资格，放入阻塞队列
//        long orderId = redisIdWorker.nextId("order");
//        VoucherOrder order = new VoucherOrder();
//        order.setId(orderId);
//        order.setUserId(userId);
//        order.setVoucherId(voucherId);
//        orderTasks.add(order);
//
//        // 3. 获取代理对象
//        proxy = (IVoucherOrderService) AopContext.currentProxy();
//
//        // 4. 返回订单 Id
//        return Result.ok(orderId);
//    }

    // @Override
    // public Result seckillVoucher(Long voucherId) {
    //     // 1. 查询优惠券
    //     SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
    //     // TODO: 这里不判空是否有问题？

    //     // 2. 判断秒杀是否开始
    //     if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
    //         return Result.fail("秒杀还未开始！");
    //     }

    //     // 3. 判断秒杀是否结束
    //     if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
    //         return Result.fail("秒杀已经结束！");
    //     }

    //     // 4. 查询库存
    //     Integer stock = voucher.getStock();

    //     // 5. 若无库存，返回
    //     if (stock < 1) {
    //         return Result.fail("库存不足！");
    //     }

    //     // 8. 返回订单 Id
    //     Long userId = UserHolder.getUser().getId();
    //     // SimpleRedisLock lock = new SimpleRedisLock("lock:order:" + userId, stringRedisTemplate);
    //     RLock lock = redissonClient1.getLock("lock:order:" + userId);

    //     boolean isLock = lock.tryLock();
    //     if (!isLock) {
    //         return Result.fail("请勿重复下单！");
    //     }
    //     try {
    //         IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
    //         return proxy.createVoucherOrder(voucherId);
    //     } catch (IllegalStateException e) {
    //         throw new RuntimeException(e);
    //     } finally {
    //         lock.unlock();
    //     }
    // }

    @Transactional
    public void createVoucherOrder(VoucherOrder order) {
        // 6. 一人一单
        // 6.1. 查询订单
        // NOTE: 可以把简单的判断逻辑交给数据库做，把并发问题转移给数据库
        Long userId = order.getUserId();
        Long count = query().eq("user_id", userId).eq("voucher_id", order).count();
        // 6.2. 判断是否存在
        if (count > 0L) {
            // 6.3. 若已存在，返回
            log.error("用户已经购买过了");
            return;
        }
        // 6. 若有库存，扣减库存
        // 利用数据库的行锁来解决超卖问题，一条 UPDATE 语句内的操作是原子的
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", order.getVoucherId())
                .gt("stock", 0).update();

        if (!success) {
            // 扣减失败
            log.error("库存不足");
            return;
        }

        // 7. 保存订单
        save(order);
    }
}