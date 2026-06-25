-- V5__add_pg_inbox_attempt.sql
-- DLQ-REACHABILITY Task 1: pg_inbox 시도횟수 영속화(Option B SoT).
-- 기존 행은 default 1 — 워커 신규 INSERT(insertPending)도 attempt 미지정 시 default 1 적용.
ALTER TABLE pg_inbox
    ADD COLUMN attempt INT NOT NULL DEFAULT 1;
