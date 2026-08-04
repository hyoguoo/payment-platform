package com.hyoguoo.paymentplatform.payment.infrastructure.aspect;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.hyoguoo.paymentplatform.payment.application.aspect.annotation.PublishDomainEvent;
import com.hyoguoo.paymentplatform.payment.application.publisher.PaymentEventPublisher;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentCommandUseCase;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * {@code markPaymentAsFail} / {@code markPaymentAsQuarantined} 에 {@code @Trigger} 파라미터가
 * 새로 끼어든 뒤에도 {@link DomainEventLoggingAspect#findReasonParameter}(private, 리플렉션 대상)가
 * {@code @Reason} 파라미터만 정확히 골라내는지 확인한다.
 *
 * <p>findReasonParameter 는 파라미터 위치가 아니라 파라미터 애노테이션으로 대상을 찾으므로,
 * @Reason 뒤에 @Trigger 파라미터가 추가돼도 안전해야 한다 — 실제 뒤섞이지 않음을 실행으로 확인한다.
 */
@DisplayName("DomainEventLoggingAspect — Trigger 파라미터 추가 후 Reason 탐지 안전성 검증")
class DomainEventLoggingAspectReasonParameterTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-08-03T00:00:00Z"), ZoneOffset.UTC);

    @Test
    @DisplayName("markPaymentAsFail 의 @Reason 값이 뒤에 붙은 trigger 값과 섞이지 않고 그대로 기록된다")
    void reason파라미터_뒤에_trigger파라미터가_있어도_reason값이_정확히_추출된다() throws Throwable {
        // given
        PaymentEventPublisher paymentEventPublisher = Mockito.mock(PaymentEventPublisher.class);
        DomainEventLoggingAspect sut = new DomainEventLoggingAspect(paymentEventPublisher, FIXED_CLOCK);

        Method method = PaymentCommandUseCase.class.getMethod(
                "markPaymentAsFail", PaymentEvent.class, String.class, String.class);
        PublishDomainEvent publishDomainEvent = method.getAnnotation(PublishDomainEvent.class);

        PaymentEvent beforeEvent = buildEvent(PaymentEventStatus.IN_PROGRESS);
        PaymentEvent afterEvent = buildEvent(PaymentEventStatus.FAILED);

        MethodSignature signature = Mockito.mock(MethodSignature.class);
        given(signature.getMethod()).willReturn(method);

        ProceedingJoinPoint joinPoint = Mockito.mock(ProceedingJoinPoint.class);
        given(joinPoint.getSignature()).willReturn(signature);
        given(joinPoint.getArgs())
                .willReturn(new Object[]{beforeEvent, "재고 부족으로 인한 결제 실패", "stock_failure"});
        given(joinPoint.proceed()).willReturn(afterEvent);

        // when
        sut.publishHistoryEvent(joinPoint, publishDomainEvent);

        // then — @Reason 값("재고 부족...")이 기록되고, @Trigger 값("stock_failure")은 섞이지 않는다
        then(paymentEventPublisher).should()
                .publishStatusChange(eq(afterEvent), eq(PaymentEventStatus.IN_PROGRESS),
                        eq("재고 부족으로 인한 결제 실패"), any());
    }

    private static PaymentEvent buildEvent(PaymentEventStatus status) {
        return PaymentEvent.allArgsBuilder()
                .id(1L)
                .buyerId(100L)
                .sellerId(200L)
                .orderName("테스트 상품")
                .orderId("order-reason-safety-001")
                .status(status)
                .paymentOrderList(Collections.emptyList())
                .createdAt(Instant.parse("2026-08-03T00:00:00Z"))
                .lastStatusChangedAt(Instant.parse("2026-08-03T00:00:00Z"))
                .allArgsBuild();
    }
}
