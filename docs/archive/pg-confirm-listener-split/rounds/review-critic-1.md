# review-critic-1

**Topic**: PG-CONFIRM-LISTENER-SPLIT
**Round**: 1
**Persona**: Critic

## Reasoning

PCS-1~PCS-16 16 태스크 GREEN + 위키 + 영구 문서 동기화 모두 진행됐고 토픽 §1.5 / §1.7 / §1.8 / §1.9 결정과 §3 인벤토리 / §7 acceptance 의 큰 골격은 코드에 들어와 있다. 다만 토픽 §1.6 (워커 처리자 두 진입점 분리) + §1.4 (좀비 폴링 SKIP LOCKED) + §7.2 F3 (`zombie_recovered_total` 카운터) + plan D-F3 (`handleTerminal` `@Transactional` self-invocation 우회) 4건이 코드 수준에서 빠져 있거나 약화됐다. critical 0 / major 4 / minor 3 — **revise**.

## Checklist judgement

기준: `_shared/checklists/code-ready.md` Gate checklist (review 단계 적용).

| 섹션 | 항목 | 판정 | 근거 |
|---|---|---|---|
| test gate | 전체 `./gradlew test` 통과 | yes | 마지막 커밋 메시지(`integrationTest 4/4 PASS, test 281/281 PASS`) + 16개 태스크 GREEN 커밋 인덱스. 로컬 재실행은 Gradle wrapper 환경 문제로 미가능 — 커밋 SoT 신뢰. |
| test gate | 신규/수정 business logic 테스트 커버리지 | yes | PCS-2/4/6/7/8/9/10/11/12/13/15 모두 단위/통합 테스트 동반. |
| test gate | state machine 전이 `@ParameterizedTest @EnumSource` | yes | `PgInboxStatusTest`, `PgInboxTest` (PCS-2). |
| 토픽 §1.1~§1.9 결정 반영 | §1.1 listener TX 경계 (`PgInboxPendingService` `@Transactional(timeout=5)`) | yes | `PgInboxPendingService.java` 봉인. |
| 토픽 §1.1~§1.9 결정 반영 | §1.2 `PgInboxChannel` cap=1024 | yes | `PgInboxChannel.java` 봉인 + 메트릭 게이지. |
| 토픽 §1.1~§1.9 결정 반영 | §1.3 `PgInboxImmediateWorker` worker=5 + 두 진입점 dispatch | **no (major M1)** | `processPending` 단독 호출. `processInProgressZombie` 분기 없음. |
| 토픽 §1.1~§1.9 결정 반영 | §1.4 `PgInboxPollingWorker` 60s 통일 + SKIP LOCKED | **no (major M4)** | 좀비 폴링 JPQL 에 `FOR UPDATE SKIP LOCKED` 없음. |
| 토픽 §1.1~§1.9 결정 반영 | §1.5 PENDING 추가 + NONE 폐기 | yes | Flyway V2 + `PgInboxStatus` enum. |
| 토픽 §1.1~§1.9 결정 반영 | §1.6 워커 진입점 두 메서드 분리 | partial (M1 참조) | `PgInboxProcessUseCase` 두 메서드 선언 OK. 워커가 분기 안 함. |
| 토픽 §1.1~§1.9 결정 반영 | §1.7 위키 갱신 (`pg-confirm-flow.md` + `outbox-channel-dispatch.md`) | yes | 위키 PCS-16 commit `639b973`. |
| 토픽 §1.1~§1.9 결정 반영 | §1.8 보정 경로 PENDING 우회 + repo 메서드 4종 | yes | `DuplicateApprovalHandler` `transitDirectToTerminal` / `transitDirectToInProgress` 호출. |
| 토픽 §3 인벤토리 누락 0 | 모든 신규/변경 컴포넌트 코드 존재 | partial | M3 (`pg_inbox.zombie_recovered_total` 카운터 빠짐) 외 컴포넌트 존재. |
| 토픽 §7 acceptance 검증 신호 | A1 (listener 내 벤더 0) | yes | Integration test `listenerDoesNotCallVendor_whenNewMessage` PASS. |
| 토픽 §7 acceptance 검증 신호 | A2 (벤더 지연 격리) | yes | Integration test `listenerThroughputUnaffectedByVendorDelay` < 1s PASS. |
| 토픽 §7 acceptance 검증 신호 | A3 (좀비 회수) | yes | Integration test 실행. 단 worker dispatch 우회로 직접 `processInProgressZombie` 호출 (M1 영향). |
| 토픽 §7 acceptance 검증 신호 | A4 (보정 경로 PENDING 우회) | yes | Integration test PASS. |
| 토픽 §7 acceptance 검증 신호 | A5 (active TX publishEvent) | yes | `PgInboxPendingServiceTest` 5케이스. |
| 토픽 §7 acceptance 검증 신호 | F1 (채널 가득 빈도) | yes | `pg_inbox_channel_queue_size` Gauge. |
| 토픽 §7 acceptance 검증 신호 | F2 (워커 RuntimeException 카운터) | yes | `pg_inbox.process_fail_total`. |
| 토픽 §7 acceptance 검증 신호 | F3 (좀비 회수 카운터) | **no (major M3)** | `pg_inbox.zombie_recovered_total` 부재. fail 카운터만 존재. |
| 토픽 §7 acceptance 검증 신호 | F4 (listener TX timeout) | yes | `pg_inbox.listener_tx_timeout_total` + `PG_INBOX_LISTENER_TX_TIMEOUT`. |
| discuss / plan finding 흡수 | C-F1~F6, D-F1~F5, PC-F1~F7, plan-domain D-F1~F4 | partial | D-F3 (`handleTerminal` `@Transactional`) self-invocation 으로 무력화 (M2). |
| 거울 패턴 (PgOutbox* ↔ PgInbox*) | infrastructure layer 패키지 + 시그니처 | yes | channel/listener/scheduler 패키지 일치. |
| 헥사고날 layer 룰 | port 위치 + listener/scheduler/channel infrastructure 위치 | yes | `port/in/PgInboxProcessUseCase` + `infrastructure/listener/InboxReadyEventHandler` + `infrastructure/scheduler/*` + `infrastructure/channel/*`. |
| 멱등성 / TX 경계 / SKIP LOCKED | listener TX(timeout=5) / TX_A SKIP LOCKED PENDING / TX_B 결과 반영 | partial | PENDING 측 SKIP LOCKED OK. IN_PROGRESS 좀비 / 폴링 측 SKIP LOCKED 부재 (M4). `handleTerminal` TX self-invocation (M2). |
| 위키 vs 코드 정합 | `pg-confirm-flow.md` / `outbox-channel-dispatch.md` ↔ 코드 | yes | 위키 본문 + 영구 문서 갱신. |

