# Planned Cleanup / Future Work

> 최종 갱신: 2026-05-17 (PAYMENT-EOS-TRANSITION 봉인 — TC-13 완료 + TC-13-FOLLOW-1~5 등재).
> 분류 룰: **현재 과업** = 측정 / Toxiproxy / 멀티 인스턴스 환경 의존 없는 작업. **Phase 5** = 부하 측정 결과 또는 인프라 환경 필요.
> discuss 단계 시작 시 다음 작업을 고를 때 이 파일을 참고한다.

---

## 토픽 묶음 계획 (PR 단위)

현재 과업 7개를 3 PR 로 진행. 작은 청소 → 도메인 결정 → 큰 정합성 순서.

| PR | 묶음명 | 토픽 | 크기 | 성격 | 상태 |
|---|---|---|---|---|---|
| **A** | 코드 청소 4건 | TC-16, TC-10, TC-2, TC-5 | ~2일 | 도메인 결정 없음, 영역 분리 | ✅ 완료 (브랜치 #75, PR 생성 예정) |
| **B** | 도메인 결정 묶음 | TC-4, TC-8 | ~3~4일 | EXPIRED 정책 + 시간 추상화 표준 결정 동반 | — |
| **C** | EOS 전환 (위키 정합) | TC-13 | ~1주+ | 가용성 결 트레이드오프 결정 동반 | ✅ 완료 (브랜치 #77, PR #77) |

### 묶음 근거

- **PR A** — 모두 작은 청소. 도메인 결정 없고 영역이 분리됨 (pg-service 도메인 / Flyway / ControllerAdvice / Lombok 패턴). plan / review 단계 부담 적음.
- **PR B** — 둘 다 도메인 결정 동반 (EXPIRED 전이 정책 + Clock vs LocalDateTimeProvider). 시간 표준 결정이 EXPIRED 만료 시각 타입에 영향이라 같이 가는 게 자연스러움.
- **PR C** — 가용성 결 트레이드오프 (Kafka tx coordinator 의존) 결정 동반. 변경 범위 가장 큼.

### 권장 순서

A → B → C. 작은 청소로 직전 토픽 (PG-CONFIRM-LISTENER-SPLIT) 맥락 살아있을 때 마무리하고, 도메인 결정 묶음 거쳐 EOS 큰 토픽으로.

---

## 현재 과업 (작업 가능 — 측정 / 인프라 무관)

### A. 위키 정합 (큰 토픽 1)

#### ~~TC-13 — payment-service EOS 전환 (위키 sync 잔여 갭) `[PR C]`~~ ✅ 완료 (PR #77, 2026-05-17)

PAYMENT-EOS-TRANSITION 봉인으로 완료. 상세: `docs/archive/payment-eos-transition/` (review 완료 후 이동 예정).

**완료 내용**:
1. `ConfirmedEventConsumer` → `KafkaTransactionManager` 통합 (EOS consumer wiring, PET-7)
2. `KafkaProducerConfig` EOS-aware `stockCommittedProducerFactory` + `KafkaTransactionManager` + `stockCommittedKafkaTemplate` 빈 (transactional.id, PET-6)
3. `payment_event_dedupe` 테이블 신설 (Flyway V2) + `JdbcPaymentEventDedupeStore` 어댑터 (PET-4/PET-5)
4. `PaymentConfirmResultUseCase` 재작성 — D7 가드 + D5 멱등 마킹 + D8 multi-product 직접 발행 (PET-8)
5. `StockOutbox` 묶음 16+ 파일 삭제 + `payment_stock_outbox` 테이블 DROP (Flyway V3, PET-9/PET-10)
6. product-service `isolation.level=read_committed` 적용 (PET-11)
7. Testcontainers 통합 5 시나리오 GREEN (PET-12)
8. 위키 4개 + 영구 문서 6개 갱신 (PET-13/PET-14)

#### TC-13-FOLLOW-1 — multi-instance 확장 시 docker-compose hostname 처리 (DR-2 / L6)

- **트리거**: payment-service 를 2개 이상 컨테이너로 scale-out 할 때
- **문제**: `docker-compose.apps.yml` 의 `hostname: payment-service` 라인이 동일 hostname 을 두 컨테이너에 부여 → transactional.id 충돌 → Kafka producer fencing 동작 불확실
- **처방 후보**: (a) `hostname:` 라인 제거 (컨테이너 id 기반 자동 hostname 사용) 또는 (b) `INSTANCE_ID` 환경변수 도입 → `transactional.id = ${spring.application.name}-${INSTANCE_ID:${HOSTNAME:local}}`
- **선행**: Phase 5 멀티 인스턴스 환경 구성 시

#### TC-13-FOLLOW-2 — `payment_event_dedupe` TTL 정리 스케줄러 (TC-11 통합)

- **문제**: `expires_at = receivedAt + P8D` 컬럼 존재하지만 자동 cleanup 없음 → 만료 row 누적
- **처방 후보**: TC-11 (product/pg dedupe cleanup 스케줄러) 와 통합 토픽으로 묶기. `@Scheduled DELETE WHERE expires_at < NOW()`
- **관련 코드**: `payment-service/.../infrastructure/dedupe/JdbcPaymentEventDedupeStore.java`

#### TC-13-FOLLOW-3 — Kafka tx coordinator 가용성 모니터링 대시보드

- **문제**: EOS 전환 이후 Kafka tx coordinator 의존 (L1). coordinator 장애 시 처리 멈춤 조기 탐지 수단 없음
- **처방 후보**: Grafana 대시보드 — `kafka_producer_transaction_in_progress_total` / `kafka_consumer_fetch_manager_records_lag_max` 임계 알람

#### TC-13-FOLLOW-4 — D7 가드 분기 알람 SLO (DM2-3)

- **문제**: `isCompensatableByFailureHandler()` 가 false 로 noop 한 케이스 (QUARANTINED 늦은 APPROVED 등) 가 운영 시 얼마나 발생하는지 모니터링 수단 없음
- **처방 후보**: `PaymentConfirmResultUseCase` 내 D7 가드 분기에 `payment_eos_guard_skip_total{status}` Micrometer 카운터 + Grafana 알람 SLO

#### TC-13-FOLLOW-6 — `@Transactional` qualifier 명시 또는 ChainedKafkaTransactionManager 검토 (RD1-2)

- **문제**: `PaymentConfirmResultUseCase.handle` 의 `@Transactional(timeout=5)` 가 qualifier 미명시로 `@Primary JpaTransactionManager` 를 선택. `KafkaTransactionManager(EOS)` 와 별개 TM 으로 동작해 crash 시 at-least-once 재배달이 발생 가능.
- **정합 SSOT**: "중복 시 발행 항상 진행 (위키 line 141)" + product-service dedupe 조합 (CONCERNS.md L-1, CONFIRM-FLOW.md §5).
- **처방 후보**: (a) `@Transactional("transactionManager")` qualifier 명시 (현재 동작 그대로 문서화) 또는 (b) `ChainedKafkaTransactionManager` 도입 — JPA TM 과 Kafka TM 을 체인해 원자성 강화.
- **선행**: 운영 환경에서 at-least-once 허용 불가 수준의 중복 발생 시 우선 처리.

#### TC-13-FOLLOW-5 — D7 `isCompensatableByFailureHandler` 시맨틱 SSOT 정리 (DM2-2 후속)

- **문제**: `isCompensatableByFailureHandler` 가 두 사용처 (보상 핸들러 재고 복원 허용 / EOS consumer 진입 가드) 에 사용됨. 시맨틱이 두 도메인 목적을 동시에 표현해 변경 시 하나를 깨뜨릴 수 있음
- **PET-3 에서 해결한 부분**: Javadoc 보강 (두 사용처 명시 + DR-3 영향 경고)
- **잔여 정리**: 두 용도를 각각 명시적 메서드로 분리하거나 SSOT 결정 별 토픽

---

### B. 코드 청결도 (측정 무관 6개)

#### TC-4 — EXPIRED 만료 스케줄러 정책 명확화 `[PR B]`

- `PaymentEventStatus.EXPIRED` 정의는 있으나 도메인 매핑은 일부만 활성
- 만료 스케줄러 정책 (몇 시간 후 EXPIRED 전이?) 별도 토픽 정리 필요

#### TC-8 — 시간 추상화 통합 (Clock / Instant / LocalDateTime 혼용 정리) `[PR B]`

서비스 간 + 서비스 내부에서 시간 처리 방식이 일관되지 않음.

**현황**:
- **payment-service**: `LocalDateTimeProvider` 포트 + `SystemLocalDateTimeProvider` 구현. `LocalDateTime` 위주. 일부 메서드에 `Instant.now()` default 도 노출
- **pg-service**: `Clock` 직접 빈 주입. 서비스 / 워커 / 메트릭은 `Clock` 사용 (`clock.instant()`). 단 도메인 (`PgInbox`, `PgOutbox`) 내부는 `Instant.now()` 직접 호출 — Clock 미주입 시점 혼재
- **product-service / user-service**: 미조사 (작업 시 추가 검사 필요)
- **외부 PG strategy**: `TossPaymentGatewayStrategy:244` 에 `LocalDateTime.now()` 직접 호출 잔존

**문제점**:
1. **인지 부담**: 두 서비스의 다른 추상화 (`LocalDateTimeProvider` vs `Clock`) 학습 비용
2. **도메인 의미 불일치**: `LocalDateTime` (timezone 정보 없음) vs `Instant` (UTC 기반) — 같은 결제 시각을 두 형태로 표현
3. **testability 흠집**: 도메인 안의 `Instant.now()` / `LocalDateTime.now()` 직접 호출 → fixed clock 주입 불가, 시간 의존 단위 테스트 어려움
4. **pg-service 내부 일관성도 일부 깨짐**: 서비스는 Clock, 도메인은 `Instant.now()` 직접

**조정 방향 (검토 필요)**:
1. **표준 선택** — 두 후보:
   - (a) `Clock` + `Instant` 통일 (modern, timezone 안전, JDK 권장 패턴)
   - (b) `LocalDateTimeProvider` 같은 자체 포트 + `LocalDateTime` 통일
2. **도메인 메서드 시그니처에 시간 인자 명시** — `markSeen()` / `done(now)` 처럼 호출자가 시간 주입 → 도메인 안에서 `now()` 직접 호출 금지
3. **외부 PG strategy 잔존 `LocalDateTime.now()` 제거** — Clock 주입으로 교체

**관련 코드**:
- `payment-service/.../core/common/service/port/LocalDateTimeProvider.java`
- `payment-service/.../core/common/infrastructure/SystemLocalDateTimeProvider.java`
- `pg-service/.../infrastructure/config/PgServiceConfig.java:25` (Clock Bean)
- `pg-service/.../domain/PgInbox.java:40,107,139,174,212` (Instant.now() 직접 호출)
- `pg-service/.../domain/PgOutbox.java:45,51` (동)
- `pg-service/.../infrastructure/gateway/toss/TossPaymentGatewayStrategy.java:244` (LocalDateTime.now() 잔존)

### C. CLEANUP-BATCH-A 후속 등재

#### [NET-RETRY] — Feign ErrorDecoder 429/503 분기 보존

현재 `ProductFeignConfig.ErrorDecoder` / `UserFeignConfig.ErrorDecoder` 가 429/503 두 status code 를 단일 `*ServiceRetryableException` 으로 통합. CLEANUP-BATCH-A (CBA-6) 에서 503 일괄 매핑 결정으로 정보 손실 구조가 코드에 확정됨. 추가로 ErrorDecoder 4 분기 중 "그 외 5xx (500/502/504) → `IllegalStateException` → `GlobalExceptionHandler` 500 응답" 경로가 본 토픽으로 변경되지 않아, vendor 게이트웨이 504/502 같은 비-503 5xx 도 500 으로 노출되는 갭 잔존.

후속 토픽에서 (1) ErrorDecoder 단계에 status code 별 분기 예외 타입 도입 + `PaymentExceptionHandler` 의 503/429 분리 매핑 (2) 비-503 5xx 전용 매핑 결정 (예: 502/504 → 503 또는 별도 응답 코드) 이 필요.

**관련 코드**:
- `payment-service/.../infrastructure/feign/ProductFeignConfig.java` (ErrorDecoder)
- `payment-service/.../infrastructure/feign/UserFeignConfig.java` (ErrorDecoder)
- `payment-service/.../exception/common/PaymentExceptionHandler.java`

#### [FLYWAY-USER-SEED-GAP] — user-service Testcontainers 검증 부재

CBA-7 이 product-service `FlywayDockerProfileTest` 1건만 추가. user-service `application-docker.yml` 의 `spring.flyway.locations: classpath:db/schema` override 가 회귀 무방어 상태. 후속 토픽에서 user-service 동등 Testcontainers 테스트 추가 또는 infra-healthcheck 스크립트에 V2 row count=0 체크 추가 결정 필요.

**관련 코드**:
- `user-service/src/main/resources/application-docker.yml`
- `user-service/src/main/resources/db/schema/V1__user_schema.sql`

---

## Phase 5 — 추후 (부하 측정 / 인프라 의존)

> 모두 (a) k6 부하 측정 결과 또는 (b) Toxiproxy 8종 장애 주입 환경 또는 (c) 멀티 인스턴스 환경이 필요한 작업. Phase 4 환경이 준비된 뒤 진행.

### Phase 4 본진 (4개)

#### T4-A — Toxiproxy 8종 장애 주입 시나리오

- Kafka producer/consumer 지연
- DB 지연 / 연결 끊김
- payment-service / pg-service 프로세스 kill + 재시작
- 보상 트랜잭션 중복 진입 방지 (D12 가드 실증)
- FCG (Final Confirmation Gate) PG timeout
- Redis dedupe / stock cache 다운
- 재고 캐시 발산 시나리오
- DLQ 소진

각 시나리오: `payment_outbox_pending_age_seconds` p95≥10s, 결제·재고 정합성 교차 검증.

#### T4-B — k6 시나리오 재설계

- Gateway → payment confirm → 비동기 status 폴링 단일 시나리오
- 경로별 TPS / p95 / p99 / failure rate 메트릭
- ramping-arrival-rate 부하 곡선

#### T4-C — 로컬 오토스케일러

- Prometheus 큐 길이 / CPU 임계 기반 payment-service 레플리카 자동 scale
- docker compose scale up/down 자동화
- scale 결정 logging + Grafana dashboard

#### T4-D — CircuitBreaker 적용

- `ProductHttpAdapter` / `UserHttpAdapter` 에 Resilience4j CircuitBreaker
- Prometheus 메트릭 (`circuit_breaker_state`, `circuit_breaker_calls_total`)
- 폐쇄/반열림/열림 상태 시각화
- **이 도입과 동시에**: 어댑터의 `try/catch (feign.RetryableException)` 매핑을 Feign **fallbackFactory** 로 마이그레이션
- **timeout 정밀 튜닝**: `application.yml` 의 `spring.cloud.openfeign.client.config.default.{connectTimeout: 2000, readTimeout: 5000}` baseline 을 Phase 4 부하 측정 기반 SLO 로 조정
- **pg-service 외부 PG timeout 정밀 튜닝**: `pg.http.{connect-timeout-millis: 3000, read-timeout-millis: 10000}` 은 현재 측정 없는 baseline. T4-B/T4-A 부하 + 장애 주입 측정 결과로 SLO 기반 값으로 교체. `max.poll.records` 기본값(500) 검증도 병행

### Phase 4 후속 — 자동 운영 도구 (7개)

#### TQ-1 — DLQ 처리 정책 + admin tool

- `payment.commands.confirm.dlq`, `payment.events.confirmed.dlq` 가 자동 처리되지 않음
- 별도 admin endpoint 또는 CLI 로 트리아지 + 재발행 가능하도록
- 조건부 자동 재시도 (벤더 5xx 같은 일시적 실패)

#### TQ-2 — QUARANTINED-ADMIN-RECOVERY

- `PaymentEventStatus.QUARANTINED` 결제의 수동 복구 인터페이스
- 관리자가 검토 후 DONE / FAILED 로 강제 전이 + audit
- 격리 사유별 (AMOUNT_MISMATCH, CACHE_DOWN, 판단 불가) UI

#### TQ-3 — REDIS-CACHE-FAILURE-POLICY

- `redis-stock` 다운 시 어떤 정책으로 가야 하는가? — 현재는 CACHE_DOWN → QUARANTINED + 보상 펜딩
- redis 데이터 lost 시 부팅 재시드(`scripts/seed-stock.sh`) 외 회복 경로 없음 — payment 진행 중이면 Redis 키 부재로 confirm DECR 음수 가능성
- 운영 시 Redis HA / fallback / AOF 운영 가이드 결정 필요

#### TQ-4 — Vendor 동적 라우팅

- 현재 `gatewayType` 은 client 결정. 벤더 장애 시 자동 fallback 미구현
- 헬스 체크 기반 동적 라우팅 정책

#### TQ-5 — multi-broker Kafka

- 현재 broker 1대 + replication-factor=1
- HA 환경 검증 필요

#### TQ-6 — Cancel / Refund 워크플로우

- `PgGatewayPort.cancel(...)` 인터페이스만 존재
- 운영 cancel 정책 + 부분 환불 + audit trail

#### TQ-7 — STOCK-COMPENSATION-OTHER-PATHS (보상 패턴 일관 적용)

STOCK-COMPENSATION-RECOVERY 가 `PaymentConfirmResultUseCase.handleFailed` / `handleQuarantined` 만 Lua atomic + dedup token 으로 정리. 동일 silent loss 패턴이 남아 있는 다른 경로들을 같은 모델로 일관 적용.

**현황**:
- `OutboxAsyncConfirmService.compensateStock` (line 99-119) — confirm TX 실패 보상. 같은 try/catch swallow 패턴, 동일 Lua atomic 모델 재사용 가능
- `PaymentTransactionCoordinator.compensateStockCacheGuarded` (line 168-180) — D12 재고 복구 가드 보상

**추가 정밀화 필요 사항**:
- `decrement:done:{orderId}` dedup token namespace 정합 — confirm TX 실패 보상이 `decrementAtomic` 이미 박은 token 을 어떻게 처리할지 정책 결정 (DEL vs compensation token 박기)
- L6 cascade (보상 끝 결제 재confirm) 차단 layer 추가 검토

**관련 코드**:
- `payment-service/.../application/OutboxAsyncConfirmService.java`
- `payment-service/.../application/usecase/PaymentTransactionCoordinator.java`
- `payment-service/src/main/resources/lua/stock_compensation_atomic.lua` (재사용 가능)

### 측정 의존 코드 청결도 (8개)

#### TC-1 — observability 대시보드 현행화

- Grafana dashboard JSON 들이 옛 메트릭 이름 일부 사용 가능
- Phase 4 부하 테스트 시작 전 inventory + 갱신

#### TC-3 — 재고 동기화 정책 (부팅 외 시점)

- 새 재고 모델: redis-stock = payment 의 선차감 캐시, product RDB = SoT
- 현재는 부팅 직후 `scripts/seed-stock.sh` 가 mysql-product → redis-stock 으로 1회 시드. 이후 동기화 X
- 발산 발생 시점: product RDB 가 외부(관리자 / 입고 / 외부 시스템)에서 변경되면 Redis 와 발산
- 후보 방안: (a) admin endpoint `/admin/stock/resync` 로 수동 재시드, (b) product 가 RDB 변경 시 redis pub/sub 으로 cache invalidation, (c) 주기적 재시드 스케줄러

#### TC-6 — 가상 스레드 명시적 throttle / bulkhead 검토

- 현재 백프레셔는 다운스트림 자원 (Hikari 30, Kafka in-flight 5, Redis Lettuce single connection, scheduler batch-size 50) 으로 자연 형성
- 명시적 `Semaphore` / `RateLimiter` / Resilience4j `Bulkhead` 코드는 0건
- 위험 시나리오: 외부 PG (Toss/NicePay) 호출 시 벤더 측 rate limit 초과 / 다운스트림 다운 시 VT 가 timeout 까지 spawn 누적 → 메모리 압박
- 도입 후보: T4-D 의 Resilience4j 묶음에 `@Bulkhead("productService")` 추가, 또는 외부 PG 호출 어댑터에 명시 Semaphore. 측정값 기반으로 결정

#### TC-7 — outbox retry 정책 정렬 (stock 도입 + payment 재검토)

두 outbox 의 retry 정책이 비대칭. 같은 시점에 일관 정책으로 정렬 필요.

**현황**:
- `payment_outbox`: `RetryPolicy` 활용 중 — `RetryPolicyProperties` (env 주입) + maxAttempts=5 + FIXED 5s default. `incrementRetryCount(policy, now)` 호출 + `nextRetryAt` 시각 표현 + 한도 초과 시 종결
- `stock_outbox`: schema 와 `attempt` 필드 / `incrementAttempt()` 메서드 자리는 잡혀 있으나 **호출처 0건** — 실제 retry policy 미연결. 발행 실패 시 다음 cycle 그대로 재시도, counter 미관리, backoff 미적용

**조정 필요 사항**:
1. **stock_outbox 에 retry policy 도입** — `payment_outbox` 미러링. `incrementAttempt()` 활성화 + `nextAvailableAt` 또는 동등 backoff 도입 + 한도 초과 시 DLQ / FAILED 처리
2. **payment_outbox 정책 재검토** — 현재 maxAttempts=5 + FIXED 5s 가 SLO 기준 적절한지 측정 검증. backoff 가 EXPONENTIAL 가 더 적합한 시나리오인지 검토
3. **두 outbox 의 정책 일관성** — 같은 책임 (Kafka publish 실패 회복) 이라 동일 RetryPolicy 클래스 또는 통일된 properties 구조로 정렬

**관련 코드**:
- `payment-service/.../domain/PaymentOutbox.java` — retryCount + incrementRetryCount
- `payment-service/.../domain/StockOutbox.java:93` — incrementAttempt() (호출 0)
- `payment-service/.../application/config/RetryPolicyProperties.java`
- `payment-service/.../domain/RetryPolicy.java`

#### TC-9 — FakePgGatewayAdapter 의 vendor 멱등성 시뮬 추가

`FakePgGatewayAdapter` 가 같은 paymentKey 두 번 호출 시 `PgGatewayDuplicateHandledException` 을 던지지 않아 production vendor 의 멱등성 응답을 시뮬레이션하지 못함.

**현황**:
- IN_PROGRESS self-loop retry path 에서 vendor 재호출이 가능해짐
- production: Toss/NicePay 가 `paymentKey + orderId` 단위 멱등성 보장 → 두 번째 호출은 "이미 처리됨" 응답 → `PgGatewayDuplicateHandledException` → `DuplicateApprovalHandler` 가 흡수
- Fake: 두 번째 호출 시 도메인 가드 예외만 발생 → production 동작과 다름

**도입 시**: `FakePgGatewayAdapter` 에 "같은 paymentKey 가 이미 SUCCESS 로 처리됐으면 다음 호출 시 duplicate 예외 던짐" 모드 추가. T4-A 시 retry 시나리오 검증과 함께.

**관련 코드**:
- `pg-service/.../infrastructure/gateway/fake/FakePgGatewayStrategy.java`
- `pg-service/.../exception/PgGatewayDuplicateHandledException.java`
- `pg-service/.../application/service/DuplicateApprovalHandler.java`

#### TC-11 — product / pg dedupe 테이블 cleanup 스케줄러 부재

장기 운영 시 만료 row 누적으로 쿼리 성능 저하 가능.

**현황**:
- product-service `stock_commit_dedupe` — 만료 row 자동 cleanup 스케줄러 없음
- pg-service `pg_inbox` — 동일
- payment-service 의 Redis dedupe 는 TTL 자동 expire — 문제 없음

**도입 후보**:
- (a) `@Scheduled` cleanup 워커 — 주기적 `DELETE WHERE created_at < NOW() - INTERVAL X` (X = Kafka retention + 버퍼 = 8일 정도)
- (b) admin endpoint `/admin/dedupe/cleanup` 수동 트리거

**관련 코드**:
- `product-service/.../infrastructure/idempotency/JdbcEventDedupeStore.java`
- `pg-service/.../infrastructure/repository/PgInboxRepositoryImpl.java`

#### TC-12 — pg-service Worker.stop 채널 drain 도입

`PgOutboxImmediateWorker.stop()` 의 graceful 동작이 *의도한 설계* 와 *현재 구현* 사이에 갭이 있음.

**현황**:
- `stop()` 가 `running=false` + `Thread::interrupt` + `worker.join(10s)` + `relayExecutor.shutdown()` 까지만 수행
- Worker 가 `channel.take()` 에서 `InterruptedException` 받으면 채널에 남아있는 `OutboxJob` 들을 drain 안 하고 즉시 종료
- 잔여 Job 은 다음 부팅 시 `PgOutboxPollingWorker` 가 RDB SoT 에서 회수 — 메시지 유실 0 은 보장됨

**도입 방향**:
1. `stop()` 안에 best-effort drain 단계 추가 — `pg.outbox.channel.drain-timeout-ms` (default 5000) 안에서 채널이 비워질 때까지 폴링 대기
2. drain timeout 초과 시 기존 경로 (interrupt + join) 로 폴백
3. K8s SIGTERM grace period(보통 30s) 와 정합성 검증

**관련 코드**:
- `pg-service/.../infrastructure/scheduler/PgOutboxImmediateWorker.java:91-106` (stop 메서드)
- `pg-service/.../infrastructure/channel/PgOutboxChannel.java`
- `pg-service/.../infrastructure/scheduler/PgOutboxPollingWorker.java` (RDB 폴백)

#### TC-15 — PG-CONFIRM-LISTENER-SPLIT PHASE2 정밀화

PG-CONFIRM-LISTENER-SPLIT 이 의도적으로 측정 없는 baseline 으로 채택한 값들의 부하 기반 정밀화 + 알려진 한계 해소.

**항목 1 — 워커 VT 풀 / 채널 cap / 좀비 임계 측정 기반 정밀화**:
- 워커 5개 / cap=1024 / PENDING-IN_PROGRESS 좀비 임계 60s 모두 측정 없는 baseline
- T4-B (k6 부하 곡선) 측정 결과로 벤더 latency p95 확인 → 임계 정밀화 (60s ↔ 실제 벤더 timeout × 2)
- cap=1024 가 peak TPS 에서 부족한지 overflow + fallback 빈도 측정
- yml 키 (`pg.inbox.channel.capacity` / `pg.inbox.channel.worker-count` / `pg.scheduler.inbox-polling-worker.*`) 로 즉시 조정 가능 — 코드 변경 없이 운영 배포 가능

**항목 2 — 멀티 인스턴스 worker concurrency 검증 (SKIP LOCKED 멀티 인스턴스)**:
- 현재 구현은 단일 인스턴스 가정. `FOR UPDATE SKIP LOCKED` 가 멀티 인스턴스 환경에서도 중복 처리 0 을 보장하는지 검증
- 검증 환경: 동일 pg-service 2~3 인스턴스 + 같은 `mysql-pg` DB + 동일 Kafka consumer group

**항목 3 — 좀비 폴링 회수 traceparent 이어붙이기 (stored_traceparent RDB 보관 방안)**:
- 현재 `PgInboxPollingWorker` 의 폴링 진입은 OTel 새 root span — 원 Kafka message traceparent 와 끊김
- 방안: `pg_inbox` 에 `stored_traceparent VARCHAR` 컬럼 추가 → listener INSERT 시 Kafka header 의 `traceparent` 값 기록 → 폴링 회수 시 저장된 값으로 새 span 의 parent 를 설정
- 필요 Flyway migration 1건 + `PgInboxPendingService` + `PgInboxPollingWorker` 수정

**관련 코드**:
- `pg-service/.../infrastructure/scheduler/PgInboxImmediateWorker.java`
- `pg-service/.../infrastructure/scheduler/PgInboxPollingWorker.java`
- `pg-service/.../infrastructure/channel/PgInboxChannel.java`
- `pg-service/src/main/resources/application.yml` (inbox 설정 키)

---

## 완료

- ✅ **TC-13** — payment-service EOS 전환 (PR #77, 2026-05-17, 브랜치 #77). PET-1~PET-14 14개 태스크. `payment_event_dedupe` 신설 + `KafkaTransactionManager` 통합 + `StockOutbox` 묶음 16+파일 + `payment_stock_outbox` DROP + product-service `read_committed` + Testcontainers 5 시나리오 GREEN. 후속: TC-13-FOLLOW-1~5.
- ✅ **TC-14** — pg-service vendor 호출 listener thread 분리 (PR #74, 2026-05-09). 상세: `docs/archive/pg-confirm-listener-split/COMPLETION-BRIEFING.md`
- ✅ **TC-16** — PgInboxAmountService dead service 제거 (CLEANUP-BATCH-A CBA-1, 브랜치 #75). main 호출처 0건 확인 후 본체 + PgInboxAmountStorageTest 삭제. CONFIRM-FLOW.md / PAYMENT-FLOW.md dangling reference 정정.
- ✅ **TC-2** — Seed 데이터 분리 (CLEANUP-BATCH-A CBA-2~5, 브랜치 #75). product/user-service `db/migration/` → `db/schema/` + `db/seed/` 물리 분리. docker profile `classpath:db/schema` 만 적용.
- ✅ **TC-5** — Retryable 예외 ControllerAdvice 매핑 보강 (CLEANUP-BATCH-A CBA-6, 브랜치 #75). `ProductServiceRetryableException` / `UserServiceRetryableException` → 503 + `Retry-After: 5` 매핑 추가.
- ✅ **TC-10** — pg-service 도메인 객체 생성자 패턴 통일 (CLEANUP-BATCH-A CBA-8/9, 브랜치 #75). PgInbox / PgOutbox `@Builder(allArgsBuilder/allArgsBuild) + @AllArgsConstructor(PRIVATE)` + factory only 노출. PgOutbox.create/createWithAvailableAt dead id 파라미터 제거.

---

## Plan 작성 시 사용 가이드

- 각 T 항목을 새 토픽으로 승격할 때 `docs/topics/<TOPIC>.md` + `docs/<TOPIC>-PLAN.md` 신규
- 본 TODOS 의 항목은 plan 의 "근거" 절에서 인용 가능
- 토픽 종결 시 본 파일에서 해당 항목 삭제 (또는 archive briefing 으로 이전)

## 관련

- 학습된 함정: `PITFALLS.md`
- 알려진 우려: `CONCERNS.md`
- 직전 봉인 토픽 회고: `docs/archive/{msa-transition,pre-phase-4-hardening,stock-compensation-recovery,pg-confirm-listener-split}/COMPLETION-BRIEFING.md`
