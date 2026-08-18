# Technology Stack

> 최종 갱신: 2026-08-18 (STOCK-GATE-PER-PRODUCT — dlq 알람 그룹에 `ProductStockQuarantineBacklog` 추가, promtool 픽스처 26→27케이스, product-service 에 Kafka producer·에러 핸들러·격리 토픽 신설과 `spring-kafka-test` 테스트 의존 추가). 이전: 2026-08-14 (PG-VENDOR-SIGNAL-CONSOLIDATION — dlq 알람 그룹 서술에 앱 카운터 pg 분기가 FCG 결과 합산으로 바뀐 사실 반영, promtool 픽스처 25→26케이스). 이전: 2026-08-11 (PG-MESSAGE-DEDUPE-LAYER-REMOVAL — `spring-boot-starter-data-redis` 의존 사유 주석에서 pg-side dedupe 제거, payment-service 전용으로 정정). 이전: 2026-08-04 (BACKLOG-RESIDUE-CLEANUP ship — 정적 검출 기준선 억제 잔여 서술을 실제 상태(전량 해소)로 정정 + 검출 게이트 승격 판단을 대장 참조 대신 이 문서 안에 직접 서술). 이전: 2026-07-29 (LIVE-DRILL-FORMALIZATION — 라이브 검증 절차 문단 신설(진입점 스킬·캡처용 compose override·산출물 저장소 제외) + 스크립트 표에 `seed-stock.sh` 행 추가). 이전: 2026-07-03 (DOCS-CONSISTENCY-OVERHAUL Task 10 — stale 마커 게이트 재검증에서 신규 발견, redis-starter-data-redis 의존 사유 주석의 "payment-side EventDedupeStore" 표기 정정 — payment-service 는 해당 이름의 Redis 클래스가 없고 EventDedupeStore 는 pg-service 전용). 이전: 2026-07-03 (Task 9 — 스케줄러 활성화 매트릭스에 누락됐던 user-service 행 + 4서비스 공통 `DependencyHealthMetrics` 역할 반영, JaCoCo 정적 분석 행을 `TESTING.md` 참조로 축약(S4 중복 정리)), 2026-07-01 (context-update 헤더 동기화 — 알람 4그룹/Toxiproxy 드릴 본문은 ALERTING-RULES 6/27 + FAULT-INJECTION 6/30 ship 에서 이미 반영됨)

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
spring-boot-starter-data-redis       # StockCachePort (Lua atomic) + IdempotencyStore — payment-service 전용 (pg-service 는 dedupe 층 제거로 의존 해제)
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
| Redis dedupe | `redis:7.x` (alpine) | 6379 | checkout 멱등성 (payment-service 전용) |
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

