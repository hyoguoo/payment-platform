package com.hyoguoo.paymentplatform.payment.application.port.out;

/**
 * payment.events.confirmed.dlq 발행 포트.
 * T-C3: remove 실패 시 dedupe 영구 잠금 방지를 위해 DLQ로 이벤트를 전송한다.
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
