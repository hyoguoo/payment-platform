package com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http.dto;

import java.time.Instant;
import java.util.List;

/**
 * pg-service GET /api/v1/confirmations/{orderId}/attempts 응답 수신 DTO.
 * pg-service presentation.dto.PgAttemptHistoryResponse 와 필드 시그니처가 일치해야 한다(레코드 역직렬화).
 *
 * <p>{@code found=false} 는 해당 주문이 pg-service 에 없다는 뜻(이력 없음)이며 HTTP 200 응답이다 —
 * 404 가 아니다.
 */
public record PgAttemptHistoryResponse(
        String orderId,
        boolean found,
        String finalStatus,
        Instant finalizedAt,
        String reasonCode,
        List<PgAttemptEntryResponse> attempts
) {}
