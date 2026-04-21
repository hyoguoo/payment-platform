-- product-service V3 — stock_commit_dedupe 테이블 신설
-- T3-04: StockCommitConsumer eventUUID dedupe 전용 테이블.
-- T3-05에서 stock.events.restore 보상 이벤트 dedupe(V2)와 별개 스키마로 관리.
-- NOTE: V2는 T3-05(FailureCompensationService)에서 신설 예정.

CREATE TABLE IF NOT EXISTS stock_commit_dedupe
(
    event_uuid VARCHAR(64)  NOT NULL,
    order_id   BIGINT,
    product_id BIGINT,
    qty        INT,
    expires_at TIMESTAMP    NOT NULL,
    created_at TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (event_uuid),
    INDEX idx_expires_at (expires_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
