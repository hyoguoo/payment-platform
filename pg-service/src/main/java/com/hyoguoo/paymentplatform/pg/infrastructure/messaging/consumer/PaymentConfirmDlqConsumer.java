package com.hyoguoo.paymentplatform.pg.infrastructure.messaging.consumer;

import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmCommand;
import com.hyoguoo.paymentplatform.pg.application.service.PgDlqService;
import com.hyoguoo.paymentplatform.pg.infrastructure.messaging.PgTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * payment.commands.confirm.dlq 토픽 Kafka consumer.
 * ADR-30(T2b-02): PaymentConfirmConsumer와 물리적으로 다른 Spring bean.
 *
 * <p>처리 위임: PgDlqService.handle(command) — 실제 QUARANTINED 전이 로직은 서비스 계층.
 *
 * <p>레이어 규칙: @KafkaListener는 infrastructure/messaging/consumer에만 위치.
 * service 계층에 Kafka 의존 금지.
 *
 * <p>ConditionalOnProperty: spring.kafka.bootstrap-servers가 설정된 환경에서만 빈 등록.
 * 테스트에서는 PgDlqService를 직접 호출하여 Kafka 없이 검증한다.
 *
 * <p>DLQ consumer 자체 실패 시 offset 미커밋 → 재기동 후 재처리.
 * pg_inbox UNIQUE + terminal 체크로 중복 방어 (불변식 6c).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "spring.kafka.bootstrap-servers")
public class PaymentConfirmDlqConsumer {

    private final PgDlqService pgDlqService;

    /**
     * payment.commands.confirm.dlq 토픽 메시지를 소비한다.
     * groupId는 pg-service-dlq 전용으로 고정 (ADR-30: PaymentConfirmConsumer groupId=pg-service와 분리).
     *
     * @param command 역직렬화된 PgConfirmCommand
     */
    @KafkaListener(
            topics = PgTopics.COMMANDS_CONFIRM_DLQ,
            groupId = "pg-service-dlq",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(PgConfirmCommand command) {
        log.info("PaymentConfirmDlqConsumer: DLQ 메시지 수신 orderId={} eventUuid={}",
                command.orderId(), command.eventUuid());
        pgDlqService.handle(command);
    }
}
