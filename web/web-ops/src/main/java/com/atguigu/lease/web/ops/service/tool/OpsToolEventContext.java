package com.atguigu.lease.web.ops.service.tool;

public final class OpsToolEventContext {

    private static final ThreadLocal<OpsToolEventEmitter> EMITTER_HOLDER = new ThreadLocal<>();

    private OpsToolEventContext() {
    }

    public static void bind(OpsToolEventEmitter emitter) {
        EMITTER_HOLDER.set(emitter);
    }

    public static OpsToolEventEmitter currentEmitter() {
        OpsToolEventEmitter emitter = EMITTER_HOLDER.get();
        return emitter == null ? OpsToolEventEmitter.noop() : emitter;
    }

    public static void clear() {
        EMITTER_HOLDER.remove();
    }
}
