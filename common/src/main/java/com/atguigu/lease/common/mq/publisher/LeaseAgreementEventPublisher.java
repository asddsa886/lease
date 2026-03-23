package com.atguigu.lease.common.mq.publisher;

import com.atguigu.lease.common.mq.LeaseMqConstants;
import com.atguigu.lease.common.mq.event.LeaseAgreementEvent;
import com.atguigu.lease.common.utils.TransactionUtils;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 租约事件发布器：统一封装 routingKey、traceId 注入，并在事务提交后再发送，保障一致性。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "mq.enabled", havingValue = "true", matchIfMissing = true)
public class LeaseAgreementEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    public LeaseAgreementEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publish(LeaseAgreementEvent event) {
        if (event == null) {
            return;
        }
        // 事务提交后发送：避免 DB 回滚但消息已发导致下游脏读
        TransactionUtils.runAfterCommit(() -> {
            try {
                rabbitTemplate.convertAndSend(
                        LeaseMqConstants.LEASE_EXCHANGE,
                        LeaseMqConstants.LEASE_AGREEMENT_EVENT_ROUTING_KEY,
                        event
                );
                log.info("MQ published leaseAgreementEvent type={} agreementId={} traceId={}",
                        event.getType(), event.getAgreementId(), event.getTraceId());
            } catch (Exception e) {
                // 生产级可接入重试/Outbox；这里先记录日志，避免影响主链路
                log.error("MQ publish failed, event={}", event, e);
            }
        });
    }

    // 状态变更事件：从租约服务发出，供其他服务监听后续处理，如通知房东/用户
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

    // 续约请求事件：从租约服务发出，供其他服务监听后续处理，如通知房东/用户
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

    // 创建/更新事件（如续约）共用一个方法，区别在于 type 和 beforeStatus 可选
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
}
