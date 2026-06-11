# review-domain-1

**Topic**: OBSERVABILITY-COMPLETION
**Round**: 1
**Persona**: Domain Expert

## Reasoning

관측 코드의 결제 흐름 비침투성은 확보됐다 — D7 가드 조건·상태 전이·EOS 트랜잭션 경계·DLQ 경로·cleanup 워커 회복력 모두 diff 가 건드리지 않았고, 신규 카운터 라벨은 닫힌 enum 집합으로 PII/카디널리티 오염이 없다. 그러나 비즈니스 대시보드의 돈-경로 알람 2곳(발행 vs 종결 funnel·in-flight 잔차, events.confirmed.dlq 패널)이 **코드에 존재하지 않는 메트릭 시리즈**를 참조해 영구 No data 가 된다 — 본 토픽이 메우겠다고 선언한 공백이 그대로 어둡게 남는 major 2건으로 revise.

## Domain risk checklist

| 항목 | 판정 | 근거 |
|---|---|---|
| paymentKey/orderId/카드번호 plaintext 메트릭 라벨 노출 없음 | yes | `payment_confirm_guard_skip_total` 라벨 `status` 1개(enum ≤9값), cleanup 카운터 무라벨, 대시보드 expr 전부 status/topic/application 집계. Tempo span_metrics dimensions 는 http.method/status_code/route(템플릿 경로)로 닫힌 집합 |
| 보상/취소 로직 멱등성 가드 | n/a | 보상·취소 로직 무변경 — DedupeCleanupWorker catch 의 counter.increment 는 기존 예외 삼킴 + `return 0` 보존 (payment :96-100 / product :97-101) |
| PG "이미 처리됨" 응답 맹목 수용 없음 | n/a | PG 연동 계약 무변경 |
| 상태 전이 불변식 위반 없음 | yes | `PaymentConfirmResultUseCase.handle:115-118` — 가드 분기 안에 `guardSkipMetrics.record(status)` 1줄만 추가, 가드 조건·noop return·후속 분기 전부 무변경. `PaymentEventStatus.canApplyConfirmResult` 무변경 (false=종결 6종) |
| race window 락/트랜잭션 격리 | yes | `KafkaConsumerConfig:63` setObservationEnabled / `KafkaProducerConfig:77` MicrometerProducerListener 는 transactional.id·setKafkaAwareTransactionManager·commit/abort 와 직교. 카운터는 in-memory ConcurrentHashMap, RDB/Redis 미접촉 |

### 추가 점검 (dispatch 지정)

