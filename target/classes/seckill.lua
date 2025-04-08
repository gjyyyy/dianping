--参数列表
--1.1 优惠券id
local voucherId = ARGV[1]
--1.2 用户id
local userId = ARGV[2]

--库存key
local stockKey = 'seckill:stock:' .. voucherId
--订单key
local orderKey = 'seckill:order:' .. voucherId

--判断库存是否充足
if(tonumber(redis.call('get',stockKey)) <= 0) then
    --库存不足返回1
    return 1
end
--该用户是否下过一次单
if(redis.call('sismember',orderKey,userId) == 1) then
    --下过单，返回2
    return 2
end

--库存减一并将该用户加入redis中，保证不重复下单
redis.call('incrby',stockKey,-1)
redis.call('sadd',orderKey,userId)
return 0