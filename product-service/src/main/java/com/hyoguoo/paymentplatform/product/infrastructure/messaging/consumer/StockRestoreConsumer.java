package com.hyoguoo.paymentplatform.product.infrastructure.messaging.consumer;

import com.hyoguoo.paymentplatform.product.infrastructure.messaging.consumer.dto.StockRestoreMessage;
import com.hyoguoo.paymentplatform.product.presentation.port.StockRestoreCommandService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * stock.events.restore 토픽 Kafka consumer.
 * <p>
 * payment-service T3-04b(FailureCompensationService)가 발행한 보상 이벤트를 소비한다.
 * 메시지를 파싱하여 StockRestoreCommandService(inbound port)로 위임한다.
 * <p>
 * ADR-16: dedupe·재고 복원 로직은 StockRestoreUseCase(application)에서 처리.
 * consumer 내부에서 dedupe·stock 직접 로직 금지.
 * <p>
 * T1-18 교훈: ConditionalOnProperty(matchIfMissing=true) 적용.
 * spring.kafka.bootstrap-servers 미설정 환경에서 빈 등록은 되나 리스너는 기동하지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "spring.kafka.bootstrap-servers", matchIfMissing = true)
public class StockRestoreConsumer {

    private static final String TOPIC = "stock.events.restore";
    private static final String GROUP_ID = "product-service";

    private final StockRestoreCommandService stockRestoreCommandService;

    /**
     * stock.events.restore 메시지를 수신하여 재고 복원을 실행한다.
     *
     * @param message 역직렬화된 StockRestoreMessage
     */
    @KafkaListener(
            topics = TOPIC,
            groupId = GROUP_ID,
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(StockRestoreMessage message) {
        log.info("StockRestoreConsumer: 메시지 수신 orderId={} productId={} qty={} eventUuid={}",
                message.orderId(), message.productId(), message.qty(), message.eventUuid());

        stockRestoreCommandService.restore(
                message.orderId(),
                message.eventUuid(),
                message.productId(),
                message.qty()
        );
    }
}
