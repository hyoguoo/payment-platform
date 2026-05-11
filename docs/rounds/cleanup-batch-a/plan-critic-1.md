# plan-critic-1

**Topic**: CLEANUP-BATCH-A
**Round**: 1
**Persona**: Critic

## Reasoning

12 태스크가 §1.1~§1.4 결정과 discuss D1~D4·Round 0 ledger 항목에 모두 매핑돼 traceability·산출물·TDD 분류는 견고하다. 다만 Architect 인라인 주석 4건(CBA-2~5 commit 정책 / CBA-7 위치 / CBA-7 user-service 동등 / CBA-8 어댑터 회귀)이 **모두 "Planner 가 결정"으로 끝났는데 PLAN.md 본문 어디에도 결정 / carry-over 표기가 없다** — plan-ready 체크리스트의 "Architect 인라인 주석 4건 처리 또는 carry-over 명시" 요구를 정면 위반. 그 중 CBA-2~5 commit 정책은 단순 문서 흠집이 아니라 "CBA-2 적용 후 CBA-4 적용 전 시점에 product-service 빌드가 깨지는 중간 상태"가 권고 구현 순서 그대로 실현될 위험과 묶여 있어 major. 나머지 3건은 minor. critical 0 + major 1 → revise.

## Checklist judgement

### traceability
- PLAN.md → topic 참조: **yes** (line 3 `토픽: [docs/topics/CLEANUP-BATCH-A.md]`)
- 결정 → 태스크 매핑 완전성: **yes** (line 397~413 추적 테이블, 미매핑 0건)

### task quality
- 객관적 완료 기준: **yes** (모든 태스크 acceptance 명시 — `grep`·`./gradlew`·테스트 메서드명)
- 태스크 크기 ≤ 2h: **yes** (CBA-9가 5 파일 + test + 도메인 변경으로 가장 크지만 atomic 1 커밋 가능)
- 소스 파일/패턴 언급: **yes** (각 태스크 산출물 섹션에 절대 경로 명시)

### TDD specification
- tdd=true 4 태스크(CBA-6, CBA-7, CBA-8, CBA-9) 모두 테스트 클래스·메서드 스펙 명시: **yes**
- tdd=false 8 태스크는 산출물 / 파일 경로 명시: **yes**
- TDD 분류 합리성: **yes** (도메인 builder 전환·503 매핑·Testcontainers 검증은 tdd=true 적절)

### dependency ordering
- layer 순서 (port → domain → application → infrastructure → controller): **yes** (도메인 CBA-8/9 → application 호출처 동일 태스크 내 처리 / Flyway는 infrastructure 단독)
- Fake가 소비자보다 먼저: **n/a** (본 토픽은 신규 Fake 도입 없음)
- orphan port 없음: **yes**

### architecture fit
- ARCHITECTURE layer 규칙 충돌: **partial — minor** (CBA-7 테스트 산출물 위치 `product/FlywayDockerProfileTest.java`가 기존 test 트리 `application/ infrastructure/ mock/` 레이어 분류와 어긋남. 위반은 아니지만 일관성 흠집)
- 모듈 간 호출이 port / InternalReceiver 통과: **n/a** (호출 그래프 신규 없음)
- CONVENTIONS Lombok / 예외 / 로깅 준수: **yes** (CBA-6 핸들러가 `LogFmt.warn(log, LogDomain.PAYMENT, EventType.EXCEPTION, e::getMessage)` 패턴 명시 / CBA-8/9 도메인이 `@Builder + @AllArgsConstructor(PRIVATE)` 패턴 채택)

### artifact
- `docs/CLEANUP-BATCH-A-PLAN.md` 존재: **yes**

### Architect 인라인 주석 4건 처리
- CBA-2~5 commit 정책: **no — major** (PLAN.md 어디에도 commit 묶음 정책 결정 없음)
- CBA-7 위치 권고: **no — minor** (산출물 경로가 `product/FlywayDockerProfileTest.java` 그대로, infrastructure 하위 이동 결정도 carry-over도 없음)
- CBA-7 user-service 동등 1건: **no — minor** (PLAN.md에 user-service 동등 검증 추가 결정도 명시 거부도 없음)
- CBA-8 어댑터 회귀 가시화: **no — minor** (CBA-8 acceptance에 `PgInboxRepositoryImplTest` 또는 동등 통합 테스트 명시 안 됨, `./gradlew :pg-service:test` PASS만으로 묵시 cover)

## Findings

### F1 — Architect 인라인 주석 4건 모두 Planner 결정 / carry-over 미명시 (major)

