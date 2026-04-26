package com.hyoguoo.paymentplatform.payment.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hyoguoo.paymentplatform.payment.application.dto.event.ConfirmedEventMessage;
import com.hyoguoo.paymentplatform.payment.application.event.StockOutboxReadyEvent;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockCachePort;
import com.hyoguoo.paymentplatform.payment.application.util.StockEventUuidDeriver;
import com.hyoguoo.paymentplatform.payment.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.StockOutbox;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOrderStatus;
import com.hyoguoo.paymentplatform.payment.mock.FakeEventDedupeStore;
import com.hyoguoo.paymentplatform.payment.mock.FakePaymentConfirmDlqPublisher;
import com.hyoguoo.paymentplatform.payment.mock.FakePaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.mock.FakeStockOutboxRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

/**
 * PaymentConfirmResultUseCase.handleApproved 단위 검증.
 *
 * <p>커버 범위:
 * <ul>
 *   <li>수신 approvedAt → markPaymentAsDone 으로 그대로 전달</li>
 *   <li>amount 역방향 대조 — 불일치 시 AMOUNT_MISMATCH 격리, DONE 전이 차단</li>
 *   <li>approvedAt null 수신 시 IllegalArgumentException</li>
 *   <li>amount 일치 시 markPaymentAsDone 위임 + stock_outbox INSERT 발생</li>
 *   <li>multi-product 시 stock_outbox 가 productId 별로 따로 INSERT 되고 idempotencyKey 가 분리</li>
 *   <li>상태 전이는 PaymentCommandUseCase 가 단독 수행 — UseCase 가 paymentEventRepository.saveOrUpdate 를 직접 호출하면 안 됨</li>
 * </ul>
 */
@DisplayName("PaymentConfirmResultUseCase handleApproved")
class PaymentConfirmResultUseCaseHandleApprovedTest {

    private static final String ORDER_ID = "order-approved-001";
    private static final String EVENT_UUID = "evt-approved-001";
    private static final String APPROVED_AT_STR = "2026-04-24T10:00:00Z";
    private static final LocalDateTime EXPECTED_APPROVED_AT = LocalDateTime.of(2026, 4, 24, 10, 0, 0);
    private static final long AMOUNT = 1000L;

    private FakePaymentEventRepository paymentEventRepository;
    private FakeEventDedupeStore dedupeStore;
    private CapturingApplicationEventPublisher capturingPublisher;
    private QuarantineCompensationHandler quarantineCompensationHandler;
    private StockCachePort stockCachePort;
    private FakePaymentConfirmDlqPublisher dlqPublisher;
    private FakeStockOutboxRepository stockOutboxRepository;
    private PaymentCommandUseCase paymentCommandUseCase;
    private PaymentConfirmResultUseCase sut;

    @BeforeEach
    void setUp() {
        paymentEventRepository = new FakePaymentEventRepository();
        dedupeStore = new FakeEventDedupeStore();
        capturingPublisher = new CapturingApplicationEventPublisher();
        quarantineCompensationHandler = Mockito.mock(QuarantineCompensationHandler.class);
        stockCachePort = Mockito.mock(StockCachePort.class);
        dlqPublisher = new FakePaymentConfirmDlqPublisher();
        stockOutboxRepository = new FakeStockOutboxRepository();
        paymentCommandUseCase = Mockito.mock(PaymentCommandUseCase.class);

        LocalDateTimeProvider fixedClock = () -> LocalDateTime.of(2026, 4, 24, 12, 0, 0);

        sut = new PaymentConfirmResultUseCase(
                paymentEventRepository,
                dedupeStore,
                capturingPublisher,
                quarantineCompensationHandler,
                fixedClock,
                stockCachePort,
                dlqPublisher,
                stockOutboxRepository,
                new ObjectMapper().registerModule(new JavaTimeModule()),
                PaymentConfirmResultUseCase.DEFAULT_LEASE_TTL,
                PaymentConfirmResultUseCase.DEFAULT_LONG_TTL,
                paymentCommandUseCase
        );
    }

