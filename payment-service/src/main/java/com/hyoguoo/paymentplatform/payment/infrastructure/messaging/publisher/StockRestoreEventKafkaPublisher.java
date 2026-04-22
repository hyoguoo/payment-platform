package com.hyoguoo.paymentplatform.payment.infrastructure.messaging.publisher;

import com.hyoguoo.paymentplatform.payment.application.dto.StockRestoreEventPayload;
import com.hyoguoo.paymentplatform.payment.application.port.out.MessagePublisherPort;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockRestoreEventPublisherPort;
import com.hyoguoo.paymentplatform.payment.infrastructure.messaging.PaymentTopics;
import com.hyoguoo.paymentplatform.payment.infrastructure.messaging.event.StockRestoreEvent;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/**
 * StockRestoreEventPublisherPort 구현체 — stock.events.restore 토픽 Kafka 발행 어댑터.
 *
 * <p>파티션 키: productId.toString() — 동일 상품 복원 이벤트 순서 보장.
 *
 * <p>ADR-16 (UUID dedupe): eventUUID는 "stock-restore:{orderId}:{productId}" 기반 결정론적 UUID v3.
 * 동일 orderId+productId 재발행 시 동일 UUID → 소비자가 중복 차단.
 *
 * <p>publish(orderId, productIds)는 qty 정보가 없는 레거시 진입 경로로, qty=0 플레이스홀더로 발행한다.
 * UUID 멱등을 활용하려면 {@link #publishPayload(StockRestoreEventPayload)}를 직접 호출한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnBean(MessagePublisherPort.class)
public class StockRestoreEventKafkaPublisher implements StockRestoreEventPublisherPort {

    private final MessagePublisherPort messagePublisherPort;

    @Override
    public void publish(String orderId, List<Long> productIds) {
        Instant now = Instant.now();
        for (Long productId : productIds) {
            UUID eventUUID = deriveEventUUID(orderId, productId);
            StockRestoreEvent event = new StockRestoreEvent(
                    eventUUID,
                    orderId,
                    productId,
                    0,
                    now
            );
            messagePublisherPort.send(
                    PaymentTopics.EVENTS_STOCK_RESTORE,
                    String.valueOf(productId),
                    event
            );
        }
    }

    @Override
    public void publishPayload(StockRestoreEventPayload payload) {
        StockRestoreEvent event = new StockRestoreEvent(
                payload.eventUUID(),
                payload.orderId(),
                payload.productId(),
                payload.qty(),
                Instant.now()
        );
        messagePublisherPort.send(
                PaymentTopics.EVENTS_STOCK_RESTORE,
                String.valueOf(payload.productId()),
                event
        );
    }

    private UUID deriveEventUUID(String orderId, Long productId) {
        String seed = "stock-restore:" + orderId + ":" + productId;
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }
}
