# plan-critic-1

**Topic**: MSA-TRANSITION
**Round**: 1
**Persona**: Critic
**Stage**: plan
**Note**: 전면 재작성된 PLAN.md(59 태스크) 대상. 이전 plan 사이클의 동일 파일명 내용 덮어씀.

## Reasoning

Plan Gate checklist의 `dependency ordering` / `architecture fit` / `TDD specification` / `task quality` 4개 섹션에서 blocking 결함 확인. T1-11·T2a-05 두 앵커 태스크가 (a) scheduler Worker에서 `KafkaTemplate`을 직접 호출하도록 기술돼 선언된 `MessagePublisherPort`·`PgEventPublisherPort`를 우회하며, (b) T1-11은 `PaymentConfirmChannel` 산출물 자체 누락으로 self-contained 실패, (c) T2a-05는 테스트 클래스 `PgOutboxRelayServiceTest`와 대응하는 Service 산출물이 없어 orphan 테스트 발생. 4건 모두 Architect Round 1 critical과 동일 위치 확정.

## Checklist judgement

총 15 항목, passed 10, failed 4, n/a 1.

| section | item | status | evidence |
|---|---|---|---|
| traceability | PLAN.md가 topic.md 결정 사항 참조 | yes | line 3 |
| traceability | 모든 태스크가 ADR에 매핑 (orphan 없음) | yes | lines 1125-1161 커버리지 표 |
| task quality | 객관적 완료 기준 | yes | 태스크마다 테스트 메서드명/산출물 경로 |
| task quality | 태스크 크기 ≤ 2h | no | T1-11(5 파일+SmartLifecycle+concurrency), T2a-05 동일 |
| task quality | 관련 소스 파일/패턴 언급 | no | T1-11 산출물에 PaymentConfirmChannel 누락 |
| TDD specification | tdd=true 태스크 테스트 클래스/메서드 명시 | partial | T2a-05 PgOutboxRelayServiceTest orphan |
| TDD specification | tdd=false 태스크 산출물 명시 | yes | T0-01, T1-01, T1-02, T2a-01 |
| TDD specification | TDD 분류 합리성 | yes | state machine·FCG·dedupe 전부 tdd=true |
| dependency ordering | layer 의존 순서 준수 | no | T1-11·T2a-05 Worker → KafkaTemplate 직접 호출 |
| dependency ordering | Fake가 소비자 앞에 옴 | yes | T1-03<T1-04~, T2a-03<T2a-05~ |
| dependency ordering | orphan port 없음 | yes | 모든 port가 Fake/실어댑터 보유 |
| architecture fit | ARCHITECTURE.md layer 규칙 충돌 없음 | no | T1-11·T2a-05 scheduler → infrastructure 기술 직접 의존 |
| architecture fit | 모듈 간 호출이 port/InternalReceiver 경유 | yes | T3-06 port 구현, T1-01 outbound port 전수 |
| architecture fit | CONVENTIONS.md 준수 | n/a | 코드 미작성 단계 |
| artifact | PLAN.md 존재 | yes | docs/MSA-TRANSITION-PLAN.md |

## Findings

### C-1 (critical) — T1-11 Worker가 MessagePublisherPort 우회
- **location**: T1-11 line 313
- **problem**: `OutboxImmediateWorker`가 `channel.take() → row 로드 → KafkaTemplate.send().get()` 형태로 기술됨. T1-02(line 168)에서 선언한 `MessagePublisherPort` 우회. T1-03 `FakeMessagePublisher`로 단위 테스트 불가.
- **suggestion**: "Worker → `MessagePublisherPort.publish(topic, key, payload)` → `processed_at=NOW()`"로 교정. `KafkaMessagePublisher`는 port 구현체로만 존재.

### C-2 (critical) — T1-11 PaymentConfirmChannel 산출물 누락
- **location**: T1-11 lines 324-329
- **problem**: 목적 문단이 `PaymentConfirmChannel.offer(outboxId)`를 호출한다고 기술했으나 산출물에 Channel 클래스 부재. self-contained 아님.
- **suggestion**: 산출물에 `payment-service/.../payment/core/channel/PaymentConfirmChannel.java` 추가 + 큐 capacity·엘리먼트 타입 명시.

### C-3 (critical) — T2a-05 Worker가 PgEventPublisherPort 우회
- **location**: T2a-05 line 545
- **problem**: `PgOutboxImmediateWorker`가 `KafkaTemplate.send(...)` 직접 호출 — T2a-01 선언 `PgEventPublisherPort` 우회.
- **suggestion**: "Worker → `PgEventPublisherPort.publish(...)` → `processed_at=NOW()`"로 교정.

