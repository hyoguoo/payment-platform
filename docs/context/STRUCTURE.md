# Codebase Structure

**Analysis Date:** 2026-04-05

## Directory Layout

```
payment-platform/
├── src/main/java/com/hyoguoo/paymentplatform/
│   ├── PaymentPlatformApplication.java          # Spring Boot entry point
│   ├── core/
│   │   ├── common/
│   │   │   ├── aspect/                          # @PublishDomainEvent AOP
│   │   │   │   └── annotation/                  # @PublishDomainEvent, @Reason
│   │   │   ├── dto/                             # PageResponse, PageSpec, SortDirection
│   │   │   ├── exception/                       # ErrorCode, GlobalErrorCode, GlobalExceptionHandler
│   │   │   ├── filter/                          # TraceIdFilter
│   │   │   ├── infrastructure/
│   │   │   │   └── http/                        # HttpOperator, HttpOperatorImpl
│   │   │   ├── log/                             # LogFmt, LogDomain, EventType, MaskingPatternLayout
│   │   │   ├── metrics/                         # Micrometer metric beans
│   │   │   │   ├── annotation/                  # @PaymentStatusChange, @TossApiMetric, @ErrorCode
│   │   │   │   └── aspect/                      # PaymentStatusMetricsAspect, TossApiMetricsAspect
│   │   │   ├── service/port/                    # LocalDateTimeProvider, UUIDProvider
│   │   │   └── util/                            # EncodeUtils
│   │   ├── config/                              # JpaConfig, MetricsConfig, QueryDslConfig, SchedulerConfig, WebConfig
│   │   └── response/                            # BasicResponse, ErrorResponse, ResponseAdvice, ResponseUtil
│   ├── mock/                                    # FakeTossHttpOperator, BenchmarkConfig (@Profile("benchmark"))
│   ├── payment/                                 # Primary bounded context
│   │   ├── application/
│   │   │   ├── OutboxAsyncConfirmService.java   # confirm service implementation (outbox 전략)
│   │   │   ├── IdempotencyKeyHasher.java        # idempotency key hashing helper
│   │   │   ├── PaymentStatusServiceImpl.java    # always active
│   │   │   ├── PaymentCheckoutServiceImpl.java
│   │   │   ├── AdminPaymentServiceImpl.java
│   │   │   ├── PaymentExpirationServiceImpl.java
│   │   │   ├── PaymentHistoryServiceImpl.java
│   │   │   ├── dto/
│   │   │   │   ├── admin/                       # PaymentEventResult, PaymentHistoryResult, PaymentOrderResult, search queries
│   │   │   │   ├── request/                     # CheckoutCommand, PaymentConfirmCommand, TossConfirmGatewayCommand, etc.
│   │   │   │   ├── response/                    # PaymentConfirmAsyncResult, PaymentConfirmResult, CheckoutResult, PaymentStatusResult
│   │   │   │   └── vo/                          # OrderedProduct
│   │   │   ├── port/                            # outbound port interfaces
│   │   │   │   ├── PaymentEventRepository.java
│   │   │   │   ├── PaymentGatewayPort.java
│   │   │   │   ├── PaymentOrderRepository.java
│   │   │   │   ├── PaymentOutboxRepository.java
│   │   │   │   ├── PaymentHistoryRepository.java
│   │   │   │   ├── IdempotencyStore.java        # idempotency 저장소 포트
│   │   │   │   ├── ProductPort.java
│   │   │   │   ├── UserPort.java
│   │   │   │   └── AdminPaymentQueryRepository.java
│   │   │   ├── publisher/
│   │   │   │   └── PaymentEventPublisher.java   # Spring ApplicationEventPublisher wrapper
│   │   │   └── usecase/                         # internal application services (not ports)
│   │   │       ├── PaymentTransactionCoordinator.java   # all @Transactional boundaries
│   │   │       ├── PaymentCommandUseCase.java   # status-change operations
│   │   │       ├── PaymentLoadUseCase.java
│   │   │       ├── PaymentOutboxUseCase.java
│   │   │       ├── PaymentHistoryUseCase.java
│   │   │       ├── AdminPaymentLoadUseCase.java
│   │   │       ├── PaymentFailureUseCase.java
│   │   │       ├── PaymentCreateUseCase.java
│   │   │       ├── OrderedProductUseCase.java
│   │   │       └── OrderedUserUseCase.java
│   │   ├── domain/
│   │   │   ├── PaymentEvent.java                # primary aggregate
│   │   │   ├── PaymentOrder.java
│   │   │   ├── PaymentOutbox.java               # outbox strategy domain object
│   │   │   ├── PaymentHistory.java
│   │   │   ├── dto/                             # cross-layer DTOs (records)
│   │   │   │   ├── enums/                       # PaymentStatus, TossPaymentStatus, PaymentConfirmResultStatus, etc.
│   │   │   │   └── vo/                          # PaymentDetails, PaymentFailure
│   │   │   ├── enums/                           # PaymentEventStatus, PaymentOrderStatus, PaymentOutboxStatus
│   │   │   └── event/                           # PaymentCreatedEvent, PaymentStatusChangedEvent, PaymentRetryAttemptedEvent, PaymentHistoryEvent
│   │   ├── exception/
│   │   │   ├── PaymentStatusException.java
│   │   │   ├── PaymentValidException.java
│   │   │   ├── PaymentTossRetryableException.java
│   │   │   ├── PaymentTossNonRetryableException.java
│   │   │   ├── PaymentTossConfirmException.java
│   │   │   ├── PaymentOrderedProductStockException.java
│   │   │   ├── PaymentFoundException.java
│   │   │   ├── PaymentHistoryException.java
│   │   │   ├── PaymentRetryableValidateException.java
│   │   │   ├── UnsupportedPaymentGatewayException.java
│   │   │   └── common/
│   │   │       ├── PaymentErrorCode.java
│   │   │       └── PaymentExceptionHandler.java
│   │   ├── infrastructure/
│   │   │   ├── PaymentInfrastructureMapper.java
│   │   │   ├── entity/                          # JPA entities (PaymentEventEntity, etc.)
│   │   │   ├── gateway/
│   │   │   │   ├── PaymentGatewayFactory.java
│   │   │   │   ├── PaymentGatewayProperties.java
│   │   │   │   ├── PaymentGatewayStrategy.java  # interface
│   │   │   │   ├── PaymentGatewayType.java
│   │   │   │   └── toss/
│   │   │   │       └── TossPaymentGatewayStrategy.java
│   │   │   ├── idempotency/
│   │   │   │   ├── IdempotencyStoreImpl.java    # implements IdempotencyStore
│   │   │   │   └── IdempotencyProperties.java   # idempotency 설정 프로퍼티
│   │   │   ├── internal/                        # cross-context adapters
│   │   │   │   ├── InternalPaymentGatewayAdapter.java  # implements PaymentGatewayPort
│   │   │   │   ├── InternalProductAdapter.java         # implements ProductPort
│   │   │   │   └── InternalUserAdapter.java            # implements UserPort
│   │   │   ├── publisher/
│   │   │   │   └── OutboxImmediatePublisher.java  # outbox 즉시 발행 구현체
│   │   │   └── repository/
│   │   │       ├── JpaPaymentEventRepository.java
│   │   │       ├── JpaPaymentOrderRepository.java
│   │   │       ├── JpaPaymentOutboxRepository.java
│   │   │       ├── JpaPaymentHistoryRepository.java
│   │   │       ├── PaymentEventRepositoryImpl.java
│   │   │       ├── PaymentOutboxRepositoryImpl.java
│   │   │       ├── PaymentHistoryRepositoryImpl.java
│   │   │       ├── PaymentOrderRepositoryImpl.java
│   │   │       └── AdminPaymentQueryRepositoryImpl.java
│   │   ├── listener/
│   │   │   ├── OutboxImmediateEventHandler.java # @TransactionalEventListener(AFTER_COMMIT) — channel.offer 호출
│   │   │   ├── PaymentHistoryEventListener.java # Spring ApplicationEvent handler
│   │   │   └── port/
│   │   │       └── PaymentHistoryService.java
│   │   ├── presentation/
│   │   │   ├── PaymentController.java           # POST /api/v1/payments/confirm, /checkout; GET /status
│   │   │   ├── PaymentAdminController.java
│   │   │   ├── PaymentPresentationMapper.java
│   │   │   ├── dto/
│   │   │   │   ├── request/                     # CheckoutRequest, PaymentConfirmRequest, PaymentCancelRequest
│   │   │   │   └── response/                    # PaymentConfirmResponse, PaymentStatusResponse, PaymentStatusApiResponse, etc.
│   │   │   └── port/                            # inbound port interfaces
│   │   │       ├── PaymentConfirmService.java   # implemented by OutboxAsyncConfirmService
│   │   │       ├── PaymentStatusService.java    # implemented by PaymentStatusServiceImpl
│   │   │       ├── PaymentCheckoutService.java
│   │   │       └── AdminPaymentService.java
│   │   └── scheduler/
│   │       ├── OutboxImmediateWorker.java       # SmartLifecycle — VT/PT 워커 스레드; channel.take()
│   │       ├── OutboxProcessingService.java     # ImmediateWorker/OutboxWorker 공유 처리 로직
│   │       ├── OutboxWorker.java                # @Scheduled 폴백 outbox processor
│   │       ├── PaymentScheduler.java            # @Scheduled expiration
│   │       └── port/
│   │           └── PaymentExpirationService.java
│   ├── paymentgateway/                          # Toss Payments gateway context
│   │   ├── application/
│   │   │   ├── PaymentGatewayServiceImpl.java
│   │   │   ├── dto/request/                     # TossConfirmCommand, TossCancelCommand
│   │   │   ├── port/
│   │   │   │   └── TossOperator.java
│   │   │   └── usecase/                         # TossApiCallUseCase, TossApiFailureUseCase
│   │   ├── domain/
│   │   │   ├── TossPaymentInfo.java
│   │   │   ├── enums/                           # PaymentConfirmResultStatus, TossPaymentStatus
│   │   │   └── vo/                              # TossPaymentDetails, TossPaymentFailure
│   │   ├── exception/
│   │   │   ├── PaymentGatewayApiException.java
│   │   │   └── common/                          # PaymentGatewayErrorCode, PaymentGatewayExceptionHandler, TossPaymentErrorCode
│   │   ├── infrastructure/
│   │   │   ├── PaymentGatewayInfrastructureMapper.java
│   │   │   ├── api/
│   │   │   │   └── HttpTossOperator.java        # implements TossOperator
│   │   │   └── dto/response/                    # TossPaymentApiResponse, TossPaymentApiFailResponse
│   │   └── presentation/
│   │       ├── PaymentGatewayInternalReceiver.java   # internal Java facade (not a public HTTP endpoint)
│   │       ├── PaymentGatewayPresentationMapper.java
│   │       ├── dto/request/                     # TossConfirmRequest, TossCancelRequest
│   │       ├── dto/response/                    # TossPaymentResponse
│   │       └── port/
│   │           └── PaymentGatewayService.java
│   ├── product/                                 # Product / stock context
│   │   ├── application/
│   │   │   ├── ProductServiceImpl.java
│   │   │   ├── dto/                             # ProductStockCommand
│   │   │   └── port/
│   │   │       └── ProductRepository.java
│   │   ├── domain/
│   │   │   └── Product.java
│   │   ├── exception/                           # ProductFoundException, ProductStockException
│   │   │   └── common/                          # ProductErrorCode, ProductExceptionHandler
│   │   ├── infrastructure/
│   │   │   ├── entity/                          # ProductEntity
│   │   │   └── repository/                      # JpaProductRepository, ProductRepositoryImpl
│   │   └── presentation/
│   │       ├── ProductInternalReceiver.java     # internal Java facade
│   │       ├── ProductPresentationMapper.java
│   │       ├── dto/                             # ProductInfoResponse, ProductStockRequest
│   │       └── port/
│   │           └── ProductService.java
│   └── user/                                    # User context
│       ├── application/
│       │   ├── UserServiceImpl.java
│       │   └── port/
│       │       └── UserRepository.java
│       ├── domain/
│       │   └── User.java
│       ├── exception/                           # UserFoundException
│       │   └── common/                          # UserErrorCode, UserExceptionHandler
│       ├── infrastructure/
│       │   ├── entity/                          # UserEntity
│       │   └── repository/                      # JpaUserRepository, UserRepositoryImpl
│       └── presentation/
│           ├── UserInternalReceiver.java        # internal Java facade
│           ├── UserPresentationMapper.java
│           ├── dto/                             # UserInfoResponse
│           └── port/
│               └── UserService.java
├── src/main/resources/
│   ├── application.yml                          # default config (outbox 전략, JPA settings)
│   ├── application-benchmark.yml               # benchmark profile overrides
│   ├── application-docker.yml                  # docker profile overrides
│   ├── data.sql                                # seed data
│   ├── logback-spring.xml
│   └── templates/admin/                        # Thymeleaf admin UI templates
└── src/test/java/com/hyoguoo/paymentplatform/
    ├── core/test/                               # shared test utilities
    ├── mixin/                                   # Jackson mixin helpers
    ├── mock/                                    # test fakes (FakePaymentEventRepository, FakeIdempotencyStore, etc.)
    └── payment/
        ├── application/                         # unit tests for application services
        │   ├── dto/response/                    # DTO unit tests
        │   ├── IdempotencyKeyHasherTest.java    # IdempotencyKeyHasher 단위 테스트
        │   └── usecase/                         # use-case unit tests
        ├── domain/                              # domain entity unit tests
        ├── infrastructure/
        │   ├── gateway/                         # TossPaymentGatewayStrategy tests
        │   └── publisher/                       # OutboxImmediatePublisher tests
        ├── listener/                            # PaymentHistoryEventListener tests
        ├── presentation/                        # PaymentController slice tests (extends BaseIntegrationTest)
        └── scheduler/                           # OutboxWorker tests
```

