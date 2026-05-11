-- PG-CONFIRM-LISTENER-SPLIT PCS-9: pg_inbox 에 paymentKey / vendorType 컬럼 추가
-- PCS-8 보고: PgInboxProcessor.buildRequest 가 PgConfirmRequest 구성 시 paymentKey / vendorType 필요.
-- listener 가 PENDING INSERT 시 이미 두 값을 알고 있으므로 함께 저장.
-- 워커(PgInboxProcessor)가 inboxId 기반으로 재조회 시 사용.

ALTER TABLE pg_inbox
    ADD COLUMN payment_key VARCHAR(200) DEFAULT NULL COMMENT '벤더 결제 키 (PENDING INSERT 시 기록)',
    ADD COLUMN vendor_type VARCHAR(50)  DEFAULT NULL COMMENT '벤더 타입 (e.g., TOSS_PAYMENTS)';
