# Technology Stack

**Project:** Payment Platform — Async Strategy Migration
**Researched:** 2026-03-14

---

## Existing Stack (Do Not Change)

The project is already running on a fixed stack. These are constraints, not choices.

| Technology | Version | Role |
|------------|---------|------|
| Java | 21 | Runtime |
| Spring Boot | 3.3.3 | Framework |
| Spring Data JPA | (managed by Boot) | ORM |
| MySQL | 8.x | Primary datastore |
| Gradle | 8.10 | Build system |
| QueryDSL | 5.0.0 | Type-safe JPA queries |
| Testcontainers | 1.19.8 | Integration test containers |

**Note on Spring Boot version:** The project is pinned to 3.3.3 (not 3.4.x). This matters for Kafka dependency management — see below.

---

## Recommended Additions for Async Strategies

### Kafka Integration

| Technology | Version | Purpose | Why |
|------------|---------|---------|-----|
| `spring-kafka` | 3.2.x (Boot-managed) | Spring abstraction over Kafka client | Spring Boot 3.3.3 BOM manages spring-kafka 3.2.x automatically. Adding `spring-boot-starter` without a version pin picks this up. No manual version needed. |
| Apache Kafka (Docker) | 3.9.x (apache/kafka image) | Broker for local dev | Kafka 3.9 is the last version with ZooKeeper support but runs fine in KRaft mode. Spring Kafka 3.2.x is compiled against Kafka client 3.x. Kafka 4.0 dropped ZooKeeper entirely — use 3.9 to avoid adopting a brand-new major simultaneously with the Spring Kafka version. |
| `testcontainers:kafka` | 1.19.8 (matches existing) | Kafka container in integration tests | Consistent with existing Testcontainers version. `org.testcontainers:kafka` module wraps the Confluent CP image and integrates cleanly with `@DynamicPropertySource`. |

**Confidence:** MEDIUM — Spring Boot 3.3.3 BOM version for spring-kafka confirmed via official Spring release blog (3.2.0 bundled with Spring Boot 3.3.0; patch releases maintained in 3.2.x). Spring Kafka 3.3.x is bundled with Boot 3.4.x, not 3.3.x.

**Do NOT** manually pin spring-kafka to 3.3.x when using Boot 3.3.3. The BOM versions are not interchangeable without verifying Kafka client compatibility.

---

### Outbox Pattern (DB Outbox Adapter)

**Recommended approach: Custom Polling Publisher with `@Scheduled`**

Do NOT use Debezium or Spring Modulith for this project. Rationale below.

| Technology | Version | Purpose | Why |
|------------|---------|---------|-----|
| JPA / `@Transactional` | existing | Atomic write to outbox table | The outbox record and the business record are written in the same local transaction. No new library needed — this is a pattern, not a framework dependency. |
| Spring `@Scheduled` | existing (Spring Boot) | Worker that polls outbox and processes pending records | Boot already has scheduling on the classpath. One `@EnableScheduling` + a `@Scheduled` method is sufficient for a single-instance setup. |
| `@TransactionalEventListener` | existing (Spring Framework) | Alternative to direct outbox write inside service layer | Lets domain events trigger outbox writes without coupling service code to infrastructure. Optional refinement. |

**Why NOT Debezium:**
Debezium CDC requires Kafka Connect infrastructure (a separate JVM process, connector plugins, connector config API). For a single-instance portfolio project this is severe over-engineering. Debezium is justified only when throughput > ~10k events/minute and you need sub-second latency — neither applies here. Confidence: HIGH (multiple authoritative sources confirm this threshold).

**Why NOT Spring Modulith:**
Spring Modulith's Event Publication Registry works well but introduces a dependency on the Spring Modulith BOM and restructures how modules are declared. The existing project uses Hexagonal Architecture (Ports & Adapters) — not the "logical module" decomposition Spring Modulith expects. Introducing it mid-project adds complexity without adding clarity. Confidence: MEDIUM (based on Spring Modulith docs + architecture analysis).

**Why NOT `spring-outbox` (raedbh/spring-outbox on GitHub):**
It is a third-party library with limited production track record and no Spring official backing. Rolling a simple polling publisher is ~50 lines of code; a third-party library adds opaque transactional behavior and upgrade risk. Confidence: MEDIUM.

**Existing `PaymentProcess` table reuse decision:**
Per `PROJECT.md`, `PaymentProcess` already tracks payment jobs. Whether to repurpose it as the outbox record or create a dedicated `payment_outbox` table is an architecture decision, not a library decision. From a stack perspective, both use identical JPA entity + repository patterns.

---

### Load Testing

| Technology | Version | Purpose | Why |
|------------|---------|---------|-----|
| k6 | `grafana/k6:latest` (Docker) | Load test runner | JS-based test scripts, native Docker support, exports Prometheus metrics via `--out prometheus`. No JVM process — runs entirely outside the Spring Boot app. Standard in 2025 for API load testing. |

**k6 Setup Pattern for this project:**

Run k6 via Docker Compose as a one-shot service against the running Spring Boot container. Scripts live in `k6/scripts/`. Three scripts: `sync.js`, `outbox.js`, `kafka.js` — each targeting the same `/api/v1/payments/confirm` endpoint with the corresponding Spring profile active.

**Confidence:** HIGH — Official Docker image `grafana/k6` is actively maintained by Grafana Labs. k6 v0.46+ images include browser support but that's not needed here. `grafana/k6:latest` is sufficient.

