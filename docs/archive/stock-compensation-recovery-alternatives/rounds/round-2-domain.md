# Round 2 Domain Expert — STOCK-COMPENSATION-RECOVERY 대안 도메인 검증

> 검증 대상: docs/topics/STOCK-COMPENSATION-RECOVERY-ALTERNATIVES.md (Round 1 Architect 산출)
> 검증자 페르소나: Domain Expert (결제 도메인 리스크)
> 검증 기준: silent loss 차단 / race window / 돈 새는 경로 / state machine 위반 / 일반화 가능성

## 종합 verdict

7개 candidate 를 도메인 안전성 한 축 — **돈이 새지 않는가** — 으로만 줄세우면 결과가 Architect 의 비즈니스 흐름 정합성 추천과 정확히 갈린다. **Candidate 0 / F 는 도메인 안전성 fit (Lua 토큰으로 over-restore 강한 차단), Candidate B 는 Lua 없이도 outbox UNIQUE + claim CAS + Redis SETNX 로 fit 에 근접, Candidate A / D 는 강한 차단 부재로 partial, Candidate C 는 dedupe 의미 변경이 happy path 까지 침투해 "돈은 안 새지만 다른 새 silent loss 를 만든다", Candidate E 는 break (회복 트리거가 외부 의존이라 silent under-restore 가 회복 불가)** 다. Architect 의 1순위 A 는 도메인 안전성으로는 2~3순위로 떨어진다.

3가지 도메인 사실이 본 검증의 결론을 갈랐다. 첫째, `PaymentConfirmResultUseCase.handleFailed` 의 호출 순서가 **`markPaymentAsFail` (terminal 전이) → `compensateStockCache` (보상)** 다 (코드 사실, 266-268 행). 즉 PaymentEvent 가 FAILED 로 먼저 가고 보상이 뒤따른다 — 이 순서는 Candidate A 의 `STOCK_COMPENSATION_PENDING` 전이에 도메인적으로 맞지 않는다 (마킹은 이미 끝났는데 어떻게 다시 non-terminal 로 돌아가는가). 둘째, `payment_event` 스키마에 **`version` 컬럼이 없다** (V1 schema 재확인, 7-29행) — Candidate A 의 over-restore 강한 차단이 추가 schema 변경 (Round 1 D-A1 자체가 인정) 없이 불가능. 셋째, `PaymentOutbox.incrementRetryCount` / `toFailed` / `toDone` / `toInFlight` 모두 IN_FLIGHT 단일 상태 가드 (`PaymentOutbox.java` 36-65행) 로 잠겨 있다 — Candidate B 가 `STOCK_COMPENSATE` 타입을 같은 도메인 객체에 얹으면 lifecycle 분기가 도메인 메서드 가드를 모두 흔든다.

도메인 관점 deep dive 우선순위는 **B → F → A** 다. B 는 도메인 안전성이 fit 에 근접하면서 Architect 가 지적한 outbox 의미 일반화 위험을 도메인 메서드 가드 분리만 결정하면 흡수 가능. F 는 Lua 정직 도입을 받아들여 over-restore 강한 차단을 얻는다 — 다만 작업량이 가장 크다. A 는 도메인 정합성은 가장 좋아 보이지만 **호출 순서를 뒤집어야 하고 + version 컬럼을 추가해야 하고 + 항목 단위 격리를 포기해야 하는** 세 가지 도메인 부채를 동시에 진다.

---

## Candidate 0 (baseline) — 도메인 검증

### Silent loss 차단

| 경로 | 차단되는가 | 사유 |
|---|---|---|
| Silent over-restore (INCR 이중 호출) | fit | Lua SETNX `compensation:token:{outboxId}` + INCRBY atomic 묶음 — 같은 outboxId 두 번째 진입은 SETNX 실패로 INCR 미발생 |
| Silent under-restore (보상 누락) | fit | catch 블록에서 outbox INSERT 실패 자체가 별도 알람 (`insert_fail_total++` + `STOCK_COMPENSATION_OUTBOX_INSERT_FAILED` EventType) — 마지막 silent loss 도 가시화 |
| 부분 보상 일관성 | fit | 행 단위 (orderId, productId) UNIQUE — 한 결제 N 항목 중 일부만 INSERT, 일부만 회복 가능. 이미 회복된 항목이 다시 INCR 되는 race 는 토큰이 차단 |

### Race window 발굴

- **R0-1** (claim CAS 미도입): 단일 워커 가정이지만 PaymentReconciler / 다른 워커와 동시 실행 가능성. 본 토픽이 SKIP LOCKED 만 의존 — DB 인스턴스 페일오버 시 row lock 끊기는 짧은 윈도우에서 두 트랜잭션이 같은 row 를 픽업할 가능성. 단 Lua 토큰이 INCR 자체는 차단하므로 RDB 측 update 두 번이 일어나도 over-restore 는 막힌다 (그 대신 `worker_reentry_total++` 가 늘어난다).
- **R0-2** (토큰 TTL 만료 직전 재진입): P8D TTL 토큰이 만료되는 **8일 1초** 시점에 같은 outboxId row 가 살아 있다면 (운영자 reset 한 case) INCR 두 번 가능. 단 실 운영에서 8일 보존 보상 row 가 자동 회복 대상이 되는 경로 없음 (FAILED 자동 reset 없음, D3) — closed.
- **R0-3** (workers crash + Redis-only success): Lua 가 `INCR` 성공 후 RDB row update 직전 워커 크래시. 다음 시도에서 토큰이 살아있어 `ALREADY_PROCESSED` no-op 하지만 RDB row 는 PENDING attempt=N 상태로 남아 max-attempts 까지 재시도하다 FAILED 로 종결 — **재고는 회복됐는데 운영자에게 FAILED 알람이 뜨는** false-positive. 실 손실은 없음 (operationally noisy 만).

### 돈 새는 경로 검증

- **돈 새는 경로 0건**. Lua 토큰이 over-restore 의 차단을 보장하고, INSERT 실패는 별도 메트릭 + EventType 으로 알람 트리거 (silent 아님).
- 단 RDB outage 자체는 회복 책임 외 — 본 토픽이 명시적 non-goal.

### State machine 영향

- PaymentEvent 상태 전이 추가/변경 없음 — 보상 회복이 PaymentEvent 라이프사이클 외부 트랙. Round 1 신호 (d) 의 의미 그대로다.
- 기존 `isTerminal()` 가드와 정합성 위반 없음 — 본 회복 layer 가 PaymentEvent 를 건드리지 않음.
- 단 운영자가 PaymentEvent.status 만 보면 보상 진행 상황을 모름 — `stock_compensation_outbox` 별도 테이블을 봐야 함 (Round 1 신호 (d) 의 mental model 부담).

### 다른 보상 경로 일반화 가능성

