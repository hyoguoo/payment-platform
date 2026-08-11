# External Integrations

> 최종 갱신: 2026-08-11 (PG-MESSAGE-DEDUPE-LAYER-REMOVAL — 인프라 장애 표의 Redis dedupe 행에서 pg-service 를 제거, pg 는 캐시 의존 자체가 없어졌음을 명시). 이전: 2026-08-06 (RETRY-EXHAUSTION-DISPOSITION — cross-service HTTP 표에 pg 벤더 상태 조회 행 추가 + 전용 클라이언트 분리 근거(contextId, 시간 제한 관계)·조회 포트가 예외를 던지지 않는 계약 2문단 신설). 이전: 2026-08-05 (BACKLOG-RESIDUE-CLEANUP — Fake 전략 행에 활성 프로파일 기준 부팅 가드 반영: 경고 배너 뒤에 `smoke`/`test` 미포함 시 기동 차단). 이전: 2026-07-29 (LIVE-DRILL-FORMALIZATION — Fake 전략 행에 라이브 검증 용도·`supports()` 무차별 수락 위험 반영 + Fake 시나리오 접두어 문단 신설(중복 판정보다 앞선 순서 근거, 자가 회복 횟수와 재시도 한도 관계, 전역 실패율 병용 금지)). 이전: 2026-07-28 (ADMIN-VISIBILITY — cross-service HTTP 표에 payment→pg 시도 이력 조회(`PgFeignClient`/`PgAttemptHistoryHttpAdapter`, pg-service 최초 컨트롤러)·payment→product 목록 조회(`ProductCatalogHttpAdapter`) 2행 추가 + 관리자 조회 포트를 승인 경로 포트와 분리하는 방침 + pg 전용 짧은 timeout(1s/2s) 문단 + 통신 매트릭스 payment→pg HTTP 행 추가). 이전: 2026-07-07 (DOCS-CONSISTENCY-OVERHAUL Task 19 — 최종 검증 스윕의 stale 마커 grep 에서 신규 발견, 관측성 통합 표의 Loki 행이 `LogstashEncoder` 를 현재형으로 서술하던 것을 Console appender + docker 로깅 드라이버 + Promtail 기준으로 정정). 이전: 2026-07-03 (Task 9 — Contract test 문단에 상세 근거 문서(`TESTING.md`) 링크 추가, S4 중복 SSOT 정리). 2026-06-23 (코드 대조 — PG 포트 분리(`PgConfirmPort`/`PgStatusLookupPort`)·예외명 현행화 + self-loop attempt 갭)

## PG 벤더 — Strategy 패턴

pg-service 가 두 PG 벤더를 추상화하고 결제 건별로 라우팅한다(`gatewayType` 필드, Toss / NicePay).

**전략 위치**: `pg-service/.../infrastructure/gateway/`

| 전략 | 클래스 | 활성화 조건 |
|---|---|---|
| Toss | `toss/TossPaymentGatewayStrategy` | 항상 |
| NicePay | `nicepay/NicepayPaymentGatewayStrategy` | 항상 |
| Fake | `fake/FakePgGatewayStrategy` | `@ConditionalOnProperty(pg.gateway.type=fake)` — 스모크/벤치/라이브 검증 전용. PostConstruct 가 경고 배너를 남긴 뒤 활성 프로파일에 `smoke` 도 `test` 도 없으면 `IllegalStateException` 으로 기동을 멈춘다. `supports()` 는 벤더 종류를 가리지 않고 수락하므로, 이 가드가 없으면 설정 오배포 시 실제 승인 없이 결제가 완료된다(`CONCERNS.md` L-18) |

**Fake 시나리오 접두어** (라이브 검증용): `paymentKey` 앞머리로 벤더 응답을 결정적으로 고른다 — `fake-fail-`(확정 실패), `fake-retry-`(매 호출 재시도 가능 실패 → 소진 → 격리), `fake-flaky-`(정해진 횟수 실패 후 승인 → 자가 회복). 판정은 **중복 승인 판정보다 앞**에서 이뤄진다 — 뒤에 두면 재시도 자기루프가 중복으로 흡수돼 시도 횟수가 소진되지 않는다. 자가 회복 실패 횟수는 `RetryPolicy.MAX_ATTEMPTS` 보다 작아야 하며 그 관계는 단위 테스트로 고정돼 있다. 전역 합성 실패율(`pg.gateway.fake.fail-rate`)과 함께 켜면 회복 회차가 다시 실패할 수 있어 병용하지 않는다.

