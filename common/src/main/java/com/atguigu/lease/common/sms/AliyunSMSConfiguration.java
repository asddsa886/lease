package com.atguigu.lease.common.sms;

import com.aliyun.dysmsapi20170525.Client;
import com.aliyun.teaopenapi.models.Config;
import com.atguigu.lease.common.exception.LeaseException;
import com.atguigu.lease.common.result.ResultCodeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
@EnableConfigurationProperties(AliyunSMSProperties.class)
@Slf4j
public class AliyunSMSConfiguration {

    @Autowired
    private AliyunSMSProperties aliyunSMSProperties;

    @Bean
    @ConditionalOnProperty(name = "aliyun.sms.mock-enabled", havingValue = "false")
    public Client createClient() {
        if (!StringUtils.hasText(aliyunSMSProperties.getEndpoint())) {
            throw new LeaseException(ResultCodeEnum.APP_SMS_CLIENT_INIT_ERROR);
        }

        Config config = new Config();
        config.setAccessKeyId(aliyunSMSProperties.getAccessKeyId());
        config.setAccessKeySecret(aliyunSMSProperties.getAccessKeySecret());
        config.setEndpoint(aliyunSMSProperties.getEndpoint());

        try {
            return new Client(config);
        } catch (Exception e) {
            log.error("Aliyun SMS client init failed, endpoint={}", aliyunSMSProperties.getEndpoint(), e);
            throw new LeaseException(ResultCodeEnum.APP_SMS_CLIENT_INIT_ERROR);
        }

    }
}
