package com.atguigu.lease.common.mq.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

/**
 * 租约事件（可视作“订单事件”）：用于把核心写操作与下游动作（通知/审计/异步处理）解耦。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LeaseAgreementEvent implements Serializable {

    public enum Type {
        CREATED,
        UPDATED,
        STATUS_CHANGED,
        RENEW_REQUESTED
    }

    private Type type;

    private Long agreementId;

    /** 业务主维度：手机号（本项目里租约 phone 与用户绑定） */
    private String phone;

    /** 变更前状态（可空） */
    private String beforeStatus;

    /** 变更后状态（可空） */
    private String afterStatus;

    /** traceId：便于按链路排查（来自 MDC） */
    private String traceId;

    /** 事件发生时间 */
    private Instant occurredAt;
}
