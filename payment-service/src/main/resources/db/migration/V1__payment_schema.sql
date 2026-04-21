-- Flyway V1 — T1-07 (MSA-TRANSITION Phase 1). ADR-23 DB 분리 시작 시점.

-- ─────────────────────────────────────────────────────────
-- payment_event
-- ─────────────────────────────────────────────────────────
CREATE TABLE payment_event (
    id                             BIGINT          NOT NULL AUTO_INCREMENT,
    buyer_id                       BIGINT          NOT NULL,
    seller_id                      BIGINT          NOT NULL,
    order_name                     VARCHAR(255)    NOT NULL,
    order_id                       VARCHAR(255)    NOT NULL,
    payment_key                    VARCHAR(255),
    gateway_type                   VARCHAR(50)     NOT NULL,
    status                         VARCHAR(50)     NOT NULL,
    executed_at                    DATETIME(6),
    approved_at                    DATETIME(6),
    retry_count                    INT,
    status_reason                  VARCHAR(255),
    last_status_changed_at         DATETIME(6),
    -- ADR-13 §2-2b-3: QUARANTINED 전이 시 2단계 복구 대기 플래그 (T1-12 QuarantineCompensationHandler 소비)
    quarantine_compensation_pending BOOLEAN        NOT NULL DEFAULT FALSE,
    created_at                     DATETIME,
    updated_at                     DATETIME,
    deleted_at                     DATETIME,
    PRIMARY KEY (id),
    UNIQUE INDEX uk_payment_event_order_id (order_id),
    INDEX idx_payment_event_status_last_changed (status, last_status_changed_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- ─────────────────────────────────────────────────────────
-- payment_order
-- ─────────────────────────────────────────────────────────
CREATE TABLE payment_order (
    id               BIGINT          NOT NULL AUTO_INCREMENT,
    payment_event_id BIGINT          NOT NULL,
    order_id         VARCHAR(255)    NOT NULL,
    product_id       BIGINT          NOT NULL,
    quantity         INT             NOT NULL,
    amount           DECIMAL(19, 2)  NOT NULL,
    status           VARCHAR(50)     NOT NULL,
    created_at       DATETIME,
    updated_at       DATETIME,
    deleted_at       DATETIME,
    PRIMARY KEY (id),
    INDEX idx_payment_order_payment_event_id (payment_event_id),
    INDEX idx_payment_order_order_id (order_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- ─────────────────────────────────────────────────────────
-- payment_outbox
-- ADR-30: 지수 백오프 지연 발행용 available_at 컬럼 포함.
-- OutboxPollingWorker: WHERE status IN ('PENDING','IN_FLIGHT') AND available_at <= NOW()
-- ─────────────────────────────────────────────────────────
CREATE TABLE payment_outbox (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    order_id      VARCHAR(100) NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    retry_count   INT          NOT NULL DEFAULT 0,
    next_retry_at DATETIME(6),
    in_flight_at  DATETIME(6),
    -- ADR-30 지수 백오프 지연 발행용: 폴링 기준 컬럼, DEFAULT = 즉시 발행
    available_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_at    DATETIME,
    updated_at    DATETIME,
    deleted_at    DATETIME,
    PRIMARY KEY (id),
    UNIQUE INDEX uk_payment_outbox_order_id (order_id),
    -- OutboxPollingWorker 범위 스캔용 복합 인덱스 (status + available_at)
    INDEX idx_payment_outbox_status_available (status, available_at),
    -- 기존 폴링 패턴 호환용 복합 인덱스 (status + next_retry_at + created_at)
    INDEX idx_payment_outbox_status_retry_created (status, next_retry_at, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- ─────────────────────────────────────────────────────────
-- payment_history
-- BEFORE_COMMIT 인서트 대상 감사 로그 (append-only).
-- FK 제약 없음 — MSA DB 분리 사전 조치.
-- ─────────────────────────────────────────────────────────
CREATE TABLE payment_history (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    payment_event_id BIGINT       NOT NULL,
    order_id         VARCHAR(255) NOT NULL,
    previous_status  VARCHAR(50),
    current_status   VARCHAR(50)  NOT NULL,
    reason           TEXT,
    change_status_at DATETIME(6)  NOT NULL,
    created_at       DATETIME,
    updated_at       DATETIME,
    deleted_at       DATETIME,
    PRIMARY KEY (id),
    INDEX idx_payment_history_payment_event_id (payment_event_id),
    INDEX idx_payment_history_created_at (created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
