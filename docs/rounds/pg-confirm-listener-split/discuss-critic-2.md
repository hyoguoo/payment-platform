# discuss-critic-2

**Topic**: PG-CONFIRM-LISTENER-SPLIT
**Round**: 2
**Persona**: Critic

## Reasoning

Round 1 critic finding 6건 (major 3 / minor 3) 모두 흡수 위치 식별 가능 — C-F1 → §7 (성공 5 / 실패 4 신호 + 관찰 방법), C-F2 → §2.1 정상 흐름 + §2.2 보정 5분기 sub-flowchart, C-F3 → §1.8 보정 경로 PENDING 우회 룰 + repo 메서드 4종 봉인, C-F4 → §1.5 안 A 단일화 + 안 B 기각 명시, C-F5 → §1.6 두 진입점 (`processPending` / `processInProgressZombie`) 봉인, C-F6 → §4.4 3계층 본문 명시. 흡수 과정에서 §3 인벤토리 `PgInboxStatus` row 가 "4 상태" 로 표기됐으나 실제 5 상태 (PENDING + IN_PROGRESS + APPROVED + FAILED + QUARANTINED) — 단순 카운트 오기재 minor 1건 외 새 major / critical 결함 0. **pass**.

## Checklist judgement

### scope (범위)
- TOPIC UPPER-KEBAB-CASE: **yes** (변경 없음)
- 모듈/패키지 경계 명시: **yes** (§3 갱신 — 신규 컴포넌트 인벤토리 확장, pg-service 한정 유지)
- non-goals ≥ 1: **yes** (§non-goal 4건 유지)
- 범위 밖 이슈 처리: **yes** (§6 PHASE2 7건 — 정량 SLO / 멀티 인스턴스 / CDC / TC-13 등 명시 deferred)

### design decisions (설계 결정)
- hexagonal layer 배치: **yes** (§1.1 / §1.6 / §3 인벤토리 application/port/in/PgInboxProcessUseCase 신설)
- 포트 인터페이스 위치: **yes** (§1.6 봉인 — `application/port/in/PgInboxProcessUseCase`)
- 신규 상태 → 상태 전이: **yes** (§1.5 PENDING 신규 + NONE 폐기, §1.8 위키 stateDiagram 인용 5상태 매핑)
- 전체 결제 흐름 호환성: **yes** (`PaymentConfirmConsumer` 변경 0, §3 인벤토리 봉인)

### acceptance criteria (수락 조건)
- 성공 조건 관찰 가능: **yes** (§7.1 — A1~A5 5건 모두 단위/통합 테스트 + verify 방법 명시. Mockito verify 호출 0 / records consumed/s 측정 / `TransactionSynchronizationManager.isActualTransactionActive()` 검증 등 관찰 방법까지 적힘)
- 실패 관찰 방법: **yes** (§7.2 — F1~F4 4건 모두 Micrometer gauge / counter / Loki EventType 노출 명시)

### verification plan (검증 계획)
- 테스트 계층 결정: **yes** (§4.4 — 단위 7건 / 통합 4건 / 회귀 207 PASS 본문 명시)
- 벤치마크 지표: **n/a** (§7.3 + §6 — 정량 SLO 는 PHASE2 위임. 본 토픽은 정합 작업)

### artifact (산출물)
- 결정 사항 섹션 존재: **yes** (§1.1~§1.9 — 9개 결정 단위)

### Round 1 finding 흡수 검증 (Round 2 추가 판정 차원)
- C-F1 흡수: **yes** (§7 신설, 성공 5 + 실패 4)
- C-F2 흡수: **yes** (§2.1 + §2.2 분리, 5분기 매핑)
- C-F3 흡수: **yes** (§1.8 봉인 + repo 메서드 4종)
- C-F4 흡수: **yes** (§1.5 안 A 단일화)
- C-F5 흡수: **yes** (§1.6 두 메서드 분리)
- C-F6 흡수: **yes** (§4.4 3계층)

