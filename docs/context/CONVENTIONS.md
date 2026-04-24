# Coding Conventions

**Analysis Date:** 2026-03-18

## Lombok Usage Patterns

**Standard Spring bean class:**
```java
@Slf4j                        // logging: generates `log` field
@Service
@RequiredArgsConstructor      // constructor injection — never use @Autowired on fields
public class PaymentCheckoutServiceImpl implements PaymentCheckoutService {
    private final OrderedUserUseCase orderedUserUseCase;
    // all fields injected via generated constructor
}
```

**Domain entity (custom builder names):**
```java
@Getter
@Builder(builderMethodName = "allArgsBuilder", buildMethodName = "allArgsBuild")
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class PaymentEvent {
    // allArgsBuilder / allArgsBuild distinguishes from standard @Builder
    // factory method create() used for new domain object creation
}
```

**Utility class (prevent instantiation):**
```java
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class LogFmt { ... }

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ResponseUtil { ... }
```

**Enum with fields:**
```java
@Getter
@RequiredArgsConstructor
public enum PaymentErrorCode implements ErrorCode {
    PAYMENT_EVENT_NOT_FOUND("E03001", "존재하지 않는 결제 이벤트입니다."),
    // ...
    private final String code;
    private final String message;
}
```

**DTO / Response objects:**
```java
@Getter
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BasicResponse<T> {
    private T data;
    private ErrorResponse error;
}
```

**Rules:**
- Use `@RequiredArgsConstructor` on every Spring bean; never field `@Autowired`
- Domain entities use custom builder names (`allArgsBuilder` / `allArgsBuild`) to signal "reconstitution from persistence" vs. domain creation
- Add `@Slf4j` to all `@Service` and handler classes that log
- Utility classes always have `@NoArgsConstructor(access = AccessLevel.PRIVATE)`

## Exception Hierarchy

**Split between checked and unchecked:**

| Type | Base class | Usage |
|------|------------|-------|
| Unchecked | `RuntimeException` | Domain state violations, not-found, validation errors |
| Checked | `Exception` | Toss API errors requiring explicit caller handling |

**Unchecked exception pattern:**
```java
@Getter
public class PaymentStatusException extends RuntimeException {
    private final String code;
    private final String message;

    private PaymentStatusException(PaymentErrorCode code) {
        this.code = code.getCode();
        this.message = code.getMessage();
    }

    public static PaymentStatusException of(PaymentErrorCode errorCode) {
        return new PaymentStatusException(errorCode);
    }
}
```

**Checked exception pattern (Toss API):**
```java
@Getter
public class PaymentTossNonRetryableException extends Exception {
    private final String code;
    private final String message;

    private PaymentTossNonRetryableException(PaymentErrorCode code) { ... }

    public static PaymentTossNonRetryableException of(PaymentErrorCode errorCode) {
        return new PaymentTossNonRetryableException(errorCode);
    }
}
```

**Pattern rules:**
- Constructor is always `private`, taking a single `PaymentErrorCode` parameter
- Static factory method `of(PaymentErrorCode)` — never call `new` from outside the class
- No cause chaining to `super()` — message is carried via `code`/`message` fields
- `PaymentTossRetryableException` and `PaymentTossNonRetryableException` are checked; callers must declare or catch

**Payment exception classes** (`src/main/java/com/hyoguoo/paymentplatform/payment/exception/`):
- `PaymentFoundException` (unchecked) — entity not found → HTTP 404
- `PaymentStatusException` (unchecked) — invalid state transition → HTTP 400
- `PaymentValidException` (unchecked) — field validation failure → HTTP 400
- `PaymentOrderedProductStockException` (unchecked) — insufficient stock → HTTP 400
- `PaymentTossRetryableException` (checked) — retryable Toss API error
- `PaymentTossNonRetryableException` (checked) — non-retryable Toss API error
- `PaymentTossConfirmException` (unchecked) — top-level confirm exception wrapper
- `PaymentRetryableValidateException` (unchecked) — retry validation failure
- `UnsupportedPaymentGatewayException` (unchecked) — unknown gateway

## Error Code Enums

All error codes implement the `ErrorCode` interface and live at `{module}.exception.common.{Module}ErrorCode`.

