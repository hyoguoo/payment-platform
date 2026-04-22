package com.hyoguoo.paymentplatform.payment.infrastructure.messaging.consumer;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyoguoo.paymentplatform.payment.application.service.StockCacheWarmupService;
import com.hyoguoo.paymentplatform.payment.infrastructure.messaging.consumer.dto.StockSnapshotEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("StockSnapshotWarmupConsumer 테스트")
class StockSnapshotWarmupConsumerTest {

    private StockCacheWarmupService mockWarmupService;
    private ObjectMapper objectMapper;
    private StockSnapshotWarmupConsumer consumer;

    @BeforeEach
    void setUp() {
        mockWarmupService = mock(StockCacheWarmupService.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        consumer = new StockSnapshotWarmupConsumer(mockWarmupService, objectMapper);
    }

    @Test
    @DisplayName("consume - JSON 문자열 수신 시 WarmupService에 역직렬화된 이벤트를 1회 위임한다")
    void consume_ShouldDelegateToWarmupService() {
        // given
        String payload = "{\"productId\":1,\"quantity\":100,\"capturedAt\":\"2026-04-22T09:18:35.694540670Z\"}";

        // when
        consumer.consume(payload);

        // then
        ArgumentCaptor<StockSnapshotEvent> captor = ArgumentCaptor.forClass(StockSnapshotEvent.class);
        verify(mockWarmupService, times(1)).handleSnapshot(captor.capture());
        StockSnapshotEvent delivered = captor.getValue();
        org.assertj.core.api.Assertions.assertThat(delivered.productId()).isEqualTo(1L);
        org.assertj.core.api.Assertions.assertThat(delivered.quantity()).isEqualTo(100);
    }

    @Test
    @DisplayName("consume - JSON 파싱 실패 시 IllegalStateException, WarmupService 호출 금지")
    void consume_ShouldThrowOnInvalidJson() {
        // given
        String invalid = "not-json";

        // when / then
        assertThatThrownBy(() -> consumer.consume(invalid))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stock snapshot 역직렬화 실패");
        verify(mockWarmupService, never()).handleSnapshot(any());
    }
}
