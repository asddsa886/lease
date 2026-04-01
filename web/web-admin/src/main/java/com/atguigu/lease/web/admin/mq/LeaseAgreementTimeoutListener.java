package com.atguigu.lease.web.admin.mq;

import com.atguigu.lease.common.mq.LeaseMqConstants;
import com.atguigu.lease.common.mq.event.LeaseAgreementEvent;
import com.atguigu.lease.common.mq.publisher.LeaseAgreementEventPublisher;
import com.atguigu.lease.model.entity.LeaseAgreement;
import com.atguigu.lease.model.enums.LeaseStatus;
import com.atguigu.lease.web.admin.service.LeaseAgreementService;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@ConditionalOnProperty(name = "mq.enabled", havingValue = "true", matchIfMissing = true)
public class LeaseAgreementTimeoutListener {

    private final LeaseAgreementService leaseAgreementService;
    private final LeaseAgreementEventPublisher leaseAgreementEventPublisher;

    public LeaseAgreementTimeoutListener(LeaseAgreementService leaseAgreementService,
                                         LeaseAgreementEventPublisher leaseAgreementEventPublisher) {
        this.leaseAgreementService = leaseAgreementService;
        this.leaseAgreementEventPublisher = leaseAgreementEventPublisher;
    }

    @RabbitListener(queues = LeaseMqConstants.LEASE_AGREEMENT_TIMEOUT_QUEUE)
    @Transactional(rollbackFor = Exception.class)
    public void onTimeout(LeaseAgreementEvent event) {
        if (event == null || event.getType() != LeaseAgreementEvent.Type.TIMEOUT_CHECK || event.getAgreementId() == null) {
            return;
        }

        LeaseStatus expectedStatus = parseStatus(event.getBeforeStatus());
        LeaseStatus timeoutStatus = parseStatus(event.getAfterStatus());
        if (expectedStatus == null || timeoutStatus == null) {
            log.warn("[MQ-TIMEOUT] invalid event, agreementId={}, before={}, after={}",
                    event.getAgreementId(), event.getBeforeStatus(), event.getAfterStatus());
            return;
        }

        LambdaUpdateWrapper<LeaseAgreement> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(LeaseAgreement::getId, event.getAgreementId());
        updateWrapper.eq(LeaseAgreement::getStatus, expectedStatus);
        updateWrapper.set(LeaseAgreement::getStatus, timeoutStatus);

        boolean updated = leaseAgreementService.update(updateWrapper);
        if (!updated) {
            log.info("[MQ-TIMEOUT] skipped, agreementId={} expectedStatus={}",
                    event.getAgreementId(), expectedStatus.name());
            return;
        }

        LeaseAgreement current = leaseAgreementService.getById(event.getAgreementId());
        String phone = current == null ? event.getPhone() : current.getPhone();

        log.info("[MQ-TIMEOUT] agreementId={} status {} -> {}",
                event.getAgreementId(), expectedStatus.name(), timeoutStatus.name());

        leaseAgreementEventPublisher.publishStatusChanged(
                event.getAgreementId(),
                phone,
                expectedStatus.name(),
                timeoutStatus.name()
        );
    }

    private static LeaseStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LeaseStatus.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