### 흡수 과정의 새 결함
- §3 인벤토리 `PgInboxStatus` row 카운트 오기재 — **no / minor** (line 575 "4 상태" → 실제 5 상태, NONE 폐기 후 PENDING / IN_PROGRESS / APPROVED / FAILED / QUARANTINED)
- §3 인벤토리 vs §1.8 repo 메서드 정합: **yes** (`insertPending` / `transitDirectToInProgress` / `transitDirectToTerminal` / `transitPendingToInProgress` 4종 + `findPendingZombies` / `findInProgressZombies` 좀비 폴링용 모두 매핑)
- §1.6 안 B 채택 vs §2.1 다이어그램 정합: **yes** (LT 노드 = terminal 재수신 listener 직접 처리 + LPe 노드 = PENDING 재수신 publishEvent 만, INSERT 0)
- §7 acceptance vs §1 결정 정합: **yes** (A1 = §1.1 listener 벤더 호출 0, A2 = §1.3 워커 격리, A3 = §1.4 좀비 회수, A4 = §1.8 보정 경로 PENDING 우회, A5 = §1.1 active TX 위 publishEvent)
- §1.5 NONE 폐기 vs `transitNoneToInProgress` 호출처 3곳 정리 vs §1.8 신규 메서드 매핑: **yes** (`handleNone` → `insertPending` / `handleDbAbsent*` → `transitDirectToTerminal` / `handleVendorIndeterminate` → `transitDirectToInProgress`)

---

## Findings

### F1 (minor) — §3 인벤토리 `PgInboxStatus` 상태 카운트 오기재

- **checklist_item**: 산출물 정합 (§3 인벤토리 vs §1.5 enum 결정)
- **location**: `docs/topics/PG-CONFIRM-LISTENER-SPLIT.md#3-컴포넌트-인벤토리` (line 575)
- **problem**: §3 인벤토리 `PgInboxStatus` row 의 "비고" 컬럼이 **"`PENDING` 추가, `NONE` 폐기 (§1.5 단일 채택). 4 상태 (`PENDING / IN_PROGRESS / APPROVED / FAILED / QUARANTINED`)"** 로 적혀 있다. 그러나 백틱 안 enum 5종이 나열되어 있고 (PENDING + IN_PROGRESS + APPROVED + FAILED + QUARANTINED), §1.5 본문 line 342 도 "4 종결 / 진행 상태" + PENDING 신규 = 5 상태로 결정 — "4 상태" 표기는 단순 카운트 오기재.
- **evidence**: §3 line 575 백틱 안 5개 enum 값 vs 카운트 "4". §1.5 line 342 "기존 4 종결 / 진행 상태 (`IN_PROGRESS / APPROVED / FAILED / QUARANTINED`) 와 동등 layer" + PENDING 신규.
- **suggestion**: §3 line 575 비고 컬럼 "4 상태" → "5 상태" 정정. plan 단계 진입 전 단순 typo 수정.

---

## JSON

