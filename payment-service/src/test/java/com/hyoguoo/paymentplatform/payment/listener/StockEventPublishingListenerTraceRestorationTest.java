package com.hyoguoo.paymentplatform.payment.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import com.hyoguoo.paymentplatform.payment.application.event.StockCommitRequestedEvent;
import com.hyoguoo.paymentplatform.payment.application.event.StockRestoreRequestedEvent;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockCommitEventPublisherPort;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockRestoreEventPublisherPort;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.MDC;

/**
 * T-I4 RED — StockEventPublishingListener AFTER_COMMIT ContextSnapshot 복원 검증.
 *
 * <p>검증 목표:
 * <ul>
 *   <li>StockCommitRequestedEvent 에 포함된 ContextSnapshot 이 onStockCommitRequested 내부에서
 *       복원되어 publish 호출 시점에 MDC traceId 가 캡처 시점 값과 일치해야 한다.</li>
 *   <li>StockRestoreRequestedEvent 에 포함된 ContextSnapshot 이 onStockRestoreRequested 내부에서
 *       복원되어 publish 호출 시점에 MDC traceId 가 캡처 시점 값과 일치해야 한다.</li>
 *   <li>리스너 종료 후 MDC 가 원래 값(또는 빈 값)으로 복원되어야 한다.</li>
 * </ul>
 *
 * <p>시뮬레이션:
 * <ol>
 *   <li>producer 측: MDC 에 traceId=test-trace-123 설정 후 captureAll() → snapshot 생성
 *       → StockCommitRequestedEvent 에 snapshot 포함하여 발행.</li>
 *   <li>listener 측: AFTER_COMMIT 직후 MDC 가 비어 있는 상태에서 event 수신.</li>
 *   <li>listener 가 snapshot.setThreadLocals() 로 MDC 복원 → publish 호출.</li>
 *   <li>publish 호출 시점 MDC.get("traceId") == "test-trace-123" 검증.</li>
 *   <li>try-with-resources 종료 후 MDC.get("traceId") == null 검증.</li>
 * </ol>
 */
@DisplayName("StockEventPublishingListener — T-I4 AFTER_COMMIT ContextSnapshot 복원 검증")
class StockEventPublishingListenerTraceRestorationTest {

    private StockCommitEventPublisherPort stockCommitPublisher;
    private StockRestoreEventPublisherPort stockRestorePublisher;
    private SimpleMeterRegistry meterRegistry;
    private StockEventPublishingListener sut;

    @BeforeEach
    void setUp() {
        stockCommitPublisher = Mockito.mock(StockCommitEventPublisherPort.class);
        stockRestorePublisher = Mockito.mock(StockRestoreEventPublisherPort.class);
        meterRegistry = new SimpleMeterRegistry();
        sut = new StockEventPublishingListener(stockCommitPublisher, stockRestorePublisher, meterRegistry);
    }

    // -----------------------------------------------------------------------
    // TC-I4-1: onStockCommitRequested — publish 시점 MDC traceId 복원 검증
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("onStockCommitRequested — publish 시점에 producer 측 MDC traceId가 복원된다")
    void onStockCommitRequested_shouldRestoreTraceIdDuringPublish() {
        // given: producer 측 MDC 설정 + snapshot 캡처
        MDC.put("traceId", "test-trace-123");
        ContextSnapshot snapshot = ContextSnapshotFactory.builder().build().captureAll();
        MDC.clear(); // producer MDC 해제 (AFTER_COMMIT 시점 시뮬레이션)

        AtomicReference<String> traceIdAtPublish = new AtomicReference<>();
        Mockito.doAnswer(invocation -> {
            traceIdAtPublish.set(MDC.get("traceId"));
            return null;
        }).when(stockCommitPublisher).publish(any(), any(Integer.class), any());

        StockCommitRequestedEvent event = new StockCommitRequestedEvent(
                "evt-i4-commit-001", "order-i4-001", 42L, 3, "order-i4-001", snapshot
        );

        // when
        sut.onStockCommitRequested(event);

        // then — publish 시점 MDC traceId가 producer 측 값과 일치
        then(stockCommitPublisher).should(times(1)).publish(any(), any(Integer.class), any());
        assertThat(traceIdAtPublish.get()).isEqualTo("test-trace-123");

        // then — 리스너 종료 후 MDC 복원 (빈 값)
        assertThat(MDC.get("traceId")).isNull();
    }

    // -----------------------------------------------------------------------
    // TC-I4-2: onStockRestoreRequested — publish 시점 MDC traceId 복원 검증
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("onStockRestoreRequested — publish 시점에 producer 측 MDC traceId가 복원된다")
    void onStockRestoreRequested_shouldRestoreTraceIdDuringPublish() {
        // given: producer 측 MDC 설정 + snapshot 캡처
        MDC.put("traceId", "test-restore-trace-456");
        ContextSnapshot snapshot = ContextSnapshotFactory.builder().build().captureAll();
        MDC.clear(); // producer MDC 해제

        AtomicReference<String> traceIdAtPublish = new AtomicReference<>();
        Mockito.doAnswer(invocation -> {
            traceIdAtPublish.set(MDC.get("traceId"));
            return null;
        }).when(stockRestorePublisher).publishPayload(any());

        StockRestoreRequestedEvent event = new StockRestoreRequestedEvent(
                "a1b2c3d4-e5f6-7890-abcd-ef1234567890", "order-i4-002", 99L, 5, snapshot
        );

        // when
        sut.onStockRestoreRequested(event);

        // then — publish 시점 MDC traceId가 producer 측 값과 일치
        then(stockRestorePublisher).should(times(1)).publishPayload(any());
        assertThat(traceIdAtPublish.get()).isEqualTo("test-restore-trace-456");

        // then — 리스너 종료 후 MDC 복원
        assertThat(MDC.get("traceId")).isNull();
    }

    // -----------------------------------------------------------------------
    // TC-I4-3: listener 종료 후 MDC 가 원래 컨텍스트(호출 전 값)로 복원
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("onStockCommitRequested — 리스너 종료 후 MDC가 호출 전 컨텍스트로 복원된다")
    void onStockCommitRequested_afterListener_shouldRestoreOriginalMdc() {
        // given: 호출 스레드에 기존 traceId가 있는 상태 (호출 전 컨텍스트)
        MDC.put("traceId", "original-trace-caller");

        // snapshot은 다른 traceId로 캡처
        MDC.put("traceId", "captured-trace-producer");
        ContextSnapshot snapshot = ContextSnapshotFactory.builder().build().captureAll();

        // 호출 스레드 MDC를 호출 전 값으로 되돌림 (리스너 호출 스레드 컨텍스트 시뮬레이션)
        MDC.put("traceId", "original-trace-caller");

        StockCommitRequestedEvent event = new StockCommitRequestedEvent(
                "evt-i4-commit-003", "order-i4-003", 10L, 1, "order-i4-003", snapshot
        );

        // when
        sut.onStockCommitRequested(event);

        // then — 리스너 종료 후 호출 전 traceId로 복원
        assertThat(MDC.get("traceId")).isEqualTo("original-trace-caller");

        // cleanup
        MDC.clear();
    }
}
