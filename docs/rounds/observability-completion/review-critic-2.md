# review-critic-2

**Topic**: OBSERVABILITY-COMPLETION
**Round**: 2
**Persona**: Critic

## Reasoning
Fix-A/B/C 는 review-1 의도를 대체로 충족한다: `:payment-service:test` BUILD SUCCESSFUL, 신규 funnel 카운터(`payment.event.published`/`payment.event.terminal`)는 무라벨·eager 등록·never-throw 로 D7 을 지키며 단위 테스트 9종으로 가드되고, published(READY 생성 1회)·terminal(AOP 종결 전이 1회, confirm 가드가 종결 재진입 차단) 계측은 중복 없이 funnel 의도에 맞다. QUARANTINED 종결 제외는 코드·문서에 일관 명시되어 in-flight=published−terminal 가 미복구 이벤트를 신호로 남기는 의도가 보존된다. Fix-B 는 events.confirmed.dlq(컨슈머 부재)를 kafka-exporter `kafka_topic_partition_current_offset` 로 교체하고 commands.confirm.dlq(pg 컨슈머)는 소비 기반 유지 — 두 대시보드 JSON valid. 다만 신규 Aspect 의 `isTerminalStatus()` 가 `PaymentEventStatus.isTerminal()`(SSOT 판별자, docstring 상 중복 종결-Set 선언 대체 목적) 을 그대로 재복제해 종결 집합이 두 곳에 존재 — 현재 값은 일치하나 드리프트 시 funnel under/over-count 위험. major 1, critical 0 → revise. 새 critical 없음.

## Checklist judgement
- test gate: `:payment-service:test` BUILD SUCCESSFUL, PaymentEventFlowMetricsTest 9종(증가·무라벨 D7·never-throw)·guard-skip EnumSource 6종+null 커버 — **yes** (단, Aspect terminal 분기 직접 단위 테스트 부재 — minor)
- test gate / state machine: 신규 상태 전이 없음, 가드/종결 분기는 EnumSource 로 커버 — **n/a**
- convention: `@RequiredArgsConstructor`/`Counter.builder`/eager·cached 패턴 PaymentQuarantineMetrics 와 동형, `catch(Exception)` 부재, null 반환 부재 — **yes** (PaymentCreateUseCaseTest FQN inline SimpleMeterRegistry — minor)
- convention / SSOT: Aspect `isTerminalStatus()` 가 enum `isTerminal()` SSOT 를 재복제 — 종결 집합 이중 정의 — **no (major)**
- execution discipline: 상태머신/DTO/계약/enum 무변경, 기존 테스트 편집은 생성자 파라미터 전파 한정 — **yes**
- domain risk: published/terminal 중복 계측 없음(confirm 가드가 종결 재진입 차단), QUARANTINED 제외 의도적·문서화, throw-free, PII/고카디널리티 라벨 부재 — **yes**

## Findings
- (major) F1 — `PaymentStatusMetricsAspect.isTerminalStatus(PaymentEventStatus)` 가 도메인 enum 의 SSOT 판별자 `PaymentEventStatus.isTerminal()` 을 값까지 verbatim 으로 재복제한다. enum docstring 은 "이 메서드는 LOCAL_TERMINAL_STATUSES Set 중복 선언을 대체하는 SSOT 판별자다" 라고 명시 — 즉 종결 집합 중복 선언을 막으려고 만든 메서드인데 fix 가 새 중복을 도입했다. location: `payment-service/.../infrastructure/aspect/PaymentStatusMetricsAspect.java:69-75` vs `payment-service/.../domain/enums/PaymentEventStatus.java:21-25`. evidence: 두 메서드의 종결 집합 {DONE,FAILED,CANCELED,PARTIAL_CANCELED,EXPIRED} 가 동일; enum 측 docstring 이 SSOT 의도를 명문화. problem: 한쪽이 드리프트하면 terminal 카운터가 funnel under/over-count 를 일으키고 in-flight 계산이 왜곡된다(현재 값 일치라 correctness 는 보존). suggestion: Aspect 의 private `isTerminalStatus()` 제거하고 `resultEvent.getStatus().isTerminal()` 직접 호출로 SSOT 단일화.
- (minor) F2 — `PaymentCreateUseCaseTest` 가 `io.micrometer.core.instrument.simple.SimpleMeterRegistry` 를 import 없이 FQN inline 으로 2회 사용. location: `payment-service/src/test/.../PaymentCreateUseCaseTest.java` setUp. evidence: 동일 클래스의 다른 의존은 import 정리됨. suggestion: import 추가로 정리(차단 사유 아님).
- (minor) F3 — `PaymentStatusMetricsAspect` 의 종결 전이 → `recordTerminal()` 분기에 대한 직접 단위 테스트가 없다. terminal 카운팅은 funnel 의 핵심 변이이나 EnumSource/AOP 통합 테스트로만 간접 커버. location: `PaymentStatusMetricsAspect.java:62-64` (대응 테스트 부재). evidence: `find PaymentStatusMetricsAspect*Test.java` 결과 없음. suggestion: 종결/비종결/null resultEvent 분기에 대한 Aspect 단위 테스트 추가 — 회귀 가드 강화(차단 사유 아님).

