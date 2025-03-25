package com.hmdp.config;

import com.hmdp.dto.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
// 全局异常处理器，用于捕获并处理控制器抛出的异常
public class WebExceptionAdvice {

    @ExceptionHandler(RuntimeException.class)
    // 捕获 RuntimeException 异常，并返回统一格式的错误信息
    public Result handleRuntimeException(RuntimeException e) {
        log.error(e.toString(), e);
        return Result.fail("服务器异常");
    }
}