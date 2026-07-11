package com.hyoguoo.paymentplatform.payment.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import com.hyoguoo.paymentplatform.payment.application.port.out.StockCachePort;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockRecoveryCompensationResult;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.exception.PaymentStatusException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentValidException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("QuarantineResolveUseCase 테스트")
@ExtendWith(MockitoExtension.class)
class QuarantineResolveUseCaseTest {

    private static final String ORDER_ID = "order-quarantine-resolve-001";
    private static final String REASON = "관리자 안전 종결 — 벤더 미캡처 확인";

    @InjectMocks
    private QuarantineResolveUseCase quarantineResolveUseCase;

    @Mock
    private PaymentLoadUseCase paymentLoadUseCase;

    @Mock
    private StockCachePort stockCachePort;

    @Mock
    private PaymentCommandUseCase paymentCommandUseCase;

    @Test
    @DisplayName("resolve - 보상(compensateIfDecremented)을 먼저 호출한 뒤 도메인 전이를 호출한다 (SCR-6)")
    void resolve_ShouldCompensateBeforeTransition() {
        // given
        PaymentOrder order = buildPaymentOrder(40L, 7);
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.QUARANTINED, List.of(order));
        PaymentEvent resolvedEvent = buildPaymentEvent(PaymentEventStatus.FAILED, List.of(order));

        given(paymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(event);
        given(stockCachePort.compensateIfDecremented(ORDER_ID, event.getPaymentOrderList()))
                .willReturn(StockRecoveryCompensationResult.OK);
        given(paymentCommandUseCase.markPaymentAsFailFromQuarantine(event, REASON))
                .willReturn(resolvedEvent);

        // when
        PaymentEvent result = quarantineResolveUseCase.resolve(ORDER_ID, REASON);

        // then
        InOrder inOrder = Mockito.inOrder(stockCachePort, paymentCommandUseCase);
        inOrder.verify(stockCachePort).compensateIfDecremented(ORDER_ID, event.getPaymentOrderList());
        inOrder.verify(paymentCommandUseCase).markPaymentAsFailFromQuarantine(event, REASON);
        assertThat(result).isEqualTo(resolvedEvent);
    }

    @ParameterizedTest
    @EnumSource(StockRecoveryCompensationResult.class)
    @DisplayName("resolve - 보상 결과(OK/ALREADY_DONE/NO_DECREMENT) 무관하게 전이를 진행한다")
    void resolve_AllCompensationResults_ShouldProceedTransition(StockRecoveryCompensationResult compensationResult) {
        // given
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.QUARANTINED, List.of());
        PaymentEvent resolvedEvent = buildPaymentEvent(PaymentEventStatus.FAILED, List.of());

        given(paymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(event);
        given(stockCachePort.compensateIfDecremented(ORDER_ID, event.getPaymentOrderList()))
                .willReturn(compensationResult);
        given(paymentCommandUseCase.markPaymentAsFailFromQuarantine(event, REASON))
                .willReturn(resolvedEvent);

        // when
        PaymentEvent result = quarantineResolveUseCase.resolve(ORDER_ID, REASON);

        // then
        then(paymentCommandUseCase).should(times(1)).markPaymentAsFailFromQuarantine(event, REASON);
        assertThat(result).isEqualTo(resolvedEvent);
    }

    @Test
    @DisplayName("resolve - CAS 충돌로 도메인 전이가 예외를 던지면 그대로 전파한다")
    void resolve_WhenTransitionThrowsConflict_ShouldPropagate() {
        // given
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.QUARANTINED, List.of());

        given(paymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(event);
        given(stockCachePort.compensateIfDecremented(ORDER_ID, event.getPaymentOrderList()))
                .willReturn(StockRecoveryCompensationResult.OK);
        given(paymentCommandUseCase.markPaymentAsFailFromQuarantine(event, REASON))
                .willThrow(PaymentStatusException.of(PaymentErrorCode.QUARANTINE_RESOLVE_CONFLICT));

        // when & then
        assertThatThrownBy(() -> quarantineResolveUseCase.resolve(ORDER_ID, REASON))
                .isInstanceOf(PaymentStatusException.class)
                .extracting("code")
                .isEqualTo(PaymentErrorCode.QUARANTINE_RESOLVE_CONFLICT.getCode());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("resolve - reason 누락(null/공백) 시 거부하고 어떤 협력자도 호출하지 않는다")
    void resolve_WhenReasonMissing_ShouldRejectAndSkipEverything(String blankReason) {
        // when & then
        assertThatThrownBy(() -> quarantineResolveUseCase.resolve(ORDER_ID, blankReason))
                .isInstanceOf(PaymentValidException.class)
                .extracting("code")
                .isEqualTo(PaymentErrorCode.QUARANTINE_RESOLVE_REASON_REQUIRED.getCode());
        then(paymentLoadUseCase).shouldHaveNoInteractions();
        then(stockCachePort).shouldHaveNoInteractions();
        then(paymentCommandUseCase).shouldHaveNoInteractions();
    }

    @ParameterizedTest
    @EnumSource(value = PaymentEventStatus.class, names = "QUARANTINED", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("resolve - 격리(QUARANTINED) 상태가 아니면 보상 호출 전에 거부한다")
    void resolve_WhenNotQuarantined_ShouldRejectBeforeCompensation(PaymentEventStatus nonQuarantinedStatus) {
        // given
        PaymentEvent event = buildPaymentEvent(nonQuarantinedStatus, List.of());

        given(paymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(event);

        // when & then
        assertThatThrownBy(() -> quarantineResolveUseCase.resolve(ORDER_ID, REASON))
                .isInstanceOf(PaymentStatusException.class)
                .extracting("code")
                .isEqualTo(PaymentErrorCode.INVALID_STATUS_TO_FAIL_FROM_QUARANTINE.getCode());
        then(stockCachePort).should(Mockito.never()).compensateIfDecremented(Mockito.anyString(), Mockito.anyList());
        then(paymentCommandUseCase).shouldHaveNoInteractions();
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
