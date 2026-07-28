-- V6__add_pg_outbox_key_topic_index.sql
-- ADMIN-VISIBILITY Task 2: 관리자 화면의 주문번호별 시도 이력 조회 전용 인덱스.
-- `key` 는 MySQL 예약어라 백틱 처리 (V1 DDL 과 동일).
-- 조회 조건(주문번호 일치 + 토픽 구분)에 맞춰 컬럼 순서를 (`key`, topic) 으로 잡는다.
-- 발행 큐 폴링용 idx_pg_outbox_processed_available(processed_at, available_at) 과는
-- 용도가 다르다 — 저 인덱스는 미발행 행 배치 스캔, 이 인덱스는 주문 단위 이력 조회다.
CREATE INDEX idx_pg_outbox_key_topic
    ON pg_outbox (`key`, topic);
