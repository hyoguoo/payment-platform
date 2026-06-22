package com.hyoguoo.paymentplatform.payment.core.common.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PaymentConfirmTerminalResendMetrics 단위 테스트")
class PaymentConfirmTerminalResendMetricsTest {

    private SimpleMeterRegistry meterRegistry;
    private PaymentConfirmTerminalResendMetrics sut;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        sut = new PaymentConfirmTerminalResendMetrics(meterRegistry);
    }

    @Test
    @DisplayName("record_DONE_호출시_카운터_1증가 — record(DONE) 후 counter 1.0 증가")
    void record_DONE_호출시_카운터_1증가() {
        sut.record(PaymentEventStatus.DONE);

        Counter counter = meterRegistry.find("payment_confirm_terminal_resend_total")
                .tag("status", "DONE")
                .counter();

        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("record_null_입력_throwFree_noop — record(null) 호출 시 예외 없이 noop")
    void record_null_입력_throwFree_noop() {
        assertThatCode(() -> sut.record(null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("생성자_eager등록_DONE_0시리즈 — 기동 직후 DONE 라벨 counter가 0으로 사전 등록됨")
    void 생성자_eager등록_DONE_0시리즈() {
        Counter counter = meterRegistry.find("payment_confirm_terminal_resend_total")
                .tag("status", "DONE")
                .counter();

        assertThat(counter)
                .as("생성자 직후 status=DONE 라벨 counter 사전 등록 확인")
                .isNotNull();
        assertThat(counter.count())
                .as("이벤트 0건 상태에서 counter 값 = 0.0")
                .isEqualTo(0.0);
    }
}
