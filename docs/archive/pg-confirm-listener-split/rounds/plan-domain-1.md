# plan-domain-1

**Topic**: PG-CONFIRM-LISTENER-SPLIT
**Round**: 1
**Persona**: Domain Expert

## Reasoning

PLAN 16개 태스크의 layer 의존 / TDD 분배 / discuss finding 매핑은 결제 도메인 시각으로도 대부분 합리적이고, §1.8 보정 경로 PENDING 우회 / §1.4 좀비 폴링 두 경로 / §1.1 listener TX 경계 봉인 같은 핵심 결정은 PCS-2 / PCS-3 / PCS-4 / PCS-7 / PCS-9 / PCS-13 으로 잘 분배되어 있다. 그러나 결제에서 돈이 새는 핵심 race window 셋 — (a) listener 의 publishEvent 가 active TX 안에서 호출됨 보장, (b) `DuplicateApprovalHandler.handleVendorIndeterminate` 의 `transitDirectToInProgress` + `transitToQuarantined` 두 호출 atomicity, (c) terminal 재수신 listener 직접 처리의 TX 경계 — 가 PLAN 산출물 / 테스트 명세에서 가시화되지 않았다. 셋 모두 정합성 사고 또는 좀비 무한 루프로 이어질 수 있는 도메인 리스크이므로 `major` 3건 + `minor` 1건 (호출처 인벤토리 부정확) 으로 `revise` 판정한다.

## Domain risk checklist

- [yes] 중복 방지 체크: `insertPending` UNIQUE 충돌 시 IGNORE + 기존 inboxId 반환 — PCS-3 시그니처 + PCS-4 테스트 `insertPending_duplicateOrderId_returnsExistingId` 정합
- [yes] 결제 상태 전이: PENDING / IN_PROGRESS / APPROVED / FAILED / QUARANTINED 5상태 — PCS-2 도메인 갱신 + PCS-4 `transitPendingToInProgress` SKIP LOCKED 정합. `markInProgress` 가드 NONE → PENDING 변경 명시
- [yes] 보정 경로 PENDING 우회 룰 (§1.8): `transitDirectToInProgress` / `transitDirectToTerminal` 신설 — PCS-3 / PCS-9 정합
- [no] **listener TX 경계 검증** (§1.1 / D-F3): publishEvent 가 active TX 안에서 호출되는지 검증 누락 — PCS-7 테스트 메서드 3개 모두 publish 호출 횟수 + 롤백 경로만 cover. PITFALLS §3 짝패턴이 명시한 active TX 안 publish 가드가 acceptance A5 와 1:1 매핑되지 않음
- [no] **`handleVendorIndeterminate` 두 호출 atomicity**: `transitDirectToInProgress` + `transitToQuarantined` 가 같은 TX 단위에서 묶이는 룰 미명시 — 묶이지 않으면 IN_PROGRESS 좀비 잔존 → `processInProgressZombie` → 벤더 재호출 → ALREADY_PROCESSED → DuplicateApprovalHandler 재진입 무한 루프 위험
- [no] **terminal 재수신 listener 직접 처리 TX 경계** (§1.6 안 B): PCS-9 `handleTerminal` 산출물에 `@Transactional` 표기 없음 — outbox save 후 publishEvent 가 active TX 안에서 호출되지 않으면 AFTER_COMMIT listener 미등록 → 채널 우회 latency 우위 무효화 (폴링 5s 회수와 같아짐)
- [yes] 멱등성 layer 연계: 좀비 회수 → `processInProgressZombie` → 벤더 재호출 ALREADY_PROCESSED → DuplicateApprovalHandler 보정 — PCS-8 / PCS-9 / PCS-15 매핑
- [yes] race window 가드: SKIP LOCKED + WHERE status=? 두 layer — PCS-4 단위 + PCS-15 통합
- [yes] PG 벤더 실패 모드 cover: timeout / 5xx / ALREADY_PROCESSED / 응답 미확정 — `applyOutcome` 5분기 (Success / Retryable / NonRetryable / HandledInternally / DLQ) PCS-6 테스트로 cover
- [n/a] PII 처리: 본 토픽은 결제 흐름 분리 안만 다룸 — PII 신규 노출 surface 0
- [n/a] 금전 정확성: amount 컬럼 보존 / scale 처리 변경 없음 — PCS-2 도메인 변경에서 amount 필드 그대로

