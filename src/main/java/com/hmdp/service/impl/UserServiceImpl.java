package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            //不符合返回错误信息
            return Result.fail("手机号格式错误，请重新输入");
        }
        //2.生成验证码
        String code = RandomUtil.randomNumbers(6);
        //3.保存到redis

        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY + phone, code, RedisConstants.LOGIN_CODE_TTL, TimeUnit.SECONDS);

        //4.发送验证码
        log.debug("验证码发送成功: {}", code);
        //返回OK
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1.检验手机号和验证码
        String code = loginForm.getCode();
        String phone = loginForm.getPhone();

        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号错误");
        }

        String redisCode = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone).toString();

        System.out.println("redisCode:" + redisCode + "  =?" + redisCode.equals(code));

        if (redisCode == null || !redisCode.equals(code)) {
            return Result.fail("验证码错误");
        }

        //2.查询用户信息
        User user = query().eq("phone", phone).one();
        //3.用户是否存在
        //不存在则注册
        if (user == null) {
            user = createUserWithPhone(phone);
        }

        //4存在则保存到redis
        //4.1生成token作为key-hutool-uuid
        String token = UUID.randomUUID().toString(true);
        //4.2把user信息转为map作为hashValue
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create().setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()
                        )
        );
        //4.3将key+value存入redis
        String tokenKey = RedisConstants.LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        //设置过期时间
        //stringRedisTemplate.expire(tokenKey,RedisConstants.LOGIN_USER_TTL,TimeUnit.SECONDS);
        //5.返回token给用户保存在前端(前端自动保存，并在请求头中加入字段：authorization表示携带的token)
        return Result.ok(token);

    }

    //用户签到功能，实现当前用户当天签到记录
    @Override
    public Result sign() {
        //1.获取当前登录用户
        UserDTO userDTO = UserHolder.getUser();
        if (userDTO == null) {
            //空参判断
            return Result.fail("请先登录");
        }
        //非空则获取userId
        Long userId = userDTO.getId();
        //2.获取当天日期
        LocalDateTime now = LocalDateTime.now();
        //3.存储至redis（以年月为key，以具体日期的bitmap为value）
        String yearAndMonth = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        int day = now.getDayOfMonth();

        String key = RedisConstants.USER_SIGN_KEY + userId + yearAndMonth;
        stringRedisTemplate.opsForValue().setBit(key, day - 1, true);
        //4.返回ok
        return Result.ok();
    }

    //用户连续签到统计
    @Override
    public Result signCount() {
        //1.获取用户签到统计表
        //1.1获取当前登录用户
        UserDTO userDTO = UserHolder.getUser();
        if (userDTO == null) {
            //空参判断
            return Result.fail("请先登录");
        }
        //1.2非空则获取userId
        Long userId = userDTO.getId();
        //1.3获取当天日期
        LocalDateTime now = LocalDateTime.now();
        //1.4获取年月、日
        String yearAndMonth = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        int day = now.getDayOfMonth();
        //1.5获取bitmap
        String key = RedisConstants.USER_SIGN_KEY + userId + yearAndMonth;
        //BITFIELD sign:1011:202608 Get u22 0
        List<Long> results = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(day)).valueAt(0)
        );
        //2.判断结果集是否有结果
        if (results == null || results.isEmpty()) {
            return Result.ok(0);
        }
        //3.有结果，取第一个子命令结果
        Long recordBitmap = results.get(0);
        //非空判断
        if (recordBitmap == null || recordBitmap == 0) {
            return Result.ok(0);
        }
        //4.从尾部遍历记录每一位，记录连续1的数量
        int count = 0;
        //4.1每次取出最后一位(与1做位与&运算)
        while ((recordBitmap & 1) != 0) {
            //是1则计数
            count++;
            //每次右移一位
            recordBitmap>>>=1;
        }
        //返回计数
        return Result.ok(count);
    }

    //创建账户对象并保存到db中
    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