**선택 로직**: `PgConfirmStrategySelector` 가 `gatewayType` (DB 또는 메시지 payload 의 `PaymentGatewayType` enum) 으로 분기 → 해당 전략 호출.

**포트** (confirm / status 분리 — 단일 `PgGatewayPort` 아님):
- `PgConfirmPort.confirm(PgConfirmRequest)` → `PgConfirmResult` (성공 + amount + approvedAtRaw / 실패·재시도·멱등은 `PgGateway*Exception` 으로 표현) — `PgConfirmStrategySelector` 가 vendorType 으로 선택
- `PgStatusLookupPort.getStatusByOrderId(orderId)` → 벤더 상태 조회 (`DuplicateApprovalHandler` 의 멱등 응답 재확정용) — `PgStatusLookupStrategySelector` 가 선택
- cancel / refund 은 현재 미구현 (포트에 메서드 없음 — CONCERNS L-9)

## Toss Payments

| 항목 | 값 |
|---|---|
| Base URL | 환경별 (`payment.gateway.toss.base-url` 등 — 벤더 키 환경변수에서 주입) |
| 인증 | Basic Auth (Secret Key, base64) |
| confirm endpoint | `POST /v1/payments/confirm` |
| getStatus endpoint | `GET /v1/payments/orders/{orderId}` |
| 상태 매핑 | `TossPaymentStatus` enum + `PaymentStatus` 도메인 매핑 (UNMAPPED 시 `PaymentGatewayStatusUnmappedException`) |
| 에러 코드 | `TossPaymentErrorCode` — retryable / non-retryable 분류 |
| 시각 처리 | `approvedAt` 원문(ISO-8601 offset) 보존 → `ConfirmedEventPayload.approvedAt`(`approvedAtRaw` String) 전달. payment 측 `parseApprovedAt` 에서 `.toInstant()` 정규화 (offset 보존, TIME-MODEL-AND-EXPIRY) |

## NicePay

| 항목 | 값 |
|---|---|
| Base URL | `${NICEPAY_API_URL:https://sandbox-api.nicepay.co.kr}` |
| 인증 | Client Key / Secret Key (`NICEPAY_CLIENT_KEY` / `NICEPAY_SECRET_KEY`) |
| confirm endpoint | `POST /v1/payments/{tid}` |
| getStatus endpoint | `GET /v1/payments/{tid}` |
| 시각 처리 | `paidAt` 원문(offset) 보존 → `approvedAtRaw` 전달. payment 측 `OffsetDateTime.parse(approvedAtRaw).toInstant()` 정규화 — `.toLocalDateTime()` 금지(정산 9시간 오차 차단, PITFALLS §13). NicePay 내부 fallback `OffsetDateTime.now(clock)` (예외 경로) |
| 응답 매핑 | `NicepayPaymentApiResponse` / `NicepayPaymentApiFailResponse` |

## 벤더 호출 회복성

PG 호출 회복은 pg-service 가 전담한다 (payment 는 결과만 수신 — ADR-04). 결과 분기는 `PgVendorCallService.applyOutcome` 5분기.

- retryable: 타임아웃 / 5xx — `PgGatewayRetryableException` → `handleRetry` (commands.confirm self-loop 재발행 + `pg_inbox.attempt` 증가). attempt 가 `pg_inbox.attempt`(Flyway V5) 에 영속돼 한도(4) 소진 시 DLQ→QUARANTINED 자동 격리 (DLQ-REACHABILITY)
- non-retryable: 4xx 확정 거절 — `PgGatewayNonRetryableException` → `pg_inbox FAILED` + events.confirmed FAILED
- 멱등 응답("이미 처리됨"): `PgGatewayDuplicateHandledException` → `DuplicateApprovalHandler` 가 vendor getStatus 재조회로 APPROVED / QUARANTINED 확정
- AMOUNT_MISMATCH: 벤더 응답 amount 와 로컬 `paymentEvent.totalAmount` 불일치 → QUARANTINED (양방향 방어)

## 외부 PG HTTP timeout 정책

pg-service 가 Toss / NicePay 벤더를 호출할 때 적용하는 timeout 설정과 그 근거.

| timeout | 기본값 | 환경변수 | 근거 |
|---|---|---|---|
| connect-timeout | 3000ms | `PG_HTTP_CONNECT_TIMEOUT_MS` | 벤더 LB 가 TCP 연결을 빠르게 수락하므로 3s 로 충분 |
| read-timeout | 10000ms | `PG_HTTP_READ_TIMEOUT_MS` | 카드망 round-trip 포함 벤더 처리에 평균 1~3s, 피크 시 그 이상도 가능. 10s 를 안전 baseline 으로 설정 |

