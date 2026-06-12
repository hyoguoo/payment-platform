# review-domain-2

**Topic**: OBSERVABILITY-COMPLETION
**Round**: 2
**Persona**: Domain Expert

## Reasoning

review-1 major 2건이 모두 실증으로 해소됐다 — funnel 카운터(`payment_event_published_total`/`terminal_total`)는 이제 실제 등록·계측되고 대시보드 expr 과 이름이 일치하며, events.confirmed.dlq 패널은 컨슈머 없는 토픽에 맞는 브로커 측 `kafka_topic_partition_current_offset` 으로 교체됐다. 신규 계측은 결제 상태 전이·트랜잭션 경계·멱등성에 비침투적이고(증가만 하는 무라벨 카운터, never-throw), QUARANTINED 의 terminal 제외는 도메인 `isTerminal()` SSOT 와 정확히 동조해 격리 결제가 in-flight 잔차에 남는 것이 오히려 올바른 알람 의미다. 잔여는 정확도·드리프트 성격의 minor 3건뿐 — pass.

## Domain risk checklist

| 항목 | 판정 | 근거 |
|---|---|---|
| paymentKey/orderId/카드번호 plaintext 메트릭 라벨 노출 없음 | yes | `PaymentEventFlowMetrics` 두 카운터 무라벨(D7) — `PaymentEventFlowMetricsTest.recordPublished_noLabels_d7Invariant` 등 getTags().isEmpty() 단언. 대시보드 신규 expr 도 무라벨 sum |
| 보상/취소 로직 멱등성 가드 | yes | 보상·취소 로직 자체 무변경. `recordTerminal` 은 AOP after-proceed 1줄 — `compensateAtomic` dedup token / `markPaymentAs*` 도메인 가드 미접촉 |
| PG "이미 처리됨" 응답 맹목 수용 없음 | n/a | PG 연동 계약 무변경 |
| 상태 전이 불변식 위반 없음 | yes | `PaymentCreateUseCase:53` recordPublished 는 READY 저장+order 연결 후 증가만, 반환·TX·@PublishDomainEvent 무변경. `PaymentStatusMetricsAspect:62-64` recordTerminal 은 proceed 성공 후 분기 — 전이 실패(throw) 시 미집계, 전이 결과 무간섭 |
| race window 락/트랜잭션 격리 | yes | Micrometer Counter 는 RDB/Redis 미접촉, EOS TM·offset commit 과 직교. 단 카운터가 비트랜잭셔널이라 abort 재배달 시 중복 증가 가능 — finding 2 (minor, 메트릭 정확도만) |

### 추가 점검 (dispatch 지정)