## Findings

### M1 (major) — 워커 처리자 status 분기 누락

- **checklist_item**: 토픽 §1.3 / §1.6 워커 두 진입점 dispatch
- **location**: `pg-service/src/main/java/com/hyoguoo/paymentplatform/pg/infrastructure/scheduler/PgInboxImmediateWorker.java:154-163`
- **problem**: `process(Long inboxId)` 가 `processor.processPending(inboxId)` 만 호출. inboxId 의 row status 분기(PENDING vs IN_PROGRESS) 로직 부재 → 채널로 들어오는 IN_PROGRESS 자기 재진입 작업이 워커에서 silently 무시되고 60s 좀비 폴링까지 대기.
- **evidence**: 토픽 §1.6 line 327 ("워커 진입 시 status 를 먼저 SELECT 후 분기") + §1.6 line 439 ("IN_PROGRESS 재진입 | AFTER_COMMIT publishEvent (기존 inboxId) | `processInProgressZombie(inboxId)`") + §3 인벤토리 line 655 ("`PgInboxImmediateWorker` ... `processPending` / `processInProgressZombie` 호출") 명시. `PgConfirmService.handleActiveInbox` (line 127-133) 가 PENDING / IN_PROGRESS 모두 동일 publishEvent 경로로 보냄. 워커 측 분기 로직 부재.
- **suggestion**: `PgInboxImmediateWorker.process(inboxId)` 에서 `inboxRepository.findById(inboxId)` 로 status 확인 후 PENDING → `processPending`, IN_PROGRESS → `processInProgressZombie` 로 dispatch. 또는 `processPending` 안에서 `transitPendingToInProgress` 가 false 인 경우 status 재확인 후 IN_PROGRESS 면 `processInProgressZombie` 로 fall-through. 단위 테스트 `workerLoop_inProgressJob_callsProcessInProgressZombie` 추가.

