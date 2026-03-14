# Architecture

## Pattern

**Hexagonal Architecture (Ports & Adapters)** with Clean Architecture layering.

Each module enforces strict dependency direction: Presentation → Application → Domain ← Infrastructure.

## Modules

```
com.hyoguoo.paymentplatform/
├── core/           # Cross-cutting: logging, metrics, exceptions, filters, config
├── payment/        # Core payment processing domain
├── paymentgateway/ # Payment provider abstraction (Toss Payments)
├── product/        # Product catalog & stock management
└── user/           # User lookup
```

Each module (`payment`, `paymentgateway`, `product`, `user`) is self-contained with its own layers.

## Layers Per Module

```
{module}/
├── presentation/       # REST controllers, request/response DTOs, PresentationMapper
│   └── port/           # Interfaces controllers depend on (e.g., PaymentCheckoutService)
├── application/        # Service impls, use cases, DTOs
│   ├── port/           # Interfaces services depend on (repositories, external ports)
│   ├── usecase/        # Fine-grained use case classes
│   └── publisher/      # Domain event publishers
├── domain/             # Pure domain entities, value objects, enums, domain logic
│   ├── enums/
│   └── dto/            # Domain-internal DTOs
├── infrastructure/     # JPA entities, repository impls, HTTP adapters
│   ├── entity/
│   └── repository/
├── listener/           # Spring event listeners (domain event → side effects)
├── scheduler/          # Scheduled jobs
│   └── port/           # Interfaces schedulers depend on
└── exception/          # Module exceptions, error codes, exception handlers
    └── common/
```

## Key Abstractions

### Ports (interfaces in application/port)
```java
// payment/application/port/PaymentEventRepository.java
// payment/application/port/PaymentGatewayPort.java
// payment/application/port/ProductPort.java
// payment/application/port/UserPort.java
// paymentgateway/application/port/TossOperator.java
// core/common/infrastructure/http/HttpOperator.java
// core/common/service/port/LocalDateTimeProvider.java
// core/common/service/port/UUIDProvider.java
```

### Use Cases (orchestration classes, not interfaces)
```java
PaymentTransactionCoordinator   // Transactional: stock + job + payment state
PaymentCommandUseCase           // execute, confirm, mark done/fail/unknown
PaymentLoadUseCase              // queries
PaymentCreateUseCase            // checkout creation
PaymentFailureUseCase           // failure compensation
PaymentRecoveryUseCase          // recovery logic
TossApiCallUseCase              // Toss API calls
TossApiFailureUseCase           // Toss failure classification
```

### Inter-module Communication
Modules communicate via internal HTTP controllers (`*InternalReceiver`) or direct Spring bean injection through port interfaces:

- `payment` → `product` via `ProductPort` (interface) → `ProductInternalReceiver` (HTTP) or direct service
- `payment` → `user` via `UserPort` → `UserInternalReceiver`
- `payment` → `paymentgateway` via `PaymentGatewayPort` → `PaymentGatewayServiceImpl`

## Data Flow: Payment Checkout

```
POST /api/v1/payments/checkout
  → PaymentController
  → PaymentCheckoutServiceImpl
  → PaymentCreateUseCase (validate user, product, create PaymentEvent)
  → PaymentEventRepository.save()
  ← orderId, amount
```

## Data Flow: Payment Confirm

```
POST /api/v1/payments/confirm
  → PaymentController
  → PaymentConfirmServiceImpl
  → PaymentTransactionCoordinator.executeStockDecreaseWithJobCreation()
      → OrderedProductUseCase.decreaseStockForOrders()  [pessimistic lock]
      → PaymentProcessUseCase.createProcessingJob()
  → PaymentCommandUseCase.executePayment()  [status: READY→IN_PROGRESS]
  → PaymentCommandUseCase.confirmPaymentWithGateway()  [Toss API call]
  → PaymentTransactionCoordinator.executePaymentSuccessCompletion()
      → PaymentProcessUseCase.completeJob()
      → PaymentCommandUseCase.markPaymentAsDone()  [status: IN_PROGRESS→DONE]
  ← orderId, amount
```

**On failure:** `PaymentFailureUseCase.handleNonRetryableFailure/handleRetryableFailure` triggers `executePaymentFailureCompensation` (stock restore + job fail + payment fail).

## Data Flow: Payment Recovery (Scheduler)

```
PaymentScheduler (fixed rate: 5min)
  → PaymentRecoverService.recoverRetryablePayment()
      → find UNKNOWN/IN_PROGRESS payments past retryable threshold
      → re-execute confirm flow via PaymentGatewayService
  → PaymentExpirationService.expireOldReadyPayments()
      → find READY payments older than 30min → expire
```

## Domain Events

`@PublishDomainEvent` AOP annotation triggers `DomainEventLoggingAspect`.

`PaymentHistoryEventListener` captures Spring application events and records history via `PaymentHistoryUseCase → PaymentHistoryRepository`.

## State Machine: PaymentEvent

```
READY → IN_PROGRESS → DONE
              ↓
           FAILED
              ↓
           UNKNOWN → (retry) → IN_PROGRESS
READY → EXPIRED
```

State transitions enforced in domain entity (`PaymentEvent`) methods: `execute()`, `done()`, `fail()`, `unknown()`, `expire()`.

## Entry Points

| Endpoint | Handler |
|----------|---------|
| `POST /api/v1/payments/checkout` | `PaymentController.checkout()` |
| `POST /api/v1/payments/confirm` | `PaymentController.confirm()` |
| `GET /api/v1/admin/payments` | `AdminPaymentController` |
| Internal product/user receivers | `ProductInternalReceiver`, `UserInternalReceiver` |
| Internal gateway receiver | `PaymentGatewayInternalReceiver` |
| Scheduler | `PaymentScheduler` (3 jobs) |

## Cross-Cutting Concerns (`core/`)

| Component | Purpose |
|-----------|---------|
| `LogFmt` | Structured logfmt logging helper |
| `MaskingPatternLayout` | PII masking in log output |
| `TraceIdFilter` | MDC trace ID injection per request |
| `PaymentStateMetrics`, `PaymentTransitionMetrics`, `TossApiMetrics` | Micrometer metrics via AOP |
| `DomainEventLoggingAspect` | AOP domain event logging |
| `GlobalExceptionHandler` | Catch-all exception → error response |
| `BaseEntity` | JPA audit fields (createdAt, updatedAt) |
| `SystemLocalDateTimeProvider`, `SystemUUIDProvider` | Testable time/UUID abstraction |