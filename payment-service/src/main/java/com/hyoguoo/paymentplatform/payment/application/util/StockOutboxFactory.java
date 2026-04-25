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
 * stock_outbox row 빌더 유틸리티.
 *
 * <p>F-7: PaymentConfirmResultUseCase / FailureCompensationService 의 공통 직렬화 + outbox 빌드 로직을
 * 단일 헬퍼로 추출하여 책임을 분리한다. ADR-19 복제(b) 정책 — 모듈 내부 유틸리티.
 *
 * <p>stock commit outbox row 빌드:
 * <ul>
 *   <li>idempotencyKey: (orderId, productId) 기반 결정론적 UUID v3 (ADR-16)</li>
 *   <li>payload: StockCommittedEvent JSON 직렬화</li>
 *   <li>key: productId.toString() — 동일 상품 이벤트를 동일 파티션에 라우팅(ADR-12)</li>
 * </ul>
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class StockOutboxFactory {

    /**
     * 단일 PaymentOrder 에 대한 stock commit outbox row 를 빌드한다.
     *
     * <p>K1 fix: idempotencyKey 는 (orderId, productId) 기반 결정론적 UUID v3 으로 도출.
     * K3 fix: StockCommittedEvent 에 orderId(String) + expiresAt(Instant) 명시 전달.
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
     * try 블록 내 외부 변수 재할당 금지 규약 준수 — private 메서드 추출 패턴.
     *
     * @param event        직렬화 대상 객체
     * @param objectMapper JSON 직렬화 용 ObjectMapper
     * @return JSON 문자열
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