### M2 (major) — `handleTerminal` `@Transactional` self-invocation 우회로 무력화

- **checklist_item**: discuss/plan D-F3 (`handleTerminal` TX 경계 봉인)
- **location**: `pg-service/src/main/java/com/hyoguoo/paymentplatform/pg/application/service/PgConfirmService.java:71,91,142-164`
- **problem**: `processCommand` (line 78) 안에서 `handleTerminal(inbox)` (line 91) 를 호출 — 같은 `PgConfirmService` 인스턴스의 self-invocation. Spring AOP proxy 는 외부 호출만 가로채므로 `@Transactional` (line 142) 가 적용되지 않는다. Plan PCS-9 D-F3 흡수 사유 ("pgOutboxRepository.save + publishEvent 가 같은 active TX 안에서 실행되어야 `@TransactionalEventListener(AFTER_COMMIT)` 가 등록된다") 무효화.
- **evidence**: PLAN line 338 ("TX 없이 `JpaRepository.save` 가 자체 TX 로 즉시 커밋되면 후속 `publishEvent` 는 active TX 외부 → AFTER_COMMIT 미등록 → 채널 적재 0 → §1.6 안 B 채택 사유 (latency 우위) 무효화"). 현재 코드에서 `processCommand(...)` → `handleTerminal(...)` 는 동일 인스턴스 직접 호출이라 Spring CGLIB/JDK proxy 가 가로채지 않는다. `OutboxReadyEventHandler` 의 `fallbackExecution=true` 가 latency 손실은 막지만 plan 봉인 의도(active TX) 위반.
- **suggestion**: 다음 중 하나로 정정 — (a) `handleTerminal` 을 별도 `@Service` (예: `PgTerminalReemitService`) 로 분리해 `PgConfirmService` 가 외부 빈으로 호출 (Spring proxy 작동), (b) `AopContext.currentProxy()` 로 self proxy 호출, (c) `PgConfirmService` 가 자기 인터페이스 빈을 주입해 호출. plan-supported (a) 권장. 단위 테스트에 `handle_terminalInbox_publishesEventInsideActiveTransaction` 추가 (TransactionSynchronizationManager.isActualTransactionActive() 검증).

### M3 (major) — `pg_inbox.zombie_recovered_total` Micrometer counter 부재

- **checklist_item**: 토픽 §7.2 F3 + §3 인벤토리 메트릭 row + plan PCS-13
- **location**: `pg-service/src/main/java/com/hyoguoo/paymentplatform/pg/infrastructure/scheduler/PgInboxPollingWorker.java:44,66-69` (counter 정의 없음)
- **problem**: 토픽 §7.2 F3 + §3 인벤토리 메트릭 row + plan PCS-13 본문이 모두 `pg_inbox.zombie_recovered_total{status=PENDING|IN_PROGRESS}` 카운터를 요구하지만 코드에 정의되지 않음. `PgInboxPollingWorker` 는 `pg_inbox.zombie_fail_total` (실패) 만 노출. 성공 회수 카운터가 없으므로 운영 시 회수 빈도 / 부하 신호 관측 불가. `EventType.PG_INBOX_ZOMBIE_RECOVERED_PENDING` / `PG_INBOX_ZOMBIE_RECOVERED_IN_PROGRESS` enum 만 선언되고 emit 코드 없음.
- **evidence**: 토픽 line 660 ("`pg_inbox_channel_queue_size` / `pg_inbox.process_fail_total` / `pg_inbox.zombie_recovered_total` 신규") + line 767 (F3 표). PLAN line 444 ("`pg_inbox.zombie_recovered_total{status=PENDING|IN_PROGRESS}` 카운터"). 코드 grep 결과 main/ 내 `zombie_recovered` 0건. `PgInboxPollingWorkerTest:118` 주석은 "zombie_recovered_total" 이라고 적었지만 실제로는 `ZOMBIE_FAIL_COUNTER_NAME` 검증 — 의도-구현 불일치.
- **suggestion**: `PgInboxPollingWorker` 에 `pg_inbox.zombie_recovered_total` Micrometer counter 추가 (status=PENDING/IN_PROGRESS 태그). `processSafely` 가 정상 종료(예외 미발생) 시 increment + `LogFmt.info(EventType.PG_INBOX_ZOMBIE_RECOVERED_*)` 호출. 단위 테스트 `poll_pendingZombiesRecovered_incrementsCounterWithPendingTag` / `poll_inProgressZombiesRecovered_incrementsCounterWithInProgressTag` 추가.

