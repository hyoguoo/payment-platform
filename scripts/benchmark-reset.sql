-- 벤치마크 전략 전환 시 이전 데이터 초기화 스크립트
-- 실행 순서: FK 의존 테이블 먼저 TRUNCATE 후 payment_event 삭제

SET FOREIGN_KEY_CHECKS = 0;

TRUNCATE TABLE payment_history;
TRUNCATE TABLE payment_order;
TRUNCATE TABLE payment_outbox;
TRUNCATE TABLE payment_process;
TRUNCATE TABLE payment_event;

SET FOREIGN_KEY_CHECKS = 1;

UPDATE product SET stock = 999999 WHERE id IN (1, 2);
