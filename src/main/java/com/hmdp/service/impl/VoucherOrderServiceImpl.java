package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.MqConst;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private RabbitTemplate rabbitTemplate;

    @Resource
    private RedissonClient redissonClient;


    public static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static{
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

//    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
//    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
//
//    @PostConstruct
//    public void init(){
//        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
//    }
//    private class VoucherOrderHandler implements Runnable{
//        @Override
//        public void run() {
//            while(true){
//                try {
//                    VoucherOrder voucherOrder = orderTasks.take();
//                    handleVoucherOrder(voucherOrder);
//                } catch (Exception e) {
//                    log.info("处理订单异常："+e);
//                }
//            }
//        }
//    }

    public void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = lock.tryLock();
        if(!isLock){
            log.info("一人限购一单");
            return ;
        }
        try {
            //采用spring的事务管理时，必须调用spring管理的代理对象
            //此时VoucherOrderServiceImpl在spring中的代理对象为IVoucherService
            proxy.createVoucherOrder(voucherOrder);
        }finally {
            lock.unlock();
        }
    }
    public IVoucherOrderService proxy;

    //秒杀代金券
    @Override
    public Result secKillVouvher(Long voucherId) {
        //用lua脚本先原子性判断是否能进行秒杀
        Long userId = UserHolder.getUser().getId();

        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );
        int r = result.intValue();
        if(r != 0){
            return Result.fail(r == 1?"优惠券库存不足！" : "一人只能下一单！");
        }

        //异步执行
        //将下单信息加入阻塞队列
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        //orderTasks.add(voucherOrder);
        rabbitTemplate.convertAndSend(MqConst.ORDER_EXCHANGE,MqConst.ORDER_ROUTINGKEY,voucherOrder);

       // proxy = (IVoucherOrderService) AopContext.currentProxy();
        //返回订单id
        return Result.ok(orderId);
    }

/*    //秒杀代金券
    @Override
    public Result secKillVouvher(Long voucherId) {
        //得到秒杀券
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        //秒杀活动是否开始
        LocalDateTime beginTime = seckillVoucher.getBeginTime();
        if(beginTime.isAfter(LocalDateTime.now())){
            return Result.fail("活动未开始！");
        }
        //秒杀活动是否结束
        LocalDateTime endTime = seckillVoucher.getEndTime();
        if(endTime.isBefore(LocalDateTime.now())){
            return Result.fail("活动已经结束！");
        }
        //库存是否足够
        Integer stock = seckillVoucher.getStock();
        if(stock<=0){
            return Result.fail("库存不足！");
        }

        Long userId = UserHolder.getUser().getId();
        //这里是新增订单，不能使用乐观锁，故采用悲观锁
        //对每个用户加锁，不同用户只能限购一单
//        synchronized (userId.toString().intern()) {
//            //采用spring的事务管理时，必须调用spring管理的代理对象
//            //此时VoucherOrderServiceImpl在spring中的代理对象为IVoucherService
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        }

        //创建分布式锁1
//        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//        boolean isLock = lock.tryLock(1200);
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = lock.tryLock();
        if(!isLock){
            return Result.fail("一人限购一单");
        }

        try {
            //采用spring的事务管理时，必须调用spring管理的代理对象
            //此时VoucherOrderServiceImpl在spring中的代理对象为IVoucherService
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }finally {
            lock.unlock();
        }
    }*/



    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder){
        //一人一单
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if(count > 0){
            log.info("一人限购一单！");
            return ;
        }
        //扣库存
        // 可能会出现超卖问题，这里采用乐观锁来解决，用stock来替代版本号,用stock>0 可以完美解决超卖问题并且不会出现卖不完的情况（stock = 上次查出的stock）
        boolean update = seckillVoucherService.
                update().setSql("stock = stock - 1")
                .eq("voucher_id", voucherId).gt("stock",0).update();
        if(!update){
            log.info("库存不足！");
            return ;
        }
        //生成订单
        save(voucherOrder);
    }
}
