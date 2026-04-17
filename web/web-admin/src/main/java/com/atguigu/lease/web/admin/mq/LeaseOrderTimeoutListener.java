package com.atguigu.lease.web.admin.mq;

import com.atguigu.lease.common.mq.LeaseMqConstants;
import com.atguigu.lease.common.mq.event.LeaseOrderEvent;
import com.atguigu.lease.common.mq.publisher.LeaseOrderEventPublisher;
import com.atguigu.lease.model.entity.LeaseOrder;
import com.atguigu.lease.model.enums.LeaseOrderStatus;
import com.atguigu.lease.web.admin.service.LeaseOrderService;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@ConditionalOnProperty(name = "mq.enabled", havingValue = "true", matchIfMissing = true)
public class LeaseOrderTimeoutListener {

    private final LeaseOrderService leaseOrderService;
    private final LeaseOrderEventPublisher leaseOrderEventPublisher;

    public LeaseOrderTimeoutListener(LeaseOrderService leaseOrderService,
                                     LeaseOrderEventPublisher leaseOrderEventPublisher) {
        this.leaseOrderService = leaseOrderService;
        this.leaseOrderEventPublisher = leaseOrderEventPublisher;
    }

    @RabbitListener(queues = LeaseMqConstants.LEASE_ORDER_TIMEOUT_QUEUE)
    @Transactional(rollbackFor = Exception.class)
    public void onTimeout(LeaseOrderEvent event) {
        if (event == null || event.getType() != LeaseOrderEvent.Type.TIMEOUT_CHECK || event.getOrderId() == null) {
            return;
        }

        LeaseOrderStatus expectedStatus = parseStatus(event.getBeforeStatus());
        LeaseOrderStatus timeoutStatus = parseStatus(event.getAfterStatus());
        if (expectedStatus == null || timeoutStatus == null) {
            log.warn("[MQ-TIMEOUT] invalid order event, orderId={}, before={}, after={}",
                    event.getOrderId(), event.getBeforeStatus(), event.getAfterStatus());
            return;
        }

        LambdaUpdateWrapper<LeaseOrder> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(LeaseOrder::getId, event.getOrderId())
                .eq(LeaseOrder::getStatus, expectedStatus)
                .set(LeaseOrder::getStatus, timeoutStatus);
        boolean updated = leaseOrderService.update(updateWrapper);
        if (!updated) {
            log.info("[MQ-TIMEOUT] skipped orderId={} expectedStatus={}", event.getOrderId(), expectedStatus.name());
            return;
        }

        LeaseOrder current = leaseOrderService.getById((java.io.Serializable) event.getOrderId());
        Long agreementId = current == null ? event.getAgreementId() : current.getAgreementId();
        Long userId = current == null ? event.getUserId() : current.getUserId();
        Long roomId = current == null ? event.getRoomId() : current.getRoomId();
        String phone = current == null ? event.getPhone() : current.getPhone();

        log.info("[MQ-TIMEOUT] orderId={} status {} -> {}", event.getOrderId(), expectedStatus.name(), timeoutStatus.name());

        leaseOrderEventPublisher.publishStatusChanged(
                event.getOrderId(),
                agreementId,
                userId,
                roomId,
                phone,
                expectedStatus.name(),
                timeoutStatus.name()
        );
    }

    private static LeaseOrderStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LeaseOrderStatus.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
