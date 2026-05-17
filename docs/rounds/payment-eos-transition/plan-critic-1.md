# plan-critic-1

**Topic**: PAYMENT-EOS-TRANSITION
**Round**: 1
**Persona**: Critic

## Reasoning

PLAN.md 의 14개 태스크가 토픽 §4 D1~D8 결정에 모두 매핑되고 (orphan 없음), Architect 가 인라인 주석으로 지적한 2건 (PET-11 commit 순서 권장, PET-6/7 tdd=false 적정성) 모두 Gate 차원에서는 minor — major / critical 결함을 형성하지 않는다. layer 의존 순서 (port → domain → application → infrastructure → wiring), Fake 우선순위, 삭제 순서 (PET-8 → PET-9 → PET-10), Flyway 순서 (V2 → V3) 가 모두 만족. TDD 분류는 business logic / state machine / 통합 시나리오 4건 (PET-3 / PET-5 / PET-8 / PET-12) 에 tdd=true, config 변경 / 파일 삭제 / yml 단일 라인 / 문서 갱신에 tdd=false 로 합리적이다. 다만 PET-9 의 main 코드 삭제 파일 수 (목록에는 12 파일) ↔ 비고 "11파일" 표기 불일치 1건이 명세 정확성에 minor 흠집을 남기며, DR-7 (cascade 빈도 평가) 의 PET-8 매핑이 보상 순서 테스트로 대체된 것이 약한 우려.

## Checklist judgement

### traceability
- **PLAN.md 가 topic 결정 참조**: yes — line 4 "토픽: docs/topics/PAYMENT-EOS-TRANSITION.md" 명시 + 각 태스크 `결정 매핑` 필드 + line 431~444 결정→태스크 매핑 표
- **orphan 태스크 없음**: yes — D1~D8 모두 최소 1개 태스크에 매핑됨 (line 433~442 의 매핑 표). 역방향도 모든 PET-1~14 가 결정 ID 를 들고 있음

### task quality
- **객관적 완료 기준**: yes — 14개 태스크 모두 "파일 존재 / 컴파일 통과 / 테스트 메서드 GREEN / SQL 라인 존재" 등 검증 가능 명세
- **태스크 크기 ≤ 2시간**: yes — S(~30분) 6개 / M(~1시간) 5개 / L(~2시간) 3개, 한도 안
- **관련 소스 파일/패턴 명시**: yes — 모든 태스크가 `건드릴 파일/패턴` 섹션에 절대경로 명시

### TDD specification
- **tdd=true 테스트 스펙 명시**: yes — PET-3 (3 메서드), PET-5 (4 메서드), PET-8 (7 메서드), PET-12 (5 메서드) 모두 테스트 클래스명 + 메서드명 + @ParameterizedTest 패턴 명시
- **tdd=false 산출물 명시**: yes — 10개 태스크 모두 `건드릴 파일/패턴` + `완료 기준` 으로 산출물 위치 명시
- **TDD 분류 합리성**: yes — domain enum 메서드 / JDBC 어댑터 / use case 비즈니스 로직 / EOS 통합 시나리오를 tdd=true; config wiring / 파일 삭제 / yml 1줄 / 문서 갱신을 tdd=false. PET-6/7 (config) 가 PET-12 통합 테스트로 후방 검증되는 점은 Architect 인라인 주석에도 deferred 합리로 표시됨

### dependency ordering
- **layer 의존 순서**: yes — PET-1 (port) → PET-3 (domain) → PET-8 (application use case) → PET-5/6/7 (infrastructure adapter + config) 순. 역의존 없음
- **Fake 가 소비처 전**: yes — PET-2 (FakeEventDedupeStore) ← PET-1 (port), PET-8 (RED 단계) 가 PET-2 소비 — 선행 의존 명시
- **orphan port 없음**: yes — PET-1 (port) 이 PET-2 (Fake) + PET-5 (Jdbc 어댑터) 양쪽에서 즉시 흡수됨

