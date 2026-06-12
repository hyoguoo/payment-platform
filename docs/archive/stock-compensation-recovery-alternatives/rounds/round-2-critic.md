# Round 2 Critic — STOCK-COMPENSATION-RECOVERY 대안 검증

> 검증 대상: `docs/topics/STOCK-COMPENSATION-RECOVERY-ALTERNATIVES.md` (Round 1 Architect 산출, 555 라인)
> 검증자 페르소나: Critic
> 검증 기준: 평가 축 6개 + 사용자 거부 신호 4가지 (a/b/c/d) + hidden cost / 멱등성 / 안전성
> 코드 사실 재확인: PaymentEvent / PaymentReconciler / PaymentOutbox / StockOutbox / payment_outbox.sql / DomainEventLoggingAspect / pg-service self-loop / EventDedupeStore / StockCachePort / PaymentConfirmResultUseCase / OutboxAsyncConfirmService / PaymentTransactionCoordinator 11개 파일 직접 Read 확인.

---

## 종합 verdict

Round 1 Architect 의 7개 candidate 발굴은 폭과 깊이 측면에서 충분하며, 이질성 신호 (a/b/c/d) 매핑도 일관되어 있다. 다만 **자기 평가 점수에 일관된 낙관 편향이 있다**. 특히 (i) Candidate A 의 "Flyway 0" 주장은 정확하지만 그 trade-off 인 **PaymentEvent.fail() / quarantine() 가드 변경이 광범위 회귀**를 유발한다는 점이 Round 1 §변경 표 한 행으로 압축돼 비용이 가려졌다, (ii) Candidate B 의 "Strategy 패턴 자연 도입" 은 표현이 매끄럽지만 **payment_outbox 가 현재 payload 컬럼이 없는 schema 라 Strategy 별 데이터 모델 통합 비용이 묘사보다 크다**, (iii) Candidate D 의 `@PublishDomainEvent(action="...", statusChange=false)` 제안은 현재 AOP action enum 분기가 `created/retry/changed` 3종 고정이라 어노테이션 + Aspect + Publisher + history schema 4 layer 변경이 동시 요구된다.

7개 모두에 공통으로 가려진 hidden cost 두 가지는: (1) **`OutboxAsyncConfirmService.compensateStock` (line 99-119) 와 `PaymentTransactionCoordinator.compensateStockCacheGuarded` (line 168-180) 의 silent loss 경로** — 본 토픽의 §7 후속 표가 "별도 토픽" 으로 미뤘으나 채택 candidate 가 도메인 layer (PaymentEvent / payment_history / outbox) 에서 회복 책임을 표현하면 **이 두 경로도 같은 해법을 요구**할 가능성이 높음. Candidate A/D 처럼 PaymentEvent 라이프사이클에 회복을 묶으면 두 경로는 PaymentEvent 가 없는 시점이라 같은 해법을 못 씀 → 결국 별도 회복 layer 필요. Round 1 이 이 일관성 비용을 누락. (2) **dedupe lease (P5M / P8D) 와 회복 latency 의 정렬** — Candidate A 가 60~300초 회복 latency 를 가지면 short lease P5M 만료 후 같은 메시지 재수신 시 재처리 → 회복 워커 + 자연 재배달이 **동시 발생** 가능. 이 race 가 어느 candidate 든 분석 필요.

Round 3 deep dive 우선순위는 Architect 추천 (A → B → D) 과 일부 다르다. 본 Critic 의 독립 평가는 **A → D → B** — 사유는 §Round 3 deep dive 우선순위 추천 절. Candidate 0 / E / F 는 deep dive 가치 낮음 (deselected baseline / 외부 의존 회복 모델 약함 / 작업량 최대 + 사용자 신호 회피 안 함).

---

## Candidate 0 (baseline) — 검증

### Round 1 평가의 정직성

- 인용한 라인 / 클래스 정확성: §1 코드 사실 표의 인용은 11개 컴포넌트 모두 실제 파일과 라인 일치. 단 **Round 1 §1 표의 `EventDedupeStore.markWithLease` "Duration shortTtl(P5M)"** 표기는 실제 시그니처가 `markWithLease(String eventUuid, Duration shortTtl)` 로 P5M 은 caller (`PaymentConfirmResultUseCase`) 가 주입하는 값. 의미 영향 없음.
- 누락한 hidden cost: §3 가 §6 이 §7 이 §8 등 11단계 task 외에 (i) `StockCachePort` 시그니처 분리 시 기존 호출자 (`PaymentTransactionCoordinator.decrementStock` / `compensateStockCacheGuarded` / `OutboxAsyncConfirmService.compensateStock`) 모두 token 도입 결정 필요, (ii) Lua KEYS 가 다른 hash slot 일 때 cluster mode `{...}` hash tag 강제 — 본 토픽 원본 §사후 트레이드오프 (line 123) 에 언급은 있으나 **다중 워커 도입 시 Lua → Redis Cluster 의 cross-slot 영향**은 task 미반영.
- 과대평가 / 과소평가 부분: 회복 latency "5~25초" 는 정확하나 baseline §평가 표가 Lua 도입 후의 운영 부담 (스크립트 sha 캐싱 / RedisAdapter 주입 / Testcontainers Lua sha 일관성) 을 "신규 인프라 의존: break" 한 줄로 압축. 실제 plan 단계에 별도 task 가 등장.

### Hidden cost 발굴

- **마이그레이션 비용**: 신규 테이블 1 + 신규 인덱스 2 + 신규 Lua 스크립트 1 + 신규 application.yml 키 3 + 신규 EventType 5 + Counter 5 — single Flyway V file 이지만 운영 스크립트 적재량은 가장 큼.
- **테스트 복잡도**: `StockCompensationWorkerIntegrationTest` (Testcontainers MySQL + Redis) 가 워커 크래시 시뮬레이션 + Lua 토큰 검증을 모두 커버. 기존 Testcontainers 셋업과 격리하려면 별도 test profile 필요.
- **운영 대시보드**: `worker_reentry_total` Counter 알람 임계 정의 필요 — 단발성인지 누적인지 운영 가이드 미정.
- **회귀 리스크**: 본 baseline 은 PaymentEvent / dedupe / payment_outbox / stock_outbox 어느 것도 안 건드려 회귀 surface 는 가장 작음. 이는 **장점**.

### 멱등성 / 안전성 검증

| 시나리오 | Candidate 0 의 거동 | 안전한가 |
|---|---|---|
| 워커 크래시 후 재시작 | Lua SETNX `compensation:token:{outboxId}` 가 INCR 이중 호출 차단 | fit (강한 차단) |
| Redis 일시 다운 | 워커 polling 매 5초 재시도, attempt 5 도달 시 FAILED. INCR 자체가 실패하고 토큰 SET 도 실패 → 다음 시도에서 같은 토큰으로 재진입 가능 | fit |
| RDB 일시 다운 | appender INSERT 실패 → swallow + `insert_fail_total++` + EventType.STOCK_COMPENSATION_OUTBOX_INSERT_FAILED. silent loss 잔존 (A3 가정) | partial — 마지막 silent loss |
| 동시 N 결제 같은 product 보상 race | UNIQUE `(order_id, product_id)` + SKIP LOCKED 로 row 단위 격리. 같은 product 의 다른 order 는 별 row 라 동시 INCR 가능 → 이는 **정상 동작** (각 order 의 보상은 독립) | fit |

### Critic 점수 재평가 (6축)

| 축 | Round 1 평가 | Critic 평가 | 사유 |
|---|---|---|---|
| 비즈니스 흐름 정합 | break | break | 일치 — (a)(b)(c)(d) 4신호 모두 위반 |
| 신규 인프라 의존 | break | break | 일치 — Lua + 새 테이블 + 새 워커 + 새 port API |
| 멱등성 / 안전성 | fit | **fit (단 silent loss 분기 보강 권장)** | RDB 다운 시 insert_fail swallow 가 마지막 silent loss. Candidate 0 자체 평가가 이를 메트릭으로 흡수했지만 회복은 못함 |
| happy path 영향 | fit | fit | 일치 — catch 분기에서만 INSERT |
| 회복 latency | 5~25초 | 5~25초 | 일치 |
| 작업량 | 큼 | **가장 큼** | 11단계 task — Lua 스크립트 + Testcontainers Redis + cluster hash tag + 5 메트릭. 본 baseline 이 가장 무거운 변경 |

### 발견 사항 (findings)

- **F0-1 [MAJOR]**: `insert_fail_total` 메트릭이 기록만 하고 회복 안 함 — RDB 다운 시 본 layer 가 silent loss 그대로 보유. 본 토픽이 silent loss 봉인을 목표로 하는데 회복 layer 가 새 silent loss 를 도입하는 비대칭. (Round 1 §평가표 "fit" 은 자기 평가 낙관)
- **F0-2 [MINOR]**: `worker_reentry_total` Counter 알람 임계 미정. plan 단계에서 결정 필요.
- **F0-3 [MINOR]**: Lua KEYS 가 다른 hash slot 일 때 single-node 가정 또는 hash tag 강제 — 본 토픽 §123 line 에 언급되어 있으나 task 분해에서 별도 단계로 미분리.

