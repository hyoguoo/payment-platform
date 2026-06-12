package com.hyoguoo.paymentplatform.payment.application.usecase;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import com.hyoguoo.paymentplatform.payment.application.dto.event.ConfirmedEventMessage;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockCachePort;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockCompensationAtomicResult;
import com.hyoguoo.paymentplatform.payment.core.common.metrics.PaymentConfirmGuardSkipMetrics;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOrderStatus;
import com.hyoguoo.paymentplatform.payment.mock.FakePaymentEventDedupeStore;
import com.hyoguoo.paymentplatform.payment.mock.FakePaymentEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * ConfirmedEventConsumer(PaymentConfirmResultUseCase) 단위 테스트.
 *
 * <p>APPROVED/FAILED/QUARANTINED 분기를 검증한다.
 * stockCommittedKafkaTemplate.send 로 재고 확정을 직접 발행한다.
 */
@DisplayName("ConfirmedEventConsumerTest")
class ConfirmedEventConsumerTest {

    private static final String ORDER_ID = "order-confirmed-001";
    private static final String EVENT_UUID = "evt-uuid-confirmed-001";

    private FakePaymentEventRepository paymentEventRepository;
    private FakePaymentEventDedupeStore dedupeStore;
    private QuarantineCompensationHandler quarantineCompensationHandler;
    private StockCachePort stockCachePort;
    private PaymentCommandUseCase paymentCommandUseCase;
    @SuppressWarnings("unchecked")
    private KafkaTemplate<String, String> stockCommittedKafkaTemplate;
    private PaymentConfirmResultUseCase sut;

    @BeforeEach
    void setUp() {
        paymentEventRepository = new FakePaymentEventRepository();
        dedupeStore = new FakePaymentEventDedupeStore();
        quarantineCompensationHandler = Mockito.mock(QuarantineCompensationHandler.class);
        stockCachePort = Mockito.mock(StockCachePort.class);
        paymentCommandUseCase = Mockito.mock(PaymentCommandUseCase.class);
        stockCommittedKafkaTemplate = Mockito.mock(KafkaTemplate.class);

        Clock fixedClock = Clock.fixed(
                Instant.parse("2026-04-24T00:00:00Z"), ZoneOffset.UTC);

        sut = new PaymentConfirmResultUseCase(
                paymentEventRepository,
                quarantineCompensationHandler,
                fixedClock,
                stockCachePort,
                dedupeStore,
                stockCommittedKafkaTemplate,
                paymentCommandUseCase,
                new PaymentConfirmGuardSkipMetrics(new SimpleMeterRegistry())
        );
    }

    // -----------------------------------------------------------------------
    // APPROVED → PaymentEvent DONE 전이 + stockCommittedKafkaTemplate.send 발행
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("consume — APPROVED 수신 시 PaymentCommandUseCase.markPaymentAsDone 위임 + send 호출")
    void consume_WhenApproved_ShouldTransitionToDoneAndSend() {
        PaymentOrder order = buildPaymentOrder(1L, 2);
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order));
        paymentEventRepository.save(event);

        given(paymentCommandUseCase.markPaymentAsDone(
                any(PaymentEvent.class),
                any(Instant.class)))
                .willReturn(event);

        // amount=2000(=1000*2), approvedAt non-null — 역방향 방어선 통과 조건
        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "APPROVED", null, 2000L, "2026-04-24T01:00:00Z", EVENT_UUID);

        sut.handle(message);

        then(paymentCommandUseCase)
                .should(times(1))
                .markPaymentAsDone(
                        any(PaymentEvent.class),
                        any(Instant.class));

        then(stockCommittedKafkaTemplate)
                .should(times(1))
                .send(eq("payment.events.stock-committed"), eq("1"), anyString());
    }

    // -----------------------------------------------------------------------
    // FAILED → compensateAtomic 먼저 + markPaymentAsFail 나중
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("consume — FAILED 수신 시 compensateAtomic + markPaymentAsFail 호출")
    void consume_WhenFailed_ShouldCompensateAndTransitionToFailed() {
        PaymentOrder order = buildPaymentOrder(2L, 3);
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order));
        paymentEventRepository.save(event);

        given(stockCachePort.compensateAtomic(eq(ORDER_ID), any()))
                .willReturn(StockCompensationAtomicResult.OK);
        given(paymentCommandUseCase.markPaymentAsFail(
                any(PaymentEvent.class),
                any(String.class)))
                .willReturn(event);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "FAILED", "VENDOR_FAILED", null, null, EVENT_UUID);

        sut.handle(message);

        then(stockCachePort)
                .should(times(1))
                .compensateAtomic(eq(ORDER_ID), any());
        then(paymentCommandUseCase)
                .should(times(1))
                .markPaymentAsFail(
                        any(PaymentEvent.class),
                        eq("VENDOR_FAILED"));
    }

    // -----------------------------------------------------------------------
    // QUARANTINED → QuarantineCompensationHandler.handle 위임
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("consume — QUARANTINED 수신 시 QuarantineCompensationHandler.handle 1회 호출")
    void consume_WhenQuarantined_ShouldDelegateToQuarantineHandler() {
        PaymentOrder order = buildPaymentOrder(3L, 1);
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order));
        paymentEventRepository.save(event);

        given(stockCachePort.compensateAtomic(eq(ORDER_ID), any()))
                .willReturn(StockCompensationAtomicResult.OK);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "QUARANTINED", "RETRY_EXHAUSTED", null, null, EVENT_UUID);

        sut.handle(message);

        then(quarantineCompensationHandler)
                .should(times(1))
                .handle(eq(ORDER_ID), eq("RETRY_EXHAUSTED"));
    }

    // ---- factory helpers ----

    private PaymentEvent buildPaymentEvent(PaymentEventStatus status, List<PaymentOrder> orders) {
        return PaymentEvent.allArgsBuilder()
                .id(1L)
                .buyerId(100L)
                .sellerId(200L)
                .orderName("테스트 상품")
                .orderId(ORDER_ID)
                .paymentKey("pk-001")
                .status(status)
                .retryCount(0)
                .paymentOrderList(orders)
                .allArgsBuild();
    }

    private PaymentOrder buildPaymentOrder(Long productId, int quantity) {
        return PaymentOrder.allArgsBuilder()
                .id(1L)
                .paymentEventId(1L)
                .orderId(ORDER_ID)
                .productId(productId)
                .quantity(quantity)
                .totalAmount(BigDecimal.valueOf(1000L * quantity))
                .status(PaymentOrderStatus.EXECUTING)
                .allArgsBuild();
    }
}
