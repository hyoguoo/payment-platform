# Technology Stack

**Analysis Date:** 2026-03-18

## Languages

**Primary:**
- Java 21 - All production and test code (toolchain `JavaLanguageVersion.of(21)`)

## Runtime

**Environment:**
- JVM — Eclipse Temurin 21 JRE (Jammy) in production Docker image (`eclipse-temurin:21-jre-jammy`)
- JVM flags (Docker): G1GC, MaxGCPauseMillis=100, ParallelRefProcEnabled

**Build Image:**
- `gradle:8.10-jdk21` (multi-stage Dockerfile)

**Package Manager:**
- Gradle 8.10 (wrapper)
- `settings.gradle`: rootProject.name = `payment-platform`
- `build.gradle`: group `com.hyoguoo`, version `2.0.0-SNAPSHOT`

## Frameworks

**Core:**
- Spring Boot 3.3.3 — application framework
- Spring Web MVC — REST controllers (`spring-boot-starter-web`)
- Spring Data JPA — ORM layer (`spring-boot-starter-data-jpa`)
- Spring Validation — bean validation (`spring-boot-starter-validation`)
- Spring AOP — cross-cutting aspects (`spring-boot-starter-aop`)
- Spring Actuator — health/metrics endpoints (`spring-boot-starter-actuator`)
- Thymeleaf — admin UI templates (`spring-boot-starter-thymeleaf`)

**Build Plugins:**
- `org.springframework.boot` 3.3.3
- `io.spring.dependency-management` 1.1.6
- `jacoco` — coverage reports (toolVersion `0.8.11`)

## Key Dependencies

**Critical:**
- `spring-boot-starter-data-jpa` — all repository adapters
- `com.mysql:mysql-connector-j` (runtime) — MySQL 8.0 driver
- `org.projectlombok:lombok` — `@Getter`, `@Builder`, `@RequiredArgsConstructor`, `@Slf4j` throughout
- `com.querydsl:querydsl-jpa:5.0.0:jakarta` + `querydsl-apt:5.0.0:jakarta` — admin query in `AdminPaymentQueryRepositoryImpl`
- `jakarta.annotation:jakarta.annotation-api` / `jakarta.persistence:jakarta.persistence-api` (annotationProcessor)

**Infrastructure:**
- `org.springdoc:springdoc-openapi-starter-webmvc-ui:2.5.0` — Swagger UI at `/swagger-ui.html`, JSON at `/api-docs/json`
- `io.micrometer:micrometer-registry-prometheus` — Prometheus metrics export
- `net.logstash.logback:logstash-logback-encoder:7.4` — structured JSON logging to Logstash (docker profile)

**Testing:**
- `spring-boot-starter-test` — JUnit 5, Mockito, AssertJ
- `spring-boot-testcontainers` — testcontainers Spring integration
- `org.testcontainers:mysql:1.20.4` — MySQL container for integration tests
- `org.testcontainers:junit-jupiter:1.20.4` — JUnit Jupiter extension
- `org.junit.platform:junit-platform-launcher` (testRuntime)

**Development:**
- `spring-boot-devtools` (developmentOnly) — hot reload

## Configuration

**Application Properties:**

| File | Profile | Purpose |
|------|---------|---------|
| `src/main/resources/application.yml` | default (`local`) | base config, Kafka, JPA, payment gateway, scheduler, metrics |
| `src/main/resources/application-docker.yml` | `docker` | MySQL datasource, logging levels, Kafka `kafka:9092`, scheduler, Actuator |
| `src/main/resources/application-benchmark.yml` | `benchmark` | HikariCP pool-30, FakeToss delays, outbox scheduler enabled |

**Key Config Properties:**

```yaml
spring.payment.async-strategy: sync | outbox   # strategy switch (default: outbox)
payment.gateway.type: TOSS
payment.gateway.toss.base-url: https://api.tosspayments.com
payment.gateway.toss.connect-timeout: 3000
payment.gateway.toss.read-timeout: 10000
scheduler.enabled: true                                  # gates SchedulerConfig
scheduler.outbox-worker.fixed-delay-ms: 1000
scheduler.outbox-worker.batch-size: 10
scheduler.outbox-worker.parallel-enabled: false
scheduler.outbox-worker.in-flight-timeout-minutes: 5
spring.myapp.toss-payments.http.read-timeout-millis: 30000
spring.myapp.toss-payments.fake.min-delay-millis: 100   # benchmark only
spring.myapp.toss-payments.fake.max-delay-millis: 300   # benchmark only
```

**Environment Variables Required:**
- `TOSS_SECRET_KEY` — Toss Payments secret key (docker profile: `${TOSS_SECRET_KEY}`)
- `DB_USERNAME` / `DB_PASSWORD` — MySQL credentials (docker profile)

**Build:**
- `build.gradle` — single-module Gradle build
- JaCoCo HTML+XML reports; coverage verification excludes `Q*`, `dto`, `entity`, `exception`, `infrastructure`, `enums` packages
- Test task excludes `integration` tag by default (`excludeTags 'integration'`)

## Spring Profiles

| Profile | Activation | Notes |
|---------|-----------|-------|
| `local` (default) | `spring.profiles.default: local` | base profile, no datasource config |
| `docker` | `SPRING_PROFILES_ACTIVE=docker` | MySQL datasource, Logstash, Prometheus |
| `benchmark` | `SPRING_PROFILES_ACTIVE=docker,benchmark` | docker-compose app service; activates `BenchmarkConfig` (FakeTossHttpOperator), HikariCP pool-30 |
| `test` | test classes | Testcontainers, debug logging |

## Platform Requirements

**Development:**
- Java 21 SDK
- Gradle 8.10 (wrapper provided)
- Docker + Docker Compose (for MySQL/Kafka/ELK infra)

**Production:**
- Docker image: `eclipse-temurin:21-jre-jammy`
- JVM heap: `-Xms512m -Xmx2048m` (container default), `-Xms512m -Xmx1024m` (docker-compose)
- Tomcat: max 200 threads, min-spare 25, accept-count 100 (docker profile)

---

*Stack analysis: 2026-03-18*
