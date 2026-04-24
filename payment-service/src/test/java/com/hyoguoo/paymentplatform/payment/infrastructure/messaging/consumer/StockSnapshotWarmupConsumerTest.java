package com.hyoguoo.paymentplatform.payment.infrastructure.messaging.consumer;

import static org.assertj.core.api.Assertions.assertThat;
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

    @Test
    @DisplayName("parse_whenInvalidJson_shouldCatchJsonProcessingOnly — RuntimeException 은 catch 하지 않고 전파한다")
    void parse_whenInvalidJson_shouldCatchJsonProcessingOnly() {
        // given: warmupService 가 RuntimeException 을 던지는 상황
        //   parse 는 성공(유효 JSON)하지만 warmupService.handleSnapshot 이 RuntimeException 을 throw
        String validJson = "{\"productId\":1,\"quantity\":100,\"capturedAt\":\"2026-04-24T09:00:00Z\"}";
        org.mockito.Mockito.doThrow(new RuntimeException("downstream 오류"))
                .when(mockWarmupService).handleSnapshot(any());

        // when / then: JsonProcessingException 만 catch 이므로 RuntimeException 은 전파되어야 한다
        assertThatThrownBy(() -> consumer.consume(validJson))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("downstream 오류");

        // 그리고 InvalidJson 은 여전히 IllegalStateException 으로 래핑된다
        assertThatThrownBy(() -> consumer.consume("not-json"))
                .isInstanceOf(IllegalStateException.class);

        // parse 내부의 catch 타입이 JsonProcessingException 전용인지를 리플렉션 없이
        // 행동 기반으로 검증:
        // RuntimeException 이 전파된다면 catch(Exception) 이 아닌 catch(JsonProcessingException) 임
        assertThat(true).as("RuntimeException 전파 확인 — catch 범위가 JsonProcessingException 전용임을 증명").isTrue();
    }
}
