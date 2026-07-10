package com.hyoguoo.paymentplatform.payment.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hyoguoo.paymentplatform.payment.application.aspect.annotation.PaymentStatusChange;
import com.hyoguoo.paymentplatform.payment.application.aspect.annotation.PublishDomainEvent;
import com.hyoguoo.paymentplatform.payment.core.common.aspect.annotation.Reason;
import com.hyoguoo.paymentplatform.payment.core.common.metrics.PaymentQuarantineMetrics;
import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.exception.PaymentStatusException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.transaction.annotation.Transactional;

// payment-service 는 PG 를 직접 호출하지 않는다 — confirmPaymentWithGateway / getPaymentStatusByOrderId
// 메서드는 삭제되었으며 그에 대응하는 테스트도 함께 제거되었다.

class PaymentCommandUseCaseTest {

    private static final Instant FIXED_INSTANT = Instant.now();
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

    private PaymentCommandUseCase paymentCommandUseCase;
    private PaymentEventRepository mockPaymentEventRepository;
    private PaymentQuarantineMetrics mockPaymentQuarantineMetrics;

    @BeforeEach
    void setUp() {
        mockPaymentEventRepository = Mockito.mock(PaymentEventRepository.class);
        mockPaymentQuarantineMetrics = Mockito.mock(PaymentQuarantineMetrics.class);
        paymentCommandUseCase = new PaymentCommandUseCase(
                mockPaymentEventRepository,
                FIXED_CLOCK,
                mockPaymentQuarantineMetrics
        );
    }

    @Test
    @DisplayName("결제 시작을 호출하고 성공적으로 처리된 PaymentEvent를 반환한다.")
    void testExecutePayment_Success() {
        // given
        String paymentKey = "paymentKey";
        PaymentEvent paymentEvent = Mockito.mock(PaymentEvent.class);

        // when
        when(mockPaymentEventRepository.saveOrUpdate(any(PaymentEvent.class)))
                .thenReturn(paymentEvent);
        PaymentEvent result = paymentCommandUseCase.executePayment(paymentEvent, paymentKey);

        // then
        verify(paymentEvent, times(1)).execute(paymentKey, FIXED_INSTANT,
                FIXED_INSTANT);
        assertThat(result).isEqualTo(paymentEvent);
    }

    @Test
    @DisplayName("결제 완료 처리를 호출하고 성공적으로 완료된 PaymentEvent를 반환한다.")
    void testMarkPaymentAsDone() {
        // given
        PaymentEvent paymentEvent = Mockito.mock(PaymentEvent.class);
        Instant approvedAt = Instant.parse("2021-01-01T00:00:00Z");

        // when
        when(mockPaymentEventRepository.saveOrUpdate(any(PaymentEvent.class)))
                .thenReturn(paymentEvent);
        PaymentEvent result = paymentCommandUseCase.markPaymentAsDone(paymentEvent, approvedAt);

        // then
        verify(paymentEvent, times(1)).done(approvedAt, FIXED_INSTANT);
        assertThat(result.getId()).isEqualTo(paymentEvent.getId());

    }

    @Test
    @DisplayName("결제 실패 처리를 호출하고 성공적으로 실패된 PaymentEvent를 반환한다.")
    void testMarkPaymentAsFail() {
        // given
        PaymentEvent paymentEvent = Mockito.mock(PaymentEvent.class);
        String failureReason = "";

        // when
        when(mockPaymentEventRepository.saveOrUpdate(any(PaymentEvent.class)))
                .thenReturn(paymentEvent);
        PaymentEvent result = paymentCommandUseCase.markPaymentAsFail(paymentEvent, failureReason);

        // then
        verify(paymentEvent, times(1)).fail(failureReason, FIXED_INSTANT);
        assertThat(result).isEqualTo(paymentEvent);
    }

    @Test
    @DisplayName("markPaymentAsQuarantined 호출 시 quarantine()을 호출하고 payment_quarantined_total 카운터를 기록한다.")
    void markPaymentAsQuarantined_RecordsQuarantineMetric() {
        // given
        PaymentEvent paymentEvent = Mockito.mock(PaymentEvent.class);
        String reason = "GATEWAY_STATUS_UNKNOWN";
        given(mockPaymentEventRepository.saveOrUpdate(any(PaymentEvent.class)))
                .willReturn(paymentEvent);

        // when
        paymentCommandUseCase.markPaymentAsQuarantined(paymentEvent, reason);

        // then
        then(paymentEvent).should(times(1)).quarantine(reason, FIXED_INSTANT);
        then(mockPaymentQuarantineMetrics).should(times(1)).recordQuarantine(reason);
    }