### C-4 (critical) — T2a-05 PgOutboxRelayService orphan 테스트
- **location**: T2a-05 lines 549 vs 557-561
- **problem**: 테스트 클래스 `PgOutboxRelayServiceTest` + 메서드 3종 명세됐으나 산출물에 대응 Service 없음. T1-11 `OutboxRelayService.java`(line 326)와 대칭 깨짐. TDD RED 진입 불가.
- **suggestion**: (a) Service를 산출물에 추가해 T1-11 대칭 복원, 또는 (b) relay 로직을 Worker 내부로 흡수하고 테스트를 `PgOutboxImmediateWorkerTest`에 합침.

### M-1 (major) — T1-11·T2a-05 커밋 단위 초과
- **location**: T1-11 lines 310-330, T2a-05 lines 542-561
- **problem**: 산출물 5개 + SmartLifecycle + concurrency 테스트까지 한 태스크에 묶여 2h 초과.
- **suggestion**: 3 커밋으로 분해 — (i) Publisher+RelayService, (ii) EventHandler+Channel, (iii) ImmediateWorker+PollingWorker.

### M-2 (major) — T1-12 dual entry point 테스트 모호
- **location**: T1-12 lines 341-346
- **problem**: 핸들러가 FCG QUARANTINED와 DLQ consumer 두 진입점 수렴하나 테스트명이 entry 구분 없음.
- **suggestion**: `handle_WhenEntryIsFcg_ShouldNotRollbackStockImmediately` / `handle_WhenEntryIsDlqConsumer_ShouldRollbackStockAfterCommit`.

### m-1 (minor) — T1-10·T2d-02 Topics 상수 배치
- **location**: T1-10 line 304, T2d-02 lines 789-791
- **problem**: Kafka 토픽 상수를 `domain/messaging/`에 배치.
- **suggestion**: `infrastructure/messaging/` 또는 `application/messaging/`로 재배치.

### m-2 (minor) — T2a-06 참조 typo
- **location**: T2a-06 line 570
- **problem**: "T2a-07로 위임" 기술 — 실제 대상은 T2b-01.
- **suggestion**: "T2b-01로 위임"으로 수정.

### m-3 (minor) — T1-17 StockCacheWarmupService 관심사 혼재
- **location**: T1-17 line 438
- **problem**: Kafka consume + 결제 차단 orchestration이 `infrastructure/cache/` 단일 배치.
- **suggestion**: consumer는 `infrastructure/messaging/consumer/`, orchestration은 `application/service/`로 분리.

### m-4 (minor) — T3-04 PaymentRedisStockPort 이름
- **location**: T3-04 line 901
- **problem**: port 이름에 대상 서비스(`Payment`) + 기술(`Redis`) 동시 노출.
- **suggestion**: port는 `PaymentStockCachePort`/`StockCacheSyncPort`, adapter는 `PaymentRedisStockAdapter`.

## JSON

```json
{
  "stage": "plan",
  "persona": "critic",
  "round": 1,
  "decision": "revise",
  "reason_summary": "Plan Gate의 layer 의존 순서·architecture fit·TDD 명세·task 자급자족 4개 섹션에서 critical 4건. T1-11·T2a-05 Worker의 KafkaTemplate 직접 호출, T1-11 PaymentConfirmChannel 산출물 누락, T2a-05 PgOutboxRelayService orphan 테스트.",
  "findings": [
    {"id": "C-1", "severity": "critical", "location": "T1-11 line 313", "problem": "Worker → KafkaTemplate 직접 호출, MessagePublisherPort 우회"},
    {"id": "C-2", "severity": "critical", "location": "T1-11 lines 324-329", "problem": "PaymentConfirmChannel 산출물 누락"},
    {"id": "C-3", "severity": "critical", "location": "T2a-05 line 545", "problem": "Worker → KafkaTemplate 직접 호출, PgEventPublisherPort 우회"},
    {"id": "C-4", "severity": "critical", "location": "T2a-05 lines 549 vs 557-561", "problem": "PgOutboxRelayServiceTest orphan"},
    {"id": "M-1", "severity": "major", "location": "T1-11, T2a-05", "problem": "태스크 크기 2h 초과"},
    {"id": "M-2", "severity": "major", "location": "T1-12", "problem": "dual entry point 테스트 모호"},
    {"id": "m-1", "severity": "minor", "location": "T1-10, T2d-02", "problem": "Topics 상수 domain 패키지 배치"},
    {"id": "m-2", "severity": "minor", "location": "T2a-06 line 570", "problem": "T2a-07 참조 오류 (실제 T2b-01)"},
    {"id": "m-3", "severity": "minor", "location": "T1-17 line 438", "problem": "StockCacheWarmupService 관심사 혼재"},
    {"id": "m-4", "severity": "minor", "location": "T3-04 line 901", "problem": "PaymentRedisStockPort 이름 중복 기술명"}
  ]
}
```
