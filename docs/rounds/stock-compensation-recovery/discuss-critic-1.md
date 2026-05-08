# discuss-critic-1

**Topic**: STOCK-COMPENSATION-RECOVERY
**Round**: 1
**Persona**: Critic

## Reasoning

설계 문서는 사용자 결정 4건(회복 메커니즘=RDB 보상 outbox / 적재 단위=주문 항목 N행 / 재시도=FIXED 5s × max 5 / DLQ=RDB FAILED)을 9건의 결정으로 정합하게 풀어냈고, hexagonal layer 룰(domain → port → application → infrastructure)도 §3 에서 위반 없이 배치했다. 멱등성 4-layer, 부분 실패 격리, dedupe two-phase lease 와의 무충돌이 §5 에 명시되어 도메인 리스크는 충분히 다뤄졌다. 다만 (a) yml 키 표기가 실제 코드(`payment.retry.base-delay-ms`)와 어긋나 `payment.retry.fixed-delay-ms` 로 적힌 점, (b) 호출자가 검증 포인트로 적시한 mermaid 결정 노드 `{...}` 가 §2 mermaid 블록 4곳에 그대로 사용된 점이 부정확. 모두 plan 단계 진입 전 1회 patch 로 해소 가능한 major 수준이며 critical 결함은 없다 → **revise**.

## Checklist judgement

### scope
- TOPIC UPPER-KEBAB-CASE — **yes** (`STOCK-COMPENSATION-RECOVERY`).
- 모듈 경계 — **yes** (payment-service 내부, §3 layer 표 + Round 0 scope 트랙).
- non-goals ≥ 1 — **yes** (§7.1 한계 4건: admin 도구 / RDB 다운 회복 / 메시지 손실 / Toxiproxy. 명시 섹션은 아니지만 산재).
- 범위 밖 이슈 위임/포함 — **yes** (§7.2 후속 표 + CONCERNS C-5 부분 해소 표기).

### design decisions
- hexagonal layer 배치 — **yes** (§3.1~§3.4).
- 포트 위치 결정 — **yes** (`application/port/out/StockCompensationOutboxRepository`).
- 상태 전이 다이어그램 — **yes** (§2.3 워커 회복 사이클 mermaid 가 PENDING → processedAt / FAILED 전이를 표현).
- 전체 결제 흐름 호환성 — **yes** (§5.3 dedupe lease 무충돌, §5.4 IN_PROGRESS self-loop race 가드).

### acceptance criteria
- 성공 조건 관찰 가능 — **yes** (§6.2 통합 테스트 시나리오 3종 + outbox `processedAt non-null` / FAILED 마킹 / attempt 한도 동작이 검증 단위).
- 실패 관찰 — **yes** (§3.5 메트릭 3종 + EventType 3종, §7.2 알람 임계 연계).

### verification plan
- 테스트 계층 결정 — **yes** (§6.1 단위 / §6.2 Testcontainers 통합 / §6.3 회귀 영향).
- 벤치마크 지표 — **n/a** (happy path INSERT 0 가정으로 벤치마크 변동 없음. §1 A1).

### artifact
- "결정 사항" 섹션 — **yes** (§1 결정 9건 표 + §4 상세 근거).

### domain risk
- 멱등성 전략 — **yes** (§5.1 4-layer: UNIQUE / SKIP LOCKED / processedAt 가드 / lease).
- 장애 시나리오 ≥ 3 — **yes** (Redis 장애·RDB 다운·Kafka redeliver·부분 실패·IN_PROGRESS race 5개 식별).
- 재시도 정책 — **yes** (D5 + §4.5).
- PII — **n/a** (productId / quantity 만, 신규 PII 없음).

### 사실 정합성 (코드와의 일치)
- yml 키 표기 — **no** (§3.4 + §1 D5 의 `payment.retry.fixed-delay-ms: 5000` 표기는 실제 `payment.retry.base-delay-ms: 5000` + `backoff-type: FIXED` 와 불일치).

### 호출자 명시 검증 포인트
- Mermaid 금지 문자 `{ }` 부재 — **no** (§2.1 line 5, §2.1 line 13, §2.2 line 27, §2.3 line 61 — 결정 노드 4곳에 `{...}` 그대로 사용).

## Findings

