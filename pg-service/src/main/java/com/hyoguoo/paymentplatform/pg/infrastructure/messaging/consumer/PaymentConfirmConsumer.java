package com.hyoguoo.paymentplatform.pg.infrastructure.messaging.consumer;

import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmCommand;
import com.hyoguoo.paymentplatform.pg.core.common.log.EventType;
import com.hyoguoo.paymentplatform.pg.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.pg.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.pg.infrastructure.messaging.PgTopics;
import com.hyoguoo.paymentplatform.pg.presentation.port.PgConfirmCommandService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * payment.commands.confirm 토픽 Kafka consumer.
 * ADR-04(2단 멱등성): 메시지 수신 후 PgConfirmCommandService로 위임 — 실제 dedupe/분기 로직은 service 계층에 위치.
 * ADR-21(inbox 5상태): 상태 분기는 PgConfirmService에서 수행한다.
 *
 * <p>레이어 규칙: @KafkaListener는 infrastructure/messaging/consumer에만 위치한다.
 * service 계층에 Kafka 의존을 금지한다.
 *
 * <p>ConditionalOnProperty: spring.kafka.bootstrap-servers가 설정된 환경에서만 빈으로 등록된다.
 * 테스트에서는 PgConfirmService를 직접 호출하여 Kafka 없이 검증한다.
 *
 * <p>dry_run 모드(pg.retry.mode=dry_run): T2c-01 이후 확장 예정.
 * 현재 구현은 항상 service.handle()을 호출한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "spring.kafka.bootstrap-servers")
public class PaymentConfirmConsumer {

    private static final String GROUP_ID = "pg-service";

    private final PgConfirmCommandService pgConfirmCommandService;

    /**
     * payment.commands.confirm 토픽 메시지를 소비한다.
     * groupId는 pg-service 전용으로 고정 (ADR-21: pg-service 독립 소비 그룹).
     *
     * @param command 역직렬화된 PgConfirmCommand
     */
    @KafkaListener(
            topics = PgTopics.COMMANDS_CONFIRM,
            groupId = GROUP_ID,
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(PgConfirmCommand command) {
        LogFmt.info(log, LogDomain.PG, EventType.PG_CONFIRM_RECEIVED,
                () -> "orderId=" + command.orderId() + " eventUuid=" + command.eventUuid());
        pgConfirmCommandService.handle(command);
    }
}
