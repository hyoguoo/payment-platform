# Round 3 Architect Refinement Log — STOCK-COMPENSATION-RECOVERY 대안

> 입력: Round 1 산출물 (`docs/topics/STOCK-COMPENSATION-RECOVERY-ALTERNATIVES.md` 555 라인) + Round 2 Critic 산출 (29 finding) + Round 2 Domain 산출 (29 finding)
> 작업: Candidate A / B / D 를 deep refine — 코드 사실 재확인 + Round 2 finding 흡수 + 마이그레이션·테스트 전략 구체화 + Round 4 검증 포인트 도출
> 산출 위치: 본 파일 (결정 로그) + `docs/topics/STOCK-COMPENSATION-RECOVERY-ALTERNATIVES.md` (in-place refine + 종합 섹션)

---

## Round 2 finding 흡수 매트릭스

severity / 흡수 상태 / refine 방식을 한 표로 정리한다.

### Candidate A

| Finding ID | Severity | 출처 | 흡수 상태 | refine 위치 |
|---|---|---|---|---|
| DA-1 / RA-1 | critical (Domain) | handleFailed 호출 순서 (markPaymentAsFail → compensateStockCache) 와 STOCK_COMPENSATION_PENDING 도입의 terminal→non-terminal 역전이 모순 | **흡수 — 호출 순서 + 신규 상태 모델 자체를 재설계** | A §결정 D-A1' / §컴포넌트 표 / §Mermaid |
| DA-2 | critical (Domain) | 부분 보상 일관성 break — productA 성공 + productB 실패 후 재시도 시 productA 또 INCR | **부분 흡수 — PaymentOrder 단위 가드 도입을 결정안에 명시 + 도입 시 Flyway 1 추가 명시** | A §결정 D-A2' / §마이그레이션 |
| FA-1 / DA-3 | major (Critic + Domain) | silent over-restore 강한 차단 부재 + payment_event.version 컬럼 부재 | **흡수 — version 컬럼 추가를 마이그레이션 표에 명시 + 가드 위치 명시** | A §결정 D-A3' / §마이그레이션 / §한계 |
| FA-2 | major (Critic) | "Flyway 0" 주장이 강한 차단 + 항목 격리 충족 시 Flyway 1+ 로 변경 | **흡수 — Round 1 의 "Flyway 0" 주장을 정정. version + compensated_at 둘 다 도입 시 Flyway 2** | A §마이그레이션 |
| FA-3 | minor (Critic) | retryCount 컬럼 의미 겸용 (PG vendor retry vs 보상 회복) | **흡수 — 별 컬럼 (compensation_retry_count) 분리 결정** | A §결정 D-A6' (신규) |
| FA-4 | major (Critic) | markStockCompensationPending 이 RDB UPDATE 의존 → RDB 다운 시 신규 silent loss | **흡수 — RDB 다운 처리를 명시. 운영 알람 메트릭 추가** | A §결정 D-A7' (신규) |
| DA-4 | major (Domain) | isCompensatableByFailureHandler switch exhaustive — STOCK_COMPENSATION_PENDING 추가 시 컴파일 에러 | **흡수 — switch 분기 결정 명시 (false 반환)** | A §결정 D-A4' |
| DA-5 | major (Domain) | 회복 latency 60~300초 — 다른 사용자 결제 reject 의 silent under-restore 새 형태 | **흡수 — 별도 reconciler 키 (`reconciler.stock-compensation-fixed-delay-ms: 5000`) 도입 + trade-off 운영 명시** | A §결정 D-A5' / §한계 |

### Candidate B

