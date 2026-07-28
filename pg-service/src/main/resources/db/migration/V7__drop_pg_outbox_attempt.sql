-- ─────────────────────────────────────────────────────────
-- pg_outbox.attempt 컬럼 drop
-- PgOutbox.create / createWithAvailableAt 두 팩토리가 항상 0을 넣고,
-- 저장소 어디에도 증가시키는 UPDATE가 없어 값이 항상 0인 死 컬럼이었다.
-- 재시도 회차는 outbox 행의 headers_json(attempt 헤더)에서 조립한다
-- (PgAttemptHistoryService 참고) — 이 컬럼과 무관하다.
-- 참고: pg_inbox.attempt(V5)는 시도 횟수 정본이며 이 마이그레이션과 무관, 보존한다.
-- ADMIN-VISIBILITY 토픽 Task 14 결정.
-- 참고: MySQL은 ALTER TABLE ... DROP COLUMN IF EXISTS 구문을 지원하지 않는다
-- (DROP TABLE IF EXISTS 와 달리 컬럼 단위 IF EXISTS는 MariaDB 전용 확장).
-- Flyway가 버전당 1회만 적용하므로 멱등성은 마이그레이션 체크섬으로 보장된다.
-- ─────────────────────────────────────────────────────────
ALTER TABLE pg_outbox
    DROP COLUMN attempt;
