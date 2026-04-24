package com.hyoguoo.paymentplatform.payment.listener;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.hyoguoo.paymentplatform.payment.application.dto.StockRestoreEventPayload;
import com.hyoguoo.paymentplatform.payment.application.event.StockCommitRequestedEvent;
import com.hyoguoo.paymentplatform.payment.application.event.StockRestoreRequestedEvent;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockCommitEventPublisherPort;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockRestoreEventPublisherPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * T-D2 RED 테스트: StockEventPublishingListener
 * - StockCommitRequestedEvent 수신 → stockCommitEventPublisherPort.publish 1회 호출
 * - StockRestoreRequestedEvent 수신 → stockRestoreEventPublisherPort.publishPayload 1회 호출
 * - Kafka publish 실패 시 re-throw 안 함(TX는 이미 commit)
 */
@DisplayName("StockEventPublishingListener — AFTER_COMMIT 리스너 위임 검증")
class StockEventPublishingListenerTest {

    private StockCommitEventPublisherPort stockCommitPublisher;
    private StockRestoreEventPublisherPort stockRestorePublisher;
    private StockEventPublishingListener sut;

    @BeforeEach
    void setUp() {
        stockCommitPublisher = Mockito.mock(StockCommitEventPublisherPort.class);
        stockRestorePublisher = Mockito.mock(StockRestoreEventPublisherPort.class);
        sut = new StockEventPublishingListener(stockCommitPublisher, stockRestorePublisher);
    }

    // -----------------------------------------------------------------------
    // TC-D2-3: StockCommitRequestedEvent → stockCommitEventPublisherPort.publish 1회 호출
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("onStockCommitEvent — stockCommitEventPublisherPort.publish 1회 호출")
    void onStockCommitEvent_shouldDelegateToPublisher() {
        // given
        StockCommitRequestedEvent event = new StockCommitRequestedEvent(
                "evt-d2-commit-001", "order-001", 42L, 3, "order-001"
        );

        // when
        sut.onStockCommitRequested(event);

        // then
        then(stockCommitPublisher)
                .should(times(1))
                .publish(eq(42L), eq(3), eq("order-001"));

        then(stockRestorePublisher).should(never()).publishPayload(any());
    }

    // -----------------------------------------------------------------------
    // TC-D2-4: StockRestoreRequestedEvent → stockRestoreEventPublisherPort.publishPayload 1회 호출
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("onStockRestoreEvent — stockRestoreEventPublisherPort.publishPayload 1회 호출")
    void onStockRestoreEvent_shouldDelegateToPublisher() {
        // given
        StockRestoreRequestedEvent event = new StockRestoreRequestedEvent(
                "a1b2c3d4-e5f6-7890-abcd-ef1234567890", "order-002", 99L, 5
        );

        // when
        sut.onStockRestoreRequested(event);

        // then
        then(stockRestorePublisher)
                .should(times(1))
                .publishPayload(any(StockRestoreEventPayload.class));

        then(stockCommitPublisher).should(never()).publish(any(), any(Integer.class), any());
    }

    // -----------------------------------------------------------------------
    // TC-D2-5(선택): Kafka publish 실패 시 listener가 re-throw 하지 않음
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("onStockCommitEvent — Kafka publish 실패 시 listener가 예외를 삼킨다(TX 이미 commit)")
    void whenCommitPublishFails_shouldLogErrorAndSwallow() {
        // given
        doThrow(new RuntimeException("Kafka broker down"))
                .when(stockCommitPublisher)
                .publish(any(), any(Integer.class), any());

        StockCommitRequestedEvent event = new StockCommitRequestedEvent(
                "evt-fail-001", "order-fail", 1L, 1, "order-fail"
        );

        // when & then — 예외 전파 없음
        sut.onStockCommitRequested(event);
        // 도달하면 PASS
    }
}
