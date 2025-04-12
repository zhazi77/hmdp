package com.hmdp.dto;

import lombok.Data;

// 只在 session 中保存部分信息，这样：1.避免返回用户敏感信息，2.减少 session 所占的内存
@Data
public class UserDTO {
    private Long id;
    private String nickName;
    private String icon;
}
