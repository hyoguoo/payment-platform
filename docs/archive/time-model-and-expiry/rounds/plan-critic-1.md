# plan-critic-1

**Topic**: TIME-MODEL-AND-EXPIRY
**Round**: 1
**Persona**: Critic

## Reasoning

PLAN의 traceability(D1~D8 전부 ≥1 태스크 매핑, orphan 없음)와 TDD 명세는 견고하다. 그러나 `LocalDateTimeProvider` 참조처 grep 결과 두 인프라 클래스(`PaymentStatusMetricsAspect`, `DomainEventLoggingAspect`)와 `PaymentConfirmResultUseCase`의 `nowInstant()` 호출(L126/L191)이 어느 태스크에도 매핑되지 않아, T1이 포트를 삭제하면 컴파일 깨짐 + AC1(`grep` 0건) 미달이 확정된다 — 이는 task quality "관련 소스 파일 누락"의 critical 위반이다. 또한 Architect 인라인 주석 4건 중 #1(T8이 존재하지 않는 클래스를 지목)은 사실과 다르나(`PgInboxProcessor`는 실존), 같은 주석의 `PgInboxRepositoryImpl` 누락 지적과 #2 포트 javadoc 죽은 참조 미매핑은 유효하다. critical 1건 이상 → fail.

## Checklist judgement

### traceability
- PLAN이 topic.md 결정 참조: **yes** — L3 링크 + Traceability 표(L32-41)로 D1~D8 매핑.
- 모든 태스크 ≥1 결정 매핑(orphan 없음): **yes** — T1~T16 전부 D1~D8 또는 R1~R4/리스크에 매핑. 반환 요약 L390 "매핑 못한 항목 없음" 정확.