---

## Directory Purposes

**`payment/application/`:**
- All application-level service beans live here, including the confirm service implementation (outbox 단일 전략)
- Use-case sub-services are in `usecase/`; port interfaces in `port/`

**`payment/application/usecase/`:**
- Internal collaborators, not exposed as ports
- `PaymentTransactionCoordinator` is the only place where `@Transactional` coordinates multiple use cases

**`payment/infrastructure/internal/`:**
- Adapters that cross context boundaries by calling into another context's `presentation/port` interface
- No HTTP wire calls — direct Spring bean method calls

**`payment/infrastructure/idempotency/`:**
- `IdempotencyStoreImpl` implements the `IdempotencyStore` port using Redis or in-memory store
- `IdempotencyProperties` holds related configuration properties

**`payment/infrastructure/publisher/`:**
- `OutboxImmediatePublisher` implements outbox 즉시 발행 로직 (outbox 레코드 생성 후 즉시 처리)

**`payment/listener/`:**
- Spring event listener (`PaymentHistoryEventListener`) for domain event handling
- Infrastructure-adjacent but placed in its own package due to its cross-cutting driver role

**`mock/`:**
- `@Profile("benchmark")` only; activates `FakeTossHttpOperator` so k6 tests run without real Toss API

**`core/`:**
- Shared cross-cutting infrastructure not belonging to any bounded context: logging, metrics, AOP, HTTP client, global exception handling, pagination DTOs

