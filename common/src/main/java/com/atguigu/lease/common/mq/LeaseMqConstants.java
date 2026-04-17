package com.atguigu.lease.common.mq;

/**
 * 租房业务相关 MQ 常量。
 */
public final class LeaseMqConstants {

    private LeaseMqConstants() {
    }

    public static final String LEASE_EXCHANGE = "lease.topic";

    public static final String LEASE_AGREEMENT_EVENT_ROUTING_KEY = "lease.agreement.event";
    public static final String LEASE_AGREEMENT_TIMEOUT_DELAY_ROUTING_KEY = "lease.agreement.timeout.delay";
    public static final String LEASE_AGREEMENT_TIMEOUT_ROUTING_KEY = "lease.agreement.timeout";
    public static final String LEASE_AGREEMENT_AUDIT_QUEUE = "lease.agreement.audit.queue";
    public static final String LEASE_AGREEMENT_TIMEOUT_DELAY_QUEUE = "lease.agreement.timeout.delay.queue";
    public static final String LEASE_AGREEMENT_TIMEOUT_QUEUE = "lease.agreement.timeout.queue";

    public static final String LEASE_ORDER_EVENT_ROUTING_KEY = "lease.order.event";
    public static final String LEASE_ORDER_TIMEOUT_DELAY_ROUTING_KEY = "lease.order.timeout.delay";
    public static final String LEASE_ORDER_TIMEOUT_ROUTING_KEY = "lease.order.timeout";
    public static final String LEASE_ORDER_AUDIT_QUEUE = "lease.order.audit.queue";
    public static final String LEASE_ORDER_TIMEOUT_DELAY_QUEUE = "lease.order.timeout.delay.queue";
    public static final String LEASE_ORDER_TIMEOUT_QUEUE = "lease.order.timeout.queue";
}
