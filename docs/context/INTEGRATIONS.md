# External Integrations

**Analysis Date:** 2026-04-05

## APIs & External Services

**Payment Gateway — Toss Payments:**
- Base URL: `https://api.tosspayments.com` (config key `payment.gateway.toss.base-url`)
- API version: `2022-11-16`
- SDK/Client: `HttpTossOperator` implements `TossOperator` port
  - Implementation: `src/main/java/com/hyoguoo/paymentplatform/paymentgateway/infrastructure/api/HttpTossOperator.java`
  - HTTP client: Spring `WebClient` (WebFlux) via `HttpOperatorImpl` (`src/main/java/com/hyoguoo/paymentplatform/core/common/infrastructure/http/HttpOperatorImpl.java`)
- Auth: HTTP Basic Auth with Base64-encoded `${TOSS_SECRET_KEY}:` — config key `spring.myapp.toss-payments.secret-key` (docker) or `payment.gateway.toss.secret-key` (PaymentGatewayProperties)
- Timeouts: connect 3000ms, read 10000ms (default); read 30000ms (docker/benchmark)

**Toss Payments API Endpoints:**

| Operation | Method | Path | Notes |
|-----------|--------|------|-------|
| Confirm payment | POST | `{base-url}/v1/payments/confirm` | Idempotency-Key header required |
| Cancel payment | POST | `{base-url}/v1/payments/{paymentKey}/cancel` | Idempotency-Key header required |
| Get payment by orderId | GET | `{base-url}/v1/payments/orders/{orderId}` | |
| Get payment by paymentKey | GET | `{base-url}/v1/payments/{paymentKey}` | |

**Confirm Request Body** (`TossConfirmCommand` → `TossConfirmRequest`):
```json
{
  "paymentKey": "string",
  "orderId": "string",
  "amount": "number"
}
```

**Confirm Response** (`TossPaymentApiResponse`, version `2022-11-16`):
```json
{
  "version": "2022-11-16",
  "paymentKey": "string",
  "type": "NORMAL",
  "orderId": "string",
  "orderName": "string",
  "currency": "KRW",
  "method": "카드",
  "totalAmount": 0.0,
  "balanceAmount": 0.0,
  "status": "DONE | IN_PROGRESS | ...",
  "requestedAt": "2024-01-01T00:00:00+09:00",
  "approvedAt": "2024-01-01T00:00:00+09:00",
  ...
}
```

**Error Response** (`TossPaymentApiFailResponse`):
```json
{ "code": "string", "message": "string" }
```

**Confirm Flow:**

_Outbox_ (`OutboxAsyncConfirmService`, `@Service` — 단일 구현체):
1. `POST /api/v1/payments/confirm` received
2. READY → IN_PROGRESS + stock decrease + `PaymentOutbox(PENDING)` in single TX (`executePaymentAndStockDecreaseWithOutbox`)
3. Return `202 Accepted` with `PaymentConfirmAsyncResult(ASYNC_202)`
4. `OutboxImmediateEventHandler` (AFTER_COMMIT, @Async) calls Toss API and marks DONE/FAILED
5. **폴백**: `OutboxWorker` (5s fixedDelay)가 즉시 처리 누락된 PENDING 레코드 재처리

---

**Payment Gateway — NicePay:**
- Base URL: `https://sandbox-api.nicepay.co.kr` (config key `payment.gateway.nicepay.base-url`)
- SDK/Client: `HttpNicepayOperator` implements `NicepayOperator` port
  - Implementation: `src/main/java/com/hyoguoo/paymentplatform/paymentgateway/infrastructure/api/HttpNicepayOperator.java`
  - HTTP client: Spring `WebClient` via `HttpOperatorImpl` (same as Toss)
- Auth: HTTP Basic Auth with Base64-encoded `${NICEPAY_SECRET_KEY}:` — config key `payment.gateway.nicepay.secret-key`

**NicePay API Endpoints:**

| Operation | Method | Path | Notes |
|-----------|--------|------|-------|
| Confirm payment | POST | `{base-url}/v1/payments/{tid}` | tid = paymentKey (멱등성 키 역할) |
| Cancel payment | POST | `{base-url}/v1/payments/{tid}/cancel` | |
| Get payment by tid | GET | `{base-url}/v1/payments/{tid}` | |
| Get payment by orderId | GET | `{base-url}/v1/payments/find/{orderId}` | |

