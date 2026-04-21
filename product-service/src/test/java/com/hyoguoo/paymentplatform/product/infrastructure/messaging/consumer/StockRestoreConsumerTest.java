package com.hyoguoo.paymentplatform.product.infrastructure.messaging.consumer;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.hyoguoo.paymentplatform.product.infrastructure.messaging.consumer.dto.StockRestoreMessage;
import com.hyoguoo.paymentplatform.product.presentation.port.StockRestoreCommandService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * StockRestoreConsumer 단위 테스트.
 * <p>
 * Mock StockRestoreCommandService — 메시지 파싱·위임 검증.
 * consumer 내부에서 dedupe·stock 직접 로직 없음을 간접 확인 (usecase 1회 위임만).
 */
@ExtendWith(MockitoExtension.class)
class StockRestoreConsumerTest {

    @Mock
    private StockRestoreCommandService stockRestoreCommandService;

    @InjectMocks
    private StockRestoreConsumer stockRestoreConsumer;

    @Test
    @DisplayName("TC-C1: consume 호출 시 StockRestoreCommandService.restore 1회 위임만")
    void consume_ShouldDelegateToStockRestoreUseCase() {
        // given
        String orderId = "order-consumer-test";
        String eventUuid = "event-uuid-consumer-test";
        long productId = 10L;
        int qty = 3;

        StockRestoreMessage message = new StockRestoreMessage(orderId, eventUuid, productId, qty);

        // when
        stockRestoreConsumer.consume(message);

        // then: StockRestoreCommandService.restore 1회 위임만
        verify(stockRestoreCommandService, times(1))
                .restore(orderId, eventUuid, productId, qty);
    }
}
