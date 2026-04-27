# Planned Cleanup / Future Work

> 최종 갱신: 2026-04-27 (MSA + PRE-PHASE-4-HARDENING 봉인 후 전면 재작성).
> 이 파일은 현재 활성 작업 범위 밖이지만 향후 처리가 필요한 항목을 추적한다.
> discuss 단계 시작 시 다음 작업을 고를 때 이 파일을 참고한다.

---

## Phase 4 — 다음 토픽 (계획됨, 활성 X)

### T4-A — Toxiproxy 8종 장애 주입 시나리오

- Kafka producer/consumer 지연
- DB 지연 / 연결 끊김
- payment-service / pg-service 프로세스 kill + 재시작
- 보상 트랜잭션 중복 진입 방지 (D12 가드 실증)
- FCG (Final Confirmation Gate) PG timeout
- Redis dedupe / stock cache 다운
- 재고 캐시 발산 시나리오
- DLQ 소진

각 시나리오: `payment_outbox_pending_age_seconds` p95≥10s, 결제·재고 정합성 교차 검증.

### T4-B — k6 시나리오 재설계

- Gateway → payment confirm → 비동기 status 폴링 단일 시나리오
- 경로별 TPS / p95 / p99 / failure rate 메트릭
- ramping-arrival-rate 부하 곡선

### T4-C — 로컬 오토스케일러

- Prometheus 큐 길이 / CPU 임계 기반 payment-service 레플리카 자동 scale
- docker compose scale up/down 자동화
- scale 결정 logging + Grafana dashboard

### T4-D — CircuitBreaker 적용

- `ProductHttpAdapter` / `UserHttpAdapter` 에 Resilience4j CircuitBreaker
- Prometheus 메트릭 (`circuit_breaker_state`, `circuit_breaker_calls_total`)
- 폐쇄/반열림/열림 상태 시각화
- **이 도입과 동시에**: 어댑터의 `try/catch (feign.RetryableException)` 매핑을 Feign **fallbackFactory** 로 마이그레이션 (transport / read-timeout / circuit-open 을 한 곳에서 처리). 현재 어댑터의 임시 try/catch 는 회귀 방어용으로 회복성 마이그레이션 시점에 제거.
- **timeout 정밀 튜닝**: `application.yml` 의 `spring.cloud.openfeign.client.config.default.{connectTimeout: 2000, readTimeout: 5000}` baseline 을 Phase 4 부하 측정 기반 SLO 로 조정.

---

## Phase 4 후속 — 자동 운영 도구 (계획됨)

### TQ-1 — DLQ 처리 정책 + admin tool

- `payment.commands.confirm.dlq`, `payment.events.confirmed.dlq` 가 자동 처리되지 않음
- 별도 admin endpoint 또는 CLI 로 트리아지 + 재발행 가능하도록
- 조건부 자동 재시도 (벤더 5xx 같은 일시적 실패)

### TQ-2 — QUARANTINED-ADMIN-RECOVERY

- `PaymentEventStatus.QUARANTINED` 결제의 수동 복구 인터페이스
- 관리자가 검토 후 DONE / FAILED 로 강제 전이 + audit
- 격리 사유별 (AMOUNT_MISMATCH, CACHE_DOWN, 판단 불가) UI

### TQ-3 — REDIS-CACHE-FAILURE-POLICY

- `redis-stock` 다운 시 어떤 정책으로 가야 하는가? — 현재는 CACHE_DOWN → QUARANTINED + 보상 펜딩
- 또한 redis 데이터 lost 시 부팅 재시드(`scripts/seed-stock.sh`) 외 회복 경로 없음 — payment 진행 중이면 Redis 키 부재로 confirm DECR 음수 가능성
- 운영 시 Redis HA / fallback / AOF 운영 가이드 결정 필요

### TQ-4 — Vendor 동적 라우팅

- 현재 `gatewayType` 은 client 결정. 벤더 장애 시 자동 fallback 미구현
- 헬스 체크 기반 동적 라우팅 정책

### TQ-5 — multi-broker Kafka

- 현재 broker 1대 + replication-factor=1
- HA 환경 검증 필요

### TQ-6 — Cancel / Refund 워크플로우

- `PgGatewayPort.cancel(...)` 인터페이스만 존재
- 운영 cancel 정책 + 부분 환불 + audit trail

---

## Low priority — 코드 청결도

### TC-1 — observability 대시보드 현행화

- Grafana dashboard JSON 들이 옛 메트릭 이름 일부 사용 가능
- Phase 4 부하 테스트 시작 전 inventory + 갱신

### TC-2 — Seed 데이터 분리 (운영 안전성)

- `product/V2__seed_product_stock.sql`, `user/V2__seed_user.sql` 가 운영 배포에도 같이 적용됨
- 옵션: `spring.flyway.locations` 환경별 분리 또는 placeholder 활용
- 현재는 데모 / 스모크 환경에서 동작하므로 우선순위 낮음

### TC-3 — 재고 동기화 정책 (부팅 외 시점)

- 새 재고 모델: redis-stock = payment 의 선차감 캐시, product RDB = SoT
- 현재는 부팅 직후 `scripts/seed-stock.sh` 가 mysql-product → redis-stock 으로 1회 시드. 이후 동기화 X
- 발산 발생 시점: product RDB 가 외부(관리자 / 입고 / 외부 시스템)에서 변경되면 Redis 와 발산
- 후보 방안: (a) admin endpoint `/admin/stock/resync` 로 수동 재시드, (b) product 가 RDB 변경 시 redis pub/sub 으로 cache invalidation, (c) 주기적 재시드 스케줄러 (사용자 결정으로 일단 미도입)
- 우선순위: Phase 4 부하 시나리오에서 발산이 실제로 문제되는 것을 확인한 후 결정