## 도메인 관점 추가 검토

다음은 일반적인 코드리뷰 영역 (Critic 책임) 과 중복하지 않는 결제 도메인 전용 finding 이다.

### F1 — `PgInboxPendingService.insertPendingAndPublish` 의 active TX 안 publishEvent 검증 누락 (major)

**위치**: `docs/PG-CONFIRM-LISTENER-SPLIT-PLAN.md` PCS-7 테스트 메서드 3개

**문제**: PCS-7 의 단위 테스트 셋:
- `insertPendingAndPublish_insertsRowAndPublishesEvent` — 호출 횟수만 검증
- `insertPendingAndPublish_duplicateOrderId_publishesEventWithExistingId` — 호출 횟수만 검증
- `insertPendingAndPublish_repositoryThrows_eventNotPublished` — 롤백 경로

세 케이스 모두 **publishEvent 가 active TX 안에서 호출되는지** 를 검증하지 않는다. `@Transactional(timeout=5)` 가 있어도 self-invocation / proxy 우회 / mock 주입 실수 등으로 메서드가 active TX 외부에서 실행되는 경우, `publishEvent` 는 호출은 되지만 `@TransactionalEventListener(AFTER_COMMIT)` 등록이 안 됨 → 채널 적재 0 → 폴링 5s 회수까지 silent latency. 정합성 자체는 살아있지만 **분리 안의 핵심 가치 (벤더 latency 격리 = listener throughput 보호) 를 무효화** 하는 도메인 리스크이며, PITFALLS.md §3 짝패턴이 정확히 이 함정을 지목한다.

**evidence**:
- `docs/PG-CONFIRM-LISTENER-SPLIT-PLAN.md` PCS-7 테스트 메서드 3개 모두 Mockito verify 호출 횟수 / 비호출만 검증
- `docs/PG-CONFIRM-LISTENER-SPLIT-PLAN.md` §7.2 acceptance A5 ("listener INSERT TX 안에서 publishEvent 가 active TX 위에서 호출됨") 가 PLAN 본문에는 적혀 있으나 PCS-7 테스트로 매핑되지 않음
- `docs/topics/PG-CONFIRM-LISTENER-SPLIT.md` line 290 명시: "publishEvent 가 active TX 외부에서 호출되면 `@TransactionalEventListener(AFTER_COMMIT)` 등록 안 됨 → 채널 적재 0 → 폴링이 5s 주기로 회수 (silent latency 5s)"

**suggestion**: PCS-7 에 테스트 메서드 1개 추가:
- `insertPendingAndPublish_publishesEventInsideActiveTransaction` — `@Transactional` 진입 후 publishEvent 호출 시점에 `TransactionSynchronizationManager.isActualTransactionActive()` true 검증. 또는 통합 layer (`@SpringBootTest` 또는 `@DataJpaTest` + `ApplicationEventPublisher`) 에서 실제 AFTER_COMMIT 리스너가 발화되는지 검증.

또는 PCS-15 통합 테스트에 acceptance A5 검증 메서드 1개 추가 — listener 진입 후 `PgInboxChannel.size()` 가 1 이상으로 증가하는지 (= AFTER_COMMIT 발화 → channel.offerNow 도달).

### F2 — `DuplicateApprovalHandler.handleVendorIndeterminate` 의 두 호출 atomicity 미명시 (major)

**위치**: `docs/PG-CONFIRM-LISTENER-SPLIT-PLAN.md` PCS-9 산출물 + `docs/topics/PG-CONFIRM-LISTENER-SPLIT.md` §1.8 표

**문제**: §1.8 표 line 523 의 `transitDirectToInProgress` 행은 호출처를 "DuplicateApprovalHandler.handleVendorIndeterminate (보정 경로 — 벤더 응답 미확정 후 IN_PROGRESS 신설 후 격리)" 로 명시. 즉 `transitDirectToInProgress(orderId, amount)` 호출 후 즉시 `transitToQuarantined(orderId, REASON_VENDOR_INDETERMINATE)` 가 같은 메서드 안에서 호출된다.

