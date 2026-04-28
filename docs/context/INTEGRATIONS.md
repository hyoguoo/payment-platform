# External Integrations

> 최종 갱신: 2026-04-27

## PG 벤더 — Strategy 패턴

pg-service 가 두 PG 벤더를 추상화하고 결제 건별로 라우팅한다(`gatewayType` 필드, Toss / NicePay).

**전략 위치**: `pg-service/.../infrastructure/gateway/`

| 전략 | 클래스 | 활성화 조건 |
|---|---|---|
| Toss | `toss/TossPaymentGatewayStrategy` | 항상 |
| NicePay | `nicepay/NicepayPaymentGatewayStrategy` | 항상 |
| Fake | `fake/FakePgGatewayStrategy` | `@ConditionalOnProperty(pg.gateway.type=fake)` — 스모크/벤치 전용. PostConstruct 경고 배너 |

**선택 로직**: `PgConfirmStrategySelector` 가 `gatewayType` (DB 또는 메시지 payload 의 `PaymentGatewayType` enum) 으로 분기 → 해당 전략 호출.

**공통 인터페이스** (`PgGatewayPort`):
- `confirm(PgConfirmCommand)` → `PgConfirmResult` (APPROVED / FAILED / QUARANTINED 결과 + amount + approvedAtRaw)
- `getStatus(orderId)` → 벤더 상태 조회 (복구 사이클 진입 전 선행 호출 — Final Confirmation Gate)
- `cancel(...)` (구조 존재, 운영 활용 별도)

## Toss Payments

| 항목 | 값 |
|---|---|
| Base URL | 환경별 (`payment.gateway.toss.base-url` 등 — 벤더 키 환경변수에서 주입) |
| 인증 | Basic Auth (Secret Key, base64) |
| confirm endpoint | `POST /v1/payments/confirm` |
| getStatus endpoint | `GET /v1/payments/orders/{orderId}` |
| 상태 매핑 | `TossPaymentStatus` enum + `PaymentStatus` 도메인 매핑 (UNMAPPED 시 `PaymentGatewayStatusUnmappedException`) |
| 에러 코드 | `TossPaymentErrorCode` — retryable / non-retryable 분류 |
| 시각 처리 | `approvedAt` 원문(ISO-8601) 보존 → `ConfirmedEventPayload.approvedAt` 으로 전달 |

## NicePay

| 항목 | 값 |
|---|---|
| Base URL | `${NICEPAY_API_URL:https://sandbox-api.nicepay.co.kr}` |
| 인증 | Client Key / Secret Key (`NICEPAY_CLIENT_KEY` / `NICEPAY_SECRET_KEY`) |
| confirm endpoint | `POST /v1/payments/{tid}` |
| getStatus endpoint | `GET /v1/payments/{tid}` |
| 시각 처리 | `paidAt` 원문 보존, offset 정규화 적용 (직전 fix — `NicePay paidAt offset 정규화 — ConfirmedEvent 역직렬화 회복`) |
| 응답 매핑 | `NicepayPaymentApiResponse` / `NicepayPaymentApiFailResponse` |

## 벤더 호출 회복성

- retryable 분류: 타임아웃 / 5xx / 매핑 불가 — `PaymentRetryableException` 또는 `PaymentTossRetryableException`. 복구 사이클이 RETRY_LATER 로 처리
- non-retryable: 4xx / PG_NOT_FOUND — 즉시 COMPLETE_FAILURE 분기
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

payment-service 가 product-service / user-service 를 OpenFeign + LoadBalancer 로 호출 (Eureka discovery + 클라이언트 사이드 round-robin, CLIENT-SIDE-LB Phase B).

| 호출 | 경로 | Feign 클라이언트 | 어댑터 (port 구현) |
|---|---|---|---|
| product 조회 | `GET /api/v1/products/{id}` | `ProductFeignClient` (`@FeignClient(name = "product-service", configuration = ProductFeignConfig.class)`) | `ProductHttpAdapter` |
| user 조회 | `GET /api/v1/users/{id}` | `UserFeignClient` (`@FeignClient(name = "user-service", configuration = UserFeignConfig.class)`) | `UserHttpAdapter` |

**계약 매핑**: 각 `*FeignConfig` 의 `ErrorDecoder` 가 4xx / 5xx 응답을 도메인 예외로 매핑.
- 404 → `*NotFoundException` (PRODUCT_NOT_FOUND / USER_NOT_FOUND)
- 429 / 503 → `*ServiceRetryableException` (`PRODUCT_SERVICE_UNAVAILABLE` / `USER_SERVICE_UNAVAILABLE`)
- 그 외 5xx → `IllegalStateException`

