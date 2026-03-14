# Coding Conventions

**Analysis Date:** 2026-03-14

## Naming Patterns

**Files:**
- Classes use PascalCase matching their primary purpose: `PaymentEvent.java`, `PaymentCreateUseCase.java`, `PaymentController.java`
- Test files use Test suffix: `PaymentCreateUseCaseTest.java`, `PaymentEventTest.java`
- Helper/utility classes use descriptive names: `ResponseUtil.java`, `LogFmt.java`

**Functions/Methods:**
- camelCase for all method names
- Verb-based names for action methods: `execute()`, `validateCompletionStatus()`, `saveNewPaymentEvent()`
- Getter/setter methods generated via Lombok: `getName()`, `setStatus()`
- Static factory methods use `create()` or `of()` pattern: `PaymentEvent.create()`, `ResponseUtil.success()`

**Variables:**
- camelCase for all variable names
- Descriptive names reflecting purpose and type: `paymentEventRepository`, `mockPaymentEventRepository`, `orderedProductList`
- Mock variables prefixed with "mock" in tests: `mockPaymentEventRepository`, `mockTransactionCoordinator`
- Constants use UPPER_SNAKE_CASE: `EXPIRATION_MINUTES`, `RETRYABLE_LIMIT`, `PAYMENT_EVENT_INSERT_SQL`

**Types/Classes:**
- Domain entities: `PaymentEvent`, `PaymentOrder`, `User`, `Product`
- Use cases: `PaymentCreateUseCase`, `PaymentCommandUseCase` (Service pattern)
- Repositories: `PaymentEventRepository` (interface), `PaymentEventRepositoryImpl` (implementation)
- DTOs: `PaymentConfirmCommand`, `CheckoutResult`, `PaymentConfirmResponse`
- Exception classes: `PaymentStatusException`, `PaymentValidException`, `PaymentTossConfirmException`

## Code Style

**Formatting:**
- Java 21 target (configured in `build.gradle`)
- 4-space indentation (default IDE formatting)
- Imports organized by package hierarchy
- Lombok annotations used extensively to reduce boilerplate

**Linting:**
- Build managed through Gradle
- No explicit linting configuration (uses IDE defaults with Spring Boot conventions)
- Project uses Google Java Style conventions (implicit from Spring community standards)

## Import Organization

**Order:**
1. Static imports: `import static org.assertj.core.api.Assertions.assertThat;`
2. Java core packages: `import java.time.LocalDateTime;`, `import java.util.List;`
3. Third-party packages: `import org.springframework.stereotype.Service;`, `import lombok.RequiredArgsConstructor;`
4. Project packages: `import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;`

**Path Aliases:**
- Package structure mirrors business domain: `com.hyoguoo.paymentplatform.payment.domain`, `com.hyoguoo.paymentplatform.payment.application`, `com.hyoguoo.paymentplatform.payment.presentation`
- No IDE path aliases configured; full package paths used throughout

## Error Handling

**Patterns:**
- Custom domain exceptions extend base exception type or runtime exception
- Exception classes: `PaymentStatusException`, `PaymentValidException`, `PaymentTossNonRetryableException`, `PaymentTossRetryableException`
- Exceptions created via static factory: `PaymentStatusException.of(PaymentErrorCode.INVALID_STATUS)`
- Domain objects validate state transitions and throw exceptions: `if (this.status != PaymentEventStatus.READY) throw PaymentStatusException.of(...)`
- Exception handling via `@ExceptionHandler` methods in `PaymentExceptionHandler.java` mapping to HTTP responses
- Exceptions include error codes for categorization: `PaymentErrorCode` enum with specific codes

**Example from `PaymentEvent.java` (lines 69-79):**
```java
public void execute(String paymentKey, LocalDateTime executedAt, LocalDateTime lastStatusChangedAt) {
    if (this.status != PaymentEventStatus.READY &&
            this.status != PaymentEventStatus.IN_PROGRESS &&
            this.status != PaymentEventStatus.UNKNOWN) {
        throw PaymentStatusException.of(PaymentErrorCode.INVALID_STATUS_TO_EXECUTE);
    }
    paymentOrderList.forEach(PaymentOrder::execute);
    this.paymentKey = paymentKey;
    this.status = PaymentEventStatus.IN_PROGRESS;
    this.executedAt = executedAt;
    this.lastStatusChangedAt = lastStatusChangedAt;
}
```

## Logging

**Framework:** SLF4J with Logback backend (Spring Boot standard)

