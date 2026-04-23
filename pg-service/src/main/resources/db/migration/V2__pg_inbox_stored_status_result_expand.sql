-- Flyway V2 — stored_status_result 컬럼 확장 (VARCHAR(50) → VARCHAR(1024)).
-- 사유: 원문 상태 스냅샷이 아닌 재발행용 전체 JSON payload({"orderId":"<UUID>","status":"APPROVED"} 등)를
-- 저장하므로 UUID(36자)+JSON 래퍼 포함 시 50자를 쉽게 초과. Data truncation 방지.
ALTER TABLE pg_inbox
    MODIFY COLUMN stored_status_result VARCHAR(1024);
