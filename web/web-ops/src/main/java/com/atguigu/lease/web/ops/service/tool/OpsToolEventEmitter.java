package com.atguigu.lease.web.ops.service.tool;

@FunctionalInterface
public interface OpsToolEventEmitter {

    void emit(String eventName, String toolName, String message);

    static OpsToolEventEmitter noop() {
        return (eventName, toolName, message) -> {
        };
    }
}
