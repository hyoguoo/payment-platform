-- ─────────────────────────────────────────────────────────
-- payment_outbox.available_at 컬럼 + 전용 인덱스 drop
-- 엔티티에 매핑된 적 없는 값 고정 컬럼(DEFAULT CURRENT_TIMESTAMP(6) 이후 갱신 경로 없음).
-- 대기 행 조회는 next_retry_at 기반 idx_payment_outbox_status_retry_created 를 그대로 쓴다.
-- MySQL 은 ALTER TABLE ... DROP COLUMN IF EXISTS 구문을 지원하지 않는다
-- (DROP TABLE IF EXISTS 와 달리 컬럼 단위 IF EXISTS 는 MariaDB 전용 확장).
-- Flyway 가 버전당 1회만 적용하므로 멱등성은 마이그레이션 체크섬으로 보장된다.
-- ─────────────────────────────────────────────────────────
ALTER TABLE payment_outbox
    DROP INDEX idx_payment_outbox_status_available,
    DROP COLUMN available_at;
