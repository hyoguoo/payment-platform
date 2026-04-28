# Trace Continuity Smoke — 다중 홉 traceId 연속성 보증 문서

**산출물**: T-E3 (`PRE-PHASE-4-HARDENING`)
**날짜**: 2026-04-24
**스크립트**: `scripts/smoke/trace-continuity-check.sh`

---

## 목적

Phase 4 진입 전 "HTTP → Kafka → HTTP 다중 홉에서 traceId 가 끊기지 않는다"는 전제를 자동으로 검증한다.

T-E1(VT/@Async MDC 전파)과 T-E2(HTTP observationRegistry 자동화) 완료 후, 실제 compose-up 환경에서
실측 검증 경로를 확보하는 것이 목표다.

---

## 검증 시나리오

### 요청 흐름

```
테스트 클라이언트
  │  traceparent: 00-<trace-id>-<span-id>-01
  ▼
gateway (HTTP POST /api/v1/payments/checkout → 201)
  │  MDC traceId 전파 (TraceContextPropagationFilter)
  ▼
payment-service (checkout → outbox INSERT → confirm 발행)
  │  Kafka record header: traceparent
  ▼
pg-service (PaymentConfirmConsumer → FakePgGatewayStrategy → APPROVED)
  │  Kafka record header: traceparent (outbox relay 경로)
  ▼
payment-service (ConfirmedEventConsumer → done() → StockCommitRequestedEvent)
  │  AFTER_COMMIT listener → Kafka publish
  ▼
product-service (StockCommitConsumer → 재고 감소)

payment-service → user-service (HTTP 사용자 조회 — checkout 단계)
  RestClient.Builder / WebClient.Builder observationRegistry 자동 전파
```

### 검증 대상 서비스 및 로그 위치

| 서비스 | traceId 등장 예상 경로 | logback 패턴 |
|---|---|---|
| gateway | `TraceContextPropagationFilter` — HTTP 수신 직후 MDC 주입 | `[traceId:<trace-id>]` |
| payment-service | HTTP 수신 + `OutboxAsyncConfirmService` + `ConfirmedEventConsumer` + outbox relay | `[traceId:<trace-id>]` |
| pg-service | `PaymentConfirmConsumer` + `PgOutboxImmediateWorker` relay | `[traceId:<trace-id>]` |
| product-service | `StockCommitConsumer` (Kafka) + HTTP 수신 (재고 조회) | `[traceId:<trace-id>]` |
| user-service | HTTP 수신 (사용자 조회 — checkout 단계) | `[traceId:<trace-id>]` |

---

## 재현 명령어

### 1. 스택 기동 (이미 기동 중이면 생략)

```bash
# 전체 스택 기동 (bootJar 빌드 포함)
bash scripts/compose-up.sh

# pg-service smoke override 적용 (FakePgGatewayStrategy 활성화)
docker compose \
  -f docker/docker-compose.infra.yml \
  -f docker/docker-compose.apps.yml \
  -f docker/docker-compose.smoke.yml \
  up -d --force-recreate --no-deps pg-service

# pg-service 재기동 안정화 대기
sleep 15
```

### 2. 스크립트 실행

```bash
# 기본 실행
bash scripts/smoke/trace-continuity-check.sh

# 상세 로그 출력
bash scripts/smoke/trace-continuity-check.sh --verbose

# 스택 미기동 시 자동 compose-up 포함
bash scripts/smoke/trace-continuity-check.sh --auto-compose-up
```

### 3. 수동 검증 (스크립트 내부 로직 재현)

```bash
# traceId 생성
TRACE_ID="$(openssl rand -hex 16)"
SPAN_ID="$(openssl rand -hex 8)"
TRACEPARENT="00-${TRACE_ID}-${SPAN_ID}-01"

# checkout 요청
curl -sS -X POST http://localhost:8090/api/v1/payments/checkout \
  -H "Content-Type: application/json" \
  -H "traceparent: ${TRACEPARENT}" \
  -d '{"userId":1,"gatewayType":"TOSS","orderedProductList":[{"productId":1,"quantity":1}]}'

# (confirm 요청 후) 로그 수집
docker compose -f docker/docker-compose.apps.yml logs --since=5m gateway | grep "traceId:${TRACE_ID}"
docker compose -f docker/docker-compose.apps.yml logs --since=5m payment-service | grep "traceId:${TRACE_ID}"
docker compose -f docker/docker-compose.apps.yml logs --since=5m pg-service | grep "traceId:${TRACE_ID}"
docker compose -f docker/docker-compose.apps.yml logs --since=5m product-service | grep "traceId:${TRACE_ID}"
docker compose -f docker/docker-compose.apps.yml logs --since=5m user-service | grep "traceId:${TRACE_ID}"
```

