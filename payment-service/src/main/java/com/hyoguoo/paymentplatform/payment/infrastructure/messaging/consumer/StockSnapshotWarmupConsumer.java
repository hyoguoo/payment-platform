package com.hyoguoo.paymentplatform.payment.infrastructure.messaging.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyoguoo.paymentplatform.core.common.log.EventType;
import com.hyoguoo.paymentplatform.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.payment.application.service.StockCacheWarmupService;
import com.hyoguoo.paymentplatform.payment.infrastructure.messaging.consumer.dto.StockSnapshotEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * product.events.stock-snapshot 토픽 Kafka consumer 어댑터.
 *
 * <p>레이어 책임: Kafka 메시지 수신만 담당(thin adapter).
 * 비즈니스 로직은 {@link StockCacheWarmupService}에 위임한다.
 *
 * <p>Producer(product-service StockSnapshotPublisher)는 String 직렬화로 JSON 페이로드를 발행하므로
 * 이 consumer도 String으로 받아 ObjectMapper로 역직렬화한다
 * (StringDeserializer 기본값 유지, JsonDeserializer 별도 설정 불필요).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockSnapshotWarmupConsumer {

    private final StockCacheWarmupService warmupService;
    private final ObjectMapper objectMapper;

    /**
     * snapshot 이벤트 수신 — WarmupService에 1회 위임.
     *
     * @param payload 수신한 snapshot JSON 문자열
     */
    @KafkaListener(
            topics = "${payment.stock-cache.warmup.topic:product.events.stock-snapshot}",
            groupId = "${spring.kafka.consumer.group-id:payment-service}"
    )
    public void consume(String payload) {
        StockSnapshotEvent event = parse(payload);
        warmupService.handleSnapshot(event);
    }

    private StockSnapshotEvent parse(String payload) {
        try {
            return objectMapper.readValue(payload, StockSnapshotEvent.class);
        } catch (Exception e) {
            LogFmt.error(log, LogDomain.PRODUCT, EventType.STOCK_SNAPSHOT_PARSE_FAIL,
                    () -> "payload=" + payload + " error=" + e.getMessage());
            throw new IllegalStateException("stock snapshot 역직렬화 실패", e);
        }
    }
}
