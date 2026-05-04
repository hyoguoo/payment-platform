# Round 4 Domain Expert — Refined Candidate A/B/D Deep Validation

> 검증 대상: Round 3 refined Candidate A / B / D (`docs/topics/STOCK-COMPENSATION-RECOVERY-ALTERNATIVES.md` 145-230 / 311-405 / 559-653 + 종합 837-883)
> 검증자: Domain Expert (결제 도메인 리스크)
> 사전 읽기: INTEGRATIONS / CONFIRM-FLOW / PITFALLS + Round 2 Domain (`round-2-domain.md`) + Round 3 Architect (`round-3-architect.md`)
> 코드 사실: `payment-service` 소스 직접 Read (PaymentConfirmResultUseCase / PaymentEvent / PaymentEventStatus / PaymentOutbox / OutboxRelayService / DomainEventLoggingAspect / PaymentTransactionCoordinator / OutboxAsyncConfirmService / V1__payment_schema.sql / OutboxImmediateEventHandler / PaymentReconciler)

## 종합 verdict

Round 3 가 흡수했다고 주장하는 도메인 finding 들 중 **Candidate A 의 DA-1 (호출 순서 모순) 흡수가 가장 의심스럽다**. Round 3 는 옵션 A2 (compensation_status 별 컬럼) 로 호출 순서 유지를 정당화하지만, 이는 RA-1 의 표면을 "PaymentEvent.status 의 terminal 가드 위반" 에서 "PaymentEvent.compensation_status 가 markPaymentAsFail 후 markCompensationPending 사이의 process crash 윈도우에서 NULL 로 남는 silent under-restore" 로 **이동시킨 것일 뿐 제거하지 않았다**. handleFailed (`PaymentConfirmResultUseCase.java` 258-272) 의 두 호출이 동일 `@Transactional(timeout=5)` (line 120, 메서드 진입점) 안에 들어 있으므로 process crash 시 둘 다 rollback 된다는 점이 있긴 하지만, **Redis INCR 은 이미 발생했다** — RDB rollback 으로 compensation_status 가 NULL 로 되돌아가지만 Redis 측 일부 INCR 은 살아 있어 다음 events.confirmed 재배달 시 (extendLease 가 호출되지 않았으므로 가능) 또 INCR → silent over-restore. Round 3 는 이 시나리오에 대한 답이 없다.

세 candidate 의 silent loss 차단을 도메인 깊이로 줄세우면: **B 가 가장 두꺼운 차단 layer (claimToInFlight CAS + IN_FLIGHT timeout + SETNX 토큰)** 를 갖는다. B 의 한계는 SETNX-INCR atomic 묶음 부재 — Round 3 가 정직히 인정 — 이지만 손실의 형태는 silent over-restore 가 아니라 "토큰만 살고 INCR 이 일어나지 않은 상태가 토큰 TTL 동안 고정" (under-restore + 운영 false-positive FAILED) 이다. 즉 **돈이 새는 방향이 비대칭** — B 의 한계는 under, A 의 한계는 over (재고 발산). 도메인적으로 under 가 회복 가능 (admin reset + token DEL), over 는 발산 (RDB SoT 와 Redis 캐시 정합 깨짐) 이라 **B 가 한 단계 안전**하다. D 는 PaymentEvent.compensation_state_version 컬럼 도입으로 A 와 동일한 schema 변경 비용을 부담하면서 차별점은 "audit row 가 회복 SoT" 인 점만 남는다 — Round 3 가 이를 정직 인정. 하지만 D 의 진짜 도메인 위험은 별 Aspect (StockCompensationLoggingAspect) 가 try/catch 외부에서 호출되는 시점 — 본 검증의 새 finding.

다른 두 silent loss 경로 (`OutboxAsyncConfirmService.compensateStock` line 99-119 + `PaymentTransactionCoordinator.compensateStockCacheGuarded` line 168-180) 의 **일반화 가능성**: Round 3 가 A 를 partial→break, B/D 를 fit 으로 평가했다. 본 검증의 결론도 같다 — 단 A 의 break 이 더 본질적이다. A 는 `compensateStockCache` catch 안에서 `markCompensationPending` 호출 모델인데, `OutboxAsyncConfirmService.compensateStock` 은 confirm TX 실패 후 보상이라 PaymentEvent 가 아직 IN_PROGRESS 진입 전 시점이다 — compensation_status PENDING 마킹이 PaymentEvent 가 INSERT 되기도 전에 일어나야 할 수 있다. B 는 동일 outbox 작업 큐 모델로 확장만 하면 된다. **본 토픽 단독 결정에서는 B 가 가장 적합** — Round 5 의 1순위 권고.

## Candidate A (refined) — Domain Deep Validation

### Round 3 도메인 흡수 정직성

| Finding ID (Round 2 Domain) | Round 3 흡수 주장 | Domain Expert 평가 | 사유 |
|---|---|---|---|
| DA-1 / RA-1 | 옵션 A2 채택 — 호출 순서 유지 + compensation_status 별 컬럼. PaymentEvent.status terminal 가드 위반 0 | **부분 흡수** | terminal 가드 위반은 해소됨. 그러나 RA-1 의 본질적 race window — "보상이 markPaymentAsFail **이후** 라 보상 실패 시 결제 상태가 이미 terminal 이라 회복 불가" — 가 **새 형태로 이동**. A4-DA-1' (아래) 참고. handleFailed 호출 순서 유지의 도메인 부담을 표면적 enum 변경 0 으로 위장 |
| DA-2 (부분 보상 일관성 break) | PaymentOrder.compensated_at 컬럼 도입은 본 토픽 non-goal. 결제 단위 재순회 채택 — 알려진 한계 | **흡수 미흡** | 정직 인정이지만 **도메인적으로 받아들이기 어려운 손실 모델**. productA INCR 성공 + productB INCR 실패 시 다음 retry 가 productA 또 INCR (over-restore). compensation_state_version 가드는 두 워커 동시 픽업만 차단할 뿐, 같은 워커의 N항목 중 1항목만 실패 후 N항목 모두 재순회는 차단 못 함. 본 토픽 단독 결정으로 채택할 수 없는 부담 — Round 5 권고 핵심 |
| FA-1 / DA-3 (over-restore 강한 차단) | compensation_state_version 컬럼 + Reconciler version 가드 | 흡수 OK | JPA `@Version` 으로 구현 시 OptimisticLockingException 처리. **단 위 DA-2 의 PaymentOrder 단위 격리 부재가 이 가드를 무력화** — 같은 PaymentEvent 가 같은 version 으로 같은 워커 단일 호출 안에서 N항목 재순회하면 version 가드 통과 |
| DA-4 | 옵션 A2 가 새 enum 도입 안 함 → switch 변경 0 | 흡수 OK | 정확. compensation_status 가 별 컬럼이라 PaymentEventStatus enum 자체에 변경 없음 |
| DA-5 (회복 latency 60~300초) | 별 reconciler 키 (5000ms) + 별 메서드 + thread-pool-size 2 권장 | 흡수 OK | 단 본 토픽이 `reconciler.fixed-delay-ms:120000` (`PaymentReconciler.java` 44) 한 인스턴스에 두 fixed-delay 메서드를 같이 두면 Spring scheduler 풀 contention. thread-pool-size 권고는 합리적이지만 **운영 측정 deferred** — Round 5 사용자 결정 시 운영 부하 평가 필요 |

