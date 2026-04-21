package com.hyoguoo.paymentplatform.pg.application.port.out;

import java.util.Map;

/**
 * pg-service outbound 포트 — 결제 이벤트 발행 추상.
 * ADR-04: Transactional Outbox 패턴을 통한 이벤트 발행.
 * ADR-30: pg_outbox row의 topic 필드가 발행 대상 토픽을 결정한다.
 *
 * <p>PgEventPublisher(infrastructure/messaging/publisher/)가 유일한 Kafka 구현체.
 * Worker / RelayService 는 반드시 이 포트를 경유해야 한다 — KafkaTemplate 직접 호출 금지.
 *
 * <p>발행 대상 토픽 (PgTopics 참고):
 * <ul>
 *   <li>payment.commands.confirm</li>
 *   <li>payment.commands.confirm.dlq</li>
 *   <li>payment.events.confirmed</li>
 * </ul>
 */
public interface PgEventPublisherPort {

    /**
     * 지정한 토픽으로 메시지를 발행한다.
     *
     * @param topic   발행 대상 Kafka 토픽 (PgTopics 상수)
     * @param key     파티션 키 (orderId 등)
     * @param payload 직렬화될 페이로드 객체
     * @param headers 전달할 Kafka 헤더 (key → byte[], null 이면 빈 맵으로 처리)
     */
    void publish(String topic, String key, Object payload, Map<String, byte[]> headers);
}
