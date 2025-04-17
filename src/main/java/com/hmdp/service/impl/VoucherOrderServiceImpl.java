package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
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

    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1. 查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // TODO: 这里不判空是否有问题？

        // 2. 判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀还未开始！");
        }

        // 3. 判断秒杀是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束！");
        }

        // 4. 查询库存
        Integer stock = voucher.getStock();

        // 5. 若无库存，返回
        if (stock < 1) {
            return Result.fail("库存不足！");
        }

        // 8. 返回订单 Id
        Long userId = UserHolder.getUser().getId();
        // SimpleRedisLock lock = new SimpleRedisLock("lock:order:" + userId, stringRedisTemplate);
        RLock lock = redissonClient1.getLock("lock:order:" + userId);

        boolean isLock = lock.tryLock();
        if (!isLock) {
            return Result.fail("请勿重复下单！");
        }
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
    }

    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        // 6. 一人一单
        // 6.1. 查询订单
        // NOTE: 可以把简单的判断逻辑交给数据库做，把并发问题转移给数据库
        Long userId = UserHolder.getUser().getId();
        Long count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        // 6.2. 判断是否存在
        if (count > 0L) {
            return Result.fail("您已经购买过了！");
        }
        // 6.3. 若已存在，返回

        // 6. 若有库存，扣减库存
        // 利用数据库的行锁来解决超卖问题，一条 UPDATE 语句内的操作是原子的
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0).update();

        if (!success) {
            // 扣减失败
            return Result.fail("库存不足！");
        }

        // 7. 创建订单
        VoucherOrder order = new VoucherOrder();
        // 7.1 设置订单 id
        long orderId = redisIdWorker.nextId("order");
        order.setId(orderId);
        // 7.2 设置用户 id
        UserDTO userDTO = UserHolder.getUser();
        order.setUserId(userDTO.getId());
        // 7.3 设置优惠券 id
        order.setVoucherId(voucherId);
        // 7.4 保存订单
        save(order);
        return Result.ok(orderId);
    }
}
