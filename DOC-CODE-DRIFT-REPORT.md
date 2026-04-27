# Documentation vs Code Drift Report

> 작성: 2026-04-27 (CONFIRM-FLOW 통합 작업 시 발견)
> 임시 파일 — 사용자가 검토 후 처리되면 삭제 가능

## 발견된 차이점

### Drift #1: Phase 5 flowchart 에서 `EventDedupeStore.markSeen` 잘못 기재

- **원본 문서**: PAYMENT-FLOW.md Phase 5 flowchart (line ~138)
- **기록 내용**: `EventDedupeStore.markSeen 중복?`
- **실제 코드**: `PaymentConfirmResultUseCase.handle` (line 122) — `eventDedupeStore.markWithLease(message.eventUuid(), leaseTtl)`
- **실제 동작**: payment-service 는 pg-service 와 달리 `markSeen` (단순 mark) 이 아닌 `markWithLease` (two-phase lease) 를 호출한다. pg-service 가 `markSeen` 사용. 두 서비스의 dedupe 모델이 다름.
- **처리**: PAYMENT-FLOW.md Phase 5 flowchart `markSeen` → `markWithLease(eventUuid, leaseTtl=PT5M)` 로 정정 반영

### Drift #2: `confirmPublisher.publish` 파라미터 불일치

- **원본 문서**: PAYMENT-FLOW.md 시계열 요약 행 #6 — `confirmPublisher.publish(orderId, userId, amount, paymentKey)`
- **실제 코드**: `PaymentTransactionCoordinator.executeConfirmTx` (line 86~90) — `confirmPublisher.publish(orderId, paymentEvent.getBuyerId(), paymentEvent.getTotalAmount(), paymentKey)`. 파라미터 이름이 `userId` 가 아닌 `buyerId`.
- **처리**: 시계열 표 #6 를 `confirmPublisher.publish(orderId, buyerId, totalAmount, paymentKey)` 로 정정

### Drift #3: CONFIRM-FLOW-ANALYSIS.md 에서 `AMOUNT_MISMATCH` 시 redis 보상 기재 오류

- **원본 문서**: CONFIRM-FLOW-ANALYSIS.md `handleApproved` 분기 — `isAmountMismatch` 가 true 이면 `stockCachePort.increment 보상` 을 암시하는 설명 없음. CONFIRM-FLOW-FLOWCHART.md 5번 다이어그램에서는 `QU_AM` 노드에 `stockCachePort.increment 보상` 을 명시.
- **실제 코드**: `PaymentConfirmResultUseCase.handleApproved` (line 191~218) — AMOUNT_MISMATCH 분기에서 `stockCachePort.increment` 직접 호출 없음. `QuarantineCompensationHandler.handle` 위임 후 early return.
- **실제 동작**: AMOUNT_MISMATCH 격리 시 `handleApproved` 는 redis 보상을 직접 수행하지 않는다 — 격리 정책에 따라 보상 없이 quarantine 전이만 함. (FAILED/QUARANTINED 분기는 `compensateStockCache` 호출함)
- **처리**: CONFIRM-FLOW.md 8절에 "AMOUNT_MISMATCH 시 redis 보상 미수행" 명시

### Drift #4: CONFIRM-FLOW-FLOWCHART.md 에서 `handleQuarantined` AMOUNT_MISMATCH 를 `QU_AM` 로 표현 — extendLease 흐름 누락

- **원본 문서**: CONFIRM-FLOW-FLOWCHART.md 5번 다이어그램 — `QU_AM` 가 `stockCachePort.increment 보상` 을 하고 `extendLease` 로 합류하는 흐름
- **실제 코드**: `handleApproved` 의 AMOUNT_MISMATCH 분기 (line 199~204) — `QuarantineCompensationHandler.handle` 호출 후 return. stockCachePort.increment 없음. extendLease 는 `processMessageWithLeaseGuard` 에서 `processMessage` 성공 후 호출되므로 AMOUNT_MISMATCH 경우에도 extendLease 가 호출됨 (handleApproved 가 예외 없이 return 하므로).
- **처리**: CONFIRM-FLOW.md 5절 다이어그램에 정확히 반영

### Drift #5: CONFIRM-FLOW-ANALYSIS.md `OutboxWorker` 기본 설정값 오류

- **원본 문서**: CONFIRM-FLOW-ANALYSIS.md `OutboxWorker` 절 — `batchSize 기본 50건`
- **실제 코드**: `OutboxWorker` 생성자 (line 26) — `@Value("${scheduler.outbox-worker.batch-size:10}") int batchSize`. application.yml 에서 `scheduler.outbox-worker.batch-size: 50` 으로 재정의. annotation default 는 10 이지만 application.yml 에서 50 으로 설정.
- **처리**: 코드 default 10 이지만 application.yml 값 50 이 실제 동작값. CONFIRM-FLOW.md 4절에 "기본 50건 (application.yml)" 으로 정확히 기재

