package com.hyoguoo.paymentplatform.pg.infrastructure.messaging.publisher;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmCommand;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgEventPublisherPort;
import com.hyoguoo.paymentplatform.pg.core.common.log.EventType;
import com.hyoguoo.paymentplatform.pg.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.pg.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.pg.application.dto.event.ConfirmedEventPayload;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * PgEventPublisherPort 유일 Kafka 구현체.
 * KafkaTemplate 직접 호출은 이 클래스에만 허용된다.
 * pg-service 는 payment-service 의 MessagePublisherPort 를 공유하지 않고 독립 복제로 PgEventPublisherPort 를 둔다.
 *
 * <p>토픽별 타입드 KafkaTemplate 3종을 주입받아 topic 필드 기준으로 발행 경로를 분기한다
 * ({@code KafkaProducerConfig} 에서 빈 등록). outbox row 의 payload 는 직렬화된 JSON 문자열이므로
 * 릴레이 단계에서 record 로 역직렬화한 뒤 타입드 템플릿으로 재발행한다 — 직렬화 비용은 DB IO 대비 무시 가능.
 *
 * <p>발행 대상 토픽 (PgTopics 참고):
 * <ul>
 *   <li>payment.events.confirmed → ConfirmedEventPayload</li>
 *   <li>payment.commands.confirm → PgConfirmCommand (재시도)</li>
 *   <li>payment.commands.confirm.dlq → PgConfirmCommand (재시도 한도 소진)</li>
 * </ul>
 *
 * <p>멱등성 정책: 호출자(PgOutboxRelayService) 책임.
 * 이 publisher는 단순 Kafka send 어댑터이며 내부에서 중복 발행을 차단하지 않는다.
 * 호출자는 available_at·processed_at 조건으로 정확히 1회만 publish()를 호출함으로써 멱등성을 보장한다.
 *
 * <p>ConditionalOnProperty: spring.kafka.bootstrap-servers가 설정된 환경에서만 빈으로 등록된다.
 * 테스트에서는 FakePgEventPublisher를 직접 주입해 Kafka 없이 검증한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "spring.kafka.bootstrap-servers")
public class PgEventPublisher implements PgEventPublisherPort {

    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, ConfirmedEventPayload> confirmedEventKafkaTemplate;
    private final KafkaTemplate<String, PgConfirmCommand> commandsConfirmKafkaTemplate;
    private final KafkaTemplate<String, PgConfirmCommand> commandsConfirmDlqKafkaTemplate;

    private final String eventsConfirmedTopic;
    private final String commandsConfirmTopic;
    private final String commandsConfirmDlqTopic;

    @Value("${kafka.publisher.send-timeout-millis:10000}")
    private long sendTimeoutMillis;

    public PgEventPublisher(
            ObjectMapper objectMapper,
            @Qualifier("confirmedEventKafkaTemplate")
            KafkaTemplate<String, ConfirmedEventPayload> confirmedEventKafkaTemplate,
            @Qualifier("commandsConfirmKafkaTemplate")
            KafkaTemplate<String, PgConfirmCommand> commandsConfirmKafkaTemplate,
            @Qualifier("commandsConfirmDlqKafkaTemplate")
            KafkaTemplate<String, PgConfirmCommand> commandsConfirmDlqKafkaTemplate,
            @Value("${pg.kafka.topics.events-confirmed}") String eventsConfirmedTopic,
            @Value("${pg.kafka.topics.commands-confirm}") String commandsConfirmTopic,
            @Value("${pg.kafka.topics.commands-confirm-dlq}") String commandsConfirmDlqTopic
    ) {
        this.objectMapper = objectMapper;
        this.confirmedEventKafkaTemplate = confirmedEventKafkaTemplate;
        this.commandsConfirmKafkaTemplate = commandsConfirmKafkaTemplate;
        this.commandsConfirmDlqKafkaTemplate = commandsConfirmDlqKafkaTemplate;
        this.eventsConfirmedTopic = eventsConfirmedTopic;
        this.commandsConfirmTopic = commandsConfirmTopic;
        this.commandsConfirmDlqTopic = commandsConfirmDlqTopic;
    }

