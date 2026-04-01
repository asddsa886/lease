package com.atguigu.lease.common.mq;

/**
 * 租约相关 MQ 常量。
 * <p>
 * 说明：
 * - exchange/queue/routingKey 统一管理，避免散落字符串
 * - 目前用于 LeaseAgreement（可视作“订单”）状态变更等事件的异步解耦
 */
public final class LeaseMqConstants {

    private LeaseMqConstants() {
    }

    public static final String LEASE_EXCHANGE = "lease.topic";
    public static final String LEASE_AGREEMENT_EVENT_ROUTING_KEY = "lease.agreement.event";

    /** 延迟队列入口：租约超时检查（进入 delay queue，TTL 到期后死信到 timeout queue） */
    public static final String LEASE_AGREEMENT_TIMEOUT_DELAY_ROUTING_KEY = "lease.agreement.timeout.delay";

    /** 超时检查真正消费的 routingKey（由死信投递） */
    public static final String LEASE_AGREEMENT_TIMEOUT_ROUTING_KEY = "lease.agreement.timeout";

    /**
     * 事件队列：这里用一个“审计/通知”队列做演示。
     * 若未来拆微服务，可按下游服务拆分多个 queue（同一个 exchange，不同 binding）。
     */
    public static final String LEASE_AGREEMENT_AUDIT_QUEUE = "lease.agreement.audit.queue";

    /** 延迟队列（带 TTL + DLX） */
    public static final String LEASE_AGREEMENT_TIMEOUT_DELAY_QUEUE = "lease.agreement.timeout.delay.queue";

    /** 超时检查消费队列 */
    public static final String LEASE_AGREEMENT_TIMEOUT_QUEUE = "lease.agreement.timeout.queue";
}
