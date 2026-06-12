# discuss-domain-1

**Topic**: OBSERVABILITY-COMPLETION
**Round**: 1
**Persona**: Domain Expert

## Reasoning

설계는 결제 트랜잭션·멱등성·상태 전이에 새 I/O를 추가하지 않고(in-memory set/increment만), 메트릭 라벨 오염 금지 불변식(D7)과 PII 경로 검토(§5-2)를 충실히 지켰다. 그러나 산출물의 두 핵심 주장 — (1) confirmed 컨슈머 경계 span 부착이 "결과 회신 + 좀비 복원 트레이스"를 커버한다, (2) Kafka tx coordinator 패널이 "기존 메트릭 조합·신규 코드 0"으로 성립한다 — 을 실제 wiring과 대조하면 둘 다 **현 코드에서 성립이 확인되지 않는다**(커스텀 EOS 컨테이너 팩토리가 listener observation 미설정, 커스텀 ProducerFactory에 Micrometer 리스너 미부착). 관측 토픽에서 "가시화하려던 운영 리스크가 침묵으로 가시화 실패"하는 것이 곧 도메인 리스크이므로 revise.

## Domain risk checklist

| 항목 | 판정 | 근거 |
|---|---|---|
| 멱등성 전략이 결정됨 | **yes** | §5-2 — 신규 관측 신호는 멱등 메커니즘 비참여·비변경. 카운터 중복 증가는 "메트릭=추세, 회계 SoT 아님" 원칙으로 수용(§4 트랜잭션 경계 원칙). 가드 스킵 카운터는 기존 멱등 가드(`PaymentConfirmResultUseCase.java:112` 실측 일치)의 작동 횟수 관측일 뿐 분기 불변 |
| 장애 시나리오 최소 3개 식별됨 | **yes** | §5-1 S1~S5 — Tempo 다운(export 비동기 드롭, 결제 영향 0), Prometheus 다운, 샘플링 1.0 고부하, exemplar 메모리, cleanup 실패 지속. 관측 경로 자체의 장애로 적절히 한정 |
| 재시도 정책이 정의됨 | **yes** | §5-2 — 신규 재시도 경로 없음. OTLP exporter 기본 배치 재시도만, 비즈니스 재시도(PG retry/DLQ)와 분리 명시 |
| PII/민감정보 신규 도입 시 경로 검토됨 | **yes** | §5-2 — userId(내부 Long) span 속성: 전송=내부 docker 네트워크, 저장=Tempo 24h retention(`observability/tempo/tempo.yml` `block_retention: 24h` 실측 일치), 외부 반출 경로 없음. 학습 프로젝트 수용 타당 |
| (gate) 전체 결제 흐름과의 호환성 검토됨 | **yes** | §4 — `ConfirmedEventMessage` 계약 불변 주장은 실제 record(orderId/status/reasonCode/amount/approvedAt/eventUuid, userId 없음)와 일치. 컨트롤러 부착 키(orderId+userId)도 `PaymentConfirmRequest` 필드와 일치 |

## 도메인 관점 추가 검토

