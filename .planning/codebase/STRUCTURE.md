# Directory Structure

## Root Layout

```
payment-platform/
├── src/
│   ├── main/
│   │   ├── java/com/hyoguoo/paymentplatform/
│   │   └── resources/
│   └── test/
│       ├── java/com/hyoguoo/paymentplatform/
│       └── resources/
├── build.gradle (or pom.xml)
├── .planning/
│   └── codebase/         # Codebase map documents
└── README.md
```

## Main Source Tree

```
src/main/java/com/hyoguoo/paymentplatform/
│
├── core/                          # Cross-cutting infrastructure
│   ├── common/
│   │   ├── aspect/                # AOP: domain event logging
│   │   │   └── annotation/        # @PublishDomainEvent, @Reason
│   │   ├── dto/                   # PageResponse, PageSpec, SortDirection
│   │   ├── exception/             # ErrorCode interface, GlobalErrorCode, GlobalExceptionHandler
│   │   ├── filter/                # TraceIdFilter (MDC trace ID)
│   │   ├── infrastructure/        # BaseEntity, SystemLocalDateTimeProvider, SystemUUIDProvider
│   │   │   └── http/              # HttpOperator interface + HttpOperatorImpl
│   │   ├── log/                   # LogFmt, LogDomain, EventType, MaskingPatternLayout
│   │   ├── metrics/               # Micrometer metric classes + AOP aspects
│   │   │   └── annotation/        # @TossApiMetric, @PaymentStatusChange, @ErrorCode
│   │   ├── service/port/          # LocalDateTimeProvider, UUIDProvider interfaces
│   │   └── util/                  # EncodeUtils
│   ├── config/                    # JpaConfig, QueryDslConfig, SchedulerConfig, WebConfig, MetricsConfig
│   └── response/                  # BasicResponse, ErrorResponse, ResponseAdvice, ResponseUtil
│
├── payment/                       # Core payment module
│   ├── application/
│   │   ├── dto/
│   │   │   ├── admin/             # Admin query/result DTOs
│   │   │   ├── request/           # Command objects (CheckoutCommand, PaymentConfirmCommand, ...)
│   │   │   ├── response/          # Result objects (CheckoutResult, PaymentConfirmResult)
│   │   │   └── vo/                # Value objects (OrderedProduct)
│   │   ├── port/                  # Outbound port interfaces (repositories, external)
│   │   ├── publisher/             # PaymentEventPublisher
│   │   ├── usecase/               # Fine-grained use case classes
│   │   ├── AdminPaymentServiceImpl.java
│   │   ├── PaymentCheckoutServiceImpl.java
│   │   ├── PaymentConfirmServiceImpl.java
│   │   ├── PaymentExpirationServiceImpl.java
│   │   ├── PaymentHistoryServiceImpl.java
│   │   └── PaymentRecoverServiceImpl.java
│   ├── domain/
│   │   ├── dto/                   # Domain DTOs (PaymentGatewayInfo, UserInfo, ProductInfo)
│   │   │   ├── enums/             # TossPaymentStatus, PaymentConfirmResultStatus
│   │   │   └── vo/                # PaymentDetails
│   │   ├── enums/                 # PaymentEventStatus, PaymentOrderStatus
│   │   ├── PaymentEvent.java      # Aggregate root
│   │   ├── PaymentOrder.java      # Order line
│   │   ├── PaymentProcess.java    # Processing job tracker
│   │   └── PaymentHistory.java    # Audit history
│   ├── exception/
│   │   └── common/                # PaymentErrorCode, PaymentExceptionHandler
│   ├── infrastructure/
│   │   ├── entity/                # PaymentEventEntity, PaymentOrderEntity, etc.
│   │   └── repository/            # JPA repos + QueryDSL impls + port adapters
│   ├── listener/                  # PaymentHistoryEventListener
│   │   └── port/                  # PaymentHistoryService interface
│   ├── presentation/
│   │   ├── dto/
│   │   │   ├── request/           # CheckoutRequest, PaymentConfirmRequest
│   │   │   └── response/          # CheckoutResponse, PaymentConfirmResponse
│   │   ├── port/                  # PaymentCheckoutService, PaymentConfirmService, AdminPaymentService
│   │   ├── PaymentController.java
│   │   ├── AdminPaymentController.java
│   │   └── PaymentPresentationMapper.java
│   └── scheduler/
│       ├── port/                  # PaymentExpirationService, PaymentRecoverService
│       └── PaymentScheduler.java
│
├── paymentgateway/                # Toss Payments gateway module
│   ├── application/
│   │   ├── dto/request/           # TossConfirmCommand, TossCancelCommand
│   │   ├── port/                  # TossOperator interface
│   │   ├── usecase/               # TossApiCallUseCase, TossApiFailureUseCase
│   │   └── PaymentGatewayServiceImpl.java
│   ├── domain/
│   │   ├── enums/                 # TossPaymentStatus, PaymentConfirmResultStatus
│   │   ├── vo/                    # TossPaymentDetails, TossPaymentFailure
│   │   └── TossPaymentInfo.java
│   ├── exception/
│   │   └── common/                # TossPaymentErrorCode, PaymentGatewayErrorCode, handler
│   ├── infrastructure/
│   │   ├── api/                   # HttpTossOperator (TossOperator impl)
│   │   ├── dto/response/          # TossPaymentApiResponse, TossPaymentApiFailResponse
│   │   └── PaymentGatewayInfrastructureMapper.java
│   └── presentation/
│       ├── dto/                   # TossConfirmRequest/Response, TossCancelRequest
│       ├── port/                  # PaymentGatewayService interface
│       ├── PaymentGatewayInternalReceiver.java
│       └── PaymentGatewayPresentationMapper.java
│
├── product/                       # Product module
│   ├── application/
│   │   ├── dto/                   # ProductStockCommand
│   │   ├── port/                  # ProductRepository interface
│   │   └── ProductServiceImpl.java
│   ├── domain/
│   │   └── Product.java
│   ├── exception/
│   │   └── common/                # ProductErrorCode, ProductExceptionHandler
│   ├── infrastructure/
│   │   ├── entity/                # ProductEntity
│   │   └── repository/            # JpaProductRepository, ProductRepositoryImpl
│   └── presentation/
│       ├── dto/                   # ProductInfoResponse, ProductStockRequest
│       ├── port/                  # ProductService interface
│       ├── ProductInternalReceiver.java
│       └── ProductPresentationMapper.java
│
└── user/                          # User module (minimal)
    ├── application/
    │   ├── port/                  # UserRepository interface
    │   └── UserServiceImpl.java
    ├── domain/
    │   └── User.java
    ├── exception/
    │   └── common/                # UserErrorCode, UserExceptionHandler
    ├── infrastructure/
    │   └── repository/            # JpaUserRepository, UserRepositoryImpl
    └── presentation/
        ├── dto/                   # UserInfoResponse
        ├── port/                  # UserService interface
        ├── UserInternalReceiver.java
        └── UserPresentationMapper.java
```