**Do NOT** use JMeter. k6 scripts are version-controllable JavaScript, integrate better with Docker Compose, and produce Prometheus-compatible output that feeds the existing Micrometer/Prometheus stack.

---

### Kafka UI (Development Tooling — Not a Runtime Dependency)

| Technology | Version | Purpose | Why |
|------------|---------|---------|-----|
| Kafbat UI | `ghcr.io/kafbat/kafka-ui:latest` | Web UI for inspecting Kafka topics during dev | `provectuslabs/kafka-ui` has been abandoned. Kafbat is the community fork with active maintenance as of 2025. Add to `docker-compose.yml` under a `tools` profile so it doesn't start by default. |

**Confidence:** MEDIUM — Kafbat abandonment of provectuslabs confirmed by community sources; Kafbat active on GitHub. Not critical-path infrastructure.

---

## Alternatives Considered

| Category | Recommended | Alternative | Why Not |
|----------|-------------|-------------|---------|
| Outbox implementation | Custom `@Scheduled` polling | Debezium CDC | Requires Kafka Connect infra, overkill for single-instance portfolio |
| Outbox implementation | Custom `@Scheduled` polling | Spring Modulith | Architecture mismatch with existing Hexagonal design; adds module restructuring overhead |
| Kafka client | spring-kafka (Boot-managed 3.2.x) | Spring Cloud Stream | Additional abstraction layer (binders) adds complexity without benefit when you only have one broker and one topic pattern |
| Kafka broker Docker | `apache/kafka:3.9` | `apache/kafka:4.0` | Kafka 4.0 dropped ZooKeeper but is brand-new (released March 2025). Combined with Spring Kafka 3.2.x (compiled against Kafka client 3.x), version mismatch risk is unnecessary for a portfolio project |
| Kafka broker Docker | `apache/kafka:3.9` | `confluentinc/cp-kafka` | Confluent image adds licensing complexity and a heavier image. Apache image is sufficient for KRaft single-node dev. |
| Load testing | k6 | JMeter | JMeter XML scripts are not version-control friendly; k6 JS integrates with existing Docker Compose; Prometheus output is native |
| Kafka UI | Kafbat (`ghcr.io/kafbat/kafka-ui`) | `provectuslabs/kafka-ui` | provectuslabs/kafka-ui is abandoned as of 2024 |

---

## Gradle Dependency Additions

```groovy
// Kafka (version managed by Spring Boot 3.3.3 BOM → spring-kafka 3.2.x)
implementation 'org.springframework.boot:spring-boot-starter' // already present
implementation 'org.springframework.kafka:spring-kafka'

// Testcontainers Kafka (for integration tests — match existing testcontainersVersion = '1.19.8')
testImplementation "org.testcontainers:kafka:${testcontainersVersion}"
```

No additional version properties required for Kafka — `io.spring.dependency-management` resolves `spring-kafka` via the Spring Boot 3.3.3 BOM.

---

## Docker Compose Additions

```yaml
# Kafka broker (KRaft mode, no ZooKeeper)
kafka:
  image: apache/kafka:3.9.0
  environment:
    KAFKA_NODE_ID: 1
    KAFKA_PROCESS_ROLES: broker,controller
    KAFKA_LISTENERS: PLAINTEXT://:9092,CONTROLLER://:9093
    KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092
    KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
    KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
    CLUSTER_ID: "payment-platform-cluster-01"
  ports:
    - "9092:9092"

# Kafka UI (dev tooling only — start with --profile tools)
kafka-ui:
  image: ghcr.io/kafbat/kafka-ui:latest
  profiles: ["tools"]
  environment:
    KAFKA_CLUSTERS_0_NAME: local
    KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS: kafka:9092
  ports:
    - "8090:8080"
  depends_on:
    - kafka

# k6 load tester (run as one-shot — start with --profile loadtest)
k6:
  image: grafana/k6:latest
  profiles: ["loadtest"]
  volumes:
    - ./k6:/scripts
  environment:
    K6_PROMETHEUS_RW_SERVER_URL: http://prometheus:9090/api/v1/write
  command: run /scripts/sync.js  # override per strategy
```

---

## Sources

- Spring Kafka release blog (3.3.4, 3.2.8): https://spring.io/blog/2025/03/18/spring-kafka-4-0-0-M1-and-3-3-4-and-3-2-8-available-now/
- Spring Kafka 3.3.2 release notes: https://spring.io/blog/2025/01/22/spring-kafka-3/
- Spring Boot dependency versions appendix: https://docs.spring.io/spring-boot/appendix/dependency-versions/index.html
- Kafka 4.0 KRaft Docker: https://medium.com/@kinneko-de/kafka-4-kraft-docker-compose-874d8f1ffd9b
- Outbox Pattern polling vs CDC comparison: https://architectureway.dev/outbox-pattern
- Spring Modulith Event Externalization: https://spring.io/blog/2023/09/22/simplified-event-externalization-with-spring-modulith/
- Kafbat UI (active fork of provectuslabs/kafka-ui): https://github.com/kafbat/kafka-ui
- grafana/k6 Docker image: https://hub.docker.com/r/grafana/k6
- Testcontainers Kafka guide: https://testcontainers.com/guides/testing-spring-boot-kafka-listener-using-testcontainers/
- Outbox Pattern with Spring Boot and Debezium (infrastructure complexity): https://dev.to/raedobh/outbox-pattern-with-spring-boot-and-debezium-1od7
