# discuss-critic-1

**Topic**: MSA-TRANSITION
**Round**: 1
**Persona**: Critic

## Reasoning

scope / acceptance-criteria / verification-plan / artifact 네 축은 대체로 충족한다. 29개 ADR 인덱스·의존 그래프·phase 분할·크래시×방어선 매트릭스까지 구조적 완결성은 높다. 다만 **design decisions** 블록에서 hexagonal layer 배치 / 포트 인터페이스 위치라는 체크리스트 두 항목이 문서 어디에도 명시되지 않았다. 모든 레이어 결정을 개별 ADR로 위임하는 것은 타당하나, 최소한 "이 토픽에서 새로 도입되는 컴포넌트(Gateway, Discovery, Config, Autoscaler, Kafka producer/consumer 어댑터, 분산 Idempotency 저장소)를 hexagonal layer 중 어디에 배치할 원칙인가"에 대한 방향성 진술은 discuss-gate 통과에 필요하다. critical이 아니므로 **revise**.

## Checklist judgement

### scope
- TOPIC UPPER-KEBAB-CASE: **yes** (line 1 `# MSA-TRANSITION`)
- 모듈/패키지 경계 명시: **yes** (§1-1 현 컨텍스트 4종 + §3 to-be 토폴로지)
- non-goals ≥1: **yes** (§1-3에 5개)
- 범위 밖 이슈 위임: **yes** (§1-3에서 CONCERNS.md 결함들을 "후속 토픽"으로, §8-4에서 INTEGRATIONS.md 불일치를 "후속 TODOS"로 위임 선언. 단, 실제 TODOS.md 업데이트는 post-phase에서 수행 예정)

### design decisions
- hexagonal layer 배치 명시: **no** — 문서 전체에 "어느 use case / adapter / domain에 무엇을 둘지"의 방향성 진술이 없다. ADR별 deferral뿐.
- 포트 인터페이스 위치: **no** — `application/port` vs `infrastructure/port` 어느 쪽인지 원칙 진술 없음.
- 새 상태 전이 다이어그램: **n/a** (새 상태 없음, QUARANTINED 등 기존 유지 — §7 row 10)
- 전체 결제 흐름 호환성: **yes** (§2, §6 phase, §7 crash×방어선 매트릭스 전체)

### acceptance criteria
- 관찰 가능 성공 조건: **yes** (§1-4의 4개 조건 — ADR 29개 4요소·compose 기동+k6·Toxiproxy 3종 복원·오토스케일러 코드)
- 실패 관찰 방법: **yes** (§1-1 Micrometer 5종 자산 + §7 매트릭스에서 크래시별 지표 매핑)

### verification plan
- 테스트 계층: **yes(부분)** — k6 + Toxiproxy 수준은 §1-4, §6 Phase 4에 명시. 단위/통합 계층의 명시적 기술은 없으나 discuss-gate에서는 최소 충족.
- 벤치마크 지표: **n/a** — §1-3에서 절대 TPS/latency 비목표 선언, §8-1 Mac I/O 천장 명시. 상대 비교·복원 여부로 치환.

### artifact
- "결정 사항" 섹션 존재: **yes(약)** — 섹션명은 "이행 원칙(§2)"로 다르지만 container-per-service, at-least-once + 멱등 consumer, Saga Choreography 기본, Reconciliation 루프, Strangler 5개 결정이 서술됨. 헤딩명 불일치는 minor.

## Findings

### F1 (major) — hexagonal layer 배치 원칙 미명시
- **checklist_item**: "hexagonal layer 배치(어느 layer에 무엇을 둘지)가 명시됨"
- **location**: `docs/topics/MSA-TRANSITION.md` 전체 (§2, §3 모두)
- **problem**: 본 토픽은 Gateway, Discovery, Config Server, Autoscaler, Kafka producer/consumer, 분산 Idempotency 저장소 등 **신규 컴포넌트 다수를 도입**한다. 그러나 이들이 기존 hexagonal 규칙(`application/port` · `application/service` · `infrastructure/adapter` · `core`) 중 어느 layer에 배치되는지에 대한 원칙 진술이 없다. 전부 개별 ADR로 deferral되어 있다.
- **evidence**: §1-1 line 127에서 "헥사고날 layer 규칙을 따라 공존"만 언급, §3 토폴로지(line 188~280)는 서비스 수준 그림으로 layer 매핑 없음. §4 ADR 표(line 294~366)의 어느 항목도 layer 배치를 primary decision으로 다루지 않음.
- **suggestion**: §2에 "2-6. hexagonal layer 배치 원칙" 서브섹션을 추가하여 다음 3개 규칙만이라도 discuss-gate에서 확정:
  1. Kafka producer(outbox relay) → `infrastructure/adapter/messaging`, 포트는 `application/port/out/EventPublisher`
  2. Kafka consumer(이벤트 구독) → `infrastructure/adapter/messaging/consumer`, use case는 `application/service`
  3. 분산 Idempotency 저장소(Redis) → `infrastructure/adapter/idempotency`, 포트는 `application/port/out/IdempotencyStore`(현 `IdempotencyStoreImpl` 승계)