- `OutboxAsyncConfirmService.compensateStock` (line 99-119) — 같은 catch swallow 패턴 + 같은 (orderId, productId, quantity) 시그니처. 본 candidate 의 appender + outbox 그대로 재사용 가능. **일반화 가능성 fit**.
- `PaymentTransactionCoordinator.compensateStockCacheGuarded` (line 168-180) — 같은 catch swallow + 같은 시그니처. 동일하게 적용 가능. **일반화 가능성 fit**.
- 본 토픽이 의도적으로 events.confirmed 경로만 다루지만, 후속 토픽이 같은 `StockCompensationOutboxAppender` 를 호출하면 끝 — 변경 surface 가 작다.

### 발견 사항 (findings)

- **D0-1 [MINOR]**: Lua 토큰 TTL P8D 와 dedupe lease P8D 정렬은 좋지만 토큰 수명이 outbox row 자동 reset 정책 (D3 에서 reset 안 함) 과 결합하면 운영자가 수동 reset 한 행에서 토큰이 잔존해 재시도 시 ALREADY_PROCESSED 가 영영 떨어지는 false-positive 가능. operationally noisy. plan 단계에서 admin reset 도구 설계 시 토큰 수동 삭제도 포함되어야 함.
- **D0-2 [MAJOR]**: R0-3 시나리오 — Lua INCR 성공 후 RDB update 직전 크래시 시 재고는 정상이지만 운영자 알람이 뜬다 (PENDING → 재시도 max → FAILED). 도메인적으로 안전 (over-restore 없음) 하지만 이 case 가 실제로 얼마나 자주 발생할지 본 토픽이 답하지 않음. plan 단계에서 메트릭 임계 정책 (`worker_reentry_total > 0` 가 알람인지 무시인지) 정확히 정의 필요.

---

## Candidate A — 도메인 검증

### Silent loss 차단

| 경로 | 차단되는가 | 사유 |
|---|---|---|
| Silent over-restore (INCR 이중 호출) | **break** | `lastStatusChangedAt` timeout 가드만으로는 race 차단 안 됨. PaymentReconciler 가 timeout 직후 픽업하는 동안 워커 크래시 → 재시작 후 Reconciler 가 같은 PaymentEvent 두 번 픽업 가능. **payment_event.version 컬럼 부재** (V1 schema 재확인) — Round 1 D-A1 이 인정한 한계 그대로 |
| Silent under-restore (보상 누락) | partial | Reconciler 가 `STOCK_COMPENSATION_PENDING + lastStatusChangedAt < cutoff` 픽업하므로 회복 트리거는 있음. 단 PaymentEvent 가 markStockCompensationPending 직후 update 자체가 실패 (RDB outage) 시 catch 블록의 처리 정의 안 됨 — 본 candidate 가 명시 안 함 |
| 부분 보상 일관성 | **break** | Round 1 D-A2 가 항목 단위 격리 포기를 명시. 한 결제의 productA INCR 성공 + productB INCR 실패 시 다음 retry 가 productA 도 다시 INCR → over-restore 발산 |

### Race window 발굴

- **RA-1** (호출 순서 모순 — 가장 critical): 코드 사실 — `handleFailed` 가 `markPaymentAsFail` (terminal 전이) **을 먼저 호출**하고 `compensateStockCache` 가 **뒤에** 온다 (PaymentConfirmResultUseCase.java line 266-268). PaymentEvent 가 이미 FAILED (terminal) 로 전이된 상태에서 보상이 실패한다. Candidate A 가 도입하는 `markStockCompensationPending` 은 도메인적으로 **terminal 상태에서 non-terminal 로 역전이**다. `PaymentEvent.quarantine` 은 isTerminal 가드로 IllegalStateException throw (PaymentEvent.java 162-167행). 즉 Candidate A 의 도입이 **호출 순서 자체를 뒤집어야 한다** — 이는 본 candidate 가 명시하지 않은 도메인 변경.
- **RA-2** (Reconciler + 워커 동시 트리거): Reconciler 가 IN_PROGRESS + `STOCK_COMPENSATION_PENDING` 두 분기를 같이 도는데, 한 PaymentEvent 가 두 분기에 걸치는 case (예: 보상 실패 후 markStockCompensationPending 호출 직전 race) 시 Reconciler 가 두 번 픽업. 단 각 `findIn*OlderThan` 쿼리가 SQL 단위 atomic 이므로 같은 row 가 두 결과 set 에 동시 들어가지는 않음 — 두 분기 처리 코드가 같은 row 를 같은 scan() 내 두 번 보지 않음을 plan 단계에서 명시 필요.
- **RA-3** (PaymentEvent 가 IN_PROGRESS 인 동안 보상 실패): 본 candidate 는 events.confirmed FAILED/QUARANTINED 진입에서만 보상하지만, Reconciler 가 IN_PROGRESS 도 픽업한다. IN_PROGRESS 회복 사이클에서 self-loop retry → 또 다른 eventUuid 의 결과가 도착해 두 번째 보상 호출 → 두 번째 markStockCompensationPending 호출 — `STOCK_COMPENSATION_PENDING → STOCK_COMPENSATION_PENDING` 자기 전이 처리 정의 안 됨.

### 돈 새는 경로 검증

- **돈 새는 경로 1건** — RA-1 의 호출 순서 변경 + 부분 보상 일관성 break 결합 시 발산 가능. 한 결제에 productA INCR 성공 + productB INCR 실패 → markStockCompensationPending → Reconciler 재시도 → productA 또 INCR (over-restore) + productB INCR 성공.
- 환불 후 재고 회복 미체결 가능성 — PaymentEvent 가 `STOCK_COMPENSATION_PENDING` 상태에서 user 환불 요청이 들어오면 어떻게 처리되는가? 본 candidate 가 답하지 않음. 환불도 막혀야 하는가, 환불은 별 트랙이라 무관한가.

### State machine 영향

- 신규 enum `STOCK_COMPENSATION_PENDING` (non-terminal) 도입.
- 기존 `isTerminal()` 가드 위반 — 위 RA-1 참조. 본 candidate 가 작동하려면 `handleFailed` 의 호출 순서를 뒤집어야 한다 — Candidate E 의 D-E1 과 같은 결정. 즉 **A 가 정합하려면 E 의 도메인 의미 변경을 같이 받아들여야 한다**. Round 1 산출물이 이를 명시하지 않음.
- `markPaymentAsFail` / `markPaymentAsQuarantined` 가드를 `STOCK_COMPENSATION_PENDING` 도 from 으로 허용해야 함 (Round 1 명시) — `PaymentEvent.fail` 의 isTerminalStatus 가드 (PaymentEvent.java 119행) 와 충돌하지 않으려면 STOCK_COMPENSATION_PENDING 이 isTerminal=false 인지 확인 OK.
- `isCompensatableByFailureHandler()` 가드 (PaymentEventStatus.java 34-39행) 영향 — 본 candidate 가 신규 상태를 추가하는데 본 가드의 switch 가 exhaustive 라 컴파일 에러. plan 에서 추가 결정 필요 — `STOCK_COMPENSATION_PENDING` 이 보상 가능 상태인가, 아닌가?

