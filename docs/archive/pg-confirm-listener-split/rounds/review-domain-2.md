# review-domain-2

**Topic**: PG-CONFIRM-LISTENER-SPLIT
**Round**: 2
**Persona**: Domain Expert
**Previous round**: review-domain-1.md

## Reasoning

Round 1 도메인 5건 (major #1, #2, minor #3, #4, #5) 의 흡수 결과를 코드와 직접 교차 검증했다. M1 dispatch 분기 + M2 PgTerminalReemitService 분리 + M3 zombie_recovered_total 카운터 + M5 NONE rename 4건은 깨끗하게 흡수됐다. M4 IN_PROGRESS SKIP LOCKED 는 Round 1 finding #2 의 microsecond race window 는 차단하지만 plan SOT (§1.6 line 454 — `WHERE status=IN_PROGRESS FOR UPDATE SKIP LOCKED`) 의 봉인 그대로다. 그러나 **lock TX 가 SELECT 만 하고 즉시 commit → updated_at 미갱신** 이라 polling cycle (5s) + invokeVendor read-timeout (10s) 조합에서 같은 row 가 다음 cycle 에 재발견될 수 있는 sequential race 가 잔존. 다만 downstream Lua dedup token 멱등성 + DuplicateApprovalHandler 보정 경로가 자금 손실은 차단하므로 minor 로 강등 (이미 알려진 한계 + idempotent 재발행 설계 인정 범위).

## Domain risk checklist

- [x] `paymentKey` / `orderId` / 카드번호 등이 plaintext 로그에 노출되지 않음 — Round 1 흡수 작업으로 신규 노출 없음. M1 dispatch 분기 LogFmt 도 inboxId / orderId / status 만.
- [x] 보상 / 취소 로직에 멱등성 가드 존재 — Round 1 #1 (dispatch 분기 누락) 흡수 완료. PgInboxImmediateWorker.process 가 PENDING / IN_PROGRESS / terminal / 부재 4분기 모두 처리 (PgInboxImmediateWorker.java:173-198). DuplicateApprovalHandler 5분기 경로는 그대로 유지.
- [x] PG 가 반환하는 "이미 처리됨" 응답이 맹목 수용되지 않음 — 변경 없음, 정상.
- [x] 상태 전이가 불변식을 위반하지 않음 — M2 PgTerminalReemitService 가 도메인 가드 (storedStatusResult null/blank 검사) 유지. PgInbox.markInProgress/Approved/Failed/Quarantined 사전 가드 그대로.
- [P] race window 가 있는 경로에 락 / 트랜잭션 격리 고려됨 — Round 1 #2 의 microsecond race 는 `selectInProgressForUpdateSkipLocked` 로 차단. 그러나 lock TX 가 SELECT-only + updated_at 미갱신 → polling rediscovery sequential race 잔존 (Findings #1 — minor 강등).
- [x] 관측 가능 acceptance 신호 정합 — M3 `pg_inbox.zombie_recovered_total{status=PENDING|IN_PROGRESS}` 두 태그 카운터 등록 확인 (PgInboxPollingWorker.java:81-88) + processSafely 안에서 success branch increment (line 149).

## 도메인 관점 추가 검토

### 1. M1 — Immediate worker dispatch 분기 (Round 1 #1 흡수)
- `PgInboxImmediateWorker.process` (PgInboxImmediateWorker.java:173-198) 가 4분기 처리:
  - 부재 → warn LogFmt + return (`reason=NOT_FOUND`)
  - PENDING → `processor.processPending(inboxId)`
  - IN_PROGRESS → `processor.processInProgressZombie(inboxId)`
  - terminal (APPROVED/FAILED/QUARANTINED) → info LogFmt + return (`reason=TERMINAL_SKIP`)
- `inboxRepository.findById(inboxId)` 가 read-only 조회 — TX 없음. 이후 분기에 따라 별 메서드 호출. dispatch 자체는 lock-free 정합.
- 단위 테스트 (`PgInboxImmediateWorkerTest.java:104-120`) 가 PENDING / IN_PROGRESS 두 분기 모두 검증. ✓

### 2. M4 — IN_PROGRESS SKIP LOCKED 락 선점 (Round 1 #2 흡수)
- `PgInboxRepositoryImpl.selectInProgressForUpdateSkipLocked` (line 188-196):
  - `@Transactional` opens TX_lock
  - `selectForUpdateSkipLockedInProgress(inboxId)` native query (`SELECT id FROM pg_inbox WHERE id=? AND status='IN_PROGRESS' FOR UPDATE SKIP LOCKED`) — JpaPgInboxRepository.java:152-155
  - 0 row → Optional.empty()
  - 1 row → findById + toDomain + return
  - TX_lock commits → row lock 해제
- `PgInboxProcessor.processInProgressZombie` (line 115-136) 가 진입 직후 호출 → empty 시 silent return.
- Testcontainers 동시성 테스트 (`PgInboxRepositoryImplTest.java:343-380`) 가 두 스레드 동시 호출 시 정확히 1개만 row 획득함을 검증 ✓.
- **그러나 lock TX 는 SELECT-only — updated_at 미갱신**. lock TX commit 후 row 는 status=IN_PROGRESS 그대로 + updated_at 그대로. 다음 polling cycle (5s) 가 같은 row 를 다시 zombie 로 발견할 수 있음 (벤더 read-timeout=10s, polling 임계=60s, 한 cycle 안에 회수 미완료 시).
- plan SOT §1.6 line 454 (`WHERE status=IN_PROGRESS FOR UPDATE SKIP LOCKED`) 의 명시는 충족. updated_at 갱신 또는 longer lock 보유는 plan 문서에 명시 안 됨.

### 3. M2 — handleTerminal self-invocation 해소 (Round 1 #3 흡수)
- `PgTerminalReemitService` 신규 외부 빈 (PgTerminalReemitService.java) — `@Service` + `@Transactional reemit(PgInbox)`.
- `PgConfirmService.processCommand` 의 terminal 분기 (line 86-87) 가 외부 빈 메서드 호출 → Spring AOP proxy 통과 → `@Transactional` 정상 동작.
- `pg_outbox save + publishEvent(PgOutboxReadyEvent)` 가 reemit() 의 단일 active TX 안에서 실행 → AFTER_COMMIT listener (`OutboxReadyEventHandler`) 정상 등록 보장.
- Javadoc 표현이 코드와 일치. 단위 테스트 (`PgTerminalReemitServiceTest.java:48-125`) 4건 검증 ✓.

### 4. M3 — `zombie_recovered_total` 카운터 (Round 1 #4 흡수)
- `PgInboxPollingWorker.ZOMBIE_RECOVERED_COUNTER_NAME = "pg_inbox.zombie_recovered_total"` (line 51).
- 두 태그 카운터 별도 등록 (PgInboxPollingWorker.java:81-88):
  - `zombieRecoveredPendingCounter` (`tag("status", "PENDING")`)
  - `zombieRecoveredInProgressCounter` (`tag("status", "IN_PROGRESS")`)
- `processSafely` (line 144-158) 가 action.run() 성공 후 `recoveredCounter.increment()` + LogFmt info emit. 실패 시 zombieFailCounter increment + ERROR 로그.
- §7.2 F3 acceptance 표 (`pg_inbox.zombie_recovered_total{status=PENDING|IN_PROGRESS}`) 와 정합 ✓.

### 5. M5 — NONE 잔재 rename (Round 1 #5 흡수)
- `EventType.PG_CONFIRM_PENDING_INSERT` (line 13) — 기존 `PG_CONFIRM_NONE_TO_IN_PROGRESS` rename.
- `EventType.PG_INBOX_AMOUNT_PENDING_PREEMPTED` (line 53) — 기존 `PG_INBOX_AMOUNT_NONE_TO_IN_PROGRESS_PREEMPTED` rename.
- `PgInboxRepositoryImpl` Javadoc 의 stale "status=NONE" 문장은 `grep "status=NONE 조건"` 0 hit 확인. 깨끗 ✓.
- 이름이 도메인 의미와 일치 (PENDING 진입). LogFmt 호출처도 정합.

### 6. 새로 도입된 race / TX 경계 / 멱등성 검토

- **PgTerminalReemitService 분리로 인한 TX 경계 변경**: 분리 전 `handleTerminal` 직접 호출은 self-invocation 으로 TX 가 무력화돼 `pgOutboxRepository.save` 의 per-method TX 1번 + publishEvent (TX 외부) 였다. 분리 후 외부 빈 호출은 `reemit` 의 단일 TX 안에서 두 작업이 묶임. 결과적으로 publishEvent 가 active TX 안에서 호출 → AFTER_COMMIT listener 의 fallbackExecution=true 안전망 의존이 사라지고 정상 등록. 동작은 사실상 강화 (의도와 일치).
- **selectInProgressForUpdateSkipLocked 의 lock TX 짧음**: lock TX 가 SELECT-only 라 ms 단위로 끝남. 이후 invokeVendor 는 lock 외부에서 실행 → 다른 워커가 같은 row 의 lock 을 다시 획득 가능 (microsecond race 차단, sequential race 미차단). Findings #1 — minor.
- **dispatch 분기 안 read-only findById**: PgInboxImmediateWorker.process 가 호출하는 `inboxRepository.findById` (PgInboxRepositoryImpl.java:48-50) 는 단순 JPA findById — TX 없이도 안전. status 분기 후 호출하는 processPending / processInProgressZombie 가 각자 TX 시작.
- **terminal 재수신 race**: M2 후 `pgTerminalReemitService.reemit` 이 외부 빈 호출. 동일 orderId 의 terminal inbox 에 동시 호출 두 번 발생 시? `PgConfirmService.handle` 입구에 `eventDedupeStore.markSeen(eventUuid)` 가드 있음 (PgConfirmService.java:58-62). 같은 eventUuid 면 두 번째 호출은 즉시 return. 다른 eventUuid 로 같은 terminal orderId 에 두 번 도달 시 두 번 reemit → 두 outbox INSERT → 두 publish. 그러나 storedStatusResult 가 같으므로 payment-service 는 같은 메시지 두 개 수신 → Lua dedup token 으로 멱등 흡수. 도메인 영향 무시 가능 (재발행 설계가 idempotent 가정).

## Findings

### Finding #1 (minor) — `processInProgressZombie` lock TX 가 SELECT-only — updated_at 미갱신 → polling rediscovery sequential race 잔존

`PgInboxRepositoryImpl.selectInProgressForUpdateSkipLocked` (line 188-196) 의 `@Transactional` lock TX 는 `selectForUpdateSkipLockedInProgress` native query 1건 + `findById` 1건만 수행하고 즉시 commit한다. `updated_at` 컬럼은 갱신 안 됨. lock TX 가 commit 되는 즉시 row-level lock 해제 → 같은 row 에 대한 다음 SKIP LOCKED 호출은 다시 성공한다.

이 결과로 M4 가 차단하는 race 는 **microsecond 동시 진입** 만이다. 그러나 실제 도메인에서 흥미로운 race 는 **polling cycle 5s + 벤더 read-timeout 10s + zombie 임계 60s** 조합 안에서 발생한다:

```
t=0~60s: row IN_PROGRESS, updated_at = t=0
t=60s: cycle N 발견 → processInProgressZombie 진입 → lock 획득(microseconds) → release → invokeVendor 시작
t=65s: cycle N+1. updated_at(=0) < threshold(=5) → 같은 row 재발견. processInProgressZombie 진입.
       lock 획득 (이전 cycle 의 lock TX 는 이미 commit, row free) → release → 두 번째 invokeVendor 시작
t=70s: 첫 invokeVendor 완료. applyOutcome (TX_B) → handleSuccess → pg_outbox INSERT + transitToApproved + publishEvent
t=75s: 두 번째 invokeVendor 완료 (벤더 idempotency-key 로 ALREADY_PROCESSED → HandledInternally outcome).
       applyOutcome → handleDuplicate → DuplicateApprovalHandler.handleDuplicateApproval →
       pg DB 존재 (방금 APPROVED) + amount 일치 → reemitStoredStatus → enqueueOutbox 추가 INSERT + publish
```

**도메인 영향**:
- 자금 손실 0 (downstream Lua atomic dedup token `decrement:done:{orderId}` / `compensation:done:{orderId}` SETNX P8D 가 결제 단위 멱등 보장).
- 두 번째 publish 는 storedStatusResult 동일 — payment-service `markPaymentAsDone` 의 첫 호출은 정상 audit, 두 번째 호출은 `IN_PROGRESS → DONE` 가드가 throw → DefaultErrorHandler 5회 retry 후 `payment.events.confirmed.dlq` 진입 → DLQ noise.
- 벤더 호출 2회 — Toss/NicePay rate-limit (운영 plan 별) 에 영향. ALREADY_PROCESSED 응답이라도 카운터 소진.
- 운영 시 `pg_inbox.zombie_recovered_total{status=IN_PROGRESS}` 가 같은 inboxId 에 대해 2번 increment — 카운터 정상 신호이지만 DLQ noise 와 함께 발생 시 사고 진단 시점에 혼동.

**위험 등급 minor 강등 사유**:
- plan SOT §1.6 line 454 (`WHERE status=IN_PROGRESS FOR UPDATE SKIP LOCKED`) 의 명시 그대로 구현됨. updated_at 갱신 또는 longer lock 보유는 plan 명시 안 됨 — 본 토픽 봉인 안에서는 plan 충족.
- 트리거 조건 까다로움 (벤더 timeout 임박 + zombie 임계 정확히 만족). 정상 환경에서 빈도 낮음.
- 자금 손실 0 — Lua dedup token + DuplicateApprovalHandler 의 reemitStoredStatus 경로가 idempotent 재발행 설계로 작동.
- DLQ noise 는 사고 가시화 영향 있으나, payment-service 측 retry 5회 + DLQ 정책의 알려진 비용 안.

- **location**: `pg-service/src/main/java/com/hyoguoo/paymentplatform/pg/infrastructure/repository/PgInboxRepositoryImpl.java:188-196` + `pg-service/src/main/java/com/hyoguoo/paymentplatform/pg/infrastructure/repository/JpaPgInboxRepository.java:152-155`
- **suggestion**: 후속 토픽 또는 PHASE2 에서 다음 중 택 1.
  - 옵션 A: lock TX 안에서 `updated_at = now` UPDATE 추가 — `selectForUpdateSkipLockedInProgress` 이후 `UPDATE pg_inbox SET updated_at = :now WHERE id = :inboxId AND status='IN_PROGRESS'` 1건 수행. polling threshold 가 즉시 미스 → rediscovery 차단. 부작용: `updated_at` 의 의미가 "마지막 처리 시각" 이 됨 (현재는 "마지막 상태 전이 시각") — 다른 쿼리 영향 검토 필요.
  - 옵션 B: 별 컬럼 `processing_started_at` 추가 → polling threshold 를 이 컬럼 기준으로 변경. lock TX 안에서 `processing_started_at = now` UPDATE. updated_at 의미는 보존.
  - 옵션 C: 현재 상태 인정. CONCERNS.md 에 "polling rediscovery race during long-running invokeVendor" 알려진 한계 등록 + idempotent 재발행 설계가 cover 한다는 근거 정리. 본 토픽 봉인 안에서 처리.

### Finding #2 (n/a — 정보) — `eventUuid = UUID.randomUUID()` 가 outbox INSERT 마다 새 값 → 재발행 메시지가 payment-service 측 dedupe 로 흡수되지 않음

PgVendorCallService.buildApprovedPayload (line 246-257) / DuplicateApprovalHandler.buildApprovedPayload (line 321-326) / PgVendorCallService.buildCommandPayload (line 266-281) 모두 `UUID.randomUUID()` 를 매번 생성. payment-service 측 `EventDedupeStore` (Redis SET NX EX) 는 eventUuid 기준 dedupe — 같은 orderId 의 두 번째 outbox INSERT (Finding #1 race 에서 발생 가능) 는 새 eventUuid 라 dedupe 통과 → 두 번째 메시지가 onConfirm 으로 진입.

이는 **의도된 설계** (CONFIRM-FLOW.md §13: payment-service 측 메시지 dedupe 는 Spring Kafka native error handler + Lua atomic dedup token 으로 일원화됨, EventDedupeStore lease 는 폐기). 같은 orderId 두 번째 메시지가 들어와도 Lua atomic 차감/보상 dedup token 이 결제 단위 멱등 보장. 본 토픽 범위 외 — 정보성 finding.

- **location**: `pg-service/src/main/java/com/hyoguoo/paymentplatform/pg/application/service/PgVendorCallService.java:249,262,274` + `DuplicateApprovalHandler.java:324,329`
- **suggestion**: 변경 불요. 본 finding 은 Finding #1 의 도메인 영향 분석 보조용. CONFIRM-FLOW.md §13 의 멱등성 layer 설계가 cover.

## JSON

```json
{
  "stage": "code",
  "persona": "domain-expert",
  "round": 2,
  "task_id": null,

  "decision": "pass",
  "reason_summary": "Round 1 도메인 5건 흡수 결과 교차 검증 완료. M1 dispatch 분기 (PENDING/IN_PROGRESS/terminal/부재 4분기) + M2 PgTerminalReemitService 외부 빈 분리 (proxy 통과) + M3 zombie_recovered_total 카운터 (status=PENDING|IN_PROGRESS 2태그) + M5 NONE rename 4건은 깨끗하게 흡수. M4 IN_PROGRESS SKIP LOCKED 는 plan SOT §1.6 line 454 의 봉인 그대로 구현 — microsecond race 는 차단. 다만 lock TX 가 SELECT-only + updated_at 미갱신 → polling rediscovery sequential race 잔존하나 자금 손실 0 (Lua dedup token + DuplicateApprovalHandler reemit idempotent) + 트리거 조건 까다로움 (벤더 timeout 임박 + zombie 임계 정확히 만족) → minor 강등. 새 critical 또는 새 race window 도입 0. 294 PASS / 0 FAIL 통과.",

  "checklist": {
    "source": "_shared/checklists/code-ready.md",
    "items": [
      {
        "section": "domain risk",
        "item": "race window 가 있는 경로에 락 / 트랜잭션 격리 고려됨",
        "status": "partial",
        "evidence": "Round 1 #2 의 microsecond race 는 selectInProgressForUpdateSkipLocked native query (JpaPgInboxRepository.java:152-155) + Testcontainers 동시성 테스트 (PgInboxRepositoryImplTest.java:343-380) 로 차단. 그러나 lock TX 가 SELECT-only + updated_at 미갱신 → polling cycle (5s) + invokeVendor read-timeout (10s) 조합에서 sequential rediscovery 잔존 (Findings #1 — minor)."
      },
      {
        "section": "domain risk",
        "item": "보상 / 취소 로직에 멱등성 가드 존재 (워커 dispatch 분기)",
        "status": "yes",
        "evidence": "PgInboxImmediateWorker.process (PgInboxImmediateWorker.java:173-198) 가 inboxRepository.findById + status 분기 4분기 처리: PENDING → processPending, IN_PROGRESS → processInProgressZombie, terminal → skip + LogFmt info, 부재 → warn + return. 단위 테스트 (PgInboxImmediateWorkerTest.java:104-120) 가 두 분기 모두 검증."
      },
      {
        "section": "domain risk",
        "item": "TX 경계 봉인 정합 (handleTerminal proxy)",
        "status": "yes",
        "evidence": "PgTerminalReemitService (PgTerminalReemitService.java) 외부 빈 분리 — @Service + @Transactional reemit(PgInbox). PgConfirmService.processCommand line 86-87 이 외부 빈 메서드 호출 → Spring AOP proxy 경유. pg_outbox save + publishEvent 가 단일 active TX. 단위 테스트 (PgTerminalReemitServiceTest.java) 4건 검증."
      },
      {
        "section": "domain risk",
        "item": "관측 가능 acceptance 신호 정합",
        "status": "yes",
        "evidence": "PgInboxPollingWorker (line 51, 81-88) 가 pg_inbox.zombie_recovered_total 카운터를 status=PENDING / IN_PROGRESS 두 태그로 등록. processSafely (line 144-158) 가 action.run() 성공 후 recoveredCounter.increment() + LogFmt info emit. §7.2 F3 acceptance 정합."
      },
      {
        "section": "domain risk",
        "item": "NONE 폐기 잔재",
        "status": "yes",
        "evidence": "EventType.PG_CONFIRM_PENDING_INSERT (line 13) + EventType.PG_INBOX_AMOUNT_PENDING_PREEMPTED (line 53) rename. PgInboxRepositoryImpl Javadoc 의 stale 'status=NONE' 문장 0 hit (grep 확인). LogFmt 호출처 정합."
      }
    ],
    "total": 5,
    "passed": 4,
    "failed": 0,
    "not_applicable": 0,
    "partial": 1
  },

  "scores": {
    "correctness": 0.90,
    "conventions": 0.92,
    "discipline": 0.92,
    "test_coverage": 0.88,
    "domain": 0.85,
    "mean": 0.89
  },

  "findings": [
    {
      "severity": "minor",
      "checklist_item": "race window 가 있는 경로에 락 / 트랜잭션 격리 고려됨",
      "location": "pg-service/src/main/java/com/hyoguoo/paymentplatform/pg/infrastructure/repository/PgInboxRepositoryImpl.java:188-196 + pg-service/src/main/java/com/hyoguoo/paymentplatform/pg/infrastructure/repository/JpaPgInboxRepository.java:152-155",
      "problem": "selectInProgressForUpdateSkipLocked 의 @Transactional lock TX 가 SELECT-only (selectForUpdateSkipLockedInProgress native query + findById 만 수행) → updated_at 미갱신 → lock TX commit 즉시 row-level lock 해제. polling cycle (5s) + 벤더 read-timeout (10s) + zombie 임계 (60s) 조합에서 같은 row 가 다음 cycle 에 다시 zombie 로 발견되어 sequential 두 번째 processInProgressZombie 진입 가능. M4 SKIP LOCKED 는 microsecond 동시 진입만 차단 — sequential rediscovery 미차단. 결과적으로 두 번 invokeVendor 호출 + 두 번 outbox INSERT + 두 번 publish 가능. 자금 손실 0 (Lua dedup token + DuplicateApprovalHandler reemit idempotent) 이지만 payment-service markPaymentAsDone 두 번째 호출이 terminal 재전이 throw → DLQ noise. 벤더 rate-limit 카운터 2배 소진.",
      "evidence": "PgInboxRepositoryImpl.java:188-196 selectInProgressForUpdateSkipLocked 안에 status 또는 updated_at UPDATE 0건 / JpaPgInboxRepository.java:152-155 selectForUpdateSkipLockedInProgress 가 SELECT id only / PgInboxPollingWorker.java:62-63 inProgressTimeoutMs default 60000 / pg-service application.yml: 5s polling cycle / INTEGRATIONS.md pg read-timeout 10000ms / Round 1 finding #2 시나리오와 동일 패턴 (단 M4 가 microsecond race 부분만 차단)",
      "suggestion": "후속 토픽 또는 PHASE2 처리. 옵션 A: lock TX 안에서 'UPDATE pg_inbox SET updated_at = :now WHERE id=:inboxId AND status=IN_PROGRESS' 1건 추가 → polling threshold 즉시 미스. 옵션 B: 별 컬럼 processing_started_at 추가 + polling threshold 를 이 컬럼 기준으로. 옵션 C: 현재 상태 인정 + CONCERNS.md 에 'polling rediscovery race during long invokeVendor' 등록. 본 토픽 봉인 안에서는 plan SOT 충족 + minor 위험 등급 → 처리 미룸."
    }
  ],

  "previous_round_ref": "docs/rounds/pg-confirm-listener-split/review-domain-1.md",
  "delta": {
    "domain#1 (IN_PROGRESS 채널 재적재 silent drop)": "resolved — PgInboxImmediateWorker.process (PgInboxImmediateWorker.java:173-198) 가 inboxRepository.findById + status 4분기 dispatch. 단위 테스트 (PgInboxImmediateWorkerTest.java:104-120) 가 PENDING / IN_PROGRESS 두 분기 검증.",
    "domain#2 (processInProgressZombie row 락 부재 → outbox 중복 INSERT + Kafka 중복 publish)": "partial — selectInProgressForUpdateSkipLocked native query (JpaPgInboxRepository.java:152-155) + Testcontainers 동시성 테스트 (PgInboxRepositoryImplTest.java:343-380) 로 microsecond race 차단. plan SOT §1.6 line 454 의 봉인 그대로. 그러나 lock TX 가 SELECT-only + updated_at 미갱신 → polling rediscovery sequential race 잔존 (Findings #1 — minor 강등).",
    "domain#3 (handleTerminal self-invocation)": "resolved — PgTerminalReemitService (PgTerminalReemitService.java) 외부 빈 분리, PgConfirmService.processCommand line 87 이 외부 빈 호출. Spring AOP proxy 통과 → @Transactional 정상. 단위 테스트 4건 (PgTerminalReemitServiceTest.java).",
    "domain#4 (zombie_recovered_total 미구현)": "resolved — PgInboxPollingWorker.ZOMBIE_RECOVERED_COUNTER_NAME (line 51) + 두 태그 카운터 (line 81-88) 등록 + processSafely 안에서 success branch increment (line 149). §7.2 F3 acceptance 정합.",
    "domain#5 (EventType NONE 잔재)": "resolved — EventType.PG_CONFIRM_PENDING_INSERT / PG_INBOX_AMOUNT_PENDING_PREEMPTED rename. PgInboxRepositoryImpl Javadoc 의 'status=NONE' stale 문장 0 hit."
  },

  "unstuck_suggestion": null
}
```