### F2 (major) — 포트 인터페이스 위치 원칙 미결정
- **checklist_item**: "포트 인터페이스 위치(`application/port` vs `infrastructure/port`)가 결정됨"
- **location**: `docs/topics/MSA-TRANSITION.md` §2, §3
- **problem**: ADR-04(outbox), ADR-05/16(idempotency), ADR-13(AOP 운명), ADR-21(PG 물리 분리) 모두 포트 신설/재배치를 야기하지만, 프로젝트 기본 원칙(포트는 application 쪽에 둔다, 혹은 드물게 infrastructure) 중 어느 쪽을 따를지 명시되지 않았다.
- **evidence**: §2-1~§2-5 (line 156~184)에 원칙 5개 나열되나 포트 위치 언급 없음. §4의 ADR 표 어느 행도 "포트 위치" 결정을 포함하지 않음.
- **suggestion**: §2에 "모든 신규 포트는 `application/port/{in,out}`에 둔다. 인프라 기술 선택(Kafka vs Redis)은 adapter 구현체로만 드러낸다. 기존 관례(`PaymentCommandUseCase`, `IdempotencyStore` 등)를 승계한다."는 1문장 원칙 진술 추가.

### F3 (minor) — "결정 사항" 섹션 헤딩 부재
- **checklist_item**: "`docs/topics/<TOPIC>.md`에 '결정 사항' 섹션이 존재"
- **location**: `docs/topics/MSA-TRANSITION.md` §2 (line 156 `## § 2. 이행 원칙`)
- **problem**: 체크리스트가 요구하는 "결정 사항" 이름의 섹션이 없다. 내용상으로는 §2 "이행 원칙"이 그 역할을 하지만 헤딩명이 다르다.
- **evidence**: line 156 `## § 2. 이행 원칙`.
- **suggestion**: 헤딩을 `## § 2. 이행 원칙 (결정 사항)`으로 변경하거나, §2 도입부에 "본 섹션은 이번 discuss에서 확정된 결정 사항을 담는다."는 1문장 추가.

### F4 (minor) — 테스트 계층 기술 부분적
- **checklist_item**: "테스트 계층이 결정됨 (단위/통합/k6 중 어디까지 할지)"
- **location**: `docs/topics/MSA-TRANSITION.md` §1-4, §6 Phase 4 (line 147~152, 455~458)
- **problem**: k6 + Toxiproxy 장애 주입은 명시되었으나 단위/통합 테스트 범위가 불명. MSA 전환에서는 consumer 멱등성 테스트, outbox relay 테스트, cross-service 계약 테스트 등의 계층이 새로 등장한다.
- **evidence**: §1-4 line 151~152은 Toxiproxy+k6만 언급, 단위/통합 계층 범위 기술 없음.
- **suggestion**: §1-4에 "단위/통합 테스트는 각 서비스 분리 phase에서 기존 `./gradlew test` + Testcontainers 패턴을 승계. 신규 계층으로 consumer 멱등성·outbox relay·계약 테스트를 ADR-29에서 정의." 1문장 추가.

## JSON