### 다른 보상 경로 일반화 가능성

- `OutboxAsyncConfirmService.compensateStock` — 본 candidate 가 PaymentEvent 가 IN_PROGRESS 직전에 일어나므로 markStockCompensationPending 도입이 IN_PROGRESS 진입 자체를 막아야 함. 즉 confirm flow 가 차단됨 — **일반화 가능성 break**.
- `PaymentTransactionCoordinator.compensateStockCacheGuarded` — D12 가드 (event.isCompensatableByFailureHandler) 안에서만 호출되는데 본 candidate 가 PaymentEvent 상태로 회복하면 D12 의 회복 가드 자체가 보상 회복과 결합 — coupling 증가. **일반화 가능성 partial**.

### 발견 사항 (findings)

- **DA-1 [BLOCKER]**: 호출 순서 모순 (RA-1). 본 candidate 가 작동하려면 `handleFailed` / `handleQuarantined` 의 markPaymentAsFail/Quarantined → compensateStockCache 순서를 뒤집어야 하고, 이는 도메인 의미 변경 (결제 실패 결정이 보상 성공 여부에 종속) — Candidate E 의 D-E1 과 동일한 부담. Round 1 산출물에 명시 안 됨.
- **DA-2 [BLOCKER]**: 부분 보상 일관성 break — Round 1 D-A2 가 인정. PaymentOrder 단위 가드 컬럼 (`compensated_at`) 을 추가하면 candidate 가 candidate F (또는 0) 만큼 무거워진다.
- **DA-3 [MAJOR]**: silent over-restore 강한 차단 부재 — Round 1 D-A3 가 인정. `payment_event.version` 컬럼 추가 필요 — V1 schema 변경. Round 1 이 "task 5~6개" 로 정성평가했지만 version 컬럼 추가 + 호출 순서 변경 + 항목 단위 가드 모두 합치면 작업량이 Candidate B 수준.
- **DA-4 [MAJOR]**: `isCompensatableByFailureHandler` switch 가 exhaustive — STOCK_COMPENSATION_PENDING 추가 시 컴파일 에러. D12 가드 (executePaymentFailureCompensationWithOutbox) 의 회복 의미와 본 candidate 의 회복 의미가 결합된다.
- **DA-5 [MAJOR]**: Reconciler 의 회복 latency 60~300초가 도메인 의미상 받아들일만한가? 사용자는 결제 실패 응답을 받았는데 재고는 5분 후에야 풀린다 — 다른 사용자가 그 사이 동일 상품 결제 시도 시 재고 부족 reject. silent under-restore 의 새로운 형태 (결제는 끝났는데 다른 결제가 막힘).

---

## Candidate B — 도메인 검증

### Silent loss 차단

| 경로 | 차단되는가 | 사유 |
|---|---|---|
| Silent over-restore (INCR 이중 호출) | partial → fit (조건부) | claimToInFlight CAS + IN_FLIGHT timeout + Redis SETNX `compensation:{orderId}:{productId}` (Lua 아님) 결합. SETNX 후 INCR 직전 크래시 시 token 만 살고 INCR 미발생 → 다음 시도가 `compensation:` 토큰 EXPIRE 후 재진입해야 함 — Round 1 산출물이 인정한 한계 |
| Silent under-restore (보상 누락) | fit | UNIQUE `(order_id, message_type, dedup_key)` + IN_FLIGHT timeout 으로 적재 멱등 + 워커 폴백 자연 회복 |
| 부분 보상 일관성 | fit | dedup_key 가 productId 면 행 단위 격리 — 결제 1건 = STOCK_COMPENSATE N행. 한 항목만 실패해도 다른 항목에 영향 없음 |

### Race window 발굴

- **RB-1** (CAS + Redis token 비대칭): claimToInFlight CAS 가 RDB 측 격리만 한다 — Redis INCR 두 호출은 별도 layer (Redis SETNX) 가 막아야 한다. SETNX 가 Lua 가 아니라 일반 SET NX 라면 SETNX 직후 / INCR 직전 사이에 두 번째 워커가 같은 토큰 키로 SETNX 실패 후 INCR skip — 안전. 하지만 같은 워커 자기 자신이 SETNX 성공 + INCR 실패 (Redis 일시 timeout) + RDB toFailed/incrementRetryCount 시 토큰만 남고 INCR 안 일어남 → 다음 시도가 토큰 만료 (TTL P8D) 후에만 재진입 → 운영 false-positive FAILED. 본 candidate 가 인정 (D-B3).
- **RB-2** (PaymentOutbox 도메인 가드 위반): `PaymentOutbox.toInFlight / toDone / toFailed / incrementRetryCount` 모두 IN_FLIGHT 단일 상태 가드 (PaymentOutbox.java 36-65행). STOCK_COMPENSATE 타입 row 가 같은 도메인 객체를 쓰면 같은 가드 통과 OK — 의미 충돌 없음. 하지만 future 변경 시 type 별 lifecycle 분기를 도메인이 알아야 할 수 있다 (예: STOCK_COMPENSATE 는 한도 도달 시 toFailed 가 아니라 별도 종결 상태가 필요한 case).
- **RB-3** (UNIQUE 제약 변경 운영 부하): `(order_id)` UNIQUE → `(order_id, message_type, dedup_key)` 변경은 운영 배포 시 lock — 기존 운영 중 데이터에 대해 dedup_key 가 NULL 이라면 NULL UNIQUE 처리 (MySQL 은 NULL 중복 허용) — 기존 CONFIRM_COMMAND row 들의 message_type/dedup_key 기본값이 무엇인지 plan 단계에서 결정 필요.

### 돈 새는 경로 검증

- **돈 새는 경로 0건 (조건부)** — RB-1 의 false-positive FAILED 가 운영 false-positive 일 뿐 실 재고 손실은 없음. 단 RB-1 case 가 발생했을 때 운영자가 admin 도구로 reset 시 토큰이 살아 있으면 재진입 시 INCR 안 일어남 — 토큰 수명 P8D 동안 운영자도 못 풀어줌. plan 단계에서 admin reset 시 토큰도 함께 삭제하는 절차 명시 필요.

### State machine 영향

- PaymentEvent 상태 전이 변경 없음 — `payment_outbox` 도메인 객체 lifecycle 만 type 분기.
- 기존 `isTerminal()` / `isCompensatableByFailureHandler()` 가드 위반 없음.
- payment_outbox UNIQUE 제약 + STOCK_COMPENSATE 타입의 도메인 의미가 겹치는지 확인 필요 — 결제 1건 = CONFIRM_COMMAND 1행 + STOCK_COMPENSATE N행 → outbox 가 결제 1건당 N+1 행이 생기는 의미적 부풀음.

### 다른 보상 경로 일반화 가능성

