# Coding Conventions — Kafka

> Consumer groupId 네이밍, 에러 핸들링(retry/DLQ), timeout 정렬.

## Consumer groupId 네이밍

- **기본**: `{service-name}` (예: `payment-service`, `pg-service`)
- **같은 서비스 내 별도 consumer group 필요 시**: `{service-name}-{purpose}` (예: `pg-service-dlq` — DLQ 토픽이 정상 토픽 consumer offset 진행을 막지 않도록 분리)

현재 codebase 의 groupId 사용 패턴:

| consumer | groupId |
|---|---|
| `ConfirmedEventConsumer` (payment-service) | `payment-service` |
| `PaymentConfirmConsumer` (pg-service) | `pg-service` |
| `PaymentConfirmDlqConsumer` (pg-service) | `pg-service-dlq` |

Kafka rebalance 단위는 consumer group. 하나의 서비스가 여러 토픽을 같은 group 으로 소비하면 한 토픽 처리 지연이 다른 토픽 consume 도 막을 수 있다. 정상 토픽과 DLQ 토픽을 다른 group 으로 분리(`pg-service` / `pg-service-dlq`)한 이유가 이것이다. 토픽별 추가 group 분리는 부하 측정 결과 기반으로 결정한다.

## Kafka consumer 에러 핸들링 (retry / DLQ)

payment-service `ConfirmedEventConsumer` 는 Spring Kafka native 패턴 사용 — 직접 try/catch 후 DLQ publish 호출 금지.

| 컴포넌트 | 책임 |
|---|---|
| `DefaultErrorHandler` | retry 정책 (`FixedBackOff(1000ms, 5)`) + not-retryable 분류 (`MessageConversionException` / `IllegalArgumentException` / `IllegalStateException` 즉시 DLQ) |
| `DeadLetterPublishingRecoverer` | 한도 초과 메시지를 `<topic>.dlq` 로 자동 발행 |
| 빈 등록 | `infrastructure/config/KafkaErrorHandlerConfig` |

룰:
- consumer use case 에서는 비즈니스 분기만, 예외는 그대로 throw
- 도메인이 비/재시도 가능 의미를 가지면 not-retryable 예외 (`IllegalArgumentException` / `IllegalStateException`) 사용 → 즉시 DLQ 분기
- 직접 `kafkaTemplate.send(<dlq-topic>, ...)` 호출하는 패턴 금지

## Kafka consumer timeout 정렬

Kafka consumer 진입 트랜잭션의 timeout 은 다음 셋의 정렬을 고려한다:

| timeout | 값 | 의미 |
|---|---|---|
| `@Transactional(timeout)` | 5s | 한 메시지 처리 트랜잭션 한도 |
| `max.poll.records` × 처리 시간 합산 | (계산 필요) | 한 poll batch 의 총 처리 시간 |
| Kafka broker `max.poll.interval.ms` | 5분 (default) | poll 사이 최대 간격. 초과 시 consumer 가 group 에서 퇴출 → rebalance |

룰:
- `@Transactional(timeout) = 5s` 가 메시지당 처리 시간을 cap → batch 단위 합산도 `max.poll.interval.ms` 안에 들어온다
- 메시지당 5s × `max.poll.records` 합이 5분(`max.poll.interval.ms`)을 넘지 않아야 함. 기본 `max.poll.records=500` 이라면 이론상 최대 2500s 로 5분을 훨씬 초과 — 실제 처리는 정상 경로에서 50~200ms 이므로 평소는 문제없지만 GC pause 나 부하 집중 상황에서 batch 가 쌓이면 위험할 수 있음. `max.poll.records` 를 더 작게 설정하는 것이 안전
- 본 codebase 는 `max.poll.records` 를 `application.yml` 에 명시하지 않아 Kafka default(500) 사용 중 — 부하 측정 시 검증 필요

> `@Transactional(timeout)` 정책 일반 룰은 [transactions.md](transactions.md) 참고.
