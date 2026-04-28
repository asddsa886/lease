package com.atguigu.lease.common.mq.outbox.service.impl;

import com.atguigu.lease.common.mq.outbox.OutboxMessageStatus;
import com.atguigu.lease.common.mq.outbox.entity.OutboxMessage;
import com.atguigu.lease.common.mq.outbox.mapper.OutboxMessageMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Date;
import java.util.Objects;

/**
 * Outbox 发送服务：
 * - NEW/FAILED -> 发送 -> 标记 SENT
 * - ConfirmCallback ack=true -> 标记 ACKED（这里用 correlation future 简化演示）
 * - 发送异常/ack=false -> 标记 FAILED 并回退重试时间
 *
 * 说明：这里采用 correlationData.getFuture() 等待 confirm（带超时），实现“消息必达”的最小闭环。
 * 更完整的版本会把 confirm/return 回调与 outbox 状态更新完全解耦并持久化。
 */
@Slf4j
@Service
public class OutboxServiceImpl implements com.atguigu.lease.common.mq.outbox.service.OutboxService {

    private final OutboxMessageMapper outboxMessageMapper;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    public OutboxServiceImpl(OutboxMessageMapper outboxMessageMapper,
                             RabbitTemplate rabbitTemplate,
                             ObjectMapper objectMapper) {
        this.outboxMessageMapper = outboxMessageMapper;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OutboxMessage saveNew(OutboxMessage msg) {
        Objects.requireNonNull(msg, "msg");
        if (msg.getStatus() == null) {
            msg.setStatus(OutboxMessageStatus.NEW);
        }
        if (msg.getTryCount() == null) {
            msg.setTryCount(0);
        }
        if (msg.getCreateTime() == null) {
            msg.setCreateTime(new Date());
        }
        msg.setUpdateTime(new Date());
        outboxMessageMapper.insert(msg);
        return msg;
    }

    @Override
    public void sendOne(Long outboxId) {
        if (outboxId == null) {
            return;
        }

        OutboxMessage msg = outboxMessageMapper.selectById(outboxId);
        if (msg == null) {
            return;
        }

        if (msg.getStatus() != null && msg.getStatus() != OutboxMessageStatus.NEW && msg.getStatus() != OutboxMessageStatus.FAILED) {
            return;
        }

        int nextTry = (msg.getTryCount() == null ? 0 : msg.getTryCount()) + 1;
        Date now = new Date();
        int claimed = outboxMessageMapper.claimForSend(
                outboxId,
                OutboxMessageStatus.NEW,
                OutboxMessageStatus.FAILED,
                OutboxMessageStatus.SENT,
                nextTry,
                now
        );
        if (claimed < 1) {
            return;
        }

        CorrelationData correlationData = new CorrelationData(String.valueOf(outboxId));

        try {
            Object payload = deserializePayload(msg);
            rabbitTemplate.convertAndSend(msg.getExchange(), msg.getRoutingKey(), payload, correlationData);

            boolean ack = correlationData.getFuture().get(3000, java.util.concurrent.TimeUnit.MILLISECONDS).isAck();
            if (ack) {
                OutboxMessage acked = new OutboxMessage();
                acked.setId(outboxId);
                acked.setStatus(OutboxMessageStatus.ACKED);
                acked.setUpdateTime(new Date());
                outboxMessageMapper.updateById(acked);
            } else {
                markFailed(outboxId, nextTry, "broker nack", backoff(nextTry));
            }

        } catch (Exception e) {
            markFailed(outboxId, nextTry, safeMsg(e), backoff(nextTry));
        }
    }

    private void markFailed(Long outboxId, int tryCount, String err, Duration backoff) {
        OutboxMessage failed = new OutboxMessage();
        failed.setId(outboxId);
        failed.setStatus(OutboxMessageStatus.FAILED);
        failed.setTryCount(tryCount);
        failed.setLastError(truncate(err, 500));
        failed.setNextRetryAt(new Date(System.currentTimeMillis() + backoff.toMillis()));
        failed.setUpdateTime(new Date());
        outboxMessageMapper.updateById(failed);
        log.warn("Outbox send failed, id={}, tryCount={}, nextRetryIn={}s, err={}", outboxId, tryCount, backoff.toSeconds(), err);
    }

    private Object deserializePayload(OutboxMessage msg) throws Exception {
        if (msg == null || msg.getPayload() == null || msg.getPayloadType() == null) {
            return msg == null ? null : msg.getPayload();
        }
        Class<?> payloadClass = Class.forName(msg.getPayloadType());
        return objectMapper.readValue(msg.getPayload(), payloadClass);
    }

    private static Duration backoff(int tryCount) {
        long sec = Math.min(60, 1L << Math.min(tryCount, 6));
        return Duration.ofSeconds(sec);
    }

    private static String safeMsg(Exception e) {
        return e == null ? "unknown" : (e.getClass().getSimpleName() + ":" + e.getMessage());
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max);
    }
}
