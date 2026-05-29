package com.hyoguoo.paymentplatform.pg.infrastructure.messaging.consumer;

import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmCommand;
import com.hyoguoo.paymentplatform.pg.application.messaging.PgTopics;
import com.hyoguoo.paymentplatform.pg.core.common.log.EventType;
import com.hyoguoo.paymentplatform.pg.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.pg.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.pg.infrastructure.trace.TraceparentExtractor;
import com.hyoguoo.paymentplatform.pg.presentation.port.PgConfirmCommandService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * payment.commands.confirm 토픽 Kafka consumer.
 * 메시지 수신 후 PgConfirmCommandService 로 위임 — dedupe/상태 분기 로직은 service 계층 책임.
 *
 * <p>레이어 규칙: @KafkaListener는 infrastructure/messaging/consumer에만 위치한다.
 * service 계층에 Kafka 의존을 금지한다.
 *
 * <p>ConditionalOnProperty: spring.kafka.bootstrap-servers가 설정된 환경에서만 빈으로 등록된다.
 * 테스트에서는 PgConfirmService를 직접 호출하여 Kafka 없이 검증한다.
 *
 * <p>traceparent 추출: Kafka 메시지 소비 시점의 OTel Context 에서 W3C traceparent 를 추출한다.
 * {@link TraceparentExtractor}(infrastructure/trace)에 위임 — OTel API import 는 infrastructure 계층에만.
 * 추출한 traceparent 는 불투명 String 으로만 application 계층에 전달된다.
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
     * groupId 는 pg-service 전용으로 고정 — pg-service 독립 소비 그룹.
     *
     * <p>attempt 헤더: self-loop retry 시 발행자가 설정하는 1-based 시도 횟수.
     * 헤더 부재 시 1(최초 진입)로 간주한다.
     *
     * <p>traceparent 추출: {@link TraceparentExtractor#extractFromCurrentContext()} 로
     * 현재 OTel Context 에서 W3C traceparent 를 추출한다.
     * INVALID span 이면 null (폴백 — absent 경로에서 NULL 저장).
     *
     * @param command       역직렬화된 PgConfirmCommand
     * @param attemptHeader attempt 헤더 값 (없으면 null)
     */
    @KafkaListener(
            topics = PgTopics.COMMANDS_CONFIRM,
            groupId = GROUP_ID,
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(
            PgConfirmCommand command,
            @Header(value = "attempt", required = false) String attemptHeader
    ) {
        int attempt = parseAttempt(attemptHeader);
        String storedTraceparent = TraceparentExtractor.extractFromCurrentContext();
        LogFmt.info(log, LogDomain.PG, EventType.PG_CONFIRM_RECEIVED,
                () -> "orderId=" + command.orderId() + " eventUuid=" + command.eventUuid()
                        + " attempt=" + attempt);
        pgConfirmCommandService.handle(command, attempt, storedTraceparent);
    }

    private int parseAttempt(String headerValue) {
        if (headerValue == null) {
            return 1;
        }
        try {
            return Integer.parseInt(headerValue.trim());
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}
