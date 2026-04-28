-- 통합 테스트 시드 SQL. 현재 시드 데이터 없음 (MSA 분리 후 user/product 는 별도 서비스 책임).
-- 빈 파일이면 Spring @Sql 의 ScriptUtils 가 "script must not be null or empty" 예외를 던지므로
-- 의도적으로 NOOP 주석을 둔다. 시드가 필요해지면 여기에 INSERT 추가.
SELECT 1;