**payment-service Feign(5s) vs pg-service 외부 PG(10s) 비대칭 이유**:
payment-service 의 Feign `readTimeout: 5000` 은 같은 플랫폼 내부 서비스 간 call 기준이다.
pg-service 는 카드망을 포함한 외부 PG 처리를 기다려야 하므로 내부 call timeout 보다 외부 PG timeout 이 반드시 길어야 한다.
내부 5s 보다 짧으면 pg-service 가 벤더 응답을 기다리는 중에 payment-service 가 먼저 타임아웃 나는 것을 방지하지 못한다.

**Phase 4 튜닝 deferred**: 현재 값은 운영 측정 없는 baseline. T4-D (부하 측정) 결과를 기반으로 실제 SLO 에 맞춰 정밀 튜닝할 예정.

## Cross-service HTTP

payment-service 가 product-service / user-service / pg-service 를 OpenFeign + LoadBalancer 로 호출 (Eureka discovery + 클라이언트 사이드 round-robin, CLIENT-SIDE-LB Phase B).

| 호출 | 경로 | Feign 클라이언트 | 어댑터 (port 구현) |
|---|---|---|---|
| product 조회 | `GET /api/v1/products/{id}` | `ProductFeignClient` (`@FeignClient(name = "product-service", configuration = ProductFeignConfig.class)`) | `ProductHttpAdapter` |
| product 목록 조회 (관리자 재고 화면) | `GET /api/v1/products?page=&size=` | `ProductFeignClient` (동일 client 공유) | `ProductCatalogHttpAdapter` |
| user 조회 | `GET /api/v1/users/{id}` | `UserFeignClient` (`@FeignClient(name = "user-service", configuration = UserFeignConfig.class)`) | `UserHttpAdapter` |
| pg 시도 이력 조회 (관리자 결제 상세) | `GET /api/v1/confirmations/{orderId}/attempts` | `PgFeignClient` (`@FeignClient(name = "pg-service", configuration = PgFeignConfig.class)`) | `PgAttemptHistoryHttpAdapter` |
| pg 벤더 상태 조회 (격리 종결 전 확인) | `GET /api/v1/confirmations/{orderId}/vendor-status` | `PgVendorStatusFeignClient` (`@FeignClient(name = "pg-service", contextId = "pgVendorStatus", configuration = PgVendorStatusFeignConfig.class)`) | `PgVendorStatusHttpAdapter` |

**관리자 조회 포트는 승인 경로 포트와 분리**: `ProductCatalogQueryPort` / `PgAttemptHistoryPort` 는 결제 승인 경로가 쓰는 `ProductPort` / `UserPort` 와 별개 인터페이스다. 승인 경로 포트에 관리자 용도가 섞이면 나중에 떼어내기 어렵다. 반면 Feign client 는 공유해 중복을 만들지 않는다 (product 는 기존 client 에 메서드 추가).

**payment → pg 는 관리자 조회 전용**: 결제 확정 자체는 여전히 Kafka 단방향이다 (아래 통신 매트릭스). pg-service 는 이 엔드포인트가 생기기 전까지 컨트롤러가 0개인 Kafka 전용 서비스였다 (ADMIN-VISIBILITY).

**계약 매핑**: 각 `*FeignConfig` 의 `ErrorDecoder` 가 4xx / 5xx 응답을 도메인 예외로 매핑.
- 404 → `*NotFoundException` (PRODUCT_NOT_FOUND / USER_NOT_FOUND)
- 429 / 503 / **502 / 504** → `*ServiceRetryableException` (`PRODUCT_SERVICE_UNAVAILABLE` / `USER_SERVICE_UNAVAILABLE`) → 핸들러가 `503 + Retry-After: 5` 로 환원. 502 Bad Gateway / 504 Gateway Timeout 은 게이트웨이·프록시 일시 장애라 retryable 로 승격 (cross-service 호출이 GET 단건 조회 전용이라 비멱등 재시도 위험 없음)
- 500 및 그 외 5xx → `IllegalStateException` (영구 오류, 재시도 유도 안 함)

**Transport 예외**: 어댑터 (`ProductHttpAdapter` / `UserHttpAdapter`) 가 `feign.RetryableException` 만 catch 해 `*ServiceRetryableException` 으로 변환. 4xx / 5xx 매핑은 `ErrorDecoder` 단계에서 끝났으므로 어댑터에는 try/catch 가 transport 한 분기만 남는다.

