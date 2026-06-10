# discuss-domain-2

**Topic**: OBSERVABILITY-COMPLETION
**Round**: 2
**Persona**: Domain Expert

## Reasoning

Round 1 major 2건이 코드 실측 기반으로 설계에 정확히 반영됐다. D16(컨슈머 listener observation 1줄)은 spring-kafka 3.3.15 실소스 확인 결과 단독으로 충분하고(컨테이너가 start 시 ApplicationContext에서 ObservationRegistry 빈을 자체 조회), D15는 "신규 코드 0" 전제를 기각하고 EOS factory 한정 Micrometer 리스너 1줄 + 부재 시 fallback 분기·한계 명기로 수정됐다. 신규 wiring 2줄 모두 관측 계층 전용으로 EOS 트랜잭션 경계·멱등성·상태 전이에 부작용이 없음을 소스 레벨로 재확인 — pass.

## Domain risk checklist

| 항목 | 판정 | 근거 |
|---|---|---|
| 멱등성 전략이 결정됨 | **yes** | §5-2 — 관측 신호 멱등 비참여·비변경, 카운터 중복 증가 수용 원칙(메트릭=추세, 회계 SoT 아님) 유지. Round 1 판정 불변 |
| 장애 시나리오 최소 3개 식별됨 | **yes** | §5-1 S1~S5 + §5-0 T0 실측 태스크로 fallback 발동 조건까지 구조화 |
| 재시도 정책이 정의됨 | **yes** | §5-2 — 신규 재시도 경로 없음, OTLP exporter 기본 재시도만. 불변 |
| PII/민감정보 신규 도입 시 경로 검토됨 | **yes** | §5-2 — userId(내부 Long) span 속성: 내부 네트워크·Tempo 24h retention·외부 반출 0. 불변 |
| (gate) 전체 결제 흐름과의 호환성 검토됨 | **yes** | §4 — Kafka wiring 2줄(D15·D16)이 EOS wiring(setKafkaAwareTransactionManager·transactional.id)과 직교함을 명시. 소스 검증 일치(아래 1·2번) |
| span 부착 지점 2(confirmed 컨슈머)의 활성 span 전제가 실제 wiring으로 뒷받침됨 | **yes** (Round 1 no → 해소) | D16 신설 + §2 공백 4번 실측 + §3-3 부착 전제 절 + AC3 컨슈머발 span 검색 케이스. 1줄 충분성은 spring-kafka 3.3.15 소스로 본 라운드 확정(아래 1번) |
| tx coordinator 패널(D15)의 소스 메트릭 존재 확인 + 부재 시 fallback 결정됨 | **yes** (Round 1 no → 해소) | D15 수정 — EOS factory 리스너 1줄 + §3-7-C fallback(kafka_brokers liveness + 발행 타이머 프록시) + §6-5 조건부 한계 명기 + §5-0 T0 실측으로 발동 조건 고정 |

## 도메인 관점 추가 검토