---

## Candidate A — 검증

### Round 1 평가의 정직성

- 인용한 코드: `PaymentReconciler.scan()` (line 44-48) 정확, `PaymentEvent.fail / quarantine` (line 118-131 / 162-171) 정확, `PaymentEventStatus.isTerminal` (line 21-26) 정확, `payment_event.retry_count` 컬럼 schema 존재 (V1__payment_schema.sql line 18) 정확.
- 누락한 hidden cost: **PaymentEvent.fail() / quarantine() 의 from-status 가드 변경 영향 누락**. 현재 `fail()` 는 `READY/IN_PROGRESS/RETRYING` 만 from 으로 허용 (line 122-126), `STOCK_COMPENSATION_PENDING` 추가 시 가드 절 변경. 마찬가지로 `quarantine()` 는 isTerminal() 단일 가드라 비교적 단순. 단 isTerminal() 분기 자체에 STOCK_COMPENSATION_PENDING 항목 추가 필요. **변경 확산 surface 가 §변경 표 1행으로 압축됨**.
- 과대평가: "Flyway 마이그레이션 0" 정확 (status 컬럼이 VARCHAR(50) 으로 enum check 없음 — V1 schema 확인). 단 status enum 의 경우 자바 enum 추가만으로 끝나지만 **DB 행이 STOCK_COMPENSATION_PENDING 문자열 진입** 하므로 운영 도구 / admin 쿼리 / Grafana 대시보드의 status 분류 모두 영향.
- 과소평가: "재시도 횟수 retryCount 컬럼 재사용" — 현재 `retryCount` 는 `toRetrying()` 에서 PG vendor call retry 의미 (line 92-94). 보상 회복 retry 의미와 **컬럼 1개에 두 의미 겸용** → 운영 가시성 혼선 가능. Round 1 D-A2 가 "재사용" 만 명시.

### Hidden cost 발굴

- **fail() / quarantine() 호출 순서 의존**: 현재 `compensateStockCache` 는 `markPaymentAsFail` **이후** 호출 (PaymentConfirmResultUseCase 흐름). Candidate A 에서는 보상 일부 실패 시 markStockCompensationPending 으로 분기 → markPaymentAsFail 호출하지 않음 → **PaymentEvent 가 FAILED 로 못 가고 STOCK_COMPENSATION_PENDING 에 머무름**. 이는 dedupe extendLease(P8D) 호출 시점도 영향 — 보상 미완 상태에서 P8D lease 연장 시 회복 워커가 같은 PaymentEvent 를 픽업하는 동안 events.confirmed 재수신은 8일간 차단. 회복 latency 와 lease P8D 의 정렬 검증 필요.
- **payment_history audit 자동 발행의 trade-off**: `markStockCompensationPending` 호출 시 `@PublishDomainEvent(action="changed")` AOP 가 발화 → status_change history 1행 INSERT. 회복 완료 시 또 1행, retry 시도마다 또 1행 → **history 행 부풀음** (한 결제당 5+ 행 가능). 운영 audit 체인 부담.
- **Reconciler 폴링 부하**: 현재 `findInProgressOlderThan` 는 IN_PROGRESS 만 조회. STOCK_COMPENSATION_PENDING 추가 시 별도 쿼리 + UNION 또는 OR 조건. 인덱스 (status, last_status_changed_at) 추가 필요 — V1 schema 확인 결과 status 단일 인덱스 미존재.
- **항목 단위 격리 포기**: D-A2 명시. 한 결제 5항목 중 1항목만 실패해도 5항목 전체 재순회 → 성공한 4항목에 INCR 두 번 발생. 처리 토큰 또는 PaymentOrder 단위 가드 (`compensated_at`) 도입 별도 결정 필요. **이 비용이 "약점" 1줄로 압축돼 deep dive 필수**.

### 멱등성 / 안전성 검증

| 시나리오 | Candidate A 의 거동 | 안전한가 |
|---|---|---|
| 워커 크래시 후 재시작 | Reconciler timeout 가드 (예: 60초) 미만 PaymentEvent 는 픽업 안 함. 그러나 timeout 직후 진입한 경우 INCR 이중 호출 가능 | partial — Lua 토큰 같은 강한 차단 부재 |
| Redis 일시 다운 | Reconciler 가 120초마다 재시도. 5회 도달 후 STOCK_COMPENSATION_FAILED 마킹 | fit |
| RDB 일시 다운 | markStockCompensationPending 자체가 RDB UPDATE → RDB 다운 시 예외 전파 → consumer 측 catch 잡음 → silent loss 발생 가능 | break — F0-1 보다 더 심각 (compensateStockCache 자체 흐름이 RDB 의존) |
| 동시 N 결제 같은 product 보상 | PaymentEvent 단위 (orderId) 격리. 같은 product 의 다른 order 는 별 PaymentEvent → 안전 | fit |
| **dedupe lease P5M 만료 + Reconciler timeout 동시 도달** | 같은 events.confirmed 메시지 재수신 가능 (lease 만료) + Reconciler 가 같은 PaymentEvent 픽업 → **이중 회복 트리거** | break — 이 race 분석이 §A1 명시 한계와 별개로 누락 |

### Critic 점수 재평가 (6축)

| 축 | Round 1 평가 | Critic 평가 | 사유 |
|---|---|---|---|
| 비즈니스 흐름 정합 | fit | fit | 일치 — 4신호 모두 회피 |
| 신규 인프라 의존 | fit | **partial** | enum 1 + 도메인 메서드 3 + repository 메서드 1 + Reconciler 분기 1 + isTerminal 분기 + retryCount 의미 겸용 — 변경 surface 가 fit 수준은 아님 |
| 멱등성 / 안전성 | partial | **partial (약점 그대로)** | 일치 — silent over-restore 강한 차단 부재 + dedupe-Reconciler race 추가 |
| happy path 영향 | fit | fit | 일치 |
| 회복 latency | 60~300초 | 60~300초 (단 별도 reconciler 키 분리 시 5~25초 가능) | 일치 |
| 작업량 | 작음 | **중간** | task 5~6개 + 위 hidden cost (PaymentOrder 단위 가드, history 부풀음 대응, status 인덱스) 추가 시 7~9개 |

### 발견 사항 (findings)

- **FA-1 [MAJOR]**: silent over-restore 차단이 timeout 가드만 있고 강한 차단 부재. dedupe-Reconciler race 케이스가 §평가표 한계 표에 누락. PaymentEvent 에 처리 토큰 또는 version 컬럼 도입 시 Candidate 0 의 Lua 와 동일 비용 — "Lua 미도입" 장점이 흐려질 수 있음. Round 3 에서 강한 차단 방안 비교 필수.
- **FA-2 [MAJOR]**: 항목 단위 격리 포기가 PaymentOrder 단위 `compensated_at` 컬럼 추가 → payment_order 테이블 변경. Round 1 §변경 표는 "Flyway 0" 이라 했으나 실제 강한 차단 + 항목 격리 둘 다 달성하려면 Flyway 1+ 추가.
- **FA-3 [MINOR]**: `retryCount` 컬럼 의미 겸용 (PG vendor retry vs 보상 회복 retry) — 운영 가시성 혼선. 별 컬럼 (`compensation_retry_count`) 추가 또는 의미 통일 결정 필요.
- **FA-4 [MAJOR]**: `compensateStockCache` 가 RDB UPDATE 의존 (markStockCompensationPending) — RDB 다운 시 events.confirmed consumer 측 silent loss 신규 도입. 이는 Candidate 0 의 `insert_fail` 과 같은 약점이 아니라 **happy path 측 silent loss** 라 더 심각.

---

## Candidate B — 검증

### Round 1 평가의 정직성

- 인용한 코드: `PaymentOutbox.toInFlight / toDone / toFailed / incrementRetryCount` (line 34-65) 정확, `OutboxRelayService.relay()` (line 50-78) 정확, `claimToInFlight` CAS 패턴 정확.
- 누락한 hidden cost: **payment_outbox 가 현재 payload 컬럼이 없다** (V1__payment_schema.sql line 57-77 확인 — id / order_id / status / retry_count / next_retry_at / in_flight_at / available_at / created_at / updated_at / deleted_at). 현재 `OutboxRelayService.relay()` 는 PaymentEvent 를 별도 조회 (`paymentLoadUseCase.getPaymentEventByOrderId`) 해 payload 를 동적 구성. STOCK_COMPENSATE 도 PaymentEvent 의 PaymentOrder 들에서 productId/quantity 추출 가능 → **payload 컬럼 추가 불필요 가능**. Round 1 D-B5 가 dedup_key 컬럼 신설을 결정했지만 payload 컬럼은 "현재 미보유 → 추가" 라고 잘못 가정.
- 과대평가: "Strategy 패턴 자연 도입" — 현재 OutboxRelayService 는 `claimToInFlight → buildMessage → publish → toDone` 의 4단계 단순 흐름. Strategy 도입 시 (a) ConfirmCommandHandler 가 기존 buildMessage 추출, (b) StockCompensationHandler 가 stockCachePort.increment 호출 + Redis SETNX 멱등성 키 — **Redis SETNX 는 Lua 가 아니지만 멱등성 layer 추가** (Round 1 D-B3). 즉 사용자 신호 (b) Lua 회피는 했지만 멱등성 키 자체는 등장. 본질적으로 처리 토큰이 Redis 측에 있다는 점은 Candidate 0 와 동일.
- 과소평가: "기존 OutboxRelayService refactoring 영향이 크다" 명시는 했으나 **AFTER_COMMIT 즉시 발행 (`OutboxImmediateEventHandler`) + OutboxWorker 폴백** 두 entry point 모두 type 분기 영향. Strategy 가 제대로 작동하려면 두 entry point 의 dispatch 가 일관되게 type 별로 분기. 회귀 surface 가 4 layer (handler / worker / relay / metric).

