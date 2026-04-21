package com.hyoguoo.paymentplatform.payment.infrastructure.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("StockCacheDivergenceMetrics 테스트")
class StockCacheDivergenceMetricsTest {

    private SimpleMeterRegistry meterRegistry;
    private StockCacheDivergenceMetrics metrics;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        metrics = new StockCacheDivergenceMetrics(meterRegistry);
    }

    @Test
    @DisplayName("increment - 호출 시 divergence counter가 1 증가한다")
    void increment_ShouldIncreaseDivergenceCounter() {
        // given: counter 초기값 0

        // when
        metrics.increment();

        // then: counter = 1
        Counter counter = meterRegistry.find("payment.stock_cache.divergence_count").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("noDivergence - increment를 호출하지 않으면 counter가 증가하지 않는다")
    void noDivergence_ShouldNotIncrementCounter() {
        // given: increment 미호출

        // when: 아무것도 하지 않음

        // then: counter가 없거나 0
        Counter counter = meterRegistry.find("payment.stock_cache.divergence_count").counter();
        if (counter != null) {
            assertThat(counter.count()).isEqualTo(0.0);
        }
    }
}