```java
@Getter
@RequiredArgsConstructor
public enum PaymentErrorCode implements ErrorCode {
    PAYMENT_EVENT_NOT_FOUND("E03001", "존재하지 않는 결제 이벤트입니다."),
    INVALID_STATUS_TO_EXECUTE("E03002", "결제 실행할 수 없는 상태입니다."),
    // ...
    private final String code;
    private final String message;
}
```

- Code format: `E{module_number}{3-digit_seq}` (e.g., `E03001`)
- Message text is in Korean
- `ErrorCode` interface provides `getCode()` and `getMessage()` contracts

## Exception Handler Pattern

Each module has its own `@RestControllerAdvice` handler at `{module}.exception.common.{Module}ExceptionHandler`.

```java
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
@RestControllerAdvice
public class PaymentExceptionHandler {

    @ExceptionHandler(PaymentFoundException.class)
    public ResponseEntity<ErrorResponse> catchRuntimeException(PaymentFoundException e) {
        LogFmt.warn(log, LogDomain.PAYMENT, EventType.EXCEPTION, e::getMessage);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(e.getCode(), e.getMessage()));
    }
    // one @ExceptionHandler method per exception type — no generic catch-all
}
```

- `@Order(Ordered.HIGHEST_PRECEDENCE)` overrides Spring's default handler
- Log with `LogFmt.warn` before returning the response — never call `log.warn(...)` directly
- One handler method per exception class
- HTTP status determined by semantic meaning; never a generic 500 for domain exceptions
- File: `src/main/java/com/hyoguoo/paymentplatform/payment/exception/common/PaymentExceptionHandler.java`

## ResponseAdvice Wrapping

`ResponseAdvice` at `src/main/java/com/hyoguoo/paymentplatform/core/response/ResponseAdvice.java` automatically wraps all controller responses:

```json
// Success
{ "data": <payload>, "error": null }

// Error
{ "data": null, "error": { "code": "E03001", "message": "존재하지 않는 결제 이벤트입니다." } }
```

Produced by `ResponseUtil.success(body)` and `ResponseUtil.error(ErrorResponse)` returning `BasicResponse<T>`.

Paths starting with `/actuator` or the Springdoc path are passed through unwrapped.

**Rule:** Controllers return plain result objects (e.g., `CheckoutResult`), not `BasicResponse`. The advice handles wrapping automatically.

## LogFmt Logging Pattern

Use the `LogFmt` static helper instead of calling `log.info(...)` directly.
File: `src/main/java/com/hyoguoo/paymentplatform/core/common/log/LogFmt.java`

```java
// With message (lazy Supplier — avoids string construction when disabled)
LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_CHECKOUT_START,
        () -> String.format("userId=%s", checkoutCommand.getUserId()));

// Without message
LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_CHECKOUT_END);

// For exceptions in handlers
LogFmt.warn(log, LogDomain.PAYMENT, EventType.EXCEPTION, e::getMessage);

// For infrastructure failures
LogFmt.error(log, LogDomain.PAYMENT, EventType.KAFKA_PUBLISH_FAIL, e::getMessage);
```

**Output format:** `[PAYMENT] | PAYMENT_CHECKOUT_START | userId=123`

**Log levels:**
- `LogFmt.info` — normal business flow
- `LogFmt.warn` — recoverable exceptions, unexpected-but-handled events
- `LogFmt.error` — fatal/infrastructure failures

**`LogDomain` values** (`src/main/java/com/hyoguoo/paymentplatform/core/common/log/LogDomain.java`):
`GLOBAL`, `PAYMENT`, `PAYMENT_GATEWAY`, `USER`, `PRODUCT`

**`EventType` values** (`src/main/java/com/hyoguoo/paymentplatform/core/common/log/EventType.java`):
All domain events are enumerated: `PAYMENT_CHECKOUT_START`, `PAYMENT_CONFIRM_SUCCESS`, `KAFKA_PUBLISH_FAIL`, `EXCEPTION`, etc.

**Rule:** Always pass message as `Supplier<String>` (lambda or method reference). Never pre-compute a string and pass it as a plain `String`.

**기동 배너 예외 조항 (T-F3, 2026-04-24):**
- 애플리케이션 기동 시 시각적 경고 배너(`╔...╗` 형식)는 `LogFmt.banner(Logger, Level, String... lines)` 로만 허용한다.
- 직접 `log.warn("╔...")` / `log.info("╔...")` 호출은 금지 — 반드시 `LogFmt.banner` 경유.
- 배너 레벨은 일반적으로 `Level.WARN` 사용 (prod 오염 경고 등 즉시 눈에 띄어야 할 경우).
- 예시:
  ```java
  LogFmt.banner(log, Level.WARN,
      "╔══════════════════════════════╗",
      "║  FAKE MODE ACTIVE — TEST ONLY ║",
      "╚══════════════════════════════╝"
  );
  ```

