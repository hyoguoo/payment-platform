-- product-service V1 초기 스키마
-- mysql-product 컨테이너 (포트 3309) — docker-compose 추가는 Phase 3 Gate 또는 별도 태스크.
-- payment-service의 product 테이블과 별도 DB에 존재 (충돌 없음).

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

-- EventDedupeStore DB 구현체용 — product-service 독립 dedupe 테이블
-- pg-service의 dedupe 방식(markSeen/boolean)과 달리 TTL 기반 recordIfAbsent 방식 채택.
CREATE TABLE IF NOT EXISTS product_event_dedupe
(
    event_uuid CHAR(36)    NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (event_uuid)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