이 두 호출이 **같은 TX 단위로 묶이지 않으면** 다음 race window 발생:
1. `transitDirectToInProgress` 커밋 → IN_PROGRESS row 박힘
2. JVM 죽음 또는 네트워크 단절 → `transitToQuarantined` 미호출
3. 60s 후 `PgInboxPollingWorker` 가 IN_PROGRESS 좀비로 회수
4. `processInProgressZombie` 진입 → 벤더 재호출
5. 벤더가 이번엔 응답 — APPROVED 또는 ALREADY_PROCESSED
6. ALREADY_PROCESSED 응답 → `DuplicateApprovalHandler.handleDuplicateApproval` 재진입
7. 1단계 vendor 조회 → 이번엔 응답 옴 → 정상 종결 또는 다시 INDETERMINATE
8. INDETERMINATE 분기 재진입 시 **무한 루프 가능성**

기존 코드 (`@Transactional` 가 `handleDuplicateApproval` 메서드에 부착) 가 두 호출을 같은 TX 안에 묶고 있으므로 race 가 차단되지만, PLAN PCS-9 산출물이 이 TX 경계를 **명시적으로 보존한다고 적지 않았다**. 분리 안 도입 후 누군가 `handleVendorIndeterminate` 를 별도 application service 로 추출하면서 `@Transactional` 을 빠뜨리면 위 무한 루프 race 가 즉시 활성화된다.

**evidence**:
- `pg-service/.../application/service/DuplicateApprovalHandler.java:137` `@Transactional` (현재 코드)
- `pg-service/.../application/service/DuplicateApprovalHandler.java:264-278` `handleVendorIndeterminate` 가 `transitNoneToInProgress` + `transitToQuarantined` 두 repo 호출
- `docs/PG-CONFIRM-LISTENER-SPLIT-PLAN.md` PCS-9 산출물에 `DuplicateApprovalHandler.java (갱신)` 만 있고 TX 경계 / `@Transactional` 보존 명시 없음
- `docs/topics/PG-CONFIRM-LISTENER-SPLIT.md` §1.8 line 523 `transitDirectToInProgress` 행은 호출처만 명시하고 후속 `transitToQuarantined` 와의 atomicity 룰 미명시

**suggestion**: 두 옵션 중 택일:
- (a) PCS-9 산출물에 `DuplicateApprovalHandler.handleDuplicateApproval` 의 `@Transactional` 보존 + `handleVendorIndeterminate` 가 같은 TX 단위에서 두 repo 호출을 묶는 룰을 명시.
- (b) `transitDirectToInProgress` 자체를 폐기하고 `transitDirectToTerminal(QUARANTINED, reason=VENDOR_INDETERMINATE)` 단일 호출로 압축. 이쪽이 더 도메인 명료성 있음 — 보정 경로는 본질적으로 종결 상태로 직행, IN_PROGRESS 중간 상태는 무한 루프 위험만 만든다.

### F3 — terminal 재수신 listener 직접 처리의 TX 경계 미명시 (major)

**위치**: `docs/PG-CONFIRM-LISTENER-SPLIT-PLAN.md` PCS-9 `handleTerminal` 산출물

**문제**: §1.6 안 B 채택 — terminal 재수신은 listener 가 직접 `storedStatusResult` 재발행 (벤더 호출 안 함, 워커 큐 우회). 채택 사유 (§1.6 line 443) "terminal 재수신은 본질적으로 벤더 호출 안 함 → 워커 큐 대기 의미 없음. **latency 우위**".

그러나 PCS-9 의 `PgConfirmService.handle_terminalInbox_reemitsStoredStatus` 테스트는 "outbox INSERT 1회, 벤더 호출 0" 만 검증. **AFTER_COMMIT listener 가 발화해 채널에 적재되는지** 는 검증 안 함. 현재 코드의 `handleTerminal` (line 117-139) 는 별도 `@Transactional` 없이 `pgOutboxRepository.save` + `applicationEventPublisher.publishEvent` 를 호출 — `JpaRepository.save` 가 자체 REQUIRED TX 를 만들고 그 TX가 즉시 커밋되면, 그 후 호출되는 `publishEvent` 는 active TX 외부에서 발화 → AFTER_COMMIT listener 등록 안 됨 → `PgOutboxChannel` 적재 0 → `PgOutboxPollingWorker` 가 5s 후 회수.