**Transport 예외**: 어댑터 (`ProductHttpAdapter` / `UserHttpAdapter`) 가 `feign.RetryableException` 만 catch 해 `*ServiceRetryableException` 으로 변환. 4xx / 5xx 매핑은 `ErrorDecoder` 단계에서 끝났으므로 어댑터에는 try/catch 가 transport 한 분기만 남는다.

**Timeout baseline**: `application.yml:18-23` — `spring.cloud.openfeign.client.config.default.{connectTimeout: 2000, readTimeout: 5000}`. Phase 4 측정 기반 SLO 로 조정 예정 (TODOS T4-D).

**Traceparent 전파**: Spring Cloud OpenFeign 이 OTel observation 통합을 통해 자동 주입. `RestTemplate` 자체 builder 추가 wiring 불필요.

**Contract test**: `ProductFeignConfigTest` / `UserFeignConfigTest` 가 ErrorDecoder 4분기 (404 / 429 / 503 / 그 외 5xx) 를 검증. `ProductHttpAdapterContractTest` / `UserHttpAdapterContractTest` 는 Mockito 로 FeignClient mock 후 어댑터의 예외 propagation + transport 변환만 검증 (MockWebServer 사용 안 함).

**회복성**: 현재 어댑터의 transport try/catch 만. **CircuitBreaker 는 Phase 4 (T4-D) 예정** — 도입 시점에 fallbackFactory 로 마이그레이션하면서 어댑터 try/catch 제거.

## 외부 시스템 통신 매트릭스

| 출발 | 도착 | 프로토콜 | 토픽/엔드포인트 |
|---|---|---|---|
| 브라우저 | gateway | HTTP | `/api/v1/payments/{checkout,confirm,status}/...` |
| gateway | payment-service | HTTP | Eureka 라우팅 |
| gateway | product-service | HTTP | Eureka 라우팅 |
| gateway | user-service | HTTP | Eureka 라우팅 |
| payment-service | product-service | HTTP (Feign + LB) | `GET /api/v1/products/{id}` |
| payment-service | user-service | HTTP (Feign + LB) | `GET /api/v1/users/{id}` |
| payment-service → pg-service | Kafka | one-way | `payment.commands.confirm` (최초 confirm 명령) |
| pg-service → pg-service | Kafka | self-loop | `payment.commands.confirm` 재발행 (자체 retry, attempt < 4) — `pg_outbox.available_at` 기반 지연 발행 |
| pg-service → DLQ | Kafka | one-way | `payment.commands.confirm.dlq` (attempt ≥ 4 시 격리, `PgVendorCallService.insertDlqOutbox`) |
| pg-service → payment-service | Kafka | one-way | `payment.events.confirmed` (PG 결과 회신 — APPROVED/FAILED/QUARANTINED) |
| payment-service → DLQ | Kafka | one-way | `payment.events.confirmed.dlq` (`PaymentConfirmDlqKafkaPublisher` — 결과 처리 영구 실패 시) |
| payment-service → product-service | Kafka | one-way | `payment.events.stock-committed` (APPROVED 시만 — RDB 누적 차감 ledger) |
| pg-service → 벤더 | HTTP | one-way | Toss / NicePay confirm/getStatus |

## 관측성 통합

| 시스템 | 통합 방식 |
|---|---|
| Prometheus | 각 서비스 `/actuator/prometheus` 스크랩 (15s) |
| Grafana | Prometheus + Loki + Tempo 데이터소스 |
| Loki | Logback `LogstashEncoder` + LogFmt → Promtail/직접 push |
| Tempo | OTel exporter (`io.opentelemetry:opentelemetry-exporter-otlp`) |
| traceparent 전파 | OTel propagation — Servlet/VT/Async/Kafka producer/consumer 경계 모두 |

## 로컬 개발 시 외부 의존 관리

| 의존 | 안 떠 있을 때 동작 |
|---|---|
| 다른 비즈니스 서비스 | checkout/confirm 시 503 (`USER_SERVICE_UNAVAILABLE` / `PRODUCT_SERVICE_UNAVAILABLE`) |
| Kafka | confirm 은 HTTP 202 까지 가지만 outbox→Kafka 발행 실패 → relay 재시도 또는 DLQ. payment.events.confirmed consumer 도 미동작 → status 영구 PROCESSING |
| Redis dedupe | `EventDedupeStore` 호출 실패 → CACHE_DOWN 경로 → QUARANTINED + 보상 펜딩 |
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