---

## Key File Locations

**Entry Point:**
- `src/main/java/com/hyoguoo/paymentplatform/PaymentPlatformApplication.java`

**Confirm Service Implementation (단일 전략):**
- `src/main/java/com/hyoguoo/paymentplatform/payment/application/OutboxAsyncConfirmService.java`

**Shared Transaction Coordinator:**
- `src/main/java/com/hyoguoo/paymentplatform/payment/application/usecase/PaymentTransactionCoordinator.java`

**Publisher Port and Impl:**
- `src/main/java/com/hyoguoo/paymentplatform/payment/infrastructure/publisher/OutboxImmediatePublisher.java`

**Outbox Worker:**
- `src/main/java/com/hyoguoo/paymentplatform/payment/scheduler/OutboxWorker.java`

**Primary Domain Entity:**
- `src/main/java/com/hyoguoo/paymentplatform/payment/domain/PaymentEvent.java`

**PaymentController:**
- `src/main/java/com/hyoguoo/paymentplatform/payment/presentation/PaymentController.java`

**Confirm Response Type:**
- `src/main/java/com/hyoguoo/paymentplatform/payment/application/dto/response/PaymentConfirmAsyncResult.java`

---

## Naming Conventions

**Files:**
- Domain aggregates: `PaymentEvent`, `PaymentOrder`, `PaymentOutbox` (PascalCase, no suffix)
- Use-case services: `PaymentCommandUseCase`, `PaymentLoadUseCase` (suffix `UseCase`)
- Confirm service: `OutboxAsyncConfirmService` (단일 전략)
- Port interfaces: `PaymentEventRepository`, `PaymentGatewayPort`, `PaymentConfirmService` (no `I` prefix)
- Infrastructure implementations: `PaymentEventRepositoryImpl`, `OutboxImmediatePublisher`, `InternalPaymentGatewayAdapter`
- JPA Spring Data: `JpaPaymentEventRepository`, `JpaPaymentOrderRepository` (prefix `Jpa`)
- JPA entity classes: `PaymentEventEntity`, `PaymentOrderEntity` (suffix `Entity`)
- Mapper utilities: `PaymentInfrastructureMapper`, `PaymentPresentationMapper` (suffix `Mapper`)
- Exception classes: `PaymentStatusException`, `PaymentTossRetryableException`
- Error codes: `PaymentErrorCode`, `GlobalErrorCode` (suffix `ErrorCode`)