| Finding ID | Severity | 출처 | 흡수 상태 | refine 위치 |
|---|---|---|---|---|
| FB-1 | major (Critic) | UNIQUE 제약 변경 운영 lock — payment_outbox 가 happy path 직접 사용 | **흡수 — online DDL 가이드 + 배포 절차 명시 + 기존 row 백필 정책 결정** | B §마이그레이션 |
| FB-2 | major (Critic) | Strategy refactoring 회귀 surface — happy path 영향 0 가드 위협 | **흡수 — 회귀 테스트 surface 정량화 + happy path 영향 검증 절** | B §Happy path 영향 0 가드 검증 |
| FB-3 | major (Critic) | SETNX 멱등성 키 race window — Lua 미도입 시 SETNX 와 INCR atomic 묶음 부재 | **부분 흡수 — race window 명시 + 보상 후 SETNX 순서 결정 (INCR 직전 SET → 실패 시 토큰 정리). 사용자 신호 (b) Lua 회피와의 trade-off 정직 인정** | B §결정 D-B3' / §한계 |
| FB-4 | minor (Critic) | payload 컬럼 추가 vs 동적 조회 결정 미정 | **흡수 — 동적 조회 결정 (payload 컬럼 미도입)** | B §결정 D-B5' |
| FB-5 | minor (Critic) | OutboxImmediateEventHandler / OutboxWorker 두 entry point 의 STOCK_COMPENSATE 적용 | **흡수 — 두 entry point dispatch 명시. AFTER_COMMIT 발행은 STOCK_COMPENSATE 도 적용** | B §컴포넌트 표 |
| DB-1 | major (Domain) | PaymentOutbox 도메인 가드 분기 — STOCK_COMPENSATE 의 toFailed 의미가 CONFIRM_COMMAND 와 다름 | **흡수 — 도메인 메서드는 IN_FLIGHT 단일 가드 그대로 + 상위 layer (운영자 admin) 에서 type 별 처리 priority 결정** | B §결정 D-B6' (신규) |
| DB-2 | major (Domain) | Redis SETNX 토큰 false-positive — admin reset 시 토큰 수동 삭제 절차 | **흡수 — admin reset SoP 에 토큰 삭제 단계 추가** | B §운영 가이드 |
| DB-3 | minor (Domain) | UNIQUE 제약 변경 마이그레이션 운영 lock | **흡수 — FB-1 과 통합** | B §마이그레이션 |
| DB-4 | major (Domain) | 다른 보상 경로 일반화 시 dedup_key 분기 | **흡수 — 본 토픽 단독 결정 범위는 events.confirmed catch 만. 후속 일반화는 별도 토픽으로 명시적 미루기** | B §결정 D-B7' (신규) / §한계 |
| FB-2 + claimToInFlight 시그니처 | hidden (Round 3 발굴) | `claimToInFlight(orderId, inFlightAt)` 가 orderId 단일 인자. message_type 별 row 다중일 때 시그니처 변경 필요 | **흡수 — Round 3 신규 발굴. claimToInFlight(orderId, messageType, dedupKey, inFlightAt) 또는 `claimToInFlight(outboxId, ...)` 로 변경. 회귀 surface 추가** | B §컴포넌트 표 / §보강된 결정 |

### Candidate D

| Finding ID | Severity | 출처 | 흡수 상태 | refine 위치 |
|---|---|---|---|---|
| FD-1 | major (Critic) | AOP action 분기 5 layer 변경 (어노테이션 + Aspect + Publisher + Service + schema) | **흡수 — 5 layer 변경 명시 + Aspect 의 status_change 결합 분리 안 명시** | D §결정 D-D2' |
| FD-2 | major (Critic) | payment_history 의미 변질 — ad-hoc event store 화 위험 | **흡수 — action 화이트리스트 운영 가이드 결정 (도메인 가이드 문서로 분리)** | D §결정 D-D2'' (신규) |
| FD-3 | major (Critic) | 강한 차단 부재 — append-only audit 특성상 처리 토큰 못 둠 | **부분 흡수 — PaymentEvent 측 version 컬럼 또는 별도 보호 컬럼 도입 (Candidate A 와 같은 부담). 도입 시 Candidate A 와 사실상 합쳐짐을 정직 인정** | D §결정 D-D4' / §한계 |
| FD-4 | minor (Critic) | payment_history.current_status NOT NULL 제약과 STOCK_COMPENSATE_* action 행의 의미 충돌 | **흡수 — current_status NOT NULL 유지 + 현재 PaymentEvent.status 값을 그대로 채움 (audit 의미 유지)** | D §결정 D-D5' (신규) |
| FD-5 | minor (Critic) | retry count = 행 카운트 운영 가시성 부담 | **흡수 — retry count 산출 쿼리 인덱스 명시 + 운영 가이드** | D §마이그레이션 |
| DD-1 | major (Domain) | AOP processResultAndPublishEvent 의 status_change 결합 분리 PITFALLS #1 직격 | **흡수 — Aspect 변경의 회귀 전체 영향 검증 필수 — Round 4 검증 포인트** | D §결정 D-D2' / Round 4 권고 |
| DD-2 | major (Domain) | 강한 차단 부재 — Candidate A 와 동일 부담 | **흡수 — FD-3 와 통합** | D §결정 D-D4' |
| DD-3 | major (Domain) | AOP findPaymentEvent args 분기 — productId/quantity 인자 audit 적재 | **흡수 — markStockCompensateFailed 시그니처를 (PaymentEvent, productId, quantity, reasonCode) 로 명시. AOP findPaymentEvent 분기 그대로 사용** | D §결정 D-D6' (신규) |
| DD-4 | major (Domain) | payment_history.action / dedup_key 컬럼 추가 + 기존 row backfill | **흡수 — 마이그레이션 표에 backfill 정책 명시** | D §마이그레이션 |
| DD-5 | minor (Domain) | NOT EXISTS 서브쿼리 운영 부하 | **흡수 — 인덱스 + cutoff 윈도우 (예: 최근 24h) 정책** | D §마이그레이션 |