## JSON
```json
{
  "stage": "code",
  "persona": "critic",
  "round": 2,
  "task_id": null,
  "decision": "revise",
  "reason_summary": "Fix-A/B/C 의도 충족·테스트 통과·funnel 중복 계측 없음·DLQ 패널 교체 valid 이나, 신규 Aspect isTerminalStatus() 가 enum SSOT isTerminal() 을 재복제해 종결 집합 이중 정의(major). critical 없음.",
  "checklist": {
    "source": "_shared/checklists/code-ready.md",
    "items": [
      { "section": "test gate", "item": "전체 ./gradlew test 통과", "status": "yes", "evidence": ":payment-service:test BUILD SUCCESSFUL" },
      { "section": "test gate", "item": "신규 business logic 테스트 커버리지", "status": "yes", "evidence": "PaymentEventFlowMetricsTest 9종(increment/무라벨 D7/never-throw), PaymentConfirmGuardSkipMetricsTest EnumSource 6종+null; 단 Aspect terminal 분기 직접 단위 테스트 부재(F3 minor)" },
      { "section": "test gate", "item": "새 state machine 전이 @ParameterizedTest @EnumSource 커버", "status": "n/a", "evidence": "신규 상태 전이 없음; 가드/종결 분기는 EnumSource 로 커버" },
      { "section": "convention", "item": "Lombok/Counter 패턴·null 금지·catch(Exception) 없음", "status": "yes", "evidence": "PaymentEventFlowMetrics eager·무라벨, PaymentConfirmGuardSkipMetrics @RequiredArgsConstructor+ConcurrentHashMap cached, PaymentQuarantineMetrics 와 동형; FQN inline SimpleMeterRegistry(F2 minor)" },
      { "section": "convention", "item": "종결 집합 SSOT 단일화", "status": "no", "evidence": "Aspect.isTerminalStatus() 가 PaymentEventStatus.isTerminal() SSOT 를 재복제(F1 major)" },
      { "section": "execution discipline", "item": "범위 밖 코드 수정 없음", "status": "yes", "evidence": "상태머신/DTO/계약/enum 무변경; 기존 테스트 편집은 생성자 파라미터 전파 한정" },
      { "section": "domain risk", "item": "funnel 중복 계측 없음·종결 제외 의도적·PII 미노출", "status": "yes", "evidence": "published READY 생성 1회/terminal AOP 종결 전이 1회(confirm 가드가 종결 재진입 차단), QUARANTINED 제외 코드·문서 일관 명시, 무라벨/status 1개 라벨(D7)" }
    ],
    "total": 7,
    "passed": 5,
    "failed": 1,
    "not_applicable": 1
  },
  "scores": {
    "correctness": 0.90,
    "conventions": 0.80,
    "discipline": 0.95,
    "test-coverage": 0.85,
    "domain": 0.92,
    "mean": 0.88
  },
  "findings": [
    {
      "severity": "major",
      "checklist_item": "종결 집합 SSOT 단일화 / convention",
      "location": "payment-service/src/main/java/com/hyoguoo/paymentplatform/payment/infrastructure/aspect/PaymentStatusMetricsAspect.java:69-75 vs payment-service/src/main/java/com/hyoguoo/paymentplatform/payment/domain/enums/PaymentEventStatus.java:21-25",
      "problem": "신규 Aspect 의 private isTerminalStatus() 가 도메인 enum 의 SSOT 판별자 isTerminal() 을 값까지 verbatim 재복제 — 종결 집합 {DONE,FAILED,CANCELED,PARTIAL_CANCELED,EXPIRED} 이중 정의. 한쪽 드리프트 시 terminal 카운터 under/over-count → in-flight(published-terminal) 왜곡.",
      "evidence": "enum isTerminal() docstring: '이 메서드는 LOCAL_TERMINAL_STATUSES Set 중복 선언을 대체하는 SSOT 판별자다'. 두 메서드 종결 집합 동일. 현재 값 일치라 correctness 는 보존되나 회귀 표면 도입.",
      "suggestion": "Aspect 의 isTerminalStatus() 제거 후 resultEvent.getStatus().isTerminal() 직접 호출로 SSOT 단일화."
    },
    {
      "severity": "minor",
      "checklist_item": "convention / 코드 스타일",
      "location": "payment-service/src/test/java/com/hyoguoo/paymentplatform/payment/application/usecase/PaymentCreateUseCaseTest.java setUp",
      "problem": "SimpleMeterRegistry 를 import 없이 FQN inline 으로 사용.",
      "evidence": "diff 상 io.micrometer.core.instrument.simple.SimpleMeterRegistry FQN 2회 inline.",
      "suggestion": "import 추가로 정리 — 차단 사유 아님."
    },
    {
      "severity": "minor",
      "checklist_item": "신규 business logic 테스트 커버리지",
      "location": "payment-service/src/main/java/com/hyoguoo/paymentplatform/payment/infrastructure/aspect/PaymentStatusMetricsAspect.java:62-64",
      "problem": "종결 전이 → recordTerminal() 분기에 대한 직접 단위 테스트 부재. funnel 핵심 변이가 간접 커버에만 의존.",
      "evidence": "PaymentStatusMetricsAspect*Test.java 미존재(find 결과 없음).",
      "suggestion": "종결/비종결/null resultEvent 분기 Aspect 단위 테스트 추가 — 차단 사유 아님."
    }
  ],
  "previous_round_ref": "review-critic-1.md",
  "delta": {
    "newly_passed": ["funnel 카운터(published/terminal) 신규 추가·무라벨 D7·never-throw 단위 테스트", "events.confirmed.dlq 패널을 kafka_topic_partition_current_offset 로 교체(컨슈머 부재 정합)"],
    "newly_failed": ["Aspect isTerminalStatus() 가 enum isTerminal() SSOT 재복제(종결 집합 이중 정의)"],
    "still_failing": []
  },
  "unstuck_suggestion": null
}
```
