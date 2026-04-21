package com.hyoguoo.paymentplatform.product.infrastructure.messaging.consumer;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.hyoguoo.paymentplatform.product.application.usecase.StockCommitUseCase;
import com.hyoguoo.paymentplatform.product.infrastructure.messaging.consumer.dto.StockCommittedMessage;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * StockCommitConsumer 단위 테스트.
 * <p>
 * Mock StockCommitUseCase — 메시지 파싱·위임 검증.
 */
@ExtendWith(MockitoExtension.class)
class StockCommitConsumerTest {

    @Mock
    private StockCommitUseCase stockCommitUseCase;

    @InjectMocks
    private StockCommitConsumer stockCommitConsumer;

    @Test
    @DisplayName("TC4: consume 호출 시 StockCommitUseCase.commit 1회 위임")
    void consume_ShouldDelegateToStockCommitUseCase() {
        // given
        long productId = 10L;
        long orderId = 1000L;
        String eventUuid = "event-uuid-consumer-test";
        int qty = 7;
        Instant occurredAt = Instant.now();
        Instant expiresAt = occurredAt.plusSeconds(86400);

        StockCommittedMessage message = new StockCommittedMessage(
                productId,
                qty,
                eventUuid,
                occurredAt,
                orderId,
                expiresAt
        );

        // when
        stockCommitConsumer.consume(message);

        // then: usecase 1회 위임
        verify(stockCommitUseCase, times(1))
                .commit(eventUuid, orderId, productId, qty, expiresAt);
    }
}
