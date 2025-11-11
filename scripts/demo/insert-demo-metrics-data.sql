-- ============================================================================
-- Portfolio Metrics Data Generator (실제 스키마 기반)
-- 포트폴리오용 메트릭 데이터 직접 삽입 스크립트
--
-- 목적: 1시간 전부터 현재까지의 결제 데이터를 생성하여
--      모든 메트릭(State, Health, Transition History)을 즉시 활성화
--
-- 실행 방법:
--   docker exec -i payment-mysql mysql -uroot -ppayment123! payment-platform < scripts/insert-demo-metrics-data.sql
--
-- 소요 시간: 약 5초
-- ============================================================================

USE `payment-platform`;

-- 기존 데이터 정리 (선택사항 - 깨끗한 상태에서 시작)
-- DELETE FROM payment_history WHERE 1=1;
-- DELETE FROM payment_order WHERE 1=1;
-- DELETE FROM payment_event WHERE 1=1;

-- ============================================================================
-- 1. 정상 운영 데이터 (Normal Operations)
-- 목적: State metrics 활성화, 기본 흐름 시연
-- ============================================================================

-- 1.1 성공 완료된 결제 (DONE) - 30개
-- 시간 분포: 60분 전 ~ 5분 전
INSERT INTO payment_event (
    buyer_id, seller_id, order_name, order_id,
    payment_key, status, executed_at, approved_at,
    retry_count, status_reason, last_status_changed_at, created_at
)
SELECT
    1 + MOD(seq, 5) as buyer_id,
    1 as seller_id,
    CONCAT('Product ', seq) as order_name,
    CONCAT('ORDER-DONE-', LPAD(seq, 3, '0')) as order_id,
    CONCAT('toss_payment_', REPLACE(UUID(), '-', '')) as payment_key,
    'DONE' as status,
    DATE_SUB(NOW(), INTERVAL (60 - seq * 2) MINUTE) as executed_at,
    DATE_SUB(NOW(), INTERVAL (60 - seq * 2 - 1) MINUTE) as approved_at,
    0 as retry_count,
    NULL as status_reason,
    DATE_SUB(NOW(), INTERVAL (60 - seq * 2 - 1) MINUTE) as last_status_changed_at,
    DATE_SUB(NOW(), INTERVAL (60 - seq * 2) MINUTE) as created_at
FROM (
    SELECT 1 as seq UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL
    SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9 UNION ALL SELECT 10 UNION ALL
    SELECT 11 UNION ALL SELECT 12 UNION ALL SELECT 13 UNION ALL SELECT 14 UNION ALL SELECT 15 UNION ALL
    SELECT 16 UNION ALL SELECT 17 UNION ALL SELECT 18 UNION ALL SELECT 19 UNION ALL SELECT 20 UNION ALL
    SELECT 21 UNION ALL SELECT 22 UNION ALL SELECT 23 UNION ALL SELECT 24 UNION ALL SELECT 25 UNION ALL
    SELECT 26 UNION ALL SELECT 27 UNION ALL SELECT 28 UNION ALL SELECT 29 UNION ALL SELECT 30
) as numbers;

-- 1.2 준비 상태 (READY) - 5개
-- Age bucket 분포: 0-5분 (3개), 5-30분 (2개)
INSERT INTO payment_event (
    buyer_id, seller_id, order_name, order_id,
    payment_key, status, executed_at, approved_at,
    retry_count, status_reason, last_status_changed_at, created_at
)
VALUES
    -- 0-5분 (fresh)
    (2, 1, 'Fresh Product 1', 'ORDER-READY-001',
     NULL, 'READY', NULL, NULL, 0, NULL,
     DATE_SUB(NOW(), INTERVAL 2 MINUTE), DATE_SUB(NOW(), INTERVAL 2 MINUTE)),
    (3, 1, 'Fresh Product 2', 'ORDER-READY-002',
     NULL, 'READY', NULL, NULL, 0, NULL,
     DATE_SUB(NOW(), INTERVAL 3 MINUTE), DATE_SUB(NOW(), INTERVAL 3 MINUTE)),
    (4, 1, 'Fresh Product 3', 'ORDER-READY-003',
     NULL, 'READY', NULL, NULL, 0, NULL,
     DATE_SUB(NOW(), INTERVAL 4 MINUTE), DATE_SUB(NOW(), INTERVAL 4 MINUTE)),

    -- 5-30분 (medium age)
    (5, 1, 'Medium Product 1', 'ORDER-READY-004',
     NULL, 'READY', NULL, NULL, 0, NULL,
     DATE_SUB(NOW(), INTERVAL 10 MINUTE), DATE_SUB(NOW(), INTERVAL 10 MINUTE)),
    (1, 1, 'Medium Product 2', 'ORDER-READY-005',
     NULL, 'READY', NULL, NULL, 0, NULL,
     DATE_SUB(NOW(), INTERVAL 15 MINUTE), DATE_SUB(NOW(), INTERVAL 15 MINUTE));