- **가드 noop 계측 위치**: record 가 `!canApplyConfirmResult()` 분기 내부에만 존재 — 적용 가능 3종(READY/IN_PROGRESS/RETRYING)에서 미증가는 `PaymentConfirmResultUseCaseGuardSkipTest:100-113` EnumSource 로 검증. 단, false 6종 증가 검증은 DONE 1종뿐 (finding #3)
- **EOS 회귀 가드**: `PaymentEosIntegrationTest` (payment-service/src/test/.../integration/) 존속 — Testcontainers 로 실제 `KafkaConsumerConfig` 를 부팅하므로 observation-enabled 경로가 5개 시나리오(commit/abort/dedupe/multi-product/D7)에 포함됨. 충분
- **샘플링 1.0**: 5서비스 모두 `${TRACING_SAMPLING_PROBABILITY:1.0}` — env 하향 경로 유지. compose(apps/smoke)는 명시 1.0 고정. benchmark 프로필에 별도 하향 없음 → k6 측정 시 env 로 내려야 export 오버헤드가 latency 에 안 섞임 (참고)
- **신규 테스트 실증**: `./gradlew :payment-service:test :product-service:test --tests "*GuardSkip*" --tests "*DedupeCleanupWorker*"` → 18/18 GREEN

## 도메인 관점 추가 검토

1. **funnel 메트릭 실체 부재** — 대시보드 row 1 의 `payment_event_published_total`/`payment_event_terminal_total` 은 4서비스 main 소스 전체 grep 결과 등록처 0건 (Counter/Gauge/Timer.builder 전수 확인). `docs/topics/OBSERVABILITY-COMPLETION.md:213` 인벤토리가 이 둘을 "코드 등록 + 대시보드 노출 O, 갭 —" 로 기재한 것은 사실 오류 — 옛 payment-dashboard.json 의 dead expr 를 실존 메트릭으로 오인해 신규 대시보드에 승계했다. in-flight 잔차 패널(`published - terminal`)은 "결제가 limbo 에 갇혀 있다" 를 보는 핵심 지표인데 영구 No data.
2. **events.confirmed.dlq 패널의 잘못된 시리즈 선택** — `kafka_consumer_records_consumed_total` 은 컨슈머 클라이언트 측 메트릭. `payment.commands.confirm.dlq` 는 pg-service `PaymentConfirmDlqConsumer` 가 소비하므로 시리즈가 생기지만, `payment.events.confirmed.dlq` 는 발행자(DeadLetterPublishingRecoverer)만 있고 **컨슈머가 어느 서비스에도 없음** (@KafkaListener 전수 grep: EVENTS_CONFIRMED / commands.confirm / commands.confirm.dlq / stock-committed 뿐) → 시리즈 영구 부재. confirm 결과가 5회 재시도 소진 후 죽는 곳의 유입 알람 — 본 토픽 §2 가 "미노출 갭" 으로 지목해 보강했다고 주장한 바로 그 패널 — 이 그대로 어둡다. kafka-exporter 가 이미 떠 있으므로 `kafka_topic_partition_current_offset{topic=...}` 가 올바른 소스.
3. 가드 false 6종 중 DONE 만 증가 단정 — FAILED/CANCELED/PARTIAL_CANCELED/EXPIRED/QUARANTINED 라벨 경로 미검증 (행동 위험은 낮음, enum pass-through).
4. `PaymentConfirmGuardSkipMetrics.record:38-50` — throw-free 계약을 javadoc 으로 주장하나 null 가드뿐. 가드 분기 안 lazy `register()` 가 이름/태그셋 충돌 시 `IllegalArgumentException` → `KafkaErrorHandlerConfig` not-retryable 목록 → 종결 결제의 무해한 중복 메시지가 즉시 DLQ 행. 발생 확률 매우 낮음(이름 고유·태그키 고정)이라 minor — DedupeCleanupWorker 처럼 생성자 eager 등록이면 hot path 에서 등록 예외 가능성 자체가 사라진다.

## Findings

- [major] business-dashboard.json:80,85,126 — 존재하지 않는 `payment_event_published_total`/`payment_event_terminal_total` 참조, funnel·in-flight 패널 영구 No data
- [major] business-dashboard.json (DLQ row) — `payment.events.confirmed.dlq` 를 컨슈머 부재 토픽인데 `kafka_consumer_records_consumed_total` 로 조회, 유입·누적 패널 영구 부재
- [minor] PaymentConfirmResultUseCaseGuardSkipTest:80-98 — 가드 false 6종 중 DONE 만 증가 검증
- [minor] PaymentConfirmGuardSkipMetrics.java:38-50 — lazy register 예외 시 not-retryable → DLQ 이론 경로, eager 등록 권장

## JSON

```json
{
  "stage": "review",
  "persona": "domain-expert",
  "round": 1,
  "task_id": null,

  "decision": "revise",
  "reason_summary": "관측 코드는 결제 상태 전이·멱등성·EOS 경계에 비침투적이고 라벨 오염도 없으나, 비즈니스 대시보드의 돈-경로 알람 2곳(발행 vs 종결 funnel·in-flight, events.confirmed.dlq)이 코드에 존재하지 않는 메트릭 시리즈를 참조해 영구 No data — 본 토픽이 메우기로 한 공백이 그대로 남는 major 2건.",

  "checklist": {
    "source": "_shared/checklists/code-ready.md (domain risk 섹션) + 결제 도메인 추가 점검",
    "items": [
      {
        "section": "domain risk",
        "item": "paymentKey/orderId 등 plaintext 메트릭 라벨 노출 없음",
        "status": "yes",
        "evidence": "payment_confirm_guard_skip_total 라벨 status 1개(닫힌 enum), 대시보드 expr 전부 status/topic/application 집계, Tempo span_metrics dims 템플릿 route"
      },
      {
        "section": "domain risk",
        "item": "상태 전이 불변식 무변경 (D7 가드 조건·noop return·DLQ 경로)",
        "status": "yes",
        "evidence": "PaymentConfirmResultUseCase.java:115-118 — 가드 분기 내 record() 1줄만 추가, PaymentEventStatus.canApplyConfirmResult 무변경"
      },
      {
        "section": "domain risk",
        "item": "EOS 트랜잭션 경계와 관측 wiring 직교 + 회귀 가드 존재",
        "status": "yes",
        "evidence": "KafkaConsumerConfig.java:63 / KafkaProducerConfig.java:77 — tx manager·transactional.id·error handler 무변경. PaymentEosIntegrationTest 가 실제 config 부팅으로 5 시나리오 커버"
      },
      {
        "section": "domain risk",
        "item": "DedupeCleanupWorker 스케줄러 회복력 보존 (예외 삼킴 + return 0)",
        "status": "yes",
        "evidence": "payment DedupeCleanupWorker.java:96-100 / product :97-101 — catch 구조 무변경, counter.increment 추가만. 카운터는 생성자 eager 등록이라 런타임 throw 없음"
      },
      {
        "section": "domain risk (추가)",
        "item": "대시보드 돈-경로 패널이 실존 메트릭 시리즈를 참조함",
        "status": "no",
        "evidence": "payment_event_published_total/terminal_total 등록처 0건 (main 소스 meter 등록 전수 grep), payment.events.confirmed.dlq 컨슈머 0건인데 kafka_consumer_records_consumed_total 로 조회"
      },
      {
        "section": "domain risk (추가)",
        "item": "가드 스킵 카운터가 false 6종 전체에서 증가함을 테스트로 단정",
        "status": "no",
        "evidence": "PaymentConfirmResultUseCaseGuardSkipTest — 증가 단정은 DONE 단건, EnumSource 는 true 3종(미증가)만"
      },
      {
        "section": "domain risk (추가)",
        "item": "샘플링 1.0 전환에 env 하향 경로 유지",
        "status": "yes",
        "evidence": "5서비스 ${TRACING_SAMPLING_PROBABILITY:1.0} placeholder 유지, compose 명시 1.0"
      },
      {
        "section": "test gate",
        "item": "신규 계측 테스트 GREEN",
        "status": "yes",
        "evidence": "gradlew :payment-service:test :product-service:test --tests *GuardSkip* --tests *DedupeCleanupWorker* → 18/18 passed"
      }
    ],
    "total": 8,
    "passed": 6,
    "failed": 2,
    "not_applicable": 0
  },

  "scores": {
    "correctness": 0.72,
    "conventions": 0.90,
    "discipline": 0.92,
    "test_coverage": 0.85,
    "domain": 0.80,
    "mean": 0.838
  },

  "findings": [
    {
      "severity": "major",
      "checklist_item": "대시보드 돈-경로 패널이 실존 메트릭 시리즈를 참조함",
      "location": "observability/grafana/dashboards/business-dashboard.json:80,85,126 + docs/topics/OBSERVABILITY-COMPLETION.md:213",
      "problem": "row 1 의 발행 vs 종결 funnel 과 in-flight 잔차 패널이 payment_event_published_total / payment_event_terminal_total 을 참조하나, 이 메트릭은 4서비스 어디에도 등록돼 있지 않다. in-flight 잔차는 '결제가 limbo 에 갇혔다'를 보는 핵심 알람인데 영구 No data.",
      "evidence": "payment/pg/product main 소스의 Counter/Gauge/Timer.builder 전수 grep 에 해당 이름 0건. 옛 payment-dashboard.json 에도 같은 dead expr 존재 — 토픽 문서 §2 인벤토리가 '코드 등록 메트릭 전수 조사' 결과로 이 둘을 실존(노출 O, 갭 —)으로 잘못 기재하고 신규 대시보드가 승계.",
      "suggestion": "(a) 실존 메트릭으로 row 1 재구성 — 예: in-flight = sum(payment_state_current_total{status=~\"READY|IN_PROGRESS|RETRYING\"}), 발행/종결률은 payment_transition_total 라벨 집계, 또는 (b) 두 카운터를 실제 등록(상태 전이 AOP 경유). 어느 쪽이든 T10 라이브 스모크 체크리스트에 '전 패널 데이터 존재' 항목을 명시해 dead expr 재발 차단."
    },
    {
      "severity": "major",
      "checklist_item": "대시보드 돈-경로 패널이 실존 메트릭 시리즈를 참조함",
      "location": "observability/grafana/dashboards/business-dashboard.json (DLQ 유입률·누적 패널)",
      "problem": "payment.events.confirmed.dlq 패널이 kafka_consumer_records_consumed_total{topic=\"payment.events.confirmed.dlq\"} 를 조회하나, 이 토픽은 DeadLetterPublishingRecoverer 발행 전용으로 어느 서비스에도 컨슈머가 없어 클라이언트 consumed 시리즈가 영구 부재. confirm 결과가 5회 재시도 소진 후 격리되는 지점의 유입 알람 — 본 토픽 §2 가 '미노출 갭'으로 지목해 보강했다고 주장한 바로 그 패널 — 이 그대로 어둡다.",
      "evidence": "@KafkaListener 전수 grep: EVENTS_CONFIRMED(payment) / commands.confirm·commands.confirm.dlq(pg) 뿐. commands.confirm.dlq 가 작동하는 이유는 pg PaymentConfirmDlqConsumer 가 소비해 시리즈가 생기기 때문 — 같은 expr 패턴을 컨슈머 없는 토픽에 복제한 것이 원인.",
      "suggestion": "이미 배치된 kafka-exporter 의 브로커 측 메트릭 kafka_topic_partition_current_offset{topic=\"payment.events.confirmed.dlq\"} (sum by topic, increase = 유입률 / 현재값 = 누적) 으로 교체. commands.confirm.dlq 쪽도 동일 소스로 통일하면 '소비됨'과 '쌓임'의 의미 차이도 해소."
    },
    {
      "severity": "minor",
      "checklist_item": "가드 스킵 카운터가 false 6종 전체에서 증가함을 테스트로 단정",
      "location": "payment-service/src/test/java/.../PaymentConfirmResultUseCaseGuardSkipTest.java:80-113",
      "problem": "증가 검증이 DONE 단건. FAILED/CANCELED/PARTIAL_CANCELED/EXPIRED/QUARANTINED 5종의 status 라벨 경로가 미검증 — 특히 QUARANTINED 늦은 APPROVED(D7 핵심 시나리오)의 카운터 증가가 단정되지 않음.",
      "evidence": "@EnumSource 는 true 3종(record 미호출)만 커버. 행동 위험 자체는 낮음(닫힌 enum pass-through).",
      "suggestion": "증가 단정 테스트를 @EnumSource(names={\"DONE\",\"FAILED\",\"CANCELED\",\"PARTIAL_CANCELED\",\"EXPIRED\",\"QUARANTINED\"}) 로 확장."
    },
    {
      "severity": "minor",
      "checklist_item": "race window가 있는 경로에 락 / 트랜잭션 격리 고려됨",
      "location": "payment-service/src/main/java/.../core/common/metrics/PaymentConfirmGuardSkipMetrics.java:38-50",
      "problem": "javadoc 이 throw-free 계약을 주장하나 null 가드만 존재. noop 가드 분기 안에서 lazy Counter.register() 가 meter 이름/태그셋 충돌 시 IllegalArgumentException 을 던지면 not-retryable 분류 → 종결 결제의 무해한 중복 메시지가 즉시 DLQ 로 빠지는 이론 경로.",
      "evidence": "KafkaErrorHandlerConfig 의 not-retryable 목록에 IllegalArgumentException 포함. 현재는 이름 고유·태그키 고정이라 충돌 확률 극히 낮음.",
      "suggestion": "DedupeCleanupWorker 패턴처럼 생성자에서 false 6종 카운터를 eager 등록 — 등록 실패는 부팅 시 fail-fast 로 드러나고 hot path 에서 등록 예외 가능성이 제거됨."
    }
  ],

  "previous_round_ref": null,
  "delta": null,

  "unstuck_suggestion": null
}
```
