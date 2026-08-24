package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.apache.ibatis.annotations.Delete;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    //关注/取关请求
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        //0.获取当前用户id
        UserDTO userDTO = UserHolder.getUser();
        if(userDTO==null){
            return Result.fail("请先登录");
        }
        Long userId = userDTO.getId();
        //redis中关注列表key
        String key="follows:"+userId;
        //1.判断时关注请求还是取关请求
        if(isFollow){
            //2.关注
            //2.1封装follow关系数据
            Follow follow = new Follow();
            follow.setFollowUserId(followUserId);
            follow.setUserId(userId);
            //2.2写入数据库
            boolean isSuccess = save(follow);
            if(isSuccess){
                //2.3将用户关注写入redis中的关注列表
                stringRedisTemplate.opsForSet().add(key,followUserId.toString());
            }

        }else{
            //3.取关
            //根据userId和followId删除对应数据
            boolean isSuccess = remove(new QueryWrapper<Follow>().eq("user_id", userId)
                    .eq("follow_user_id", followUserId));
            if(isSuccess){
                //2.3从redis的关注列表中移除
                stringRedisTemplate.opsForSet().remove(key,followUserId.toString());
            }
        }
        return Result.ok();
    }

    //判断是否关注
    @Override
    public Result isFollow(Long followUserId) {
        //1.获取用户id
        UserDTO userDTO = UserHolder.getUser();
        if(userDTO==null){
            return Result.ok(false);
        }
        Long userId = userDTO.getId();

        //2.查询是否有关注follow关系数据
        Integer count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
        //3.有则返回true，否则false
        return Result.ok(count>0);
    }

    //共同关注查询
    @Override
    public Result followCommons(Long id) {
        //1.取得当前登录用户的关注列表
        //1.1获取当前用户id
        UserDTO userDTO = UserHolder.getUser();
        if(userDTO==null){
            return Result.fail("请先登录");
        }
        Long userId = userDTO.getId();
        //1.2获取登录用户关注列表的key
        String userKey = "follows:" + userId;

        //2.取得当前查询用户的关注列表
        //2.1获取目标用户关注列表的key
        String curKey = "follows:" + id;

        //3.查询两个key对应的set交集
        Set<String> followCommonsSet = stringRedisTemplate.opsForSet().intersect(userKey, curKey);

        //3.1判断是否为空，为空直接返回空集合
        if(followCommonsSet==null||followCommonsSet.isEmpty()){
            return Result.ok(Collections.emptyList());
        }

        //4.解析set内容(string->long)
        List<Long>  sameIds= followCommonsSet.stream()
                .map(s -> Long.valueOf(s)).collect(Collectors.toList());
        //5.获取对应用户信息
        List<User> sameUsers = userService.listByIds(sameIds);
        List<UserDTO> sameUserDTOS = sameUsers.stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        return Result.ok(sameUserDTOS);
    }
}
