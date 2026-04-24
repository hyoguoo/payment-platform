package com.hyoguoo.paymentplatform.payment.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import com.hyoguoo.paymentplatform.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.payment.application.event.StockCommitRequestedEvent;
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
 * ConfirmedEventConsumer(PaymentConfirmResultUseCase) 단위 테스트.
 * ADR-04(eventUUID dedupe), ADR-14(stock 이벤트 발행).
 * domain_risk=true: APPROVED/FAILED/QUARANTINED 분기 + dedupe 불변 커버.
 */
@DisplayName("ConfirmedEventConsumerTest")
class ConfirmedEventConsumerTest {

    private static final String ORDER_ID = "order-confirmed-001";
    private static final String EVENT_UUID = "evt-uuid-confirmed-001";

    private FakePaymentEventRepository paymentEventRepository;
    private FakeEventDedupeStore dedupeStore;
    private CapturingApplicationEventPublisher capturingPublisher;
    private QuarantineCompensationHandler quarantineCompensationHandler;
    private FailureCompensationService failureCompensationService;
    private FakePaymentConfirmDlqPublisher dlqPublisher;
    private PaymentConfirmResultUseCase sut;

    @BeforeEach
    void setUp() {
        paymentEventRepository = new FakePaymentEventRepository();
        dedupeStore = new FakeEventDedupeStore();
        capturingPublisher = new CapturingApplicationEventPublisher();
        quarantineCompensationHandler = Mockito.mock(QuarantineCompensationHandler.class);
        failureCompensationService = Mockito.mock(FailureCompensationService.class);
        dlqPublisher = new FakePaymentConfirmDlqPublisher();

        LocalDateTimeProvider fixedClock = () -> LocalDateTime.of(2026, 4, 24, 0, 0, 0);

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

        public long countFor(Long productId) {
            return events.stream()
                    .filter(e -> e instanceof StockCommitRequestedEvent sce
                            && sce.productId().equals(productId))
                    .count();
        }

        public List<Object> publishedEvents() {
            return List.copyOf(events);
        }
    }

