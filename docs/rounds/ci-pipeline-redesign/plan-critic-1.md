# plan-critic-1

**Topic**: CI-PIPELINE-REDESIGN
**Round**: 1
**Persona**: Critic

## Reasoning
PLAN.md는 topic.md D1~D8 결정 전부를 추적 테이블로 매핑하고 7개 태스크 각각 객관적 완료 기준(grep/gradlew 출력)과 산출물 경로를 갖췄으며, 실측 기반 주장(exceptionFormat 4곳, product resolutionStrategy 블록, UserQueryUseCase 시그니처, V2__seed_user.sql)이 실제 코드와 일치한다. critical은 없다. major 1건: T5의 user minimum 목표값이 "T3 실측 후 확정"으로 plan 시점에 미정인데, 이는 측정 종속이라 plan 단계에서 수치 고정이 불가능한 정당한 deferral로 판단되어 minor로 강등한다. 따라서 minor만 남아 pass.

## Checklist judgement

### traceability
- PLAN.md가 topic.md 참조함 — **yes** (line 3 `> 토픽: docs/topics/CI-PIPELINE-REDESIGN.md`, P-DEFER-1~5가 D1~D8 결정의 plan 이연 항목을 명시 확정)
- 모든 태스크가 설계 결정에 매핑 (orphan 없음) — **yes** (line 207~220 추적 테이블, T1=D6, T2=D3, T3=D7, T4=D8, T5=D7, T6=D1/D2/D3/D4/D5, T7=D1/D2/D4/D5/D8. 역방향 D1~D8 전부 ≥1 태스크 매핑)

### task quality
- 객관적 완료 기준 — **yes** (T1 `grep -r 'exceptionFormat "full"' 0건`, T2 grep + integrationTest green, T3 2개 테스트 PASS + 커버리지 0% 초과, T6 `grep '-x integrationTest'` 1건 이상, 전 태스크 gradlew/grep 검증 명시)
- 태스크 크기 ≤ 2시간 (1커밋 단위) — **yes** (T1 문법 4곳, T2 plugin wiring, T3 단위 테스트 1클래스, T5 minimum 4줄 등 모두 단일 커밋 분해 가능. T4/T6/T7이 상대적으로 크나 각각 단일 산출물 파일 단위로 묶임)
- 관련 소스 파일/패턴 언급 — **yes** (전 태스크 산출물 절대 경로 명시, product 49~56행 동형 등 실측 라인 참조)

### TDD specification
- tdd=true 태스크 테스트 클래스+메서드 스펙 — **yes** (T3: `UserQueryUseCaseTest`, `@ExtendWith(MockitoExtension.class)`, 2개 메서드명+검증 내용. T4: `FlywayDockerProfileTest`, 어노테이션/컨테이너/@DynamicPropertySource/메서드 스펙 명시)
- tdd=false 태스크 산출물 명시 — **yes** (T1/T2/T5/T6/T7 전부 산출물 파일 경로 명시)
- TDD 분류 합리적 — **yes** (T3 use case 단위 로직=tdd:true, T4 회귀 가드 테스트=tdd:true, build.gradle/YAML 위생=tdd:false. CI 인프라 맥락상 YAML tdd 불가 판정 정당)

### dependency ordering
- layer(빌드 인프라) 의존 순서 준수 — **yes** (line 63 명시: build.gradle 위생 선행 → 테스트/커버리지 기반 → 게이트 상향 → 재사용 워크플로우 → ci.yml. T3→T5 선행조건, T6→T7 선행조건 명시)
- Fake/선행 구현이 소비자보다 먼저 — **yes** (T3 user 테스트가 T5 user 게이트 상향 전제, T6 `_service-ci.yml`이 T7 ci.yml 호출 전제, T4 user integrationTest가 T7 has-integration=true 전제)
- orphan port 없음 — **n/a** (코드 레이어 port 개념 없는 CI 인프라 토픽)

### architecture fit
- ARCHITECTURE.md layer 규칙 충돌 없음 — **n/a** (도메인 코드 변경 아님. T3 use case 테스트는 기존 hexagonal 구조 준수)
- 모듈 간 호출 port/InternalReceiver 경유 — **n/a** (해당 없음)
- CONVENTIONS Lombok/예외/로깅 패턴 준수 계획 — **yes** (T3가 기존 `UserQueryUseCase`/`UserNotFoundException`/`UserQueryResult`/`UserRepository` 시그니처에 맞춰 작성, 실측 확인. test-first 규칙 준수)

