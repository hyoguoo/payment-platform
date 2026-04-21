-- product-service V2 — product_event_dedupe 인덱스 보강
-- T3-05: StockRestoreConsumer dedupe 조회 성능 개선.
-- NOTE: product_event_dedupe 테이블은 V1__product_schema.sql에서 이미 생성됨.
--       V2에서는 expires_at 컬럼 인덱스만 추가한다 (TTL 만료 행 조회 최적화).

ALTER TABLE product_event_dedupe
    ADD INDEX IF NOT EXISTS idx_expires_at (expires_at);