### Hidden cost 발굴

- **UNIQUE 제약 변경의 운영 비용**: `(order_id)` UNIQUE 를 풀고 `(order_id, message_type, dedup_key)` 로 가려면 ALTER TABLE DROP + ADD UNIQUE — MySQL 기본 lock 발생. payment_outbox 가 결제 happy path 에 직접 사용되므로 운영 배포 시 잠금 시간 측정 필수.
- **dedup_key 의 NULL 의미**: CONFIRM_COMMAND 행은 dedup_key 가 NULL (orderId 단독 식별), STOCK_COMPENSATE 행은 dedup_key=productId. UNIQUE 제약에 NULL 컬럼 포함 시 MySQL 의 NULL 비교 의미 (multiple NULL 허용) → CONFIRM_COMMAND 행은 사실상 (order_id) 단독 UNIQUE 와 동등. 이 의미 운영 가이드 필요.
- **STOCK_COMPENSATE 행의 payload 출처**: PaymentEvent 의 PaymentOrder 에서 추출 가능하지만 Strategy handler 가 PaymentEvent 를 매번 조회 → DB 부하. payload 컬럼에 직렬화 적재 vs 동적 조회 결정 필요.
- **retry policy 충돌**: 기존 PaymentOutbox.incrementRetryCount(policy, now) 는 RetryPolicy bean 1개 가정. STOCK_COMPENSATE 가 다른 정책 (delay 5초 fixed) 을 쓰려면 type 별 RetryPolicy lookup. 이는 `payment.retry.*` 가 그대로 재사용 가능하지만 **type 별 정책 분기 진입점이 OutboxRelayService 안에 추가** → 책임 부풀음.
- **운영자 admin 도구**: 기존 admin 이 (order_id) UNIQUE 가정. STOCK_COMPENSATE 다중 행 진입 후 admin 쿼리·복구 도구 모두 type-aware 변경.

### 멱등성 / 안전성 검증

| 시나리오 | Candidate B 의 거동 | 안전한가 |
|---|---|---|
| 워커 크래시 후 재시작 | claimToInFlight CAS + IN_FLIGHT timeout 으로 재선점 가능. 단 INCR 직후 toDone 직전 크래시 시 같은 row 재진입 → INCR 이중 호출. **D-B3 의 Redis SETNX `compensation:{orderId}:{productId}` 마커가 차단** | fit (단 SETNX 키 도입 = 처리 토큰 with 다른 이름) |
| Redis 일시 다운 | OutboxWorker 폴링 5초 + retry policy fixed 5초. attempt 5 도달 시 FAILED | fit |
| RDB 일시 다운 | `compensateStockCache` 의 catch 가 paymentOutboxRepository.save → RDB 다운 시 예외. 현재 catch 가 swallow 하면 silent loss, re-throw 하면 events.confirmed 재처리. Round 1 묘사 모호 | partial — RDB 다운 처리 정의 필요 |
| 동시 N 결제 같은 product | (order_id, message_type, dedup_key) UNIQUE → 같은 (order, product) 는 1행만 살림. 다른 order 의 같은 product 는 별 row → 안전 | fit |
| **claimToInFlight CAS 와 SETNX 마커 분리 race** | claimToInFlight 성공 + INCR 호출 직전 크래시 → row IN_FLIGHT timeout 후 재선점 + SETNX 가 NX 통과 (아직 안 set) → INCR 이중 가능 | partial — SETNX 가 INCR 직전이 아니라 INCR 직후라면 race window 좁음. Round 1 D-B3 묘사가 정확한 순서 미명시 |

### Critic 점수 재평가 (6축)

| 축 | Round 1 평가 | Critic 평가 | 사유 |
|---|---|---|---|
| 비즈니스 흐름 정합 | partial | partial | 일치 — outbox 의미 작업 큐로 일반화 |
| 신규 인프라 의존 | partial | **partial (Lua 회피 trade-off 강조)** | Strategy 인터페이스 + 2 구현 + Flyway + Redis SETNX 멱등성 키 — Lua 미도입은 했으나 마커는 도입 |
| 멱등성 / 안전성 | fit | **partial** | claimToInFlight + SETNX 의 race window 정의 모호. Round 3 에서 SETNX vs INCR 순서 명시 필요 |
| happy path 영향 | fit | fit | 일치 |
| 회복 latency | 5~25초 | 5~25초 | 일치 |
| 작업량 | 중간 | **중간~큼** | Strategy refactoring 영향 광범위 — 4 layer (handler / worker / relay / metric). 회귀 테스트 부담 candidate 중 최대 |

### 발견 사항 (findings)

- **FB-1 [MAJOR]**: payment_outbox UNIQUE 제약 변경의 운영 lock 비용이 §평가표 누락. ALTER TABLE 시간 / online DDL 가능 여부 / 배포 절차 plan 단계에서 결정 필수.
- **FB-2 [MAJOR]**: Strategy refactoring 의 회귀 surface — 기존 confirm command 발행 경로 (happy path) 회귀 테스트 전체 영향. 본 토픽 핵심인 "happy path 영향 0" 가드가 흔들릴 위험.
- **FB-3 [MAJOR]**: SETNX 멱등성 키 (`compensation:{orderId}:{productId}`) 가 Round 1 D-B3 에 등장 — 사용자 신호 (b) Lua 회피는 했으나 **Redis 측 멱등성 layer 자체는 도입**. 이름만 "Lua 아님" 인 점이 도메인 안전성 비대칭 해소에 부족할 수 있음. INCR 과 SETNX 의 atomic 묶음 부재 → race window 존재.
- **FB-4 [MINOR]**: payment_outbox.payload 컬럼 추가 vs 동적 조회 결정 미정. Round 1 §변경 표가 "추가" 로 가정했으나 실제 불필요할 수 있음.
- **FB-5 [MINOR]**: `OutboxImmediateEventHandler` AFTER_COMMIT 발행이 STOCK_COMPENSATE 행에도 적용되는가? 적용되면 적재 즉시 dispatch 시도 — Round 1 묘사 명시 안 됨.

---

## Candidate C — 검증

### Round 1 평가의 정직성

- 인용한 코드: `PgVendorCallService.insertRetryOutbox` (line 171-188) 정확. `attempt = attempt + 1` 표현은 실제는 `nextAttempt = attempt + 1` (line 172) — 의미 동등.
- 누락한 hidden cost: **dedupe key `eventUuid:attempt` 변경의 cross-impact**. 현재 `EventDedupeStore` 인터페이스가 `eventUuid` 단일 식별자 기반. 변경하면 (a) `PaymentConfirmResultUseCase` 의 lease 호출 모두 attempt 추출 + 키 조립, (b) `extendLease(P8D)` 도 `eventUuid:attempt` 별로 따로 잡힘 → 한 eventUuid 의 happy path 도 attempt=1 키 1개. 그러나 **dedupe 의 본 의미 ("같은 메시지 두 번 처리 안 함")** 가 attempt 별로 분리되면 의미 자체가 변경됨. 운영 가시성 영향 큼.
- 과대평가: "**self-retry 메시지가 events.confirmed 의 다른 신선한 메시지와 같은 토픽 큐에 섞임**" 약점 명시는 했으나 **events.confirmed 가 현재 단일 partition 인지 multi-partition 인지** 검증 없음. 단일 partition 이면 self-retry 가 head-of-line blocking 유발. PaymentTopics.EVENTS_CONFIRMED 토픽 partition 수 plan 단계 확인 필요.
- 과소평가: "PaymentOrder.compensated_at 컬럼 추가 = payment_order 테이블 마이그레이션" — 본 컬럼이 도메인 schema 에 진입. 본 토픽 §0 non-goal 표는 "본 토픽이 의식적으로 책임지지 않는 영역" 인데 PaymentOrder 변경은 결제 도메인 schema 이고 본 토픽 책임 안. 그러나 변경 surface 가 §변경 표 1행이 아닌 4 layer (도메인 + Flyway + 가드 메서드 + 통합 테스트).

### Hidden cost 발굴

