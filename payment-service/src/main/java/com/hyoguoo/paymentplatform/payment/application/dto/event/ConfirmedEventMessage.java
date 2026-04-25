package com.hyoguoo.paymentplatform.payment.application.dto.event;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * payment.events.confirmed 토픽 payload.
 * pg-service가 Kafka로 발행하는 결제 확정 결과 메시지.
 *
 * <p>payload 예시 (APPROVED):
 * {"orderId":"order-001","status":"APPROVED","reasonCode":null,"amount":15000,
 *  "approvedAt":"2026-04-24T10:00:00+09:00","eventUuid":"evt-uuid-001"}
 *
 * <p>ADR-15 AMOUNT_MISMATCH 역방향 방어선: handleApproved 에서 amount 총액 대조에 사용.
 * approvedAt 은 OffsetDateTime.parse 후 PaymentEvent.done(...) 에 전달 (T-A2 범위).
 *
 * <p>K3: 필드 선언 순서를 pg-service {@code ConfirmedEventPayload} canonical 순서와 동일하게 통일.
 * {@code @JsonPropertyOrder} 명시로 직렬화 순서까지 강제.
 * ADR-30: 공유 JAR 없이 독립 복제 — {@code ConfirmedEventSchemaParityTest} 가 동기화를 강제한다.
 *
 * <p>K9b: infrastructure.messaging.consumer.dto → application.dto.event 으로 이동.
 * application 계층이 infrastructure 패키지를 직접 참조하지 않도록 hexagonal layer 규약 준수.
 *
 * @param orderId    주문 ID
 * @param status     결제 결과 상태 (APPROVED, FAILED, QUARANTINED)
 * @param reasonCode 실패/격리 사유 코드 (APPROVED 시 null)
 * @param amount     벤더 실측 금액 minor unit (APPROVED 시 non-null, 그 외 nullable)
 * @param approvedAt 벤더 승인 시각 ISO-8601 OffsetDateTime 문자열 (APPROVED 시 non-null, 그 외 nullable)
 * @param eventUuid  이벤트 고유 식별자 (dedupe 키)
 */
@JsonPropertyOrder({"orderId", "status", "reasonCode", "amount", "approvedAt", "eventUuid"})
public record ConfirmedEventMessage(
        String orderId,
        String status,
        String reasonCode,
        Long amount,
        String approvedAt,
        String eventUuid
) {

}
