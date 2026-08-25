package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.service.IUVService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

@Slf4j
@Service
public class UVServiceImpl implements IUVService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    //统计查询店铺独立总访客访问量
    @Override
    public Result queryUV(Long shopId) {
        //1.获取当前shop的uv记录key
        String key=RedisConstants.UV_SHOP_KEY+shopId;
        //2.查询redis
        Long count = stringRedisTemplate.opsForHyperLogLog().size(key);
        return Result.ok(count);
    }

    //每次访问，店铺独立总访客访问量+1
    @Override
    public void recordUV(Long id) {
        //1.redis中存储key
        String key= RedisConstants.UV_SHOP_KEY+id;
        //2.存储访客访问

        //2.1获取userId
        UserDTO userDTO = UserHolder.getUser();
        if(userDTO==null){
            //用户未登录则不记录访问次数
            return;
        }
        Long userId = userDTO.getId();
        //2.2记录当前登录用户的访问记录
        stringRedisTemplate.opsForHyperLogLog().add(key,userId.toString());
    }
}
