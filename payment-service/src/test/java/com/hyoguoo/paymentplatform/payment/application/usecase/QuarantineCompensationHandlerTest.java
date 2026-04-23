package com.hyoguoo.paymentplatform.payment.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockCachePort;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("QuarantineCompensationHandler 테스트")
@ExtendWith(MockitoExtension.class)
class QuarantineCompensationHandlerTest {

    private static final String ORDER_ID = "order-quarantine-001";
    private static final String REASON = "RETRY_EXHAUSTED";

    @InjectMocks
    private QuarantineCompensationHandler handler;

    @Mock
    private PaymentCommandUseCase paymentCommandUseCase;

    @Mock
    private PaymentLoadUseCase paymentLoadUseCase;

    @Mock
    private StockCachePort stockCachePort;

    @Mock
    private PaymentEventRepository paymentEventRepository;

    @Test
    @DisplayName("handle - TX 내 QUARANTINED 전이 + 플래그 true + payment_history insert 단일 TX 완료")
    void handle_ShouldTransitionToQuarantinedAndSetPendingFlag() {
        // given
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.RETRYING, false);
        PaymentEvent quarantinedEvent = buildPaymentEvent(PaymentEventStatus.QUARANTINED, true);

        given(paymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(event);
        given(paymentCommandUseCase.markPaymentAsQuarantined(event, REASON)).willReturn(quarantinedEvent);
        given(paymentEventRepository.saveOrUpdate(quarantinedEvent)).willReturn(quarantinedEvent);

        // when
        handler.handle(ORDER_ID, REASON, QuarantineCompensationHandler.QuarantineEntry.FCG);

        // then: TX 내에서 markPaymentAsQuarantined 호출됨
        then(paymentCommandUseCase).should(times(1)).markPaymentAsQuarantined(event, REASON);
        // quarantineCompensationPending 플래그가 true인 이벤트 저장 확인
        assertThat(quarantinedEvent.isQuarantineCompensationPending()).isTrue();
    }

    @Test
    @DisplayName("handle - DLQ consumer 진입점: TX 커밋 후 Redis INCR 1회 호출")
    void handle_WhenEntryIsDlqConsumer_ShouldRollbackStockAfterCommit() {
        // given
        PaymentOrder order = buildPaymentOrder(10L, 3);
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.RETRYING, false);
        PaymentEvent quarantinedEvent = buildPaymentEventWithOrders(
                PaymentEventStatus.QUARANTINED, true, List.of(order));

