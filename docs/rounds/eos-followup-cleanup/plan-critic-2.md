# plan-critic-2

**Topic**: EOS-FOLLOWUP-CLEANUP
**Round**: 2
**Persona**: Critic

## Reasoning
Round 1 의 critical(F1: E-3 application→infrastructure 역의존)·major(F2: E-4 읽기 경로 누락 + domain 필드 모호)·minor(F3: C-1 타입 불일치)는 R2 에서 모두 해소됐다 — 추출이 `PaymentConfirmConsumer`(infrastructure, 실재 확인)로 이동해 `PgInboxPendingService` 가 String 만 받고(현 import 블록에 OTel 없음과 정합), `findStoredTraceparent(Long): Optional<String>` 전용 조회 + `stored_traceparent` 의 `PgInboxEntity` 한정 배치(domain `PgInbox` 무변경, 실코드와 정합)로 읽기 경로·필드 모호가 닫혔고, C-1 은 `Instant` 로 확정됐다. 그러나 R2 재배치가 새 결함을 도입했다(major): E-3 산출물 목록이 traceparent 의 실제 전달 경로 중간 계층(`PgConfirmCommandService` inbound 포트 + `PgConfirmService.handleAbsent`)을 누락한다. consumer 추출값이 `insertPendingAndPublish` 에 닿으려면 `handle(command, attempt)` 와 `handleAbsent(command)` 시그니처가 반드시 바뀌어야 하나 두 파일이 산출물에 없어 "consumer→service 전달"의 완료를 객관 검증할 수 없다. critical 0·major 1 → revise.

## Checklist judgement

### traceability
- PLAN이 topic.md 결정 참조함 — **yes** (PLAN line 3 원본 링크, line 620~642 리스크→태스크 교차 테이블)
- 모든 태스크가 설계 결정에 매핑됨(orphan 없음) — **yes** (line 675 — D-TM/D-SPLIT/D-CLEAN/D-TRACE 전부 매핑)

### task quality
- 모든 태스크가 객관적 완료 기준을 가짐 — **no** (E-3 산출물(line 504~518)이 traceparent 전달 경로의 중간 계층 `PgConfirmCommandService.handle`/`PgConfirmService.handleAbsent` 시그니처 변경을 누락 — consumer 추출값이 insertPendingAndPublish 까지 닿는 경로 완료를 객관 검증 불가)
- 태스크 크기 ≤ 2시간 — **yes** (14개 모두 한 커밋 단위 분해 가능)
- 각 태스크에 소스 파일/패턴 언급됨 — **yes** (전 태스크 산출물 경로 명시, 단 E-3 일부 경유 파일 누락은 위 항목으로 별도 처리)

### TDD specification
- tdd=true 태스크 테스트 클래스+메서드 스펙 명시 — **yes** (A-1/A-2/C-2/C-3/D-2/D-3/E-2/E-4/E-5 스펙 기재)
- tdd=false 태스크 산출물 명시 — **yes** (A-3/B-1/B-2/E-1/E-3 산출물 경로 명시)
- TDD 분류 합리적 — **yes** (상태 판별 A-1/A-2 tdd=true, wiring/문서 B-1/B-2 tdd=false 합리적)

### dependency ordering
- layer 의존 순서 준수 — **yes** (R2 에서 E-3 역방향 호출 제거 — 추출은 infrastructure consumer, 포트/application 은 String 만 수신. 의존 방향 application→domain·infrastructure→port.out 정상)
- Fake/구현 선행 — **yes** (포트 C-1/D-1 → 어댑터 C-2/D-2 → 워커 C-3/D-3, E-1→E-2→E-3→E-4→E-5)
- orphan port 없음 — **yes** (deleteExpired·findStoredTraceparent·insertPending 모두 구현 태스크 존재)

### architecture fit
- ARCHITECTURE layer 규칙과 충돌 없음 — **yes** (R1 F1 해소 — PgInboxPendingService.java import 블록(line 1~15)에 OTel 의존 전무 확인, 포트 시그니처 String/Optional<String> 만 노출. E-3 중간 계층(application)도 String 불투명 토큰만 통과해 OTel 누출 없음 — 누락은 layer 가 아닌 task quality 결함)
- 모듈 간 호출이 port/InternalReceiver 통함 — **yes**
- CONVENTIONS Lombok/예외/로깅/시계 패턴 따르도록 계획됨 — **yes** (C-1 Instant·nowInstant() 선례 정합 확인)

