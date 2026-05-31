# Technology Stack

> 최종 갱신: 2026-05-08 (STOCK-COMPENSATION-RECOVERY — redis-stock AOF `appendfsync=always` 반영)

## 언어 + 빌드

| 항목 | 값 |
|---|---|
| 언어 | Java 21 (`JavaLanguageVersion.of(21)`) |
| 빌드 | Gradle 8.14.4 (멀티 모듈, `settings.gradle` 6개) — wrapper 8.14.4 (Java 24 런타임 호환; toolchain 은 Java 21 유지) |
| Spring Boot | 3.4.4 (`spring-boot-dependencies` BOM) |
| Spring Cloud | 2024.0.0 (Eureka client / Gateway) |
| Lombok | compileOnly + annotationProcessor (전 모듈) |

## 비즈니스 서비스 의존 (4서비스 공통)

```
spring-boot-starter-web              # REST 진입점 (gateway 는 webflux 기반 spring-cloud-starter-gateway)
spring-boot-starter-data-jpa
spring-boot-starter-actuator         # /actuator/health · prometheus 스크랩
spring-boot-starter-data-redis       # StockCachePort (Lua atomic) + IdempotencyStore + pg/payment-side EventDedupeStore
spring-boot-starter-aop              # @PublishDomainEvent, @PaymentStatusChange
spring-cloud-starter-netflix-eureka-client  # docker 프로필에서 활성화

org.springframework.kafka:spring-kafka       # producer + @KafkaListener
org.flywaydb:flyway-core
org.flywaydb:flyway-mysql                    # MySQL 8 dialect
runtimeOnly com.mysql:mysql-connector-j

io.micrometer:micrometer-registry-prometheus
io.micrometer:micrometer-tracing-bridge-otel  # W3C Trace Context
io.opentelemetry:opentelemetry-exporter-otlp
net.logstash.logback:logstash-logback-encoder

# 테스트
spring-boot-starter-test
spring-boot-testcontainers
org.testcontainers:mysql
org.testcontainers:junit-jupiter
com.squareup.okhttp3:mockwebserver  # pg-service 의 외부 PG vendor HTTP 어댑터(HttpOperatorImpl) traceparent 전파 테스트 전용
```

서비스별 추가 의존:
- payment-service: thymeleaf, springdoc-openapi-starter-webmvc-ui, querydsl-jpa(:jakarta), caffeine, spring-cloud-starter-loadbalancer, spring-cloud-starter-openfeign (CLIENT-SIDE-LB Phase B)
- pg-service: springdoc-openapi-starter-webmvc-ui, FakePgGatewayStrategy 가 `pg.gateway.type=fake` 로 활성화
- product-service: querydsl-jpa(:jakarta)
- gateway: spring-cloud-starter-gateway (webflux 기반 라우팅 전용)
- eureka-server: spring-cloud-starter-netflix-eureka-server

## 인프라

| 컴포넌트 | 이미지 / 버전 | 호스트 포트 | 책임 |
|---|---|---|---|
| MySQL × 4 | `mysql:8.0` (linux/arm64) | 3306 / 3308 / 3309 / 3310 | 서비스별 독립 DB |
| Redis dedupe | `redis:7.x` (alpine) | 6379 | checkout 멱등성 + pg-service 메시지 중복 제거 |
| Redis stock | `redis:7.x` | 6380 | 재고 캐시 + Lua atomic dedup token (AOF `appendfsync=always` 운영 — fsync 매 명령, throughput trade-off 인정. `docker/docker-compose.infra.yml`) |
| Kafka | `confluentinc/cp-kafka` (KRaft 모드) | 9092 / 29092 | 메시지 브로커 |
| Eureka | (자체 모듈) | 8761 | 서비스 디스커버리 |
| Gateway | (자체 모듈) | 8090 | 단일 진입점 |

## 관측성 스택

