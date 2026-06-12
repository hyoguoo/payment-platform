# discuss-domain-3

**Topic**: OBSERVABILITY-COMPLETION
**Round**: 3
**Persona**: Domain Expert

## Reasoning

Round 3 변경은 결제 경로에서 관측 코드를 제거하는 de-risk 방향이다 — span 속성 부착(confirm 202 응답 전 경로 + EOS 컨슈머 Kafka TX 안 호출 지점)이 통째 빠지고, 잔존 신규 코드는 Round 2 에서 EOS 경계 직교성이 소스 검증된 wiring 2줄(D15·D16) + in-memory 카운터뿐이다. 로그 기반 추적 진입(D2)의 전제 3연결과 복구·좀비 경로 trace 연속성(producer template observation 명시 wiring → pg stored_traceparent 회수 → D16 컨슈머 observation)을 실소스로 교차 검증해 end-to-end 성립을 확인 — pass.

## Domain risk checklist

| 항목 | 판정 | 근거 |
|---|---|---|
| 멱등성 전략이 결정됨 | **yes** | §5-2 — 관측 신호 멱등 비참여·비변경, 카운터 중복 증가 수용 원칙(메트릭=추세, 회계 SoT=RDB·payment_history) Round 2 대비 불변 |
| 장애 시나리오 최소 3개 식별됨 | **yes** | §5-1 S1~S5 + §5-0 T0 실측으로 D15 fallback 발동 조건 고정. 불변 |
| 재시도 정책이 정의됨 | **yes** | §5-2 — 신규 재시도 경로 없음, OTLP exporter 기본 배치 재시도만. 불변 |
| PII/민감정보 신규 도입 시 경로 검토됨 | **yes (Round 2 대비 개선)** | §5-2 — span 속성 제거(D2)로 트레이스에 새 비즈니스 값(userId·amount 등) 미탑재. 로그 orderId 는 기존 노출분 그대로 — 신규 PII 표면 0 |
| (gate) 전체 결제 흐름과의 호환성 검토됨 | **yes** | §4 — 잔존 신규 코드가 wiring 2줄 + 카운터뿐임을 소스로 재확인(아래 1·2번). 상태 전이·멱등성·Kafka 계약·TX 경계 변경 0 |
| 로그 기반 추적 진입(D2)의 전제 3연결이 실제 설정·코드로 성립함 | **yes** | 아래 3번 — orderId LogFmt 로그 + logback traceId MDC 패턴 + Loki derivedFields→tempo 전부 실측 일치 |
| 컨슈머발(복구·좀비) 경로의 traceId 연속성(D16)이 상류~하류 전 구간 성립함 | **yes** | 아래 4번 — producer observation 명시 wiring → pg stored_traceparent 저장·회수 → D16 컨슈머 observation 의 3구간 연속 확인 |

## 도메인 관점 추가 검토