### artifact
- `docs/EOS-FOLLOWUP-CLEANUP-PLAN.md` 존재 — **yes**

## Findings

### F1 (resolved, was critical) — E-3 application→infrastructure 역의존 해소
- checklist_item: architecture fit — ARCHITECTURE layer 규칙과 충돌 없음
- location: `docs/EOS-FOLLOWUP-CLEANUP-PLAN.md` line 418 (작업군 E 헤더), 513~518 (E-3 산출물)
- status: **resolved**
- problem(R1): E-3가 `PgInboxPendingService`(application)로 하여금 `TraceparentExtractor`(infrastructure)를 직접 호출하게 해 역의존·D-TRACE-2 위반.
- resolution: R2 가 추출 호출을 `PaymentConfirmConsumer`(infrastructure/messaging/consumer)로 이동(PLAN line 516~518). application 서비스/포트는 `String storedTraceparent` 만 수신(line 513~515). 실코드 `PgInboxPendingService.java` import 블록(line 1~15)에 OTel/Context 의존 없음(repository/log/micrometer/spring tx만) — 재배치가 현 구조와 정합. `PaymentConfirmConsumer` 실재 확인(infrastructure/messaging/consumer/PaymentConfirmConsumer.java line 30).
- evidence: PLAN line 418 "추출은 PaymentConfirmConsumer(infrastructure)에서 수행 ... OTel API import는 infrastructure 계층에만 존재". 실코드 PgInboxPendingService.java import 블록에 OTel 부재.

### F2 (resolved, was major) — E-4 읽기 경로 누락 + domain 필드 모호 해소
- checklist_item: task quality — 객관적 완료 기준 / dependency ordering
- location: `docs/EOS-FOLLOWUP-CLEANUP-PLAN.md` line 505~510 (findStoredTraceparent 신설), 511~512 (PgInboxEntity 한정), 526~535 (시그니처 확정)
- status: **resolved**
- problem(R1): E-4 가 findById 로 stored_traceparent 를 읽는 전제이나 읽기 시그니처 누락 + domain PgInbox 필드가 "필요 시" 모호.
- resolution: R2 가 `PgInboxRepository.findStoredTraceparent(Long inboxId): Optional<String>` 전용 조회를 신설(PLAN line 507, 526~535)하고, `stored_traceparent` 를 `PgInboxEntity` 에만 두며 `PgInbox` domain 무변경을 명시(line 511~512, 418, 538). 실코드 `PgInbox.java`(domain, line 27~50)에 traceparent 필드 없음·`PgInboxEntity.java`(line 32~73)가 컬럼 매핑 담당과 정합. E-4 테스트(line 558~582)가 `findStoredTraceparent` 반환을 mock 해 객관 검증 가능.
- evidence: PLAN line 507 "findStoredTraceparent(Long inboxId) 전용 조회 메서드 신설 — Optional<String> 반환", line 538 "PgInbox domain 엔티티 무변경". 실코드 PgInbox.java 필드(line 34~50)에 traceparent 부재.

### F3 (resolved, was minor) — C-1 포트 시계 타입 Instant 확정
- checklist_item: task quality — 소스 파일/패턴 언급
- location: `docs/EOS-FOLLOWUP-CLEANUP-PLAN.md` line 199~206 (C-1 시그니처·근거), 180 (architect R2 주석)
- status: **resolved**
- problem(R1): 포트 deleteExpired 시계 타입과 LocalDateTimeProvider 정합 우려.
- resolution: C-1 시그니처를 `deleteExpired(Instant now, int batchSize)` 로 Instant 통일(line 203), 근거 블록(line 206)에 LocalDateTimeProvider.nowInstant():Instant + 기존 JdbcPaymentEventDedupeStore 선례 명시. D-1(product, line 329)도 동일 Instant 로 통일 — 두 서비스 포트 일관. 실코드 LocalDateTimeProvider.nowInstant()(line 26) + JdbcPaymentEventDedupeStore nowInstant() 사용(line 20) 선례 확인.
- evidence: PLAN line 203 "int deleteExpired(Instant now, int batchSize)". 실코드 grep — LocalDateTimeProvider.java line 26 nowInstant():Instant, JdbcPaymentEventDedupeStore.java line 20 nowInstant() 주입 선례.

