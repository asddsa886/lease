package com.atguigu.lease.common.mq;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 基础配置。
 * <p>
 * 开关：mq.enabled=true 时启用（默认启用）。
 */
@Configuration
@ConditionalOnProperty(name = "mq.enabled", havingValue = "true", matchIfMissing = true)
public class RabbitMqConfiguration {

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
        return template;
    }
}
