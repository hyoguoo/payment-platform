package com.hyoguoo.paymentplatform.payment.core.common.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("PaymentConfirmGuardSkipMetrics 단위 테스트")
class PaymentConfirmGuardSkipMetricsTest {

    private SimpleMeterRegistry meterRegistry;
    private PaymentConfirmGuardSkipMetrics sut;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        sut = new PaymentConfirmGuardSkipMetrics(meterRegistry);
    }

    @Test
    @DisplayName("record_terminalStatus_counterIncremented — DONE status로 record() 호출 시 counter 1.0 증가")
    void record_terminalStatus_counterIncremented() {
        sut.record(PaymentEventStatus.DONE);

        Counter counter = meterRegistry.find("payment_confirm_guard_skip_total")
                .tag("status", "DONE")
                .counter();

        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("record_differentStatuses_separateCounters — DONE/FAILED 각 1회 호출 시 독립 집계")
    void record_differentStatuses_separateCounters() {
        sut.record(PaymentEventStatus.DONE);
        sut.record(PaymentEventStatus.FAILED);

        Counter doneCounter = meterRegistry.find("payment_confirm_guard_skip_total")
                .tag("status", "DONE")
                .counter();
        Counter failedCounter = meterRegistry.find("payment_confirm_guard_skip_total")
                .tag("status", "FAILED")
                .counter();

        assertThat(doneCounter).isNotNull();
        assertThat(doneCounter.count()).isEqualTo(1.0);
        assertThat(failedCounter).isNotNull();
        assertThat(failedCounter.count()).isEqualTo(1.0);
    }

    @ParameterizedTest(name = "record_allSixTerminalStatuses_allRegistered — {0} status counter 증가")
    @EnumSource(value = PaymentEventStatus.class, names = {
            "DONE", "FAILED", "CANCELED", "PARTIAL_CANCELED", "EXPIRED", "QUARANTINED"
    })
    @DisplayName("record_allSixTerminalStatuses_allRegistered — 가드 false 6종 각각 counter 1.0 증가")
    void record_allSixTerminalStatuses_allRegistered(PaymentEventStatus status) {
        sut.record(status);

        Counter counter = meterRegistry.find("payment_confirm_guard_skip_total")
                .tag("status", status.name())
                .counter();

        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("record_counterTagKeysOnlyStatus — 태그 키 집합이 정확히 {status} (D7 불변식: orderId/userId 미포함)")
    void record_counterTagKeysOnlyStatus() {
        sut.record(PaymentEventStatus.DONE);

        Meter meter = meterRegistry.find("payment_confirm_guard_skip_total")
                .tag("status", "DONE")
                .meter();

        assertThat(meter).isNotNull();

        List<String> tagKeys = meter.getId().getTags().stream()
                .map(io.micrometer.core.instrument.Tag::getKey)
                .collect(Collectors.toList());

        assertThat(tagKeys).containsExactly("status");
        assertThat(tagKeys).doesNotContain("orderId", "userId");
    }

    @Test
    @DisplayName("record_neverThrows — null 등 비정상 입력에도 예외 미발생(noop/무시)")
    void record_neverThrows() {
        assertThatCode(() -> sut.record(null))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "constructor_eagerRegister_{0} — 생성자 호출 직후(이벤트 0건) {0} 라벨 counter 존재(값 0)")
    @ValueSource(strings = {"DONE", "FAILED", "CANCELED", "PARTIAL_CANCELED", "EXPIRED", "QUARANTINED"})
    @DisplayName("constructor_eagerRegister — 가드 false 6종 생성자 직후 counter 시리즈가 모두 값 0으로 사전 등록됨")
    void constructor_eagerRegister_allSixLabelsPreRegisteredWithZero(String statusName) {
        // 생성자 직후 record() 호출 없이 카운터가 등록돼 있어야 한다 (eager 등록)
        Counter counter = meterRegistry.find("payment_confirm_guard_skip_total")
                .tag("status", statusName)
                .counter();

        assertThat(counter)
                .as("생성자 직후 status=%s 라벨 counter 사전 등록 확인", statusName)
                .isNotNull();
        assertThat(counter.count())
                .as("이벤트 0건 상태에서 counter 값 = 0.0")
                .isEqualTo(0.0);
    }
}
