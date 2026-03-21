package com.atguigu.lease.web.admin.mq;

import com.atguigu.lease.common.mq.LeaseMqConstants;
import com.atguigu.lease.common.mq.event.LeaseAgreementEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 租约事件消费者：用于演示“下单/租约写入 -> MQ -> 下游异步处理”。
 * <p>
 * 目前处理逻辑先以审计日志为主；后续可扩展为：短信通知、站内信、风控、数据同步等。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "mq.enabled", havingValue = "true", matchIfMissing = true)
public class LeaseAgreementEventListener {

    @RabbitListener(queues = LeaseMqConstants.LEASE_AGREEMENT_AUDIT_QUEUE)
    public void onMessage(LeaseAgreementEvent event) {
        log.info("[MQ] leaseAgreementEvent received: type={} agreementId={} phone={} before={} after={} traceId={} occurredAt={}",
                event == null ? null : event.getType(),
                event == null ? null : event.getAgreementId(),
                event == null ? null : event.getPhone(),
                event == null ? null : event.getBeforeStatus(),
                event == null ? null : event.getAfterStatus(),
                event == null ? null : event.getTraceId(),
                event == null ? null : event.getOccurredAt());
    }
}