---

## 주요 refine 결정

### Candidate A

핵심 변경:
1. **호출 순서 모델 재설계 (DA-1 흡수)** — 두 가지 선택지를 명시:
   - 옵션 A1: `compensateStockCache` 를 `markPaymentAsFail` **이전** 으로 이동 (Candidate E 의 D-E1 와 동일). 결제 종결 시점이 보상 성공에 종속.
   - 옵션 A2: 호출 순서 유지 + `markPaymentAsFail` 이 항상 먼저 실행. 단 보상 실패 시 `markStockCompensationPending` 대신 **별도 컬럼 `compensation_status` (PENDING / IN_PROGRESS / DONE / FAILED)** 를 PaymentEvent 에 추가. status 는 terminal (FAILED / QUARANTINED) 그대로, compensation_status 가 비-terminal 회복 진행 추적 — terminal+compensation_status 분리 모델.
   - **권고: 옵션 A2** — 도메인 의미 보존 (결제 실패 결정이 보상 결과와 독립) + isTerminal 가드 위반 0. Reconciler 가 status (terminal) + compensation_status (PENDING) 조합으로 폴백 회복 대상 픽업.
2. **PaymentEvent.version 컬럼 도입 (DA-3 / FA-1 흡수)** — over-restore 강한 차단을 위해 V<n>__add_payment_event_version_and_compensation_columns.sql 도입. 옵션 A2 채택 시 같은 마이그레이션에서 compensation_status / compensation_retry_count / compensated_at 도 함께 추가.
3. **항목 단위 격리 결정 (DA-2 / FA-2 흡수)** — PaymentOrder.compensated_at 컬럼은 **본 토픽 범위 외로 명시** (decision under sketch). 본 토픽이 받아들이면 결제 도메인 schema 진입 — non-goal 위반. 받아들이지 않으면 결제 단위 재순회 시 over-restore 발산. **운영 영향 정량화는 Round 4 검증 포인트**.
4. **Reconciler 분리 키 (DA-5 흡수)** — `reconciler.stock-compensation-fixed-delay-ms: 5000` 별도 키 + 별도 메서드. 단일 reconciler 인스턴스 유지 (별도 인스턴스 신설 안 함).

### Candidate B

