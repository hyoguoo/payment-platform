# STOCK-COMPENSATION-RECOVERY — 채택 결정

> stage: discuss → plan
> 결정일: 2026-05-04 (Round 6 audit 완료 직후, 사용자 위임)
> 5라운드 탐색·검증 + Round 6 시나리오 audit 의 최종 결정.

---

## 채택 결정 — Candidate D (enhanced)

**선택**: payment_history audit-driven + happy path SUCCEEDED action

### 한 줄 요약

`payment_history` 의 audit row 를 **회복 SoT** 로 두고, 보상 실패뿐 아니라 **보상 성공도 audit row 로 기록**한다. 다음 진입 시 항목별 SUCCEEDED audit 존재 여부로 INCR 이중 호출을 막는다. 별 Aspect + 별 Reconciler 로 기존 audit AOP 침투 0.

### 왜 D 인가 — 다른 후보와의 비교

| 후보 | 시나리오 1/2/3 모두 커버? | 사용자 신호 위반 | 결정 |
|---|---|---|---|
| Baseline 0 | ✓ (Lua 토큰 happy path 확장 시) | (a)(b)(c)(d) 4 모두 — 사용자 명시 거부 | **DELETE** |
| Candidate A | ✓ (PaymentOrder.compensated_at 도입 시) | (d) 가속 + 본 토픽 §0 Non-goal 위반 | **DEMOTE** |
| Candidate B | ✓ (SETNX happy path 확장 시) | happy path 영향 0 가드 정면 충돌 가속 | **DEMOTE** |
| **Candidate D** | ✓ (SUCCEEDED audit happy path 추가) | (d) 부분 — audit 의미 확장이 본질과 정합 | **PASS** |

D 만이 (i) 시나리오 3 까지 커버하면서 (ii) 사용자 신호를 본질적으로 위반하지 않고 (iii) 본 토픽 non-goal 을 침범 안 함.

### 시나리오별 보호 layer

| 시나리오 | 막는 mechanism |
|---|---|
| **1. 수신 실패** | Kafka 인프라 (broker retention 7d + consumer 재시작 시 자연 재배달). 후보 차이 없음 |
| **2. 처리 실패 + 커밋** | `compensateStockCache` catch 분기에서 `markStockCompensateFailed` wrapper → audit row INSERT (action='STOCK_COMPENSATE_FAILED'). Reconciler 5초 polling 으로 NOT EXISTS scan → INCR 재시도 → `markStockCompensateRecovered` audit |
| **3. 처리 후 커밋 직전 죽음** | happy path INCR 성공 직후 `markStockCompensateSucceeded` wrapper → audit row INSERT (action='STOCK_COMPENSATE_SUCCEEDED'). 재진입 시 `compensateStockCache` 가 `WHERE NOT EXISTS (... action='STOCK_COMPENSATE_SUCCEEDED' AND dedup_key=productId)` 가드로 항목 skip → INCR 이중 호출 차단 |

### 핵심 변경 6개 (구현 범위)

1. **Flyway 2건** — payment_history 에 `action` / `dedup_key` 컬럼 추가 + payment_event 에 `compensation_state_version` 컬럼 추가
2. **payment_history.action 화이트리스트 4종** — STATUS_CHANGE / STOCK_COMPENSATE_FAILED / STOCK_COMPENSATE_RECOVERED / STOCK_COMPENSATE_SUCCEEDED
3. **별 Aspect** — `StockCompensationLoggingAspect` + `@PublishStockCompensationEvent` 어노테이션. 기존 `DomainEventLoggingAspect` 변경 0
4. **별 Reconciler** — `PaymentHistoryCompensationReconciler` fixed-delay 5초. NOT EXISTS 서브쿼리 + cutoff 24h
5. **PaymentCommandUseCase wrapper 3 메서드** — `markStockCompensateFailed` / `markStockCompensateRecovered` / `markStockCompensateSucceeded`. application 빈 메서드라 Spring AOP 가로채기 작동 OK
6. **compensateStockCache 항목 단위 가드** — INCR 호출 전 `WHERE NOT EXISTS (SUCCEEDED audit)` 체크 → 이미 처리된 항목 skip

### 받아들이는 trade-off