### Round 3 도메인 신규 위험

- **DA4-1 [BLOCKER]**: handleFailed (`PaymentConfirmResultUseCase.java` 258-272) 의 호출 순서 유지 + 옵션 A2 의 race window. handle 메서드는 `@Transactional(timeout=5)` (line 120) 안에서 실행되지만 두 단계 사이에 process crash 시 시나리오:
  - Step 1: `paymentCommandUseCase.markPaymentAsFail(paymentEvent, reasonCode)` (line 266) — TX 안에서 PaymentEvent.status=FAILED + last_status_changed_at 갱신
  - Step 2: `compensateStockCache` (line 268) — for 루프 내 `stockCachePort.increment` 호출 (Redis 측). 첫 INCR 성공 + 두 번째 INCR 직전 process crash
  - Step 3: TX rollback → PaymentEvent.status 가 FAILED 가 아닌 원래 (IN_PROGRESS / RETRYING) 로 되돌아감. compensation_status 도 NULL (markCompensationPending 호출 안 됨)
  - Step 4: `processMessageWithLeaseGuard` catch (line 140-144) → `eventDedupeStore.remove(message.eventUuid())` 호출 → Kafka 재배달 시 다시 처리 가능
  - Step 5: 재배달 후 handleFailed 재진입 → `stockCachePort.increment` 호출이 같은 productId 에 또 INCR → **silent over-restore**
  - 핵심 문제: Round 3 의 옵션 A2 가 도입한 compensation_state_version 가드는 RDB UPDATE 측 동시성만 차단. **Redis INCR 은 가드 밖**. PaymentOrder 단위 멱등성이 없으면 Kafka 재배달 한 번에 같은 결제의 같은 productId 가 INCR 두 번 가능
  - Round 3 는 이를 인지하지 못함. compensation_status PENDING 모델은 "한 번 마킹된 후 회복 워커가 차단" 패턴인데 마킹 자체가 일어나지 않은 (TX rollback) 윈도우의 회복이 부재

- **DA4-2 [MAJOR]**: terminal+compensation_status 분리 모델의 도메인 의미 모호 — `PaymentEvent.status=FAILED, compensation_status=PENDING` 상태에서 user 환불 요청이 들어오면? Round 3 가 답하지 않는다. 현재 코드에는 환불 도메인 메서드 자체가 부재 (CANCELED / PARTIAL_CANCELED enum 만 존재 — `PaymentEventStatus.java` 10-11) 라 가설적 이슈지만, terminal 인 PaymentEvent 가 "결제 실패는 끝났는데 재고는 회복 진행 중" 인 도메인 모델이 자연스러운가? 환불 요청은 status=FAILED 라 막혀야 하지만 운영자가 admin 도구로 강제 처리 시 compensation_status 와 어떻게 정합?

- **DA4-3 [MAJOR]**: compensation_status=DONE 도달 후 PaymentEvent 의 도메인 의미 — "결제 실패했지만 재고는 회복됐다" 가 status=FAILED + compensation_status=DONE 으로 표현됨. 운영자가 PaymentEvent 한 곳만 보면 됨이 Round 3 의 정당화 (1트랙 mental model) 인데, 실제로는 **두 컬럼을 같이 봐야 진짜 종결 의미를 안다** — 1.5 트랙. status=FAILED 만 보면 보상 진행 상황을 모름

- **DA4-4 [MAJOR]**: `isTerminal()` 가드 (`PaymentEventStatus.java` 21-26) 는 status 만 본다. status=FAILED 면 isTerminal=true. 다른 use case (예: PaymentReconciler.scan() 의 `findInProgressOlderThan`, `paymentEvent.fail` 의 `isTerminalStatus → return` 등) 가 isTerminal 만 체크하면 compensation_status 진행 중인데도 terminal 로 통과. 옵션 A2 가 isTerminal 의 의미를 오염시키는가? — 각 use case 별 가드 결정 필요. Round 3 가 명시 안 함

- **DA4-5 [MAJOR]**: 부분 보상 격리 미도입의 실질 부담 — DA-2 흡수 미흡 연장. compensation_status 의 의미가 한 결제 단위로만 정의되므로 N항목 중 1항목 실패 시:
  - case (a) 1항목 INCR 성공 + 1항목 INCR 실패 → compensation_status=PENDING. Reconciler 가 다시 N항목 모두 INCR 재시도 → 1항목 over-restore
  - case (b) 모두 INCR 성공 → compensation_status=DONE
  - case (c) 모두 INCR 실패 → compensation_status=PENDING. Reconciler 가 N항목 재시도
  - **(a) 의 over-restore 는 compensation_state_version 가드로 차단 안 됨** — 같은 워커가 같은 version 으로 N항목 재순회 시 모두 통과
  - PaymentOrder.compensated_at 컬럼 도입을 본 토픽 외로 미루면 본 candidate 의 도메인 안전성은 **partial 그대로** — Round 3 의 "fit 강화" 평가는 과대 평가

