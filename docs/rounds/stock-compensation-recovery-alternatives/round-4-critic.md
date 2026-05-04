# Round 4 Critic — Refined Candidate A/B/D Deep Validation

> 검증 대상: Round 3 Architect 가 refine 한 Candidate A / B / D (`docs/topics/STOCK-COMPENSATION-RECOVERY-ALTERNATIVES.md` line 145-230 / 311-405 / 559-654 + 종합 line 837-883)
> 검증자: Critic (격리 서브에이전트, Domain Expert 산출 미참조)
> 검증 모드: Round 3 흡수 정직성 + Round 3 신규 위험 발굴 + 작업량 / happy path 영향 0 가드 / Round 5 권고
> 코드 사실 재확인: `OutboxRelayService.java` / `JpaPaymentOutboxRepository.java` / `OutboxImmediateEventHandler.java` / `OutboxWorker.java` / `PaymentConfirmEvent.java` / `PaymentEvent.java` / `PaymentCommandUseCase.java` / `DomainEventLoggingAspect.java` / `PaymentEventPublisher.java` / `PaymentReconciler.java` / `V1__payment_schema.sql` / `PaymentConfirmResultUseCase.java` 12개 파일 직접 Read 확인.

---

## 종합 verdict

Round 3 Architect 의 refinement 작업은 Round 2 finding 흡수 매트릭스를 표면적으로 정직하게 작성했고 코드 인용 12건 재확인도 정확하다. 다만 **흡수 방식 자체가 도입한 신규 위험이 충분히 노출되지 않았다.** 특히 (i) **Candidate D 옵션 D2 의 핵심 가정이 무너진다** — `paymentEvent.markStockCompensateFailed` 를 도메인 메서드로 두고 별 Aspect 가 `@PublishStockCompensationEvent` 어노테이션을 잡아 `PaymentHistoryEvent` 를 발행한다는 설계는 **PaymentEvent 가 plain POJO (Spring bean 아님)** 라 Spring AOP 의 proxy-based 가로채기가 작동하지 않는다. 현재 `@PublishDomainEvent` 는 `PaymentCommandUseCase` (application 빈) 의 메서드에만 붙어 있다 (line 28/37/46/55/64/73). 이 critical error 가 Round 3 본문 + Round 2 흡수 표 어디에도 인지되지 않았다. (ii) **Candidate B 의 claimToInFlight 시그니처 변경 surface 는 Round 3 추정보다 광범위** — 호출 surface 가 도메인 이벤트 (`PaymentConfirmEvent`) 자체 + `OutboxImmediatePublisher` + AFTER_COMMIT 리스너 + OutboxWorker + 통합 테스트 다수까지 4 layer 가 아닌 6 layer 로 확장된다. (iii) **Candidate A 의 호출 순서 유지 (markPaymentAsFail → compensateStockCache) + compensation_status NULL 도입** 은 markPaymentAsFail 직후 + compensateStockCache 호출 직전 process crash 시 **compensation_status=NULL 인 채로 status=FAILED (terminal) 도달** — Reconciler 의 `WHERE compensation_status='PENDING'` 쿼리가 NULL 행을 못 잡아 silent under-restore 신규 발생 가능. Round 3 흡수 매트릭스 FA-4 가 "RDB 다운 시" 만 다루고 process crash 케이스를 누락했다.

3 candidate 의 refine 후 실질 차별점을 보면 **Candidate A 가 silent over-restore 강한 차단 (compensation_state_version) + happy path 영향 0 가드 무결성 두 차원에서 가장 안전**하지만 위 (iii) 의 NULL-edge race 가 critical-level 검증 필요. **Candidate B 는 회귀 surface 가 본 토픽 핵심 가드 (happy path 영향 0) 와 정면 충돌** — claimToInFlight 시그니처 변경 + Strategy refactoring + UNIQUE 제약 변경의 3중 surface 가 Round 3 가 인정한 "위협" 수준을 넘어 본 토픽 단독 결정 범위를 초과한다. **Candidate D 는 옵션 D2 자체가 무너졌으므로 채택 시 옵션 D1 (기존 Aspect 보강) 으로 회귀** — 그러면 Round 2 FD-1 가 다시 살아나 5 layer 변경. Round 3 의 옵션 D2 채택 결정이 무효화된다.

본 Critic 의 Round 5 권고는 **A → D (옵션 재논의) → B**. Candidate A 의 NULL-edge race 만 해소되면 가장 안전. Candidate B 는 happy path 영향 0 가드 위협이 본 토픽의 가장 큰 결정 무게라 사용자에게 "B 채택 = 본 토픽이 outbox 패턴 의미를 변경하는 무게의 단독 결정" 으로 명시적 confirm 필요.

---

## Candidate A (refined) — Deep Validation

### Round 3 흡수 정직성

| Finding ID (Round 2) | Round 3 흡수 주장 | Critic 평가 | 사유 |
|---|---|---|---|
| DA-1 / RA-1 (critical) | 옵션 A2 채택 — `compensation_status` 별 컬럼 + status (terminal) 그대로 유지 + 호출 순서 유지 | **부분 흡수** | 호출 순서 유지 + 별 컬럼 도입은 isTerminal 가드 위반은 회피하지만 `markPaymentAsFail → compensateStockCache 호출 직전` process crash 케이스에서 compensation_status=NULL 로 terminal 진입 → silent under-restore 신규 발생. Round 3 본문 line 156 의 catch 블록 흐름이 process crash 대응 안 됨 |
| DA-2 (critical) | 부분 보상 일관성 break — PaymentOrder.compensated_at 도입은 본 토픽 non-goal 명시 미루기 | **정직** | non-goal 명시는 정직. 단 결제 단위 재순회로 인한 over-restore 발산은 알려진 한계로 인정됨 — Round 5 사용자 결정 입력 |
| FA-1 / DA-3 (major) | `payment_event.compensation_state_version` 컬럼 + Reconciler version 가드 | **정직** | 강한 차단 fit. JPA `@Version` 어노테이션 위치 / OptimisticLockingException 처리는 Round 4 도메인 검증 권고로 이관됨 — 합리적 |
| FA-2 (major) | "Flyway 0" 정정 — V<n> 1개 (compensation_status / compensation_retry_count / compensation_state_version 3 컬럼 + 인덱스 1) | **정직** | Round 1 의 Flyway 0 주장이 정확히 정정됨. Round 3 line 188-198 의 V<n> SQL 도 합리적 |
| FA-3 (minor) | `compensation_retry_count INT NULL DEFAULT 0` 별 컬럼 분리 | **정직** | retryCount 의미 겸용 해소 |
| FA-4 (major) | RDB 다운 처리 — STOCK_COMPENSATION_RDB_FAIL Counter + swallow + 예외 무시 | **부분 흡수** | RDB 다운만 다룸. **process crash (RDB 정상 + JVM 죽음) 케이스 누락** — markPaymentAsFail UPDATE 커밋 후 + markCompensationPending UPDATE 호출 직전 crash 시 compensation_status=NULL terminal 잔존 |
| DA-4 (major) | switch 변경 0 (옵션 A2 가 새 enum 추가 안 함) | **정직** | 정확. PaymentEventStatus enum 변경 0 |
| DA-5 (major) | 별 reconciler 키 + 별 메서드 + 단일 reconciler 인스턴스 + scheduler.thread-pool-size: 2 | **정직** | 합리적. 단 Round 4 운영 검증 권고 정직하게 명시됨 |

