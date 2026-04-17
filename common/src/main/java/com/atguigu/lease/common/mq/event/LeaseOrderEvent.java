package com.atguigu.lease.common.mq.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

/**
 * Lease order domain event used for async fan-out and timeout checks.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LeaseOrderEvent implements Serializable {

    public enum Type {
        CREATED,
        STATUS_CHANGED,
        TIMEOUT_CHECK
    }

    private Type type;

    private Long orderId;

    private Long agreementId;

    private Long userId;

    private Long roomId;

    private String phone;

    private String beforeStatus;

    private String afterStatus;

    private String traceId;

    private Instant occurredAt;
}
