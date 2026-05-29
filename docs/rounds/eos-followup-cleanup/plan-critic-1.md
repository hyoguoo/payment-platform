# plan-critic-1

**Topic**: EOS-FOLLOWUP-CLEANUP
**Round**: 1
**Persona**: Critic

## Reasoning
PLAN의 traceability·태스크 품질·TDD 명세·의존 순서는 전반적으로 탄탄하다(설계 결정 D-TM/D-SPLIT/D-CLEAN/D-TRACE 14개 모두 태스크로 매핑, orphan 없음). 그러나 E-3가 application 계층 `PgInboxPendingService`로 하여금 infrastructure `TraceparentExtractor`를 직접 호출하도록 명시한 것은 ARCHITECTURE layer 룰 및 본 토픽의 핵심 결정 D-TRACE-2("application은 불투명 문자열만 전달")와 정면 모순하는 구조적 결함이다(critical → fail). 추가로 E-4 회수 시 traceparent 읽기 경로가 산출물에서 누락되고 domain 필드 배치가 "필요 시"로 모호해 객관적 완료 기준이 성립하지 않는다(major). C-1 타입 불일치는 기존 어댑터 선례(`nowInstant()`→`Instant`)로 이미 해소 가능해 execute 중 확정 사항(minor).

## Checklist judgement

### traceability
- PLAN이 topic.md 결정 참조함 — **yes** (PLAN line 3 원본 링크, "리스크 → 태스크 교차 참조 테이블" line 589~611)
- 모든 태스크가 설계 결정에 매핑됨(orphan 없음) — **yes** (D-TM-1~4 / D-SPLIT-1~3 / D-CLEAN-1~4 / D-TRACE-1~3 전부 매핑, line 644)

### task quality
- 모든 태스크가 객관적 완료 기준을 가짐 — **no** (E-4는 `findById`로 `stored_traceparent`를 읽는다는 전제인데, 그 읽기 경로 시그니처가 E-3/E-4 산출물에 없어 "회수 시 부모 복원"의 완료를 객관 검증 불가)
- 태스크 크기 ≤ 2시간 — **yes** (전 태스크 한 커밋 단위 분해 가능)
- 각 태스크에 소스 파일/패턴 언급됨 — **yes**

### TDD specification
- tdd=true 태스크에 테스트 클래스+메서드 스펙 명시 — **yes** (A-1/A-2/C-2/C-3/D-2/D-3/E-2/E-4/E-5 모두 스펙 기재)
- tdd=false 태스크에 산출물 명시 — **yes** (A-3/B-1/B-2/E-1/E-3 산출물 경로 명시)
- TDD 분류 합리적 — **yes** (도메인 판별·상태 동조는 A-1/A-2 tdd=true)

### dependency ordering
- layer 의존 순서 준수 — **partial/no** (E-1 migration 선행은 본 작업군 한정 정당. 그러나 E-3가 application→infrastructure 역방향 호출을 명시 → 의존 방향 위반)
- Fake/구현 선행 — **yes** (포트 C-1/D-1 → 어댑터 C-2/D-2 → 워커 C-3/D-3)
- orphan port 없음 — **yes** (모든 포트 메서드에 구현 태스크 존재)

### architecture fit
- ARCHITECTURE layer 규칙과 충돌 없음 — **no** (E-3: application 서비스가 OTel 의존 infrastructure 헬퍼를 직접 import → application은 domain만 의존하는 룰 위반, D-TRACE-2 위반)
- 모듈 간 호출이 port/InternalReceiver 통함 — **partial** (E-3 위반 외 정상)
- CONVENTIONS Lombok/예외/로깅 따르도록 계획됨 — **yes**

### artifact
- `docs/EOS-FOLLOWUP-CLEANUP-PLAN.md` 존재 — **yes**

## Findings