**NicePay 특수 에러 처리:**
- `2201` (중복 승인): `handleDuplicateApprovalCompensation` — tid로 PG 재조회 → status==paid AND 금액 일치 검증 → SUCCESS
- Retryable: `2159`, `A246`, `A299`
- Non-retryable: `3011`~`3014`, `2152`, `2156`

---

**Benchmark mode** (`benchmark` profile, `BenchmarkConfig`):
- `FakeTossHttpOperator` replaces `HttpOperatorImpl` as `@Primary` `HttpOperator` bean
- Location: `src/main/java/com/hyoguoo/paymentplatform/mock/FakeTossHttpOperator.java`
- Simulates network delay: `min-delay-millis` to `max-delay-millis` (default 100–300ms)
- Returns fixed `DONE` response on POST, `IN_PROGRESS` on GET

## Data Storage

**Databases:**
- Type: MySQL 8.0 (`mysql:8.0` Docker image)
- Database name: `payment-platform`
- Connection (docker): `jdbc:mysql://mysql:3306/payment-platform?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true`
- Connection env vars: `DB_USERNAME` / `DB_PASSWORD`
- Client: Spring Data JPA with Hibernate (dialect `org.hibernate.dialect.MySQLDialect`)
- ORM: Hibernate with JPA entities extending `BaseEntity` (`created_at`, `updated_at`)
- DDL: `hibernate.ddl-auto: update` (docker profile)
- Seed data: `src/main/resources/data.sql` (2 users, 2 products via `INSERT IGNORE`)

**MySQL Schema — Key Tables:**

`payment_event` — maps to `PaymentEventEntity`:
- `id` BIGINT PK AUTO_INCREMENT
- `buyer_id` BIGINT NOT NULL
- `seller_id` BIGINT NOT NULL
- `order_name` VARCHAR NOT NULL
- `order_id` VARCHAR NOT NULL
- `payment_key` VARCHAR (nullable until confirm)
- `status` ENUM(`READY`, `IN_PROGRESS`, `RETRYING`, `DONE`, `FAILED`, `CANCELED`, `PARTIAL_CANCELED`, `EXPIRED`, `QUARANTINED`) NOT NULL
- `gateway_type` VARCHAR(20) (TOSS/NICEPAY, nullable for legacy records — Flyway V1 migration)
- `executed_at`, `approved_at`, `last_status_changed_at` DATETIME
- `retry_count` INT
- `status_reason` VARCHAR
- `created_at`, `updated_at` (from `BaseEntity`)

`payment_order` — maps to `PaymentOrderEntity`:
- `id` BIGINT PK
- `payment_event_id` BIGINT NOT NULL (FK to payment_event)
- `order_id` VARCHAR NOT NULL
- `product_id` BIGINT NOT NULL
- `quantity` INT NOT NULL
- `amount` DECIMAL NOT NULL
- `status` ENUM(`NOT_STARTED`, `EXECUTING`, `SUCCESS`, `FAIL`, `CANCEL`, `EXPIRED`) NOT NULL

`payment_outbox` — maps to `PaymentOutboxEntity` (Outbox strategy):
- `id` BIGINT PK
- `order_id` VARCHAR(100) NOT NULL UNIQUE
- `status` ENUM(`PENDING`, `IN_FLIGHT`, `DONE`, `FAILED`) NOT NULL VARCHAR(20)
- `retry_count` INT NOT NULL
- `next_retry_at` DATETIME (nullable; set on RETRYABLE_FAILURE to schedule next processing time)
- `in_flight_at` DATETIME
- `created_at`, `updated_at`
- Index: `idx_payment_outbox_status_created` on `(status, created_at)`

`payment_history` — maps to `PaymentHistoryEntity` (audit trail):
- `id` BIGINT PK
- `payment_event_id` BIGINT NOT NULL
- `order_id` VARCHAR NOT NULL
- `previous_status` ENUM (nullable for initial creation)
- `current_status` ENUM NOT NULL
- `reason` TEXT
- `change_status_at` DATETIME NOT NULL

`user` — maps to `UserEntity`:
- `id`, `email`, `username`, `created_at`, `updated_at`

`product` — maps to `ProductEntity`:
- `id`, `name`, `description`, `price`, `stock`, `seller_id`, `created_at`, `updated_at`