-- 1.3 진행 중 (IN_PROGRESS) - 8개
-- Age bucket 분포: 0-5분 (5개), 5-30분 (3개)
INSERT INTO payment_event (
    buyer_id, seller_id, order_name, order_id,
    payment_key, status, executed_at, approved_at,
    retry_count, status_reason, last_status_changed_at, created_at
)
VALUES
    -- 0-5분 (정상 처리 중)
    (2, 1, 'Processing 1', 'ORDER-PROG-001',
     'toss_prog_001', 'IN_PROGRESS',
     DATE_SUB(NOW(), INTERVAL 2 MINUTE), NULL, 0, NULL,
     DATE_SUB(NOW(), INTERVAL 2 MINUTE), DATE_SUB(NOW(), INTERVAL 2 MINUTE)),
    (3, 1, 'Processing 2', 'ORDER-PROG-002',
     'toss_prog_002', 'IN_PROGRESS',
     DATE_SUB(NOW(), INTERVAL 3 MINUTE), NULL, 0, NULL,
     DATE_SUB(NOW(), INTERVAL 3 MINUTE), DATE_SUB(NOW(), INTERVAL 3 MINUTE)),
    (4, 1, 'Processing 3', 'ORDER-PROG-003',
     'toss_prog_003', 'IN_PROGRESS',
     DATE_SUB(NOW(), INTERVAL 4 MINUTE), NULL, 1, NULL,
     DATE_SUB(NOW(), INTERVAL 3 MINUTE), DATE_SUB(NOW(), INTERVAL 4 MINUTE)),
    (5, 1, 'Processing 4', 'ORDER-PROG-004',
     'toss_prog_004', 'IN_PROGRESS',
     DATE_SUB(NOW(), INTERVAL 4 MINUTE), NULL, 0, NULL,
     DATE_SUB(NOW(), INTERVAL 4 MINUTE), DATE_SUB(NOW(), INTERVAL 4 MINUTE)),
    (1, 1, 'Processing 5', 'ORDER-PROG-005',
     'toss_prog_005', 'IN_PROGRESS',
     DATE_SUB(NOW(), INTERVAL 5 MINUTE), NULL, 0, NULL,
     DATE_SUB(NOW(), INTERVAL 5 MINUTE), DATE_SUB(NOW(), INTERVAL 5 MINUTE)),

    -- 5-30분 (정상 범위 내)
    (2, 1, 'Processing 6', 'ORDER-PROG-006',
     'toss_prog_006', 'IN_PROGRESS',
     DATE_SUB(NOW(), INTERVAL 8 MINUTE), NULL, 2, NULL,
     DATE_SUB(NOW(), INTERVAL 6 MINUTE), DATE_SUB(NOW(), INTERVAL 8 MINUTE)),
    (3, 1, 'Processing 7', 'ORDER-PROG-007',
     'toss_prog_007', 'IN_PROGRESS',
     DATE_SUB(NOW(), INTERVAL 10 MINUTE), NULL, 1, NULL,
     DATE_SUB(NOW(), INTERVAL 8 MINUTE), DATE_SUB(NOW(), INTERVAL 10 MINUTE)),
    (4, 1, 'Processing 8', 'ORDER-PROG-008',
     'toss_prog_008', 'IN_PROGRESS',
     DATE_SUB(NOW(), INTERVAL 12 MINUTE), NULL, 0, NULL,
     DATE_SUB(NOW(), INTERVAL 12 MINUTE), DATE_SUB(NOW(), INTERVAL 12 MINUTE));

-- ============================================================================
-- 2. 에러 및 실패 케이스 (Error Cases)
-- 목적: 실패 관련 메트릭 활성화
-- ============================================================================

