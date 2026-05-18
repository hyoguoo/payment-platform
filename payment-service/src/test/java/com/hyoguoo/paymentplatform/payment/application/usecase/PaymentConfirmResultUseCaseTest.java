package com.hyoguoo.paymentplatform.payment.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.hyoguoo.paymentplatform.payment.application.dto.event.ConfirmedEventMessage;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockCachePort;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockCompensationAtomicResult;
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
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * PaymentConfirmResultUseCase PET-8 EOS 재작성 단위 검증.
 *
 * <p>커버 범위 (PLAN PET-8 명세):
 * <ul>
 *   <li>D7 진입 가드 — isCompensatableByFailureHandler false(종결 상태) → markIfAbsent 미호출 + warn noop</li>
 *   <li>멱등 마킹 0 row — 비즈니스 skip (markPaymentAsDone 미호출)</li>
 *   <li>멱등 마킹 0 row 에도 발행은 항상 진행 (위키 line 141)</li>
 *   <li>multi-product 결제 — PaymentOrder 수만큼 send 호출, 각 idempotencyKey 결정성 (DR-1)</li>
 *   <li>FAILED 보상 순서 보존 — compensateAtomic 먼저, markPaymentAsFail 나중 (DR-7)</li>
 *   <li>APPROVED 정상 — markPaymentAsDone + send loop 호출</li>
 *   <li>amount 불일치 — quarantineCompensationHandler 위임 (markPaymentAsDone 미호출)</li>
 * </ul>
 */
@DisplayName("PaymentConfirmResultUseCase PET-8 EOS 재작성")
class PaymentConfirmResultUseCaseTest {

    private static final String ORDER_ID = "order-eos-001";
    private static final String EVENT_UUID = "evt-eos-001";
    private static final String APPROVED_AT_STR = "2026-04-24T10:00:00Z";
    private static final long AMOUNT = 1000L;

    private FakePaymentEventRepository paymentEventRepository;
    private FakePaymentEventDedupeStore dedupeStore;
    private QuarantineCompensationHandler quarantineCompensationHandler;
    private StockCachePort stockCachePort;
    private PaymentCommandUseCase paymentCommandUseCase;
    @SuppressWarnings("unchecked")
    private KafkaTemplate<String, String> stockCommittedKafkaTemplate = Mockito.mock(KafkaTemplate.class);
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
            public LocalDateTime now() {
                return LocalDateTime.of(2026, 4, 24, 12, 0, 0);
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

    // ---- D7 진입 가드 (DR-3) ----

    @Test
    @DisplayName("shouldSkipWhenStatusIsNotProceedable — QUARANTINED(종결) 상태면 markIfAbsent 미호출 + warn noop")
    void shouldSkipWhenStatusIsNotProceedable() {
        PaymentOrder order = buildPaymentOrder(1L, 1, BigDecimal.valueOf(AMOUNT));
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.QUARANTINED, List.of(order));
        paymentEventRepository.save(event);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "APPROVED", null, AMOUNT, APPROVED_AT_STR, EVENT_UUID);

        sut.handle(message);

