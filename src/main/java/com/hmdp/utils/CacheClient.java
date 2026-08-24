package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Component
@Slf4j
public class CacheClient {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    //创建一个线程池
    private ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);


    //将数据以及过期时间存入redis
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }


    //将数据以及逻辑过期时间存入redis
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        //设置逻辑过期字段
        LocalDateTime expireTime = LocalDateTime.now().plusSeconds(unit.toSeconds(time));
        //组装redisdata
        redisData.setData(value);
        redisData.setExpireTime(expireTime);
        //存入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    //查询数据-with解决缓存穿透、雪崩
    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallBack,Long time,TimeUnit unit) {
        //1.redis查询
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.存在直接返回（存在有效数据）
        if (StrUtil.isNotBlank(json)) {
            R r = JSONUtil.toBean(json, type);
            return r;
        }
        //2.5 存在但值为“”（空数据拦截）
        if (json != null) {
            return null;
        }

        //3.真的不存在，查询db
        R r=dbFallBack.apply(id);
        //3.1 db不存在
        if (r == null) {
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        //3.2 db存在
        //3.3 存入redis
        long randomTTL = RandomUtil.randomLong(0L, 2L);
        this.set(key,r,time+randomTTL,unit);
        //4.返回shop
        return r;
        //end
    }



    //查询数据-with解决击穿(逻辑过期)
    public <R,ID> R queryWithLogicalExpire(String keyPrefix, ID id,Class<R> type,Function<ID,R> dbFallBack,Long time,TimeUnit unit) {
        //1.查redis
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.redis查询失败-不是热点数据-返回null
        if (StrUtil.isBlank(json)) {
            return null;
        }
        //3.redis查询成功，取出redisData
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        //3.1 检查逻辑过期字段,转换Json
        LocalDateTime expireTime = redisData.getExpireTime();
        JSONObject Data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(Data, type);
        if (expireTime.isAfter(LocalDateTime.now())) {
            //3.1.1未过期返回shop数据
            return r;
        }
        //3.1.2 已过期-重建缓存
        //3.2 获取该数据的锁
        String lockOfKey = RedisConstants.CACHE_LOCK_KEY + id;
        String isLock = tryLock(lockOfKey);
        //3.2.1 获取成功-获取一个线程并提交保存数据任务
        if (isLock != null) {
            //不需要double check，因为本来就允许返回旧数据
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //查询数据
                    R r1 = dbFallBack.apply(id);
                    //保存逻辑过期数据
                    this.setWithLogicalExpire(key,r1,time,unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unLock(lockOfKey, isLock);
                }
            });
        }

        //3.2.2 获取成功/失败-返回旧数据
        return r;
    }

    //尝试获取锁
    private String tryLock(String LockKey) {
        String uuid = UUID.randomUUID().toString();
        Boolean setnx = stringRedisTemplate.opsForValue().setIfAbsent(LockKey, uuid, 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(setnx) ? uuid : null;
    }
    // 释放锁：Lua 脚本保证「是自己的锁才删」，避免删掉别人的锁
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then " + "  return redis.call('del', KEYS[1]) " + "else " + "  return 0 " + "end", Long.class);
    //解锁
    private void unLock(String LockKey, String isLock) {
        stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(LockKey), isLock);
    }

}
