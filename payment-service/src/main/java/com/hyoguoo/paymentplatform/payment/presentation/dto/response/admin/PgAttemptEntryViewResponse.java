package com.hyoguoo.paymentplatform.payment.presentation.dto.response.admin;

import com.hyoguoo.paymentplatform.payment.application.dto.admin.PgAttemptEntryInfo;
import java.time.Instant;
import lombok.Builder;
import lombok.Getter;

/**
 * 시도 이력 타임라인 회차 1건 — 화면 표시용.
 *
 * <p>{@code attemptNo} 는 회차를 읽지 못한 옛 행이면 {@code null} 이다(회차 미지).
 * {@code publishedAt} 은 아직 발행되지 않은(실행 예정) 행이면 {@code null} 이다.
 * 템플릿은 두 경우 모두 빈 값("-")으로 렌더하고 예외를 던지지 않는다.
 */
@Getter
@Builder
public class PgAttemptEntryViewResponse {

    private final Integer attemptNo;
    private final Instant reservedAt;
    private final Instant scheduledAt;
    private final Instant publishedAt;
    private final boolean exhausted;
    private final boolean normalAttempt;

    public static PgAttemptEntryViewResponse from(PgAttemptEntryInfo entry) {
        return PgAttemptEntryViewResponse.builder()
                .attemptNo(entry.getAttemptNo().orElse(null))
                .reservedAt(entry.getReservedAt())
                .scheduledAt(entry.getScheduledAt())
                .publishedAt(entry.getPublishedAt())
                .exhausted(entry.isExhausted())
                .normalAttempt(entry.isNormalAttempt())
                .build();
    }
}
