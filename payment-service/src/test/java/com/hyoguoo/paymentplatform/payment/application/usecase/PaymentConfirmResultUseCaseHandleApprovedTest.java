package com.hyoguoo.paymentplatform.payment.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.hyoguoo.paymentplatform.payment.application.dto.event.ConfirmedEventMessage;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockCachePort;
import com.hyoguoo.paymentplatform.payment.application.util.StockEventUuidDeriver;
import com.hyoguoo.paymentplatform.payment.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOrderStatus;
import com.hyoguoo.paymentplatform.payment.mock.FakePaymentEventDedupeStore;
import com.hyoguoo.paymentplatform.payment.mock.FakePaymentEventRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * PaymentConfirmResultUseCase.handleApproved 단위 검증.
 *
 * <p>커버 범위:
 * <ul>
 *   <li>수신 approvedAt → markPaymentAsDone 으로 그대로 전달</li>
 *   <li>amount 역방향 대조 — 불일치 시 AMOUNT_MISMATCH 격리, DONE 전이 차단</li>
 *   <li>approvedAt null 수신 시 IllegalArgumentException</li>
 *   <li>amount 일치 시 markPaymentAsDone 위임 + stockCommittedKafkaTemplate.send 호출</li>
 *   <li>multi-product 시 send 가 productId 별로 분리 호출되고 idempotencyKey 가 분리</li>
 *   <li>상태 전이는 PaymentCommandUseCase 가 단독 수행 — UseCase 가 paymentEventRepository.saveOrUpdate 를 직접 호출하면 안 됨</li>
 * </ul>
 */
@DisplayName("PaymentConfirmResultUseCase handleApproved")
class PaymentConfirmResultUseCaseHandleApprovedTest {

    private static final String ORDER_ID = "order-approved-001";
    private static final String EVENT_UUID = "evt-approved-001";
    private static final String APPROVED_AT_STR = "2026-04-24T10:00:00Z";
    // parseApprovedAt 이 toInstant() 를 사용하므로 오프셋 보존된 Instant 가 기대값
    private static final Instant EXPECTED_APPROVED_AT = Instant.parse("2026-04-24T10:00:00Z");
    private static final long AMOUNT = 1000L;

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

        LocalDateTimeProvider fixedClock = new LocalDateTimeProvider() {
            @Override
            public java.time.LocalDateTime now() {
                return java.time.LocalDateTime.of(2026, 4, 24, 12, 0, 0);
            }

            @Override
            public Instant nowInstant() {
                return Instant.parse("2026-04-24T12:00:00Z");
            }
        };

