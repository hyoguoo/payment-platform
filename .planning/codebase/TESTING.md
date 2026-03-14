# Testing Patterns

## Framework & Tools

| Tool | Version | Purpose |
|------|---------|---------|
| JUnit 5 | (Spring Boot managed) | Test runner |
| Mockito | (Spring Boot managed) | Mocking |
| AssertJ | (Spring Boot managed) | Fluent assertions |
| Spring Boot Test | (Spring Boot managed) | Integration test context |
| Testcontainers (MySQL) | 1.19.x | Real DB for integration tests |
| JaCoCo | 0.8.11 | Code coverage |

## Test Structure

```
src/test/java/com/hyoguoo/paymentplatform/
├── IntegrationTest.java                  # Top-level integration base (MockMvc + SQL)
├── core/
│   └── test/
│       └── BaseIntegrationTest.java      # Testcontainers MySQL + SpringBootTest setup
├── mock/                                 # Fakes and test doubles
│   ├── FakePaymentEventRepository.java
│   ├── FakeProductRepository.java
│   ├── FakeUserRepository.java
│   ├── FakeTossHttpOperator.java
│   ├── FakeTossOperator.java
│   ├── AdditionalHeaderHttpOperator.java
│   └── TestLocalDateTimeProvider.java
├── mixin/                                # Jackson mixins for test deserialization
│   ├── BasicResponseMixin.java
│   ├── CheckoutResponseMixin.java
│   └── PaymentConfirmResponseMixin.java
└── payment/
    ├── application/                      # Unit tests for service layer (Mockito)
    │   ├── PaymentCheckoutServiceImplTest.java
    │   ├── PaymentConfirmServiceImplTest.java
    │   ├── PaymentExpirationServiceImplTest.java
    │   ├── PaymentRecoverServiceImplTest.java
    │   └── usecase/                      # Unit tests for use cases
    │       ├── OrderedProductUseCaseTest.java
    │       ├── OrderedUserUseCaseTest.java
    │       ├── PaymentCommandUseCaseTest.java
    │       ├── PaymentCreateUseCaseTest.java
    │       ├── PaymentLoadUseCaseTest.java
    │       ├── PaymentProcessUseCaseTest.java
    │       ├── PaymentRecoveryUseCaseTest.java
    │       └── PaymentTransactionCoordinatorTest.java
    ├── domain/                           # Domain unit tests (no mocks, pure logic)
    │   ├── PaymentEventTest.java
    │   ├── PaymentOrderTest.java
    │   └── PaymentProcessTest.java
    ├── infrastructure/
    │   └── gateway/
    │       └── PaymentGatewayFactoryTest.java
    ├── presentation/
    │   └── PaymentControllerTest.java    # Integration test (MockMvc + Testcontainers)
    └── scheduler/
        └── PaymentSchedulerTest.java
```

**Total test classes:** ~35 | **Estimated test methods:** ~240+

## Test Categories

### 1. Domain Unit Tests
Pure Java, no Spring context, no mocks.

```java
// payment/domain/PaymentEventTest.java
class PaymentEventTest {
    @Test
    @DisplayName("required Builder를 사용하여 객체를 생성 시 올바른 상태로 생성된다.")
    void testRequiredConstructor() { ... }

    @ParameterizedTest
    @EnumSource(value = PaymentEventStatus.class, names = {"READY", "IN_PROGRESS", "UNKNOWN"})
    void execute_Success(PaymentEventStatus paymentEventStatus) { ... }
}
```

Pattern: given-when-then structure, heavy use of `@ParameterizedTest` to cover all enum states.

### 2. Application/Use Case Unit Tests
Manual Mockito mocks, no Spring context.