### F4 (major, NEW — R2 재배치로 도입) — E-3 traceparent 전달 경로 중간 계층 산출물 누락
- checklist_item: task quality — 모든 태스크가 객관적 완료 기준을 가짐
- location: `docs/EOS-FOLLOWUP-CLEANUP-PLAN.md` line 504~518 (E-3 산출물 목록), line 424 (architect 비-blocking 주석)
- status: **new**
- problem: E-3 가 추출을 consumer 로 옮기면서 "추출한 traceparent를 pgConfirmCommandService.handle(command, attempt) → insertPendingAndPublish 경로로 전달"(PLAN line 518)한다고 서술하나, 그 경로 중간의 두 계층 시그니처 변경이 산출물 목록에서 누락됐다. 실제 호출 사슬은 `PaymentConfirmConsumer.consume → PgConfirmCommandService.handle(command, attempt) → PgConfirmService.processCommand → handleAbsent(command) → pgInboxPendingService.insertPendingAndPublish(...)` 다. consumer 가 추출한 String 을 insertPendingAndPublish 까지 닿게 하려면 (a) inbound 포트 `PgConfirmCommandService.handle` 시그니처, (b) `PgConfirmService.handleAbsent` 가 반드시 traceparent 파라미터를 통과시켜야 하나, E-3 산출물에 `PgConfirmCommandService.java` / `PgConfirmService.java` 두 파일이 없다. 그 결과 "consumer 추출 → 저장" 완료를 어느 시그니처로 달성하는지 객관 검증 불가. 추가로 traceparent 기록 분기가 absent 경로(handleAbsent)에만 유효하고 PENDING/IN_PROGRESS 재진입(handleActiveInbox)은 INSERT 없음 — 이 분기 한정도 E-3에 미명시.
- evidence: 실코드 PgConfirmService.java handleAbsent(line 100~113) 가 insertPendingAndPublish 의 유일 호출처이며 인자가 (orderId, amountLong, eventUuid, vendorType, paymentKey) 5개뿐 — traceparent 통과 슬롯 없음. PgConfirmCommandService.java(presentation/port, line 19) handle 시그니처는 (PgConfirmCommand, int attempt) 로 traceparent 미수용. PLAN line 518 은 전달 경로만 서술하고 산출물(line 504~517)에 두 파일 부재. architect 주석(line 424)이 "중간 전달 시그니처도 String 으로 일관 유지 ... 구조 결정 아님"이라 인정했으나, 시그니처가 바뀌는 파일을 산출물에서 빼면 implementer 가 "어디까지 손대야 완료인가"를 PLAN 으로 판정할 수 없다(task quality 결함). layer 위반은 아님 — 중간 계층이 application 이어도 String 불투명 토큰만 통과해 OTel 누출 없음.
- suggestion: E-3 산출물에 (1) `pg-service/.../presentation/port/PgConfirmCommandService.java` — handle 시그니처에 String storedTraceparent 추가(또는 attempt 처럼 별도 경유 수단), (2) `pg-service/.../application/service/PgConfirmService.java` — handleAbsent 가 traceparent 를 insertPendingAndPublish 로 전달, 두 파일을 명시하고, traceparent 기록이 absent 경로 한정임을 수락 조건에 박는다. 모두 String 통과뿐이라 layer 무영향 — 산출물·완료기준 보강만으로 충분(대규모 재설계 불요).