```json
{
  "stage": "discuss",
  "persona": "critic",
  "round": 1,
  "task_id": null,

  "decision": "revise",
  "reason_summary": "scope/AC/verification/artifact은 충족하나 design-decisions 블록에서 hexagonal layer 배치 원칙과 포트 위치 원칙이 명시되지 않아 체크리스트 2개 항목이 실패. critical 없음.",

  "checklist": {
    "source": "_shared/checklists/discuss-ready.md",
    "items": [
      {
        "section": "scope",
        "item": "TOPIC이 UPPER-KEBAB-CASE로 확정됨",
        "status": "yes",
        "evidence": "docs/topics/MSA-TRANSITION.md:1"
      },
      {
        "section": "scope",
        "item": "이 변경이 건드리는 모듈/패키지 경계가 명시됨",
        "status": "yes",
        "evidence": "docs/topics/MSA-TRANSITION.md:127 (§1-1 컨텍스트 4종) + §3 to-be 토폴로지"
      },
      {
        "section": "scope",
        "item": "non-goals(이번 작업에서 안 할 것)가 최소 1개 이상 명시됨",
        "status": "yes",
        "evidence": "docs/topics/MSA-TRANSITION.md §1-3 line 139~145 (5개)"
      },
      {
        "section": "scope",
        "item": "범위 밖에서 발견된 이슈는 docs/context/TODOS.md로 위임됐거나 현재 스코프에 포함됨",
        "status": "yes",
        "evidence": "§1-3 CONCERNS.md 결함을 후속 토픽으로 위임 선언 + §8-4 라인 515 INTEGRATIONS.md 불일치 후속 TODOS 위임 선언"
      },
      {
        "section": "design decisions",
        "item": "hexagonal layer 배치(어느 layer에 무엇을 둘지)가 명시됨",
        "status": "no",
        "evidence": "§1-1에 '헥사고날 layer 규칙을 따라 공존'만 언급되고 §2 이행 원칙·§3 토폴로지 어디에도 신규 컴포넌트(Kafka producer/consumer, 분산 Idempotency, Gateway adapter)의 layer 배치 원칙 진술 없음"
      },
      {
        "section": "design decisions",
        "item": "포트 인터페이스 위치(application/port vs infrastructure/port)가 결정됨",
        "status": "no",
        "evidence": "§2-1~§2-5 line 156~184 원칙 5개에 포트 위치 언급 없음. ADR-04/05/13/16/21 모두 포트 신설/재배치를 유발하나 표 어디도 포트 위치를 primary로 다루지 않음"
      },
      {
        "section": "design decisions",
        "item": "새 상태가 추가되는 경우, 상태 전이 다이어그램이 있음",
        "status": "n/a",
        "evidence": "본 토픽은 새 payment state를 도입하지 않음 — §7 row 10 QUARANTINED 등 기존 상태 유지"
      },
      {
        "section": "design decisions",
        "item": "전체 결제 흐름과의 호환성이 검토됨",
        "status": "yes",
        "evidence": "§6 phase 0~5 전체가 결제 승인 비동기 흐름(§2-2 다이어그램) 호환 유지 전제. §7 crash×방어선 매트릭스가 현 자산 → MSA 자산 1:1 매핑"
      },
      {
        "section": "acceptance criteria",
        "item": "성공 조건이 관찰 가능한 형태로 기술됨",
        "status": "yes",
        "evidence": "§1-4 line 149~152 — 29 ADR 4요소, compose 기동+k6 통과, Toxiproxy 3종 최종 정합성 복원, 오토스케일러 코드 동작"
      },
      {
        "section": "acceptance criteria",
        "item": "실패를 어떻게 관찰할지(로그/지표/테스트) 명시됨",
        "status": "yes",
        "evidence": "§1-1 Micrometer 5종 + §7 크래시×방어선 매트릭스 각 행의 지표 매핑"
      },
      {
        "section": "verification plan",
        "item": "테스트 계층이 결정됨",
        "status": "yes",
        "evidence": "§1-4 k6 + Toxiproxy, §6 Phase 4. 단위/통합 계층은 약함(F4 minor)"
      },
      {
        "section": "verification plan",
        "item": "벤치마크 지표가 명시됨",
        "status": "n/a",
        "evidence": "§1-3 line 143 절대 TPS/latency 비목표 선언. §8-1 Mac I/O 천장 근거. 상대 비교·복원 여부로 치환"
      },
      {
        "section": "artifact",
        "item": "docs/topics/<TOPIC>.md에 '결정 사항' 섹션이 존재",
        "status": "yes",
        "evidence": "§2 '이행 원칙'이 결정 사항 역할. 헤딩명 불일치는 minor(F3)"
      }
    ],
    "total": 13,
    "passed": 9,
    "failed": 2,
    "not_applicable": 2
  },

  "scores": {
    "clarity": 0.82,
    "completeness": 0.70,
    "risk": 0.80,
    "testability": 0.72,
    "fit": 0.85,
    "mean": 0.778
  },

  "findings": [
    {
      "severity": "major",
      "checklist_item": "hexagonal layer 배치(어느 layer에 무엇을 둘지)가 명시됨",
      "location": "docs/topics/MSA-TRANSITION.md §2 / §3 (line 156~280)",
      "problem": "Gateway, Discovery, Config, Autoscaler, Kafka producer/consumer, 분산 Idempotency 저장소 등 신규 컴포넌트 다수가 도입되나 hexagonal layer(application/port · application/service · infrastructure/adapter · core) 중 어디에 배치되는지에 대한 원칙 진술이 없다.",
      "evidence": "§1-1 line 127 '헥사고날 layer 규칙을 따라 공존'만 언급. §2 이행 원칙 5개(line 156~184) 및 §3 토폴로지(line 188~280) 어디에도 layer 매핑 없음. §4 ADR 표 어느 항목도 layer 배치를 primary decision으로 다루지 않음.",
      "suggestion": "§2에 서브섹션 '2-6. hexagonal layer 배치 원칙' 추가: (1) Kafka producer → infrastructure/adapter/messaging, 포트는 application/port/out/EventPublisher; (2) Kafka consumer → infrastructure/adapter/messaging/consumer, use case는 application/service; (3) 분산 Idempotency 저장소(Redis) → infrastructure/adapter/idempotency, 포트는 기존 application/port/out/IdempotencyStore 승계."
    },
    {
      "severity": "major",
      "checklist_item": "포트 인터페이스 위치(application/port vs infrastructure/port)가 결정됨",
      "location": "docs/topics/MSA-TRANSITION.md §2 (line 156~184)",
      "problem": "ADR-04(outbox), ADR-05/16(idempotency), ADR-13(AOP 운명), ADR-21(PG 물리 분리) 모두 포트 신설/재배치를 유발하지만 포트 위치 원칙(application/port 기본 vs infrastructure/port 예외)이 어디에도 기술되지 않음.",
      "evidence": "§2-1~§2-5 line 156~184 원칙 5개에 포트 위치 언급 없음. §4 ADR 표에서도 포트 위치가 primary 결정 질문으로 잡힌 항목 부재.",
      "suggestion": "§2에 1문장 원칙 진술 추가: '모든 신규 포트는 application/port/{in,out}에 둔다. 인프라 기술(Kafka·Redis 등) 선택은 adapter 구현체로만 드러낸다. 기존 관례(PaymentCommandUseCase, IdempotencyStore)를 승계한다.'"
    },
    {
      "severity": "minor",
      "checklist_item": "docs/topics/<TOPIC>.md에 '결정 사항' 섹션이 존재",
      "location": "docs/topics/MSA-TRANSITION.md §2 line 156",
      "problem": "'결정 사항' 헤딩이 없고 '이행 원칙'으로 명명되어 있다. 내용상 5개 결정이 담겼으나 체크리스트 항목은 헤딩명을 요구.",
      "evidence": "line 156 '## § 2. 이행 원칙'.",
      "suggestion": "헤딩을 '## § 2. 이행 원칙 (결정 사항)'로 수정하거나 §2 도입부에 '본 섹션은 discuss에서 확정된 결정 사항이다' 1문장 추가."
    },
    {
      "severity": "minor",
      "checklist_item": "테스트 계층이 결정됨 (단위/통합/k6 중 어디까지)",
      "location": "docs/topics/MSA-TRANSITION.md §1-4 line 147~152, §6 Phase 4 line 455~458",
      "problem": "k6 + Toxiproxy 장애 주입은 명시되나 단위/통합 계층 범위와 MSA 신규 계층(consumer 멱등성·outbox relay·계약 테스트)의 존재가 불명.",
      "evidence": "§1-4는 Toxiproxy + k6만 언급. §6 Phase 4도 동일.",
      "suggestion": "§1-4에 '단위/통합은 각 phase에서 Testcontainers 승계. 신규 계층으로 consumer 멱등성·outbox relay·계약 테스트를 ADR-29에서 정의.' 1문장 추가."
    }
  ],

  "previous_round_ref": null,
  "delta": null,

  "unstuck_suggestion": null
}
```
