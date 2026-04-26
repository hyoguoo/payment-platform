package com.hyoguoo.paymentplatform.payment.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hyoguoo.paymentplatform.payment.application.dto.event.ConfirmedEventMessage;
import com.hyoguoo.paymentplatform.payment.application.event.StockOutboxReadyEvent;
import com.hyoguoo.paymentplatform.payment.application.service.FailureCompensationService;
import com.hyoguoo.paymentplatform.payment.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOrderStatus;
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
 * PaymentConfirmResultUseCase.handleFailed 단위 검증.
 *
 * <p>커버 범위:
 * <ul>
 *   <li>FAILED 수신 시 PaymentCommandUseCase.markPaymentAsFail 위임 (reasonCode 그대로 전달)</li>
 *   <li>FailureCompensationService.compensate 가 PaymentOrder 별로 실 qty 와 함께 호출되어야 함
 *       — 단일 주문 / 복수 주문 모두</li>
 *   <li>UseCase 자체는 stock_outbox 를 직접 INSERT 하거나 StockOutboxReadyEvent 를 발행하지 않음
 *       (그 책임은 FailureCompensationService 내부)</li>
 *   <li>UseCase 가 paymentEventRepository.saveOrUpdate 를 직접 호출하지 않고 위임한다</li>
 * </ul>
 */
@DisplayName("PaymentConfirmResultUseCase handleFailed")
class PaymentConfirmResultUseCaseHandleFailedTest {

    private static final String ORDER_ID = "order-failed-001";
    private static final String EVENT_UUID = "evt-failed-001";
    private static final String REASON_CODE = "VENDOR_FAILED";

    private FakePaymentEventRepository paymentEventRepository;
    private FakeEventDedupeStore dedupeStore;
    private CapturingApplicationEventPublisher capturingPublisher;
    private QuarantineCompensationHandler quarantineCompensationHandler;
    private FailureCompensationService failureCompensationService;
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
        failureCompensationService = Mockito.mock(FailureCompensationService.class);
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
                failureCompensationService,
                dlqPublisher,
                stockOutboxRepository,
                new ObjectMapper().registerModule(new JavaTimeModule()),
                PaymentConfirmResultUseCase.DEFAULT_LEASE_TTL,
                PaymentConfirmResultUseCase.DEFAULT_LONG_TTL,
                paymentCommandUseCase
        );
    }

    @Test
    @DisplayName("단일 주문 FAILED — markPaymentAsFail 위임 + compensate 가 실 qty 로 호출된다")
    void 단일_주문_FAILED_시_markPaymentAsFail_위임_및_compensate_호출() {
        PaymentOrder order = buildPaymentOrder(100L, 3, BigDecimal.valueOf(300));
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order));
        paymentEventRepository.save(event);
        given(paymentCommandUseCase.markPaymentAsFail(any(PaymentEvent.class), any(String.class)))
                .willReturn(event);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "FAILED", REASON_CODE, null, null, EVENT_UUID);

        sut.handle(message);

        then(paymentCommandUseCase)
                .should(times(1))
                .markPaymentAsFail(any(PaymentEvent.class), eq(REASON_CODE));
        then(failureCompensationService)
                .should(times(1))
                .compensate(eq(ORDER_ID), eq(100L), eq(3));
    }

    @Test
    @DisplayName("복수 주문 FAILED — compensate 가 productId/qty 별로 따로 호출된다")
    void 복수_주문_FAILED_시_compensate_가_productId_별로_호출() {
        PaymentOrder order1 = buildPaymentOrder(100L, 2, BigDecimal.valueOf(200));
        PaymentOrder order2 = buildPaymentOrder(200L, 5, BigDecimal.valueOf(500));
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order1, order2));
        paymentEventRepository.save(event);
        given(paymentCommandUseCase.markPaymentAsFail(any(PaymentEvent.class), any(String.class)))
                .willReturn(event);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "FAILED", REASON_CODE, null, null, EVENT_UUID);

        sut.handle(message);

        then(failureCompensationService)
                .should(times(1))
                .compensate(eq(ORDER_ID), eq(100L), eq(2));
        then(failureCompensationService)
                .should(times(1))
                .compensate(eq(ORDER_ID), eq(200L), eq(5));
    }

    @Test
    @DisplayName("UseCase 는 stock_outbox 를 직접 INSERT 하거나 StockOutboxReadyEvent 를 직접 발행하지 않는다")
    void UseCase_는_stock_outbox_를_직접_INSERT_하지_않는다() {
        PaymentOrder order = buildPaymentOrder(100L, 5, BigDecimal.valueOf(500));
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order));
        paymentEventRepository.save(event);
        given(paymentCommandUseCase.markPaymentAsFail(any(PaymentEvent.class), any(String.class)))
                .willReturn(event);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "FAILED", REASON_CODE, null, null, EVENT_UUID);

        sut.handle(message);

        assertThat(capturingPublisher.captured(StockOutboxReadyEvent.class)).isEmpty();
        assertThat(stockOutboxRepository.savedCount()).isZero();
    }

    @Test
    @DisplayName("UseCase 가 paymentEventRepository.saveOrUpdate 를 직접 호출하지 않고 PaymentCommandUseCase 에 위임한다")
    void UseCase_가_paymentEventRepository_saveOrUpdate_를_직접_호출하지_않는다() {
        PaymentOrder order = buildPaymentOrder(100L, 3, BigDecimal.valueOf(300));
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order));
        paymentEventRepository.save(event);
        given(paymentCommandUseCase.markPaymentAsFail(any(PaymentEvent.class), any(String.class)))
                .willReturn(event);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "FAILED", REASON_CODE, null, null, EVENT_UUID);

        int saveCountBefore = paymentEventRepository.saveOrUpdateCallCount();
        sut.handle(message);
        int saveCountAfter = paymentEventRepository.saveOrUpdateCallCount();

        then(paymentCommandUseCase)
                .should(times(1))
                .markPaymentAsFail(any(PaymentEvent.class), eq(REASON_CODE));
        assertThat(saveCountAfter - saveCountBefore).isZero();
    }

    private PaymentEvent buildPaymentEvent(PaymentEventStatus status, List<PaymentOrder> orders) {
        return PaymentEvent.allArgsBuilder()
                .id(1L)
                .buyerId(100L)
                .sellerId(200L)
                .orderName("테스트 상품")
                .orderId(ORDER_ID)
                .paymentKey("pk-failed")
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
    }
}