-- 2.1 실패 상태 (FAILED) - 3개
INSERT INTO payment_event (
    buyer_id, seller_id, order_name, order_id,
    payment_key, status, executed_at, approved_at,
    retry_count, status_reason, last_status_changed_at, created_at
)
VALUES
    (5, 1, 'Failed Payment 1', 'ORDER-FAIL-001',
     'toss_fail_001', 'FAILED',
     DATE_SUB(NOW(), INTERVAL 20 MINUTE), NULL, 3, 'INSUFFICIENT_BALANCE',
     DATE_SUB(NOW(), INTERVAL 15 MINUTE), DATE_SUB(NOW(), INTERVAL 20 MINUTE)),
    (1, 1, 'Failed Payment 2', 'ORDER-FAIL-002',
     'toss_fail_002', 'FAILED',
     DATE_SUB(NOW(), INTERVAL 30 MINUTE), NULL, 5, 'NETWORK_TIMEOUT',
     DATE_SUB(NOW(), INTERVAL 25 MINUTE), DATE_SUB(NOW(), INTERVAL 30 MINUTE)),
    (2, 1, 'Failed Payment 3', 'ORDER-FAIL-003',
     'toss_fail_003', 'FAILED',
     DATE_SUB(NOW(), INTERVAL 40 MINUTE), NULL, 2, 'INVALID_CARD',
     DATE_SUB(NOW(), INTERVAL 38 MINUTE), DATE_SUB(NOW(), INTERVAL 40 MINUTE));

-- 2.2 만료 상태 (EXPIRED) - 2개
INSERT INTO payment_event (
    buyer_id, seller_id, order_name, order_id,
    payment_key, status, executed_at, approved_at,
    retry_count, status_reason, last_status_changed_at, created_at
)
VALUES
    (3, 1, 'Expired Payment 1', 'ORDER-EXP-001',
     NULL, 'EXPIRED', NULL, NULL, 0, 'EXCEEDED_TIME_LIMIT',
     DATE_SUB(NOW(), INTERVAL 20 MINUTE), DATE_SUB(NOW(), INTERVAL 50 MINUTE)),
    (4, 1, 'Expired Payment 2', 'ORDER-EXP-002',
     NULL, 'EXPIRED', NULL, NULL, 0, 'USER_ABANDONED',
     DATE_SUB(NOW(), INTERVAL 25 MINUTE), DATE_SUB(NOW(), INTERVAL 55 MINUTE));

-- ============================================================================
-- 3. 헬스 메트릭 활성화 데이터 (Health Alerts)
-- 목적: 모든 health metrics > 0 만들기
-- ============================================================================

-- 3.1 Stuck in Progress (5분 이상 경과) - 2개
-- payment_health_stuck_in_progress > 0
INSERT INTO payment_event (
    buyer_id, seller_id, order_name, order_id,
    payment_key, status, executed_at, approved_at,
    retry_count, status_reason, last_status_changed_at, created_at
)
VALUES
    (5, 1, 'Stuck Payment 1', 'ORDER-STUCK-001',
     'toss_stuck_001', 'IN_PROGRESS',
     DATE_SUB(NOW(), INTERVAL 10 MINUTE), NULL, 1, NULL,
     DATE_SUB(NOW(), INTERVAL 10 MINUTE), DATE_SUB(NOW(), INTERVAL 10 MINUTE)),
    (1, 1, 'Stuck Payment 2', 'ORDER-STUCK-002',
     'toss_stuck_002', 'IN_PROGRESS',
     DATE_SUB(NOW(), INTERVAL 15 MINUTE), NULL, 2, NULL,
     DATE_SUB(NOW(), INTERVAL 15 MINUTE), DATE_SUB(NOW(), INTERVAL 15 MINUTE));

-- 3.2 Unknown Status - 1개
-- payment_health_unknown_status > 0
INSERT INTO payment_event (
    buyer_id, seller_id, order_name, order_id,
    payment_key, status, executed_at, approved_at,
    retry_count, status_reason, last_status_changed_at, created_at
)
VALUES
    (2, 1, 'Unknown Payment 1', 'ORDER-UNK-001',
     'toss_unknown_001', 'UNKNOWN',
     DATE_SUB(NOW(), INTERVAL 8 MINUTE), NULL, 3, 'TOSS_RETURNED_UNKNOWN',
     DATE_SUB(NOW(), INTERVAL 5 MINUTE), DATE_SUB(NOW(), INTERVAL 8 MINUTE));

