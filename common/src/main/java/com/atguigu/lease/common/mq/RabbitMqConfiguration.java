package com.atguigu.lease.common.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * RabbitMQ 基础配置。
 */
@Configuration
@ConditionalOnProperty(name = "mq.enabled", havingValue = "true", matchIfMissing = true)
public class RabbitMqConfiguration {

    @Value("${lease.mq.timeout-ttl-ms:60000}")
    private long leaseAgreementTimeoutTtlMs;

    @Value("${lease.mq.order-timeout-ttl-ms:900000}")
    private long leaseOrderTimeoutTtlMs;

    @Bean
    public TopicExchange leaseExchange() {
        return new TopicExchange(LeaseMqConstants.LEASE_EXCHANGE, true, false);
    }

    @Bean
    public Queue leaseAgreementAuditQueue() {
        return QueueBuilder.durable(LeaseMqConstants.LEASE_AGREEMENT_AUDIT_QUEUE).build();
    }

    @Bean
    public Binding leaseAgreementAuditBinding(Queue leaseAgreementAuditQueue, TopicExchange leaseExchange) {
        return BindingBuilder.bind(leaseAgreementAuditQueue)
                .to(leaseExchange)
                .with(LeaseMqConstants.LEASE_AGREEMENT_EVENT_ROUTING_KEY);
    }

    @Bean
    public Queue leaseAgreementTimeoutDelayQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", LeaseMqConstants.LEASE_EXCHANGE);
        args.put("x-dead-letter-routing-key", LeaseMqConstants.LEASE_AGREEMENT_TIMEOUT_ROUTING_KEY);
        args.put("x-message-ttl", leaseAgreementTimeoutTtlMs);
        return QueueBuilder.durable(LeaseMqConstants.LEASE_AGREEMENT_TIMEOUT_DELAY_QUEUE)
                .withArguments(args)
                .build();
    }

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
    public Queue leaseOrderAuditQueue() {
        return QueueBuilder.durable(LeaseMqConstants.LEASE_ORDER_AUDIT_QUEUE).build();
    }

    @Bean
    public Binding leaseOrderAuditBinding(Queue leaseOrderAuditQueue, TopicExchange leaseExchange) {
        return BindingBuilder.bind(leaseOrderAuditQueue)
                .to(leaseExchange)
                .with(LeaseMqConstants.LEASE_ORDER_EVENT_ROUTING_KEY);
    }

    @Bean
    public Queue leaseOrderTimeoutDelayQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", LeaseMqConstants.LEASE_EXCHANGE);
        args.put("x-dead-letter-routing-key", LeaseMqConstants.LEASE_ORDER_TIMEOUT_ROUTING_KEY);
        args.put("x-message-ttl", leaseOrderTimeoutTtlMs);
        return QueueBuilder.durable(LeaseMqConstants.LEASE_ORDER_TIMEOUT_DELAY_QUEUE)
                .withArguments(args)
                .build();
    }

    @Bean
    public Queue leaseOrderTimeoutQueue() {
        return QueueBuilder.durable(LeaseMqConstants.LEASE_ORDER_TIMEOUT_QUEUE).build();
    }

    @Bean
    public Binding leaseOrderTimeoutDelayBinding(Queue leaseOrderTimeoutDelayQueue, TopicExchange leaseExchange) {
        return BindingBuilder.bind(leaseOrderTimeoutDelayQueue)
                .to(leaseExchange)
                .with(LeaseMqConstants.LEASE_ORDER_TIMEOUT_DELAY_ROUTING_KEY);
    }

    @Bean
    public Binding leaseOrderTimeoutBinding(Queue leaseOrderTimeoutQueue, TopicExchange leaseExchange) {
        return BindingBuilder.bind(leaseOrderTimeoutQueue)
                .to(leaseExchange)
                .with(LeaseMqConstants.LEASE_ORDER_TIMEOUT_ROUTING_KEY);
    }

    @Bean
    public MessageConverter jackson2JsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    @Bean
    public RabbitTemplate rabbitTemplate(CachingConnectionFactory connectionFactory,
                                         MessageConverter jackson2JsonMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jackson2JsonMessageConverter);
        template.setMandatory(true);

        template.setConfirmCallback((correlationData, ack, cause) -> {
            String cid = correlationData == null ? null : correlationData.getId();
            if (ack) {
                org.slf4j.LoggerFactory.getLogger(RabbitMqConfiguration.class)
                        .info("[MQ-CONFIRM] ack=true correlationId={}", cid);
            } else {
                org.slf4j.LoggerFactory.getLogger(RabbitMqConfiguration.class)
                        .warn("[MQ-CONFIRM] ack=false correlationId={} cause={}", cid, cause);
            }
        });

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
