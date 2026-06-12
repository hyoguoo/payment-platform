# plan-critic-1 — PG-CONFIRM-LISTENER-SPLIT plan 1라운드 Critic

> stage: plan
> round: 1
> persona: Critic
> 작성일: 2026-05-09

---

## 판정

**revise** — critical 0, major 4, minor 3.

---

## Reasoning

PLAN 은 16개 태스크로 토픽 §1.1~§1.9 결정을 대체로 traceable 하게 분해했고 의존 순서 / 도메인 위험 플래그 / TDD 분류는 합리적이다. 다만 **PCS-11 산출물 경로가 CONVENTIONS.md line 109 + ARCHITECTURE.md "비동기 어댑터 위치" 표 두 곳 모두와 충돌** (major), 토픽 §7.1 acceptance A5 (listener INSERT TX active TX 검증) 와 §7.2 F4 (listener TX timeout 실패 신호) 가 어느 태스크에도 매핑되지 않으며 (각 major), C-F2 흡수로 명시된 "ALREADY_PROCESSED 보정 5분기 → DuplicateApprovalHandler 위임" 단위 테스트가 PCS-8 / PCS-9 어디에도 명시되지 않아 (major) — 판정은 **revise**.

---

## Findings

### PC-F1 (major) — PCS-11 산출물 경로가 CONVENTIONS.md + ARCHITECTURE.md 명시 룰 위반

- **checklist_item**: architecture fit / `docs/context/CONVENTIONS.md` 패턴 준수
- **location**: PLAN PCS-11 산출물 절 (line 295)
- **problem**: PCS-11 `InboxReadyEventHandler` 산출물 경로가 `infrastructure/messaging/event/`. CONVENTIONS.md line 109 + ARCHITECTURE.md 비동기 어댑터 위치 표 모두 AFTER_COMMIT 리스너는 `infrastructure/listener/` 명시. 거울 `OutboxReadyEventHandler` 실제 위치도 `infrastructure/listener/`.
- **fix_hint**: PCS-11 산출물 두 경로를 `infrastructure/listener/InboxReadyEventHandler.java` + `infrastructure/listener/InboxReadyEventHandlerTest.java` 로 변경. 토픽 §3 인벤토리 line 657 은 이미 `infrastructure/listener` 라 정합.

### PC-F2 (major) — 토픽 §7.1 A5 acceptance 신호가 어느 태스크에도 매핑되지 않음

- **checklist_item**: traceability / 결정 → 태스크 매핑
- **location**: 토픽 §7.1 line 759 vs PLAN PCS-15 (line 369~385)
- **problem**: 토픽 §7.1 acceptance A5 = "listener INSERT TX 안에서 publishEvent 가 active TX 위에서 호출됨 — `TransactionSynchronizationManager.isActualTransactionActive()` mock + AFTER_COMMIT listener 발화 검증". PLAN PCS-15 통합 테스트는 A1~A4 4건만. PCS-7 단위 테스트는 롤백 경로만 검증. D-F3 (listener TX 경계 봉인) 흡수 결정의 핵심 검증 신호 누락.
- **fix_hint**: PCS-7 테스트에 `insertPendingAndPublish_publishEventCalledWithinActiveTransaction` + `insertPendingAndPublish_afterCommitListenerFires` 두 메서드 추가. 또는 PCS-15 통합 테스트에 A5 시나리오 추가. PLAN finding 추적 표에 A5 → PCS-7/PCS-15 행 추가.

### PC-F3 (major) — 토픽 §7.2 F4 (listener TX timeout 5s) 가 어느 태스크에도 매핑되지 않음

- **checklist_item**: traceability / 결정 → 태스크 매핑
- **location**: 토픽 §7.2 line 768 vs PLAN PCS-14 (line 342~366)
- **problem**: 토픽 §7.2 F4 = "listener TX timeout (5s) 발화 — Spring `TransactionTimedOutException` 카운터 + Loki 로그". PLAN PCS-14 EventType 6종 추가 (워커 / 좀비 측만) — listener TX timeout 신호 부재.
- **fix_hint**: PCS-14 EventType 목록에 `PG_INBOX_LISTENER_TX_TIMEOUT` 추가 + Spring `TransactionTimedOutException` 카운터 메트릭 이름 상수 정의. 또는 PCS-7 단위 테스트에 timeout 검증 추가 + finding 추적 표에 F4 → PCS-14 매핑 행 추가.

### PC-F4 (major) — C-F2 흡수 (ALREADY_PROCESSED 보정 5분기) 단위 테스트가 PCS-8/PCS-9 양쪽에 누락

- **checklist_item**: domain risk / 멱등성 검증 테스트
- **location**: PLAN PCS-8 line 213~217 + PCS-9 line 240~242
- **problem**: 토픽 §1.6 line 351 `processInProgressZombie` 흐름의 ALREADY_PROCESSED 응답 시 `DuplicateApprovalHandler` 위임 + §2.2 보정 5분기. PLAN PCS-8 테스트 5건은 ALREADY_PROCESSED 분기 부재. PCS-9 `DuplicateApprovalHandler` 테스트 3건은 부재 분기 (`handleDbAbsent*`, `handleVendorIndeterminate`) 만, IN_PROGRESS 잔존 row + ALREADY_PROCESSED 보정 분기 부재.
- **fix_hint**: PCS-8 테스트 추가: `processInProgressZombie_vendorReturnsAlreadyProcessed_delegatesToDuplicateApprovalHandler`. PCS-9 테스트 추가: `handleVendorAlreadyProcessed_inProgressInbox_amountMatch_transitsToApproved`. finding 추적 표 보강.

