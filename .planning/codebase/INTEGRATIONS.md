# External Integrations

**Analysis Date:** 2026-03-14

## APIs & External Services

**Payment Gateway:**
- **Toss Payments** - Primary payment processing provider
  - SDK/Client: Custom HTTP client via `HttpOperatorImpl` and `HttpTossOperator`
  - Auth: Basic authentication with API secret key (base64 encoded)
  - Endpoints:
    - `GET https://api.tosspayments.com/v1/payments/orders/{orderId}` - Retrieve order payment status
    - `GET https://api.tosspayments.com/v1/payments/{paymentKey}` - Retrieve payment info by key
    - `POST https://api.tosspayments.com/v1/payments/confirm` - Confirm payment (with idempotency key)
    - `POST https://api.tosspayments.com/v1/payments/{paymentKey}/cancel` - Cancel payment
  - Configuration location: `src/main/resources/application.yml` (line 31-38) and `src/main/resources/application-docker.yml` (line 29-34)
  - Environment variables:
    - `TOSS_SECRET_KEY` - Production/local API secret
    - `TOSS_TEST_SECRET_KEY` - Test environment API secret
  - Timeout configuration:
    - Connect timeout: 3000ms
    - Read timeout: 10000ms (local), 5000ms (test), 30000ms (docker)

## Data Storage

**Databases:**
- **MySQL 5.7+**
  - Connection: JDBC via MySQL Connector/J driver
  - URL pattern: `jdbc:mysql://[host]:[port]/payment-platform`
  - Local default: `localhost:3306` with database auto-creation
  - Docker default: `mysql:3306` (container network)
  - ORM: Spring Data JPA with Hibernate
  - Dialect: MySQLDialect
  - Configuration locations:
    - Base: `src/main/resources/application.yml`
    - Docker: `src/main/resources/application-docker.yml` (line 6-16)
    - Test: `src/test/resources/application-test.yml` (line 7-13)
  - Connection pooling: HikariCP
    - Docker: max pool 30, min idle 15, connection timeout 30s
    - Test: max pool 10, min idle 2, connection timeout 20s
  - DDL strategy:
    - Local: undefined (use default)
    - Docker: `update` (alter existing schema)
    - Test: `create-drop` (recreate schema per test run)

**Repositories:**
- Payment-related: `JpaPaymentEventRepository`, `JpaPaymentOrderRepository`, `JpaPaymentProcessRepository`, `JpaPaymentHistoryRepository` in `src/main/java/com/hyoguoo/paymentplatform/payment/infrastructure/repository/`
- QueryDSL-enabled repositories for type-safe queries
- Located at: `src/main/java/com/hyoguoo/paymentplatform/[domain]/infrastructure/repository/`

**File Storage:**
- Local filesystem only - No external file storage service configured

**Caching:**
- None detected - No caching framework configured

## Authentication & Identity

**Auth Provider:**
- Custom - Toss Payments uses API key-based authentication (not OAuth/SSO)
- Implementation: Basic authentication header with base64-encoded secret key
- Implementation file: `src/main/java/com/hyoguoo/paymentplatform/paymentgateway/infrastructure/api/HttpTossOperator.java` (line 146-148)

## Monitoring & Observability

**Error Tracking:**
- Custom exception handling framework (no Sentry/Rollbar integration)
- Exceptions mapped in dedicated exception handler classes:
  - `src/main/java/com/hyoguoo/paymentplatform/payment/exception/common/PaymentExceptionHandler.java`
  - `src/main/java/com/hyoguoo/paymentplatform/paymentgateway/exception/common/PaymentGatewayExceptionHandler.java`
- Custom exceptions:
  - `PaymentTossConfirmException`
  - `PaymentTossRetryableException`
  - `PaymentTossNonRetryableException`
  - `PaymentGatewayApiException`

**Logs:**
- Logback framework with Logstash integration
- Console appender: Always enabled
- Logstash appender: Docker environment only (TCP socket to `logstash:5050`)
- Configuration: `src/main/resources/logback-spring.xml`
- Structured JSON logging with custom fields: application name, environment
- MDC (Mapped Diagnostic Context) includes: traceId, orderId, paymentKey, userId
- Log patterns include thread info and trace ID for distributed tracing

**Metrics:**
- Prometheus metrics via Micrometer
- Actuator endpoints expose metrics at `/actuator/metrics` and `/actuator/prometheus`
- Custom metrics:
  - `TossApiMetrics` - API call tracking
  - `PaymentStateMetrics` - Payment state monitoring
  - `PaymentHealthMetrics` - Payment health monitoring
- Aspect-based instrumentation via `@Timed` and `@Counted` annotations
- Configuration: `src/main/java/com/hyoguoo/paymentplatform/core/config/MetricsConfig.java`

## CI/CD & Deployment

**Hosting:**
- Docker containerized deployment
- Docker Compose orchestration (compose files in `docker/compose/`)
- Health check: `curl -f http://localhost:8080/actuator/health`

**CI Pipeline:**
- Not detected - No GitHub Actions/Jenkins/GitLab CI configuration found

**Docker Compose Services:**
- Application container (Spring Boot JAR)
- MySQL database container
- Logstash container (for log aggregation)
- Kibana container (for log visualization)
- Prometheus container (for metrics collection)
- Grafana container (for metrics visualization)

## Environment Configuration

**Required env vars:**
- `TOSS_SECRET_KEY` - Production Toss API secret key
- `TOSS_TEST_SECRET_KEY` - Test Toss API secret key
- `DB_USERNAME` - Database username (default: root)
- `DB_PASSWORD` - Database password (default: payment123)

**Secrets location:**
- `.env.secret` file in project root and `docker/compose/` directory
- Environment variables passed to containers via Docker Compose
- File format: `KEY=value` pairs

## Webhooks & Callbacks

**Incoming:**
- None detected

**Outgoing:**
- Payment status polling implemented instead of webhooks
- Scheduler-based synchronization with Toss Payments API
- Configuration in `src/main/resources/application.yml` and `src/main/resources/application-docker.yml`:
  - `metrics.payment.state.polling-interval-seconds` - Payment state polling interval (default: 10s)
  - `metrics.payment.health.polling-interval-seconds` - Health check interval (default: 10s)
  - `scheduler.payment-status-sync.fixed-rate` - Status sync interval (default: 3600000ms / 1 hour in Docker)
  - `scheduler.payment-recovery.interval-ms` - Recovery job interval (default: 60000ms / 1 minute)
- Implementation: `src/main/java/com/hyoguoo/paymentplatform/payment/scheduler/PaymentScheduler.java`

## API Patterns

**HTTP Client:**
- Spring's RestTemplate via custom wrapper `HttpOperatorImpl`
- Location: `src/main/java/com/hyoguoo/paymentplatform/core/common/infrastructure/http/HttpOperatorImpl.java`
- Supports GET and POST methods
- Configurable read timeout per environment
- SimpleClientHttpRequestFactory for timeout management

**Gateway Strategy Pattern:**
- `PaymentGatewayStrategy` interface for payment provider abstraction
- Implementation: `TossPaymentGatewayStrategy` in `src/main/java/com/hyoguoo/paymentplatform/payment/infrastructure/gateway/toss/TossPaymentGatewayStrategy.java`
- Factory: `PaymentGatewayFactory` for instantiating appropriate gateway
- Supports multiple payment gateways via `PaymentGatewayType` enum

---

*Integration audit: 2026-03-14*