1. **[major] confirmed 컨슈머 span 부착 지점의 전제(활성 span 존재)가 현 wiring에서 미확인** — `KafkaConsumerConfig.kafkaListenerContainerFactory`(payment-service `infrastructure/config/KafkaConsumerConfig.java:54-66`)는 Boot auto-config 팩토리를 **동명 빈으로 교체**하면서 `getContainerProperties().setObservationEnabled(true)` 를 호출하지 않는다. `application.yml:63-64` 의 `spring.kafka.listener.observation-enabled: true` 는 auto-config configurer 경로에서만 적용되므로 커스텀 팩토리에는 닿지 않을 개연성이 높다. observation 미활성이면 `ConfirmedEventConsumer.consume`(`:49`) 내 `Span.current()` 는 noop span — `setAttribute` 가 예외 없이 침묵 무시되어 **부착 실패가 어떤 신호도 남기지 않는다**. 특히 §3-3 이 컨슈머 부착의 가치로 명시한 "좀비 폴링 복원 트레이스(stored_traceparent 경유)"와 스케줄러발 복구 사이클 트레이스(controller span 부재 케이스)는 컨슈머 부착이 유일한 orderId 태그 지점이라, 사고 조사가 가장 절실한 복구·격리 케이스에서 AC3 동선이 무너진다. 기존 `trace-continuity-check.sh` 도 listener 경로 traceId 미발견을 WARN 처리(`:303`)라 회귀 가드가 아니다.
2. **[major] D15 "기존 메트릭 조합·신규 코드 0" 전제 미검증 — tx coordinator 가시화가 빈 패널로 끝날 수 있음** — `kafka_producer_txn_*` 류 클라이언트 메트릭은 `MicrometerProducerListener` 가 ProducerFactory 에 부착돼야 노출되는데, `stockCommittedProducerFactory`(`KafkaProducerConfig.java:61-71`)는 커스텀 `DefaultKafkaProducerFactory` 라 Boot 의 메트릭 customizer(auto-config 팩토리 전용, `@ConditionalOnMissingBean(ProducerFactory.class)` back-off)가 적용되지 않을 개연성이 높다. 설계의 plan 단계 실측 hedge(§3-7-C)는 "메트릭 **이름** 확정"을 전제하지, **부재 시 fallback**(리스너 1줄 wiring=신규 코드 발생 vs `kafka_brokers` 단독 수용=EOS tx 활동 신호 0)을 결정하지 않았다. L-1(EOS coordinator 의존)을 가시화하려던 패널이 침묵 실패하면 coordinator 장애 시 confirm 결과 처리 정체(abort→재배달→DLQ)를 조기에 못 본다.
3. **[minor] D13 라벨 시맨틱 서술 부정확** — "종결 상태 3종 내외"라 했으나 `canApplyConfirmResult()==false` 집합은 실제 6종(DONE/FAILED/CANCELED/PARTIAL_CANCELED/EXPIRED/QUARANTINED, `PaymentEventStatus.java:45`). 카디널리티 리스크는 여전히 무시 가능하나, 대시보드 패널 범례·알람 후속 설계의 기준 서술이므로 정정 필요.
4. **[minor] span 헬퍼의 never-throw 계약 미명문화** — §4 가 "in-memory set 1회"라고만 서술. OTel API 는 실질적으로 throw 하지 않지만, "관측 코드가 결제 비즈니스 실패를 유발하지 않는다"는 원칙을 헬퍼(`SpanBusinessAttributes`)의 코드 계약(Javadoc + null-safe 인자 처리)으로 명문화해야 confirm 경로(202 응답 전)와 EOS 컨슈머 TX 안 호출 지점의 안전이 리뷰 가능한 형태가 된다.
5. **(이상 없음 확인)** 샘플링 1.0 부하 — `docker/docker-compose.apps.yml` 이 이미 전 서비스 `MANAGEMENT_TRACING_SAMPLING_PROBABILITY: "1.0"` 으로 운영 중이라 yml 기본값 변경(0.0→1.0)의 실효 델타는 IDE 로컬뿐. S3 의 env 하향 경로 보존과 결합해 수용 타당.
6. **(이상 없음 확인)** 메트릭 라벨 오염 금지 — 신규 3종 라벨: `status`(enum 저카디널리티) / 무라벨 / 무라벨(패널만). orderId·userId 라벨 0건, D7 불변식 준수. exemplar 의 trace_id 는 라벨이 아닌 고정 크기 circular buffer 저장이라 카디널리티 폭발 없음(S4 서술 정확).
7. **(이상 없음 확인)** D14 부착 지점 — payment/product 양쪽 `DedupeCleanupWorker.executeDeleteExpired` catch 분기(payment `:82-90`, product 동일 구조)가 "ERROR 로그 후 0 반환" 서술과 실측 일치. TTL 8일 > retention 7일 불변식 하에서 failed 카운터가 수동 개입 시간 확보 신호로 기능한다는 S5 논리 타당.
8. **(이상 없음 확인)** 가드 스킵 카운터는 `@Transactional(timeout=5)` 내 in-memory 증가뿐, 해당 분기는 읽기 후 즉시 return(쓰기 0)이라 롤백 불일치 자체가 없음 — 실측(`PaymentConfirmResultUseCase.java:105-118`) 일치.

## Findings

- **major** — 컨슈머 경계 span 부착 전제(listener observation 활성) 미검증: 커스텀 EOS 컨테이너 팩토리에 observation 설정 부재. 복구/좀비 트레이스의 orderId 검색 불능이 침묵 발생 가능. → 설계에 "컨슈머 listener observation 활성 실측 + 필요 시 `setObservationEnabled(true)` 1줄을 본 토픽 범위에 포함" 명시 요구
- **major** — D15 의 `kafka_producer_txn_*` 노출 전제 미검증 + 부재 시 fallback 미결정: 커스텀 ProducerFactory 에 Micrometer 리스너 미부착 개연성. → 부재 시 분기(리스너 wiring 소량 코드 허용 vs kafka_brokers 단독 수용+한계 명기)를 설계 결정으로 선결
- **minor** — D13 "종결 상태 3종 내외" → 실제 6종으로 정정
- **minor** — span 헬퍼 never-throw/null-safe 계약 명문화

