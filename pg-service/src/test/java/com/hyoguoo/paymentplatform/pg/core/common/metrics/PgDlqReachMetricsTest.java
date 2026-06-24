package com.hyoguoo.paymentplatform.pg.core.common.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PgDlqReachMetrics 단위 테스트")
class PgDlqReachMetricsTest {

    private SimpleMeterRegistry meterRegistry;
    private PgDlqReachMetrics sut;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        sut = new PgDlqReachMetrics(meterRegistry);
    }

    @Test
    @DisplayName("record_호출시_카운터_1증가 — record() 후 counter 1.0 증가")
    void record_호출시_카운터_1증가() {
        sut.record();

        Counter counter = meterRegistry.find("pg_retry_exhausted_quarantine_total").counter();

        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("record_throwFree — record() 반복 호출 시 예외 없이 누적")
    void record_throwFree() {
        assertThatCode(() -> {
            sut.record();
            sut.record();
        }).doesNotThrowAnyException();

        Counter counter = meterRegistry.find("pg_retry_exhausted_quarantine_total").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("생성자_eager등록_0시리즈 — 기동 직후 counter가 0으로 사전 등록됨")
    void 생성자_eager등록_0시리즈() {
        Counter counter = meterRegistry.find("pg_retry_exhausted_quarantine_total").counter();

        assertThat(counter)
                .as("생성자 직후 counter 사전 등록 확인")
                .isNotNull();
        assertThat(counter.count())
                .as("이벤트 0건 상태에서 counter 값 = 0.0")
                .isEqualTo(0.0);
    }
}
