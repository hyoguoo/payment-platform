---
name: review
description: >
  Perform a structured code review on uncommitted changes in this payment-platform project.
  Trigger this skill whenever the user asks to "review", "리뷰", "코드 리뷰", or "check my changes".
  Even if the request seems casual ("looks good?", "뭐 문제 없어?"), use this skill to run a
  thorough, structured review across architecture, conventions, tests, and payment-specific risks.
---

## Architecture

This project uses **Hexagonal Architecture**. The allowed dependency direction is:

```
Presentation → Application → Domain ← Infrastructure
```

Check for:
- **Layer violations** — Infrastructure classes imported in Domain; Domain importing Application; Presentation calling use cases directly instead of through port interfaces
- **Port placement** — Ports (interfaces) live in `application/port/` or `presentation/port/`. Implementations live in `infrastructure/` or `application/`
- **Cross-module imports** — Modules (`payment`, `paymentgateway`, `product`, `user`) must communicate only through port interfaces or internal HTTP receivers (`*InternalReceiver`), never by direct import of another module's implementation classes

## Conventions

### Lombok
- **Use** `@RequiredArgsConstructor` on service/use-case classes for constructor injection
- **Never use** `@Data` on domain entities — use `@Getter` only; mutation goes through domain methods
- Domain entities need `@AllArgsConstructor(access = AccessLevel.PRIVATE)` + `@Builder(builderMethodName = "allArgsBuilder", buildMethodName = "allArgsBuild")` so construction is forced through the `create()` static factory
- Utility/helper classes (stateless) use `@NoArgsConstructor(access = AccessLevel.PRIVATE)`

### Naming
- Static factory methods: `create()` or `of()` — not constructors
- Exceptions created via static factory: `PaymentStatusException.of(PaymentErrorCode.XYZ)` — never `new PaymentStatusException(...)`
- Test mock variables prefixed with `mock`: `mockPaymentEventRepository`
- Constants in `UPPER_SNAKE_CASE`

### Exception handling
- No bare `catch (Exception e)` unless it is the final catch-all in a confirm/compensation flow and explicitly routed to `handleUnknownFailure`
- Domain objects validate state and throw typed domain exceptions; service layer does not swallow them

### Return values
- Methods must not return `null` — use `Optional` or throw a typed exception (`orElseThrow()` pattern)

### Logging
- Always use `LogFmt` helper — **never** raw `log.info("...")` string interpolation
- Pattern: `LogFmt.warn(log, LogDomain.PAYMENT, EventType.EXCEPTION, e::getMessage);`
- Wrap log calls in level checks: `if (logger.isInfoEnabled()) { ... }` to avoid unnecessary string construction

## Tests

### Which test style for which layer

| Layer | Style | Rule |
|---|---|---|
| Domain | Pure Java, no mocks | Test all state transitions with `@ParameterizedTest @EnumSource` |
| Application / UseCase | Mockito mocks in `@BeforeEach` (no `@MockBean`) | Use real collaborators where possible; mock only ports |
| Integration | Testcontainers MySQL + MockMvc | Use `FakeX` implementations for external ports (Toss, product, user) |

### Fake vs Mock policy
- Port interfaces (repositories, external gateways) get **Fake implementations** in `src/test/.../mock/` (e.g., `FakePaymentEventRepository`, `FakeTossOperator`)
- Mockito is acceptable for service-layer unit tests where constructing a Fake would require significant setup
- Never introduce `@MockBean` for repository interfaces — it bypasses the Fake strategy and is slower

### Naming
- Class: `{ClassUnderTest}Test`
- Method: `{methodName}_{scenario}` (e.g., `execute_Success`, `confirm_InvalidPaymentKey_ThrowsException`) **or** Korean display name via `@DisplayName`
- Both styles are valid; do not mix them in the same class

### Coverage
- Every new public method on a domain entity needs a corresponding test covering valid and invalid state transitions
- New use-case branches must have at least one unit test

## Risks (payment-specific)

1. **Compensation idempotency** — `increaseStockForOrders` and `decreaseStockForOrders` must be guarded against double execution. Check for existence validation before state mutation in compensation paths.

2. **Race conditions** — `executeStockDecreaseWithJobCreation` uses pessimistic locking, but `executePayment` (READY → IN_PROGRESS) is a separate transaction. Any new code that adds a step between these two transactions increases the window for a race.

3. **PII / secret in logs** — `paymentKey` and `orderId` must not appear in plain log messages. Verify new log statements do not expose these. Rely on `MaskingPatternLayout` only as a safety net — do not log sensitive fields intentionally.

4. **State machine violations** — PaymentEvent transitions must follow:
   ```
   READY → IN_PROGRESS → DONE
                 ↓
              FAILED
                 ↓
              UNKNOWN → (retry) → IN_PROGRESS
   READY → EXPIRED
   ```
   Any new transition must be validated in the domain entity method and covered by `@ParameterizedTest @EnumSource`.

5. **`existsByOrderId` guard** — Idempotent write operations (e.g., creating a payment event) must check for an existing record before inserting to avoid duplicates.

6. **`ALREADY_PROCESSED_PAYMENT` shortcut** — Code that shortcuts on this Toss error code should verify the local DB state matches Toss's state, not just accept it as success blindly.

7. **Broad `Exception` catch** — New catch-all blocks must explicitly route to `handleUnknownFailure`, not silently swallow errors. Flag any broad catch that does something other than that.

## Output Format

For each finding:

```
[CRITICAL] path/to/File.java:42 — One-sentence description
  Why: Brief explanation of impact
  Fix: Concrete suggestion

[WARNING] path/to/File.java:15 — One-sentence description
  Why: ...
  Fix: ...

[INFO] path/to/File.java:8 — Observation (no action required)
```

## Severity Definitions

- **CRITICAL** — Will break functionality, violates architecture contract, or creates data corruption / security risk
- **WARNING** — Degrades quality, deviates from conventions, or introduces tech debt
- **INFO** — Style note or observation for awareness; no action required

## Closing Summary

End with:

```
---
Summary: X CRITICAL, Y WARNING, Z INFO
Verdict: PASS (0 critical) | FAIL (N critical issues must be resolved)
```
