package com.hyoguoo.paymentplatform.payment.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.hyoguoo.paymentplatform.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.payment.application.event.StockCommitRequestedEvent;
import com.hyoguoo.paymentplatform.payment.application.event.StockRestoreRequestedEvent;
import com.hyoguoo.paymentplatform.payment.application.service.FailureCompensationService;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOrderStatus;
import com.hyoguoo.paymentplatform.payment.infrastructure.messaging.consumer.dto.ConfirmedEventMessage;
import com.hyoguoo.paymentplatform.payment.mock.FakeEventDedupeStore;
import com.hyoguoo.paymentplatform.payment.mock.FakePaymentConfirmDlqPublisher;
import com.hyoguoo.paymentplatform.payment.mock.FakePaymentEventRepository;
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
 * T-D2 RED 테스트:
 * - handleApproved: StockCommitRequestedEvent ApplicationEvent 발행 확인 + 직접 publisher 호출 0건
 * - handleFailed: StockRestoreRequestedEvent ApplicationEvent 발행 확인
 * ADR-04: stock publish는 TX 외부(AFTER_COMMIT)에서 실행 — TX 내부는 이벤트 발행만.
 */
@DisplayName("PaymentConfirmResultUseCase T-D2 — stock publish AFTER_COMMIT 리스너 분리")
class PaymentConfirmResultUseCaseD2Test {

    private static final String ORDER_ID = "order-d2-001";
    private static final String EVENT_UUID = "evt-d2-001";
    private static final String APPROVED_AT_STR = "2026-04-24T10:00:00Z";
    private static final long AMOUNT = 1000L;

    private FakePaymentEventRepository paymentEventRepository;
    private FakeEventDedupeStore dedupeStore;
    private QuarantineCompensationHandler quarantineCompensationHandler;
    private FailureCompensationService failureCompensationService;
    private FakePaymentConfirmDlqPublisher dlqPublisher;
    private CapturingApplicationEventPublisher capturingPublisher;
    private PaymentConfirmResultUseCase sut;

    @BeforeEach
    void setUp() {
        paymentEventRepository = new FakePaymentEventRepository();
        dedupeStore = new FakeEventDedupeStore();
        quarantineCompensationHandler = Mockito.mock(QuarantineCompensationHandler.class);
        failureCompensationService = Mockito.mock(FailureCompensationService.class);
        dlqPublisher = new FakePaymentConfirmDlqPublisher();
        capturingPublisher = new CapturingApplicationEventPublisher();

        LocalDateTimeProvider fixedClock = () -> LocalDateTime.of(2026, 4, 24, 12, 0, 0);

        sut = new PaymentConfirmResultUseCase(
                paymentEventRepository,
                dedupeStore,
                capturingPublisher,
                quarantineCompensationHandler,
                fixedClock,
                failureCompensationService,
                dlqPublisher
        );
    }

    // -----------------------------------------------------------------------
    // TC-D2-1: APPROVED 처리 → StockCommitRequestedEvent 발행 확인,
    //          직접 publisher(StockCommitEventPublisherPort) 호출 0건
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("handleApproved — ApplicationEvent StockCommitRequestedEvent 발행, 직접 publisher 미호출")
    void handleApproved_shouldPublishStockCommitEvent_notDirectPublisher() {
        // given: 2개 PaymentOrder → 2개 StockCommitRequestedEvent 기대
        PaymentOrder order1 = buildPaymentOrder(10L, 2, BigDecimal.valueOf(500));
        PaymentOrder order2 = buildPaymentOrder(20L, 3, BigDecimal.valueOf(500));
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order1, order2));
        paymentEventRepository.save(event);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "APPROVED", null, EVENT_UUID, AMOUNT, APPROVED_AT_STR
        );

        // when
        sut.handle(message);

        // then — StockCommitRequestedEvent 2건 발행
        List<StockCommitRequestedEvent> commitEvents = capturingPublisher.captured(StockCommitRequestedEvent.class);
        assertThat(commitEvents).hasSize(2);

        // productId 순 정렬 후 검증
        commitEvents.sort((a, b) -> Long.compare(a.productId(), b.productId()));
        assertThat(commitEvents.get(0).productId()).isEqualTo(10L);
        assertThat(commitEvents.get(0).quantity()).isEqualTo(2);
        assertThat(commitEvents.get(1).productId()).isEqualTo(20L);
        assertThat(commitEvents.get(1).quantity()).isEqualTo(3);

        // then — StockRestoreRequestedEvent 발행 없음
        assertThat(capturingPublisher.captured(StockRestoreRequestedEvent.class)).isEmpty();
    }

    // -----------------------------------------------------------------------
    // TC-D2-2: FAILED 처리 → StockRestoreRequestedEvent 발행 확인
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("handleFailed — FailureCompensationService.compensate 호출 (StockRestoreRequestedEvent 발행)")
    void handleFailed_shouldPublishStockRestoreEvent() {
        // given
        PaymentOrder order = buildPaymentOrder(100L, 5, BigDecimal.valueOf(AMOUNT));
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order));
        paymentEventRepository.save(event);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "FAILED", "VENDOR_FAILED", EVENT_UUID, null, null
        );

        // when
        sut.handle(message);

        // then — FailureCompensationService.compensate 1회 호출(orderId, productId=100, qty=5)
        then(failureCompensationService)
                .should(times(1))
                .compensate(ORDER_ID, 100L, 5);

        // then — StockCommitRequestedEvent 발행 없음
        assertThat(capturingPublisher.captured(StockCommitRequestedEvent.class)).isEmpty();
    }

    // ---- factory helpers ----

    private PaymentEvent buildPaymentEvent(PaymentEventStatus status, List<PaymentOrder> orders) {
        return PaymentEvent.allArgsBuilder()
                .id(1L)
                .buyerId(100L)
                .sellerId(200L)
                .orderName("테스트 상품")
                .orderId(ORDER_ID)
                .paymentKey("pk-d2")
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
                    .collect(java.util.stream.Collectors.toList());
        }
    }
}