**Round 3 흡수 종합 평가**: 8건 중 6건 정직 흡수 / 2건 (DA-1, FA-4) 부분 흡수. DA-1 의 부분 흡수가 critical 잠재 위험 (NULL-edge race) 을 노출.

### Round 3 신규 위험

- **A4-1 [CRITICAL]**: handleFailed 호출 순서 유지 (`PaymentConfirmResultUseCase.handleFailed` line 258-272) + 옵션 A2 의 compensation_status NULL 기본값 결합이 silent under-restore 신규 경로 도입.
  - **시나리오**: `markPaymentAsFail(paymentEvent, reasonCode)` (line 266) UPDATE TX 커밋 → status=FAILED + compensation_status=NULL 영구. 직후 `compensateStockCache(paymentEvent, reasonCode)` (line 268) 호출 직전 process crash → catch 블록 진입 0 → markCompensationPending 호출 0.
  - **결과**: events.confirmed 메시지 재배달 시 dedupe lease 가 살아 있어 `markWithLease` 통과 못 함 → 재처리 0. Reconciler 의 `WHERE compensation_status='PENDING'` 쿼리는 compensation_status=NULL 행 못 잡음 → 영구 silent under-restore.
  - **evidence**: `PaymentConfirmResultUseCase.java:266-268` 호출 순서 + `STOCK-COMPENSATION-RECOVERY-ALTERNATIVES.md:171` (`WHERE compensation_status = 'PENDING' AND last_status_changed_at < cutoff`).
  - **suggestion**: (a) markPaymentAsFail 메서드 안에서 compensation_status=NEEDS_CHECK 같은 marker 동시 설정 (single TX 묶음) 또는 (b) Reconciler 쿼리를 `WHERE compensation_status = 'PENDING' OR (status IN ('FAILED','QUARANTINED') AND compensation_status IS NULL AND last_status_changed_at < cutoff)` 로 확장 — but 후자는 정상 결제 (FAILED + 보상 0건 결제) 도 픽업하는 false-positive 발생.

- **A4-2 [MAJOR]**: Reconciler scan 쿼리에 compensation_status='PENDING' OR 분기 추가 시 V1 schema 의 `idx_payment_event_status_last_changed (status, last_status_changed_at)` 인덱스가 못 쓰임. Round 3 line 196-198 가 `idx_payment_event_compensation_status_changed (compensation_status, last_status_changed_at)` 추가로 보강했으나 **Reconciler 가 이 인덱스 + 기존 인덱스 두 분기를 OR 조회 시 MySQL optimizer 가 인덱스 merge 또는 두 쿼리 분리 결정 — fixed-delay 5초 polling 부하 증가 가능**.
  - **evidence**: `V1__payment_schema.sql:26` (기존 인덱스), Round 3 신규 인덱스 line 196-198, Round 3 별 메서드 결정 line 158 (`scanStockCompensationPending()`).
  - **suggestion**: 별 메서드 (Round 3 결정) 가 별 쿼리로 두 분기 격리 — 인덱스 분리 사용 명시. Round 4 통합 테스트에 EXPLAIN 검증 추가.

- **A4-3 [MAJOR]**: compensation_state_version 컬럼이 모든 PaymentEvent update 경로에 적용된다 (Round 3 line 213) — 즉 **정상 결제의 done / fail / quarantine / resetToReady / toRetrying / execute / expire 모든 도메인 메서드가 version increment 영향**. JPA `@Version` 어노테이션 도입 시 OptimisticLockingException 이 정상 결제의 동시 update 경로 (예: PG 결과 + reconciler timeout 동시) 에서 트리거 가능. **Round 3 의 "happy path latency 영향 0 가드 유지" 주장 (line 213) 이 회귀 surface 정량화 안 됨**.
  - **evidence**: `PaymentEvent.java:97-186` 모든 도메인 메서드 + Round 3 결정 line 167.
  - **suggestion**: Round 5 진입 전 정상 결제 회귀 테스트 surface 정량화 — `PaymentEventTest`, `PaymentCommandUseCaseTest`, `PaymentConfirmResultUseCaseTest` 의 모든 기존 테스트가 version 증가 검증 추가 필요. plan 단계에서 task 분리.

- **A4-4 [MINOR]**: dedupe lease P5M 만료 + Reconciler stock-compensation-fixed-delay 5초 동시 도달 race (Round 2 FA-1 의 잔존 약점) — Round 3 가 compensation_state_version 으로 강한 차단했지만 **lease 만료 후 같은 메시지 재처리 + Reconciler 픽업 동시 진입 시 두 워커 모두 compensation_state_version=N 읽어 한 쪽이 OptimisticLockingException 처리 운영 noise**. Round 3 line 158 의 단일 reconciler 인스턴스 결정이 이 race 를 부분 회피.
  - **evidence**: Round 3 line 211-213, EventDedupeStore P5M 의미 (Round 2 critic line 13).
  - **suggestion**: 운영 알람 임계 — OptimisticLockingException 발생 시 메트릭 + 임계 이상 시 알람.

### 작업량 / 회귀 surface 검증

| 항목 | Round 3 추정 | Critic 검증 | 사유 |
|---|---|---|---|
| Flyway V file 수 | 1 | **1 (정확)** | compensation_status / compensation_retry_count / compensation_state_version 3 컬럼 + 인덱스 1 단일 V file. `V1__payment_schema.sql` 와 정합. |
| 신규 도메인 메서드 수 | 3 (markCompensationPending/Done/Failed) | **3 (정확)** | status 전이 없음 명시 정확. 단 PaymentEvent.java 에 추가될 메서드라 PaymentCommandUseCase 에도 wrapper 메서드 추가 필요 — Round 3 미명시. |
| 신규 application 빈 메서드 수 | 미정 | **추가 3 (Critic 발굴)** | PaymentCommandUseCase.markCompensationPending/Done/Failed wrapper 메서드 — 도메인 메서드를 호출하고 saveOrUpdate 하는 application 책임. Round 3 line 173 catch 가 직접 `paymentEvent.markCompensationPending` 부르면 saveOrUpdate 누가 부르는가 모호. |
| 신규 repository 메서드 수 | 1 (findInCompensationPendingOlderThan) | **1 (정확)** | |
| Reconciler 변경 | 별 메서드 + 별 fixed-delay 키 | **정확** | scanStockCompensationPending 단일 reconciler 인스턴스 결정 합리. |
| 신규 단위 테스트 수 | 5 | **5+ (Critic 검증)** | A4-3 의 정상 결제 회귀 테스트 추가 필요 시 +N 건. |
| 신규 통합 테스트 수 | 3 | **4+ (Critic 검증)** | A4-1 의 process crash 시뮬레이션 통합 테스트 추가 필수. |
| 신규 application.yml 키 | 2 (`reconciler.stock-compensation-fixed-delay-ms`, `scheduler.thread-pool-size`) | **2 (정확)** | |
| 신규 EventType 수 | 3 (PENDING_MARKED / RECOVERED / RDB_FAIL) | **3 (정확)** | |
| 신규 Counter 수 | 2 (compensation_pending_total / compensation_recovered_total) | **3 (Critic 보강)** | OptimisticLockingException Counter 추가 필요 (A4-4). |