- **events.confirmed 토픽 retention 한계**: Kafka retention 7d (본 토픽 §138 line 명시). self-retry 가 7d 안에서만 가능. attempt 5 + backoff 5초 = 25초 안에 종료라 retention 안전하지만 backoff 정책 변경 (예: exponential 1분 5분 30분 6시간 1일) 시 retention 위반 가능.
- **pg-service self-loop 와의 정합**: pg-service 가 같은 토픽 (`payment.commands.confirm`) 에 self-retry. payment-service 가 events.confirmed 에 self-retry — 서로 다른 토픽. 단 두 패턴이 일관되어 있어 **운영 mental model 일관**. 이 점은 강점.
- **Kafka 발행 비용**: self-retry 가 outbox INSERT → AFTER_COMMIT relay → Kafka publish 사이클을 Tax. 5회 self-retry = 5회 Kafka publish + 5회 토픽 메시지. Redis INCR 1회보다 무거움.
- **메시지 페이로드 누적**: 같은 ConfirmedEventMessage 가 5번 들어와 PaymentEvent / dedupe / consumer 처리 모두 5번 — 운영 트레이스 5중. observability 비용.

### 멱등성 / 안전성 검증

| 시나리오 | Candidate C 의 거동 | 안전한가 |
|---|---|---|
| 워커 크래시 후 재시작 | self-retry outbox INSERT 가 RDB TX 에 묶이면 트랜잭션 보장. INSERT 후 Kafka publish 직전 크래시 → AFTER_COMMIT relay 가 회복. attempt 헤더 그대로 → dedupe 가 같은 키로 막음 | partial — Kafka publish 실패 시 같은 attempt 메시지 재발행 가능 |
| Redis 일시 다운 | Redis INCR 실패 → catch → outbox INSERT (self-retry) → 5초 후 재컨슘 → 또 실패 → ... | fit (5회 실패 후 DLQ) |
| RDB 일시 다운 | outbox INSERT 실패 → 예외 전파 → consumer 측 silent loss 가능. Round 1 묘사 모호 | partial — RDB 다운 처리 정의 필요 |
| 동시 N 결제 같은 product | 결제 단위 self-retry → 같은 결제의 이미 성공한 항목이 다음 시도에서 또 INCR. **PaymentOrder.compensated_at 가드 필수** — Round 1 D-C5 명시. 미도입 시 이중 회복 | break — 가드 미도입 시 |
| dedupe key `eventUuid:attempt` 의 cross-attempt 보호 | attempt=1 lease 가 살아있는 동안 attempt=2 메시지가 들어오면 다른 키로 통과 → **두 attempt 가 동시에 처리 가능** | break — attempt 별 lease 가 cross-attempt race 못 막음 |

### Critic 점수 재평가 (6축)

| 축 | Round 1 평가 | Critic 평가 | 사유 |
|---|---|---|---|
| 비즈니스 흐름 정합 | partial | **partial** | dedupe key 의미 변경이 비즈니스 흐름 핵심 신호 변경 → fit 까지는 못 감 |
| 신규 인프라 의존 | partial | partial | 일치 |
| 멱등성 / 안전성 | partial | **break** | PaymentOrder.compensated_at 가드 미도입 시 항목 이중 회복. cross-attempt race 도 추가 약점 |
| happy path 영향 | fit | fit | 일치 |
| 회복 latency | 5~25초 | 5~25초 | 일치 |
| 작업량 | 중간 | **중간** | dedupe 변경 + payment_outbox + self-retry publisher + PaymentOrder schema — Round 1 보다 많음 |

### 발견 사항 (findings)

- **FC-1 [CRITICAL]**: dedupe key `eventUuid:attempt` 변경이 dedupe 의 본 의미 (메시지 1회 처리) 를 부분 해체. attempt=1 처리 중 attempt=2 가 들어오면 다른 키로 lease 획득 → **두 attempt 동시 처리 race**. 본 candidate 의 본질 약점. Round 3 에서 cross-attempt 보호 layer 명시 필요.
- **FC-2 [MAJOR]**: PaymentOrder.compensated_at 컬럼 추가가 payment_order 테이블 변경 — 결제 도메인 schema 진입. 본 토픽 §0 non-goal 표 정신 (보상 회복 layer 가 결제 도메인 안 건드림) 위반.
- **FC-3 [MAJOR]**: events.confirmed self-retry 가 토픽 큐에 섞여 다른 결제 happy path latency 영향. pg-service 도 같은 한계 — 그러나 본 토픽이 새로 도입하는 회복 layer 가 같은 약점 재생산.
- **FC-4 [MINOR]**: events.confirmed 토픽 partition 수 / retention 검증 미정.

---

## Candidate D — 검증

### Round 1 평가의 정직성

- 인용한 코드: `payment_history` 테이블 / `@PublishDomainEvent` AOP / BEFORE_COMMIT 리스너 정확.
- 누락한 hidden cost: **`DomainEventLoggingAspect.processResultAndPublishEvent` 의 action 분기가 "created / retry / changed" 3종 고정** (확인: line 91-106). Candidate D 가 제안한 `@PublishDomainEvent(action="stock_compensate_failed", statusChange=false)` 는 (a) 어노테이션 시그니처 변경, (b) Aspect switch 분기 추가, (c) PaymentEventPublisher 에 publishStockCompensateFailed 메서드 추가, (d) PaymentHistoryService 에 dedup_key + action 컬럼 처리 추가, (e) payment_history schema 변경 — **5 layer 동시 변경**. Round 1 §변경 표가 "AOP 보강" 1행으로 압축.
- 과대평가: "Flyway 1개" — payment_history 에 action / dedup_key 컬럼 추가는 ALTER TABLE. 본 테이블이 append-only audit 이라 운영 lock 영향은 작지만 **`current_status NOT NULL`** 제약 (V1 schema line 89) 이 STOCK_COMPENSATE_* action 행에는 의미 없음 → 제약 완화 또는 dummy 값 채움 결정. Round 1 누락.
- 과소평가: "AOP 의 status_change 결합 분리가 회귀 영향 큼" 명시는 했으나 **현재 PaymentEvent 의 모든 도메인 메서드 (done / fail / quarantine / resetToReady) 가 status 전이를 수반** → AOP 가 status 전이 없는 메서드를 처음 받음. 의미적으로 "domain event" 의 외연이 status 전이에서 audit 행위로 확장됨.

### Hidden cost 발굴

- **payment_history 인덱스 신설**: `findUnrecoveredCompensations` 가 NOT EXISTS 서브쿼리 → 큰 테이블에서 성능 영향. 인덱스 `(action, created_at)` + `(order_id, action)` 추가 필요. 현재 인덱스는 `(payment_event_id)` / `(created_at)` 만.
- **append-only audit 의미 변질**: payment_history 는 status_change 의 SoT. 보상 audit 으로 확장 시 향후 다른 도메인 이벤트 (refund / cancel / partial_cancel) 도 audit 으로 진입 가능 → ad-hoc event store 화 위험. Round 1 §한계 표 명시.
- **회복 retry count = 행 카운트 의 운영 가시성**: retry count 가 `WHERE order_id = ? AND action = STOCK_COMPENSATE_FAILED` 행 수. 운영자 admin 쿼리 / Grafana 메트릭에서 retry count 추출 시 매번 카운트 쿼리. 컬럼 1개로 표현하는 Candidate 0/A 보다 무거움.
- **부분 회복 시 행 매트릭스**: 한 결제 N항목 중 일부만 STOCK_COMPENSATE_RECOVERED, 일부만 STOCK_COMPENSATE_FAILED → history 행이 항목별로 흩어짐. 결제 단위 회복 상태 산출이 복잡 쿼리.
- **silent over-restore 차단**: Round 1 D-D4 는 PaymentEvent 측 version 컬럼 또는 processedAt 가드 도입 필요 명시. **즉 강한 차단 = PaymentEvent schema 변경 또는 처리 토큰 도입** — Candidate A 와 동일 한계.

### 멱등성 / 안전성 검증

| 시나리오 | Candidate D 의 거동 | 안전한가 |
|---|---|---|
| 워커 크래시 후 재시작 | history 가 append-only → 워커 크래시 시 STOCK_COMPENSATE_RECOVERED 미기록 → 같은 history 행이 다음 폴링에서 재진입 → INCR 이중 호출 | break — 강한 차단 부재 |
| Redis 일시 다운 | 워커 폴링 (5초 또는 120초) 마다 재시도 → history 행 1개씩 누적 → 5회 후 STOCK_COMPENSATE_ABANDONED | fit |
| RDB 일시 다운 | history INSERT 실패 → consumer 측 catch → silent loss. 본 토픽 본질 silent loss 와 같은 약점 | partial |
| 동시 N 결제 같은 product | dedup_key 컬럼으로 항목 단위 격리 가능 — Candidate A 의 한계 회피 | fit |
| **history append-only 와 회복 트리거의 race** | 두 워커 인스턴스가 같은 미회복 행을 동시 픽업 → SELECT FOR UPDATE 안 쓰면 race | partial — 워커 단일 인스턴스 가정이라야 안전 |

### Critic 점수 재평가 (6축)

