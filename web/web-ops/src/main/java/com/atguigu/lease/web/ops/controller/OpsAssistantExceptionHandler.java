package com.atguigu.lease.web.ops.controller;

import com.atguigu.lease.common.result.Result;
import com.atguigu.lease.common.result.ResultCodeEnum;
import com.atguigu.lease.web.ops.exception.OpsAssistantUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = OpsAssistantController.class)
public class OpsAssistantExceptionHandler {

    @ExceptionHandler(OpsAssistantUnavailableException.class)
    public ResponseEntity<Result<Void>> handleOpsAssistantUnavailable(OpsAssistantUnavailableException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Result.fail(ResultCodeEnum.SERVICE_ERROR.getCode(), exception.getMessage()));
    }
}