## Test Source Tree

```
src/test/java/com/hyoguoo/paymentplatform/
├── IntegrationTest.java           # MockMvc + @Sql base class
├── core/test/
│   └── BaseIntegrationTest.java   # @SpringBootTest + Testcontainers MySQL
├── mock/                          # Fake implementations for tests
├── mixin/                         # Jackson mixins for test deserialization
└── payment/                       # Unit + integration tests mirroring main structure
```

## Key File Locations

| Purpose | Path |
|---------|------|
| App entry point | `src/main/.../PaymentPlatformApplication.java` |
| Payment aggregate root | `payment/domain/PaymentEvent.java` |
| Confirm flow orchestration | `payment/application/PaymentConfirmServiceImpl.java` |
| Transaction coordinator | `payment/application/usecase/PaymentTransactionCoordinator.java` |
| Toss error codes | `paymentgateway/exception/common/TossPaymentErrorCode.java` |
| Scheduler jobs | `payment/scheduler/PaymentScheduler.java` |
| Global exception handler | `core/common/exception/GlobalExceptionHandler.java` |
| Structured logging | `core/common/log/LogFmt.java` |
| Metrics aspects | `core/common/metrics/aspect/` |
| Test data SQL | `src/test/resources/data-test.sql` |

## Naming Conventions

| Pattern | Example |
|---------|---------|
| Service impl | `{Domain}ServiceImpl` | `PaymentConfirmServiceImpl` |
| Service port (interface) | `{Domain}Service` | `PaymentConfirmService` |
| Use case class | `{Domain}UseCase` | `PaymentCommandUseCase` |
| Repository port | `{Domain}Repository` | `PaymentEventRepository` |
| JPA repository | `Jpa{Domain}Repository` | `JpaPaymentEventRepository` |
| Repository impl | `{Domain}RepositoryImpl` | `ProductRepositoryImpl` |
| JPA entity | `{Domain}Entity` | `PaymentEventEntity` |
| Presentation mapper | `{Module}PresentationMapper` | `PaymentPresentationMapper` |
| Internal controller | `{Domain}InternalReceiver` | `ProductInternalReceiver` |
| Request DTO | `{Action}Request` / `{Action}Command` | `CheckoutRequest`, `CheckoutCommand` |
| Response DTO | `{Action}Response` / `{Action}Result` | `CheckoutResponse`, `CheckoutResult` |
