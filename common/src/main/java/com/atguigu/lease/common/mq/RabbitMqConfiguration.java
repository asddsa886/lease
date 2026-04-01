package com.atguigu.lease.common.mq;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * RabbitMQ 基础配置。
 * <p>
 * 开关：mq.enabled=true 时启用（默认启用）。
 */
@Configuration
@ConditionalOnProperty(name = "mq.enabled", havingValue = "true", matchIfMissing = true)
public class RabbitMqConfiguration {

    /**
     * 延迟消息 TTL（毫秒）：用于演示“超时自动处理”。
     * 你可以在 application.yml 里配置 lease.mq.timeout-ttl-ms。
     */
    @Value("${lease.mq.timeout-ttl-ms:60000}")
    private long leaseTimeoutTtlMs;

    @Bean
    public TopicExchange leaseExchange() {
        return new TopicExchange(LeaseMqConstants.LEASE_EXCHANGE, true, false);
    }

    @Bean
    public Queue leaseAgreementAuditQueue() {
        // 需要更强的可靠性可继续配置 DLX/TTL 等，这里先给出一个可运行的基础版本
        return QueueBuilder.durable(LeaseMqConstants.LEASE_AGREEMENT_AUDIT_QUEUE).build();
    }

    @Bean
    public Binding leaseAgreementAuditBinding(Queue leaseAgreementAuditQueue, TopicExchange leaseExchange) {
        return BindingBuilder.bind(leaseAgreementAuditQueue)
                .to(leaseExchange)
                .with(LeaseMqConstants.LEASE_AGREEMENT_EVENT_ROUTING_KEY);
    }

    /**
     * 延迟队列：消息先投递到该队列，TTL 到期后通过死信投递到 timeout queue。
     */
    @Bean
    public Queue leaseAgreementTimeoutDelayQueue() {
        Map<String, Object> args = new HashMap<>();
        // 死信交换机&路由
        args.put("x-dead-letter-exchange", LeaseMqConstants.LEASE_EXCHANGE);
        args.put("x-dead-letter-routing-key", LeaseMqConstants.LEASE_AGREEMENT_TIMEOUT_ROUTING_KEY);
        // 固定 TTL（也可改成“每条消息自带 expiration”）
        args.put("x-message-ttl", leaseTimeoutTtlMs);
        return QueueBuilder.durable(LeaseMqConstants.LEASE_AGREEMENT_TIMEOUT_DELAY_QUEUE)
                .withArguments(args)
                .build();
    }

    /** 超时检查消费队列 */
    @Bean
    public Queue leaseAgreementTimeoutQueue() {
        return QueueBuilder.durable(LeaseMqConstants.LEASE_AGREEMENT_TIMEOUT_QUEUE).build();
    }

    @Bean
    public Binding leaseAgreementTimeoutDelayBinding(Queue leaseAgreementTimeoutDelayQueue, TopicExchange leaseExchange) {
        return BindingBuilder.bind(leaseAgreementTimeoutDelayQueue)
                .to(leaseExchange)
                .with(LeaseMqConstants.LEASE_AGREEMENT_TIMEOUT_DELAY_ROUTING_KEY);
    }

    @Bean
    public Binding leaseAgreementTimeoutBinding(Queue leaseAgreementTimeoutQueue, TopicExchange leaseExchange) {
        return BindingBuilder.bind(leaseAgreementTimeoutQueue)
                .to(leaseExchange)
                .with(LeaseMqConstants.LEASE_AGREEMENT_TIMEOUT_ROUTING_KEY);
    }

    @Bean
    public MessageConverter jackson2JsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * 显式为 RabbitTemplate 设置 JSON converter，避免默认 Java 序列化。
     */
    @Bean
    public RabbitTemplate rabbitTemplate(CachingConnectionFactory connectionFactory,
                                         MessageConverter jackson2JsonMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jackson2JsonMessageConverter);

        // 开启 mandatory，保证路由失败能触发 returns 回调
        template.setMandatory(true);

        // Confirm：broker 收到消息（到 exchange）后的回执
        template.setConfirmCallback((correlationData, ack, cause) -> {
            String cid = correlationData == null ? null : correlationData.getId();
            if (ack) {
                // 这里先打日志；真正的“更新 outbox 状态”放到 OutboxSender 里做
                org.slf4j.LoggerFactory.getLogger(RabbitMqConfiguration.class)
                        .info("[MQ-CONFIRM] ack=true correlationId={}", cid);
            } else {
                org.slf4j.LoggerFactory.getLogger(RabbitMqConfiguration.class)
                        .warn("[MQ-CONFIRM] ack=false correlationId={} cause={}", cid, cause);
            }
        });

        // Return：exchange 存在但路由不到任何 queue
        template.setReturnsCallback((ReturnedMessage returned) -> {
            org.slf4j.LoggerFactory.getLogger(RabbitMqConfiguration.class)
                    .warn("[MQ-RETURN] replyCode={} replyText={} exchange={} routingKey={} messageId={}",
                            returned.getReplyCode(),
                            returned.getReplyText(),
                            returned.getExchange(),
                            returned.getRoutingKey(),
                            returned.getMessage() == null ? null : returned.getMessage().getMessageProperties().getMessageId());
        });

        return template;
    }
}