- **happy path latency +N×ms** — FAILED/QUARANTINED 정상 보상 경로만. 정상 APPROVED 경로 영향 **0**
- **payment_history 행 증가** — action 4종 × 보상 발생 결제. partitioning / retention 운영 정책은 PLAN 단계
- **action 화이트리스트 운영 가이드 책임** — DB CHECK 또는 application enum 강제, PLAN 단계 결정
- **SUCCEEDED audit INSERT 직전 crash race window** — INCR 후 audit 직전 crash 시 다음 시도에 INCR 두 번. 이는 Reconciler 가 NOT EXISTS 로 회복 가능한 잔여 race (Kafka 인프라 race window 와 같은 등급)

### PHASE2 (별 토픽) 로 미루는 것

- `OutboxAsyncConfirmService.compensateStock` (line 99-119) 의 동일 silent loss 패턴 회복
- `PaymentTransactionCoordinator.compensateStockCacheGuarded` (line 168-180) 의 동일 패턴 회복
- 다중 Reconciler 인스턴스 운용 (본 토픽은 단일 인스턴스 가정)
- admin 도구 (FAILED audit 조회 / 수동 reset / 강제 종결)
- 메시지 broker 자체 retention 초과 손실 회복 (PITFALLS 별 layer)

PHASE2 토픽이 같은 audit-driven 모델 재사용 가능 — mental model 분기 0.

---

## 5라운드 + Round 6 요약 (이력)

> 5라운드 탐색·검증 (Round 1 Architect → Round 2 Critic+Domain → Round 3 Architect refine → Round 4 Critic+Domain deep → Round 5 Architect synthesis) + Round 6 (Architect 시나리오 audit) 의 최종 비교 산출물.

---

## 배경 및 문제 (요약)

`payment-service` 의 `events.confirmed` consumer 는 PG 결과가 FAILED / QUARANTINED 일 때 Redis 선차감 캐시를 INCR 로 보상해야 하는데, `compensateStockCache` (`PaymentConfirmResultUseCase.java:304-317`) 의 catch 가 INCR 실패를 LogFmt.error 만 하고 swallow 한다. 결과적으로 dedupe lease 가 P8D 로 연장돼 같은 메시지 재배달도 막힌다 — silent loss. 같은 swallow 패턴이 두 곳 더 있다 (`OutboxAsyncConfirmService.compensateStock:99-119`, `PaymentTransactionCoordinator.compensateStockCacheGuarded:168-180`). 본 토픽은 events.confirmed catch 1개 경로의 자동 회복 layer 도입을 결정한다 — 다른 두 경로는 PHASE2 토픽에서 결정.

원래 결정안 (Candidate 0) 은 신규 `stock_compensation_outbox` 테이블 + Lua 처리 토큰 + 5초 워커 모델이었으나, 사용자가 **4가지 이질 신호** 를 들어 거부:

| # | 신호 | 의미 |
|---|---|---|
| (a) | 신규 테이블이 작업 큐 의미 — outbox 패밀리 정의 위반 | `payment_outbox` / `stock_outbox` / `pg_outbox` 는 Kafka 발행 큐. 신규 보상 outbox 는 Redis 작업 큐 |
| (b) | Lua 두 번째 사용처 — 도구 정책 흐림 | 현재 Lua 1건 (선차감 원자성). 보상이 두 번째 사용처가 되면 도구 사용 정책 모호 |
| (c) | 신규 port API 시그니처가 기존과 결 다름 | `StockCachePort.(productId, quantity)` 통일 시그니처 vs `incrementWithToken(...)` 토큰 노출 |
| (d) | PaymentEvent 라이프사이클 외부 회복 | Reconciler / dedupe / PaymentEvent state machine 3트랙 분기 |

5라운드 탐색은 **이 4 신호를 회피하는 대안** 을 찾는 작업이었다.

---

## 5라운드 요약

| 라운드 | 산출 | 결과 |
|---|---|---|
| 1 (Architect) | 6 후보 sketch (A/B/C/D/E/F + baseline 0) | A: PaymentEvent state machine 안 회복 / B: payment_outbox message_type 확장 / C: events.confirmed self-loop / D: payment_history audit-driven / E: try/catch 제거 + lease 회복 / F: Lua + 별 port 정직 변형 |
| 2 (Critic + Domain) | finding 발굴 | Critic 권고 A→D→B / Domain 권고 B→F→A |
| 3 (Architect refine) | A/B/D refine + Round 2 finding 흡수 | A 신규 enum 폐기 (옵션 A2 — compensation_status 별 컬럼) / B claimToInFlight 시그니처 변경 명시 / D 옵션 D2 (별 Aspect 신설) |
| 4 (Critic + Domain deep) | critical 4건 발굴 | A4-1 (NULL-edge silent under-restore) / B4-1 (claimToInFlight 6 layer surface) / D4-1 (옵션 D2 AOP 작동 불가) / DA4-1 (handle TX rollback + Redis INCR 잔존 over-restore) |
| 5 (Architect synthesis) | 최종 흡수 + 비교표 | 본 문서 — A: 흡수안 (b) 디폴트 마킹 / B: 흡수안 (c) PaymentConfirmEvent 보존 / D: 흡수안 (a) application 빈 wrapper |