-- 3.3 Max Retry Reached (5회 이상) - 2개
-- payment_health_max_retry_reached > 0
INSERT INTO payment_event (
    buyer_id, seller_id, order_name, order_id,
    payment_key, status, executed_at, approved_at,
    retry_count, status_reason, last_status_changed_at, created_at
)
VALUES
    (3, 1, 'Max Retry 1', 'ORDER-MAX-001',
     'toss_retry_001', 'IN_PROGRESS',
     DATE_SUB(NOW(), INTERVAL 20 MINUTE), NULL, 5, NULL,
     DATE_SUB(NOW(), INTERVAL 18 MINUTE), DATE_SUB(NOW(), INTERVAL 20 MINUTE)),
    (4, 1, 'Max Retry 2', 'ORDER-MAX-002',
     'toss_retry_002', 'FAILED',
     DATE_SUB(NOW(), INTERVAL 35 MINUTE), NULL, 6, 'EXCEEDED_MAX_RETRIES',
     DATE_SUB(NOW(), INTERVAL 30 MINUTE), DATE_SUB(NOW(), INTERVAL 35 MINUTE));

-- 3.4 Near Expiration (만료 5분 전 - 25~29분 경과) - 3개
-- payment_health_near_expiration > 0
INSERT INTO payment_event (
    buyer_id, seller_id, order_name, order_id,
    payment_key, status, executed_at, approved_at,
    retry_count, status_reason, last_status_changed_at, created_at
)
VALUES
    (5, 1, 'Near Expiration 1', 'ORDER-NEXP-001',
     NULL, 'READY', NULL, NULL, 0, NULL,
     DATE_SUB(NOW(), INTERVAL 26 MINUTE), DATE_SUB(NOW(), INTERVAL 26 MINUTE)),
    (1, 1, 'Near Expiration 2', 'ORDER-NEXP-002',
     'toss_nearexp_002', 'IN_PROGRESS',
     DATE_SUB(NOW(), INTERVAL 27 MINUTE), NULL, 1, NULL,
     DATE_SUB(NOW(), INTERVAL 27 MINUTE), DATE_SUB(NOW(), INTERVAL 27 MINUTE)),
    (2, 1, 'Near Expiration 3', 'ORDER-NEXP-003',
     NULL, 'READY', NULL, NULL, 0, NULL,
     DATE_SUB(NOW(), INTERVAL 28 MINUTE), DATE_SUB(NOW(), INTERVAL 28 MINUTE));

-- ============================================================================
-- 4. Payment History (Transition 메트릭용)
-- 목적: payment_transition_window_total 활성화
-- ============================================================================

-- DONE 결제들의 transition history (READY → IN_PROGRESS → DONE)
-- payment_event_id를 가져오기 위해 서브쿼리 사용
INSERT INTO payment_history (
    payment_event_id, order_id, previous_status, current_status, reason, change_status_at, created_at
)
SELECT
    pe.id as payment_event_id,
    pe.order_id,
    'READY' as previous_status,
    'IN_PROGRESS' as current_status,
    NULL as reason,
    DATE_SUB(NOW(), INTERVAL CAST(SUBSTRING(pe.order_id, 12) AS UNSIGNED) * 2 MINUTE) as change_status_at,
    DATE_SUB(NOW(), INTERVAL CAST(SUBSTRING(pe.order_id, 12) AS UNSIGNED) * 2 MINUTE) as created_at
FROM payment_event pe
WHERE pe.order_id LIKE 'ORDER-DONE-%'
  AND pe.status = 'DONE';

INSERT INTO payment_history (
    payment_event_id, order_id, previous_status, current_status, reason, change_status_at, created_at
)
SELECT
    pe.id as payment_event_id,
    pe.order_id,
    'IN_PROGRESS' as previous_status,
    'DONE' as current_status,
    NULL as reason,
    pe.approved_at as change_status_at,
    pe.approved_at as created_at
FROM payment_event pe
WHERE pe.order_id LIKE 'ORDER-DONE-%'
  AND pe.status = 'DONE';

-- FAILED 결제들의 transition history
INSERT INTO payment_history (
    payment_event_id, order_id, previous_status, current_status, reason, change_status_at, created_at
)
SELECT
    pe.id as payment_event_id,
    pe.order_id,
    'READY' as previous_status,
    'IN_PROGRESS' as current_status,
    NULL as reason,
    pe.executed_at as change_status_at,
    pe.executed_at as created_at
