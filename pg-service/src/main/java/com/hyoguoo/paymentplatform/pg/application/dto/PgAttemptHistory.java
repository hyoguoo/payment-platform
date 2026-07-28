package com.hyoguoo.paymentplatform.pg.application.dto;

import com.hyoguoo.paymentplatform.pg.domain.enums.PgInboxStatus;
import java.time.Instant;
import java.util.List;

/**
 * 주문번호 기준으로 조립한 확정 시도 이력 전체.
 *
 * <p>{@code found=false} 는 해당 주문이 pg-service 에 없다는 뜻(이력 없음)이다. 조회 자체가
 * 실패(DB 장애 등)한 경우와는 다른 상태다 — 조회 실패는 예외로 전파되고 이 타입으로 흡수하지 않는다.
 *
 * <p>{@code finalStatus} / {@code finalizedAt} 은 결제가 종결(APPROVED/FAILED/QUARANTINED)된
 * 경우에만 값이 있다. 비종결 결제는 종결 시각이 없어 {@link PgAttemptHistoryEntry#normalAttempt()}
 * 판정 자체를 건너뛴다.
 *
 * <p>원문 컬럼(payload/headers_json)과 벤더 결제 키는 이 DTO 어디에도 담기지 않는다.
 */
public record PgAttemptHistory(
        String orderId,
        boolean found,
        PgInboxStatus finalStatus,
        Instant finalizedAt,
        String reasonCode,
        List<PgAttemptHistoryEntry> attempts
) {

    /**
     * 해당 주문이 pg-service 에 없을 때(이력 없음) 반환한다.
     */
    public static PgAttemptHistory notFound(String orderId) {
        return new PgAttemptHistory(orderId, false, null, null, null, List.of());
    }
}
