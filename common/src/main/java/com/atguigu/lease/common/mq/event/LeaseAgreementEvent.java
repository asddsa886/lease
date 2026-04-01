package com.atguigu.lease.common.mq.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

/**
 * Lease agreement domain event used for async fan-out and timeout checks.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LeaseAgreementEvent implements Serializable {

    public enum Type {
        CREATED,
        UPDATED,
        STATUS_CHANGED,
        RENEW_REQUESTED,
        TIMEOUT_CHECK
    }

    private Type type;

    private Long agreementId;

    private String phone;

    private String beforeStatus;

    private String afterStatus;

    private String traceId;

    private Instant occurredAt;
}
