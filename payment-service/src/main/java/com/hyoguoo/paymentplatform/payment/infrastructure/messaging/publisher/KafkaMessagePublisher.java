package com.hyoguoo.paymentplatform.payment.infrastructure.messaging.publisher;

import com.hyoguoo.paymentplatform.payment.core.common.log.EventType;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.payment.application.port.out.MessagePublisherPort;
import com.hyoguoo.paymentplatform.payment.application.dto.event.PaymentConfirmCommandMessage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * MessagePublisherPort 유일 Kafka 구현체.
 * ADR-04: KafkaTemplate 직접 호출은 이 클래스에만 허용된다.
 * Worker / RelayService 등 호출자는 반드시 MessagePublisherPort 인터페이스를 통해 발행한다.
 *
 * <p>stock publishing 은 StockOutboxKafkaPublisher / StockOutboxPublisherPort 로 분리되어 있고,
 * 이 클래스는 payment.commands.confirm 토픽 단일 경로만 담당한다.
 *
 * <p>발행은 호출 스레드에서 블로킹 동기 방식이다. kafkaTemplate.send()가 반환하는
 * CompletableFuture를 sendTimeoutMillis 까지 대기한 뒤 결과를 확인한다.
 * 실패/타임아웃 시 호출 스레드로 예외를 전파해 OutboxRelayService가 DONE 전이를 막도록 한다.
 * (virtual thread 기반 OutboxImmediateWorker에서 블로킹은 저비용이다.)
 *
 * <p>ConditionalOnProperty: spring.kafka.bootstrap-servers가 설정된 환경에서만 빈으로 등록된다.
 * 테스트에서는 FakeMessagePublisher를 직접 주입해 Kafka 없이 검증한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "spring.kafka.bootstrap-servers")
public class KafkaMessagePublisher implements MessagePublisherPort {

    private final KafkaTemplate<String, PaymentConfirmCommandMessage> commandsConfirmKafkaTemplate;
    private final String commandsConfirmTopic;
    private final long sendTimeoutMillis;

    public KafkaMessagePublisher(
            @Qualifier("commandsConfirmKafkaTemplate")
            KafkaTemplate<String, PaymentConfirmCommandMessage> commandsConfirmKafkaTemplate,
            @Value("${payment.kafka.topics.commands-confirm}") String commandsConfirmTopic,
            @Value("${kafka.publisher.send-timeout-millis:10000}") long sendTimeoutMillis
    ) {
        this.commandsConfirmKafkaTemplate = commandsConfirmKafkaTemplate;
        this.commandsConfirmTopic = commandsConfirmTopic;
        this.sendTimeoutMillis = sendTimeoutMillis;
    }

    @Override
    public void send(String topic, String key, Object payload) {
        if (commandsConfirmTopic.equals(topic)) {
            sendTyped(commandsConfirmKafkaTemplate, topic, key,
                    cast(payload, PaymentConfirmCommandMessage.class));
        } else {
            throw new IllegalArgumentException("Unknown Kafka topic: " + topic);
        }
    }

    private <T> void sendTyped(KafkaTemplate<String, T> template, String topic, String key, T payload) {
        try {
            template.send(topic, key, payload).get(sendTimeoutMillis, TimeUnit.MILLISECONDS);
            LogFmt.debug(log, LogDomain.PAYMENT, EventType.KAFKA_PUBLISH_SUCCESS,
                    () -> "topic=" + topic + " key=" + key);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LogFmt.error(log, LogDomain.PAYMENT, EventType.KAFKA_PUBLISH_FAIL,
                    () -> "interrupted topic=" + topic + " key=" + key);
            throw new IllegalStateException(
                    "Kafka 발행 중단 topic=" + topic + " key=" + key, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            LogFmt.error(log, LogDomain.PAYMENT, EventType.KAFKA_PUBLISH_FAIL,
                    () -> "topic=" + topic + " key=" + key + " error=" + cause.getMessage());
            throw new IllegalStateException(
                    "Kafka 발행 실패 topic=" + topic + " key=" + key, cause);
        } catch (TimeoutException e) {
            LogFmt.error(log, LogDomain.PAYMENT, EventType.KAFKA_PUBLISH_FAIL,
                    () -> "timeout topic=" + topic + " key=" + key + " timeoutMs=" + sendTimeoutMillis);
            throw new IllegalStateException(
                    "Kafka 발행 타임아웃 topic=" + topic + " key=" + key
                            + " timeoutMs=" + sendTimeoutMillis, e);
        }
    }

    private static <T> T cast(Object payload, Class<T> type) {
        if (type.isInstance(payload)) {
            return type.cast(payload);
        }
        throw new IllegalArgumentException(
                "payload 타입 불일치 expected=" + type.getSimpleName()
                        + " actual=" + (payload == null ? "null" : payload.getClass().getSimpleName()));
    }
}