```java
class PaymentConfirmServiceImplTest {
    @BeforeEach
    void setUp() {
        mockTransactionCoordinator = Mockito.mock(PaymentTransactionCoordinator.class);
        // ...
        paymentConfirmService = new PaymentConfirmServiceImpl(...);
    }

    @Test
    void testConfirm_Success() throws ... {
        // given
        when(mockPaymentLoadUseCase.getPaymentEventByOrderId(any())).thenReturn(...);
        // when
        PaymentConfirmResult result = paymentConfirmService.confirm(command);
        // then
        assertThat(result.getOrderId()).isEqualTo(...);
        verify(mockTransactionCoordinator, times(1)).executeStockDecreaseWithJobCreation(...);
    }
}
```

Pattern: real `PaymentFailureUseCase` with mocked dependencies; `TransactionTemplate` mock executes callbacks immediately.

### 3. Integration Tests
Full Spring context + Testcontainers MySQL.

```java
// BaseIntegrationTest.java
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
public abstract class BaseIntegrationTest {
    @Container
    protected static final MySQLContainer<?> MYSQL_CONTAINER =
        new MySQLContainer<>("mysql:8.0").withDatabaseName("payment-test")...;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) { ... }
}

// IntegrationTest.java
@AutoConfigureMockMvc
@Sql(scripts = "/data-test.sql")
public abstract class IntegrationTest extends BaseIntegrationTest { }
```

Test data initialized per-test with `@Sql("/data-test.sql")` and inline JDBC inserts.

**`PaymentControllerTest`** exercises the full HTTP→domain→DB flow using MockMvc and `FakeTossHttpOperator` injected via `@TestConfiguration`.

## Mocking Strategy

### Fake Implementations (preferred for ports)
```java
// FakePaymentEventRepository.java - in-memory List-backed repository
// FakeTossHttpOperator.java - returns predefined Toss API responses
// FakeTossOperator.java - gateway-level fake
// TestLocalDateTimeProvider.java - fixed clock for determinism
```

### Mockito (for service-layer unit tests)
Used when constructing service classes directly without Spring context. Mocks are created via `Mockito.mock(...)` in `@BeforeEach`, not via `@MockBean`.

### Jackson Mixins (for deserialization in tests)
Response DTOs without default constructors use Jackson `MixIn` annotations in tests:
```java
// BasicResponseMixin.java, CheckoutResponseMixin.java, PaymentConfirmResponseMixin.java
```

## Parameterized Test Patterns

```java
// EnumSource - test all valid/invalid state transitions
@ParameterizedTest
@EnumSource(value = PaymentEventStatus.class, names = {"READY", "IN_PROGRESS", "UNKNOWN"})
void execute_Success(PaymentEventStatus status) { ... }

// CsvSource - multiple field combinations
@ParameterizedTest
@CsvSource({
    "2, validPaymentKey, 15000, order123, INVALID_USER_ID",
    "1, invalidPaymentKey, 15000, order123, INVALID_PAYMENT_KEY",
})
void validate_InvalidCases(Long userId, String paymentKey, int amount, String orderId) { ... }

// MethodSource - complex argument sets
@ParameterizedTest
@MethodSource("provideRetryCountAndExpectedResult")
void isRetryableInProgress_RetryCount(int retryCount, boolean expectedResult) { ... }
```

## Test Naming Conventions

- Class: `{ClassUnderTest}Test`
- Method: `{methodName}_{scenario}` or descriptive Korean display name via `@DisplayName`
- `@DisplayName` uses Korean for human-readable test names

## Coverage Configuration

```xml
<!-- build.gradle / pom.xml → JaCoCo 0.8.11 -->
```

JaCoCo configured at 0.8.11. Specific coverage thresholds not observed in config.

## Key Test Fixtures

- `PaymentEventTest.defaultPaymentEvent()` — static factory for reusable `PaymentEvent`
- `PaymentConfirmServiceImplTest.getDefaultMockConfirmData()` — static helper returning `MockConfirmData` record
- Data SQL: `src/test/resources/data-test.sql` — initializes product/user/payment seed data

## Running Tests

```bash
./gradlew test                  # All tests
./gradlew test --tests "*.PaymentControllerTest"  # Single class
```

Integration tests require Docker (Testcontainers starts MySQL container automatically).