- **DA4-6 [MAJOR]**: D-A7' (RDB 다운 시 swallow + STOCK_COMPENSATION_RDB_FAIL Counter + events.confirmed 재배달 의존) 가 **이미 발생한 silent loss 자체를 무시**. 시나리오:
  - Step 1: handleFailed 진입 → markPaymentAsFail 성공 (RDB 정상)
  - Step 2: compensateStockCache catch → markCompensationPending 호출 직전 RDB 다운
  - Step 3: D-A7' 에 따라 swallow + Counter 증가 + 예외 무시
  - Step 4: handle 메서드 진행 → `extendLease(message.eventUuid(), longTtl)` (line 139) 호출 → P8D dedupe 잠금
  - Step 5: events.confirmed 재배달 시 dedupe 가 막음 → silent loss 영구
  - Round 3 의 "events.confirmed 재배달 의존" 은 dedupe 가 잠긴 상태에서는 **재배달 자체가 효과 없음**. PITFALLS #10 의 single-phase mark with long TTL 패턴 직격

### Silent loss 차단 layer 매트릭스

| 경로 | Candidate A 차단 layer | 도메인 안전성 |
|---|---|---|
| Silent over-restore (INCR 이중 호출) | compensation_state_version (RDB UPDATE 측 동시성). PaymentOrder 단위 가드 부재 → 동일 워커의 결제 단위 재순회 over-restore 차단 안 됨 | **partial** — DA4-1 / DA4-5 |
| Silent under-restore (보상 누락) | Reconciler 별 메서드 (compensation_status=PENDING + last_status_changed_at < cutoff) | partial — DA4-6 의 RDB 다운 + extendLease 시 영구 잠금 |
| 부분 보상 일관성 | 부재 — 결제 단위 재순회 채택. 알려진 한계 | **break** |
| 환불 race (status=FAILED + compensation_status=PENDING) | 부재 — Round 3 답 없음. 단 환불 도메인 메서드 자체 부재 (가설적) | n/a (현 코드) — 미래 위험 |

### 다른 보상 경로 일반화 가능성

- **OutboxAsyncConfirmService.compensateStock (`OutboxAsyncConfirmService.java` 99-119)** — confirm TX 실패 후 보상. PaymentEvent 가 IN_PROGRESS 진입 전 (executeConfirmTx rollback 으로 status 는 READY 유지). compensation_status 도입을 적용하려면 PaymentEvent 가 markCompensationPending 호출되어야 하는데 PaymentEvent 자체가 status=READY 상태에서 compensation_status=PENDING 으로 들어가면 도메인 의미 모순 (READY = 결제 시작 전, 그런데 보상 진행 중) — **일반화 break**
- **PaymentTransactionCoordinator.compensateStockCacheGuarded (`PaymentTransactionCoordinator.java` 168-180)** — D12 가드 안 (`executePaymentFailureCompensationWithOutbox` line 134-162). `outboxInFlight && eventCompensatable` 가드 통과 후 호출. eventCompensatable = `READY/IN_PROGRESS/RETRYING`. 이 시점에 markCompensationPending 호출 시 status 는 그대로 두고 compensation_status PENDING 마킹 — 가능. 단 이 use case 는 **markPaymentAsFail (`freshEvent`, failureReason)** 도 같이 호출 (line 161) — 호출 순서 문제 그대로. **일반화 partial**
- 본 토픽 단독에서 events.confirmed catch 만 다루면 일반화 break — Round 3 의 평가 ("partial → break") 와 일치

## Candidate B (refined) — Domain Deep Validation

### Round 3 도메인 흡수 정직성

| Finding ID (Round 2 Domain) | Round 3 흡수 주장 | Domain Expert 평가 | 사유 |
|---|---|---|---|
| DB-1 (PaymentOutbox 도메인 가드 분기) | IN_FLIGHT 단일 가드 그대로 유지. type 별 운영 priority 는 admin layer 흡수 | **흡수 OK** | `PaymentOutbox.java` 36-65 의 toInFlight/toDone/toFailed/incrementRetryCount 모두 IN_FLIGHT 단일 가드. STOCK_COMPENSATE 타입이 같은 lifecycle 따라가도 도메인 의미 충돌 없음. 단 toFailed 의 운영 의미 분기 (Kafka publish 영구 실패 vs Redis INCR 영구 실패 → 재고 발산) 가 admin 대시보드에서 제대로 구분되는지가 운영 검증 필요 |
| DB-2 (SETNX 토큰 admin reset) | admin reset SoP 에 토큰 수동 삭제 단계 추가 | **흡수 OK** | `redis-cli DEL compensation:{orderId}:{productId}` SoP 명시 |
| DB-3 (UNIQUE 제약 변경) | online DDL 명시 (`ALGORITHM=INPLACE, LOCK=NONE`) + backfill `('CONFIRM_COMMAND', NULL)` | 흡수 OK | MySQL 8 InnoDB 지원 가능. 단 운영 lock 시간 측정은 **deferred** |
| DB-4 (다른 보상 경로 일반화) | 본 토픽 = events.confirmed catch 1개. 다른 경로는 별 토픽 STOCK-COMPENSATION-RECOVERY-PHASE2 | **흡수 OK + 정직** | "단독 결정 범위 명시" 패턴은 도메인 mental model 깔끔하게 유지 |
| FB-3 (SETNX-INCR atomic 묶음 부재) | race window 정직 인정 — 토큰 TTL 만료 후 회복 | **부분 흡수** | 인정 자체는 정직. 단 race window 의 도메인 영향 (운영 false-positive FAILED 빈도) 정량화 필요 — Round 4 검증 포인트 (B-1 아래) |
| Round 3 신규 발굴 — claimToInFlight 시그니처 | claimToInFlightByOutboxId(outboxId, ...) + relay(outboxId) + PaymentConfirmEvent payload outboxId | **흡수 OK + Round 3 자체 발굴** | `PaymentOutboxRepository.java` 18 의 `claimToInFlight(orderId, inFlightAt)` 그대로 검증. `OutboxRelayService.relay(orderId)` (line 50) + `OutboxImmediateEventHandler.handle(PaymentConfirmEvent event)` 의 `event.getOrderId()` (line 38) + `PaymentConfirmEvent.of(orderId, userId, amount, paymentKey)` 4 인자 모두 변경 필요. 회귀 surface 정확하게 잡음 |

