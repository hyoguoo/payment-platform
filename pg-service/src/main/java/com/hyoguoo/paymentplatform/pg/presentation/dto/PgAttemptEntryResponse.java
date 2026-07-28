package com.hyoguoo.paymentplatform.pg.presentation.dto;

import com.hyoguoo.paymentplatform.pg.application.dto.PgAttemptHistoryEntry;
import java.time.Instant;

/**
 * GET /api/v1/confirmations/{orderId}/attempts 응답의 회차 1건.
 * payment-service infrastructure.adapter.http.dto 수신 DTO 와 필드 시그니처가 일치해야 한다(레코드 역직렬화).
 *
 * <p>회차 미지 상태는 {@code attemptNo=null} 로 표현한다(애플리케이션 DTO 의 {@code Optional} 을
 * 여기서 nullable 로 변환).
 */
public record PgAttemptEntryResponse(
        Integer attemptNo,
        Instant reservedAt,
        Instant scheduledAt,
        Instant publishedAt,
        boolean exhausted,
        boolean normalAttempt
) {

    public static PgAttemptEntryResponse from(PgAttemptHistoryEntry entry) {
        return new PgAttemptEntryResponse(
                entry.attemptNo().orElse(null),
                entry.reservedAt(),
                entry.scheduledAt(),
                entry.publishedAt(),
                entry.exhausted(),
                entry.normalAttempt()
        );
    }
}
