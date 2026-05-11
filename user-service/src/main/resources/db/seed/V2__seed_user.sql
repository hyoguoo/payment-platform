-- 데모 환경 초기 시드 — 스모크/벤치마크/로컬 개발 전용.
-- `INSERT IGNORE`로 멱등. 실환경 운영 배포에서는 시드 값이 이미 존재하면 no-op.

INSERT IGNORE INTO `user` (id, email) VALUES (1, 'smoke@test.com');