**알람 규칙 (rule 평가만, Alertmanager 미도입)**: `prometheus.yml` `rule_files: /etc/prometheus/rules/*.yml` 로 `observability/prometheus/rules/*.yml`(observability compose 가 `rules` 디렉토리 마운트) 로드 → `/api/v1/rules`·`/api/v1/alerts` 평가/조회까지만(통지 채널 미연결). 운영 위험 4그룹 — **coordinator**(`KafkaCoordinatorTxnAbortRising`/`...LagHigh`/`KafkaBrokerUnavailable`: EOS txn abort·`events.confirmed` consumer lag·broker 가용성 backstop), **guard-skip**(`GuardSkipDangerousStatusHigh`: 종결 가드 위험 status skip 비율), **dlq**(`DlqAppCounterRising`/`DlqTopicOffsetRising`/`DlqCommandsConsumerLag`: 앱 카운터·`.dlq` offset 델타·`commands.confirm.dlq` 정체 독립 cross-check — 앱 카운터 pg 분기는 FCG 배선 후 `sum(increase(pg_final_confirmation_outcome_total[5m]))` 합산이다. 격리 전이만 세면 자동 승인·자동 실패로 끝난 소진 건이 신호에서 빠진다), **dlq 그룹의 `ProductStockQuarantineBacklog`**(상품 서비스 재고 확정 격리 토픽 `payment.events.stock-committed.dlq` 적체 — 음수 가드에 걸린 건은 이미 벤더 승인이 난 결제라 자동 복구 대상이 아니다. 관리자 재고 조정 직후 발화(이미 받아들인 발산)와 게이트 자체 결함을 가르는 기준은 `docs/smoke/alert-firing-check.md` 런북), **availability**(`ServiceDown`/`DependencyDown`/`DependencyHealthStale`: 서비스 프로세스 `up{job=~".*-service"}==0`·의존성 health 게이지 `dependency_up{component}==0`(4서비스 db + payment/pg redis 를 `DependencyHealthMetrics` 가 actuator 대신 직접 폴링 브리지, payment redis 는 dedupe/stock 2분리)·폴러 staleness `dependency_health_last_poll_timestamp_seconds`, 각 분기에 `absent()` backstop). 규칙은 `promtool test rules` 픽스처(`rules/tests/*.yml`, 27케이스)로 회귀 고정 — `scripts/smoke/alert-rules-promtool.sh`(Docker 경유, 라이브 불요)·`smoke-all.sh` Phase 1.3. ⚠️ broker 완전 정지 시 `kafka_brokers` 는 0 이 아니라 시리즈 소멸(absent) — backstop 은 `up==0`/`absent(kafka_brokers)` (PITFALLS #24).

**장애 주입 드릴 (Toxiproxy, 전용 프로파일)**: `docker/docker-compose.drill.yml`(+`toxiproxy.json`) override — 평상시 미기동, drill 기동 시에만 적용. kafka PROXY 리스너(9094) 추가 광고 + payment-service bootstrap 을 `toxiproxy:9094` 로 우회해 latency toxic 주입(admin API 8474). 단일 broker + payment 가 `commands.confirm` producer 겸 consumer 라 전역 지연이 produce/fetch 대칭 저하 → consumer-only lag 비대칭 미실현(피크 ~150 ≪ 임계)·EOS commit timeout 미발화가 구조적 한계 → 코디네이터/EOS 라이브 결정적 발화는 promtool + 통합테스트로 격하(`scripts/smoke/alert-firing-*.sh`, 가이드 `docs/smoke/alert-firing-check.md`). `KafkaBrokerUnavailable`·`DlqTopicOffsetRising` 는 라이브 발화 실측됨.

**라이브 검증 (모의 벤더 전 구간 구동)**: 결제 성공·실패와 보상·재시도 소진과 격리·자가 회복·재고 경합·중복 결제 차단·인프라 장애를 실제 스택에서 일으켜 화면과 대조하는 절차. 진입점은 `payment-live-drill` 스킬(`.claude/skills/payment-live-drill/`)이며 장면 정의·캡처 방식·구동 스크립트가 모두 그 안에 있다. 장면마다 기대 결과를 먼저 정의하고 실행 결과와 대조해 판정한다. 캡처용 관측 도구 익명 조회 override 는 `docker/docker-compose.live-drill.yml`(평상시 미적용, 검증 시에만 grafana 재기동에 얹는다). 리포트와 캡처 원본은 저장소 밖 `live-drill/`(`.gitignore`)에 남아 코드 이력에 섞이지 않는다.

## DB 마이그레이션 (Flyway)

스키마 위치가 두 패턴 — **payment/pg 는 `db/migration/`**(단일, seed 없음), **product/user 는 `db/schema/` + `db/seed/`** 분리(profile 별 `locations` 로 `docker` 프로필에서 seed 차단).

운영 가이드 상세(profile 설정, 부팅 동작, named volume 재사용 시 `MissingMigrationException` 3-step 대응, Testcontainers 격리)는 [`stack/flyway-operations.md`](stack/flyway-operations.md) 참고.

## 스케줄러 활성화 정책

**게이트 메커니즘**: payment-service 와 product-service 에 각각 `SchedulerConfig` 클래스가 있으며, `@EnableScheduling + @ConditionalOnProperty(name = "scheduler.enabled", havingValue = "true")` 조합으로 활성화된다. `matchIfMissing` 기본값(false)이므로 `scheduler.enabled` 프로퍼티가 없으면 `SchedulerConfig` 빈 자체가 등록되지 않아 모든 `@Scheduled` 컴포넌트가 미기동된다. 즉 **게이트는 worker 클래스가 아니라 `SchedulerConfig`** 다. **pg-service 는 이 게이트가 없다** — `PgServiceConfig`(`@Configuration @EnableScheduling`)가 `@ConditionalOnProperty` 없이 선언되어 있어 프로파일에 무관하게 항상 활성이다. eureka / gateway 는 `@Scheduled` 없음.

**서비스별 활성 매트릭스**:

| 서비스 | 게이트 | 기본(로컬) | docker 프로파일 |
|---|---|---|---|
| payment-service | `scheduler.enabled=true` 필요 | 비활성 | 활성 |
| product-service | `scheduler.enabled=true` 필요 | 비활성 | 활성 |
| user-service | `scheduler.enabled=true` 필요 | 비활성 | 활성 |
| pg-service | 없음 (항상 활성) | 활성 | 활성 |
| eureka / gateway | `@Scheduled` 없음 | 해당 없음 | 해당 없음 |

**스케줄러 역할별 목록**:
- payment-service: `DedupeCleanupWorker`(payment_event_dedupe 만료 행 청소) / `PaymentScheduler`(READY 만료) / `PaymentReconciler`(IN_PROGRESS 정체 복원) / `OutboxWorker`(outbox PENDING 폴링·Kafka 발행) / `PaymentStateMetrics`(결제 상태별 카운트 gauge 갱신, `@ConditionalOnProperty` 없이 빈 등록되나 `@EnableScheduling`이 SchedulerConfig 게이트 안에 있어 `scheduler.enabled=true` 필요) / `PaymentHealthMetrics`(stuck IN_PROGRESS·max-retry 이상 탐지 gauge 갱신, 동일 게이트) / `PaymentOutboxMetrics`(payment_outbox PENDING 지표 갱신, `@ConditionalOnProperty(scheduler.enabled=true)` 직접 부착) / `DependencyHealthMetrics`(의존성 가용성 폴링 게이지 `dependency_up{component}`, availability 알람 그룹 소비, 동일 게이트)
- pg-service: `PgOutboxPollingWorker`(outbox PENDING 폴링·Kafka 발행) / `PgInboxPollingWorker`(inbox PENDING·IN_PROGRESS 좀비 회수) / `PgOutboxMetrics`(outbox 지표 갱신, `@Scheduled(fixedDelay = 60_000)`) / `DependencyHealthMetrics`(의존성 가용성 폴링 게이지, availability 알람 그룹 소비 — 게이트 없이 항상 활성)
- product-service: `DedupeCleanupWorker`(stock_commit_dedupe 만료 행 청소) / `DependencyHealthMetrics`(의존성 가용성 폴링 게이지, availability 알람 그룹 소비, `scheduler.enabled=true` 필요)
- user-service: `DependencyHealthMetrics`(의존성 가용성 폴링 게이지, availability 알람 그룹 소비, `scheduler.enabled=true` 필요) — user-service 는 이 컴포넌트가 유일한 `@Scheduled`

## 빌드 / 검증

| 명령 | 동작 |
|---|---|
| `./gradlew build` | 컴파일 + 단위 테스트 + JaCoCo + checkstyle + spotbugs |
| `./gradlew test` | 단위 테스트만 (`integration` 태그 제외) |
| `./gradlew :<svc>:integrationTest` | `@Tag("integration")` 만 |
| `./scripts/compose-up.sh` | docker compose 전체 스택 기동 |
| `./scripts/smoke/infra-healthcheck.sh` | 인프라 + 서비스 살아있음 검사 |
| `./scripts/seed-stock.sh` | product RDB 재고를 redis-stock 선차감 캐시에 정렬 (기동 직후 1회 가정 — 운영 중 호출은 진행 중 결제의 차감분을 되돌릴 수 있다) |

## 정적 분석 도구

| 도구 | 버전 | 룰 |
|---|---|---|
| Checkstyle | 10.17.0 | `config/checkstyle/checkstyle.xml` + 커스텀 Check `config/checkstyle/custom-checks/` |
| SpotBugs | 6.0.9 | `config/spotbugs/spotbugs-exclude.xml` (main) / `spotbugs-exclude-test.xml` (test) |

**코드 스타일 5규칙 자동 검출** — `docs/context/conventions/code-style.md` 의 다섯 규칙을 checkstyle 로 검출한다. 모두 `severity=warning` 이라 빌드를 막지 않는다.

- 문자열 판정 3종(`RegexpSinglelineJava`): 타입 추론 키워드(`var`) 금지, `@Data` 금지, 공개 유스케이스·포트의 null 반환 금지
- 구조 판정 2종(커스텀 `TreeWalker` Check): 광범위 예외 삼킴 금지(`SwallowedBroadException`), try 블록 외부 변수 재할당 금지(`TryBlockExternalReassignment`)
- 커스텀 Check 는 root project 전용 sourceSet(`checkstyleCustomChecks`)에서 컴파일해 각 서비스 checkstyle classpath 에 얹는다. root 자신의 `checkstyleMain`/`checkstyleTest` 는 순환을 피해 비활성(root 에 `src/main/java` 없음 — 서비스 검사 범위에는 영향 없다)
- ArchUnit 은 기각했다 — 바이트코드 기반 의존 그래프만 다뤄 메서드 본문의 제어 흐름(catch 안에 재throw 가 있는가, 변수가 try 밖에서 선언됐는가)을 표현할 수 없다
- 도입 시점 기존 위반은 코드를 고쳐 전량 해소했다(BACKLOG-RESIDUE-CLEANUP) — `config/checkstyle/checkstyle-suppressions.xml` 에는 파일·행 지정 기준선 억제가 더 없고, 디렉토리 단위 블랑켓 억제(`dto`/`entity`/`infrastructure` 등 데이터 캐리어·어댑터 계층)와 `PublicUseCasePortNullReturn` 적용 범위를 `application/usecase`·`application/port` 로 좁히는 항목만 남는다
| JaCoCo | 0.8.11 | 값·정책 상세(측정 대상/제외/게이트 산정 근거/서비스별 minimum)는 [`TESTING.md`](TESTING.md) §JaCoCo 커버리지 정책 참고(SSOT) |

## CI 파이프라인 (GitHub Actions)

매 PR / main push 마다 **6서비스(payment / pg / product / user / gateway / eureka)를 각각 독립 파이프라인으로 fan-out** 한다.

- **`.github/workflows/ci.yml`** (진입) — 6서비스를 재사용 워크플로우로 각각 호출(`with: { service, has-integration }`, `secrets: inherit`) + 취합 `report` job.
- **`.github/workflows/_service-ci.yml`** (재사용, `workflow_call`) — 서비스 1개 파이프라인:
  - `build-test-lint` job(항상): `./gradlew :<svc>:build -x integrationTest`(컴파일+단위+JaCoCo+checkstyle+spotbugs) → reviewdog 서비스별 인라인(checkstyle/spotbugs) → JaCoCo XML·lint 요약 아티팩트 업로드 → 단위 JUnit Check 리포트 → JaCoCo HTML 아티팩트 → lint gate. **`-x integrationTest` 로 통합을 제외**(단위/통합 막대 분리, `check.dependsOn integrationTest` 끌림 차단).
  - `integration-test` job(`has-integration == true` 일 때만 = payment/pg/product/user): `./gradlew :<svc>:integrationTest`(`org.gradle.test-retry` 통합 한정 `maxRetries=2 maxFailures=3`, `DOCKER_API_VERSION=1.44`) → JUnit 리포트. gateway/eureka 는 통합 job 생략. **Testcontainers reuse 는 비활성** — 같은 job 내 여러 `@SpringBootTest` 클래스가 재사용 컨테이너의 더럽혀진 스키마에 Flyway 를 재적용하면 "non-empty schema but no schema history table" 로 컨텍스트 로드가 깨져, 정합성 우선으로 철회.
- **`agent-docs-check` job** — 6서비스 fan-out과 무관한 독립 job. `scripts/check-agent-docs.py`(지침 문서 참조 무결성·frontmatter·체크리스트 참조·중복 규칙·Mermaid 금지 문자·고아 문서 판정)를 실행해 결과를 job 로그와 워크플로우 요약에 남긴다. 스크립트가 종료 코드를 0으로 고정하므로 **머지를 막지 않는다** — 게이트 승격 여부는 오탐이 잦아드는지 운용 관찰 후 판단한다. 코드 스타일 5규칙(위 checkstyle `severity=warning`)도 같은 이유로 빌드를 막지 않는 상태라, 승격 판단이 두 검사에 함께 걸려 있다.
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