### PC-F5 (minor) — PCS-5 PgInboxReadyEvent 위치 거울 비정합

- **checklist_item**: architecture fit / 거울 패턴 일관성
- **location**: PLAN PCS-5 산출물 line 144 + 토픽 §3 인벤토리 line 658
- **problem**: PCS-5 산출물 `application/event/`. 거울 `PgOutboxReadyEvent` 실제 위치 `domain/event/`. CONVENTIONS.md 명시 룰 위반은 아니나 거울 1:1 패턴 깨짐.
- **fix_hint**: (a) PCS-5 산출물을 `domain/event/PgInboxReadyEvent.java` 로 변경 + 토픽 §3 인벤토리 정정. (b) 별 후속 태스크로 outbox 도 application/event 로 이동 (본 토픽 범위 외). **권장: (a)**.

### PC-F6 (minor) — PCS-14 EventType 명명 불일치

- **checklist_item**: artifact / 산출물 명세 정합
- **location**: 토픽 §7.2 line 766 vs PLAN PCS-14 line 365
- **problem**: 토픽 `EventType.PG_INBOX_WORKER_FAIL` vs PLAN `PG_INBOX_WORKER_PROCESS_FAIL`. SoT 봉인 필요.
- **fix_hint**: PLAN PCS-14 의 `PG_INBOX_WORKER_PROCESS_FAIL` → `PG_INBOX_WORKER_FAIL` 통일 (토픽 SoT 따름). 또는 PCS-16 위키 갱신 시 토픽 §7.2 본문도 함께 갱신.

### PC-F7 (minor) — PCS-3 / PCS-9 의 transitNoneToInProgress 삭제 위치 불일치

- **checklist_item**: dependency ordering / orphan port 없음
- **location**: PLAN PCS-3 line 76 + PCS-9 line 248
- **problem**: PCS-3 본문 "(삭제는 PCS-6)" vs PCS-9 산출물 "transitNoneToInProgress 삭제". PCS-6 은 PgVendorCallService 분리 — repo 메서드 삭제 책임 아님.
- **fix_hint**: PCS-3 본문 "(삭제는 PCS-6)" → "(삭제는 PCS-9)" 정정.

---

## JSON

```json
{
  "stage": "plan",
  "persona": "critic",
  "round": 1,
  "decision": "revise",
  "summary": "PCS-11 산출물 경로 CONVENTIONS/ARCHITECTURE 위반 + 토픽 §7.1 A5 / §7.2 F4 미매핑 + ALREADY_PROCESSED 5분기 단위 테스트 누락 — major 4건. minor 3건은 거울 패턴 / 명명 불일치 / PCS-3 주석 오타.",
  "findings": [
    {"id": "PC-F1", "severity": "major", "location": "PCS-11 line 295", "what": "InboxReadyEventHandler 위치 infrastructure/messaging/event/ → infrastructure/listener/", "fix_hint": "PCS-11 산출물 두 경로를 infrastructure/listener/ 로 변경"},
    {"id": "PC-F2", "severity": "major", "location": "PCS-7 / PCS-15 vs 토픽 §7.1 A5", "what": "listener INSERT TX 안 publishEvent active TX 검증 미매핑", "fix_hint": "PCS-7 또는 PCS-15 에 A5 테스트 추가"},
    {"id": "PC-F3", "severity": "major", "location": "PCS-14 vs 토픽 §7.2 F4", "what": "listener TX timeout 5s EventType / 카운터 미명시", "fix_hint": "PCS-14 EventType 에 PG_INBOX_LISTENER_TX_TIMEOUT 추가"},
    {"id": "PC-F4", "severity": "major", "location": "PCS-8 / PCS-9", "what": "ALREADY_PROCESSED 보정 5분기 단위 테스트 누락", "fix_hint": "PCS-8 / PCS-9 에 ALREADY_PROCESSED 분기 테스트 추가"},
    {"id": "PC-F5", "severity": "minor", "location": "PCS-5 line 144", "what": "PgInboxReadyEvent 위치 거울 비정합 (application/event vs domain/event)", "fix_hint": "PCS-5 산출물을 domain/event 로 변경"},
    {"id": "PC-F6", "severity": "minor", "location": "PCS-14 line 365", "what": "EventType 명명 불일치 (WORKER_PROCESS_FAIL vs WORKER_FAIL)", "fix_hint": "토픽 SoT WORKER_FAIL 로 통일"},
    {"id": "PC-F7", "severity": "minor", "location": "PCS-3 line 76", "what": "transitNoneToInProgress 삭제 위치 주석 오타", "fix_hint": "(삭제는 PCS-6) → (삭제는 PCS-9)"}
  ]
}
```