### M4 (major) — 좀비 폴링 + IN_PROGRESS 처리 SKIP LOCKED 부재

- **checklist_item**: 토픽 §1.4 + §1.6 SKIP LOCKED 룰
- **location**:
  - `pg-service/src/main/java/com/hyoguoo/paymentplatform/pg/infrastructure/repository/JpaPgInboxRepository.java:121-139` (findPending/InProgressZombieIdsBefore)
  - `pg-service/src/main/java/com/hyoguoo/paymentplatform/pg/application/service/PgInboxProcessor.java:114-141` (processInProgressZombie)
- **problem**: 토픽 §1.4 line 373 / 377 이 `WHERE ... FOR UPDATE SKIP LOCKED` 명시. §1.6 line 455 가 `processInProgressZombie` TX_A 검사 조건을 `WHERE id=? AND status=IN_PROGRESS FOR UPDATE SKIP LOCKED` 로 봉인. 실제 구현은 (a) JPQL `SELECT e.id FROM PgInboxEntity e WHERE e.status = ... AND ... < :before ORDER BY ...` — `FOR UPDATE SKIP LOCKED` 없음, (b) `processInProgressZombie` 가 `findById` (lock 없음) 후 곧장 벤더 호출 — TX_A 잠금 없음. 동일 row 가 (immediate 워커 + polling 워커) 또는 (다중 polling tick) 사이에서 race → 벤더 중복 호출. 멱등성 3-layer (Idempotency-Key + ALREADY_PROCESSED + `DuplicateApprovalHandler`) 가 흡수하지만 plan 이 명시한 race-prevention 1단계 layer 누락.
- **evidence**: 토픽 line 373 (`WHERE status=PENDING AND received_at < now - INTERVAL Ns FOR UPDATE SKIP LOCKED`) + line 377 (`WHERE status=IN_PROGRESS AND updated_at < now - INTERVAL 60s FOR UPDATE SKIP LOCKED`) + line 455 (표 `WHERE id=? AND status=IN_PROGRESS FOR UPDATE SKIP LOCKED`). 코드 grep: `selectForUpdateSkipLockedPending` 만 SKIP LOCKED 사용 (PCS-4 PENDING 측). 다른 zombie 측 query 들은 plain JPQL.
- **suggestion**: (a) `JpaPgInboxRepository.findPendingZombieIdsBefore` / `findInProgressZombieIdsBefore` 를 native query 로 전환 + `FOR UPDATE SKIP LOCKED` 추가, (b) `PgInboxProcessor.processInProgressZombie` 진입 시 `selectForUpdateSkipLockedInProgress(inboxId)` (신규) 로 lock 후 진행. 또는 멀티 인스턴스 race 검증을 PHASE2 로 명시 deferred 라면 토픽/PLAN 본문에 그렇게 적고 SKIP LOCKED 룰 완화 명시.

### m1 (minor) — `PgInboxAmountService` PENDING-bypass 룰 위반 (dead service 경유)

- **checklist_item**: 토픽 §1.8 보정 경로 PENDING 우회 룰
- **location**: `pg-service/src/main/java/com/hyoguoo/paymentplatform/pg/application/service/PgInboxAmountService.java:55-61, 119-135`
- **problem**: 토픽 §1.8 line 504 ("PENDING 진입은 listener 분기 (inbox 부재 신설) 전용. 보정 경로 (`DuplicateApprovalHandler`) 의 inbox 신설은 PENDING 거치지 않고 ...") 에서 PENDING-bypass 호출처는 `DuplicateApprovalHandler` 한정. `PgInboxAmountService` 가 `transitDirectToInProgress` (line 58) + `transitDirectToTerminal` (line 130) 호출. plan PCS-9 가 dead service 라 컴파일 에러 해소만 진행한다고 명시했지만 (코드 javadoc + 본문 명시 OK), 룰 자체는 위반.
- **evidence**: 코드 line 18-20 javadoc("이 서비스는 main 코드에서 호출처가 없는 dead service") + plan line 332 / 622 도 인지. 그러나 main 호출처 0이라도 enabled 상태로 남아 있으면 의도치 않은 활성화 시 룰 위반. 
- **suggestion**: 별 토픽으로 dead service 제거 처리 — `docs/context/TODOS.md` 또는 TC-15 본문에 "PgInboxAmountService 제거" 항목 추가. 본 토픽 범위 외라면 minor 로 인지.

