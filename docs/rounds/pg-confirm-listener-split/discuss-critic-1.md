# discuss-critic-1

**Topic**: PG-CONFIRM-LISTENER-SPLIT
**Round**: 1
**Persona**: Critic

## Reasoning

채택안 §1 (1.1~1.8) 8 결정 / §2 to-be 다이어그램 / §3 인벤토리는 위키 `pg-confirm-flow.md` 의 분리 안 (listener / 워커 / 릴레이) 골격과 큰 틀에서 정합한다. 그러나 (a) 관찰 가능한 수락 조건이 본문에 부재하고, (b) §2 다이어그램이 위키 `중복 승인 응답 보정 ALREADY_PROCESSED` 5분기를 누락했으며, (c) 보정 경로의 PENDING 우회 결정이 §1 채택안으로 승격되지 못하고 §4.4 "검증 항목" 으로 잔존한다. major 3건 → **revise**.

## Checklist judgement

### scope (범위)
- TOPIC UPPER-KEBAB-CASE: **yes** (`PG-CONFIRM-LISTENER-SPLIT`, topic 헤더 line 1)
- 모듈/패키지 경계 명시: **yes** (`pg-service` 한정, §3 인벤토리 + 관련 코드 인덱스)
- non-goals ≥ 1: **yes** (§non-goal 4건, line 159~165)
- 범위 밖 이슈 처리: **yes** (DLQ → TQ-1, 운영 마이그레이션 / CDC / TC-13 → §6 PHASE2)

### design decisions (설계 결정)
- hexagonal layer 배치: **yes** (§1.1, §1.6 Layer 위치 — listener / application / infrastructure 명시)
- 포트 인터페이스 위치: **yes** (§1.6 후보1 권장, `application/port/in/PgInboxProcessUseCase`)
- 신규 상태 → 상태 전이 다이어그램: **partial / minor 미흡** (§1.5 가 PENDING 신규 + "기존 5 상태와 동등 layer" 만 텍스트로. 본문에 stateDiagram 미포함 — 위키 stateDiagram 인용으로 보완)
- 전체 결제 흐름 호환성: **yes** (payment-service 변경 0, listener 시그니처 유지, §1.1 + §non-goal)

### acceptance criteria (수락 조건)
- 성공 조건 관찰 가능: **no — major** (본문 어디에도 "p95 / TPS / 특정 테스트 pass / listener thread time 분리 측정값" 같은 관찰 지표 없음. §6 PHASE2 가 "측정 정밀화" 로 미루는 항목은 있으나 본 토픽 자체의 acceptance 지표 부재)
- 실패 관찰 방법: **no — major** (좀비 회수 메트릭 / `pg_inbox.process_fail_total` 카운터 §1.3 이 1건 언급되지만, "실패 시 어떤 신호로 식별하는가" 가 acceptance 수준에서 정리되지 않음)

### verification plan (검증 계획)
- 테스트 계층 결정: **partial / minor** (Round 0 ledger §outputs / §verification 에 "단위 + 통합 + 회귀" 명시. Topic 본문 §4 / §5 가 이를 본문에 옮기지 않음 — discuss 산출물 단독으로는 테스트 계층 미명시)
- 벤치마크 지표: **n/a** (§6 PHASE2 로 위임 — 본 토픽은 정합 작업이라 k6 신규 벤치마크 미요구. 단 acceptance 항목과 충돌 가능성 있음)

### artifact (산출물)
- "결정 사항" 섹션 존재: **yes** (§1 채택안 골격 1.1~1.8)

### domain risk (Domain Expert 전용 — Critic 참고만)
- 멱등성 / 장애 시나리오 / 재시도 / PII — Domain Expert 판정 영역. Critic 미판정.

