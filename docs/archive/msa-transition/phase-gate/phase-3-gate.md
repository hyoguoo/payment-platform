# Phase 3 Gate — 주변 도메인 + Saga 보상 왕복 E2E 검증

**태스크**: T3-Gate
**날짜**: 2026-04-21
**관련 태스크**: T3-01 ~ T3-07 (Phase 3 전체 — 상품·사용자 서비스 분리)

---

## 목적

T3-01 ~ T3-07 완료 후 아래 항목을 자동화 스크립트로 종합 검증한다.

- **product/user-service 독립 기동**: 각 서비스가 독립 포트(8083/8084)에서 정상 응답하는지 확인
- **Gateway 라우트**: T3-07에서 추가된 `/api/v1/products/**`, `/api/v1/users/**` 라우트 동작 확인
- **stock-snapshot 토픽**: T3-01에서 신설된 `product.events.stock-snapshot` Kafka 토픽 존재 확인
- **StockCommit dedupe**: T3-04 산출물 — RDB+Redis 원자적 호출 + eventUUID dedupe 검증
- **StockRestore dedupe**: T3-05 산출물 — 불변식 14(이중 복원 방지) + TTL 기반 dedupe 검증
- **product→payment Redis SET**: T3-04 StockRedisAdapter — 단위 테스트 커버 확인
- **FailureCompensationService**: T3-04b — FAILED 전이 시 보상 outbox 발행 + 멱등 UUID 보장
- **HTTP 어댑터 스위치**: T3-06 @ConditionalOnProperty 병행 유지 확인 (Strangler Vine 원칙)
- **전체 Gradle test 516건 이상** PASS (회귀 없음)

이를 통해 Phase 3(상품·사용자 서비스 분리) 전체 완료를 확인하고, Phase 4(장애 주입 검증) 진입 전 broken foundation을 방지한다.

---

## 전제 조건

### depends

| 태스크 | 내용 | 상태 |
|--------|------|------|
| T3-01 | product-service 모듈 신설 + stock-snapshot 발행 훅 | 완료 |
| T3-02 | user-service 모듈 신설 + 도메인 이관 | 완료 |
| T3-03 | Fake 상품·사용자 서비스 구현 | 완료 |
| T3-04 | StockCommitConsumer + payment-service 전용 Redis 직접 SET | 완료 |
| T3-04b | FAILED 결제 stock.events.restore 보상 이벤트 발행 (UUID 멱등) | 완료 |
| T3-05 | 보상 이벤트 consumer dedupe 구현 | 완료 |
| T3-06 | 결제 서비스 ProductPort/UserPort → HTTP 어댑터 교체 | 완료 |
| T3-07 | Gateway 라우팅: 상품·사용자 엔드포인트 교체 | 완료 |

### 필수 소프트웨어

```bash
# macOS
brew install jq

# kafka-topics CLI — Kafka 컨테이너 내 제공 (별도 설치 불필요)
# docker, curl — 기본 제공
```

### 포트 구성

| 서비스 | 포트 | 비고 |
|--------|------|------|
| Gateway | 8080 | Spring Cloud Gateway |
| payment-service | 8081 | 결제 서비스 |
| pg-service | 8082 | PG 처리 서비스 |
| product-service | 8083 | T3-01 application.yml 고정 |
| user-service | 8084 | T3-02 application.yml 고정 |
| MySQL (payment) | 3307 | docker-compose.infra.yml |
| MySQL (pg) | 3308 | docker-compose.infra.yml |
| Redis (payment) | 6380 | product→payment 전용 Redis (product-service 쓰기) |

### Kafka 토픽

| 토픽 | 생산자 | 소비자 |
|------|--------|--------|
| `product.events.stock-snapshot` | product-service | 모니터링 |
| `payment.events.stock-committed` | payment-service | product-service |
| `stock.events.restore` | payment-service | product-service |

### 환경 변수

| 변수 | 기본값 | 설명 |
|------|--------|------|
| `PRODUCT_SERVICE_BASE` | `http://localhost:8083` | product-service 베이스 URL |
| `USER_SERVICE_BASE` | `http://localhost:8084` | user-service 베이스 URL |
| `GATEWAY_BASE` | `http://localhost:8080` | Gateway 베이스 URL |
| `KAFKA_CONTAINER` | `payment-kafka` | Kafka 컨테이너명 |
| `KAFKA_BOOTSTRAP` | `localhost:9092` | Kafka bootstrap 주소 (컨테이너 내부) |