**작업량 종합**: Round 3 추정 "작음~중간" 은 NULL-edge race (A4-1) + JPA `@Version` 회귀 surface (A4-3) 보강 시 **중간** 으로 상향. Candidate B 의 "중간~큼" 보다는 여전히 작음.

### happy path 영향 0 가드 검증

- **결과**: Round 3 의 "정상 결제 처리 latency 영향 0" 주장 (line 211-213) 은 **부분 위협** — A4-3 의 JPA `@Version` 도입이 정상 결제 update 경로 모두에 영향. 회귀 surface = JPA mapping 1곳 + 모든 도메인 메서드 호출 + Reconciler version 가드. Round 3 가 "JPA mapping 1곳 + Reconciler version 가드 1곳" 으로 surface 추정했으나 **PaymentCommandUseCase 의 saveOrUpdate 호출 (8회 — `executePayment`/`markPaymentAsDone`/`markPaymentAsFail`/`expirePayment`/`markPaymentAsRetrying`/`markPaymentAsQuarantined` 등) 모두 version increment 영향**. 단 latency 영향은 micro-second 수준으로 받아들일 가능성 높음 — 측정 필요.

---

## Candidate B (refined) — Deep Validation

### Round 3 흡수 정직성

| Finding ID (Round 2) | Round 3 흡수 주장 | Critic 평가 | 사유 |
|---|---|---|---|
| FB-1 / DB-3 | online DDL 가이드 (`ALGORITHM=INPLACE, LOCK=NONE`) + 기존 row backfill | **정직** | MySQL 8 InnoDB 가정 정확. 단 운영 배포 트래픽 저점 권장 명시는 plan 단계로 미룸 |
| FB-2 (major) | Strategy refactoring 회귀 surface 정량화 + happy path 영향 0 가드 검증 절 | **부분 흡수** | 4 layer 정량화 부분 흡수 — Critic 검증 결과 **6 layer 로 확장** (아래 B4-1) |
| FB-3 (major) | SETNX 토큰 위치 INCR 직전 + race window 정직 인정 | **정직** | race window 인정 + 토큰 TTL 만료 회복 결정. 단 race window 의 운영 영향 정량화는 Round 4 도메인 검증 권고로 이관 |
| FB-4 (minor) | 동적 조회 결정 (payload 컬럼 미도입) | **정직** | PaymentEvent 동적 조회 결정 합리 |
| FB-5 (minor) | 두 entry point dispatch 명시 + AFTER_COMMIT STOCK_COMPENSATE 도 적용 + available_at = now + 5초 지연 | **정직** | 합리. 단 즉시 발행 dispatch 시 시간 가드 검증은 Round 4 도메인 |
| DB-1 (major) | 도메인 메서드 IN_FLIGHT 단일 가드 그대로 + admin 도구 type-aware 분류 | **부분 흡수** | 도메인 변경 0 정직. **단 admin 도구 변경은 본 토픽 범위 외로 미룸 — 운영 가이드 부재 시 admin 처리 confusion 위험** |
| DB-2 (major) | admin reset SoP 토큰 삭제 단계 추가 | **정직** | redis-cli DEL 단계 명시 |
| DB-4 (major) | 본 토픽 단독 결정은 events.confirmed catch 1개. 다른 두 silent loss 경로 별 토픽 미루기 | **정직** | 명시적 미루기 합리. 단 일반화 가능성은 Round 5 사용자 입력 |
| Round 3 신규 발굴 — claimToInFlight 시그니처 | claimToInFlightByOutboxId 변경 + 모든 호출자 + PaymentConfirmEvent payload 변경 | **부분 흡수** | 호출자 surface 추정이 작음 — Critic 검증 결과 **6 layer + 통합 테스트 다수 + payment_outbox 의 PK 기반 잠금 의미 변경** (아래 B4-1) |

**Round 3 흡수 종합 평가**: 9건 중 6건 정직 / 3건 부분 흡수. 부분 흡수 3건이 모두 happy path 영향 0 가드 위협 영역.

### Round 3 신규 위험

- **B4-1 [CRITICAL]**: claimToInFlight 시그니처 변경의 회귀 surface 가 Round 3 추정 "4 layer" (handler / worker / relay / metric) 를 넘어 **6 layer + 호출자 다수**.
  - **호출 surface 정량화** (grep 검증):
    1. `PaymentOutboxRepository.java:18` — port 인터페이스
    2. `PaymentOutboxRepositoryImpl.java:54-55` — 구현체
    3. `JpaPaymentOutboxRepository.java:24-30` — JPA `@Modifying UPDATE` 쿼리 변경
    4. `PaymentOutboxUseCase.java:37-38` — application 빈 wrapper
    5. `OutboxRelayService.java:49-50, 54` — relay(orderId) 시그니처 변경
    6. `OutboxImmediateEventHandler.java:38` — `relay(event.getOrderId())` 호출
    7. `OutboxWorker.java:49, 58` — 두 호출 분기 (parallel + serial)
    8. `OutboxImmediatePublisher.java:18` — `PaymentConfirmEvent.of(orderId, ...)` 시그니처 변경
    9. `PaymentConfirmEvent.java:8-19` — record/value class 자체 변경
  - **테스트 surface**: `OutboxRelayServiceTest.java:55, 79, 117, 154-156`, `PaymentOutboxUseCaseTest.java:65-95`, `OutboxPendingAgeMetricsTest.java:124`, `PaymentOutboxMetricsTest.java:126` — 6개 파일 + 통합 테스트 다수.
  - **evidence**: 위 grep 결과 라인 모두 직접 확인.
  - **suggestion**: Round 5 진입 전 회귀 테스트 surface 정량화 필수. 본 토픽 단독 결정으로 본 surface 를 받아들이는 무게는 사용자에게 명시적 confirm 필요.

- **B4-2 [MAJOR]**: PaymentConfirmEvent.payload 변경 — Round 3 line 337 의 "PaymentConfirmEvent payload 변경 (orderId → outboxId)" 결정이 **AFTER_COMMIT 리스너 흐름의 도메인 이벤트 식별자 변경**. PaymentConfirmEvent 는 `domain.event` 패키지의 `@Value` immutable record 라 변경 무게가 도메인 layer 진입.
  - **evidence**: `PaymentConfirmEvent.java:8-19` (`@Value` + 4 필드).
  - **suggestion**: 별 이벤트 (`PaymentOutboxReadyEvent` 같은) 신설로 PaymentConfirmEvent 변경 회피 — 단 entry point dispatch 분기 하나 추가.