**Timeout baseline**: `application.yml:18-23` — `spring.cloud.openfeign.client.config.default.{connectTimeout: 2000, readTimeout: 5000}`. Phase 4 측정 기반 SLO 로 조정 예정 (TODOS T4-D).

**pg 관리자 조회는 전용 짧은 timeout**: `spring.cloud.openfeign.client.config.pg-service.{connectTimeout: 1000, readTimeout: 2000}` (`PG_ADMIN_QUERY_CONNECT_TIMEOUT_MS` / `PG_ADMIN_QUERY_READ_TIMEOUT_MS`). 기본값(2s/5s)이면 pg 가 느릴 때 관리자 상세 진입이 그만큼 지연된다 — 관측 화면은 빨리 실패하고 부분 렌더하는 편이 낫다. `default` 블록은 변경하지 않으므로 product / user client 는 영향받지 않는다. 이 설정은 `@FeignClient(configuration = PgFeignConfig.class)` 로만 한정 등록한다 — 전역 `@Configuration` 으로 올리면 다른 client 에 새어 나간다.

**벤더 상태 조회는 전용 클라이언트로 분리**: `PgVendorStatusFeignClient` 는 `name` 을 `pg-service` 로 그대로 두고 `contextId = "pgVendorStatus"` 로 설정 네임스페이스만 나눈다 (`spring.cloud.openfeign.client.config.pgVendorStatus.{connectTimeout: 2000, readTimeout: 15000}`). 위 관리자 조회용 짧은 값(1s/2s)을 물려받으면 안 되기 때문이다 — 이 조회는 pg 가 벤더를 부르는 시간(read-timeout 10s)을 포함하므로 정상 응답도 먼저 끊긴다. 끊기면 판정이 "확인 불가"가 되고 그건 종결 허용 분기라 **로그로는 정상처럼 보인다**. `name` 을 바꾸거나 `url` 을 박으면 Eureka 인스턴스 해석·부하 분산을 우회하므로 둘 다 금지 — 선언 자체를 `PgVendorStatusFeignTimeoutTest` 가 구조 계약으로 고정한다.

**벤더 상태 조회 포트는 예외를 던지지 않는다**: `PgVendorStatusPort.lookup` 은 통신 예외든 pg 자체 오류든 확인 불가 값으로 접어 항상 세 값(`APPROVED`/`FAILED`/`UNKNOWN`) 중 하나를 반환한다. 소비자(`QuarantineResolveUseCase`)의 판정이 세 갈래 분기 하나로 끝나게 하려는 것이다. pg 측 `PgVendorStatusQueryServiceImpl` 도 같은 이유로 조회에서 나오는 모든 실행 시 예외를 흡수한다 — 모의 벤더가 미처리 주문에 던지는 `UnsupportedOperationException` 이 정확히 재시도 소진 격리 건에 해당해 좁게 잡으면 새어나간다. 양쪽 다 예외 타입·사유를 로그에 남긴다.

**Traceparent 전파**: Spring Cloud OpenFeign 이 OTel observation 통합을 통해 자동 주입. `RestTemplate` 자체 builder 추가 wiring 불필요.

**Contract test**: `ProductFeignConfigTest` / `UserFeignConfigTest` / `PgFeignConfigTest` 가 ErrorDecoder 분기 (404 / 429 / 503 / 502 / 504 retryable / 500 등 그 외 5xx) 를 검증. `ProductHttpAdapterContractTest` / `UserHttpAdapterContractTest` / `PgAttemptHistoryHttpAdapterContractTest` / `ProductCatalogHttpAdapterContractTest` 는 Mockito 로 FeignClient mock 후 어댑터의 예외 propagation + transport 변환만 검증 (MockWebServer 사용 안 함). 2-layer 패턴 상세(표 + 시나리오)는 [`TESTING.md`](TESTING.md) §Contract test 패턴 참고.

**회복성**: 현재 어댑터의 transport try/catch 만. **CircuitBreaker 는 Phase 4 (T4-D) 예정** — 도입 시점에 fallbackFactory 로 마이그레이션하면서 어댑터 try/catch 제거.

## 외부 시스템 통신 매트릭스

