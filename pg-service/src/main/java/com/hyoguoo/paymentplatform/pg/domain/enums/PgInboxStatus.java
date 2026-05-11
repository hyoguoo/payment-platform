package com.hyoguoo.paymentplatform.pg.domain.enums;

import java.util.Set;

/**
 * pg-service business inbox 5상태.
 * PgInboxEntity 의 status 컬럼에 ENUM 매핑된다.
 *
 * <p>PCS-2: NONE 폐기 + PENDING 도입.
 * 상태 전이: PENDING → IN_PROGRESS → APPROVED / FAILED / QUARANTINED
 * 보정 경로(DuplicateApprovalHandler) 전용: PENDING 우회 → 직접 IN_PROGRESS 또는 terminal
 */
public enum PgInboxStatus {

    PENDING,
    IN_PROGRESS,
    APPROVED,
    FAILED,
    QUARANTINED;

    private static final Set<PgInboxStatus> TERMINAL = Set.of(APPROVED, FAILED, QUARANTINED);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }
}