### Round 3 도메인 신규 위험

- **DB4-1 [MAJOR]**: SETNX-INCR race window 의 도메인 영향 정량 추정. 시나리오:
  - Step 1: `compensation:{orderId}:{productId}` SETNX 성공 (TTL = `outbox.in-flight-timeout`)
  - Step 2: INCR 호출 직전 워커 process crash
  - Step 3: 토큰 TTL 만료 전 (예: 5분 안) 다음 워커가 같은 outboxId row 픽업 시도 → claimToInFlight 가 IN_FLIGHT row 라 재선점 차단 (in-flight-timeout 까지 대기)
  - Step 4: in-flight-timeout 만료 → row PENDING 복귀 + 다음 워커 재선점 → SETNX 시도 → **이미 토큰 살아있음 → ALREADY_PROCESSED no-op + outbox.toDone**
  - Step 5: INCR 이 일어나지 않은 채 outbox=DONE 종결 → silent under-restore (재고 회복 안 됨, 결제는 FAILED 종결)
  - 빈도 추정: 워커 crash 시점이 SETNX 직후 + INCR 직전 1ms 미만 윈도우. 일반 운영 환경 (k8s pod kill / OOM) 에서 분당 0~1회 발생 가능. 토큰 TTL 5분 = in-flight-timeout 와 정렬해도 5분 이내 재선점 시 차단
  - **도메인 영향**: under-restore 는 admin reset (token DEL + outbox 재기동) 으로 회복 가능. 운영 noise 수준 — over-restore 보다 안전
  - 결론: 받아들일만한 race window. 단 **메트릭 (`setnx_already_processed_total`) 임계 정책** 명시 필요 (Round 5 plan 단계)

- **DB4-2 [MAJOR]**: payment_outbox 의 STOCK_COMPENSATE 메시지 타입이 IN_FLIGHT lifecycle 따라가면 publish 와 다른 의미 — 운영자 mental model 분기. CONFIRM_COMMAND IN_FLIGHT = "Kafka 발행 진행 중", STOCK_COMPENSATE IN_FLIGHT = "Redis INCR 진행 중". toFailed 도 의미 다름. Round 3 가 admin 도구 layer 에서 흡수한다고 답하지만, **PaymentOutbox 도메인 객체 자체가 두 의미를 같이 가진 점** 은 ubiquitous language 위반. 다만 단일 토픽 결정 안에서는 받아들일만한 trade-off — Round 5 의 도입 전 결정 필요 (도메인 ubiquitous language 정리)

- **DB4-3 [MAJOR]**: claimToInFlight 시그니처 변경의 PG vendor callback receive 경로 영향. `OutboxImmediateEventHandler.handle(PaymentConfirmEvent event)` (line 37-39) 가 `event.getOrderId()` → `event.getOutboxId()` 변경 필요. AFTER_COMMIT 리스너의 PaymentConfirmEvent payload 변경은 `PaymentTransactionCoordinator.executeConfirmTx` (line 82-93) 에서 `confirmPublisher.publish(orderId, ...)` 로 호출되는 시그니처도 변경. **회귀 surface = 4 layer (Repository / Service / Listener / Event payload) + happy path 통합 테스트 전부**. Round 3 의 "happy path 영향 0 가드 흔들 위험" 평가 정확

- **DB4-4 [MAJOR]**: PaymentConfirmEvent payload migration 정책. 기존 운영 환경에 outbox row 가 있는 상태에서 새 코드 배포 시:
  - 시점 t1: 신규 코드 배포 직전, PaymentConfirmEvent payload 가 orderId 인 ApplicationEvent 가 발화 → AFTER_COMMIT 리스너 처리 진행 중
  - 시점 t2: 신규 코드 배포 직후, ApplicationEvent 가 outboxId 로 변경됨 → 시점 t1 의 in-flight ApplicationEvent 는 **deserialize 실패** 또는 호출 시그니처 mismatch
  - Round 3 가 답 안 함. ApplicationEvent 는 in-memory + same-JVM 이라 round-trip 자체는 없지만, blue-green 배포 시 두 버전이 같이 떠 있을 때 한 JVM 의 publisher 가 다른 JVM 의 listener 에 닿진 않으므로 **실제 위험 0**. 단 Round 5 plan 단계 명시 필요

- **DB4-5 [MINOR]**: `payment_outbox.dedup_key` 컬럼 + UNIQUE 변경 후 기존 CONFIRM_COMMAND row 의 dedup_key=NULL 처리. MySQL UNIQUE 제약은 NULL 중복 허용이라 OK. 단 STOCK_COMPENSATE 행 전후 backfill 시 NULL 처리 정합 — Round 3 가 정확히 인정

### Silent loss 차단 layer 매트릭스

| 경로 | Candidate B 차단 layer | 도메인 안전성 |
|---|---|---|
| Silent over-restore (INCR 이중 호출) | claimToInFlight CAS (RDB 측 row 단위 잠금) + IN_FLIGHT timeout + SETNX 토큰 (Redis 측). 3 layer 차단 | **fit (조건부)** — DB4-1 의 race window 인정. 토큰 TTL 만료 후 INCR 미발생은 under-restore 형태 |
| Silent under-restore (보상 누락) | UNIQUE `(order_id, message_type, dedup_key)` + outbox PENDING 영구 보존 (FAILED 도 admin 도구로 회복 가능) | fit |
| 부분 보상 일관성 | dedup_key 가 productId — 항목 단위 격리 자연 표현 | **fit** |
| 환불 race | n/a — outbox 트랙은 PaymentEvent.status 와 직접 결합 없음 | n/a (현 코드) |

