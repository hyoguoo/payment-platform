package com.hyoguoo.paymentplatform.payment.infrastructure.messaging.publisher;

import com.hyoguoo.paymentplatform.payment.application.port.out.MessagePublisherPort;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * MessagePublisherPort 유일 Kafka 구현체.
 * ADR-04: KafkaTemplate 직접 호출은 이 클래스에만 허용된다.
 * Worker / RelayService 등 호출자는 반드시 MessagePublisherPort 인터페이스를 통해 발행한다.
 *
 * <p>ConditionalOnProperty: spring.kafka.bootstrap-servers가 설정된 환경에서만 빈으로 등록된다.
 * 테스트에서는 FakeMessagePublisher를 직접 주입해 Kafka 없이 검증한다.
 *
 * <p>발행은 호출 스레드에서 블로킹 동기 방식이다. kafkaTemplate.send()가 반환하는
 * CompletableFuture를 sendTimeoutMillis 까지 대기한 뒤 결과를 확인한다.
 * 실패/타임아웃 시 호출 스레드로 예외를 전파해 OutboxRelayService가 DONE 전이를 막도록 한다.
 * (virtual thread 기반 OutboxImmediateWorker에서 블로킹은 저비용이다.)
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "spring.kafka.bootstrap-servers")
public class KafkaMessagePublisher implements MessagePublisherPort {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${kafka.publisher.send-timeout-millis:10000}")
    private long sendTimeoutMillis;

    @Override
    public void send(String topic, String key, Object payload) {
        try {
            kafkaTemplate.send(topic, key, payload)
                    .get(sendTimeoutMillis, TimeUnit.MILLISECONDS);
            log.debug("Kafka 발행 성공 topic={} key={}", topic, key);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Kafka 발행 중단 topic={} key={}", topic, key);
            throw new IllegalStateException(
                    "Kafka 발행 중단 topic=" + topic + " key=" + key, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.error("Kafka 발행 실패 topic={} key={} error={}", topic, key, cause.getMessage());
            throw new IllegalStateException(
                    "Kafka 발행 실패 topic=" + topic + " key=" + key, cause);
        } catch (TimeoutException e) {
            log.error("Kafka 발행 타임아웃 topic={} key={} timeoutMs={}",
                    topic, key, sendTimeoutMillis);
            throw new IllegalStateException(
                    "Kafka 발행 타임아웃 topic=" + topic + " key=" + key
                            + " timeoutMs=" + sendTimeoutMillis, e);
        }
    }
}