- `OutboxAsyncConfirmService.compensateStock` — 같은 catch swallow + 같은 시그니처. 본 candidate 의 appender 호출만 추가. **일반화 가능성 fit**.
- `PaymentTransactionCoordinator.compensateStockCacheGuarded` — 동일. **일반화 가능성 fit**.
- 단 두 보상 경로 모두 같은 `payment_outbox` 테이블에 STOCK_COMPENSATE 행을 추가하면 outbox 가 4종 작업 큐 (CONFIRM_COMMAND + STOCK_COMPENSATE_FROM_*3) 가 되어 의미가 더 흐려진다 — Round 1 D-B1 의 "비동기 작업 큐 일반화" 가 정당화되긴 함.

### 발견 사항 (findings)

- **DB-1 [MAJOR]**: PaymentOutbox 도메인 가드 분기 결정 누락. STOCK_COMPENSATE 타입에서 `incrementRetryCount` 호출 시 attempt 한도 도달이 `toFailed` 인가, 별도 종결 상태인가? CONFIRM_COMMAND 의 toFailed 는 "Kafka publish 영구 실패" 의미인데 STOCK_COMPENSATE 의 toFailed 는 "Redis INCR 영구 실패 → 재고 발산" 의미로 운영자 처리 priority 가 다르다. plan 단계에서 명시 필요.
- **DB-2 [MAJOR]**: RB-1 의 토큰 false-positive — 운영자 admin reset 시 토큰 수동 삭제 절차 명시 필요. Candidate 0 의 D0-1 과 같은 결.
- **DB-3 [MINOR]**: UNIQUE 제약 변경 마이그레이션 배포 부하 (RB-3) — 운영 중 lock 시간 측정 필요. 기존 row 의 message_type/dedup_key 기본값 결정.
- **DB-4 [MAJOR]**: outbox 의미 일반화가 다른 보상 경로 (OutboxAsyncConfirmService / PaymentTransactionCoordinator) 까지 적용되면 STOCK_COMPENSATE_FROM_CONFIRM / STOCK_COMPENSATE_FROM_RESULT / STOCK_COMPENSATE_FROM_FAILURE 같은 dedup_key 충돌 회피 변수까지 등장. 즉 본 candidate 가 단일 토픽 (events.confirmed) 안에서는 깔끔하지만 후속 일반화 시 의미 부풀음 가속. plan 단계에서 후속 일반화 정책 같이 결정 또는 명시적 미루기.

---

## Candidate C — 도메인 검증

### Silent loss 차단

| 경로 | 차단되는가 | 사유 |
|---|---|---|
| Silent over-restore (INCR 이중 호출) | **break** | dedupe key 를 `eventUuid:attempt` 로 분리하면 attempt 별 처리 권한이 따로 잡히지만, **PaymentOrder 단위 멱등성은 별도 layer 필요** (Round 1 D-C5). 채택안인 PaymentOrder.compensated_at 컬럼은 도메인 schema 변경 — 본 candidate 가 명시한 것보다 작업량 큼. 미적용 시 over-restore 발산 |
| Silent under-restore (보상 누락) | partial | 5초 backoff × 5회 self-retry 가 회복 — pg-service 패턴과 동일. 한도 소진 시 DLQ |
| 부분 보상 일관성 | **break** | 결제 단위 self-retry — 항목 단위 격리 포기. compensated_at 컬럼 추가 안 하면 반복 INCR |

### Race window 발굴

