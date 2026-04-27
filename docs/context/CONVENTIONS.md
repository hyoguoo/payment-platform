# Coding Conventions

> 최종 갱신: 2026-04-27

## Lombok 사용 패턴

**Spring Bean 클래스** — 일반적인 use case / service / adapter:
```java
@Service
@RequiredArgsConstructor
public class PaymentConfirmResultUseCase {
    private final EventDedupeStore eventDedupeStore;
    private final StockCachePort stockCachePort;
    // ...
}
```
- `@RequiredArgsConstructor` + `private final` 필드 → 생성자 주입 (Spring 4.x 부터 단일 생성자 자동 주입)
- `@Autowired` 명시 금지

**도메인 Entity / Value Object**:
```java
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
public class PaymentEvent {
    private Long id;
    private PaymentEventStatus status;
    // 변경 메서드는 도메인 행위로만 — setter 금지
    public void done(LocalDateTime approvedAt) { ... }
}
```
- `@Setter` 금지. 상태 변경은 도메인 메서드로
- JPA Entity 는 `@NoArgsConstructor(access = PROTECTED)` 로 외부 생성 차단

**Record DTO**:
```java
public record ConfirmedEventMessage(
    String orderId,
    String eventUuid,
    String status,
    Long amount,
    String approvedAt
) { ... }
```
- Kafka payload, response DTO 는 record 우선

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

## Naming

| 카테고리 | 규칙 | 예 |
|---|---|---|
| Use case | `<Action><Subject>UseCase` | `PaymentConfirmResultUseCase`, `StockCommitUseCase` |
| Service (보조) | `<Subject>Service` | `OutboxRelayService`, `StockOutboxRelayService` |
| Use case 입력 포트 | `<Verb>UseCase` 인터페이스 | `PaymentCommandUseCase` |
| 출력 포트 | `<Subject>Port` | `StockCachePort`, `PaymentConfirmPublisherPort` |
| 출력 포트 어댑터 | `<Subject><Tech>Adapter` | `StockCacheRedisAdapter`, `PaymentConfirmDlqKafkaPublisher` |
| Kafka 메시지 record | `<Subject>EventMessage` (수신) / `<Subject>EventPayload` (발행) | `ConfirmedEventMessage`, `ConfirmedEventPayload` |
| 이벤트 (Spring ApplicationEvent) | `<Subject>RequestedEvent` | `StockCommitRequestedEvent` |
| Listener | `<Subject>Listener` 또는 `<Subject>EventHandler` | `StockOutboxImmediateEventHandler`, `OutboxImmediateEventHandler` |
| Scheduler | `<Subject>Worker` | `OutboxWorker`, `PgOutboxImmediateWorker` |
| Fake (테스트 전용) | `Fake<Subject><Type>` | `FakeStockCachePort`, `FakeEventDedupeStore` |
| Test class | `<Subject>Test` (단위) / `<Subject>ContractTest` / `<Subject>MdcPropagationTest` | `PaymentConfirmResultUseCaseTest` |

## LogFmt + 트레이스 컨텍스트

**모든 로그는 `core/log/LogFmt` 를 통해**:
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

## Try 블록 패턴

**`try` 블록 안에서 외부 변수 재할당 금지** (메모리 룰):
```java
// ❌ 금지
ResultType result = null;
try {
    result = service.call();
} catch (Exception e) { /* ... */ }
process(result);

// ✅ 권장 — private 메서드 추출
private ResultType doSafely() {
    try {
        return service.call();
    } catch (Exception e) {
        // ...
        throw new ...;
    }
}
```

이유: 외부 변수 재할당은 catch 분기에서 null/sentinel 처리가 필요하고, 코드 readability 가 떨어진다. private 메서드 추출로 반환값에 의도 표현.

## 트랜잭션 + Hikari

- `@Transactional` 안에서 동기 Kafka publish 금지 — AFTER_COMMIT 분리
- 외부 HTTP / Redis 호출도 `@Transactional` 밖에서 우선 시도 (실패 보상은 catch)

### `@Transactional(timeout)` 명시 정책

Spring 의 default `@Transactional` 은 **timeout 무한**. 외부 의존성 (Redis / DB lock / 외부 호출) 이 hang 되면 한 트랜잭션이 영원히 점유 → Hikari 풀 고갈 / Kafka consumer rebalance / 다른 동시 처리 starve 로 cascade 가능. 따라서 외부 호출이 끼는 트랜잭션엔 **반드시 명시 timeout**.

| 메서드 카테고리 | 권장 timeout | 사유 |
|---|---|---|
| Kafka consumer 진입 (짧은 작업) | **5s** | 정상 처리 ~50–200ms 의 25배 마진 + DB row lock 점유 한도 + Kafka `max.poll.interval.ms`(5분) 안에 들어옴 + 장애 격리 |
| 외부 HTTP 호출 포함 | 호출 timeout × 1.5 | Feign / WebClient timeout 보다 살짝 길게 — TX rollback 보다 client timeout 이 먼저 발생 |
| 단순 단일 row update | 명시 불요 | default 도 OK (외부 의존성 없음) |

**예시** (`PaymentConfirmResultUseCase.handle:120`):
```java
@Transactional(timeout = 5)
public void handle(ConfirmedEventMessage message) {
    eventDedupeStore.markWithLease(...);   // Redis ~ms
    processMessage(message);                // DB save + outbox INSERT
    eventDedupeStore.extendLease(...);      // Redis ~ms
}
```

5초의 4가지 동시 만족: false-positive 방지 (GC pause / Hikari 대기 마진) + DB row lock 한계 + 장애 격리 (5초 후 rollback → 다음 redeliver) + Kafka rebalance 회피.

**값 결정 룰**: 정상 처리 시간 측정 → 그 25배 정도 + 다른 제약 (rebalance / lock / SLA) 만족하는지 검증. 측정 없이 마법 숫자 박지 않음.

## Bean Validation

- request DTO 에 `@NotNull`, `@NotBlank`, `@Min`, `@Max` 등
- `@Valid` 는 controller method parameter 에서만
- 도메인 entity 의 invariant 는 도메인 메서드 내부 가드로 (`Objects.requireNonNull` 또는 `IllegalArgumentException`)

## TDD 흐름

1. **RED**: 실패하는 테스트 작성 → `git commit -m "test: ..."`
2. **GREEN**: 최소 구현으로 테스트 통과 → `git commit -m "feat: ..."` (PLAN.md / STATE.md 함께 갱신)
3. **REFACTOR** (선택): `git commit -m "refactor: ..."`

도메인 entity 는 `@ParameterizedTest @EnumSource` 로 유효/무효 상태 전환 모두 커버.

## Commit Style

- **영문 type prefix + 한글 본문**: `feat:`, `fix:`, `refactor:`, `test:`, `docs:`, `chore:`
- amend 금지, hook 우회 금지, `git add -A` 대신 명시 staging
- `STATE.md` 단독 커밋 금지 — 항상 다른 변경과 함께
- 문서 변경은 단일 커밋에 묶음 (한 결의 변경 한 번에)

자세한 룰: `.claude/skills/_shared/protocols/commit-round.md`