---

## 최종 후보

### Baseline 0 — RDB 보상 outbox + Lua 처리 토큰 + 5초 워커

원래 결정안. 사용자가 4 이질 신호 (a/b/c/d) 모두 위반으로 거부.

**핵심 결정**:
- 신규 `stock_compensation_outbox` 테이블 + `@Scheduled` 워커 5초 폴링
- Redis Lua `SETNX compensation:token:{outboxId} EX P8D` + INCRBY atomic 묶음 — 워커 크래시 재진입 시 INCR 이중 호출 강한 차단
- 신규 port `StockCompensationCachePort` (또는 기존 port 확장) + outbox 멱등성 토큰 노출
- `compensateStockCache` catch 가 outbox INSERT 만, 정상 경로 INSERT 0

**한계**:
- 사용자 신호 4개 모두 위반 — outbox 의미 작업 큐 변질 (a) + Lua 두 번째 사용처 (b) + port 시그니처 일반성 깨짐 (c) + PaymentEvent 옆 트랙 회복 (d)
- 운영 mental model 3트랙 (Reconciler / dedupe / 신규 보상 워커)
- 작업량 가장 큼 (11 task)

**비교 baseline 으로 본 문서에 보존** — 사용자가 명시 비교 원할 때 입력.

---

### Candidate A — terminal+compensation_status 분리 모델 (Round 4 흡수 후)

**핵심 결정** (Round 3 옵션 A2 + Round 5 흡수안 (b) 디폴트 마킹):
- PaymentEvent 에 별 컬럼 3종 추가 — `compensation_status` (PENDING/IN_PROGRESS/DONE/FAILED) / `compensation_retry_count` / `compensation_state_version`
- PaymentEvent.status 는 terminal (FAILED / QUARANTINED) 그대로 — isTerminal 가드 위반 0
- handleFailed 진입 즉시 PaymentCommandUseCase.markStockCompensationPending 호출 (markPaymentAsFail 직후) — 같은 `@Transactional(timeout=5)` TX 묶음. compensateStockCache 호출은 호출 순서 그대로
- 모든 INCR 성공 시 catch 외부에서 markStockCompensationDone 호출 → compensation_status DONE 전이
- 별 Reconciler fixed-delay 5000ms 키 + 별 메서드 (`scanStockCompensationPending`) — IN_PROGRESS scan 과 별 분기. 회복 latency 5~25초
- compensation_state_version (JPA `@Version`) 으로 두 워커 동시 픽업 차단 — over-restore 강한 차단

**흡수된 critical findings**:
- A4-1 (NULL-edge silent under-restore): handleFailed 진입 즉시 markStockCompensationPending 디폴트 마킹으로 NULL terminal 잔존 회피
- DA4-1 (handle TX rollback + Redis INCR 잔존 over-restore) **부분 흡수** — TX rollback + dedupe remove + Kafka 재배달 시 같은 productId 가 mid-INCR 위치라면 INCR 두 번 가능. **PaymentOrder.compensated_at 컬럼 도입은 PHASE2 deferred** 라 본 candidate 단독 채택 시 잔여 위험

**잔여 위험**:
- DA4-1 over-restore 잔여 (process crash 빈도 × mid-INCR 위치 확률 — 낮음)
- DA4-5 (PaymentOrder 단위 격리 부재 시 결제 단위 재순회 over-restore) — 알려진 한계
- DA4-6 (RDB 다운 + extendLease 잠금 시 dedupe 영구 잠금) — STOCK_COMPENSATION_RDB_FAIL Counter + 운영 알람으로 흡수
- 다른 두 silent loss 경로 일반화 break — PHASE2 가 별 모델 도입 필요

---

### Candidate B — payment_outbox message_type 확장 (Round 4 흡수 후)