### 서비스 기동 순서

```bash
# 1. 인프라 컨테이너 기동
docker compose -f docker-compose.infra.yml up -d

# 2. Kafka 토픽 생성
bash scripts/phase-gate/create-topics.sh

# 3. 각 서비스 기동 (별도 터미널)
./gradlew :product-service:bootRun     # 포트 8083
./gradlew :user-service:bootRun        # 포트 8084
./gradlew :payment-service:bootRun     # 포트 8081
./gradlew :pg-service:bootRun          # 포트 8082
./gradlew :gateway:bootRun             # 포트 8080

# 4. Phase 3 Gate 스크립트 실행
bash scripts/phase-gate/phase-3-gate.sh
```

> **주의사항**:
> - 로컬 실행 전제 (docker-compose 기동 필요). product-service/user-service 컨테이너 배포는 별도 태스크.
> - 포트 충돌 주의: 8080(Gateway), 8081(payment), 8082(pg), 8083(product), 8084(user) 동시 기동.
> - Gradle 테스트(단위/통합)는 서비스 미기동 상태에서도 실행 가능 — a/b/c/d 섹션은 서비스 기동 필요.
> - a/b/c 섹션은 서비스 미기동 시 `[SKIP]`으로 자동 처리.

---

## 실행 방법

```bash
# 기본 실행
bash scripts/phase-gate/phase-3-gate.sh

# 서비스 포트 재정의
PRODUCT_SERVICE_BASE=http://localhost:8083 \
USER_SERVICE_BASE=http://localhost:8084 \
  bash scripts/phase-gate/phase-3-gate.sh
```

---

## 검증 항목 체크리스트

각 항목은 `scripts/phase-gate/phase-3-gate.sh` 스크립트와 1:1 대응한다.
`(Gradle)` 표시 항목은 Gradle 테스트 러너로 위임한다.
`[SKIP]` 항목은 서비스/Kafka 미기동 환경에서 자동 스킵된다.

### Section pre. 사전 조건

| # | 항목 | 검증 방법 | 기대 결과 |
|---|------|-----------|-----------|
| 전제 | `docker`, `curl`, `jq` 설치 | `command -v` | 3개 모두 존재 |
| pre | `./gradlew test` 전체 PASS | Gradle exit 0 | 516건 이상 |

### Section a. product-service 독립 기동

| # | 항목 | 검증 방법 | 기대 결과 |
|---|------|-----------|-----------|
| a | `product-service /actuator/health` UP | curl | status=UP (포트 8083) |

> product-service 미기동 시 `[SKIP]` 처리.

### Section b. user-service 독립 기동

| # | 항목 | 검증 방법 | 기대 결과 |
|---|------|-----------|-----------|
| b | `user-service /actuator/health` UP | curl | status=UP (포트 8084) |

> user-service 미기동 시 `[SKIP]` 처리.

### Section c. Gateway 라우트 확인 (T3-07)

| # | 항목 | 검증 방법 | 기대 결과 |
|---|------|-----------|-----------|
| c-1 | `/api/v1/users/{id}` → 200/404/503 | curl -w "%{http_code}" | 라우트 경유 확인 (200/404 정상, 503은 user-service 미기동) |
| c-2 | `/api/v1/products/{id}` → 200/404/503 | curl -w "%{http_code}" | 라우트 경유 확인 |

