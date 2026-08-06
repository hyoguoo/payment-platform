package com.hyoguoo.paymentplatform.payment.application.dto.admin;

import com.hyoguoo.paymentplatform.payment.application.port.out.PgAttemptHistoryPort;
import java.time.Instant;

/**
 * pg-service 벤더 상태 조회 결과 — payment 쪽 도메인 표현.
 *
 * <p>{@link PgAttemptHistoryPort} 와 달리 조회 실패는 이 DTO 로 흡수된다({@code judgement=UNKNOWN}).
 * 호출부({@code QuarantineResolveUseCase})가 승인/실패/확인불가 세 갈래 분기 하나로 판정을 끝낼 수
 * 있어야 하기 때문이다 — 예외를 던지면 그 분기 밖으로 흐름이 샌다.
 */
public record PgVendorStatusInfo(
        PgVendorStatusJudgement judgement,
        String vendorStatus,
        Instant queriedAt
) {

    public static PgVendorStatusInfo of(PgVendorStatusJudgement judgement, String vendorStatus, Instant queriedAt) {
        return new PgVendorStatusInfo(judgement, vendorStatus, queriedAt);
    }

    public static PgVendorStatusInfo unknown(Instant queriedAt) {
        return new PgVendorStatusInfo(PgVendorStatusJudgement.UNKNOWN, null, queriedAt);
    }
}