## Naming Conventions

**Files:**
- Application service implementations: `{Domain}ServiceImpl.java` implementing a port interface
- Use case classes: `{Domain}UseCase.java` annotated `@Service`, no interface
- Port interfaces (presentation-facing): `{Domain}Service.java` in `presentation.port`
- Port interfaces (application-facing): `{Domain}Repository.java` or `{Domain}Port.java` in `application.port`
- Exception handler: `{Module}ExceptionHandler.java`
- Error code enum: `{Module}ErrorCode.java`

**Classes:**
- Spring services: `PascalCase` + `Impl` suffix (e.g., `PaymentConfirmServiceImpl`)
- Use cases: `PascalCase` + `UseCase` suffix (e.g., `PaymentCreateUseCase`)
- Domain entities: `PascalCase`, no suffix (e.g., `PaymentEvent`, `PaymentOrder`)
- Test fakes: `Fake` prefix (e.g., `FakePaymentEventRepository`, `FakeTossOperator`)

**Methods:**
- camelCase throughout
- Domain state-change methods named after the target state: `execute()`, `done()`, `fail()`, `unknown()`, `expire()`
- Repository finders: `findByOrderId()`, `findDelayedInProgressOrUnknownEvents()`
- Repository writers: `saveOrUpdate()`, `saveAll()`
- Test SQL constants: `PAYMENT_EVENT_INSERT_SQL` (UPPER_SNAKE_CASE)

## Transaction Patterns

**Method-level at use-case layer (preferred):**
```java
@Transactional
public PaymentEvent executePayment(PaymentEvent paymentEvent, String paymentKey) { ... }

@Transactional(readOnly = true)
public PaymentEvent getPaymentEventByOrderId(String orderId) { ... }
```

**Explicit rollback for checked exceptions:**
```java
@Transactional(rollbackFor = PaymentOrderedProductStockException.class)
public PaymentEvent executePaymentAndStockDecreaseWithOutbox(
        PaymentEvent paymentEvent, String paymentKey,
        String orderId, List<PaymentOrder> paymentOrderList)
        throws PaymentOrderedProductStockException { ... }
```

**New transaction for independent outbox operations:**
```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
public PaymentOutbox markInFlight(String orderId) { ... }
```

**Domain event listener (before commit):**
```java
@TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
public void handle(...) { ... }
```

**Rules:**
- Place `@Transactional` at use-case method level, not on service orchestrators
- Service classes (e.g., `PaymentCheckoutServiceImpl`) delegate to transactional use-cases without being transactional themselves
- All read-only queries use `@Transactional(readOnly = true)`
- `rollbackFor` must be specified when calling methods that declare checked exceptions

## Import Organization

Static imports first, then external packages, then internal. No wildcard imports.

```java
// Static imports
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

// External framework imports
import com.fasterxml.jackson.databind.ObjectMapper;

// Internal imports
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;

// JDK imports
import java.math.BigDecimal;
import java.time.LocalDateTime;

// Test framework
import org.junit.jupiter.api.Test;
```

## Comments

- Business logic explanations in Korean: `// given: DB에 READY 상태의 PaymentEvent + PaymentOrder 준비`
- Code clarity in English: `// Use real PaymentFailureUseCase with mocked dependencies`
- Every test method uses `// given`, `// when`, `// then` comment sections
- `@DisplayName` always in Korean describing the business scenario

## 환경변수 네이밍 규약 (T3.5-04, 2026-04-24)

### Redis

| 용도 | compose 서비스명 | container_name | Spring 환경변수 | 프로퍼티 키 |
|------|----------------|----------------|-----------------|-------------|
| dedupe 키 (결제·PG·재고) | `redis-dedupe` | `payment-redis-dedupe` | `SPRING_DATA_REDIS_HOST` / `SPRING_DATA_REDIS_PORT` | `spring.data.redis.host/port` (Spring Boot autoconfigure) |
| 재고 캐시 (product-service 전용) | `redis-stock` | `payment-redis-stock` | `REDIS_STOCK_HOST` / `REDIS_STOCK_PORT` | `product.cache.stock-redis.host/port` |

