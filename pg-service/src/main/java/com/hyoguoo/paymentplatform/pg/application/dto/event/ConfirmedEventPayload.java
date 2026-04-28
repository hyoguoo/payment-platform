package com.hyoguoo.paymentplatform.pg.application.dto.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.Objects;

/**
 * payment.events.confirmed 토픽 payload — pg-service 발행 측.
 *
 * <p>payment-service {@code ConfirmedEventMessage} record 와 필드명·의미가 대칭이어야 한다.
 * 구조 변경 시 양쪽을 함께 갱신한다 — 공유 jar 없이 독립 복제이므로
 * {@code @JsonPropertyOrder} 로 직렬화 순서까지 강제하고
 * payment-service {@code ConfirmedEventSchemaParityTest} 가 양쪽 동기화를 검증한다.
 *
 * <p>Null 필드는 JSON 직렬화에서 제외된다 (APPROVED 에서 reasonCode 가 누락되는 등).
 *
 * <p>application 계층이 infrastructure 패키지를 직접 참조하지 않도록 hexagonal layer 규약을 따른다.
 *
 * @param orderId    주문 ID
 * @param status     APPROVED / FAILED / QUARANTINED
 * @param reasonCode 실패·격리 사유 코드 (APPROVED 시 null)
 * @param amount     원화 최소 단위 정수 (APPROVED 시 non-null, FAILED/QUARANTINED 시 nullable)
 * @param approvedAt 벤더 승인 시각 ISO-8601 OffsetDateTime 문자열 (APPROVED 시 non-null, 그 외 nullable)
 * @param eventUuid  0단계 dedupe 키 (매 outbox row 당 1 개 생성)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"orderId", "status", "reasonCode", "amount", "approvedAt", "eventUuid"})
public record ConfirmedEventPayload(
        String orderId,
        String status,
        String reasonCode,
        Long amount,
        String approvedAt,
        String eventUuid
) {

    /**
     * APPROVED 팩토리 — amount/approvedAt non-null 강제.
     * AMOUNT_MISMATCH 역방향 방어선: payment-service 가 수신 후 amount 총액을 대조한다.
     *
     * @param orderId    주문 ID
     * @param eventUuid  dedupe 키
     * @param amount     벤더 실측 금액 (minor unit, non-null)
     * @param approvedAt 벤더 승인 시각 ISO-8601 문자열 (non-null)
     */
    public static ConfirmedEventPayload approved(
            String orderId, String eventUuid, Long amount, String approvedAt) {
        Objects.requireNonNull(amount, "APPROVED payload: amount must not be null");
        Objects.requireNonNull(approvedAt, "APPROVED payload: approvedAt must not be null");
        return new ConfirmedEventPayload(orderId, "APPROVED", null, amount, approvedAt, eventUuid);
    }

    public static ConfirmedEventPayload failed(String orderId, String reasonCode, String eventUuid) {
        return new ConfirmedEventPayload(orderId, "FAILED", reasonCode, null, null, eventUuid);
    }

    public static ConfirmedEventPayload quarantined(String orderId, String reasonCode, String eventUuid) {
        return new ConfirmedEventPayload(orderId, "QUARANTINED", reasonCode, null, null, eventUuid);
    }

    public static ConfirmedEventPayload quarantinedWithAmount(String orderId, String reasonCode, Long amount, String eventUuid) {
        return new ConfirmedEventPayload(orderId, "QUARANTINED", reasonCode, amount, null, eventUuid);
    }
}
