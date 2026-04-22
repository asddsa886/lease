package com.atguigu.lease.web.ops.exception;

public class OpsAssistantUnavailableException extends RuntimeException {

    public OpsAssistantUnavailableException(String message) {
        super(message);
    }

    public OpsAssistantUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