### F1 (critical) — E-3 application→infrastructure 역의존, D-TRACE-2 위반
- checklist_item: architecture fit — ARCHITECTURE layer 규칙과 충돌 없음
- location: `docs/EOS-FOLLOWUP-CLEANUP-PLAN.md` line 493, 501 (E-3 목적·산출물)
- problem: E-3은 `PgInboxPendingService.insertPendingAndPublish`(application/service)가 `TraceparentExtractor.extractFromCurrentContext()`(infrastructure/trace)를 직접 호출·import하도록 명시한다. application이 OTel 의존 infrastructure에 결합되어 hexagonal layer 룰을 위반한다.
- evidence: 현재 `pg-service/.../application/service/PgInboxPendingService.java` import 블록(line 3~15)에 OTel/Context 의존이 전혀 없음(repository·log·micrometer·spring tx만). topic §D-TRACE-2(`EOS-FOLLOWUP-CLEANUP.md` line 280~281): "추출은 infrastructure(어댑터 또는 consumer 인접 헬퍼)에서 수행, application 서비스는 문자열을 전달만 한다(application은 OTel API에 직접 의존하지 않음)". E-3 서술은 이 결정과 정면 모순.
- suggestion: 추출 호출 계층을 (a) consumer/InboxReadyEventHandler(infrastructure) 또는 (b) port.out 추상(예: TraceTokenProvider, 구현 infrastructure) 중 하나로 PLAN이 확정하고, application 서비스 시그니처는 `String storedTraceparent`만 받도록 E-3을 재작성한다. 이는 "어느 계층에서 추출하는가"라는 구조 결정이라 execute 중 implementer 재량으로 넘길 수 없음 → plan 재작성 필요.

### F2 (major) — E-4 traceparent 읽기 경로 누락 + domain 필드 배치 "필요 시" 모호
- checklist_item: task quality — 객관적 완료 기준
- location: `docs/EOS-FOLLOWUP-CLEANUP-PLAN.md` line 497~502 (E-3 산출물), line 528~536 (E-4 테스트 스펙)
- problem: E-4 테스트는 `inboxRepository.findById(1L) → stored_traceparent="..."인 PgInbox`를 전제하므로 도메인 `PgInbox`에 traceparent 필드 + 읽기 경로가 필요하다. 그러나 (1) E-3 산출물의 `PgInbox.java` 필드 추가가 "필요 시"(line 502)로 implementer 판단에 위임됨, (2) 읽기 시그니처(findById 확장/전용 조회) 변경이 E-3/E-4 산출물 어디에도 없음. 쓰기(insertPending 파라미터)만 명시됨.
- evidence: 현재 `PgInboxRepository.findById`는 `Optional<PgInbox>`(도메인 객체)를 반환하고, `PgInbox.java`(line 34~50) 필드에 traceparent 없음. topic §5(`EOS-FOLLOWUP-CLEANUP.md` line 317)는 stored_traceparent를 "관측성 전용, 비즈니스 판정 불참여"로 못박아 도메인 엔티티 삽입과 긴장. 배치 미확정 시 E-4 완료를 객관 검증 불가.
- suggestion: PLAN이 읽기 경로 계층(infrastructure entity/조회 DTO 우선, domain 회피)과 시그니처를 E-3 또는 E-4 산출물에 명시한다. domain에 불가피하게 둘 경우 상태 전이·멱등·금액 검증 불참여(읽기 전용 메타)를 수락 조건에 박는다.

### F3 (minor) — C-1 포트 Instant vs 스케줄러 LocalDateTimeProvider 타입 불일치 우려는 기존 선례로 해소 가능
- checklist_item: task quality — 소스 파일/패턴 언급
- location: `docs/EOS-FOLLOWUP-CLEANUP-PLAN.md` line 201~204 (C-1 시그니처·architect 주석)
- problem: 포트 `deleteExpired(Instant now, ...)`와 스케줄러 시계 `LocalDateTimeProvider` 타입 정합 확인 요청.
- evidence: `LocalDateTimeProvider`(payment .../service/port/LocalDateTimeProvider.java line 18·26)는 `now(): LocalDateTime`과 `nowInstant(): Instant` 둘 다 노출. 기존 `JdbcPaymentEventDedupeStore`(line 53)가 이미 `nowInstant()`→`Timestamp.from(Instant)`로 expires_at을 기록 중 → `Instant` 선택이 동일 테이블 어댑터 선례와 정합. 타입 불일치 아님.
- suggestion: 포트는 `Instant`로 확정(기존 어댑터 선례 일치). C-3 워커는 `localDateTimeProvider.nowInstant()`를 포트에 전달하면 됨. plan 재작성 필수 아님 — execute 중 확정 메모로 충분.