- **B4-3 [MAJOR]**: Strategy 패턴 OutboxRelayService 의 latency overhead — `OutboxRelayService.relay(orderId)` (line 49-82) 가 Strategy 인젝션 + type 분기 + handler.handle() 호출로 변경되면 happy path (CONFIRM_COMMAND) 도 분기 진입. Round 3 추정 "PK 기반 update 가 더 빠름 — latency 개선 가능성" (line 384) 은 낙관 — Strategy dispatch 의 reflection / proxy overhead 무시.
  - **evidence**: `OutboxRelayService.java:49-82` 4단계 직선 흐름 + Round 3 line 282 Strategy 결정.
  - **suggestion**: happy path 회귀 테스트에 latency 측정 (k6 confirm 시나리오).

- **B4-4 [MAJOR]**: payment_outbox UNIQUE 제약 변경의 운영 lock 비용 — Round 3 line 358-364 가 `ALGORITHM=INPLACE, LOCK=NONE` 으로 online DDL 가정. 단 **MySQL 8 InnoDB 의 ADD UNIQUE INDEX 는 ALGORITHM=INPLACE 가능하지만 DROP INDEX + ADD UNIQUE 의 두 단계 ALTER 는 atomic 아님** — 두 ALTER 사이 윈도우에서 UNIQUE 제약 부재 → race 진입 가능.
  - **evidence**: Round 3 SQL line 361-365 (`DROP INDEX uk_payment_outbox_order_id, ADD UNIQUE INDEX uk_payment_outbox_order_msgtype_dedup`).
  - **suggestion**: 단일 ALTER TABLE 안에 DROP + ADD 묶기 (MySQL 8 지원 — 검증 필요). 또는 신규 UNIQUE 추가 후 기존 DROP — 단 신규 (order_id, message_type, dedup_key) 가 기존 (order_id) 단독 동등 의미 (dedup_key NULL) 라 별 UNIQUE 동시 보유 가능.

- **B4-5 [MINOR]**: SETNX 토큰 race window — Round 3 line 76 가 인정한 "SETNX 직후 + INCR 직전 워커 크래시 → 토큰만 살고 INCR 미발생" 케이스의 회복 latency = 토큰 TTL (`outbox.in-flight-timeout` 정렬). 운영 측 관점에서 **이 case 가 발생 빈도 연 1~2회 수준이면 받아들일만 한가** 정량 분석 부재.
  - **evidence**: Round 3 line 76 + line 339.
  - **suggestion**: 운영 알람 메트릭 (setnx_race_window_total Counter — Round 3 line 402 명시됨) 임계 plan 단계에서 결정.

### 작업량 / 회귀 surface 검증

| 항목 | Round 3 추정 | Critic 검증 | 사유 |
|---|---|---|---|
| Flyway V file 수 | 1 (online DDL 명시) | **1 (정확하지만 두 단계 ALTER 의 atomic 보장 검증 필요)** | B4-4 |
| 신규 enum 수 | 1 (PaymentOutboxMessageType) | 1 | |
| 신규 도메인 메서드 수 | 1 (createCompensation) | 1 | |
| claimToInFlight 시그니처 변경 | 명시 | **6 layer (Critic 검증)** | B4-1 |
| OutboxRelayService 변경 | 4 layer | **6 layer + 도메인 이벤트** | B4-1, B4-2 |
| 신규 단위 테스트 수 | 6 | **6+ + 기존 6개 파일 회귀** | B4-1 의 호출자 6개 파일 모두 테스트 변경 |
| 신규 통합 테스트 수 | 4 + 기존 happy path 회귀 | **5+** | B4-3 의 happy path latency 측정 추가 |
| 신규 application.yml 키 | 2 | 2 | |
| 신규 EventType 수 | 3 | 3 | |
| 신규 Counter 수 | 3 | 3 | |

**작업량 종합**: Round 3 추정 "중간~큼" 은 B4-1 의 6 layer + 도메인 이벤트 변경 + B4-3 happy path 회귀 보강 시 **큼** 으로 상향. 3 candidate 중 가장 큼.

### happy path 영향 0 가드 검증

- **결과**: **위협 — 가드 무결성 흔들림**. Round 3 가 line 384 에서 "happy path latency 영향 측정 필수" 로 인정한 위협이 Critic 검증 결과 6 layer + PaymentConfirmEvent 도메인 이벤트 + Strategy dispatch overhead 까지 surface 확장. 본 토픽 핵심 가드 (정상 결제 처리에 회복 layer 가 영향 0) 와 정면 충돌. **Round 5 사용자 결정 시 이 trade-off 명시적 confirm 필요**.

---

## Candidate D (refined) — Deep Validation

### Round 3 흡수 정직성

| Finding ID (Round 2) | Round 3 흡수 주장 | Critic 평가 | 사유 |
|---|---|---|---|
| FD-1 / DD-1 (major) | 옵션 D2 채택 — 별 Aspect 신설 (`StockCompensationLoggingAspect`) + 기존 `DomainEventLoggingAspect` 변경 0 + PITFALLS #1 회피 | **흡수 실패 (CRITICAL)** | 옵션 D2 의 핵심 가정 (PaymentEvent 도메인 메서드에 별 Aspect 적용) 이 Spring AOP 기술적으로 작동 불가 — 아래 D4-1 |
| FD-2 (major) | action 화이트리스트 운영 가이드 (3종) | **정직** | 합리적 |
| FD-3 / DD-2 (major) | PaymentEvent.compensation_state_version 컬럼 추가 (Candidate A 동일) + Candidate A 와 schema 변경 비용 동일 정직 인정 | **정직** | "차별점은 회복 SoT (audit row vs PaymentEvent.compensation_status)" line 567 정직 명시. 단 Candidate D 본질 약화 |
| FD-4 (minor) | current_status NOT NULL 유지 + STOCK_COMPENSATE_FAILED 행에 PaymentEvent.status 값 채움 | **정직** | 합리적 |
| FD-5 (minor) | (action, created_at) + (order_id, action) 인덱스 추가 + cutoff 24h | **정직** | NOT EXISTS 부하 완화 |
| DD-3 (major) | `markStockCompensateFailed(PaymentEvent, productId, quantity, reasonCode)` 시그니처. 별 Aspect 가 args 직접 추출 | **흡수 실패** | D4-1 와 같은 이유 — PaymentEvent 도메인 메서드에 AOP 적용 불가 |
| DD-4 (major) | payment_history.action / dedup_key + 기존 row backfill | **정직** | 합리적 |
| DD-5 (minor) | (action, created_at) 인덱스 + cutoff 24h | **정직** | |

**Round 3 흡수 종합 평가**: 8건 중 6건 정직 / 2건 (FD-1, DD-3) 흡수 실패 — 실패한 2건이 candidate 의 핵심 결정.

### Round 3 신규 위험

