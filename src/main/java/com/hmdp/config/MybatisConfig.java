package com.hmdp.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
// 配置 MyBatis-Plus 的拦截器
public class MybatisConfig {
    // 定义一个 Bean，返回 MybatisPlusInterceptor 对象
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        // 创建 MybatisPlusInterceptor 实例
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 添加分页插件
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        // 返回配置好的拦截器
        return interceptor;
    }
}