### m2 (minor) — `PgInboxProcessor.buildRequest` javadoc stale TODO

- **checklist_item**: convention / 코드 청결성
- **location**: `pg-service/src/main/java/com/hyoguoo/paymentplatform/pg/application/service/PgInboxProcessor.java:42-46`
- **problem**: 클래스 javadoc 에 "현재 스키마 제약: pg_inbox 에 paymentKey / vendorType 컬럼 없음 ... TODO PCS-X: pg_inbox 스키마에 vendorType / paymentKey 컬럼 추가 후 정합 필요" 가 남아 있음. PCS-9 가 V3 migration 으로 두 컬럼을 실제 추가했고 `buildRequest` (line 157-165) 가 inbox 에서 직접 읽는 구현으로 갱신됐다 (line 150-152 javadoc 도 갱신됨). 클래스 레벨 javadoc 만 stale.
- **evidence**: line 42-46 vs line 150-156 javadoc 정합 불일치. PCS-15 implementer 가 `buildRequest()` 의 null 하드코딩을 실 값 사용으로 수정한 시점에 클래스 레벨 주석 갱신 누락.
- **suggestion**: 클래스 레벨 javadoc 의 "현재 스키마 제약" 단락 삭제 또는 "PCS-9 V3 migration 으로 paymentKey / vendorType 컬럼 정합" 으로 수정.

### m3 (minor) — `PgInboxRepositoryImpl` javadoc stale 메서드 참조

- **checklist_item**: convention / 코드 청결성
- **location**: `pg-service/src/main/java/com/hyoguoo/paymentplatform/pg/infrastructure/repository/PgInboxRepositoryImpl.java:16-22`
- **problem**: 클래스 javadoc 에 "{@link #transitNoneToInProgress(String, long)} 는 row 부재 시 INSERT 로 선점하고, 존재 시 JPQL UPDATE 의 status=NONE 조건으로 CAS 한다." 가 남아 있음. PCS-9 에서 `transitNoneToInProgress` 메서드는 삭제됐고 line 50 코멘트 ("PCS-9: transitNoneToInProgress 삭제 — 호출처 모두 교체 완료") 와 모순.
- **evidence**: line 17-22 와 line 50 비교.
- **suggestion**: 클래스 javadoc 을 신규 메서드 (`insertPending` / `transitPendingToInProgress` / `transitDirectToInProgress` / `transitDirectToTerminal` / `find{Pending,InProgress}ZombieIds`) 기준으로 갱신.

## JSON

