# review-domain-1

**Topic**: PG-CONFIRM-LISTENER-SPLIT
**Round**: 1
**Persona**: Domain Expert

## Reasoning

PCS-1~16 16 태스크가 모두 GREEN 으로 마무리됐고 listener / 채널 / 워커 / 폴링 / 보정 경로 PENDING 우회 / Flyway V2~V3 / 위키 + 영구 문서 동기화까지 1차 정합돼 있다. 그러나 IN_PROGRESS 재진입 채널 경로가 본 토픽 §1.6 안 B 에서 봉인한 동작과 다르게 구현돼 있고 (`PgInboxImmediateWorker` 가 `processInProgressZombie` 분기를 호출하지 않음), `processInProgressZombie` 경로에 row-level 락이 없어 다중 워커 동시 진입 시 outbox 중복 INSERT → 중복 Kafka publish 가 가능하다. 둘 다 결제 도메인 정합성에 직접 영향이 있는 major 결함이다.

## Domain risk checklist

- [x] `paymentKey` / `orderId` / 카드번호 등이 plaintext 로그에 노출되지 않음 — orderId 만 LogFmt 에 노출, paymentKey 는 V3 컬럼에만 저장.
- [x] 보상 / 취소 로직에 멱등성 가드 존재 — DuplicateApprovalHandler 5분기 + Lua dedup token 정렬 (payment-service 측).
- [x] PG 가 반환하는 "이미 처리됨" 응답이 맹목 수용되지 않음 — `handleDuplicateApproval` 이 `getStatus` 1회 + 금액 양방향 대조.
- [x] 상태 전이가 불변식을 위반하지 않음 — `PgInbox.markInProgress/Approved/Failed/Quarantined` 가 사전 가드 + `casNonTerminalToQuarantined` SQL CAS 이중 가드.
- [N] race window 가 있는 경로에 락 / 트랜잭션 격리 고려됨 — `processPending` 은 SKIP LOCKED CAS 가 정상이지만 `processInProgressZombie` 경로에는 row 락 없음 (아래 Findings #2 참조).

## 도메인 관점 추가 검토

### 1. listener TX 봉인 (§1.1 / D-F3)
- `PgInboxPendingService.insertPendingAndPublish` `@Transactional(timeout=5)` + INSERT IGNORE + publishEvent 정합. timeout 발화 시 `pg_inbox.listener_tx_timeout_total` 카운터 + warn 로그 — F4 acceptance 충족 (`PgInboxPendingService.java:78-110`).
- `applicationEventPublisher.publishEvent(new PgInboxReadyEvent(inboxId))` 가 active TX 안에서 호출됨 — A5 acceptance 정합.

### 2. NONE 폐기 (§1.5 / C-F4)
- `PgInboxStatus` enum 5상태 (`PENDING/IN_PROGRESS/APPROVED/FAILED/QUARANTINED`), NONE 미존재 (`PgInboxStatus.java:13-26`).
- Flyway `V2__add_pg_inbox_pending_status.sql` 가 `NONE → PENDING` 사전 변환 + ENUM 재정의로 dev/test 호환 (운영 데이터 부재 가정).
- 잔존 NONE 참조: `EventType.PG_CONFIRM_NONE_TO_IN_PROGRESS`, `PG_INBOX_AMOUNT_NONE_TO_IN_PROGRESS_PREEMPTED` 두 enum 상수 (event 이름) — 실제 분기 동작은 PENDING 으로 갱신됐으나 이름이 NONE 잔존. 도메인 로그 의미가 misleading 한 minor 잔재.
- `PgInboxRepositoryImpl.java:21` Javadoc 에 "status=NONE 조건으로 CAS 한다" 라는 stale 문장 잔재 — 실제 코드는 NONE 폐기됐는데 Javadoc 만 미갱신. minor.

### 3. 보정 경로 PENDING 우회 (§1.8 / C-F3 / D-F1)
- `DuplicateApprovalHandler.handleDbAbsentAmountMatch/Mismatch` 가 `transitDirectToTerminal` 호출, `handleVendorIndeterminate` 가 `transitDirectToInProgress + transitToQuarantined` 같은 `@Transactional` TX 안에서 atomic 봉인 — A4 acceptance 정합 + D-F2 atomicity 룰 준수 (`DuplicateApprovalHandler.java:227-296`).
- `transitDirectToTerminal` 이 `PgInboxStatus.isTerminal()` 사전 가드로 비-terminal 값 (PENDING / IN_PROGRESS) 거부 — 도메인 침범 차단 (`PgInboxRepositoryImpl.java:143-145`).

### 4. 워커 처리 TX 경계 (§1.6)
- `PgInboxProcessor.processPending`: `findById` + `transitPendingToInProgress` (TX_A) + `invokeVendor` (TX 외부) + `applyOutcome` (TX_B) 3-phase 정합. CAS 0 row → 정상 return + LogFmt info — race 흡수 (`PgInboxProcessor.java:69-98`).
- `PgInboxProcessor.processInProgressZombie`: TX_A 생략 — `findById` + status 검사 후 즉시 `invokeVendor` (TX 외부) + `applyOutcome` (TX_B). row-level 락 없음. 아래 Findings #2 의 race window 근거.

### 5. PG 실패 모드 5분기 (§1.6 + DuplicateApprovalHandler)
- `dispatchOutcome` (`PgVendorCallService.java:149-156`) 가 4가지 sealed Outcome 분기. ALREADY_PROCESSED → `HandledInternally` → `DuplicateApprovalHandler.handleDuplicateApproval` 위임. 보정 경로 `getStatus` 1회 호출 후 5분기 (DB 존재/부재 × 금액 일치/불일치 + 벤더 미확정).
- `handleVendorIndeterminate` 의 `transitDirectToInProgress + transitToQuarantined` 가 같은 active TX 안에 묶여 atomicity 보장 (`DuplicateApprovalHandler.java:280-296`) — D-F2 atomicity 흡수 정상.

### 6. acceptance A1~A5 / F1~F4 코드 정합
- A1 (listener 벤더 호출 0): `PgConfirmListenerSplitIntegrationTest.listenerDoesNotCallVendor_whenNewMessage` 가 `verify(fakePgGatewayStrategy, never()).confirm(any())` 로 검증 ✓.
- A2 (벤더 지연 무관): 5s `Thread.sleep` 주입 후 `handle` 두 번 합산 latency < 1s 검증 ✓.
- A3 (좀비 회수): `processPending` 실패 → IN_PROGRESS 잔존 → `processInProgressZombie` 호출 → terminal ✓. **단** 폴링 worker 가 60s 임계로 자동 회수하는 경로는 time-skipping 미적용으로 불검증 — Findings #3.
- A4 (PENDING 우회): `handleDuplicateApproval` 직접 호출 → APPROVED 직접 전이 ✓.
- A5 (active TX publishEvent): 단위 테스트 (`PgInboxPendingServiceTest`) 검증 가정.
- F1 (`pg_inbox_channel_queue_size/remaining_capacity`): `PgInboxChannel.registerMetrics` 등록 ✓ (`PgInboxChannel.java:53-60`).
- F2 (`pg_inbox.process_fail_total`): `PgInboxImmediateWorker.processFailCounter` 등록 ✓.
- F3 (`pg_inbox.zombie_recovered_total`) — **요건 명시는 acceptance §7.2 F3 인데 코드는 `pg_inbox.zombie_fail_total` 만 등록**. recovered 카운터 부재. Findings #4.
- F4 (`pg_inbox.listener_tx_timeout_total`): `PgInboxPendingService.listenerTxTimeoutCounter` 등록 ✓.

## Findings

### Finding #1 (major) — IN_PROGRESS 채널 재적재가 immediate worker 에서 silent drop, 폴링까지 60s 지연

`PgConfirmService.handleActiveInbox` (PgConfirmService.java:127-133) 는 PENDING 또는 IN_PROGRESS 상태의 inbox 에 대해 `applicationEventPublisher.publishEvent(new PgInboxReadyEvent(inbox.getId()))` 를 호출해 채널에 재적재한다. 그러나 `PgInboxImmediateWorker.process` (PgInboxImmediateWorker.java:154-163) 는 `processor.processPending(inboxId)` 만 호출하고 `processInProgressZombie` 분기를 호출하지 않는다.

`processPending` 안의 `transitPendingToInProgress` (PgInboxProcessor.java:80) 는 `WHERE id=? AND status=PENDING` CAS 인데, 재진입 inbox 는 이미 IN_PROGRESS 라 CAS 가 0 row → `acquired=false` → silent return (PgInboxProcessor.java:81-86, "PREEMPTED_OR_NOT_PENDING" 로그).

→ Kafka 가 같은 orderId 를 IN_PROGRESS 상태에서 재배달하면 listener 가 채널 재적재 → immediate worker 가 silent drop → polling worker 가 60s 임계 만난 뒤 `processInProgressZombie` 로 회수. 평균 30s 지연 발생. 본 토픽 §1.6 안 B 봉인 표는 "IN_PROGRESS 재진입 → workder.processInProgressZombie" 로 명시했는데 코드는 그 분기를 immediate worker 에서 구현하지 않았다.

도메인 영향: 단일 IN_PROGRESS 재진입의 경우 60s 회수가 SLO 안이지만, 채널 cap=1024 부근 트래픽 + IN_PROGRESS 재진입이 다수 발생하면 polling worker batch=10 으로 회수 속도가 부족해 인바운드 backlog 가 쌓일 수 있다. 또한 정상 동작이 폴링 의존이 되면서 acceptance §7.2 F3 의 "정상 흐름이라면 0 또는 매우 낮음" 가정이 깨진다.

- **location**: `pg-service/src/main/java/com/hyoguoo/paymentplatform/pg/infrastructure/scheduler/PgInboxImmediateWorker.java:154-156` + `pg-service/src/main/java/com/hyoguoo/paymentplatform/pg/application/service/PgInboxProcessor.java:69-98`
- **suggestion**: immediate worker 에서 `processWithContext` 직전에 `inboxRepository.findById(inboxId)` 1회 조회 후 status 분기 — PENDING → `processPending`, IN_PROGRESS → `processInProgressZombie`, terminal → skip + LogFmt info. 또는 토픽 §1.6 의 다른 옵션대로 `PgInboxProcessUseCase` 에 `process(Long inboxId)` 단일 진입점 + 내부 status 분기 메서드 추가. plan §1.3 line 327 "워커 진입 시 status 를 먼저 SELECT 후 분기" 설계 의도 그대로.

### Finding #2 (major) — `processInProgressZombie` 경로에 row-level 락 없음 → 다중 워커 동시 진입 시 outbox 중복 INSERT + 중복 Kafka publish

`PgInboxProcessor.processInProgressZombie` (PgInboxProcessor.java:115-141) 는 `findById(inboxId)` 후 status 검사만으로 벤더 호출에 진입한다. SKIP LOCKED 또는 `SELECT FOR UPDATE` 없음. `applyOutcome` 의 `handleSuccess` (PgVendorCallService.java:162-170) 와 `handleDefinitiveFailure` (PgVendorCallService.java:176-184) 는 `pg_outbox INSERT → transitToApproved/Failed (CAS WHERE status=IN_PROGRESS) → publishEvent` 순서로 동작한다.

다중 워커 race 시나리오: polling worker (batch=10) 가 같은 IN_PROGRESS 좀비 row 를 두 번 회수 (예: polling 두 cycle 사이 회수 row 가 여전히 IN_PROGRESS 인 경우 — Findings #5 추가 검토 필요), 또는 보정 경로의 `transitDirectToInProgress` 후 polling worker 가 회수하기 직전에 같은 워커가 다시 진입하는 경우.

```
T0: Worker A: findById(N) → IN_PROGRESS
T0: Worker B: findById(N) → IN_PROGRESS  (둘 다 통과)
T1: Worker A: invokeVendor() → APPROVED (idempotency-key)
T1: Worker B: invokeVendor() → APPROVED (idempotency-key, ALREADY_PROCESSED 가능)
T2: Worker A: applyOutcome → pg_outbox INSERT (id=R1) + transitToApproved (CAS, 1 row) + publishEvent
T2: Worker B: applyOutcome → pg_outbox INSERT (id=R2) + transitToApproved (CAS, 0 row — 이미 APPROVED) + publishEvent
```

**결과**: pg_outbox 에 동일 orderId 의 events.confirmed payload 가 두 row 박힘 (eventUuid 가 `UUID.randomUUID()` 로 매번 새 UUID 생성 — `PgVendorCallService.buildApprovedPayload` 라인 249). `PgOutboxRelayService` 가 두 row 를 모두 Kafka publish → payment-service `ConfirmedEventConsumer` 가 두 메시지 수신.

도메인 영향:
- payment-service 는 Lua atomic dedup token (`decrement:done:{orderId}` / `compensation:done:{orderId}` SETNX P8D) 으로 결제 단위 멱등성 보장 — 재고 발산은 차단됨 (CONFIRM-FLOW.md §13).
- 그러나 `markPaymentAsDone` AOP audit (`@PaymentStatusChange` + `@PublishDomainEvent`) 가 두 번 발화 — 첫 번째는 정상 audit + stock_outbox INSERT, 두 번째는 `IN_PROGRESS → DONE` 사전 가드가 throw → DefaultErrorHandler retry 5회 + DLQ 진입 (silent loss 가 아닌 noise).
- 벤더 API 호출 횟수 2배 — Toss / NicePay 의 rate-limit 정책 (운영 plan 별 다름) 에 영향. ALREADY_PROCESSED 응답이라도 호출 카운터는 소진.
- `handleDefinitiveFailure` 의 경우, A 워커는 `transitToFailed` 성공, B 워커는 `transitToApproved/Failed` 모두 0 row (이미 FAILED) → 두 outbox row 모두 publish → payment-service 가 동일 reasonCode 로 두 번 `markPaymentAsFail` → 두 번째에서 terminal 재전이 throw → DLQ.

PITFALLS.md §11 "보상 트랜잭션 중복 진입" 의 처방 ("outbox 가 IN_FLIGHT AND event 가 비종결일 때만 재고 INCR") 와 동일 패턴이 pg 측 inbox 에도 필요.

- **location**: `pg-service/src/main/java/com/hyoguoo/paymentplatform/pg/application/service/PgInboxProcessor.java:115-141` + `pg-service/src/main/java/com/hyoguoo/paymentplatform/pg/application/service/PgVendorCallService.java:162-184`
- **suggestion**: `processInProgressZombie` 에 새 repo 메서드 `selectForUpdateSkipLockedInProgress(inboxId)` (또는 `lockInProgressForUpdate(inboxId)`) 추가 — `SELECT id FROM pg_inbox WHERE id=? AND status=IN_PROGRESS FOR UPDATE SKIP LOCKED` 같은 형태. 0 row 반환 시 silent return. `transitToApproved/Failed` 의 CAS 만으로는 outbox INSERT 가 already 박혔기 때문에 race 차단 부족. plan §1.6 표 line 454 도 IN_PROGRESS row 에 SKIP LOCKED 적용을 명시했는데 구현이 누락됨.

### Finding #3 (minor) — `handleTerminal` `@Transactional` 자기 호출로 proxy 우회 → annotation 무력화

`PgConfirmService.handleTerminal` (PgConfirmService.java:142-164) 에 `@Transactional` 이 명시돼 있고 Javadoc 에 "pg_outbox save + publishEvent 가 동일 active TX 안에서 실행되어야 AFTER_COMMIT 등록된다" 고 적혀 있다. 그러나 호출처 `PgConfirmService.processCommand` (PgConfirmService.java:78-93) 는 같은 클래스의 private 메서드이고, `processCommand` 자체에는 TX 가 없으며 `this.handleTerminal(inbox)` 직접 호출 — Spring AOP proxy 를 거치지 않으므로 `@Transactional` annotation 이 무력화된다.

실질 동작: `pgOutboxRepository.save` 의 per-method TX 가 row 를 commit, 후속 `publishEvent` 는 active TX 외부. `OutboxReadyEventHandler` 의 `@TransactionalEventListener(fallbackExecution=true)` 덕에 TX 부재여도 listener 는 즉시 발화 → 채널 적재 → 정상 동작.

도메인 영향: 기능적으로는 정상 (fallbackExecution=true 가 안전망). 그러나 Javadoc 의 "동일 active TX 안에서 실행" 은 사실과 다름 → 향후 fallbackExecution 을 제거하거나 행위가 바뀔 때 silent regression 위험. 비슷한 경우 (`payment-service` 의 D-F3 흡수와 표현 정합) 와 misleading.

- **location**: `pg-service/src/main/java/com/hyoguoo/paymentplatform/pg/application/service/PgConfirmService.java:142-164`
- **suggestion**: 옵션 A — `handleTerminal` 을 별 빈 (`PgInboxTerminalService` 등) 으로 추출해서 proxy 통과 보장. 옵션 B — `handle()` 에 `@Transactional` 을 두고 `processCommand` 전체를 한 TX 에 묶음 (단 listener TX timeout=5 와 충돌 주의 — listener TX 안에 벤더 호출 0 룰 위반 안 함). 옵션 C — 현재 상태 인정 + Javadoc 을 "fallbackExecution=true 가 TX 부재 안전망" 으로 정정. 본 토픽 sealing 후속에서 결정.

### Finding #4 (minor) — acceptance §7.2 F3 카운터 (`pg_inbox.zombie_recovered_total`) 미구현, `zombie_fail_total` 만 존재

본 토픽 §7.2 acceptance 표 (PG-CONFIRM-LISTENER-SPLIT.md:767) 는 "F3 — 좀비 폴링이 회수한 row 수: Micrometer counter `pg_inbox.zombie_recovered_total{status=PENDING|IN_PROGRESS}` 노출" 로 명시했다. 그러나 `PgInboxPollingWorker.ZOMBIE_FAIL_COUNTER_NAME` 만 등록 (`pg_inbox.zombie_fail_total`) — 회수 성공 카운터 미구현.

회수 성공 카운터가 없으면 운영 시 "정상 처리는 0 또는 매우 낮음, 부하 / 워커 크래시 시 증가" 라는 신호 자체가 관측 불가. 실패 카운터만으로는 "0 인지 정상 인지 자체를 모르는 silent" 상태. F3 신호를 위해 회수 성공 카운터 추가 필요.

- **location**: `pg-service/src/main/java/com/hyoguoo/paymentplatform/pg/infrastructure/scheduler/PgInboxPollingWorker.java:44-69` + 본 토픽 §7.2 F3 acceptance
- **suggestion**: `zombieRecoveredCounter = Counter.builder("pg_inbox.zombie_recovered_total").tag("status", ...)` 형태로 status=PENDING / IN_PROGRESS 두 태그 카운터 추가. `recoverPendingZombies` / `recoverInProgressZombies` 의 each row 처리 후 increment (단, processSafely 가 throw 안 했을 때만 — `processSafely` Runnable 안에서 increment 하면 fail 시 미증가 자연스러움).

### Finding #5 (minor) — `findInProgressZombieIds` 에 SKIP LOCKED 없음 → polling 두 cycle 동시 회수 risk

`JpaPgInboxRepository.findInProgressZombieIdsBefore` (JpaPgInboxRepository.java:131-139) 는 JPQL `SELECT e.id FROM PgInboxEntity ... WHERE status=IN_PROGRESS AND updatedAt < :before` 로 lock 없이 id 만 반환한다. polling 한 cycle (5s) 안에 회수 처리가 끝나지 않으면 다음 cycle 이 같은 id 를 다시 반환 가능. `processSafely → processInProgressZombie` 진입 후 row 가 아직 IN_PROGRESS 면 두 번째 cycle 의 회수가 같은 row 를 또 처리.

이 risk 는 Finding #2 의 race 와 동일 도메인 영향 (outbox 중복 INSERT, 벤더 호출 2회) 으로 귀결됨. Finding #2 의 처방 (TX_A SKIP LOCKED 추가) 으로 cover 됨. 단 `findInProgressZombieIdsBefore` 자체에 `FOR UPDATE SKIP LOCKED` 를 native query 로 추가하면 1차 차단 가능 (PgOutbox 측은 이미 `findReadyForUpdate` 같은 native + SKIP LOCKED 패턴이 있으므로 거울 패턴 적용 가능).

- **location**: `pg-service/src/main/java/com/hyoguoo/paymentplatform/pg/infrastructure/repository/JpaPgInboxRepository.java:124-139`
- **suggestion**: native query + `FOR UPDATE SKIP LOCKED` 로 변경 — `SELECT id FROM pg_inbox WHERE status='IN_PROGRESS' AND updated_at < :before ORDER BY updated_at ASC LIMIT :batchSize FOR UPDATE SKIP LOCKED`. PgOutbox 측 동일 패턴 (`findReadyForUpdateSkipLocked`) 의 native + SKIP LOCKED 정합.

## JSON

```json
{
  "stage": "code",
  "persona": "domain-expert",
  "round": 1,
  "task_id": null,

  "decision": "revise",
  "reason_summary": "16 태스크 모두 GREEN 이고 acceptance A1/A2/A4 정합이지만, IN_PROGRESS 채널 재적재의 immediate worker 분기 누락 + processInProgressZombie row 락 부재 두 major 가 결제 멱등성/벤더 호출 횟수에 직접 영향. F3 카운터 명세 불일치 + handleTerminal 자기호출 proxy 우회 minor 잔재.",

  "checklist": {
    "source": "_shared/checklists/code-ready.md",
    "items": [
      {
        "section": "domain risk",
        "item": "race window 가 있는 경로에 락 / 트랜잭션 격리 고려됨",
        "status": "no",
        "evidence": "PgInboxProcessor.processInProgressZombie (PgInboxProcessor.java:115-141) 가 SELECT FOR UPDATE SKIP LOCKED 없이 findById + status 검사만으로 벤더 호출에 진입 — 다중 워커 동시 진입 시 pg_outbox 중복 INSERT + Kafka 중복 publish 가능."
      },
      {
        "section": "domain risk",
        "item": "보상 / 취소 로직에 멱등성 가드 존재 (워커 dispatch 분기)",
        "status": "no",
        "evidence": "PgInboxImmediateWorker.process (PgInboxImmediateWorker.java:154-156) 가 processPending 만 호출 — IN_PROGRESS 재진입 (handleActiveInbox 가 채널 재적재) 이 silent drop 됨. plan §1.6 안 B 봉인 표 (IN_PROGRESS → processInProgressZombie) 와 불일치."
      },
      {
        "section": "domain risk",
        "item": "PG가 반환하는 ALREADY_PROCESSED 가 정당성 검증을 거침",
        "status": "yes",
        "evidence": "DuplicateApprovalHandler.handleDuplicateApproval 의 5분기 (DB 존재/부재 × 금액 일치/불일치 + 벤더 미확정) + transitDirectToInProgress + transitToQuarantined atomicity 봉인 (DuplicateApprovalHandler.java:280-296)."
      },
      {
        "section": "domain risk",
        "item": "상태 전이가 불변식을 위반하지 않음",
        "status": "yes",
        "evidence": "PgInbox.markInProgress/Approved/Failed/Quarantined 사전 가드 + casNonTerminalToQuarantined SQL CAS 이중 가드 (PgInbox.java:234-366)."
      },
      {
        "section": "domain risk",
        "item": "관측 가능 acceptance 신호 정합",
        "status": "no",
        "evidence": "본 토픽 §7.2 F3 가 pg_inbox.zombie_recovered_total 명세인데 PgInboxPollingWorker 는 ZOMBIE_FAIL_COUNTER_NAME (pg_inbox.zombie_fail_total) 만 등록. 회수 성공 카운터 미구현."
      }
    ],
    "total": 5,
    "passed": 2,
    "failed": 3,
    "not_applicable": 0
  },

  "scores": {
    "correctness": 0.65,
    "conventions": 0.85,
    "discipline": 0.85,
    "test_coverage": 0.75,
    "domain": 0.55,
    "mean": 0.73
  },

  "findings": [
    {
      "severity": "major",
      "checklist_item": "보상 / 취소 로직에 멱등성 가드 존재 (워커 dispatch 분기)",
      "location": "pg-service/src/main/java/com/hyoguoo/paymentplatform/pg/infrastructure/scheduler/PgInboxImmediateWorker.java:154-156 + pg-service/src/main/java/com/hyoguoo/paymentplatform/pg/application/service/PgInboxProcessor.java:69-98",
      "problem": "PgInboxImmediateWorker.process 가 processor.processPending(inboxId) 만 호출. PgConfirmService.handleActiveInbox 가 IN_PROGRESS inbox 재진입 시 채널 재적재해도 processPending 의 transitPendingToInProgress (CAS WHERE status=PENDING) 가 0 row → silent return. 결과적으로 IN_PROGRESS 재진입은 polling worker 가 60s 임계 후 processInProgressZombie 로 회수 → 평균 30s 지연. 본 토픽 §1.6 안 B 봉인 표 (IN_PROGRESS 재진입 → workder.processInProgressZombie) 설계 의도와 불일치.",
      "evidence": "PgInboxImmediateWorker.java:156 'processor.processPending(inboxId);' / PgInboxProcessor.java:80-86 transitPendingToInProgress 0 row 시 PREEMPTED_OR_NOT_PENDING 로그 후 return / PgConfirmService.java:127-133 handleActiveInbox 가 PENDING/IN_PROGRESS 모두 publishEvent / docs/topics/PG-CONFIRM-LISTENER-SPLIT.md:439 안 B 봉인 표 'IN_PROGRESS 재진입 → workder.processInProgressZombie'",
      "suggestion": "immediate worker process 메서드 안에서 inboxRepository.findById(inboxId) 1회 조회 후 status 분기 — PENDING → processPending, IN_PROGRESS → processInProgressZombie, terminal → skip + LogFmt info. 또는 PgInboxProcessUseCase 에 process(Long inboxId) 단일 진입점 + 내부 status 분기 메서드 추가. plan §1.3 line 327 'status 를 먼저 SELECT 후 분기' 설계 의도 그대로."
    },
    {
      "severity": "major",
      "checklist_item": "race window 가 있는 경로에 락 / 트랜잭션 격리 고려됨",
      "location": "pg-service/src/main/java/com/hyoguoo/paymentplatform/pg/application/service/PgInboxProcessor.java:115-141 + pg-service/src/main/java/com/hyoguoo/paymentplatform/pg/application/service/PgVendorCallService.java:162-184",
      "problem": "processInProgressZombie 가 SELECT FOR UPDATE SKIP LOCKED 없이 findById + status 검사만으로 벤더 호출에 진입. 다중 워커가 같은 IN_PROGRESS row 를 동시 처리 시 (a) 두 번 벤더 HTTP 호출 (rate-limit / latency 비용 증가) (b) 두 outbox row 가 INSERT 되어 Kafka 에 중복 publish (c) payment-service 의 markPaymentAsDone 두 번째 호출이 terminal 재전이 throw → DefaultErrorHandler retry 5회 + DLQ noise. payment-service 측 Lua dedup token 이 결제 단위 재고 발산은 차단하지만 audit / DLQ noise 유발.",
      "evidence": "PgInboxProcessor.java:115-141 processInProgressZombie 안에 락 없음 / PgVendorCallService.java:165-167 handleSuccess 가 pg_outbox INSERT → transitToApproved CAS 순서, A 워커 commit 후 B 워커는 outbox INSERT 만 통과하고 transitToApproved 0 row → publishEvent / PgVendorCallService.java:249 buildApprovedPayload 의 UUID.randomUUID() 가 outbox row 별 새 eventUuid 생성 → payment-service 측 EventDedupeStore 가 두 메시지를 다른 메시지로 인식 / docs/topics/PG-CONFIRM-LISTENER-SPLIT.md:454 plan §1.6 표 'processInProgressZombie | WHERE id=? AND status=IN_PROGRESS FOR UPDATE SKIP LOCKED'",
      "suggestion": "PgInboxRepository 에 selectInProgressForUpdateSkipLocked(inboxId) 신규 메서드 + native query (SELECT id FROM pg_inbox WHERE id=? AND status='IN_PROGRESS' FOR UPDATE SKIP LOCKED). processInProgressZombie 진입 직후 호출, 0 row 반환 시 silent return. 이 lock 이 같은 TX 안에서 유지되도록 processInProgressZombie 자체에 @Transactional(propagation=REQUIRED) 추가 + invokeVendor 는 TX 외부에서 수행 (양쪽 분리). 또는 plan §1.6 line 454 표 봉인대로 TX_A 진입 단계에 SELECT FOR UPDATE SKIP LOCKED + status=IN_PROGRESS 검사 + updated_at 갱신을 동시에 수행."
    },
    {
      "severity": "minor",
      "checklist_item": "관측 가능 acceptance 신호 정합",
      "location": "pg-service/src/main/java/com/hyoguoo/paymentplatform/pg/infrastructure/scheduler/PgInboxPollingWorker.java:44-69 + docs/topics/PG-CONFIRM-LISTENER-SPLIT.md:767",
      "problem": "본 토픽 §7.2 F3 acceptance 표는 'pg_inbox.zombie_recovered_total{status=PENDING|IN_PROGRESS}' 카운터를 정의했는데 코드는 ZOMBIE_FAIL_COUNTER_NAME (pg_inbox.zombie_fail_total) 만 등록. 회수 성공 카운터 미구현 — 운영 시 '정상 흐름 0 / 부하 시 증가' 신호가 관측 불가능.",
      "evidence": "PG-CONFIRM-LISTENER-SPLIT.md:767 'F3 | 좀비 폴링이 회수한 row 수 | Micrometer counter pg_inbox.zombie_recovered_total{status=PENDING|IN_PROGRESS}' / PgInboxPollingWorker.java:44 'ZOMBIE_FAIL_COUNTER_NAME = \"pg_inbox.zombie_fail_total\"'",
      "suggestion": "PgInboxPollingWorker 에 zombieRecoveredCounter = Counter.builder('pg_inbox.zombie_recovered_total').tag('status', ...) 추가. recoverPendingZombies / recoverInProgressZombies 의 processSafely 안에서 action.run() 성공 후 increment. PgInboxImmediateWorker 측에도 정상 처리 카운터 (pg_inbox.process_success_total 또는 동일 카운터 status=PENDING 태그) 추가 가능 — F3 신호 강화."
    },
    {
      "severity": "minor",
      "checklist_item": "TX 경계 봉인 정합 (handleTerminal)",
      "location": "pg-service/src/main/java/com/hyoguoo/paymentplatform/pg/application/service/PgConfirmService.java:142-164",
      "problem": "handleTerminal 에 @Transactional 명시 + Javadoc 에 'pg_outbox save + publishEvent 가 동일 active TX 안에서 실행되어야 AFTER_COMMIT 등록' 이라고 적혀 있으나, processCommand (PgConfirmService.java:78) 가 같은 클래스 안에서 this.handleTerminal(inbox) 직접 호출 → Spring AOP proxy 우회 → @Transactional 무력화. 실제로는 OutboxReadyEventHandler 의 fallbackExecution=true 가 안전망 역할로 동작은 정상이지만, Javadoc 표현이 실제와 불일치.",
      "evidence": "PgConfirmService.java:78-93 processCommand 가 private + @Transactional 없음, line 91 'handleTerminal(inbox)' 직접 호출 / PgConfirmService.java:142 '@Transactional protected void handleTerminal' / OutboxReadyEventHandler.java:35 '@TransactionalEventListener(phase=AFTER_COMMIT, fallbackExecution=true)' / PITFALLS.md §3 '@Transactional 안에서 동기 Kafka publish' 처방 짝패턴",
      "suggestion": "옵션 A: handleTerminal 을 별 빈 (PgInboxTerminalService) 으로 추출해 proxy 통과 보장. 옵션 B: handle() public 메서드에 @Transactional 명시 + processCommand 전체 한 TX 묶음 (단 listener TX timeout=5 룰 영향 검토). 옵션 C: 현재 상태 인정 + Javadoc 을 'OutboxReadyEventHandler.fallbackExecution=true 가 TX 부재 안전망' 으로 정정. 본 토픽 sealing 후속 또는 후속 토픽 결정."
    },
    {
      "severity": "minor",
      "checklist_item": "NONE 폐기 잔재",
      "location": "pg-service/src/main/java/com/hyoguoo/paymentplatform/pg/core/common/log/EventType.java:12,51 + pg-service/src/main/java/com/hyoguoo/paymentplatform/pg/infrastructure/repository/PgInboxRepositoryImpl.java:21",
      "problem": "NONE 폐기 후에도 EventType enum 상수 PG_CONFIRM_NONE_TO_IN_PROGRESS, PG_INBOX_AMOUNT_NONE_TO_IN_PROGRESS_PREEMPTED 가 잔존. 이름이 도메인 의미와 misleading (실제로는 PENDING 진입). PgInboxRepositoryImpl Javadoc 도 'status=NONE 조건으로 CAS 한다' stale 문장 잔재.",
      "evidence": "EventType.java:12 'PG_CONFIRM_NONE_TO_IN_PROGRESS' / EventType.java:51 'PG_INBOX_AMOUNT_NONE_TO_IN_PROGRESS_PREEMPTED' / PgInboxRepositoryImpl.java:21 'JPQL UPDATE 의 status=NONE 조건으로 CAS 한다' Javadoc",
      "suggestion": "EventType 두 상수를 PG_CONFIRM_PENDING_INSERT, PG_INBOX_AMOUNT_PENDING_PREEMPTED 로 rename. PgInboxRepositoryImpl Javadoc 의 NONE 문장 삭제 또는 PENDING 으로 정정. 단순 cosmetic 이라 본 토픽 sealing 안에서 처리하거나 다음 minor housekeeping 으로 미룸."
    }
  ],

  "previous_round_ref": null,
  "delta": null,

  "unstuck_suggestion": null
}
```