```json
{
  "stage": "discuss",
  "persona": "critic",
  "round": 2,
  "task_id": null,

  "decision": "pass",
  "reason_summary": "Round 1 critic finding 6건 모두 §1.1 / §1.4 / §1.5 / §1.6 / §1.7 / §1.8 / §2.1 / §2.2 / §4.4 / §7 흡수 위치 식별 + §8 흡수 노트로 추적성 확보. 흡수 과정의 sibling 섹션 모순 0건 (repo 메서드 / 다이어그램 분기 / acceptance vs 결정 매핑 모두 정합). minor 1건 (§3 인벤토리 카운트 오기재) 외 새 major 결함 0.",

  "checklist": {
    "source": "_shared/checklists/discuss-ready.md",
    "items": [
      { "section": "scope", "item": "TOPIC UPPER-KEBAB-CASE", "status": "yes", "evidence": "PG-CONFIRM-LISTENER-SPLIT 변경 없음" },
      { "section": "scope", "item": "모듈/패키지 경계 명시", "status": "yes", "evidence": "§3 인벤토리 갱신 — pg-service 한정, hexagonal layer 별 신규/변경 컴포넌트 매핑" },
      { "section": "scope", "item": "non-goals ≥ 1 명시", "status": "yes", "evidence": "§non-goal 4건 유지" },
      { "section": "scope", "item": "범위 밖 이슈 처리", "status": "yes", "evidence": "§6 PHASE2 7건 명시 deferred" },
      { "section": "design decisions", "item": "hexagonal layer 배치 명시", "status": "yes", "evidence": "§1.1 + §1.6 + §3 인벤토리 layer 별 컴포넌트 명시" },
      { "section": "design decisions", "item": "포트 인터페이스 위치 결정", "status": "yes", "evidence": "§1.6 application/port/in/PgInboxProcessUseCase 봉인" },
      { "section": "design decisions", "item": "신규 상태 → 상태 전이 다이어그램", "status": "yes", "evidence": "§1.5 PENDING 신규 + NONE 폐기, §1.8 위키 stateDiagram 인용 + §2.1 / §2.2 mermaid 본문 포함" },
      { "section": "design decisions", "item": "전체 결제 흐름 호환성", "status": "yes", "evidence": "PaymentConfirmConsumer 변경 0, §3 인벤토리 line 591" },
      { "section": "acceptance criteria", "item": "성공 조건 관찰 가능 형태", "status": "yes", "evidence": "§7.1 A1~A5 5건 — Mockito verify / records consumed/s / TX active 검증 등 관찰 방법까지 명시" },
      { "section": "acceptance criteria", "item": "실패 관찰 방법 명시", "status": "yes", "evidence": "§7.2 F1~F4 4건 — Micrometer gauge / counter / Loki EventType 노출 명시" },
      { "section": "verification plan", "item": "테스트 계층 결정", "status": "yes", "evidence": "§4.4 단위 7건 / 통합 4건 / 회귀 207 PASS 본문 명시" },
      { "section": "verification plan", "item": "벤치마크 지표 명시", "status": "n/a", "evidence": "§7.3 + §6 — 정량 SLO 는 PHASE2 위임. 본 토픽은 정합 작업" },
      { "section": "artifact", "item": "결정 사항 섹션 존재", "status": "yes", "evidence": "§1.1~§1.9 9개 결정 단위" },
      { "section": "round-1-absorption", "item": "C-F1 acceptance 흡수", "status": "yes", "evidence": "§7 신설, 성공 5 + 실패 4 신호 + 관찰 방법" },
      { "section": "round-1-absorption", "item": "C-F2 보정 5분기 다이어그램 흡수", "status": "yes", "evidence": "§2.1 정상 흐름 + §2.2 ALREADY_PROCESSED 5분기 sub-flowchart 분리" },
      { "section": "round-1-absorption", "item": "C-F3 보정 PENDING 우회 §1 승격", "status": "yes", "evidence": "§1.8 신설 + repo 메서드 4종 봉인" },
      { "section": "round-1-absorption", "item": "C-F4 안 A 단일화", "status": "yes", "evidence": "§1.5 안 B 기각 + 안 A 단일 채택" },
      { "section": "round-1-absorption", "item": "C-F5 워커 TX_A 두 메서드 분리", "status": "yes", "evidence": "§1.6 봉인 표 + §1.3 두 진입점" },
      { "section": "round-1-absorption", "item": "C-F6 테스트 3계층", "status": "yes", "evidence": "§4.4 단위/통합/회귀 본문 명시" }
    ],
    "total": 19,
    "passed": 18,
    "failed": 0,
    "not_applicable": 1
  },

  "scores": {
    "clarity": 0.90,
    "completeness": 0.92,
    "risk": 0.85,
    "testability": 0.88,
    "fit": 0.92,
    "mean": 0.894
  },

  "findings": [
    {
      "severity": "minor",
      "checklist_item": "산출물 정합 — §3 인벤토리 vs §1.5 enum 결정",
      "location": "docs/topics/PG-CONFIRM-LISTENER-SPLIT.md#3-컴포넌트-인벤토리 (line 575)",
      "problem": "§3 인벤토리 `PgInboxStatus` row 비고 컬럼이 '4 상태 (`PENDING / IN_PROGRESS / APPROVED / FAILED / QUARANTINED`)' 로 적혀 있으나 백틱 안 enum 5종이 나열되어 있고, §1.5 결정도 PENDING 신규 + 기존 4 종결/진행 = 5 상태. 단순 카운트 오기재.",
      "evidence": "§3 line 575 백틱 안 PENDING / IN_PROGRESS / APPROVED / FAILED / QUARANTINED 5개 vs '4 상태' 표기. §1.5 line 342 기존 4 + PENDING 신규.",
      "suggestion": "§3 line 575 '4 상태' → '5 상태' 정정. plan 단계 진입 전 typo 수정."
    }
  ],

  "previous_round_ref": "discuss-critic-1.md",
  "delta": {
    "newly_passed": [
      "성공 조건 관찰 가능 형태 (§7.1 신설)",
      "실패 관찰 방법 명시 (§7.2 신설)",
      "테스트 계층 결정 (§4.4 신설)",
      "C-F1 acceptance 흡수",
      "C-F2 보정 5분기 다이어그램 흡수",
      "C-F3 보정 PENDING 우회 §1 승격",
      "C-F4 안 A 단일화",
      "C-F5 워커 TX_A 두 메서드 분리",
      "C-F6 테스트 3계층"
    ],
    "newly_failed": [],
    "still_failing": []
  },

  "unstuck_suggestion": null
}
```
