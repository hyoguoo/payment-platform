# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Payment platform integrating with Toss Payments, implementing resilient payment flows with state-based recovery models, cross-validation for data integrity, and comprehensive monitoring. The system addresses payment data consistency, transient failures, and transaction scope optimization.

**Tech Stack:** Java 21, Spring Boot 3.3.3, MySQL 8.0.33, JUnit 5

## Commands

### Development

```bash
# Run all tests with JaCoCo coverage
./gradlew test

# Run tests without coverage verification
./gradlew cleanTest test

# Build executable JAR (skips tests)
./gradlew clean bootJar

# Run single test class
./gradlew test --tests "ClassName"

# Run single test method
./gradlew test --tests "ClassName.methodName"
```

### Running Services

```bash
# Start all services (app + MySQL + ELK + monitoring)
./scripts/run.sh

# Stop and clean up Docker environment
cd docker/compose && docker-compose down -v

# Check logs for specific service
docker-compose logs -f <service-name>
```

**Service URLs:**
- Application: http://localhost:8080
- Kibana: http://localhost:5601
- Grafana: http://localhost:3000 (admin/admin123!)
- Prometheus: http://localhost:9090

### Environment Setup

Two separate `.env.secret` files are required:

```bash
# 1. Root .env.secret (for tests)
cp .env.secret.example .env.secret
# Edit and add: TOSS_TEST_SECRET_KEY=test_sk_xxxxx

# 2. Docker Compose .env.secret (for running services)
cd docker/compose && cp .env.secret.example .env.secret
# Edit and add: TOSS_SECRET_KEY=your_actual_toss_key
```

**Important:** You must edit `/docker/compose/.env.secret` and replace `your_toss_secret_key_here` with your actual Toss Payments API key before running Docker Compose.

**File purposes:**
- `/.env.secret`: Test environment (loaded via `spring.config.import`)
- `/docker/compose/.env.secret`: Docker environment (loaded via `env_file` in docker-compose.yml)

## Development Workflow

Follow this required workflow when implementing tasks:

1.  **Review Specifications:** Always refer to `TECHSPEC.md` to understand the technical requirements before starting implementation.
2.  **Follow the Plan:** Use `PLAN.md` as the authoritative source for the implementation sequence.
    * After completing a task, mark it as **checked** in `PLAN.md`.
    * Always begin work on the next **unchecked** task listed in `PLAN.md`.
3.  **Test-First Development:**
    * Write tests (unit or integration) *before* writing the implementation code.
    * Implement the **minimum amount of code** necessary to pass the newly written tests. Avoid making overly large or unrelated changes.
4.  **Atomic Commits:**
    * Commit your work after a single, discrete unit of functionality or task is complete.
5.  **Update Documentation:**
    * Upon task completion, if the changes necessitate updates to guidance or specification files (e.g., CLAUDE.md, TECHSPEC.md), update those files accordingly.

## Architecture

### Port-Adapter Pattern (Hexagonal Architecture)

The codebase strictly follows Port-Adapter pattern with dependency inversion:

```
Domain (pure business logic, no external dependencies)
   ↑
Application (orchestrates domain logic)
   ├── ServiceImpl (orchestrates multiple use cases)
   ├── UseCase (single responsibility units)
   └── Port (interface abstractions for external dependencies)
   ↑
Infrastructure (implements Ports)
   ├── repository (JPA implementations)
   ├── internal (cross-domain collaboration via Receiver)
   └── entity (JPA entities)
   ↑
Presentation (controllers, DTOs, InternalReceiver)
```

### Layer Responsibilities

**Domain Layer**
- Pure business logic and rules
- No dependencies on frameworks or external systems
- State transitions, validations, business calculations
- Example: `PaymentEvent` with status transition logic

**Application Layer**
- **ServiceImpl**: Orchestrates use cases, controls execution flow
- **UseCase**: Single-responsibility components that compose domain logic
- **Port**: Interfaces abstracting external dependencies (repositories, external domains)
- Example: `PaymentConfirmServiceImpl` orchestrates `PaymentLoadUseCase`, `OrderedProductUseCase`, `PaymentProcessorUseCase`

**Infrastructure Layer**
- **repository**: JPA repository implementations
- **internal**: Adapters implementing Ports for cross-domain communication (e.g., `InternalProductAdapter` implements `ProductPort`)
- **entity**: JPA entities mapped to database tables

**Presentation Layer**
- Controllers for external API requests
- **InternalReceiver**: Entry points for internal cross-domain calls (e.g., `ProductInternalReceiver` receives requests from payment domain)

### Cross-Domain Communication

Domains communicate through Port → Adapter → InternalReceiver pattern:

```
Payment Domain                    Product Domain
PaymentService                    ProductService
    ↓ uses                            ↑
ProductPort (interface)          ProductInternalReceiver
    ↓ implemented by                  ↑ uses
InternalProductAdapter ──────────────┘
```