- **D4-1 [CRITICAL]**: 옵션 D2 의 핵심 가정 — `paymentEvent.markStockCompensateFailed` 가 PaymentEvent (plain POJO) 의 도메인 메서드이고 별 Aspect (`StockCompensationLoggingAspect`) 가 `@Around("@annotation(...)")` 으로 가로챈다 — **Spring AOP 의 proxy-based 가로채기는 plain POJO 메서드 호출에 작동하지 않는다**. 현재 `@PublishDomainEvent` 가 적용된 곳은 모두 application 빈 (`PaymentCommandUseCase` line 28/37/46/55/64/73 + `PaymentCreateUseCase.java:29`). PaymentEvent 자체는 `@Component/@Service` 없는 plain `@Builder` 클래스 (PaymentEvent.java:22).
  - **결과**: Round 3 결정안 (옵션 D2) 채택 시 별 Aspect 가 발화 안 함 → payment_history INSERT 0 → 회복 워커가 polling 할 데이터 없음 → 회복 layer 자체가 작동 안 함.
  - **evidence**: `PaymentEvent.java:22` (no Spring annotation), `PaymentCommandUseCase.java:28-73` (모든 @PublishDomainEvent 적용 메서드는 application 빈), `DomainEventLoggingAspect.java:34-54` (`@Around("@annotation(publishEvent)")` proxy-based).
  - **suggestion**: 옵션 D2 폐기. 두 가지 대안:
    - 대안 1: `markStockCompensateFailed/Recovered` 를 `PaymentCommandUseCase` (application 빈) 메서드로 이동. PaymentEvent 의 도메인 메서드는 status 전이 없는 setter (예: `paymentEvent.markCompensationFailed(productId, quantity, reasonCode)`) 로 단순 mutation 만 하고, application 빈이 saveOrUpdate + AOP 발화 책임. → 옵션 D1 회귀 (FD-1 의 5 layer 변경) 또는 그 변형.
    - 대안 2: AspectJ load-time/compile-time weaving 도입 — 본 프로젝트 인프라에 미존재. plan 작업량 폭증.

- **D4-2 [MAJOR]**: payment_history audit table size 폭증 — 보상 5회 retry × 모든 결제 실패 케이스 시 history 행이 결제당 6+ 행 (FAILED 5회 + RECOVERED 1회). 본 토픽이 정상 결제 실패율 X% 가정 시 history INSERT 부하 = X% × 6배. 운영 측 audit table 가시성 부담.
  - **evidence**: Round 3 line 489-490, `PaymentHistory` 인덱스 V1 schema line 96-97 (이미 `(payment_event_id)` + `(created_at)` 만).
  - **suggestion**: history table partitioning 또는 retention 정책 plan 단계 결정.

- **D4-3 [MAJOR]**: audit row count 기반 retry 카운트는 RDB scan 비용. Round 3 가 `(order_id, action)` 인덱스 추가 (line 612-614) 했지만 **결제 단위 회복 상태 산출 쿼리 = `SELECT COUNT(*) FROM payment_history WHERE order_id=? AND action=?` 가 회복 retry 매번 실행**. 컬럼 1개 (compensation_retry_count) 로 표현하는 Candidate A 보다 무거움.
  - **evidence**: Round 3 line 489-490 + Round 2 critic FD-5 line 469.
  - **suggestion**: Candidate A 의 compensation_retry_count 도입 시 본 비용 회피 가능 — 단 그러면 Candidate D 의 "audit-driven retry count = 행 수" 본질이 약화 → Candidate A 와 사실상 합쳐짐 (Round 3 line 567 정직 인정한 "schema 변경 비용 동일" 의 연장).

- **D4-4 [MAJOR]**: payment_history.action 화이트리스트 운영 가이드 (Round 3 D-D2'' line 592) 의 enforcement — 운영자가 임의 action 추가 시 (예: `STOCK_COMPENSATE_ABANDONED` 또는 다른 도메인 이벤트) audit row count 가 회복 결정에 영향. **DB-level CHECK 제약 또는 ENUM 변환 필요** — Round 3 미명시.
  - **evidence**: Round 3 line 592 (화이트리스트 결정만 있고 enforcement 안 정의).
  - **suggestion**: payment_history.action 컬럼에 DB CHECK 제약 또는 application layer 의 enum 강제 + 검증 메서드.

- **D4-5 [MINOR]**: 두 Aspect (`DomainEventLoggingAspect` / `StockCompensationLoggingAspect`) 의 @Around 중첩 — Round 3 line 636 에서 인정한 위험. 만약 옵션 D2 가 D4-1 의 이유로 옵션 D1 (기존 Aspect 보강) 으로 회귀 시 본 위험은 자동 해소.
  - **evidence**: Round 3 line 636.
  - **suggestion**: D4-1 의 옵션 D2 폐기와 함께 자동 해소.

### 작업량 / 회귀 surface 검증

| 항목 | Round 3 추정 | Critic 검증 | 사유 |
|---|---|---|---|
| Flyway V file 수 | 2 | **2 (정확)** | payment_history ALTER + payment_event compensation_state_version |
| 신규 enum 항목 수 | 2 | 2 | |
| 신규 도메인 메서드 수 | 2 | **0 (D4-1)** | PaymentEvent 도메인 메서드 추가 가정 무효. application 빈 메서드로 이동 시 +2 |
| 신규 application 빈 메서드 수 | 0 | **+2 (Critic 발굴)** | D4-1 의 대안 1 채택 시 PaymentCommandUseCase 에 markStockCompensateFailed/Recovered wrapper |
| 신규 Aspect 수 | 1 (옵션 D2) | **0 또는 1** | 옵션 D2 폐기 시 0 (옵션 D1 = 기존 Aspect 분기 추가) 또는 application layer 별 Aspect 1 |
| 신규 어노테이션 수 | 1 (`@PublishStockCompensationEvent`) | **0 또는 1** | D4-1 폐기 후 옵션 D1 이면 기존 `@PublishDomainEvent(action="...")` 확장. 어노테이션 신설 0 — 단 Aspect switch 분기 + Publisher 메서드 + Service 분기 5 layer 모두 영향 (FD-1 회귀) |
| 신규 repository 메서드 수 | 1 (findUnrecoveredCompensations) | 1 | |
| 신규 reconciler 인스턴스 | 1 (PaymentHistoryCompensationReconciler) | 1 | |
| 신규 단위 테스트 수 | 5 | **5 (옵션 변경 시 재정의 필요)** | |
| 신규 통합 테스트 수 | 4 | 4 | |
| 신규 application.yml 키 | 2 | 2 | |

**작업량 종합**: Round 3 추정 "중간~큼" 은 D4-1 의 옵션 D2 무효화 후 옵션 D1 회귀 시 **큼** 으로 상향 — 5 layer 변경 (어노테이션 / Aspect / Publisher / Service / schema) 부활. Candidate B 와 동등.

### happy path 영향 0 가드 검증

- **결과**: Round 3 의 옵션 D2 채택 + "기존 `DomainEventLoggingAspect` 변경 0" 주장 (line 634) 이 D4-1 으로 무효화. 옵션 D1 (기존 Aspect 보강) 으로 회귀 시 happy path AOP 에 신규 action 분기 추가 — 기존 created/retry/changed 3종 switch 에 stock_compensate_failed/recovered 추가. happy path 영향은 default branch warn 으로 떨어지므로 **부분 fit** (action 분기 추가 자체는 happy path latency 영향 0 이지만 Aspect 변경 회귀 surface 가 광범위).

---

## 3 Candidate 비교 매트릭스 (Critic Round 4 관점)

