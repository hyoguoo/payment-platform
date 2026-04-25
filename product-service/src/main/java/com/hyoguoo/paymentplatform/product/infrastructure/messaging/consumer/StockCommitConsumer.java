package com.hyoguoo.paymentplatform.product.infrastructure.messaging.consumer;

import com.hyoguoo.paymentplatform.product.application.usecase.StockCommitUseCase;
import com.hyoguoo.paymentplatform.product.core.common.log.EventType;
import com.hyoguoo.paymentplatform.product.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.product.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.product.infrastructure.messaging.consumer.dto.StockCommittedMessage;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * payment.events.stock-committed 토픽 Kafka consumer.
 * <p>
 * S-2(StockCommitEvent 소비): 메시지를 파싱하여 StockCommitUseCase로 위임한다.
 * 멱등성·dedupe 로직은 usecase 계층에서 처리한다.
 *
 * <p>T3.5-02 규약: infra @ConditionalOnProperty는 matchIfMissing=false(기본).
 * spring.kafka.bootstrap-servers 미명시 시 빈 자체가 등록되지 않는다.
 * 테스트 컨텍스트는 spring.kafka.listener.auto-startup=false 로 제어한다.
 *
 * <p>T3.5-09 규약: StockCommit/StockRestore consumer 는 독립 groupId 를 사용한다.
 * commit 경로의 rebalance·lag·장애가 restore(보상) 경로에 파급되지 않도록 격리한다.
 *
 * <p>레이어 규칙: @KafkaListener는 infrastructure/messaging/consumer에만 위치한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "spring.kafka.bootstrap-servers")
public class StockCommitConsumer {

    private static final String TOPIC = "payment.events.stock-committed";
    private static final String GROUP_ID = "product-service-stock-commit";

    private final StockCommitUseCase stockCommitUseCase;

    /**
     * payment.events.stock-committed 메시지를 수신하여 재고 확정 커밋을 실행한다.
     *
     * <p>T-I6: expiresAt null fallback — Producer(payment-service StockCommittedEvent)가
     * expiresAt 을 미전송할 경우(스키마 불일치), Jackson 역직렬화 시 null 이 된다.
     * null 이면 {@code occurredAt + DEDUPE_TTL} 로 계산하고,
     * occurredAt 도 null 이면 {@code Instant.now() + DEDUPE_TTL} 로 fallback 한다.
     * 사전 메시지(구버전 페이로드)와의 하위 호환 유지 목적.
     *
     * @param message 역직렬화된 StockCommittedMessage
     */
    @KafkaListener(
            topics = TOPIC,
            groupId = GROUP_ID,
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(StockCommittedMessage message) {
        LogFmt.info(log, LogDomain.STOCK, EventType.STOCK_COMMIT_RECEIVED,
                () -> "productId=" + message.productId() + " qty=" + message.qty() + " eventUUID=" + message.idempotencyKey());

        Instant expiresAt = resolveExpiresAt(message);

        stockCommitUseCase.commit(
                message.idempotencyKey(),
                message.orderId() != null ? message.orderId() : 0L,
                message.productId(),
                message.qty(),
                expiresAt
        );
    }

    /**
     * expiresAt null fallback 계산.
     * <ol>
     *   <li>message.expiresAt() non-null → 그대로 사용</li>
     *   <li>null + occurredAt non-null → occurredAt + DEDUPE_TTL</li>
     *   <li>null + occurredAt null → Instant.now() + DEDUPE_TTL</li>
     * </ol>
     */
    private Instant resolveExpiresAt(StockCommittedMessage message) {
        if (message.expiresAt() != null) {
            return message.expiresAt();
        }
        Instant base = message.occurredAt() != null ? message.occurredAt() : Instant.now();
        LogFmt.info(log, LogDomain.STOCK, EventType.STOCK_COMMIT_RECEIVED,
                () -> "expiresAt null fallback: base=" + base + " eventUUID=" + message.idempotencyKey());
        return base.plus(StockCommitUseCase.DEDUPE_TTL);
    }
}
