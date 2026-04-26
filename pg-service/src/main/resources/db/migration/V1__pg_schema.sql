-- pg-service Flyway baseline.
-- ADR-21 보강(business inbox amount 컬럼), ADR-30(pg_outbox available_at).

-- ─────────────────────────────────────────────────────────
-- pg_inbox
-- business inbox 5상태(NONE/IN_PROGRESS/APPROVED/FAILED/QUARANTINED).
-- amount: 원화 최소 단위 정수 (payload BigDecimal → DB BIGINT 변환 규약: scale=0, 음수·소수 거부 — 애플리케이션 계층 검증).
-- stored_status_result: 재발행용 전체 JSON payload 스냅샷 (nullable — 벤더 호출 후에만 채움).
-- reason_code: 실패·격리 사유 코드 (nullable).
-- ─────────────────────────────────────────────────────────
CREATE TABLE pg_inbox (
    id                   BIGINT        NOT NULL AUTO_INCREMENT,
    order_id             VARCHAR(100)  NOT NULL,
    status               ENUM('NONE','IN_PROGRESS','APPROVED','FAILED','QUARANTINED') NOT NULL,
    amount               BIGINT        NOT NULL,
    stored_status_result VARCHAR(1024),
    reason_code          VARCHAR(100),
    created_at           DATETIME(6)   NOT NULL,
    updated_at           DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE INDEX ux_pg_inbox_order_id (order_id),
    -- reconciler scan용 상태 인덱스
    INDEX idx_pg_inbox_status (status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- ─────────────────────────────────────────────────────────
-- pg_outbox
-- ADR-30: available_at — 재시도 백오프 예약 발행 시각.
-- topic: payment.commands.confirm / payment.commands.confirm.dlq / payment.events.confirmed.
-- processed_at: NULL = pending, NOT NULL = 발행 완료.
-- attempt: 재시도 카운트.
-- ─────────────────────────────────────────────────────────
CREATE TABLE pg_outbox (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    topic          VARCHAR(200)  NOT NULL,
    `key`          VARCHAR(100)  NOT NULL,
    payload        LONGTEXT      NOT NULL,
    headers_json   TEXT,
    available_at   DATETIME(6)   NOT NULL,
    processed_at   DATETIME(6),
    attempt        INT           NOT NULL DEFAULT 0,
    created_at     DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    -- polling worker 배치 조회용: processed_at IS NULL AND available_at <= NOW()
    INDEX idx_pg_outbox_processed_available (processed_at, available_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
