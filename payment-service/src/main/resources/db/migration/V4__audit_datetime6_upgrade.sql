-- D4: audit 컬럼 DATETIME → DATETIME(6) 정밀도 승급.
-- BaseEntity(created_at/updated_at/deleted_at) 컬럼을 microsecond 정밀도로 통일한다.
-- P14(BaseEntity Instant 전환) 이전에 반드시 적용(DM-1 순서 불변).
-- 운영 데이터 0 전제(학습 프로젝트) — 기존 행 혼재 무해.
-- NOT NULL 여부·DEFAULT: V1 정의 기준 3컬럼 모두 nullable + DEFAULT 없음 그대로 유지.

-- ─────────────────────────────────────────────────────────
-- payment_event
-- ─────────────────────────────────────────────────────────
ALTER TABLE payment_event
    MODIFY COLUMN created_at  DATETIME(6),
    MODIFY COLUMN updated_at  DATETIME(6),
    MODIFY COLUMN deleted_at  DATETIME(6);

-- ─────────────────────────────────────────────────────────
-- payment_order
-- ─────────────────────────────────────────────────────────
ALTER TABLE payment_order
    MODIFY COLUMN created_at  DATETIME(6),
    MODIFY COLUMN updated_at  DATETIME(6),
    MODIFY COLUMN deleted_at  DATETIME(6);

-- ─────────────────────────────────────────────────────────
-- payment_outbox
-- created_at 은 복합 인덱스 idx_payment_outbox_status_retry_created 키 컬럼.
-- DATETIME→DATETIME(6) MODIFY 시 MySQL이 인덱스를 자동 재구성하므로 인덱스 재정의 불필요.
-- ─────────────────────────────────────────────────────────
ALTER TABLE payment_outbox
    MODIFY COLUMN created_at  DATETIME(6),
    MODIFY COLUMN updated_at  DATETIME(6),
    MODIFY COLUMN deleted_at  DATETIME(6);

-- ─────────────────────────────────────────────────────────
-- payment_history
-- created_at 은 단일 인덱스 idx_payment_history_created_at 키 컬럼.
-- DATETIME→DATETIME(6) MODIFY 시 MySQL이 인덱스를 자동 재구성하므로 인덱스 재정의 불필요.
-- ─────────────────────────────────────────────────────────
ALTER TABLE payment_history
    MODIFY COLUMN created_at  DATETIME(6),
    MODIFY COLUMN updated_at  DATETIME(6),
    MODIFY COLUMN deleted_at  DATETIME(6);