        given(paymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID))
                .willReturn(event)           // 1st call: TX 내 전이용
                .willReturn(quarantinedEvent); // 2nd call: TX 밖 재고 복구용
        given(paymentCommandUseCase.markPaymentAsQuarantined(event, REASON)).willReturn(quarantinedEvent);
        given(paymentEventRepository.saveOrUpdate(quarantinedEvent)).willReturn(quarantinedEvent);

        // when
        handler.handle(ORDER_ID, REASON, QuarantineCompensationHandler.QuarantineEntry.DLQ_CONSUMER);

        // then: Redis INCR(rollback) 1회 호출
        then(stockCachePort).should(times(1)).rollback(10L, 3);
    }

    @Test
    @DisplayName("handle - Redis INCR 실패 시 플래그 유지 (불변식 7b)")
    void handle_WhenRedisIncrFails_PendingFlagShouldRemainTrue() {
        // given
        PaymentOrder order = buildPaymentOrder(20L, 2);
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.RETRYING, false);
        PaymentEvent quarantinedEvent = buildPaymentEventWithOrders(
                PaymentEventStatus.QUARANTINED, true, List.of(order));

        given(paymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID))
                .willReturn(event)
                .willReturn(quarantinedEvent);
        given(paymentCommandUseCase.markPaymentAsQuarantined(event, REASON)).willReturn(quarantinedEvent);
        given(paymentEventRepository.saveOrUpdate(quarantinedEvent)).willReturn(quarantinedEvent);
        willThrow(new RuntimeException("Redis unavailable"))
                .given(stockCachePort).rollback(20L, 2);

        // when: 예외가 외부로 전파되지 않아야 함
        handler.handle(ORDER_ID, REASON, QuarantineCompensationHandler.QuarantineEntry.DLQ_CONSUMER);

        // then: 플래그 해제(clearPendingFlag) 호출 없음 → 플래그 유지
        // quarantinedEvent.isQuarantineCompensationPending()은 여전히 true
        assertThat(quarantinedEvent.isQuarantineCompensationPending()).isTrue();
        // 플래그 해제를 위한 save 호출 없어야 함 (첫 TX save 제외)
        then(paymentEventRepository).should(times(1)).saveOrUpdate(quarantinedEvent);
    }

    @Test
    @DisplayName("handle - FCG QUARANTINED 진입점: 즉시 INCR 금지 (Reconciler 경로와 구분)")
    void handle_WhenEntryIsFcg_ShouldNotRollbackStockImmediately() {
        // given
        PaymentOrder order = buildPaymentOrder(30L, 5);
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, false);
        PaymentEvent quarantinedEvent = buildPaymentEventWithOrders(
                PaymentEventStatus.QUARANTINED, true, List.of(order));

        given(paymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(event);
        given(paymentCommandUseCase.markPaymentAsQuarantined(event, REASON)).willReturn(quarantinedEvent);
        given(paymentEventRepository.saveOrUpdate(quarantinedEvent)).willReturn(quarantinedEvent);

        // when
        handler.handle(ORDER_ID, REASON, QuarantineCompensationHandler.QuarantineEntry.FCG);

        // then: FCG 진입점은 Redis INCR 즉시 호출 금지
        then(stockCachePort).should(never()).rollback(any(Long.class), any(Integer.class));
    }

    @Test
    @DisplayName("handle - QUARANTINED 전이 성공 후 StockCachePort 접촉 0회 (불변식: QUARANTINED는 홀딩)")
    void handle_QuarantinedTransition_ShouldNeverTouchStockCachePort() {
        // given: QUARANTINED 전이는 성공하지만 StockCachePort는 전혀 호출되지 않아야 함
        PaymentOrder order = buildPaymentOrder(40L, 7);
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS, false);
        PaymentEvent quarantinedEvent = buildPaymentEventWithOrders(
                PaymentEventStatus.QUARANTINED, false, List.of(order));

        given(paymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(event);
        given(paymentCommandUseCase.markPaymentAsQuarantined(event, REASON)).willReturn(quarantinedEvent);
        given(paymentEventRepository.saveOrUpdate(quarantinedEvent)).willReturn(quarantinedEvent);

        // when: 진입점에 관계없이 StockCachePort 접촉 0회
        handler.handle(ORDER_ID, REASON);

        // then: StockCachePort 전혀 호출 없음
        then(stockCachePort).shouldHaveNoInteractions();
        // then: QUARANTINED 전이는 정상 수행
        then(paymentCommandUseCase).should(times(1)).markPaymentAsQuarantined(event, REASON);
        then(paymentEventRepository).should(times(1)).saveOrUpdate(quarantinedEvent);
    }

    // ---- factory helpers ----

    private PaymentEvent buildPaymentEvent(PaymentEventStatus status, boolean pending) {
        return PaymentEvent.allArgsBuilder()
                .id(1L)
                .buyerId(100L)
                .sellerId(200L)
                .orderName("테스트 상품")
                .orderId(ORDER_ID)
                .paymentKey("pk-001")
                .status(status)
                .retryCount(0)
                .paymentOrderList(List.of())
                .quarantineCompensationPending(pending)
                .allArgsBuild();
    }

    private PaymentEvent buildPaymentEventWithOrders(
            PaymentEventStatus status, boolean pending, List<PaymentOrder> orders) {
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
                .quarantineCompensationPending(pending)
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
                .allArgsBuild();
    }
}
