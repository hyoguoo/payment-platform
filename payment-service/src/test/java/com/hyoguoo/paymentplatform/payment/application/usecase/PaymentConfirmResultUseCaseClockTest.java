package com.hyoguoo.paymentplatform.payment.application.usecase;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import com.hyoguoo.paymentplatform.payment.application.dto.event.ConfirmedEventMessage;
import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentEventDedupeStore;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockCachePort;
import com.hyoguoo.paymentplatform.payment.core.common.metrics.PaymentConfirmGuardSkipMetrics;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOrderStatus;
import com.hyoguoo.paymentplatform.payment.mock.FakePaymentEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * T3 — PaymentConfirmResultUseCase 에서 Clock 주입 기반 시각 소스 전환 검증.
 *
 * <p>고정 Clock.fixed()로 expiresAt(dedupe TTL) 과 occurredAt(stock-committed) 이
 * fixedInstant 기반으로 결정되는지 verify.
 */
@DisplayName("PaymentConfirmResultUseCase Clock 전환 검증")
class PaymentConfirmResultUseCaseClockTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-06-01T12:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
    /** stock-committed expiresAt TTL (PaymentConfirmResultUseCase.STOCK_COMMITTED_TTL). */
    private static final Duration STOCK_COMMITTED_TTL = Duration.ofDays(8);

    private static final String ORDER_ID = "order-clock-001";
    private static final String EVENT_UUID = "evt-clock-001";
    private static final long AMOUNT = 1000L;

    private FakePaymentEventRepository paymentEventRepository;
    private PaymentEventDedupeStore mockDedupeStore;
    @SuppressWarnings("unchecked")
    private KafkaTemplate<String, String> stockCommittedKafkaTemplate;
    private PaymentCommandUseCase paymentCommandUseCase;
    private PaymentConfirmResultUseCase sut;

    @BeforeEach
    void setUp() {
        paymentEventRepository = new FakePaymentEventRepository();
        mockDedupeStore = Mockito.mock(PaymentEventDedupeStore.class);
        stockCommittedKafkaTemplate = Mockito.mock(KafkaTemplate.class);
        QuarantineCompensationHandler quarantineCompensationHandler =
                Mockito.mock(QuarantineCompensationHandler.class);
        StockCachePort stockCachePort = Mockito.mock(StockCachePort.class);
        paymentCommandUseCase = Mockito.mock(PaymentCommandUseCase.class);

        // markIfAbsent 기본 반환 1(신규 마킹)
        given(mockDedupeStore.markIfAbsent(any(), any(Long.class), any(), any())).willReturn(1);

        sut = new PaymentConfirmResultUseCase(
                paymentEventRepository,
                quarantineCompensationHandler,
                FIXED_CLOCK,
                stockCachePort,
                mockDedupeStore,
                stockCommittedKafkaTemplate,
                paymentCommandUseCase,
                new PaymentConfirmGuardSkipMetrics(new SimpleMeterRegistry())
        );
    }

    @Test
    @DisplayName("confirmResult — dedupe expiresAt 은 clock.instant().plus(TTL) 이어야 한다")
    void confirmResult_expiresAt_usesClockInstant() {
        PaymentOrder order = buildOrder(1L, 1, BigDecimal.valueOf(AMOUNT));
        PaymentEvent event = buildEvent(PaymentEventStatus.IN_PROGRESS, List.of(order));
        paymentEventRepository.save(event);
        given(paymentCommandUseCase.markPaymentAsDone(any(), any())).willReturn(event);

        sut.handle(buildMessage("APPROVED", AMOUNT, "2026-06-01T12:00:00Z"));

        // markIfAbsent 에 전달된 expiresAt = fixedInstant + TTL
        Instant expectedExpiresAt = FIXED_INSTANT.plus(STOCK_COMMITTED_TTL);
        ArgumentCaptor<Instant> expiresAtCaptor = ArgumentCaptor.forClass(Instant.class);
        then(mockDedupeStore).should(times(1))
                .markIfAbsent(eq(EVENT_UUID), eq(1L), anyString(), expiresAtCaptor.capture());
        org.assertj.core.api.Assertions.assertThat(expiresAtCaptor.getValue())
                .isEqualTo(expectedExpiresAt);
    }

    @Test
    @DisplayName("confirmResult — stock-committed occurredAt 은 clock.instant() 기반이어야 한다")
    void confirmResult_occurredAt_usesClockInstant() {
        PaymentOrder order = buildOrder(1L, 1, BigDecimal.valueOf(AMOUNT));
        PaymentEvent event = buildEvent(PaymentEventStatus.IN_PROGRESS, List.of(order));
        paymentEventRepository.save(event);
        given(paymentCommandUseCase.markPaymentAsDone(any(), any())).willReturn(event);

        sut.handle(buildMessage("APPROVED", AMOUNT, "2026-06-01T12:00:00Z"));

        // send 에 넘어간 payload JSON 에 occurredAt = fixedInstant(에포크 초) 가 포함되어야 한다.
        // ObjectMapper 는 JavaTimeModule 기본 설정에서 Instant 를 에포크 초(소수점)로 직렬화한다.
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        then(stockCommittedKafkaTemplate).should(times(1))
                .send(anyString(), anyString(), payloadCaptor.capture());

        String payload = payloadCaptor.getValue();
        // FIXED_INSTANT = 2026-06-01T12:00:00Z = 1780315200 에포크초
        long expectedEpochSeconds = FIXED_INSTANT.getEpochSecond();
        org.assertj.core.api.Assertions.assertThat(payload)
                .contains(String.valueOf(expectedEpochSeconds));
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private ConfirmedEventMessage buildMessage(String status, long amount, String approvedAt) {
        return new ConfirmedEventMessage(ORDER_ID, status, null, amount, approvedAt, EVENT_UUID);
    }

    private PaymentEvent buildEvent(PaymentEventStatus status, List<PaymentOrder> orders) {
        return PaymentEvent.allArgsBuilder()
                .id(1L)
                .buyerId(100L)
                .sellerId(200L)
                .orderName("시계 테스트 상품")
                .orderId(ORDER_ID)
                .paymentKey("pk-clock-001")
                .status(status)
                .retryCount(0)
                .paymentOrderList(orders)
                .allArgsBuild();
    }

    private PaymentOrder buildOrder(Long productId, int quantity, BigDecimal totalAmount) {
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