        assertThat(dedupeStore.size()).isZero();
        then(paymentCommandUseCase).should(never()).markPaymentAsDone(any(), any());
        then(stockCommittedKafkaTemplate).should(never()).send(any(), any(), any());
    }

    @Test
    @DisplayName("shouldProceedBusinessWhenStatusIsProceedable — IN_PROGRESS 상태면 markIfAbsent 호출됨")
    void shouldProceedBusinessWhenStatusIsProceedable() {
        PaymentOrder order = buildPaymentOrder(1L, 1, BigDecimal.valueOf(AMOUNT));
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order));
        paymentEventRepository.save(event);
        given(paymentCommandUseCase.markPaymentAsDone(any(), any())).willReturn(event);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "APPROVED", null, AMOUNT, APPROVED_AT_STR, EVENT_UUID);

        sut.handle(message);

        assertThat(dedupeStore.size()).isEqualTo(1);
        assertThat(dedupeStore.contains(EVENT_UUID)).isTrue();
    }

    // ---- 멱등 마킹 0 row (DR-5) ----

    @Test
    @DisplayName("shouldSkipBusinessWhenMarkIfAbsentReturnsZero — 0 row 시 markPaymentAsDone 미호출")
    void shouldSkipBusinessWhenMarkIfAbsentReturnsZero() {
        PaymentOrder order = buildPaymentOrder(1L, 1, BigDecimal.valueOf(AMOUNT));
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order));
        paymentEventRepository.save(event);

        // 먼저 같은 eventUuid 로 한 번 마킹해 0 row 상태 만들기
        dedupeStore.markIfAbsent(EVENT_UUID, 1L, "APPROVED", Instant.now());

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "APPROVED", null, AMOUNT, APPROVED_AT_STR, EVENT_UUID);

        sut.handle(message);

        then(paymentCommandUseCase).should(never()).markPaymentAsDone(any(), any());
    }

    @Test
    @DisplayName("shouldSkipBusinessButAlwaysSendWhenMarkIfAbsentReturnsZero — 0 row 에도 발행은 항상 진행 (위키 line 141)")
    void shouldSkipBusinessButAlwaysSendWhenMarkIfAbsentReturnsZero() {
        PaymentOrder order = buildPaymentOrder(1L, 1, BigDecimal.valueOf(AMOUNT));
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order));
        paymentEventRepository.save(event);

        // 중복 상태 만들기
        dedupeStore.markIfAbsent(EVENT_UUID, 1L, "APPROVED", Instant.now());

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "APPROVED", null, AMOUNT, APPROVED_AT_STR, EVENT_UUID);

        sut.handle(message);

        // 비즈니스 skip 이지만 발행은 진행
        then(paymentCommandUseCase).should(never()).markPaymentAsDone(any(), any());
        then(stockCommittedKafkaTemplate).should(times(1))
                .send(eq("payment.events.stock-committed"), eq("1"), anyString());
    }

    // ---- multi-product 결정성 (DR-1) ----

    @Test
    @DisplayName("shouldDeriveDistinctIdempotencyKeyPerProduct — PaymentOrder 2건 → send 2회, 각 idempotencyKey 결정성")
    void shouldDeriveDistinctIdempotencyKeyPerProduct() throws Exception {
        PaymentOrder order1 = buildPaymentOrder(10L, 2, BigDecimal.valueOf(500));
        PaymentOrder order2 = buildPaymentOrder(20L, 3, BigDecimal.valueOf(500));
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order1, order2));
        paymentEventRepository.save(event);
        given(paymentCommandUseCase.markPaymentAsDone(any(), any())).willReturn(event);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "APPROVED", null, AMOUNT, APPROVED_AT_STR, EVENT_UUID);

        sut.handle(message);

        // send 2회 호출 확인
        then(stockCommittedKafkaTemplate).should(times(2))
                .send(eq("payment.events.stock-committed"), anyString(), anyString());

        // 각 호출의 payload 에서 idempotencyKey 결정성 검증
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        then(stockCommittedKafkaTemplate).should(times(2))
                .send(anyString(), keyCaptor.capture(), payloadCaptor.capture());

        List<String> keys = keyCaptor.getAllValues();
        assertThat(keys).containsExactlyInAnyOrder("10", "20");

        // payload JSON 에서 idempotencyKey 추출해 결정성 확인
        String expectedKey10 = StockEventUuidDeriver.derive(ORDER_ID, 10L, "stock-commit");
        String expectedKey20 = StockEventUuidDeriver.derive(ORDER_ID, 20L, "stock-commit");
        assertThat(expectedKey10).isNotEqualTo(expectedKey20);

        List<String> payloads = payloadCaptor.getAllValues();
        assertThat(payloads).anyMatch(p -> p.contains(expectedKey10));
        assertThat(payloads).anyMatch(p -> p.contains(expectedKey20));
    }

    // ---- FAILED 보상 순서 보존 (DR-7) ----

    @Test
    @DisplayName("shouldMaintainCompensationOrderForFailed — compensateAtomic 먼저, markPaymentAsFail 나중")
    void shouldMaintainCompensationOrderForFailed() {
        PaymentOrder order = buildPaymentOrder(100L, 3, BigDecimal.valueOf(300));
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order));
        paymentEventRepository.save(event);

        given(stockCachePort.compensateAtomic(eq(ORDER_ID), any()))
                .willReturn(StockCompensationAtomicResult.OK);
        given(paymentCommandUseCase.markPaymentAsFail(any(), anyString())).willReturn(event);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "FAILED", "VENDOR_FAILED", null, null, EVENT_UUID);

        sut.handle(message);

        InOrder inOrder = inOrder(stockCachePort, paymentCommandUseCase);
        inOrder.verify(stockCachePort).compensateAtomic(eq(ORDER_ID), any());
        inOrder.verify(paymentCommandUseCase).markPaymentAsFail(any(PaymentEvent.class), eq("VENDOR_FAILED"));
    }

    // ---- APPROVED 정상 (1 row) ----

    @Test
    @DisplayName("shouldProceedBusinessWhenMarkIfAbsentReturnsOne — 1 row → markPaymentAsDone + send loop 호출")
    void shouldProceedBusinessWhenMarkIfAbsentReturnsOne() {
        PaymentOrder order = buildPaymentOrder(1L, 1, BigDecimal.valueOf(AMOUNT));
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order));
        paymentEventRepository.save(event);
        given(paymentCommandUseCase.markPaymentAsDone(any(), any())).willReturn(event);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "APPROVED", null, AMOUNT, APPROVED_AT_STR, EVENT_UUID);

        sut.handle(message);

        then(paymentCommandUseCase).should(times(1)).markPaymentAsDone(any(), any());
        then(stockCommittedKafkaTemplate).should(times(1))
                .send(eq("payment.events.stock-committed"), eq("1"), anyString());
    }

    // ---- amount 불일치 격리 ----

    @Test
    @DisplayName("shouldQuarantineOnAmountMismatch — amount 불일치 시 quarantineCompensationHandler 위임")
    void shouldQuarantineOnAmountMismatch() {
        PaymentOrder order = buildPaymentOrder(1L, 1, BigDecimal.valueOf(1000));
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order));
        paymentEventRepository.save(event);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "APPROVED", null, 999L, APPROVED_AT_STR, EVENT_UUID);

        sut.handle(message);

        then(quarantineCompensationHandler).should(times(1))
                .handle(eq(ORDER_ID), eq("AMOUNT_MISMATCH"));
        then(paymentCommandUseCase).should(never()).markPaymentAsDone(any(), any());
        then(stockCommittedKafkaTemplate).should(never()).send(any(), any(), any());
    }

    // ---- factory helpers ----

    private PaymentEvent buildPaymentEvent(PaymentEventStatus status, List<PaymentOrder> orders) {
        return PaymentEvent.allArgsBuilder()
                .id(1L)
                .buyerId(100L)
                .sellerId(200L)
                .orderName("테스트 상품")
                .orderId(ORDER_ID)
                .paymentKey("pk-eos-001")
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
