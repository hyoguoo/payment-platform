package com.hyoguoo.paymentplatform.core.config;

import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * outbox relay 용 가상 스레드 executor를 제공한다.
 *
 * <p>{@code @TransactionalEventListener(AFTER_COMMIT)} 리스너가
 * {@code @Async("outboxRelayExecutor")}로 이 executor를 가리키면
 * confirm 요청 스레드는 즉시 반환되고, relay는 별도 VT에서 수행된다.
 * Tomcat 워커 스레드(플랫폼 스레드)는 점유되지 않는다.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean("outboxRelayExecutor")
    public AsyncTaskExecutor outboxRelayExecutor() {
        return new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor());
    }
}