이 경우 **분리 안 안 B의 채택 사유 (latency 우위) 가 무효화** — 폴링 latency 5s 와 사실상 동일. 게다가 현재 코드는 `@Transactional` 이 외곽에 없는 상태로 동작 중 — 실제 production 흐름에서 AFTER_COMMIT 등록이 어떻게 되는지가 묵시적 가정에 의존.

**evidence**:
- `pg-service/.../application/service/PgConfirmService.java:117-139` `handleTerminal` 메서드, `@Transactional` 부착 없음
- `pg-service/.../application/service/PgConfirmService.java:55` `handle` 메서드, `@Transactional` 부착 없음
- `docs/PG-CONFIRM-LISTENER-SPLIT-PLAN.md` PCS-9 테스트 `handle_terminalInbox_reemitsStoredStatus` — 채널 적재 / AFTER_COMMIT 발화 검증 누락
- `docs/topics/PG-CONFIRM-LISTENER-SPLIT.md` §1.6 line 466 "terminal → handleTerminal 그대로 (storedStatusResult 재발행 + pg_outbox INSERT — 별 application service 또는 같은 service 의 메서드)" — TX 경계 미명시

**suggestion**: PCS-9 산출물에 다음 중 하나 명시:
- `handleTerminal` 호출 경로 전체에 `@Transactional` (외곽 또는 `handleTerminal` 자체) 부착 봉인 — outbox save + publishEvent 가 active TX 안에서 발화하도록 보장.
- 또는 별도 application service (`PgInboxTerminalReemitService.reemit(orderId)`) 신규 — `@Transactional(timeout=5)` 봉인. `PgInboxPendingService` 거울 패턴.

PCS-9 의 `handle_terminalInbox_reemitsStoredStatus` 테스트도 "AFTER_COMMIT 발화 후 `PgOutboxChannel` 적재 1회 검증" 항목 추가 권장.

### F4 — `transitNoneToInProgress` 호출처 인벤토리 부정확 — `PgInboxAmountService` dead service (minor)

**위치**: `docs/PG-CONFIRM-LISTENER-SPLIT-PLAN.md` 메타박스 line 19, 호출처 인벤토리 line 489 / PCS-9 산출물

**문제**: PLAN 메타박스가 "transitNoneToInProgress 호출 5곳: PgConfirmService, PgInboxAmountService(×2), DuplicateApprovalHandler(×3)" 라 적었는데 실제는 6곳 (1+2+3). 그리고 더 중요한 것은 — **`PgInboxAmountService` 는 main 코드에서 호출처가 0인 dead service** 다.

```
$ grep -rln PgInboxAmountService pg-service/src/main → 1개 (자기 파일만)
$ grep -rln PgInboxAmountService pg-service/src/test → 1개 (PgInboxAmountStorageTest)
```

`DuplicateApprovalHandler` / `PgConfirmService` / `PgVendorCallService` 어디에서도 `PgInboxAmountService` 를 import / 주입하지 않는다. PCS-9 의 "PgInboxAmountService 갱신 — transitNoneToInProgress 2곳 교체" 작업은 dead service 안의 죽은 호출을 갱신하는 셈 — 외부 행동 변화 0.

도메인 안전성 직접 위협은 없다 (`transitNoneToInProgress` 포트 메서드 삭제가 컴파일 에러로 잡힘). 다만:
- PLAN 의 호출처 추적성이 dead 호출 포함이라 `transitDirectTo*` 메서드 매핑이 부정확하게 분배됨.
- dead service 의 처리 (제거 / 보존) 가 본 토픽 범위에서 결정되지 않음 — 유저 확인 필요한 데드 판정 룰 (memory `feedback_dead_code_requires_user_confirmation.md`) 정합 필요.

**evidence**:
- `pg-service/src/main/java/com/hyoguoo/paymentplatform/pg/application/service/PgInboxAmountService.java` — 자기 파일 외 main 호출처 0
- `pg-service/src/test/java/com/hyoguoo/paymentplatform/pg/application/service/PgInboxAmountStorageTest.java` — 유일한 호출처 (테스트)
- `docs/PG-CONFIRM-LISTENER-SPLIT-PLAN.md` line 19 메타박스 "5곳" — 실제 6곳
- `docs/PG-CONFIRM-LISTENER-SPLIT-PLAN.md` line 489 "transitNoneToInProgress 호출처: PgConfirmService(1), PgInboxAmountService(2), DuplicateApprovalHandler(3) = 총 6곳" — 6곳은 맞으나 PgInboxAmountService 2곳이 dead