    // -----------------------------------------------------------------------
    // TC1: APPROVED → PaymentEvent DONE 전이 + StockCommitEvent 발행
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("consume — APPROVED 수신 시 PaymentEvent DONE 전이 + StockCommitEvent 발행")
    void consume_WhenApproved_ShouldTransitionToDone() {
        // given
        PaymentOrder order = buildPaymentOrder(1L, 2);
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order));
        paymentEventRepository.save(event);

        // amount=2000(=1000*2), approvedAt non-null — T-A2 역방향 방어선 통과 조건
        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "APPROVED", null, EVENT_UUID, 2000L, "2026-04-24T01:00:00Z");

        // when
        sut.handle(message);

        // then — DONE 전이
        PaymentEvent saved = paymentEventRepository.findByOrderId(ORDER_ID).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(PaymentEventStatus.DONE);

        // then — StockCommitEvent 발행 1회 (orderId 기준)
        assertThat(capturingPublisher.countFor(1L)).isEqualTo(1L);
    }

    // -----------------------------------------------------------------------
    // TC2: FAILED → PaymentEvent FAILED 전이 + stock.events.restore 발행
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("consume — FAILED 수신 시 PaymentEvent FAILED 전이 + FailureCompensationService.compensate 경유 재고 복원")
    void consume_WhenFailed_ShouldTransitionToFailed() {
        // given
        PaymentOrder order = buildPaymentOrder(2L, 3);
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order));
        paymentEventRepository.save(event);

        ConfirmedEventMessage message = new ConfirmedEventMessage(ORDER_ID, "FAILED", "VENDOR_FAILED", EVENT_UUID, null, null);

        // when
        sut.handle(message);

        // then — FAILED 전이
        PaymentEvent saved = paymentEventRepository.findByOrderId(ORDER_ID).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(PaymentEventStatus.FAILED);

        // then — FailureCompensationService.compensate(orderId, productId=2, qty=3) 1회 호출 (T-B1)
        then(failureCompensationService)
                .should(times(1))
                .compensate(
                        org.mockito.ArgumentMatchers.eq(ORDER_ID),
                        org.mockito.ArgumentMatchers.eq(2L),
                        org.mockito.ArgumentMatchers.eq(3)
                );
        // then — StockCommitEvent 미발행
        assertThat(capturingPublisher.countFor(2L)).isEqualTo(0L);
    }

    // -----------------------------------------------------------------------
    // TC3: QUARANTINED → QuarantineCompensationHandler.handle 위임
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("consume — QUARANTINED 수신 시 QuarantineCompensationHandler.handle 1회 호출")
    void consume_WhenQuarantined_ShouldDelegateToQuarantineHandler() {
        // given
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of());
        paymentEventRepository.save(event);

        ConfirmedEventMessage message = new ConfirmedEventMessage(ORDER_ID, "QUARANTINED", "RETRY_EXHAUSTED", EVENT_UUID, null, null);

        // when
        sut.handle(message);

        // then — handler 1회 호출
        then(quarantineCompensationHandler)
                .should(times(1))
                .handle(
                        org.mockito.ArgumentMatchers.eq(ORDER_ID),
                        org.mockito.ArgumentMatchers.eq("RETRY_EXHAUSTED")
                );

        // then — 직접 상태 전이 없음 (handler 내부 책임)
        assertThat(capturingPublisher.countFor(999L)).isEqualTo(0L);
    }

    // -----------------------------------------------------------------------
    // TC4: 동일 eventUUID 2회 수신 → 상태 전이 1회만
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("consume — 동일 eventUUID 2회 수신 시 상태 전이는 1회만 수행 (dedupe 불변식 5)")
    void consume_DuplicateEvent_ShouldDedupeByEventUUID() {
        // given
        PaymentOrder order = buildPaymentOrder(3L, 1);
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order));
        paymentEventRepository.save(event);

        // amount=1000(=1000*1), approvedAt non-null — T-A2 역방향 방어선 통과 조건
        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "APPROVED", null, EVENT_UUID, 1000L, "2026-04-24T01:00:00Z");

        // when — 첫 번째 소비 (정상 처리)
        sut.handle(message);
        long firstCommitCount = capturingPublisher.countFor(3L);

        // when — 두 번째 소비 (동일 eventUUID → dedupe)
        sut.handle(message);

        // then — StockCommitEvent 추가 발행 없음
        assertThat(capturingPublisher.countFor(3L)).isEqualTo(firstCommitCount);
        // dedupe store 에 1개만 저장됨
        assertThat(dedupeStore.contains(EVENT_UUID)).isTrue();
    }

    // -----------------------------------------------------------------------
    // TC5: dedupe no-op — publisher/handler 호출 0회
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("consumer — 동일 eventUUID 재수신 시 publisher 0회, handler 0회 (no-op)")
    void consumer_WhenSameEventUUIDReceived_ShouldNoOp() {
        // given
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of());
        paymentEventRepository.save(event);

        ConfirmedEventMessage first = new ConfirmedEventMessage(ORDER_ID, "QUARANTINED", "RETRY_EXHAUSTED", EVENT_UUID, null, null);
        ConfirmedEventMessage second = new ConfirmedEventMessage(ORDER_ID, "QUARANTINED", "RETRY_EXHAUSTED", EVENT_UUID, null, null);

        // when — 첫 번째 소비
        sut.handle(first);
        // when — 두 번째 소비 (동일 eventUUID)
        sut.handle(second);

        // then — handler는 1회만 호출
        then(quarantineCompensationHandler)
                .should(times(1))
                .handle(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()
                );

        // then — publisher는 0회
        assertThat(capturingPublisher.publishedEvents()).isEmpty();
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
                .status(PaymentOrderStatus.EXECUTING)  // done()/fail() 호출 가능 상태
                .allArgsBuild();
    }
}
