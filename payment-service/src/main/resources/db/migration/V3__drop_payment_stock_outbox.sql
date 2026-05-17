-- ─────────────────────────────────────────────────────────
-- stock_outbox 테이블 drop
-- PET-9 에서 StockOutbox 묶음 19 파일 삭제로 코드 사용처 0 도달.
-- EOS 전환 (PET-6/7/8) 후 outbox 패턴 자체 폐기.
-- PAYMENT-EOS-TRANSITION 토픽 D2 #8 결정.
-- 참고: V1 DDL 의 실제 테이블명은 stock_outbox (payment_stock_outbox 는 설계 문서 상 비공식 명칭).
-- FK 제약 없음 — V1 schema 확인 완료.
-- ─────────────────────────────────────────────────────────
DROP TABLE IF EXISTS stock_outbox;