**suggestion**:
- (a) PLAN 의 호출처 인벤토리에서 `PgInboxAmountService` 가 dead service 임을 명시 — 본 토픽에서는 transitNoneToInProgress 삭제에 따른 컴파일 에러 해소만 하고, dead service 자체 제거는 별 토픽 / 사용자 확인 후 처리.
- (b) 또는 `PgInboxAmountService` 의 main 호출처가 정말 0인지 사용자 확인 후 본 토픽 안에서 제거 (PCS-9 산출물에 "PgInboxAmountService 제거" 추가).
- 메타박스 line 19 "5곳" → "6곳" 정정.

## Findings

| ID | Severity | Location | Problem | Suggestion |
|---|---|---|---|---|
| F1 | major | PCS-7 테스트 / 산출물 | listener active TX 안 publishEvent 검증 누락 — silent latency 5s 위험 | 테스트 메서드 1개 추가 (active TX 안 publish 검증) 또는 PCS-15 통합 테스트에 채널 적재 검증 추가 |
| F2 | major | PCS-9 / §1.8 `transitDirectToInProgress` | `handleVendorIndeterminate` 두 호출 atomicity 미명시 — 좀비 무한 루프 위험 | (a) `@Transactional` 보존 명시 또는 (b) `transitDirectToTerminal(QUARANTINED)` 단일 압축 |
| F3 | major | PCS-9 `handleTerminal` 산출물 | terminal 재수신 listener 직접 처리의 TX 경계 미명시 — latency 우위 무효화 | `@Transactional` 봉인 + 채널 적재 검증 테스트 추가 |
| F4 | minor | PLAN 메타박스 line 19 / line 489 / PCS-9 | `PgInboxAmountService` dead service — 호출처 인벤토리 부정확 | dead service 명시 또는 본 토픽에서 제거 (사용자 확인 후) |

## JSON