### artifact
- docs/CI-PIPELINE-REDESIGN-PLAN.md 존재 — **yes** (판정 대상 파일 자체)

## Findings

- **id**: F1
  - **severity**: minor
  - **checklist_item**: task quality (객관적 완료 기준)
  - **location**: PLAN.md line 158, 283 (T5 user minimum), line 121/283 (T3 전제)
  - **problem**: T5의 user-service minimum 목표값이 `0.0 → <T3 실측값 - 안전마진>`으로 plan 시점에 수치 미확정. payment(0.90)/pg(0.93)/product(0.43)는 고정값인데 user만 측정 종속.
  - **evidence**: line 158 `user-service/build.gradle — jacoco.lineCoverageMinimum 0.0 → <T3 실측값 - 안전마진> (측정 후 확정...)`
  - **suggestion**: 정당한 measurement-dependent deferral이므로 차단 사유는 아님. 다만 완료 기준에 "T3 jacocoTestReport 실측값 기록 → minimum = floor(실측 - 0.05) 같은 결정 규칙"을 명시하면 execute 단계에서 자의적 수치 선택 위험이 제거된다.

- **id**: F2
  - **severity**: minor
  - **checklist_item**: TDD specification (tdd=false 산출물 명시)
  - **location**: PLAN.md line 92, 173, 175 (액션/플러그인 버전 `<최신>`)
  - **problem**: T2 `test-retry version '<최신>'`, T6 액션 버전이 본문엔 플레이스홀더지만 topic.md §3 버전표(line 261~270)에 실측 확정값 존재. PLAN.md T2 산출물에는 구체 버전이 플레이스홀더로 남아 cross-file 참조가 필요.
  - **evidence**: line 92 `id 'org.gradle.test-retry' version '<최신>' apply false`
  - **suggestion**: 차단 아님. T2 산출물에 test-retry 구체 버전을 박아두면 topic.md 버전표를 안 열어도 자기완결적이 된다. (액션 8종은 topic.md 버전표 D5 전부 적용으로 T6에 위임돼 traceable함)

## JSON
```json
{
  "stage": "plan",
  "persona": "critic",
  "topic": "CI-PIPELINE-REDESIGN",
  "round": 1,
  "decision": "pass",
  "checklist": {
    "traceability.references_topic": "yes",
    "traceability.no_orphan_tasks": "yes",
    "task_quality.objective_done_criteria": "yes",
    "task_quality.size_le_2h": "yes",
    "task_quality.source_files_referenced": "yes",
    "tdd.true_has_test_spec": "yes",
    "tdd.false_has_artifact": "yes",
    "tdd.classification_reasonable": "yes",
    "dependency.layer_ordering": "yes",
    "dependency.fake_before_consumer": "yes",
    "dependency.no_orphan_port": "n/a",
    "architecture.layer_rules_ok": "n/a",
    "architecture.calls_via_port": "n/a",
    "architecture.conventions_followed": "yes",
    "artifact.plan_exists": "yes"
  },
  "findings": [
    {
      "id": "F1",
      "severity": "minor",
      "checklist_item": "task_quality.objective_done_criteria",
      "location": "PLAN.md:158,283",
      "problem": "T5 user minimum 목표값이 plan 시점 미확정(측정 종속). 결정 규칙이 없어 execute 단계 자의적 수치 위험.",
      "evidence": "line 158: jacoco.lineCoverageMinimum 0.0 -> <T3 실측값 - 안전마진> (측정 후 확정)",
      "suggestion": "minimum = floor(실측 - 0.05) 류의 결정 규칙을 완료 기준에 명시."
    },
    {
      "id": "F2",
      "severity": "minor",
      "checklist_item": "tdd.false_has_artifact",
      "location": "PLAN.md:92,173",
      "problem": "T2 test-retry version 플레이스홀더 '<최신>'. topic.md 버전표 참조 필요(자기완결성 약함).",
      "evidence": "line 92: id 'org.gradle.test-retry' version '<최신>' apply false",
      "suggestion": "T2 산출물에 test-retry 구체 버전 명기."
    }
  ],
  "scores": {
    "traceability": 5,
    "decomposition": 5,
    "ordering": 5,
    "specificity": 4,
    "risk_coverage": 4
  },
  "delta": null,
  "unstuck_suggestion": null
}
```
