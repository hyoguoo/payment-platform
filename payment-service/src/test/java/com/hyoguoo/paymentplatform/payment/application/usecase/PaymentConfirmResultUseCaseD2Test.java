package com.hyoguoo.paymentplatform.payment.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hyoguoo.paymentplatform.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.payment.application.event.StockOutboxReadyEvent;
import com.hyoguoo.paymentplatform.payment.application.service.FailureCompensationService;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.StockOutbox;
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
 * T-D2 (갱신됨 → T-J1) RED→GREEN 테스트:
 * - handleApproved: stock_outbox INSERT + StockOutboxReadyEvent 발행 확인
 * - handleFailed: FailureCompensationService.compensate 호출 확인
 * ADR-04: stock publish는 TX 외부(AFTER_COMMIT)에서 실행 — TX 내부는 outbox INSERT + event 발행만.
 *
 * <p>T-J1: StockCommitRequestedEvent → StockOutboxReadyEvent 전환 검증.
 * stockOutboxRepository.save 호출 여부 + StockOutboxReadyEvent 발행 건수.
 */
@DisplayName("PaymentConfirmResultUseCase T-J1 — stock outbox 패턴 도입 검증")
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
    private FakeStockOutboxRepository stockOutboxRepository;
    private CapturingApplicationEventPublisher capturingPublisher;
    private PaymentConfirmResultUseCase sut;

    @BeforeEach
    void setUp() {
        paymentEventRepository = new FakePaymentEventRepository();
        dedupeStore = new FakeEventDedupeStore();
        quarantineCompensationHandler = Mockito.mock(QuarantineCompensationHandler.class);
        failureCompensationService = Mockito.mock(FailureCompensationService.class);
        dlqPublisher = new FakePaymentConfirmDlqPublisher();
        stockOutboxRepository = new FakeStockOutboxRepository();
        capturingPublisher = new CapturingApplicationEventPublisher();

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

    // -----------------------------------------------------------------------
    // TC-J1-6: APPROVED 처리 → stock_outbox save 2회 + StockOutboxReadyEvent 2건 발행
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("handleApproved — stock_outbox INSERT 2건 + StockOutboxReadyEvent 2건 발행 (productId 2개)")
    void handleApproved_shouldSaveStockOutboxAndPublishReadyEvent() {
        // given: 2개 PaymentOrder → 2개 outbox INSERT 기대
        PaymentOrder order1 = buildPaymentOrder(10L, 2, BigDecimal.valueOf(500));
        PaymentOrder order2 = buildPaymentOrder(20L, 3, BigDecimal.valueOf(500));
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order1, order2));
        paymentEventRepository.save(event);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "APPROVED", null, AMOUNT, APPROVED_AT_STR, EVENT_UUID
        );

        // when
        sut.handle(message);

        // then — stock_outbox 2건 저장
        assertThat(stockOutboxRepository.savedCount()).isEqualTo(2);

        // then — StockOutboxReadyEvent 2건 발행
        List<StockOutboxReadyEvent> readyEvents = capturingPublisher.captured(StockOutboxReadyEvent.class);
        assertThat(readyEvents).hasSize(2);

        // then — saved outbox의 topic이 올바름
        List<StockOutbox> saved = stockOutboxRepository.allSaved();
        assertThat(saved).allMatch(o -> "payment.events.stock-committed".equals(o.getTopic()));

        // then — key는 productId 문자열
        List<String> keys = saved.stream().map(StockOutbox::getKey).sorted().toList();
        assertThat(keys).containsExactlyInAnyOrder("10", "20");
    }

    // -----------------------------------------------------------------------
    // TC-K1-5: APPROVED 처리 — 2개 ProductId → payload의 idempotencyKey 서로 다름 (K1 회귀)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("handleApproved — multi-product 시 각 stock_outbox payload의 idempotencyKey가 서로 다르다")
    void handleApproved_multiProduct_shouldUseUniqueIdempotencyKeyPerProduct() throws Exception {
        // given: productId 10, productId 20 — 2개 PaymentOrder
        PaymentOrder order1 = buildPaymentOrder(10L, 2, BigDecimal.valueOf(500));
        PaymentOrder order2 = buildPaymentOrder(20L, 3, BigDecimal.valueOf(500));
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order1, order2));
        paymentEventRepository.save(event);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "APPROVED", null, AMOUNT, APPROVED_AT_STR, EVENT_UUID
        );

        // when
        sut.handle(message);

        // then — stock_outbox payload에서 idempotencyKey 추출
        com.fasterxml.jackson.databind.ObjectMapper om =
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

        List<StockOutbox> saved = stockOutboxRepository.allSaved()
                .stream()
                .sorted(java.util.Comparator.comparing(StockOutbox::getKey))
                .toList();
        assertThat(saved).hasSize(2);

        String key0 = om.readTree(saved.get(0).getPayload()).get("idempotencyKey").asText();
        String key1 = om.readTree(saved.get(1).getPayload()).get("idempotencyKey").asText();

        // idempotencyKey는 productId별로 달라야 한다 (K1 핵심 불변식)
        assertThat(key0).isNotEqualTo(key1);

        // 결정론적 UUID 검증 — StockEventUuidDeriver.derive 예측값과 일치해야 한다
        String expectedKey10 = com.hyoguoo.paymentplatform.payment.application.util.StockEventUuidDeriver
                .derive(ORDER_ID, 10L, "stock-commit");
        String expectedKey20 = com.hyoguoo.paymentplatform.payment.application.util.StockEventUuidDeriver
                .derive(ORDER_ID, 20L, "stock-commit");

        // saved는 key(productId) 오름차순 정렬: "10" < "20"
        assertThat(key0).isEqualTo(expectedKey10);
        assertThat(key1).isEqualTo(expectedKey20);
    }

    // -----------------------------------------------------------------------
    // TC-J1-7: FAILED 처리 → FailureCompensationService.compensate 호출 확인
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("handleFailed — FailureCompensationService.compensate 호출 (StockOutboxReadyEvent 발행은 서비스 내부)")
    void handleFailed_shouldCallFailureCompensationService() {
        // given
        PaymentOrder order = buildPaymentOrder(100L, 5, BigDecimal.valueOf(AMOUNT));
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, List.of(order));
        paymentEventRepository.save(event);

        ConfirmedEventMessage message = new ConfirmedEventMessage(
                ORDER_ID, "FAILED", "VENDOR_FAILED", null, null, EVENT_UUID
        );

        // when
        sut.handle(message);

        // then — FailureCompensationService.compensate 1회 호출(orderId, productId=100, qty=5)
        then(failureCompensationService)
                .should(times(1))
                .compensate(ORDER_ID, 100L, 5);

        // then — StockOutboxReadyEvent는 FailureCompensationService 내부에서 발행 — 이 UseCase는 발행 안 함
        assertThat(capturingPublisher.captured(StockOutboxReadyEvent.class)).isEmpty();
        // stockOutboxRepository는 FailureCompensationService 내부 책임 — mock이므로 UseCase 테스트에선 0건
        assertThat(stockOutboxRepository.savedCount()).isEqualTo(0);
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