### architecture fit
- **ARCHITECTURE.md layer 룰 정합**: yes — `application/port/out/EventDedupeStore` 위치 (ARCHITECTURE.md line 84 룰) + `infrastructure/dedupe/JdbcEventDedupeStore` (ARCHITECTURE.md line 200 product `JdbcEventDedupeStore` 와 같은 결의 `MySQL + 같은 TX` 패턴 일관)
- **모듈 간 호출이 port / InternalReceiver 통함**: yes — payment-service 내부만 변경, cross-service HTTP 추가 없음. product-service 는 yml 1줄 (PET-11) 만 독립 변경
- **CONVENTIONS.md (Lombok / 예외 / 로깅) 패턴 계획됨**: yes — PET-8 가 `LogFmt.warn` 명시 (D7 가드 분기), Mock 대상 / TDD 흐름 명시 (TESTING.md 의 Fake vs Mock 룰 준수)

### artifact
- **docs/<TOPIC>-PLAN.md 존재**: yes — `docs/PAYMENT-EOS-TRANSITION-PLAN.md` 확인됨

## Findings

### F1 (minor)
- **checklist_item**: task quality / 객관적 완료 기준
- **location**: `docs/PAYMENT-EOS-TRANSITION-PLAN.md:230~270` (PET-9)
- **problem**: PET-9 의 `건드릴 파일/패턴` 섹션 main 삭제 목록은 12 파일 (line 241~253 의 bullet 12개 — `StockOutboxReadyEvent` / `StockOutboxPublisherPort` / `StockOutboxRepository` / `StockOutboxRelayService` / `StockOutboxFactory` / `StockOutbox` / `StockOutboxEntity` / `StockOutboxImmediateEventHandler` / `StockOutboxKafkaPublisher` / `JpaStockOutboxRepository` / `StockOutboxRepositoryImpl` / `StockOutboxWorker`), 직전 헤더는 "삭제 (main, 11파일)" 으로 표기. 토픽 §6 합계는 "main 10 + test 5 + Fake 1 + DB 1 + Bean 1 = 17 단위" 인데 main 항목이 §6 표에서도 12 row (line 564~577) 라 토픽 자체 합산이 어긋난다 (PLAN 의 11파일 표기는 §6 표를 그대로 따른 결과로 보이지만 실제 row 카운트는 12).
- **evidence**: PLAN line 241 헤더 "삭제 (main, 11파일):" + line 242~253 의 12 항목 bullet. 토픽 §6 의 main 합계 "10" + 표 row 12 와도 불일치.
- **suggestion**: PET-9 헤더를 "삭제 (main, 12파일)" 로 보정하고, 토픽 §6 합계도 verify 시 함께 정정. PET-9 완료 기준 "17단위 파일 삭제 완료" 의 17 도 main 12 + test 6 (test 5 + Fake 1) + DB 1 + Bean 1 = 20 으로 재계산 필요. 카운트 어긋남은 사후 grep 검증에서 PR review 잡음을 만든다.

### F2 (minor)
- **checklist_item**: domain risk / 재시도 안전성 검증 태스크 존재
- **location**: `docs/PAYMENT-EOS-TRANSITION-PLAN.md:417~427` (도메인 리스크 추적 테이블) + PET-8 testcase 목록
- **problem**: DR-7 (EOS 도입 후 SCR L7 cascade 빈도 평가) 의 PLAN 대응이 "PET-8 의 `shouldMaintainCompensationOrderForFailed` 테스트 + PET-14 문서 갱신" 으로 환원됐다. 그러나 토픽 §10 line 786~793 의 평가 자체는 "빈도 무변 ~ marginal 증가" 라는 정성 평가이지 보상 **순서** 유지와 직결되지 않는다 — 순서 가드는 SCR D6 (이미 존재) 의 회귀 가드일 뿐. cascade 빈도 평가를 검증할 테스트가 본 PLAN 에 없고, PET-14 의 CONFIRM-FLOW 갱신 문서 등재로만 처리되는 셈.
- **evidence**: PLAN line 423 "DR-7 ... PET-8: 보상 순서 유지 확인 ... + PET-14: CONFIRM-FLOW 갱신에 평가 표 반영". PET-8 의 7번째 테스트 `shouldMaintainCompensationOrderForFailed` 는 SCR D6 회귀 가드, 빈도 평가 아님.
- **suggestion**: DR-7 의 "검증 task" 를 PET-14 단독 (문서 평가) 로 명시하고, PET-8 매핑은 SCR D6 회귀 가드 (다른 리스크 ID) 로 분리. 또는 DR-7 자체를 "정성 평가 — 테스트 불가, 문서만" severity:low 로 표시하면 추적 테이블이 정직해진다. 현재 표현은 "PET-8 테스트가 DR-7 을 검증한다" 는 잘못된 인상을 준다.