> Gateway 미기동 시 `[SKIP]` 처리. Eureka lb 경유 (lb://product-service, lb://user-service).

### Section d. stock-snapshot 토픽 확인

| # | 항목 | 검증 방법 | 기대 결과 |
|---|------|-----------|-----------|
| d | `product.events.stock-snapshot` 토픽 존재 + 파티션 확인 | `kafka-topics --describe` | 토픽 존재, PartitionCount 확인 |

> Kafka 미기동 시 `[SKIP]` 처리.

### Section e. StockCommit dedupe 검증 (T3-04)

| # | 항목 | 검증 방법 | 기대 결과 |
|---|------|-----------|-----------|
| e-1 | `StockCommitConsumerTest` (1케이스) | Gradle `:product-service:test` | usecase 1회 위임 |
| e-2-TC1 | RDB UPDATE 후 Redis SET 순서대로 호출 | `StockCommitUseCaseTest` (Gradle) | 재고 감소 + setCallCount=1 |
| e-2-TC2 | 동일 eventUUID 2회 → Redis SET 0회 (dedupe) | `StockCommitUseCaseTest` (Gradle) | setCallCount=0 |
| e-2-TC3 | RDB UPDATE 실패 시 Redis SET 호출 0회 | `StockCommitUseCaseTest` (Gradle) | IllegalStateException, setCallCount=0 |

### Section f. StockRestore dedupe 검증 (T3-05 — 불변식 14)

| # | 항목 | 검증 방법 | 기대 결과 |
|---|------|-----------|-----------|
| f-1 | `StockRestoreConsumerTest` (1케이스) | Gradle `:product-service:test` | usecase 1회 위임 |
| f-2-TC-R1 | restore 호출 시 재고가 qty만큼 증가 | `StockRestoreUseCaseTest` (Gradle) | 재고 증가 확인 |
| f-2-TC-R2 | 동일 eventUuid 2회 → 두 번째 no-op (불변식 14) | `StockRestoreUseCaseTest` (Gradle) | 이중 복원 방지 |
| f-2-TC-R3 | TTL 만료 후 재처리 → 재고 증가 1회 | `StockRestoreUseCaseTest` (Gradle) | TTL-aware 재처리 |
| f-2-TC-R4 | 재고 증가 실패 시 dedupe 미기록 | `StockRestoreUseCaseTest` (Gradle) | IllegalStateException, dedupe 미기록 |

### Section g. product→payment Redis SET 확인

| # | 항목 | 검증 방법 | 기대 결과 |
|---|------|-----------|-----------|
| g | `StockCommitUseCaseTest` TC1+TC3 커버 | Gradle `:product-service:test` (e-2 재실행) | TC1(RDB+Redis 순서) + TC3(RDB 실패 Redis 미호출) GREEN |

> 별도 통합 smoke 여부 확인. 현 단위 테스트로 TC1(RDB+Redis 순서) TC3(RDB 실패 Redis 미호출) 커버됨. 로컬 E2E는 optional.

### Section h. FailureCompensationService 검증 (T3-04b)

| # | 항목 | 검증 방법 | 기대 결과 |
|---|------|-----------|-----------|
| h-TC1 | FAILED 전이 시 stock.events.restore outbox row 1건 INSERT | `FailureCompensationServiceTest` (Gradle) | orderId·productId·qty·eventUUID 필드 포함 |
| h-TC2 | 동일 orderId 2회 → outbox row 1건만 (멱등 UUID) | `FailureCompensationServiceTest` (Gradle) | `whenFailed_IdempotentWhenCalledTwice` PASS |

### Section i. HTTP 어댑터 스위치 확인 (T3-06)

| # | 항목 | 검증 방법 | 기대 결과 |
|---|------|-----------|-----------|
| i-TC1 | HTTP 응답 → 도메인 DTO 변환 | `ProductHttpAdapterTest` (Gradle) | `getProduct_ShouldCallProductServiceAndReturnDomain` PASS |
| i-TC2 | HTTP 503 → RetryableException | `ProductHttpAdapterTest` (Gradle) | `decreaseStock_WhenServiceUnavailable_ShouldThrowRetryableException` PASS |

> Strangler Vine 원칙 확인: `InternalProductAdapter`(matchIfMissing=true 기본 유지) + `ProductHttpAdapter`(@ConditionalOnProperty product.adapter.type=http 활성화 시).

### Section j. 전체 Gradle test

| # | 항목 | 검증 방법 | 기대 결과 |
|---|------|-----------|-----------|
| j | `./gradlew test` 전체 모듈 PASS | Gradle exit 0 | 516건 이상 |

---

## Gate 통과 기준

스크립트 종료 코드 `0` (exit 0) + 모든 FAIL 항목 없음 (`[SKIP]` 허용).

| 섹션 | 합격 기준 |
|------|-----------|
| pre | `./gradlew test` 516건 이상 PASS |
| a | product-service /actuator/health UP (포트 8083, 또는 SKIP) |
| b | user-service /actuator/health UP (포트 8084, 또는 SKIP) |
| c | Gateway /api/v1/users·/api/v1/products 라우트 동작 확인 (또는 SKIP) |
| d | product.events.stock-snapshot 토픽 존재 (또는 SKIP) |
| e | StockCommitConsumerTest 1건 + StockCommitUseCaseTest 3건 GREEN |
| f | StockRestoreConsumerTest 1건 + StockRestoreUseCaseTest 4건 GREEN (불변식 14) |
| g | StockCommitUseCaseTest TC1+TC3 GREEN |
| h | FailureCompensationServiceTest 2건 GREEN (멱등 UUID) |
| i | ProductHttpAdapterTest 2건 GREEN (Strangler Vine 병행 유지) |
| j | `./gradlew test` 516건 이상 PASS |

---

## 실패 시 대응 매트릭스

### pre: 전체 Gradle test 실패

```
[FAIL] pre. 전체 Gradle test
```

- 조치: `./gradlew test --info 2>&1 | grep -A 5 "FAILED"` 로 실패 테스트 확인.
- 해당 태스크로 복귀:
  - product-service 테스트 실패 → T3-03 ~ T3-05 확인
  - payment-service 테스트 실패 → T3-04b, T3-06 확인
  - gateway 테스트 실패 → T3-07 확인

### a: product-service 미기동

```
[FAIL] product-service /actuator/health → UP
```

- 복귀 태스크: **T3-01** (product-service 모듈 신설 + application.yml 포트 8083)
- 조치: `./gradlew :product-service:bootRun` 실행 후 포트 8083 확인.
- `PRODUCT_SERVICE_BASE` 환경 변수 재정의 필요 시 적용.

### b: user-service 미기동

```
[FAIL] user-service /actuator/health → UP
```

- 복귀 태스크: **T3-02** (user-service 모듈 신설 + application.yml 포트 8084)
- 조치: `./gradlew :user-service:bootRun` 실행 후 포트 8084 확인.

### c: Gateway 라우트 실패

```
[FAIL] Gateway /api/v1/users/{id} 라우트
[FAIL] Gateway /api/v1/products/** 라우트
```

- 복귀 태스크: **T3-07** (gateway/src/main/resources/application.yml 라우트 추가)
- 조치:
  ```bash
  # application.yml에서 아래 라우트 존재 확인
  # products-service-route: lb://product-service, /api/v1/products/**
  # users-service-route: lb://user-service, /api/v1/users/**
  ```

### d: stock-snapshot 토픽 없음

```
[FAIL] product.events.stock-snapshot 토픽 존재
```

- 복귀 태스크: **T3-01** (KafkaTopicConfig + StockSnapshotPublisher)
- 조치:
  ```bash
  bash scripts/phase-gate/create-topics.sh
  # 또는 product-service 기동 시 KafkaTopicConfig 자동 생성 확인
  docker exec payment-kafka kafka-topics \
    --bootstrap-server localhost:9092 \
    --create --topic product.events.stock-snapshot --partitions 3 --replication-factor 1
  ```

### e: StockCommit 테스트 실패

```
[FAIL] StockCommitConsumerTest
[FAIL] StockCommitUseCaseTest
```

- 복귀 태스크: **T3-04** (StockCommitConsumer + StockRedisAdapter)
- 조치:
  ```bash
  ./gradlew :product-service:test --tests '*.StockCommitConsumerTest' --info
  ./gradlew :product-service:test --tests '*.StockCommitUseCaseTest' --info
  ```
- TC3(RDB 실패 Redis 미호출) 실패 시: StockCommitUseCase에서 RDB 실패 후 Redis 호출 여부 확인.

### f: StockRestore 테스트 실패

```
[FAIL] StockRestoreConsumerTest
[FAIL] StockRestoreUseCaseTest
```

- 복귀 태스크: **T3-05** (StockRestoreConsumer + JdbcEventDedupeStore)
- 조치:
  ```bash
  ./gradlew :product-service:test --tests '*.StockRestoreConsumerTest' --info
  ./gradlew :product-service:test --tests '*.StockRestoreUseCaseTest' --info
  ```
- TC-R2(불변식 14) 실패 시: `EventDedupeStore.existsValid` TTL 체크 로직 확인.
- TC-R4(dedupe 미기록) 실패 시: `recordIfAbsent`가 재고 증가 성공 후에만 호출되는지 확인.

### g: product→payment Redis SET 단위 테스트 실패

```
[FAIL] product→payment Redis SET 단위 테스트
```

- 복귀 태스크: **T3-04** (StockRedisAdapter + StockCommitUseCase 원자성)
- 조치: `e` 섹션 원인 확인 후 재시도 (동일 테스트 클래스).

### h: FailureCompensationService 테스트 실패

```
[FAIL] FailureCompensationServiceTest
```

- 복귀 태스크: **T3-04b** (FailureCompensationService + StockRestoreEventPayload)
- 조치:
  ```bash
  ./gradlew :payment-service:test --tests '*.FailureCompensationServiceTest' --info
  ```
- `whenFailed_IdempotentWhenCalledTwice` 실패 시: 결정론적 UUID 생성(orderId+productId 기반 UUID v3) 확인.

### i: HTTP 어댑터 테스트 실패

```
[FAIL] ProductHttpAdapterTest
```

- 복귀 태스크: **T3-06** (ProductHttpAdapter + @ConditionalOnProperty)
- 조치:
  ```bash
  ./gradlew :payment-service:test --tests '*.ProductHttpAdapterTest' --info
  ```
- `decreaseStock_WhenServiceUnavailable_ShouldThrowRetryableException` 실패 시: `@CircuitBreaker` 위치가 adapter 내부 메서드인지 확인 (port 인터페이스 오염 금지 — ADR-02).

---

## Phase 3 완료 의의

Phase 3 Gate 통과 시 Phase 3(상품·사용자 서비스 분리) 전체가 완료된다.

```
Phase 3 태스크:
T3-01: product-service 신규 모듈 + 도메인 이관 + stock-snapshot 발행 훅
T3-02: user-service 신규 모듈 + 도메인 이관
T3-03: Fake 상품·사용자 서비스 구현
T3-04: StockCommitConsumer + payment-service 전용 Redis 직접 SET
T3-04b: FAILED 결제 stock.events.restore 보상 이벤트 발행 (UUID 멱등)
T3-05: 보상 이벤트 consumer dedupe 구현
T3-06: 결제 서비스 ProductPort/UserPort → HTTP 어댑터 교체
T3-07: Gateway 라우팅: 상품·사용자 엔드포인트 교체
T3-Gate: Phase 3 전체 E2E 검증  ← 이 Gate
```

### Phase 3 완료 시 확정되는 ADR

#### ADR-22 — user-service 신설

- user-service가 독립 포트(8084), 독립 DB, 독립 Flyway 스키마를 가진다.
- UserController: `GET /api/v1/users/{id}` — Gateway 경유 노출.
- payment-service는 UserHttpAdapter(@ConditionalOnProperty)를 통해 HTTP로 연결.

#### ADR-16 — 보상 이벤트 dedupe

- StockCommit dedupe: `PaymentStockCachePort` → product-service EventDedupeStore에서 UUID 중복 거부.
- StockRestore dedupe: `JdbcEventDedupeStore`(product-service 소유) — TTL 8일(Kafka retention 7+1).
- 불변식 14 보장: 동일 eventUuid로 재고 복원 1회만. TTL 만료 시 재처리 허용.

#### ADR-14 — 재시도 정책

- StockCommitConsumer(@ConditionalOnProperty, matchIfMissing=true): Kafka listener 자동 기동.
- StockRestoreConsumer(@ConditionalOnProperty, matchIfMissing=true): 동일 패턴 적용.
- T1-18 교훈 적용: `matchIfMissing=true`로 기본 활성화.

#### ADR-02 — 재확정: port 오염 금지

- `@CircuitBreaker`는 adapter 내부 메서드에만 적용 (port 인터페이스에 오염 금지).
- ProductHttpAdapter: `getProduct()`, `decreaseStock()` 메서드 레벨 @CircuitBreaker.
- UserHttpAdapter: 동일 패턴 적용.

---

## Phase 4 진입 기준

본 Gate PASS 시 Phase 4 (T4-01) 착수 가능하다.

| 기준 | 확인 방법 |
|------|-----------|
| Phase 3 Gate 스크립트 exit 0 | `bash scripts/phase-gate/phase-3-gate.sh` |
| `./gradlew test` 516건 이상 GREEN | j 섹션 결과 |
| FAIL 항목 0건 | 최종 요약 FAIL=0 |

Phase 4에서는 이 구조를 기반으로 Toxiproxy 장애 주입 검증 및 로컬 오토스케일러를 구현한다.

```
T4-01: Toxiproxy 장애 시나리오 스위트 8종
       (Kafka 지연, DB 지연, 프로세스 kill, 보상 중복,
        FCG PG timeout, Redis down, 재고 캐시 발산, DLQ 소진)
```
