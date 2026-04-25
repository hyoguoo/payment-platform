package com.hyoguoo.paymentplatform.core.config;

import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;
import io.opentelemetry.context.Context;
import java.util.concurrent.ExecutorService;
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
 *
 * <p>T-I2: 이중 래핑으로 @Async 경계에서 MDC(SLF4J) + OpenTelemetry Tracing context 를 모두 승계한다.
 * <ol>
 *   <li>{@link Context#taskWrapping} — OTel Context ThreadLocal(span/traceId) 전파.
 *       OTel ContextStorage 는 Micrometer ContextRegistry 와 별개이므로 별도 래핑 필요.</li>
 *   <li>{@link ContextExecutorService#wrap} — Micrometer ContextRegistry 에 등록된
 *       accessor(MDC 등) 전파. {@link MdcContextPropagationConfig#registerMdcAccessor()} 로
 *       {@link Slf4jMdcThreadLocalAccessor} 가 ContextRegistry 에 등록된다.</li>
 * </ol>
 *
 * <p>레거시 {@code MdcTaskDecorator} 는 MDC 만 복사하고 OTel Context ThreadLocal 은 무시하므로
 * 실 환경에서 incoming HTTP traceparent 와 다른 새 trace 가 Kafka 메시지에 박히는
 * 회귀가 발생한다 — 이중 래핑으로 교체하여 해결 (ADR-13).
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean("outboxRelayExecutor")
    public AsyncTaskExecutor outboxRelayExecutor() {
        ContextSnapshotFactory factory = ContextSnapshotFactory.builder().build();
        ExecutorService raw = Executors.newVirtualThreadPerTaskExecutor();
        // Step 1: OTel Context 전파 (OTel ContextStorage 는 Micrometer ContextRegistry 와 별개)
        ExecutorService otelWrapped = Context.taskWrapping(raw);
        // Step 2: Micrometer ContextRegistry 등록 accessor(MDC 등) 전파
        ExecutorService fullyWrapped = ContextExecutorService.wrap(otelWrapped, factory::captureAll);
        return new TaskExecutorAdapter(fullyWrapped);
    }
}
