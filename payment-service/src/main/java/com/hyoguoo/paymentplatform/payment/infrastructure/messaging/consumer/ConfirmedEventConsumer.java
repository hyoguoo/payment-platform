package com.hyoguoo.paymentplatform.payment.infrastructure.messaging.consumer;

import com.hyoguoo.paymentplatform.payment.core.common.log.EventType;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentConfirmResultUseCase;
import com.hyoguoo.paymentplatform.payment.application.messaging.PaymentTopics;
import com.hyoguoo.paymentplatform.payment.application.dto.event.ConfirmedEventMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * payment.events.confirmed 토픽 Kafka consumer.
 * ADR-04(2단 멱등성): 메시지 수신 후 PaymentConfirmResultUseCase로 위임 — 실제 dedupe/분기 로직은 use-case 계층에 위치.
 * ADR-14: stock 이벤트 발행(commit/restore)는 use-case 내부에서 처리.
 *
 * <p>레이어 규칙: @KafkaListener는 infrastructure/messaging/consumer에만 위치한다.
 * use-case 계층에 Kafka 의존을 금지한다.
 *
 * <p>T3.5-02 규약: infra @ConditionalOnProperty는 matchIfMissing=false(기본).
 * spring.kafka.bootstrap-servers 미명시 시 빈 자체가 등록되지 않는다.
 * 테스트 컨텍스트는 spring.kafka.listener.auto-startup=false 로 제어한다.
 * 단위 테스트는 PaymentConfirmResultUseCase를 직접 호출하여 Kafka 없이 검증한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "spring.kafka.bootstrap-servers")
public class ConfirmedEventConsumer {

    private static final String GROUP_ID = "payment-service";

    private final PaymentConfirmResultUseCase paymentConfirmResultUseCase;

    /**
     * payment.events.confirmed 토픽 메시지를 소비한다.
     * groupId는 payment-service 전용으로 고정.
     *
     * @param message 역직렬화된 ConfirmedEventMessage
     */
    @KafkaListener(
            topics = PaymentTopics.EVENTS_CONFIRMED,
            groupId = GROUP_ID,
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(ConfirmedEventMessage message) {
        LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_CONFIRM_RESULT_RECEIVED,
                () -> "orderId=" + message.orderId() + " status=" + message.status()
                        + " eventUuid=" + message.eventUuid());
        paymentConfirmResultUseCase.handle(message);
    }
}
