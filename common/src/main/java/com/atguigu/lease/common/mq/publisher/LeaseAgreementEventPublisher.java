package com.atguigu.lease.common.mq.publisher;

import com.atguigu.lease.common.mq.LeaseMqConstants;
import com.atguigu.lease.common.mq.event.LeaseAgreementEvent;
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
 * Publishes lease-agreement domain events through outbox first, then sends after commit.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "mq.enabled", havingValue = "true", matchIfMissing = true)
public class LeaseAgreementEventPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final OutboxService outboxService;

    public LeaseAgreementEventPublisher(RabbitTemplate rabbitTemplate,
                                        ObjectMapper objectMapper,
                                        OutboxService outboxService) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.outboxService = outboxService;
    }

    public void publish(LeaseAgreementEvent event) {
        publish(LeaseMqConstants.LEASE_AGREEMENT_EVENT_ROUTING_KEY, event);
    }

    public void publish(String routingKey, LeaseAgreementEvent event) {
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
                    log.info("MQ fallback publish routingKey={} type={} agreementId={}",
                            routingKey, event.getType(), event.getAgreementId());
                } catch (Exception ex) {
                    log.error("MQ fallback publish failed, routingKey={}, event={}", routingKey, event, ex);
                }
            });
        }
    }

    public void publishStatusChanged(Long agreementId, String phone, String before, String after) {
        LeaseAgreementEvent event = new LeaseAgreementEvent();
        event.setType(LeaseAgreementEvent.Type.STATUS_CHANGED);
        event.setAgreementId(agreementId);
        event.setPhone(phone);
        event.setBeforeStatus(before);
        event.setAfterStatus(after);
        event.setTraceId(MDC.get("traceId"));
        event.setOccurredAt(Instant.now());
        publish(event);
    }

    public void publishRenewRequested(Long agreementId, String phone, String before, String after) {
        LeaseAgreementEvent event = new LeaseAgreementEvent();
        event.setType(LeaseAgreementEvent.Type.RENEW_REQUESTED);
        event.setAgreementId(agreementId);
        event.setPhone(phone);
        event.setBeforeStatus(before);
        event.setAfterStatus(after);
        event.setTraceId(MDC.get("traceId"));
        event.setOccurredAt(Instant.now());
        publish(event);
    }

    public void publishUpsert(Long agreementId, String phone, String after, boolean created) {
        LeaseAgreementEvent event = new LeaseAgreementEvent();
        event.setType(created ? LeaseAgreementEvent.Type.CREATED : LeaseAgreementEvent.Type.UPDATED);
        event.setAgreementId(agreementId);
        event.setPhone(phone);
        event.setAfterStatus(after);
        event.setTraceId(MDC.get("traceId"));
        event.setOccurredAt(Instant.now());
        publish(event);
    }

    public void publishTimeoutCheck(Long agreementId, String phone, String expectedStatus, String timeoutStatus) {
        LeaseAgreementEvent event = new LeaseAgreementEvent();
        event.setType(LeaseAgreementEvent.Type.TIMEOUT_CHECK);
        event.setAgreementId(agreementId);
        event.setPhone(phone);
        event.setBeforeStatus(expectedStatus);
        event.setAfterStatus(timeoutStatus);
        event.setTraceId(MDC.get("traceId"));
        event.setOccurredAt(Instant.now());
        publish(LeaseMqConstants.LEASE_AGREEMENT_TIMEOUT_DELAY_ROUTING_KEY, event);
    }

    private OutboxMessage buildOutbox(String routingKey, LeaseAgreementEvent event) throws Exception {
        OutboxMessage outbox = new OutboxMessage();
        outbox.setBiz("leaseAgreement");
        outbox.setBizKey(event.getAgreementId() == null ? null : String.valueOf(event.getAgreementId()));
        outbox.setExchange(LeaseMqConstants.LEASE_EXCHANGE);
        outbox.setRoutingKey(routingKey);
        outbox.setPayloadType(LeaseAgreementEvent.class.getName());
        outbox.setPayload(objectMapper.writeValueAsString(event));
        return outbox;
    }
}
