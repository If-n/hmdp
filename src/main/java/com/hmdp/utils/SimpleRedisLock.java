package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import io.lettuce.core.dynamic.annotation.Key;
import org.apache.ibatis.javassist.ClassPath;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;

public class SimpleRedisLock implements ILock {

    //数据库连接操作对象
    private StringRedisTemplate stringRedisTemplate;
    //锁前缀标识
    private  static final String KEY_PREFIX="lock:";
    //锁的对应业务标识
    private String name;
    //获取线程的uuid（不同机器的线程id可能一样，加上uuid则不可能相同）
    private static final String ID_PREFIX= UUID.randomUUID().toString(true);
    //注入lua脚本
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        //指定lua脚本文件位置
        UNLOCK_SCRIPT=new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    public SimpleRedisLock(String name,StringRedisTemplate stringRedisTemplate){
        this.name=name;
        this.stringRedisTemplate=stringRedisTemplate;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        //获取线程标识作为value部分
        long threadId =Thread.currentThread().getId();
        Boolean isLock = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, ID_PREFIX+ threadId, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(isLock);
    }

    @Override
    public void unlock() {
        //用lua脚本实现锁的释放，保证原子性
        stringRedisTemplate.execute(UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX+Thread.currentThread().getId());
    }


    /*@Override
    public void unlock() {
        //根据value部分判断锁是不是属于自己线程的

        //本线程的value标识
        long threadId = Thread.currentThread().getId();
        String thisValue =  ID_PREFIX+threadId ;

        //锁的value标识
        String lockValue = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        if(thisValue.equals(lockValue)){
            //是自己线程上的锁
            //释放
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }
    }*/
}