- **checklist_item**: Architect 인라인 주석 4건 (CBA-7 위치 / CBA-7 검증 범위 / CBA-8 어댑터 가시화 / CBA-2~5 commit 정책) 처리 또는 carry-over 명시
- **location**: `docs/CLEANUP-BATCH-A-PLAN.md` line 71 / line 239 / line 241 / line 280
- **problem**: 네 곳 모두 "Planner 가 결정" 으로 종결됐는데 PLAN.md 본문(태스크 설명·acceptance·메타 섹션)에 Planner 결정 / carry-over 표기가 없다. plan-ready 체크리스트가 명시적으로 요구한 처리 단계.
- **evidence**: 
  - line 71 (CBA-2 위 architect comment): "implementer 가 commit 을 어떻게 묶을지 (sub-section 단위 vs 태스크 단위) 명시 권고. Planner 가 결정." → 메타 / 권고 구현 순서 섹션 어디에도 commit 정책 결정 없음
  - line 239 (CBA-7 위치): "권고 위치: `product-service/src/test/java/.../product/infrastructure/FlywayDockerProfileTest.java`. … Planner 가 결정." → CBA-7 산출물(line 237)은 `product/FlywayDockerProfileTest.java` 그대로
  - line 241 (CBA-7 검증 범위): "user-service 동등 1건을 추가하는 방안 검토 여지. Planner 가 결정." → PLAN.md 어디에도 user-service 동등 1건 추가 결정도, 명시적 거부도 없음
  - line 280 (CBA-8 어댑터 회귀): "acceptance 에 어댑터 회귀 테스트 명시 권고. Planner 가 결정." → CBA-8 acceptance (line 278)는 `./gradlew :pg-service:test` PASS만, 어댑터 통합 테스트 명시 없음
- **suggestion**: 다음 라운드에서 PLAN.md 본문 또는 메타 섹션에 4건 각각의 결정을 명시한다 — 채택 / 거부 / carry-over (별 토픽으로 미루기) 어느 쪽이든. 특히 (a) CBA-2~5 commit 정책 결정(sub-section 단위 vs 태스크 단위)과 (b) CBA-7 위치(infrastructure 하위 이동 채택 여부) 두 건은 implementer dispatch 직전 결정이 필요해 carry-over 불가, 본 라운드 안 결정 권고.

### F2 — 권고 구현 순서가 product-service 빌드 깨지는 중간 상태를 그대로 노출 (major)

- **checklist_item**: layer 의존 순서 준수 + 객관적 완료 기준 (각 태스크 단위 acceptance 통과 가능성)
- **location**: `docs/CLEANUP-BATCH-A-PLAN.md` 메타 line 46 (권고 구현 순서) + CBA-2 / CBA-4 의존 그래프
- **problem**: 권고 순서가 `… CBA-2 → CBA-3 → CBA-4 → CBA-5 …`인데, CBA-2 (product SQL `db/migration` → `db/schema` + `db/seed` 이동) 적용 후 CBA-4 (yml `spring.flyway.locations` 변경) 적용 전 중간 상태에서 product-service 의 Flyway 가 빈 `db/migration` 을 가리키며 부팅 / 테스트 실패. 즉 각 태스크 acceptance 의 `./gradlew :product-service:test PASS` 가 CBA-4 적용 전엔 깨진다. Architect 가 line 71 에서 직접 지적했고, commit 정책 결정이 안 됐기 때문에 implementer 가 CBA-2 와 CBA-4 를 별 커밋으로 끊으면 중간 회귀가 발생.
- **evidence**:
  - line 46: 권고 구현 순서 `CBA-1 → CBA-2 → CBA-3 → CBA-4 → CBA-5 → …`
  - line 119 (CBA-4 acceptance): `./gradlew :product-service:test PASS` — 그러나 CBA-2 가 먼저 끊긴 커밋으로 들어가면 CBA-2 단독 커밋의 acceptance `ls product-service/src/main/resources/db/schema/ db/seed/` 는 통과해도 그 시점의 product-service test 는 부팅 실패
  - line 71 architect 코멘트: "현 분해라면 CBA-2 적용 후 CBA-4 적용 전 시점에 product-service 빌드가 깨지는 중간 상태 존재"
- **suggestion**: F1 의 commit 정책 결정과 묶어 처리. 둘 중 하나:
  - (a) CBA-2 + CBA-4 (product) 를 **단일 커밋**으로 묶는 룰 명시 (CBA-3 + CBA-5 user 동일) — 메타 또는 권고 구현 순서 섹션에 추가
  - (b) CBA-2 의 acceptance 를 `ls … 배치 확인` 만으로 두고 `:product-service:test PASS` 는 CBA-4 acceptance 에 일임한다는 점을 양 태스크에 명시 — 단 이 경우에도 두 태스크 사이에 git push / CI run 끼면 안 된다는 dispatch 룰 명시 필요

