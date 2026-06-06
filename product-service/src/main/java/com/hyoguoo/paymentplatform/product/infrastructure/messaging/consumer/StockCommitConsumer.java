package com.hyoguoo.paymentplatform.product.infrastructure.messaging.consumer;

import com.hyoguoo.paymentplatform.product.application.usecase.StockCommitUseCase;
import com.hyoguoo.paymentplatform.product.core.common.log.EventType;
import com.hyoguoo.paymentplatform.product.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.product.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.product.infrastructure.messaging.ProductTopics;
import com.hyoguoo.paymentplatform.product.infrastructure.messaging.consumer.dto.StockCommittedMessage;
import java.time.Clock;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * payment.events.stock-committed 토픽 Kafka consumer.
 * 메시지를 파싱하여 StockCommitUseCase 로 위임한다 — 멱등성·dedupe 로직은 use case 계층 책임.
 *
 * <p>infra {@code @ConditionalOnProperty} 는 matchIfMissing=false(기본) — spring.kafka.bootstrap-servers
 * 미명시 시 빈 자체가 등록되지 않는다. 테스트 컨텍스트는 spring.kafka.listener.auto-startup=false 로 제어한다.
 *
 * <p>groupId 는 {@code product-service-stock-commit} 으로 고정 — 다른 consumer 그룹과 격리한다.
 *
 * <p>레이어 규칙: {@code @KafkaListener} 는 infrastructure/messaging/consumer 에만 위치한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "spring.kafka.bootstrap-servers")
public class StockCommitConsumer {

    private static final String TOPIC = ProductTopics.PAYMENT_EVENTS_STOCK_COMMITTED;
    private static final String GROUP_ID = "product-service-stock-commit";

    private final Clock clock;
    private final StockCommitUseCase stockCommitUseCase;

    public StockCommitConsumer(Clock clock, StockCommitUseCase stockCommitUseCase) {
        this.clock = clock;
        this.stockCommitUseCase = stockCommitUseCase;
    }

    /**
     * payment.events.stock-committed 메시지를 수신하여 재고 확정 커밋을 실행한다.
     *
     * <p>expiresAt 이 null 이면 {@code occurredAt + DEDUPE_TTL} 로 계산하고,
     * occurredAt 도 null 이면 {@code clock.instant() + DEDUPE_TTL} 로 fallback 한다 —
     * producer 가 expiresAt 을 미전송하던 구버전 페이로드와의 하위 호환 유지 목적이다.
     *
     * <p>orderId 는 String 으로 통일됐고 producer 가 직접 채워 전송한다.
     * null 인 경우엔 빈 문자열로 fallback 하여 구버전 producer 대응만 남긴다.
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

        // D1 — 단일 진입점 단일 시각 산출: now를 먼저 산출하고 commit 인자와 resolveExpiresAt fallback base에 동일하게 전달한다.
        Instant now = clock.instant();
        Instant expiresAt = resolveExpiresAt(message, now);
        String orderId = message.orderId() != null ? message.orderId() : "";

        stockCommitUseCase.commit(
                message.idempotencyKey(),
                orderId,
                message.productId(),
                message.qty(),
                now,
                expiresAt
        );
    }

    /**
     * expiresAt null fallback 계산.
     *
     * <p>D1: {@code now} 는 consume 진입 시 단일 산출된 Instant — 내부에서 clock.instant() 를 재호출하지 않는다.
     * <ol>
     *   <li>message.expiresAt() non-null → 그대로 사용</li>
     *   <li>null + occurredAt non-null → occurredAt + DEDUPE_TTL</li>
     *   <li>null + occurredAt null → now + DEDUPE_TTL (commit 인자 now 와 동일 Instant 공유)</li>
     * </ol>
     */
    private Instant resolveExpiresAt(StockCommittedMessage message, Instant now) {
        if (message.expiresAt() != null) {
            return message.expiresAt();
        }
        Instant base = message.occurredAt() != null ? message.occurredAt() : now;
        LogFmt.info(log, LogDomain.STOCK, EventType.STOCK_COMMIT_RECEIVED,
                () -> "expiresAt null fallback: base=" + base + " eventUUID=" + message.idempotencyKey());
        return base.plus(StockCommitUseCase.DEDUPE_TTL);
    }
}
