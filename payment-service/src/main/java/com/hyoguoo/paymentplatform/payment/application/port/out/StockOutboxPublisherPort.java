package com.hyoguoo.paymentplatform.payment.application.port.out;

/**
 * stock outbox relay 전용 Kafka 발행 포트.
 * stock_outbox row 의 String payload 를 그대로 Kafka 로 발행한다.
 *
 * <p>pg-service {@code PgEventPublisherPort} 와 동격 구조이지만 공유 JAR 없이 독립 복제한다.
 * outbox row 의 payload 는 이미 JSON String 으로 직렬화되어 있으므로 재직렬화 없이
 * KafkaTemplate&lt;String, String&gt; 으로 직접 발행한다.
 *
 * <p>traceparent 는 outboxRelayExecutor 의 OTel Context + MDC 복원과
 * {@code spring.kafka.template.observation-enabled=true} 가 함께 보장한다.
 */
public interface StockOutboxPublisherPort {

    /**
     * topic/key/payload(JSON String)를 Kafka로 발행한다.
     *
     * @param topic   발행 토픽
     * @param key     파티션 라우팅 키
     * @param payload 미리 직렬화된 JSON String
     */
    void send(String topic, String key, String payload);
}