### 다른 보상 경로 일반화 가능성

- **OutboxAsyncConfirmService.compensateStock** — 동일 catch 패턴. payment_outbox 에 STOCK_COMPENSATE row INSERT 만 추가. dedup_key = `productId`, message_type = `STOCK_COMPENSATE`. PaymentEvent 가 어떤 status 든 outbox 트랙은 독립 — **일반화 fit**
- **PaymentTransactionCoordinator.compensateStockCacheGuarded** — D12 가드 안에서 try/catch 추가 + outbox INSERT. PaymentEvent.status 와 무관 — **일반화 fit**
- 단 일반화 시 message_type 의 의미 분기 — STOCK_COMPENSATE_FROM_CONFIRM / FROM_RESULT / FROM_FAILURE 같이 origin 분류 필요한가? Round 3 의 D-B7' 명시적 미루기 정책은 합리적

## Candidate D (refined) — Domain Deep Validation

### Round 3 도메인 흡수 정직성

| Finding ID (Round 2 Domain) | Round 3 흡수 주장 | Domain Expert 평가 | 사유 |
|---|---|---|---|
| DD-1 (AOP status_change 결합 분리 PITFALLS #1 직격) | 옵션 D2 — 별 Aspect 신설. 기존 `DomainEventLoggingAspect` 변경 0 | **흡수 OK + 핵심 정직** | `DomainEventLoggingAspect.java` 80-107 의 `processResultAndPublishEvent` switch 변경 0 — PITFALLS #1 회피. 별 Aspect 가 신규 어노테이션만 처리 |
| DD-2 (강한 차단 부재) | PaymentEvent.compensation_state_version 컬럼 = Candidate A 와 동일 schema 변경 비용. 정직 인정 | **흡수 OK + 정직** | "audit-driven 차별점이 약화" 정직 인정. 본 candidate 의 가치 = audit row 가 회복 SoT 인 점뿐 |
| DD-3 (AOP findPaymentEvent args 분기) | markStockCompensateFailed/Recovered 시그니처 = (PaymentEvent, productId, quantity, reasonCode). 별 Aspect 가 args 직접 추출 | **흡수 OK** | 별 Aspect 가 자체 책임 — 기존 AOP 의 reflection 추가 분기 회피 |
| DD-4 (history 컬럼 추가 + backfill) | UPDATE 후 NOT NULL DEFAULT 변경 | 흡수 OK | 단 운영 backfill 시 lock 시간은 history append-only 라 큰 부하 가능 — Round 5 측정 필요 |
| DD-5 (NOT EXISTS 부하) | 인덱스 (action, created_at) + (order_id, action) + cutoff 24h | 흡수 OK |
| FD-1 (AOP 5 layer 변경) | 옵션 D2 가 status_change 결합 분리 회피 | 흡수 OK |
| FD-2 (history 의미 변질) | action 화이트리스트 운영 가이드 (3종) | **부분 흡수** | "향후 진입은 별 토픽 결정" 정책은 합리적. 단 audit trail SoT 의 신뢰성이 한 번 깨지면 회복 어려움 — 본 candidate 의 도메인 부담 |

### Round 3 도메인 신규 위험

- **DD4-1 [MAJOR]**: 별 Aspect (`StockCompensationLoggingAspect`) 가 `compensateStockCache` catch 안에서 호출되는 시점의 도메인 자연성. 시나리오:
  - `PaymentConfirmResultUseCase.compensateStockCache` (line 304-317) 의 for 루프 내 try/catch
  - catch 블록에서 `paymentEvent.markStockCompensateFailed(productId, quantity, reasonCode)` 도메인 메서드 호출
  - 별 Aspect 가 메서드 가로채 `payment_history` INSERT
  - **시점 문제**: catch 는 `stockCachePort.increment` 가 RuntimeException throw 한 직후. 즉 INCR 이 실패한 직후 history INSERT — 한 항목에 대해 1개 history 행
  - 다음 항목 (다른 productId) INCR 실패 시 또 history 행 1개 추가 — 한 결제 N항목 중 N개 history 행 (일부 항목)
  - **도메인 의미**: history 의 retry count = 같은 (orderId, productId) 행 카운트. 첫 시도는 stock_compensate_failed 1 행, 회복 워커 재시도 실패 시 또 1 행. retry count 가 (orderId, productId) 별 자연 표현 — Round 3 의 D-D1' 정합
  - 단 Aspect 가 try/catch 외부의 도메인 메서드만 가로챈다는 보장이 코드로 강제되지 않음 — 별 Aspect 가 실수로 try 블록 안의 다른 도메인 메서드까지 가로채면 의미 오염

- **DD4-2 [MAJOR]**: payment_history audit row 추가 시 운영자가 임의 audit row 추가하면 retry count misinterpretation. 시나리오:
  - 운영자 admin 도구로 수동 retry count reset 또는 같은 (orderId, productId) 의 stock_compensate_failed 행 임의 INSERT/DELETE
  - 회복 워커가 행 카운트 = retry count 로 간주 → 의도와 다른 retry 횟수 추출
  - audit "기록" 과 "회복 트리거" 의 의미가 한 테이블에 섞임. **payment_history 가 상태 SoT 가 되는 도메인 부담** — 기존 audit 트레일 SoT 신뢰성 vs 새 회복 트랙 SoT 의미 충돌
  - Round 3 의 action 화이트리스트 운영 가이드 (3종) 는 의미 분류만 다룰 뿐, 운영자 수동 개입 정책은 답 없음

- **DD4-3 [MAJOR]**: PaymentEvent.compensation_state_version 컬럼 도입 → Candidate A 와 schema 변경 비용 동일. 본 candidate 의 차별점 정량화:
  - A: PaymentEvent (compensation_status / compensation_retry_count / compensation_state_version) 3 컬럼
  - D: PaymentEvent (compensation_state_version) 1 컬럼 + payment_history (action / dedup_key) 2 컬럼 + 별 Aspect + 별 어노테이션 + 별 Reconciler
  - 작업량 합계 D > A. 차별점은 audit row 가 회복 SoT 인 점뿐
  - 도메인 운영자 mental model: A (PaymentEvent 한 곳 + 두 컬럼 동시 봐야 함) vs D (PaymentEvent 1 컬럼 + payment_history 별 트레일) — **D 가 1.5 트랙으로 명백**. Round 3 의 평가 ("두 곳 가시화 1.5 트랙") 정확. A 도 사실 1.5 트랙
  - 결론: 본 candidate 의 도메인 가치 약화 — A 또는 B 가 운영 mental model 더 깔끔

- **DD4-4 [MAJOR]**: 별 Aspect 와 기존 `DomainEventLoggingAspect` 의 @Around 중첩 — `markStockCompensateFailed` 가 `@PublishStockCompensationEvent` 만 갖는다면 중첩 0. 단 만약 PaymentEvent 도메인 메서드가 `@PublishDomainEvent + @PublishStockCompensationEvent` 둘 다 갖게 되면 (실수 또는 의도) 두 Aspect 같이 통과. Spring AOP 의 @Order / @Aspect order 결정 필요. Round 3 의 운영 SoP 명시는 합리적이지만 **컴파일 시점 체크 부재** — Round 5 plan 단계의 Aspect 어노테이션 단독 적용 가드 (예: ArchUnit 테스트) 추가 권고

- **DD4-5 [MINOR]**: payment_history 의 큰 테이블 (append-only) 에 대해 NOT EXISTS 서브쿼리 운영 부하. cutoff 24h 윈도우 + 인덱스 (action, created_at) + (order_id, action) 가 효과적인지 운영 측정 deferred. payment_history 는 결제 1건 = N 행 (status_change 모든 전이) 이라 빠르게 큼

### Silent loss 차단 layer 매트릭스

| 경로 | Candidate D 차단 layer | 도메인 안전성 |
|---|---|---|
| Silent over-restore (INCR 이중 호출) | compensation_state_version 컬럼 (PaymentEvent 측). audit row append-only 자체는 over-restore 차단 안 됨 | **partial** — A 와 동일 한계 |
| Silent under-restore (보상 누락) | NOT EXISTS 서브쿼리 (회복 대상 조회) + Reconciler | fit |
| 부분 보상 일관성 | dedup_key 컬럼 (productId) — 항목 단위 격리 자연 표현 | **fit** |
| 환불 race | n/a (현 코드 부재) | n/a |

### 다른 보상 경로 일반화 가능성

- **OutboxAsyncConfirmService.compensateStock** — payment_history 의 `action='STOCK_COMPENSATE_FAILED'` 패턴 재사용. PaymentEvent 도메인 메서드 호출만 추가. 단 PaymentEvent 가 status=READY 인 상태에서 markStockCompensateFailed 호출 시 history.current_status 컬럼에 'READY' 채움 — 도메인 의미 모순 약함 (READY 인데 보상 진행) 가능. **일반화 fit (조건부)**
- **PaymentTransactionCoordinator.compensateStockCacheGuarded** — D12 가드 안에서 별 Aspect 메서드 추가. 동일 패턴 — **일반화 fit**
- A 와 비교 시 D 는 PaymentEvent.status 와 의미 결합이 약해 일반화 더 자연스러움 — Round 3 평가 ("일반화 fit") 정확

## 3 Candidate 도메인 비교 매트릭스

| 경로 | A (refined) | B (refined) | D (refined) |
|---|---|---|---|
| Silent over-restore | partial — DA4-1 (markPaymentAsFail 후 markCompensationPending 사이 race) + DA4-5 (PaymentOrder 단위 격리 부재 시 결제 단위 재순회) | **fit (조건부)** — claimToInFlight + IN_FLIGHT timeout + SETNX. SETNX-INCR atomic 묶음 부재 인정 (under 형태 race) | partial — A 와 동일 한계 (compensation_state_version 만으로는 결제 단위 재순회 차단 안 됨) |
| Silent under-restore | partial — DA4-6 (RDB 다운 + extendLease 잠금 시 dedupe 영구 잠금) | fit | fit (NOT EXISTS) |
| 부분 보상 일관성 | **break** — PaymentOrder 단위 격리 본 토픽 외 미루기 (Round 3 정직 인정) | **fit** — dedup_key 자연 표현 | **fit** — dedup_key 자연 표현 |
| 환불 race | 미해결 (terminal+compensation_status 분리 모델 의미 모호) | n/a (outbox 트랙 독립) | n/a |
| 일반화 가능성 (다른 두 보상 경로) | **break** — PaymentEvent IN_PROGRESS 진입 전/D12 가드 시점 적용 모순 | **fit** | fit (조건부) |
| 운영자 mental model | 1.5 트랙 — PaymentEvent (status + compensation_status 두 컬럼) | 1 트랙 — payment_outbox 한 곳 | 1.5 트랙 — PaymentEvent + payment_history |
| Round 3 흡수 정직성 | DA-1 부분 흡수 (race window 형태 이동) + DA-2 흡수 미흡 (단독 채택 부담) | 흡수 OK + 정직 (race window 인정) | 흡수 OK + 정직 (A 와 비용 동등 인정) |
| Round 4 신규 BLOCKER | DA4-1 (markPaymentAsFail 후 process crash + Kafka 재배달 silent over-restore) | 0 | 0 |

## Round 5 권고 (도메인 관점)

### 추천 1순위 — **Candidate B (refined)**

근거 (도메인 안전성 관점):
- silent over-restore 차단 layer 가 가장 두꺼움 — claimToInFlight CAS (RDB) + IN_FLIGHT timeout + SETNX 토큰 (Redis) 3 layer
- SETNX-INCR atomic 묶음 부재의 한계는 정직 인정 + race window 가 silent under 형태 (admin reset 으로 회복 가능) — 돈이 새는 방향이 발산 아닌 수렴
- 항목 단위 격리 자연 표현 (dedup_key=productId) — 부분 보상 일관성 fit
- 다른 두 silent loss 경로 일반화 fit — 본 토픽 후속 PHASE2 토픽에서 같은 모델 재사용 가능
- 운영자 mental model 1 트랙 (payment_outbox 한 곳) — Round 3 가 outbox 의미 일반화 위험을 정직 인정하지만 ubiquitous language 분기는 admin layer 흡수 가능
- 회귀 surface (claimToInFlight 시그니처 + Strategy refactoring + UNIQUE 제약) 가 가장 큼 — Round 4 Critic 의 정량 측정과 happy path 회귀 테스트 보강 결정 필수

### 추천 2순위 — **Candidate D (refined)**

근거:
- audit row 가 회복 SoT 인 점이 도메인 mental model 새 트랙. retry count = 행 카운트 자연 표현
- 일반화 fit (다른 두 보상 경로에 같은 audit 패턴 적용 가능)
- A 와 schema 변경 비용 동일 (compensation_state_version 컬럼) 정직 인정
- 도메인 부담: payment_history 의미 변질 (audit + 회복 트리거 의미 섞임) + 운영자 임의 audit row 개입 시 retry count misinterpretation 위험
- 별 Aspect 의 @Order 결정 + 어노테이션 단독 적용 가드 (ArchUnit) 등 plan 단계 결정 필요

### 추천하지 않는 후보 — **Candidate A (refined)**

사유:
- DA4-1 (markPaymentAsFail 후 process crash + Kafka 재배달 silent over-restore) BLOCKER — Round 3 가 인지 못한 race window. Round 4 의 핵심 발굴
- DA-2 흡수 미흡 — PaymentOrder 단위 격리를 본 토픽 외 미루기 = 부분 보상 break 그대로 채택. 항목 단위 over-restore 발산 가능
- DA4-6 (RDB 다운 + extendLease 잠금 시 dedupe 영구 잠금) — single-phase mark with long TTL PITFALLS #10 직격
- DA4-2 / DA4-4 (compensation_status 와 isTerminal 가드 정합 / 환불 race) — Round 3 답 없음
- 일반화 break — 다른 두 보상 경로에 적용 시 PaymentEvent 라이프사이클과 결합으로 돔구조
- 운영자 mental model 1 트랙 정당화는 표면적 — 두 컬럼 동시 봐야 진짜 종결 의미 (1.5 트랙)
- 본 토픽 단독 채택 시 후속 PHASE2 가 별 모델로 가야 하므로 mental model 분기

### Round 5 사용자 결정 시 도메인 축

1. **돈이 새는 방향 위험 비대칭** — A (over-restore 발산 가능) vs B (under-restore 회복 가능) vs D (audit 신뢰성 vs 회복 SoT trade-off). 결제 도메인 SLA 가 어느 비대칭을 받아들이는가?
2. **부분 보상 일관성 (PaymentOrder 단위 격리)** — A 는 단독 채택 시 break, B/D 는 dedup_key 자연 표현. 본 토픽 외 미루기로 받아들이는 부담 vs 본 토픽에서 해결
3. **다른 두 silent loss 경로 일반화** — 본 토픽 단독 결정으로 봉인할지, 후속 PHASE2 의 입력으로 묶을지. A 채택 시 PHASE2 가 별 모델 = mental model 분기. B/D 채택 시 같은 모델 재사용

## decision

Round 5 진행 가부: **revise**

근거:
- Candidate A 의 DA4-1 BLOCKER (markPaymentAsFail 후 process crash + Kafka 재배달 silent over-restore) — Round 3 의 옵션 A2 흡수가 본질적 race window 를 해결 못함
- Candidate A 의 DA-2 흡수 미흡 (PaymentOrder 단위 격리 본 토픽 외 미루기 = 부분 보상 break 채택)
- Candidate A 가 Round 5 최종 비교에 포함되려면 위 두 BLOCKER 해소 명시 필요 (PaymentOrder.compensated_at 컬럼 본 토픽 도입 또는 PaymentEvent.markCompensationPending 호출을 markPaymentAsFail 이전으로 이동 - 이 경우 호출 순서 모순 RA-1 그대로 부활)

Round 5 진행 권고:
- Candidate A 는 위 BLOCKER 해소 안 명시 또는 후보에서 제외
- Candidate B / D 는 Round 5 진행 가능
- 1순위 B (도메인 안전성 fit + 일반화 fit + mental model 1트랙) — Round 5 사용자 선택지의 1순위
- 2순위 D (audit-driven SoT + 일반화 fit + schema 변경 비용 A 와 동등 정직 인정)

## JSON

```json
{
  "stage": "discuss-extended",
  "topic": "STOCK-COMPENSATION-RECOVERY-ALTERNATIVES",
  "round": 4,
  "persona": "domain-expert",
  "decision": "revise",
  "verdict_summary": "Candidate A 의 Round 3 옵션 A2 흡수가 DA-1 본질을 해결하지 못함 (race window 형태 이동). DA-2 흡수 미흡 (부분 보상 break 채택). Candidate B 가 도메인 안전성 fit + 일반화 fit + mental model 1트랙으로 1순위 추천. Candidate D 는 schema 변경 비용 A 와 동등 정직 인정 + audit 회복 SoT 차별점 으로 2순위.",
  "findings": [
    { "id": "DA4-1", "severity": "critical", "candidate": "A", "summary": "옵션 A2 의 호출 순서 유지 + handleFailed (PaymentConfirmResultUseCase.java 258-272) 의 markPaymentAsFail → compensateStockCache 사이 process crash 시 TX rollback 으로 RDB 복원되지만 Redis INCR 일부 살아있음. processMessageWithLeaseGuard catch (line 140-144) 가 dedupeStore.remove → Kafka 재배달 → 재진입 시 같은 productId INCR 두 번 = silent over-restore. Round 3 의 compensation_state_version 가드는 RDB 측만 차단" },
    { "id": "DA4-2", "severity": "major", "candidate": "A", "summary": "terminal+compensation_status 분리 모델의 도메인 의미 모호 — status=FAILED + compensation_status=PENDING 상태에서 환불 요청 정책 답 없음 (현 코드 환불 도메인 메서드 부재 가설적)" },
    { "id": "DA4-3", "severity": "major", "candidate": "A", "summary": "compensation_status=DONE 도달 후 PaymentEvent 의미 = status=FAILED + compensation_status=DONE. 운영자 1트랙 정당화는 표면적 — 두 컬럼 동시 봐야 진짜 종결 의미 (1.5 트랙)" },
    { "id": "DA4-4", "severity": "major", "candidate": "A", "summary": "isTerminal() 가드 (PaymentEventStatus.java 21-26) 가 status 만 봄. 다른 use case 가 isTerminal 만 체크하면 compensation_status 진행 중에도 통과. 옵션 A2 가 isTerminal 의미 오염" },
    { "id": "DA4-5", "severity": "major", "candidate": "A", "summary": "DA-2 흡수 미흡 — PaymentOrder 단위 격리 본 토픽 외 미루기. 결제 단위 재순회 시 productA INCR 성공 + productB INCR 실패 후 재시도 시 productA 또 INCR (over-restore). compensation_state_version 가드는 동일 워커 단일 호출 안의 N항목 재순회 차단 못 함" },
    { "id": "DA4-6", "severity": "major", "candidate": "A", "summary": "D-A7' 의 RDB 다운 + swallow + events.confirmed 재배달 의존 — extendLease (PaymentConfirmResultUseCase.java 139) 가 P8D 잠근 후라 Kafka 재배달 시 dedupe 영구 잠금. PITFALLS #10 single-phase mark with long TTL 직격" },
    { "id": "DB4-1", "severity": "major", "candidate": "B", "summary": "SETNX-INCR race window — SETNX 직후 + INCR 직전 process crash 시 토큰만 살고 INCR 미발생. 토큰 TTL 만료 전 재선점 시 ALREADY_PROCESSED no-op + outbox=DONE 종결 → silent under-restore. admin reset (token DEL + outbox 재기동) 으로 회복 가능 — 운영 noise 수준. 메트릭 임계 정책 plan 단계 결정 필요" },
    { "id": "DB4-2", "severity": "major", "candidate": "B", "summary": "payment_outbox 의 STOCK_COMPENSATE 메시지 타입 lifecycle 의미 분기 — IN_FLIGHT/toFailed 가 CONFIRM_COMMAND 와 다른 의미. PaymentOutbox 도메인 객체 ubiquitous language 위반. admin layer 흡수 가능하지만 plan 단계 명시 필요" },
    { "id": "DB4-3", "severity": "major", "candidate": "B", "summary": "claimToInFlight 시그니처 변경 회귀 surface — PaymentOutboxRepository (line 18) + OutboxRelayService.relay (line 50) + OutboxImmediateEventHandler (line 38) + PaymentTransactionCoordinator.executeConfirmTx (line 82-93) + PaymentConfirmEvent payload 4 인자 (PaymentConfirmEvent.java) 변경. 4 layer 회귀 + happy path 통합 테스트 전부" },
    { "id": "DB4-4", "severity": "minor", "candidate": "B", "summary": "PaymentConfirmEvent payload migration 정책 — ApplicationEvent in-memory 라 round-trip 위험 0. blue-green 배포 시 두 JVM 격리. plan 단계 명시" },
    { "id": "DB4-5", "severity": "minor", "candidate": "B", "summary": "payment_outbox.dedup_key + UNIQUE 변경 backfill — MySQL UNIQUE NULL 중복 허용으로 호환. Round 3 정직 인정" },
    { "id": "DD4-1", "severity": "major", "candidate": "D", "summary": "별 Aspect (StockCompensationLoggingAspect) 의 catch 블록 시점 호출 도메인 자연성 — 한 결제 N항목 중 일부 INCR 실패 시 N개 history 행 일부. retry count = (orderId, productId) 별 행 카운트 자연 표현 OK. 단 try 블록 안의 다른 도메인 메서드 가로채기 컴파일 시점 차단 부재" },
    { "id": "DD4-2", "severity": "major", "candidate": "D", "summary": "운영자 임의 payment_history audit row 추가/삭제 시 retry count misinterpretation. audit 기록과 회복 트리거 의미 한 테이블 섞임. action 화이트리스트 정책은 의미 분류만 — 운영자 수동 개입 정책 답 없음" },
    { "id": "DD4-3", "severity": "major", "candidate": "D", "summary": "PaymentEvent.compensation_state_version 컬럼 도입 = Candidate A 와 schema 변경 비용 동일 정직 인정 (Round 3). 작업량 합계 D > A. 차별점은 audit row 회복 SoT 점뿐. 도메인 mental model 1.5 트랙 (PaymentEvent + payment_history)" },
    { "id": "DD4-4", "severity": "major", "candidate": "D", "summary": "별 Aspect 와 기존 DomainEventLoggingAspect (DomainEventLoggingAspect.java 80-107) @Around 중첩. 같은 메서드 두 어노테이션 동시 적용 시 두 Aspect 통과 — Spring AOP @Order 결정 + ArchUnit 어노테이션 단독 적용 가드 plan 단계 명시 필요" },
    { "id": "DD4-5", "severity": "minor", "candidate": "D", "summary": "payment_history append-only 큰 테이블 NOT EXISTS 운영 부하. cutoff 24h + 인덱스 효과 운영 측정 deferred" }
  ],
  "domain_recommendation": {
    "1st": "B",
    "2nd": "D",
    "rejected": "A",
    "rejected_reason": "DA4-1 BLOCKER (Round 3 흡수 미흡 — race window 형태 이동) + DA4-5 (DA-2 흡수 미흡, 부분 보상 break 본 토픽 외 미루기 채택) + 일반화 break + mental model 1.5 트랙 표면적 정당화"
  },
  "verdict_axes": [
    "돈이 새는 방향 비대칭 — A (over-restore 발산) vs B (under-restore 회복 가능) vs D (audit SoT trade-off)",
    "부분 보상 일관성 (PaymentOrder 단위 격리) — A 본 토픽 외 미루기 break, B/D dedup_key 자연 표현",
    "다른 두 silent loss 경로 (OutboxAsyncConfirmService.compensateStock + PaymentTransactionCoordinator.compensateStockCacheGuarded) 일반화 — A break, B/D fit"
  ]
}
```
