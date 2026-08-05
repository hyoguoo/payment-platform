package com.hyoguoo.paymentplatform.payment.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import com.hyoguoo.paymentplatform.payment.application.dto.admin.PgVendorStatusInfo;
import com.hyoguoo.paymentplatform.payment.application.dto.admin.PgVendorStatusJudgement;
import com.hyoguoo.paymentplatform.payment.application.port.out.PgVendorStatusPort;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockCachePort;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockRecoveryCompensationResult;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.exception.PaymentStatusException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentValidException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
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

    @Mock
    private PgVendorStatusPort pgVendorStatusPort;

    @Test
    @DisplayName("resolve - 벤더 조회 → 보상(compensateIfDecremented) → 도메인 전이 순서로 호출한다 (SCR-6)")
    void resolve_ShouldCompensateBeforeTransition() {
        // given
        PaymentOrder order = buildPaymentOrder(40L, 7);
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.QUARANTINED, List.of(order));
        PaymentEvent resolvedEvent = buildPaymentEvent(PaymentEventStatus.FAILED, List.of(order));

        given(paymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(event);
        given(pgVendorStatusPort.lookup(ORDER_ID)).willReturn(vendorStatus(PgVendorStatusJudgement.FAILED, "CANCELED"));
        given(stockCachePort.compensateIfDecremented(ORDER_ID, event.getPaymentOrderList()))
                .willReturn(StockRecoveryCompensationResult.OK);
        given(paymentCommandUseCase.markPaymentAsFailFromQuarantine(Mockito.eq(event), Mockito.anyString()))
                .willReturn(resolvedEvent);

        // when
        PaymentEvent result = quarantineResolveUseCase.resolve(ORDER_ID, REASON);

        // then
        InOrder inOrder = Mockito.inOrder(pgVendorStatusPort, stockCachePort, paymentCommandUseCase);
        inOrder.verify(pgVendorStatusPort).lookup(ORDER_ID);
        inOrder.verify(stockCachePort).compensateIfDecremented(ORDER_ID, event.getPaymentOrderList());
        inOrder.verify(paymentCommandUseCase).markPaymentAsFailFromQuarantine(Mockito.eq(event), Mockito.anyString());
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
        given(pgVendorStatusPort.lookup(ORDER_ID)).willReturn(vendorStatus(PgVendorStatusJudgement.UNKNOWN, null));
        given(stockCachePort.compensateIfDecremented(ORDER_ID, event.getPaymentOrderList()))
                .willReturn(compensationResult);
        given(paymentCommandUseCase.markPaymentAsFailFromQuarantine(Mockito.eq(event), Mockito.anyString()))
                .willReturn(resolvedEvent);

        // when
        PaymentEvent result = quarantineResolveUseCase.resolve(ORDER_ID, REASON);

        // then
        then(paymentCommandUseCase).should(times(1))
                .markPaymentAsFailFromQuarantine(Mockito.eq(event), Mockito.anyString());
        assertThat(result).isEqualTo(resolvedEvent);
    }

    @Test
    @DisplayName("resolve - CAS 충돌로 도메인 전이가 예외를 던지면 그대로 전파한다")
    void resolve_WhenTransitionThrowsConflict_ShouldPropagate() {
        // given
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.QUARANTINED, List.of());

        given(paymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(event);
        given(pgVendorStatusPort.lookup(ORDER_ID)).willReturn(vendorStatus(PgVendorStatusJudgement.FAILED, "CANCELED"));
        given(stockCachePort.compensateIfDecremented(ORDER_ID, event.getPaymentOrderList()))
                .willReturn(StockRecoveryCompensationResult.OK);
        given(paymentCommandUseCase.markPaymentAsFailFromQuarantine(Mockito.eq(event), Mockito.anyString()))
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
        then(pgVendorStatusPort).shouldHaveNoInteractions();
        then(stockCachePort).shouldHaveNoInteractions();
        then(paymentCommandUseCase).shouldHaveNoInteractions();
    }

    @ParameterizedTest
    @EnumSource(value = PaymentEventStatus.class, names = "QUARANTINED", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("resolve - 격리(QUARANTINED) 상태가 아니면 벤더 조회·보상 호출 전에 거부한다")
    void resolve_WhenNotQuarantined_ShouldRejectBeforeCompensation(PaymentEventStatus nonQuarantinedStatus) {
        // given
        PaymentEvent event = buildPaymentEvent(nonQuarantinedStatus, List.of());

        given(paymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(event);

        // when & then
        assertThatThrownBy(() -> quarantineResolveUseCase.resolve(ORDER_ID, REASON))
                .isInstanceOf(PaymentStatusException.class)
                .extracting("code")
                .isEqualTo(PaymentErrorCode.INVALID_STATUS_TO_FAIL_FROM_QUARANTINE.getCode());
        // 비격리 건은 벤더 조회조차 나가지 않는다 — 조회는 격리 상태 확인 이후에만 수행된다
        then(pgVendorStatusPort).shouldHaveNoInteractions();
        then(stockCachePort).should(Mockito.never()).compensateIfDecremented(Mockito.anyString(), Mockito.anyList());
        then(paymentCommandUseCase).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("resolve - 벤더 조회가 승인(APPROVED)이면 종결을 거부한다")
    void resolve_WhenVendorApproved_ShouldRejectResolve() {
        // given
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.QUARANTINED, List.of());

        given(paymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(event);
        given(pgVendorStatusPort.lookup(ORDER_ID)).willReturn(vendorStatus(PgVendorStatusJudgement.APPROVED, "DONE"));

        // when & then
        assertThatThrownBy(() -> quarantineResolveUseCase.resolve(ORDER_ID, REASON))
                .isInstanceOf(PaymentStatusException.class)
                .extracting("code")
                .isEqualTo(PaymentErrorCode.QUARANTINE_RESOLVE_VENDOR_APPROVED.getCode());
    }

    @Test
    @DisplayName("resolve - 벤더 조회가 승인이면 재고 보상을 호출하지 않는다")
    void resolve_WhenVendorApproved_ShouldNotCompensateStock() {
        // given
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.QUARANTINED, List.of());

        given(paymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(event);
        given(pgVendorStatusPort.lookup(ORDER_ID)).willReturn(vendorStatus(PgVendorStatusJudgement.APPROVED, "DONE"));

        // when
        assertThatThrownBy(() -> quarantineResolveUseCase.resolve(ORDER_ID, REASON))
                .isInstanceOf(PaymentStatusException.class);

        // then — 보상은 비가역이라 승인이 확인된 건에는 절대 나가면 안 된다
        then(stockCachePort).should(Mockito.never()).compensateIfDecremented(Mockito.anyString(), Mockito.anyList());
        then(paymentCommandUseCase).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("resolve - 벤더 조회가 실패(FAILED)면 종결을 진행한다")
    void resolve_WhenVendorFailed_ShouldProceedResolve() {
        // given
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.QUARANTINED, List.of());
        PaymentEvent resolvedEvent = buildPaymentEvent(PaymentEventStatus.FAILED, List.of());

        given(paymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(event);
        given(pgVendorStatusPort.lookup(ORDER_ID)).willReturn(vendorStatus(PgVendorStatusJudgement.FAILED, "CANCELED"));
        given(stockCachePort.compensateIfDecremented(ORDER_ID, event.getPaymentOrderList()))
                .willReturn(StockRecoveryCompensationResult.OK);
        given(paymentCommandUseCase.markPaymentAsFailFromQuarantine(Mockito.eq(event), Mockito.anyString()))
                .willReturn(resolvedEvent);

        // when
        PaymentEvent result = quarantineResolveUseCase.resolve(ORDER_ID, REASON);

        // then
        assertThat(result).isEqualTo(resolvedEvent);
        then(stockCachePort).should(times(1)).compensateIfDecremented(ORDER_ID, event.getPaymentOrderList());
    }

    @Test
    @DisplayName("resolve - 벤더 조회가 확인불가(UNKNOWN)면 종결을 진행한다")
    void resolve_WhenVendorUnknown_ShouldProceedResolve() {
        // given
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.QUARANTINED, List.of());
        PaymentEvent resolvedEvent = buildPaymentEvent(PaymentEventStatus.FAILED, List.of());

        given(paymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(event);
        given(pgVendorStatusPort.lookup(ORDER_ID)).willReturn(vendorStatus(PgVendorStatusJudgement.UNKNOWN, null));
        given(stockCachePort.compensateIfDecremented(ORDER_ID, event.getPaymentOrderList()))
                .willReturn(StockRecoveryCompensationResult.OK);
        given(paymentCommandUseCase.markPaymentAsFailFromQuarantine(Mockito.eq(event), Mockito.anyString()))
                .willReturn(resolvedEvent);

        // when
        PaymentEvent result = quarantineResolveUseCase.resolve(ORDER_ID, REASON);

        // then
        assertThat(result).isEqualTo(resolvedEvent);
        then(stockCachePort).should(times(1)).compensateIfDecremented(ORDER_ID, event.getPaymentOrderList());
    }

    @Test
    @DisplayName("resolve - 종결 사유에 벤더 조회 결과가 덧붙는다")
    void resolve_ShouldAppendVendorStatusToReason() {
        // given
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.QUARANTINED, List.of());
        PaymentEvent resolvedEvent = buildPaymentEvent(PaymentEventStatus.FAILED, List.of());

        given(paymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(event);
        given(pgVendorStatusPort.lookup(ORDER_ID)).willReturn(vendorStatus(PgVendorStatusJudgement.FAILED, "CANCELED"));
        given(stockCachePort.compensateIfDecremented(ORDER_ID, event.getPaymentOrderList()))
                .willReturn(StockRecoveryCompensationResult.OK);
        given(paymentCommandUseCase.markPaymentAsFailFromQuarantine(Mockito.eq(event), Mockito.anyString()))
                .willReturn(resolvedEvent);

        // when
        quarantineResolveUseCase.resolve(ORDER_ID, REASON);

        // then
        ArgumentCaptor<String> reasonCaptor = ArgumentCaptor.forClass(String.class);
        then(paymentCommandUseCase).should().markPaymentAsFailFromQuarantine(Mockito.eq(event), reasonCaptor.capture());
        assertThat(reasonCaptor.getValue()).isEqualTo(REASON + " / 벤더 상태 조회 결과: 실패(CANCELED)");
    }

    // ---- factory helpers ----

    private PgVendorStatusInfo vendorStatus(PgVendorStatusJudgement judgement, String vendorStatus) {
        return PgVendorStatusInfo.of(judgement, vendorStatus, Instant.parse("2026-08-06T00:00:00Z"));
    }

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
