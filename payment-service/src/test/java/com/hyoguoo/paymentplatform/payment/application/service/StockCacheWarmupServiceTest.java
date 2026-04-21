package com.hyoguoo.paymentplatform.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hyoguoo.paymentplatform.payment.infrastructure.messaging.consumer.dto.StockSnapshotEvent;
import com.hyoguoo.paymentplatform.payment.mock.FakeStockCachePort;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

@DisplayName("StockCacheWarmupService 테스트")
class StockCacheWarmupServiceTest {

    private FakeStockCachePort fakeStockCachePort;
    private StockCacheWarmupService warmupService;

    @BeforeEach
    void setUp() {
        fakeStockCachePort = new FakeStockCachePort();
        warmupService = new StockCacheWarmupService(fakeStockCachePort);
    }

    @Test
    @DisplayName("onApplicationReady - snapshot 항목을 StockCachePort에 SET한다")
    void onApplicationReady_ShouldPopulateCacheFromSnapshotTopic() {
        // given
        List<StockSnapshotEvent> snapshots = List.of(
                new StockSnapshotEvent(1L, 100, Instant.parse("2026-04-21T10:00:00Z")),
                new StockSnapshotEvent(2L, 50, Instant.parse("2026-04-21T10:00:00Z"))
        );

        // when
        warmupService.applySnapshots(snapshots);

        // then
        assertThat(fakeStockCachePort.current(1L)).isEqualTo(100);
        assertThat(fakeStockCachePort.current(2L)).isEqualTo(50);
        assertThat(warmupService.isWarmupCompleted()).isTrue();
    }

    @Test
    @DisplayName("warmup_WhenTopicEmpty - 빈 토픽이면 캐시를 설정하지 않고 경고 로그 후 완료 처리")
    void warmup_WhenTopicEmpty_ShouldLeaveEmptyCacheAndLog() {
        // given: empty snapshot list

        // when
        warmupService.applySnapshots(List.of());

        // then: 캐시 미설정, warmup 완료 플래그 true
        assertThat(fakeStockCachePort.getInternalMap()).isEmpty();
        assertThat(warmupService.isWarmupCompleted()).isTrue();
    }

    @Test
    @DisplayName("warmup_DuplicateSnapshot - 동일 productId 복수이면 마지막 수신값으로 덮어쓴다")
    void warmup_DuplicateSnapshot_ShouldUseLatestValue() {
        // given: 동일 productId에 두 snapshot (순서대로 100, 200)
        Instant t1 = Instant.parse("2026-04-21T10:00:00Z");
        Instant t2 = Instant.parse("2026-04-21T10:01:00Z");
        List<StockSnapshotEvent> snapshots = List.of(
                new StockSnapshotEvent(1L, 100, t1),
                new StockSnapshotEvent(1L, 200, t2)
        );

        // when
        warmupService.applySnapshots(snapshots);

        // then: 최신(마지막) 값 200이 설정되어야 함
        assertThat(fakeStockCachePort.current(1L)).isEqualTo(200);
    }

    @Test
    @DisplayName("warmup_AfterCompletion - warmup 완료 후 decrement()가 즉시 동작한다")
    void warmup_AfterCompletion_ShouldAllowDecrementImmediately() {
        // given: warmup 완료
        List<StockSnapshotEvent> snapshots = List.of(
                new StockSnapshotEvent(1L, 100, Instant.parse("2026-04-21T10:00:00Z"))
        );
        warmupService.applySnapshots(snapshots);
        assertThat(warmupService.isWarmupCompleted()).isTrue();

        // when: decrement 호출
        boolean result = fakeStockCachePort.decrement(1L, 10);

        // then: 차감 성공, 잔여 90
        assertThat(result).isTrue();
        assertThat(fakeStockCachePort.current(1L)).isEqualTo(90);
    }
}
