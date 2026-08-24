package com.hmdp.utils;


import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    //时间戳开始标志
    private static final long BEGIN_TIMESTAMP=1786736483L;
    //序列号部分长度
    public static final int COUNT_BITS=32;

    public long nextId(String taskPrefix){
        //1.时间戳部分
        //1.1 获取当前时间秒
        LocalDateTime now=LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        //1.2 当前时间-开始时间=时间戳
        long timeStamp=nowSecond-BEGIN_TIMESTAMP;
        //2.序列号部分
        //2.1 每天存储一个key，防止数据量不足
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        //2.2 以这个key每天自增形成id
        long count = stringRedisTemplate.opsForValue().increment("icr:" + taskPrefix + ":" + date);
        //3.二进制拼接返回
        //3.1 时间戳部分左移，插入序列号部分(二进制或运算拼接)
        return timeStamp << COUNT_BITS | count;
    }

}