### F1 (major)
- **checklist_item**: 호출자 명시 검증 — Mermaid 금지 문자 `{` `}` 미사용
- **location**: `docs/topics/STOCK-COMPENSATION-RECOVERY.md` §2.1 (line 22, 30), §2.2 (line 47), §2.3 (line 148)
- **problem**: as-is 와 to-be mermaid 블록에 결정 노드 `{메시지 status}`, `{INCR 결과}`, `{8일 잠금 키 살아있는가?}`, `{attempt 가 max 도달?}` 4곳이 그대로 사용됨. 호출자가 검증 포인트로 `{` `}` 를 명시 금지로 적시함.
- **evidence**: `B -->|잠금 획득| C{메시지 status}` (§2.1 line 22), `G --> H{INCR 결과}` (§2.1 line 30), `P1 --> P2{8일 잠금 키 살아있는가?}` (§2.2 line 47), `R -->|RuntimeException| N{attempt 가 max 도달?}` (§2.3 line 148).
- **suggestion**: 결정 노드를 모두 사각 노드로 치환. 예: `C{메시지 status}` → `C[메시지 status 분기]`, edge 라벨로 분기 조건 표현. 4건 모두 같은 패턴으로 일괄 patch.

### F2 (major)
- **checklist_item**: 사실 정합성 — 기존 yml 키 표기가 실제 코드와 일치
- **location**: `docs/topics/STOCK-COMPENSATION-RECOVERY.md` §1 D5 (line 94), §3.4 (line 199), §4.5 (line 276)
- **problem**: "기존 `payment.retry.fixed-delay-ms: 5000` 재사용" 이라고 표기되었으나 실제 `application.yml` line 97~101 의 키는 `payment.retry.base-delay-ms: 5000` + `payment.retry.backoff-type: FIXED` 이다. plan/execute 단계 implementer 가 이 표기를 그대로 따르면 존재하지 않는 키를 주입 시도해 컴파일은 통과하되 default fallback (5000ms) 만 동작하는 silent 버그가 된다.
- **evidence**: `payment-service/src/main/resources/application.yml` line 97-101 (`payment.retry: max-attempts: 5, backoff-type: FIXED, base-delay-ms: 5000, max-delay-ms: 60000`); `RetryPolicyProperties.java` line 21 (`@DefaultValue("5000") long baseDelayMs`).
- **suggestion**: D5 / §3.4 / §4.5 모든 곳에서 `payment.retry.fixed-delay-ms` → `payment.retry.base-delay-ms` 정정. 또는 워커 폴링 주기는 별도 키 (`scheduler.stock-compensation-worker.fixed-delay-ms`) 로 명확히 분리하고, retry policy 의 base-delay-ms 는 `scheduleNextAttempt(delay)` 의 delay 값 출처로만 사용한다고 명시.

### F3 (minor)
- **checklist_item**: scope — non-goals 명시 섹션
- **location**: `docs/topics/STOCK-COMPENSATION-RECOVERY.md` 전반
- **problem**: 범위 외 항목 (admin 도구 / RDB 다운 / 메시지 손실 / Toxiproxy) 이 §7.1 + 본문 산문에 흩어져 있고 별도 "Non-goals" 섹션 헤더가 없다. 체크리스트 기준 "최소 1개 이상 명시" 는 관대 해석으로 통과하지만 plan-reviewer 가 한 번에 짚기 어렵다.
- **evidence**: §7.1 line 395-398 한계 4건은 정확히 non-goals 의미지만 헤더가 "본 설계의 한계" 라 검색성 낮음.
- **suggestion**: §7.1 헤더를 "본 설계의 한계 / Non-goals" 로 보강하거나 §1 직전에 별도 "Non-goals" 한 줄 표를 둔다.

### F4 (minor)
- **checklist_item**: design decisions — 컴포넌트 책임 정합성
- **location**: `docs/topics/STOCK-COMPENSATION-RECOVERY.md` §3.2 line 176-179
- **problem**: §3.2 표에서 `StockCompensationOutboxAppender` 는 `application/usecase/` 에, `StockCompensationRetryService` 는 `application/service/` 에 배치되었다. 두 컴포넌트 모두 application use case 인데 패키지 분리 기준이 명시되지 않음. 기존 컨벤션 (`StockOutboxRelayService` 가 service, `OutboxRelayService` 등도 service) 과 비교하면 합리적이지만 §4 에 근거가 없다.
- **evidence**: §3.2 표 위치 컬럼 "`.../application/usecase/StockCompensationOutboxAppender.java`" vs "`.../application/service/StockCompensationRetryService.java`".
- **suggestion**: §4 결정 상세에 "appender = use case (1회성 INSERT 동작), retryService = service (워커 호출 다회·상태 전이 다분기) 로 분리 — 기존 `StockOutboxRelayService` 컨벤션 따름" 한 줄 추가.

## JSON