### traceability (위키 SoT 정합)
- 위키 `pg-confirm-flow.md` 분리 안 거울: **yes** (§1.1~§1.4 가 위키 5단계 1:1 매핑)
- 위키 stateDiagram (`[*] → PENDING` 시작) 정합: **partial** (§1.5 안 A 권장은 정합. 안 B 제시는 위키 SoT 우회 가능성 — minor)
- 위키 "중복 승인 응답 보정" 5분기 매핑: **no — major** (§2 to-be flowchart 가 보정 경로 누락. §4.4 가 verification 항목으로만 처리. 위키 wiki line 262~277 5분기 vs topic 매핑 부재)
- 워커 TX_A 검사 조건 결정 정밀도: **partial / minor** (위키 sequence diagram line 169 는 `WHERE status=PENDING` 만. 토픽 §1.4 / §4.6 은 IN_PROGRESS 좀비도 take 가능성 열어 둠 — 결정 미완)

---

## Findings

### F1 (major) — 관찰 가능한 acceptance 부재
- **checklist_item**: "성공 조건이 관찰 가능한 형태로 기술됨" + "실패를 어떻게 관찰할지 명시됨"
- **location**: `docs/topics/PG-CONFIRM-LISTENER-SPLIT.md` 본문 전체 (acceptance 섹션 자체 부재)
- **problem**: 본 토픽이 분리 후 listener 가 벤더 latency 에 묶이지 않음을 약속하지만, 그 효과를 어떤 지표로 / 어떤 테스트로 / 어떤 메트릭으로 검증할지 본문에 없다. "비교 효과" 표 (line 136~144) 는 정성 비교만이고, "성공 = X 가 관찰됨" 형태가 없다.
- **evidence**: §검증 plan §4 는 정밀화 / 결정 항목만 6건이고, "p95 / 좀비 회수 latency / listener TX duration" 같은 관찰 지표 0건. §6 PHASE2 도 "측정 기반 정밀화" 로 미룸.
- **suggestion**: 본문에 "수락 조건" 섹션 신설. 후보 — (a) 단위/통합 테스트 추가 시 listener TX duration ≤ N ms / 벤더 호출 안 일어남 검증, (b) 통합 테스트에서 벤더 응답 지연 시 listener throughput 영향 0 시나리오, (c) `pg_inbox.process_fail_total` / `pg_inbox_channel_queue_size` 메트릭 노출 확인. 측정 정밀화는 PHASE2 라도 본 토픽의 "제대로 분리됐다" 수락 신호는 정의 가능.

### F2 (major) — §2 to-be 다이어그램이 ALREADY_PROCESSED 보정 5분기 누락
- **checklist_item**: "전체 결제 흐름과의 호환성이 검토됨" (위키 봉인 흐름 정합)
- **location**: `docs/topics/PG-CONFIRM-LISTENER-SPLIT.md#2-to-be-플로우차트` (line 396~435)
- **problem**: 위키 `pg-confirm-flow.md` "중복 승인 응답 보정 — ALREADY_PROCESSED" (wiki line 258~277) 가 5분기 보정 경로 (조회 실패 → 격리 / 일치 재발행 / 불일치 격리 / 부재+일치 → APPROVED 신설 / 부재+불일치 → QUARANTINED 신설) 를 봉인. Topic §2 to-be 다이어그램은 정상 흐름만 그리고 이 보정 경로 진입점 (벤더 응답이 ALREADY_PROCESSED 일 때 워커가 어떤 분기로 가는지) 이 다이어그램에 없다.
- **evidence**: Topic §2 의 `WV[벤더 HTTP 호출]` → `WTxB[TX_B ...]` 까지가 단일 화살표. 위키 line 234~244 의 "벤더 응답 4분기" + line 263~274 의 "ALREADY_PROCESSED 5분기" 는 §2 다이어그램에 0건 매핑.
- **suggestion**: §2 에 보정 경로 sub-flowchart 추가하거나, `WV → 응답분류` 분기 노드 추가하여 4 (또는 5) 응답 분기 + ALREADY_PROCESSED 보정 진입을 명시. 또는 위키 다이어그램으로의 명시적 cross-reference 라도 §2 안에 인용.

