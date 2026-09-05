package com.hyoguoo.paymentplatform.payment.core.common.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.mock.FakePaymentEventRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("PaymentHealthMetrics 테스트")
class PaymentHealthMetricsTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-09-05T12:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
    private static final long AWAITING_RESULT_MINUTES = 10L;

    private SimpleMeterRegistry meterRegistry;
    private FakePaymentEventRepository fakePaymentEventRepository;
    private PaymentHealthMetrics metrics;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        fakePaymentEventRepository = new FakePaymentEventRepository();
        metrics = new PaymentHealthMetrics(meterRegistry, fakePaymentEventRepository, FIXED_CLOCK);
        ReflectionTestUtils.setField(metrics, "awaitingResultMinutes", AWAITING_RESULT_MINUTES);
        metrics.init();
    }

    @Test
    @DisplayName("결과 대기 적체를 센다 — 임계를 넘긴 건만 반영하고 앵커는 상태 변경 시각이다")
    void 결과_대기_적체를_센다() {
        // given: 임계(10분)를 넘긴 결과 대기 1건, 방금 결과 대기로 옮겨간 1건(확정 시작은 오래됐어도 진입은 최근), 다른 상태 1건
        fakePaymentEventRepository.save(awaitingResultWith(1L, "order-stale",
                FIXED_INSTANT.minus(Duration.ofHours(2)), FIXED_INSTANT.minus(Duration.ofMinutes(20))));
        fakePaymentEventRepository.save(awaitingResultWith(2L, "order-recently-moved",
                FIXED_INSTANT.minus(Duration.ofHours(2)), FIXED_INSTANT.minus(Duration.ofMinutes(1))));
        fakePaymentEventRepository.save(inProgressWith(3L, "order-in-progress",
                FIXED_INSTANT.minus(Duration.ofHours(2))));

        // when
        metrics.updateHealthGauges();

        // then: 앵커 기준으로 임계를 넘긴 1건만 게이지에 반영된다
        Gauge gauge = meterRegistry.find("payment_health_stuck_awaiting_result_total").gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isEqualTo(1.0);
    }

    private PaymentEvent awaitingResultWith(Long id, String orderId, Instant executedAt, Instant lastStatusChangedAt) {
        return PaymentEvent.allArgsBuilder()
                .id(id)
                .orderId(orderId)
                .status(PaymentEventStatus.AWAITING_RESULT)
                .executedAt(executedAt)
                .lastStatusChangedAt(lastStatusChangedAt)
                .paymentOrderList(List.of())
                .allArgsBuild();
    }

    private PaymentEvent inProgressWith(Long id, String orderId, Instant executedAt) {
        return PaymentEvent.allArgsBuilder()
                .id(id)
                .orderId(orderId)
                .status(PaymentEventStatus.IN_PROGRESS)
                .executedAt(executedAt)
                .lastStatusChangedAt(executedAt)
                .paymentOrderList(List.of())
                .allArgsBuild();
    }
}
