# plan-domain-1

**Topic**: OBSERVABILITY-COMPLETION
**Round**: 1
**Persona**: Domain Expert

## Reasoning

PLAN 이 주장하는 결제 도메인 접점(가드 분기 위치, enum 닫힌 집합, cleanup catch 분기, 커스텀 Kafka factory 의 observation/메트릭 공백)을 전부 소스로 실측한 결과 서술과 코드가 일치하며, 결제 정합성에 닿는 4개 태스크(T1·T5·T6·T7)에 domain_risk 가 정확히 부여되고 관측 코드의 비침투 원칙(상태 전이·멱등성·EOS TX 경계 무변경)이 태스크와 SSOT 양쪽에 명시돼 있다. 잔여 지적은 회귀 가드 명시성·테스트 커버 폭에 관한 minor 4건뿐 — pass.

## Domain risk checklist

`.claude/skills/_shared/checklists/plan-ready.md` — domain risk 섹션:

| 항목 | 판정 | 근거 |
|---|---|---|
| discuss 식별 domain risk 가 각각 대응 태스크를 가짐 | yes | PLAN "discuss 리스크 → 태스크 교차 참조" 표 — domain-1 major 2건(컨슈머 observation 공백·producer 메트릭 공백) → T0/T1, minor(status 라벨 6종) → T5 @EnumSource, domain-3(trace 3구간 연속성) → T1+T10 AC3. 실측: `KafkaConsumerConfig.kafkaListenerContainerFactory`(:53-66) 에 setObservationEnabled 부재, `KafkaProducerConfig.stockCommittedProducerFactory`(:60-71) 에 MicrometerProducerListener 부재 — 공백 주장 사실 |
| 중복 방지 체크가 필요한 경로에 계획됨 | n/a | 신규 메시지 경로·쓰기 경로 0 — 관측 전용. 기존 멱등 메커니즘(payment_event_dedupe / Lua dedup token) 비참여·비변경 (SSOT §5-2) |
| 재시도 안전성 검증 태스크 존재 | n/a | 신규 재시도 정책 없음. OTLP exporter 배치 재시도는 비즈니스 재시도와 무관 (SSOT §5-2) |

디스패치 지정 추가 점검:

| 항목 | 판정 | 근거 |
|---|---|---|
| domain_risk 플래그 적정성 (D13/D14/D15/D16) | yes | T1(Kafka wiring)·T5(가드 스킵)·T6/T7(cleanup 실패) 모두 `domain_risk: true`. 설정/대시보드 태스크(T2~T4·T8~T10)는 false — 억지 부여 없음 |
| 가드 스킵 카운터의 noop 분기 무변경 | yes | `PaymentConfirmResultUseCase.java:112` `canApplyConfirmResult()` 가드 — PLAN T5 "분기 조건·전이 무변경(상태 머신 읽기 전용)" + SSOT §4 "신규 상태·기존 전이 변경 없음" 명시. 카운터는 in-memory, TX 쓰기 없음 |
| cleanup 카운터의 return 0 동작 보존 | yes | `DedupeCleanupWorker.java:82-90`(payment) catch(RuntimeException) → ERROR 로그 → return 0 실측. product 측 동일 패턴(:83-86). T6/T7 테스트 `cleanup_예외발생시_failedCounter증가()` 가 예외 비전파(워커 생존) + failed=1.0 + deleted=0.0 을 함께 고정 |
| wiring 2줄의 EOS TX 경계 직교성 | yes | T1 목적에 "EOS 트랜잭션 경계·commit/abort 경로 무변경" 명시. `setObservationEnabled`/`addListener(MicrometerProducerListener)` 는 `setKafkaAwareTransactionManager`(:62)·transactional.id prefix(:69) 와 별개 속성 — SSOT §4 직교성 서술과 코드 구조 일치 |
| D16 추적성 — 컨슈머발 traceId 검증 | yes | T0 산출표 "컨슈머발 처리 로그 traceId 동반" + T10 AC3 컨슈머발 케이스(복구·좀비 폴링 경로) + §6-2 trace-continuity-check WARN 한계 명시·TODOS (f) 자동화 위임 |
| 신규 메트릭 3종 TDD 회귀 가드 | yes | T1·T5·T6·T7 전부 tdd=true + 테스트 클래스/메서드 스펙 명시. 가드 false 케이스에서만 증가는 `handle_terminalStatus_...` / `handle_nonTerminalStatus_...` 쌍이 커버 (폭 보강은 finding 3) |
| D7 라벨 오염 금지 — status 닫힌 집합 | yes | `PaymentEventStatus.canApplyConfirmResult()`(:42-47) false 집합 = DONE/FAILED/CANCELED/PARTIAL_CANCELED/EXPIRED/QUARANTINED 정확히 6종 실측 — PLAN "최대 6종" 일치. orderId/userId 라벨 부재 (명시 단언 부재는 finding 2) |

