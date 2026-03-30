package com.hyoguoo.paymentplatform.core.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Value("${spring.threads.virtual.enabled:false}")
    private boolean virtualThreadsEnabled;

    @Value("${outbox.immediate-handler.concurrency-limit:10}")
    private int concurrencyLimit;

    @Bean("immediateHandlerExecutor")
    public TaskExecutor immediateHandlerExecutor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("outbox-immediate-");
        executor.setVirtualThreads(virtualThreadsEnabled);
        executor.setConcurrencyLimit(concurrencyLimit);
        return executor;
    }
}
