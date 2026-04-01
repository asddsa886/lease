package com.atguigu.lease.web.app.schedule;

import com.atguigu.lease.common.mq.outbox.entity.OutboxMessage;
import com.atguigu.lease.common.mq.outbox.mapper.OutboxMessageMapper;
import com.atguigu.lease.common.mq.outbox.service.OutboxService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

/**
 * Outbox 补偿任务（App 服务也可以跑；实际部署可只让一个服务/实例负责）。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "mq.enabled", havingValue = "true", matchIfMissing = true)
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
        for (OutboxMessage m : list) {
            try {
                outboxService.sendOne(m.getId());
            } catch (Exception e) {
                log.warn("Outbox retry sendOne failed, id={}", m.getId(), e);
            }
        }
    }
}
