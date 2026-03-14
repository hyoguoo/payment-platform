# Technology Stack

**Analysis Date:** 2026-03-14

## Languages

**Primary:**
- Java 21 - Server-side application logic, payment processing, and business logic

**Secondary:**
- YAML - Configuration files (Spring Boot profiles)
- SQL - Database initialization scripts in `src/main/resources/data.sql`

## Runtime

**Environment:**
- JVM (OpenJDK/Eclipse Temurin 21)

**Build System:**
- Gradle 8.10 - Configured in `build.gradle`
- Gradle Wrapper - `gradlew` executable included for standardized builds

## Frameworks

**Core:**
- Spring Boot 3.3.3 - Web application framework
- Spring Data JPA - Database abstraction and ORM
- Spring AOP - Aspect-oriented programming for cross-cutting concerns
- Spring Validation - Input validation framework
- Spring Actuator - Health checks and metrics exposure

**Template Engine:**
- Thymeleaf - Server-side template rendering

**API Documentation:**
- SpringDoc OpenAPI 2.5.0 - Auto-generated REST API documentation and Swagger UI
  - Configured in `src/main/resources/application.yml` with package scanning from `com.hyoguoo.paymentplatform`

**Testing:**
- JUnit 5 (Jupiter) - Test framework
- Spring Boot Test - Spring integration testing
- TestContainers 1.19.8 - Docker-based test containers for MySQL

**Build/Dev:**
- Gradle plugins:
  - `io.spring.dependency-management` 1.1.6 - Manages Spring dependencies
  - `org.springframework.boot` 3.3.3 - Spring Boot Gradle plugin
  - `jacoco` 0.8.11 - Code coverage reporting

## Key Dependencies

**Critical:**
- Spring Boot Starters (Web, Data JPA, Validation, AOP, Actuator)
- MySQL Connector/J - JDBC driver for MySQL database connections
- QueryDSL 5.0.0 - Type-safe query builder for JPA
  - Annotation processor generates Q-classes at compile time (excluded from JaCoCo coverage)

**Infrastructure:**
- Micrometer Prometheus Registry - Metrics export to Prometheus
- Logstash Logback Encoder 7.4 - Structured logging to Logstash in JSON format
- Lombok - Code generation for getters, setters, constructors, and logging

## Configuration

**Environment:**
- Spring profiles: `local`, `docker`, `test`
- Configuration files:
  - `src/main/resources/application.yml` - Base configuration
  - `src/main/resources/application-docker.yml` - Docker deployment overrides
  - `src/test/resources/application-test.yml` - Test environment setup
- Environment variables for sensitive data (Toss API secret key)
- `.env.secret` file support for local development

**Build:**
- JVM Options in Dockerfile: G1GC, parallel reference processing, 512MB min / 2GB max heap
- Gradle tasks:
  - `gradle build` - Full build with tests
  - `gradle test` - Run JUnit tests
  - `gradle jacocoTestReport` - Generate coverage reports
  - `gradle jacocoTestCoverageVerification` - Verify coverage thresholds

## Platform Requirements

**Development:**
- Java 21 toolchain (automatically enforced via Gradle)
- Gradle 8.10 (provided via wrapper)
- MySQL 5.7+ for local database
- Optional: Docker for containerized development

**Production:**
- Docker-based deployment
- Multi-stage Docker build (Gradle 8.10-jdk21 builder → Eclipse Temurin 21-jre-jammy runtime)
- Health check endpoint: `GET /actuator/health`
- Exposed port: 8080
- Target deployment environment: Docker with Compose orchestration

## Monitoring & Observability

**Metrics:**
- Micrometer Prometheus - Metrics registry and export
- Actuator endpoints: `/actuator/health`, `/actuator/info`, `/actuator/metrics`, `/actuator/prometheus`
- Custom annotations: `@Timed`, `@Counted` for method-level instrumentation

**Logging:**
- Logback with Spring Profile support
- Console appender for all profiles
- Logstash TCP socket appender for Docker profile (destination: `logstash:5050`)
- Log pattern includes trace ID and thread information
- Color output for console in development
- Structured JSON output to Logstash in Docker environment

## Version Information

**Application Version:** 2.0.0-SNAPSHOT (in `build.gradle`)
**Group:** com.hyoguoo
**Artifact:** payment-platform

---

*Stack analysis: 2026-03-14*