        sut = new PaymentConfirmResultUseCase(
                paymentEventRepository,
                quarantineCompensationHandler,
                fixedClock,
                stockCachePort,
                dedupeStore,
                stockCommittedKafkaTemplate,
                paymentCommandUseCase
        );
    }

    @Test
    @DisplayName("수신 approvedAt 이 markPaymentAsDone 인자로 그대로 전달된다")
    void 수신_approvedAt_이_markPaymentAsDone_인자로_그대로_전달된다() {
        PaymentOrder order = buildPaymentOrder(1L, 1, BigDecimal.valueOf(AMOUNT));
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order));
        paymentEventRepository.save(event);
        given(paymentCommandUseCase.markPaymentAsDone(any(PaymentEvent.class), any(Instant.class)))
                .willReturn(event);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "APPROVED", null, AMOUNT, APPROVED_AT_STR, EVENT_UUID);

        sut.handle(message);

        then(paymentCommandUseCase)
                .should(times(1))
                .markPaymentAsDone(any(PaymentEvent.class), eq(EXPECTED_APPROVED_AT));
    }

    @Test
    @DisplayName("amount 가 paymentEvent 총액과 다르면 AMOUNT_MISMATCH 로 격리되고 DONE 전이가 일어나지 않는다")
    void amount_불일치_시_AMOUNT_MISMATCH_격리되고_DONE_전이가_일어나지_않는다() {
        PaymentOrder order = buildPaymentOrder(1L, 1, BigDecimal.valueOf(1000));
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order));
        paymentEventRepository.save(event);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "APPROVED", null, 999L, APPROVED_AT_STR, EVENT_UUID);

        sut.handle(message);

        then(quarantineCompensationHandler)
                .should(times(1))
                .handle(eq(ORDER_ID), eq("AMOUNT_MISMATCH"));
        PaymentEvent saved = paymentEventRepository.findByOrderId(ORDER_ID).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(PaymentEventStatus.IN_PROGRESS);
        then(stockCommittedKafkaTemplate).should(never()).send(any(), any(), any());
    }

    @Test
    @DisplayName("approvedAt 이 null 이면 IllegalArgumentException")
    void approvedAt_null_이면_IllegalArgumentException() {
        PaymentOrder order = buildPaymentOrder(1L, 1, BigDecimal.valueOf(AMOUNT));
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order));
        paymentEventRepository.save(event);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "APPROVED", null, AMOUNT, null, EVENT_UUID);

        assertThatThrownBy(() -> sut.handle(message))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("amount 일치 시 markPaymentAsDone 위임 + stockCommittedKafkaTemplate.send 호출")
    void amount_일치_시_markPaymentAsDone_위임_및_stock_committed_발행() {
        PaymentOrder order = buildPaymentOrder(2L, 1, BigDecimal.valueOf(AMOUNT));
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order));
        paymentEventRepository.save(event);
        given(paymentCommandUseCase.markPaymentAsDone(any(PaymentEvent.class), any(Instant.class)))
                .willReturn(event);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "APPROVED", null, AMOUNT, APPROVED_AT_STR, EVENT_UUID);

        sut.handle(message);

        then(paymentCommandUseCase)
                .should(times(1))
                .markPaymentAsDone(any(PaymentEvent.class), eq(EXPECTED_APPROVED_AT));
        then(stockCommittedKafkaTemplate).should(times(1))
                .send(eq("payment.events.stock-committed"), eq("2"), anyString());
        then(quarantineCompensationHandler).should(never()).handle(any(), any());
    }

    @Test
    @DisplayName("multi-product 시 send 가 productId 별로 분리 호출된다 (topic = stock-committed)")
    void multiProduct_시_send_가_productId_별로_분리_호출() {
        PaymentOrder order1 = buildPaymentOrder(10L, 2, BigDecimal.valueOf(500));
        PaymentOrder order2 = buildPaymentOrder(20L, 3, BigDecimal.valueOf(500));
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order1, order2));
        paymentEventRepository.save(event);
        given(paymentCommandUseCase.markPaymentAsDone(any(PaymentEvent.class), any(Instant.class)))
                .willReturn(event);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "APPROVED", null, AMOUNT, APPROVED_AT_STR, EVENT_UUID);

        sut.handle(message);

        then(stockCommittedKafkaTemplate).should(times(2))
                .send(eq("payment.events.stock-committed"), anyString(), anyString());

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        then(stockCommittedKafkaTemplate).should(times(2))
                .send(anyString(), keyCaptor.capture(), anyString());
        assertThat(keyCaptor.getAllValues()).containsExactlyInAnyOrder("10", "20");
    }

    @Test
    @DisplayName("multi-product 시 각 payload 의 idempotencyKey 가 productId 단위로 분리된다")
    void multiProduct_idempotencyKey_가_productId_단위로_분리된다() {
        PaymentOrder order1 = buildPaymentOrder(10L, 2, BigDecimal.valueOf(500));
        PaymentOrder order2 = buildPaymentOrder(20L, 3, BigDecimal.valueOf(500));
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order1, order2));
        paymentEventRepository.save(event);
        given(paymentCommandUseCase.markPaymentAsDone(any(PaymentEvent.class), any(Instant.class)))
                .willReturn(event);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "APPROVED", null, AMOUNT, APPROVED_AT_STR, EVENT_UUID);

        sut.handle(message);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        then(stockCommittedKafkaTemplate).should(times(2))
                .send(anyString(), anyString(), payloadCaptor.capture());

        String expectedKey10 = StockEventUuidDeriver.derive(ORDER_ID, 10L, "stock-commit");
        String expectedKey20 = StockEventUuidDeriver.derive(ORDER_ID, 20L, "stock-commit");
        assertThat(expectedKey10).isNotEqualTo(expectedKey20);

        List<String> payloads = payloadCaptor.getAllValues();
        assertThat(payloads).anyMatch(p -> p.contains(expectedKey10));
        assertThat(payloads).anyMatch(p -> p.contains(expectedKey20));
    }

    @Test
    @DisplayName("UseCase 가 paymentEventRepository.saveOrUpdate 를 직접 호출하지 않고 PaymentCommandUseCase 에 위임한다")
    void UseCase_가_paymentEventRepository_saveOrUpdate_를_직접_호출하지_않는다() {
        PaymentOrder order = buildPaymentOrder(1L, 1, BigDecimal.valueOf(AMOUNT));
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order));
        paymentEventRepository.save(event);
        given(paymentCommandUseCase.markPaymentAsDone(any(PaymentEvent.class), any(Instant.class)))
                .willReturn(event);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "APPROVED", null, AMOUNT, APPROVED_AT_STR, EVENT_UUID);

        int saveCountBefore = paymentEventRepository.saveOrUpdateCallCount();
        sut.handle(message);
        int saveCountAfter = paymentEventRepository.saveOrUpdateCallCount();

        then(paymentCommandUseCase)
                .should(times(1))
                .markPaymentAsDone(any(PaymentEvent.class), eq(EXPECTED_APPROVED_AT));
        assertThat(saveCountAfter - saveCountBefore).isZero();
    }

    private PaymentEvent buildPaymentEvent(PaymentEventStatus status, List<PaymentOrder> orders) {
        return PaymentEvent.allArgsBuilder()
                .id(1L)
                .buyerId(100L)
                .sellerId(200L)
                .orderName("테스트 상품")
                .orderId(ORDER_ID)
                .paymentKey("pk-approved")
                .status(status)
                .retryCount(0)
                .paymentOrderList(orders)
                .allArgsBuild();
    }

    private PaymentOrder buildPaymentOrder(Long productId, int quantity, BigDecimal totalAmount) {
        return PaymentOrder.allArgsBuilder()
                .id(1L)
                .paymentEventId(1L)
                .orderId(ORDER_ID)
                .productId(productId)
                .quantity(quantity)
                .totalAmount(totalAmount)
                .status(PaymentOrderStatus.EXECUTING)
                .allArgsBuild();
    }
}
