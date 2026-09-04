-- pg_inbox 의 status 단일 인덱스를 (status, updated_at) 복합으로 교체한다.
--
-- 배경 — 확정 결과를 기록하는 UPDATE 가 데드락으로 실패하고 있었다:
--   UPDATE pg_inbox SET status=?, stored_status_result=?, updated_at=?
--    WHERE order_id=? AND status=?
--
-- order_id 는 유니크라 이 조건만으로 행이 하나로 특정되는데, 옵티마이저가
-- index_merge intersect(ux_pg_inbox_order_id, idx_pg_inbox_status) 를 골라
-- 유니크 인덱스와 status 인덱스를 함께 잠갔다. 두 인덱스를 잡는 순서가 문장마다
-- 달라지면서 서로 다른 주문을 갱신하는 UPDATE 끼리 반대 순서로 물려 데드락이 났다.
-- (실측: 부하 5,000건 중 PG_INBOX_WORKER_FAIL 163건 = 3.3%. 실패한 건은 IN_PROGRESS 로
--  남아 60초 뒤 PgInboxPollingWorker 의 좀비 회수로만 종결돼, 종결 지연이 p50 509ms 인
--  반면 상위 2.1% 가 62~77초로 튀는 두 덩어리 분포를 만들었다.)
--
-- status 는 값이 다섯 개뿐인 enum 이라 단일 인덱스로는 선택도가 없다. updated_at 을 붙이면
-- 좀비 조회(WHERE status=? AND updated_at<? ORDER BY updated_at)가 그대로 인덱스를 타면서
-- 선택도가 올라가, 옵티마이저가 유니크 인덱스 단독 경로를 고르도록 유도한다.
-- 좌측 프리픽스가 status 라 기존의 status 단독 조회도 그대로 쓸 수 있다.

ALTER TABLE pg_inbox DROP INDEX idx_pg_inbox_status;
ALTER TABLE pg_inbox ADD INDEX idx_pg_inbox_status_updated_at (status, updated_at);