### TC-4 — EXPIRED 만료 스케줄러 정책 명확화

- `PaymentEventStatus.EXPIRED` 정의는 있으나 도메인 매핑은 일부만 활성
- 만료 스케줄러 정책 (몇 시간 후 EXPIRED 전이?) 별도 토픽 정리 필요

### TC-5 — Retryable 예외 ControllerAdvice 매핑 보강

- `ProductServiceRetryableException` / `UserServiceRetryableException` 가 ControllerAdvice 매처 미등록 → 클라이언트엔 503/429 가 500 으로 보임
- pre-existing 이슈, CLIENT-SIDE-LB 회귀 아님
- ErrorDecoder + 어댑터 try/catch 가 명시적으로 throw 하기 시작했으니 정렬 가치 있음
- 처리 시: HTTP 503 / 429 로 정확히 노출 + Retry-After 헤더 포함 검토

### TC-6 — 가상 스레드 명시적 throttle / bulkhead 검토

- 현재 백프레셔는 다운스트림 자원 (Hikari 30, Kafka in-flight 5, Redis Lettuce single connection, scheduler batch-size 50) 으로 자연 형성
- 명시적 `Semaphore` / `RateLimiter` / Resilience4j `Bulkhead` 코드는 0건
- 위험 시나리오: 외부 PG (Toss/NicePay) 호출 시 벤더 측 rate limit 초과 / 다운스트림 다운 시 VT 가 timeout 까지 spawn 누적 → 메모리 압박
- 처리 시점: Phase 4 의 T4-A (Toxiproxy 8종 장애 주입) + T4-B (k6 부하) 측정 결과 기반 — "측정 없이 마법 숫자 박지 않는다" 원칙
- 도입 후보: T4-D 의 Resilience4j 묶음에 `@Bulkhead("productService")` 추가, 또는 외부 PG 호출 어댑터에 명시 Semaphore. 측정값 기반으로 결정

### TC-7 — outbox retry 정책 정렬 (stock 도입 + payment 재검토)

두 outbox 의 retry 정책이 비대칭. 같은 시점에 일관 정책으로 정렬 필요.

**현황**:
- `payment_outbox`: `RetryPolicy` 활용 중 — `RetryPolicyProperties` (env 주입) + maxAttempts=5 + FIXED 5s default. `incrementRetryCount(policy, now)` 호출 + `nextRetryAt` 시각 표현 + 한도 초과 시 종결
- `stock_outbox`: schema 와 `attempt` 필드 / `incrementAttempt()` 메서드 자리는 잡혀 있으나 **호출처 0건** — 실제 retry policy 미연결. 발행 실패 시 다음 cycle 그대로 재시도, counter 미관리, backoff 미적용

**조정 필요 사항**:
1. **stock_outbox 에 retry policy 도입** — `payment_outbox` 미러링. `incrementAttempt()` 활성화 + `nextAvailableAt` 또는 동등 backoff 도입 + 한도 초과 시 DLQ / FAILED 처리
2. **payment_outbox 정책 재검토** — 현재 maxAttempts=5 + FIXED 5s 가 SLO 기준 적절한지 측정 검증. backoff 가 EXPONENTIAL 가 더 적합한 시나리오인지 검토
3. **두 outbox 의 정책 일관성** — 같은 책임 (Kafka publish 실패 회복) 이라 동일 RetryPolicy 클래스 또는 통일된 properties 구조로 정렬

**처리 시점**: Phase 4 의 T4-A (Toxiproxy 장애 주입) + T4-B (k6 부하) 측정 결과 기반. 운영 SLO 데이터 없이 마법 숫자 정렬 금지.

**관련 코드**:
- `payment-service/.../domain/PaymentOutbox.java` — retryCount + incrementRetryCount
- `payment-service/.../domain/StockOutbox.java:93` — incrementAttempt() (호출 0)
- `payment-service/.../application/config/RetryPolicyProperties.java`
- `payment-service/.../domain/RetryPolicy.java`

### TC-8 — 시간 추상화 통합 (Clock / Instant / LocalDateTime 혼용 정리)

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

**처리 시점**: 별개의 정리 토픽으로 승격 가능 또는 다른 cleanup 묶음. 운영 영향 없는 코드 청결도 작업이라 priority 낮음. Phase 4 진입 후 짬내서 처리하거나 별도 토픽.

**관련 코드**:
- `payment-service/.../core/common/service/port/LocalDateTimeProvider.java`
- `payment-service/.../core/common/infrastructure/SystemLocalDateTimeProvider.java`
- `pg-service/.../infrastructure/config/PgServiceConfig.java:25` (Clock Bean)
- `pg-service/.../domain/PgInbox.java:40,107,139,174,212` (Instant.now() 직접 호출)
- `pg-service/.../domain/PgOutbox.java:45,51` (동)
- `pg-service/.../infrastructure/gateway/toss/TossPaymentGatewayStrategy.java:244` (LocalDateTime.now() 잔존)

---

## Plan 작성 시 사용 가이드

- 각 T 항목을 새 토픽으로 승격할 때 `docs/topics/<TOPIC>.md` + `docs/<TOPIC>-PLAN.md` 신규
- 본 TODOS 의 항목은 plan 의 "근거" 절에서 인용 가능
- 토픽 종결 시 본 파일에서 해당 항목 삭제 (또는 archive briefing 으로 이전)

## 관련

- 학습된 함정: `PITFALLS.md`
- 알려진 우려: `CONCERNS.md`
- 직전 봉인 토픽 회고: `docs/archive/{msa-transition,pre-phase-4-hardening}/COMPLETION-BRIEFING.md`
