package com.hyoguoo.paymentplatform.payment.infrastructure.messaging.consumer;

import com.hyoguoo.paymentplatform.payment.application.service.StockCacheWarmupService;
import com.hyoguoo.paymentplatform.payment.infrastructure.messaging.consumer.dto.StockSnapshotEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * product.events.stock-snapshot 토픽 Kafka consumer 어댑터.
 *
 * <p>레이어 책임: Kafka 메시지 수신만 담당(thin adapter).
 * 비즈니스 로직은 {@link StockCacheWarmupService}에 위임한다.
 *
 * <p>Phase-3.1(T3 계열)에서 product-service가 실제 snapshot을 발행할 때까지
 * 이 consumer는 빈 토픽에서 메시지를 받지 못하며,
 * warmup orchestration은 {@link StockCacheWarmupApplicationEventListener}가
 * ApplicationReadyEvent로 처리한다.
 */
@Component
@RequiredArgsConstructor
public class StockSnapshotWarmupConsumer {

    private final StockCacheWarmupService warmupService;

    /**
     * snapshot 이벤트 수신 — WarmupService에 1회 위임.
     *
     * @param event 수신한 snapshot 이벤트
     */
    @KafkaListener(
            topics = "${payment.stock-cache.warmup.topic:product.events.stock-snapshot}",
            groupId = "${spring.kafka.consumer.group-id:payment-service}"
    )
    public void consume(StockSnapshotEvent event) {
        warmupService.handleSnapshot(event);
    }
}