- **RC-1** (dedupe key 의미 변경의 happy path 침투): `eventUuid:attempt` 로 변경하면 events.confirmed 모든 dedupe 가 영향. happy path (보상 실패 없는 정상 케이스) 도 attempt=1 로 lease 획득. 같은 결제에 대한 두 번째 메시지 (예: pg-service self-loop 후 events.confirmed 두 번 발행) 가 attempt=1 두 번이면 dedupe 가 차단 — OK. 하지만 attempt=1 / attempt=2 두 메시지가 같은 결제로 도착하면 둘 다 처리됨 (다른 dedupe key) → **happy path 멱등성이 깨진다**. APPROVED 수신을 두 번 처리하면 markPaymentAsDone 두 번 호출 (terminal → terminal self-transition no-op) + stock_outbox INSERT 두 번. UNIQUE 가 막지만 의미적 race.
- **RC-2** (Kafka topic retention 7일 한계): self-retry 가 events.confirmed 자체에 발행되므로 retention 7일 안에서만 회복 가능. 8일째 self-retry 메시지가 broker 에서 사라지면 회복 불가 — silent under-restore. dedupe TTL P8D 와 어긋남 (PITFALLS #9).
- **RC-3** (self-retry 메시지 + 신선한 메시지 토픽 큐 섞임): 한 결제의 보상 실패 self-retry 가 같은 토픽에 들어가면 다른 결제의 happy path latency 영향 (Round 1 인정). 단 도메인 안전성 직접 영향은 없음.

### 돈 새는 경로 검증

- **돈 새는 경로 1건** — RC-1 의 happy path 멱등성 깨짐. APPROVED 수신을 두 번 처리하면 stock_outbox INSERT 두 번 → 같은 결제에 stock-committed 메시지 두 번 발행 → product RDB 가 두 번 차감 (product-service 측 dedupe 가 막아주긴 하지만 본 candidate 가 의존성을 부과).
- 부분 보상 일관성 break + compensated_at 컬럼 미적용 시 over-restore 발산.

### State machine 영향

- PaymentEvent 상태 전이 변경 없음.
- PaymentOrder 에 `compensated_at` 컬럼 추가 — payment_order 테이블 V<n> 마이그레이션. 결제 도메인 schema 가 보상 회복 의미를 알게 되는 design coupling.
- `paymentOrderList` 가 보상 회복 가드를 갖는다 — payment 도메인의 본질 의미 (구매 항목) 와 회복 layer 의미가 결합.

### 다른 보상 경로 일반화 가능성

- `OutboxAsyncConfirmService.compensateStock` — events.confirmed 가 아닌 confirm 직후 동기 보상이라 self-loop 모델이 안 맞음. 다른 토픽 self-loop 가 필요. **일반화 가능성 break**.
- `PaymentTransactionCoordinator.compensateStockCacheGuarded` — D12 가드 안의 동기 보상이라 self-loop 모델 안 맞음. **일반화 가능성 break**.

### 발견 사항 (findings)

- **DC-1 [BLOCKER]**: dedupe key 의미 변경 (RC-1) 이 happy path 멱등성을 깬다. attempt=1 / attempt=2 두 메시지가 같은 결제로 도착하면 둘 다 처리됨. 본 candidate 가 명시 안 함.
- **DC-2 [MAJOR]**: Kafka retention 7일 vs dedupe TTL P8D 어긋남 (RC-2) — PITFALLS #9 와 직결. 8일째 self-retry 메시지가 broker 에서 사라지면 회복 불가.
- **DC-3 [MAJOR]**: PaymentOrder 단위 compensated_at 컬럼 추가 필요 (Round 1 D-C5) — payment 도메인 schema 가 보상 회복 의미를 알게 되는 coupling. Round 1 산출물 작업량 평가가 이 컬럼 추가 비용을 underestimate.
- **DC-4 [MAJOR]**: 다른 보상 경로 일반화 break — events.confirmed self-loop 가 confirm 직후 동기 보상 / D12 가드에 안 맞음. 본 토픽이 채택해도 후속 두 토픽이 다른 모델을 채택해야 함 — 3 mental model 분기 가능.

---

## Candidate D — 도메인 검증

### Silent loss 차단

| 경로 | 차단되는가 | 사유 |
|---|---|---|
| Silent over-restore (INCR 이중 호출) | **break** | append-only audit 이라 retry count 가 자연 표현이지만, 회복 워커가 같은 (orderId, productId) 의 STOCK_COMPENSATE_FAILED 행을 두 번 픽업하는 race 차단 부재. PaymentEvent 측 가드 컬럼 추가 필요 — Round 1 D-D4 인정 |
| Silent under-restore (보상 누락) | fit | NOT EXISTS 서브쿼리로 미회복 행 픽업 — Reconciler 또는 별도 워커 |
| 부분 보상 일관성 | fit | dedup_key 컬럼이 productId 면 항목 단위 격리 자연 표현 |

### Race window 발굴

- **RD-1** (AOP status_change 결합 분리): `DomainEventLoggingAspect.processResultAndPublishEvent` 가 `result instanceof PaymentEvent` 체크 후 분기 (DomainEventLoggingAspect.java 80-107행). 본 candidate 가 status 전이 없는 도메인 메서드도 history 발행하도록 변경하면 AOP 의 `result instanceof PaymentEvent` 분기에서 **status_change action 인데 beforeStatus == afterStatus** 라는 신규 case 가 등장 — 기존 publishStatusChange 구현이 이 경우 어떻게 동작하는지 별도 검증 필요. 잘못 처리하면 status 전이 없는 history 행이 status_change 의미로 발행돼 audit trail 의 신뢰가 깨진다 (PITFALLS #1).
- **RD-2** (NOT EXISTS 서브쿼리 + 큰 history 테이블): payment_history 는 append-only 라 시간이 갈수록 거대해진다. NOT EXISTS 서브쿼리가 대용량 table scan → 폴링 latency 영향. plan 단계에서 인덱스 검증 + cutoff 윈도우 (예: 최근 1일) 제한 필요.
- **RD-3** (`@PublishDomainEvent` AOP 의 args 분기): AOP 가 args 에서 첫 PaymentEvent 만 가져온다 (`findPaymentEvent`, DomainEventLoggingAspect.java 56-61행). 본 candidate 의 `markStockCompensateFailed(reasonCode, productId, quantity)` 메서드는 PaymentEvent 가 첫 인자가 아닐 수도 있고 productId/quantity 인자를 audit 에 어떻게 넣을지 별도 설계 필요. dedup_key (productId) 를 history 행에 적재하려면 reflection 추가 분기 필요.
- **RD-4** (payment_history 컬럼 추가 운영 부하): 현 V1 schema 의 payment_history 컬럼은 `previous_status`/`current_status`/`reason` 셋이 핵심 (V1__payment_schema.sql 84-100행). 본 candidate 가 추가하려는 `action`/`dedup_key` 컬럼은 V<n> 마이그레이션 필요 + 기존 status_change 행에 action='STATUS_CHANGE' 채우기 backfill 필요.

### 돈 새는 경로 검증

- **돈 새는 경로 1건** — RD-1 + 강한 차단 부재 (Round 1 D-D4 인정) 결합 시. 운영자가 같은 (orderId, productId) 의 회복 행을 두 번 픽업해 INCR 두 번 — silent over-restore.
- payment_history 의미 변질 위험 — audit trail SoT 의 신뢰성. status_change 가 아닌 보상 audit 까지 history 가 떠안으면 운영자가 status_change 만 필터링할 때 reasoning 부담 증가.

### State machine 영향

- PaymentEvent 상태 전이 추가 없음 (status 전이 없는 도메인 메서드만 추가).
- 기존 `isTerminal()` 가드 위반 없음.
- AOP 의 status_change 결합 분리는 PITFALLS #1 (AOP 우회 → audit trail 누락) 의 정확한 회복 layer 위반 위험을 부과 — 새 분기가 잘못 들어가면 status_change 의미가 오염.

### 다른 보상 경로 일반화 가능성

- `OutboxAsyncConfirmService.compensateStock` — payment_history 의 `action='STOCK_COMPENSATE_FAILED'` 패턴 재사용 가능. **일반화 가능성 fit**.
- `PaymentTransactionCoordinator.compensateStockCacheGuarded` — 동일. **일반화 가능성 fit**.
- 단 일반화 시 history 테이블이 4종 audit 의미 (status_change + STOCK_COMPENSATE_FAILED_FROM_*3) 가 되어 의미 부풀음 가속.

### 발견 사항 (findings)

- **DD-1 [MAJOR]**: AOP 분리 (RD-1) 가 PITFALLS #1 의 audit trail 누락 위험을 직접 건드린다. 본 candidate 가 명시한 `statusChange = false` 분기 추가는 회귀 영향 큼.
- **DD-2 [MAJOR]**: 강한 차단 부재 (Round 1 D-D4 인정). PaymentEvent 측 version 컬럼 또는 `compensation_token` 컬럼 추가 — Candidate A 와 동일한 부담.
- **DD-3 [MAJOR]**: AOP args 분기 (RD-3) — `markStockCompensateFailed` 시그니처에서 productId/quantity 를 audit 에 어떻게 적재할지 명시 안 됨. reflection 추가 분기 필요.
- **DD-4 [MAJOR]**: payment_history 컬럼 추가 (RD-4) — V<n> 마이그레이션 + 기존 row backfill. Round 1 작업량 평가 underestimate.
- **DD-5 [MINOR]**: NOT EXISTS 서브쿼리 운영 부하 (RD-2) — 인덱스 검증 + cutoff 윈도우 정책 필요.

---

## Candidate E — 도메인 검증

### Silent loss 차단

| 경로 | 차단되는가 | 사유 |
|---|---|---|
| Silent over-restore (INCR 이중 호출) | partial | markWithLease P5M 동안 두 번째 진입 차단. 단 5분 후 가드 0 — pg-service 가 같은 메시지를 다시 발행해야만 회복 가능 |
| Silent under-restore (보상 누락) | **break** | pg-service 가 events.confirmed 를 다시 발행 안 하는 case (Round 0 Q1 — "거의 없음") 시 회복 트리거 0. silent under-restore 영구 |
| 부분 보상 일관성 | **break** | 결제 단위 ack 실패 → 같은 메시지 전체 재처리. 이미 성공한 항목이 또 INCR 가능 (markWithLease 5분 안에서는 차단되지만 그 후엔 0) |

### Race window 발굴

- **RE-1** (호출 순서 변경의 도메인 의미 — Round 1 D-E1 인정): `compensateStockCache` 가 `markPaymentAsFail` **이전** 으로 이동하면 결제 실패 결정이 보상 성공 여부에 종속. 사용자에게 결제 실패 응답이 늦어지거나 결제 status 가 IN_PROGRESS 에 오래 머무름 — Candidate A 의 RA-1 과 같은 본질적 결.
- **RE-2** (handleApproved 와 비대칭): APPROVED 경로는 stock 확정 outbox INSERT 가 happy path 인데 FAILED/QUARANTINED 만 보상 실패 → 메시지 ack 실패로 가면 두 분기의 회복 모델이 다름 — Round 1 인정. 도메인 의미 비대칭.
- **RE-3** (lease P5M 만료 후 재진입 의존): pg-service 가 재발행하지 않으면 회복 0. **회복 트리거가 외부 (pg-service) 에 의존** — 본 candidate 의 결정적 약점. Round 0 Q1 에서 "거의 없음" 으로 확인.

### 돈 새는 경로 검증

- **돈 새는 경로 2건** — silent under-restore 영구 (RE-3) + 호출 순서 변경 부작용 (RE-1).
- pg-service 가 재발행하지 않는 운영 case 에서 재고가 영구 잠김 → 다른 사용자 결제 reject → 운영자가 발견할 때까지 재고 발산.

### State machine 영향

- PaymentEvent 상태 전이 변경 없음 (호출 순서만 변경).
- `markPaymentAsFail` 호출이 보상 성공 후로 이동 — `handleFailed` 가 동기 보상 로직을 갖는 의미 변경 (현재는 markPaymentAsFail 직후 보상이 try/catch 흡수). lease 만료 시점 + Kafka 재배달 의존 모델로는 도메인 의미가 약함.

### 다른 보상 경로 일반화 가능성

- `OutboxAsyncConfirmService.compensateStock` — try/catch 제거 + 예외 전파 후 caller 가 처리. confirm flow 가 동기 차단되면 사용자 결제 실패 latency 증가. **일반화 가능성 break**.
- `PaymentTransactionCoordinator.compensateStockCacheGuarded` — D12 가드 안의 보상이 동기 차단되면 결제 실패 결정 자체가 늦어짐. **일반화 가능성 break**.

### 발견 사항 (findings)

- **DE-1 [BLOCKER]**: 회복 트리거 외부 의존 (RE-3) — silent under-restore 영구 가능성. 본 토픽이 해결하려는 본질 문제를 다시 만든다.
- **DE-2 [MAJOR]**: 호출 순서 변경 (RE-1) — Candidate A 의 RA-1 과 같은 도메인 의미 변경. 결제 종결 시점이 보상 성공에 종속.
- **DE-3 [MAJOR]**: handleApproved 와 비대칭 (RE-2) — 도메인 의미 분기.
- **DE-4 [MAJOR]**: 다른 두 보상 경로 일반화 불가 — confirm 직후 동기 보상에 try/catch 제거하면 사용자 결제 실패.

---

## Candidate F — 도메인 검증

### Silent loss 차단

| 경로 | 차단되는가 | 사유 |
|---|---|---|
| Silent over-restore (INCR 이중 호출) | fit | Lua 토큰 + UNIQUE + claim_at CAS — Candidate 0 와 동일 |
| Silent under-restore (보상 누락) | fit | catch 블록 outbox INSERT + INSERT 실패 알람 — Candidate 0 와 동일 |
| 부분 보상 일관성 | fit | 행 단위 (orderId, productId) UNIQUE — Candidate 0 와 동일 |

### Race window 발굴

- **RF-1** (Candidate A 의 호출 순서 모순 + Candidate 0 의 Lua 토큰 결합): 본 candidate 가 PaymentEvent 의 `STOCK_COMPENSATION_PENDING` 신규 상태 도입 + Lua 토큰을 같이 둠. Candidate A 의 RA-1 (호출 순서 모순) 그대로 — `markPaymentAsFail` 이 먼저 호출된 후 PaymentEvent 가 이미 FAILED (terminal) 라 markStockCompensationPending 으로 역전이 불가. 본 candidate 가 작동하려면 호출 순서 변경 필요 — Round 1 산출물 명시 안 됨.
- **RF-2** (port 분리의 호출자 결정 부담): `StockCachePort` 와 `StockCompensationCachePort` 가 분리되면 호출자가 어느 port 를 써야 하는지 결정 부담. `compensateStockCache` 는 정상 increment 와 token 기반 increment 둘 다 호출 가능 — 운영 가이드 필요 (Round 1 인정).

### 돈 새는 경로 검증

- **돈 새는 경로 0건 (조건부)** — Candidate A 의 호출 순서 모순 (RF-1) 만 해결되면 도메인 안전성 fit. 단 RF-1 미해결 시 `STOCK_COMPENSATION_PENDING` 전이 자체가 IllegalStateException throw (PaymentEvent.quarantine 가드 패턴) — 운영 알람만 떨고 보상은 outbox 측 Lua 토큰이 회복.

### State machine 영향

- 신규 enum `STOCK_COMPENSATION_PENDING` 도입 — Candidate A 와 동일.
- `isCompensatableByFailureHandler` switch 가 exhaustive 라 추가 분기 필요 — Candidate A 의 DA-4 와 동일.
- 단 본 candidate 는 PaymentEvent 상태 + outbox 모두 갖고 있어 둘 사이 일관성 결정 필요 — `STOCK_COMPENSATION_PENDING` PaymentEvent 와 `processedAt IS NULL` outbox row 의 양쪽 정합 가드 — 한쪽만 변경되는 race 윈도우 발굴 필요.

### 다른 보상 경로 일반화 가능성

- `OutboxAsyncConfirmService.compensateStock` — Candidate 0 와 같은 outbox 재사용 가능 + PaymentEvent 신규 상태도 같이 적용해야 일관 — 작업량 큼. **일반화 가능성 partial**.
- `PaymentTransactionCoordinator.compensateStockCacheGuarded` — D12 가드 안 보상이 PaymentEvent 신규 상태로 진입하면 D12 회복 가드와 결합. **일반화 가능성 partial**.

### 발견 사항 (findings)

- **DF-1 [BLOCKER]**: Candidate A 의 호출 순서 모순 (RF-1) 그대로 상속 — Round 1 산출물이 RA-1 인지하지 못한 채 본 candidate 가 같은 PaymentEvent 상태를 받아들임. plan 단계에서 호출 순서 변경 결정 필요.
- **DF-2 [MAJOR]**: PaymentEvent 상태 + outbox row 의 양쪽 정합 race — `STOCK_COMPENSATION_PENDING` PaymentEvent 와 `processedAt IS NULL` outbox row 가 한쪽만 update 되는 윈도우 (트랜잭션 경계) 명시 필요.
- **DF-3 [MAJOR]**: port 분리의 호출자 결정 부담 (RF-2) — 운영 가이드 필요. Round 1 인정.
- **DF-4 [MINOR]**: 작업량 가장 큼 — Candidate A + Candidate 0 의 변경 합계.

---

## Round 3 Deep Dive 우선순위 추천 (도메인 안전성 관점)

### Architect (Round 1) 추천: A → B → D
### Domain Expert 독립 추천: B → F → A

근거:
- **1순위 (B)**: 도메인 안전성에서 가장 fit 에 근접하면서 (Round 1 산출물이 인정 안 한) Architect 의 RA-1 같은 호출 순서 모순 부담이 없다. `payment_outbox` 의미 일반화 위험은 **DB-1 (PaymentOutbox 도메인 가드 분기)** 만 결정하면 흡수 가능. 다른 두 보상 경로 일반화 가능성도 fit. 단 RB-1 (Lua 미사용 + Redis SETNX 조합 false-positive) 가 운영 noise 로 받아들일만한지가 핵심 deep dive 질문.
- **2순위 (F)**: Lua 정직 도입을 받아들여 over-restore 강한 차단 fit. 단 PaymentEvent 신규 상태 도입이 Candidate A 의 호출 순서 모순 (RF-1) 을 그대로 상속한다 — 이 부분이 deep dive 의 핵심. 호출 순서 변경 결정 + port 분리 가이드만 결정되면 안전성 최강.
- **3순위 (A)**: 도메인 정합성은 가장 좋아 보이지만 호출 순서 모순 (DA-1 BLOCKER) + version 컬럼 추가 (DA-3) + 항목 단위 격리 포기 (DA-2) 의 세 가지 도메인 부채. Round 1 작업량 평가가 underestimate. 단 결정안이 받아들이면 운영자 mental model 1트랙으로 가장 깔끔.
- **후순위 (C)**: dedupe key 의미 변경이 happy path 멱등성을 깨는 BLOCKER (DC-1) + Kafka retention 8일 어긋남 (DC-2) — 도메인 안전성 break.
- **후순위 (D)**: AOP status_change 결합 분리가 PITFALLS #1 직격 + 강한 차단 부재. payment_history 의 audit trail 신뢰성 위험.
- **후순위 (E)**: silent under-restore 영구 가능성 (DE-1 BLOCKER) — 본 토픽이 해결하려는 문제를 다시 만든다.
- **baseline 0**: 사용자가 거부한 baseline. 도메인 안전성 자체는 fit 이지만 비즈니스 흐름 정합 결정에 기각됨.

---

## Round 3 에 던질 질문 (도메인 관점)

### Candidate B (1순위)
- **B-Q1**: PaymentOutbox 의 `incrementRetryCount` / `toFailed` / `toDone` / `toInFlight` 도메인 메서드가 IN_FLIGHT 단일 상태 가드로 잠겨 있다 (PaymentOutbox.java 36-65행). STOCK_COMPENSATE 타입이 같은 lifecycle 을 따라도 의미적으로 OK 인가, 아니면 `MessageType` 별 분리된 도메인 메서드가 필요한가?
- **B-Q2**: RB-1 의 false-positive FAILED — Redis SETNX 후 INCR 직전 워커 크래시 시 토큰만 살고 INCR 미발생 → 다음 시도에서 ALREADY_PROCESSED. 운영자가 admin reset 해도 토큰 P8D 동안 INCR 안 들어감. 운영 절차로 받아들일만한가? 토큰 수명 단축 (P5M 같은) 으로 회복 가능한가?
- **B-Q3**: outbox UNIQUE `(order_id, message_type, dedup_key)` 변경 시 기존 CONFIRM_COMMAND row 의 message_type/dedup_key 기본값은? 운영 배포 시 lock 시간 측정?
- **B-Q4**: 다른 두 보상 경로 (OutboxAsyncConfirmService / PaymentTransactionCoordinator) 일반화 시 STOCK_COMPENSATE_FROM_CONFIRM / FROM_RESULT / FROM_FAILURE 같은 dedup_key 분기 등장. plan 단계에서 후속 일반화 정책 같이 결정인가, 명시적 미루기인가?

### Candidate F (2순위)
- **F-Q1**: Candidate A 의 호출 순서 모순 (RA-1) 을 본 candidate 가 그대로 상속. `handleFailed` 의 markPaymentAsFail → compensateStockCache 순서를 뒤집어야 PaymentEvent 가 STOCK_COMPENSATION_PENDING 진입 가능. 도메인 의미 변경 (결제 실패가 보상 성공에 종속) 받아들일만한가?
- **F-Q2**: PaymentEvent 상태 + outbox row 양쪽 정합 race (DF-2) — `STOCK_COMPENSATION_PENDING` 전이와 outbox INSERT 가 같은 TX 인가, 별도 TX 인가? 한쪽만 update 되는 윈도우의 회복 시나리오?
- **F-Q3**: port 분리 (StockCachePort vs StockCompensationCachePort) 의 호출자 결정 가이드. `compensateStockCache` 는 어느 port 를 써야 하는가?
- **F-Q4**: 작업량 (Candidate A + 0 합계) 이 도메인 안전성 강화 가치를 정당화하는가? Candidate B 대비 안전성 차이가 어느 정도인가?

### Candidate A (3순위)
- **A-Q1**: 호출 순서 모순 (DA-1) — `handleFailed` 의 markPaymentAsFail → compensateStockCache 순서 뒤집기. Round 1 산출물 명시 안 됨. plan 단계에서 어떻게 흡수?
- **A-Q2**: PaymentEvent 가 `STOCK_COMPENSATION_PENDING` 상태에서 user 환불 요청이 들어오면 어떻게 처리되는가? 환불도 막혀야 하는가, 아니면 별 트랙이라 무관?
- **A-Q3**: payment_event.version 컬럼 추가 (DA-3) — V<n> 마이그레이션 + 기존 row backfill. `isCompensatableByFailureHandler` switch exhaustive 처리 (DA-4) 까지 합치면 작업량은? Round 1 평가가 underestimate 라고 본 검증의 결론.
- **A-Q4**: 항목 단위 격리 포기 (DA-2) — productA INCR 성공 + productB 실패 후 재시도 시 productA 또 INCR. PaymentOrder.compensated_at 컬럼 도입은 결제 도메인 schema 가 보상 회복 의미를 알게 되는 coupling. 받아들일만한가?
- **A-Q5**: 회복 latency 60~300초 — 다른 사용자 결제 reject 의 silent under-restore 새 형태 (DA-5). 운영 SLA 정의?

---

## decision

Round 3 진행 가부: **pass**

7개 candidate 모두 같은 깊이로 검증 완료. 도메인 안전성 관점 우선순위 (B → F → A) 가 Architect 의 추천 (A → B → D) 과 다르므로 Round 3 deep dive 가 필요. 특히 RA-1 (호출 순서 모순) 이 Round 1 산출물에서 명시되지 않은 BLOCKER 로 발굴됨 — Candidate A / E / F 의 채택 결정에 직접 영향.

## JSON

```json
{
  "stage": "discuss-extended",
  "topic": "STOCK-COMPENSATION-RECOVERY-ALTERNATIVES",
  "round": 2,
  "persona": "domain-expert",
  "decision": "pass",
  "findings": [
    { "id": "D0-1", "severity": "minor", "candidate": "0", "summary": "Lua 토큰 P8D + admin reset 시 토큰 수동 삭제 절차 명시 필요" },
    { "id": "D0-2", "severity": "major", "candidate": "0", "summary": "Lua INCR 성공 후 RDB update 직전 크래시 시 false-positive FAILED — 메트릭 임계 정책 미정의" },
    { "id": "DA-1", "severity": "critical", "candidate": "A", "summary": "호출 순서 모순 — handleFailed 의 markPaymentAsFail → compensateStockCache 순서를 뒤집어야 markStockCompensationPending 진입 가능 (PaymentConfirmResultUseCase.java 266-268)" },
    { "id": "DA-2", "severity": "critical", "candidate": "A", "summary": "부분 보상 일관성 break — PaymentOrder.compensated_at 컬럼 추가 필요 (Round 1 D-A2 인정)" },
    { "id": "DA-3", "severity": "major", "candidate": "A", "summary": "payment_event.version 컬럼 부재 (V1 schema 재확인) — silent over-restore 강한 차단 부재" },
    { "id": "DA-4", "severity": "major", "candidate": "A", "summary": "isCompensatableByFailureHandler switch exhaustive — STOCK_COMPENSATION_PENDING 추가 시 컴파일 에러 + 회복 가드 의미 결합" },
    { "id": "DA-5", "severity": "major", "candidate": "A", "summary": "회복 latency 60~300초 — 다른 사용자 결제 reject 의 silent under-restore 새 형태" },
    { "id": "DB-1", "severity": "major", "candidate": "B", "summary": "PaymentOutbox 도메인 가드 분기 결정 누락 — STOCK_COMPENSATE 타입의 toFailed 의미가 CONFIRM_COMMAND 와 다름" },
    { "id": "DB-2", "severity": "major", "candidate": "B", "summary": "Redis SETNX 토큰 false-positive — admin reset 시 토큰 수동 삭제 절차 명시 필요" },
    { "id": "DB-3", "severity": "minor", "candidate": "B", "summary": "UNIQUE 제약 변경 마이그레이션 운영 lock — 기존 row 의 message_type/dedup_key 기본값 결정" },
    { "id": "DB-4", "severity": "major", "candidate": "B", "summary": "다른 보상 경로 일반화 시 STOCK_COMPENSATE_FROM_CONFIRM/FROM_RESULT/FROM_FAILURE 같은 dedup_key 분기 등장 — 후속 일반화 정책 같이 결정 또는 명시적 미루기" },
    { "id": "DC-1", "severity": "critical", "candidate": "C", "summary": "dedupe key eventUuid:attempt 변경이 happy path 멱등성 깸 — APPROVED 메시지 attempt=1 / attempt=2 두 번 처리 가능" },
    { "id": "DC-2", "severity": "major", "candidate": "C", "summary": "Kafka retention 7일 vs dedupe TTL P8D 어긋남 (PITFALLS #9) — 8일째 self-retry 메시지 손실 시 회복 불가" },
    { "id": "DC-3", "severity": "major", "candidate": "C", "summary": "PaymentOrder.compensated_at 컬럼 추가 필요 — payment 도메인 schema 가 보상 회복 의미를 알게 되는 coupling" },
    { "id": "DC-4", "severity": "major", "candidate": "C", "summary": "다른 보상 경로 일반화 break — confirm 직후 동기 보상 / D12 가드에 self-loop 모델 안 맞음" },
    { "id": "DD-1", "severity": "major", "candidate": "D", "summary": "AOP status_change 결합 분리가 PITFALLS #1 (audit trail 누락) 직격 — DomainEventLoggingAspect.java 80-107행 분기 수정 회귀 영향" },
    { "id": "DD-2", "severity": "major", "candidate": "D", "summary": "강한 차단 부재 — PaymentEvent.version 컬럼 추가 필요 (Candidate A 와 동일 부담)" },
    { "id": "DD-3", "severity": "major", "candidate": "D", "summary": "AOP findPaymentEvent args 분기 — productId/quantity 인자를 audit 에 적재할 reflection 추가 분기 필요" },
    { "id": "DD-4", "severity": "major", "candidate": "D", "summary": "payment_history.action/dedup_key 컬럼 추가 + 기존 row backfill — 작업량 underestimate" },
    { "id": "DD-5", "severity": "minor", "candidate": "D", "summary": "NOT EXISTS 서브쿼리 운영 부하 — 인덱스 + cutoff 윈도우 정책 필요" },
    { "id": "DE-1", "severity": "critical", "candidate": "E", "summary": "회복 트리거 외부 (pg-service 재발행) 의존 — silent under-restore 영구 가능. 본 토픽이 해결하려는 문제를 다시 만든다" },
    { "id": "DE-2", "severity": "major", "candidate": "E", "summary": "호출 순서 변경 — 결제 종결 시점이 보상 성공에 종속 (Candidate A 의 RA-1 과 동일)" },
    { "id": "DE-3", "severity": "major", "candidate": "E", "summary": "handleApproved 와 비대칭 — APPROVED 경로는 stock 확정 outbox INSERT 가 happy path / FAILED 만 ack 실패 모델" },
    { "id": "DE-4", "severity": "major", "candidate": "E", "summary": "다른 두 보상 경로 일반화 불가 — 동기 보상 try/catch 제거 시 사용자 결제 실패 latency 증가" },
    { "id": "DF-1", "severity": "critical", "candidate": "F", "summary": "Candidate A 의 호출 순서 모순 (RA-1) 그대로 상속 — Round 1 산출물 명시 안 됨" },
    { "id": "DF-2", "severity": "major", "candidate": "F", "summary": "PaymentEvent 상태 + outbox row 양쪽 정합 race — 한쪽만 update 윈도우 명시 필요" },
    { "id": "DF-3", "severity": "major", "candidate": "F", "summary": "port 분리 호출자 결정 부담 — compensateStockCache 가 어느 port 를 써야 하는지 가이드 필요" },
    { "id": "DF-4", "severity": "minor", "candidate": "F", "summary": "작업량 가장 큼 — Candidate A + Candidate 0 합계" }
  ],
  "domain_recommendation": {
    "1st": "B",
    "2nd": "F",
    "3rd": "A",
    "rejected": ["C", "D", "E"]
  }
}
```
