package com.hyoguoo.paymentplatform.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * T-E1 RED — MdcTaskDecorator 단위 테스트.
 *
 * <p>decorator 적용 후 Runnable 실행 시 호출자 MDC 가 복원되고,
 * 실행 완료 후 MDC 가 clear 되어야 한다.
 */
@DisplayName("MdcTaskDecorator 단위 테스트 (T-E1 RED)")
class MdcTaskDecoratorTest {

    private static final String TRACE_ID_KEY = "traceId";
    private static final String TRACE_ID_VALUE = "decorator-trace-111";

    private MdcTaskDecorator decorator;

    @BeforeEach
    void setUp() {
        decorator = new MdcTaskDecorator();
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("decorate — 호출 스레드의 MDC 컨텍스트가 Runnable 실행 시 복원된다")
    void decorate_restoresMdcInsideRunnable() {
        // given: 호출 스레드에 MDC 설정
        MDC.put(TRACE_ID_KEY, TRACE_ID_VALUE);

        AtomicReference<String> capturedTraceId = new AtomicReference<>();
        Runnable original = () -> capturedTraceId.set(MDC.get(TRACE_ID_KEY));

        // when: decorate 후 실행 (호출 스레드와 동일 스레드에서)
        Runnable decorated = decorator.decorate(original);
        MDC.clear(); // 실행 전에 MDC 비움 (다른 스레드 시뮬레이션)
        decorated.run();

        // then
        assertThat(capturedTraceId.get())
                .as("Runnable 내부에서 decorate 시점의 traceId 가 복원되어야 한다")
                .isEqualTo(TRACE_ID_VALUE);
    }

    @Test
    @DisplayName("decorate — Runnable 실행 완료 후 MDC 가 clear 된다")
    void decorate_clearsMdcAfterRunnable() {
        // given
        MDC.put(TRACE_ID_KEY, TRACE_ID_VALUE);
        Runnable original = () -> {};

        // when
        Runnable decorated = decorator.decorate(original);
        decorated.run();

        // then: 실행 완료 후 MDC clear
        assertThat(MDC.get(TRACE_ID_KEY))
                .as("Runnable 실행 완료 후 MDC 가 비워져야 한다")
                .isNull();
    }

    @Test
    @DisplayName("decorate — Runnable 예외 발생 시에도 MDC 가 clear 된다")
    void decorate_clearsMdcEvenOnException() {
        // given
        MDC.put(TRACE_ID_KEY, TRACE_ID_VALUE);
        Runnable original = () -> {
            throw new RuntimeException("test error");
        };

        // when
        Runnable decorated = decorator.decorate(original);
        try {
            decorated.run();
        } catch (RuntimeException ignored) {
            // expected
        }

        // then: 예외 발생 후에도 MDC clear
        assertThat(MDC.get(TRACE_ID_KEY))
                .as("예외 발생 후에도 MDC 가 비워져야 한다")
                .isNull();
    }
}
