package com.hyoguoo.paymentplatform.payment.application.usecase;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hyoguoo.paymentplatform.payment.application.dto.event.ConfirmedEventMessage;
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
 * K15 RED — PaymentConfirmResultUseCase → PaymentCommandUseCase 위임 검증.
 *
 * <p>검증 목표:
 * <ul>
 *   <li>APPROVED 처리 시 PaymentCommandUseCase.markPaymentAsDone 1회 호출</li>
 *   <li>APPROVED 처리 시 paymentEventRepository.saveOrUpdate 직접 호출 없음 (PaymentCommandUseCase가 내부에서 수행)</li>
 *   <li>FAILED 처리 시 PaymentCommandUseCase.markPaymentAsFail 1회 호출</li>
 *   <li>FAILED 처리 시 paymentEventRepository.saveOrUpdate 직접 호출 없음</li>
 * </ul>
 */
@DisplayName("PaymentConfirmResultUseCaseK15Test — PaymentCommandUseCase 위임 검증 RED")
class PaymentConfirmResultUseCaseK15Test {

    private static final String ORDER_ID = "order-k15-001";
    private static final String EVENT_UUID = "evt-k15-001";
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

        // K15: PaymentCommandUseCase 주입 필요 — 현재 생성자에 없으므로 RED 단계에서 컴파일 실패
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
                paymentCommandUseCase  // K15: 신규 파라미터
        );
    }

    // -----------------------------------------------------------------------
    // TC-K15-1: APPROVED → PaymentCommandUseCase.markPaymentAsDone 1회 호출
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("handleApproved — PaymentCommandUseCase.markPaymentAsDone 1회 호출")
    void handleApproved_shouldDelegateToMarkPaymentAsDone() {
        // given
        PaymentOrder order = buildPaymentOrder(1L, 1, BigDecimal.valueOf(AMOUNT));
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order));
        paymentEventRepository.save(event);

        given(paymentCommandUseCase.markPaymentAsDone(any(PaymentEvent.class), any(LocalDateTime.class)))
                .willReturn(event);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "APPROVED", null, AMOUNT, APPROVED_AT_STR, EVENT_UUID
        );

        // when
        sut.handle(message);

        // then — PaymentCommandUseCase.markPaymentAsDone 1회 호출 (approvedAt 값 일치)
        then(paymentCommandUseCase)
                .should(times(1))
                .markPaymentAsDone(any(PaymentEvent.class), eq(EXPECTED_APPROVED_AT));
    }

    // -----------------------------------------------------------------------
    // TC-K15-2: APPROVED → paymentEventRepository.saveOrUpdate 직접 호출 없음
    //           (PaymentCommandUseCase 내부에서 처리)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("handleApproved — paymentEventRepository.saveOrUpdate 직접 호출 없음 (PaymentCommandUseCase 위임)")
    void handleApproved_shouldNotCallSaveDirectly() {
        // given
        PaymentOrder order = buildPaymentOrder(1L, 1, BigDecimal.valueOf(AMOUNT));
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order));
        paymentEventRepository.save(event);

        given(paymentCommandUseCase.markPaymentAsDone(any(PaymentEvent.class), any(LocalDateTime.class)))
                .willReturn(event);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "APPROVED", null, AMOUNT, APPROVED_AT_STR, EVENT_UUID
        );

        // when
        int saveCountBefore = paymentEventRepository.saveOrUpdateCallCount();
        sut.handle(message);
        int saveCountAfter = paymentEventRepository.saveOrUpdateCallCount();

        // then — PaymentConfirmResultUseCase 자체에서 saveOrUpdate 추가 호출 없음
        // (PaymentCommandUseCase.markPaymentAsDone이 내부에서 저장 담당)
        then(paymentCommandUseCase)
                .should(times(1))
                .markPaymentAsDone(any(PaymentEvent.class), eq(EXPECTED_APPROVED_AT));
        // saveOrUpdate 호출 횟수가 위임 전후 동일해야 함 (직접 호출 없음 검증)
        org.assertj.core.api.Assertions.assertThat(saveCountAfter - saveCountBefore).isZero();
    }

    // -----------------------------------------------------------------------
    // TC-K15-3: FAILED → PaymentCommandUseCase.markPaymentAsFail 1회 호출
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("handleFailed — PaymentCommandUseCase.markPaymentAsFail 1회 호출")
    void handleFailed_shouldDelegateToMarkPaymentAsFail() {
        // given
        PaymentOrder order = buildPaymentOrder(100L, 3, BigDecimal.valueOf(300));
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order));
        paymentEventRepository.save(event);

        given(paymentCommandUseCase.markPaymentAsFail(any(PaymentEvent.class), any(String.class)))
                .willReturn(event);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "FAILED", "VENDOR_FAILED", null, null, EVENT_UUID
        );

        // when
        sut.handle(message);

        // then — PaymentCommandUseCase.markPaymentAsFail 1회 호출 (reasonCode 값 일치)
        then(paymentCommandUseCase)
                .should(times(1))
                .markPaymentAsFail(any(PaymentEvent.class), eq("VENDOR_FAILED"));
    }

    // -----------------------------------------------------------------------
    // TC-K15-4: FAILED → paymentEventRepository.saveOrUpdate 직접 호출 없음
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("handleFailed — paymentEventRepository.saveOrUpdate 직접 호출 없음 (PaymentCommandUseCase 위임)")
    void handleFailed_shouldNotCallSaveDirectly() {
        // given
        PaymentOrder order = buildPaymentOrder(100L, 3, BigDecimal.valueOf(300));
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order));
        paymentEventRepository.save(event);

        given(paymentCommandUseCase.markPaymentAsFail(any(PaymentEvent.class), any(String.class)))
                .willReturn(event);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "FAILED", "VENDOR_FAILED", null, null, EVENT_UUID
        );

        // when
        int saveCountBefore = paymentEventRepository.saveOrUpdateCallCount();
        sut.handle(message);
        int saveCountAfter = paymentEventRepository.saveOrUpdateCallCount();

        // then — saveOrUpdate 직접 호출 없음
        then(paymentCommandUseCase)
                .should(times(1))
                .markPaymentAsFail(any(PaymentEvent.class), eq("VENDOR_FAILED"));
        org.assertj.core.api.Assertions.assertThat(saveCountAfter - saveCountBefore).isZero();
    }

    // ---- factory helpers ----

    private PaymentEvent buildPaymentEvent(PaymentEventStatus status, List<PaymentOrder> orders) {
        return PaymentEvent.allArgsBuilder()
                .id(1L)
                .buyerId(100L)
                .sellerId(200L)
                .orderName("테스트 상품")
                .orderId(ORDER_ID)
                .paymentKey("pk-k15")
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
    }
}
