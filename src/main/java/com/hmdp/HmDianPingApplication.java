package com.hmdp;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// 使用@MapperScan注解扫描指定包下的MyBatis Mapper接口
@MapperScan("com.hmdp.mapper")
// 使用@SpringBootApplication注解启用Spring Boot自动配置
@SpringBootApplication
public class HmDianPingApplication {

    // 定义main方法作为程序入口点
    public static void main(String[] args) {
        // 调用SpringApplication.run方法启动Spring Boot应用
        SpringApplication.run(HmDianPingApplication.class, args);
    }
}