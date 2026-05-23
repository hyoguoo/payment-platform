# Coding Conventions — 트랜잭션 + Hikari

> `@Transactional` 사용 룰, timeout 명시 정책.

## 트랜잭션 + Hikari

- `@Transactional` 안에서 동기 Kafka publish 금지 — AFTER_COMMIT 분리
- 외부 HTTP / Redis 호출도 `@Transactional` 밖에서 우선 시도 (실패 보상은 catch)

## `@Transactional(timeout)` 명시 정책

Spring 의 default `@Transactional` 은 **timeout 무한**. 외부 의존성 (Redis / DB lock / 외부 호출) 이 hang 되면 한 트랜잭션이 영원히 점유 → Hikari 풀 고갈 / Kafka consumer rebalance / 다른 동시 처리 starve 로 cascade 가능. 따라서 외부 호출이 끼는 트랜잭션엔 **반드시 명시 timeout**.

| 메서드 카테고리 | 권장 timeout | 사유 |
|---|---|---|
| Kafka consumer 진입 (짧은 작업) | **5s** | 정상 처리 ~50–200ms 의 25배 마진 + DB row lock 점유 한도 + Kafka `max.poll.interval.ms`(5분) 안에 들어옴 + 장애 격리 |
| 외부 HTTP 호출 포함 | 호출 timeout × 1.5 | Feign / WebClient timeout 보다 살짝 길게 — TX rollback 보다 client timeout 이 먼저 발생 |
| 단순 단일 row update | 명시 불요 | default 도 OK (외부 의존성 없음) |

**예시** (`PaymentConfirmResultUseCase.handle`):
```java
@Transactional(timeout = 5)
public void handle(ConfirmedEventMessage message) {
    PaymentEvent paymentEvent = paymentEventRepository.findByOrderId(...).orElseThrow(...);
    switch (ConfirmStatus.from(message.status())) {
        case APPROVED -> handleApproved(...);
        case FAILED -> handleFailed(...);     // compensateAtomic 먼저 → markPaymentAsFail 나중
        case QUARANTINED -> handleQuarantined(...);
        case UNKNOWN -> // warn noop
    }
    // 예외 throw 시 Spring Kafka DefaultErrorHandler 가 retry / DLQ 책임
}
```

5초의 4가지 동시 만족: false-positive 방지 (GC pause / Hikari 대기 마진) + DB row lock 한계 + 장애 격리 (5초 후 rollback → 다음 redeliver) + Kafka rebalance 회피.

**값 결정 룰**: 정상 처리 시간 측정 → 그 25배 정도 + 다른 제약 (rebalance / lock / SLA) 만족하는지 검증. 측정 없이 마법 숫자 박지 않음.
