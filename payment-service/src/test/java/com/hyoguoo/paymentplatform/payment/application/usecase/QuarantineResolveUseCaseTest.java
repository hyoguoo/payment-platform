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
import com.hyoguoo.paymentplatform.payment.application.port.out.StockHoldRecordRepository;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockHoldRecordSnapshot;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockRecoveryCompensationResult;
import com.hyoguoo.paymentplatform.payment.application.util.StockHoldReverter;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.StockHoldRecordStatus;
import com.hyoguoo.paymentplatform.payment.exception.PaymentStatusException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentValidException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import com.hyoguoo.paymentplatform.payment.mock.FakeStockCachePort;
import com.hyoguoo.paymentplatform.payment.mock.FakeStockHoldRecordRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import org.mockito.Spy;
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
    private StockHoldRecordRepository stockHoldRecordRepository;

    @Mock
    private PaymentCommandUseCase paymentCommandUseCase;

    @Mock
    private PgVendorStatusPort pgVendorStatusPort;

    @Spy
    private StockHoldReverter stockHoldReverter = new StockHoldReverter(new SimpleMeterRegistry());

    @Test
    @DisplayName("resolve - 벤더 조회 → 상품별 되돌리기(흔적 확인 → 보상 → 기록 닫기) → 도메인 전이 순서로 호출한다")
    void resolve_ShouldRevertBeforeTransition() {
        // given
        PaymentOrder order = buildPaymentOrder(40L, 7);
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.QUARANTINED, List.of(order));
        PaymentEvent resolvedEvent = buildPaymentEvent(PaymentEventStatus.FAILED, List.of(order));
        String cycleToken = "cycle-quarantine-resolve-001";

        given(paymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(event);
        given(pgVendorStatusPort.lookup(ORDER_ID)).willReturn(vendorStatus(PgVendorStatusJudgement.FAILED, "CANCELED"));
        given(stockHoldRecordRepository.findSnapshot(ORDER_ID, order))
                .willReturn(Optional.of(new StockHoldRecordSnapshot(StockHoldRecordStatus.NOISE, cycleToken)));
        given(stockCachePort.compensateIfDecremented(ORDER_ID, order))
                .willReturn(StockRecoveryCompensationResult.OK);
        given(paymentCommandUseCase.markPaymentAsFailFromQuarantine(Mockito.eq(event), Mockito.anyString()))
                .willReturn(resolvedEvent);

        // when
        PaymentEvent result = quarantineResolveUseCase.resolve(ORDER_ID, REASON);

        // then
        InOrder inOrder = Mockito.inOrder(pgVendorStatusPort, stockHoldRecordRepository, stockCachePort, paymentCommandUseCase);
        inOrder.verify(pgVendorStatusPort).lookup(ORDER_ID);
        inOrder.verify(stockHoldRecordRepository).findSnapshot(ORDER_ID, order);
        inOrder.verify(stockCachePort).compensateIfDecremented(ORDER_ID, order);
        inOrder.verify(stockHoldRecordRepository).closeAsReverted(ORDER_ID, order, cycleToken);
        inOrder.verify(paymentCommandUseCase).markPaymentAsFailFromQuarantine(Mockito.eq(event), Mockito.anyString());
        assertThat(result).isEqualTo(resolvedEvent);
        then(stockHoldReverter).should().revertEachProductHold(event, stockCachePort, stockHoldRecordRepository);
    }

    @ParameterizedTest
    @EnumSource(StockRecoveryCompensationResult.class)
    @DisplayName("resolve - 보상 결과(OK/ALREADY_DONE/NO_DECREMENT) 무관하게 전이를 진행한다")
    void resolve_AllCompensationResults_ShouldProceedTransition(StockRecoveryCompensationResult compensationResult) {
        // given
        PaymentOrder order = buildPaymentOrder(41L, 2);
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.QUARANTINED, List.of(order));
        PaymentEvent resolvedEvent = buildPaymentEvent(PaymentEventStatus.FAILED, List.of(order));

        given(paymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(event);
        given(pgVendorStatusPort.lookup(ORDER_ID)).willReturn(vendorStatus(PgVendorStatusJudgement.UNKNOWN, null));
        given(stockHoldRecordRepository.findSnapshot(ORDER_ID, order))
                .willReturn(Optional.of(new StockHoldRecordSnapshot(StockHoldRecordStatus.NOISE, "cycle-x")));
        given(stockCachePort.compensateIfDecremented(ORDER_ID, order))
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
        then(stockHoldRecordRepository).shouldHaveNoInteractions();
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
        then(stockHoldRecordRepository).shouldHaveNoInteractions();
        then(stockCachePort).should(Mockito.never()).compensateIfDecremented(Mockito.anyString(), Mockito.any(PaymentOrder.class));
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
    @DisplayName("resolve - 벤더 조회가 승인이면 재고 되돌리기를 호출하지 않는다")
    void resolve_WhenVendorApproved_ShouldNotRevertStock() {
        // given
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.QUARANTINED, List.of());

        given(paymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(event);
        given(pgVendorStatusPort.lookup(ORDER_ID)).willReturn(vendorStatus(PgVendorStatusJudgement.APPROVED, "DONE"));

        // when
        assertThatThrownBy(() -> quarantineResolveUseCase.resolve(ORDER_ID, REASON))
                .isInstanceOf(PaymentStatusException.class);

        // then — 되돌리기는 비가역이라 승인이 확인된 건에는 절대 나가면 안 된다
        then(stockHoldRecordRepository).shouldHaveNoInteractions();
        then(stockCachePort).should(Mockito.never()).compensateIfDecremented(Mockito.anyString(), Mockito.any(PaymentOrder.class));
        then(paymentCommandUseCase).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("resolve - 벤더 조회가 실패(FAILED)면 종결을 진행한다")
    void resolve_WhenVendorFailed_ShouldProceedResolve() {
        // given
        PaymentOrder order = buildPaymentOrder(42L, 1);
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.QUARANTINED, List.of(order));
        PaymentEvent resolvedEvent = buildPaymentEvent(PaymentEventStatus.FAILED, List.of(order));

        given(paymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(event);
        given(pgVendorStatusPort.lookup(ORDER_ID)).willReturn(vendorStatus(PgVendorStatusJudgement.FAILED, "CANCELED"));
        given(stockHoldRecordRepository.findSnapshot(ORDER_ID, order))
                .willReturn(Optional.of(new StockHoldRecordSnapshot(StockHoldRecordStatus.NOISE, "cycle-y")));
        given(stockCachePort.compensateIfDecremented(ORDER_ID, order))
                .willReturn(StockRecoveryCompensationResult.OK);
        given(paymentCommandUseCase.markPaymentAsFailFromQuarantine(Mockito.eq(event), Mockito.anyString()))
                .willReturn(resolvedEvent);

        // when
        PaymentEvent result = quarantineResolveUseCase.resolve(ORDER_ID, REASON);

        // then
        assertThat(result).isEqualTo(resolvedEvent);
        then(stockCachePort).should(times(1)).compensateIfDecremented(ORDER_ID, order);
    }

    @Test
    @DisplayName("resolve - 벤더 조회가 확인불가(UNKNOWN)면 종결을 진행한다")
    void resolve_WhenVendorUnknown_ShouldProceedResolve() {
        // given
        PaymentOrder order = buildPaymentOrder(43L, 1);
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.QUARANTINED, List.of(order));
        PaymentEvent resolvedEvent = buildPaymentEvent(PaymentEventStatus.FAILED, List.of(order));

        given(paymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(event);
        given(pgVendorStatusPort.lookup(ORDER_ID)).willReturn(vendorStatus(PgVendorStatusJudgement.UNKNOWN, null));
        given(stockHoldRecordRepository.findSnapshot(ORDER_ID, order))
                .willReturn(Optional.of(new StockHoldRecordSnapshot(StockHoldRecordStatus.NOISE, "cycle-z")));
        given(stockCachePort.compensateIfDecremented(ORDER_ID, order))
                .willReturn(StockRecoveryCompensationResult.OK);
        given(paymentCommandUseCase.markPaymentAsFailFromQuarantine(Mockito.eq(event), Mockito.anyString()))
                .willReturn(resolvedEvent);

        // when
        PaymentEvent result = quarantineResolveUseCase.resolve(ORDER_ID, REASON);

        // then
        assertThat(result).isEqualTo(resolvedEvent);
        then(stockCachePort).should(times(1)).compensateIfDecremented(ORDER_ID, order);
    }

    @Test
    @DisplayName("resolve - 종결 사유에 벤더 조회 결과가 덧붙는다")
    void resolve_ShouldAppendVendorStatusToReason() {
        // given
        PaymentEvent event = buildPaymentEvent(PaymentEventStatus.QUARANTINED, List.of());
        PaymentEvent resolvedEvent = buildPaymentEvent(PaymentEventStatus.FAILED, List.of());

        given(paymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(event);
        given(pgVendorStatusPort.lookup(ORDER_ID)).willReturn(vendorStatus(PgVendorStatusJudgement.FAILED, "CANCELED"));
        given(paymentCommandUseCase.markPaymentAsFailFromQuarantine(Mockito.eq(event), Mockito.anyString()))
                .willReturn(resolvedEvent);

        // when
        quarantineResolveUseCase.resolve(ORDER_ID, REASON);

        // then
        ArgumentCaptor<String> reasonCaptor = ArgumentCaptor.forClass(String.class);
        then(paymentCommandUseCase).should().markPaymentAsFailFromQuarantine(Mockito.eq(event), reasonCaptor.capture());
        assertThat(reasonCaptor.getValue()).isEqualTo(REASON + " / 벤더 상태 조회 결과: 실패(CANCELED)");
    }

    @Nested
    @DisplayName("상품별 되돌리기 — 캐시 재고 값 복원까지 단정 (FakeStockCachePort/FakeStockHoldRecordRepository 사용)")
    class RevertPerProductTest {

        private PaymentLoadUseCase fakePaymentLoadUseCase;
        private FakeStockCachePort fakeStockCachePort;
        private FakeStockHoldRecordRepository fakeStockHoldRecordRepository;
        private PaymentCommandUseCase fakePaymentCommandUseCase;
        private PgVendorStatusPort fakePgVendorStatusPort;
        private QuarantineResolveUseCase sutWithFake;

        @BeforeEach
        void setUp() {
            fakePaymentLoadUseCase = Mockito.mock(PaymentLoadUseCase.class);
            fakeStockCachePort = new FakeStockCachePort();
            fakeStockHoldRecordRepository = new FakeStockHoldRecordRepository();
            fakePaymentCommandUseCase = Mockito.mock(PaymentCommandUseCase.class);
            fakePgVendorStatusPort = Mockito.mock(PgVendorStatusPort.class);

            sutWithFake = new QuarantineResolveUseCase(
                    fakePaymentLoadUseCase,
                    fakePgVendorStatusPort,
                    fakeStockCachePort,
                    fakeStockHoldRecordRepository,
                    fakePaymentCommandUseCase,
                    new StockHoldReverter(new SimpleMeterRegistry())
            );

            given(fakePgVendorStatusPort.lookup(ORDER_ID))
                    .willReturn(vendorStatus(PgVendorStatusJudgement.FAILED, "CANCELED"));
        }

        @Test
        @DisplayName("선차감 흔적이 있는 상품은 되돌아가고 기록이 되돌림으로 닫힌다 — 재고 값이 원래대로 복원된다")
        void 선차감_흔적_있으면_재고_복원되고_기록_닫힘() {
            PaymentOrder order = buildPaymentOrder(1L, 3);
            PaymentEvent event = buildPaymentEvent(PaymentEventStatus.QUARANTINED, List.of(order));
            given(fakePaymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(event);
            given(fakePaymentCommandUseCase.markPaymentAsFailFromQuarantine(Mockito.eq(event), Mockito.anyString()))
                    .willReturn(event);

            // 체크아웃 시점의 선차감을 흉내: 기록을 열고 캐시를 차감한다
            fakeStockCachePort.set(1L, 10);
            fakeStockHoldRecordRepository.openHold(ORDER_ID, order);
            fakeStockCachePort.decrementAtomic(ORDER_ID, order);
            assertThat(fakeStockCachePort.current(1L)).isEqualTo(7);

            sutWithFake.resolve(ORDER_ID, REASON);

            assertThat(fakeStockCachePort.current(1L)).isEqualTo(10);
            assertThat(fakeStockHoldRecordRepository.statusOf(ORDER_ID, 1L))
                    .contains(StockHoldRecordStatus.REVERTED);
        }

        @Test
        @DisplayName("선차감 흔적이 없는 상품은 되돌리지 않고 기록만 닫는다 — 재고 값이 변하지 않는다")
        void 선차감_흔적_없으면_재고_불변_기록만_닫힘() {
            PaymentOrder order = buildPaymentOrder(2L, 3);
            PaymentEvent event = buildPaymentEvent(PaymentEventStatus.QUARANTINED, List.of(order));
            given(fakePaymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(event);
            given(fakePaymentCommandUseCase.markPaymentAsFailFromQuarantine(Mockito.eq(event), Mockito.anyString()))
                    .willReturn(event);

            // 기록만 열고 캐시 차감은 아직 일어나지 않은 상태
            fakeStockCachePort.set(2L, 10);
            fakeStockHoldRecordRepository.openHold(ORDER_ID, order);

            sutWithFake.resolve(ORDER_ID, REASON);

            assertThat(fakeStockCachePort.current(2L)).isEqualTo(10);
            assertThat(fakeStockHoldRecordRepository.statusOf(ORDER_ID, 2L))
                    .contains(StockHoldRecordStatus.REVERTED);
        }

        @Test
        @DisplayName("캐시가 이미 처리됨을 돌려줘도 기록은 되돌림으로 닫힌다 — 다른 경로가 먼저 되돌린 경우")
        void 캐시_이미처리됨_이어도_기록은_되돌림으로_닫힘() {
            PaymentOrder order = buildPaymentOrder(3L, 3);
            PaymentEvent event = buildPaymentEvent(PaymentEventStatus.QUARANTINED, List.of(order));
            given(fakePaymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(event);
            given(fakePaymentCommandUseCase.markPaymentAsFailFromQuarantine(Mockito.eq(event), Mockito.anyString()))
                    .willReturn(event);

            // 선차감 후 다른 경로가 캐시만 먼저 되돌린 상태 — 기록은 아직 NOISE 로 남아있다
            fakeStockCachePort.set(3L, 10);
            fakeStockHoldRecordRepository.openHold(ORDER_ID, order);
            fakeStockCachePort.decrementAtomic(ORDER_ID, order);
            fakeStockCachePort.compensateIfDecremented(ORDER_ID, order);
            assertThat(fakeStockCachePort.current(3L)).isEqualTo(10);
            assertThat(fakeStockHoldRecordRepository.statusOf(ORDER_ID, 3L)).contains(StockHoldRecordStatus.NOISE);

            sutWithFake.resolve(ORDER_ID, REASON);

            // 캐시는 이미 처리됨(ALREADY_DONE)이라 재고 값이 그대로지만, 기록은 되돌림으로 닫혀야 한다
            assertThat(fakeStockCachePort.current(3L)).isEqualTo(10);
            assertThat(fakeStockHoldRecordRepository.statusOf(ORDER_ID, 3L))
                    .contains(StockHoldRecordStatus.REVERTED);
        }
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
