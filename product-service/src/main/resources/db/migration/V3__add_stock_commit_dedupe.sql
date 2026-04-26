-- product-service V3 — stock_commit_dedupe 테이블 신설
-- StockCommitConsumer 의 eventUUID dedupe 전용 테이블.
-- stock.events.restore 보상 이벤트 dedupe 는 V2 의 product_event_dedupe 와 별개 스키마로 관리한다.

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