    @Test
    @DisplayName("수신 approvedAt 이 markPaymentAsDone 인자로 그대로 전달된다")
    void 수신_approvedAt_이_markPaymentAsDone_인자로_그대로_전달된다() {
        PaymentOrder order = buildPaymentOrder(1L, 1, BigDecimal.valueOf(AMOUNT));
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order));
        paymentEventRepository.save(event);
        given(paymentCommandUseCase.markPaymentAsDone(any(PaymentEvent.class), any(LocalDateTime.class)))
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
        assertThat(capturingPublisher.countReadyEvents()).isZero();
        assertThat(stockOutboxRepository.savedCount()).isZero();
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
    @DisplayName("amount 일치 시 markPaymentAsDone 위임 + stock_outbox INSERT + StockOutboxReadyEvent 발행")
    void amount_일치_시_markPaymentAsDone_위임_및_stock_outbox_발행() {
        PaymentOrder order = buildPaymentOrder(2L, 1, BigDecimal.valueOf(AMOUNT));
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order));
        paymentEventRepository.save(event);
        given(paymentCommandUseCase.markPaymentAsDone(any(PaymentEvent.class), any(LocalDateTime.class)))
                .willReturn(event);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "APPROVED", null, AMOUNT, APPROVED_AT_STR, EVENT_UUID);

        sut.handle(message);

        then(paymentCommandUseCase)
                .should(times(1))
                .markPaymentAsDone(any(PaymentEvent.class), eq(EXPECTED_APPROVED_AT));
        assertThat(capturingPublisher.countReadyEvents()).isEqualTo(1L);
        assertThat(stockOutboxRepository.savedCount()).isEqualTo(1);
        then(quarantineCompensationHandler).should(never()).handle(any(), any());
    }

    @Test
    @DisplayName("multi-product 시 stock_outbox 가 productId 별로 분리 INSERT 되고 topic 은 stock-committed")
    void multiProduct_시_stock_outbox_가_productId_별로_분리_INSERT() {
        PaymentOrder order1 = buildPaymentOrder(10L, 2, BigDecimal.valueOf(500));
        PaymentOrder order2 = buildPaymentOrder(20L, 3, BigDecimal.valueOf(500));
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order1, order2));
        paymentEventRepository.save(event);
        given(paymentCommandUseCase.markPaymentAsDone(any(PaymentEvent.class), any(LocalDateTime.class)))
                .willReturn(event);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "APPROVED", null, AMOUNT, APPROVED_AT_STR, EVENT_UUID);

        sut.handle(message);

        assertThat(stockOutboxRepository.savedCount()).isEqualTo(2);
        assertThat(capturingPublisher.captured(StockOutboxReadyEvent.class)).hasSize(2);
        List<StockOutbox> saved = stockOutboxRepository.allSaved();
        assertThat(saved).allMatch(o -> "payment.events.stock-committed".equals(o.getTopic()));
        assertThat(saved.stream().map(StockOutbox::getKey).sorted().toList())
                .containsExactly("10", "20");
    }

    @Test
    @DisplayName("multi-product 시 각 stock_outbox payload 의 idempotencyKey 가 productId 단위로 분리된다")
    void multiProduct_idempotencyKey_가_productId_단위로_분리된다() throws Exception {
        PaymentOrder order1 = buildPaymentOrder(10L, 2, BigDecimal.valueOf(500));
        PaymentOrder order2 = buildPaymentOrder(20L, 3, BigDecimal.valueOf(500));
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order1, order2));
        paymentEventRepository.save(event);
        given(paymentCommandUseCase.markPaymentAsDone(any(PaymentEvent.class), any(LocalDateTime.class)))
                .willReturn(event);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "APPROVED", null, AMOUNT, APPROVED_AT_STR, EVENT_UUID);

        sut.handle(message);

        ObjectMapper om = new ObjectMapper().registerModule(new JavaTimeModule());
        List<StockOutbox> saved = stockOutboxRepository.allSaved().stream()
                .sorted(Comparator.comparing(StockOutbox::getKey))
                .toList();
        String key10 = om.readTree(saved.get(0).getPayload()).get("idempotencyKey").asText();
        String key20 = om.readTree(saved.get(1).getPayload()).get("idempotencyKey").asText();

        assertThat(key10).isNotEqualTo(key20);
        assertThat(key10).isEqualTo(StockEventUuidDeriver.derive(ORDER_ID, 10L, "stock-commit"));
        assertThat(key20).isEqualTo(StockEventUuidDeriver.derive(ORDER_ID, 20L, "stock-commit"));
    }

    @Test
    @DisplayName("UseCase 가 paymentEventRepository.saveOrUpdate 를 직접 호출하지 않고 PaymentCommandUseCase 에 위임한다")
    void UseCase_가_paymentEventRepository_saveOrUpdate_를_직접_호출하지_않는다() {
        PaymentOrder order = buildPaymentOrder(1L, 1, BigDecimal.valueOf(AMOUNT));
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order));
        paymentEventRepository.save(event);
        given(paymentCommandUseCase.markPaymentAsDone(any(PaymentEvent.class), any(LocalDateTime.class)))
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

    static class CapturingApplicationEventPublisher implements ApplicationEventPublisher {

        private final List<Object> events = new ArrayList<>();

        @Override
        public void publishEvent(ApplicationEvent event) {
            events.add(event);
        }

        @Override
        public void publishEvent(Object event) {
            events.add(event);
        }

        @SuppressWarnings("unchecked")
        public <T> List<T> captured(Class<T> type) {
            return events.stream()
                    .filter(type::isInstance)
                    .map(e -> (T) e)
                    .toList();
        }

        public long countReadyEvents() {
            return events.stream()
                    .filter(StockOutboxReadyEvent.class::isInstance)
                    .count();
        }
    }
}
