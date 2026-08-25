--1.从redis获取voucherId的库存，需要voucherId拼接orderKey
local voucherId = ARGV[1]
local stockKey = 'seckill:stock:' .. voucherId
--2.从redis获取优惠券的购买用户set集合，需要userId，需要优惠券-用户订单集合key
local userId = ARGV[2]
local orderKey = 'seckill:order:' .. voucherId
--3.获取订单id
local orderId=ARGV[3]

--lua脚本查询用户购买资格

--1。查询库存是否充足
if (tonumber(redis.call('get', stockKey)) <= 0) then
    --库存不足
    return 1
end
--2.判断用户购买是否上限
if (redis.call('sismember', orderKey, userId) == 1) then
    --用户购买上限
    return 2
end
--3.通过检查，可以购买
--扣减redis中的库存信息
redis.call('incrby', stockKey, -1)
--将用户信息保存到优惠券-用户订单集合
redis.call('sadd', orderKey, userId)
--将订单信息发送到消息队列 xadd stream.orders * key-values
redis.call('XADD','stream.orders','*','userId',userId,'voucherId',voucherId,'id',orderId)
return 0