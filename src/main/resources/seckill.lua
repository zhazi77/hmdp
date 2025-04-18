-- 参数列表
-- 1.1 优惠券 id
local voucherId = ARGV[1]
-- 1.2 用户 id
local userId = ARGV[2]
-- 1.3 订单 id
local orderId = ARGV[3]

-- key 列表
-- 库存 Key
local stockKey = "seckill:stock:" .. voucherId
-- 订单 Key
local orderKey = "seckill:order:" .. voucherId

if (tonumber(redis.call("get", stockKey)) <= 0) then
    -- 库存不足
    return 1
end

if (redis.call("sismember", orderKey, userId) == 1) then
    -- 用户已经购买过
    return 2
end

-- 扣减库存
redis.call("incrby", stockKey, -1)
-- 下单
redis.call("sadd", orderKey, userId)
-- 将订单放入消息队列中(已经在外面手动建好了)
redis.call("xadd", "stream.orders", "*", "userId", userId, "voucherId", voucherId, "id", orderId)
return 0