```json
{
  "stage": "plan",
  "persona": "domain-expert",
  "round": 1,
  "task_id": null,

  "decision": "revise",
  "reason_summary": "도메인 리스크 시각의 핵심 race window 셋 (listener active TX 안 publish / handleVendorIndeterminate atomicity / terminal 재수신 TX 경계) 이 PLAN 산출물 / 테스트 명세에 가시화되지 않았다. 셋 모두 정합성 사고 또는 좀비 무한 루프로 이어질 수 있어 major 3건 + minor 1건 (호출처 인벤토리 부정확) 으로 revise.",

  "checklist": {
    "source": "_shared/checklists/plan-ready.md (Gate checklist — domain risk 섹션)",
    "items": [
      {
        "section": "domain risk",
        "item": "discuss에서 식별된 domain risk가 각각 대응 태스크를 가짐 (멱등성 검증 테스트, 상태 전이 테스트 등)",
        "status": "no",
        "evidence": "D-F3 (listener TX 경계) 가 PCS-7 로 매핑됐으나 핵심 가드 (active TX 안 publish 검증) 가 PCS-7 테스트에 빠짐. acceptance A5 가 PLAN 본문에는 있으나 PCS-7 테스트로 매핑 안 됨"
      },
      {
        "section": "domain risk",
        "item": "중복 방지 체크(예: existsByOrderId)가 필요한 경로에 계획됨",
        "status": "yes",
        "evidence": "PCS-3 insertPending UNIQUE 충돌 IGNORE 시그니처 + PCS-4 duplicate test"
      },
      {
        "section": "domain risk",
        "item": "재시도 안전성 검증 태스크 존재 (재시도 정책이 있는 경우만)",
        "status": "yes",
        "evidence": "PCS-15 zombieRecovery 통합 테스트 + PCS-9 DuplicateApprovalHandler 보정 경로 단위 테스트"
      },
      {
        "section": "task quality (도메인 안전성 영향)",
        "item": "보정 경로 PENDING 우회 룰의 atomicity 봉인",
        "status": "no",
        "evidence": "§1.8 transitDirectToInProgress 호출처 (handleVendorIndeterminate) 가 두 단계 호출 + TX 경계 미명시 → 좀비 회수 시 무한 루프 race"
      },
      {
        "section": "task quality (도메인 안전성 영향)",
        "item": "terminal 재수신 listener 직접 처리의 TX 경계 봉인",
        "status": "no",
        "evidence": "PCS-9 handleTerminal 산출물에 @Transactional 표기 없음 — AFTER_COMMIT 등록 묵시적 가정"
      },
      {
        "section": "traceability",
        "item": "호출처 인벤토리 정확성",
        "status": "no",
        "evidence": "PLAN 메타박스 line 19 '5곳' (실제 6곳) + PgInboxAmountService 2곳이 dead service 호출"
      }
    ],
    "total": 6,
    "passed": 2,
    "failed": 4,
    "not_applicable": 0
  },

  "scores": {
    "traceability": 0.72,
    "decomposition": 0.85,
    "ordering": 0.90,
    "specificity": 0.70,
    "risk_coverage": 0.65,
    "mean": 0.764
  },

  "findings": [
    {
      "severity": "major",
      "checklist_item": "discuss domain risk → 태스크 매핑 (D-F3 listener TX 경계)",
      "location": "docs/PG-CONFIRM-LISTENER-SPLIT-PLAN.md PCS-7 테스트 메서드 + PCS-15 통합 테스트",
      "problem": "PCS-7 의 단위 테스트 3개 (insertPendingAndPublish_insertsRowAndPublishesEvent / duplicateOrderId / repositoryThrows) 모두 publishEvent 호출 횟수 / 비호출만 검증하고, publishEvent 가 active TX 안에서 호출되는지 검증하지 않는다. acceptance A5 (PLAN 본문) 가 PCS-7 테스트로 1:1 매핑되지 않음. self-invocation / proxy 우회 / mock 주입 실수로 active TX 외부 publish 발생 시 AFTER_COMMIT listener 등록 안 됨 → 채널 적재 0 → 폴링 5s silent latency. 분리 안의 핵심 가치 (벤더 latency 격리) 무효화. PITFALLS §3 짝패턴이 정확히 지목한 함정.",
      "evidence": "PCS-7 테스트 메서드 3개 모두 Mockito verify 호출 횟수만; PLAN §7.2 A5 가 PCS-7 산출물에 매핑 안 됨; topics/PG-CONFIRM-LISTENER-SPLIT.md line 290 silent latency 5s 위험 명시",
      "suggestion": "PCS-7 에 테스트 1개 추가 — insertPendingAndPublish_publishesEventInsideActiveTransaction (TransactionSynchronizationManager.isActualTransactionActive() 검증). 또는 PCS-15 통합 테스트에 listener 진입 후 PgInboxChannel.size() 증가 검증 추가."
    },
    {
      "severity": "major",
      "checklist_item": "보정 경로 PENDING 우회 룰의 atomicity 봉인",
      "location": "docs/PG-CONFIRM-LISTENER-SPLIT-PLAN.md PCS-9 DuplicateApprovalHandler 산출물 + docs/topics/PG-CONFIRM-LISTENER-SPLIT.md §1.8 표 line 523",
      "problem": "§1.8 표 transitDirectToInProgress 행은 호출처를 'handleVendorIndeterminate (보정 경로 — IN_PROGRESS 신설 후 격리)' 로 명시. 즉 transitDirectToInProgress 후 transitToQuarantined 두 호출이 같은 메서드 안에서 일어남. 두 호출의 atomicity (같은 TX 단위 보장) 가 PLAN PCS-9 산출물에 명시되지 않음. 묶이지 않으면 transitDirectToInProgress 커밋 후 JVM 죽음 → IN_PROGRESS 좀비 → processInProgressZombie 진입 → 벤더 재호출 → ALREADY_PROCESSED 또는 INDETERMINATE 재진입 → 무한 루프 race.",
      "evidence": "DuplicateApprovalHandler.java:137 현재 @Transactional 부착; PCS-9 산출물에 TX 경계 명시 없음; topics §1.8 line 523 atomicity 룰 미명시",
      "suggestion": "(a) PCS-9 산출물에 DuplicateApprovalHandler.handleDuplicateApproval @Transactional 보존 명시 + handleVendorIndeterminate 두 호출이 같은 TX 단위에서 묶이는 룰 봉인. 또는 (b) transitDirectToInProgress 폐기 + transitDirectToTerminal(QUARANTINED, reason=VENDOR_INDETERMINATE) 단일 압축 — 보정 경로가 본질적으로 종결로 직행하므로 IN_PROGRESS 중간 상태 무용."
    },
    {
      "severity": "major",
      "checklist_item": "terminal 재수신 listener 직접 처리의 TX 경계 봉인 (§1.6 안 B)",
      "location": "docs/PG-CONFIRM-LISTENER-SPLIT-PLAN.md PCS-9 handleTerminal 산출물 + docs/topics/PG-CONFIRM-LISTENER-SPLIT.md §1.6 안 B 채택 사유",
      "problem": "§1.6 안 B 채택 사유 = latency 우위 (벤더 호출 안 함이라 워커 큐 우회). 그러나 PCS-9 handleTerminal 산출물에 @Transactional 표기 없음. 현재 코드 PgConfirmService.handleTerminal 도 @Transactional 부착 없이 pgOutboxRepository.save + publishEvent 호출. JpaRepository.save 가 자체 REQUIRED TX 만들고 즉시 커밋되면, 후속 publishEvent 는 active TX 외부에서 발화 → AFTER_COMMIT listener 미등록 → PgOutboxChannel 적재 0 → PgOutboxPollingWorker 5s 후 회수. latency 우위 무효화. PCS-9 의 handle_terminalInbox_reemitsStoredStatus 테스트도 outbox INSERT + 벤더 호출 0 만 검증 — 채널 적재 검증 누락.",
      "evidence": "PgConfirmService.java:117-139 handleTerminal @Transactional 없음; PgConfirmService.java:55-72 handle @Transactional 없음; PCS-9 테스트 채널 적재 검증 누락; topics §1.6 line 466 TX 경계 미명시",
      "suggestion": "PCS-9 산출물에 명시: (a) handleTerminal (또는 외곽 handle 메서드) 에 @Transactional 봉인 — outbox save + publishEvent 가 active TX 안에서 발화하도록 보장. 또는 (b) 별도 application service PgInboxTerminalReemitService.reemit(orderId) 신규 (@Transactional(timeout=5), PgInboxPendingService 거울 패턴). PCS-9 테스트 handle_terminalInbox_reemitsStoredStatus 에 PgOutboxChannel 적재 1회 검증 항목 추가."
    },
    {
      "severity": "minor",
      "checklist_item": "호출처 인벤토리 정확성",
      "location": "docs/PG-CONFIRM-LISTENER-SPLIT-PLAN.md 메타박스 line 19 + line 489 검증 plan + PCS-9 PgInboxAmountService 갱신 항목",
      "problem": "PLAN 메타박스 line 19 'transitNoneToInProgress 호출 5곳' (실제 6곳, line 489 와 불일치). 더 중요하게는 PgInboxAmountService 가 main 코드에서 호출처 0 인 dead service — DuplicateApprovalHandler / PgConfirmService / PgVendorCallService 어디에도 import / 주입 없음. PCS-9 의 'PgInboxAmountService 갱신 (transitNoneToInProgress 2곳 교체)' 작업은 dead 호출 갱신 — 외부 행동 변화 0. 도메인 안전성 직접 위협은 없으나 (transitNoneToInProgress 삭제가 컴파일 에러로 잡힘) 본 토픽 범위에서 dead service 처리 결정이 빠져 사용자 확인 필요한 데드 판정 룰과 정합 미흡.",
      "evidence": "grep -rln PgInboxAmountService pg-service/src/main → 자기 파일 1개; test 1개; main 호출처 0; PLAN 메타박스 line 19 '5곳' / line 489 '6곳' 자체 불일치",
      "suggestion": "(a) PLAN 호출처 인벤토리에 PgInboxAmountService 가 dead service 임을 명시 + 본 토픽에서는 transitNoneToInProgress 삭제에 따른 컴파일 에러 해소만, dead service 자체 제거는 별 토픽 / 사용자 확인 후. 또는 (b) 사용자 확인 후 본 토픽 안에서 제거 (PCS-9 산출물에 'PgInboxAmountService 제거 + PgInboxAmountStorageTest 제거' 추가). 메타박스 line 19 '5곳' → '6곳' 정정."
    }
  ],

  "previous_round_ref": null,
  "delta": null,

  "unstuck_suggestion": null
}
```
