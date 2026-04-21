package com.hyoguoo.paymentplatform.pg.infrastructure.messaging.publisher;

import com.hyoguoo.paymentplatform.pg.application.port.out.PgEventPublisherPort;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * PgEventPublisherPort 유일 Kafka 구현체.
 * ADR-04: KafkaTemplate 직접 호출은 이 클래스에만 허용된다.
 * ADR-30: pg-service는 payment-service의 MessagePublisherPort를 공유하지 않고 독립 복제.
 *         PgEventPublisherPort가 그 역할을 대신한다.
 *
 * <p>발행 대상 토픽 (PgTopics 참고):
 * <ul>
 *   <li>payment.commands.confirm</li>
 *   <li>payment.commands.confirm.dlq</li>
 *   <li>payment.events.confirmed</li>
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
@RequiredArgsConstructor
@ConditionalOnProperty(name = "spring.kafka.bootstrap-servers")
public class PgEventPublisher implements PgEventPublisherPort {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * 지정 토픽으로 메시지를 동기 발행한다.
     * 발행 실패 시 예외를 호출자에게 전파한다 (RelayService가 processed_at 갱신을 막을 수 있도록).
     *
     * @param topic   발행 대상 Kafka 토픽
     * @param key     파티션 키
     * @param payload 직렬화될 페이로드 객체
     * @param headers 전달할 Kafka 헤더 (null-safe, 빈 맵 허용)
     */
    @Override
    public void publish(String topic, String key, Object payload, Map<String, byte[]> headers) {
        ProducerRecord<String, Object> record = buildRecord(topic, key, payload, headers);
        kafkaTemplate.send(record)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("PgEventPublisher: Kafka 발행 실패 topic={} key={} error={}",
                                topic, key, ex.getMessage());
                        throwUnchecked(ex);
                    } else {
                        log.info("PgEventPublisher: Kafka 발행 성공 topic={} key={} offset={}",
                                topic, key,
                                result.getRecordMetadata().offset());
                    }
                });
    }

    private ProducerRecord<String, Object> buildRecord(
            String topic, String key, Object payload, Map<String, byte[]> headers) {
        ProducerRecord<String, Object> record = new ProducerRecord<>(topic, key, payload);
        if (headers != null) {
            headers.forEach((headerKey, headerValue) ->
                    record.headers().add(new RecordHeader(headerKey, headerValue)));
        }
        return record;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void throwUnchecked(Throwable t) throws T {
        throw (T) t;
    }
}
