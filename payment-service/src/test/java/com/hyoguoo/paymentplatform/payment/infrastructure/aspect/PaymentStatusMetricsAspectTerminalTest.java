package com.hyoguoo.paymentplatform.payment.infrastructure.aspect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.hyoguoo.paymentplatform.payment.application.aspect.annotation.PaymentStatusChange;
import com.hyoguoo.paymentplatform.payment.core.common.metrics.PaymentEventFlowMetrics;
import com.hyoguoo.paymentplatform.payment.core.common.metrics.PaymentTransitionMetrics;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;

/**
 * PaymentStatusMetricsAspect 종결 카운터 분기 단위 테스트.
 *
 * <p>검증 범위:
 * <ul>
 *   <li>종결 5종(DONE/FAILED/CANCELED/PARTIAL_CANCELED/EXPIRED) → payment.event.terminal 1 증가</li>
 *   <li>비종결(READY/IN_PROGRESS/RETRYING/QUARANTINED) → 증가 안 함</li>
 * </ul>
 *
 * <p>종결 판별은 {@link PaymentEventStatus#isTerminal()} SSOT 위임 — aspect 직접 집합 정의 없음.
 * AOP proceed 성공 후에만 계측하는 구조이므로 {@link ProceedingJoinPoint} 를 Mockito 로 stub.
 */
@DisplayName("PaymentStatusMetricsAspect 종결 카운터 분기 단위 테스트")
class PaymentStatusMetricsAspectTerminalTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-06-11T00:00:00Z"), ZoneOffset.UTC);

    private SimpleMeterRegistry meterRegistry;
    private PaymentStatusMetricsAspect sut;
    private PaymentStatusChange annotationStub;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        PaymentTransitionMetrics transitionMetrics = new PaymentTransitionMetrics(meterRegistry);
        PaymentEventFlowMetrics flowMetrics = new PaymentEventFlowMetrics(meterRegistry);
        sut = new PaymentStatusMetricsAspect(transitionMetrics, flowMetrics, FIXED_CLOCK);

        annotationStub = Mockito.mock(PaymentStatusChange.class);
        given(annotationStub.trigger()).willReturn("confirm");
        given(annotationStub.toStatus()).willReturn("UNKNOWN");
    }

    @ParameterizedTest(name = "종결 상태 {0} → terminal 카운터 1 증가")
    @EnumSource(value = PaymentEventStatus.class, names = {
            "DONE", "FAILED", "CANCELED", "PARTIAL_CANCELED", "EXPIRED"
    })
    @DisplayName("terminalStatus_recordStatusChange_terminalCounterIncremented")
    void terminalStatus_recordStatusChange_terminalCounterIncremented(
            PaymentEventStatus terminalStatus
    ) throws Throwable {
        PaymentEvent resultEvent = buildEventWithStatus(terminalStatus);
        ProceedingJoinPoint joinPoint = mockJoinPointReturning(resultEvent);

        sut.recordStatusChange(joinPoint, annotationStub);

        Counter counter = meterRegistry.find("payment.event.terminal").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @ParameterizedTest(name = "비종결 상태 {0} → terminal 카운터 증가 안 함")
    @EnumSource(value = PaymentEventStatus.class, names = {
            "READY", "IN_PROGRESS", "RETRYING", "QUARANTINED"
    })
    @DisplayName("nonTerminalStatus_recordStatusChange_terminalCounterNotIncremented")
    void nonTerminalStatus_recordStatusChange_terminalCounterNotIncremented(
            PaymentEventStatus nonTerminalStatus
    ) throws Throwable {
        PaymentEvent resultEvent = buildEventWithStatus(nonTerminalStatus);
        ProceedingJoinPoint joinPoint = mockJoinPointReturning(resultEvent);

        sut.recordStatusChange(joinPoint, annotationStub);

        Counter counter = meterRegistry.find("payment.event.terminal").counter();
        // 비종결 상태에서는 카운터가 등록되지 않거나 0.0 이어야 한다
        assertThat(counter == null || counter.count() == 0.0).isTrue();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // helpers
    // ──────────────────────────────────────────────────────────────────────────

    private static PaymentEvent buildEventWithStatus(PaymentEventStatus status) {
        return PaymentEvent.allArgsBuilder()
                .id(1L)
                .buyerId(100L)
                .sellerId(200L)
                .orderName("테스트 상품")
                .orderId("order-test-001")
                .paymentKey(null)
                .gatewayType(null)
                .status(status)
                .executedAt(null)
                .approvedAt(null)
                .retryCount(0)
                .statusReason(null)
                .paymentOrderList(Collections.emptyList())
                .createdAt(Instant.parse("2026-06-11T00:00:00Z"))
                .lastStatusChangedAt(Instant.parse("2026-06-11T00:00:00Z"))
                .allArgsBuild();
    }

    private ProceedingJoinPoint mockJoinPointReturning(PaymentEvent returnValue) throws Throwable {
        ProceedingJoinPoint joinPoint = Mockito.mock(ProceedingJoinPoint.class);
        given(joinPoint.proceed()).willReturn(returnValue);
        given(joinPoint.getArgs()).willReturn(new Object[]{returnValue});
        return joinPoint;
    }
}
