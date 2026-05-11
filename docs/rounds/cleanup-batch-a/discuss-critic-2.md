# discuss-critic-2

**Topic**: CLEANUP-BATCH-A
**Round**: 2
**Persona**: Critic

## Reasoning

Round 1 Domain Expert finding 4건 (D1 major / D2-D4 minor) 흡수가 본문에 명시 마커로 모두 검증된다. D1 가드 surface 인벤토리는 §1.2 "main 실제 보호 surface" 컬럼 + Round 2 흡수 노트 2건 (line 159~162)으로 정정됐고, `createDirectTerminal` 의 main 가드 분담이 어댑터 가드(`PgInboxRepositoryImpl.java:150`)로 명시됐다. D2 `PgInbox.create` 4 오버로드 main 호출처 0건 사실은 line 162 + 표 line 166 양쪽에 박혔다. D3 후속 등재 (Feign ErrorDecoder 429/503 분기 보존)는 §3 line 369 + §7.1 line 453에 신규 TODOS 항목 + [NET-RETRY] 태그 후보까지 명시. D4 Flyway named volume 재사용 시나리오는 §4.1 line 383~393 재평가 + §2 acceptance line 327 신규 행 + §3 STACK.md 갱신 행 line 371 + plan 단계 인벤토리 line 388~390에 다층 반영. Round 1 minor m1 (§1.3 검증 방식)은 plan 단계 위임 자체는 그대로지만 D4 흡수로 인해 named volume + ignore-migration-patterns default 확인 같은 plan 인풋이 보강돼 결정 근거가 더 단단해진 상태로 plan 단계로 넘어간다. 신규 critical / major 0건 — pass.

## Checklist judgement

### scope (4/4 yes)
- TOPIC UPPER-KEBAB-CASE: yes — line 1
- 모듈/패키지 경계 명시: yes — §1.1~§1.4 + §3 인벤토리
- non-goals: yes — §0 비범위 7건 line 111~118
- 범위 밖 이슈 위임: yes — D3 흡수로 `Feign ErrorDecoder 429/503 분기` 신규 TODOS 등재 결정 명시 (line 369, 453)

### design decisions (2 yes / 2 n/a)
- hexagonal layer 배치: yes
- 포트 인터페이스 위치: n/a — 신규 포트 0
- 상태 전이 다이어그램: n/a — 새 도메인 상태 0
- 결제 흐름 호환성 검토: yes — D1 흡수로 PG-CONFIRM-LISTENER-SPLIT m1 가드 surface 분담 (도메인 factory 가드 = 이중화 / 어댑터 가드 = main 활성) 정정 명시 (line 159~162, 168)

### acceptance criteria (2/2 yes)
- 관찰 가능 성공 조건: yes — §2 acceptance 표 + D4 흡수 신규 행 line 327 (named volume + ignore-migration-patterns default 인벤토리)
- 실패 관찰 방법: yes

### verification plan (1 yes / 1 n/a)
- 테스트 계층 결정: yes — §5 + minor m1은 plan 위임 유지, D4 흡수로 plan 인풋 보강 (line 388~390)
- 벤치마크 지표: n/a

### artifact (1/1 yes)
- 결정 사항 섹션: yes

### domain risk (4 항목 — D1~D4 흡수 후)
- 멱등성: n/a (§7.4 INSERT IGNORE 보강 유지)
- 장애 시나리오 3+: yes — §4 3건 유지 + 4.1 named volume 재평가 + 3-step 가이드 보강 (line 383~393)
- 재시도 정책: yes
- PII: n/a

## Findings

(critical / major 0건)

### Minor (참고만)
- **m1 (carry-over)** — §1.3 검증 방식 (Testcontainers vs 수동 스모크 vs healthcheck) 최종 1개 plan 단계 위임 유지. D4 흡수로 named volume + `ignore-migration-patterns` default 확인이 plan 인풋으로 추가돼 결정 근거가 보강됨. discuss 라운드 차단 사유 아님.
  - location: `docs/topics/CLEANUP-BATCH-A.md` line 247, 326, 388~390
  - 영향: §5 fallback (수동 스모크) 명시되어 검증 갭 0
  - 권고: plan 단계 초입에 (1) docker-compose `SPRING_PROFILES_ACTIVE=docker` 활성 검증, (2) named volume 재사용 케이스 1회 시뮬레이션, (3) Testcontainers 비용 vs 학습 가치 판단 후 (a) 1건 채택 권고.

## JSON

