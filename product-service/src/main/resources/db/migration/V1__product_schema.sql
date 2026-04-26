-- product-service Flyway baseline.
-- mysql-product 컨테이너 (포트 3309) — payment-service의 과거 모놀리식 product 테이블과 별도 DB.

-- ─────────────────────────────────────────────────────────
-- product
-- ─────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS product
(
    id          BIGINT         NOT NULL AUTO_INCREMENT,
    name        VARCHAR(200)   NOT NULL,
    price       DECIMAL(19, 2) NOT NULL,
    description VARCHAR(500)   NOT NULL DEFAULT '',
    seller_id   BIGINT         NOT NULL,
    created_at  DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- ─────────────────────────────────────────────────────────
-- stock
-- ─────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS stock
(
    product_id BIGINT      NOT NULL,
    quantity   INT         NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (product_id),
    CONSTRAINT fk_stock_product FOREIGN KEY (product_id) REFERENCES product (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- ─────────────────────────────────────────────────────────
-- stock_commit_dedupe
-- StockCommitConsumer 의 eventUUID dedupe 전용 테이블.
-- ─────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS stock_commit_dedupe
(
    event_uuid VARCHAR(64) NOT NULL,
    order_id   BIGINT,
    product_id BIGINT,
    qty        INT,
    expires_at TIMESTAMP   NOT NULL,
    created_at TIMESTAMP   DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (event_uuid),
    INDEX idx_expires_at (expires_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
