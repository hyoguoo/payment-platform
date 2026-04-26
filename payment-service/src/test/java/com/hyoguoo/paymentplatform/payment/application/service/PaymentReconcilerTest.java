package com.hyoguoo.paymentplatform.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * PaymentReconciler 단위 테스트.
 *
 * <p>새 모델: stock 발산 감지/보정 책임이 제거되어 IN_FLIGHT timeout 복원만 담당한다.
 */
@DisplayName("PaymentReconciler")
class PaymentReconcilerTest {

    private static final long TIMEOUT_SECONDS = 300;

    private PaymentEventRepository paymentEventRepository;
    private LocalDateTimeProvider localDateTimeProvider;
    private PaymentReconciler reconciler;

    @BeforeEach
    void setUp() {
        paymentEventRepository = Mockito.mock(PaymentEventRepository.class);
        localDateTimeProvider = Mockito.mock(LocalDateTimeProvider.class);

        reconciler = new PaymentReconciler(
                paymentEventRepository,
                localDateTimeProvider,
                TIMEOUT_SECONDS
        );
    }

    @Test
    @DisplayName("stale IN_FLIGHT 가 있으면 READY 로 복원한다.")
    void scan_resetsStaleInFlightRecords() {
        LocalDateTime now = LocalDateTime.of(2026, 4, 27, 12, 0, 0);
        when(localDateTimeProvider.now()).thenReturn(now);

        PaymentEvent stale = Mockito.mock(PaymentEvent.class);
        given(paymentEventRepository.findInProgressOlderThan(any())).willReturn(List.of(stale));

        reconciler.scan();

        verify(stale, times(1)).resetToReady(now);
        verify(paymentEventRepository, times(1)).saveOrUpdate(stale);
    }

    @Test
    @DisplayName("stale IN_FLIGHT 가 없으면 saveOrUpdate 가 호출되지 않는다.")
    void scan_whenNoStale_skipsSave() {
        LocalDateTime now = LocalDateTime.of(2026, 4, 27, 12, 0, 0);
        when(localDateTimeProvider.now()).thenReturn(now);
        given(paymentEventRepository.findInProgressOlderThan(any())).willReturn(List.of());

        reconciler.scan();

        verify(paymentEventRepository, never()).saveOrUpdate(any());
    }

    @Test
    @DisplayName("findInProgressOlderThan 의 cutoff 가 now - timeout 이다.")
    void scan_cutoffEqualsNowMinusTimeout() {
        LocalDateTime now = LocalDateTime.of(2026, 4, 27, 12, 0, 0);
        when(localDateTimeProvider.now()).thenReturn(now);
        given(paymentEventRepository.findInProgressOlderThan(any())).willReturn(List.of());

        reconciler.scan();

        LocalDateTime expectedCutoff = now.minusSeconds(TIMEOUT_SECONDS);
        verify(paymentEventRepository, times(1)).findInProgressOlderThan(expectedCutoff);
        assertThat(expectedCutoff).isBefore(now);
    }
}
