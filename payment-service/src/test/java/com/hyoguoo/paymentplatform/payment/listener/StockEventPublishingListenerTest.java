package com.hyoguoo.paymentplatform.payment.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.hyoguoo.paymentplatform.payment.application.dto.StockRestoreEventPayload;
import com.hyoguoo.paymentplatform.payment.application.event.StockCommitRequestedEvent;
import com.hyoguoo.paymentplatform.payment.application.event.StockRestoreRequestedEvent;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockCommitEventPublisherPort;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockRestoreEventPublisherPort;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.context.Context;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * T-D2 + T-H2 RED 테스트: StockEventPublishingListener
 * - StockCommitRequestedEvent 수신 → stockCommitEventPublisherPort.publish 1회 호출
 * - StockRestoreRequestedEvent 수신 → stockRestoreEventPublisherPort.publishPayload 1회 호출
 * - Kafka publish 실패 시 re-throw 안 함(TX는 이미 commit)
 * - T-H2: Kafka publish 실패 시 stock.kafka.publish.fail.total counter 증가
 */
@DisplayName("StockEventPublishingListener — AFTER_COMMIT 리스너 위임 검증")
class StockEventPublishingListenerTest {

    private StockCommitEventPublisherPort stockCommitPublisher;
    private StockRestoreEventPublisherPort stockRestorePublisher;
    private SimpleMeterRegistry meterRegistry;
    private StockEventPublishingListener sut;

    /** 빈 context snapshot — 기존 테스트에서 contextSnapshot 인자로 사용. */
    private static ContextSnapshot emptySnapshot() {
        return ContextSnapshotFactory.builder().build().captureAll();
    }

    @BeforeEach
    void setUp() {
        stockCommitPublisher = Mockito.mock(StockCommitEventPublisherPort.class);
        stockRestorePublisher = Mockito.mock(StockRestoreEventPublisherPort.class);
        meterRegistry = new SimpleMeterRegistry();
        sut = new StockEventPublishingListener(stockCommitPublisher, stockRestorePublisher, meterRegistry);
    }

    // -----------------------------------------------------------------------
    // TC-D2-3: StockCommitRequestedEvent → stockCommitEventPublisherPort.publish 1회 호출
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("onStockCommitEvent — stockCommitEventPublisherPort.publish 1회 호출")
    void onStockCommitEvent_shouldDelegateToPublisher() {
        // given
        StockCommitRequestedEvent event = new StockCommitRequestedEvent(
                "evt-d2-commit-001", "order-001", 42L, 3, "order-001", emptySnapshot(), Context.root()
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
                "a1b2c3d4-e5f6-7890-abcd-ef1234567890", "order-002", 99L, 5, emptySnapshot(), Context.root()
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
                "evt-fail-001", "order-fail", 1L, 1, "order-fail", emptySnapshot(), Context.root()
        );

        // when & then — 예외 전파 없음
        sut.onStockCommitRequested(event);
        // 도달하면 PASS
    }

    // -----------------------------------------------------------------------
    // TC-H2-1: StockCommit publish 실패 시 stock.kafka.publish.fail.total tag event=commit 증가
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("onStockCommitRequested — publish 실패 시 stock.kafka.publish.fail.total[event=commit] 카운터 1 증가")
    void onStockCommitRequested_whenPublishFails_shouldIncrementCounter() {
        // given
        doThrow(new RuntimeException("Kafka broker down"))
                .when(stockCommitPublisher)
                .publish(any(), any(Integer.class), any());

        StockCommitRequestedEvent event = new StockCommitRequestedEvent(
                "evt-h2-commit-001", "order-h2-commit", 10L, 2, "order-h2-commit", emptySnapshot(), Context.root()
        );

        // when
        sut.onStockCommitRequested(event);

        // then — counter 1 증가
        Counter counter = meterRegistry.find("stock.kafka.publish.fail.total")
                .tag("event", "commit")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    // -----------------------------------------------------------------------
    // TC-H2-2: StockRestore publish 실패 시 stock.kafka.publish.fail.total tag event=restore 증가
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("onStockRestoreRequested — publish 실패 시 stock.kafka.publish.fail.total[event=restore] 카운터 1 증가")
    void onStockRestoreRequested_whenPublishFails_shouldIncrementCounter() {
        // given
        doThrow(new RuntimeException("Kafka broker down"))
                .when(stockRestorePublisher)
                .publishPayload(any());

        StockRestoreRequestedEvent event = new StockRestoreRequestedEvent(
                "a1b2c3d4-e5f6-7890-abcd-ef1234567890", "order-h2-restore", 20L, 3, emptySnapshot(), Context.root()
        );

        // when
        sut.onStockRestoreRequested(event);

        // then — counter 1 증가
        Counter counter = meterRegistry.find("stock.kafka.publish.fail.total")
                .tag("event", "restore")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }
}
