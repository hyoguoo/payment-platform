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
| Promtail | docker 로그 수집 → Loki | (내부) |

각 서비스는 OTel exporter 로 traceparent 를 Tempo 에 전송, LogFmt 로그는 Loki 에 적재. 한 traceId 로 메트릭/로그/트레이스 교차 조회 가능.

**트레이스 샘플링**: `management.tracing.sampling.probability` 기본 `${TRACING_SAMPLING_PROBABILITY:1.0}` (학습/데모 전량 export, 운영/벤치 시 env 로 하향). eureka 제외 5서비스.

**대시보드 2분할** (`observability/grafana/dashboards/`):
- `business-dashboard.json` — 결제 흐름 funnel(`payment_event_published_total`/`terminal_total`)·상태 전이(`payment_transition_*`)·상태 분포(`payment_state_current`)·격리(`payment_confirm_guard_skip_total` eager 6종 + `payment_state_current{status="QUARANTINED"}`)·벤더 latency(`toss_api_call_*`, prod 트래픽 의존)·DLQ(commands.confirm.dlq=consumer 메트릭 / confirmed.dlq=`kafka_topic_partition_current_offset` exporter)·outbox·cleanup·코디네이터(`kafka_producer_txn_*`).
- `system-dashboard.json` — `$application` 변수로 6서비스 JVM/GC/CPU/HTTP/Hikari/consumer lag.

**추적 진입(로그 기반)**: span 에 비즈니스 속성을 새기지 않는다. orderId 가 LogFmt 로그에 + MDC traceId 동반 → Loki 에서 orderId 검색 → derivedFields(traceId→Tempo) 점프. 컨슈머 처리 로그 traceId 연속성은 `KafkaConsumerConfig` listener observation 활성으로 보장(복구/좀비 경로 포함).

**exemplar / 서비스 그래프**: Prometheus `--enable-feature=exemplar-storage` + 앱 percentiles-histogram(payment·pg) + Grafana `exemplarTraceIdDestinations` 로 latency 패널→트레이스 클릭 점프. Tempo `metrics_generator`(service-graphs + span-metrics) → Prometheus `remote_write`(out-of-order window) 로 서비스 토폴로지(`traces_service_graph_*`)·span RED(`traces_spanmetrics_*`) 생성.

## DB 마이그레이션 (Flyway)

4서비스 모두 동일 패턴 — `db/schema/`(스키마 baseline) + `db/seed/`(seed) 디렉토리 분리, profile 별 `locations` 로 `docker` 프로필에서 seed 를 차단한다.

운영 가이드 상세(profile 설정, 부팅 동작, named volume 재사용 시 `MissingMigrationException` 3-step 대응, Testcontainers 격리)는 [`stack/flyway-operations.md`](stack/flyway-operations.md) 참고.

## 스케줄러 활성화 정책

**게이트 메커니즘**: payment-service 와 product-service 에 각각 `SchedulerConfig` 클래스가 있으며, `@EnableScheduling + @ConditionalOnProperty(name = "scheduler.enabled", havingValue = "true")` 조합으로 활성화된다. `matchIfMissing` 기본값(false)이므로 `scheduler.enabled` 프로퍼티가 없으면 `SchedulerConfig` 빈 자체가 등록되지 않아 모든 `@Scheduled` 컴포넌트가 미기동된다. 즉 **게이트는 worker 클래스가 아니라 `SchedulerConfig`** 다.

**서비스별 활성 매트릭스**:

| 서비스 | 프로파일 | scheduler.enabled | `@Scheduled` 기동 |
|---|---|---|---|
| payment-service | docker | `application-docker.yml` 에 `true` | 활성 |
| payment-service | benchmark | `application-benchmark.yml` 에 `true` | 활성 |
| payment-service | 기본(로컬) | 미설정 | 비활성 |
| product-service | docker | `application-docker.yml` 에 `true` (CLEANUP-BATCH-D Task 3 에서 추가) | 활성 |
| product-service | 기본(로컬) | 미설정 | 비활성 |

**스케줄러 역할별 목록**:
- payment-service: `DedupeCleanupWorker`(payment_event_dedupe 만료 행 청소) / `PaymentScheduler`(READY 만료) / `PaymentReconciler`(IN_PROGRESS 정체 복원) / `OutboxAsyncConfirmService` outbox 폴링
- product-service: `DedupeCleanupWorker`(stock_commit_dedupe 만료 행 청소)

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
| JaCoCo | 0.8.11 | application/use case/domain 만 측정 (DTO/entity/infrastructure 제외). 설정은 루트 `build.gradle` `subprojects` 공통(4서비스). **게이트·리포트 모두 단위 `test` exec 기준**(통합 exec 미합산) — CI build job 이 `build -x integrationTest` 로 돌아 게이트가 단위만으로 평가되기 때문. `jacocoTestCoverageVerification` 에 서비스별 LINE `minimum` 게이트(ext `jacoco.lineCoverageMinimum`, element=`BUNDLE`) — payment 0.86 / pg 0.93 / product 0.43 / user 0.97 / gateway·eureka 0.0(측정 대상 클래스 0). 통합테스트 정합성은 게이트가 아닌 CI `integration-test` job 통과(pass/fail)로 보호 |

## CI 파이프라인 (GitHub Actions)

매 PR / main push 마다 **6서비스(payment / pg / product / user / gateway / eureka)를 각각 독립 파이프라인으로 fan-out** 한다.

- **`.github/workflows/ci.yml`** (진입) — 6서비스를 재사용 워크플로우로 각각 호출(`with: { service, has-integration }`, `secrets: inherit`) + 취합 `report` job.
- **`.github/workflows/_service-ci.yml`** (재사용, `workflow_call`) — 서비스 1개 파이프라인:
  - `build-test-lint` job(항상): `./gradlew :<svc>:build -x integrationTest`(컴파일+단위+JaCoCo+checkstyle+spotbugs) → reviewdog 서비스별 인라인(checkstyle/spotbugs) → JaCoCo XML·lint 요약 아티팩트 업로드 → 단위 JUnit Check 리포트 → JaCoCo HTML 아티팩트 → lint gate. **`-x integrationTest` 로 통합을 제외**(단위/통합 막대 분리, `check.dependsOn integrationTest` 끌림 차단).
  - `integration-test` job(`has-integration == true` 일 때만 = payment/pg/product/user): `./gradlew :<svc>:integrationTest`(`org.gradle.test-retry` 통합 한정 `maxRetries=2 maxFailures=3`, `DOCKER_API_VERSION=1.44`) → JUnit 리포트. gateway/eureka 는 통합 job 생략. **Testcontainers reuse 는 비활성** — 같은 job 내 여러 `@SpringBootTest` 클래스가 재사용 컨테이너의 더럽혀진 스키마에 Flyway 를 재적용하면 "non-empty schema but no schema history table" 로 컨텍스트 로드가 깨져, 정합성 우선으로 철회.
- **취합 `report` job** — `needs` 6서비스 + `always() && pull_request`. 6서비스 커버리지/lint 아티팩트를 `actions/github-script` + `.github/scripts/report-comment.js` 로 **단일 PR 통합 코멘트**(커버리지 + 테스트수 + lint 요약, `update-comment` 로 난립 방지)로 조립.
- `spotbugs-to-rdjsonl.py`(spotbugs→reviewdog 변환)는 `_service-ci.yml` 내 서비스별 호출. Discord 알림 없음.
- **머지 차단**은 각 서비스 `build-test-lint` + `integration-test` job 결과로 결정(`report` 의 `always()` 는 코멘트 전용). GitHub branch protection 의 required status checks 에 각 job 등록이 전제.

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
