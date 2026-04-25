package com.hyoguoo.paymentplatform.payment.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hyoguoo.paymentplatform.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.payment.application.event.StockOutboxReadyEvent;
import com.hyoguoo.paymentplatform.payment.application.service.FailureCompensationService;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOrderStatus;
import com.hyoguoo.paymentplatform.payment.application.dto.event.ConfirmedEventMessage;
import com.hyoguoo.paymentplatform.payment.mock.FakeEventDedupeStore;
import com.hyoguoo.paymentplatform.payment.mock.FakePaymentConfirmDlqPublisher;
import com.hyoguoo.paymentplatform.payment.mock.FakePaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.mock.FakeStockOutboxRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

/**
 * T-A2: handleApproved 수신 approvedAt 주입 + amount 역방향 대조 RED 테스트.
 * ADR-15 AMOUNT_MISMATCH 역방향 방어선 검증.
 *
 * <p>T-B1: handleFailed 실 qty FailureCompensationService 경유 RED 테스트.
 * FAILED 결제 재고 복원 실 수량 전달 + 레거시 publish 미호출 검증.
 *
 * <p>T-J1: StockCommitRequestedEvent → StockOutboxReadyEvent + stock_outbox INSERT 전환.
 */
@DisplayName("PaymentConfirmResultUseCaseTest — T-A2 역방향 방어선 + T-B1 handleFailed 실 qty")
class PaymentConfirmResultUseCaseTest {

    private static final String ORDER_ID = "order-ta2-001";
    private static final String EVENT_UUID = "evt-ta2-001";
    private static final String APPROVED_AT_STR = "2026-04-24T10:00:00Z";
    private static final LocalDateTime EXPECTED_APPROVED_AT = LocalDateTime.of(2026, 4, 24, 10, 0, 0);
    private static final long AMOUNT = 1000L;

    private FakePaymentEventRepository paymentEventRepository;
    private FakeEventDedupeStore dedupeStore;
    private CapturingApplicationEventPublisher capturingPublisher;
    private QuarantineCompensationHandler quarantineCompensationHandler;
    private FailureCompensationService failureCompensationService;
    private FakePaymentConfirmDlqPublisher dlqPublisher;
    private FakeStockOutboxRepository stockOutboxRepository;
    private PaymentConfirmResultUseCase sut;

    @BeforeEach
    void setUp() {
        paymentEventRepository = new FakePaymentEventRepository();
        dedupeStore = new FakeEventDedupeStore();
        capturingPublisher = new CapturingApplicationEventPublisher();
        quarantineCompensationHandler = Mockito.mock(QuarantineCompensationHandler.class);
        failureCompensationService = Mockito.mock(FailureCompensationService.class);
        dlqPublisher = new FakePaymentConfirmDlqPublisher();
        stockOutboxRepository = new FakeStockOutboxRepository();

        LocalDateTimeProvider fixedClock = () -> LocalDateTime.of(2026, 4, 24, 12, 0, 0);

        sut = new PaymentConfirmResultUseCase(
                paymentEventRepository,
                dedupeStore,
                capturingPublisher,
                quarantineCompensationHandler,
                fixedClock,
                failureCompensationService,
                dlqPublisher,
                stockOutboxRepository,
                new ObjectMapper().registerModule(new JavaTimeModule()),
                PaymentConfirmResultUseCase.DEFAULT_LEASE_TTL,
                PaymentConfirmResultUseCase.DEFAULT_LONG_TTL
        );
    }