### F3 (major) — 보정 경로 PENDING 우회 결정 미승격
- **checklist_item**: "결정 사항" (§1 의 8 결정에 보정 경로 inbox 신설 status 가 빠짐)
- **location**: `docs/topics/PG-CONFIRM-LISTENER-SPLIT.md#44-duplicateapprovalhandler-보정-경로의-pending-우회` (line 491~496) + `#1.6 PgConfirmService 분기 재배치` (line 313~)
- **problem**: 위키 stateDiagram (wiki line 116~127) 은 `PENDING --> QUARANTINED: 벤더만 승인 + 이력 부재 + 금액 불일치` 를 봉인. 즉 보정 경로에서 inbox 신설 시 PENDING 진입 후 QUARANTINED 로 갈 수도 / `Inbox 신설 → APPROVED` 로 직행할 수도 (wiki line 272 ApprA) 있음. Topic §4.4 는 "PENDING 단계 거치지 않고 바로 IN_PROGRESS / QUARANTINED 진입" 이라고 critic 라운드에 위임만 한다. 이는 §1 8 결정 중 누락 — listener INSERT PENDING 이 항상 적용되는지 / 보정 경로는 예외인지가 §1 결정으로 승격 안 됨.
- **evidence**: §1.1 listener 책임이 "분기별 listener 책임" 표에서 inbox 부재 / IN_PROGRESS / 종결 3분기만 다루고, "보정 경로 (DuplicateApprovalHandler) 가 inbox 신설하는 경우" 가 listener 분기 표에 없음. §3 인벤토리 `DuplicateApprovalHandler` row 가 변경 / 영향 명시만 적고 정확히 어떤 status 로 신설하는지 §1.5 enum 결정과 정합 안 됨.
- **suggestion**: §1 에 결정 1.9 신설 — "보정 경로 (DuplicateApprovalHandler) 의 inbox 신설은 PENDING 거치지 않고 바로 종결 (APPROVED / QUARANTINED) 으로 신설. 워커 큐 우회. 위키 stateDiagram `[*] → APPROVED` / `[*] → QUARANTINED` (또는 `PENDING → QUARANTINED`) 어느 경로 채택할지 명시." 그리고 §1.5 enum 결정과 §1.6 분기 재배치에 cross-link.

### F4 (minor) — §1.5 안 B (NONE 보존) 가 위키 SoT 우회 가능성
- **checklist_item**: "위키 = 진실원" 정합 (Round 0 ledger §constraints 봉인)
- **location**: `docs/topics/PG-CONFIRM-LISTENER-SPLIT.md#15-pg_inbox-status-enum--flyway-migration` (line 293~310)
- **problem**: 위키 stateDiagram 은 `[*] --> PENDING` 으로 시작 — `NONE` 상태는 위키 어디에도 없다. 즉 위키 SoT 는 안 A (NONE 폐기) 와 정합. 안 B 제시는 토픽 본문에서 "마이그레이션 부담 최소" 사유로 적었으나, Round 0 ledger §constraints "위키 = 진실원, 코드를 위키에 정합" 과 충돌. Round 1 권장은 안 A 로 정확하나 안 B 제시 자체가 결정 정밀도 노이즈.
- **evidence**: Wiki `pg-confirm-flow.md` line 79 (`PENDING → IN_PROGRESS → APPROVED / FAILED / QUARANTINED`) + line 116~127 stateDiagram. NONE 미언급. Topic line 302~304 안 B 가 "NONE 도메인 객체 임시 sentinel 로만 남음" 으로 위키와 다른 도메인 의미 보존.
- **suggestion**: §1.5 본문에서 안 B 를 "위키 SoT 비정합으로 기각" 으로 명시 또는 안 B 제거. Round 1 권장 안 A 단일화. critic / domain expert 라운드 영향 평가는 "안 A 채택 시 호출처 인벤토리" 한 갈래만.

