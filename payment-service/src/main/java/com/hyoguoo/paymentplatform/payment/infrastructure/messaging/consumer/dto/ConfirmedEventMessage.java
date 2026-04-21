package com.hyoguoo.paymentplatform.payment.infrastructure.messaging.consumer.dto;

/**
 * payment.events.confirmed 토픽 payload.
 * pg-service가 Kafka로 발행하는 결제 확정 결과 메시지.
 *
 * <p>payload 예시:
 * {"orderId":"order-001","status":"APPROVED","reasonCode":null,"eventUuid":"evt-uuid-001"}
 *
 * @param orderId    주문 ID
 * @param status     결제 결과 상태 (APPROVED, FAILED, QUARANTINED)
 * @param reasonCode 실패/격리 사유 코드 (APPROVED 시 null)
 * @param eventUuid  이벤트 고유 식별자 (dedupe 키)
 */
public record ConfirmedEventMessage(
        String orderId,
        String status,
        String reasonCode,
        String eventUuid
) {

}