## 도메인 관점 추가 검토

1. **T1 의 EOS 회귀 가드가 일반 AC7 에만 위임됨** — T1 이 수정하는 두 파일은 EOS wiring 의 SSOT 그 자체(`KafkaConsumerConfig.java:53-66`, `KafkaProducerConfig.java:60-71`)다. observation 래핑이 tx begin/offset 동행과 간섭하면 abort invisibility 가 깨져 spurious 재고 차감 윈도우(PITFALLS §23 류)가 생기는데, 이 시나리오의 직접 회귀 가드인 `PaymentEosIntegrationTest`(payment-service/src/test/.../integration/, 5 시나리오 — commit·abort invisibility·중복 INSERT IGNORE·multi-product·D7 가드) 가 T1 완료 조건에 이름으로 박혀 있지 않다. `./gradlew test` 캐시(UP-TO-DATE) 시 통합테스트가 실제로 안 돌 수 있어 명시 재실행 지정이 안전하다. → finding 1 (minor)
2. **D7 불변식의 테스트 단언 부재** — 교차 참조 표가 "D7 → T5(라벨 검증 테스트)" 로 매핑하지만, T5 테스트 스펙 5건 중 어디에도 "카운터의 태그 집합이 정확히 {status} 하나" (orderId/userId 라벨 부재) 를 단언하는 케이스가 없다. status 라벨 증가 검증은 다른 라벨 혼입을 못 잡는다. → finding 2 (minor)
3. **가드 비발동(false 케이스 미증가) 커버 폭** — `handle_nonTerminalStatus_guardSkipCounterNotCalled()` 가 IN_PROGRESS 1종만 검증. 가드 true 집합은 READY/IN_PROGRESS/RETRYING 3종이고, 특히 RETRYING 은 복구 사이클 경로라 도메인적으로 카운터 오발(정상 진행 건을 스킵으로 집계 → 운영 신호 오염) 검증 가치가 가장 높다. → finding 3 (minor)
4. **noop 분기에 신규 호출 추가의 throw 전파 경로** — `record()` 가 예외를 던지면 지금까지 안전했던 noop 분기(`PaymentConfirmResultUseCase.java:112-118`)가 RuntimeException → FixedBackOff 1s×5 → `payment.events.confirmed.dlq` 로 바뀐다. QUARANTINED 결제의 늦은 APPROVED 가 DLQ 로 빠지는 PITFALLS §21 침묵 분기의 재현 경로다. Micrometer Counter 는 enum 유래 non-null 태그에서 throw 하지 않고 T5 @EnumSource 6종이 전 입력을 커버하므로 잔여 위험은 미미 — 구현 노트로 못박을 것. → finding 4 (minor)
5. **PII**: 신규 노출 데이터 0 (D2 span 미부착, 라벨 = enum status 뿐, 로그 orderId 는 기존 노출분) — 추가 지적 없음.
6. **금전 정확성**: amount·재고 수치에 닿는 변경 0. 메트릭 = best-effort 관측치, 회계 SoT 아님 원칙(SSOT §4 TX 절)이 기존 전 메트릭과 동일하게 유지됨 — 적정.

## Findings