### F3 — CBA-7 테스트 산출물 위치가 기존 product-service 테스트 트리 layer 분류와 어긋남 (minor)

- **checklist_item**: architecture fit — 기존 layer 트리 일관성
- **location**: `docs/CLEANUP-BATCH-A-PLAN.md` line 237 (CBA-7 산출물)
- **problem**: `product-service/src/test/java/com/hyoguoo/paymentplatform/product/FlywayDockerProfileTest.java` 가 product 패키지 루트에 박힘. 기존 product-service test 트리는 `application/ domain/ infrastructure/ mock/` 4 layer 분류. Testcontainers + DataSource 직접 사용 → infrastructure 관심사가 명백.
- **evidence**:
  - `ls product-service/src/test/java/.../product/` → `application / infrastructure / mock / ProductServiceApplicationTest.java` (Critic 검증)
  - PLAN.md line 237: `product-service/src/test/java/com/hyoguoo/paymentplatform/product/FlywayDockerProfileTest.java`
  - line 239 architect 코멘트 동일 지적
- **suggestion**: CBA-7 산출물 경로를 `product-service/src/test/java/.../product/infrastructure/FlywayDockerProfileTest.java` 로 변경.

### F4 — CBA-7 검증이 product-service 1건에 한정돼 user-service yml override 회귀 무방어 (minor)

- **checklist_item**: domain risk 대응 (D4 흡수 — Flyway docker profile 회귀 보호)
- **location**: `docs/CLEANUP-BATCH-A-PLAN.md` line 188 (CBA-7 목적) + line 241 architect 코멘트
- **problem**: §1.3 의 yml override 가 product + user 양쪽 동등 적용되는데 자동 회귀 테스트는 product 1건. user 의 `application-docker.yml` 이 미래에 깨져도 자동 회귀 보호 부재. 토픽 §5 acceptance line 389 에서 "Testcontainers `@ActiveProfiles("docker")` 통합 테스트 — V2 row count = 0 검증" 을 1건만 채택한 것은 의도적 결정이지만 PLAN 단계에서 그 결정을 재확인하거나 user-service 동등 추가 여부를 결정 표기로 남기지 않음.
- **evidence**:
  - line 188 (CBA-7 목적): "(또는 `user-service` 동등 1건)" — 둘 중 어느 쪽인지 결정 안 됨
  - line 241 architect 코멘트
- **suggestion**: PLAN.md 본문에 "CBA-7 은 product-service 1건만 적용. user-service 동등 회귀 보호 부재는 STACK.md 운영 가이드 노트 + 후속 토픽 [NET-RETRY] 와 별개 항목으로 TODOS.md 등재(또는 명시 거부)" 결정을 명시. 또는 CBA-7 을 product / user 2건으로 확장.

### F5 — CBA-8 어댑터 회귀 acceptance 가 묵시적 (minor)

- **checklist_item**: domain risk 대응 (D1 흡수 — 어댑터 가드 보존)
- **location**: `docs/CLEANUP-BATCH-A-PLAN.md` line 278 (CBA-8 acceptance) + line 280 architect 코멘트
- **problem**: D1 흡수 노트가 "`createDirectTerminal` 의 main 활성 보호는 어댑터 가드(`PgInboxRepositoryImpl:150`)" 라고 명시. builder 전환 후 어댑터의 `PgInbox.of(...)` 7-arg 호출 시그니처가 그대로 유지되는지를 검증할 어댑터 통합 / 회귀 테스트가 acceptance 에 명시되지 않고 `./gradlew :pg-service:test PASS` 라는 묵시 cover 에만 의존.
- **evidence**:
  - line 278: acceptance — "기존 `PgInboxTest` 회귀 0 + 신규 케이스 PASS + `./gradlew :pg-service:test` PASS" (어댑터 테스트 클래스명 미명시)
  - line 280 architect 코멘트
- **suggestion**: CBA-8 acceptance 에 어댑터 회귀 테스트명(예: `PgInboxRepositoryImplTest` 또는 동등 통합 테스트) 을 명시 — 묵시 cover 라도 명시화하면 implementer 가 회귀 발생 시 추적 가능.

## JSON