This ensures loose coupling and domain independence.

### Domain Models

**Payment Domain**
- `PaymentEvent`: Main aggregate with status-based state machine
  - States: READY → IN_PROGRESS → DONE / FAILED / EXPIRED / UNKNOWN
  - Contains retry logic, expiration rules, cross-validation
- `PaymentOrder`: Individual order items
- `PaymentHistory`: AOP-based change tracking for audit trail

**Other Domains**
- `User`: User information and balances
- `Product`: Product catalog and stock management
- `PaymentGateway`: Toss Payments API integration

### Key Components

**Scheduled Jobs** (`payment/scheduler/`)
- `PaymentScheduler`: Auto-recovery for stuck payments and expiration handling
- Configurable via `scheduler.enabled` and `scheduler.payment-status-sync.enabled`

**Event Listeners** (`payment/listener/`)
- `PaymentHistoryEventListener`: AOP-based tracking of payment state changes

**Monitoring & Logging**
- Structured logging with `LogFmt`, `LogDomain`, `EventType`
- Prometheus metrics exposed at `/actuator/prometheus`
- Logstash encoder for ELK stack integration

## Testing Strategy

### Test Structure

**Unit Tests**
- Domain logic tests: Pure business logic without external dependencies
- UseCase tests: Test single-responsibility use cases with mocked ports
- Example: `PaymentEventTest`, `PaymentProcessorUseCaseTest`

**Integration Tests**
- Extend `BaseIntegrationTest` (provides Testcontainers MySQL)
- Tests full application flow with real database
- Example: `PaymentConfirmServiceImplTest`

**Fake Objects** (`src/test/java/.../mock/`)
- Fake implementations of Ports for controlled testing scenarios
- `FakeTossOperator`: Simulates Toss API responses (delays, failures, etc.)
- `FakeProductRepository`, `FakeUserRepository`: In-memory repositories
- `TestLocalDateTimeProvider`: Time control for expiration testing

### Running Tests

Tests use Testcontainers for MySQL and require `TOSS_TEST_SECRET_KEY` in `.env.secret` (loaded automatically via Spring test profile).

JaCoCo coverage excludes: Q* (QueryDSL), dto, entity, exception, infrastructure, enums, main class

## Key Patterns & Conventions

**Naming Conventions**
- Services: `*ServiceImpl` (implementation), `*Service` (interface in presentation/port)
- Use Cases: `*UseCase` (implementation), no interface needed
- Ports: `*Port` (interface in application/port)
- Repositories: `*Repository` (interface), `*RepositoryImpl` (implementation)
- Internal collaboration: `*InternalReceiver` (presentation), `Internal*Adapter` (infrastructure)

**Transaction Scope**
- Keep transactions minimal - external API calls are outside transaction boundaries
- Use compensating transactions when external calls fail after DB commits
- Example: Stock decrease → Toss API → if API fails, stock increase (compensating)

**Error Handling**
- Retryable errors: `PaymentTossRetryableException` (network timeouts, etc.)
- Non-retryable errors: `PaymentTossNonRetryableException` (invalid data, etc.)
- Status transitions handle retry counts and failure reasons

**Payment State Transitions**
- READY: Initial checkout
- IN_PROGRESS: Execution started, waiting for Toss confirmation
- DONE: Successfully confirmed
- FAILED: Permanent failure
- EXPIRED: Exceeded 30-minute validity
- UNKNOWN: Toss API returned unknown status (recoverable)

## Common Development Patterns

**Adding New Domain**
1. Create package structure: `domain/`, `application/`, `infrastructure/`, `presentation/`
2. Define domain model in `domain/`
3. Create use cases in `application/usecase/`
4. Define ports in `application/port/` for external dependencies
5. Implement service in `application/*ServiceImpl`
6. Create infrastructure implementations in `infrastructure/`
7. Add controller in `presentation/`
8. If other domains need to call this domain, add `*InternalReceiver` in `presentation/`

**Cross-Domain Integration**
1. Define Port interface in consuming domain's `application/port/`
2. Create InternalReceiver in providing domain's `presentation/`
3. Implement Adapter in consuming domain's `infrastructure/internal/`
4. Adapter calls InternalReceiver to bridge domains

**Adding Scheduled Job**
1. Create service interface in `scheduler/port/`
2. Implement in `application/*ServiceImpl`
3. Add method to `PaymentScheduler` with `@Scheduled` annotation
4. Configure in application.yml under `scheduler.*`

## Important Configuration

- **Database**: MySQL with UTF-8MB4
- **QueryDSL**: Generated Q-classes in `build/generated/sources/annotationProcessor`
- **Profiles**: `test` (integration tests with Testcontainers), default (production-like)
- **Actuator**: Metrics at `/actuator/prometheus`, health at `/actuator/health`
- **Swagger**: Available at `/swagger-ui.html` (disabled in test profile)