    @Test
    @DisplayName("결제 만료 처리를 호출하고 성공적으로 만료된 PaymentEvent를 반환한다.")
    void testExpirePayment() {
        // given
        PaymentEvent paymentEvent = Mockito.mock(PaymentEvent.class);
        given(mockPaymentEventRepository.saveOrUpdate(any(PaymentEvent.class)))
                .willReturn(paymentEvent);

        // when
        PaymentEvent result = paymentCommandUseCase.expirePayment(paymentEvent);

        // then
        then(paymentEvent).should(times(1)).expire(FIXED_INSTANT);
        assertThat(result).isEqualTo(paymentEvent);
    }

    @Test
    @DisplayName("markPaymentAsFailFromQuarantine - 도메인 전이 후 CAS 저장 성공 시 이벤트를 반환한다.")
    void markPaymentAsFailFromQuarantine_CasSuccess_ReturnsEvent() {
        // given
        PaymentEvent paymentEvent = Mockito.mock(PaymentEvent.class);
        String reason = "관리자 안전 종결 — 벤더 미캡처 확인";
        given(paymentEvent.getId()).willReturn(1L);
        given(mockPaymentEventRepository.resolveQuarantineToFailed(1L, reason, FIXED_INSTANT))
                .willReturn(true);

        // when
        PaymentEvent result = paymentCommandUseCase.markPaymentAsFailFromQuarantine(paymentEvent, reason);

        // then
        then(paymentEvent).should(times(1)).failFromQuarantine(reason, FIXED_INSTANT);
        then(mockPaymentEventRepository).should(times(1))
                .resolveQuarantineToFailed(1L, reason, FIXED_INSTANT);
        assertThat(result).isEqualTo(paymentEvent);
    }

    @Test
    @DisplayName("markPaymentAsFailFromQuarantine - CAS 0건(충돌) 시 도메인 전이는 수행됐으나 예외를 던진다.")
    void markPaymentAsFailFromQuarantine_CasConflict_ThrowsException() {
        // given
        PaymentEvent paymentEvent = Mockito.mock(PaymentEvent.class);
        String reason = "관리자 안전 종결";
        given(paymentEvent.getId()).willReturn(1L);
        given(mockPaymentEventRepository.resolveQuarantineToFailed(1L, reason, FIXED_INSTANT))
                .willReturn(false);

        // when & then
        assertThatThrownBy(() -> paymentCommandUseCase.markPaymentAsFailFromQuarantine(paymentEvent, reason))
                .isInstanceOf(PaymentStatusException.class)
                .extracting("code")
                .isEqualTo(PaymentErrorCode.QUARANTINE_RESOLVE_CONFLICT.getCode());
        then(paymentEvent).should(times(1)).failFromQuarantine(reason, FIXED_INSTANT);
    }

    @Test
    @DisplayName("markPaymentAsFailFromQuarantine - AOP audit 애노테이션이 부착되어 있다.")
    void markPaymentAsFailFromQuarantine_HasAuditAnnotations() throws NoSuchMethodException {
        // given
        Method method = PaymentCommandUseCase.class.getMethod(
                "markPaymentAsFailFromQuarantine", PaymentEvent.class, String.class);

        // when
        Transactional transactional = method.getAnnotation(Transactional.class);
        PublishDomainEvent publishDomainEvent = method.getAnnotation(PublishDomainEvent.class);
        PaymentStatusChange paymentStatusChange = method.getAnnotation(PaymentStatusChange.class);
        Annotation[][] parameterAnnotations = method.getParameterAnnotations();

        // then
        assertThat(transactional).isNotNull();
        assertThat(publishDomainEvent).isNotNull();
        assertThat(publishDomainEvent.action()).isEqualTo("changed");
        assertThat(paymentStatusChange).isNotNull();
        assertThat(paymentStatusChange.toStatus()).isEqualTo("FAILED");
        assertThat(parameterAnnotations[1])
                .anyMatch(annotation -> annotation instanceof Reason);
    }
}
