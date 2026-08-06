package com.hyoguoo.paymentplatform.pg.application.dto;

import java.time.Instant;

/**
 * 관리자 벤더 상태 조회 결과 — 벤더 원 상태를 승인/실패/확인 불가 세 값으로 접은 판정.
 *
 * <p>{@code vendorStatus} 는 벤더가 실제로 돌려준 원 상태 문자열이다. 조회 자체가 실패했거나
 * (예외 흡수) 대상 주문을 pg-service 가 모르는 경우 null — 판정은 확인 불가
 * ({@link PgVendorStatusJudgement#UNKNOWN}) 지만 근거가 되는 벤더 응답이 없다는 뜻이다.
 */
public record PgVendorStatusView(
        PgVendorStatusJudgement judgement,
        String vendorStatus,
        Instant queriedAt
) {

    public static PgVendorStatusView of(PgVendorStatusJudgement judgement, String vendorStatus, Instant queriedAt) {
        return new PgVendorStatusView(judgement, vendorStatus, queriedAt);
    }

    public static PgVendorStatusView unknown(Instant queriedAt) {
        return new PgVendorStatusView(PgVendorStatusJudgement.UNKNOWN, null, queriedAt);
    }
}