FROM payment_event pe
WHERE pe.order_id LIKE 'ORDER-FAIL-%'
  AND pe.status = 'FAILED';

INSERT INTO payment_history (
    payment_event_id, order_id, previous_status, current_status, reason, change_status_at, created_at
)
SELECT
    pe.id as payment_event_id,
    pe.order_id,
    'IN_PROGRESS' as previous_status,
    'FAILED' as current_status,
    pe.status_reason as reason,
    pe.last_status_changed_at as change_status_at,
    pe.last_status_changed_at as created_at
FROM payment_event pe
WHERE pe.order_id LIKE 'ORDER-FAIL-%'
  AND pe.status = 'FAILED';

-- EXPIRED 결제들의 transition history
INSERT INTO payment_history (
    payment_event_id, order_id, previous_status, current_status, reason, change_status_at, created_at
)
SELECT
    pe.id as payment_event_id,
    pe.order_id,
    'READY' as previous_status,
    'EXPIRED' as current_status,
    pe.status_reason as reason,
    pe.last_status_changed_at as change_status_at,
    pe.last_status_changed_at as created_at
FROM payment_event pe
WHERE pe.order_id LIKE 'ORDER-EXP-%'
  AND pe.status = 'EXPIRED';

-- IN_PROGRESS 진행 중인 결제들의 transition history
INSERT INTO payment_history (
    payment_event_id, order_id, previous_status, current_status, reason, change_status_at, created_at
)
SELECT
    pe.id as payment_event_id,
    pe.order_id,
    'READY' as previous_status,
    'IN_PROGRESS' as current_status,
    NULL as reason,
    pe.executed_at as change_status_at,
    pe.executed_at as created_at
FROM payment_event pe
WHERE pe.order_id LIKE 'ORDER-PROG-%'
  AND pe.status = 'IN_PROGRESS';

-- ============================================================================
-- 5. 데이터 검증 쿼리 (삽입 후 확인용)
-- ============================================================================

-- 상태별 개수 확인
SELECT status, COUNT(*) as count,
       MIN(created_at) as oldest,
       MAX(created_at) as newest
FROM payment_event
GROUP BY status
ORDER BY
    CASE status
        WHEN 'READY' THEN 1
        WHEN 'IN_PROGRESS' THEN 2
        WHEN 'DONE' THEN 3
        WHEN 'FAILED' THEN 4
        WHEN 'EXPIRED' THEN 5
        WHEN 'UNKNOWN' THEN 6
    END;

-- Health 조건 확인
SELECT
    'Stuck in Progress (>5min)' as metric,
    COUNT(*) as count
FROM payment_event
WHERE status = 'IN_PROGRESS'
  AND executed_at < DATE_SUB(NOW(), INTERVAL 5 MINUTE)

UNION ALL

SELECT
    'Unknown Status' as metric,
    COUNT(*) as count
FROM payment_event
WHERE status = 'UNKNOWN'

UNION ALL

SELECT
    'Max Retry Reached (>=5)' as metric,
    COUNT(*) as count
FROM payment_event
WHERE retry_count >= 5

UNION ALL

SELECT
    'Near Expiration (25-30min)' as metric,
    COUNT(*) as count
FROM payment_event
WHERE status IN ('READY', 'IN_PROGRESS')
  AND created_at BETWEEN DATE_SUB(NOW(), INTERVAL 30 MINUTE)
                     AND DATE_SUB(NOW(), INTERVAL 25 MINUTE);

-- Transition history 확인
SELECT
    CONCAT(previous_status, ' → ', current_status) as transition,
    COUNT(*) as count,
    MIN(change_status_at) as oldest,
    MAX(change_status_at) as newest
FROM payment_history
GROUP BY previous_status, current_status
ORDER BY count DESC;

-- Age bucket 분포 확인
SELECT
    status,
    CASE
        WHEN created_at >= DATE_SUB(NOW(), INTERVAL 5 MINUTE) THEN '0-5m'
        WHEN created_at >= DATE_SUB(NOW(), INTERVAL 30 MINUTE) THEN '5-30m'
        ELSE '30m+'
    END as age_bucket,
    COUNT(*) as count
FROM payment_event
GROUP BY status, age_bucket
ORDER BY status, age_bucket;

-- ============================================================================
-- 완료 메시지
-- ============================================================================
SELECT
    '✅ Portfolio metrics data inserted successfully!' as status,
    COUNT(*) as total_payments
FROM payment_event;
