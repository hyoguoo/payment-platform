package com.hyoguoo.paymentplatform.payment.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.hyoguoo.paymentplatform.payment.application.dto.event.ConfirmedEventMessage;
import com.hyoguoo.paymentplatform.payment.core.common.metrics.PaymentConfirmGuardSkipMetrics;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOrderStatus;
import com.hyoguoo.paymentplatform.payment.mock.FakePaymentEventDedupeStore;
import com.hyoguoo.paymentplatform.payment.mock.FakePaymentEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * PaymentConfirmResultUseCase 종결 상태 가드 스킵 카운터 검증.
 *
 * <p>커버 범위:
 * <ul>
 *   <li>canApplyConfirmResult()==false 상태(DONE/FAILED/CANCELED/PARTIAL_CANCELED/EXPIRED/QUARANTINED) 6종에서
 *       guardSkipMetrics.record() 1회 호출 + counter 1.0</li>
 *   <li>canApplyConfirmResult()==true 상태(READY/IN_PROGRESS/RETRYING)에서 record() 미호출 (가드 통과)</li>
 * </ul>
 */
@DisplayName("PaymentConfirmResultUseCase 가드 스킵 카운터")
class PaymentConfirmResultUseCaseGuardSkipTest {

    private static final String ORDER_ID = "order-guard-skip-001";
    private static final String EVENT_UUID = "evt-guard-skip-001";
    private static final String APPROVED_AT_STR = "2026-04-24T10:00:00Z";
    private static final long AMOUNT = 1000L;

    private FakePaymentEventRepository paymentEventRepository;
    private FakePaymentEventDedupeStore dedupeStore;
    private SimpleMeterRegistry meterRegistry;
    private PaymentConfirmGuardSkipMetrics guardSkipMetrics;
    @SuppressWarnings("unchecked")
    private KafkaTemplate<String, String> stockCommittedKafkaTemplate = Mockito.mock(KafkaTemplate.class);
    private PaymentConfirmResultUseCase sut;

    @BeforeEach
    void setUp() {
        paymentEventRepository = new FakePaymentEventRepository();
        dedupeStore = new FakePaymentEventDedupeStore();
        meterRegistry = new SimpleMeterRegistry();
        guardSkipMetrics = Mockito.spy(new PaymentConfirmGuardSkipMetrics(meterRegistry));
        stockCommittedKafkaTemplate = Mockito.mock(KafkaTemplate.class);

        Clock fixedClock = Clock.fixed(
                Instant.parse("2026-04-24T12:00:00Z"), ZoneOffset.UTC);

        sut = new PaymentConfirmResultUseCase(
                paymentEventRepository,
                Mockito.mock(QuarantineCompensationHandler.class),
                fixedClock,
                Mockito.mock(com.hyoguoo.paymentplatform.payment.application.port.out.StockCachePort.class),
                dedupeStore,
                stockCommittedKafkaTemplate,
                Mockito.mock(PaymentCommandUseCase.class),
                guardSkipMetrics
        );
    }

    @ParameterizedTest(name = "handle_terminalStatus_guardSkipCounterIncremented — {0} 상태 진입 시 record() 1회 + counter 1.0")
    @EnumSource(value = PaymentEventStatus.class, names = {
            "DONE", "FAILED", "CANCELED", "PARTIAL_CANCELED", "EXPIRED", "QUARANTINED"
    })
    @DisplayName("handle_terminalStatus_guardSkipCounterIncremented — 가드 false 6종에서 record() 1회 호출 + counter 1.0")
    void handle_terminalStatus_guardSkipCounterIncremented(PaymentEventStatus status) {
        PaymentEvent event = buildPaymentEvent(status);
        paymentEventRepository.save(event);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "APPROVED", null, AMOUNT, APPROVED_AT_STR, EVENT_UUID);

        sut.handle(message);

        then(guardSkipMetrics).should(times(1)).record(status);

        Counter counter = meterRegistry.find("payment_confirm_guard_skip_total")
                .tag("status", status.name())
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @ParameterizedTest(name = "handle_nonTerminalStatus_guardSkipCounterNotCalled — {0} 상태는 record() 미호출")
    @EnumSource(value = PaymentEventStatus.class, names = {"READY", "IN_PROGRESS", "RETRYING"})
    @DisplayName("handle_nonTerminalStatus_guardSkipCounterNotCalled — 가드 true 3종에서 record() 0회 호출")
    void handle_nonTerminalStatus_guardSkipCounterNotCalled(PaymentEventStatus status) {
        PaymentEvent event = buildPaymentEvent(status);
        paymentEventRepository.save(event);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "APPROVED", null, AMOUNT, APPROVED_AT_STR, EVENT_UUID);

        sut.handle(message);

        then(guardSkipMetrics).should(never()).record(any());
    }

    // ---- factory helpers ----

    private PaymentEvent buildPaymentEvent(PaymentEventStatus status) {
        PaymentOrder order = PaymentOrder.allArgsBuilder()
                .id(1L)
                .paymentEventId(1L)
                .orderId(ORDER_ID)
                .productId(1L)
                .quantity(1)
                .totalAmount(BigDecimal.valueOf(AMOUNT))
                .status(PaymentOrderStatus.EXECUTING)
                .allArgsBuild();

        return PaymentEvent.allArgsBuilder()
                .id(1L)
                .buyerId(100L)
                .sellerId(200L)
                .orderName("테스트 상품")
                .orderId(ORDER_ID)
                .paymentKey("pk-guard-skip-001")
                .status(status)
                .retryCount(0)
                .paymentOrderList(List.of(order))
                .allArgsBuild();
    }
}