    // ---- helper: ApplicationEventPublisher that captures events ----

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
                    .filter(e -> e instanceof StockOutboxReadyEvent)
                    .count();
        }
    }

    // -----------------------------------------------------------------------
    // TC-A2-1: APPROVED 수신 approvedAt → PaymentEvent.done(receivedApprovedAt, ...)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("handleApproved — 수신 approvedAt이 PaymentEvent.done에 주입된다")
    void handleApproved_whenReceivedApprovedAt_shouldPassToPaymentEventDone() {
        // given
        PaymentOrder order = buildPaymentOrder(1L, 1, BigDecimal.valueOf(AMOUNT));
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order));
        paymentEventRepository.save(event);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "APPROVED", null, AMOUNT, APPROVED_AT_STR, EVENT_UUID
        );

        // when
        sut.handle(message);

        // then — PaymentEvent.approvedAt이 수신값(UTC→LDT 변환)과 일치
        PaymentEvent saved = paymentEventRepository.findByOrderId(ORDER_ID).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(PaymentEventStatus.DONE);
        assertThat(saved.getApprovedAt()).isEqualTo(EXPECTED_APPROVED_AT);
    }

    // -----------------------------------------------------------------------
    // TC-A2-2: amount 불일치 → QUARANTINED(AMOUNT_MISMATCH), done 미호출
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("handleApproved — amount 불일치 시 AMOUNT_MISMATCH로 격리, done 미호출")
    void handleApproved_whenAmountMismatch_shouldQuarantine() {
        // given: paymentEvent 총액 1000, 수신 amount=999 → 불일치
        PaymentOrder order = buildPaymentOrder(1L, 1, BigDecimal.valueOf(1000));
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order));
        paymentEventRepository.save(event);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "APPROVED", null, 999L, APPROVED_AT_STR, EVENT_UUID
        );

        // when
        sut.handle(message);

        // then — QuarantineCompensationHandler가 AMOUNT_MISMATCH로 호출됨
        then(quarantineCompensationHandler)
                .should(times(1))
                .handle(eq(ORDER_ID), eq("AMOUNT_MISMATCH"));

        // then — PaymentEvent는 DONE 전이 없음 (IN_PROGRESS 유지)
        PaymentEvent saved = paymentEventRepository.findByOrderId(ORDER_ID).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(PaymentEventStatus.IN_PROGRESS);

        // then — stock outbox 미발행
        assertThat(capturingPublisher.countReadyEvents()).isEqualTo(0L);
        assertThat(stockOutboxRepository.savedCount()).isEqualTo(0);
    }

    // -----------------------------------------------------------------------
    // TC-A2-3: approvedAt=null → IllegalArgumentException
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("handleApproved — approvedAt=null 수신 시 IllegalArgumentException throw")
    void handleApproved_whenApprovedAtNull_shouldThrow() {
        // given
        PaymentOrder order = buildPaymentOrder(1L, 1, BigDecimal.valueOf(AMOUNT));
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order));
        paymentEventRepository.save(event);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "APPROVED", null, AMOUNT, null, EVENT_UUID
        );

        // when & then
        assertThatThrownBy(() -> sut.handle(message))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -----------------------------------------------------------------------
    // TC-A2-4: amount 일치 시 정상 DONE 전이 + StockOutboxReadyEvent 발행
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("handleApproved — amount 일치 시 PaymentEvent DONE 전이 + StockOutboxReadyEvent 발행")
    void handleApproved_whenAmountMatch_shouldTransitToDone() {
        // given
        PaymentOrder order = buildPaymentOrder(2L, 1, BigDecimal.valueOf(AMOUNT));
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order));
        paymentEventRepository.save(event);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "APPROVED", null, AMOUNT, APPROVED_AT_STR, EVENT_UUID
        );

        // when
        sut.handle(message);

        // then — DONE 전이
        PaymentEvent saved = paymentEventRepository.findByOrderId(ORDER_ID).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(PaymentEventStatus.DONE);

        // then — StockOutboxReadyEvent 1건 발행 (productId=2, order 1개)
        assertThat(capturingPublisher.countReadyEvents()).isEqualTo(1L);
        // then — stock_outbox 1건 INSERT
        assertThat(stockOutboxRepository.savedCount()).isEqualTo(1);

        // then — quarantine 미호출
        then(quarantineCompensationHandler).should(never()).handle(any(), any());
    }

    // -----------------------------------------------------------------------
    // TC-B1-1: FAILED 수신 + 단일 PaymentOrder(productId=100, qty=3)
    //           → FailureCompensationService.compensate(orderId, 100L, 3) 1회 호출
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("handleFailed — 단일 주문 FAILED 시 compensate(orderId, productId, qty)가 실 qty로 호출된다")
    void handleFailed_singleOrder_publishesRestoreWithActualQty() {
        // given
        PaymentOrder order = buildPaymentOrder(100L, 3, BigDecimal.valueOf(300));
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order));
        paymentEventRepository.save(event);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "FAILED", "VENDOR_FAILED", null, null, EVENT_UUID
        );

        // when
        sut.handle(message);

        // then — compensate(orderId, 100L, 3) 1회 호출
        then(failureCompensationService)
                .should(times(1))
                .compensate(eq(ORDER_ID), eq(100L), eq(3));
    }

    // -----------------------------------------------------------------------
    // TC-B1-2: FAILED 수신 + 복수 PaymentOrder(100 qty=2, 200 qty=5)
    //           → compensate 2회, 각 인자 정확
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("handleFailed — 복수 주문 FAILED 시 각 productId/qty별 compensate가 호출된다")
    void handleFailed_multipleOrders_publishesPerProduct() {
        // given
        PaymentOrder order1 = buildPaymentOrder(100L, 2, BigDecimal.valueOf(200));
        PaymentOrder order2 = buildPaymentOrder(200L, 5, BigDecimal.valueOf(500));
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order1, order2));
        paymentEventRepository.save(event);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "FAILED", "VENDOR_FAILED", null, null, EVENT_UUID
        );

        // when
        sut.handle(message);

        // then — compensate 2회 (productId=100 qty=2 / productId=200 qty=5)
        then(failureCompensationService)
                .should(times(1))
                .compensate(eq(ORDER_ID), eq(100L), eq(2));
        then(failureCompensationService)
                .should(times(1))
                .compensate(eq(ORDER_ID), eq(200L), eq(5));
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