| 컴포넌트 | 용도 | 호스트 포트 |
|---|---|---|
| Prometheus | 메트릭 스크랩 | 9090 |
| Grafana | 대시보드 | 3000 |
| Loki | 로그 집계 | 3100 |
| Tempo | 분산 트레이스 | 3200 |
| kafka-exporter | Kafka 메트릭 | 9308 |

각 서비스는 OTel exporter 로 traceparent 를 Tempo 에 전송, LogFmt 로그는 Loki 에 적재. 한 traceId 로 메트릭/로그/트레이스 교차 조회 가능.

## DB 마이그레이션 (Flyway)

4서비스 모두 동일 패턴 — `db/schema/`(스키마 baseline) + `db/seed/`(seed) 디렉토리 분리, profile 별 `locations` 로 `docker` 프로필에서 seed 를 차단한다.

운영 가이드 상세(profile 설정, 부팅 동작, named volume 재사용 시 `MissingMigrationException` 3-step 대응, Testcontainers 격리)는 [`stack/flyway-operations.md`](stack/flyway-operations.md) 참고.

## 빌드 / 검증

| 명령 | 동작 |
|---|---|
| `./gradlew build` | 컴파일 + 단위 테스트 + JaCoCo + checkstyle + spotbugs |
| `./gradlew test` | 단위 테스트만 (`integration` 태그 제외) |
| `./gradlew :<svc>:integrationTest` | `@Tag("integration")` 만 |
| `./scripts/compose-up.sh` | docker compose 전체 스택 기동 |
| `./scripts/smoke/infra-healthcheck.sh` | 인프라 + 서비스 살아있음 검사 |

## 정적 분석 도구

| 도구 | 버전 | 룰 |
|---|---|---|
| Checkstyle | 10.17.0 | `config/checkstyle/checkstyle.xml` |
| SpotBugs | 6.0.9 | `config/spotbugs/spotbugs-exclude.xml` (main) / `spotbugs-exclude-test.xml` (test) |
| JaCoCo | 0.8.11 | application/use case/domain 만 측정 (DTO/entity/infrastructure 제외). 설정은 루트 `build.gradle` `subprojects` 공통(4서비스). `jacocoTestReport` 가 `integrationTest` exec 를 조건부 합산(`tasks.findByName` 가드, payment/pg/product 보유). `jacocoTestCoverageVerification` 에 서비스별 LINE `minimum` 게이트(ext `jacoco.lineCoverageMinimum`, element=`BUNDLE`) — payment 0.89 / pg 0.91 / product 0.40 / user 0.0(측정 대상 미미) |

## 핵심 라이브러리 패턴

| 카테고리 | 라이브러리 | 사용 위치 |
|---|---|---|
| 메시지 직렬화 | Jackson + JsonSerializer/JsonDeserializer | Kafka producer/consumer |
| HTTP 클라이언트 (cross-service) | OpenFeign (`spring-cloud-starter-openfeign`) + `spring-cloud-starter-loadbalancer` | payment-service `ProductFeignClient` / `UserFeignClient` (B Phase) — `ErrorDecoder` 가 4xx/5xx → 도메인 예외 매핑 |
| HTTP 클라이언트 (vendor) | `RestClient` (Spring Framework 6.2 동기 client, `RestClient.Builder` auto-config) | pg-service `HttpOperatorImpl` — Toss / NicePay 외부 호출. `pg.http.{connect-timeout-millis: 3000, read-timeout-millis: 10000}` |
| Test HTTP server | OkHttp MockWebServer | pg-service `HttpOperatorImpl` traceparent 전파 contract test 한정 |
| Bean Validation | spring-boot-starter-validation | request DTO `@NotNull`/`@Min` |
| In-memory cache | Caffeine | payment-service 의 `IdempotencyStore` 일부 |
| Querying | QueryDSL 5.0.0 (jakarta classifier) | payment-service / product-service 동적 쿼리 |
