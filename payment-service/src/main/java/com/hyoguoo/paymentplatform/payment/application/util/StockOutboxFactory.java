package com.hyoguoo.paymentplatform.payment.application.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.StockOutbox;
import com.hyoguoo.paymentplatform.payment.application.messaging.PaymentTopics;
import com.hyoguoo.paymentplatform.payment.application.dto.event.StockCommittedEvent;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * stock_outbox row 빌더 유틸리티. {@code PaymentConfirmResultUseCase.handleApproved} 가 사용하는
 * stock commit 직렬화 + outbox 빌드 로직을 단일 헬퍼로 추출했다.
 *
 * <p>stock commit outbox row 빌드:
 * <ul>
 *   <li>idempotencyKey: (orderId, productId) 기반 결정론적 UUID v3 — 재발행 시 동일 키 보장.</li>
 *   <li>payload: StockCommittedEvent JSON 직렬화</li>
 *   <li>key: productId.toString() — 동일 상품 이벤트를 동일 파티션으로 라우팅해 순서 보장.</li>
 * </ul>
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class StockOutboxFactory {

    /**
     * 단일 PaymentOrder 에 대한 stock commit outbox row 를 빌드한다.
     *
     * @param paymentEvent payment event (orderId, amount)
     * @param order        단일 주문 상품 (productId, quantity)
     * @param occurredAt   발생 Instant (clock 주입)
     * @param longTtl      expiresAt = occurredAt + longTtl
     * @param now          stock_outbox.created_at 기준 LocalDateTime
     * @param objectMapper JSON 직렬화 용 ObjectMapper
     * @return StockOutbox (save 전 상태)
     */
    public static StockOutbox buildStockCommitOutbox(
            PaymentEvent paymentEvent,
            PaymentOrder order,
            Instant occurredAt,
            Duration longTtl,
            LocalDateTime now,
            ObjectMapper objectMapper) {
        String idempotencyKey = StockEventUuidDeriver.derive(
                paymentEvent.getOrderId(), order.getProductId(), "stock-commit");
        Instant expiresAt = occurredAt.plus(longTtl);
        StockCommittedEvent event = new StockCommittedEvent(
                order.getProductId(),
                order.getQuantity(),
                idempotencyKey,
                occurredAt,
                paymentEvent.getOrderId(),
                expiresAt
        );
        String payloadJson = serialize(event, objectMapper);
        return StockOutbox.create(
                PaymentTopics.EVENTS_STOCK_COMMITTED,
                String.valueOf(order.getProductId()),
                payloadJson,
                now
        );
    }

    /**
     * 도메인 이벤트를 JSON String 으로 직렬화한다.
     *
     * @throws IllegalStateException 직렬화 실패 시
     */
    public static String serialize(Object event, ObjectMapper objectMapper) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stock outbox payload 직렬화 실패: " + e.getMessage(), e);
        }
    }
}