## JSON
```json
{
  "stage": "plan",
  "persona": "critic",
  "round": 2,
  "task_id": null,

  "decision": "revise",
  "reason_summary": "R1 critical(F1 역의존)·major(F2 읽기경로/domain필드)·minor(F3 Instant)는 R2에서 모두 resolved. 그러나 R2 재배치가 새 major(F4)를 도입 — E-3 산출물이 traceparent 전달 경로 중간 계층(PgConfirmCommandService inbound 포트 + PgConfirmService.handleAbsent) 시그니처 변경을 누락해 consumer→service 전달 완료를 객관 검증 불가. critical 0 · major 1 → revise.",

  "checklist": {
    "source": "_shared/checklists/plan-ready.md",
    "items": [
      {"section": "traceability", "item": "PLAN.md가 topic.md 결정 사항을 참조함", "status": "yes", "evidence": "PLAN line 3 원본 링크 + line 620~642 교차 테이블"},
      {"section": "traceability", "item": "모든 태스크가 설계 결정에 매핑됨(orphan 없음)", "status": "yes", "evidence": "PLAN line 675 — D-TM/D-SPLIT/D-CLEAN/D-TRACE 전부 매핑"},
      {"section": "task quality", "item": "모든 태스크가 객관적 완료 기준을 가짐", "status": "no", "evidence": "E-3 산출물(line 504~518)이 전달 경로 중간 계층 PgConfirmCommandService.handle/PgConfirmService.handleAbsent 시그니처 변경 누락 — consumer 추출값이 insertPendingAndPublish까지 닿는 완료 검증 불가"},
      {"section": "task quality", "item": "태스크 크기 ≤ 2시간", "status": "yes", "evidence": "14개 태스크 모두 한 커밋 단위 분해 가능"},
      {"section": "task quality", "item": "각 태스크에 소스 파일/패턴 언급됨", "status": "yes", "evidence": "전 태스크 산출물 경로 명시 (E-3 경유 파일 누락은 객관 완료기준 항목으로 별도 처리)"},
      {"section": "TDD specification", "item": "tdd=true 태스크 테스트 클래스+메서드 스펙 명시", "status": "yes", "evidence": "A-1/A-2/C-2/C-3/D-2/D-3/E-2/E-4/E-5 스펙 기재"},
      {"section": "TDD specification", "item": "tdd=false 태스크 산출물 명시", "status": "yes", "evidence": "A-3/B-1/B-2/E-1/E-3 산출물 경로 명시"},
      {"section": "TDD specification", "item": "TDD 분류 합리적", "status": "yes", "evidence": "상태 판별 A-1/A-2 tdd=true, wiring/문서 B-1/B-2 tdd=false 합리적"},
      {"section": "dependency ordering", "item": "layer 의존 순서 준수", "status": "yes", "evidence": "R2가 E-3 역방향 호출 제거 — 추출 infrastructure consumer, 포트/application은 String만 수신. PgInboxPendingService.java import 블록(line 1~15)에 OTel 부재 확인"},
      {"section": "dependency ordering", "item": "Fake/구현 선행", "status": "yes", "evidence": "포트→어댑터→워커 순서(C-1→C-2→C-3, D-1→D-2→D-3, E-1→E-5)"},
      {"section": "dependency ordering", "item": "orphan port 없음", "status": "yes", "evidence": "deleteExpired/findStoredTraceparent/insertPending 모두 구현 태스크 존재"},
      {"section": "architecture fit", "item": "ARCHITECTURE layer 규칙과 충돌 없음", "status": "yes", "evidence": "R1 F1 해소 — PgInboxPendingService.java import에 OTel 없음, 포트 String/Optional<String>만 노출. E-3 중간 계층도 String 토큰만 통과 — 누락은 layer 아닌 task quality"},
      {"section": "architecture fit", "item": "모듈 간 호출이 port/InternalReceiver 통함", "status": "yes", "evidence": "consumer→inbound port→application service→port.out 경로 정상"},
      {"section": "architecture fit", "item": "CONVENTIONS 패턴을 따르도록 계획됨", "status": "yes", "evidence": "C-1 Instant·nowInstant() 선례 정합(LocalDateTimeProvider.java line 26, JdbcPaymentEventDedupeStore.java line 20)"},
      {"section": "artifact", "item": "docs/<TOPIC>-PLAN.md 존재", "status": "yes", "evidence": "docs/EOS-FOLLOWUP-CLEANUP-PLAN.md 존재"}
    ],
    "total": 15,
    "passed": 14,
    "failed": 1,
    "not_applicable": 0
  },

  "scores": {
    "traceability": 0.93,
    "decomposition": 0.86,
    "ordering": 0.92,
    "specificity": 0.78,
    "risk_coverage": 0.90,
    "mean": 0.878
  },

  "findings": [
    {
      "severity": "major",
      "checklist_item": "task quality — 모든 태스크가 객관적 완료 기준을 가짐",
      "location": "docs/EOS-FOLLOWUP-CLEANUP-PLAN.md line 504~518 (E-3 산출물), line 424 (architect 비-blocking 주석)",
      "problem": "R2 재배치로 추출이 PaymentConfirmConsumer로 이동하면서 'traceparent를 pgConfirmCommandService.handle → insertPendingAndPublish 경로로 전달'(line 518)한다고 서술했으나, 실제 호출 사슬 PaymentConfirmConsumer.consume → PgConfirmCommandService.handle(command, attempt) → PgConfirmService.processCommand → handleAbsent(command) → insertPendingAndPublish 의 중간 두 계층(inbound 포트 handle, application handleAbsent) 시그니처 변경이 E-3 산출물에서 누락됨. 두 파일(PgConfirmCommandService.java, PgConfirmService.java)이 산출물에 없어 consumer 추출값이 저장 지점까지 닿는 완료를 PLAN으로 객관 검증 불가. 또한 traceparent 기록이 absent(handleAbsent) 경로 한정이고 PENDING/IN_PROGRESS 재진입(handleActiveInbox)은 INSERT 없음 — 이 분기 한정도 미명시.",
      "evidence": "실코드 PgConfirmService.java handleAbsent(line 100~113)가 insertPendingAndPublish 유일 호출처이며 인자 5개(orderId/amount/eventUuid/vendorType/paymentKey)만 — traceparent 슬롯 없음. PgConfirmCommandService.java(line 19) handle 시그니처 (PgConfirmCommand, int attempt). PLAN line 518은 전달만 서술, 산출물(line 504~517)에 두 파일 부재. architect 주석(line 424)이 '중간 전달 시그니처 String 일관 ... 구조 결정 아님'으로 인정했으나 시그니처 변경 파일을 산출물에서 빼면 implementer가 완료 범위를 PLAN으로 판정 불가.",
      "suggestion": "E-3 산출물에 (1) presentation/port/PgConfirmCommandService.java handle 시그니처에 String storedTraceparent 추가, (2) application/service/PgConfirmService.java handleAbsent가 traceparent를 insertPendingAndPublish로 전달, 두 파일 명시 + traceparent 기록 absent 경로 한정을 수락조건에 박을 것. 전부 String 통과뿐이라 layer 무영향 — 산출물·완료기준 보강만으로 충분."
    }
  ],

  "previous_round_ref": "docs/rounds/eos-followup-cleanup/plan-critic-1.md",
  "delta": {
    "resolved": [
      {"id": "F1", "was": "critical", "note": "추출이 PaymentConfirmConsumer(infrastructure)로 이동, 포트/application은 String storedTraceparent만 수신 — application→infrastructure 역의존 제거. PgInboxPendingService.java import에 OTel 부재 확인."},
      {"id": "F2", "was": "major", "note": "findStoredTraceparent(Long): Optional<String> 전용 조회 신설 + stored_traceparent를 PgInboxEntity 한정 배치(PgInbox domain 무변경) — 읽기 경로·domain 필드 모호 닫힘. 실코드 PgInbox.java에 traceparent 필드 부재와 정합."},
      {"id": "F3", "was": "minor", "note": "C-1 deleteExpired(Instant now, int batchSize)로 Instant 통일 + nowInstant() 선례 명시. D-1도 동일 Instant."}
    ],
    "still_failing": [],
    "new": [
      {"id": "F4", "severity": "major", "note": "R2 재배치 부작용 — E-3 산출물이 traceparent 전달 경로 중간 계층(PgConfirmCommandService inbound 포트 + PgConfirmService.handleAbsent) 시그니처 변경을 누락. 객관적 완료 기준 불완전."}
    ]
  },

  "unstuck_suggestion": null
}
```
