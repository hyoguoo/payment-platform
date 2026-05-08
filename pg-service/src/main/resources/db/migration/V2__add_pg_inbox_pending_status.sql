-- PG-CONFIRM-LISTENER-SPLIT §1.5: pg_inbox.status ENUM 변경
-- NONE 폐기 + PENDING 추가 (위키 stateDiagram [*] --> PENDING 정합).
-- dev/test 환경 전용 — 운영 데이터 부재 가정.
-- 기존 NONE row 가 있다면 PENDING 으로 사전 변환 후 ENUM 수정.

-- NONE → PENDING 사전 변환 (dev/test 시드 데이터 대비 — 실제로는 row 없을 가능성 높음)
UPDATE pg_inbox
SET status = 'PENDING'
WHERE status = 'NONE';

-- ENUM 재정의: NONE 제거, PENDING 추가 (순서: PENDING 이 초기 상태로 첫 번째)
ALTER TABLE pg_inbox
    MODIFY COLUMN status ENUM('PENDING','IN_PROGRESS','APPROVED','FAILED','QUARANTINED') NOT NULL;
