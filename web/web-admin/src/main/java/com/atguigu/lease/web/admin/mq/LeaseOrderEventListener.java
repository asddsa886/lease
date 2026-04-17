package com.atguigu.lease.web.admin.mq;

import com.atguigu.lease.common.mq.LeaseMqConstants;
import com.atguigu.lease.common.mq.event.LeaseOrderEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "mq.enabled", havingValue = "true", matchIfMissing = true)
public class LeaseOrderEventListener {

    @RabbitListener(queues = LeaseMqConstants.LEASE_ORDER_AUDIT_QUEUE)
    public void onMessage(LeaseOrderEvent event) {
        log.info("[MQ] leaseOrderEvent received: type={} orderId={} agreementId={} userId={} roomId={} before={} after={} traceId={} occurredAt={}",
                event == null ? null : event.getType(),
                event == null ? null : event.getOrderId(),
                event == null ? null : event.getAgreementId(),
                event == null ? null : event.getUserId(),
                event == null ? null : event.getRoomId(),
                event == null ? null : event.getBeforeStatus(),
                event == null ? null : event.getAfterStatus(),
                event == null ? null : event.getTraceId(),
                event == null ? null : event.getOccurredAt());
    }
}
