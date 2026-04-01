package com.atguigu.lease.common.mq.outbox;

/** Outbox 消息状态枚举（避免 magic number） */
public final class OutboxMessageStatus {
    private OutboxMessageStatus() {
    }

    public static final int NEW = 0;
    public static final int SENT = 1;
    public static final int ACKED = 2;
    public static final int FAILED = 3;
}