1. **[해소 확정 — major 1] D16 1줄 충분성 소스 검증** — spring-kafka 3.3.15(`KafkaMessageListenerContainer.java:385-395`, gradle cache 실소스): `containerProperties.getObservationRegistry()` 가 noop 이고 `isObservationEnabled()==true` 면 컨테이너가 `applicationContext.getBeanProvider(ObservationRegistry.class).getIfUnique()` 로 **빈을 자체 조회**한다. `AbstractKafkaListenerContainerFactory` 는 ApplicationContextAware(`:67`)로 생성 컨테이너에 컨텍스트를 전파(`:432`). 따라서 consumer factory 에 registry 명시 주입 없이 `setObservationEnabled(true)` 단독으로 충분. ObservationRegistry 빈 존재·단일성은 `KafkaProducerConfig.java:95` 의 타입 주입 성공이 방증. 현 `KafkaConsumerConfig.java:53-66` 에 observation 미설정 + `application.yml` `listener.observation-enabled: true` 가 auto-config configurer 경로 전용이라 커스텀 빈에 닿지 않는다는 산출물 실측 서술도 일치.
2. **[해소 확정 — EOS 부작용 0] 신규 wiring 2줄의 트랜잭션 경계 직교성** — (a) D16: 소스상 observation registry 해석(`doStart` :385-395)과 tx manager 처리(`setKafkaAwareTransactionManager` 경유 offset-to-transaction)는 별개 경로 — observation 은 레코드 처리 래핑만 하고 예외 전파·offset·commit/abort 분기 불변. (b) D15: `MicrometerProducerListener` 는 producerAdded/Removed 시 KafkaClientMetrics 바인딩만 수행 — transactional.id 정합(`KafkaProducerConfig.java:69` prefix)·`kafkaTransactionManager` 공유 인스턴스(`:78-82`)에 비침습. 결제 멱등성(D5 dedupe·Lua token)·상태 전이(D7 가드) 비참여.
3. **[해소 확정 — major 2] D15 fallback 의 L-1 가시화 평가** — fallback(`kafka_brokers` + 발행 타이머)은 (a) 단일 브로커 토폴로지라 broker 생존 ≈ coordinator 생존 프록시 타당, (b) 발행 타이머(`stockCommittedKafkaTemplate` observation — `KafkaProducerConfig.java:98` 기활성 실측)는 EOS tx 안 send 실패/지연을 잡는다. 한계 — tx commit/abort 정체는 KafkaTransactionManager 레벨이라 send 타이머에 직접 안 보임 — 는 §6-5 가 "tx 활동 직접 신호 없음"으로 정확히 명기하고 TODOS(브로커 JMX) 위임. 간접 신호(consumer lag·DLQ consumed·가드 스킵 카운터)가 같은 대시보드에 공존해 coordinator 정체 시 조기 인지 경로가 완전 공백은 아님. 도메인 관점 수용.
4. **[해소 확정 — minor 1] D13 status 라벨 6종** — `PaymentEventStatus.java:45` exhaustive switch: `DONE, FAILED, CANCELED, PARTIAL_CANCELED, EXPIRED, QUARANTINED -> false` — §3-7-A "최대 6종 = canApplyConfirmResult()==false 집합 전체" 서술과 정확히 일치. enum 닫힌 집합 저카디널리티 결론 유지.
5. **[해소 확정 — minor 2] never-throw 계약 명문화** — §3-3 "헬퍼 계약 — never-throw (필수)": null/공백 무시 + 내부 try/catch + LogFmt warn, Javadoc 계약화 + AC7 에 null 인자·noop span 단위 테스트 명시. confirm 202 응답 전 경로와 EOS 컨슈머 Kafka TX 내 호출 지점의 안전이 리뷰 가능한 형태가 됐다.
6. **(이상 없음 확인) AC3 컨슈머발 케이스의 도메인 적합성** — observation 활성 시 컨슈머 span 은 레코드 헤더 traceparent 에 연결되므로, 컨트롤러 span 이 없는 복구 사이클·좀비 폴링 복원(stored_traceparent 경유) 트레이스에서 컨슈머 부착이 유일한 orderId 태그 지점이라는 §3-3 논리가 성립. trace-continuity-check.sh:303 WARN 처리 공백을 AC3 가 별도 커버한다는 서술도 실측 일치(스크립트 `:303` "[WARN] payment-service Kafka listener 경로에서 traceId 미발견").
7. **(이상 없음 확인) 잔여 producer factory 비범위 처리** — commandsConfirm(`:110-117`)·DLQ(`:136-149`) factory 메트릭 부착은 §6-6(e) TODOS 위임 — tx coordinator 가시화에 불필요한 표면 확장 자제. EOS factory 한정 부착 범위 적절.

## Findings

(없음 — Round 1 major 2 / minor 2 전부 해소, 신규 도메인 리스크 미발견)

## JSON

