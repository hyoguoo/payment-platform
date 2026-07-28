package com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http.dto;

import java.time.Instant;

/**
 * pg-service GET /api/v1/confirmations/{orderId}/attempts 응답의 회차 1건 수신 DTO.
 * pg-service presentation.dto.PgAttemptEntryResponse 와 필드 시그니처가 일치해야 한다(레코드 역직렬화).
 *
 * <p>회차 미지 상태는 {@code attemptNo=null} 로 온다.
 */
public record PgAttemptEntryResponse(
        Integer attemptNo,
        Instant reservedAt,
        Instant scheduledAt,
        Instant publishedAt,
        boolean exhausted,
        boolean normalAttempt
) {}
