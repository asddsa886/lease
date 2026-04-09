package com.atguigu.lease.web.app.custom.config;

import com.atguigu.lease.web.app.chat.config.AssistantProperties;
import com.atguigu.lease.web.app.custom.convert.StringToBaseEnumConverterFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfiguration implements WebMvcConfigurer {
    @Autowired
    private StringToBaseEnumConverterFactory stringToBaseEnumConverterFactory;
    @Autowired
    private AssistantProperties assistantProperties;

    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverterFactory(this.stringToBaseEnumConverterFactory);
    }

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        long baseTimeout = assistantProperties.getTimeout() == null
                ? 60000L
                : assistantProperties.getTimeout().toMillis();
        int retries = assistantProperties.getMaxRetries() == null
                ? 2
                : Math.max(assistantProperties.getMaxRetries(), 0);
        long timeoutBudget = baseTimeout * (retries + 1L) * 2L + 15000L;
        configurer.setDefaultTimeout(Math.max(timeoutBudget, 60000L));
    }
}