### F5 (minor) — 워커 TX_A status 검사 조건 결정 미완
- **checklist_item**: "결정 사항" 정밀도
- **location**: `docs/topics/PG-CONFIRM-LISTENER-SPLIT.md#46-워커-process-메서드-시그니처--status-검사-조건` (line 504~507) + `#1.4` (line 277)
- **problem**: 위키 sequence diagram (wiki line 169) 은 워커 TX_A 가 `WHERE id=? AND status=PENDING` 만 검사. Topic §1.4 IN_PROGRESS 좀비 회수 path 는 `WHERE status=IN_PROGRESS` 로 별 SQL 필요. §4.6 가 "두 메서드 분리" 권장하지만 §1 결정으로 승격 안 됨.
- **evidence**: §1.3 워커 처리 흐름 line 240~242 가 "SELECT FOR UPDATE SKIP LOCKED WHERE id=? AND status=PENDING" 만 명시. §1.4 좀비 폴링 line 277 이 "TX_A 의 검사 조건을 status IN (PENDING, IN_PROGRESS) 로 확장하거나 별 메서드 분리 — 결정 항목".
- **suggestion**: §1.6 또는 §1.3 에 결정 명시 — `processPending(inboxId)` (PENDING 검사) 와 `processInProgressZombie(inboxId)` (IN_PROGRESS 검사) 두 진입점 분리. 위키 sequence diagram 정합 + 좀비 회수 명료화.

### F6 (minor) — 본문에 테스트 계층 명시 부재
- **checklist_item**: "테스트 계층이 결정됨"
- **location**: `docs/topics/PG-CONFIRM-LISTENER-SPLIT.md` §4 / §5 (line 468~518)
- **problem**: Round 0 ledger §outputs / §verification 에 "단위 + 통합 + 회귀 + 위키-코드 정합" 4계층 명시. Topic 본문 §4 / §5 가 이를 본문에 echo 안 함. discuss 산출물 본문 단독으로는 plan 단계가 어떤 테스트 계층까지 갈지 추적 불가.
- **evidence**: §4 항목 6건 모두 "정밀화 결정" 류. "단위 / 통합 / k6" 같은 계층 단어 본문 0건.
- **suggestion**: §4 또는 §검증 plan 신설하여 "단위 (워커 TX_A → TX_B 분리, 좀비 회수, 채널 가득 폴백) + 통합 (Embedded Kafka + Testcontainers MySQL, 벤더 latency 격리, DuplicateApprovalHandler 좀비 보정) + 회귀 (기존 207 PASS 유지)" 3계층 본문 명시.

---

## JSON

