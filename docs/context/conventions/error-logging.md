# Coding Conventions — 예외 / 로깅 / AOP

> 예외 계층, LogFmt + 트레이스 컨텍스트, AOP 컨벤션.

## 예외 계층

```
RuntimeException
├── PaymentBaseException                  # 도메인 공통
│   ├── PaymentValidException             # 사용자 입력 위변조
│   ├── PaymentStatusException            # 상태 전이 위반
│   ├── PaymentOrderedProductStockException  # 재고 부족
│   ├── PaymentTossRetryableException     # PG 재시도 가능
│   ├── PaymentTossNonRetryableException  # PG 종결 거절
│   └── PaymentGatewayStatusUnmappedException
└── IllegalStateException                 # 도메인 불변식 위반 — 두 번째 가드
```

**룰**:
- 도메인 예외는 `PaymentBaseException` 계열로 분류 (코드 + 메시지)
- 단순 가드(이미 다른 예외로 막혔어야 할 case)는 `IllegalStateException` 으로 두 번째 가드 (예: `quarantine()` 메서드의 isTerminal 가드)
- **`catch (Exception e)` swallow 금지** — 잡으면 LogFmt.error + 재throw 또는 명시적 fallback. 워커 등 절대 죽으면 안 되는 경로만 예외적으로 catch + ERROR 승격
- presentation 측에서 도메인 예외 → HTTP 상태 매핑은 `@RestControllerAdvice` 가 단일 진실 원천

## LogFmt + 트레이스 컨텍스트

**모든 로그는 `core/common/log/LogFmt` 를 통해** (4서비스 + gateway 공통 위치):
```java
LogFmt.info(
    EventType.PAYMENT_CONFIRM_SUCCESS,
    () -> String.format("orderId=%s amount=%d", orderId, amount)
);
```

규칙:
- `EventType` enum 으로 이벤트 분류 (`PAYMENT_CONFIRM_SUCCESS`, `STOCK_COMPENSATE_FAIL`, `PAYMENT_QUARANTINE_NOOP_TERMINAL` 등)
- 메시지는 `key=value` 형태로 구성 — Loki 에서 라벨/필터 가능
- traceparent 는 MDC 에서 자동 첨부 (별도 코드 불필요)
- `LogFmt.debug` / `info` / `warn` / `error` 4단계
- `Supplier<String>` 받는 형태 — 로그 레벨 필터링 시 문자열 빌드 비용 회피

## AOP 컨벤션

**`@PublishDomainEvent` + `@PaymentStatusChange`**:
- payment 상태 전이 시 `payment_history` audit row 자동 기록
- `markPaymentAsDone` / `markPaymentAsFail` / `markPaymentAsRetrying` / `markPaymentAsQuarantined` 같은 위임 경로에만 AOP 적용
- 직접 `paymentEvent.done() + saveOrUpdate()` 호출 시 audit trail 누락 — **반드시 위임 경로 사용**

**`@TransactionalEventListener(AFTER_COMMIT)`** 패턴:
- TX 커밋 직후 부수 발행 (Kafka publish, outbox relay) — 동기 publish 가 `@Transactional` 안에서 Hikari 점유 못 하게
- 리스너는 항상 `infrastructure/listener/` 에 위치
