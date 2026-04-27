package com.hyoguoo.paymentplatform.pg.domain.enums;

import java.util.Set;

/**
 * pg-service business inbox 5상태.
 * PgInboxEntity 의 status 컬럼에 ENUM 매핑된다.
 */
public enum PgInboxStatus {

    NONE,
    IN_PROGRESS,
    APPROVED,
    FAILED,
    QUARANTINED;

    private static final Set<PgInboxStatus> TERMINAL = Set.of(APPROVED, FAILED, QUARANTINED);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }
}
