package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Autowired
    private ISeckillVoucherService seckillVoucherService;//秒杀订单服务

    @Autowired
    private RedisIdWorker redisIdWorker;//全局id生成器

    @Autowired
    private StringRedisTemplate stringRedisTemplate;//redis

    @Autowired
    private RedissonClient redissonClient;//redisson

    private IVoucherOrderService proxy;//代理对象

    //加载lua脚本
    public static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    //创建线程池，开启线程完成阻塞队列里的订单创建
    public static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    //向线程池提交任务，初始化完毕就需要开启任务
    @PostConstruct//初始化完毕就执行
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new voucherOrderHandler());
    }

    //使用内部类创建订单任务类，提交任务自动在数据库创建订单
    private class voucherOrderHandler implements Runnable {
        String queueName="stream.orders";
        @Override
        public void run() {
            //无限循环，不断取出订单创建订单
            while (true) {
                try {
                    //1.从消息队列获得消息 xreadgroup GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS queueName >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    //2.判断是否获取成功
                    if(list==null||list.isEmpty()){
                        //3.失败则重新获取消息
                        continue;
                    }
                    //4.获取成功则处理订单，写入数据库
                    //4.1解析消息内容
                    //获取消息内容
                    MapRecord<String, Object, Object> record = list.get(0);
                    //从消息中获取传递的id信息
                    Map<Object, Object> values = record.getValue();
                    //封装为java对象
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //4.2写入数据库
                    handleVoucherOrder(voucherOrder);
                    //5.发送确认ack
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                } catch (Exception e) {
                    //出现异常，消息会被放到pending-list中
                    //从pending-list里取出异常消息处理
                    log.error("订单处理异常1",e);
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            //循环处理pending-list里的消息
            while(true){
                try {
                    //1.获取pending-list里的异常消息
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    //2.判断是否获取成功
                    if(list==null||list.isEmpty()){
                        //3.失败则说明pl里面没有异常消息，不需要处理，直接退出循环
                        break;
                    }
                    //4.获取成功
                    //4.1解析消息内容
                    MapRecord<String, Object, Object> record = list.get(0);
                    //4.2获取消息里的id信息
                    Map<Object, Object> values = record.getValue();
                    //4.3封装为java对象
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //4.4保存到数据库内
                    handleVoucherOrder(voucherOrder);
                    //5.发送确认ack
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                } catch (Exception e) {
                    //订单处理再次异常，进行下一次循环即可
                    log.error("订单处理异常2",e);
                    try {
                        //防止重试太频繁
                        Thread.sleep(20);
                    }catch (InterruptedException exception){
                        exception.printStackTrace();
                    }
                }
            }
        }
    }

    //处理订单写入数据库
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //1.对同一用户的多个线程上锁，防止数据库访问的线程并发问题
        //1.1获取用户id
        Long userId = voucherOrder.getUserId();
        //1.2获取锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //1.3尝试上锁
        boolean isLock = lock.tryLock();
        //1.4判断上锁是否成功
        if (!isLock) {
            //上锁失败
            log.error("请勿重复点击");
            return;
        }
        //1.5上锁成功
        //2开启事务并写入数据库
        //2.1用代理对象调用事务方法
        try {
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            //释放锁
            lock.unlock();
        }
    }

    //v3.0 秒杀下单-异步执行，redis判断资格->redis.stream消息队列消费者组模式->异步保存订单
    @Override
    public Result seckillServer(Long voucherId) {
        //订单id,userId
        long orderId = redisIdWorker.nextId("order:");
        Long userId = UserHolder.getUser().getId();
        //1.执行lua脚本，初步判断资格并扣减redis中的库存
        Long executeResult = stringRedisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(),String.valueOf(orderId)
        );
        //2.判断执行结果
        int result = executeResult.intValue();
        if (result != 0) {
            //执行失败
            //1.库存不足
            if (result == 1) return Result.fail("优惠券已售罄1");
            //2.用户购买上限
            if (result == 2) return Result.fail("购买数量已达上限1");
        }
        //3.执行成功，将购买消息存入消息队列stream.orders
        //5获取代理对象，执行消息队列提交任务
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //6.返回订单id
        return Result.ok(orderId);
    }

    @Override
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //1.判断一人一单
        //1.1查询相同用户的相同优惠券购买订单数
        Integer orderCount = query().eq("voucher_id", voucherOrder.getVoucherId())
                .eq("user_id", voucherOrder.getUserId()).count();
        //1.2判断是否上限
        if (orderCount > 0) {
            //超出购买上限
            log.error("购买数量已达上限2");
            return;
        }

        //2.没有上限，扣减库存，写入订单数据
        //2.1扣减库存，用数据库原子操作
        boolean updateStock = seckillVoucherService.update().setSql("stock = stock -1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0).update();

        //2.2判断扣减是否成功
        if (!updateStock) {
            //库存不足，扣减失败
            log.error("优惠券已售罄2");
            return;
        }
        //扣减成功
        //3.写入订单数据
        save(voucherOrder);
    }

    /*
    //创建阻塞队列。存放待写入数据库的订单
    public BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    //使用内部类创建订单任务类，提交任务自动在数据库创建订单
    private class voucherOrderHandler implements Runnable {

        @Override
        public void run() {
            //无限循环，不断取出订单创建订单
            while (true) {
                try {
                    //1.从阻塞队列取出待创建的订单
                    VoucherOrder voucherOrder = orderTasks.take();
                    //2.写入数据库
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("订单处理异常 ");
                }
            }

        }
    }

    //处理订单写入数据库
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //1.对同一用户的多个线程上锁，防止数据库访问的线程并发问题
        //1.1获取用户id
        Long userId = voucherOrder.getUserId();
        //1.2获取锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //1.3尝试上锁
        boolean isLock = lock.tryLock();
        //1.4判断上锁是否成功
        if (!isLock) {
            //上锁失败
            log.error("请勿重复点击");
            return;
        }
        //1.5上锁成功
        //2开启事务并写入数据库
        //2.1用代理对象调用事务方法
        try {
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            //释放锁
            lock.unlock();
        }
    }

    //v2.0 秒杀下单-异步执行，redis判断资格->阻塞消息队列->异步保存订单
    @Override
    public Result seckillServer(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        //1.执行lua脚本，初步判断资格并扣减redis中的库存
        Long executeResult = stringRedisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );
        //2.判断执行结果
        int result = executeResult.intValue();
        if (result != 0) {
            //执行失败
            //1.库存不足
            if (result == 1) return Result.fail("优惠券已售罄");
            //2.用户购买上限
            if (result == 2) return Result.fail("购买数量已达上限");
        }
        //3.执行成功，将购买消息存入阻塞队列
        //3.1生成订单id
        long orderId = redisIdWorker.nextId("order:");
        //3.2生成订单
        //-1创建优惠券订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //-2设置订单id
        voucherOrder.setId(orderId);
        //-3设置下单用户id
        voucherOrder.setUserId(userId);
        //-4设置优惠券id
        voucherOrder.setVoucherId(voucherId);

        //-5获取代理对象，执行阻塞队列提交任务
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //3.3 保存到阻塞队列
        orderTasks.add(voucherOrder);
        //4.返回订单id
        return Result.ok(orderId);
    }

    @Override
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //1.判断一人一单
        //1.1查询相同用户的相同优惠券购买订单数
        Integer orderCount = query().eq("voucher_id", voucherOrder.getVoucherId())
                .eq("user_id", voucherOrder.getUserId()).count();
        //1.2判断是否上限
        if (orderCount > 0) {
            //超出购买上限
            log.error("购买数量已达上限");
            return;
        }

        //2.没有上限，扣减库存，写入订单数据
        //2.1扣减库存，用数据库原子操作
        boolean updateStock = seckillVoucherService.update().setSql("stock = stock -1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0).update();

        //2.2判断扣减是否成功
        if (!updateStock) {
            //库存不足，扣减失败
            log.error("优惠券已售罄");
            return;
        }
        //扣减成功
        //3.写入订单数据
        save(voucherOrder);
    }*/

    /*//v1.0 秒杀下单-串行执行，性能较差
    @Override
    public Result seckillServer(Long voucherId) throws InterruptedException {
        //1.查询voucher信息-用seckill服务
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        //2.检查begin未开始则拦截
        LocalDateTime beginTime = seckillVoucher.getBeginTime();
        if (beginTime.isAfter(LocalDateTime.now())) {
            return Result.fail("活动尚未开始");
        }
        //3.检查end已结束则拦截
        LocalDateTime endTime = seckillVoucher.getEndTime();
        if (endTime.isBefore(LocalDateTime.now())) {
            return Result.fail("活动已结束");
        }
        //4.在活动时间内
        //5.1 检查库存stock，没有则拦截
        if (seckillVoucher.getStock() < 1) {
            return Result.fail("优惠券已售罄");
        }

        //保证每个用户的多个线程的一致性，每次操作前先上锁，事务提交后再释放
        Long userId = UserHolder.getUser().getId();

        //获取锁对象,使用自定义锁
        //SimpleRedisLock userOrderRedisLock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);

        //获取锁对象，使用redisson
        RLock userOrderRedissonLock = redissonClient.getLock("order:" + userId);

        //尝试上锁
        boolean isLock = userOrderRedissonLock.tryLock(2L, TimeUnit.SECONDS);

        //上锁失败，直接返回错误信息
        if (!isLock) {
            return Result.fail("请勿重复点击");
        }
        //成功-订单创建
        //因为是事务，所以应该用代理对象调用，否则事务失效
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            //不管是否创建成功，都要释放锁
            userOrderRedissonLock.unlock();
        }
    }

    @Override
    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        //5.2 一人一单
        //查询当前userid是否购买了voucherid的优惠券
        Long userId = UserHolder.getUser().getId();
        Integer userCount = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (userCount > 0) {
            //用户已经购买过，拦截请求
            return Result.fail("购买数量已达上限");
        }
        //6.用户未购买过，且库存充足
        //6.1购买后库存-1
        boolean success = seckillVoucherService.update().setSql("stock = stock - 1").eq("voucher_id", voucherId).gt("stock", 0).update();
        if (!success) {
            return Result.fail("已售罄");
        }
        //6.2创建优惠券订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //7.设置订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        //8.设置下单用户id
        voucherOrder.setUserId(userId);
        //9.设置优惠券id
        voucherOrder.setVoucherId(voucherId);
        //10.保存到数据库中
        save(voucherOrder);

        //返回订单id
        return Result.ok(orderId);
    }*/
}
