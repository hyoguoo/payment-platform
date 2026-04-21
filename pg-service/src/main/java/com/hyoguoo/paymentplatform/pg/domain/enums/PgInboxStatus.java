package com.hyoguoo.paymentplatform.pg.domain.enums;

import java.util.Set;

/**
 * pg-service business inbox 처리 상태.
 * ADR-21 보강 — 5상태 정의 (T2a-04에서 JPA 엔티티·Flyway ENUM 매핑 예정).
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
