# plan-domain-2

**Topic**: EOS-FOLLOWUP-CLEANUP
**Round**: 2
**Persona**: Domain Expert

## Reasoning

Round 1의 두 minor(E-3 application→infrastructure 역의존, E-4 stored_traceparent 읽기 경로 누락 + domain 필드 모호)가 R2에서 소스 정합하게 해소됐음을 교차검증했다. 추출이 `PaymentConfirmConsumer`(infrastructure, 실재 확인)로 이동하고 포트·application 서비스는 `String storedTraceparent`만 받으며, 읽기는 `findStoredTraceparent(Long): Optional<String>` 전용 조회로 신설, 컬럼은 `PgInboxEntity`에만 둬 `PgInbox` domain 무변경(소스상 traceparent 필드 부재 확인)이다. 이 재배치는 traceparent 관측성 격리(confirm 판정 비참여 + NULL/형식오류 best-effort 폴백)를 그대로 유지하고, R1에서 pass한 핵심 불변식(D-SPLIT-3 교차 불변식, dedupe cleanup 멱등 SoT 비파괴, TTL≥retention)은 R2에서 손대지 않았다(변경 범위는 E 작업군 layer 재배치 + C-1 포트 타입 확정뿐). 재배치로 인한 새 도메인 리스크 없음. critical/major 0 → pass 유지.

## Domain risk checklist

- **R1 minor 1(E-3 layer 역의존) 해소**: YES. PLAN line 418/420-422 + E-3 산출물(line 504-518)이 추출을 `PaymentConfirmConsumer`(infrastructure/messaging/consumer)로 이동, `PgInboxPendingService.insertPendingAndPublish`와 포트는 `String storedTraceparent`만 수신. 소스 확인: 현 `PgInboxPendingService` import 블록에 OTel 의존 없음(repository/log/micrometer/spring tx만), `PaymentConfirmConsumer`가 실재(infrastructure)하며 `pgConfirmCommandService.handle(command, attempt)`로 위임하는 진입점임. application→infrastructure 역의존 제거 확인.
- **R1 minor 2(E-4 읽기 경로 누락 + domain 필드 모호) 해소**: YES. E-3 산출물에 `findStoredTraceparent(Long inboxId): Optional<String>` 전용 조회 신설(line 507,526-535), 컬럼을 `PgInboxEntity`에만 두고 `PgInbox` domain 무변경 명시(line 418,511-512,538). 소스 확인: `PgInbox.java`에 traceparent 필드 부재(grep 0건), `PgInboxEntity`는 이미 stored_status_result/vendor_type 등 infra 전용 메타 컬럼 보유 — 동질 위치. domain 오염 차단 확인.
- **traceparent 관측성 격리 유지(confirm 판정 비참여)**: YES. `findStoredTraceparent`는 회수 경로 전용 단일 컬럼 조회(`SELECT stored_traceparent FROM pg_inbox WHERE id=:inboxId`), 상태 전이·금액·멱등 판정 어디에도 끼지 않음. NULL/형식오류 시 새 root span 폴백(E-4 테스트 2건). L-3 best-effort 정책 유지.
- **R1 pass 불변식 비파괴(D-SPLIT-3 / cleanup SoT / TTL≥retention)**: YES. R2 변경 범위는 E 작업군 layer 재배치 + C-1 포트 타입(Instant) 확정뿐. A-1/A-2/A-3 교차 불변식(isEqualTo 관계 단언), C-2/D-2 `expires_at<now` 만료 사실 기록만 삭제, 작업군 C 헤더 TTL(8d)>retention(7d) 보존정책 모두 R1과 동일 텍스트. PITFALLS §9/§20/§21 정합 유지.
- **(추가) 재배치로 인한 새 멱등성 리스크**: NONE. `insertPending`은 orderId UNIQUE 충돌 시 IGNORE+기존 id 반환(소스 확인). E-3 수락조건이 "INSERT IGNORE 멱등 보장(기존 행 보존 — traceparent 덮어쓰기 없음)"을 명시 → 재배달 시 stored_traceparent 최초 기록 고정. 늦은 재수신이 추적값을 갈아엎지 않음.

## 도메인 관점 추가 검토

1. **[검증 OK — R1 F1 해소] 추출 계층이 infrastructure(`PaymentConfirmConsumer`)로 확정, 호출 체인이 String만 통과.**
   소스로 호출 체인 확인: `PaymentConfirmConsumer.consume`(infrastructure) → `PgConfirmCommandService.handle(command, attempt)` → `PgConfirmService.handleAbsent(command)` → `PgInboxPendingService.insertPendingAndPublish(orderId, amount, eventUuid, vendorType, paymentKey)`(application). R2(PLAN line 504-518)는 consumer에서 `TraceparentExtractor.extractFromCurrentContext()`를 호출하고 그 String을 이 경로로 전달, application/포트는 String만 수신하도록 서술. `PgInboxPendingService` 현 import에 OTel 없음 확인 — 재배치 후에도 application의 OTel 결합 0. R1 minor 1 해소.