**핵심 결정** (Round 3 + Round 5 흡수안 (c) PaymentConfirmEvent 보존):
- payment_outbox 에 message_type / dedup_key 컬럼 추가 + UNIQUE 제약 `(order_id, message_type, dedup_key)` 변경 (online DDL `ALGORITHM=INPLACE, LOCK=NONE`)
- PaymentOutboxMessageType enum (CONFIRM_COMMAND / STOCK_COMPENSATE) 신규
- claimToInFlight 시그니처 `(orderId, inFlightAt)` → `(orderId, messageType, dedupKey, inFlightAt)` 4 인자 변경 — JPA `@Modifying UPDATE` 쿼리 4 인자 변경
- PaymentConfirmEvent payload 보존 — orderId 그대로 (도메인 layer 침투 회피)
- OutboxRelayService.relay 내부 messageType 분기 — Strategy 패턴 (`OutboxRelayHandler` 인터페이스 + `ConfirmCommandHandler` / `StockCompensationHandler`)
- Redis SETNX `compensation:{orderId}:{productId}` 토큰 (Lua 아님) + INCR — race window 정직 인정 (SETNX 후 INCR 직전 crash 시 토큰만 살고 INCR 미발생, 토큰 TTL 만료 후 회복)
- 회복 latency 5~25초 (기존 OutboxWorker 5초 폴링 + retry policy 그대로)

**흡수된 critical findings**:
- B4-1 (claimToInFlight 시그니처 6 layer surface): 흡수안 (c) 채택으로 PaymentConfirmEvent 변경 회피 → 회귀 surface 6 layer → 5 layer 로 감축. 단 OutboxRelayService 내부 분기 + Strategy 패턴 도입 그대로
- B4-3 (Strategy dispatch overhead) — happy path latency 측정 plan 단계 필수
- B4-4 (online DDL atomic 보장) — 단일 ALTER TABLE 안에 DROP + ADD UNIQUE 묶기 검증 plan 단계

**잔여 위험**:
- happy path 영향 0 가드 위협 — relay 내부 분기 + Strategy dispatch + claimToInFlight 4 인자 UPDATE 쿼리. **본 토픽 핵심 가드 와 정면 충돌**
- DB4-1 (SETNX-INCR race window) — 토큰 TTL 만료 후 회복. silent under 형태 (admin reset 으로 회복 가능)
- payment_outbox 의미 일반화 — outbox 가 작업 큐로 의미 확장 (사용자 신호 a 부분 위반)
- 다른 두 silent loss 경로 일반화 fit — PHASE2 토픽이 같은 모델 재사용 가능

---

### Candidate D — payment_history audit-driven (Round 4 흡수 후)