```json
{
  "stage": "discuss",
  "persona": "critic",
  "round": 1,
  "task_id": null,

  "decision": "revise",
  "reason_summary": "사용자 결정 4건이 9건 결정으로 정합 풀이되었고 layer / 멱등성 / 장애 시나리오 / 테스트 계층 모두 충족. 다만 yml 키 표기가 실제 코드와 어긋나고 mermaid 결정 노드 `{...}` 4곳이 호출자 명시 금지 문자를 사용해 major 2건. critical 없음.",

  "checklist": {
    "source": "_shared/checklists/discuss-ready.md",
    "items": [
      {
        "section": "scope",
        "item": "non-goals(이번 작업에서 안 할 것)가 최소 1개 이상 명시됨",
        "status": "yes",
        "evidence": "docs/topics/STOCK-COMPENSATION-RECOVERY.md §7.1 line 395-398 (한계 4건: admin 도구 / RDB 다운 / 메시지 손실 / Toxiproxy)"
      },
      {
        "section": "design decisions",
        "item": "hexagonal layer 배치가 명시됨",
        "status": "yes",
        "evidence": "§3.1 domain / §3.2 application / §3.3 infrastructure 표"
      },
      {
        "section": "design decisions",
        "item": "포트 인터페이스 위치 결정",
        "status": "yes",
        "evidence": "§3.2: application/port/out/StockCompensationOutboxRepository.java"
      },
      {
        "section": "design decisions",
        "item": "상태 전이 다이어그램 존재",
        "status": "yes",
        "evidence": "§2.3 mermaid (PENDING → processedAt 종결 / FAILED 마킹)"
      },
      {
        "section": "design decisions",
        "item": "전체 결제 흐름 호환성 검토",
        "status": "yes",
        "evidence": "§5.3 dedupe two-phase lease 무충돌 / §5.4 IN_PROGRESS self-loop race 가드"
      },
      {
        "section": "acceptance criteria",
        "item": "성공 조건이 관찰 가능한 형태",
        "status": "yes",
        "evidence": "§6.2 통합 테스트 시나리오 (processedAt non-null / FAILED 마킹 / 부분 처리)"
      },
      {
        "section": "acceptance criteria",
        "item": "실패 관찰 (로그/지표/테스트)",
        "status": "yes",
        "evidence": "§3.5 Counter 3종 + EventType 3종"
      },
      {
        "section": "verification plan",
        "item": "테스트 계층 결정",
        "status": "yes",
        "evidence": "§6.1 단위 / §6.2 Testcontainers 통합 / §6.3 회귀"
      },
      {
        "section": "verification plan",
        "item": "벤치마크 지표",
        "status": "n/a",
        "evidence": "happy path INSERT 0 (A1) — 벤치마크 영향 없음"
      },
      {
        "section": "artifact",
        "item": "결정 사항 섹션 존재",
        "status": "yes",
        "evidence": "§1 결정 9건 표 + §4 상세"
      },
      {
        "section": "domain risk",
        "item": "멱등성 전략 결정",
        "status": "yes",
        "evidence": "§5.1 4-layer: UNIQUE (order_id, product_id) / SKIP LOCKED / processedAt 가드 / lease"
      },
      {
        "section": "domain risk",
        "item": "장애 시나리오 최소 3개",
        "status": "yes",
        "evidence": "Redis 장애 / RDB 다운 / Kafka redeliver / 부분 실패 / IN_PROGRESS race — 5건"
      },
      {
        "section": "domain risk",
        "item": "재시도 정책 정의",
        "status": "yes",
        "evidence": "D5 + §4.5 (FIXED 5s × max 5, attempt 도달 시 FAILED)"
      },
      {
        "section": "domain risk",
        "item": "PII 신규 도입 검토",
        "status": "n/a",
        "evidence": "productId / quantity / reasonCode 만 — 신규 PII 없음"
      },
      {
        "section": "사실 정합성",
        "item": "yml 키 표기가 실제 코드 application.yml 과 일치",
        "status": "no",
        "evidence": "§1 D5 / §3.4 / §4.5 의 `payment.retry.fixed-delay-ms: 5000` 표기가 실제 application.yml line 97-101 의 `payment.retry.base-delay-ms: 5000` 과 불일치"
      },
      {
        "section": "호출자 명시 검증",
        "item": "Mermaid 금지 문자 `{` `}` 미사용",
        "status": "no",
        "evidence": "§2.1 line 22 `{메시지 status}` / line 30 `{INCR 결과}` / §2.2 line 47 `{8일 잠금 키 살아있는가?}` / §2.3 line 148 `{attempt 가 max 도달?}`"
      }
    ],
    "total": 16,
    "passed": 12,
    "failed": 2,
    "not_applicable": 2
  },

  "scores": {
    "clarity": 0.85,
    "completeness": 0.88,
    "risk": 0.86,
    "testability": 0.82,
    "fit": 0.78,
    "mean": 0.838
  },

  "findings": [
    {
      "severity": "major",
      "checklist_item": "Mermaid 금지 문자 `{` `}` 미사용 (호출자 명시 검증 포인트)",
      "location": "docs/topics/STOCK-COMPENSATION-RECOVERY.md §2.1 line 22, line 30, §2.2 line 47, §2.3 line 148",
      "problem": "as-is / to-be mermaid 블록의 결정 노드 4곳에 `{...}` 가 그대로 사용됨. 호출자가 검증 포인트로 `{` `}` 를 mermaid 금지 문자로 적시함.",
      "evidence": "`C{메시지 status}` `H{INCR 결과}` `P2{8일 잠금 키 살아있는가?}` `N{attempt 가 max 도달?}` — mermaid 결정 노드 4건",
      "suggestion": "결정 노드를 사각 노드 + edge 라벨 분기로 변환. 예: `C[메시지 status 분기] -->|APPROVED| ...`, `H[INCR 결과 분기] -->|성공| ...`. 4건 모두 동일 패턴으로 일괄 patch."
    },
    {
      "severity": "major",
      "checklist_item": "yml 키 표기가 실제 코드와 일치",
      "location": "docs/topics/STOCK-COMPENSATION-RECOVERY.md §1 D5 (line 94), §3.4 (line 199), §4.5 (line 276)",
      "problem": "기존 `payment.retry.fixed-delay-ms: 5000` 재사용이라고 표기되었으나 실제 application.yml line 97-101 의 키는 `payment.retry.base-delay-ms: 5000` + `payment.retry.backoff-type: FIXED`. plan/execute implementer 가 이 표기를 따르면 존재하지 않는 키를 주입해 default fallback 만 동작하는 silent 버그.",
      "evidence": "payment-service/src/main/resources/application.yml line 97-101: `payment.retry: max-attempts: 5, backoff-type: FIXED, base-delay-ms: 5000, max-delay-ms: 60000`. RetryPolicyProperties.java line 21 `@DefaultValue(\"5000\") long baseDelayMs`. 기존 yml 에 `fixed-delay-ms` 키는 scheduler.* 하위에만 존재 (line 120, 125).",
      "suggestion": "D5 / §3.4 / §4.5 의 `payment.retry.fixed-delay-ms` 를 `payment.retry.base-delay-ms` (또는 `RetryPolicyProperties.getBaseDelayMs()`) 로 정정. 워커 폴링 주기 키 `scheduler.stock-compensation-worker.fixed-delay-ms` 와 retry delay 키를 명확히 분리."
    },
    {
      "severity": "minor",
      "checklist_item": "scope — non-goals 명시 섹션",
      "location": "docs/topics/STOCK-COMPENSATION-RECOVERY.md §7.1 line 393-398",
      "problem": "범위 외 4건이 §7.1 본 설계의 한계 헤더 아래 산문으로 흩어져 plan-reviewer 가 한 번에 비교하기 어렵다.",
      "evidence": "§7.1 한계 1~4 (단일 워커 / FAILED 자동 회복 없음 / RDB 다운 / 메시지 손실) 가 비-goals 의미를 가지나 헤더가 한계로 표기됨",
      "suggestion": "§7.1 헤더를 \"본 설계의 한계 / Non-goals\" 로 보강하거나 §1 직전에 한 줄 표 신설."
    },
    {
      "severity": "minor",
      "checklist_item": "design decisions — 컴포넌트 책임 정합성",
      "location": "docs/topics/STOCK-COMPENSATION-RECOVERY.md §3.2 line 175-179",
      "problem": "Appender 는 application/usecase, RetryService 는 application/service 로 분리되어 있는데 분리 기준이 §4 에 명시되지 않아 implementer 의 패키지 선택 근거가 불명확.",
      "evidence": "§3.2 위치 컬럼: usecase/StockCompensationOutboxAppender.java vs service/StockCompensationRetryService.java",
      "suggestion": "§4 결정 상세에 분리 근거 한 줄 추가 (예: appender = 1회성 INSERT use case, retryService = 워커 호출 다회 + 상태 전이 다분기 service — 기존 StockOutboxRelayService 컨벤션 따름)."
    }
  ],

  "previous_round_ref": null,
  "delta": null,

  "unstuck_suggestion": null
}
```
