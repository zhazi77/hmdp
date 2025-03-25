package com.hmdp.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

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

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1. 校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2. 如果不符合，返回错误
            return Result.fail("手机号格式错误!");
        }
        // 3. 如果符合，生成验证码
        String code = RandomUtil.randomNumbers(6);

        // 4. 保存验证码到 Session
        session.setAttribute("code", code);

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
        // 2. 校验验证码
        Object cachedCode = session.getAttribute("code");
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

        // 7. 如果存在，保存用户信息到 Session 中，并返回ok
        session.setAttribute("user", user);

        return Result.ok();
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
