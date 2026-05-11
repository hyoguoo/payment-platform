# review-critic-2

**Topic**: PG-CONFIRM-LISTENER-SPLIT
**Round**: 2
**Persona**: Critic

## Reasoning

Round 1 finding 8건 (major 4 + minor 3 + domain#5 EventType rename) 모두 코드/테스트/문서 수준에서 흡수됐다. M1 워커 dispatch 분기, M2 `PgTerminalReemitService` 별 빈 분리, M3 `zombie_recovered_total` Counter (PENDING/IN_PROGRESS 태그), M4 `selectInProgressForUpdateSkipLocked` 진입 락 + Testcontainers 동시성 테스트가 들어왔고 javadoc/EventType rename/TODOS 등록도 정합. M4 의 폴링 SELECT 자체에 SKIP LOCKED 를 추가하는 옵션 (a) 는 채택되지 않았으나 Round 1 suggestion 이 (a) OR (b) 였고 (b) — 진입 락 — 이 race-prevention 1단계 layer 책임을 충족한다. critical 0 / major 0 / minor 1 (토픽 본문 §1.4 line 373/377 SoT 가 폴링 SELECT 자체의 SKIP LOCKED 를 명시하나 코드는 진입 락으로 대체 — 토픽 본문 한 줄 정합 갱신 권고). **pass**.

## Checklist judgement

기준: `_shared/checklists/code-ready.md` Gate checklist (review 단계 적용).

| 섹션 | 항목 | 판정 | 근거 |
|---|---|---|---|
| test gate | 전체 `./gradlew test` 통과 | yes | 마지막 커밋 `171e9071` 메시지("294 PASS / 0 FAIL") + M4 커밋 `adf3b5b7` ("294 PASS / 0 FAIL"). pg-service 281 → 294 (+13). |
| test gate | 신규/수정 business logic 테스트 커버리지 | yes | M1 `workerLoop_inProgressJob_callsProcessInProgressZombie` (PgInboxImmediateWorkerTest:106 외 3건), M2 `reemit_savesOutboxAndPublishesEventInsideActiveTransaction` (PgTerminalReemitServiceTest:95), M3 `poll_*Zombies_incrementsRecoveredCounterWith*Tag` (PgInboxPollingWorkerTest:151,176), M4 `selectInProgressForUpdateSkipLocked_lockHeld_returnsEmpty` (PgInboxRepositoryImplTest:343). |
| test gate | state machine `@ParameterizedTest @EnumSource` | yes | `PgInboxStatusTest`, `PgInboxTest` (Round 1 PCS-2 커버리지 유지). |
| topic-coverage | §1.1 listener TX 경계 | yes | `PgInboxPendingService.insertPendingAndPublish` `@Transactional(propagation=REQUIRED, timeout=5)` (line 78). |
| topic-coverage | §1.2 PgInboxChannel cap=1024 | yes | Round 1 회귀 없음. |
| topic-coverage | §1.3 워커 dispatch (PENDING + IN_PROGRESS) | yes (R1 M1 흡수) | `PgInboxImmediateWorker.process` line 173-198 status 분기 (`PENDING → processPending`, `IN_PROGRESS → processInProgressZombie`, terminal → skip+log, NOT_FOUND → skip+warn). |
| topic-coverage | §1.4 좀비 폴링 SKIP LOCKED 60s | partial (m1) | 진입 락 (`selectInProgressForUpdateSkipLocked` JpaPgInboxRepository:152-155) 으로 race 차단. 폴링 SELECT 자체 (`findPending/InProgressZombieIdsBefore` JpaPgInboxRepository:124-139) 는 plain JPQL — 토픽 §1.4 line 373/377 SoT 와 1줄 차이. 기능적 race-prevention 충족. |
| topic-coverage | §1.5 PENDING 추가 + NONE 폐기 | yes | 회귀 없음. EventType 안 잔재 명칭(`*_NONE_TO_*`) 도 `PG_CONFIRM_PENDING_INSERT` / `PG_INBOX_AMOUNT_PENDING_PREEMPTED` 로 rename (commit `171e9071`). |
| topic-coverage | §1.6 워커 두 진입점 dispatch | yes (R1 M1 흡수) | `PgInboxImmediateWorker.process` 분기 + `PgInboxProcessor.processPending` / `processInProgressZombie` 두 진입점 모두 SKIP LOCKED 락 보유 (PCS-4 PENDING + M4 IN_PROGRESS). |
| topic-coverage | §1.7 위키 갱신 (2 페이지) | yes | Round 1 통과 유지. |
| topic-coverage | §1.8 보정 경로 PENDING 우회 + repo 메서드 4종 | yes | 회귀 없음. |
| topic-coverage | §3 인벤토리 누락 0 | yes (R1 M3 흡수) | `pg_inbox.zombie_recovered_total` Counter (PgInboxPollingWorker:51 + tag PENDING/IN_PROGRESS) 정의 + emit 합. |
| acceptance | A1 listener 내 벤더 0 | yes | 회귀 없음. |
| acceptance | A2 벤더 지연 격리 | yes | 회귀 없음. |
| acceptance | A3 좀비 회수 | yes | 회귀 없음 + M1 워커 dispatch 분기로 채널 경로도 직접 IN_PROGRESS 처리 가능. |
| acceptance | A4 보정 경로 PENDING 우회 | yes | 회귀 없음. |
| acceptance | A5 active TX publishEvent | yes (R1 M2 흡수) | `PgTerminalReemitService.reemit` `@Transactional` 별 빈 위임 (`PgConfirmService:53,87`). self-invocation 우회 해소. `reemit_savesOutboxAndPublishesEventInsideActiveTransaction` 단위 테스트 (line 95) active TX 검증. |
| acceptance | F1 채널 가득 빈도 | yes | 회귀 없음. |
| acceptance | F2 워커 fail 카운터 | yes | 회귀 없음. |
| acceptance | F3 좀비 회수 카운터 | yes (R1 M3 흡수) | `ZOMBIE_RECOVERED_COUNTER_NAME = "pg_inbox.zombie_recovered_total"` (status 태그) + `PG_INBOX_ZOMBIE_RECOVERED_PENDING / IN_PROGRESS` emit (PgInboxPollingWorker:148-151). |
| acceptance | F4 listener TX timeout | yes | 회귀 없음. |
| finding-absorption | discuss/plan finding 흡수 정합 | yes (R1 M2 흡수) | D-F3 `handleTerminal` `@Transactional` 봉인 의도가 `PgTerminalReemitService` 별 빈 분리로 active TX 보장. |
| convention | 거울 패턴 PgOutbox* ↔ PgInbox* | yes | 회귀 없음. |
| convention | 헥사고날 layer 룰 | yes | `PgInboxRepository` 포트에 `selectInProgressForUpdateSkipLocked` 추가, 어댑터 구현 — layer 정합. |
| domain-risk | 멱등성 / TX 경계 / SKIP LOCKED | yes (R1 M2/M4 흡수) | `handleTerminal` proxy 우회 해소 + IN_PROGRESS 진입 락 layer 추가. PENDING 측 PCS-4 SKIP LOCKED 유지. |
| convention | 위키 vs 코드 정합 | yes | 회귀 없음. javadoc m2/m3 stale 참조 정정 (`171e9071`). |
| convention | 코드 청결성 (javadoc) | yes (R1 m2/m3 흡수) | `PgInboxProcessor` 클래스 javadoc TODO 단락 삭제 + PCS-9 V3 정합 표기. `PgInboxRepositoryImpl` javadoc 신규 메서드 목록으로 재작성 (line 21-28). |

## Findings

### m1 (minor) — 토픽 §1.4 line 373/377 SoT 와 폴링 SELECT 정합

- **checklist_item**: 토픽 §1.4 좀비 폴링 SKIP LOCKED
- **location**:
  - 토픽 본문: `docs/topics/PG-CONFIRM-LISTENER-SPLIT.md:373,377`
  - 코드: `pg-service/.../infrastructure/repository/JpaPgInboxRepository.java:124-139` (`findPending/InProgressZombieIdsBefore`)
- **problem**: 토픽 §1.4 SoT 가 `WHERE status=... AND ... < :before FOR UPDATE SKIP LOCKED` 를 폴링 SELECT 자체에 명시하나 코드는 plain JPQL. M4 흡수에서 진입 락 (`selectInProgressForUpdateSkipLocked`) 으로 대체했고 race-prevention 은 functionally 충족하지만 토픽 본문 한 줄과 1:1 정합이 깨진 상태.
- **evidence**: 토픽 line 373 (`findPendingZombies(...)` — `WHERE status=PENDING AND received_at < now - INTERVAL Ns FOR UPDATE SKIP LOCKED`) + line 377 (IN_PROGRESS 동일). JpaPgInboxRepository:124-139 두 query 는 `@Query("SELECT e.id FROM PgInboxEntity e WHERE ...")` plain JPQL.
- **suggestion**: 토픽 §1.4 본문에 "폴링 SELECT 의 SKIP LOCKED 는 진입 락 (`selectForUpdateSkipLockedInProgress`) layer 로 대체 — review M4 흡수" 한 줄 추가 또는 PHASE2 (§6) 측정 정밀화 항목으로 deferred 명시. verify 단계 doc-sync 라운드에서 처리 권장.

## JSON

```json
{
  "stage": "code",
  "persona": "critic",
  "round": 2,
  "task_id": null,
  "decision": "pass",
  "reason_summary": "Round 1 8건 finding 모두 흡수 (M1 워커 분기 + M2 PgTerminalReemitService 별 빈 + M3 zombie_recovered_total Counter + M4 IN_PROGRESS SKIP LOCKED 진입 락 + javadoc m2/m3 + EventType rename + TODOS m1 등록). pg-service 294 PASS. minor 1건 (토픽 §1.4 line 373/377 SoT 와 폴링 SELECT 정합 1줄 차이) 만 잔존 — pass.",
  "checklist": {
    "source": "_shared/checklists/code-ready.md",
    "items": [
      {"section": "test gate", "item": "전체 ./gradlew test 통과", "status": "yes", "evidence": "commit 171e9071 + adf3b5b7 메시지 294 PASS / 0 FAIL"},
      {"section": "test gate", "item": "신규/수정 business logic 테스트 커버리지", "status": "yes", "evidence": "M1/M2/M3/M4 별 RED-GREEN 커밋 쌍 + 신규 테스트 13건 추가"},
      {"section": "test gate", "item": "state machine @ParameterizedTest @EnumSource", "status": "yes", "evidence": "PgInboxStatusTest, PgInboxTest 회귀 없음"},
      {"section": "topic-coverage", "item": "§1.3/§1.6 워커 dispatch (PENDING + IN_PROGRESS)", "status": "yes", "evidence": "PgInboxImmediateWorker.process line 173-198 status 분기 — PENDING→processPending, IN_PROGRESS→processInProgressZombie, terminal/NOT_FOUND→skip"},
      {"section": "topic-coverage", "item": "§1.4 좀비 폴링 SKIP LOCKED", "status": "yes", "evidence": "JpaPgInboxRepository.selectForUpdateSkipLockedInProgress (line 152-155) + PgInboxRepositoryImpl.selectInProgressForUpdateSkipLocked (line 188-196) — race-prevention layer 충족. 폴링 SELECT 자체 SKIP LOCKED 는 진입 락으로 대체 (m1 minor 토픽 정합 1줄)"},
      {"section": "topic-coverage", "item": "§3 인벤토리 누락 0", "status": "yes", "evidence": "pg_inbox.zombie_recovered_total Counter (PgInboxPollingWorker:51 + status 태그) + PG_INBOX_ZOMBIE_RECOVERED_* emit"},
      {"section": "acceptance", "item": "A5 active TX publishEvent", "status": "yes", "evidence": "PgTerminalReemitService.reemit @Transactional 별 빈 — PgConfirmService:53,87 외부 빈 위임. self-invocation 우회 해소"},
      {"section": "acceptance", "item": "F3 좀비 회수 카운터", "status": "yes", "evidence": "ZOMBIE_RECOVERED_COUNTER_NAME 정의 + emit + 단위 테스트 2건"},
      {"section": "finding-absorption", "item": "discuss/plan D-F3 handleTerminal TX 경계 봉인", "status": "yes", "evidence": "PgTerminalReemitService 별 빈 분리 — Spring proxy 경유 보장"},
      {"section": "domain-risk", "item": "멱등성 / TX 경계 / SKIP LOCKED", "status": "yes", "evidence": "handleTerminal proxy 우회 해소 + IN_PROGRESS 진입 락 추가 + PENDING 측 PCS-4 SKIP LOCKED 유지"},
      {"section": "convention", "item": "코드 청결성 (javadoc)", "status": "yes", "evidence": "PgInboxProcessor 클래스 javadoc TODO 단락 삭제 + PgInboxRepositoryImpl javadoc 신규 메서드 목록 재작성 (commit 171e9071)"}
    ],
    "total": 26,
    "passed": 25,
    "failed": 1,
    "not_applicable": 0
  },
  "scores": {
    "correctness": 0.92,
    "conventions": 0.94,
    "discipline": 0.95,
    "test_coverage": 0.93,
    "domain": 0.90,
    "mean": 0.928
  },
  "findings": [
    {
      "severity": "minor",
      "checklist_item": "토픽 §1.4 좀비 폴링 SKIP LOCKED",
      "location": "docs/topics/PG-CONFIRM-LISTENER-SPLIT.md:373,377 vs pg-service/src/main/java/com/hyoguoo/paymentplatform/pg/infrastructure/repository/JpaPgInboxRepository.java:124-139",
      "problem": "토픽 §1.4 SoT 는 폴링 SELECT 자체에 FOR UPDATE SKIP LOCKED 를 명시하나 코드는 plain JPQL. M4 흡수에서 진입 락 (selectInProgressForUpdateSkipLocked) 으로 대체했고 race-prevention 은 functionally 충족하지만 토픽 본문 한 줄과 1:1 정합이 깨진 상태.",
      "evidence": "토픽 line 373/377 vs JpaPgInboxRepository:124-139 plain JPQL. PgInboxRepositoryImpl.selectInProgressForUpdateSkipLocked:188-196 진입 락이 race-prevention 1단계 layer 충족.",
      "suggestion": "verify 단계 doc-sync 라운드에서 토픽 §1.4 본문에 1줄 정합 갱신 — '폴링 SELECT 의 SKIP LOCKED 는 진입 락 (selectForUpdateSkipLockedInProgress) layer 로 대체 (review M4 흡수)' 또는 PHASE2 (§6) 측정 정밀화 항목으로 deferred 명시."
    }
  ],
  "previous_round_ref": "docs/rounds/pg-confirm-listener-split/review-critic-1.md",
  "delta": {
    "newly_passed": [
      "§1.3/§1.6 워커 dispatch (M1 흡수)",
      "discuss/plan D-F3 handleTerminal TX 경계 (M2 흡수)",
      "F3 zombie_recovered_total 카운터 (M3 흡수)",
      "domain-risk SKIP LOCKED 진입 락 (M4 흡수)",
      "convention javadoc 청결성 (m2/m3 흡수)"
    ],
    "newly_failed": [],
    "still_failing": [],
    "resolution_map": {
      "M1": "resolved",
      "M2": "resolved",
      "M3": "resolved",
      "M4": "partial — 진입 락 (option b) 채택. 폴링 SELECT 자체 SKIP LOCKED (option a) 미채택 → 토픽 본문 정합 1줄 minor (m1) 로 재분류",
      "m1_PgInboxAmountService": "resolved (TODOS TC-16 등록)",
      "m2_PgInboxProcessor_javadoc": "resolved",
      "m3_PgInboxRepositoryImpl_javadoc": "resolved",
      "domain#5_EventType_NONE": "resolved (PG_CONFIRM_PENDING_INSERT / PG_INBOX_AMOUNT_PENDING_PREEMPTED rename)"
    }
  },
  "unstuck_suggestion": null
}
```
