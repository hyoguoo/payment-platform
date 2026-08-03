package com.hyoguoo.paymentplatform.payment.infrastructure.aspect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.hyoguoo.paymentplatform.payment.application.aspect.annotation.PaymentStatusChange;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentCommandUseCase;
import com.hyoguoo.paymentplatform.payment.core.common.aspect.annotation.Trigger;
import com.hyoguoo.paymentplatform.payment.core.common.metrics.PaymentEventFlowMetrics;
import com.hyoguoo.paymentplatform.payment.core.common.metrics.PaymentTransitionMetrics;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * PaymentStatusMetricsAspect 의 전이 주체(trigger) 라벨 기록 단위 테스트.
 *
 * <p>고정 선언(애노테이션) 또는 호출자 전달(@Trigger 파라미터) 값이 그대로 기록되는지,
 * SimpleMeterRegistry 에 실제 등록된 카운터 태그 값을 읽어 확인한다 — 호출 사실만으로는
 * 라벨이 비거나 어긋나는 것을 잡을 수 없다.
 */
@DisplayName("PaymentStatusMetricsAspect 전이 주체 라벨 기록 단위 테스트")
class PaymentStatusMetricsAspectTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-08-03T00:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

    private SimpleMeterRegistry meterRegistry;
    private PaymentStatusMetricsAspect sut;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        sut = new PaymentStatusMetricsAspect(
                new PaymentTransitionMetrics(meterRegistry),
                new PaymentEventFlowMetrics(meterRegistry),
                FIXED_CLOCK);
    }

    @Test
    @DisplayName("승인_결과_수신_전이는_confirm_라벨로_기록된다")
    void 승인_결과_수신_전이는_confirm_라벨로_기록된다() throws Throwable {
        Method method = PaymentCommandUseCase.class.getMethod(
                "markPaymentAsDone", PaymentEvent.class, Instant.class);
        PaymentStatusChange annotation = method.getAnnotation(PaymentStatusChange.class);

        PaymentEvent originalEvent = buildEvent(PaymentEventStatus.IN_PROGRESS);
        PaymentEvent resultEvent = buildEvent(PaymentEventStatus.DONE);
        ProceedingJoinPoint joinPoint = mockJoinPoint(
                method, new Object[]{originalEvent, FIXED_INSTANT}, resultEvent);

        sut.recordStatusChange(joinPoint, annotation);

        assertThat(recordedTrigger("IN_PROGRESS", "DONE")).isEqualTo("confirm");
    }

    @Test
    @DisplayName("만료_전이는_expiration_라벨로_기록된다")
    void 만료_전이는_expiration_라벨로_기록된다() throws Throwable {
        Method method = PaymentCommandUseCase.class.getMethod("expirePayment", PaymentEvent.class);
        PaymentStatusChange annotation = method.getAnnotation(PaymentStatusChange.class);

        PaymentEvent originalEvent = buildEvent(PaymentEventStatus.READY);
        PaymentEvent resultEvent = buildEvent(PaymentEventStatus.EXPIRED);
        ProceedingJoinPoint joinPoint = mockJoinPoint(
                method, new Object[]{originalEvent}, resultEvent);

        sut.recordStatusChange(joinPoint, annotation);

        assertThat(recordedTrigger("READY", "EXPIRED")).isEqualTo("expiration");
    }

    @Test
    @DisplayName("관리자_수동_종결은_manual_라벨로_기록된다")
    void 관리자_수동_종결은_manual_라벨로_기록된다() throws Throwable {
        Method method = PaymentCommandUseCase.class.getMethod(
                "markPaymentAsFailFromQuarantine", PaymentEvent.class, String.class);
        PaymentStatusChange annotation = method.getAnnotation(PaymentStatusChange.class);

        PaymentEvent originalEvent = buildEvent(PaymentEventStatus.QUARANTINED);
        PaymentEvent resultEvent = buildEvent(PaymentEventStatus.FAILED);
        ProceedingJoinPoint joinPoint = mockJoinPoint(
                method, new Object[]{originalEvent, "관리자 안전 종결"}, resultEvent);

        sut.recordStatusChange(joinPoint, annotation);

        assertThat(recordedTrigger("QUARANTINED", "FAILED")).isEqualTo("manual");
    }

    @Test
    @DisplayName("라벨에_unknown_이_기록되는_경로가_없다")
    void 라벨에_unknown_이_기록되는_경로가_없다() {
        for (Method method : PaymentCommandUseCase.class.getDeclaredMethods()) {
            PaymentStatusChange annotation = method.getAnnotation(PaymentStatusChange.class);
            if (annotation == null) {
                continue;
            }

            boolean hasFixedTrigger = !annotation.trigger().isBlank();
            boolean hasTriggerParameter = hasTriggerParameter(method);

            assertThat(hasFixedTrigger || hasTriggerParameter)
                    .as("%s 메서드는 고정 trigger 또는 @Trigger 파라미터 중 하나는 있어야 한다", method.getName())
                    .isTrue();
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // helpers
    // ──────────────────────────────────────────────────────────────────────────

    private boolean hasTriggerParameter(Method method) {
        for (Annotation[] parameterAnnotations : method.getParameterAnnotations()) {
            for (Annotation annotation : parameterAnnotations) {
                if (annotation instanceof Trigger) {
                    return true;
                }
            }
        }
        return false;
    }

    private String recordedTrigger(String fromStatus, String toStatus) {
        Counter counter = meterRegistry.find("payment_transition_total")
                .tag("from_status", fromStatus)
                .tag("to_status", toStatus)
                .counter();
        assertThat(counter).isNotNull();
        return counter.getId().getTag("trigger");
    }

    private static PaymentEvent buildEvent(PaymentEventStatus status) {
        return PaymentEvent.allArgsBuilder()
                .id(1L)
                .buyerId(100L)
                .sellerId(200L)
                .orderName("테스트 상품")
                .orderId("order-trigger-label-001")
                .status(status)
                .paymentOrderList(Collections.emptyList())
                .createdAt(FIXED_INSTANT)
                .lastStatusChangedAt(FIXED_INSTANT)
                .allArgsBuild();
    }

    private ProceedingJoinPoint mockJoinPoint(Method method, Object[] args, PaymentEvent resultEvent)
            throws Throwable {
        MethodSignature signature = Mockito.mock(MethodSignature.class);
        given(signature.getMethod()).willReturn(method);

        ProceedingJoinPoint joinPoint = Mockito.mock(ProceedingJoinPoint.class);
        given(joinPoint.getSignature()).willReturn(signature);
        given(joinPoint.getArgs()).willReturn(args);
        given(joinPoint.proceed()).willReturn(resultEvent);
        return joinPoint;
    }
}
