-- benchmark 프로파일 전용 stock 초기화
-- setup()에서 1,000개 checkout 생성을 위해 충분한 stock 확보
UPDATE product SET stock = 5000 WHERE id = 1;
UPDATE product SET stock = 5000 WHERE id = 2;