```json
{
  "stage": "discuss",
  "persona": "critic",
  "round": 2,
  "task_id": null,

  "decision": "pass",
  "reason_summary": "Round 1 Domain Expert finding 4건 (D1 major / D2-D4 minor) 흡수가 본문 마커 + 표 + 추가 절로 모두 검증됨. 신규 critical / major 0, 회귀 0. Round 1 minor m1은 plan 위임 유지지만 D4 흡수로 plan 인풋이 보강돼 결정 근거가 더 단단해짐.",

  "checklist": {
    "source": ".claude/skills/_shared/checklists/discuss-ready.md",
    "items": [
      {
        "section": "scope",
        "item": "범위 밖 이슈 위임",
        "status": "yes",
        "evidence": "D3 흡수 — TODOS.md 신규 등재 'Feign ErrorDecoder 429/503 분기 보존' 결정 명시 (docs/topics/CLEANUP-BATCH-A.md line 369, 453)"
      },
      {
        "section": "design",
        "item": "결제 흐름 호환성 검토",
        "status": "yes",
        "evidence": "D1 흡수 — §1.2 main 실제 보호 surface 컬럼 + Round 2 흡수 노트 2건 (line 159~162). 어댑터 가드(PgInboxRepositoryImpl.java:150)와 도메인 factory 가드 분담 정정"
      },
      {
        "section": "design",
        "item": "PgInbox.create 호출 그래프 정확성",
        "status": "yes",
        "evidence": "D2 흡수 — line 162 + 표 line 166에 main 호출처 0건 (test 픽스처 전용) 명시. 사전 브리핑 다이어그램 정정"
      },
      {
        "section": "acceptance",
        "item": "관찰 가능 성공 조건",
        "status": "yes",
        "evidence": "D4 흡수 — §2 line 327 신규 행 'named volume 재사용' acceptance + plan 단계 인벤토리 (named volume 정의 + ignore-migration-patterns default 값) 명시"
      },
      {
        "section": "domain-risk",
        "item": "장애 시나리오 — Flyway missing migration 재평가",
        "status": "yes",
        "evidence": "D4 흡수 — §4.1 line 383~393 named volume 재사용 케이스 시나리오 추가 + 3-step 대응 가이드 (docker volume prune / flyway_schema_history 수동 정리 / ignore-migration-patterns 임시 적용) STACK.md 등재 결정"
      }
    ],
    "total": 17,
    "passed": 11,
    "failed": 0,
    "not_applicable": 6
  },

  "scores": {
    "clarity": 0.92,
    "completeness": 0.93,
    "risk": 0.90,
    "testability": 0.82,
    "fit": 0.93,
    "mean": 0.90
  },

  "findings": [
    {
      "severity": "minor",
      "checklist_item": "테스트 계층 결정 (verification plan) — carry-over",
      "location": "docs/topics/CLEANUP-BATCH-A.md line 247, 326, 388~390",
      "problem": "§1.3 docker profile seed 차단 검증 방식 (a Testcontainers / b 수동 스모크 / c infra-healthcheck) 최종 선택은 plan 단계로 위임 유지. D4 흡수로 plan 인풋(named volume 정의 + ignore-migration-patterns default)이 추가돼 결정 근거는 보강됨.",
      "evidence": "line 247 '본 결정은 §2 acceptance 의 verification 트랙에서 plan 단계에 검증 비용 vs 가치 재평가 후 확정', line 326 '(옵션) 채택 시', line 388~390 plan 단계 확인 (D4 흡수) 항목",
      "suggestion": "plan 단계 초입에 (1) docker-compose SPRING_PROFILES_ACTIVE=docker 활성 검증, (2) named volume 재사용 케이스 1회 시뮬레이션, (3) Testcontainers 비용 vs 학습 가치 판단 후 (a) 1건 채택 권고. discuss 차단 사유 아님 — §5 fallback (수동 스모크 line 430~433) 명시."
    }
  ],

  "previous_round_ref": "discuss-critic-1.md",
  "delta": {
    "newly_passed": [
      "D1 흡수 — §1.2 가드 surface 인벤토리 정정 (어댑터 가드 / 도메인 factory 가드 분담 명시)",
      "D2 흡수 — PgInbox.create 4 오버로드 main 호출처 0건 명시 + 사전 브리핑 다이어그램 정정",
      "D3 흡수 — TODOS.md 후속 등재 (Feign ErrorDecoder 429/503 분기 보존) 결정 명시",
      "D4 흡수 — Flyway named volume 재사용 시나리오 + plan 인벤토리 + STACK.md 운영 가이드 갱신"
    ],
    "newly_failed": [],
    "still_failing": [
      "§1.3 검증 방식 (a/b/c) 최종 선택 — plan 단계 위임 유지 (minor, 차단 사유 아님)"
    ]
  },

  "unstuck_suggestion": null
}
```