**핵심 결정** (Round 3 + Round 5 흡수안 (a) application 빈 wrapper):
- payment_history 에 action / dedup_key 컬럼 추가 (Flyway online DDL + backfill `action='STATUS_CHANGE'`)
- PaymentEvent 에 compensation_state_version 컬럼 추가 (Candidate A 와 schema 변경 비용 동일 정직 인정)
- PaymentHistoryEventType enum 에 STOCK_COMPENSATE_FAILED / RECOVERED 2종 추가
- **옵션 D2 폐기** (D4-1 흡수): PaymentEvent 도메인 메서드 + 별 Aspect 가로채기 모델 폐기
- 대신 PaymentCommandUseCase (application 빈) 에 wrapper 메서드 2종 추가 — `markStockCompensateFailed(PaymentEvent, productId, quantity, reasonCode)` / `markStockCompensateRecovered(...)`. application 빈 메서드라 Spring AOP proxy-based 가로채기 작동 OK
- 별 Aspect (`StockCompensationLoggingAspect`) + 별 어노테이션 (`@PublishStockCompensationEvent`) — 기존 `DomainEventLoggingAspect` 변경 0 (PITFALLS #1 회피)
- 별 Reconciler (`PaymentHistoryCompensationReconciler`) fixed-delay 5000ms — NOT EXISTS 서브쿼리 + cutoff 24h. 인덱스 (action, created_at) + (order_id, action) 추가
- 회복 SoT = payment_history audit row. retry count = `(orderId, productId)` 별 행 카운트

**흡수된 critical findings**:
- D4-1 (옵션 D2 AOP 작동 불가): 흡수안 (a) 채택으로 application 빈 wrapper 모델 도입 — Spring AOP 가로채기 작동 OK. PaymentEvent 도메인 메서드는 status 전이 0 + simple setter 만
- D4-2 (audit table size 폭증) — partitioning / retention plan 단계 결정
- D4-3 (audit row count 산출 비용) — 인덱스 + cutoff plan 단계 검증
- D4-4 (action 화이트리스트 enforcement) — DB CHECK 또는 application enum 강제 plan 단계 결정

**잔여 위험**:
- 차별점 약화 — 흡수안 (a) 채택 후 PaymentCommandUseCase wrapper 모델은 Candidate A 와 application 빈 layer 변경 동일. 차별점은 회복 SoT (audit row vs compensation_status 컬럼) 만
- DD4-2 (운영자 임의 audit row 추가/삭제 시 retry count misinterpretation) — action 화이트리스트 + audit "기록" / "회복 트리거" 의미 분기 운영 정책 plan 단계 결정
- 작업량 합계 D > A (Flyway 2 + 별 Aspect + 별 어노테이션 + 별 Reconciler + wrapper 2 메서드)
- 다른 두 silent loss 경로 일반화 fit — PHASE2 토픽이 같은 모델 재사용 가능

---

## 채택 결정 표 — 핵심 비교

| 축 | Baseline 0 | Candidate A | Candidate B | Candidate D |
|---|---|---|---|---|
| 비즈니스 흐름 정합 (사용자 신호 a/b/c/d 회피) | break — 4/4 위반 | fit — 4/4 회피 | partial — (a) 부분 위반 (outbox 작업 큐 일반화) | partial — (d) 부분 위반 (payment_history 의미 확장) |
| 신규 인프라 의존 | break — Lua + 새 테이블 + 새 워커 + 새 port | fit — payment_event 컬럼 3 + 별 reconciler 메서드 + wrapper 3 메서드 | partial — payment_outbox 컬럼 + UNIQUE 제약 변경 + Strategy 패턴 + claimToInFlight 시그니처 | partial — payment_history 컬럼 2 + payment_event 컬럼 1 + 별 Aspect + 별 어노테이션 + 별 Reconciler + wrapper 2 메서드 |
| Silent over-restore 차단 | fit — Lua 토큰 강한 차단 | partial — compensation_state_version (RDB) 강한 차단. 단 PaymentOrder 단위 격리 부재로 결제 단위 재순회 over-restore 가능 | partial — claimToInFlight CAS + IN_FLIGHT timeout + SETNX (Lua 아님) 3 layer. SETNX-INCR atomic 묶음 부재 (under 형태 race) | partial — compensation_state_version (Candidate A 와 동일 한계) |
| Silent under-restore 차단 | fit — 워커 polling + Lua 토큰 retry | fit — Reconciler scan (compensation_status=PENDING) + 별 fixed-delay 5초 | fit — outbox PENDING 영구 보존 + retry policy | fit — Reconciler NOT EXISTS scan + cutoff 24h |
| Happy path 영향 0 정직성 | fit — catch 분기에서만 outbox INSERT | partial fit — JPA `@Version` 모든 update 경로 영향 (8개 도메인 메서드). FAILED 경로 +1~2ms RDB UPDATE | **위협** — relay 내부 분기 + Strategy dispatch + claimToInFlight 4 인자 UPDATE. 본 토픽 핵심 가드와 정면 충돌 | fit — 기존 `DomainEventLoggingAspect` 변경 0. 별 Aspect 는 catch 분기 wrapper 호출만 가로챔 |
| 작업량 (line / Flyway / test) | 큼 — 11 task | 중간 — Flyway 1 + 도메인 3 + wrapper 3 + Reconciler 메서드 1 + 단위 6 + 통합 4 | 큼 — Flyway 1 (online DDL) + claimToInFlight 시그니처 변경 (5 layer) + Strategy 패턴 + 단위 6 + 통합 5+ | 큼 — Flyway 2 + 별 Aspect + 별 어노테이션 + 별 Reconciler + wrapper 2 + 단위 6 + 통합 4 |
| 다른 보상 경로 일반화 (PHASE2) | break — events.confirmed 결합 | partial → break — PaymentEvent 라이프사이클 결합 | fit — payment_outbox 작업 큐로 자연 일반화 | fit — payment_history audit 패턴으로 자연 일반화 |
| 운영자 mental model | break — 3트랙 (Reconciler / dedupe / 신규 워커) | 1.5트랙 — PaymentEvent 1 곳, status + compensation_status 동시 봐야 종결 의미 | 1트랙 — payment_outbox 한 테이블만 봄. type-aware admin 도구 보강 필요 | 1.5트랙 — PaymentEvent + payment_history 별 트레일 |
| 잔여 critical 위험 | 사용자 거부 | DA4-1 over-restore 잔여 (PHASE2 미루기 부담) | B4-1 6 layer surface 비용 (5 layer 로 감축됐으나 본 가드 위협 그대로) | D4-1 흡수안 채택 후 차별점 약화 (Candidate A 와 application 빈 layer 동등) |

---

## Architect 최종 추천

### 1순위: Candidate A (terminal+compensation_status 분리 모델)

근거:
- **사용자 신호 4개 모두 정직 회피** — Round 1 부터 본 토픽의 일관 가드. 다른 두 후보는 partial 위반.
- **happy path 영향 0 가드 무결성 가장 안전** — Candidate B 의 6 layer (흡수 후 5 layer) 회귀 surface + Strategy dispatch overhead 와 비교 불가. Candidate D 와 동등한 fit 이지만 회귀 surface 더 작음.
- **회귀 surface 가장 작음** — JPA `@Version` 1곳 + 도메인 메서드 8개 + PaymentCommandUseCase wrapper 3개. Candidate B 의 5 layer + 5 테스트 파일 vs Candidate D 의 별 Aspect + 별 어노테이션 + Reconciler 신설 + wrapper 2개 모두 더 무거움.
- **운영자 mental model 1.5트랙 단순** — PaymentEvent 1 곳에서 두 컬럼만 봄. Candidate D (PaymentEvent + payment_history 별 트레일) 와 비교 시 가시성 우위.

채택 시 trade-off 정직 인정:
- **DA4-1 over-restore 잔여 위험** — handle TX 의 mid-INCR crash 시 같은 productId 가 INCR 두 번 가능. PaymentOrder.compensated_at 컬럼 도입은 PHASE2 deferred. 본 토픽 단독 채택은 이 잔여 위험을 알려진 한계로 받아들임.
- **다른 두 silent loss 경로 일반화 break** — PHASE2 토픽이 별 모델 (B 또는 D 패턴) 도입 필요. mental model 분기 = 본 토픽 후 정직 인정.

### 2순위: Candidate D (payment_history audit-driven)

근거:
- **다른 두 silent loss 경로 일반화 fit** — PHASE2 토픽 봉인 시 같은 audit-driven 모델 재사용. mental model 분기 0.
- **happy path 영향 0 가드 fit** — 기존 `DomainEventLoggingAspect` 변경 0. 별 Aspect 는 catch 분기 wrapper 호출만 가로챔.
- **append-only audit retry count = 행 수** 발상이 도메인적으로 신선. 운영 audit 체인에 결제 / 보상 모두 한 곳.

채택 시 trade-off:
- **차별점 약화** — Candidate A 와 schema 변경 비용 동일 (compensation_state_version 도입), application 빈 wrapper layer 변경 동일. 차별점은 회복 SoT (audit row vs compensation_status 컬럼) 만.
- **작업량 합계 D > A** — Flyway 2 + 별 Aspect + 별 어노테이션 + 별 Reconciler.
- **DD4-2 운영자 임의 audit row 개입 위험** — action 화이트리스트 + 운영 정책 plan 단계 결정 부담.

### 3순위 (추천 거부): Candidate B (payment_outbox message_type 확장)

근거 (거부):
- **happy path 영향 0 가드 정면 충돌** — relay 내부 분기 + Strategy dispatch + claimToInFlight 4 인자 UPDATE 쿼리. 흡수안 (c) 채택 후에도 5 layer 회귀 surface + Strategy 패턴 도입의 위협 본질 그대로. 본 토픽 핵심 가드 위협이 본 토픽 단독 결정 범위 초과.
- **outbox 의미 작업 큐 일반화의 도메인 결정 무게** — payment_outbox 가 향후 다른 비동기 작업 (refund command 등) 도 자연 진입. 본 토픽 단독 결정으로 outbox 패턴 의미 변경하는 부담.

단, 사용자가 (i) outbox 일반화 무게 (ii) 5 layer 회귀 surface 둘 다 받아들이면 Candidate B 가 일반화 fit (PHASE2 같은 모델 재사용) + 운영자 1트랙 mental model 강점 살아남음 — 명시적 사용자 confirm 필요.

---

## 페르소나 추천 차이

| 페르소나 | 1순위 | 사유 핵심 |
|---|---|---|
| Architect (Round 1) | A | 사용자 신호 4개 정직 회피 |
| Architect (Round 5) | **A** | Round 4 critical 흡수 후도 회귀 surface 가장 작음 + happy path 가드 무결성 + mental model 가장 단순. DA4-1 잔여 over-restore 는 알려진 한계로 인정 |
| Critic (Round 2) | A | 회귀 surface 작음 |
| Critic (Round 4) | A | A4-1 NULL-edge race 흡수안 결정 시 가장 안전. B 는 6 layer surface 가 본 가드 정면 충돌, D 는 옵션 D2 무효화 후 차별점 약화 |
| Domain (Round 2) | B | 도메인 안전성 / 일반화 |
| Domain (Round 4) | **B** | silent loss 차단 layer 두꺼움 (claimToInFlight CAS + IN_FLIGHT timeout + SETNX 3 layer) + 일반화 fit + mental model 1트랙. 돈이 새는 방향이 under (회복 가능) — Candidate A 의 over (발산) 보다 안전 |

**페르소나 분기점**:
- Architect / Critic 는 **happy path 영향 0 가드 무결성 + 회귀 surface 작음** 을 1순위로 둠 → A
- Domain 은 **silent loss 차단 layer 두께 + 일반화 fit + 돈이 새는 방향 비대칭 (under vs over)** 을 1순위로 둠 → B

사용자 결정 시 고려할 핵심 분기점:
- **본 토픽 단독 vs PHASE2 묶음**: A 채택 시 PHASE2 가 별 모델 = mental model 분기 / B 또는 D 채택 시 같은 모델 재사용
- **돈이 새는 방향 비대칭**: A (over-restore 발산 가능 — admin 도구 reset 필요) vs B (under-restore 회복 가능) vs D (audit SoT trade-off — 운영자 임의 개입 위험)
- **happy path 영향 0 vs silent loss 차단 layer 두께**: A/D 가 happy path fit + 차단 partial vs B 가 happy path 위협 + 차단 두꺼움

---

## Round 6 — 3 시나리오 audit (사용자 제기)

> 사용자가 Round 5 산출물 검토 후 "현재 후보들이 정확한 해결 방법을 명시 안 한다" 지적 + Kafka consumer 신뢰성 3 시나리오 커버 여부 점검 요청. 상세 audit 은 `docs/rounds/stock-compensation-recovery-alternatives/round-6-scenario-audit.md`.

### 사용자 제기 시나리오

1. **메시지 수신 실패** — broker outage / network partition / consumer down
2. **메시지 처리 못 했는데 커밋해버림** — silent loss (현 버그)
3. **메시지 처리했는데 커밋 직전 죽음** — INCR 성공 후 extendLease/ack 직전 crash → P5M lease 만료 → redeliver → INCR 두 번 = silent over-restore

### 현행 race window (코드 사실)

호출 순서: `markWithLease(P5M)` → `processMessage(handleFailed → markPaymentAsFail → compensateStockCache)` → `extendLease(P8D)` → TX commit → ack

- **3a (INCR 성공 후 extendLease 직전 crash)**: TX rollback → markPaymentAsFail 무효화 + Redis INCR 살아있음. 5분 후 lease 만료 → redeliver → handleFailed 재진입 → isTerminal=false (TX rollback) → INCR 두 번 발산
- **3b (extendLease 후 commit 전 crash)**: dedupe 키 P8D 살아있어 redeliver 차단. 단 markPaymentAsFail rollback 으로 IN_PROGRESS 잔존 → Reconciler 가 resetToReady → 새 confirm 사이클 → 새 eventUuid 의 events.confirmed 수신 → INCR 두 번
- **3c (commit 후 ack 전 crash)**: dedupe P5M 살아있고 RDB FAILED 영구 — redeliver 시 markWithLease false → skip. silent loss 0 ✓

핵심 사실: 현행 코드에 **항목 단위 멱등성 layer 0** — Redis INCRBY 자체는 매번 누적, `compensateStockCache` 내부 dedupe 키 0.

### 후보별 시나리오 3 커버리지

| 후보 | 시나리오 3 기본 거동 | enhancement | 사용자 신호 위반 / 본질 손실 | 판정 |
|---|---|---|---|---|
| **Baseline 0** | ❌ Lua 토큰이 워커 path 에만 — consumer happy path 의 plain INCR 미보호 | Lua 토큰을 happy path 에도 적용 | (b) Lua 사용처 가속 + (c) port API 변경 그대로 | **DELETE** (사용자 거부 본질 그대로) |
| **Candidate A** | ❌ TX rollback 후 isTerminal 가드 false → INCR 두 번 | PaymentOrder.compensated_at 컬럼 도입 (PHASE2 deferred) | 본 토픽 §0 Non-goal 위반 + 채택 조건 (DA4-1 잔여 인정) 폐기 | **DEMOTE** |
| **Candidate B** | ❌ outbox row 가 catch 분기에만 — happy path crash 시 outbox 0 | SETNX 토큰을 happy path INCR 에도 적용 | happy path 영향 0 가드 정면 충돌 가속 — Round 5 거부 사유 누적 | **DEMOTE** |
| **Candidate D** | ❌ audit row 가 catch 분기에만 — happy path 성공 시 audit 0 | happy path INCR 성공 시 `STOCK_COMPENSATE_SUCCEEDED` action audit 추가 + `WHERE NOT EXISTS` 가드로 항목 skip | (d) audit 의미 확장 가속이지만 audit-driven 본질과 정합 (의미 일관성 강화) | **PASS (조건부)** |

### 살아남은 후보

**Candidate D (enhanced)** — payment_history audit-driven + happy path SUCCEEDED action

**Enhanced sketch**:
- 기본 모델: payment_history.action 화이트리스트 **4종** (STATUS_CHANGE / STOCK_COMPENSATE_FAILED / STOCK_COMPENSATE_RECOVERED / **STOCK_COMPENSATE_SUCCEEDED 신규**)
- 별 Aspect + 별 Reconciler + PaymentEvent.compensation_state_version
- consumer happy path INCR 성공 직후 `markStockCompensateSucceeded` wrapper 호출 → audit INSERT
- `compensateStockCache` 진입 시 `WHERE NOT EXISTS (action='STOCK_COMPENSATE_SUCCEEDED', dedup_key=productId)` 가드로 항목 skip → 시나리오 3a/3b 차단

**조건부 인정 사항** (PASS 조건):
- happy path latency +N×ms (N = 항목 수) — 정상 APPROVED 경로는 영향 0, FAILED/QUARANTINED 정상 보상 경로만 영향
- action 화이트리스트 4종 운영 가이드
- SUCCEEDED audit INSERT 직전 crash race window 인정 — Reconciler 가 NOT EXISTS 로 회복 (기본 거동과 동일)
- 작업량 추가 — wrapper 1 메서드 + 단위·통합 테스트 1~2건씩

### 후보 0 분기 (사용자 D 거부 시)

D enhancement 의 happy path latency / action 4종 가속이 받아들이기 어렵다면:
1. **A 또는 D 단독 채택 + 시나리오 3 은 PHASE2 미루기** — 본 토픽이 시나리오 1/2 만 커버. silent over-restore 잔여 위험 알려진 한계로 인정
2. **Baseline 0 채택 + 사용자 신호 (b)(c) 양보** — 시나리오 3 강한 차단 + Lua / port API 변경 인정
3. **본 토픽 자체 폐기 + PHASE2 합본** — 본 토픽 단독 결정 범위 초과 인정

---

## 사용자 결정 입력

Round 6 audit 결과 — 시나리오 3 까지 커버 가능한 유일한 살아남은 후보는 **Candidate D (enhanced)**. 다음 중 1개 선택:

- [ ] **Candidate D (enhanced)** — payment_history audit + happy path SUCCEEDED action (시나리오 1/2/3 ✓ + happy path latency +N×ms 인정)
- [ ] ~~Baseline 0~~ — DELETE (사용자 4 신호 거부 본질 그대로)
- [ ] **Candidate A 단독 채택 + 시나리오 3 PHASE2 미루기** — DA4-1 over-restore 잔여를 알려진 한계로 인정
- [ ] **Candidate B 단독 채택 + 시나리오 3 PHASE2 미루기** — happy path 가드 위협 인정
- [ ] **Candidate D 기본 (enhancement 없이) + 시나리오 3 PHASE2 미루기** — happy path latency 영향 0 보존
- [ ] **본 토픽 폐기 + PHASE2 합본** — 시나리오 3 까지 커버하려면 단독 결정 범위 초과 인정
- [ ] Round 7 — 추가 검증 부분 명시

---

## 채택 후 진행

채택안이 정해지면:

1. **STATE.md 갱신** — 단계 plan
2. **PLAN 입력 자료**: 채택 안의 §컴포넌트 / §결정 / §작업 분해 힌트 + 본 문서의 §흡수된 critical findings + §잔여 위험 → `docs/STOCK-COMPENSATION-RECOVERY-PLAN.md` 의 입력
3. **workflow-plan 스킬 진입**

진입 시점에 PHASE2 토픽 (다른 두 silent loss 경로 — `OutboxAsyncConfirmService.compensateStock` / `PaymentTransactionCoordinator.compensateStockCacheGuarded`) 의 위치도 함께 결정 — 본 토픽 plan 의 non-goal 명시 필요.