- [minor] T1 완료 조건에 `PaymentEosIntegrationTest` 명시 재실행 누락 (docs/OBSERVABILITY-COMPLETION-PLAN.md T1 완료 조건)
- [minor] D7 라벨 불변식(태그 집합 == {status}) 명시 단언 테스트 부재 (T5 테스트 스펙)
- [minor] 가드 비발동 케이스가 IN_PROGRESS 1종 — READY/RETRYING 미커버 (T5 `PaymentConfirmResultUseCaseGuardSkipTest`)
- [minor] noop 분기 내 record() throw-free 계약 구현 노트 부재 (T5 변경 파일 서술)

## JSON

```json
{
  "stage": "plan",
  "persona": "domain-expert",
  "round": 1,
  "task_id": null,
  "decision": "pass",
  "reason_summary": "도메인 접점 서술 전수(가드 분기 :112, enum false 집합 6종, cleanup catch return 0, 커스텀 factory observation/메트릭 공백)가 소스 실측과 일치하고, domain_risk 태스크 4개의 비침투 원칙·검증 경로가 명시됨. 잔여는 회귀 가드 명시성·테스트 폭 minor 4건뿐.",
  "checklist": {
    "source": "_shared/checklists/plan-ready.md (domain risk 섹션) + 디스패치 추가 점검",
    "items": [
      {
        "section": "domain risk",
        "item": "discuss에서 식별된 domain risk가 각각 대응 태스크를 가짐",
        "status": "yes",
        "evidence": "PLAN 교차 참조 표 — domain-1 major 2건→T0/T1, minor→T5, domain-3→T1+T10 AC3. 공백 주장은 KafkaConsumerConfig.java:53-66 / KafkaProducerConfig.java:60-71 실측 일치"
      },
      {
        "section": "domain risk",
        "item": "중복 방지 체크가 필요한 경로에 계획됨",
        "status": "n/a",
        "evidence": "신규 쓰기/메시지 경로 0 — 관측 전용, 멱등 메커니즘 비참여 (SSOT §5-2)"
      },
      {
        "section": "domain risk",
        "item": "재시도 안전성 검증 태스크 존재",
        "status": "n/a",
        "evidence": "신규 재시도 정책 없음 (SSOT §5-2)"
      },
      {
        "section": "추가 점검",
        "item": "domain_risk 플래그 적정성 (T1·T5·T6·T7 true, 설정/대시보드 false)",
        "status": "yes",
        "evidence": "PLAN 체크박스 요약 — 결제 정합성 접점 4태스크에만 부여"
      },
      {
        "section": "추가 점검",
        "item": "관측 코드 비침투 — 가드 noop·cleanup return 0·EOS TX 경계 무변경 명시",
        "status": "yes",
        "evidence": "T1 목적(EOS 경로 무변경) + T6 테스트 스펙(예외 비전파) + SSOT §4 상태전이/TX 절. PaymentConfirmResultUseCase.java:112-118, DedupeCleanupWorker.java:82-90 실측"
      },
      {
        "section": "추가 점검",
        "item": "D16 의존 추적성 — 컨슈머발 traceId 가 AC 로 보장",
        "status": "yes",
        "evidence": "T0 산출표 + T10 AC3 컨슈머발 케이스 + §6-2 trace-continuity-check WARN 한계 명시"
      },
      {
        "section": "추가 점검",
        "item": "신규 메트릭 3종 TDD 회귀 가드 + 가드 false 케이스 한정 증가 검증",
        "status": "yes",
        "evidence": "T1·T5·T6·T7 tdd=true + 테스트 스펙 명시. 폭 보강은 minor finding 3"
      },
      {
        "section": "추가 점검",
        "item": "D7 라벨 오염 금지 — status enum 닫힌 집합(6종), orderId/userId 라벨 부재",
        "status": "yes",
        "evidence": "PaymentEventStatus.java:42-47 false 집합 정확히 6종 실측 — PLAN '최대 6종' 일치. 명시 단언 부재는 minor finding 2"
      }
    ],
    "total": 8,
    "passed": 6,
    "failed": 0,
    "not_applicable": 2
  },
  "scores": {
    "traceability": 0.95,
    "decomposition": 0.88,
    "ordering": 0.90,
    "specificity": 0.85,
    "risk_coverage": 0.82,
    "mean": 0.88
  },
  "findings": [
    {
      "severity": "minor",
      "checklist_item": "신규 메트릭 3종 TDD 회귀 가드",
      "location": "docs/OBSERVABILITY-COMPLETION-PLAN.md#T1 완료 조건",
      "problem": "T1 이 수정하는 두 파일은 EOS wiring 의 SSOT 인데, EOS 직접 회귀 가드인 PaymentEosIntegrationTest(5 시나리오: commit·abort invisibility·dedupe·multi-product·D7 가드)가 완료 조건에 이름으로 명시되지 않고 일반 AC7(./gradlew test)에 위임됨. gradle 캐시(UP-TO-DATE) 시 통합테스트 미실행 가능.",
      "evidence": "payment-service/src/test/java/com/hyoguoo/paymentplatform/payment/integration/PaymentEosIntegrationTest.java 실재. T1 완료 조건 = 'AC3, AC7' 만",
      "suggestion": "T1 완료 조건에 'PaymentEosIntegrationTest 5건 green (캐시 우회 명시 실행)' 추가"
    },
    {
      "severity": "minor",
      "checklist_item": "D7 라벨 오염 금지",
      "location": "docs/OBSERVABILITY-COMPLETION-PLAN.md#T5 테스트 스펙",
      "problem": "교차 참조 표가 D7→'T5(라벨 검증 테스트)' 로 매핑하지만, 테스트 스펙 5건 중 카운터의 태그 집합이 정확히 {status} 하나임(orderId/userId 부재)을 단언하는 케이스가 없음 — status 라벨 증가 검증만으로는 다른 라벨 혼입을 못 잡는다.",
      "evidence": "T5 테스트 스펙 표 — record_*/handle_* 5건 전부 증가/미호출 검증뿐",
      "suggestion": "PaymentConfirmGuardSkipMetricsTest 에 meter Id 태그 키 집합 == [status] 단언 1건 추가"
    },
    {
      "severity": "minor",
      "checklist_item": "신규 메트릭 3종 TDD 회귀 가드",
      "location": "docs/OBSERVABILITY-COMPLETION-PLAN.md#T5 PaymentConfirmResultUseCaseGuardSkipTest",
      "problem": "가드 비발동(미증가) 케이스가 IN_PROGRESS 1종만. 가드 true 집합은 READY/IN_PROGRESS/RETRYING 3종 — 특히 RETRYING(복구 사이클)에서의 카운터 오발은 운영 신호 오염이라 검증 가치가 가장 높음.",
      "evidence": "PaymentEventStatus.java:42-47 — true 집합 3종. 테스트 스펙은 IN_PROGRESS 단건",
      "suggestion": "handle_nonTerminalStatus_* 를 @ParameterizedTest @EnumSource(names = {READY, IN_PROGRESS, RETRYING}) 로 확장"
    },
    {
      "severity": "minor",
      "checklist_item": "관측 코드 비침투",
      "location": "docs/OBSERVABILITY-COMPLETION-PLAN.md#T5 변경 파일",
      "problem": "record() 가 throw 하면 기존 안전한 noop 분기(PaymentConfirmResultUseCase.java:112-118)가 RuntimeException→재시도 5회→DLQ 로 바뀜 — QUARANTINED 의 늦은 APPROVED 가 DLQ 로 빠지는 PITFALLS §21 침묵 분기 재현 경로. Counter 는 정상 입력에서 throw 하지 않고 @EnumSource 6종이 전 입력 커버라 잔여 위험 미미하나, 구현 노트가 없음.",
      "evidence": "CONFIRM-FLOW §5 — noop 분기는 DLQ 비접촉이 설계 의도(DR-3). 신규 호출이 이 분기의 유일한 throw 후보가 됨",
      "suggestion": "T5 작업 내용에 'record() 는 throw-free 유지(검증/조회 로직 금지, 카운터 증가만)' 구현 노트 1줄 추가"
    }
  ],
  "previous_round_ref": null,
  "delta": null,
  "unstuck_suggestion": null
}
```
