package com.hmdp;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@EnableAspectJAutoProxy(exposeProxy = true)
// 使用@MapperScan注解扫描指定包下的MyBatis Mapper接口
@MapperScan("com.hmdp.mapper")
// 使用@SpringBootApplication注解启用Spring Boot自动配置
@SpringBootApplication
public class HmDianPingApplication {
    public static void main(String[] args) {
        SpringApplication.run(HmDianPingApplication.class, args);
    }
}