| 축 | Round 1 평가 | Critic 평가 | 사유 |
|---|---|---|---|
| 비즈니스 흐름 정합 | fit | **partial** | payment_history 의미 변질이 audit SoT 신뢰성에 영향 — fit 보다 한 단계 약 |
| 신규 인프라 의존 | fit | **partial** | 어노테이션 + Aspect + Publisher + Service + history schema 5 layer 변경. fit 수준 아님 |
| 멱등성 / 안전성 | partial | **break (강한 차단 부재 + 워커 race)** | append-only audit + 처리 토큰 부재 = 강한 차단 어려움. PaymentEvent.version 도입 시 surface 추가 |
| happy path 영향 | fit | fit | 일치 |
| 회복 latency | 5~25초 또는 60~300초 | 일치 | 결정 의존 |
| 작업량 | 중간 | **중간~큼** | 5 layer 변경 + history 인덱스 + admin 쿼리 도구 → Candidate B 와 동등 |

### 발견 사항 (findings)

- **FD-1 [MAJOR]**: AOP `action` 분기 5 layer 변경 (어노테이션 + Aspect + Publisher + Service + schema) — Round 1 §변경 표 "AOP 보강" 1행이 비용 가림.
- **FD-2 [MAJOR]**: payment_history 의미 변질 — status_change SoT 가 보상 audit 까지 떠안음. ad-hoc event store 화 위험. action 분류 운영 가이드 부재 시 향후 도메인 이벤트마다 history 진입.
- **FD-3 [MAJOR]**: silent over-restore 강한 차단 부재 — append-only audit 특성상 처리 토큰을 history 안에 둘 수 없음. PaymentEvent.version 또는 별 토큰 도입 필요 → Candidate A 와 동일 약점 재현.
- **FD-4 [MINOR]**: payment_history `current_status NOT NULL` 제약과 STOCK_COMPENSATE_* action 행의 의미 충돌 — 제약 완화 또는 dummy 값 결정 미정.
- **FD-5 [MINOR]**: retry count = 행 카운트 의 운영 가시성 — 컬럼 단일 참조보다 무거운 쿼리.

---

## Candidate E — 검증

### Round 1 평가의 정직성

- 인용한 코드: `processMessageWithLeaseGuard` catch 블록 + `eventDedupeStore.remove` 정확 (interface line 52-62 확인).
- 누락한 hidden cost: **호출 순서 변경 (`compensateStockCache` 가 markPaymentAsFail 이전 으로 이동) 의 도메인 의미 변경** — Round 1 D-E1 명시는 했으나 **현재 `handleFailed` / `handleQuarantined` 의 흐름 변경 surface** 미상세. 현재 `handleQuarantined` 는 `compensateStockCache → quarantineCompensationHandler.handle` 순서 (확인 line 281-298). `handleFailed` 도 검증 필요.
- 과대평가: "변경 코드 5~10줄" — 실제는 (a) try/catch 제거, (b) 호출 순서 변경, (c) 통합 테스트 신규 — 코드 라인은 작지만 도메인 의미 변경이 큼.
- 과소평가: "pg-service 재발행 의존" 약점 명시. Round 0 Q1 에서 "거의 없음" 이라 본 candidate 회복 모델이 실질 작동 안 함을 정직하게 인정.

### Hidden cost 발굴

- **결제 종결 latency 증가**: 호출 순서 변경 시 보상 모두 성공해야 markPaymentAsFail. 보상 5회 retry 도중 PaymentEvent 가 `IN_PROGRESS` 또는 `RETRYING` 에 머무름 → 사용자 응답 / 외부 시스템 (PG) 의 결제 상태 조회 모두 지연. 도메인 의미 변경.
- **handleApproved 비대칭**: APPROVED 경로는 stock 확정 outbox INSERT 가 happy path → 보상 실패 ack 실패 모델이 적용 안 됨. 두 경로의 회복 모델 비대칭.
- **dedupe lease 5분 만료의 회복 윈도우**: 5분 후 lease 만료 → 같은 메시지 재컨슘 가능. 그러나 pg-service 가 재발행 안 하면 회복 트리거 0. Round 1 D-E5 명시.

### 멱등성 / 안전성 검증

| 시나리오 | Candidate E 의 거동 | 안전한가 |
|---|---|---|
| 워커 크래시 후 재시작 | 별도 워커 없음 → events.confirmed consumer 측만 회복 가능 | break — 회복 트리거 외부 의존 |
| Redis 일시 다운 | catch 제거 → 예외 전파 → remove(eventUuid) → Kafka redeliver 의존 | break — pg-service 가 재발행 안 하면 회복 0 |
| RDB 일시 다운 | markPaymentAsFail 이전 보상 → RDB 미관여. 단 markPaymentAsFail UPDATE 실패 시 silent loss | partial |
| 동시 N 결제 같은 product | dedupe markWithLease 5분 → race 차단 | fit (단 5분 후 race window) |

### Critic 점수 재평가 (6축)

| 축 | Round 1 평가 | Critic 평가 | 사유 |
|---|---|---|---|
| 비즈니스 흐름 정합 | fit | fit | 일치 — 새 트랙 0 |
| 신규 인프라 의존 | fit | fit | 일치 |
| 멱등성 / 안전성 | break | break | 일치 — 외부 재발행 의존 |
| happy path 영향 | fit | **partial** | 결제 종결 latency 증가 — 사용자 응답 영향 가능 |
| 회복 latency | 5분~∞ | 5분~∞ | 일치 |
| 작업량 | 매우 작음 | 매우 작음 | 일치 |

### 발견 사항 (findings)

- **FE-1 [CRITICAL]**: 회복 트리거가 pg-service 외부 재발행에 의존 — 본 토픽 핵심 목표 (silent loss 자동 회복) 미달성. **Round 0 Q1 에서 이미 기각된 모델의 변형이라 본 candidate 는 deep dive 가치 없음**.
- **FE-2 [MAJOR]**: 호출 순서 변경 (보상 → 상태 전이) 이 결제 종결 latency 영향 — happy path 사용자 응답 지연.
- **FE-3 [MINOR]**: handleApproved / handleFailed 회복 모델 비대칭.

---

## Candidate F — 검증

### Round 1 평가의 정직성

- 인용한 코드: Candidate 0 + Candidate A 의 합집합.
- 누락한 hidden cost: 이미 Round 1 §평가 표에 "사용자 신호 (a)(b) 회피 안 함" + "작업량 가장 큼" 으로 명시. 정직.
- 과대평가 / 과소평가: 본 candidate 는 의도적으로 "정직한 변형" 으로 제출. 점수 자체가 deep dive 가치 낮음을 시사.

### Hidden cost 발굴

- **port 2개 분리의 호출자 부담**: `StockCachePort` (정상) + `StockCompensationCachePort` (보상). 같은 productId 에 대해 어느 port 를 쓸지 호출 시점에 결정 필요. happy path 와 보상 경로의 port 분기 운영 가이드 필수.
- **PaymentEvent 도메인 변경 + 새 테이블 동시**: Candidate A 의 STOCK_COMPENSATION_PENDING 상태 + Candidate 0 의 stock_compensation_outbox 테이블 동시 도입. **두 SoT 동시 보유** — outbox row 의 status 와 PaymentEvent.status 가 같은 의미를 두 곳에 표현 → SoT 충돌 가능.

### 멱등성 / 안전성 검증

| 시나리오 | Candidate F 의 거동 | 안전한가 |
|---|---|---|
| 워커 크래시 후 재시작 | Lua 토큰 (Candidate 0 와 동일) + PaymentEvent.version 가능 | fit (강한 차단) |
| Redis 일시 다운 | 워커 5초 + retry 5회 + PaymentEvent 도메인 가드 | fit |
| RDB 일시 다운 | outbox INSERT 실패 시 silent loss (Candidate 0 와 동일) | partial |
| 동시 N 결제 같은 product | UNIQUE + Lua 토큰 + 도메인 가드 3중 | fit |
| **두 SoT (outbox.status / PaymentEvent.status) 정합** | outbox FAILED + PaymentEvent.STOCK_COMPENSATION_PENDING 상태 동시 가능 → 운영자 admin 시 어느 SoT 우선? | partial — 결정 가이드 필요 |

### Critic 점수 재평가 (6축)

| 축 | Round 1 평가 | Critic 평가 | 사유 |
|---|---|---|---|
| 비즈니스 흐름 정합 | partial | partial | 일치 |
| 신규 인프라 의존 | break | break | 일치 |
| 멱등성 / 안전성 | fit | **partial** | 두 SoT 정합 결정 필요 |
| happy path 영향 | fit | fit | 일치 |
| 회복 latency | 5~25초 | 5~25초 | 일치 |
| 작업량 | 큼 | **가장 큼** | Candidate 0 + Candidate A 합집합 |

### 발견 사항 (findings)

- **FF-1 [MAJOR]**: 두 SoT (outbox.status / PaymentEvent.status) 정합 가이드 부재. 운영자 admin 시 어느 SoT 가 진실인지 결정 필요.
- **FF-2 [MINOR]**: port 2개 호출자 결정 부담.

---

## Round 3 Deep Dive 우선순위 추천

### Architect (Round 1) 추천: A → B → D

### Critic 독립 추천: **A → D → B**

근거:

#### 1순위 — Candidate A (PaymentEvent 신규 상태)

