package com.atguigu.lease.common.exception;

import com.atguigu.lease.common.result.Result;
import com.atguigu.lease.common.result.ResultCodeEnum;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;

import java.util.Comparator;
import java.util.stream.Collectors;

@Slf4j
@ControllerAdvice
/**
 * 作用：告诉 Spring，“这是一个全局控制器增强类”。
 * 效果：它会自动扫描并应用到所有的 @Controller 或 @RestController 上。
 * 你不需要在每个 Controller 里写 try-catch，这里写的逻辑对所有接口生效。
 */
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    /**
     * 作用：指定这个方法专门处理哪种类型的异常。
     * 机制：当某个 Controller 抛出异常时，Spring 会拿着这个异常去匹配这里的方法。
     * 如果是 MethodArgumentNotValidException，就调用第一个方法。
     * 如果是 LeaseException，就调用第四个方法。
     * 如果都不匹配，最后会落到 Exception.class（兜底）。
     */
    @ResponseBody
    public Result handle(MethodArgumentNotValidException e) {
        // @RequestBody + @Valid 校验失败
        String msg = e.getBindingResult().getFieldErrors().stream()
                .sorted(Comparator.comparing(FieldError::getField))
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.warn("Validation failed: {}", msg);
        return Result.fail(ResultCodeEnum.PARAM_ERROR.getCode(), msg);
    }

    @ExceptionHandler(BindException.class)
    @ResponseBody
    public Result handle(BindException e) {
        // 表单/QueryString 绑定失败（也可由校验触发）
        String msg = e.getBindingResult().getFieldErrors().stream()
                .sorted(Comparator.comparing(FieldError::getField))
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.warn("Bind failed: {}", msg);
        return Result.fail(ResultCodeEnum.PARAM_ERROR.getCode(), msg);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseBody
    public Result handle(ConstraintViolationException e) {
        // @RequestParam/@PathVariable + @Validated 校验失败
        String msg = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining("; "));
        log.warn("Constraint violation: {}", msg);
        return Result.fail(ResultCodeEnum.PARAM_ERROR.getCode(), msg);
    }

    @ExceptionHandler(LeaseException.class)
    @ResponseBody
    public Result handle(LeaseException e) {
        // 业务异常：通常可控，打印 warn 级别，保留堆栈用于排查（如需可降级为 debug）
        log.warn("Business exception: code={}, message={}", e.getCode(), e.getMessage(), e);
        return Result.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public void handle(AsyncRequestTimeoutException e) {
        log.warn("Async request timeout", e);
    }

    @ExceptionHandler(Exception.class)
    @ResponseBody
    public Result handle(Exception e) {
        // 系统异常：不可控，必须 error + 堆栈
        log.error("Unhandled exception", e);
        return Result.fail();
    }
}