| 출발 | 도착 | 프로토콜 | 토픽/엔드포인트 |
|---|---|---|---|
| 브라우저 | gateway | HTTP | `/api/v1/payments/{checkout,confirm,status}/...` |
| gateway | payment-service | HTTP | Eureka 라우팅 |
| gateway | product-service | HTTP | Eureka 라우팅 |
| gateway | user-service | HTTP | Eureka 라우팅 |
| payment-service | product-service | HTTP (Feign + LB) | `GET /api/v1/products/{id}` · `GET /api/v1/products?page=&size=` (관리자 재고 화면) |
| payment-service | user-service | HTTP (Feign + LB) | `GET /api/v1/users/{id}` |
| payment-service | pg-service | HTTP (Feign + LB) | `GET /api/v1/confirmations/{orderId}/attempts` — **관리자 조회 전용**. 결제 확정 경로는 아래 Kafka 그대로 (ADMIN-VISIBILITY) |
| payment-service → pg-service | Kafka | one-way | `payment.commands.confirm` (최초 confirm 명령) |
| pg-service → pg-service | Kafka | self-loop | `payment.commands.confirm` 재발행 (자체 retry) — `pg_outbox.available_at` 기반 지연 발행. attempt 는 `pg_inbox.attempt` 영속·증가 (DLQ-REACHABILITY) |
| pg-service → DLQ | Kafka | one-way | `payment.commands.confirm.dlq` (`PgVendorCallService.insertDlqOutbox` — attempt ≥ 4 소진 시 도달 → QUARANTINED 자동 격리) |
| pg-service → payment-service | Kafka | one-way | `payment.events.confirmed` (PG 결과 회신 — APPROVED/FAILED/QUARANTINED) |
| payment-service → DLQ | Kafka | one-way | `payment.events.confirmed.dlq` (Spring Kafka `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` — retry 5회 한도 초과 시 자동 발행. `KafkaErrorHandlerConfig`) |
| payment-service → product-service | Kafka | one-way | `payment.events.stock-committed` (APPROVED 시만 — RDB 누적 차감 ledger) |
| pg-service → 벤더 | HTTP | one-way | Toss / NicePay confirm/getStatus |

## 관측성 통합

| 시스템 | 통합 방식 |
|---|---|
| Prometheus | 각 서비스 `/actuator/prometheus` 스크랩 (15s) |
| Grafana | Prometheus + Loki + Tempo 데이터소스 |
| Loki | Logback Console appender(LogFmt) stdout → docker 로깅 드라이버(`com.hyoguoo.loki.enable` 라벨) → Promtail → Loki push |
| Tempo | OTel exporter (`io.opentelemetry:opentelemetry-exporter-otlp`) |
| traceparent 전파 | OTel propagation — Servlet/VT/Async/Kafka producer/consumer 경계 모두 |

## 로컬 개발 시 외부 의존 관리

| 의존 | 안 떠 있을 때 동작 |
|---|---|
| 다른 비즈니스 서비스 | checkout/confirm 시 503 (`USER_SERVICE_UNAVAILABLE` / `PRODUCT_SERVICE_UNAVAILABLE`) |
| Kafka | confirm 은 HTTP 202 까지 가지만 outbox→Kafka 발행 실패 → relay 재시도 또는 DLQ. payment.events.confirmed consumer 도 미동작 → status 영구 PROCESSING |
| Redis dedupe | payment-service checkout `IdempotencyStore` 호출 실패 → 해당 layer 가드. events.confirmed dedupe 는 redis-stock Lua atomic dedup token 으로 일원화. **pg-service 는 Redis 의존 없음** — 리스너 진입 필터 제거로 캐시 라이브러리까지 걷어냈다(PG-MESSAGE-DEDUPE-LAYER-REMOVAL), 중복 방어는 `pg_inbox.order_id` UNIQUE 단일 층 |
| Redis stock | confirm 시 재고 DECR 실패 → 동일 |
| MySQL | 부팅 자체 실패 (Flyway 가 DB 연결 못 함) |
| Eureka | discovery 미동작 → cross-service HTTP 가 IP 직접 못 찾음 |

## 설정 파일 인덱스

| 파일 | 용도 |
|---|---|
| `application.yml` | default profile — IDE 로컬 실행 (호스트 포트 사용) |
| `application-docker.yml` | docker compose 배포 — 컨테이너 hostname (`mysql-payment`, `kafka` 등) 사용 |
| `application-benchmark.yml` (payment-service) | k6 부하 테스트 프로필 |
| `application-smoke.yml` (pg-service) | FakePgGatewayStrategy 활성화용 스모크 프로필 |
