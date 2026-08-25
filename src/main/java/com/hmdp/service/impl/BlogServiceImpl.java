package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private IFollowService followService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    //查询热度高的blog
    @Override
    public Result queryHotBlog(Integer current) {
        //分页查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        //解析内容
        List<Blog> records = page.getRecords();
        records.forEach(blog -> {
            //分别设置blog作者用户昵称和头像
            //records.forEach(this::queryBlogUser(blog));
            queryBlogUser(blog);
            //分别设置当前用户是否点赞
            isBlogLiked(blog);
        });//每一个都执行一遍更新方法

        //返回bloglist
        return Result.ok(records);
    }

    //判断当前登录用户是否点赞blog
    private void isBlogLiked(Blog blog) {
        //1.获取当前登录用户
        UserDTO userDTO = UserHolder.getUser();
        //1.1如果获取不到则说明用户尚未登陆
        if (userDTO == null) {
            return;
        }
        //1.2获取到则获取用户id
        Long userId = userDTO.getId();
        //2.根据userid判断是否点赞
        //2.1获取该blog在redis中的点赞集合key
        String key = RedisConstants.BLOG_LIKED_KEY + blog.getId();
        //2.2查询userId是否在key对应集合中
        Double isMember = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        //3.如果存在则设置blog的点赞属性未true
        if (isMember != null) {
            blog.setIsLike(true);
        } else {
            //4.不存在默认false
            blog.setIsLike(false);
        }
    }

    //查询blog
    @Override
    public Result queryBlogById(Long id) {
        //根据id查询blog
        Blog blog = getById(id);

        //如果查询不到，返回错误信息
        if (blog == null) {
            return Result.fail("内容不存在");
        }
        //将blog作者昵称和头像注入blog
        queryBlogUser(blog);
        //将当前用户是否点赞注入blog
        isBlogLiked(blog);

        //返回blog
        return Result.ok(blog);
    }

    //用户点击点赞按钮
    @Override
    public Result likeBlog(Long id) {
        //1.获取当前登录用户
        UserDTO userDTO = UserHolder.getUser();
        //1.1如果获取不到则说明用户尚未登陆
        if (userDTO == null) {
            return Result.fail("请先登录");
        }
        //1.2获取到user则拿出userId
        Long userId = userDTO.getId();
        //2.查询这个user是否给这个blog点过赞，redis查询点赞集合中是否有这个userId
        //2.1获取该blog在redis中的点赞集合key
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        //2.2查询userId是否在key对应集合中
        Double isMember = stringRedisTemplate.opsForZSet().score(key, userId.toString());

        if (isMember != null) {
            //3.如果有，则说明点过赞,点击则标识取消点赞
            //3.1更新数据库点赞数量
            boolean cancelLike = update().setSql("liked = liked - 1").eq("id", id).update();
            //3.2更新成功则更新redis点赞集合,将userId移除
            if (cancelLike) {
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        } else {
            //4.如果没有，则说明为未点赞,点击则说明进行点赞
            //4.1更新数据库点赞数量
            boolean Like = update().setSql("liked = liked + 1").eq("id", id).update();
            //4.2更新成功则更新redis点赞集合,将userId加入
            if (Like) {
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        }

        return Result.ok();
    }

    //查询点赞列表topN
    @Override
    public Result queryBlogLikeList(Long id) {
        //1.根据点赞集合key查询top5
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        Set<String> likeListTop5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        //1.1判断空集合,空集合直接返回
        if (likeListTop5 == null || likeListTop5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }

        //2.解析集合数据
        List<Long> userIdList = likeListTop5.stream()
                .map(s -> Long.parseLong(s))
                .collect(Collectors.toList());

        //3.1id拼接成字符串，指定查询后的列表顺序
        String idsStr = StrUtil.join(",", userIdList);

        //3.2根据用户id查询对应用户数据
        List<User> users = userService.query()
                .in("id", userIdList)
                .last("ORDER BY FIELD(id," + idsStr + ")")
                .list();


        //4.去除敏感数据，转为userDto
        List<UserDTO> userDTOS = users.stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        //5.返回点赞用户列表
        return Result.ok(userDTOS);
    }

    //保存用户发布内容并推送至粉丝信箱（zset）
    @Override
    public Result saveBlog(Blog blog) {
        //1.获取登录用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        blog.setUserId(user.getId());
        //2.保存探店博文
        boolean saveSuccess = save(blog);
        //保存失败则返回
        if (!saveSuccess) {
            return Result.fail("保存失败，请重试");
        }
        //3.保存成功则推送至粉丝信箱

        //3.1获取粉丝数据
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        //3.2解析粉丝id并保存到对方的信箱
        for (Follow follow : follows) {
            //获取每条数据的userid（fansId）
            Long fansId = follow.getUserId();
            //保存到对方的信箱
            String key = RedisConstants.FEED_KEY + fansId;
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }


        //4.返回id
        return Result.ok(blog.getId());
    }

    //滚动分页查询当前登录用户的关注用户的新博客（从用户信箱拉取）
    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //1.从redis获取信箱内容
        //1.1 获得当前登录用户
        UserDTO userDTO = UserHolder.getUser();
        if(userDTO==null){
            //非空判断
            return Result.fail("请先登录");
        }
        Long userId = userDTO.getId();
        //1.2 拼接信箱key
        String key=RedisConstants.FEED_KEY+userId;
        //1.3 获取key-value元组集合
        Set<ZSetOperations.TypedTuple<String>> blogTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);


        //非空判断
        if(blogTuples==null||blogTuples.isEmpty()){
            return Result.ok();
        }

        //2.解析元组内容：blogIds、当前查询minTime/lastId（下一次查询max）、下一次查询offset
        ArrayList<Long> blogIds = new ArrayList<>(blogTuples.size());
        long minTime=max;
        int os=1;
        for (ZSetOperations.TypedTuple<String> tuple : blogTuples) {
            //2.1 获得blogId（使用list接收）value即是blogid
            Long blogId = Long.valueOf(tuple.getValue());
            blogIds.add(blogId);

            //2.2 minTime
            //2.3 下一次查询offset(实际就是本次查询中，与最小时间相同时间的blogid数量)
            long currentTime = tuple.getScore().longValue();
            if(currentTime==minTime){
                //假设当前是最小值，数量+1
                os++;
            }else if(currentTime<minTime){
                //最小值更新，os重新计数
                os=1;
                minTime=currentTime;
            }
        }
        //3.根据ids查询blog内容
        //为了保证返回顺序与ids列表顺序一致，使用order by field限制
        String idsStr = StrUtil.join(",", blogIds);
        List<Blog> blogs = query().in("id", blogIds).last("ORDER BY FIELD(id," + idsStr + ")").list();

        //4.为每个blog设置作者头像、昵称，设置点赞状态
        for (Blog blog : blogs) {
            queryBlogUser(blog);
            isBlogLiked(blog);
        }

        //5.封装并返回
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setMinTime(minTime);
        scrollResult.setOffset(os);

        return Result.ok(scrollResult);
    }

    //设置blog对应用户的昵称和头像
    private void queryBlogUser(Blog blog) {
        //根据blog查询对应用户id
        Long userId = blog.getUserId();
        //根据userId查询用户
        User user = userService.getById(userId);
        //判断用户是否存在（可能注销）
        if(user==null){
            return;
        }
        //设置blog对应用户的昵称和头像
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

}
