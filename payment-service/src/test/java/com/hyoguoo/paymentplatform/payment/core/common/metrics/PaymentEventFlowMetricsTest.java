package com.hyoguoo.paymentplatform.payment.core.common.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PaymentEventFlowMetrics 단위 테스트.
 *
 * <p>검증 범위:
 * <ul>
 *   <li>recordPublished() — payment.event.published 카운터 1.0 증가</li>
 *   <li>recordTerminal() — payment.event.terminal 카운터 1.0 증가</li>
 *   <li>never-throw: null 등 비정상 호출에도 예외 미발생</li>
 *   <li>D7: 라벨 없음(무라벨 카운터)</li>
 * </ul>
 */
@DisplayName("PaymentEventFlowMetrics 단위 테스트")
class PaymentEventFlowMetricsTest {

    private SimpleMeterRegistry meterRegistry;
    private PaymentEventFlowMetrics sut;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        sut = new PaymentEventFlowMetrics(meterRegistry);
    }

    @Test
    @DisplayName("recordPublished_once_counterIncremented — 1회 호출 시 payment_event_published_total 1.0")
    void recordPublished_once_counterIncremented() {
        sut.recordPublished();

        Counter counter = meterRegistry.find("payment.event.published").counter();

        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("recordPublished_twiceCalled_counterTwo — 2회 호출 시 2.0")
    void recordPublished_twiceCalled_counterTwo() {
        sut.recordPublished();
        sut.recordPublished();

        Counter counter = meterRegistry.find("payment.event.published").counter();

        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("recordTerminal_once_counterIncremented — 1회 호출 시 payment_event_terminal_total 1.0")
    void recordTerminal_once_counterIncremented() {
        sut.recordTerminal();

        Counter counter = meterRegistry.find("payment.event.terminal").counter();

        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("recordTerminal_twiceCalled_counterTwo — 2회 호출 시 2.0")
    void recordTerminal_twiceCalled_counterTwo() {
        sut.recordTerminal();
        sut.recordTerminal();

        Counter counter = meterRegistry.find("payment.event.terminal").counter();

        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("recordPublished_noLabels_d7Invariant — published 카운터에 status/orderId/userId 라벨 없음")
    void recordPublished_noLabels_d7Invariant() {
        sut.recordPublished();

        Counter counter = meterRegistry.find("payment.event.published").counter();

        assertThat(counter).isNotNull();
        assertThat(counter.getId().getTags()).isEmpty();
    }

    @Test
    @DisplayName("recordTerminal_noLabels_d7Invariant — terminal 카운터에 status/orderId/userId 라벨 없음")
    void recordTerminal_noLabels_d7Invariant() {
        sut.recordTerminal();

        Counter counter = meterRegistry.find("payment.event.terminal").counter();

        assertThat(counter).isNotNull();
        assertThat(counter.getId().getTags()).isEmpty();
    }

    @Test
    @DisplayName("recordPublished_neverThrows — 연속 호출에도 예외 미발생")
    void recordPublished_neverThrows() {
        assertThatCode(() -> {
            sut.recordPublished();
            sut.recordPublished();
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("recordTerminal_neverThrows — 연속 호출에도 예외 미발생")
    void recordTerminal_neverThrows() {
        assertThatCode(() -> {
            sut.recordTerminal();
            sut.recordTerminal();
        }).doesNotThrowAnyException();
    }
}
