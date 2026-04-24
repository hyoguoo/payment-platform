package com.hyoguoo.paymentplatform.product.infrastructure.messaging.consumer;

import com.hyoguoo.paymentplatform.product.application.usecase.StockCommitUseCase;
import com.hyoguoo.paymentplatform.product.core.common.log.EventType;
import com.hyoguoo.paymentplatform.product.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.product.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.product.infrastructure.messaging.consumer.dto.StockCommittedMessage;
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

        stockCommitUseCase.commit(
                message.idempotencyKey(),
                message.orderId() != null ? message.orderId() : 0L,
                message.productId(),
                message.qty(),
                message.expiresAt()
        );
    }
}