가장 deep dive 가치 있는 이유:
- **사용자 신호 (a)(b)(c)(d) 4개 모두 가장 정직하게 회피** — 새 테이블 0, Lua 0, 새 port API 0, PaymentEvent 라이프사이클 안에서 회복 표현. 채택 시 운영자 mental model 1트랙 (PaymentEvent.status 만 보면 됨).
- **약점이 명확하고 분리 가능** — (i) silent over-restore 강한 차단 부재, (ii) 회복 latency 60~300초, (iii) 항목 단위 격리 포기. 셋 모두 추가 결정 (PaymentOrder.compensated_at, 별 reconciler 키, 처리 토큰) 으로 보강 가능 — 본 candidate 의 본질을 무너뜨리지 않음.
- **변경 surface 가 가장 PaymentEvent 도메인 안에 응축** — 회귀 영향이 PaymentEvent 단위 테스트에 한정. Candidate B/D 의 Strategy refactoring / AOP 변경은 광범위.

Round 3 deep dive 핵심 질문:
- silent over-restore 약점 보강안 3가지 비교 (PaymentEvent.version 컬럼 / 처리 토큰 (Lua 도입 = 사용자 신호 (b) 위반) / Reconciler timeout 가드만 + 부분 over-restore 허용) 중 도메인 안전성 + 신호 회피 모두 만족하는 안.
- 항목 단위 격리 포기의 사용자 영향 — 한 결제 5항목 중 1항목 실패 시 4항목 over-restore 허용 가능한가, PaymentOrder.compensated_at 컬럼 도입 시 결제 도메인 schema 진입 비용은?
- Reconciler 폴링 주기 분리 (`reconciler.stock-compensation-fixed-delay-ms`) 가 단일 워커 분기로 충분한가, 별도 reconciler 인스턴스 필요한가?

#### 2순위 — Candidate D (payment_history audit read)

deep dive 가치:
- **append-only audit 이 회복 SoT 가 되는 발상이 신선** — retry count 가 행 수로 자연 표현. 운영 audit 체인이 결제 / 보상 모두 한 곳에.
- **항목 단위 격리가 dedup_key 컬럼으로 자연** — Candidate A 의 약점을 잘 회피.
- **회복 latency 가 결정 가능** (전용 reconciler vs 기존 분기).

단 Critic 이 Architect 의 3순위에서 2순위로 끌어올린 이유:
- **Candidate B 의 Strategy refactoring 회귀 surface** 가 **happy path 영향 0** 가드를 흔들 위험이 본 토픽 본질 위반. 반면 Candidate D 의 AOP 변경은 audit 발행 경로 — happy path 도메인 흐름 자체는 안 건드림.
- payment_history 의미 변질 우려는 운영 가이드로 흡수 가능 (action 분류 SoP). 반면 payment_outbox 의미 일반화는 운영 도구 + admin 쿼리 + 메트릭 + 알람 모두 type-aware 변경 필요.

Round 3 deep dive 핵심 질문:
- AOP `action` 분기 5 layer 변경의 회귀 영향 정량화 — 어노테이션 / Aspect / Publisher / Service / schema. 기존 status_change 행 발행이 동작 변경되는가?
- payment_history `current_status NOT NULL` 제약 처리 (제약 완화 vs dummy 값).
- silent over-restore 강한 차단 — Candidate A 와 같은 한계 어떻게 보강할지 (PaymentEvent.version 도입 시 두 candidate 가 사실상 합쳐짐).

#### 3순위 — Candidate B (payment_outbox message_type 확장)

deep dive 가치:
- **claimToInFlight CAS + IN_FLIGHT timeout 의 자연 멱등성** 패턴 재사용. 단 Redis SETNX 마커 도입 = 사용자 신호 (b) Lua 회피의 명목적 가치만 살아남음.

Critic 이 3순위로 내린 이유:
- **Strategy refactoring 의 회귀 surface 가 happy path 위협** — 본 토픽 핵심 가드 (happy path 영향 0) 위반 위험. 4 layer (handler / worker / relay / metric) 변경.
- **UNIQUE 제약 변경의 운영 lock 비용** — payment_outbox 가 결제 happy path 직접 사용. ALTER TABLE online DDL 보장 / 배포 절차 부담.
- **outbox 의미 일반화 → 향후 ad-hoc job queue 화 위험** — 본 토픽이 단일 결정으로 outbox 패턴의 본 의미를 바꿈. 도메인 결정 무게가 본 토픽 단독 책임 초과.

Round 3 deep dive 핵심 질문:
- Strategy 패턴 도입의 회귀 테스트 surface — 기존 confirm command happy path 회귀가 보장 가능한가?
- payment_outbox UNIQUE 제약 변경 online DDL 가능 여부 / 배포 절차.
- SETNX 멱등성 키 (`compensation:{orderId}:{productId}`) 와 INCR 의 atomic 묶음 — Lua 미도입 시 race window.

#### 후순위 사유

- **Candidate 0**: 사용자 거부 baseline. 대조군 가치 외 deep dive 불요.
- **Candidate E**: pg-service 외부 재발행 의존 → 본 토픽 핵심 목표 (silent loss 자동 회복) 미달성. Round 0 Q1 에서 이미 기각.
- **Candidate F**: Candidate 0 + Candidate A 의 합집합 — 작업량 가장 큼 + 사용자 신호 (a)(b) 회피 안 함 + 두 SoT 정합 부담. 의도적 "대조군" 으로 제출됐고 deep dive 가치 낮음.

---

## Round 3 에 던질 질문

### Candidate A (1순위)

1. **silent over-restore 강한 차단 방안 비교** — (i) PaymentEvent.version 컬럼 추가 (Flyway 1, 도메인 메서드 모든 곳 version 가드), (ii) Redis 처리 토큰 (Lua 도입 = 사용자 신호 (b) 위반), (iii) Reconciler timeout 가드 + 부분 over-restore 허용 (도메인 안전성 비대칭) — 셋 중 사용자 신호 4개 + 도메인 안전성 모두 만족하는 안?
2. **PaymentEvent.fail() / quarantine() 가드 변경** — STOCK_COMPENSATION_PENDING 을 from-status 로 추가 시 회귀 영향. 기존 단위 테스트 (PaymentEventTest @ParameterizedTest @EnumSource) 어떻게 확장?
3. **retryCount 컬럼 의미 겸용 vs 분리** — 기존 PG vendor retry 카운터와 보상 회복 retry 카운터 통합 시 운영 가시성 영향. 별 컬럼 (`compensation_retry_count`) 도입 시 Flyway 1 추가.
4. **Reconciler 폴링 주기 분리** — `reconciler.stock-compensation-fixed-delay-ms: 5000` 별도 키 도입 시 단일 reconciler 가 두 분기 동작 가능 (성능 영향) vs 별도 reconciler 인스턴스 (운영 mental model 분기).
5. **dedupe lease P5M 만료 + Reconciler timeout 동시 도달 race** — events.confirmed 메시지 재수신과 Reconciler 픽업이 동시 트리거되는 케이스 어떻게 차단?
6. **항목 단위 격리 vs 결제 단위 재순회** — PaymentOrder.compensated_at 컬럼 도입 시 결제 도메인 schema 진입 (본 토픽 §0 non-goal 위반?). 미도입 시 N항목 중 1항목 실패가 N항목 모두 over-restore.
7. **`OutboxAsyncConfirmService.compensateStock` / `PaymentTransactionCoordinator.compensateStockCacheGuarded` 두 silent loss 경로** — Candidate A 가 PaymentEvent 라이프사이클에 회복 묶을 때 두 경로는 PaymentEvent 가 없는 시점이라 같은 해법 못 씀. 별도 layer 필요?

### Candidate D (2순위)

1. **AOP `action` 분기 변경의 회귀 영향** — 어노테이션 / Aspect / Publisher / Service / schema 5 layer 동시 변경. 기존 status_change 행 발행 동작 보존 보장?
2. **payment_history `current_status NOT NULL` 제약 처리** — STOCK_COMPENSATE_* action 행에는 의미 없는 컬럼. 제약 완화 vs dummy 값 vs 별 테이블 분리 결정.
3. **payment_history 의미 변질 운영 가이드** — action 분류 SoP 부재 시 향후 도메인 이벤트 (refund / cancel) 도 history 진입. 명시적 action 화이트리스트 필요?
4. **silent over-restore 강한 차단** — append-only audit 특성상 처리 토큰 못 둠. PaymentEvent.version 도입 시 Candidate A 와 사실상 합쳐짐 — 본 candidate 본질 약점.
5. **NOT EXISTS 서브쿼리 성능** — `findUnrecoveredCompensations(cutoff)` 가 큰 history 테이블에서 부하. 인덱스 `(action, created_at)` + `(order_id, action)` 추가 비용.
6. **부분 회복 시 행 매트릭스** — 한 결제 N항목 중 일부 RECOVERED, 일부 FAILED, 일부 PENDING → 결제 단위 회복 상태 산출 쿼리 복잡도.

### Candidate B (3순위)

