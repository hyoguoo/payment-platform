package com.hyoguoo.paymentplatform.payment.application.port.out;

/**
 * stock outbox relay 전용 Kafka 발행 포트.
 * T-J1: stock_outbox row의 String payload를 직접 Kafka로 발행한다.
 *
 * <p>pg-service {@code PgEventPublisherPort} 구조를 독립 복제 (ADR-19 복제(b) 방침).
 * outbox row에는 payload가 미리 직렬화된 JSON String으로 저장되어 있으므로
 * 재직렬화 없이 KafkaTemplate<String, String>으로 직접 발행한다.
 *
 * <p>spring.kafka.template.observation-enabled=true가 publish 시점의
 * 현재 span에서 traceparent를 자동 주입한다.
 * outboxRelayExecutor(@Async)가 T-I2 이중 래핑(OTel Context + MDC)으로
 * submit 시점 context를 VT에서 정확히 복원하므로 traceparent 회귀 없음.
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
