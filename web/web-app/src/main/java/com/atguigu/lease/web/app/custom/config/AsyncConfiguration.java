package com.atguigu.lease.web.app.custom.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class AsyncConfiguration {

    @Value("${lease.async.browsing-history.core-pool-size:2}")
    private int browsingHistoryCorePoolSize;

    @Value("${lease.async.browsing-history.max-pool-size:8}")
    private int browsingHistoryMaxPoolSize;

    @Value("${lease.async.browsing-history.queue-capacity:5000}")
    private int browsingHistoryQueueCapacity;

    @Bean(name = "browsingHistoryTaskExecutor")
    public Executor browsingHistoryTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, browsingHistoryCorePoolSize));
        executor.setMaxPoolSize(Math.max(browsingHistoryCorePoolSize, browsingHistoryMaxPoolSize));
        executor.setQueueCapacity(Math.max(100, browsingHistoryQueueCapacity));
        executor.setKeepAliveSeconds(60);
        executor.setAllowCoreThreadTimeOut(true);
        executor.setThreadNamePrefix("browse-history-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
