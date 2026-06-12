# EOS-FOLLOWUP-CLEANUP — discuss interview 0

> Round 0 (Interviewer) 결과. 4트랙 ambiguity ledger + 확정 가정.

## Ambiguity Ledger (4트랙)

### scope (범위) — 커버됨
- **TOPIC**: `EOS-FOLLOWUP-CLEANUP` 확정 (사용자 승인).
- **모듈 경계**: payment-service / product-service / pg-service 3서비스 횡단. user-service / gateway / eureka 무관.
- **포함 작업군 5개**:
  1. FOLLOW-6 — 결제 결과 컨슈머 트랜잭션 매니저 qualifier 명시 + deprecated `setTransactionManager` → `setKafkaAwareTransactionManager` 교체 + best-effort 1PC 한계 문서화
  2. FOLLOW-5 — `PaymentEventStatus.isCompensatableByFailureHandler` 겸용 판별 메서드를 2개로 분리
  3. FOLLOW-2 — `payment_event_dedupe` 만료 행 일괄 cleanup 스케줄러
  4. TC-11 — product `stock_commit_dedupe` cleanup 스케줄러 + pg `pg_inbox` 종결 행 cleanup
  5. TC-15 항목3 — `pg_inbox` `stored_traceparent` 컬럼 + 폴링 회수 시 분산 추적 이어붙이기
- **non-goals (이번에 안 함)**:
  - 멀티 인스턴스/측정 의존 후속: TC-13-FOLLOW-1(hostname 충돌), -3(tx coordinator 모니터링), -4(D7 분기 알람 SLO)
  - `payment_outbox` retry 정책 재검토(TC-7, k6 측정 자물쇠)
  - 보상 경로 silent loss 정리(STOCK-COMPENSATION-OTHER-PATHS / TQ-7) — 별도 토픽
  - 분산 락(ShedLock) 도입 — 현 프로젝트 관행대로 미도입

### constraints (제약) — 커버됨
- **D-TM-QUALIFIER**: 결제 결과 컨슈머 진입점(`handle`)만 qualifier 명시. 나머지 13개+ `@Transactional`은 `@Primary` DB 매니저 그대로 두고, 트랜잭션 매니저 분리 원칙은 클래스 주석으로 문서화. (사용자 선택)
- **D-PGINBOX-CLEANUP**: pg_inbox는 `expires_at` 컬럼 신설 없이 "종결 상태(승인/실패/격리) + `updated_at` 경과" 기준으로 cleanup. 회수 대기(대기/진행) 행은 절대 삭제 금지. (사용자 선택)
- **청소 스케줄러**: 분산 락 없음(단일 인스턴스 가정). 동시 DELETE는 idempotent라 무해. 기존 `@Scheduled` 패턴(fixedDelay + 설정 기반 배치 + Micrometer) 따름.
- **보존 기간**: payment/product dedupe는 만료시각(8일) 기준. pg_inbox 보존 기간은 Architect가 기본값 제안 → Domain Expert 판정.

### outputs (산출물) — 커버됨
- `docs/topics/EOS-FOLLOWUP-CLEANUP.md` (설계 문서, 사전 브리핑 작성됨)
- 코드 변경: payment/product/pg 3서비스
- Flyway 마이그레이션: pg-service `stored_traceparent` 컬럼 추가 1건 (pg_inbox cleanup은 컬럼 추가 없음). payment/product dedupe는 인덱스 기존재로 마이그레이션 불요.

### verification (검증) — 커버됨
- **테스트 계층**: 단위 + Testcontainers 통합. (사용자 선택)
  - cleanup DELETE 실제 동작, traceparent 복원, Flyway 마이그레이션은 DB·트레이스 통합이라 통합 테스트 필수.
  - FOLLOW-5 메서드 분리 / FOLLOW-6 qualifier는 단위 + 컴파일 수준.
- **검증 항목**: 재시도 제외 목록(`IllegalArgumentException`/`IllegalStateException`/`MessageConversionException`)이 재시도 필요한 DB 인프라 실패(`DataAccessException` 계열 락/타임아웃)를 잘못 즉시 DLQ로 보내지 않는지 경계 확인.

## 질의응답 요약
- Q(TM qualifier 범위) → A: 컨슈머 진입점만 명시.
- Q(pg_inbox 청소 기준) → A: 종결 상태 + updated_at 경과.
- Q(검증 깊이) → A: 단위 + Testcontainers 통합.

## 확정 가정
1. 현재 중첩 트랜잭션(바깥 Kafka tx + 안쪽 DB tx) 구조는 Spring Kafka 공식 권장 패턴 — 재설계 없이 유지. best-effort 1PC 한계는 수용 + 문서화.
2. DB 실패 → DB 롤백 → 예외 전파 → Kafka tx abort → offset 미커밋 → 재배달(at-least-once). 단 5회 한정, 소진 시 DLQ.
3. `ChainedKafkaTransactionManager`는 deprecated이며 그 대체 패턴이 현 구조와 동일 — 폐기.
4. cleanup은 단일 인스턴스 가정, 분산 락 미도입.