## JSON

```json
{
  "stage": "discuss",
  "persona": "domain-expert",
  "round": 1,
  "task_id": null,

  "decision": "revise",
  "reason_summary": "결제 TX·멱등성·상태 전이 무침습 원칙과 라벨 불변식(D7)·PII 검토는 충실하나, span 부착 지점 2(confirmed 컨슈머)의 observation 활성 전제와 D15 tx coordinator 메트릭 존재 전제가 실제 커스텀 Kafka wiring(KafkaConsumerConfig/KafkaProducerConfig)에서 확인되지 않아, 가시화하려던 복구 경로 추적과 L-1 리스크 패널이 침묵 실패할 수 있다.",

  "checklist": {
    "source": "_shared/checklists/discuss-ready.md",
    "items": [
      {
        "section": "domain risk",
        "item": "멱등성 전략이 결정됨",
        "status": "yes",
        "evidence": "docs/topics/OBSERVABILITY-COMPLETION.md §5-2 — 관측 신호 멱등 비참여, 카운터 중복 증가 수용 원칙 명시. PaymentConfirmResultUseCase.java:112 가드 실측 일치"
      },
      {
        "section": "domain risk",
        "item": "장애 시나리오 최소 3개 식별됨",
        "status": "yes",
        "evidence": "§5-1 S1~S5 5건 — 관측 경로 장애가 결제 TX에 역류하지 않음 검토 포함"
      },
      {
        "section": "domain risk",
        "item": "재시도 정책이 정의됨",
        "status": "yes",
        "evidence": "§5-2 — 신규 재시도 경로 없음, OTLP exporter 기본 재시도만이며 비즈니스 재시도와 분리"
      },
      {
        "section": "domain risk",
        "item": "PII/민감정보 신규 도입 시 로깅·저장·전송 경로 검토됨",
        "status": "yes",
        "evidence": "§5-2 — userId span 속성: 내부 네트워크 전송, Tempo 24h retention(tempo.yml block_retention 실측), 외부 반출 0"
      },
      {
        "section": "design decisions",
        "item": "전체 결제 흐름(결제 요청 → 외부 PG 연동 → 후처리)과의 호환성이 검토됨",
        "status": "yes",
        "evidence": "§4 — ConfirmedEventMessage 계약 불변(실제 record 필드와 일치), 새 I/O·분기 0, TX 경계 원칙 절 존재"
      },
      {
        "section": "domain risk (추가 검토)",
        "item": "span 부착 지점 2(confirmed 컨슈머)의 활성 span 전제가 실제 wiring으로 뒷받침됨",
        "status": "no",
        "evidence": "KafkaConsumerConfig.java:54-66 커스텀 팩토리가 auto-config를 교체하나 setObservationEnabled 미호출 — application.yml listener.observation-enabled는 auto-config 경로 전용. noop span에 setAttribute는 침묵 무시"
      },
      {
        "section": "domain risk (추가 검토)",
        "item": "신규 메트릭 3종 중 tx coordinator 패널(D15)의 소스 메트릭 존재가 확인되고 부재 시 fallback이 결정됨",
        "status": "no",
        "evidence": "KafkaProducerConfig.java:61-71 커스텀 DefaultKafkaProducerFactory에 MicrometerProducerListener 미부착 — kafka_producer_txn_* 미노출 개연성. §3-7-C hedge는 이름 확정만 전제, 부재 시 분기 미결정"
      }
    ],
    "total": 7,
    "passed": 5,
    "failed": 2,
    "not_applicable": 0
  },

  "scores": {
    "clarity": 0.90,
    "completeness": 0.76,
    "risk": 0.74,
    "testability": 0.85,
    "fit": 0.88,
    "mean": 0.826
  },

  "findings": [
    {
      "severity": "major",
      "checklist_item": "span 부착 지점 2(confirmed 컨슈머)의 활성 span 전제가 실제 wiring으로 뒷받침됨",
      "location": "docs/topics/OBSERVABILITY-COMPLETION.md §3-3 / payment-service .../infrastructure/config/KafkaConsumerConfig.java:54-66",
      "problem": "커스텀 EOS kafkaListenerContainerFactory가 Boot auto-config 빈을 교체하면서 listener observation을 설정하지 않아, ConfirmedEventConsumer 내 Span.current()가 noop일 개연성이 높다. setAttribute는 noop span에서 예외 없이 무시되므로 부착 실패가 침묵한다. 컨트롤러 span이 없는 복구 사이클·좀비 폴링 복원 트레이스에서는 컨슈머 부착이 유일한 orderId 태그 지점이라, 사고 조사가 가장 필요한 격리/복구 케이스의 AC3 동선이 무너진다.",
      "evidence": "application.yml의 spring.kafka.listener.observation-enabled는 ConcurrentKafkaListenerContainerFactoryConfigurer(auto-config 전용) 경로로만 적용 — 동명 커스텀 빈 존재 시 back-off. trace-continuity-check.sh:303은 listener 경로 traceId 미발견을 WARN 처리라 회귀 가드 아님",
      "suggestion": "설계에 '컨슈머 listener observation 활성 여부 실측'을 plan 선행 검증으로 명시하고, 비활성이면 factory.getContainerProperties().setObservationEnabled(true) 1줄을 본 토픽 범위(payment-service 변경 표면)에 포함. AC3 스모크에 '컨슈머발 트레이스(복구 경로) orderId 검색' 케이스 추가"
    },
    {
      "severity": "major",
      "checklist_item": "신규 메트릭 3종 중 tx coordinator 패널(D15)의 소스 메트릭 존재가 확인되고 부재 시 fallback이 결정됨",
      "location": "docs/topics/OBSERVABILITY-COMPLETION.md §3-7-C·D15 / payment-service .../infrastructure/config/KafkaProducerConfig.java:61-71",
      "problem": "D15 채택 근거('기존 메트릭 조합·신규 코드 0')의 전제인 kafka_producer_txn_* 노출이 미검증. stockCommittedProducerFactory는 커스텀 빈이라 Boot 메트릭 customizer가 적용되지 않아 EOS producer 클라이언트 메트릭이 /actuator/prometheus에 없을 개연성이 높다. 그 경우 L-1(EOS coordinator 의존)을 가시화하려던 패널이 kafka_brokers(브로커 생존 수)만 남아 tx 활동/commit 실패 신호를 전혀 못 본다 — coordinator 장애 시 confirm 결과 정체(abort→재배달→DLQ)의 조기 경보 부재.",
      "evidence": "Boot KafkaAutoConfiguration의 ProducerFactory는 @ConditionalOnMissingBean(ProducerFactory.class)로 back-off — MicrometerProducerListener customizer는 auto-config 팩토리에만 적용. 현 KafkaProducerConfig에 메트릭 리스너 wiring 0건",
      "suggestion": "plan 실측 hedge를 '이름 확정'에서 '존재 확인 + 부재 시 분기 결정'으로 격상: (a) MicrometerProducerListener 1줄 wiring을 허용(D15의 '신규 코드 0' 서술 정정) 또는 (b) kafka_brokers 단독 수용 시 'tx 활동 신호 없음' 한계를 §6에 명기하고 TODOS 위임"
    },
    {
      "severity": "minor",
      "checklist_item": "신규 메트릭 라벨 시맨틱 정확성 (D13)",
      "location": "docs/topics/OBSERVABILITY-COMPLETION.md §3-7-A",
      "problem": "status 라벨 카디널리티를 '종결 상태 3종 내외'로 서술했으나 canApplyConfirmResult()==false 집합은 6종(DONE/FAILED/CANCELED/PARTIAL_CANCELED/EXPIRED/QUARANTINED).",
      "evidence": "PaymentEventStatus.java:45 exhaustive switch",
      "suggestion": "'최대 6종(가드 false 집합 전체)'으로 정정 — 카디널리티 결론(저위험)은 불변"
    },
    {
      "severity": "minor",
      "checklist_item": "관측 코드가 결제 흐름을 차단하지 않는다는 원칙의 코드 계약화",
      "location": "docs/topics/OBSERVABILITY-COMPLETION.md §3-3·§4 트랜잭션 경계 원칙",
      "problem": "span 헬퍼가 confirm 202 응답 전 경로와 EOS 컨슈머 TX 안에서 호출되는데, never-throw/null-safe 계약이 설계에 명문화되지 않음.",
      "evidence": "§4는 'in-memory 연산'만 서술 — 인자 null(위변조 요청 등) 처리·예외 전파 금지 계약 부재",
      "suggestion": "SpanBusinessAttributes 헬퍼 계약(어떤 입력에도 throw 금지, null 인자 무시)을 설계에 1줄 명시하고 단위 테스트(AC7)에 null 케이스 포함"
    }
  ],

  "previous_round_ref": null,
  "delta": null,

  "unstuck_suggestion": null
}
```
