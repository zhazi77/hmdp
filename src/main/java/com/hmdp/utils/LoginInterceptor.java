package com.hmdp.utils;


import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1. 获取 session
        // 2. 获取 session 中的用户
        Object user = request.getSession().getAttribute("user");

        // 3. 判断用户是否存在
        if (user == null) {
            // 3.1 不存在，拦截
            response.setStatus(401);
            return false;
        }

        // 3.2. 如果存在，把用户信息保存到 ThreadLocal，放行
        // NOTE: 需要使用 BeanUtil 将 User 转换为 UserDTO
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        UserHolder.saveUser(userDTO);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        HandlerInterceptor.super.afterCompletion(request, response, handler, ex);
        // 移除用户
        UserHolder.removeUser();
    }
}