| 축 | A (refined) | B (refined) | D (refined) |
|---|---|---|---|
| 회귀 surface | **중간** — JPA `@Version` 정상 결제 update 경로 모두 영향 (8개 도메인 메서드) | **큼** — claimToInFlight 6 layer + PaymentConfirmEvent 도메인 이벤트 + Strategy dispatch + 6개 파일 테스트 | **큼** — 옵션 D2 무효화 후 옵션 D1 회귀 시 5 layer (어노테이션/Aspect/Publisher/Service/schema) 모두 영향 |
| 작업량 | 중간 (Round 3 추정 "작음~중간" 에서 A4-1/A4-3 보강 후) | 큼 (Round 3 추정 "중간~큼" 에서 B4-1 6 layer 보강 후) | 큼 (Round 3 추정 "중간~큼" 에서 D4-1 옵션 폐기 후 옵션 D1 회귀) |
| 운영 mental model | **1트랙 + α** — PaymentEvent.status (terminal) + compensation_status 분리. 운영자가 PaymentEvent 한 곳만 보면 됨 | 1트랙 — payment_outbox 한 테이블만 보면 됨. 단 type-aware admin 도구 변경 부담 | 1.5트랙 — payment_history audit + PaymentEvent.compensation_state_version 두 곳 가시화. 회복 SoT 분산 |
| happy path 영향 0 정직성 | **부분 fit** — JPA `@Version` 도입이 정상 결제 update 경로 모두 영향. 측정 시 micro-second 수준 추정 | **위협** — 6 layer 변경 surface 가 본 토픽 핵심 가드와 정면 충돌. Round 3 자체도 위협 인정 | **부분 fit** — 옵션 D2 폐기 후 옵션 D1 회귀 시 happy path AOP default branch 영향 0 (action 분기만 추가) |
| 일반화 가능성 | **partial → break** — PaymentEvent IN_PROGRESS 진입 직전 / D12 가드 안 시점에서 compensation_status 도입 위험. 다른 두 silent loss 경로 (OutboxAsyncConfirmService / PaymentTransactionCoordinator) 일반화 불가 | **fit** — 같은 payment_outbox 작업 큐로 자연 일반화. Phase2 토픽에서 같은 모델 재사용 가능 | **fit** — payment_history audit 패턴으로 자연 일반화. Phase2 토픽에서 같은 모델 재사용 가능 |
| 가장 위험한 부분 | **A4-1 [CRITICAL]** — markPaymentAsFail 직후 process crash 시 compensation_status=NULL terminal 잔존 → silent under-restore 신규 경로 | **B4-1 [CRITICAL]** — claimToInFlight 시그니처 변경의 6 layer 회귀 surface 가 happy path 영향 0 가드와 정면 충돌 | **D4-1 [CRITICAL]** — 옵션 D2 의 PaymentEvent 도메인 메서드 + AOP 결합이 Spring AOP 기술적으로 작동 불가 |

---

## Round 5 권고

### 사용자에게 추천 1순위 (Critic 관점): **Candidate A (refined)**

근거:
- **회귀 surface 가 가장 작음** — JPA `@Version` 1곳 + 도메인 메서드 8개 영향 (B4-1 의 6 layer 호출자 변경 대비 surface 응축).
- **happy path 영향 0 가드 무결성이 가장 안전** — A4-3 의 latency 영향은 micro-second 수준 추정. B4-1 의 surface 와 비교 불가.
- **운영 mental model 가장 단순** — 1트랙 + compensation_status 단일 컬럼. D 의 audit row count 기반 retry vs A 의 compensation_retry_count 컬럼 비교 시 A 가 가시성 우위.
- **사용자 신호 (a/b/c/d) 4개 모두 정직 회피** — Round 1 부터 일관.

**채택 전 해소 필수 critical**: A4-1 (markPaymentAsFail 직후 process crash + compensation_status=NULL terminal). 해소 안 시 silent under-restore 신규 경로. plan 단계 또는 Round 5 사용자 결정 직전 보강안 결정 필요 — 권고: markPaymentAsFail 메서드 안에서 compensation_status 동시 설정 (single TX 묶음).

**채택 시 trade-off 정직 인정**: 일반화 가능성 partial → break (다른 두 silent loss 경로 별 토픽 PHASE2 에서 다른 모델 도입 필요).

### 사용자에게 추천 2순위: **Candidate D (refined) — 옵션 재논의 조건부**

근거:
- **일반화 가능성 fit** — payment_history audit 패턴이 다른 두 silent loss 경로 (OutboxAsyncConfirmService / PaymentTransactionCoordinator) 에 자연 적용. Phase2 토픽 봉인 시 동일 모델 재사용.
- **append-only audit retry count = 행 수** 발상이 도메인적으로 신선. 운영 audit 체인에 결제/보상 모두 한 곳.

**채택 전 해소 필수 critical**: D4-1 (옵션 D2 무효). 해소 방안:
- 대안 A: 옵션 D1 회귀 (기존 Aspect 보강) — Round 2 FD-1 의 5 layer 변경 다시 살아남.
- 대안 B: PaymentCommandUseCase 에 markStockCompensateFailed/Recovered wrapper 추가 (application 빈 메서드) — 도메인 메서드는 status 전이 없는 setter 만. AOP 가 application 빈 메서드 가로채기 OK.
- 권고: 대안 B (application 빈 wrapper). Round 5 사용자 결정 시 명시적 confirm.

**채택 시 trade-off 정직 인정**: PaymentEvent.compensation_state_version 도입 = Candidate A 와 schema 변경 비용 동일. 차별점은 "회복 SoT = audit row vs compensation_status 컬럼" — 운영자 가시성 trade-off만 남음.

### 추천하지 않는 후보 + 사유: **Candidate B (refined)**

근거:
- **happy path 영향 0 가드 정면 충돌** — claimToInFlight 6 layer + PaymentConfirmEvent 도메인 이벤트 + Strategy dispatch overhead. 본 토픽 핵심 가드와 trade-off 무게가 본 토픽 단독 결정 범위 초과.
- **outbox 의미 일반화의 도메인 결정 무게** — payment_outbox 가 작업 큐로 일반화되면 향후 다른 비동기 작업 (refund command 등) 도 자연 진입. 본 토픽 단독 결정으로 outbox 패턴 의미 변경.
- **회귀 surface 정량화 불완전** — Round 3 추정 "4 layer" 가 Critic 검증 결과 6 layer + 6개 테스트 파일. 본 토픽 핵심 가드 위협 정도가 Round 3 보다 큼.

단, 사용자가 (i) outbox 일반화 무게 (ii) 6 layer 회귀 surface 둘 다 받아들이면 Candidate B 가 일반화 fit + 5~25초 회복 latency 의 강점 살아남음 — 명시적 사용자 confirm 필요.

---

## decision

Round 5 진행 가부: **revise**

근거: critical 3건 (A4-1, B4-1, D4-1) 이 발견됨. 그러나 이는 후보 자체를 폐기시키는 critical 이 아니라 **각 candidate 의 핵심 결정에 보강안 (A: NULL-edge race 해소 / B: 6 layer surface 사용자 confirm / D: 옵션 D2 폐기 + 대안 채택)** 이 Round 5 진입 전에 결정돼야 한다. Round 5 가 사용자 1회 선택으로 수렴하려면 위 3건 보강안이 각 candidate refinement 에 반영돼 사용자에게 정확한 선택지를 제시해야 함. 따라서 Round 4 → Round 5 직진이 아니라 **Round 4.5 (Architect 추가 refine 또는 사용자에게 critical 3건 confirm 후 Round 5)** 권고.

