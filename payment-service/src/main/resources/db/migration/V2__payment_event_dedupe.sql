-- ─────────────────────────────────────────────────────────
-- payment_event_dedupe
-- payment.events.confirmed Kafka EOS 컨슈머의 멱등 INSERT 테이블.
-- event_uuid PK 로 INSERT IGNORE → 0 row 면 중복, 1 row 면 신규.
-- TTL = Kafka retention (7일) + 복구 버퍼 (1일) = 8일.
-- PAYMENT-EOS-TRANSITION 토픽 D5 결정.
-- ─────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS payment_event_dedupe
(
    event_uuid  VARCHAR(64) NOT NULL,
    order_id    BIGINT      NOT NULL,
    status      VARCHAR(32) NOT NULL,
    received_at TIMESTAMP   NOT NULL,
    expires_at  TIMESTAMP   NOT NULL,
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (event_uuid),
    INDEX idx_expires_at (expires_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
