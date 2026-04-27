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
 * <p>amount 는 handleApproved 의 역방향 방어선에서 paymentEvent 총액 대조에 쓰이고, approvedAt 은
 * OffsetDateTime.parse 후 markPaymentAsDone 의 인자로 들어간다.
 *
 * <p>필드 선언 순서는 pg-service {@code ConfirmedEventPayload} 와 동일해야 하며 {@code @JsonPropertyOrder} 로
 * 직렬화 순서까지 강제한다. 두 모듈은 공유 jar 없이 독립 복제이므로 {@code ConfirmedEventSchemaParityTest} 가
 * 양쪽의 동기화를 검증한다.
 *
 * <p>application 계층이 infrastructure 패키지를 직접 참조하지 않도록 hexagonal layer 규약을 따른다.
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
