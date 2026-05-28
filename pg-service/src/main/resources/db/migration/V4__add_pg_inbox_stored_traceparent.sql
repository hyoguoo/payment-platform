-- V4__add_pg_inbox_stored_traceparent.sql
-- D-TRACE-1: pg_inbox에 W3C traceparent 저장용 컬럼 추가.
-- NULL 허용 — 헤더 부재·구버전 행 호환.
-- VARCHAR(64): W3C traceparent "00-<32hex>-<16hex>-<2hex>" = 55자 + 여유.
ALTER TABLE pg_inbox
    ADD COLUMN stored_traceparent VARCHAR(64) NULL;
