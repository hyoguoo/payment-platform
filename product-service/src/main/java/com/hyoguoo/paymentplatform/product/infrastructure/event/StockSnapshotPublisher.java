package com.hyoguoo.paymentplatform.product.infrastructure.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyoguoo.paymentplatform.product.application.port.out.StockRepository;
import com.hyoguoo.paymentplatform.product.domain.Stock;
import com.hyoguoo.paymentplatform.product.infrastructure.messaging.ProductTopics;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * 앱 기동 완료 시 전 상품 재고 스냅샷을 Kafka로 일괄 발행.
 * payment-service Phase 1.17 warmup consumer(StockSnapshotWarmupConsumer)와 페어.
 *
 * <p>ADR-12: 토픽 = {@code product.events.stock-snapshot}
 * <p>Kafka 미구성 테스트 환경 autoconfig 회피 — {@code @ConditionalOnProperty(spring.kafka.bootstrap-servers)}.
 *
 * <p>Producer 기본 value.serializer=StringSerializer 전제하에 JSON 문자열로 미리 직렬화해 발행한다.
 * payload 스키마는 payment-service StockSnapshotEvent(productId, quantity, capturedAt)와 일치한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "spring.kafka.bootstrap-servers")
public class StockSnapshotPublisher {

    private final StockRepository stockRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @EventListener(ApplicationReadyEvent.class)
    public void publishAll() {
        List<Stock> stocks = stockRepository.findAll();
        log.info("action=stock_snapshot_publish count={}", stocks.size());

        Instant capturedAt = Instant.now();
        for (Stock stock : stocks) {
            String key = String.valueOf(stock.getProductId());
            String payload = toJson(stock, capturedAt);
            kafkaTemplate.send(ProductTopics.EVENTS_STOCK_SNAPSHOT, key, payload);
        }
    }

    private String toJson(Stock stock, Instant capturedAt) {
        Map<String, Object> event = Map.of(
                "productId", stock.getProductId(),
                "quantity", stock.getQuantity(),
                "capturedAt", capturedAt.toString()
        );
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "stock snapshot 직렬화 실패 productId=" + stock.getProductId(), e);
        }
    }
}
