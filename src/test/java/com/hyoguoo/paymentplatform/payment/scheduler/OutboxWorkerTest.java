package com.hyoguoo.paymentplatform.payment.scheduler;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentOutboxUseCase;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOutbox;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOutboxStatus;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("OutboxWorker 테스트")
class OutboxWorkerTest {

    private static final String ORDER_ID_1 = "order-1";
    private static final String ORDER_ID_2 = "order-2";

    private PaymentOutboxUseCase mockPaymentOutboxUseCase;
    private OutboxProcessingService mockOutboxProcessingService;
    private OutboxWorker outboxWorker;

    @BeforeEach
    void setUp() {
        mockPaymentOutboxUseCase = Mockito.mock(PaymentOutboxUseCase.class);
        mockOutboxProcessingService = Mockito.mock(OutboxProcessingService.class);

        outboxWorker = new OutboxWorker(mockPaymentOutboxUseCase, mockOutboxProcessingService);
        ReflectionTestUtils.setField(outboxWorker, "batchSize", 10);
        ReflectionTestUtils.setField(outboxWorker, "parallelEnabled", false);
        ReflectionTestUtils.setField(outboxWorker, "inFlightTimeoutMinutes", 5);
    }

    @Test
    @DisplayName("process - PENDING 없음: OutboxProcessingService를 호출하지 않는다")
    void process_noPendingRecords_doesNotCallProcessingService() {
        // given
        given(mockPaymentOutboxUseCase.findPendingBatch(anyInt()))
                .willReturn(Collections.emptyList());

        // when
        outboxWorker.process();

        // then
        then(mockOutboxProcessingService).shouldHaveNoInteractions();
        then(mockPaymentOutboxUseCase).should(times(1)).recoverTimedOutInFlightRecords(5);
    }

    @Test
    @DisplayName("process - PENDING 2건: OutboxProcessingService.process()를 2회 위임한다")
    void process_pendingRecords_delegatesToProcessingService() {
        // given
        List<PaymentOutbox> pending = List.of(
                createPendingOutbox(ORDER_ID_1),
                createPendingOutbox(ORDER_ID_2)
        );
        given(mockPaymentOutboxUseCase.findPendingBatch(anyInt())).willReturn(pending);

        // when
        outboxWorker.process();

        // then
        then(mockOutboxProcessingService).should(times(2)).process(anyString());
        then(mockOutboxProcessingService).should(times(1)).process(ORDER_ID_1);
        then(mockOutboxProcessingService).should(times(1)).process(ORDER_ID_2);
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

    private PaymentOutbox createPendingOutbox(String orderId) {
        return PaymentOutbox.allArgsBuilder()
                .id(1L)
                .orderId(orderId)
                .status(PaymentOutboxStatus.PENDING)
                .retryCount(0)
                .allArgsBuild();
    }
}
