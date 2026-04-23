package com.hyoguoo.paymentplatform.pg.infrastructure.messaging.event;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * payment.events.confirmed 토픽 payload — pg-service 발행 측.
 *
 * <p>payment-service {@code ConfirmedEventMessage} record 와 필드명·의미가 대칭이어야 한다.
 * 구조 변경 시 양쪽을 함께 갱신한다 (ADR-30: 공유 JAR 없이 pg-service 독립 복제).
 *
 * <p>Null 필드는 JSON 직렬화에서 제외 (APPROVED 에서 reasonCode/amount 가 누락되는 등).
 *
 * @param orderId    주문 ID
 * @param status     APPROVED / FAILED / QUARANTINED
 * @param reasonCode 실패·격리 사유 코드 (APPROVED 시 null)
 * @param amount     원화 최소 단위 정수 (nullable — DuplicateApprovalHandler·DLQ 경로에서만 기록)
 * @param eventUuid  0단계 dedupe 키 (매 outbox row 당 1 개 생성)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConfirmedEventPayload(
        String orderId,
        String status,
        String reasonCode,
        Long amount,
        String eventUuid
) {

    public static ConfirmedEventPayload approved(String orderId, String eventUuid) {
        return new ConfirmedEventPayload(orderId, "APPROVED", null, null, eventUuid);
    }

    public static ConfirmedEventPayload approvedWithAmount(String orderId, long amount, String eventUuid) {
        return new ConfirmedEventPayload(orderId, "APPROVED", null, amount, eventUuid);
    }

    public static ConfirmedEventPayload failed(String orderId, String reasonCode, String eventUuid) {
        return new ConfirmedEventPayload(orderId, "FAILED", reasonCode, null, eventUuid);
    }

    public static ConfirmedEventPayload quarantined(String orderId, String reasonCode, String eventUuid) {
        return new ConfirmedEventPayload(orderId, "QUARANTINED", reasonCode, null, eventUuid);
    }

    public static ConfirmedEventPayload quarantinedWithAmount(String orderId, String reasonCode, Long amount, String eventUuid) {
        return new ConfirmedEventPayload(orderId, "QUARANTINED", reasonCode, amount, eventUuid);
    }
}
