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
- `getStatus(orderId)` → 벤더 상태 조회 (복구 사이클 진입 전 선행 호출 — ADR-D1 reverse direction)
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
- AMOUNT_MISMATCH: 벤더 응답 amount 와 로컬 `paymentEvent.totalAmount` 불일치 → QUARANTINED (양방향 방어, ADR-15 + ADR-D1)

## Cross-service HTTP

payment-service 가 product-service / user-service 를 직접 HTTP 조회 (Eureka discovery + WebClient).

| 호출 | 경로 | 어댑터 |
|---|---|---|
| product 조회 | `GET /api/products/{id}` 등 | `ProductHttpAdapter` (payment-service 측) |
| user 조회 | `GET /api/users/{id}` | `UserHttpAdapter` (payment-service 측) |

**공통 어댑터** `HttpOperatorImpl` 가 traceparent 헤더 자동 주입. `MockWebServer` 기반 contract test 로 404/503/429/500 분기 계약 고정 (T3.5-10 + T-E2).

**회복성**: 현재 `try/catch` + 재시도. **CircuitBreaker 는 Phase 4 예정** (ADR-22).

## 외부 시스템 통신 매트릭스

| 출발 | 도착 | 프로토콜 | 토픽/엔드포인트 |
|---|---|---|---|
| 브라우저 | gateway | HTTP | `/api/v1/payments/{checkout,confirm,status}/...` |
| gateway | payment-service | HTTP | Eureka 라우팅 |
| gateway | product-service | HTTP | Eureka 라우팅 |
| gateway | user-service | HTTP | Eureka 라우팅 |
| payment-service | product-service | HTTP | `GET /api/products/*` |
| payment-service | user-service | HTTP | `GET /api/users/*` |
| payment-service ↔ pg-service | Kafka | bidirectional | `payment.commands.confirm` / `payment.events.confirmed` (+ DLQ 2종) |
| payment-service → product-service | Kafka | one-way | `stock.events.commit` (APPROVED 시만 — RDB 누적 차감 ledger) |
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
