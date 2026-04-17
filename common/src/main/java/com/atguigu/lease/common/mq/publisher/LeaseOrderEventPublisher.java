package com.atguigu.lease.common.mq.publisher;

import com.atguigu.lease.common.mq.LeaseMqConstants;
import com.atguigu.lease.common.mq.event.LeaseOrderEvent;
import com.atguigu.lease.common.mq.outbox.entity.OutboxMessage;
import com.atguigu.lease.common.mq.outbox.service.OutboxService;
import com.atguigu.lease.common.utils.TransactionUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Publishes lease-order domain events through outbox first, then sends after commit.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "mq.enabled", havingValue = "true", matchIfMissing = true)
public class LeaseOrderEventPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final OutboxService outboxService;

    public LeaseOrderEventPublisher(RabbitTemplate rabbitTemplate,
                                    ObjectMapper objectMapper,
                                    OutboxService outboxService) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.outboxService = outboxService;
    }

    public void publish(LeaseOrderEvent event) {
        publish(LeaseMqConstants.LEASE_ORDER_EVENT_ROUTING_KEY, event);
    }

    public void publish(String routingKey, LeaseOrderEvent event) {
        if (event == null) {
            return;
        }

        try {
            OutboxMessage outbox = buildOutbox(routingKey, event);
            OutboxMessage saved = outboxService.saveNew(outbox);
            TransactionUtils.runAfterCommit(() -> outboxService.sendOne(saved.getId()));
        } catch (Exception e) {
            log.error("MQ outbox enqueue failed, routingKey={}, event={}", routingKey, event, e);
            TransactionUtils.runAfterCommit(() -> {
                try {
                    rabbitTemplate.convertAndSend(LeaseMqConstants.LEASE_EXCHANGE, routingKey, event);
                    log.info("MQ fallback publish routingKey={} type={} orderId={}",
                            routingKey, event.getType(), event.getOrderId());
                } catch (Exception ex) {
                    log.error("MQ fallback publish failed, routingKey={}, event={}", routingKey, event, ex);
                }
            });
        }
    }

    public void publishCreated(Long orderId, Long userId, Long roomId, String phone, String afterStatus) {
        LeaseOrderEvent event = new LeaseOrderEvent();
        event.setType(LeaseOrderEvent.Type.CREATED);
        event.setOrderId(orderId);
        event.setUserId(userId);
        event.setRoomId(roomId);
        event.setPhone(phone);
        event.setAfterStatus(afterStatus);
        event.setTraceId(MDC.get("traceId"));
        event.setOccurredAt(Instant.now());
        publish(event);
    }

    public void publishStatusChanged(Long orderId,
                                     Long agreementId,
                                     Long userId,
                                     Long roomId,
                                     String phone,
                                     String before,
                                     String after) {
        LeaseOrderEvent event = new LeaseOrderEvent();
        event.setType(LeaseOrderEvent.Type.STATUS_CHANGED);
        event.setOrderId(orderId);
        event.setAgreementId(agreementId);
        event.setUserId(userId);
        event.setRoomId(roomId);
        event.setPhone(phone);
        event.setBeforeStatus(before);
        event.setAfterStatus(after);
        event.setTraceId(MDC.get("traceId"));
        event.setOccurredAt(Instant.now());
        publish(event);
    }

    public void publishTimeoutCheck(Long orderId,
                                    Long userId,
                                    Long roomId,
                                    String phone,
                                    String expectedStatus,
                                    String timeoutStatus) {
        LeaseOrderEvent event = new LeaseOrderEvent();
        event.setType(LeaseOrderEvent.Type.TIMEOUT_CHECK);
        event.setOrderId(orderId);
        event.setUserId(userId);
        event.setRoomId(roomId);
        event.setPhone(phone);
        event.setBeforeStatus(expectedStatus);
        event.setAfterStatus(timeoutStatus);
        event.setTraceId(MDC.get("traceId"));
        event.setOccurredAt(Instant.now());
        publish(LeaseMqConstants.LEASE_ORDER_TIMEOUT_DELAY_ROUTING_KEY, event);
    }

    private OutboxMessage buildOutbox(String routingKey, LeaseOrderEvent event) throws Exception {
        OutboxMessage outbox = new OutboxMessage();
        outbox.setBiz("leaseOrder");
        outbox.setBizKey(event.getOrderId() == null ? null : String.valueOf(event.getOrderId()));
        outbox.setExchange(LeaseMqConstants.LEASE_EXCHANGE);
        outbox.setRoutingKey(routingKey);
        outbox.setPayloadType(LeaseOrderEvent.class.getName());
        outbox.setPayload(objectMapper.writeValueAsString(event));
        return outbox;
    }
}