Major finding 다수 (A4-2, A4-3, A4-4, B4-2, B4-3, B4-4, B4-5, D4-2, D4-3, D4-4) 는 plan 단계로 이관 가능 — 현 단계에서 candidate 비교에는 영향 없음.

---

## JSON

```json
{
  "stage": "discuss",
  "persona": "critic",
  "round": 4,
  "topic": "STOCK-COMPENSATION-RECOVERY-ALTERNATIVES",
  "decision": "revise",
  "findings": [
    { "id": "A4-1", "severity": "critical", "candidate": "A", "checklist_item": "흡수 정직성 — DA-1 / FA-4 부분 흡수", "location": "PaymentConfirmResultUseCase.java:266-268, ALTERNATIVES.md:171", "problem": "handleFailed 호출 순서 유지 (markPaymentAsFail → compensateStockCache) + 옵션 A2 의 compensation_status NULL 기본값 결합으로 markPaymentAsFail UPDATE 커밋 직후 process crash 시 status=FAILED + compensation_status=NULL terminal 잔존 → Reconciler 의 WHERE compensation_status='PENDING' 쿼리가 NULL 행 못 잡음 → 영구 silent under-restore 신규 경로", "evidence": "PaymentConfirmResultUseCase.java line 266-268 호출 순서 + ALTERNATIVES.md line 171 Reconciler 쿼리", "suggestion": "markPaymentAsFail 메서드 안에서 compensation_status 동시 설정 (single TX 묶음) 또는 Reconciler 쿼리에 status terminal + compensation_status NULL 분기 추가 (false-positive trade-off)" },
    { "id": "A4-2", "severity": "major", "candidate": "A", "checklist_item": "Reconciler scan 인덱스", "location": "ALTERNATIVES.md:196-198, V1__payment_schema.sql:26", "problem": "Reconciler scan 쿼리에 compensation_status='PENDING' OR 분기 추가 시 기존 idx_payment_event_status_last_changed (status, last_status_changed_at) 인덱스가 OR 분기에서 못 쓰임. 별 인덱스 추가했으나 OR 두 분기 fixed-delay 5초 polling 부하 증가 가능", "evidence": "V1 schema line 26 + Round 3 SQL line 196-198", "suggestion": "별 메서드 (Round 3 결정) 가 별 쿼리로 두 분기 격리 — Round 4 통합 테스트에 EXPLAIN 검증 추가" },
    { "id": "A4-3", "severity": "major", "candidate": "A", "checklist_item": "happy path 영향 0 가드", "location": "PaymentEvent.java:97-186, ALTERNATIVES.md:213", "problem": "compensation_state_version 컬럼이 모든 PaymentEvent update 경로 (8개 도메인 메서드) 에 영향. JPA @Version 도입 시 OptimisticLockingException 정상 결제 동시 update 경로에서 트리거 가능. Round 3 surface 추정 1+1곳이 실제 8+ 곳", "evidence": "PaymentEvent.java line 97-186 + Round 3 line 213", "suggestion": "정상 결제 회귀 테스트 surface 정량화 (PaymentEventTest, PaymentCommandUseCaseTest, PaymentConfirmResultUseCaseTest 모두). plan 단계 task 분리" },
    { "id": "A4-4", "severity": "minor", "candidate": "A", "checklist_item": "dedupe-Reconciler race", "location": "ALTERNATIVES.md:158, EventDedupeStore P5M", "problem": "dedupe lease P5M 만료 + Reconciler stock-compensation-fixed-delay 5초 동시 도달 race — compensation_state_version 강한 차단 후 운영 OptimisticLockingException 발생 가능", "evidence": "Round 3 line 158 + Round 2 critic line 13", "suggestion": "OptimisticLockingException 메트릭 + 운영 알람 임계 plan 단계 결정" },
    { "id": "B4-1", "severity": "critical", "candidate": "B", "checklist_item": "흡수 정직성 — claimToInFlight 시그니처 surface", "location": "PaymentOutboxRepository.java:18, PaymentOutboxRepositoryImpl.java:54-55, JpaPaymentOutboxRepository.java:24-30, PaymentOutboxUseCase.java:37-38, OutboxRelayService.java:49-82, OutboxImmediateEventHandler.java:38, OutboxWorker.java:49,58, OutboxImmediatePublisher.java:18, PaymentConfirmEvent.java:8-19", "problem": "claimToInFlight 시그니처 변경의 회귀 surface 가 Round 3 추정 4 layer 를 넘어 6 layer + 6개 파일 테스트 + 도메인 이벤트 (PaymentConfirmEvent) 자체 변경. 본 토픽 핵심 가드 (happy path 영향 0) 와 정면 충돌", "evidence": "9개 파일 grep 검증 결과 (호출자 6 layer + 도메인 이벤트 + 통합 테스트 다수)", "suggestion": "Round 5 진입 전 회귀 테스트 surface 정량화 필수. 본 토픽 단독 결정으로 본 surface 받아들이는 무게는 사용자에게 명시적 confirm 필요" },
    { "id": "B4-2", "severity": "major", "candidate": "B", "checklist_item": "도메인 이벤트 변경", "location": "PaymentConfirmEvent.java:8-19, ALTERNATIVES.md:337", "problem": "PaymentConfirmEvent.payload 변경 (orderId → outboxId) 가 domain.event 패키지 @Value immutable record 변경 = 도메인 layer 진입. 본 토픽 단독 결정 범위 초과", "evidence": "PaymentConfirmEvent.java line 8-19 + Round 3 line 337", "suggestion": "별 이벤트 (PaymentOutboxReadyEvent 등) 신설로 PaymentConfirmEvent 변경 회피" },
    { "id": "B4-3", "severity": "major", "candidate": "B", "checklist_item": "happy path latency", "location": "OutboxRelayService.java:49-82, ALTERNATIVES.md:384", "problem": "Strategy 패턴 도입의 happy path (CONFIRM_COMMAND) latency overhead 측정 부재. Round 3 'PK 기반 update 더 빠름' 주장 (line 384) 은 Strategy dispatch overhead 무시한 낙관", "evidence": "OutboxRelayService.java 4단계 직선 흐름 + Round 3 line 282/384", "suggestion": "happy path 회귀 테스트에 latency 측정 (k6 confirm 시나리오)" },
    { "id": "B4-4", "severity": "major", "candidate": "B", "checklist_item": "online DDL atomic 보장", "location": "ALTERNATIVES.md:361-365", "problem": "DROP INDEX uk_payment_outbox_order_id + ADD UNIQUE INDEX uk_payment_outbox_order_msgtype_dedup 두 단계 ALTER 의 atomic 보장 부재. 두 ALTER 사이 윈도우에서 UNIQUE 제약 부재 → race 진입 가능", "evidence": "Round 3 SQL line 361-365", "suggestion": "단일 ALTER TABLE 안에 DROP + ADD 묶기 (MySQL 8 지원 검증 필요) 또는 신규 UNIQUE 추가 후 기존 DROP 순서로 변경" },
    { "id": "B4-5", "severity": "minor", "candidate": "B", "checklist_item": "SETNX race window 운영 영향", "location": "ALTERNATIVES.md:76,339,402", "problem": "SETNX 직후 INCR 직전 워커 크래시 case 의 발생 빈도 정량 분석 부재. 운영 측 받아들일만 한가 검증 안 됨", "evidence": "Round 3 line 76/339/402", "suggestion": "setnx_race_window_total Counter 임계 plan 단계 결정" },
    { "id": "D4-1", "severity": "critical", "candidate": "D", "checklist_item": "흡수 정직성 — 옵션 D2 기술적 가능성", "location": "PaymentEvent.java:22, PaymentCommandUseCase.java:28-73, DomainEventLoggingAspect.java:34-54", "problem": "옵션 D2 의 핵심 가정 (PaymentEvent 도메인 메서드 + 별 Aspect @Around 가로채기) 이 Spring AOP proxy-based 가로채기 기술적으로 작동 불가. PaymentEvent 는 plain POJO (no @Component/@Service). 현재 @PublishDomainEvent 적용 모두 application 빈 메서드. 옵션 D2 채택 시 별 Aspect 발화 0 → payment_history INSERT 0 → 회복 layer 자체 작동 안 함", "evidence": "PaymentEvent.java line 22 (plain POJO) + PaymentCommandUseCase.java line 28-73 (모든 @PublishDomainEvent 적용 위치) + DomainEventLoggingAspect.java line 34-54 (proxy-based @Around)", "suggestion": "옵션 D2 폐기. 대안 1: markStockCompensateFailed/Recovered 를 PaymentCommandUseCase 메서드로 이동 + PaymentEvent 도메인 메서드는 status 전이 없는 setter 만. 대안 2: AspectJ load-time/compile-time weaving 도입 (인프라 미존재, 작업량 폭증). 권고: 대안 1" },
    { "id": "D4-2", "severity": "major", "candidate": "D", "checklist_item": "audit table size", "location": "ALTERNATIVES.md:489-490, V1__payment_schema.sql:96-97", "problem": "보상 5회 retry × 모든 결제 실패 시 history 행 결제당 6+ 행. 본 토픽 정상 결제 실패율 가정 시 history INSERT 부하 = X% × 6배. 운영 audit table 가시성 부담", "evidence": "Round 3 line 489-490 + V1 schema line 96-97 (인덱스 2개만)", "suggestion": "history table partitioning 또는 retention 정책 plan 단계 결정" },
    { "id": "D4-3", "severity": "major", "candidate": "D", "checklist_item": "retry count 산출 비용", "location": "ALTERNATIVES.md:489-490,612-614", "problem": "audit row count 기반 retry 카운트 = SELECT COUNT(*) WHERE order_id=? AND action=? 매번 실행. 컬럼 1개 (compensation_retry_count) 보다 무거움. Candidate A 와 schema 변경 비용 동일 인정 시 candidate D 본질 약화", "evidence": "Round 3 line 489-490 + Round 3 line 612-614 인덱스", "suggestion": "Candidate A 의 compensation_retry_count 도입 시 본 비용 회피 가능 — 단 Candidate A 와 사실상 합쳐짐" },
    { "id": "D4-4", "severity": "major", "candidate": "D", "checklist_item": "action 화이트리스트 enforcement", "location": "ALTERNATIVES.md:592", "problem": "payment_history.action 화이트리스트 운영 가이드 (3종) 의 enforcement 부재. 운영자 임의 action 추가 시 audit row count 가 회복 결정에 영향", "evidence": "Round 3 D-D2'' line 592", "suggestion": "DB-level CHECK 제약 또는 application layer enum 강제 + 검증 메서드" },
    { "id": "D4-5", "severity": "minor", "candidate": "D", "checklist_item": "Aspect 중첩", "location": "ALTERNATIVES.md:636", "problem": "두 Aspect (DomainEventLoggingAspect / StockCompensationLoggingAspect) 의 @Around 중첩 위험. 옵션 D2 폐기 시 자동 해소", "evidence": "Round 3 line 636", "suggestion": "D4-1 의 옵션 D2 폐기와 함께 자동 해소" }
  ],
  "scores": {
    "candidate_a_business_fit": "fit",
    "candidate_a_infra_dependency": "fit",
    "candidate_a_idempotency_safety": "partial_fit_with_critical_a4-1",
    "candidate_a_happy_path": "partial_fit",
    "candidate_a_recovery_latency": "5-25s_with_separate_reconciler_key",
    "candidate_a_workload": "medium",
    "candidate_a_critical_remaining": ["A4-1"],
    "candidate_b_business_fit": "partial",
    "candidate_b_infra_dependency": "partial",
    "candidate_b_idempotency_safety": "partial",
    "candidate_b_happy_path": "threat_critical_b4-1",
    "candidate_b_recovery_latency": "5-25s",
    "candidate_b_workload": "large",
    "candidate_b_critical_remaining": ["B4-1"],
    "candidate_d_business_fit": "partial",
    "candidate_d_infra_dependency": "partial",
    "candidate_d_idempotency_safety": "partial",
    "candidate_d_happy_path": "partial_fit_after_option_d2_drop",
    "candidate_d_recovery_latency": "5-25s",
    "candidate_d_workload": "large",
    "candidate_d_critical_remaining": ["D4-1"]
  },
  "round5_priority_recommendation": ["A", "D_with_option_revisit", "B"],
  "round5_user_decision_required": [
    "A 채택 시 A4-1 (NULL-edge silent under-restore) 보강안 결정",
    "B 채택 시 B4-1 (6 layer 회귀 surface) + outbox 일반화 무게 둘 다 confirm",
    "D 채택 시 D4-1 (옵션 D2 무효 → 옵션 D1 또는 application 빈 wrapper) 결정"
  ],
  "deep_validation_summary": {
    "round_3_absorption_honesty": "A=8/8 partial(2) / B=9/9 partial(3) / D=8/8 fail(2)",
    "round_3_new_risks_per_candidate": "A=4 (1 critical) / B=5 (1 critical) / D=5 (1 critical)",
    "happy_path_guard_integrity": "A=partial_fit / B=threat / D=partial_fit_after_option_drop"
  },
  "delta": "Round 3 가 흡수 매트릭스로 Round 2 finding 처리 정직 작성. 단 흡수 방식 자체가 도입한 critical 3건 (A4-1 / B4-1 / D4-1) 발굴. D4-1 은 candidate D 의 옵션 D2 핵심 가정 무효화 — Spring AOP 가 plain POJO 가로채기 불가. A4-1 은 process crash + compensation_status NULL 결합의 silent under-restore 신규 경로. B4-1 은 호출자 6 layer + 도메인 이벤트 변경의 happy path 가드 정면 충돌. Round 5 직진보다 Round 4.5 (보강 또는 사용자 critical 3건 confirm) 권고.",
  "unstuck_suggestion": "architect"
}
```