---

## 실패 시 원인 트리아지

### 판정 기준

| 조건 | 판정 |
|---|---|
| 5개 서비스 모두 `traceId:<trace-id>` 포함 로그 존재 | PASS |
| 하나 이상 서비스에서 미발견 | FAIL |

### 서비스별 실패 원인

#### gateway 미등장

- `gateway/src/main/java/.../filter/TraceContextPropagationFilter.java` 가 MDC 에 `traceId` 키를 주입하는지 확인
- `gateway/src/main/resources/logback-spring.xml` 패턴에 `%X{traceId:-N/A}` 포함 여부 확인

#### payment-service 미등장

- T-E1 구현 확인: `MdcContextPropagationConfig.@PostConstruct registerMdcAccessor()` 가
  `ContextRegistry.getInstance().registerThreadLocalAccessor(new Slf4jMdcThreadLocalAccessor())` 를 호출하는지 점검
- T-E2 구현 확인: `HttpOperatorImpl` 생성자에 `WebClient.Builder webClientBuilder` 가 주입돼 있는지,
  `webClientBuilder.clone()...build()` 패턴으로 auto-config ObservationRegistry 가 상속되는지 점검
- `spring.kafka.listener.observation-enabled=true` 설정 여부 확인

#### pg-service 미등장

- T-E1 구현 확인: `PgServiceConfig.@PostConstruct registerMdcAccessor()` 가
  `PgSlf4jMdcThreadLocalAccessor` 를 ContextRegistry 에 등록하는지 점검
- `PgOutboxImmediateWorker.start()` 에서 `ContextExecutorService.wrap(...)` 적용 여부 확인
- `spring.kafka.listener.observation-enabled=true` 설정 여부 확인 (pg-service application.yml)

#### product-service 미등장

- `StockCommitConsumer` / `StockRestoreConsumer` 가 Kafka observation 을 통해 MDC 를 받는지 확인
- product-service 에 logback-spring.xml `traceId` MDC 패턴이 설정됐는지 확인

#### user-service 미등장

- user-service logback-spring.xml `traceId` MDC 패턴 설정 확인
- payment-service → user-service HTTP 호출 경로에서 `traceparent` 헤더 자동 전파 여부 확인
  (T-E2 `UserHttpAdapter` WebClient.Builder 사용 여부)

### 공통 점검 사항

```bash
# Kafka observation 설정 확인
grep -r "observation-enabled" */src/main/resources/application*.yml

# MDC accessor 등록 코드 확인
grep -r "registerThreadLocalAccessor\|ContextRegistry" */src/main/java --include="*.java"

# logback traceId 패턴 확인
grep -r "traceId" */src/main/resources/logback-spring.xml
```

---

## compose-up smoke override 전제

이 스크립트는 `docker-compose.smoke.yml` override 가 적용된 상태를 전제한다.

- `pg-service.environment.PG_GATEWAY_TYPE=fake` → `FakePgGatewayStrategy` 활성화
- `MANAGEMENT_TRACING_SAMPLING_PROBABILITY=1.0` → 5개 서비스 전부 100% 샘플링
- Toss/NicePay sandbox 호출 없이 happy path 전 구간 검증 가능

---

## Phase 4 진입 관문

이 스크립트가 exit 0 을 반환하는 것이 Phase 4 진입 전 `T-Gate` 종료 조건 중 하나다.

```
T-Gate 종료 조건:
  [ ] Critic + Domain Expert 재리뷰 양쪽 SHIP_READY verdict
  [ ] scripts/smoke/trace-continuity-check.sh PASS  ← 이 스크립트
  [ ] ./gradlew test 전수 PASS
```
