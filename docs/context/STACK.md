# Technology Stack

버전 표(Java, Gradle, Spring Boot, Spring Cloud, Checkstyle, ArchUnit)와 검증 명령(단위·정적 분석·통합 테스트, `--rerun-tasks`)은
[스택 프로필](../../.agents/profiles/stack.md)이 정본이다. 이 문서는 의존성의 쓰임, 인프라 구성, 관측·알람, Flyway 운영을 다룬다.
검증 hook은 [AGENTS.md의 검증 hook](../../AGENTS.md#검증-hook)과 스택 프로필의 "검증 hook 설정"을, 에이전트 설정은 [.agents/README.md](../../.agents/README.md)를 본다.

## 의존성

멀티 모듈 6개(`settings.gradle`)이고 모든 모듈이 Lombok을 `compileOnly` + `annotationProcessor`로 쓴다(루트 `build.gradle` subprojects).

비즈니스 서비스 4개(payment, pg, product, user) 공통:

```
spring-boot-starter-web
spring-boot-starter-data-jpa
spring-boot-starter-actuator                 # /actuator/health · prometheus 스크랩
spring-cloud-starter-netflix-eureka-client   # apps compose가 EUREKA_CLIENT_ENABLED=true로 켠다
org.flywaydb:flyway-core, flyway-mysql       # MySQL 8 dialect
runtimeOnly com.mysql:mysql-connector-j      # pg·product·user는 implementation
io.micrometer:micrometer-registry-prometheus
io.micrometer:micrometer-tracing-bridge-otel # W3C Trace Context
io.opentelemetry:opentelemetry-exporter-otlp

# 테스트
spring-boot-starter-test (루트), spring-boot-testcontainers, org.testcontainers:mysql, junit-jupiter
archunit-junit5 (루트, 레이어 규칙)
```

서비스별 추가 의존:

| 서비스 | 추가 의존 |
|---|---|
| payment-service | `spring-boot-starter-aop`(`@PublishDomainEvent`, `@PaymentStatusChange`), `spring-boot-starter-validation`, `spring-boot-starter-data-redis`(재고 캐시 Lua + 멱등성 저장소), `spring-kafka`, `caffeine`(`IdempotencyStore` 구현), `querydsl-jpa:jakarta`(관리자 조회), `spring-cloud-starter-openfeign` + `spring-cloud-starter-loadbalancer`(`ProductFeignClient`·`UserFeignClient`), thymeleaf, springdoc-openapi, `logstash-logback-encoder`, 테스트 `spring-kafka-test` |
| pg-service | `spring-boot-starter-aop`, `spring-kafka`, 테스트 `okhttp3:mockwebserver`(`HttpOperatorImpl` traceparent 전파 테스트). `FakePgGatewayStrategy`는 `pg.gateway.type=fake`일 때 활성화 |
| product-service | `spring-kafka`(재고 확정 소비·격리 토픽), 테스트 `spring-kafka-test` |
| user-service | 공통 의존만 |
| gateway | `spring-cloud-starter-gateway`(webflux 기반 라우팅 전용), eureka client, actuator, prometheus, `logstash-logback-encoder`, OTel 트레이싱 |
| eureka-server | `spring-cloud-starter-netflix-eureka-server`, actuator, prometheus, `logstash-logback-encoder` |

pg-service는 Redis 의존이 없다. Redis는 payment-service 전용이다.

## 핵심 라이브러리 패턴

| 카테고리 | 라이브러리 | 사용 위치 |
|---|---|---|
| HTTP 클라이언트 (서비스 간) | OpenFeign + `spring-cloud-starter-loadbalancer` | payment-service `ProductFeignClient` / `UserFeignClient`. `ProductHttpAdapter`·`UserHttpAdapter`가 감싼다 |
| HTTP 클라이언트 (벤더) | `RestClient` | pg-service `HttpOperatorImpl`, `TossPaymentGatewayStrategy`. `pg.http.connect-timeout-millis` 기본 3000, `pg.http.read-timeout-millis` 기본 10000 (`pg-service/.../application.yml:118-119`) |
| 테스트 HTTP 서버 | OkHttp MockWebServer | pg-service `HttpOperatorImpl` traceparent 전파 테스트 |
| 메모리 캐시 | Caffeine | payment-service `IdempotencyStoreImpl` |
| 동적 쿼리 | QueryDSL 5.0.0 (jakarta) | payment-service `AdminPaymentQueryRepositoryImpl` |

## 인프라

컨테이너 이름과 포트는 `docker/docker-compose.infra.yml`, 관측 스택은 `docker/docker-compose.observability.yml`이 정의한다.

| 컴포넌트 | 컨테이너 | 이미지 | 호스트 포트 | 책임 |
|---|---|---|---|---|
| MySQL × 4 | `payment-mysql-payment` / `-pg` / `-product` / `-user` | `mysql:8.0` | 3306 / 3308 / 3309 / 3310 | 서비스별 독립 DB |
| Redis dedupe | `payment-redis-dedupe` | `redis:7.2-alpine` | 6379 | 체크아웃 멱등성(payment-service 전용). 영속화 끔(`--save "" --appendonly no`) |
| Redis stock | `payment-redis-stock` | `redis:7.2-alpine` | 6380 | 재고 선차감 캐시 + Lua. AOF `appendfsync always`(매 명령 fsync, 처리량을 내주고 내구성을 얻는다) |
| Kafka | `payment-kafka` | `confluentinc/cp-kafka:7.6.1` (KRaft, 단일 broker) | 9092 / 29092 | 메시지 브로커. 토픽 자동 생성 끔, replication-factor 1 |
| Eureka | `payment-eureka` | 자체 모듈 | 8761 | 서비스 디스커버리 |
| Gateway | (apps compose) | 자체 모듈 | 8090(컨테이너 8080) | 단일 진입점 |

서비스 컨테이너 포트(`docker/docker-compose.apps.yml`): payment-service 8080, pg-service 8082, product-service 8083, user-service 8084.
인프라 compose의 네트워크 `payment-infra-network`는 외부 네트워크이고 볼륨은 `kafka-data`, `redis-*-data`, `mysql-*-data`다.

### Compose 파일별 용도

| 파일 | 용도 |
|---|---|
| `docker-compose.infra.yml` | MySQL, Redis, Kafka, Eureka |
| `docker-compose.apps.yml` | 비즈니스 서비스 4개 + Gateway. 전부 `SPRING_PROFILES_ACTIVE: docker` |
| `docker-compose.observability.yml` | Prometheus, Alertmanager, Grafana, Loki, Tempo, Promtail, kafka-exporter |
| `docker-compose.smoke.yml` | 스모크 override. pg-service를 `PG_GATEWAY_TYPE=fake`로 강제하고 샘플링을 1.0으로 고정한다 |
| `docker-compose.benchmark.yml` | k6 부하 측정 override. Fake 지연·실패율, 단축 리컨실러, 커넥션 풀·JVM 옵션을 주입한다 |
| `docker-compose.quality.yml` | 로컬 품질 검증 override. 복구 기본값, 고정 heap, 외부 통지 없는 Alertmanager 설정(`observability/alertmanager/quality.yml`) |
| `docker-compose.scaleout.yml` | 공유 자원 스케일아웃 측정 override. mysql-payment binlog + 읽기 복제본, Redis 클러스터 노드 |
| `docker-compose.cpulimit.yml` | payment-service 인스턴스당 CPU 상한을 거는 스케일아웃 판정 override |
| `docker-compose.drill.yml` + `toxiproxy.json` | Toxiproxy 장애 주입 override. 평상시 미적용 |
| `docker-compose.live-drill.yml` | 라이브 검증 캡처용 Grafana 익명 조회 override. 평상시 미적용 |

override 파일은 단독으로 쓰지 못하고 infra·apps 위에 얹는다.

## 빌드·실행 보조 명령

빌드·검증 명령은 [스택 프로필](../../.agents/profiles/stack.md)의 "검증 순서"를 따른다. 아래는 그 밖의 도구다.

| 명령 | 동작 |
|---|---|
| `./scripts/compose-up.sh` | docker compose 전체 스택 기동. `--mode fake`(Fake PG), `--mode quality`(품질 검증 구성), `--skip-obs`, `--reset-db`, `--with-smoke` 옵션이 있다 |
| `bash scripts/compose-up.sh --mode quality` | Fake 100~300ms·복구 기본값·고정 heap·로컬 알림의 품질 검증 구성. `--skip-obs` 거부, 재고 자동 재시드 생략 |
| `python3 scripts/quality/run.py --run-id <id>` | 실행별 시도·환경·자원·주문/재고 snapshot·판정 보존. `--fault`는 payment 강제 종료 포함, 전용 시험 환경에서만 실행 |
| `./scripts/smoke/infra-healthcheck.sh` | 인프라 + 서비스 살아있음 검사 |
| `bash scripts/smoke-all.sh` | 스모크 전체(헬스, 토픽, 알람 규칙 promtool 등) |
| `./scripts/test-hooks.sh` | 검증 hook의 판정을 픽스처로 검증 (CI `hook-guardrail-check` 게이트가 실행) |
| `python3 scripts/ci-integration-shard.py <service> <shard> <count>` | 컴파일된 최상위 테스트 클래스를 CI 통합 테스트 shard별 Gradle `--tests` 인자로 분할 |
| `./scripts/seed-stock.sh` | product RDB 재고를 redis-stock 선차감 캐시에 정렬 (기동 직후 1회 가정 — 운영 중 호출은 진행 중 결제의 차감분을 되돌릴 수 있다) |

품질 기준과 재현 절차는 [로컬 운영 기준](../quality/RUNBOOK.md)을 따른다.
기존 벤치마크의 단축 리컨실러 설정과 quality 구성을 섞지 않는다.
quality는 `COMPOSE_PROJECT_NAME`별 전용 네트워크와 named volume을 사용하며 기동 시 재고를 덮어쓰지 않는다.
컨테이너 이름과 호스트 포트는 기존 설정을 공유하므로 다른 프로젝트를 동시에 기동하지 않는다.

## 관측성 스택

| 컴포넌트 | 용도 | 호스트 포트 |
|---|---|---|
| Prometheus (`v2.51.2`) | 메트릭 스크랩, 알람 규칙 평가 | 9090 |
| Alertmanager (`v0.27.0`) | 알람 통지(Discord 웹훅) | 9093 |
| Grafana (`10.4.2`) | 대시보드 | 3000 |
| Loki (`3.0.0`) | 로그 집계 | 3100 |
| Tempo (`2.4.2`) | 분산 트레이스. OTLP 수신 4317(gRPC) / 4318(HTTP) | 3200 |
| kafka-exporter (`v1.7.0`) | Kafka 메트릭 | 9308 |
| Promtail (`3.0.0`) | docker 로그 수집 → Loki | (없음) |

각 서비스는 OTel exporter로 traceparent를 Tempo에 전송하고 로그는 Loki에 적재한다. 한 traceId로 메트릭·로그·트레이스를 교차 조회할 수 있다.

**트레이스 샘플링**: `management.tracing.sampling.probability`는 `${TRACING_SAMPLING_PROBABILITY:1.0}`이다(학습·데모는 전량 export, 부하 측정은 env로 낮춘다). eureka-server를 뺀 5서비스에 있다.

**대시보드 2분할** (`observability/grafana/dashboards/`):
- `business-dashboard.json` — 결제 흐름(`payment_event_published_total`/`terminal_total`), 상태 전이(`payment_transition_*`), 상태 분포(`payment_state_current`), 격리(`payment_quarantined_total`, `payment_confirm_guard_skip_total`, 선차감 미회수 `stock_retention_unrecovered_total`), 벤더 latency(`toss_api_call_*`), DLQ(`commands.confirm.dlq`는 consumer 메트릭, `events.confirmed.dlq`는 `kafka_topic_partition_current_offset`), outbox(`payment_outbox.*`·`pg_outbox.*`), dedupe cleanup 워커, Kafka 트랜잭션 코디네이터(`kafka_producer_txn_*`).
- `system-dashboard.json` — `$application` 변수(`label_values(application)`)로 서비스를 고르는 JVM·GC·CPU·HTTP·Hikari·consumer lag.

**추적 진입(로그 기반)**: span에 비즈니스 속성을 새기지 않는다. orderId가 LogFmt 로그에 MDC traceId와 함께 남으므로 Loki에서 orderId를 검색하고 `derivedFields`(traceId → Tempo)로 점프한다. 컨슈머 처리 로그의 traceId 연속성은 `KafkaConsumerConfig`의 listener observation 활성(`setObservationEnabled(true)`)으로 보장한다.

**exemplar / 서비스 그래프**: Prometheus `--enable-feature=exemplar-storage`, payment·pg의 `percentiles-histogram`, Grafana `exemplarTraceIdDestinations`로 latency 패널에서 트레이스로 점프한다(system 대시보드의 exemplar 링크는 payment·pg 한정). Tempo `metrics_generator`(service-graphs + span-metrics)가 Prometheus `remote_write`(out-of-order 30m 윈도우)로 서비스 토폴로지(`traces_service_graph_*`)와 span RED(`traces_spanmetrics_*`)를 만든다.

**알람 규칙과 통지**: `observability/prometheus/prometheus.yml`의 `rule_files`가 `observability/prometheus/rules/*.yml`(observability compose가 `rules` 디렉토리를 마운트)을 로드하고, `alerting` 블록이 FIRING 알람을 `alertmanager:9093`으로 보낸다. Alertmanager는 `observability/alertmanager/alertmanager.yml`의 Discord receiver로 통지한다(웹훅 URL은 env `DISCORD_WEBHOOK_URL`로 기동 시 치환하고 커밋하지 않는다). 규칙은 5개 파일 4그룹이다.

- **coordinator**: `KafkaCoordinatorTxnAbortRising`, `KafkaCoordinatorLagHigh`, `KafkaBrokerUnavailable` — EOS 트랜잭션 abort, `events.confirmed` consumer lag, broker 가용성 backstop
- **guard-skip**: `GuardSkipDangerousStatusHigh` — 종결 가드가 위험 status를 skip하는 비율
- **dlq**: `DlqAppCounterRising`, `DlqTopicOffsetRising`, `DlqCommandsConsumerLag` — 앱 카운터, `.dlq` 토픽 offset 델타, `commands.confirm.dlq` 정체를 서로 독립 신호로 교차 확인한다. 앱 카운터의 pg 분기는 `sum(increase(pg_final_confirmation_outcome_total[5m]))` 합산이다. 격리 전이만 세면 자동 승인·자동 실패로 끝난 소진 건이 신호에서 빠진다. 같은 그룹의 `ProductStockQuarantineBacklog`는 재고 확정 격리 토픽 `payment.events.stock-committed.dlq` 적체를 본다. 음수 가드에 걸린 건은 이미 벤더 승인이 난 결제라 자동 복구 대상이 아니다. 관리자 재고 조정 직후 발화와 게이트 자체 결함을 가르는 기준은 `docs/smoke/alert-firing-check.md`의 런북이다
- **availability**: `ServiceDown`(`up{job=~".*-service"} == 0`), `DependencyDown`(`dependency_up == 0`), `DependencyHealthStale`(`dependency_health_last_poll_timestamp_seconds`) — 각 분기에 `absent()` backstop이 있다. `dependency_up{component}`는 `DependencyHealthMetrics`가 actuator 대신 직접 폴링해 만든다. 4서비스는 `db`, payment-service는 `redis-dedupe`·`redis-stock`을 더한다

규칙은 `promtool test rules` 픽스처(`observability/prometheus/rules/tests/*.yml`, 27케이스)로 회귀를 고정한다. `scripts/smoke/alert-rules-promtool.sh`(Docker 경유, 라이브 스택 불필요)와 `smoke-all.sh` Phase 1.3이 실행한다. broker를 완전히 멈추면 `kafka_brokers`는 0이 아니라 시리즈가 사라지므로 backstop은 `up == 0`과 `absent(kafka_brokers)`다([PITFALLS](PITFALLS.md) #24). 남은 알람 정밀화는 `../TODOS.md`의 T4-B다.

**장애 주입 드릴 (Toxiproxy)**: `docker/docker-compose.drill.yml`과 `toxiproxy.json`을 얹어 쓴다. kafka에 PROXY 리스너(9094)를 추가로 광고하고 payment-service의 bootstrap을 `toxiproxy:9094`로 우회시켜 latency toxic을 넣는다(admin API 8474, 프록시 등록은 `scripts/smoke/drill-toxiproxy.sh`). pg-service는 `kafka:9092`에 직결이다. broker가 1대이고 payment-service가 `commands.confirm`의 producer이자 consumer라 지연이 발행과 소비에 대칭으로 걸린다. consumer만 늦어지는 비대칭 lag와 EOS commit timeout은 이 구성으로 만들지 못하므로, 코디네이터·EOS 알람의 결정적 발화는 promtool과 통합 테스트로 확인한다(`scripts/smoke/alert-firing-*.sh`, 가이드 `docs/smoke/alert-firing-check.md`). 라이브로 발화를 확인한 알람은 `KafkaBrokerUnavailable`과 `DlqTopicOffsetRising`이다. 시나리오 확대는 `../TODOS.md`의 T4-A다.

**라이브 검증 (모의 벤더 전 구간 구동)**: 결제 성공·실패, 보상·재시도 소진, 격리, 자가 회복, 재고 경합, 중복 결제 차단, 인프라 장애를 실제 스택에서 일으켜 화면과 대조하는 절차다. 진입점은 `payment-live-drill` 스킬(`.agents/skills/payment-live-drill/`)이고 장면 정의·캡처 방식·구동 스크립트가 그 안에 있다. 장면마다 기대 결과를 먼저 정의하고 실행 결과와 대조해 판정한다. 캡처용 Grafana 익명 조회 override는 `docker/docker-compose.live-drill.yml`이다(검증 시에만 grafana 재기동에 얹는다). 리포트와 캡처 원본은 저장소 밖 `live-drill/`(`.gitignore`)에 남는다.

## Flyway

### 스키마 위치

두 패턴이다.

- **payment-service / pg-service** — `db/migration/`(Flyway 기본 위치) 단일 locations. seed 디렉토리가 없고 신규 스키마도 같은 위치에 추가한다.
- **product-service / user-service** — `db/schema/`(baseline)와 `db/seed/`(seed)를 나눈다. 신규 스키마는 `db/schema/`에 둔다.

```
# payment / pg
<service>/src/main/resources/db/migration/V*.sql

# product / user
<service>/src/main/resources/db/schema/V1__<서비스>_schema.sql   # baseline
<service>/src/main/resources/db/seed/V2__seed_*.sql             # 시드 데이터
```

### 프로필별 locations (product / user)

```yaml
# application.yml (default)
spring:
  flyway:
    enabled: true
    locations: classpath:db/schema,classpath:db/seed
  jpa:
    hibernate:
      ddl-auto: validate    # Flyway 적용 후 JPA가 컬럼을 검증한다

# product-service application-docker.yml
spring:
  flyway:
    locations: classpath:db/schema   # seed 제외
```

- default: `db/schema` + `db/seed`를 모두 적용한다.
- `docker` 프로필(apps compose가 4서비스 모두 `SPRING_PROFILES_ACTIVE: docker`로 기동)의 seed 취급은 서비스마다 다르다.
  - **product-service**: `db/schema`만 적용해 V2 seed 행을 막는다. 재고는 `scripts/seed-stock.sh`가 따로 넣는다.
  - **user-service**: `db/schema,db/seed`를 둘 다 적용한다. **빼면 안 된다.** seed를 빼면 `V2__seed_user.sql`이 돌지 않아 사용자 행이 없는 스택이 뜨고 로그인·checkout이 전량 실패한다. 볼륨이 남아 있는 동안은 예전에 들어간 행이 증상을 가리다가 새 볼륨에서만 드러나 진단이 늦다(`user-service/.../application-docker.yml:11-15`).
- **payment / pg**: seed가 없어 프로필 override도 없다. 기본 `classpath:db/migration`만 적용한다.

### 부팅 시 동작

1. DataSource가 준비되면 Flyway `migrate()`가 자동 호출된다.
2. `flyway_schema_history`가 없으면 V1부터 적용하고 이력 행을 추가한다.
3. JPA EntityManager가 `@Entity`와 실제 스키마의 일치를 검증한다(`ddl-auto: validate`).

버전 추적은 각 DB 안의 `flyway_schema_history` 테이블이다.

```sql
SELECT version, script, installed_on, success FROM flyway_schema_history ORDER BY installed_rank;
```

**불변성**: 이미 적용된 V 파일의 내용은 바꾸지 않는다(checksum 충돌). 바꿀 일이 있으면 새 V 번호를 추가한다.

**기존 DB에 Flyway를 도입할 때**는 `spring.flyway.baseline-on-migrate: true`와 `baseline-version: 0`으로 기존 스키마를 baseline으로 잡고 그 위에 V1+를 적용한다. 이 프로젝트는 설정하지 않았고(기본 false) 빈 DB에서 시작한다고 가정한다. 운영 도입 시에는 DB 백업, 마이그레이션 사전 검증, baseline 전략이 추가로 필요하다.

### 볼륨 재사용 시 MissingMigrationException

`docker compose down`만 실행하면 `mysql-product-data`·`mysql-user-data` 같은 named volume이 남는다(infra compose에 `external` 지정이 없다). 그 볼륨의 `flyway_schema_history`에 V2(seed)가 `db/seed` 경로로 기록돼 있는데 `docker` 프로필은 `db/schema`만 스캔하면 **`MissingMigrationException`**으로 부팅이 실패한다. 해당 사례는 seed를 막는 product-service다. `spring.flyway.ignore-migration-patterns`의 기본값은 `*:future`만 무시하고 `*:missing`은 실패로 다룬다.

볼륨 이름의 접두어는 compose 프로젝트명이다. `scripts/compose-up.sh`가 기본값 `docker`를 쓰므로 `docker_mysql-product-data` 형태다(`scripts/compose-up.sh:69`).

1. **권장 (학습 환경)**: MySQL 컨테이너와 볼륨을 지우고 다시 띄운다. `bash scripts/compose-up.sh --reset-db`가 네 MySQL 컨테이너와 볼륨만 지운다.
2. **볼륨을 보존해야 할 때**: 컨테이너에 접속해 이력의 V2 행을 지운다.

   ```sql
   DELETE FROM flyway_schema_history WHERE version = '2';
   ```

3. **일시 우회 (운영 비권장)**: `spring.flyway.ignore-migration-patterns: '*:missing'`을 임시로 둔다. 실제로 누락된 마이그레이션도 무시될 수 있다.

### 테스트

- `product-service`와 `user-service`의 `FlywayDockerProfileTest`는 `@ActiveProfiles("docker")`로 docker 프로필의 seed 취급을 통합 검증한다(product는 차단, user는 V1+V2 적용과 사용자 행 존재).
- payment-service 통합 테스트는 `application-test.yml`에서 `spring.flyway.enabled: false`와 `ddl-auto: create-drop`을 쓰고, `BaseIntegrationTest`의 Testcontainers MySQL 위에서 JPA가 `@Entity` 기준으로 스키마를 만든다. Flyway와 `entityManagerFactory`의 순환 의존(`Circular depends-on relationship between 'flyway' and 'entityManagerFactory'`)을 피하려는 설정이다.
- `BaseIntegrationTest`의 `@Sql("/data-test.sql")`은 `SELECT 1`뿐인 NOOP 시드다. 빈 스크립트는 Spring이 예외로 다루기 때문에 자리만 둔다.

## 스케줄러 활성화 정책

**게이트**: payment-service, product-service, user-service에 각각 `SchedulerConfig`가 있고 `@EnableScheduling` + `@ConditionalOnProperty(name = "scheduler.enabled", havingValue = "true")`다. 프로퍼티가 없으면 `SchedulerConfig` 빈이 등록되지 않아 그 서비스의 `@Scheduled`가 모두 돌지 않는다. **게이트는 worker 클래스가 아니라 `SchedulerConfig`다.** pg-service는 이 게이트가 없다 — `PgServiceConfig`(`@Configuration @EnableScheduling`)에 조건이 없어 프로파일과 무관하게 항상 활성이다. eureka와 gateway에는 `@Scheduled`가 없다.

| 서비스 | 게이트 | 기본(로컬) | docker 프로필 |
|---|---|---|---|
| payment-service | `scheduler.enabled=true` 필요 | 비활성 | 활성(`application-docker.yml`) |
| product-service | `scheduler.enabled=true` 필요 | 비활성 | 활성 |
| user-service | `scheduler.enabled=true` 필요 | 비활성 | 활성 |
| pg-service | 없음 | 활성 | 활성 |
| eureka / gateway | `@Scheduled` 없음 | 해당 없음 | 해당 없음 |

**서비스별 스케줄러**:
- payment-service: `DedupeCleanupWorker`(`payment_event_dedupe` 만료 행 청소), `PaymentScheduler`(READY 만료), `PaymentReconciler`(IN_PROGRESS 정체분을 결과 대기로 되돌리고, 결과 대기 2차 임계 초과분을 격리로 보낸다. `reconciler.in-flight-timeout-seconds` 기본 300, `reconciler.awaiting-result-timeout-seconds` 기본 900, `reconciler.fixed-delay-ms` 기본 120000), `OutboxWorker`(outbox PENDING 폴링·Kafka 발행), `StockHoldRecoveryWorker`(미회수 선차감 기록 회수 판정), `PaymentStateMetrics`(상태별 건수 gauge), `PaymentHealthMetrics`(stuck IN_PROGRESS·결과 대기 적체·max-retry 이상 gauge), `PaymentOutboxMetrics`(`payment_outbox` PENDING 지표, 클래스에 `@ConditionalOnProperty(scheduler.enabled=true)` 직접 부착), `DependencyHealthMetrics`(의존성 가용성 게이지 `dependency_up{component}`, availability 알람이 소비)
- pg-service: `PgOutboxPollingWorker`(outbox PENDING 폴링·Kafka 발행), `PgInboxPollingWorker`(inbox PENDING·IN_PROGRESS 좀비 회수), `PgOutboxMetrics`(outbox 지표), `DependencyHealthMetrics`(게이트 없이 항상 활성)
- product-service: `DedupeCleanupWorker`(`stock_commit_dedupe` 만료 행 청소), `DependencyHealthMetrics`
- user-service: `DependencyHealthMetrics`가 유일한 `@Scheduled`다

## 정적 분석

| 도구 | 설정 |
|---|---|
| Checkstyle | `config/checkstyle/checkstyle.xml` + 커스텀 Check `config/checkstyle/custom-checks/`. 버전은 [스택 프로필](../../.agents/profiles/stack.md) |
| SpotBugs (플러그인 6.0.9) | `config/spotbugs/spotbugs-exclude.xml`(main), `spotbugs-exclude-test.xml`(test) |
| ArchUnit | 레이어 규칙의 정의처는 `config/archunit/`이고 서비스들이 공유한다 |
| JaCoCo (0.8.11) | 측정 대상·제외·게이트 산정은 [TESTING.md](TESTING.md)의 "JaCoCo 커버리지 정책"이 정본 |

**코드 스타일 5규칙 자동 검출**: [code-style.md](conventions/code-style.md)의 규칙을 Checkstyle이 검출한다. 다섯 규칙 모두 `severity=error`이고 `maxWarnings = 0`(`build.gradle:138`)이라 경고 계층이 없다. 규칙은 빌드를 깨뜨리거나 아예 없거나 둘 중 하나다.

- 문자열 판정 3종(`RegexpSinglelineJava`): 타입 추론 키워드(`var`) 금지, `@Data` 금지, 공개 유스케이스·포트의 null 반환 금지
- 구조 판정 2종(커스텀 `TreeWalker` Check): 광범위 예외 삼킴 금지(`SwallowedBroadException`), try 블록 외부 변수 재할당 금지(`TryBlockExternalReassignment`)
- 커스텀 Check는 루트 프로젝트 전용 sourceSet(`checkstyleCustomChecks`)에서 컴파일해 각 서비스 checkstyle classpath에 얹는다. 루트 자신의 `checkstyleMain`/`checkstyleTest`는 순환을 피해 비활성이다
- ArchUnit과 Checkstyle은 담당 영역이 다르다. ArchUnit은 바이트코드 의존 그래프만 다뤄 메서드 본문의 제어 흐름(catch 안에 재throw가 있는가 등)을 표현하지 못하고, Checkstyle은 레이어 의존 방향을 판정하지 못한다
- `config/checkstyle/checkstyle-suppressions.xml`에는 파일·행 지정 억제가 없다. 데이터 캐리어·어댑터 계층(`dto`, `entity`, `infrastructure` 등)의 디렉토리 단위 억제와, `PublicUseCasePortNullReturn`의 적용 범위를 `application/usecase`·`application/port`로 좁히는 항목이 있다

**규칙을 추가할 때**:

1. 먼저 전체 코드베이스의 위반 수를 센다. 정당한 코드를 지적하는 규칙이 하나라도 섞이면 규칙 세트 전체가 무시되기 시작한다. 위반이 0이거나 전부 정리한 뒤에만 켠다.
2. 경고로 추가하지 않는다. `maxWarnings = 0`이 이를 강제한다.
3. 에러 메시지에 대안을 적는다. 에이전트에게 린트 메시지는 곧 프롬프트라서, 대안을 같이 주면 한 번에 고친다.

## CI 파이프라인 (GitHub Actions)

PR과 main push마다 6서비스(payment / pg / product / user / gateway / eureka)를 각각 독립 파이프라인으로 fan-out한다.

- **`.github/workflows/ci.yml`** (진입): 6서비스를 재사용 워크플로우로 각각 호출한다(`service`, `has-integration`, payment·pg의 `integration-shards`·`integration-shard-count`). 마지막에 취합 `report` job이 있다.
- **`.github/workflows/_service-ci.yml`** (재사용, `workflow_call`): 서비스 1개 파이프라인이다.
  - `build-test-lint` job(항상): `./gradlew :<svc>:build`에서 통합 테스트·Checkstyle·SpotBugs를 제외해 컴파일과 단위 테스트, JaCoCo를 돌리고, 별도 step에서 Checkstyle·SpotBugs를 실행한다. 이어 reviewdog 인라인 코멘트, JaCoCo·lint 요약 아티팩트, lint gate를 처리한다.
  - `integration-test` job(`has-integration`이 true인 payment / pg / product / user): payment 3개, pg 2개 shard이고 product·user는 shard 없이 1개 job이다. 분할 job은 `testClasses` 후 `scripts/ci-integration-shard.py`가 컴파일된 최상위 클래스마다 shard를 정하고 Gradle `--tests`로 선택한다. `DOCKER_API_VERSION=1.44`를 쓰고 shard별 JUnit Check를 게시하며 `fail-fast: false`다. gateway와 eureka는 통합 job이 없다. **Testcontainers reuse는 CI에서 끈다.** 같은 job 안의 여러 `@SpringBootTest` 클래스가 재사용 컨테이너의 더럽혀진 스키마에 Flyway를 다시 적용하면 "non-empty schema but no schema history table"로 컨텍스트 로드가 깨지기 때문이다.
- **`agent-docs-check` job**: 6서비스 fan-out과 별개인 단일 job이다. `scripts/check-agent-docs.py`를 인자 없는 정보 보고 모드로 돌려 결과를 job 로그와 요약에 남기고(실패시키지 않는다), `sync-agent-configs.py --check`(역할 원본과 생성 파일이 다르면 실패)와 `scripts/test_check_agent_docs.py`를 실행한다.
- **`hook-guardrail-check` job**: 단일 job이고 **게이트다.** `scripts/test-hooks.sh`로 검증 hook의 판정을 확인하며, Java 21·Gradle과 `writeCheckstyleCliClasspath` 단계를 포함한다.
- **취합 `report` job**: 6서비스 아티팩트를 `actions/github-script` + `.github/scripts/report-comment.js`로 모아 PR에 통합 코멘트 하나(커버리지, 테스트 수, lint 요약)를 갱신한다. 일부 서비스가 실패해도 `always()`로 코멘트는 남기며 머지 판정에는 쓰이지 않는다.
- 머지 차단은 각 서비스 `build-test-lint`와 모든 `integration-test` shard의 결과로 정해진다. GitHub branch protection의 required status checks에 각 job을 등록하는 것이 전제다.
