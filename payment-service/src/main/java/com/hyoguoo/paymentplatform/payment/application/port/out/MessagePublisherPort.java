package com.hyoguoo.paymentplatform.payment.application.port.out;

/**
 * Kafka producer 추상 — 릴레이(outbox processor)가 사용하는 저수준 발행 port.
 * Phase 2 pg-service에도 동일 이름으로 복제 예정.
 */
public interface MessagePublisherPort {

    void send(String topic, String key, Object payload);
}
