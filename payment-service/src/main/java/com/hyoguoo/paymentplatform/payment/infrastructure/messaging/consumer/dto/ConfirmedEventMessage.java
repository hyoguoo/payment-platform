package com.hyoguoo.paymentplatform.payment.infrastructure.messaging.consumer.dto;

/**
 * payment.events.confirmed 토픽 payload.
 * pg-service가 Kafka로 발행하는 결제 확정 결과 메시지.
 *
 * <p>payload 예시 (APPROVED):
 * {"orderId":"order-001","status":"APPROVED","reasonCode":null,"eventUuid":"evt-uuid-001",
 *  "amount":15000,"approvedAt":"2026-04-24T10:00:00+09:00"}
 *
 * <p>ADR-15 AMOUNT_MISMATCH 역방향 방어선: handleApproved 에서 amount 총액 대조에 사용.
 * approvedAt 은 OffsetDateTime.parse 후 PaymentEvent.done(...) 에 전달 (T-A2 범위).
 *
 * @param orderId    주문 ID
 * @param status     결제 결과 상태 (APPROVED, FAILED, QUARANTINED)
 * @param reasonCode 실패/격리 사유 코드 (APPROVED 시 null)
 * @param eventUuid  이벤트 고유 식별자 (dedupe 키)
 * @param amount     벤더 실측 금액 minor unit (APPROVED 시 non-null, 그 외 nullable)
 * @param approvedAt 벤더 승인 시각 ISO-8601 OffsetDateTime 문자열 (APPROVED 시 non-null, 그 외 nullable)
 */
public record ConfirmedEventMessage(
        String orderId,
        String status,
        String reasonCode,
        String eventUuid,
        Long amount,
        String approvedAt
) {

}