1. **Strategy refactoring 회귀 surface** — 기존 confirm command happy path 회귀 테스트 보장. AFTER_COMMIT 즉시 발행 + OutboxWorker 폴백 두 entry point 모두 type 분기 동작.
2. **payment_outbox UNIQUE 제약 변경 online DDL** — `(order_id)` UNIQUE 풀고 `(order_id, message_type, dedup_key)` 추가. MySQL online DDL 가능 여부 / 배포 절차 / lock 시간.
3. **SETNX 멱등성 키 race window** — `compensation:{orderId}:{productId}` SETNX 와 INCR 의 atomic 묶음 부재. Lua 미도입 시 SETNX 실패 + INCR 성공 race window 어떻게 차단?
4. **payload 컬럼 추가 vs 동적 조회** — STOCK_COMPENSATE 행이 PaymentEvent 에서 productId/quantity 추출 가능. payload 컬럼 추가 vs PaymentEvent 매번 조회의 trade-off.
5. **outbox 의미 일반화의 도메인 결정 무게** — 본 토픽 단독으로 outbox 패턴 의미 변경 (발행 큐 → 작업 큐). 향후 다른 비동기 작업 (refund command) 도 같은 테이블 진입 가이드 필요?

---

## decision

Round 3 진행 가부: **pass**

Round 1 산출물은 7개 candidate 의 폭과 깊이 모두 Round 3 deep dive 입력으로 충분. critical finding 은 Candidate C (FC-1) / Candidate E (FE-1) 두 건이지만 이는 deselected candidate 의 약점이라 Round 3 진행에 영향 없음. Round 3 우선순위 (A → D → B) 는 본 Critic 독립 평가로 명시.

Major finding 다수 (FA-1, FA-2, FA-4, FB-1, FB-2, FB-3, FC-2, FC-3, FD-1, FD-2, FD-3, FE-2, FF-1) 는 Round 3 에서 candidate 별로 deep dive 시 답해야 할 질문으로 이미 §Round 3 에 던질 질문 절에 환원됨.

---

## JSON