### Drift #6: CONFIRM-FLOW-ANALYSIS.md `PaymentReconciler` 스케줄 주기 서술 불일치

- **원본 문서**: CONFIRM-FLOW-ANALYSIS.md — `PaymentReconciler (@Scheduled 2분)`
- **실제 코드**: `PaymentReconciler.scan` (line 44) — `@Scheduled(fixedDelayString = "${reconciler.fixed-delay-ms:120000}")`. 120000ms = 2분 일치. 하지만 application.yml 에 `reconciler.fixed-delay-ms` 별도 설정 없음 → 기본값 120000ms (2분) 사용.
- **처리**: 수치 일치하므로 기록 정확. CONFIRM-FLOW.md 회복 시나리오 표에 `fixedDelayMs=120000` 명시

### Drift #7: PAYMENT-FLOW.md Phase 5 `QUARANTINED` 분기 서술 — FCG 언급

- **원본 문서**: PAYMENT-FLOW.md Phase 5 flowchart — `QuarantineCompensationHandler.handle / FCG 진입점 / 재고 복구 + 수동 조사 알림`
- **실제 코드**: `PaymentConfirmResultUseCase.handleQuarantined` (line 267~276) — `compensateStockCache` + `quarantineCompensationHandler.handle`. FCG (`PgFinalConfirmationGate`) 는 payment-service 소스에 없음. 장애 복원 포인트의 "FCG 경로는 pg-service PgFinalConfirmationGate 가 담당" 서술은 PAYMENT-FLOW.md 내 다른 위치에 명시됨.
- **처리**: Phase 5 flowchart 에서 "FCG 진입점" 표현 제거 → `compensateStockCache (redis INCR) + QuarantineCompensationHandler.handle` 로 정정

### Drift #8: PAYMENT-FLOW.md 로컬 구동 주의사항 — "Lua DECR" 언급

- **원본 문서**: PAYMENT-FLOW.md 로컬 구동 주의사항 — `confirm 진입 시 Lua DECR 결과가 음수`
- **실제 코드**: `StockCacheRedisAdapter` (재고 선차감 캐시) 는 Redis DECR 를 사용하나 Lua 스크립트 여부는 현재 코드 기준 확인 필요. 단, commit `58a055e9` 에서 `IdempotencyStoreRedisAdapter` 의 Lua 제거 → Spring opsForValue 변경이 있었음. redis-stock adapter 의 Lua 사용 여부는 별도 확인이 필요하지만 "Lua DECR" 표현은 오해 소지가 있음.
- **처리**: "Lua DECR" → "DECR" 로 중립 표현 정정

---

## 의문점 (코드만 보고 결정 어려움)

- **StockOutbox 폴링 폴백**: `StockOutboxRelayService.relay` 에서 `processedAt != null` 체크로 중복 방지를 하지만, stock_outbox 발행 실패 시 재시도하는 별도 폴링 워커(`@Scheduled`)가 있는지 코드에서 확인 불가. `OutboxWorker` 는 `payment_outbox` 만 다룸. stock_outbox 는 AFTER_COMMIT 리스너에만 의존하는지 사용자 확인 필요.

---

## 통합 후 검증 체크리스트

- [x] 모든 상태 / event type / 메서드 시그니처 코드와 정확 (PaymentEventStatus, PaymentOutboxStatus, PaymentConfirmResultUseCase 시그니처 확인)
- [x] 재시도 정책 표 (payment / pg) 코드 hardcoded 값과 일치 (payment: maxAttempts=5 FIXED 5s, pg: MAX_ATTEMPTS=4 EXPONENTIAL base=2s ×3 ±25%)
- [x] 장애 시나리오 인덱스 빠짐 없음 (CONFIRM-FLOW.md 11절)
- [x] dedupe 방식 정확 (payment: Redis two-phase lease, pg: Redis markSeen, product: JDBC stock_commit_dedupe)
- [x] traceparent / MDC 전파 메커니즘 정확 (AsyncConfig, ContextAwareVirtualThreadExecutors, Slf4jMdcThreadLocalAccessor)
- [x] 멱등성 layer 정확 (CONFIRM-FLOW.md 13절)
- [x] 최근 commit 변경사항 반영 (e524b514 attempt 헤더, 58a055e9 Lua 제거, 4da8dbb0/06c48d49 Feign)
- [x] cross-link 갱신 (CLAUDE.md, ARCHITECTURE.md, PAYMENT-FLOW.md, .claude/agents/domain-expert.md, .claude/skills/_shared/personas/domain-expert.md, .claude/skills/context-update/SKILL.md)