- **Fix-A 계측 비침투**: `createNewPaymentEvent` — recordPublished 가 저장 로직 뒤 1줄 추가, 시그니처·반환·@Transactional 무변경. `PaymentStatusMetricsAspect` — 기존 transition 기록 뒤 terminal 분기만 추가, proceed 결과 그대로 반환. `Counter.increment()` 는 throw 불가 + 생성자 eager 등록이라 hot path 등록 예외 경로 제거 (review-1 minor 2 의 권장 패턴을 신규 클래스가 채택). never-throw 실질 충족
- **종결 중복 계측**: 정상 경로 1결제=1terminal — handleApproved/handleFailed 는 D7 가드+D5 dedupe 가 재진입 차단, handleStockFailure 는 READY 전용, expire() 는 READY 외 throw(집계 안 됨). 주의 경로 2건: (a) EOS abort→RDB rollback(dedupe 포함)→재배달→markPaymentAsDone 재실행 — 카운터는 롤백 안 되므로 +2 (비트랜잭셔널 메트릭 본질 한계), (b) `PaymentEvent.fail():117-119`/`done():102-104` 의 terminal no-op 패턴 — no-op 인데 terminal 상태 event 가 반환되면 aspect 가 재집계. 단 해당 no-op 재진입 호출자(`PaymentTransactionCoordinator:157` D12 경로, `:99` done 래퍼)는 현재 프로덕션 call site 0건이라 실도달 불가. 양쪽 다 minor
- **QUARANTINED 제외 타당성**: 타당. aspect 의 terminal 5종은 `PaymentEventStatus.isTerminal()` (DONE/FAILED/CANCELED/PARTIAL_CANCELED/EXPIRED=true, QUARANTINED=false) 과 정확히 동일 집합. 격리=복구대기 미종결(PITFALLS §19, 자동 진행 메커니즘 부재·admin 강제 전이 필요)이므로 격리 결제가 in-flight 잔차에 남는 것은 "사람 손길 필요한 결제가 시야에서 사라지지 않는다"는 의도된 알람 의미 — funnel 왜곡이 아님. 격리 식별은 같은 보드 격리 row(`payment_quarantined_total` + 가드 스킵)가 분리 담당하고, 추후 admin 전이(TQ-2)가 markPaymentAsDone/Fail 경유 시 terminal 집계로 funnel 자연 종결. 정합적
- **published 1회 보장**: createNewPaymentEvent 는 checkout 1회 경로. pg self-loop 재발행·Reconciler resetToReady 재confirm 은 기존 PaymentEvent 재사용 — 생성 재진입 없음. checkout 중복은 Idempotency-Key Redis 가드가 차단. 1결제=1published 성립 (TX 커밋 실패 시 +1 과대 가능 — finding 2 에 포함)
- **D7 라벨 불변식**: 두 카운터 무라벨 — 테스트 단언 존재. 유지
- **Fix-B 교차 검증**: business-dashboard.json DLQ row — `payment.events.confirmed.dlq` 유입률 `increase(kafka_topic_partition_current_offset{topic=...}[1m])` / 누적 `sum(kafka_topic_partition_current_offset{...})` 로 교체 확인. 오프셋은 단조 증가라 increase 의미 성립, retention 삭제에도 불감. `payment.commands.confirm.dlq` 는 pg `PaymentConfirmDlqConsumer` 존재로 consumer 기반 유지 — 올바른 비대칭
- **Fix-C 교차 검증**: `PaymentConfirmResultUseCaseGuardSkipTest` — 가드 false 6종(DONE/FAILED/CANCELED/PARTIAL_CANCELED/EXPIRED/QUARANTINED) @ParameterizedTest @EnumSource 로 record() 1회 + counter 1.0 단언, true 3종 미호출 단언. QUARANTINED 늦은 APPROVED 카운트 경로 포함
- **테스트 실증**: `./gradlew :payment-service:test --tests *PaymentEventFlowMetrics* --tests *GuardSkip* --tests *PaymentCreateUseCase*` (jacoco 게이트 제외) → GREEN

## 도메인 관점 추가 검토

1. **terminal 판별의 SSOT 중복 재도입** — `PaymentStatusMetricsAspect.isTerminalStatus:69-75` 가 `PaymentEventStatus.isTerminal()` 과 동일 집합을 || 체인으로 로컬 중복 선언. enum Javadoc 이 "LOCAL_TERMINAL_STATUSES Set 중복 선언을 대체하는 SSOT 판별자" 라고 명시한 바로 그 패턴의 회귀 — enum 의 exhaustive switch 는 상태 추가 시 컴파일 강제가 걸리지만 aspect 의 || 체인은 조용히 드리프트해 funnel 이 왜곡된다. `resultEvent.getStatus().isTerminal()` 1줄 대체 가능 (QUARANTINED 제외 의도도 자동 보존).
2. **in-flight stat(절대값 차)의 드리프트 3경로** — (a) EOS abort 재배달 시 terminal 중복 증가, (b) create TX 커밋 실패 시 published 과대, (c) 프로세스 재시작 시 카운터 리셋로 재시작 전 published ↔ 재시작 후 terminal 비대칭 → stat 음수/과소 가능. rate 패널 2개는 카운터 리셋 시멘틱으로 무해하고, stat 의 보정 소스(`payment_state_current_total{status} by status` gauge)가 같은 보드 상태 전이 row 에 이미 존재. T10 라이브 스모크에서 stat 의 한계 주석 또는 gauge 기반 보조 단정 권장.
3. **가드 스킵 카운터 lazy register 잔존** (review-1 minor 4 carry-over, Fix 대상 아니었음) — `PaymentConfirmGuardSkipMetrics:44-47` 여전히 가드 분기 내 lazy register. 신규 `PaymentEventFlowMetrics` 가 eager 패턴을 채택했으므로 정렬 여지. 이론 경로 그대로 minor.

## Findings

