package com.hmdp.utils;

import com.hmdp.dto.UserDTO;

public class UserHolder {
    private static final ThreadLocal<UserDTO> tl = new ThreadLocal<>();

    // 保存用户信息到ThreadLocal
    public static void saveUser(UserDTO user){
        tl.set(user);
    }

    // 从ThreadLocal获取用户信息
    public static UserDTO getUser(){
        return tl.get();
    }

    // 从ThreadLocal移除用户信息
    public static void removeUser(){
        tl.remove();
    }
}
