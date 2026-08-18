-- ─────────────────────────────────────────────────────────
-- stock_hold_record
-- 재고 선차감 게이트가 상품 단위로 남기는 기록. 주문번호·상품번호 조합마다 한 행이고
-- status 는 NOISE(차감 직전에 적힘) / REVERTED(되돌려짐) / COMMITTED(벤더 승인 반영과
-- 같은 트랜잭션에서 확정, 이후 재요청에 훼손되지 않음) 세 가지를 오간다.
--
-- order_id + product_id 에 유일 제약을 걸어 재진입 때마다 행이 쌓이지 않게 한다 —
-- 행이 여럿이면 미회수 합산과 회수 판정이 어긋난다.
--
-- cycle_token 은 이 행이 지금 속한 차감 사이클을 식별한다. 되돌리는 주체가 여럿이라
-- (거절 전용 되돌리기, 확정 실패, 격리 진입, 관리자 종결, 주기 회수) 캐시 되돌리기와
-- 기록 닫기 사이에 새 사이클이 열릴 수 있는데, 상태값만으로는 그 새 사이클을 "자기 것"과
-- 구분할 수 없다. 닫기는 조회 시점 cycle_token 이 그대로일 때만 반영되고, 그 사이 값이
-- 바뀌었으면(새 사이클이 열렸으면) 반영 없이 실패한다.
-- ─────────────────────────────────────────────────────────
CREATE TABLE stock_hold_record (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    order_id     VARCHAR(100) NOT NULL,
    product_id   BIGINT       NOT NULL,
    quantity     INT          NOT NULL,
    status       VARCHAR(20)  NOT NULL,
    cycle_token  VARCHAR(36)  NOT NULL,
    created_at   DATETIME(6),
    updated_at   DATETIME(6),
    deleted_at   DATETIME(6),
    PRIMARY KEY (id),
    UNIQUE INDEX uk_stock_hold_record_order_product (order_id, product_id),
    INDEX idx_stock_hold_record_status (status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
