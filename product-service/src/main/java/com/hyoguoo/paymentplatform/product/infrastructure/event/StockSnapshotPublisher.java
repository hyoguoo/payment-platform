package com.hyoguoo.paymentplatform.product.infrastructure.event;

import com.hyoguoo.paymentplatform.product.application.port.out.StockRepository;
import com.hyoguoo.paymentplatform.product.domain.Stock;
import com.hyoguoo.paymentplatform.product.infrastructure.messaging.ProductTopics;
import java.util.List;
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
 * 실제 발행 로직(스냅샷 직렬화·파티션 키)은 T3-04 또는 후속 태스크에서 검증.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "spring.kafka.bootstrap-servers")
public class StockSnapshotPublisher {

    private final StockRepository stockRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @EventListener(ApplicationReadyEvent.class)
    public void publishAll() {
        List<Stock> stocks = stockRepository.findAll();
        log.info("action=stock_snapshot_publish count={}", stocks.size());

        for (Stock stock : stocks) {
            String key = String.valueOf(stock.getProductId());
            kafkaTemplate.send(ProductTopics.EVENTS_STOCK_SNAPSHOT, key, stock);
        }
    }
}
