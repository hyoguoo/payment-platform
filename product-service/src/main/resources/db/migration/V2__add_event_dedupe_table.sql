-- product-service V2 — product_event_dedupe 인덱스 보강
-- T3-05: StockRestoreConsumer dedupe 조회 성능 개선.
-- NOTE: product_event_dedupe 테이블은 V1__product_schema.sql에서 이미 생성됨.
--       V2에서는 expires_at 컬럼 인덱스만 추가한다 (TTL 만료 행 조회 최적화).
-- MySQL 8.0은 ALTER TABLE ADD INDEX 문법에서 IF NOT EXISTS를 지원하지 않는다.
-- Flyway 히스토리가 재실행을 막으므로 별도 조건 없이 ADD INDEX만 사용한다.

ALTER TABLE product_event_dedupe
    ADD INDEX idx_expires_at (expires_at);
