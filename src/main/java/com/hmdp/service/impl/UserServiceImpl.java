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
import com.hmdp.utils.JWTUtils;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1. 校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2. 如果不符合，返回错误
            return Result.fail("手机号格式错误!");
        }

        // 3. 如果符合，生成验证码
        String code = RandomUtil.randomNumbers(6);

        // 4. 保存验证码到 Redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);

        // 5. 发送验证码
        log.debug("发送验证码成功，验证码：{}", code);

        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1. 校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误!");
        }
        // 2. 校验验证码(Redis)
        Object cachedCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        if (cachedCode == null || !cachedCode.toString().equals(code)) {
            // 3. 如果不一致，返回错误
            return Result.fail("验证码错误!");
        }

        // 4. 如果一致，根据手机号查询用户
        User user = query().eq("phone", phone).one();

        // 5. 判断用户是否存在
        if (user == null) {
            // 6. 如果不存在，创建新用户并保存
            user = createUserWithPhone(phone);
        }

        // 7. 如果存在，生成 JWT 令牌，返回给用户。
        // 7.1 生成一个 JWT 作为登录令牌
        String token = JWTUtils.generateToken(user.getId());
        // 7.2 将 User 对象转为 Hash 存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        // 7.3 存储到 Redis (使用 userId 做为键）
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + user.getId(), userMap);
        // 7.4 设置有效期
        stringRedisTemplate.expire(LOGIN_CODE_KEY + token, LOGIN_USER_TTL, TimeUnit.SECONDS);

        // 8. 返回 token
        return Result.ok(token);
    }

    @Override
    public Result sign() {
        // 1. 获取当前登录用户
        UserDTO user = UserHolder.getUser();

        // 2. 获取当前日期
        LocalDateTime time = LocalDateTime.now();

        // 3. 拼接 key
        String dateStr = time.format(DateTimeFormatter.ofPattern("yyyy:MM"));
        String key = USER_SIGN_KEY + user.getId() + ":" + dateStr;

        // 4. 签到
        int dayOfMonth = time.getDayOfMonth();
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        // 1. 获取当前登录用户
        UserDTO user = UserHolder.getUser();

        // 2. 获取当前日期
        LocalDateTime time = LocalDateTime.now();

        // 3. 拼接 key
        String dateStr = time.format(DateTimeFormatter.ofPattern("yyyy:MM"));
        String key = USER_SIGN_KEY + user.getId() + ":" + dateStr;

        // 4. 获取当前日期是本月的第几天
        int dayOfMonth = time.getDayOfMonth();

        // 5. 获取当前用户本月的签到情况
        List<Long> signResult = stringRedisTemplate.opsForValue().bitField(key, BitFieldSubCommands.create()
                .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));
        if (signResult == null || signResult.isEmpty()) {
            // 没有找到签到结果
            return Result.ok(0);
        }

        // 6. 统计用户连续签到次数
        int cnt = 0;
        Long num = signResult.get(0);
        while ((num & 1) == 1) {
            cnt++;
            num >>>= 1;
        }
        return Result.ok(cnt);
    }

    private User createUserWithPhone(String phone) {
        // 1. 创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));

        // 2. 保存用户信息到数据库
        save(user);
        return user;
    }
}
