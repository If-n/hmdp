package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.security.Key;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    private static final String KEY = "cache:shopType";

    @Override
    public Result queryBySort() {
        //1.redis查询
        String shopTypeJson = stringRedisTemplate.opsForValue().get(KEY);
        //2.查到直接返回
        if(StrUtil.isNotBlank(shopTypeJson)){
            return Result.ok(JSONUtil.toList(shopTypeJson,ShopType.class));
        }
        //3.查不到查db
        List<ShopType> shopTypes = query().orderByAsc("sort").list();

        //3.1 db查不到，返回错误信息
        if(shopTypes.isEmpty()){
            return Result.fail("没有相关数据");
        }
        //3.2 db查到，写到redis
        stringRedisTemplate.opsForValue().set(KEY,JSONUtil.toJsonStr(shopTypes));

        //返回
        return Result.ok(shopTypes);
    }
}
