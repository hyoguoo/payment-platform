-- 참고용 DDL 스크립트 (운영 환경 적용 시 사용)
-- 현재 프로젝트는 ddl-auto: update 방식으로 운영 환경에서는 이 스크립트를 직접 실행해야 함

ALTER TABLE payment_event
    ADD COLUMN gateway_type VARCHAR(20) NOT NULL DEFAULT 'TOSS';
