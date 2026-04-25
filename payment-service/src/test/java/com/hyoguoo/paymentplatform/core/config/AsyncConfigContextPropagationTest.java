package com.hyoguoo.paymentplatform.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.context.ContextRegistry;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.core.task.AsyncTaskExecutor;

/**
 * T-I2 RED — AsyncConfig.outboxRelayExecutor ContextExecutorService.wrap 검증.
 *
 * <p>현재 MdcTaskDecorator 만 적용된 구현은 MDC 는 전파하지만
 * OpenTelemetry Context (별도 ThreadLocal) 는 전파하지 않는다.
 *
 * <p>ContextExecutorService.wrap 으로 교체하면 MDC + OTel Tracing context 양쪽 모두
 * ContextRegistry 에 등록된 accessor 경유 전파된다.
 *
 * <p>TC1 — OTel Context.current() 의 커스텀 키 값이 VT 경계에서 승계된다.
 *   MdcTaskDecorator 는 MDC ThreadLocal 만 복사하므로 OTel Context 는 VT 에 전파되지 않아
 *   capturedValue == null → FAIL 확인.
 *
 * <p>TC2 — MDC 값 또한 VT 경계에서 승계된다 (ContextExecutorService.wrap 이 MDC 도 캡처).
 */
@DisplayName("T-I2 AsyncConfig.outboxRelayExecutor — ContextExecutorService 전파 검증")
class AsyncConfigContextPropagationTest {

    private static final String TRACE_ID_KEY = "traceId";
    private static final String TRACE_ID_VALUE = "async-config-trace-i2-test";
    private static final ContextKey<String> OTEL_CUSTOM_KEY = ContextKey.named("test.custom.value");
    private static final String OTEL_CUSTOM_VALUE = "otel-propagation-test-value";

    @BeforeEach
    void setUp() {
        // 단위 테스트 환경에서 Spring Context 없이 MDC accessor 수동 등록
        ContextRegistry.getInstance().registerThreadLocalAccessor(new Slf4jMdcThreadLocalAccessor());
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("TC1 — outboxRelayExecutor 는 OTel Context 를 VT 경계에서 전파해야 한다 (MdcTaskDecorator 미적용 시 FAIL)")
    void outboxRelayExecutor_shouldPropagateOtelContextToVirtualThread() throws Exception {
        // given: 현재 OTel Context 에 커스텀 값을 설정한 스코프
        AtomicReference<String> capturedOtelValue = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        AsyncConfig asyncConfig = new AsyncConfig();
        AsyncTaskExecutor executor = asyncConfig.outboxRelayExecutor();

        // when: OTel Context 에 커스텀 키-값을 설정하고 task 제출
        try (var ignored = Context.current().with(OTEL_CUSTOM_KEY, OTEL_CUSTOM_VALUE).makeCurrent()) {
            Future<?> future = executor.submit(() -> {
                // VT 내부에서 OTel Context 에 설정한 값 조회
                capturedOtelValue.set(Context.current().get(OTEL_CUSTOM_KEY));
                latch.countDown();
            });

            boolean completed = latch.await(3, TimeUnit.SECONDS);
            assertThat(completed)
                    .as("3초 이내에 VT task 가 완료되어야 한다")
                    .isTrue();
            future.get(1, TimeUnit.SECONDS);
        }

        // then: MdcTaskDecorator 는 OTel Context ThreadLocal 을 복사하지 않으므로 null → FAIL
        //       ContextExecutorService.wrap 적용 후에는 OTEL_CUSTOM_VALUE 가 전파됨
        assertThat(capturedOtelValue.get())
                .as("VT 내부에서 OTel Context 의 커스텀 값이 승계되어야 한다 — MdcTaskDecorator 미적용 시 null 반환으로 FAIL")
                .isEqualTo(OTEL_CUSTOM_VALUE);
    }

    @Test
    @DisplayName("TC2 — outboxRelayExecutor 는 MDC traceId 를 VT 경계에서 전파해야 한다")
    void outboxRelayExecutor_shouldPropagateMdcToVirtualThread() throws Exception {
        // given: 호출 스레드에 traceId 설정
        MDC.put(TRACE_ID_KEY, TRACE_ID_VALUE);
        AtomicReference<String> capturedTraceId = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        AsyncConfig asyncConfig = new AsyncConfig();
        AsyncTaskExecutor executor = asyncConfig.outboxRelayExecutor();

        // when
        Future<?> future = executor.submit(() -> {
            capturedTraceId.set(MDC.get(TRACE_ID_KEY));
            latch.countDown();
        });

        boolean completed = latch.await(3, TimeUnit.SECONDS);
        assertThat(completed).as("3초 이내에 VT task 가 완료되어야 한다").isTrue();
        future.get(1, TimeUnit.SECONDS);

        // then: MDC 값도 VT 경계에서 승계되어야 함
        assertThat(capturedTraceId.get())
                .as("VT 내부에서 호출 스레드의 MDC traceId 가 승계되어야 한다")
                .isEqualTo(TRACE_ID_VALUE);
    }
}