### F3 (minor — 정보)
- **checklist_item**: dependency ordering / Fake 가 소비처 전
- **location**: `docs/PAYMENT-EOS-TRANSITION-PLAN.md:448~473` (태스크 실행 순서)
- **problem**: PET-11 (product-service yml) 의 §12 deploy 순서 의도 (product 먼저 → payment 나중) 가 PLAN 의 실행 순서 다이어그램에서 "병렬 가능: PET-3 ─ PET-4 ─ PET-11" 로 자리 잡혀 commit 순서에서 PET-11 이 PET-8/12 보다 늦게 박힐 여지가 남는다. Architect 가 인라인 주석에서 동일 권장. 강제 의존 부재라 Gate 항목 fail 은 아니지만 `git log` 만 보고 deploy 순서를 역추적하기 어렵게 된다.
- **evidence**: PLAN line 458~459 "병렬 가능: PET-3 ── PET-4 ── PET-11". PLAN line 502 "약한 우려" 라고 Architect 자체 평가.
- **suggestion**: 태스크 실행 순서 섹션에 비고 1줄 추가: "PET-11 은 PET-8 commit 이전에 push 권장 — §12 deploy 순서 의도 git log 가시화". 강제 의존 변경 불필요.

### F4 (minor — Architect 인라인 우려 흡수)
- **checklist_item**: TDD specification / TDD 분류 합리성
- **location**: `docs/PAYMENT-EOS-TRANSITION-PLAN.md:140` (PET-6 인라인) + line 168 (PET-7 인라인) + line 504 (검토 요약)
- **problem**: Architect 가 인라인 ⚠️ 주석으로 PET-6 / PET-7 의 `tdd=false` 적정성에 "약한 우려" 표시. transactional.id 패턴 / transaction.timeout.ms / KafkaTransactionManager wiring / consumer isolation.level 은 EOS 효과의 전제이지만 단위 검증이 deferred 됨. PLAN 은 PET-12 통합 테스트가 시나리오 #1/#2 로 wiring 효과를 end-to-end 검증한다고 흡수했고 Critic 도 이 위임이 합리적이라 판단 — config wiring 단위 테스트는 Spring Boot 의 일반 패턴에서도 흔치 않다. 다만 PET-12 가 fail 했을 때 단위 검증 부재로 결함 위치 (config wiring vs use case vs Flyway) 분리가 어려워질 수 있다는 미세 리스크는 남는다.
- **evidence**: PLAN line 140 "PET-6 ARCH-REVIEW", line 168 "PET-7 ARCH-REVIEW", line 504 "TDD 분류 합리성 — 약한 우려".
- **suggestion**: 강제 수정 아님. PET-12 RED 단계 디버깅 시 config 측 결함이 의심되면 implementer 가 `@SpringBootTest(classes = {KafkaProducerConfig.class, KafkaConsumerConfig.class})` 단순 빈 wiring assert 를 추가하는 패턴 정도 PLAN 비고 1줄 보강 가치.

## JSON

