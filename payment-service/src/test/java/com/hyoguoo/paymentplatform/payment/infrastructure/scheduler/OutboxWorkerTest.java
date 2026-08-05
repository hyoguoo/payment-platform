package com.hyoguoo.paymentplatform.payment.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.hyoguoo.paymentplatform.payment.application.service.OutboxRelayService;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentOutboxUseCase;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOutbox;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOutboxStatus;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
/**
 * OutboxWorker 단위 테스트 — 생성자 @Value 주입을 검증하므로 ReflectionTestUtils 미사용.
 */
@DisplayName("OutboxWorker 테스트")
class OutboxWorkerTest {

    private static final String ORDER_ID_1 = "order-1";
    private static final String ORDER_ID_2 = "order-2";

    private PaymentOutboxUseCase mockPaymentOutboxUseCase;
    private OutboxRelayService mockOutboxRelayService;
    private OutboxWorker outboxWorker;

    @BeforeEach
    void setUp() {
        mockPaymentOutboxUseCase = Mockito.mock(PaymentOutboxUseCase.class);
        mockOutboxRelayService = Mockito.mock(OutboxRelayService.class);

        outboxWorker = new OutboxWorker(mockPaymentOutboxUseCase, mockOutboxRelayService, 10, false, 5);
    }

    @Test
    @DisplayName("process - PENDING 없음: OutboxRelayService를 호출하지 않는다")
    void process_noPendingRecords_doesNotCallRelayService() {
        // given
        given(mockPaymentOutboxUseCase.findPendingBatch(anyInt()))
                .willReturn(Collections.emptyList());

        // when
        outboxWorker.process();

        // then
        then(mockOutboxRelayService).shouldHaveNoInteractions();
        then(mockPaymentOutboxUseCase).should(times(1)).recoverTimedOutInFlightRecords(5);
    }

    @Test
    @DisplayName("process - PENDING 2건: OutboxRelayService.relay()를 2회 위임한다")
    void process_pendingRecords_delegatesToRelayService() {
        // given
        List<PaymentOutbox> pending = List.of(
                createPendingOutbox(ORDER_ID_1),
                createPendingOutbox(ORDER_ID_2)
        );
        given(mockPaymentOutboxUseCase.findPendingBatch(anyInt())).willReturn(pending);

        // when
        outboxWorker.process();

        // then
        then(mockOutboxRelayService).should(times(2)).relay(anyString());
        then(mockOutboxRelayService).should(times(1)).relay(ORDER_ID_1);
        then(mockOutboxRelayService).should(times(1)).relay(ORDER_ID_2);
    }

    @Test
    @DisplayName("process - IN_FLIGHT 타임아웃 복구: process() 시작 시 recoverTimedOutInFlightRecords() 1회 호출")
    void process_alwaysCallsRecoverTimedOutInFlightRecords() {
        // given
        given(mockPaymentOutboxUseCase.findPendingBatch(anyInt()))
                .willReturn(Collections.emptyList());

        // when
        outboxWorker.process();

        // then
        then(mockPaymentOutboxUseCase).should(times(1)).recoverTimedOutInFlightRecords(5);
    }

    @Test
    @DisplayName("process - 발행이 실패하면 간격 기록을 호출한다")
    void process_relayFails_callsRecordPublishFailureDelay() {
        // given
        given(mockPaymentOutboxUseCase.findPendingBatch(anyInt()))
                .willReturn(List.of(createPendingOutbox(ORDER_ID_1)));
        willThrow(new IllegalStateException("발행 실패 — Kafka send 실패 시뮬레이션"))
                .given(mockOutboxRelayService).relay(ORDER_ID_1);

        // when
        outboxWorker.process();

        // then
        then(mockPaymentOutboxUseCase).should(times(1)).recordPublishFailureDelay(ORDER_ID_1);
    }

    @Test
    @DisplayName("process - 발행이 성공하면 간격 기록을 호출하지 않는다")
    void process_relaySucceeds_doesNotCallRecordPublishFailureDelay() {
        // given
        given(mockPaymentOutboxUseCase.findPendingBatch(anyInt()))
                .willReturn(List.of(createPendingOutbox(ORDER_ID_1)));

        // when
        outboxWorker.process();

        // then
        then(mockPaymentOutboxUseCase).should(never()).recordPublishFailureDelay(anyString());
    }

    @Test
    @DisplayName("process - 간격 기록이 실패해도 다음 행 처리를 계속한다")
    void process_recordPublishFailureDelayFails_continuesToNextRecord() {
        // given
        given(mockPaymentOutboxUseCase.findPendingBatch(anyInt()))
                .willReturn(List.of(createPendingOutbox(ORDER_ID_1), createPendingOutbox(ORDER_ID_2)));
        willThrow(new IllegalStateException("발행 실패 — Kafka send 실패 시뮬레이션"))
                .given(mockOutboxRelayService).relay(ORDER_ID_1);
        willThrow(new IllegalStateException("간격 기록 실패 — DB 오류 시뮬레이션"))
                .given(mockPaymentOutboxUseCase).recordPublishFailureDelay(ORDER_ID_1);

        // when & then — 첫 행의 기록 실패가 두 번째 행 처리를 막지 않는다
        assertThatCode(() -> outboxWorker.process()).doesNotThrowAnyException();
        then(mockOutboxRelayService).should(times(1)).relay(ORDER_ID_2);
    }

    @Test
    @DisplayName("process - 병렬 처리에서도 발행 실패 시 같은 기록이 수행된다")
    void process_parallelEnabled_relayFails_callsRecordPublishFailureDelay() {
        // given
        OutboxWorker parallelWorker =
                new OutboxWorker(mockPaymentOutboxUseCase, mockOutboxRelayService, 10, true, 5);
        given(mockPaymentOutboxUseCase.findPendingBatch(anyInt()))
                .willReturn(List.of(createPendingOutbox(ORDER_ID_1)));
        willThrow(new IllegalStateException("발행 실패 — Kafka send 실패 시뮬레이션"))
                .given(mockOutboxRelayService).relay(ORDER_ID_1);

        // when
        parallelWorker.process();

        // then
        then(mockPaymentOutboxUseCase).should(times(1)).recordPublishFailureDelay(ORDER_ID_1);
    }

    private PaymentOutbox createPendingOutbox(String orderId) {
        return PaymentOutbox.allArgsBuilder()
                .id(1L)
                .orderId(orderId)
                .status(PaymentOutboxStatus.PENDING)
                .retryCount(0)
                .allArgsBuild();
    }
}
