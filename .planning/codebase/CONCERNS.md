# Concerns

## Tech Debt

### 1. Toss Error Code Parsing — String-Based Enum Lookup
`TossPaymentErrorCode.of(String)` falls back to `UNKNOWN` if the Toss API returns an unrecognized error code. This silently classifies new or misspelled error codes as retryable, potentially causing incorrect payment state transitions.

**File:** `paymentgateway/exception/common/TossPaymentErrorCode.java:63-68`

### 2. `ALREADY_PROCESSED_PAYMENT` Treated as Success
`isSuccess()` returns true only for `ALREADY_PROCESSED_PAYMENT`. This is an intentional idempotency shortcut but is fragile — if the payment state in the DB differs from Toss's state, the system will accept a duplicate as complete.

**File:** `paymentgateway/exception/common/TossPaymentErrorCode.java:70-72`

### 3. `PaymentEvent.create()` Assumes First Product's Seller
The seller ID is taken from `productInfoList.getFirst().getSellerId()`. Multi-seller orders would silently assign the wrong seller.

**File:** `payment/domain/PaymentEvent.java:53`

### 4. Broad `Exception` Catch in Confirm Flow
`PaymentConfirmServiceImpl.confirm()` catches generic `Exception` as a final catch-all and routes it to `handleUnknownFailure`. This masks unexpected runtime errors as payment unknown failures rather than surfacing them.

**File:** `payment/application/PaymentConfirmServiceImpl.java:94-98`

### 5. ObjectMapper Instantiation Pattern
If any class instantiates `new ObjectMapper()` directly (rather than using the Spring-managed bean), Jackson configuration (custom modules, date formats) will not be applied. Review infrastructure classes for this pattern.

## Known Bugs / Risks

### 1. Race Condition in Concurrent Confirm Requests
`executeStockDecreaseWithJobCreation` uses pessimistic locking at the DB level, but the `executePayment` status transition (`READY→IN_PROGRESS`) is a separate transaction. A double-submit race between stock decrease and execute could theoretically result in duplicate stock decreases before the second request fails on status validation.

**File:** `payment/application/usecase/PaymentTransactionCoordinator.java`

### 2. Compensation Idempotency Not Guaranteed
`executePaymentFailureCompensation` calls `increaseStockForOrders` without checking if stock was previously restored. If compensation runs twice (e.g., retried after timeout), stock could be double-restored.

**File:** `payment/application/usecase/PaymentTransactionCoordinator.java:43-56`

### 3. Scheduler `@ConditionalOnProperty` with `@Scheduled` — No Guaranteed Mutual Exclusion
The scheduler does not use distributed locking. In multi-instance deployments, `recoverRetryablePayment` and `expireOldReadyPayments` will run concurrently on all nodes, potentially processing the same payment records multiple times.

**File:** `payment/scheduler/PaymentScheduler.java`

## Security

### 1. Toss Secret Key Exposure Risk
The Toss secret key is injected via Spring properties and encoded in `HttpTossOperator`. If the key is logged or included in error messages, it could be exposed. Verify `MaskingPatternLayout` covers the Base64-encoded key pattern.

**File:** `paymentgateway/infrastructure/api/HttpTossOperator.java`

### 2. No Authentication on Internal Receivers
`ProductInternalReceiver`, `UserInternalReceiver`, and `PaymentGatewayInternalReceiver` are Spring MVC controllers. If these are accessible externally (depending on gateway/proxy config), they can be called without auth.

### 3. PII in Logs
`PaymentConfirmServiceImpl` logs `orderId` and `paymentKey` in structured logs. `paymentKey` is sensitive — verify `MaskingPatternLayout` masks it appropriately.

**File:** `payment/application/PaymentConfirmServiceImpl.java:37-40`

## Performance

### 1. Pessimistic Locking on Product Stock
`decreaseStockForOrders` uses pessimistic locking (assumed from the coordination pattern). Under high concurrent checkout load, this serializes all stock updates for the same product, creating a throughput bottleneck.

**File:** `payment/application/usecase/OrderedProductUseCase.java` (and `ProductRepositoryImpl`)

### 2. Payment Recovery Loads All Retryable Payments
`recoverRetryablePayment` likely loads all UNKNOWN/IN_PROGRESS payments past threshold into memory. Without pagination, this could be expensive as the table grows.

**File:** `payment/application/PaymentRecoverServiceImpl.java`

### 3. In-Memory Metrics State
`PaymentStateMetrics`, `PaymentTransitionMetrics` use Micrometer but may register metrics per-instance. In multi-instance deployments, metrics are not aggregated automatically — requires Prometheus scraping each instance.

**File:** `core/common/metrics/`

## Fragile Areas

### 1. Payment State Machine — No Central State Machine Framework
State transitions are enforced via `if` conditions in each domain method (`execute()`, `done()`, `fail()`, `unknown()`, `expire()`). Adding a new status requires touching multiple methods. No framework-level guard prevents forgetting a transition case.

**File:** `payment/domain/PaymentEvent.java`

### 2. `PaymentHistory` Coupling to Domain Events
`PaymentHistoryEventListener` depends on Spring application events published via AOP. If the AOP aspect fails silently (e.g., wrong pointcut), payment history records will be missing with no error surfaced.

**File:** `payment/listener/PaymentHistoryEventListener.java`

### 3. `productInfoList.getFirst()` Without Empty Check
If `productInfoList` is empty during checkout, `getFirst()` throws `NoSuchElementException` rather than a domain-meaningful error.

**File:** `payment/domain/PaymentEvent.java:53,66`

## Scaling Limits

### 1. No Distributed Locking for Schedulers
Schedulers use `@Scheduled` with no distributed lock. Horizontal scaling immediately causes duplicate job execution.

### 2. No Dead Letter Queue
Failed payments that exhaust retries (`RETRYABLE_LIMIT = 5`) are marked FAILED with no DLQ or alerting mechanism. Manual intervention is required to reprocess.

### 3. `PaymentProcess` Table as In-DB Job Tracker
`PaymentProcess` records are created/completed per payment confirm. This table grows with every transaction. Cleanup/archival strategy is not evident.

## Test Coverage Gaps

### 1. Concurrent Payment Confirm
No tests for race conditions in `executeStockDecreaseWithJobCreation` under concurrent load.

### 2. Compensation Double-Execution
No test verifying idempotent behavior if `executePaymentFailureCompensation` is called twice for the same payment.

### 3. Scheduler Behavior Under Multi-Instance
No integration tests covering concurrent scheduler execution across simulated instances.

### 4. Unknown Toss Error Code Handling
No test verifying that an unrecognized Toss error code is handled correctly (falls back to `UNKNOWN` → retryable).

### 5. Empty Product List in Checkout
No test for `PaymentEvent.create()` with an empty product list.