**Packages:**
- `presentation/port/` — inbound port interfaces (consumed by controllers / schedulers / listeners)
- `application/port/` — outbound port interfaces (implemented by infrastructure)
- `infrastructure/idempotency/` — idempotency 저장소 구현체 및 설정
- `application/usecase/` — internal application services not directly injected by outside callers
- `infrastructure/internal/` — cross-context Java adapters

---

## Where to Add New Code

**New outbound port (e.g., new external service):**
1. Interface → `src/main/java/com/hyoguoo/paymentplatform/payment/application/port/NewServicePort.java`
2. Adapter implementation → `src/main/java/com/hyoguoo/paymentplatform/payment/infrastructure/NewServiceAdapter.java`
3. Inject port interface into use-case or coordinator

**New internal use-case operation:**
- Stateless helper → add method to the closest existing `UseCase` service in `application/usecase/`
- New transactional multi-step flow → add method to `PaymentTransactionCoordinator`

**New domain behavior (status transition):**
1. Add guard logic method to the relevant aggregate in `payment/domain/`
2. Add corresponding `@PublishDomainEvent` + `@PaymentStatusChange` method in `PaymentCommandUseCase`

**New scheduled job:**
- If it is specific to the outbox strategy → add method to `OutboxWorker` or a helper class in `payment/scheduler/`
- If it is for general recovery/expiration → add to `PaymentScheduler` with `@ConditionalOnProperty` guard

**New infrastructure repository:**
1. JPA entity → `payment/infrastructure/entity/`
2. Spring Data interface → `payment/infrastructure/repository/JpaXxxRepository.java`
3. Port interface → `payment/application/port/XxxRepository.java`
4. Impl → `payment/infrastructure/repository/XxxRepositoryImpl.java`

**New test:**
- Unit test for domain → `src/test/java/com/hyoguoo/paymentplatform/payment/domain/`
- Unit test for use case → `src/test/java/com/hyoguoo/paymentplatform/payment/application/usecase/`
- Fake implementation → `src/test/java/com/hyoguoo/paymentplatform/mock/`

---

## Special Directories

**`src/main/java/com/hyoguoo/paymentplatform/mock/`:**
- Purpose: `FakeTossHttpOperator` and `BenchmarkConfig`
- Generated: No
- Committed: Yes (active only with `@Profile("benchmark")`)

**`src/**/out/` directories:**
- `.gitignore` has `!src/**/out/` exception — these compiled output directories are explicitly tracked if present

---

*Structure analysis: 2026-04-05*
