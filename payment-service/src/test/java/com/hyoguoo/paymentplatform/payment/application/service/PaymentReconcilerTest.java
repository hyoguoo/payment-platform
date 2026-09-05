package com.hyoguoo.paymentplatform.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentCommandUseCase;
import com.hyoguoo.paymentplatform.payment.core.common.metrics.PaymentReconcilerBatchMetrics;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * PaymentReconciler 단위 테스트.
 *
 * <p>1차 스캔은 IN_FLIGHT timeout 건을 {@link PaymentCommandUseCase#resetPaymentToAwaitingResult}
 * 조건부 전이 위임 메서드로 결과 대기(AWAITING_RESULT) 상태로 옮긴다. 위임 메서드가 null 을
 * 반환하는 것(그 사이 확정된 건과의 경합)과 예외를 던지는 것(격리 대상 실패)을 구분해 처리하며,
 * 한 건의 실패가 나머지 건 처리를 막지 않는다.
 */
@DisplayName("PaymentReconciler")
class PaymentReconcilerTest {

    private static final long TIMEOUT_SECONDS = 300;
    private static final Instant FIXED_INSTANT = Instant.parse("2026-04-27T12:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

    private PaymentEventRepository paymentEventRepository;
    private PaymentCommandUseCase paymentCommandUseCase;
    private PaymentReconcilerBatchMetrics paymentReconcilerBatchMetrics;
    private PaymentReconciler reconciler;

    @BeforeEach
    void setUp() {
        paymentEventRepository = Mockito.mock(PaymentEventRepository.class);
        paymentCommandUseCase = Mockito.mock(PaymentCommandUseCase.class);
        paymentReconcilerBatchMetrics = Mockito.mock(PaymentReconcilerBatchMetrics.class);

        reconciler = new PaymentReconciler(
                paymentEventRepository,
                paymentCommandUseCase,
                paymentReconcilerBatchMetrics,
                FIXED_CLOCK,
                TIMEOUT_SECONDS
        );
    }

    @Test
    @DisplayName("1차 임계를 넘긴 진행 중 결제를 결과 대기로 옮긴다.")
    void scan_movesStaleInProgressPaymentToAwaitingResult() {
        PaymentEvent stale = Mockito.mock(PaymentEvent.class);
        given(stale.getOrderId()).willReturn("order-1");
        given(paymentEventRepository.findInProgressOlderThan(any())).willReturn(List.of(stale));
        given(paymentCommandUseCase.resetPaymentToAwaitingResult(stale)).willReturn(stale);

        reconciler.scan();

        verify(paymentCommandUseCase, times(1)).resetPaymentToAwaitingResult(stale);
        verify(paymentReconcilerBatchMetrics, never()).recordRaceSkip(any());
        verify(paymentReconcilerBatchMetrics, never()).recordFailure(any(), any());
    }

    @Test
    @DisplayName("그 사이 확정된 건은 건너뛴다.")
    void scan_skipsWhenAlreadyConfirmed() {
        PaymentEvent raced = Mockito.mock(PaymentEvent.class);
        given(raced.getOrderId()).willReturn("order-raced");
        given(paymentEventRepository.findInProgressOlderThan(any())).willReturn(List.of(raced));
        given(paymentCommandUseCase.resetPaymentToAwaitingResult(raced)).willReturn(null);

        assertThatCode(() -> reconciler.scan()).doesNotThrowAnyException();

        verify(paymentReconcilerBatchMetrics, times(1)).recordRaceSkip("order-raced");
        verify(paymentReconcilerBatchMetrics, never()).recordFailure(any(), any());
    }

    @Test
    @DisplayName("한 건이 실패해도 나머지를 계속 처리한다.")
    void scan_oneItemFails_doesNotBlockOthers() {
        PaymentEvent failing = Mockito.mock(PaymentEvent.class);
        given(failing.getOrderId()).willReturn("order-failing");
        PaymentEvent normal = Mockito.mock(PaymentEvent.class);
        given(normal.getOrderId()).willReturn("order-normal");

        given(paymentEventRepository.findInProgressOlderThan(any()))
                .willReturn(List.of(failing, normal));
        given(paymentCommandUseCase.resetPaymentToAwaitingResult(failing))
                .willThrow(new IllegalStateException("boom"));
        given(paymentCommandUseCase.resetPaymentToAwaitingResult(normal)).willReturn(normal);

        assertThatCode(() -> reconciler.scan()).doesNotThrowAnyException();

        verify(paymentCommandUseCase, times(1)).resetPaymentToAwaitingResult(failing);
        verify(paymentCommandUseCase, times(1)).resetPaymentToAwaitingResult(normal);
    }

    @Test
    @DisplayName("정상 경합 스킵과 예외 실패를 따로 센다.")
    void scan_countsRaceSkipAndFailureSeparately() {
        PaymentEvent raced = Mockito.mock(PaymentEvent.class);
        given(raced.getOrderId()).willReturn("order-raced");
        PaymentEvent failing = Mockito.mock(PaymentEvent.class);
        given(failing.getOrderId()).willReturn("order-failing");

        given(paymentEventRepository.findInProgressOlderThan(any()))
                .willReturn(List.of(raced, failing));
        given(paymentCommandUseCase.resetPaymentToAwaitingResult(raced)).willReturn(null);
        given(paymentCommandUseCase.resetPaymentToAwaitingResult(failing))
                .willThrow(new IllegalStateException("boom"));

        reconciler.scan();

        verify(paymentReconcilerBatchMetrics, times(1)).recordRaceSkip("order-raced");
        verify(paymentReconcilerBatchMetrics, times(1)).recordFailure(eq("order-failing"), any());
        verify(paymentReconcilerBatchMetrics, never()).recordRaceSkip("order-failing");
        verify(paymentReconcilerBatchMetrics, never()).recordFailure(eq("order-raced"), any());
    }

    @Test
    @DisplayName("stale IN_FLIGHT 가 없으면 위임 메서드가 호출되지 않는다.")
    void scan_whenNoStale_skipsDelegate() {
        given(paymentEventRepository.findInProgressOlderThan(any())).willReturn(List.of());

        reconciler.scan();

        verify(paymentCommandUseCase, never()).resetPaymentToAwaitingResult(any());
    }

    @Test
    @DisplayName("findInProgressOlderThan 의 cutoff 가 now - timeout 이다.")
    void scan_cutoffEqualsNowMinusTimeout() {
        given(paymentEventRepository.findInProgressOlderThan(any())).willReturn(List.of());

        reconciler.scan();

        Instant expectedCutoff = FIXED_INSTANT.minus(Duration.ofSeconds(TIMEOUT_SECONDS));
        verify(paymentEventRepository, times(1)).findInProgressOlderThan(expectedCutoff);
        assertThat(expectedCutoff).isBefore(FIXED_INSTANT);
    }
}
