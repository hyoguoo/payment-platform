package com.hyoguoo.paymentplatform.payment.infrastructure.messaging.publisher;

import com.hyoguoo.paymentplatform.payment.application.port.out.MessagePublisherPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "spring.kafka.bootstrap-servers")
public class KafkaMessagePublisher implements MessagePublisherPort {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * Kafka 토픽으로 페이로드를 동기 발행한다.
     * 발행 실패 시 예외를 호출자에게 전파한다 (OutboxRelayService가 상태 전이를 막을 수 있도록).
     */
    @Override
    public void send(String topic, String key, Object payload) {
        kafkaTemplate.send(topic, key, payload)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Kafka 발행 실패 topic={} key={} error={}", topic, key, ex.getMessage());
                        throwUnchecked(ex);
                    } else {
                        log.debug("Kafka 발행 성공 topic={} key={} offset={}",
                                topic, key,
                                result.getRecordMetadata().offset());
                    }
                });
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void throwUnchecked(Throwable t) throws T {
        throw (T) t;
    }
}
