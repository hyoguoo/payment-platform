-- stock_outbox 테이블 신설 — stock commit/restore 이벤트 발행에 transactional outbox 패턴 적용.
-- pg_outbox 와 동일 구조이지만 공유 lib 없이 독립 복제하며, payment_outbox 와 달리 order_id UNIQUE 제약이 없다
-- (한 주문이 여러 productId 에 대해 별도 row 를 갖는다).

CREATE TABLE stock_outbox (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    topic           VARCHAR(200) NOT NULL,
    `key`           VARCHAR(100) NOT NULL,
    payload         LONGTEXT     NOT NULL,
    headers_json    TEXT,
    available_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    processed_at    DATETIME(6),
    attempt         INT          NOT NULL DEFAULT 0,
    created_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_stock_outbox_processed_available (processed_at, available_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
