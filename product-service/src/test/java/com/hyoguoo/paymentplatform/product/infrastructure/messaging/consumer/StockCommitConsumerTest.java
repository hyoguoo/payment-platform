package com.hyoguoo.paymentplatform.product.infrastructure.messaging.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.hyoguoo.paymentplatform.product.application.usecase.StockCommitUseCase;
import com.hyoguoo.paymentplatform.product.infrastructure.messaging.consumer.dto.StockCommittedMessage;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
        String orderId = "order-1000";  // K3: String 통일
        String eventUUID = "event-uuid-consumer-test";
        int qty = 7;
        Instant occurredAt = Instant.now();
        Instant expiresAt = occurredAt.plusSeconds(86400);

        StockCommittedMessage message = new StockCommittedMessage(
                productId,
                qty,
                eventUUID,
                occurredAt,
                orderId,
                expiresAt
        );

        // when
        stockCommitConsumer.consume(message);

        // then: usecase 1회 위임 (K3: orderId String 그대로 전달)
        verify(stockCommitUseCase, times(1))
                .commit(eventUUID, orderId, productId, qty, expiresAt);
    }

    @Test
    @DisplayName("TC-I6-1: expiresAt=null, occurredAt=fixed → expiresAt = occurredAt + 8d 로 fallback")
    void consume_whenExpiresAtNull_shouldFallbackFromOccurredAt() {
        // given
        long productId = 10L;
        String orderId = "order-1000";  // K3: String 통일
        String eventUUID = "event-uuid-fallback-test";
        int qty = 3;
        Instant occurredAt = Instant.parse("2026-01-01T00:00:00Z");

        StockCommittedMessage message = new StockCommittedMessage(
                productId,
                qty,
                eventUUID,
                occurredAt,
                orderId,
                null  // expiresAt null — Producer 미전송 케이스
        );

        // when
        stockCommitConsumer.consume(message);

        // then: expiresAt = occurredAt + DEDUPE_TTL(8d)
        ArgumentCaptor<Instant> expiresAtCaptor = forClass(Instant.class);
        verify(stockCommitUseCase, times(1))
                .commit(
                        org.mockito.ArgumentMatchers.eq(eventUUID),
                        org.mockito.ArgumentMatchers.eq(orderId),
                        org.mockito.ArgumentMatchers.eq(productId),
                        org.mockito.ArgumentMatchers.eq(qty),
                        expiresAtCaptor.capture()
                );

        Instant expectedExpiresAt = occurredAt.plus(StockCommitUseCase.DEDUPE_TTL);
        assertThat(expiresAtCaptor.getValue()).isEqualTo(expectedExpiresAt);
    }

    @Test
    @DisplayName("TC-I6-2: expiresAt=null, occurredAt=null → expiresAt ≈ now + 8d (±5초)")
    void consume_whenBothNull_shouldFallbackFromNow() {
        // given
        long productId = 10L;
        String orderId = "order-1000";  // K3: String 통일
        String eventUUID = "event-uuid-bothnull-test";
        int qty = 1;

        StockCommittedMessage message = new StockCommittedMessage(
                productId,
                qty,
                eventUUID,
                null,  // occurredAt null
                orderId,
                null   // expiresAt null
        );

        Instant beforeCall = Instant.now();

        // when
        stockCommitConsumer.consume(message);

        Instant afterCall = Instant.now();

        // then: expiresAt ≈ now + DEDUPE_TTL(8d), ±5초 윈도우
        ArgumentCaptor<Instant> expiresAtCaptor = forClass(Instant.class);
        verify(stockCommitUseCase, times(1))
                .commit(
                        org.mockito.ArgumentMatchers.eq(eventUUID),
                        org.mockito.ArgumentMatchers.eq(orderId),
                        org.mockito.ArgumentMatchers.eq(productId),
                        org.mockito.ArgumentMatchers.eq(qty),
                        expiresAtCaptor.capture()
                );

        Instant captured = expiresAtCaptor.getValue();
        Instant expectedLow = beforeCall.plus(StockCommitUseCase.DEDUPE_TTL).minusSeconds(5);
        Instant expectedHigh = afterCall.plus(StockCommitUseCase.DEDUPE_TTL).plusSeconds(5);
        assertThat(captured).isBetween(expectedLow, expectedHigh);
    }
}
