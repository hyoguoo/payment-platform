package com.hyoguoo.paymentplatform.product.infrastructure.messaging.consumer;

import com.hyoguoo.paymentplatform.product.core.common.log.EventType;
import com.hyoguoo.paymentplatform.product.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.product.core.common.log.LogFmt;
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
 * T3.5-02 규약: infra @ConditionalOnProperty는 matchIfMissing=false(기본).
 * spring.kafka.bootstrap-servers 미명시 시 빈 자체가 등록되지 않는다.
 * 테스트 컨텍스트는 spring.kafka.listener.auto-startup=false 로 제어한다.
 *
 * <p>T3.5-09 규약: StockCommit/StockRestore consumer 는 독립 groupId 를 사용한다.
 * commit 경로의 rebalance·lag·장애가 restore(보상) 경로에 파급되지 않도록 격리한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "spring.kafka.bootstrap-servers")
public class StockRestoreConsumer {

    private static final String TOPIC = "stock.events.restore";
    private static final String GROUP_ID = "product-service-stock-restore";

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
        LogFmt.info(log, LogDomain.STOCK, EventType.STOCK_RESTORE_RECEIVED,
                () -> "orderId=" + message.orderId() + " productId=" + message.productId() + " qty=" + message.qty() + " eventUUID=" + message.eventUUID());

        stockRestoreCommandService.restore(
                message.orderId(),
                message.eventUUID(),
                message.productId(),
                message.qty()
        );
    }
}