1. **(de-risk 확인) span 속성 제거로 결제 경로 관측 코드 표면 축소** — 구 D8 부착 지점 2곳(confirm 202 응답 전 인바운드 어댑터 + EOS 컨슈머 Kafka TX 안)이 범위에서 사라져, never-throw 계약 실패 시 결제 흐름 역류 가능성 자체가 소멸. 잔존 신규 코드는 `KafkaConsumerConfig` `setObservationEnabled(true)` 1줄(D16) + `KafkaProducerConfig` EOS factory `MicrometerProducerListener` 1줄(D15) + in-memory 카운터 3종 — 전부 Round 2 에서 EOS 경계(commit/abort·offset·transactional.id) 직교성이 spring-kafka 3.3.15 소스로 검증된 항목이며 Round 3 에서 내용 불변.
2. **(불변 확인) D16 의 EOS 트랜잭션 경계 영향 0 재확인** — `KafkaConsumerConfig.java` 현 팩토리는 consumerFactory/`setKafkaAwareTransactionManager`/errorHandler/messageConverter 4건만 설정하고 observation 미설정(산출물 §2 공백 4번 서술과 일치). observation 은 컨테이너의 레코드 처리 래핑·MDC 복원(in-memory)만 담당하고 tx manager 경로와 별개 — Round 2 검증 결론이 Round 3 코드 기준으로도 유효. 명분 재서술(span 부착 전제 → 컨슈머 로그 traceId 연속성)은 동작 변경이 아니다.
3. **(신규 검증) 로그 기반 진입 전제 3연결 실측** — (a) `ConfirmedEventConsumer.java:50-51` 이 수신 즉시 `orderId=` LogFmt 로그를 남김(컨슈머발 사고조사의 검색 anchor 성립), (b) `payment-service/src/main/resources/logback-spring.xml:2` 패턴에 `[traceId:%X{traceId:-N/A}]` 동반, (c) `observability/grafana/provisioning/datasources/datasources.yml:17-21` Loki `derivedFields`(matcherRegex `traceId:([a-zA-Z0-9]+)` → `datasourceUid: tempo`) 기활성 + Tempo `uid: tempo` 존재. §3-3 "코드 변경 0으로 즉시 성립" 주장과 일치.
4. **(신규 검증 — 핵심) 복구·좀비 폴링 건의 trace 연속성 상류~하류 성립** — 보상/재confirm 정합성 사고 추적이 끊기지 않는지가 Round 3 의 결정적 도메인 질문. 3구간 확인: ① 상류 — payment 측 confirm 명령 발행(`KafkaProducerConfig.java:146-147` commandsConfirm template `setObservationEnabled(true)` + `setObservationRegistry` 명시 wiring, PITFALLS §12 처방 기적용)이 복구 재발행(OutboxWorker — `ContextAwareVirtualThreadExecutors` 컨텍스트 승계) 포함 traceparent 를 헤더 주입. ② 중류 — pg-service `PgInboxRepository` 의 `stored_traceparent` 저장 + 회수 경로 전용 단일 컬럼 조회 + `TraceparentExtractor.restoreContext` 가 좀비 회수 건의 원 trace 복원 후 `PgOutboxRelayService` 가 현재 span 에서 traceparent 자동 주입(`:73`). ③ 하류 — D16 observation 이 confirmed 메시지 헤더 traceparent 를 추출해 부모 trace 연속 + listener 스레드 MDC 복원. 따라서 §3-3 "좀비 회수 건도 원 트레이스에 연결" 주장은 코드 사실과 부합하며, traceparent 부재(구버전 행 등) 시에도 observation 이 새 root span 을 만들어 traceId 로그 동반 자체는 유지 — orderId→Loki→Tempo 동선이 끊기지 않는다.
5. **(후퇴 평가 수용) Tempo 직접 검색 포기의 사고조사 영향** — 진입이 항상 로그 경유 1단계가 되고 Loki 다운 시 진입 수단이 좁아지며 paymentKey(PG 벤더 콘솔 기준 식별자) 역추적이 로그·DB 경유가 되는 후퇴는 실재한다. 다만 (a) 사고조사의 1차 식별자인 orderId 동선은 AC3 가 컨슈머발 케이스 포함으로 가드하고, (b) 구 D8 설계가 §3-3 기각 대안으로 보존돼 마찰 실측 시 재논의 경로가 명시되며(§6-1), (c) D16 회귀 시 자동 탐지 공백(trace-continuity-check WARN)도 §6-2 + TODOS (f) 로 위임 — 알려진 한계로 구조화돼 있어 도메인 리스크로서의 silent 공백이 아니다. 학습 플랫폼 맥락에서 수용.
6. **(불변 확인) 신규 메트릭 3종 + D7 불변식** — D13(`payment_confirm_guard_skip_total{status}`, 가드 false 집합 6종 enum 닫힌 라벨)·D14(cleanup failed 쌍)·D15(EOS factory 리스너 + fallback) 텍스트가 Round 2 판정본과 동일 — pass 유지. D2 재정의문·§3-7-A 에 orderId/userId 메트릭 라벨 금지(D7) 유지가 명시돼 라벨 오염 불변식 보존.

## Findings

(없음 — Round 3 변경은 결제 경로 관측 코드 제거 + 기성립 연결 활용으로 일관된 de-risk. 신규 도메인 리스크 미발견)

