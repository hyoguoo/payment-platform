-- ─────────────────────────────────────────────────────────
-- payment_event.retry_count 컬럼 drop
-- pg-service self-loop 재시도 전환 이후 payment 측 retry_count 갱신 경로가
-- 모두 사라져 死 컬럼이 됨 (재시도 관측은 pg attempt 로 전담).
-- RETRY-METRIC-CLEANUP 토픽 Task 2 결정.
-- 참고: payment_outbox.retry_count(V1)는 별개 컬럼이며 PaymentOutbox
-- 재시도 정책에서 여전히 사용 중이라 보존한다.
-- 참고: MySQL 은 ALTER TABLE ... DROP COLUMN IF EXISTS 구문을 지원하지 않는다
-- (DROP TABLE IF EXISTS 와 달리 컬럼 단위 IF EXISTS 는 MariaDB 전용 확장).
-- Flyway 가 버전당 1회만 적용하므로 멱등성은 마이그레이션 체크섬으로 보장된다.
-- ─────────────────────────────────────────────────────────
ALTER TABLE payment_event
    DROP COLUMN retry_count;
