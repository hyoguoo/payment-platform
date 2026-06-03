package com.hyoguoo.paymentplatform.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentEventRepository;
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
 * <p>새 모델: stock 발산 감지/보정 책임이 제거되어 IN_FLIGHT timeout 복원만 담당한다.
 *
 * <p>T11 — D6 만료 2단 연쇄 명문화:
 * "IN_PROGRESS 정체 → Reconciler READY 복원 → 만료 스케줄러 EXPIRED" 연쇄가 의도된 정책임을
 * 단위 테스트로 고정한다. IN_PROGRESS 를 직접 expire() 시도 시 예외가 발생하고,
 * Reconciler 가 READY 로 복원한 뒤에야 만료 대상이 됨을 verify 로 문서화한다.
 */
@DisplayName("PaymentReconciler")
class PaymentReconcilerTest {

    private static final long TIMEOUT_SECONDS = 300;
    private static final Instant FIXED_INSTANT = Instant.parse("2026-04-27T12:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

    private PaymentEventRepository paymentEventRepository;
    private PaymentReconciler reconciler;

    @BeforeEach
    void setUp() {
        paymentEventRepository = Mockito.mock(PaymentEventRepository.class);

        reconciler = new PaymentReconciler(
                paymentEventRepository,
                FIXED_CLOCK,
                TIMEOUT_SECONDS
        );
    }

    @Test
    @DisplayName("stale IN_FLIGHT 가 있으면 READY 로 복원한다.")
    void scan_resetsStaleInFlightRecords() {
        Instant now = FIXED_INSTANT;

        PaymentEvent stale = Mockito.mock(PaymentEvent.class);
        given(paymentEventRepository.findInProgressOlderThan(any())).willReturn(List.of(stale));

        reconciler.scan();

        verify(stale, times(1)).resetToReady(now);
        verify(paymentEventRepository, times(1)).saveOrUpdate(stale);
    }

    @Test
    @DisplayName("stale IN_FLIGHT 가 없으면 saveOrUpdate 가 호출되지 않는다.")
    void scan_whenNoStale_skipsSave() {
        given(paymentEventRepository.findInProgressOlderThan(any())).willReturn(List.of());

        reconciler.scan();

        verify(paymentEventRepository, never()).saveOrUpdate(any());
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

    // ---- T11: 만료 2단 연쇄 명문화 — D6 정책 회귀 가드 ----

    @Test
    @DisplayName("T11 scan — stale IN_PROGRESS 가 있으면 resetToReady(Instant) 가 호출된다. (2단 연쇄 1단계)")
    void scan_staleInProgress_shouldResetToReady() {
        // given — Clock.fixed() 주입, cutoff 초과 IN_PROGRESS 1건 반환
        PaymentEvent staleEvent = Mockito.mock(PaymentEvent.class);
        Instant expectedCutoff = FIXED_INSTANT.minus(Duration.ofSeconds(TIMEOUT_SECONDS));
        given(paymentEventRepository.findInProgressOlderThan(expectedCutoff)).willReturn(List.of(staleEvent));

        // when
        reconciler.scan();

        // then — resetToReady(FIXED_INSTANT) 호출 verify: IN_PROGRESS → READY 복원이 이 시각으로 기록됨
        verify(staleEvent, times(1)).resetToReady(FIXED_INSTANT);
        verify(paymentEventRepository, times(1)).saveOrUpdate(staleEvent);
    }

    @Test
    @DisplayName("T11 scan — stale IN_PROGRESS 가 없으면 saveOrUpdate 가 0회 호출된다. (2단 연쇄 noop)")
    void scan_noStaleRecords_shouldDoNothing() {
        // given — 빈 리스트 반환
        given(paymentEventRepository.findInProgressOlderThan(any())).willReturn(List.of());

        // when
        reconciler.scan();

        // then — saveOrUpdate 0회
        verify(paymentEventRepository, never()).saveOrUpdate(any());
    }
}
