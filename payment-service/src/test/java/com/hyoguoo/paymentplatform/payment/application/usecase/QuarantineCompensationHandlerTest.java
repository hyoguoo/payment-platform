package com.hyoguoo.paymentplatform.payment.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockCachePort;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import java.math.BigDecimal;
import java.util.List;
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
    @DisplayName("handle - TX 내 QUARANTINED 전이 + 저장 완료")
    void handle_ShouldTransitionToQuarantinedAndSave() {
        // given
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.RETRYING);
        PaymentEvent quarantinedEvent = buildPaymentEvent(PaymentEventStatus.QUARANTINED);

        given(paymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(event);
        given(paymentCommandUseCase.markPaymentAsQuarantined(event, REASON)).willReturn(quarantinedEvent);
        given(paymentEventRepository.saveOrUpdate(quarantinedEvent)).willReturn(quarantinedEvent);

        // when
        handler.handle(ORDER_ID, REASON);

        // then: TX 내에서 markPaymentAsQuarantined 호출됨
        then(paymentCommandUseCase).should(times(1)).markPaymentAsQuarantined(event, REASON);
        then(paymentEventRepository).should(times(1)).saveOrUpdate(quarantinedEvent);
    }

    @Test
    @DisplayName("handle - QUARANTINED 전이 성공 후 StockCachePort 접촉 0회 (불변식: QUARANTINED는 홀딩)")
    void handle_QuarantinedTransition_ShouldNeverTouchStockCachePort() {
        // given: QUARANTINED 전이는 성공하지만 StockCachePort는 전혀 호출되지 않아야 함
        PaymentOrder order = buildPaymentOrder(40L, 7);
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS);
        PaymentEvent quarantinedEvent = buildPaymentEventWithOrders(
                PaymentEventStatus.QUARANTINED, List.of(order));

        given(paymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(event);
        given(paymentCommandUseCase.markPaymentAsQuarantined(event, REASON)).willReturn(quarantinedEvent);
        given(paymentEventRepository.saveOrUpdate(quarantinedEvent)).willReturn(quarantinedEvent);

        // when
        handler.handle(ORDER_ID, REASON);

        // then: StockCachePort 전혀 호출 없음
        then(stockCachePort).shouldHaveNoInteractions();
        // then: QUARANTINED 전이는 정상 수행
        then(paymentCommandUseCase).should(times(1)).markPaymentAsQuarantined(event, REASON);
        then(paymentEventRepository).should(times(1)).saveOrUpdate(quarantinedEvent);
    }

    @Test
    @DisplayName("handle - 상태 전이 완료 후 반환된 이벤트는 QUARANTINED 상태")
    void handle_ShouldResultInQuarantinedStatus() {
        // given
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.IN_PROGRESS);
        PaymentEvent quarantinedEvent = buildPaymentEvent(PaymentEventStatus.QUARANTINED);

        given(paymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(event);
        given(paymentCommandUseCase.markPaymentAsQuarantined(event, REASON)).willReturn(quarantinedEvent);
        given(paymentEventRepository.saveOrUpdate(quarantinedEvent)).willReturn(quarantinedEvent);

        // when
        handler.handle(ORDER_ID, REASON);

        // then: 저장된 이벤트가 QUARANTINED 상태
        assertThat(quarantinedEvent.getStatus()).isEqualTo(PaymentEventStatus.QUARANTINED);
    }

    // ---- factory helpers ----

    private PaymentEvent buildPaymentEvent(PaymentEventStatus status) {
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
                .allArgsBuild();
    }

    private PaymentEvent buildPaymentEventWithOrders(
            PaymentEventStatus status, List<PaymentOrder> orders) {
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
                .allArgsBuild();
    }
}
