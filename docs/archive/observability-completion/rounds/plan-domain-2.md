# plan-domain-2

**Topic**: OBSERVABILITY-COMPLETION
**Round**: 2
**Persona**: Domain Expert

## Reasoning

Round 1 minor 4건이 전부 PLAN 본문에 정확히 반영됐고(T1 EOS 통합테스트 캐시 우회 명시 실행, T5 태그 키 집합 단언, 가드 true 집합 3종 전수, record() throw-free 노트), T1 의 tdd=false 재분류는 domain_risk 유지 + `PaymentEosIntegrationTest` 5건(abort invisibility·D7 가드 포함, 실재 확인) 명시 재실행으로 결제 정합성 검증이 약화되지 않았다 — 설정 1줄 wiring 에는 단위 RED 보다 EOS 직접 회귀 가드가 더 강한 검증이다. 잔여는 T0 순수 측정 재분류가 만든 fallback 판정 시점 모호 minor 1건뿐 — pass.

## Domain risk checklist

`.claude/skills/_shared/checklists/plan-ready.md` — domain risk 섹션:

| 항목 | 판정 | 근거 |
|---|---|---|
| discuss 식별 domain risk 가 각각 대응 태스크를 가짐 | yes | 교차 참조 표 유지 — domain-1 major 2건→T0/T1, minor→T5, domain-3→T1+T10 AC3. 공백 재실측: `KafkaConsumerConfig.kafkaListenerContainerFactory` 에 `setObservationEnabled` 여전히 부재, `KafkaProducerConfig.stockCommittedProducerFactory` 에 `MicrometerProducerListener` 부재 (`addListener` grep 0건 — template observation 만 3건) |
| 중복 방지 체크가 필요한 경로에 계획됨 | n/a | 신규 쓰기/메시지 경로 0 — 관측 전용, 멱등 메커니즘 비참여 (SSOT §5-2, Round 2 무변경) |
| 재시도 안전성 검증 태스크 존재 | n/a | 신규 재시도 정책 없음 (SSOT §5-2) |

Round 2 재분류 추가 점검:

| 항목 | 판정 | 근거 |
|---|---|---|
| T1 재분류 후 domain_risk 유지 | yes | PLAN T1 헤더 `tdd: false \| domain_risk: true` + 체크박스 요약 `(non-TDD, domain_risk)` 동기 |
| T1 완료조건 EOS 무회귀 명시 (R1 finding 1) | yes | `./gradlew :payment-service:test --tests "*PaymentEosIntegrationTest" --rerun-tasks` — 캐시 우회 플래그 포함. 테스트 실재: `payment-service/src/test/.../integration/PaymentEosIntegrationTest.java` 5 시나리오(#2 abort invisibility, #5 QUARANTINED D7 가드 포함) |
| 재분류로 정합성 검증 약화 없음 | yes | wiring 1줄×2 는 RED 성립 불가(설정) — 단위 테스트 대신 EOS 통합 5건 + T10 AC3 컨슈머발 케이스가 검증. observation/리스너는 `setKafkaAwareTransactionManager`(:62)·`setTransactionIdPrefix`(:69) 와 독립 속성 — EOS TX 경계 직교성 서술 유지 |
| D13/D14 메트릭 코드 TDD 유지 | yes | T5·T6·T7 전부 `tdd: true` + 테스트 클래스/메서드 스펙 명시. wiring 만 비-TDD 로 분리 — 카운터 로직 회귀 가드 무손실 |
| D7 라벨 불변식 단언 (R1 finding 2) | yes | T5 `record_counterTagKeysOnlyStatus()` — 태그 키 집합 == `["status"]`, orderId/userId 미포함 단언. 결정 추적표 D7 행이 "T5(태그 키 집합 == {status} 단언 테스트)" 로 갱신 |
| 가드 비발동 3종 전수 (R1 finding 3) | yes | `handle_nonTerminalStatus_guardSkipCounterNotCalled()` — `@EnumSource(names = {"READY","IN_PROGRESS","RETRYING"})`, RETRYING 복구 경로 오발 방지 명기. enum true 집합 3종과 정확히 일치 (`PaymentEventStatus.canApplyConfirmResult` 실측) |
| record() throw-free 노트 (R1 finding 4) | yes | T5 변경 파일에 구현 노트 — "검증·조회 로직 금지, 카운터 증가만" + noop 분기 → DLQ 전환 방지 사유(PITFALLS §21 경로) 명기 |
| T6/T7 부착 지점 정확성 | yes | payment/product `DedupeCleanupWorker.executeDeleteExpired` catch(RuntimeException) → ERROR → return 0 실측 — 카운터 증가 추가가 워커 생존(예외 비전파) 보존. 테스트 스펙이 failed=1.0 + deleted=0.0 동시 고정 |

## 도메인 관점 추가 검토

1. **T0 순수 측정 재분류 → fallback 판정 시점 모호** — F-2 (b)안으로 T0 가 wiring 선적용 없는 순수 측정이 됐는데, T0 산출표의 "`kafka_producer_txn_*` 노출 여부 → 미노출(→ fallback)" 판정을 wiring 전 스냅샷으로 내리면 **항상 미노출**이다(리스너 미부착 상태에서 producer 클라이언트 메트릭은 구조적으로 부재 — §2 공백 5번 실측 그대로). SSOT §3-7-C 의 fallback 발동 조건은 "리스너 부착 **후에도** 미확인 시"이고 §5-0 도 wiring 적용 후 스냅샷을 지시한다. fallback 오발 시 tx coordinator 패널이 commit/abort 직접 신호 없는 프록시 조합으로 굳어 EOS coordinator 부분 장애(브로커 생존·tx 정체) 조기 경보가 불필요하게 공백이 된다(§6-5). T8 이 T0+T1 양쪽 의존이라 실무 복구는 가능하나, T1 후 재측정·표 갱신 지시가 명문화돼 있지 않다. → finding 1 (minor)
2. **PII**: Round 2 추가 노출 0 — T4 의 pg-service yml 추가는 histogram bucket 설정뿐, 라벨·span 신규 부착 없음. D7 단언 테스트 신설로 불변식이 기계 가드로 승격 — 개선.
3. **금전 정확성**: 무변경. 메트릭 = best-effort 관측치, TX 롤백 시 선증가 허용 원칙(SSOT §4) 유지.
4. **상태 전이/멱등성**: T5 가드 분기 호출 추가는 읽기 전용 noop 분기 내 in-memory 증가 — 상태 머신·dedupe·EOS 경계 비참여 서술이 코드 구조와 계속 일치.

## Findings

- [minor] T0(순수 측정) 시점의 `kafka_producer_txn_*` 미노출은 wiring 부재의 필연 — §3-7-C fallback 판정은 T1 wiring 후 재측정 기준이어야 하나 PLAN 에 재측정 지시 부재 (docs/OBSERVABILITY-COMPLETION-PLAN.md T0 산출 기록표 / T8 주의)

## JSON

```json
{
  "stage": "plan",
  "persona": "domain-expert",
  "round": 2,
  "task_id": null,
  "decision": "pass",
  "reason_summary": "Round 1 minor 4건 전부 반영 확인(T1 EOS 통합테스트 --rerun-tasks 명시, D7 태그 집합 단언, 가드 true 집합 3종 전수, record() throw-free 노트). T1 tdd=false 재분류는 domain_risk 유지 + EOS 직접 회귀 가드 명시로 정합성 검증 무손실. 신규 잔여는 T0 측정 시점과 fallback 판정 조건의 모호 minor 1건.",
  "checklist": {
    "source": "_shared/checklists/plan-ready.md (domain risk 섹션) + Round 2 재분류 추가 점검",
    "items": [
      {
        "section": "domain risk",
        "item": "discuss에서 식별된 domain risk가 각각 대응 태스크를 가짐",
        "status": "yes",
        "evidence": "교차 참조 표 유지. KafkaConsumerConfig setObservationEnabled 부재·KafkaProducerConfig addListener 0건 재실측 — 공백 주장 사실 유지"
      },
      {
        "section": "domain risk",
        "item": "중복 방지 체크가 필요한 경로에 계획됨",
        "status": "n/a",
        "evidence": "신규 쓰기/메시지 경로 0 — 관측 전용 (SSOT §5-2)"
      },
      {
        "section": "domain risk",
        "item": "재시도 안전성 검증 태스크 존재",
        "status": "n/a",
        "evidence": "신규 재시도 정책 없음 (SSOT §5-2)"
      },
      {
        "section": "Round 2 재분류",
        "item": "T1 tdd=false 재분류 후 domain_risk 유지 + EOS 무회귀 완료조건 명시",
        "status": "yes",
        "evidence": "T1 헤더 domain_risk: true + 완료조건 './gradlew :payment-service:test --tests \"*PaymentEosIntegrationTest\" --rerun-tasks'. 테스트 실재 5 시나리오(abort invisibility·D7 가드 포함)"
      },
      {
        "section": "Round 2 재분류",
        "item": "D13/D14 메트릭 카운터 로직 TDD 유지 (wiring 만 비-TDD 분리)",
        "status": "yes",
        "evidence": "T5·T6·T7 tdd=true + 테스트 클래스/메서드 스펙 명시"
      },
      {
        "section": "R1 minor 반영",
        "item": "R1 finding 1 — T1 완료조건 PaymentEosIntegrationTest 명시 재실행",
        "status": "yes",
        "evidence": "PLAN T1 완료조건 — 캐시 우회(--rerun-tasks) 포함"
      },
      {
        "section": "R1 minor 반영",
        "item": "R1 finding 2 — D7 태그 키 집합 == [status] 단언 테스트",
        "status": "yes",
        "evidence": "T5 record_counterTagKeysOnlyStatus() + 결정 추적표 D7 행 갱신"
      },
      {
        "section": "R1 minor 반영",
        "item": "R1 finding 3 — 가드 비발동 READY/IN_PROGRESS/RETRYING 전수",
        "status": "yes",
        "evidence": "handle_nonTerminalStatus_* @EnumSource 3종 — enum true 집합 실측과 일치"
      },
      {
        "section": "R1 minor 반영",
        "item": "R1 finding 4 — record() throw-free 구현 노트",
        "status": "yes",
        "evidence": "T5 변경 파일 구현 노트 — noop 분기 → DLQ 전환 방지 사유 명기"
      },
      {
        "section": "Round 2 신규",
        "item": "T0 측정 시점 vs §3-7-C fallback 판정 조건 정합",
        "status": "no",
        "evidence": "T0 wiring 선적용 제거(F-2 b안)로 wiring 전 스냅샷은 txn 메트릭 필연 미노출 — fallback 오발 가능. T1 후 재측정 지시 미명문화. minor"
      }
    ],
    "total": 10,
    "passed": 7,
    "failed": 1,
    "not_applicable": 2
  },
  "scores": {
    "traceability": 0.95,
    "decomposition": 0.92,
    "ordering": 0.85,
    "specificity": 0.93,
    "risk_coverage": 0.92,
    "mean": 0.914
  },
  "findings": [
    {
      "severity": "minor",
      "checklist_item": "T0 측정 시점 vs §3-7-C fallback 판정 조건 정합",
      "location": "docs/OBSERVABILITY-COMPLETION-PLAN.md#T0 산출 기록표 / #T8 주의",
      "problem": "T0 가 순수 측정 전용으로 재분류되면서 'kafka_producer_txn_* 미노출 → fallback' 판정이 wiring(T1) 전 스냅샷에서 내려질 수 있음 — 리스너 미부착 상태에서는 구조적으로 항상 미노출이라 fallback 오발. 오발 시 tx coordinator 패널이 commit/abort 직접 신호 없는 프록시 조합으로 확정돼 EOS coordinator 부분 장애 조기 경보 공백(§6-5)이 불필요하게 고착.",
      "evidence": "SSOT §3-7-C — fallback 조건은 '리스너 부착 후에도 미확인 시'. §5-0 도 wiring 적용 후 스냅샷 지시. PLAN T0 는 wiring 선적용 없음(resolved F-2 주석) + T1 후 재측정 지시 부재. KafkaProducerConfig 에 addListener 0건 실측",
      "suggestion": "T0 산출표의 txn 노출 판정 항목에 'T1 wiring 적용 후 재측정 기준' 1줄 주기 또는 T8 주의에 'fallback 발동 판정은 T1 완료 후 /actuator/prometheus 재스냅샷으로' 명시"
    }
  ],
  "previous_round_ref": "plan-domain-1.md",
  "delta": {
    "newly_passed": [
      "T1 완료조건 PaymentEosIntegrationTest 명시 재실행 (R1 finding 1)",
      "D7 라벨 불변식 태그 집합 == {status} 단언 테스트 (R1 finding 2)",
      "가드 비발동 READY/IN_PROGRESS/RETRYING 전수 커버 (R1 finding 3)",
      "noop 분기 record() throw-free 구현 노트 (R1 finding 4)"
    ],
    "newly_failed": [
      "T0 측정 시점 vs §3-7-C fallback 판정 조건 정합 (minor — Round 2 재분류가 만든 신규 항목)"
    ],
    "still_failing": []
  },
  "unstuck_suggestion": null
}
```
