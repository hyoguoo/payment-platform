package com.hyoguoo.paymentplatform.payment.core.common.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("StockRetentionMetrics 단위 테스트")
class StockRetentionMetricsTest {

    private SimpleMeterRegistry meterRegistry;
    private StockRetentionMetrics sut;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        sut = new StockRetentionMetrics(meterRegistry);
    }

    @Test
    @DisplayName("record_unrecoveredStock_counterIncremented — record() 1회 호출 시 counter 1.0 증가")
    void record_unrecoveredStock_counterIncremented() {
        sut.record();

        Counter counter = meterRegistry.find("stock_retention_unrecovered_total").counter();

        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("record_calledTwice_counterAccumulates — record() 2회 호출 시 counter 2.0 누적")
    void record_calledTwice_counterAccumulates() {
        sut.record();
        sut.record();

        Counter counter = meterRegistry.find("stock_retention_unrecovered_total").counter();

        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("constructor_eagerRegister_counterPreRegisteredWithZero — 생성자 직후(record 호출 0건) counter가 0으로 사전 등록됨")
    void constructor_eagerRegister_counterPreRegisteredWithZero() {
        Counter counter = meterRegistry.find("stock_retention_unrecovered_total").counter();

        assertThat(counter)
                .as("생성자 직후 counter 사전 등록 확인")
                .isNotNull();
        assertThat(counter.count())
                .as("이벤트 0건 상태에서 counter 값 = 0.0")
                .isEqualTo(0.0);
    }

    @Test
    @DisplayName("record_neverThrows — record() 호출은 예외를 던지지 않는다")
    void record_neverThrows() {
        assertThatCode(() -> sut.record())
                .doesNotThrowAnyException();
    }
}
