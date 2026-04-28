package com.hyoguoo.paymentplatform.payment.application.port.out;

/**
 * payment.events.confirmed.dlq 발행 포트.
 * dedupe remove 가 실패한 경우 dedupe 가 영구 잠기는 것을 막기 위해 DLQ 로 이벤트를 보낸다.
 *
 * <p>구현체:
 * <ul>
 *   <li>FakePaymentConfirmDlqPublisher (test source) — in-memory 캡처</li>
 *   <li>PaymentConfirmDlqKafkaPublisher (infrastructure) — Kafka 발행</li>
 * </ul>
 */
public interface PaymentConfirmDlqPublisher {

    /**
     * dedupe remove 실패 시 DLQ 토픽으로 이벤트를 전송한다.
     *
     * @param eventUuid   원본 이벤트 UUID
     * @param reason      실패 사유 (remove 실패 원인)
     */
    void publishDlq(String eventUuid, String reason);
}