2. **[검증 OK — R1 F2 해소] 읽기 경로 신설 + domain 청정 보존이 회수 정확성의 데이터 출처를 명시.**
   E-4 회수는 `findPendingZombieIds`/`findInProgressZombieIds`로 받은 inboxId로 `processPending(inboxId)`/`processInProgressZombie(inboxId)`를 호출(소스 확인). R2가 신설한 `findStoredTraceparent(Long inboxId): Optional<String>`은 이 inboxId 단위 회수 경로와 정확히 정합 — 부모 추적 복원 데이터 출처가 명확. 컬럼을 `PgInboxEntity`에만 두므로(domain `PgInbox` traceparent 필드 부재 grep 확인) implementer가 domain에 임의 필드를 박을 여지가 닫힘. R1 minor 2 해소.

3. **[검증 OK] traceparent 격리 불변식 — 재배치 후에도 confirm 판정 경로 비참여.**
   `findStoredTraceparent`는 별도 단일 컬럼 SELECT로 회수 시 부모 span 복원에만 쓰이고, 상태 전이(transitTo*)·금액 대조·INSERT IGNORE 멱등 판정 어디에도 입력되지 않음. NULL/형식오류는 `restoreContext` best-effort 폴백 → 새 root span으로 회수 정상 완료(E-4 테스트 `_traceparent없음_새rootSpan폴백`, `_형식오류_폴백처리완료`). 관측성 손실≠비즈니스 영향 원칙 유지.

4. **[검증 OK] R2 미변경 영역 — D7 방어선 / cleanup SoT / TTL 보존정책 무흔들림.**
   A-2 교차 불변식 isEqualTo 관계 단언(line 84-109), A-3 두 호출처 동시 갱신+grep 0건(line 129-132), C-2/D-2 `expires_at<now` 만료 사실 기록만 삭제, 작업군 C 헤더 TTL(8d)>retention(7d) 보존정책(line 173) 모두 R1과 동일. D-2 `deleteExpired_existsValid미만료행_불영향`이 SoT 비파괴 단언 유지. R2 delta가 이 영역을 건드리지 않음 확인.

5. **[비-blocking 관찰, finding 아님] 중간 전달 시그니처 String 일관성은 implementer 디테일.**
   `handle` → `handleAbsent` → `insertPendingAndPublish` 중간 경로에 `String storedTraceparent` 파라미터를 끼워야 하는데, PLAN architect 인라인 주석(line 424)이 "중간 계층도 String만 통과, 포트/메서드 시그니처 전부 String이라 OTel 누출 위험 없음 — 구조 결정 아님"으로 이미 비-blocking 처리. 도메인 리스크 아님(타입이 String이라 격리 무영향). finding으로 올리지 않음.

## Findings

(critical/major/minor 0. R1의 두 minor가 R2에서 소스 정합하게 해소됐고, R1 pass 핵심 불변식 3종은 R2 변경 범위 밖이라 그대로 보존. 재배치로 인한 새 도메인 리스크 없음 — 멱등성(INSERT IGNORE 최초 기록 고정), 격리(confirm 판정 비참여 + NULL 폴백), domain 청정(PgInbox 무변경) 모두 유지 확인.)

