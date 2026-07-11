package com.hyoguoo.paymentplatform.payment.application.usecase;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.hyoguoo.paymentplatform.payment.application.port.out.DlqReprocessPort;
import com.hyoguoo.paymentplatform.payment.core.common.metrics.PaymentDlqReprocessMetrics;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.exception.PaymentValidException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link Clock} 은 mock 대상이 아니라 매 테스트 고정 시각으로 sut 를 새로 구성해야 하므로
 * {@code @InjectMocks} 대신 {@link #newSut()} 헬퍼로 수동 생성한다 (PaymentLoadUseCaseClockTest 선례 패턴).
 */
@DisplayName("DlqReprocessUseCase 테스트")
@ExtendWith(MockitoExtension.class)
class DlqReprocessUseCaseTest {

    private static final String ORDER_ID = "order-dlq-reprocess-001";
    private static final Instant FIXED_INSTANT = Instant.parse("2026-07-11T00:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

    @Mock
    private PaymentLoadUseCase paymentLoadUseCase;

    @Mock
    private DlqReprocessPort dlqReprocessPort;

    @Mock
    private PaymentDlqReprocessMetrics paymentDlqReprocessMetrics;

    private DlqReprocessUseCase newSut() {
        return new DlqReprocessUseCase(paymentLoadUseCase, dlqReprocessPort, paymentDlqReprocessMetrics, FIXED_CLOCK);
    }

    @Test
    @DisplayName("reprocess - DONE 종결 시각 + P8D 초과 건은 차단하고 재주입 포트를 호출하지 않는다")
    void reprocess_WhenDoneAndDedupeWindowExpired_ShouldBlock() {
        // given
        Instant lastStatusChangedAt = FIXED_INSTANT.minus(Duration.ofDays(8)).minus(Duration.ofSeconds(1));
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.DONE, lastStatusChangedAt);
        given(paymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(event);

        DlqReprocessUseCase sut = newSut();

        // when & then
        assertThatThrownBy(() -> sut.reprocess(ORDER_ID))
                .isInstanceOf(PaymentValidException.class)
                .extracting("code")
                .isEqualTo(PaymentErrorCode.DLQ_REPROCESS_AGE_GATE_EXCEEDED.getCode());
        then(dlqReprocessPort).should(never()).reprocess(ORDER_ID);
        then(paymentDlqReprocessMetrics).should(times(1)).recordReprocess("blocked_age_gate");
    }

    @Test
    @DisplayName("reprocess - DONE 종결 시각 + P8D 이내 건은 통과시키고 재주입 포트를 호출한다")
    void reprocess_WhenDoneAndWithinDedupeWindow_ShouldPass() {
        // given
        Instant lastStatusChangedAt = FIXED_INSTANT.minus(Duration.ofDays(7));
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.DONE, lastStatusChangedAt);
        given(paymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(event);

        DlqReprocessUseCase sut = newSut();

        // when
        sut.reprocess(ORDER_ID);

        // then
        then(dlqReprocessPort).should(times(1)).reprocess(ORDER_ID);
        then(paymentDlqReprocessMetrics).should(times(1)).recordReprocess("reprocessed");
    }

    @ParameterizedTest
    @EnumSource(value = PaymentEventStatus.class, names = {"DONE"}, mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("reprocess - DONE 이 아닌 건은 나이 무관 통과시키고 재주입 포트를 호출한다")
    void reprocess_WhenNotDone_ShouldPassRegardlessOfAge(PaymentEventStatus status) {
        // given — 아주 오래 전(P8D 를 한참 초과)이어도 DONE 이 아니면 통과해야 한다
        Instant veryOldChangedAt = FIXED_INSTANT.minus(Duration.ofDays(365));
        PaymentEvent event = buildPaymentEvent(status, veryOldChangedAt);
        given(paymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(event);

        DlqReprocessUseCase sut = newSut();

        // when
        sut.reprocess(ORDER_ID);

        // then
        then(dlqReprocessPort).should(times(1)).reprocess(ORDER_ID);
        then(paymentDlqReprocessMetrics).should(times(1)).recordReprocess("reprocessed");
    }

    // ---- factory helpers ----

    private PaymentEvent buildPaymentEvent(PaymentEventStatus status, Instant lastStatusChangedAt) {
        return PaymentEvent.allArgsBuilder()
                .id(1L)
                .buyerId(100L)
                .sellerId(200L)
                .orderName("테스트 상품")
                .orderId(ORDER_ID)
                .paymentKey("pk-001")
                .status(status)
                .paymentOrderList(List.of())
                .lastStatusChangedAt(lastStatusChangedAt)
                .allArgsBuild();
    }
}
