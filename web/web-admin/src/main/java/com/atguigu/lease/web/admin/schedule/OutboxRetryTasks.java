package com.atguigu.lease.web.admin.schedule;

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
 * Outbox 补偿任务（后台服务默认负责）：定时扫描 NEW/FAILED 并尝试投递。
 */
@Slf4j
@Component
@ConditionalOnExpression("'${mq.enabled:true}' == 'true' and '${lease.outbox.retry-owner:admin}' == 'admin'")
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