## JSON
```json
{
  "stage": "plan",
  "persona": "domain-expert",
  "round": 2,
  "task_id": null,

  "decision": "pass",
  "reason_summary": "R1 두 minor(E-3 application→infrastructure 역의존, E-4 읽기 경로 누락+domain 필드 모호)가 R2에서 소스 정합하게 해소됨을 교차검증. 추출이 PaymentConfirmConsumer(infra)로 이동·포트/application은 String만 수신(PgInboxPendingService import에 OTel 부재 확인), 읽기는 findStoredTraceparent(Long):Optional<String> 전용 조회로 신설·컬럼은 PgInboxEntity에만(PgInbox domain traceparent 필드 grep 0건). 재배치 후에도 traceparent 격리(confirm 판정 비참여 + NULL/형식오류 best-effort 폴백)·멱등성(INSERT IGNORE 최초 기록 고정, 덮어쓰기 없음) 유지. R1 pass 핵심 불변식 3종(D-SPLIT-3 isEqualTo 교차 불변식, dedupe cleanup expires_at<now SoT 비파괴, TTL 8d>retention 7d)은 R2 변경 범위(E 재배치+C-1 Instant 확정) 밖이라 무흔들림. critical/major 0 → pass.",

  "checklist": {
    "source": "_shared/checklists/plan-ready.md",
    "items": [
      {
        "section": "domain risk",
        "item": "traceparent layer 격리(D-TRACE-2)가 application→infrastructure 역방향 의존 없이 계획됨",
        "status": "yes",
        "evidence": "R2: 추출이 PaymentConfirmConsumer(infrastructure/messaging/consumer, 실재 확인)로 이동(PLAN line 418,504-518). 포트 insertPending + PgInboxPendingService.insertPendingAndPublish는 String storedTraceparent만 수신. 소스: PgInboxPendingService import 블록에 OTel 의존 없음(repository/log/micrometer/spring tx만). R1 minor 1 해소."
      },
      {
        "section": "task quality",
        "item": "stored_traceparent 읽기 경로가 회수 태스크 산출물에 명시됨",
        "status": "yes",
        "evidence": "R2: findStoredTraceparent(Long inboxId): Optional<String> 전용 조회 신설(PLAN line 507,526-535). PgInboxPollingWorker가 findPendingZombieIds로 받은 inboxId로 processPending(inboxId) 호출하는 회수 경로(소스 확인)와 정합. 컬럼은 PgInboxEntity에만, PgInbox domain 무변경(traceparent 필드 grep 0건). R1 minor 2 해소."
      },
      {
        "section": "domain risk",
        "item": "traceparent 관측성 격리(confirm 판정 비참여 + NULL 폴백)가 재배치 후에도 유지됨",
        "status": "yes",
        "evidence": "findStoredTraceparent는 단일 컬럼 SELECT로 회수 시 부모 span 복원 전용 — 상태 전이/금액 대조/INSERT IGNORE 멱등 판정 비참여. NULL/형식오류 시 새 root span 폴백(E-4 테스트 2건). INSERT IGNORE 최초 기록 고정으로 재배달 덮어쓰기 없음(E-3 수락조건)."
      },
      {
        "section": "domain risk",
        "item": "R1 pass 핵심 불변식(D-SPLIT-3 / cleanup SoT / TTL≥retention)이 R2 수정으로 흔들리지 않음",
        "status": "yes",
        "evidence": "R2 변경 범위는 E 작업군 layer 재배치 + C-1 포트 Instant 확정뿐. A-2 isEqualTo 교차 불변식(line 84-109), A-3 두 호출처 동시 갱신+grep0건(line 129-132), C-2/D-2 expires_at<now 만료 기록만 삭제, 작업군 C 헤더 TTL(8d)>retention(7d) 보존정책(line 173) 모두 R1과 동일 텍스트. PITFALLS §9/§20/§21 정합 유지."
      },
      {
        "section": "domain risk",
        "item": "재배치로 인한 새 멱등성 리스크 없음",
        "status": "yes",
        "evidence": "insertPending은 orderId UNIQUE 충돌 시 IGNORE+기존 id 반환(소스 확인). E-3 수락조건 'INSERT IGNORE 멱등 보장(기존 행 보존 — traceparent 덮어쓰기 없음)' → 늦은 재수신이 추적값을 갈아엎지 않음. 중간 전달 시그니처 String 일관성은 architect 인라인 주석(line 424)이 비-blocking 처리 — OTel 타입 누출 없음."
      }
    ],
    "total": 5,
    "passed": 5,
    "failed": 0,
    "not_applicable": 0
  },

  "scores": {
    "traceability": 0.94,
    "decomposition": 0.89,
    "ordering": 0.88,
    "specificity": 0.90,
    "risk_coverage": 0.91,
    "mean": 0.90
  },

  "findings": [],

  "previous_round_ref": "docs/rounds/eos-followup-cleanup/plan-domain-1.md",
  "delta": "R1 minor 2건 모두 해소 확인. (1) E-3 layer 역의존: 추출을 PaymentConfirmConsumer(infrastructure)로 이동, application/포트는 String storedTraceparent만 수신 — PgInboxPendingService import에 OTel 부재 소스 확인. (2) E-3/E-4 읽기 경로: findStoredTraceparent(Long):Optional<String> 전용 조회 신설 + 컬럼을 PgInboxEntity에만(PgInbox domain traceparent 필드 grep 0건). 재배치가 traceparent 격리(confirm 판정 비참여+NULL 폴백)·멱등성(INSERT IGNORE 최초 기록 고정)을 유지하고, R1 pass 불변식 3종(D-SPLIT-3/cleanup SoT/TTL≥retention)은 R2 변경 범위 밖이라 무흔들림. 새 도메인 리스크 없음. decision pass→pass 유지, findings 2(minor)→0.",

  "unstuck_suggestion": null
}
```