핵심 변경:
1. **claimToInFlight 시그니처 변경 (Round 3 신규 발굴)** — 현재 `claimToInFlight(orderId, inFlightAt)` 가 orderId 단일 인자. message_type 별 다중 행 도입 시 `claimToInFlightByRow(outboxId, inFlightAt)` 또는 `claimToInFlight(orderId, messageType, dedupKey, inFlightAt)` 로 변경 필요. **OutboxRelayService.relay(orderId)` 도 `relay(outboxId)` 로 변경**.
2. **payload 컬럼 미도입 (FB-4 흡수)** — STOCK_COMPENSATE 행도 PaymentEvent 에서 productId/quantity 동적 조회. dedup_key 컬럼만 추가.
3. **SETNX 토큰 위치 결정 (FB-3 흡수)** — `compensation:{orderId}:{productId}` 토큰을 `stockCachePort.increment` **호출 직전** SETNX. SETNX 성공 시 INCR 호출, 실패 시 ALREADY_PROCESSED no-op + outbox.toDone. **SETNX 와 INCR 의 atomic 묶음 부재** 정직 인정 — Lua 미도입의 trade-off (사용자 신호 (b) 회피) vs race window. **race window: SETNX 직후 + INCR 직전 워커 크래시 → 토큰만 살고 INCR 미발생**. P5M 짧은 토큰 TTL 로 회복 가능 — Round 4 도메인 검증 포인트.
4. **outbox 의미 일반화 범위 명시 (DB-4 흡수)** — 본 토픽 단독 결정은 events.confirmed catch 1개 경로만. OutboxAsyncConfirmService / PaymentTransactionCoordinator 두 silent loss 경로는 **명시적 미루기** — 별도 토픽 STOCK-COMPENSATION-RECOVERY-PHASE2 에서 처리.

### Candidate D

핵심 변경:
1. **AOP 변경 안 명시 (FD-1 / DD-1 흡수)** — `@PublishDomainEvent(action="changed")` 만 status_change 발행, 신규 action 은 별도 처리. 두 가지 선택지:
   - 옵션 D1: 기존 Aspect 의 switch 에 `case "stock_compensate_failed"` / `case "stock_compensate_recovered"` 추가. PaymentEventPublisher 에 `publishStockCompensateFailed` / `publishStockCompensateRecovered` 메서드 추가. 기존 status_change 분기는 그대로 유지.
   - 옵션 D2: 별 Aspect 신설 (`StockCompensationLoggingAspect`) — 기존 Aspect 안 건드림. PITFALLS #1 회피.
   - **권고: 옵션 D2** — DD-1 의 PITFALLS #1 직격 회피. 기존 status_change AOP 회귀 영향 0.
2. **payment_history.current_status NOT NULL 처리 (FD-4 흡수)** — STOCK_COMPENSATE_FAILED 행에는 PaymentEvent 의 현재 status 값 (예: FAILED) 을 그대로 채움. previous_status 는 NULL 허용 (기존 column nullable). action 컬럼 신규 + dedup_key 컬럼 신규.
3. **PaymentEvent 측 강한 차단 (FD-3 / DD-2 흡수)** — `payment_event.compensation_state_version` 컬럼 추가 (Candidate A 의 version 컬럼과 같은 의미). 본 candidate 채택 시 Candidate A 의 schema 변경과 **사실상 같은 비용** 정직 인정.
4. **markStockCompensateFailed / markStockCompensateRecovered 시그니처 (DD-3 흡수)** — `(PaymentEvent paymentEvent, Long productId, Integer quantity, String reasonCode)` — 첫 인자가 PaymentEvent 라 AOP findPaymentEvent 그대로 동작.

---

## 코드 사실 재확인 결과

Round 2 의 finding 코드 인용을 Read·Grep 으로 모두 재검증했다. 주요 결과:

### 정확한 인용 (재확인 OK)

| 인용 | 재확인 결과 |
|---|---|
| `PaymentConfirmResultUseCase.handleFailed` 266-268 행 호출 순서 | 정확. line 258-272 실측 — `if (isTerminal) return; markPaymentAsFail; compensateStockCache;` |
| `PaymentEvent.fail` 119 행 isTerminal early return | 정확. line 118-131 — `if (isTerminalStatus()) return;` 후 status whitelist 가드 |
| `PaymentEvent.quarantine` 162-167 행 isTerminal throw | 정확. line 162-171 — `if (isTerminal) throw IllegalStateException` |
| `PaymentEventStatus.isTerminal()` 21-26 행 | 정확. DONE/FAILED/CANCELED/PARTIAL_CANCELED/EXPIRED → true |
| `PaymentEventStatus.isCompensatableByFailureHandler()` 34-39 행 switch exhaustive | 정확. switch 가 모든 enum 케이스 커버. STOCK_COMPENSATION_PENDING 추가 시 컴파일 에러 |
| `PaymentOutbox` 36-65 행 IN_FLIGHT 단일 가드 | 정확. toInFlight / toDone / toFailed / incrementRetryCount 모두 status != IN_FLIGHT 가드 |
| `payment_event` 스키마 — version 컬럼 부재 | 정확 (V1 schema line 7-29). `id / buyer_id / seller_id / order_name / order_id / payment_key / gateway_type / status / executed_at / approved_at / retry_count / status_reason / last_status_changed_at / created_at / updated_at / deleted_at` 16 컬럼. version 없음 |
| `payment_outbox` 스키마 payload 부재 | 정확 (V1 schema line 57-77). `id / order_id / status / retry_count / next_retry_at / in_flight_at / available_at / created_at / updated_at / deleted_at`. payload 없음 |
| `payment_history` 스키마 (V1 schema line 84-100) | 정확. `id / payment_event_id / order_id / previous_status / current_status / reason / change_status_at / created_at / updated_at / deleted_at`. action / dedup_key 부재. current_status NOT NULL 확인 |
| `DomainEventLoggingAspect.processResultAndPublishEvent` action switch 91-106 | 정확. created / retry / changed 3종 switch + default warn |
| `OutboxRelayService.relay(orderId)` 49-82 | 정확. claimToInFlight → outbox load → paymentEvent 동적 조회 → buildMessage → publish → toDone |
| `PaymentReconciler.scan` 44-48 + 54-70 | 정확. `findInProgressOlderThan(cutoff) → resetToReady + saveOrUpdate` |
| `PgVendorCallService.insertRetryOutbox` self-loop 패턴 | Round 1 인용 그대로 — 본 Round 3 에서 추가 검증 안 함 (Candidate C 후순위) |

### Round 3 신규 발굴 / 정정

1. **`payment_event.idx_payment_event_status_last_changed (status, last_status_changed_at)`** 인덱스가 V1 schema line 26 에 이미 존재. Round 2 critic FA hidden cost "status 단일 인덱스 미존재" 는 **부정확** — `(status, last_status_changed_at)` 복합 인덱스가 status prefix 단독 사용 가능. Candidate A 의 `findInStockCompensationPendingOlderThan` 추가는 같은 인덱스로 커버됨. **정정.**

2. **`PaymentOutboxRepository.claimToInFlight(orderId, inFlightAt)` 시그니처 (Round 3 신규 발굴)** — Round 2 critic FB-2 가 Strategy refactoring 회귀 surface 만 짚었으나, 더 근본적으로 **claimToInFlight 가 orderId 단일 인자**라 (orderId, message_type, dedup_key) UNIQUE 도입 시 시그니처 변경 필수. 이는 도메인 메서드 명세 변경 + Repository 인터페이스 변경 + JPA `@Modifying UPDATE` 쿼리 변경 + 모든 호출자 (OutboxRelayService.relay / OutboxImmediateEventHandler.handle / OutboxWorker.process) 변경. **회귀 surface 가 Round 1/2 평가보다 한 단계 더 넓다**.

3. **`OutboxImmediateEventHandler.handle` 가 `outboxRelayService.relay(event.getOrderId())` 호출 (line 38)** — Candidate B 채택 시 PaymentConfirmEvent 의 orderId → outboxId / (orderId, messageType, dedupKey) 식별자 변경. PaymentConfirmEvent 도 변경 필요. 회귀 surface 추가.

4. **AOP findPaymentEvent (line 56-61)** — `Arrays.stream(args).filter(PaymentEvent.class::isInstance).findFirst()` — 첫 PaymentEvent 인자만 추출. Candidate D 의 markStockCompensateFailed(PaymentEvent, productId, quantity, reasonCode) 시그니처는 OK. 단 productId / quantity 가 audit 행에 적재되려면 **PaymentEventPublisher 의 publishStockCompensateFailed 가 args 를 별도로 받아야 함** — 현재 AOP 가 args 를 publisher 에 전달하지 않음 (publisher 에 PaymentEvent 만 전달, line 94-102). 즉 본 candidate 가 작동하려면 (a) AOP 가 args 를 publisher 에 전달하도록 시그니처 변경 또는 (b) PaymentEvent 도메인 메서드 안에서 productId/quantity 를 PaymentEvent 의 transient 필드 또는 ThreadLocal 로 우회 — 둘 다 회귀 영향 큼. **DD-3 가 인지한 issue 의 정확한 surface**.

---

## Round 4 검증 권고 (각 candidate 별)

### Candidate A — Round 4 Critic + Domain 검증 포인트

**Critic (운영 / 작업량 / hidden cost) 관점**:
- 옵션 A2 (compensation_status 별 컬럼) 채택 시 PaymentEvent 의 도메인 모델이 status / compensation_status 두 개 상태 머신을 갖게 되는데 운영자 / admin 도구의 가시성이 어떻게 변하는가?
- `payment_event` 에 컬럼 4개 (version, compensation_status, compensation_retry_count, compensated_at — compensated_at 은 항목 단위 격리 채택 시) 추가 시 운영 데이터 backfill 정책. Flyway V<n> 의 ALTER TABLE 시간.
- 별도 Reconciler 키 (`reconciler.stock-compensation-fixed-delay-ms`) 도입 시 단일 reconciler 인스턴스가 두 분기 (IN_PROGRESS + STOCK_COMPENSATION_PENDING) 를 같이 도는 polling 부하.

**Domain (도메인 안전성 / state machine / silent loss) 관점**:
- 옵션 A2 의 compensation_status 가 PaymentEvent.status 와 별 머신이라 두 머신 간 race window 검증. status=FAILED + compensation_status=DONE 까지의 전이 path 정합성.
- PaymentEvent.version 컬럼 추가가 Reconciler + 보상 회복 + 정상 처리 모든 update 경로에 가드 추가 — 회귀 영향 정량화.
- 항목 단위 격리 미도입 시 결제 단위 재순회의 over-restore — 도메인이 받아들일만한가?

### Candidate B — Round 4 Critic + Domain 검증 포인트

**Critic 관점**:
- claimToInFlight 시그니처 변경의 회귀 surface — JPA `@Modifying UPDATE` 쿼리 + 모든 호출자 변경. 통합 테스트 보강 부담.
- payment_outbox `(order_id)` UNIQUE → `(order_id, message_type, dedup_key)` 변경 online DDL 가능성. MySQL 8 InnoDB 기본 지원.
- Strategy 패턴 도입의 회귀 테스트 surface — 기존 `OutboxRelayServiceTest` 모두 영향. happy path 영향 0 가드 검증.

**Domain 관점**:
- SETNX 토큰 + INCR 의 race window — Lua 미도입의 정직한 인정. 도메인 안전성 비대칭 (Candidate A 의 version 가드 강도 vs SETNX 부분 차단) 받아들일만한가?
- PaymentOutbox 도메인 메서드의 IN_FLIGHT 단일 가드가 STOCK_COMPENSATE 타입에도 적용 OK — 의미 충돌 없음. 단 toFailed 의 운영 priority 가 type 별 다른 점이 운영 가이드로 흡수되는가?
- AFTER_COMMIT 즉시 발행 (OutboxImmediateEventHandler) 이 STOCK_COMPENSATE 도 적용되면 catch 블록의 outbox INSERT 직후 dispatch 시도 — 지연 발행 (`available_at = now + 5초` 등) 정책과 정합?

### Candidate D — Round 4 Critic + Domain 검증 포인트

**Critic 관점**:
- 옵션 D2 (별 Aspect 신설) 채택 시 두 Aspect (`DomainEventLoggingAspect` / `StockCompensationLoggingAspect`) 의 실행 순서 / @Around 중첩 영향. PaymentEvent 도메인 메서드가 두 Aspect 모두 통과하는 케이스 (markStockCompensateFailed 가 status 전이 없으므로 `DomainEventLoggingAspect` 는 default 분기 warn 로 끝남) 검증.
- payment_history `action` / `dedup_key` 컬럼 추가 마이그레이션 + 기존 row backfill 정책. action 기본값 (`status_change`).
- NOT EXISTS 서브쿼리의 인덱스 + cutoff 윈도우 (예: 24h) 운영 부하 측정.

**Domain 관점**:
- payment_history 의미 변질 (status_change SoT → 보상 audit + 향후 다른 도메인 이벤트로 확장) 의 audit trail 신뢰성 영향. action 화이트리스트 운영 가이드로 흡수 가능한가?
- PaymentEvent.compensation_state_version 컬럼 도입은 Candidate A 와 사실상 같은 schema 변경 — Candidate D 의 audit-driven 발상 가치가 어떻게 차별화되는가?
- markStockCompensateFailed / markStockCompensateRecovered 가 PaymentEvent.status 전이 없는 도메인 메서드인 점이 도메인 의미 (PaymentEvent 가 자신의 보상 진행 상태를 "안다") 와 정합?

---

## Round 3 결론

### 3 후보 핵심 차별점 (refined)

| Candidate | 한 줄 요약 (refined) | 핵심 차별점 |
|---|---|---|
| **A (refined)** | PaymentEvent 에 별도 compensation_state 머신 + version 가드 + Reconciler 분리 키. 호출 순서 유지, isTerminal 위반 0 | **회복 표현이 PaymentEvent 도메인 안에 응축** — 운영자 mental model 1트랙 (PaymentEvent 만 보면 됨) |
| **B (refined)** | payment_outbox 에 message_type + dedup_key 컬럼 + claimToInFlight 시그니처 변경 + Strategy 핸들러. SETNX 토큰 (Lua 아님) | **claimToInFlight CAS 자연 멱등성 활용** — 새 워커 / 새 port 0. 단 outbox 의미 작업 큐 일반화 |
| **D (refined)** | 별 Aspect 신설 + payment_history.action/dedup_key + PaymentEvent.compensation_state_version | **audit-driven 회복 (retry count = 행 수)** — PaymentEvent.version 도입 시 Candidate A 와 schema 변경 비용 동일 |

### Round 5 (최종 비교) 가 사용자에게 제시할 축

Round 4 검증 후 사용자 결정 1회로 수렴 가능. 사용자에게 다음 3축으로 선택지를 제시:

1. **운영자 mental model 단순성 vs 회복 latency**:
   - A 채택 → 운영자가 PaymentEvent 한 곳만 보면 됨 (1트랙). 회복 latency 5-25초 (별 reconciler 키 도입 시) ~ 60-300초 (기존 reconciler 분기 시).
   - B 채택 → payment_outbox 가 작업 큐 일반화. 회복 latency 5-25초.
   - D 채택 → payment_history audit 트레일이 회복 SoT. PaymentEvent 도 version 컬럼 도입 시 두 곳 가시화 (1.5 트랙).

2. **schema 변경 비용 (Flyway V<n> 개수)**:
   - A: V<n> 1개 (payment_event 컬럼 추가). 항목 단위 격리 채택 시 V<n> 2개.
   - B: V<n> 1개 (payment_outbox 컬럼 추가 + UNIQUE 변경).
   - D: V<n> 1~2개 (payment_history 컬럼 추가, PaymentEvent.compensation_state_version 도입 시 +1).

3. **사용자 신호 4가지 (a/b/c/d) 회피 정도**:
   - A: 4 모두 회피 (가장 정직).
   - B: (a) 부분 회피 — outbox 의미 일반화. (b)(c)(d) 회피.
   - D: (a)(b)(c) 회피. (d) 부분 회피 — audit 트레일이 회복 트랙으로 의미 확장.

### Round 4 deep validation 가이드

Round 4 두 페르소나 (Critic + Domain Expert) 가 refined 산출물 검증 시 다음을 집중:

- **Candidate A**: 옵션 A2 (compensation_status 별 컬럼) 의 도메인 정합성 검증 — terminal+compensation_status 분리 모델이 PaymentEvent state machine 의 단순성을 깨는가, 아니면 status 의 의미 보존을 강화하는가? Reconciler 분리 키의 운영 부하 측정.
- **Candidate B**: claimToInFlight 시그니처 변경 회귀 surface 정량화. SETNX + INCR race window 의 도메인 안전성 영향 — 5분 토큰 TTL 로 회복 가능한 운영 노이즈 수준인가, silent loss 가 새로 등장하는가?
- **Candidate D**: 옵션 D2 (별 Aspect 신설) 의 Aspect 중첩 실행 검증 — `DomainEventLoggingAspect` 와 `StockCompensationLoggingAspect` 가 같은 메서드에 중복 적용될 가능성. PaymentEvent.compensation_state_version 도입 시 Candidate A 와의 차별점 재확인.

추가로 세 candidate 모두에 공통으로 검증할 hidden cost 1개:
- **`OutboxAsyncConfirmService.compensateStock` (line 99-119) 와 `PaymentTransactionCoordinator.compensateStockCacheGuarded` (line 168-180) 의 silent loss 경로** — 본 토픽이 events.confirmed catch 1개 경로만 다루는 게 일관된 회복 모델인가, 아니면 후속 토픽에서 같은 candidate 를 적용해도 일반화 가능한가? 각 candidate 의 일반화 가능성:
  - A: PaymentEvent 가 IN_PROGRESS 진입 직전 / D12 가드 안 시점이라 compensation_status 도입이 IN_PROGRESS 자체를 막을 위험 — **일반화 partial → break**.
  - B: 같은 payment_outbox 작업 큐로 자연 일반화 — **일반화 fit**.
  - D: payment_history audit 패턴으로 자연 일반화 — **일반화 fit**.