## JSON
```json
{
  "stage": "plan",
  "persona": "critic",
  "round": 1,
  "task_id": null,

  "decision": "fail",
  "reason_summary": "E-3가 application 서비스로 하여금 infrastructure OTel 헬퍼를 직접 호출하게 해 layer 룰·D-TRACE-2를 위반(critical). E-4 traceparent 읽기 경로 누락 + domain 필드 '필요 시' 모호(major). C-1 타입 우려는 기존 어댑터 선례로 해소 가능(minor).",

  "checklist": {
    "source": "_shared/checklists/plan-ready.md",
    "items": [
      {"section": "traceability", "item": "PLAN.md가 topic.md 결정 사항을 참조함", "status": "yes", "evidence": "EOS-FOLLOWUP-CLEANUP-PLAN.md line 3, 589~611 교차 참조 테이블"},
      {"section": "traceability", "item": "모든 태스크가 설계 결정에 매핑됨(orphan 없음)", "status": "yes", "evidence": "EOS-FOLLOWUP-CLEANUP-PLAN.md line 644 — D-TM/D-SPLIT/D-CLEAN/D-TRACE 전부 매핑"},
      {"section": "task quality", "item": "모든 태스크가 객관적 완료 기준을 가짐", "status": "no", "evidence": "E-4(line 528~536)가 findById로 stored_traceparent를 읽는 전제이나 읽기 경로 시그니처가 E-3/E-4 산출물에 없음"},
      {"section": "task quality", "item": "태스크 크기 ≤ 2시간", "status": "yes", "evidence": "14개 태스크 모두 한 커밋 단위 분해 가능"},
      {"section": "task quality", "item": "각 태스크에 소스 파일/패턴 언급됨", "status": "yes", "evidence": "전 태스크 산출물 경로 명시"},
      {"section": "TDD specification", "item": "tdd=true 태스크 테스트 클래스+메서드 스펙 명시", "status": "yes", "evidence": "A-1/A-2/C-2/C-3/D-2/D-3/E-2/E-4/E-5 스펙 기재"},
      {"section": "TDD specification", "item": "tdd=false 태스크 산출물 명시", "status": "yes", "evidence": "A-3/B-1/B-2/E-1/E-3 산출물 경로 명시"},
      {"section": "dependency ordering", "item": "layer 의존 순서 준수", "status": "no", "evidence": "E-3(line 493)가 application→infrastructure 역방향 호출 명시 — 의존 방향 위반"},
      {"section": "dependency ordering", "item": "orphan port 없음", "status": "yes", "evidence": "C-1/D-1 포트 메서드에 C-2/D-2 구현 존재"},
      {"section": "architecture fit", "item": "ARCHITECTURE layer 규칙과 충돌 없음", "status": "no", "evidence": "E-3: PgInboxPendingService(application/service)가 TraceparentExtractor(infrastructure/trace) 직접 호출 — 현재 import 블록(PgInboxPendingService.java line 3~15)에 OTel 의존 없음, D-TRACE-2 위반"},
      {"section": "architecture fit", "item": "CONVENTIONS 패턴을 따르도록 계획됨", "status": "yes", "evidence": "Lombok/예외/로깅/시계 주입 컨벤션 준수 명시"},
      {"section": "artifact", "item": "docs/<TOPIC>-PLAN.md 존재", "status": "yes", "evidence": "docs/EOS-FOLLOWUP-CLEANUP-PLAN.md 존재"}
    ],
    "total": 11,
    "passed": 8,
    "failed": 3,
    "not_applicable": 0
  },

  "scores": {
    "traceability": 0.92,
    "decomposition": 0.85,
    "ordering": 0.70,
    "specificity": 0.72,
    "risk_coverage": 0.88,
    "mean": 0.814
  },

  "findings": [
    {
      "severity": "critical",
      "checklist_item": "architecture fit — ARCHITECTURE layer 규칙과 충돌 없음",
      "location": "docs/EOS-FOLLOWUP-CLEANUP-PLAN.md line 493, 501 (E-3)",
      "problem": "E-3가 application 계층 PgInboxPendingService로 하여금 infrastructure TraceparentExtractor.extractFromCurrentContext()를 직접 호출·import하게 해 application→infrastructure 역방향 의존이 발생, hexagonal layer 룰과 topic §D-TRACE-2('application은 불투명 문자열만 전달')를 위반한다.",
      "evidence": "현재 pg-service/.../application/service/PgInboxPendingService.java import 블록(line 3~15)에 OTel/Context 의존 전무. topic EOS-FOLLOWUP-CLEANUP.md line 280~281: 추출은 infrastructure에서 수행, application은 문자열 전달만.",
      "suggestion": "추출 호출 계층을 (a) consumer/listener(infrastructure)에서 추출해 인자 주입 또는 (b) port.out 추상(TraceTokenProvider, 구현 infrastructure) 중 하나로 PLAN이 확정하고 application 서비스 시그니처는 String만 받도록 E-3을 재작성. 계층 결정이라 execute 위임 불가 — plan 재작성 필요."
    },
    {
      "severity": "major",
      "checklist_item": "task quality — 모든 태스크가 객관적 완료 기준을 가짐",
      "location": "docs/EOS-FOLLOWUP-CLEANUP-PLAN.md line 497~502 (E-3 산출물), line 528~536 (E-4 테스트 스펙)",
      "problem": "E-4가 findById로 stored_traceparent를 읽는 전제이나, (1) domain PgInbox 필드 추가가 '필요 시'로 implementer에 위임되고 (2) 읽기 경로 시그니처가 E-3/E-4 산출물에 없어 회수 부모 복원 완료를 객관 검증할 수 없다. 또한 topic §5는 stored_traceparent를 관측성 전용·도메인 불참여로 못박아 domain 엔티티 삽입과 긴장.",
      "evidence": "현재 PgInboxRepository.findById는 Optional<PgInbox> 반환, PgInbox.java(line 34~50)에 traceparent 필드 없음. topic EOS-FOLLOWUP-CLEANUP.md line 317: stored_traceparent는 비즈니스 판정 불참여.",
      "suggestion": "읽기 경로 계층(infrastructure entity/조회 DTO 우선)과 시그니처를 E-3 또는 E-4에 명시. domain에 둘 경우 상태전이·멱등·금액 검증 불참여를 수락조건에 박을 것."
    },
    {
      "severity": "minor",
      "checklist_item": "task quality — 각 태스크에 소스 파일/패턴 언급됨",
      "location": "docs/EOS-FOLLOWUP-CLEANUP-PLAN.md line 201~204 (C-1)",
      "problem": "포트 deleteExpired(Instant now,...)와 스케줄러 시계 LocalDateTimeProvider 타입 정합 우려가 architect 주석으로 제기됨.",
      "evidence": "LocalDateTimeProvider(payment .../service/port/LocalDateTimeProvider.java line 18·26)가 now():LocalDateTime + nowInstant():Instant 둘 다 노출. 기존 JdbcPaymentEventDedupeStore(line 53)가 이미 nowInstant()→Timestamp.from(Instant)로 expires_at 기록 — Instant 선택이 선례와 정합, 불일치 아님.",
      "suggestion": "포트는 Instant로 확정, C-3 워커는 nowInstant()를 전달. plan 재작성 불필요 — execute 중 확정 메모로 충분."
    }
  ],

  "previous_round_ref": null,
  "delta": null,

  "unstuck_suggestion": null
}
```