- `SPRING_DATA_REDIS_*` 는 Spring Boot autoconfigure 표준 환경변수. 서비스별 `application-docker.yml`에서 `spring.data.redis.host/port`로 바인딩된다.
- Java `@Value("${REDIS_PAYMENT_HOST...}")` 형태의 구버전 환경변수 직접 참조는 금지. autoconfigure가 `spring.data.redis.*`를 읽으므로 `@Value`로 중복 선언하지 않는다.
- redis-stock 연결은 `StockRedisConfig`(`@ConditionalOnProperty(name = "product.cache.stock-redis.host")`)가 담당 — host 미설정 시 빈 미등록(테스트 safe).

### MySQL

| 서비스 | compose 서비스명 | container_name | 포트 | DB명 |
|--------|----------------|----------------|------|------|
| payment-service | `mysql-payment` | `payment-mysql-payment` | 3307 | `payment` |
| pg-service | `mysql-pg` | `payment-mysql-pg` | 3308 | `pg` |
| product-service | `mysql-product` | `payment-mysql-product` | 3309 | `product` |
| user-service | `mysql-user` | `payment-mysql-user` | 3310 | `user` |

- 컨테이너 이름 규칙: `mysql-<service>` (compose 서비스) / `payment-mysql-<service>` (container_name).
- DB명은 서비스명과 동일하게 유지.
- datasource URL 환경변수: `<SERVICE>_DATASOURCE_URL` (e.g., `PRODUCT_DATASOURCE_URL`).

---

## @ConditionalOnProperty 규약 (T3.5-02, 2026-04-24)

- **infra 레이어 @ConditionalOnProperty는 항상 `matchIfMissing=false`(기본값) 사용.** 환경변수가 명시되지 않으면 빈이 등록되지 않는다.
- Kafka consumer·Redis adapter 등 인프라 bean은 운영 환경에서 환경변수를 명시해야 활성화된다.
- 테스트 컨텍스트에서 Kafka listener 비기동은 `spring.kafka.listener.auto-startup=false`(`application-test.yml` 또는 `@TestPropertySource`)로 제어한다.
- `matchIfMissing=true` 사용이 허용되는 케이스: 전략 패턴의 기본 구현체(예: `TossPaymentGatewayStrategy(pg.gateway.type=toss)`, `OutboxImmediateEventHandler(payment.monolith.confirm.enabled)`). 이 경우 인라인 주석으로 이유를 명시한다.

---

---

## 관측성 규약 (T3.5-13, 2026-04-24)

- **Kafka producer/consumer observation-enabled 필수** — W3C Trace Context(traceparent/tracestate) 자동 주입/추출을 위해 `spring.kafka.template.observation-enabled=true` + `spring.kafka.listener.observation-enabled=true` 를 반드시 활성화한다. 수동 선언 KafkaTemplate 빈은 `template.setObservationEnabled(true)` 도 추가 필요 (YAML 만으로는 미반영).
- **Micrometer Tracing 기본값 (5 서비스 전부 동일)**: `management.tracing.sampling.probability=${TRACING_SAMPLING_PROBABILITY:0.0}` (로컬 기본 꺼짐, 환경변수로 토글 가능), `management.otlp.tracing.endpoint=${OTLP_TRACING_ENDPOINT:http://localhost:4318/v1/traces}`. docker 환경은 compose env(`TRACING_SAMPLING_PROBABILITY=1.0`, `OTLP_TRACING_ENDPOINT=http://tempo:4318/v1/traces`) 로 override. **user-service, gateway 도 반드시 포함** — 누락 시 chain 끊김.
- **traceId MDC 규약** — 5 서비스 `logback-spring.xml` 패턴에 `[traceId:%X{traceId:-N/A}]` 포함. Micrometer Tracing bridge가 OpenTelemetry context → MDC 자동 연결. 로그 라인 grep 으로 전 서비스 span 추적 가능.
- **신규 서비스 추가 시 체크리스트**: (1) `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp` 의존성, (2) `management.tracing.*` + `management.otlp.tracing.*` yml, (3) `logback-spring.xml` traceId 패턴, (4) Kafka 사용 시 `observation-enabled=true` + 수동 빈 `setObservationEnabled(true)`.

*Convention analysis: 2026-03-18*
