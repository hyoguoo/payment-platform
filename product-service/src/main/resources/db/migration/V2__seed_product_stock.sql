-- 데모 환경 초기 시드 — 스모크/벤치마크/로컬 개발 전용.
-- `INSERT IGNORE`로 멱등. 실환경 운영 배포에서는 시드 값이 이미 존재하면 no-op.

INSERT IGNORE INTO product (id, name, price, description, seller_id)
VALUES (1, 'Smoke Product', 1000.00, 'smoke seed', 1);

INSERT IGNORE INTO stock (product_id, quantity)
VALUES (1, 100);
