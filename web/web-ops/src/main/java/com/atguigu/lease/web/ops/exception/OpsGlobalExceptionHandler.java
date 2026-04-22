package com.atguigu.lease.web.ops.exception;

import com.atguigu.lease.common.exception.LeaseException;
import com.atguigu.lease.common.result.Result;
import com.atguigu.lease.common.result.ResultCodeEnum;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class OpsGlobalExceptionHandler {

    @ExceptionHandler(LeaseException.class)
    public Result<Object> handleLeaseException(LeaseException e) {
        log.warn("Ops business exception: code={}, message={}", e.getCode(), e.getMessage());
        return Result.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            BindException.class,
            ConstraintViolationException.class,
            HttpMessageNotReadableException.class
    })
    public Result<Object> handleBadRequest(Exception e) {
        log.warn("Ops bad request: {}", e.getMessage());
        return Result.fail(ResultCodeEnum.PARAM_ERROR.getCode(), "请求参数不正确");
    }

    @ExceptionHandler(Exception.class)
    public Result<Object> handleException(Exception e) {
        log.error("Ops unhandled exception", e);
        return Result.fail(ResultCodeEnum.SERVICE_ERROR.getCode(), "服务异常");
    }
}