**File Storage:**
- Local filesystem only — log files written to `/var/log/app` (mounted volume in Docker)

**Caching:**
- None

## Authentication & Identity

**Auth Provider:**
- None (no user authentication for API endpoints)
- Toss Payments uses Basic Auth: `Authorization: Basic base64(secretKey:)` — implemented in `HttpTossOperator.generateBasicAuthHeaderValue()`

## Monitoring & Observability

**Metrics:**
- Micrometer + Prometheus: `micrometer-registry-prometheus`
- Exposed at `/actuator/prometheus` (docker profile)
- Custom metric beans:
  - `PaymentStateMetrics` — payment status counts (polling every 10s, `metrics.payment.state.polling-interval-seconds`)
  - `PaymentHealthMetrics` — stuck IN_PROGRESS / high retry detection (polling every 10s, `metrics.payment.health.polling-interval-seconds`)
  - `PaymentTransitionMetrics` — status transition counters (via `@PaymentStatusChange` AOP annotation)
  - `TossApiMetrics` — Toss API call duration/success (via `@TossApiMetric` AOP annotation)
- Grafana dashboards provisioned at `chaos/grafana/provisioning/`

**Logs:**
- Structured logging via `LogFmt` utility class: `src/main/java/com/hyoguoo/paymentplatform/core/common/log/LogFmt.java`
- Log fields: `domain`, `eventType`, `traceId` (MDC), message
- Local/test profile: plain-text console with color (`LOG_PATTERN_COLOR`)
- Docker profile: console + Logstash TCP socket at `logstash:5050` (port 5050)
- `TraceIdFilter` injects `traceId` into MDC for each request: `src/main/java/com/hyoguoo/paymentplatform/core/common/filter/TraceIdFilter.java`
- Log masking: `MaskingPatternLayout` (`src/main/java/com/hyoguoo/paymentplatform/core/common/log/MaskingPatternLayout.java`)

**Error Tracking:**
- None (Sentry or equivalent not present)

## CI/CD & Deployment

**Hosting:**
- Docker Compose (`docker-compose.infra.yml` + `docker-compose.apps.yml` + `docker-compose.observability.yml`) — local MSA 환경
- `bash scripts/compose-up.sh`로 일괄 기동

**CI Pipeline:**
- GitHub Actions (`.github/workflows/ci.yml`): push/PR to `main` → JUnit 테스트 + JaCoCo 커버리지 리포트 → reviewdog로 PR 어노테이션

**Docker Compose Services:**

| Service | Image | Port | Notes |
|---------|-------|------|-------|
| `mysql` | `mysql:8.0` | 3306 | database `payment-platform` |
| `elasticsearch` | `docker.elastic.co/elasticsearch/elasticsearch:7.17.9` | 9200 | single-node, no security |
| `logstash` | `docker.elastic.co/logstash/logstash:7.17.9` | 5050 (TCP), 5051 (UDP) | receives JSON from logback |
| `kibana` | `docker.elastic.co/kibana/kibana:7.17.9` | 5601 | dashboard |
| `kibana-init` | `curlimages/curl:latest` | — | loads saved objects on startup |
| `prometheus` | `prom/prometheus:latest` | 9090 | scrapes `/actuator/prometheus` |
| `grafana` | `grafana/grafana:latest` | 3000 | dashboards provisioned via bind-mounts |
| `app` | Dockerfile | 8080 | Spring Boot app, profiles `docker,benchmark` |

## Webhooks & Callbacks

**Incoming:**
- None (no Toss webhook endpoints registered)

**Outgoing:**
- Toss Payments / NicePay API calls (confirm, cancel, status query)

## Environment Configuration

**Required env vars:**
- `TOSS_SECRET_KEY` — Toss Payments secret key
- `NICEPAY_SECRET_KEY` — NicePay secret key
- `DB_USERNAME` — MySQL username (default `payment`)
- `DB_PASSWORD` — MySQL password (default `payment123`)
- `GRAFANA_USER` / `GRAFANA_PASSWORD` — Grafana admin credentials

**Secrets location:**
- `.env.secret` (루트) — `scripts/compose-up.sh`가 `docker compose --env-file`로 주입 (not committed)

---

*Integration audit: 2026-04-14*
