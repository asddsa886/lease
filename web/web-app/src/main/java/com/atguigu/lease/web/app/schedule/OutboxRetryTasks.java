package com.atguigu.lease.web.app.schedule;

import com.atguigu.lease.common.mq.outbox.entity.OutboxMessage;
import com.atguigu.lease.common.mq.outbox.mapper.OutboxMessageMapper;
import com.atguigu.lease.common.mq.outbox.service.OutboxService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

/**
 * Outbox 补偿任务（默认不由 App 服务负责；可通过 retry-owner 切换）。
 */
@Slf4j
@Component
@ConditionalOnExpression("'${mq.enabled:true}' == 'true' and '${lease.outbox.retry-owner:admin}' == 'app'")
public class OutboxRetryTasks {

    private final OutboxMessageMapper outboxMessageMapper;
    private final OutboxService outboxService;

    public OutboxRetryTasks(OutboxMessageMapper outboxMessageMapper, OutboxService outboxService) {
        this.outboxMessageMapper = outboxMessageMapper;
        this.outboxService = outboxService;
    }

    @Scheduled(fixedDelayString = "${lease.outbox.retry-delay-ms:5000}")
    public void retry() {
        List<OutboxMessage> list = outboxMessageMapper.listPending(new Date(), 50);
        if (list == null || list.isEmpty()) {
            return;
        }
        for (OutboxMessage message : list) {
            try {
                outboxService.sendOne(message.getId());
            } catch (Exception e) {
                log.warn("Outbox retry sendOne failed, id={}", message.getId(), e);
            }
        }
    }
}