```json
{
  "stage": "plan",
  "persona": "critic",
  "round": 1,
  "task_id": null,
  "decision": "pass",
  "reason_summary": "14개 태스크 모두 D1~D8 매핑 + orphan 없음 + layer/Fake/삭제 순서 정합 + TDD 분류 합리. minor 4건 (PET-9 파일 카운트 11↔12 불일치, DR-7 PET-8 매핑 정확성, PET-11 commit 순서 권장, PET-6/7 wiring 단위 검증 deferred) 모두 major 미만.",
  "checklist": {
    "source": "_shared/checklists/plan-ready.md",
    "items": [
      {"section": "traceability", "item": "PLAN.md가 docs/topics/<TOPIC>.md의 결정 사항을 참조함", "status": "yes", "evidence": "PLAN.md line 4 + line 431~444 매핑 표"},
      {"section": "traceability", "item": "모든 태스크가 설계 결정 중 하나 이상에 매핑됨 (orphan 태스크 없음)", "status": "yes", "evidence": "각 PET-* `결정 매핑` 필드 + line 433~442 결정→태스크 매핑 표, D1~D8 매핑 누락 없음"},
      {"section": "task quality", "item": "모든 태스크가 객관적 완료 기준을 가짐", "status": "yes", "evidence": "14개 태스크의 `완료 기준` 섹션이 파일 존재 / 컴파일 통과 / 테스트 GREEN 등 검증 가능 명세"},
      {"section": "task quality", "item": "태스크 크기 ≤ 2시간", "status": "yes", "evidence": "S(~30분) 6 + M(~1시간) 5 + L(~2시간) 3, 한도 안"},
      {"section": "task quality", "item": "각 태스크에 관련 소스 파일/패턴이 언급됨", "status": "yes", "evidence": "전 태스크 `건드릴 파일/패턴` 절대경로 명시. PET-9 파일 카운트 헤더 불일치는 F1 참조"},
      {"section": "TDD specification", "item": "tdd=true 태스크는 테스트 클래스+테스트 메서드 스펙이 명시됨", "status": "yes", "evidence": "PET-3 (3 메서드) / PET-5 (4 메서드) / PET-8 (7 메서드) / PET-12 (5 메서드) 모두 클래스명 + 메서드명 + @ParameterizedTest 패턴 명시"},
      {"section": "TDD specification", "item": "tdd=false 태스크는 산출물(파일/위치)이 명시됨", "status": "yes", "evidence": "10개 tdd=false 태스크 모두 산출물 절대경로 명시"},
      {"section": "TDD specification", "item": "TDD 분류가 합리적", "status": "yes", "evidence": "business logic / state machine / 통합 시나리오에 tdd=true, config wiring / 파일 삭제 / yml 1줄 / 문서에 tdd=false. PET-6/7 deferred 는 PET-12 통합으로 후방 검증, F4 minor"},
      {"section": "dependency ordering", "item": "layer 의존 순서 준수", "status": "yes", "evidence": "PET-1(port) → PET-3(domain) → PET-8(application) → PET-5/6/7(infrastructure) 순, 역의존 없음"},
      {"section": "dependency ordering", "item": "Fake 구현이 소비 태스크보다 먼저 옴", "status": "yes", "evidence": "PET-2(FakeEventDedupeStore) ← PET-1(port), PET-8 RED 단계 선행 의존에 PET-2 명시"},
      {"section": "dependency ordering", "item": "orphan port 없음", "status": "yes", "evidence": "PET-1 port → PET-2(Fake) + PET-5(Jdbc) 양쪽에서 흡수"},
      {"section": "architecture fit", "item": "ARCHITECTURE.md layer 규칙과 충돌 없음", "status": "yes", "evidence": "EventDedupeStore 가 application/port/out/, JdbcEventDedupeStore 가 infrastructure/dedupe/ 신규 서브패키지 — ARCHITECTURE.md line 84 + line 200 product JdbcEventDedupeStore 패턴 일관"},
      {"section": "architecture fit", "item": "모듈 간 호출이 port / InternalReceiver 통함", "status": "yes", "evidence": "payment-service 내부만 변경, cross-service HTTP 추가 없음. product-service 는 PET-11 yml 단일 라인 독립 변경"},
      {"section": "architecture fit", "item": "CONVENTIONS.md Lombok / 예외 / 로깅 패턴 계획됨", "status": "yes", "evidence": "PET-8 LogFmt.warn 명시 (D7 가드), Mock 대상 / TDD 흐름 명시"},
      {"section": "artifact", "item": "docs/<TOPIC>-PLAN.md 존재", "status": "yes", "evidence": "docs/PAYMENT-EOS-TRANSITION-PLAN.md 확인"}
    ],
    "total": 15,
    "passed": 15,
    "failed": 0,
    "not_applicable": 0
  },
  "scores": {
    "traceability": 0.95,
    "decomposition": 0.88,
    "ordering": 0.85,
    "specificity": 0.82,
    "risk_coverage": 0.85,
    "mean": 0.87
  },
  "findings": [
    {
      "severity": "minor",
      "checklist_item": "task quality / 객관적 완료 기준",
      "location": "docs/PAYMENT-EOS-TRANSITION-PLAN.md:241 (PET-9 헤더)",
      "problem": "PET-9 의 `삭제 (main, 11파일)` 헤더와 실제 bullet 12개 불일치. 토픽 §6 합계 (main 10) + 17단위 총합산도 어긋남.",
      "evidence": "line 241 헤더 '11파일' vs line 242~253 의 12 항목. 토픽 §6 line 564~577 의 main 표 row 12.",
      "suggestion": "헤더를 '12파일' 로 보정 + 17단위 총합산 재계산 (main 12 + test 6 + DB 1 + Bean 1 = 20). plan-review 단계에서 토픽 §6 도 함께 정정 권장."
    },
    {
      "severity": "minor",
      "checklist_item": "domain risk / 재시도 안전성 검증",
      "location": "docs/PAYMENT-EOS-TRANSITION-PLAN.md:423 (DR-7 매핑 줄)",
      "problem": "DR-7 (cascade 빈도 평가) 의 PET-8 매핑이 `shouldMaintainCompensationOrderForFailed` 인데, 이 테스트는 SCR D6 보상 순서 회귀 가드이지 cascade 빈도 평가가 아니다.",
      "evidence": "line 423 'DR-7 ... PET-8: 보상 순서 유지 확인' + 토픽 §10 line 786~793 의 평가는 정성 평가 표일 뿐.",
      "suggestion": "DR-7 을 'PET-14 단독 (문서 평가)' 으로 표기하고 severity:medium 의 검증 방식을 '정성 평가, 자동 테스트 불가' 로 명시. 또는 cascade 빈도 가설을 검증할 별 테스트 (broker 일시 장애 mock 후 retry 5회 소진 카운트) 신설."
    },
    {
      "severity": "minor",
      "checklist_item": "dependency ordering / 운영 의도 가시화 (Gate 외 정보)",
      "location": "docs/PAYMENT-EOS-TRANSITION-PLAN.md:458~459 (실행 순서 다이어그램)",
      "problem": "PET-11 (product yml) 이 '병렬 가능' 으로만 표시돼 commit 순서에서 PET-8/12 뒤로 갈 여지. §12 deploy 순서 (product 먼저) 의 git log 가시성 약화.",
      "evidence": "line 458 '병렬 가능: PET-3 ── PET-4 ── PET-11'. Architect 인라인 line 301 동일 우려.",
      "suggestion": "실행 순서 섹션에 비고 1줄: 'PET-11 commit 은 PET-8 이전에 push 권장 — §12 deploy 의도 git log 가시화'. 의존 강제 변경 불필요."
    },
    {
      "severity": "minor",
      "checklist_item": "TDD specification / TDD 분류 합리성",
      "location": "docs/PAYMENT-EOS-TRANSITION-PLAN.md:140 (PET-6 ARCH-REVIEW) + line 168 (PET-7)",
      "problem": "PET-6 / PET-7 (config wiring) tdd=false 가 PET-12 통합 테스트로 후방 검증되는데, PET-12 fail 시 결함 위치 분리가 어려울 약한 리스크.",
      "evidence": "PLAN line 140 / 168 의 Architect 인라인 ⚠️ 주석 + line 504 '약한 우려'.",
      "suggestion": "강제 수정 아님. PET-12 디버깅 시 implementer 가 `@SpringBootTest(classes=KafkaProducerConfig.class)` 빈 wiring assert 추가하는 패턴 정도 비고 1줄 보강 가치."
    }
  ],
  "previous_round_ref": null,
  "delta": null,
  "unstuck_suggestion": null
}
```
