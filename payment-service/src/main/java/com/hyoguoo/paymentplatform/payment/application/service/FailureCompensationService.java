package com.hyoguoo.paymentplatform.payment.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyoguoo.paymentplatform.payment.application.event.StockOutboxReadyEvent;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockOutboxRepository;
import com.hyoguoo.paymentplatform.payment.application.util.StockEventUuidDeriver;
import com.hyoguoo.paymentplatform.payment.domain.StockOutbox;
import com.hyoguoo.paymentplatform.payment.infrastructure.messaging.PaymentTopics;
import com.hyoguoo.paymentplatform.payment.infrastructure.messaging.event.StockRestoreEvent;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * FAILED 결제 보상 서비스.
 * ADR-04(Transactional Outbox), ADR-16(UUID dedupe).
 * stock.events.restore 보상 이벤트를 stock_outbox INSERT + StockOutboxReadyEvent 발행으로 처리한다.
 *
 * <p>UUID 멱등성 전략: UUID v3(nameUUIDFromBytes) — "stock-restore:{orderId}:{productId}" 기반 결정론적 생성.
 * 동일 orderId + productId 재호출 시 동일 UUID가 생성되어 product-service의 dedupe 로직과 결합,
 * 중복 재고 복원을 차단한다. (ADR-16)
 *
 * <p>T-J1: stock_outbox INSERT + StockOutboxReadyEvent 발행으로 전환.
 * 기존 StockRestoreRequestedEvent(T-D2~T-I10 경로) 철거.
 * outboxRelayExecutor(@Async, T-I2 이중 래핑)가 submit 시점 OTel Context + MDC를
 * VT에서 정확히 복원 → traceparent 회귀 없음.
 */
@Service
@RequiredArgsConstructor
public class FailureCompensationService {

    private final ApplicationEventPublisher applicationEventPublisher;
    private final StockOutboxRepository stockOutboxRepository;
    private final ObjectMapper objectMapper;

    /**
     * FAILED 결제에 대한 재고 복원 보상 이벤트를 발행한다.
     * ADR-16: eventUUID는 orderId 기반 결정론적 생성 → 동일 orderId 재호출 시 동일 UUID.
     *
     * <p>productIds 리스트의 각 상품에 대해 개별 stock_outbox INSERT를 수행한다.
     *
     * @param orderId    주문 ID
     * @param productIds 복원 대상 상품 ID 목록
     * @param qty        복원 수량 (각 상품별 동일 수량 적용)
     */
    public void compensate(String orderId, List<Long> productIds, int qty) {
        for (Long productId : productIds) {
            compensate(orderId, productId, qty);
        }
    }

    /**
     * 단일 상품에 대한 FAILED 결제 재고 복원 outbox INSERT + 이벤트 발행.
     * ADR-16: eventUUID는 orderId+productId 기반 결정론적 생성 → 동일 조합 재호출 시 동일 UUID.
     *
     * <p>T-B1: handleFailed 루프 내부에서 실 qty와 함께 호출하는 단위 진입점.
     *
     * <p>T-J1: stock_outbox INSERT + StockOutboxReadyEvent 발행.
     * outboxRelayExecutor(@Async)가 traceparent를 정확히 전파한다.
     *
     * @param orderId   주문 ID
     * @param productId 복원 대상 상품 ID
     * @param qty       복원 수량 (실 주문 수량)
     */
    public void compensate(String orderId, Long productId, int qty) {
        // K1: StockEventUuidDeriver 위임 — commit 측과 동일 도출 전략, "stock-restore" prefix로 분리.
        UUID eventUUID = UUID.fromString(StockEventUuidDeriver.derive(orderId, productId, "stock-restore"));
        StockRestoreEvent event = new StockRestoreEvent(eventUUID, orderId, productId, qty, Instant.now());
        String payloadJson = serializeToJson(event);

        LocalDateTime now = LocalDateTime.now();
        StockOutbox outbox = StockOutbox.create(
                PaymentTopics.EVENTS_STOCK_RESTORE,
                String.valueOf(productId),
                payloadJson,
                now
        );
        StockOutbox saved = stockOutboxRepository.save(outbox);
        applicationEventPublisher.publishEvent(new StockOutboxReadyEvent(saved.getId()));
    }

    /**
     * 도메인 이벤트를 JSON String으로 직렬화한다.
     * try 블록 내 외부 변수 재할당 금지 규약 준수 — private 메서드로 추출.
     */
    private String serializeToJson(Object event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stock restore outbox payload 직렬화 실패: " + e.getMessage(), e);
        }
    }
}
