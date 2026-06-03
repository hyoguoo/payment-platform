package com.hyoguoo.paymentplatform.payment.application.service;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentEventRepository;
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
 * T3 — PaymentReconciler 에서 Clock 주입 기반 시각 소스 전환 검증.
 *
 * <p>Clock.fixed()로 cutoff = now - timeout 이 정확히 계산되는지 verify.
 */
@DisplayName("PaymentReconciler Clock 전환 검증")
class PaymentReconcilerClockTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-06-01T12:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
    private static final long TIMEOUT_SECONDS = 300L;

    private PaymentEventRepository mockRepository;
    private PaymentReconciler reconciler;

    @BeforeEach
    void setUp() {
        mockRepository = Mockito.mock(PaymentEventRepository.class);
        reconciler = new PaymentReconciler(mockRepository, FIXED_CLOCK, TIMEOUT_SECONDS);
        given(mockRepository.findInProgressOlderThan(Mockito.any())).willReturn(List.of());
    }

    @Test
    @DisplayName("scan — Clock.fixed() 주입 시 findInProgressOlderThan(fixedInstant - timeout) 호출됨")
    void scan_withFixedClock_cutoffIsCorrect() {
        reconciler.scan();

        Instant expectedCutoff = FIXED_INSTANT.minus(Duration.ofSeconds(TIMEOUT_SECONDS));
        then(mockRepository).should(times(1)).findInProgressOlderThan(expectedCutoff);
    }
}