```json
{
  "stage": "discuss",
  "persona": "critic",
  "round": 1,
  "task_id": null,

  "decision": "revise",
  "reason_summary": "위키 SoT 분리 안 골격은 §1~§3 가 1:1 매핑하지만, 관찰 가능한 acceptance 부재 + §2 다이어그램의 ALREADY_PROCESSED 보정 5분기 누락 + 보정 경로 PENDING 우회 결정 미승격 등 major 3건이 잔존. minor 3건은 결정 정밀도 노이즈.",

  "checklist": {
    "source": "_shared/checklists/discuss-ready.md",
    "items": [
      { "section": "scope", "item": "TOPIC UPPER-KEBAB-CASE", "status": "yes", "evidence": "PG-CONFIRM-LISTENER-SPLIT, topic line 1" },
      { "section": "scope", "item": "모듈/패키지 경계 명시", "status": "yes", "evidence": "§3 인벤토리 + 관련 코드 인덱스" },
      { "section": "scope", "item": "non-goals ≥ 1 명시", "status": "yes", "evidence": "§non-goal 4건, line 159~165" },
      { "section": "scope", "item": "범위 밖 이슈 처리", "status": "yes", "evidence": "DLQ → TQ-1, CDC / 운영 마이그 → §6 PHASE2" },
      { "section": "design decisions", "item": "hexagonal layer 배치 명시", "status": "yes", "evidence": "§1.1, §1.6 Layer 위치" },
      { "section": "design decisions", "item": "포트 인터페이스 위치 결정", "status": "yes", "evidence": "§1.6 후보1, application/port/in/PgInboxProcessUseCase" },
      { "section": "design decisions", "item": "신규 상태 → 상태 전이 다이어그램", "status": "yes", "evidence": "§1.5 PENDING 신규 + 위키 stateDiagram 인용 (본문 stateDiagram 부재는 minor)" },
      { "section": "design decisions", "item": "전체 결제 흐름 호환성", "status": "yes", "evidence": "payment-service 변경 0, listener 시그니처 유지" },
      { "section": "acceptance criteria", "item": "성공 조건 관찰 가능 형태", "status": "no", "evidence": "본문 acceptance 섹션 자체 부재. §검증 plan §4 는 정밀화 항목만 6건" },
      { "section": "acceptance criteria", "item": "실패 관찰 방법 명시", "status": "no", "evidence": "§1.3 메트릭 1건 외 acceptance 수준 정리 없음" },
      { "section": "verification plan", "item": "테스트 계층 결정", "status": "no", "evidence": "Round 0 ledger 에는 명시되나 topic 본문 §4/§5 에 echo 안 됨" },
      { "section": "verification plan", "item": "벤치마크 지표 명시", "status": "n/a", "evidence": "§6 PHASE2 로 위임 — 본 토픽 정합 작업" },
      { "section": "artifact", "item": "결정 사항 섹션 존재", "status": "yes", "evidence": "§1 채택안 골격 1.1~1.8" }
    ],
    "total": 13,
    "passed": 9,
    "failed": 3,
    "not_applicable": 1
  },

  "scores": {
    "clarity": 0.78,
    "completeness": 0.62,
    "risk": 0.72,
    "testability": 0.55,
    "fit": 0.80,
    "mean": 0.694
  },

  "findings": [
    {
      "severity": "major",
      "checklist_item": "성공 조건 관찰 가능 + 실패 관찰 방법",
      "location": "docs/topics/PG-CONFIRM-LISTENER-SPLIT.md (acceptance 섹션 부재)",
      "problem": "분리 효과 (listener 가 벤더 latency 에 묶이지 않음) 를 어떤 관찰 지표 / 테스트 / 메트릭으로 검증할지 본문에 없다. 비교 효과 표는 정성 비교뿐.",
      "evidence": "§검증 plan §4 는 정밀화 / 결정 항목만 6건. p95 / 좀비 회수 latency / listener TX duration 등 관찰 지표 0건. §6 PHASE2 도 측정 기반 정밀화로 미룸.",
      "suggestion": "본문에 수락 조건 섹션 신설. 후보 — (a) listener TX duration ≤ N ms / 벤더 호출 미발생 단위 검증, (b) 벤더 응답 지연 시 listener throughput 영향 0 통합 시나리오, (c) pg_inbox_channel_queue_size / pg_inbox.process_fail_total 메트릭 노출 확인. 측정 정밀화는 PHASE2 라도 본 토픽의 분리 완료 acceptance 신호는 정의 가능."
    },
    {
      "severity": "major",
      "checklist_item": "전체 결제 흐름 호환성 (위키 봉인 흐름 정합)",
      "location": "docs/topics/PG-CONFIRM-LISTENER-SPLIT.md#2-to-be-플로우차트 (line 396~435)",
      "problem": "위키 pg-confirm-flow.md 의 중복 승인 응답 보정 (ALREADY_PROCESSED) 5분기 (조회실패→격리 / 일치 재발행 / 불일치 격리 / 부재+일치 APPROVED 신설 / 부재+불일치 QUARANTINED 신설) 가 §2 다이어그램에 0건 매핑. 정상 흐름만 그려져 있음.",
      "evidence": "§2 의 WV → WTxB 가 단일 화살표. 위키 line 234~244 (벤더 응답 4분기) + line 263~274 (ALREADY_PROCESSED 5분기) 인용 부재.",
      "suggestion": "§2 에 보정 경로 sub-flowchart 추가 또는 WV → 응답분류 분기 노드 + ALREADY_PROCESSED 진입 명시. 또는 위키 다이어그램으로의 명시적 cross-reference 인용."
    },
    {
      "severity": "major",
      "checklist_item": "결정 사항 (§1 8 결정 누락)",
      "location": "docs/topics/PG-CONFIRM-LISTENER-SPLIT.md#44-duplicateapprovalhandler-보정-경로의-pending-우회 (line 491~496)",
      "problem": "보정 경로 (DuplicateApprovalHandler) 의 inbox 신설이 PENDING 거치지 않고 바로 종결 (APPROVED / QUARANTINED) 로 신설된다는 결정이 §4.4 검증 항목으로만 표기. §1 8 결정 중 listener INSERT PENDING 이 항상 적용되는지 / 보정 경로는 예외인지가 결정 사항으로 승격 안 됨.",
      "evidence": "§1.1 listener 책임 표 (분기 3건 — inbox 부재 / IN_PROGRESS / 종결) 가 보정 경로 inbox 신설 분기 미포함. §3 DuplicateApprovalHandler row 가 변경 영향 명시만 하고 status 신설 결정 부재. 위키 stateDiagram (line 116~127) 의 PENDING → QUARANTINED / [*] → APPROVED 와 정합 결정 부재.",
      "suggestion": "§1 에 결정 1.9 신설 — 보정 경로의 inbox 신설은 PENDING 거치지 않고 바로 종결 (APPROVED / QUARANTINED) 로 신설, 워커 큐 우회. §1.5 enum 결정과 §1.6 분기 재배치에 cross-link."
    },
    {
      "severity": "minor",
      "checklist_item": "위키 = 진실원 정합",
      "location": "docs/topics/PG-CONFIRM-LISTENER-SPLIT.md#15-pg_inbox-status-enum--flyway-migration (line 293~310)",
      "problem": "안 B (NONE 보존) 제시가 위키 stateDiagram ([*] → PENDING) 과 비정합. 위키 SoT 는 NONE 미언급. Round 1 권장은 안 A 로 정확하나 안 B 제시 자체가 결정 정밀도 노이즈.",
      "evidence": "Wiki pg-confirm-flow.md line 79, line 116~127 stateDiagram — NONE 0건. Topic line 302~304 안 B 가 도메인 sentinel 로 NONE 보존.",
      "suggestion": "§1.5 안 B 를 위키 SoT 비정합으로 기각 명시 또는 제거. Round 1 권장 안 A 단일화."
    },
    {
      "severity": "minor",
      "checklist_item": "결정 사항 정밀도 (워커 TX_A status 검사)",
      "location": "docs/topics/PG-CONFIRM-LISTENER-SPLIT.md#46 (line 504~507) + §1.4 (line 277)",
      "problem": "워커 TX_A 검사 조건이 PENDING 만 / PENDING+IN_PROGRESS 둘 다 / 두 메서드 분리 — §1 결정으로 승격 안 됨. §4.6 권장 (분리) 만 있음.",
      "evidence": "§1.3 line 240~242 'WHERE id=? AND status=PENDING' / §1.4 line 277 '검사 조건을 status IN (PENDING, IN_PROGRESS) 로 확장하거나 별 메서드 분리 — 결정 항목'.",
      "suggestion": "§1.6 또는 §1.3 에 결정 명시 — processPending(inboxId) + processInProgressZombie(inboxId) 두 진입점 분리. 위키 sequence diagram (TX_A WHERE status=PENDING) 정합 유지."
    },
    {
      "severity": "minor",
      "checklist_item": "테스트 계층 결정",
      "location": "docs/topics/PG-CONFIRM-LISTENER-SPLIT.md §4 / §5 (line 468~518)",
      "problem": "Round 0 ledger §outputs / §verification 의 단위+통합+회귀+위키-코드 정합 4계층이 topic 본문에 echo 안 됨. discuss 산출물 단독으로 plan 단계 테스트 계층 추적 불가.",
      "evidence": "§4 항목 6건 모두 정밀화 결정 류. 단위 / 통합 / k6 단어 본문 0건.",
      "suggestion": "§4 또는 검증 plan 섹션에 3계층 본문 명시 — 단위 (워커 TX 분리 / 좀비 회수 / 채널 가득 폴백) + 통합 (Embedded Kafka + Testcontainers, 벤더 latency 격리, 좀비 보정 시나리오) + 회귀 (207 PASS 유지)."
    }
  ],

  "previous_round_ref": null,
  "delta": null,

  "unstuck_suggestion": null
}
```