- [minor] PaymentStatusMetricsAspect.java:69-75 — terminal 5종 || 체인이 `PaymentEventStatus.isTerminal()` SSOT 미사용, 상태 정책 변경 시 silent drift → funnel 왜곡 가능
- [minor] PaymentStatusMetricsAspect.java:62-64 + PaymentCreateUseCase.java:53 + business-dashboard.json(in-flight stat) — 비트랜잭셔널 카운터의 abort 재배달 중복/커밋 실패 과대/재시작 리셋로 절대값 차 stat 드리프트
- [minor] PaymentConfirmGuardSkipMetrics.java:44-47 — lazy register 이론 경로 잔존 (review-1 carry-over)

## JSON

```json
{
  "stage": "review",
  "persona": "domain-expert",
  "round": 2,
  "task_id": null,

  "decision": "pass",
  "reason_summary": "review-1 major 2건 해소 실증 — funnel 카운터 실제 등록·계측(발행=READY 생성 후 1회, 종결=AOP after, 이름·라벨 대시보드와 일치), events.confirmed.dlq 패널 브로커 측 메트릭 교체. 신규 계측은 상태 전이·EOS 경계·멱등성에 비침투적이고 QUARANTINED 종결 제외는 도메인 isTerminal() SSOT 와 동조하는 의도된 설계. 잔여는 메트릭 정확도 성격 minor 3건뿐.",

  "checklist": {
    "source": "_shared/checklists/code-ready.md (domain risk 섹션) + 결제 도메인 추가 점검",
    "items": [
      {
        "section": "domain risk",
        "item": "paymentKey/orderId 등 plaintext 메트릭 라벨 노출 없음",
        "status": "yes",
        "evidence": "PaymentEventFlowMetrics 두 카운터 무라벨(D7) — 테스트가 getTags().isEmpty() 단언"
      },
      {
        "section": "domain risk",
        "item": "계측이 결제 생성·상태 전이 로직/트랜잭션/멱등성/반환에 비침투적",
        "status": "yes",
        "evidence": "PaymentCreateUseCase.java:53 — 저장 후 increment 1줄, 반환·TX 무변경. PaymentStatusMetricsAspect.java:62-64 — proceed 성공 후 분기, 전이 실패 시 미집계. Counter.increment 는 throw 불가 + 생성자 eager 등록"
      },
      {
        "section": "domain risk",
        "item": "정상 경로에서 1결제 = published 1회 / terminal 1회",
        "status": "yes",
        "evidence": "published: checkout 1회 경로 + Idempotency-Key 가드, self-loop/resetToReady 는 생성 재진입 없음. terminal: D7 가드 + D5 dedupe 가 재진입 차단, expire() 는 READY 외 throw. 중복 가능 경로는 EOS abort 재배달(비트랜잭셔널 본질)과 현재 call site 0건인 coordinator no-op 재진입뿐 — minor"
      },
      {
        "section": "domain risk",
        "item": "QUARANTINED 의 terminal 제외가 도메인 의미와 정합",
        "status": "yes",
        "evidence": "aspect terminal 5종 = PaymentEventStatus.isTerminal() true 집합과 동일. 격리=자동 진행 없는 복구대기(PITFALLS §19) — in-flight 잔차에 남는 것이 의도된 알람. 격리 식별은 payment_quarantined_total 별도 row, admin 전이(TQ-2) 시 terminal 집계로 자연 종결"
      },
      {
        "section": "domain risk (review-1 재확인)",
        "item": "대시보드 돈-경로 패널이 실존 메트릭 시리즈를 참조함",
        "status": "yes",
        "evidence": "payment.event.published/terminal → Prometheus payment_event_published_total/terminal_total 로 대시보드 expr 와 일치. events.confirmed.dlq 는 kafka_topic_partition_current_offset(브로커 측, 컨슈머 불요)으로 교체, commands.confirm.dlq 는 pg 컨슈머 존재로 consumer 기반 유지"
      },
      {
        "section": "domain risk (review-1 재확인)",
        "item": "가드 스킵 카운터가 false 6종 전체에서 증가함을 테스트로 단정",
        "status": "yes",
        "evidence": "PaymentConfirmResultUseCaseGuardSkipTest — @EnumSource 6종(QUARANTINED 포함) record() 1회 + counter 1.0 단언, true 3종 미호출 단언"
      },
      {
        "section": "test gate",
        "item": "신규 계측 테스트 GREEN",
        "status": "yes",
        "evidence": "gradlew :payment-service:test --tests *PaymentEventFlowMetrics* --tests *GuardSkip* --tests *PaymentCreateUseCase* (jacoco 게이트 제외) → GREEN. 커밋 5fb629da 기준 497/497"
      }
    ],
    "total": 7,
    "passed": 7,
    "failed": 0,
    "not_applicable": 0
  },

  "scores": {
    "correctness": 0.90,
    "conventions": 0.85,
    "discipline": 0.92,
    "test_coverage": 0.92,
    "domain": 0.90,
    "mean": 0.898
  },

  "findings": [
    {
      "severity": "minor",
      "checklist_item": "QUARANTINED 의 terminal 제외가 도메인 의미와 정합",
      "location": "payment-service/src/main/java/.../infrastructure/aspect/PaymentStatusMetricsAspect.java:69-75",
      "problem": "terminal 5종 판별을 || 체인으로 로컬 중복 선언 — PaymentEventStatus.isTerminal() SSOT 미사용. enum 의 exhaustive switch 는 상태 추가 시 컴파일 강제가 걸리지만 aspect 체인은 조용히 드리프트해 funnel/in-flight 가 왜곡될 수 있다.",
      "evidence": "PaymentEventStatus.isTerminal() Javadoc 이 'LOCAL_TERMINAL_STATUSES Set 중복 선언을 대체하는 SSOT 판별자' 라고 명시 — 본 aspect 가 그 패턴을 재도입. 현재 집합은 동일하여 행동 차이 없음.",
      "suggestion": "isTerminalStatus 를 resultEvent.getStatus().isTerminal() 호출로 대체 (QUARANTINED 제외 의도 자동 보존)."
    },
    {
      "severity": "minor",
      "checklist_item": "정상 경로에서 1결제 = published 1회 / terminal 1회",
      "location": "PaymentStatusMetricsAspect.java:62-64 + PaymentCreateUseCase.java:53 + business-dashboard.json (in-flight stat 패널)",
      "problem": "카운터가 비트랜잭셔널이라 in-flight 절대값 차 stat 이 세 경로로 드리프트: (a) EOS abort→RDB rollback→재배달 시 markPaymentAsDone/Fail 재실행으로 terminal 중복 증가, (b) create TX 커밋 실패 시 published 과대, (c) 프로세스 재시작 카운터 리셋로 재시작 전 published ↔ 재시작 후 terminal 비대칭 → stat 음수/과소. PaymentEvent.fail():117-119/done():102-104 terminal no-op 반환 시 aspect 재집계 경로도 존재하나 해당 호출자(PaymentTransactionCoordinator:99,157)는 현재 프로덕션 call site 0건.",
      "evidence": "Micrometer Counter 는 RDB TX 와 독립. rate 패널 2개는 카운터 리셋 시멘틱으로 무해 — stat 패널만 영향. 같은 보드에 payment_state_current_total by status gauge 가 보정 소스로 존재.",
      "suggestion": "T10 라이브 스모크에서 in-flight stat 에 근사치 주석을 달거나, stat 을 sum(payment_state_current_total{status=~\"READY|IN_PROGRESS|RETRYING|QUARANTINED\"}) gauge 기반으로 보조/교체 검토. coordinator 의 no-op 재진입 경로는 도달 시 재집계됨을 인지."
    },
    {
      "severity": "minor",
      "checklist_item": "race window가 있는 경로에 락 / 트랜잭션 격리 고려됨",
      "location": "payment-service/src/main/java/.../core/common/metrics/PaymentConfirmGuardSkipMetrics.java:44-47",
      "problem": "review-1 minor carry-over (Fix 대상 아니었음) — 가드 분기 내 lazy Counter.register 가 이름/태그셋 충돌 시 IllegalArgumentException → not-retryable → DLQ 이론 경로 잔존.",
      "evidence": "신규 PaymentEventFlowMetrics 는 생성자 eager 등록 패턴을 채택 — 같은 모듈 내 패턴 비대칭.",
      "suggestion": "후속 정리 시 false 6종 status 카운터를 생성자 eager 등록으로 정렬."
    }
  ],

  "previous_round_ref": "review-domain-1.md",
  "delta": {
    "newly_passed": [
      "대시보드 돈-경로 패널이 실존 메트릭 시리즈를 참조함",
      "가드 스킵 카운터가 false 6종 전체에서 증가함을 테스트로 단정"
    ],
    "newly_failed": [],
    "still_failing": []
  },

  "unstuck_suggestion": null
}
```
