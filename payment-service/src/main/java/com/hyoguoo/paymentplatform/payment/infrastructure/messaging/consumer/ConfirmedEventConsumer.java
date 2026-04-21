package com.hyoguoo.paymentplatform.payment.infrastructure.messaging.consumer;

import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentConfirmResultUseCase;
import com.hyoguoo.paymentplatform.payment.infrastructure.messaging.PaymentTopics;
import com.hyoguoo.paymentplatform.payment.infrastructure.messaging.consumer.dto.ConfirmedEventMessage;
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
 * <p>ConditionalOnProperty(matchIfMissing=true): spring.kafka.bootstrap-servers 미설정 환경에서도
 * 빈 등록은 되나 KafkaAutoConfiguration이 없으면 KafkaListenerContainerFactory 빈이 없어 리스너 기동 안 됨.
 * 테스트에서는 PaymentConfirmResultUseCase를 직접 호출하여 Kafka 없이 검증한다.
 * T1-18 교훈: ConditionalOnProperty(spring.kafka.bootstrap-servers) — matchIfMissing=true 적용.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "spring.kafka.bootstrap-servers", matchIfMissing = true)
public class ConfirmedEventConsumer {

    private final PaymentConfirmResultUseCase paymentConfirmResultUseCase;

    /**
     * payment.events.confirmed 토픽 메시지를 소비한다.
     * groupId는 payment-service 전용으로 고정.
     *
     * @param message 역직렬화된 ConfirmedEventMessage
     */
    @KafkaListener(
            topics = PaymentTopics.EVENTS_CONFIRMED,
            groupId = "payment-service",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(ConfirmedEventMessage message) {
        log.info("ConfirmedEventConsumer: 메시지 수신 orderId={} status={} eventUuid={}",
                message.orderId(), message.status(), message.eventUuid());
        paymentConfirmResultUseCase.handle(message);
    }
}
