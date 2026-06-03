package com.hyoguoo.paymentplatform.payment.application.usecase;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.core.common.metrics.PaymentQuarantineMetrics;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * T3 — PaymentCommandUseCase 에서 Clock 주입 기반 시각 소스 전환 검증.
 *
 * <p>Clock.fixed()로 고정된 Instant 를 사용해 도메인 메서드에 동일 Instant 가 전달되는지 verify.
 */
@DisplayName("PaymentCommandUseCase Clock 전환 검증")
class PaymentCommandUseCaseClockTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-06-01T12:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

    private PaymentCommandUseCase sut;
    private PaymentEventRepository mockRepository;
    private PaymentEvent mockEvent;

    @BeforeEach
    void setUp() {
        mockRepository = Mockito.mock(PaymentEventRepository.class);
        PaymentQuarantineMetrics mockMetrics = Mockito.mock(PaymentQuarantineMetrics.class);
        mockEvent = Mockito.mock(PaymentEvent.class);
        given(mockRepository.saveOrUpdate(mockEvent)).willReturn(mockEvent);

        sut = new PaymentCommandUseCase(mockRepository, FIXED_CLOCK, mockMetrics);
    }

    @Test
    @DisplayName("expirePayment — Clock.fixed() 주입 시 domain.expire(fixedInstant) 호출됨")
    void expirePayment_withFixedClock_shouldCallDomainExpire() {
        sut.expirePayment(mockEvent);

        then(mockEvent).should(times(1)).expire(FIXED_INSTANT);
    }

    @Test
    @DisplayName("executePayment — Clock.fixed() 주입 시 domain.execute(key, fixedInstant, fixedInstant) 호출됨")
    void executePayment_withFixedClock_shouldCallDomainExecute() {
        String paymentKey = "pk-test";

        sut.executePayment(mockEvent, paymentKey);

        then(mockEvent).should(times(1)).execute(paymentKey, FIXED_INSTANT, FIXED_INSTANT);
    }
}
