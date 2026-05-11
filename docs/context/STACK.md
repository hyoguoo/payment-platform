# Technology Stack

> 최종 갱신: 2026-05-08 (STOCK-COMPENSATION-RECOVERY — redis-stock AOF `appendfsync=always` 반영)

## 언어 + 빌드

| 항목 | 값 |
|---|---|
| 언어 | Java 21 (`JavaLanguageVersion.of(21)`) |
| 빌드 | Gradle (멀티 모듈, `settings.gradle` 6개) |
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

**모델**: 4서비스 모두 동일 패턴.

```
<service>/src/main/resources/
├── db/schema/
│   └── V1__<bounded>_schema.sql   # 단일 schema baseline
└── db/seed/
    └── V2__seed_*.sql             # (필요 시) seed 데이터 — INSERT IGNORE 멱등
```

**디렉토리 분리 룰 (CLEANUP-BATCH-A §1.3)**:
- schema SQL 은 `db/schema/` 에, seed SQL 은 `db/seed/` 에 배치한다.
- V3 이후 신규 schema migration 은 `db/schema/` 에 추가한다.
- `db/migration/` 은 더 이상 사용하지 않는다.

**profile 별 locations 설정**:
```yaml
# default / test profile — application.yml
spring:
  flyway:
    enabled: true
    locations: classpath:db/schema,classpath:db/seed
  jpa:
    hibernate:
      ddl-auto: validate    # Flyway 가 baseline 적용 후 JPA 가 컬럼 검증

# docker profile — application-docker.yml (override)
spring:
  flyway:
    locations: classpath:db/schema   # seed 제외 — 운영 DB 에 초기 데이터 삽입 방지
```

- `default` / `test` profile: `db/schema` + `db/seed` 모두 적용 (테스트 픽스처 포함)
- `docker` profile (`SPRING_PROFILES_ACTIVE: docker`): `db/schema` 만 적용 → V2 seed row 차단
- `docker-compose.apps.yml` 의 4 비즈니스 서비스가 모두 `SPRING_PROFILES_ACTIVE: docker` 로 기동하므로 이 override 가 운영에서 실제 활성화됨.

**부팅 시 동작**:
1. DataSource 준비 → Flyway `migrate()` 자동 호출
2. `flyway_schema_history` 없으면 V1 부터 적용 + history row 추가
3. JPA EntityManager 가 `@Entity` 와 실제 schema 일치 검증

**버전 추적**: 각 DB(`payment-platform`, `pg`, `product`, `user`) 안 `flyway_schema_history` 테이블.
```sql
SELECT version, script, installed_on, success FROM flyway_schema_history ORDER BY installed_rank;
```

**불변성**: 이미 적용된 V 파일 내용은 절대 변경 금지 (checksum 충돌). 변경 필요 시 새 V 번호 추가.

**운영 적용 시**:
- 신규 DB → `migrate()` 가 V1 부터 자동 적용
- 기존 DB 에 Flyway 도입 → `spring.flyway.baseline-on-migrate: true` + `baseline-version: 0` 옵션으로 기존 schema 를 baseline 으로 잡고 그 위에 V1+ 적용. 본 프로젝트는 default(false) 로 깨끗한 시작 가정

**named volume 재사용 시 MissingMigrationException 대응 (3-step 가이드)**:

시나리오: `docker-compose down` 만 실행 시 `mysql-product-data` / `mysql-user-data` named volume 이 살아남는다 (`docker-compose.infra.yml` 에 `external: true` 없음). CLEANUP-BATCH-A 적용 후 재기동 시 기존 볼륨의 `flyway_schema_history` 에 V2 record 가 `db/seed` 경로로 기록돼 있는데, `docker` profile 은 `db/schema` 만 스캔 → **`MissingMigrationException`** 으로 부팅 실패.

`spring.flyway.ignore-migration-patterns` 기본값: `*:future` 만 ignore, `*:missing` 은 **fail** (기본 동작). V2 record 가 볼륨에 남아있으면 수동 처리 없이는 부팅 불가.

**Step 1 (권장 — 학습 환경)**: named volume 전체 또는 개별 삭제 후 재기동.
```bash
# 전체 미사용 볼륨 정리
docker volume prune

# 또는 특정 볼륨만 삭제
docker volume rm payment-platform_mysql-product-data
docker volume rm payment-platform_mysql-user-data
```

**Step 2 (볼륨 보존 필요 시)**: `flyway_schema_history` V2 record 수동 삭제 후 재기동.
```sql
-- mysql-product 또는 mysql-user 컨테이너 접속 후 실행
DELETE FROM flyway_schema_history WHERE version = '2';
```

**Step 3 (일시 우회 옵션 — 운영 비권장)**: `spring.flyway.ignore-migration-patterns: '*:missing'` 임시 설정 후 재기동.
- `db/schema` 에 V2 파일이 없으므로 missing check 를 skip 해 부팅 허용.
- 운영 누적 DB 에 적용 시 실제 누락된 migration 도 무시될 수 있어 **운영 환경에서는 비권장**.

**운영 학습용 가정**: 본 프로젝트는 학습 / 데모 단계로 운영 누적 DB 가 없다. 운영 도입 시 위 가이드 외에 추가 절차 (DB 백업 / migration 사전 검증 / `baseline-on-migrate` 전략 등) 가 필요하다.

**Testcontainers**: 매 테스트마다 새 MySQL 컨테이너 → V1 부터 자동 적용. `ddl-auto: validate` 로 테스트 schema 검증. `product-service` 의 `FlywayDockerProfileTest` 는 `@ActiveProfiles("docker")` 로 docker profile 의 seed 차단 동작을 통합 검증한다 (CBA-7).

**통합 테스트 환경 격리**: `@Tag("integration")` 통합 테스트는 `application-test.yml` 의 `spring.flyway.enabled: false` + `jpa.hibernate.ddl-auto: create-drop` 으로 운영하며, `BaseIntegrationTest` 의 Testcontainers MySQL 위에서 JPA 가 `@Entity` 기반 schema 를 생성한다. 의도: Flyway ↔ JPA 순환 의존(`Circular depends-on relationship between 'flyway' and 'entityManagerFactory'`) 회피 + 테스트 격리.
- `@Sql("/data-test.sql")` 시드: 현재 NOOP(`SELECT 1`). MSA 분리 후 user/product 데이터는 별도 서비스 책임이라 본 시드는 빈 자리만 유지

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
| JaCoCo | 0.8.11 | application/use case/domain 만 측정 (DTO/entity/infrastructure 제외) |

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
