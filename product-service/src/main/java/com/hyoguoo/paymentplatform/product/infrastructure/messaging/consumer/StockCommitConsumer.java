package com.hyoguoo.paymentplatform.product.infrastructure.messaging.consumer;

import com.hyoguoo.paymentplatform.product.application.usecase.StockCommitUseCase;
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
 * <p>T1-18 교훈: ConditionalOnProperty(matchIfMissing=true) 적용.
 * spring.kafka.bootstrap-servers 미설정 환경에서 빈 등록은 되나 리스너는 기동하지 않는다.
 *
 * <p>레이어 규칙: @KafkaListener는 infrastructure/messaging/consumer에만 위치한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "spring.kafka.bootstrap-servers", matchIfMissing = true)
public class StockCommitConsumer {

    private static final String TOPIC = "payment.events.stock-committed";
    private static final String GROUP_ID = "product-service";

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
        log.info("StockCommitConsumer: 메시지 수신 productId={} qty={} eventUuid={}",
                message.productId(), message.qty(), message.idempotencyKey());

        stockCommitUseCase.commit(
                message.idempotencyKey(),
                message.orderId() != null ? message.orderId() : 0L,
                message.productId(),
                message.qty(),
                message.expiresAt()
        );
    }
}