```json
{
  "stage": "discuss",
  "persona": "critic",
  "round": 2,
  "topic": "STOCK-COMPENSATION-RECOVERY-ALTERNATIVES",
  "decision": "pass",
  "findings": [
    { "id": "F0-1", "severity": "major", "candidate": "0", "checklist_item": "멱등성 / 안전성 — RDB 다운 silent loss", "location": "round-1 §Candidate 0 §평가표 line 61, 본 토픽 §3.5 insert_fail_total Counter", "problem": "RDB 다운 시 appender INSERT 실패 swallow 가 마지막 silent loss 잔존. 본 토픽이 silent loss 봉인 목표인데 회복 layer 가 새 silent loss 도입 비대칭", "evidence": "STOCK-COMPENSATION-RECOVERY-ALTERNATIVES.md line 61, STOCK-COMPENSATION-RECOVERY.md §3.5 / §7.1.3", "suggestion": "메트릭 임계 0 + 운영자 즉시 알람 명시 + RDB 회복 후 자동 회복 layer 후속 토픽 분리" },
    { "id": "F0-2", "severity": "minor", "candidate": "0", "checklist_item": "운영 가시성", "location": "STOCK-COMPENSATION-RECOVERY.md §3.5 worker_reentry_total", "problem": "Counter 알람 임계 미정 — 단발성 / 누적 결정 plan 단계로 미룸", "evidence": "STOCK-COMPENSATION-RECOVERY.md §3.5 line 267", "suggestion": "Round 3 또는 plan 에서 임계 결정" },
    { "id": "F0-3", "severity": "minor", "candidate": "0", "checklist_item": "Lua 인프라", "location": "STOCK-COMPENSATION-RECOVERY.md §사후 트레이드오프 line 123", "problem": "Lua KEYS cross-slot 가능성 task 미분리", "evidence": "STOCK-COMPENSATION-RECOVERY.md line 123", "suggestion": "plan 단계 task 6 에 hash tag 강제 명시" },
    { "id": "FA-1", "severity": "major", "candidate": "A", "checklist_item": "멱등성 / 안전성 — silent over-restore 강한 차단", "location": "ALTERNATIVES.md Candidate A §알려진 한계 line 140", "problem": "timeout 가드만 있고 강한 차단 부재. dedupe lease P5M 만료 + Reconciler timeout 동시 race 가 §한계 표 누락", "evidence": "ALTERNATIVES.md line 140, EventDedupeStore.java line 28-37", "suggestion": "Round 3 deep dive 1 — version 컬럼 / 처리 토큰 / timeout 가드 3안 비교" },
    { "id": "FA-2", "severity": "major", "candidate": "A", "checklist_item": "비즈니스 흐름 정합 — Flyway 0 주장", "location": "ALTERNATIVES.md Candidate A §변경 표 line 117", "problem": "Flyway 0 주장이 정확하나 강한 차단 + 항목 격리 둘 다 달성 시 Flyway 1+ 추가 (PaymentOrder.compensated_at 또는 PaymentEvent.version)", "evidence": "ALTERNATIVES.md line 117, V1__payment_schema.sql line 30-50", "suggestion": "Round 3 deep dive 6 — 항목 단위 격리 vs 결제 단위 재순회 결정" },
    { "id": "FA-4", "severity": "major", "candidate": "A", "checklist_item": "멱등성 / 안전성 — RDB 의존", "location": "PaymentConfirmResultUseCase.compensateStockCache line 304-317", "problem": "Candidate A 의 markStockCompensationPending 호출이 RDB UPDATE 의존. RDB 다운 시 events.confirmed consumer 측 신규 silent loss 도입 (happy path 측)", "evidence": "PaymentConfirmResultUseCase.java line 304-317, ALTERNATIVES.md Candidate A §3.2", "suggestion": "Round 3 에서 RDB 다운 처리 정의 (Candidate 0 의 insert_fail 메트릭과 같은 일관 처리)" },
    { "id": "FA-3", "severity": "minor", "candidate": "A", "checklist_item": "운영 가시성 — retryCount 의미 겸용", "location": "PaymentEvent.toRetrying line 86-95", "problem": "retryCount 컬럼이 PG vendor retry + 보상 회복 retry 두 의미 겸용 — 운영 가시성 혼선", "evidence": "PaymentEvent.java line 92, V1__payment_schema.sql line 18", "suggestion": "별 컬럼 (compensation_retry_count) 도입 또는 의미 통일 결정" },
    { "id": "FB-1", "severity": "major", "candidate": "B", "checklist_item": "운영 비용 — UNIQUE 제약 변경", "location": "ALTERNATIVES.md Candidate B §주요 결정 D-B2 line 201, V1__payment_schema.sql line 70", "problem": "payment_outbox UNIQUE (order_id) 단독 → (order_id, message_type, dedup_key) 변경 시 ALTER TABLE lock. happy path 직접 사용 테이블이라 운영 영향 큼", "evidence": "V1__payment_schema.sql line 70, ALTERNATIVES.md line 201", "suggestion": "Round 3 deep dive 2 — online DDL 가능 여부 + 배포 절차 정의" },
    { "id": "FB-2", "severity": "major", "candidate": "B", "checklist_item": "happy path 영향 — Strategy refactoring", "location": "OutboxRelayService.relay line 50-78, ALTERNATIVES.md Candidate B §변경 표", "problem": "Strategy 패턴 도입 시 4 layer (handler / worker / relay / metric) 변경 — 기존 confirm command happy path 회귀 surface 광범위. 본 토픽 핵심 가드 (happy path 영향 0) 위협", "evidence": "OutboxRelayService.java line 50-78, ALTERNATIVES.md Candidate B §변경 표 + §알려진 한계 line 220", "suggestion": "Round 3 deep dive 1 — 회귀 테스트 surface 정량화" },
    { "id": "FB-3", "severity": "major", "candidate": "B", "checklist_item": "멱등성 / 안전성 — SETNX race", "location": "ALTERNATIVES.md Candidate B D-B3 line 202", "problem": "Redis SETNX `compensation:{orderId}:{productId}` 와 INCR atomic 묶음 부재. Lua 미도입 시 race window 존재. 사용자 신호 (b) 회피의 명목적 가치만 남음", "evidence": "ALTERNATIVES.md line 202, StockCacheRedisAdapter.DECREMENT_SCRIPT 패턴 (본 토픽 §1 line 13)", "suggestion": "Round 3 deep dive 3 — race window 정의 + 차단안" },
    { "id": "FB-4", "severity": "minor", "candidate": "B", "checklist_item": "schema 결정", "location": "V1__payment_schema.sql line 57-77", "problem": "payment_outbox.payload 컬럼 추가 vs 동적 조회 결정 미정. 현재 schema 에 payload 없음", "evidence": "V1__payment_schema.sql line 57-77, OutboxRelayService.relay line 70-74 (PaymentEvent 동적 조회)", "suggestion": "Round 3 deep dive 4 — payload 컬럼 추가 trade-off" },
    { "id": "FB-5", "severity": "minor", "candidate": "B", "checklist_item": "발행 entry point", "location": "OutboxImmediateEventHandler / OutboxWorker", "problem": "AFTER_COMMIT 즉시 발행이 STOCK_COMPENSATE 행에도 적용되는가 명시 안 됨", "evidence": "ALTERNATIVES.md Candidate B §활용 패턴 line 181", "suggestion": "Round 3 또는 plan 에서 두 entry point dispatch 명시" },
    { "id": "FC-1", "severity": "critical", "candidate": "C", "checklist_item": "멱등성 / 안전성 — dedupe key 의미 변경", "location": "ALTERNATIVES.md Candidate C D-C1 line 277", "problem": "dedupe key `eventUuid:attempt` 변경이 dedupe 의 본 의미 (메시지 1회 처리) 부분 해체. attempt=1 처리 중 attempt=2 진입 → 다른 키로 lease 획득 → 두 attempt 동시 처리 race", "evidence": "ALTERNATIVES.md line 277, EventDedupeStore.markWithLease 시그니처 line 24-37", "suggestion": "deselected — Round 3 진행 시 cross-attempt 보호 layer 명시 필요. 본 critic 은 후순위 권고" },
    { "id": "FC-2", "severity": "major", "candidate": "C", "checklist_item": "비즈니스 흐름 정합 — 도메인 schema 진입", "location": "ALTERNATIVES.md Candidate C D-C5 line 281", "problem": "PaymentOrder.compensated_at 컬럼 추가 = payment_order 테이블 변경. 결제 도메인 schema 진입 — 본 토픽 §0 non-goal 정신 위반", "evidence": "ALTERNATIVES.md line 281, V1__payment_schema.sql line 35-50", "suggestion": "deselected" },
    { "id": "FC-3", "severity": "major", "candidate": "C", "checklist_item": "토픽 큐 latency 영향", "location": "ALTERNATIVES.md Candidate C §알려진 한계 line 299", "problem": "events.confirmed self-retry 가 토픽 큐에 섞여 다른 결제 happy path latency 영향. pg-service 도 같은 한계", "evidence": "ALTERNATIVES.md line 299, PgVendorCallService.insertRetryOutbox line 171-188", "suggestion": "deselected" },
    { "id": "FC-4", "severity": "minor", "candidate": "C", "checklist_item": "토픽 retention", "location": "ALTERNATIVES.md Candidate C", "problem": "events.confirmed 토픽 partition 수 / retention 검증 미정", "evidence": "ALTERNATIVES.md Candidate C §활용 패턴", "suggestion": "deselected" },
    { "id": "FD-1", "severity": "major", "candidate": "D", "checklist_item": "신규 인프라 의존 — AOP 5 layer 변경", "location": "DomainEventLoggingAspect.processResultAndPublishEvent line 91-106", "problem": "현재 AOP action 분기가 created/retry/changed 3종 고정. Candidate D 의 stock_compensate_failed action 추가 시 어노테이션 + Aspect + Publisher + Service + schema 5 layer 동시 변경 — Round 1 §변경 표 'AOP 보강' 1행이 비용 가림", "evidence": "DomainEventLoggingAspect.java line 91-106, ALTERNATIVES.md Candidate D §변경 표 line 343", "suggestion": "Round 3 deep dive 1 — 회귀 영향 정량화" },
    { "id": "FD-2", "severity": "major", "candidate": "D", "checklist_item": "비즈니스 흐름 정합 — payment_history 의미 변질", "location": "ALTERNATIVES.md Candidate D §알려진 한계 line 371", "problem": "payment_history 가 status_change SoT 에서 보상 audit + 향후 다른 도메인 이벤트로 확장 → ad-hoc event store 화 위험", "evidence": "V1__payment_schema.sql line 84-100, ALTERNATIVES.md line 371", "suggestion": "Round 3 deep dive 3 — action 화이트리스트 운영 가이드" },
    { "id": "FD-3", "severity": "major", "candidate": "D", "checklist_item": "멱등성 / 안전성 — 강한 차단 부재", "location": "ALTERNATIVES.md Candidate D D-D4 line 355", "problem": "append-only audit 특성상 처리 토큰 못 둠. PaymentEvent.version 도입 시 Candidate A 와 사실상 합쳐짐 — 본 candidate 본질 약점", "evidence": "ALTERNATIVES.md line 355", "suggestion": "Round 3 deep dive 4 — 강한 차단 layer 결정" },
    { "id": "FD-4", "severity": "minor", "candidate": "D", "checklist_item": "schema 제약", "location": "V1__payment_schema.sql line 89", "problem": "payment_history.current_status NOT NULL 제약과 STOCK_COMPENSATE_* action 행 의미 충돌", "evidence": "V1__payment_schema.sql line 89", "suggestion": "Round 3 또는 plan 에서 제약 완화 vs dummy 값 결정" },
    { "id": "FD-5", "severity": "minor", "candidate": "D", "checklist_item": "운영 가시성 — retry count = 행 카운트", "location": "ALTERNATIVES.md Candidate D D-D1 line 352", "problem": "retry count 가 행 수 카운트 — 컬럼 단일 참조보다 무거운 쿼리", "evidence": "ALTERNATIVES.md line 352", "suggestion": "Round 3 deep dive 6 — 결제 단위 회복 상태 산출 쿼리 복잡도" },
    { "id": "FE-1", "severity": "critical", "candidate": "E", "checklist_item": "멱등성 / 안전성 — 외부 재발행 의존", "location": "ALTERNATIVES.md Candidate E §알려진 한계 line 446", "problem": "회복 트리거가 pg-service 외부 재발행에 의존. Round 0 Q1 에서 거의 없음 확인 — 본 토픽 핵심 목표 (silent loss 자동 회복) 미달성", "evidence": "ALTERNATIVES.md line 446, STOCK-COMPENSATION-RECOVERY.md §4 Q1 line 75", "suggestion": "deselected — deep dive 가치 없음" },
    { "id": "FE-2", "severity": "major", "candidate": "E", "checklist_item": "happy path 영향", "location": "ALTERNATIVES.md Candidate E D-E1 line 427", "problem": "호출 순서 변경 (보상 → 상태 전이) 이 결제 종결 latency 영향 — 사용자 응답 지연 가능", "evidence": "ALTERNATIVES.md line 427, PaymentConfirmResultUseCase.handleQuarantined line 281-298", "suggestion": "deselected" },
    { "id": "FE-3", "severity": "minor", "candidate": "E", "checklist_item": "도메인 일관성", "location": "ALTERNATIVES.md Candidate E §알려진 한계 line 448", "problem": "handleApproved / handleFailed 회복 모델 비대칭", "evidence": "ALTERNATIVES.md line 448", "suggestion": "deselected" },
    { "id": "FF-1", "severity": "major", "candidate": "F", "checklist_item": "두 SoT 정합", "location": "ALTERNATIVES.md Candidate F §활용 패턴 line 481", "problem": "outbox.status (FAILED) + PaymentEvent.status (STOCK_COMPENSATION_PENDING) 두 SoT 동시 보유 — 운영자 admin 시 우선순위 결정 가이드 부재", "evidence": "ALTERNATIVES.md Candidate F §3 + Candidate A §3.1", "suggestion": "deselected — deep dive 가치 낮음" },
    { "id": "FF-2", "severity": "minor", "candidate": "F", "checklist_item": "호출자 결정", "location": "ALTERNATIVES.md Candidate F D-F1 line 497", "problem": "StockCachePort + StockCompensationCachePort 두 port 의 호출 시점 결정 부담", "evidence": "ALTERNATIVES.md line 497, StockCachePort.java", "suggestion": "deselected" }
  ],
  "scores": {
    "candidate_a_business_fit": "fit",
    "candidate_a_infra_dependency": "partial",
    "candidate_a_idempotency_safety": "partial",
    "candidate_a_happy_path": "fit",
    "candidate_a_recovery_latency": "60-300s",
    "candidate_a_workload": "medium",
    "candidate_b_business_fit": "partial",
    "candidate_b_infra_dependency": "partial",
    "candidate_b_idempotency_safety": "partial",
    "candidate_b_happy_path": "fit",
    "candidate_b_recovery_latency": "5-25s",
    "candidate_b_workload": "medium-large",
    "candidate_d_business_fit": "partial",
    "candidate_d_infra_dependency": "partial",
    "candidate_d_idempotency_safety": "break",
    "candidate_d_happy_path": "fit",
    "candidate_d_recovery_latency": "5-25s_or_60-300s",
    "candidate_d_workload": "medium-large"
  },
  "round3_priority_recommendation": ["A", "D", "B"],
  "round3_priority_architect_recommendation": ["A", "B", "D"],
  "deselected_candidates": ["0", "C", "E", "F"],
  "delta": "Round 1 산출 7개 candidate 모두 동일 깊이로 검증. Architect 추천 (A→B→D) 와 다르게 D를 2순위로 끌어올림 — Candidate B 의 Strategy refactoring 회귀 surface 가 본 토픽 핵심 가드 (happy path 영향 0) 위협 + payment_outbox UNIQUE 변경 운영 lock 비용. critical 2건 (FC-1, FE-1) 은 deselected candidate 약점이라 Round 3 진행 영향 없음.",
  "unstuck_suggestion": "architect"
}
```