### task quality
- 객관적 완료 기준: **yes (대체로)** — T7/T8/T13/T15는 `grep` 0건, T5/T6/T12 등 테스트 단정 명시.
- 태스크 크기 ≤ 2h / 한 커밋 단위: **no** — 주의사항 3(L398) "T1~T7 한 세션 순차 처리"가 한 커밋 분해 원칙과 충돌(Architect 주석 #3/#4).
- 관련 소스 파일/패턴 언급(정확성): **no (critical)** — `LocalDateTimeProvider` 참조처 2건(`PaymentStatusMetricsAspect`, `DomainEventLoggingAspect`)이 어느 태스크에도 없음. T14가 `PaymentConfirmResultUseCase`의 `nowInstant()` 호출 제거를 누락. T8은 실존 클래스를 지목(Architect 주석 #1은 오판)하나 `PgInboxRepositoryImpl` 누락.

### TDD specification
- tdd=true 태스크 테스트 클래스+메서드 명시: **yes** — T2/T3/T5/T6/T8/T10/T11/T12/T13/T14 모두 명세.
- tdd=false 산출물 명시: **yes** — T1/T4/T7/T9/T15/T16 산출물·완료기준 명시.
- TDD 분류 합리성: **yes** — 상태 전이/멱등성 경로 tdd=true, 설정 wiring tdd=false 합리적.

### dependency ordering
- layer 순서: **no (minor)** — T2(domain)→T3(application)→T4/T5(infra) 순서가 컴파일 그린 윈도우를 깬다(Architect 주석 layer 정합). T2+T4+T5 묶음 재배열 권고.
- Fake가 소비처보다 먼저: **n/a** — 신규 Fake 없음.
- orphan port 없음: **yes** — 포트 시그니처 무변경.

### architecture fit
- ARCHITECTURE layer 규칙 충돌: **yes(없음)** — `Clock` config 배치·domain 인자 주입 D2 준수.
- 모듈 호출 port/InternalReceiver 경유: **yes** — contract 무변경.
- CONVENTIONS 패턴 준수 계획: **yes**.

### artifact
- `docs/<TOPIC>-PLAN.md` 존재: **yes**.

## Findings

- **F1 (critical)** — task quality / 관련 소스 파일 누락. `PaymentStatusMetricsAspect`(L5,23,46 `localDateTimeProvider.now()`)와 `DomainEventLoggingAspect`(L7,32,86)가 `LocalDateTimeProvider`를 주입하는데 T1~T16 어느 태스크 산출물·관련 파일에도 없다. T1이 포트/어댑터를 삭제하면 두 클래스가 컴파일 깨지고 AC1(`grep -r "LocalDateTimeProvider" 0건`) 미충족. evidence: `grep -rln LocalDateTimeProvider payment-service/src/main/java`가 18개 파일 반환, 그중 이 둘은 T3/T7 목록에 부재. suggestion: T7 관련 파일에 두 aspect 추가하거나 별도 태스크 신설.

- **F2 (major)** — task quality / T14 범위 누락. `PaymentConfirmResultUseCase`는 `parseApprovedAt`(D8) 외에 L126·L191에서 `localDateTimeProvider.nowInstant()`를 호출한다. T14 관련 파일/목적은 `parseApprovedAt`+`markPaymentAsDone`만 다루고 `nowInstant()`→`clock.instant()` 전환·포트 주입 제거를 명시하지 않는다. T3 목록에도 이 클래스가 없어 Clock 전환이 unowned. evidence: `PaymentConfirmResultUseCase.java` L68 `private final LocalDateTimeProvider localDateTimeProvider`, L126/L191 호출. suggestion: T14에 `localDateTimeProvider` 제거+`Clock` 주입 명시하거나 T3 목록에 추가.

- **F3 (major)** — traceability / 포트 javadoc 죽은 참조 미매핑(Architect 주석 #2 유효). `PaymentEventDedupeStore.java` L32-36이 `LocalDateTimeProvider#nowInstant()`를 javadoc으로 명시 참조하는데 T1이 그 클래스를 삭제하면 죽은 식별자가 된다. 정정 작업이 어느 태스크에도 없다(T12 산출물에 미포함). evidence: 포트 L32 "시계 소스는 {@code LocalDateTimeProvider#nowInstant()} 기준", L33 "localDateTimeProvider.nowInstant()". suggestion: T12 산출물 목록에 포트 javadoc 정정 추가.

- **F4 (major)** — task quality / 한 커밋 단위 분해 위반(Architect 주석 #3/#4). 주의사항 3(L398)이 "T1~T7을 한 세션에서 순서대로 dispatch"를 요구하고, T2가 domain 타입을 바꾸면 T4(`PaymentEventEntity.from/toDomain`)·T5(repo)가 즉시 컴파일 깨져 T3 앞에 그린화돼야 한다(실행 순서는 T2→T3→T4). 또 T4/T12가 같은 `application-docker.yml`을 동시 편집해 커밋 충돌 위험. evidence: PLAN L353-367 실행 순서 + L77/L98/L133 Architect 주석. suggestion: T2+T4+T5를 "도메인 시각 타입 전환" 단일 논리 단위로 묶고, yml 동시 편집 태스크의 커밋 순서를 명시.

- **F5 (minor)** — dependency ordering / layer 순서와 실행 순서 불일치. 실행 순서가 hexagonal(port/config→domain→application→infra) 대신 T2→T3(application)→T4/T5(infra)로 배치돼 컴파일 그린 윈도우가 어긋난다. F4의 재배열로 함께 해소 가능. evidence: PLAN L353-360. suggestion: T4/T5를 T3 앞으로.

- **F6 (minor)** — task quality / Architect 주석 #1 오판 정정 필요. T8이 지목한 `PgInboxProcessor`는 실존하며(`pg-service/.../application/service/PgInboxProcessor.java`, L94·134에서 `Instant.now()` 호출) T8 대상으로 적절하다. 단 `PgInboxRepositoryImpl`(infra, `Clock` 이미 주입)도 도메인 mutator 호출처이므로 T8 관련 파일에 포함 검토. evidence: `find` 결과 클래스 존재 + L94/134 `Instant.now()`. suggestion: T8 관련 파일에 `PgInboxRepositoryImpl` 명시 추가, "PgInboxProcessor 부재" 우려는 기각.

## JSON
```json
{
  "stage": "plan",
  "persona": "critic",
  "round": 1,
  "task_id": null,
  "decision": "fail",
  "reason_summary": "D1~D8 traceability·TDD 명세는 견고하나, LocalDateTimeProvider 참조처 2개 인프라 클래스 + PaymentConfirmResultUseCase의 nowInstant() 호출이 어느 태스크에도 매핑되지 않아 T1 포트 삭제 시 컴파일 깨짐 + AC1 미달이 확정된다(critical).",
  "checklist": {
    "source": "_shared/checklists/plan-ready.md",
    "items": [
      { "section": "traceability", "item": "PLAN이 topic.md 결정 참조", "status": "yes", "evidence": "PLAN.md L3 링크 + Traceability 표 L32-41" },
      { "section": "traceability", "item": "모든 태스크가 결정에 매핑(orphan 없음)", "status": "yes", "evidence": "PLAN.md L390 + 표; T1~T16 전부 D1~D8/R1~R4 매핑" },
      { "section": "task quality", "item": "객관적 완료 기준 보유", "status": "yes", "evidence": "T7/T8/T13/T15 grep 0건, T5/T6/T12 테스트 단정" },
      { "section": "task quality", "item": "태스크 크기 ≤ 2h(한 커밋 분해)", "status": "no", "evidence": "PLAN.md L398 'T1~T7 한 세션 순차 처리'가 커밋 분해 충돌" },
      { "section": "task quality", "item": "관련 소스 파일/패턴 언급(정확)", "status": "no", "evidence": "PaymentStatusMetricsAspect/DomainEventLoggingAspect 미매핑; PaymentConfirmResultUseCase nowInstant() T14 누락" },
      { "section": "TDD specification", "item": "tdd=true 테스트 클래스+메서드 명시", "status": "yes", "evidence": "T2/T3/T5/T6/T8/T10-14 명세" },
      { "section": "TDD specification", "item": "tdd=false 산출물 명시", "status": "yes", "evidence": "T1/T4/T7/T9/T15/T16 산출물 명시" },
      { "section": "TDD specification", "item": "TDD 분류 합리성", "status": "yes", "evidence": "상태전이/멱등성 tdd=true, wiring tdd=false" },
      { "section": "dependency ordering", "item": "layer 의존 순서 준수", "status": "no", "evidence": "PLAN.md L353-360 T2→T3→T4/T5 컴파일 그린 윈도우 어긋남" },
      { "section": "dependency ordering", "item": "Fake가 소비처보다 먼저", "status": "n/a", "evidence": "신규 Fake 없음" },
      { "section": "dependency ordering", "item": "orphan port 없음", "status": "yes", "evidence": "포트 시그니처 무변경" },
      { "section": "architecture fit", "item": "ARCHITECTURE layer 규칙 충돌 없음", "status": "yes", "evidence": "Clock config 배치/domain 인자 주입 D2 준수" },
      { "section": "architecture fit", "item": "모듈 호출 port/InternalReceiver 경유", "status": "yes", "evidence": "contract 무변경 L401" },
      { "section": "architecture fit", "item": "CONVENTIONS 패턴 준수 계획", "status": "yes", "evidence": "Lombok/예외/로깅 변경 없음" },
      { "section": "artifact", "item": "docs/<TOPIC>-PLAN.md 존재", "status": "yes", "evidence": "docs/TIME-MODEL-AND-EXPIRY-PLAN.md" }
    ],
    "total": 15,
    "passed": 10,
    "failed": 4,
    "not_applicable": 1
  },
  "scores": {
    "traceability": 0.78,
    "decomposition": 0.55,
    "ordering": 0.60,
    "specificity": 0.70,
    "risk_coverage": 0.85,
    "mean": 0.696
  },
  "findings": [
    {
      "severity": "critical",
      "checklist_item": "각 태스크에 관련 소스 파일/패턴이 언급됨",
      "location": "docs/TIME-MODEL-AND-EXPIRY-PLAN.md T1/T3/T7 ; payment-service/.../infrastructure/aspect/PaymentStatusMetricsAspect.java:23,46 ; .../DomainEventLoggingAspect.java:32,86",
      "problem": "LocalDateTimeProvider를 주입하는 PaymentStatusMetricsAspect, DomainEventLoggingAspect가 어느 태스크 산출물에도 없다. T1이 포트/어댑터를 삭제하면 두 클래스가 컴파일 깨지고 AC1(grep 0건) 미충족이 확정.",
      "evidence": "grep -rln LocalDateTimeProvider payment-service/src/main/java 가 18개 파일 반환; 두 aspect는 T3/T7 목록에 부재. PaymentStatusMetricsAspect L46 'localDateTimeProvider.now()', DomainEventLoggingAspect L86 동일.",
      "suggestion": "T7 관련 파일에 두 aspect를 추가하거나 별도 Clock 전환 태스크를 신설하여 AC1 grep 0건을 달성 가능하게 한다."
    },
    {
      "severity": "major",
      "checklist_item": "각 태스크에 관련 소스 파일/패턴이 언급됨",
      "location": "docs/TIME-MODEL-AND-EXPIRY-PLAN.md T14 ; payment-service/.../application/usecase/PaymentConfirmResultUseCase.java:68,126,191",
      "problem": "PaymentConfirmResultUseCase는 parseApprovedAt 외에 L126/L191에서 localDateTimeProvider.nowInstant()를 호출한다. T14는 parseApprovedAt+markPaymentAsDone만 다루고 nowInstant()→clock.instant() 전환·포트 주입 제거를 명시하지 않으며, T3 목록에도 이 클래스가 없어 Clock 전환이 unowned.",
      "evidence": "PaymentConfirmResultUseCase.java L68 'private final LocalDateTimeProvider localDateTimeProvider', L126 'localDateTimeProvider.nowInstant().plus(STOCK_COMMITTED_TTL)', L191 'localDateTimeProvider.nowInstant()'.",
      "suggestion": "T14 관련 파일/목적에 localDateTimeProvider 제거 + Clock 주입 + nowInstant() 호출 2건 전환을 명시하거나 T3 목록에 PaymentConfirmResultUseCase를 추가한다."
    },
    {
      "severity": "major",
      "checklist_item": "PLAN.md가 topic.md 결정 사항을 참조함(죽은 참조 정정 매핑)",
      "location": "payment-service/.../application/port/out/PaymentEventDedupeStore.java:32-36 ; docs/TIME-MODEL-AND-EXPIRY-PLAN.md T12",
      "problem": "포트 javadoc L32-36이 LocalDateTimeProvider#nowInstant()를 명시 참조하는데 T1이 그 클래스를 삭제하면 죽은 식별자 참조가 된다. 정정 작업이 어느 태스크에도 매핑되지 않음(Architect 주석 #2 유효).",
      "evidence": "PaymentEventDedupeStore.java L32 '시계 소스는 {@code LocalDateTimeProvider#nowInstant()} 기준', L33 '호출자(스케줄러)가 localDateTimeProvider.nowInstant() 를 전달하면 됨', L36 '@param now ... LocalDateTimeProvider.nowInstant() 기준'.",
      "suggestion": "T12 산출물 목록에 포트 javadoc을 clock.instant() 기준으로 갱신하는 작업을 추가한다."
    },
    {
      "severity": "major",
      "checklist_item": "태스크 크기 ≤ 2시간(한 커밋 단위로 분해 가능)",
      "location": "docs/TIME-MODEL-AND-EXPIRY-PLAN.md L353-367, L398, L77, L98, L133",
      "problem": "주의사항 3이 T1~T7을 한 세션 순차 처리하도록 요구하나 실행 순서 T2(domain)→T3(application)→T4/T5(infra)는 T2 직후 T4/T5가 컴파일 깨지는 윈도우를 만든다. 또 T4/T12가 같은 application-docker.yml을 동시 편집해 커밋 충돌 위험(Architect 주석 #3/#4).",
      "evidence": "PLAN.md L353-360 실행 순서; L98 Architect '실행 순서상 T3가 T4/T5 앞에 있어 컴파일 그린 윈도우가 어긋난다'; L133 'T4와 T12가 같은 yml 파일(application-docker.yml)을 동시 편집'.",
      "suggestion": "T2+T4+T5를 '도메인 시각 타입 전환' 단일 논리 단위(test RED→impl GREEN)로 묶고 yml 동시 편집 태스크 커밋 순서를 명시한다."
    },
    {
      "severity": "minor",
      "checklist_item": "layer 의존 순서 준수(port→domain→application→infrastructure)",
      "location": "docs/TIME-MODEL-AND-EXPIRY-PLAN.md L353-360",
      "problem": "실행 순서가 hexagonal layer 순서 대신 T2→T3(application)→T4/T5(infra)로 배치돼 컴파일 그린 윈도우가 어긋난다. F4 재배열로 함께 해소 가능.",
      "evidence": "PLAN.md 실행 순서 블록 L353-360.",
      "suggestion": "T4/T5(infra entity·repo)를 T3(application) 앞으로 끌어올린다."
    },
    {
      "severity": "minor",
      "checklist_item": "각 태스크에 관련 소스 파일/패턴이 언급됨(정확성)",
      "location": "docs/TIME-MODEL-AND-EXPIRY-PLAN.md T8 (L188-205) ; pg-service/.../application/service/PgInboxProcessor.java:94,134 ; .../infrastructure/repository/PgInboxRepositoryImpl.java",
      "problem": "Architect 주석 #1의 'PgInboxProcessor 부재' 주장은 오판이다 — 클래스는 실존하며 L94/134에서 Instant.now()를 호출해 T8 대상으로 적절. 단 PgInboxRepositoryImpl(infra, Clock 이미 주입)도 도메인 mutator 호출처라 T8 관련 파일 포함 검토 필요.",
      "evidence": "find 결과 PgInboxProcessor.java 실존; L94/134 'vendorCallService.applyOutcome(..., Instant.now())'; PgInboxRepositoryImpl L40 'private final Clock clock'.",
      "suggestion": "T8 관련 파일에 PgInboxRepositoryImpl을 명시 추가하고, 'PgInboxProcessor 부재' 우려는 기각 처리한다."
    }
  ],
  "previous_round_ref": null,
  "delta": null,
  "unstuck_suggestion": null
}
```