```json
{
  "stage": "discuss",
  "persona": "domain-expert",
  "round": 2,
  "task_id": null,

  "decision": "pass",
  "reason_summary": "Round 1 major 2건(컨슈머 observation noop, D15 tx 메트릭 부재)이 D16 신설·D15 수정으로 해소됐고, D16 1줄 충분성(컨테이너의 ApplicationContext ObservationRegistry 자체 조회)을 spring-kafka 3.3.15 실소스로, wiring 2줄의 EOS 경계 직교성을 KafkaConsumerConfig/KafkaProducerConfig 실측으로 확정했다. minor 2건(D13 6종, never-throw 계약)도 코드와 일치하게 반영됨.",

  "checklist": {
    "source": "_shared/checklists/discuss-ready.md",
    "items": [
      {
        "section": "domain risk",
        "item": "멱등성 전략이 결정됨",
        "status": "yes",
        "evidence": "docs/topics/OBSERVABILITY-COMPLETION.md §5-2 — 관측 신호 멱등 비참여, 카운터 중복 증가 수용 원칙 유지"
      },
      {
        "section": "domain risk",
        "item": "장애 시나리오 최소 3개 식별됨",
        "status": "yes",
        "evidence": "§5-1 S1~S5 + §5-0 T0 실측 태스크로 fallback 발동 조건 구조화"
      },
      {
        "section": "domain risk",
        "item": "재시도 정책이 정의됨",
        "status": "yes",
        "evidence": "§5-2 — 신규 재시도 경로 없음, OTLP exporter 기본 재시도만"
      },
      {
        "section": "domain risk",
        "item": "PII/민감정보 신규 도입 시 로깅·저장·전송 경로 검토됨",
        "status": "yes",
        "evidence": "§5-2 — userId span 속성: 내부 네트워크, Tempo 24h retention, 외부 반출 0"
      },
      {
        "section": "design decisions",
        "item": "전체 결제 흐름(결제 요청 → 외부 PG 연동 → 후처리)과의 호환성이 검토됨",
        "status": "yes",
        "evidence": "§4 — Kafka wiring 2줄이 EOS wiring과 직교 명시. spring-kafka 3.3.15 KafkaMessageListenerContainer.java:385-395(observation 해석)·MicrometerProducerListener(메트릭 바인딩만) 소스 검증으로 commit/abort 경로 불변 확인"
      },
      {
        "section": "domain risk (추가 검토)",
        "item": "span 부착 지점 2(confirmed 컨슈머)의 활성 span 전제가 실제 wiring으로 뒷받침됨",
        "status": "yes",
        "evidence": "D16 — setObservationEnabled(true) 1줄. spring-kafka 3.3.15 소스: 컨테이너가 ApplicationContext에서 ObservationRegistry getIfUnique() 자체 조회(KafkaMessageListenerContainer.java:385-395), 팩토리가 컨텍스트 전파(AbstractKafkaListenerContainerFactory:432). AC3 컨슈머발 span 검색 케이스가 검증 담당"
      },
      {
        "section": "domain risk (추가 검토)",
        "item": "신규 메트릭 3종 중 tx coordinator 패널(D15)의 소스 메트릭 존재가 확인되고 부재 시 fallback이 결정됨",
        "status": "yes",
        "evidence": "D15 수정 — EOS factory 한정 MicrometerProducerListener 1줄(KafkaProducerConfig.java:60-71 wiring 0건 실측 일치) + fallback(kafka_brokers liveness + 발행 타이머 프록시) + §6-5 'tx 활동 직접 신호 없음' 한계 명기 + §5-0 T0 실측으로 발동 조건 고정"
      }
    ],
    "total": 7,
    "passed": 7,
    "failed": 0,
    "not_applicable": 0
  },

  "scores": {
    "clarity": 0.92,
    "completeness": 0.90,
    "risk": 0.89,
    "testability": 0.90,
    "fit": 0.91,
    "mean": 0.904
  },

  "findings": [],

  "previous_round_ref": "discuss-domain-1.md",
  "delta": {
    "newly_passed": [
      "span 부착 지점 2(confirmed 컨슈머)의 활성 span 전제가 실제 wiring으로 뒷받침됨",
      "신규 메트릭 3종 중 tx coordinator 패널(D15)의 소스 메트릭 존재가 확인되고 부재 시 fallback이 결정됨"
    ],
    "newly_failed": [],
    "still_failing": []
  },

  "unstuck_suggestion": null
}
```