```json
{
  "stage": "code",
  "persona": "critic",
  "round": 1,
  "task_id": null,
  "decision": "revise",
  "reason_summary": "16 태스크 GREEN + 위키/영구 문서 동기화 완료. 그러나 토픽 §1.6 워커 두 진입점 dispatch (M1), §7.2 F3 zombie_recovered_total 카운터 부재 (M3), §1.4/§1.6 SKIP LOCKED 누락 (M4), plan D-F3 handleTerminal @Transactional self-invocation 우회 (M2) 4건 major 미흡수 — revise.",
  "checklist": {
    "source": "_shared/checklists/code-ready.md",
    "items": [
      {"section": "test gate", "item": "전체 ./gradlew test 통과", "status": "yes", "evidence": "최종 commit 메시지 integrationTest 4/4 PASS, test 281/281 PASS + 16 태스크 GREEN 커밋"},
      {"section": "test gate", "item": "신규 business logic 테스트 커버리지", "status": "yes", "evidence": "PCS-2/4/6/7/8/9/10/11/12/13/15 모두 단위/통합 테스트 GREEN"},
      {"section": "test gate", "item": "state machine @ParameterizedTest @EnumSource", "status": "yes", "evidence": "PgInboxStatusTest, PgInboxTest"},
      {"section": "topic-coverage", "item": "§1.1 listener TX 경계", "status": "yes", "evidence": "PgInboxPendingService.java @Transactional(timeout=5)"},
      {"section": "topic-coverage", "item": "§1.2 PgInboxChannel cap=1024", "status": "yes", "evidence": "PgInboxChannel.java cap=1024 + 메트릭 게이지"},
      {"section": "topic-coverage", "item": "§1.3 워커 dispatch (PENDING + IN_PROGRESS)", "status": "no", "evidence": "PgInboxImmediateWorker.process line 156 processPending 단독 호출 — IN_PROGRESS 분기 없음 (M1)"},
      {"section": "topic-coverage", "item": "§1.4 좀비 폴링 SKIP LOCKED 60s", "status": "no", "evidence": "JpaPgInboxRepository.findPending/InProgressZombieIdsBefore JPQL 에 FOR UPDATE SKIP LOCKED 없음 (M4)"},
      {"section": "topic-coverage", "item": "§1.5 PENDING 추가 + NONE 폐기", "status": "yes", "evidence": "Flyway V2 + PgInboxStatus enum 5상태"},
      {"section": "topic-coverage", "item": "§1.6 워커 두 진입점 dispatch", "status": "no", "evidence": "M1 (워커 미분기). 입력포트 PgInboxProcessUseCase 두 메서드 선언 자체는 OK"},
      {"section": "topic-coverage", "item": "§1.7 위키 갱신 (2 페이지)", "status": "yes", "evidence": "wiki commit 639b973"},
      {"section": "topic-coverage", "item": "§1.8 보정 경로 PENDING 우회 + repo 메서드 4종", "status": "yes", "evidence": "DuplicateApprovalHandler.handleDbAbsent* + transitDirectTo*"},
      {"section": "topic-coverage", "item": "§3 인벤토리 누락 0", "status": "no", "evidence": "M3 (pg_inbox.zombie_recovered_total counter 부재)"},
      {"section": "acceptance", "item": "A1 listener 내 벤더 0", "status": "yes", "evidence": "Integration test listenerDoesNotCallVendor PASS"},
      {"section": "acceptance", "item": "A2 벤더 지연 격리", "status": "yes", "evidence": "Integration test listenerThroughputUnaffectedByVendorDelay < 1s"},
      {"section": "acceptance", "item": "A3 좀비 회수", "status": "yes", "evidence": "Integration test zombieRecovery_afterWorkerCrash. 단 직접 processInProgressZombie 호출 (M1 워커 dispatch 회피 사유)"},
      {"section": "acceptance", "item": "A4 보정 경로 PENDING 우회", "status": "yes", "evidence": "Integration test correctionPath_doesNotGoThroughPending PASS"},
      {"section": "acceptance", "item": "A5 active TX publishEvent", "status": "yes", "evidence": "PgInboxPendingServiceTest 5케이스. 단 handleTerminal 은 별 이슈 (M2)"},
      {"section": "acceptance", "item": "F1 채널 가득 빈도", "status": "yes", "evidence": "pg_inbox_channel_queue_size Gauge"},
      {"section": "acceptance", "item": "F2 워커 fail 카운터", "status": "yes", "evidence": "pg_inbox.process_fail_total"},
      {"section": "acceptance", "item": "F3 좀비 회수 카운터", "status": "no", "evidence": "M3 (recovered_total 부재, fail_total 만 존재)"},
      {"section": "acceptance", "item": "F4 listener TX timeout", "status": "yes", "evidence": "pg_inbox.listener_tx_timeout_total + EventType"},
      {"section": "finding-absorption", "item": "discuss/plan finding 흡수 정합", "status": "no", "evidence": "D-F3 handleTerminal @Transactional self-invocation 우회 (M2)"},
      {"section": "convention", "item": "거울 패턴 PgOutbox* ↔ PgInbox*", "status": "yes", "evidence": "channel/listener/scheduler 패키지 + 시그니처 거울 정합"},
      {"section": "convention", "item": "헥사고날 layer 룰", "status": "yes", "evidence": "port/in/PgInboxProcessUseCase + infrastructure/listener/InboxReadyEventHandler + scheduler/* + channel/*"},
      {"section": "domain-risk", "item": "멱등성 / TX 경계 / SKIP LOCKED", "status": "no", "evidence": "M2 (handleTerminal TX) + M4 (zombie SKIP LOCKED)"},
      {"section": "convention", "item": "위키 vs 코드 정합", "status": "yes", "evidence": "PCS-16 위키 + 영구 문서 동기화 완료"}
    ],
    "total": 26,
    "passed": 19,
    "failed": 6,
    "not_applicable": 0
  },
  "scores": {
    "correctness": 0.78,
    "conventions": 0.85,
    "discipline": 0.92,
    "test_coverage": 0.82,
    "domain": 0.74,
    "mean": 0.822
  },
  "findings": [
    {
      "severity": "major",
      "checklist_item": "토픽 §1.3 / §1.6 워커 두 진입점 dispatch",
      "location": "pg-service/src/main/java/com/hyoguoo/paymentplatform/pg/infrastructure/scheduler/PgInboxImmediateWorker.java:154-163",
      "problem": "process(Long inboxId) 가 processor.processPending 만 호출. IN_PROGRESS 자기 재진입 작업이 워커에서 silently 무시되고 60s 좀비 폴링까지 대기.",
      "evidence": "토픽 line 327 워커 진입 시 status SELECT 후 분기 + line 439 IN_PROGRESS 재진입 → processInProgressZombie + §3 인벤토리 line 655. PgConfirmService.handleActiveInbox 가 PENDING / IN_PROGRESS 모두 동일 publishEvent 경로로 보냄.",
      "suggestion": "PgInboxImmediateWorker.process 에서 inboxRepository.findById 로 status 확인 후 PENDING → processPending, IN_PROGRESS → processInProgressZombie dispatch. 또는 processPending 안에서 transitPendingToInProgress false 시 status 재확인 후 IN_PROGRESS fall-through."
    },
    {
      "severity": "major",
      "checklist_item": "discuss/plan D-F3 handleTerminal TX 경계 봉인",
      "location": "pg-service/src/main/java/com/hyoguoo/paymentplatform/pg/application/service/PgConfirmService.java:71,91,142-164",
      "problem": "processCommand → handleTerminal 가 같은 PgConfirmService 인스턴스의 self-invocation. Spring AOP proxy 가 가로채지 않아 @Transactional 적용 안 됨. plan D-F3 흡수 사유 (active TX 안 publishEvent → AFTER_COMMIT 등록) 무력화.",
      "evidence": "PLAN line 338 TX 없이 save 가 자체 TX 즉시 커밋 → publishEvent 가 active TX 외부 → AFTER_COMMIT 미등록. 동일 인스턴스 직접 호출은 Spring CGLIB/JDK proxy 통과 못 함. fallbackExecution=true 가 latency 손실은 막지만 plan 봉인 의도 위반.",
      "suggestion": "(a) handleTerminal 을 별도 @Service (PgTerminalReemitService) 로 분리해 외부 빈으로 호출. (b) AopContext.currentProxy() 사용. (c) self proxy 주입. plan-supported (a) 권장. 단위 테스트 active TX 검증 추가."
    },
    {
      "severity": "major",
      "checklist_item": "토픽 §7.2 F3 + §3 인벤토리 메트릭 row + plan PCS-13",
      "location": "pg-service/src/main/java/com/hyoguoo/paymentplatform/pg/infrastructure/scheduler/PgInboxPollingWorker.java:44,66-69",
      "problem": "pg_inbox.zombie_recovered_total{status=PENDING|IN_PROGRESS} 카운터 부재. PgInboxPollingWorker 는 zombie_fail_total (실패) 만 노출. EventType.PG_INBOX_ZOMBIE_RECOVERED_PENDING/IN_PROGRESS 선언만 되고 emit 없음.",
      "evidence": "토픽 line 660 메트릭 row + line 767 F3 표 + PLAN line 444. main grep zombie_recovered 0건. PgInboxPollingWorkerTest:118 주석은 zombie_recovered_total 이라 적었지만 실제 검증은 fail counter — 의도-구현 불일치.",
      "suggestion": "PgInboxPollingWorker 에 pg_inbox.zombie_recovered_total Micrometer counter (status 태그) 추가. processSafely 정상 종료 시 increment + LogFmt.info(PG_INBOX_ZOMBIE_RECOVERED_*) 호출. 단위 테스트 추가."
    },
    {
      "severity": "major",
      "checklist_item": "토픽 §1.4 + §1.6 SKIP LOCKED 룰",
      "location": "pg-service/src/main/java/com/hyoguoo/paymentplatform/pg/infrastructure/repository/JpaPgInboxRepository.java:121-139 + pg-service/src/main/java/com/hyoguoo/paymentplatform/pg/application/service/PgInboxProcessor.java:114-141",
      "problem": "좀비 폴링 query 와 processInProgressZombie 가 FOR UPDATE SKIP LOCKED 없이 실행. immediate 워커 + polling 워커 또는 다중 polling tick 사이에 동일 row race 가능 → 벤더 중복 호출. 멱등성 3-layer 흡수 가능하나 plan 명시 race-prevention 1단계 누락.",
      "evidence": "토픽 line 373 FOR UPDATE SKIP LOCKED + line 377 동일 + line 455 표 (processInProgressZombie TX_A WHERE id=? AND status=IN_PROGRESS FOR UPDATE SKIP LOCKED). 코드 grep: selectForUpdateSkipLockedPending 만 SKIP LOCKED 사용 (PCS-4 PENDING 측). zombie 측 plain JPQL.",
      "suggestion": "(a) findPending/InProgressZombieIdsBefore native query 전환 + FOR UPDATE SKIP LOCKED, (b) processInProgressZombie 진입 시 selectForUpdateSkipLockedInProgress(inboxId) 신규 lock 후 진행. 멀티 인스턴스 검증을 PHASE2 deferred 라면 토픽/PLAN 본문에 룰 완화 명시."
    },
    {
      "severity": "minor",
      "checklist_item": "토픽 §1.8 보정 경로 PENDING 우회 룰",
      "location": "pg-service/src/main/java/com/hyoguoo/paymentplatform/pg/application/service/PgInboxAmountService.java:55-61,119-135",
      "problem": "토픽 §1.8 line 504 PENDING-bypass 호출처는 DuplicateApprovalHandler 한정. PgInboxAmountService (dead service) 가 transitDirectToInProgress + transitDirectToTerminal 호출. plan PCS-9 가 dead service 라 컴파일 에러 해소만 진행한다고 명시 (인지) 했지만 룰 자체 위반.",
      "evidence": "코드 javadoc line 18-20 dead service 명시 + plan line 332/622. 별 토픽 처리 명시.",
      "suggestion": "TC-15 PHASE2 또는 별 TODO 항목으로 PgInboxAmountService 제거 등록. 본 토픽 범위 외라면 minor 인지."
    },
    {
      "severity": "minor",
      "checklist_item": "convention / 코드 청결성",
      "location": "pg-service/src/main/java/com/hyoguoo/paymentplatform/pg/application/service/PgInboxProcessor.java:42-46",
      "problem": "클래스 javadoc 에 paymentKey/vendorType 컬럼 부재 TODO 가 stale 로 남음. PCS-9 V3 migration 으로 컬럼 추가됐고 buildRequest 도 갱신됨.",
      "evidence": "line 42-46 vs line 150-156 javadoc 정합 불일치.",
      "suggestion": "클래스 레벨 javadoc 의 현재 스키마 제약 단락 삭제 또는 PCS-9 V3 migration 정합 으로 수정."
    },
    {
      "severity": "minor",
      "checklist_item": "convention / 코드 청결성",
      "location": "pg-service/src/main/java/com/hyoguoo/paymentplatform/pg/infrastructure/repository/PgInboxRepositoryImpl.java:16-22",
      "problem": "클래스 javadoc 에 transitNoneToInProgress 참조 stale. PCS-9 에서 메서드 삭제됐고 line 50 코멘트와 모순.",
      "evidence": "line 17-22 vs line 50.",
      "suggestion": "javadoc 을 신규 메서드 (insertPending / transitPendingToInProgress / transitDirectTo* / find*ZombieIds) 기준으로 갱신."
    }
  ],
  "previous_round_ref": null,
  "delta": null,
  "unstuck_suggestion": null
}
```