## JSON

```json
{
  "stage": "discuss",
  "persona": "domain-expert",
  "round": 3,
  "task_id": null,

  "decision": "pass",
  "reason_summary": "span 속성 제거(구 D8/D9)는 confirm 202 전 경로·EOS 컨슈머 TX 안의 신규 코드를 통째 없애는 de-risk 방향이며, 대체 진입(D2 로그 기반)의 전제 3연결(컨슈머 orderId LogFmt 로그·logback traceId 패턴·Loki derivedFields)과 복구·좀비 경로 trace 연속성(producer observation 명시 wiring → pg stored_traceparent 회수 → D16)을 실소스로 확인했다. D13~D15·D7 불변식은 Round 2 pass 상태 그대로 유지.",

  "checklist": {
    "source": "_shared/checklists/discuss-ready.md",
    "items": [
      {
        "section": "domain risk",
        "item": "멱등성 전략이 결정됨",
        "status": "yes",
        "evidence": "docs/topics/OBSERVABILITY-COMPLETION.md §5-2 — 관측 신호 멱등 비참여·카운터 중복 증가 수용 원칙 불변"
      },
      {
        "section": "domain risk",
        "item": "장애 시나리오 최소 3개 식별됨",
        "status": "yes",
        "evidence": "§5-1 S1~S5 + §5-0 T0 실측 태스크 불변"
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
        "evidence": "§5-2 — span 속성 제거(D2)로 트레이스 신규 비즈니스 값 0, 로그 orderId 는 기존 노출분 — Round 2 대비 표면 축소"
      },
      {
        "section": "design decisions",
        "item": "전체 결제 흐름(결제 요청 → 외부 PG 연동 → 후처리)과의 호환성이 검토됨",
        "status": "yes",
        "evidence": "§4 — 잔존 신규 코드 = wiring 2줄(D15·D16) + in-memory 카운터. KafkaConsumerConfig 실소스 기준 EOS tx wiring(setKafkaAwareTransactionManager)과 observation 직교 — Round 2 소스 검증 결론 유효"
      },
      {
        "section": "domain risk (추가 검토)",
        "item": "로그 기반 추적 진입(D2)의 전제 3연결이 실제 설정·코드로 성립함",
        "status": "yes",
        "evidence": "ConfirmedEventConsumer.java:50-51 orderId LogFmt + logback-spring.xml:2 traceId MDC 패턴 + datasources.yml:17-21 Loki derivedFields→tempo 기활성"
      },
      {
        "section": "domain risk (추가 검토)",
        "item": "컨슈머발(복구·좀비 폴링) 경로의 traceId 연속성(D16)이 상류~하류 전 구간 성립함",
        "status": "yes",
        "evidence": "KafkaProducerConfig.java:146-147(commandsConfirm template observation 명시 wiring) → pg PgInboxRepository stored_traceparent 저장·회수 + TraceparentExtractor.restoreContext + PgOutboxRelayService:73 → D16 컨슈머 observation 헤더 추출. traceparent 부재 시에도 새 root span 으로 traceId 로그 동반 유지"
      },
      {
        "section": "domain risk (추가 검토)",
        "item": "신규 메트릭 3종(D13/D14/D15) 및 D7 라벨 금지 불변식이 Round 2 pass 상태로 유지됨",
        "status": "yes",
        "evidence": "§3-7 A/B/C 텍스트 Round 2 판정본과 동일. D2 재정의문에 'orderId/userId 메트릭 라벨 금지(D7) 유지' 명시"
      }
    ],
    "total": 8,
    "passed": 8,
    "failed": 0,
    "not_applicable": 0
  },

  "scores": {
    "clarity": 0.93,
    "completeness": 0.91,
    "risk": 0.92,
    "testability": 0.90,
    "fit": 0.92,
    "mean": 0.916
  },

  "findings": [],

  "previous_round_ref": "discuss-domain-2.md",
  "delta": {
    "newly_passed": [],
    "newly_failed": [],
    "still_failing": []
  },

  "unstuck_suggestion": null
}
```
