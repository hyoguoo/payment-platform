package com.hyoguoo.paymentplatform.pg.presentation.dto;

import com.hyoguoo.paymentplatform.pg.application.dto.PgAttemptHistory;
import java.time.Instant;
import java.util.List;

/**
 * GET /api/v1/confirmations/{orderId}/attempts 응답 DTO.
 * payment-service infrastructure.adapter.http.dto.PgAttemptHistoryResponse 와 필드 시그니처가
 * 일치해야 한다(레코드 역직렬화) — 응답 형태는 Task 5 완료 시점에 고정되고, payment 측 어댑터(Task 7)가
 * 이 형태에 의존한다.
 *
 * <p>{@code found=false} 는 해당 주문이 pg-service 에 없다는 뜻(이력 없음)이며 HTTP 200 으로 응답한다 —
 * 404 가 아니다. 조회 자체의 실패(DB 장애 등)는 이 응답으로 표현되지 않고 5xx 로 전파된다.
 *
 * <p>{@code finalStatus} 는 pg-service 내부 enum({@code PgInboxStatus}) 을 그대로 노출하지 않고
 * 문자열로 변환한다 — 응답이 pg-service 도메인 타입에 결합되지 않게 한다.
 *
 * <p>원문 컬럼(payload/headers_json)과 벤더 결제 키는 이 응답 어디에도 담기지 않는다.
 */
public record PgAttemptHistoryResponse(
        String orderId,
        boolean found,
        String finalStatus,
        Instant finalizedAt,
        String reasonCode,
        List<PgAttemptEntryResponse> attempts
) {

    public static PgAttemptHistoryResponse from(PgAttemptHistory history) {
        return new PgAttemptHistoryResponse(
                history.orderId(),
                history.found(),
                history.finalStatus() != null ? history.finalStatus().name() : null,
                history.finalizedAt(),
                history.reasonCode(),
                history.attempts().stream().map(PgAttemptEntryResponse::from).toList()
        );
    }
}