**Patterns:**
- Use `LogFmt` helper class in `src/main/java/com/hyoguoo/paymentplatform/core/common/log/LogFmt.java` for structured logging
- Log levels: `info()`, `warn()`, `error()` methods with `LogDomain` and `EventType` enums
- Logger checks before logging: `if (logger.isInfoEnabled()) { logger.info(...); }` to avoid string construction overhead
- Logging includes domain context and event type: `LogFmt.warn(log, LogDomain.PAYMENT, EventType.EXCEPTION, e::getMessage);`
- Domain and event types defined as enums: `LogDomain.PAYMENT`, `EventType.EXCEPTION`

**Example from `PaymentExceptionHandler.java` (lines 29-37):**
```java
@ExceptionHandler(PaymentFoundException.class)
public ResponseEntity<ErrorResponse> catchRuntimeException(PaymentFoundException e) {
    LogFmt.warn(log, LogDomain.PAYMENT, EventType.EXCEPTION, e::getMessage);

    return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse.of(
                            e.getCode(),
                            e.getMessage()
                    )
            );
}
```

## Comments

**When to Comment:**
- Comments rare; code is self-documenting through clear naming
- One TODO found in codebase: `/src/main/java/com/hyoguoo/paymentplatform/paymentgateway/infrastructure/api/HttpTossOperator.java` - "파싱 방법 개선 필요" (need to improve parsing method)
- Comments used only for non-obvious algorithm explanations

**JSDoc/JavaDoc:**
- Not extensively used; method names and signatures are self-explanatory
- No mandatory JavaDoc requirement observed in codebase
- When needed, method names and parameter names provide sufficient context

## Function Design

**Size:**
- Methods typically range 10-40 lines
- Private methods extract logic and keep public methods focused
- Example: `PaymentCreateUseCase.java` breaks creation into `saveNewPaymentEvent()`, `saveNewPaymentOrderList()`, `findMatchingOrderedProduct()` private methods

**Parameters:**
- Favor objects over primitive parameters: `PaymentEvent` instead of multiple Long/String parameters
- Use builder pattern for complex object creation: `PaymentEvent.allArgsBuilder().buyerId(...).status(...).allArgsBuild()`
- Command/Request objects for API input: `CheckoutCommand`, `PaymentConfirmCommand`

**Return Values:**
- Methods return complete domain objects or value objects
- Never return null; use Optional or throw exception if value not found
- Example: `findMatchingOrderedProduct()` uses `.findFirst().orElseThrow()` pattern

## Module Design

**Exports:**
- Packages export interfaces for contracts: `PaymentEventRepository` is interface, `PaymentEventRepositoryImpl` is implementation detail
- Port pattern used for dependencies: `PaymentCheckoutService`, `PaymentConfirmService` defined as ports (interfaces)

**Barrel Files:**
- No barrel files (index.ts equivalents) used
- Direct imports from specific classes: `import PaymentEvent` rather than `import * from package`
- Package structure groups related functionality: `payment.application`, `payment.domain`, `payment.infrastructure`, `payment.presentation`

## Dependency Injection

**Pattern:** Constructor injection with Lombok `@RequiredArgsConstructor`

**Example from `PaymentCreateUseCase.java` (lines 18-25):**
```java
@Service
@RequiredArgsConstructor
public class PaymentCreateUseCase {

    private final PaymentEventRepository paymentEventRepository;
    private final PaymentOrderRepository paymentOrderRepository;
    private final UUIDProvider uuidProvider;
    private final LocalDateTimeProvider localDateTimeProvider;
```

All dependencies marked `private final`, constructor generated by Lombok.

## Builder Pattern Usage

**Custom Builder Configuration:**
- Domain entities use custom builder method names to avoid conflicts: `allArgsBuilder()` and `allArgsBuild()` instead of default `builder()` and `build()`
- Example from `PaymentEvent.java` (line 22): `@Builder(builderMethodName = "allArgsBuilder", buildMethodName = "allArgsBuild")`
- Allows multiple static factory methods: `create()` method uses builder internally

## Lombok Usage

**Annotations Applied:**
- `@Getter`: On domain entities for field access
- `@Builder`: On domain entities with custom builder method names
- `@AllArgsConstructor(access = AccessLevel.PRIVATE)`: Force construction through builder
- `@RequiredArgsConstructor`: On service classes for dependency injection
- `@NoArgsConstructor(access = AccessLevel.PRIVATE)`: On utility classes like `ResponseUtil.java`, `LogFmt.java`
- `@Slf4j`: On classes that log (Spring Boot standard)

---

*Convention analysis: 2026-03-14*