    /**
     * 지정 토픽으로 메시지를 동기 발행한다.
     * payload 는 outbox row 에 저장된 JSON 문자열이며, 토픽에 대응하는 record 로 역직렬화한 뒤 발행한다.
     *
     * @param topic   발행 대상 Kafka 토픽
     * @param key     파티션 키
     * @param payload outbox row 에 저장된 JSON 문자열 (String 타입으로 전달됨)
     * @param headers 전달할 Kafka 헤더 (null-safe, 빈 맵 허용)
     */
    @Override
    public void publish(String topic, String key, Object payload, Map<String, byte[]> headers) {
        String json = toJsonString(payload);

        if (eventsConfirmedTopic.equals(topic)) {
            ConfirmedEventPayload typed = deserialize(json, ConfirmedEventPayload.class);
            send(confirmedEventKafkaTemplate, topic, key, typed, headers);
        } else if (commandsConfirmTopic.equals(topic)) {
            PgConfirmCommand typed = deserialize(json, PgConfirmCommand.class);
            send(commandsConfirmKafkaTemplate, topic, key, typed, headers);
        } else if (commandsConfirmDlqTopic.equals(topic)) {
            PgConfirmCommand typed = deserialize(json, PgConfirmCommand.class);
            send(commandsConfirmDlqKafkaTemplate, topic, key, typed, headers);
        } else {
            throw new IllegalArgumentException("Unknown Kafka topic: " + topic);
        }
    }

    private <T> void send(
            KafkaTemplate<String, T> template,
            String topic,
            String key,
            T payload,
            Map<String, byte[]> headers
    ) {
        ProducerRecord<String, T> record = new ProducerRecord<>(topic, key, payload);
        if (headers != null) {
            headers.forEach((headerKey, headerValue) ->
                    record.headers().add(new RecordHeader(headerKey, headerValue)));
        }
        try {
            template.send(record).get(sendTimeoutMillis, TimeUnit.MILLISECONDS);
            LogFmt.debug(log, LogDomain.PG_OUTBOX, EventType.PG_OUTBOX_RELAY_DONE,
                    () -> "topic=" + topic + " key=" + key);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LogFmt.error(log, LogDomain.PG_OUTBOX, EventType.PG_OUTBOX_WORKER_RELAY_FAIL,
                    () -> "interrupted topic=" + topic + " key=" + key);
            throw new IllegalStateException(
                    "PgEventPublisher: Kafka 발행 중단 topic=" + topic + " key=" + key, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            LogFmt.error(log, LogDomain.PG_OUTBOX, EventType.PG_OUTBOX_WORKER_RELAY_FAIL,
                    () -> "topic=" + topic + " key=" + key + " error=" + cause.getMessage());
            throw new IllegalStateException(
                    "PgEventPublisher: Kafka 발행 실패 topic=" + topic + " key=" + key, cause);
        } catch (TimeoutException e) {
            LogFmt.error(log, LogDomain.PG_OUTBOX, EventType.PG_OUTBOX_WORKER_RELAY_FAIL,
                    () -> "timeout topic=" + topic + " key=" + key + " timeoutMs=" + sendTimeoutMillis);
            throw new IllegalStateException(
                    "PgEventPublisher: Kafka 발행 타임아웃 topic=" + topic + " key=" + key
                            + " timeoutMs=" + sendTimeoutMillis, e);
        }
    }

    private String toJsonString(Object payload) {
        if (payload instanceof String s) {
            return s;
        }
        // outbox relay 경로에서는 항상 String 이지만, 혹시 모를 직접 호출 대비 방어적 직렬화.
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("PgEventPublisher: payload 직렬화 실패", e);
        }
    }

    private <T> T deserialize(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "PgEventPublisher: payload 역직렬화 실패 type=" + type.getSimpleName(), e);
        }
    }
}
