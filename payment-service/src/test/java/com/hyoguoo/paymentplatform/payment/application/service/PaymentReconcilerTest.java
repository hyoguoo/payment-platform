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
 * <p>새 모델: stock 발산 감지/보정 책임이 제거되어 IN_FLIGHT timeout 되돌리기만 담당한다.
 * 되돌린 결제는 READY 가 아닌 결과 대기(AWAITING_RESULT) 로 옮겨져, 뒤늦게 도착하는 확정
 * 결과(완료/실패/격리)를 여전히 받아들일 수 있다. 주문이 EXECUTING 에 남아 있어 만료 대상은
 * 아니다.
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
    @DisplayName("stale IN_FLIGHT 가 있으면 결과 대기로 되돌린다.")
    void scan_resetsStaleInFlightRecords() {
        Instant now = FIXED_INSTANT;

        PaymentEvent stale = Mockito.mock(PaymentEvent.class);
        given(paymentEventRepository.findInProgressOlderThan(any())).willReturn(List.of(stale));

        reconciler.scan();

        verify(stale, times(1)).resetToAwaitingResult(now);
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
}