```json
{
  "stage": "plan",
  "persona": "critic",
  "round": 1,
  "task_id": null,

  "decision": "revise",
  "reason_summary": "12 태스크 traceability·산출물·TDD 분류는 견고하나 Architect 인라인 주석 4건 모두 Planner 결정 / carry-over 미명시. 그중 CBA-2~5 commit 정책 부재가 권고 구현 순서의 product-service 중간 상태 회귀 위험과 묶여 major 2건.",

  "checklist": {
    "source": "_shared/checklists/plan-ready.md",
    "items": [
      {
        "section": "traceability",
        "item": "PLAN.md가 docs/topics/<TOPIC>.md의 결정 사항을 참조",
        "status": "yes",
        "evidence": "docs/CLEANUP-BATCH-A-PLAN.md line 3"
      },
      {
        "section": "traceability",
        "item": "모든 태스크가 설계 결정에 매핑 (orphan 0)",
        "status": "yes",
        "evidence": "docs/CLEANUP-BATCH-A-PLAN.md line 397~413 (미매핑 0건)"
      },
      {
        "section": "task quality",
        "item": "모든 태스크가 객관적 완료 기준 보유",
        "status": "yes",
        "evidence": "각 태스크 acceptance — grep/./gradlew/테스트 메서드명"
      },
      {
        "section": "task quality",
        "item": "태스크 크기 ≤ 2시간",
        "status": "yes",
        "evidence": "CBA-9가 5 파일 + test + 도메인 변경으로 가장 크지만 1 커밋 atomic"
      },
      {
        "section": "TDD specification",
        "item": "tdd=true 태스크 테스트 스펙 명시",
        "status": "yes",
        "evidence": "CBA-6/7/8/9 모두 테스트 클래스 + 메서드 표 명시"
      },
      {
        "section": "dependency ordering",
        "item": "layer 순서 준수",
        "status": "yes",
        "evidence": "도메인 CBA-8/9 → application 호출처 동일 태스크 내 / Flyway는 infrastructure 단독"
      },
      {
        "section": "architecture fit",
        "item": "기존 test 트리 layer 분류 일관성 (CBA-7)",
        "status": "no",
        "evidence": "docs/CLEANUP-BATCH-A-PLAN.md line 237 — FlywayDockerProfileTest.java가 product 패키지 루트, 기존 트리는 application/ infrastructure/ mock/"
      },
      {
        "section": "architecture fit",
        "item": "CONVENTIONS Lombok / 예외 / 로깅 준수",
        "status": "yes",
        "evidence": "CBA-6 핸들러 LogFmt.warn 패턴 + CBA-8/9 @Builder + @AllArgsConstructor(PRIVATE) 채택"
      },
      {
        "section": "artifact",
        "item": "docs/CLEANUP-BATCH-A-PLAN.md 존재",
        "status": "yes",
        "evidence": "파일 존재 (428 라인)"
      },
      {
        "section": "architect inline comments",
        "item": "CBA-2~5 commit 정책 (line 71) 처리 또는 carry-over",
        "status": "no",
        "evidence": "PLAN.md 메타 / 권고 구현 순서 / 본문에 commit 정책 결정 없음"
      },
      {
        "section": "architect inline comments",
        "item": "CBA-7 위치 (line 239) 처리 또는 carry-over",
        "status": "no",
        "evidence": "CBA-7 산출물 line 237이 product 루트 그대로, infrastructure 이동 결정 없음"
      },
      {
        "section": "architect inline comments",
        "item": "CBA-7 user-service 동등 (line 241) 처리 또는 carry-over",
        "status": "no",
        "evidence": "PLAN.md에 user-service 동등 1건 추가 결정도 명시 거부도 없음"
      },
      {
        "section": "architect inline comments",
        "item": "CBA-8 어댑터 회귀 가시화 (line 280) 처리 또는 carry-over",
        "status": "no",
        "evidence": "CBA-8 acceptance line 278에 어댑터 테스트명 명시 없음"
      }
    ],
    "total": 13,
    "passed": 8,
    "failed": 5,
    "not_applicable": 0
  },

  "scores": {
    "traceability": 0.92,
    "decomposition": 0.82,
    "ordering": 0.72,
    "specificity": 0.78,
    "risk_coverage": 0.74,
    "mean": 0.796
  },

  "findings": [
    {
      "severity": "major",
      "checklist_item": "Architect 인라인 주석 4건 처리 또는 carry-over 명시",
      "location": "docs/CLEANUP-BATCH-A-PLAN.md line 71 / 239 / 241 / 280",
      "problem": "네 곳 모두 'Planner 가 결정'으로 종결됐는데 PLAN.md 본문에 결정 / carry-over 표기 없음. plan-ready 체크리스트의 명시적 요구 위반.",
      "evidence": "line 71 commit 정책 / line 239 CBA-7 위치 / line 241 user-service 동등 / line 280 어댑터 회귀 — 4건 모두 본문에 흡수 안 됨",
      "suggestion": "PLAN.md 본문(태스크 산출물·acceptance·메타)에 4건 각각 채택/거부/carry-over 결정을 명시. 특히 commit 정책과 CBA-7 위치는 implementer dispatch 직전 결정 필요라 carry-over 불가."
    },
    {
      "severity": "major",
      "checklist_item": "객관적 완료 기준 + layer 의존 순서 (중간 상태 회귀 방지)",
      "location": "docs/CLEANUP-BATCH-A-PLAN.md 메타 line 46 + CBA-2 / CBA-4 의존",
      "problem": "권고 구현 순서가 CBA-2 → CBA-3 → CBA-4 → CBA-5 인데, CBA-2 단독 커밋 시점에 product-service Flyway 가 빈 db/migration 을 가리켜 부팅 / 테스트 실패. commit 묶음 정책 부재로 회귀 가능 상태 그대로 노출.",
      "evidence": "line 119 CBA-4 acceptance ./gradlew :product-service:test PASS — 그러나 CBA-2 가 먼저 끊긴 커밋이면 그 시점 product-service test 부팅 실패. line 71 architect 가 같은 우려 지적",
      "suggestion": "(a) CBA-2 + CBA-4 (product) / CBA-3 + CBA-5 (user) 를 단일 커밋 묶음 룰로 명시 또는 (b) CBA-2 acceptance 를 ls 만으로 두고 :product-service:test 는 CBA-4 acceptance 에 일임하는 점을 양 태스크에 명시 + 두 태스크 사이 git push / CI run 금지 dispatch 룰 추가"
    },
    {
      "severity": "minor",
      "checklist_item": "기존 test 트리 layer 분류 일관성",
      "location": "docs/CLEANUP-BATCH-A-PLAN.md line 237 (CBA-7 산출물)",
      "problem": "CBA-7 산출물이 product 패키지 루트에 박힘. 기존 트리는 application/ infrastructure/ mock/ 4 layer 분류이고 Testcontainers + DataSource 사용은 infrastructure 관심사.",
      "evidence": "ls product-service/src/test/java/.../product/ → application / infrastructure / mock / ProductServiceApplicationTest.java (Critic 검증)",
      "suggestion": "CBA-7 산출물을 product-service/src/test/java/.../product/infrastructure/FlywayDockerProfileTest.java 로 변경"
    },
    {
      "severity": "minor",
      "checklist_item": "domain risk 대응 (D4 — docker profile 회귀 보호)",
      "location": "docs/CLEANUP-BATCH-A-PLAN.md line 188 (CBA-7 목적)",
      "problem": "§1.3 yml override 가 product + user 양쪽 적용되는데 자동 회귀 테스트는 product 1건. user application-docker.yml 회귀 무방어. 토픽 §5 acceptance 의 의도적 결정이지만 PLAN 에서 재확인 / 결정 표기 없음.",
      "evidence": "line 188 — '(또는 user-service 동등 1건)' 둘 중 어느 쪽인지 결정 안 됨",
      "suggestion": "PLAN.md 에 CBA-7 product 1건 한정 + user-service 회귀 보호 부재를 STACK.md 노트 또는 후속 TODOS 등재로 결정 명시. 또는 CBA-7 을 product/user 2건으로 확장."
    },
    {
      "severity": "minor",
      "checklist_item": "domain risk 대응 (D1 — 어댑터 가드 보존 검증)",
      "location": "docs/CLEANUP-BATCH-A-PLAN.md line 278 (CBA-8 acceptance)",
      "problem": "D1 흡수 노트가 'main 활성 가드는 어댑터(PgInboxRepositoryImpl:150)' 라고 명시. builder 전환 후 어댑터 PgInbox.of(...) 7-arg 호출 시그니처 보존 검증이 acceptance 에 명시되지 않고 :pg-service:test PASS 묵시 cover 만 의존.",
      "evidence": "line 278 acceptance — 어댑터 테스트 클래스명 미명시. line 280 architect 코멘트 동일 지적",
      "suggestion": "CBA-8 acceptance 에 PgInboxRepositoryImplTest (또는 동등 통합 테스트) 회귀 PASS 항목 명시"
    }
  ],

  "previous_round_ref": null,
  "delta": null,

  "unstuck_suggestion": null
}
```
