package com.hyoguoo.paymentplatform.payment.infrastructure.messaging.consumer;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.hyoguoo.paymentplatform.payment.application.service.StockCacheWarmupService;
import com.hyoguoo.paymentplatform.payment.infrastructure.messaging.consumer.dto.StockSnapshotEvent;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("StockSnapshotWarmupConsumer 테스트")
class StockSnapshotWarmupConsumerTest {

    private StockCacheWarmupService mockWarmupService;
    private StockSnapshotWarmupConsumer consumer;

    @BeforeEach
    void setUp() {
        mockWarmupService = mock(StockCacheWarmupService.class);
        consumer = new StockSnapshotWarmupConsumer(mockWarmupService);
    }

    @Test
    @DisplayName("consume - StockSnapshotEvent 수신 시 WarmupService에 1회 위임한다")
    void consume_ShouldDelegateToWarmupService() {
        // given
        StockSnapshotEvent event = new StockSnapshotEvent(1L, 100, Instant.now());

        // when
        consumer.consume(event);

        // then
        verify(mockWarmupService, times(1)).handleSnapshot(event);
    }
}
