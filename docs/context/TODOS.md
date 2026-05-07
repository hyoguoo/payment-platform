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
- **pg-service 외부 PG timeout 정밀 튜닝**: `pg.http.{connect-timeout-millis: 3000, read-timeout-millis: 10000}` 은 현재 측정 없는 baseline. T4-B/T4-A 부하 + 장애 주입 측정 결과로 SLO 기반 값으로 교체. `max.poll.records` 기본값(500) 검증도 병행.

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

### TC-10 — pg-service 도메인 객체 생성자 패턴 통일 (PgInbox / PgOutbox)

**현황**:
- payment-service 의 `PaymentOutbox` / `StockOutbox` 등은 `@Builder(builderMethodName = "allArgsBuilder", buildMethodName = "allArgsBuild")` + `@AllArgsConstructor(PRIVATE)` + factory method (`createPending` 등) 안에서 builder 사용 — 일관 패턴
- pg-service 의 `PgInbox` / `PgOutbox` 는 명시 `private` 생성자 + static factory method 3~4개로 별개 패턴

**문제점**:
1. **dead parameter** — `PgOutbox.create(Long id, ...)` 의 `id` 매개변수가 호출처 10곳 모두 `null` 명시. `PgOutbox.createWithAvailableAt(Long id, ...)` 도 동일
2. **일관성 부재** — payment-service 도메인과 다른 패턴 → 다른 서비스 코드 보다가 컨벤션 차이 인지 부담
3. **시나리오 분기 명시 가치 vs 호출 길이** — factory 3개 (`create` / `createWithAvailableAt` / `of`) 가 의도 명시는 좋으나 `null` 박는 게 어색

**조정 방향**:
1. **PgOutbox** — payment-service 패턴 따라 `@Builder` + `@AllArgsConstructor(PRIVATE)` + factory method 안에서 builder 사용. `id` 는 builder 에서 생략 가능
2. **PgInbox** — 동일 패턴 적용
3. factory 의 시나리오 의도 (즉시 발행 / 지연 발행 / DB 복원) 는 보존 — builder 호출을 factory 안에 캡슐화

**처리 시점**: cleanup / 청결도 작업이라 priority 낮음. 별도 정리 토픽 또는 다른 cleanup 묶음 (TC-8 시간 추상화 통합과 같이) 으로 처리 가능.

**관련 코드**:
- `pg-service/.../domain/PgInbox.java`
- `pg-service/.../domain/PgOutbox.java:44,49,54` (3개 factory)
- `payment-service/.../domain/PaymentOutbox.java:13` (참조 패턴)
- `payment-service/.../domain/StockOutbox.java:21` (참조 패턴)
- 호출처 10곳 (PgConfirmService / PgVendorCallService / PgDlqService / PgFinalConfirmationGate / DuplicateApprovalHandler)

---

### TC-9 — FakePgGatewayAdapter 의 vendor 멱등성 시뮬 추가

`FakePgGatewayAdapter` 가 같은 paymentKey 두 번 호출 시 `PgGatewayDuplicateHandledException` 을 던지지 않아 production vendor 의 멱등성 응답을 시뮬레이션하지 못함.

**현황**:
- IN_PROGRESS self-loop retry path (commit `e524b514`) 에서 vendor 재호출이 가능해짐
- production: Toss/NicePay 가 `paymentKey + orderId` 단위 멱등성 보장 → 두 번째 호출은 "이미 처리됨" 응답 → `PgGatewayDuplicateHandledException` → `DuplicateApprovalHandler` 가 흡수
- Fake: 두 번째 호출 시 도메인 가드 예외 (`PgInbox.markApproved: status must be IN_PROGRESS but was APPROVED`) 만 발생 → production 동작과 다름

**도입 시**:
- `FakePgGatewayAdapter` 에 "같은 paymentKey 가 이미 SUCCESS 로 처리됐으면 다음 호출 시 duplicate 예외 던짐" 모드 추가
- 같은 paymentKey 의 처리 결과를 in-memory map 에 보관 → 두 번째 호출 시 `PgGatewayDuplicateHandledException` throw
- 통합 테스트 (특히 retry self-loop 시나리오) 가 진짜 production 동작과 정합

**처리 시점**: Phase 4 의 T4-A (Toxiproxy 8종 장애 주입) 시 retry 시나리오 검증할 때 이 시뮬 정합성 필요. 함께 처리.

**관련 코드**:
- `pg-service/.../infrastructure/gateway/fake/FakePgGatewayStrategy.java`
- `pg-service/.../exception/PgGatewayDuplicateHandledException.java`
- `pg-service/.../application/service/DuplicateApprovalHandler.java`

### TC-12 — pg-service Worker.stop 채널 drain 도입

`PgOutboxImmediateWorker.stop()` 의 graceful 동작이 *의도한 설계* 와 *현재 구현* 사이에 갭이 있다.

**현황**:
- `stop()` 가 `running=false` + `Thread::interrupt` + `worker.join(10s)` + `relayExecutor.shutdown()` 까지만 수행
- Worker 가 `channel.take()` 에서 `InterruptedException` 받으면 채널에 남아있는 `OutboxJob` 들을 drain 안 하고 즉시 종료
- 잔여 Job 은 다음 부팅 시 `PgOutboxPollingWorker` 가 RDB SoT 에서 `availableAt <= NOW` 조건으로 회수 — 메시지 유실 0 은 보장됨

**위키 표현과의 갭**:
- `outbox-channel-dispatch.md` 의 비교 표 / 본문은 "SmartLifecycle.stop 이 채널 drain 후 Worker 종료" 라고 적힘 (의도한 설계)
- 코드는 "interrupt + RDB 폴링 회수" 만 — 위키와 코드 사이에 정밀 정합성 갭 존재

**도입 방향**:
1. `stop()` 안에 best-effort drain 단계 추가 — `running=false` 설정 후, `pg.outbox.channel.drain-timeout-ms` (default 5000) 안에서 채널이 비워질 때까지 폴링 대기
2. drain timeout 초과 시 기존 경로 (interrupt + join) 로 폴백 — 잔여는 RDB SoT 폴링이 다음 부팅에 회수
3. K8s SIGTERM grace period(보통 30s) 와 정합성 검증

**제약 / 트레이드오프**:
- drain timeout 너무 크면 K8s SIGTERM grace period 내에 못 끝나 SIGKILL 위험 — inflight publish 까지 짤림. 기본 5s 정도가 안전선
- drain 의 이득은 *재기동 시 폴링 1회 (≤ 2s) 면제* 정도로 작음. 메시지 유실 0 은 RDB SoT 로 이미 보장됨
- broker 느림 / 잔여 Job 많음 시나리오에선 drain timeout 만 다 쓰고 결국 폴백 — 그 케이스에선 도입 가치 미미

**처리 시점**: Phase 4 의 T4-A (Toxiproxy 장애 주입) 시 shutdown 시나리오 검증과 함께. 운영 SLO 데이터로 drain timeout 결정.

**관련 코드**:
- `pg-service/.../infrastructure/scheduler/PgOutboxImmediateWorker.java:91-106` (stop 메서드)
- `pg-service/.../infrastructure/channel/PgOutboxChannel.java`
- `pg-service/.../infrastructure/scheduler/PgOutboxPollingWorker.java` (RDB 폴백)

### TC-13 — payment-service confirmed consumer EOS 전환 (위키 sync)

위키는 EOS 패턴으로 기술돼 있으나, 코드는 two-phase lease + `stock_outbox` + AFTER_COMMIT relay 그대로 — 위키-코드 어긋남 상태. 면접 / 문서 자료 목적으로 위키만 선반영했고, 코드 적용은 후속 작업.

**전제**: DB 멱등성 보장 (`payment_event_dedupe(event_uuid)` UNIQUE INSERT IGNORE) + 비즈니스 멱등 (`handleApproved` 에 `isTerminal` 가드 추가). 이 전제 위에서 EOS 는 정합성 측면 모든 시나리오를 보장. 가용성 결은 별개 — Kafka 클러스터 죽으면 처리 자체가 멈춤.

**변경 폭 (코드 / 인프라)**:
- 컨슈머: `ConfirmedEventConsumer` → `KafkaTransactionManager` 통합. 명시 ack 없음
- 유스케이스: `PaymentConfirmResultUseCase` → `markWithLease/extendLease/remove` 제거 + `INSERT IGNORE payment_event_dedupe` + 직접 `producer.send(stock-committed)`. `try/extend/remove/DLQ` 4-way 분기 소멸. `handleApproved` 에 `isTerminal` 가드 추가
- 포트: `EventDedupeStore` → `markSeen`/`recordIfAbsent` 단일 메서드 (RDB JDBC 어댑터)
- 어댑터: `EventDedupeStoreRedisAdapter` 제거, `JdbcEventDedupeStore` 신규
- DLQ: `PaymentConfirmDlqPublisher` 제거 → `DefaultErrorHandler` + `FixedBackOff(N회)` + `DeadLetterPublishingRecoverer`
- outbox: `StockOutbox` 도메인 + `StockOutboxRepository` + `StockOutboxFactory` + `StockOutboxReadyEvent` 리스너 + `StockOutboxRelayService` + `StockOutboxKafkaPublisher` + `StockOutboxImmediateEventHandler` + `StockOutboxWorker` 모두 제거
- 테이블: `payment_stock_outbox` drop, `payment_event_dedupe` 신규 (Flyway migration)
- 설정: `KafkaTransactionManager` 빈 + `transactional.id` (인스턴스별 결정적 id) + producer `enable.idempotence=true`

**downstream 영향**:
- `product-service` `StockCommitConsumer` consumer config `isolation.level=read_committed` 전환. 이 설정 누락 시 abort batch 노출 → 정합 깨짐

**운영 invariant**:
- `transactional.id` — 인스턴스별 결정적 id (재시작 시 동일). pod 이름 / instance UUID 기반. fence 로 좀비 producer 차단
- `max.poll.interval.ms` — rebalance 윈도우. fence 와 함께 split-brain 방지
- broker 가용성 — Kafka tx coordinator 죽으면 처리 정지. outbox 와 비교 시 가용성 결이 약하다는 점 인지

**테스트**:
- 단위 테스트: `PaymentConfirmResultUseCaseTwoPhaseLeaseTest` 삭제 → `…EosTest` 재작성. `FakeEventDedupeStore` 단순화 또는 제거
- EOS 동작 자체는 Testcontainers Kafka tx 통합 테스트 1 개로 검증 (Kafka tx coordinator 가 본질이라 Fake 로 검증 불가)

**현재 어긋남 인벤토리** (위키 + README 는 EOS, 코드는 lease/outbox):

코드 측:
- `payment-service/.../infrastructure/messaging/consumer/ConfirmedEventConsumer.java`
- `payment-service/.../application/usecase/PaymentConfirmResultUseCase.java`
- `payment-service/.../application/port/out/EventDedupeStore.java`
- `payment-service/.../infrastructure/dedupe/EventDedupeStoreRedisAdapter.java`
- `payment-service/.../domain/StockOutbox.java` 외 stock outbox 묶음 6+ 클래스
- `payment-service/.../infrastructure/messaging/.../PaymentConfirmDlqPublisher*`
- `product-service/.../infrastructure/messaging/consumer/StockCommitConsumer` consumer config

문서 측 sync 완료 (위키 진실원 기준):
- `README.md` — 멱등 소비 표 (`payment` 행 → `RDB payment_event_dedupe` + `Kafka EOS + RDB 멱등 INSERT`), Outbox 모델 표 (stock_outbox 행 제거 + "Kafka EOS 로 발행" 한 줄), 토픽 카탈로그 (`confirmed.dlq` 발행자 → `DefaultErrorHandler retry 한도 초과`), "Outbox 3 모델" → "Outbox 모델", 진행 중 notice 에 "README/위키 = 설계 의도 기준, 코드와 일부 정합 안 맞을 수 있음" 명시

**처리 시점**: 면접 / 위키 / README 문서가 EOS 톤으로 봉인된 상태이므로 코드 sync 우선순위는 높지 않으나, 위키-코드 갭이 누적되면 추후 해석 비용. Phase 4 부하 측정 후 가용성 결 (Kafka 클러스터 의존도) 판단을 거친 뒤 결정 권장.

**관련 위키**: `message-delivery-and-dedupe.md` (메인) / `outbox-pattern.md` (stock_outbox 빠진 상태) / `tx-scope.md` / `event-driven-choreography.md` / `architecture.md` / `Home.md` / `msa-transition.md` / `trace-propagation.md`

### TC-14 — pg-service vendor 호출 listener thread 분리 검토

PG 벤더 HTTP 호출이 Kafka listener thread 안에서 이뤄지고 있어 (TX2 = `PgVendorCallService.callVendor` `@Transactional`), 벤더 latency 가 인바운드 throughput 에 직접 영향.

**검토 안 — Inbox 를 작업 큐로 겸용**:
- listener 는 `INSERT pg_inbox status='PENDING' + ack` 까지만 (TX 1개)
- 별도 워커 VT 가 채널 + 폴백 폴링으로 PENDING row 를 가져가 처리:
  - TX_A: `PENDING → IN_PROGRESS` CAS UPDATE, 짧게 commit (lock 즉시 해제)
  - 벤더 HTTP 호출 (DB 자유 상태)
  - TX_B: `IN_PROGRESS → APPROVED + Outbox INSERT + publishEvent`
- 좀비 폴링 — `WHERE status='IN_PROGRESS' AND updated_at < now() - 60s` 로 워커 크래시 회수
- 벤더 idempotency-key (orderId) + `DuplicateApprovalHandler` 가 좀비 회수 후 재호출 시 중복 흡수

**효과**:
- listener 책임이 매우 가벼워짐 → 벤더 latency 가 인바운드 처리량에 영향 없음
- 동시 처리 수는 워커 VT 풀 크기 + DB connection pool 로 명시 통제

**현재 갭 (위키 + README 는 분리 안, 코드는 listener thread 안에서 벤더 호출)**:

문서 측 (분리 안 기준):
- `pg-confirm-flow.md` — **메인 흐름이 분리 안 기준** 으로 작성됨 (listener / 워커 VT / 릴레이 워커 3단). 페이지 상단에 "도메인 설계 의도 기준, 현재 코드는 일부 단계가 합쳐져 있음" notice
- `README.md` — 해결 과제 표에 "PG 결제 확인 흐름 분리" 행 추가 (listener / 워커 VT / 릴레이 워커 3단)

코드 측 (현재 구조):
- `PgConfirmService.handleNone` 안에서 TX1 (`transitNoneToInProgress`) → TX2 (`callVendor` 안에서 벤더 HTTP + Outbox INSERT + Inbox 종결) 가 같은 listener thread 위에서 순차 진행
- 분리 안으로 가려면 Inbox 상태에 `PENDING` 추가, listener 는 PENDING INSERT + ack 까지만, 별도 워커 VT 가 PENDING 을 take 해 TX_A → 벤더 → TX_B 진행

**같이 갱신 필요한 위키 (분리 안 적용 시)**:
- `outbox-channel-dispatch.md` — 현재 발행 측 채널 (워커 → 릴레이 워커) 1개 기준. 분리 안에서는 작업 큐 채널 + 발행 큐 채널 2개가 되므로 본문 갱신 필요. 현재 페이지에서 `pg-confirm-flow.md` 와 겹치는 메시지 생명주기 / 큐 둔 이유 / 토폴로지 / 장애 폴백 시나리오 / 단계별 상세 흐름 섹션은 이미 정리 (책임 분담)

**처리 시점**: 별도 토픽으로 승격 검토. Phase 4 부하 측정 (T4-A / T4-B) 결과 listener thread 가 벤더 latency 에 묶이는 것이 throughput 병목으로 확인되면 우선순위 상승.

**관련 위키**: `pg-confirm-flow.md` (메인 — 분리 안 기준) / `outbox-channel-dispatch.md` (분리 안 적용 시 채널 두 개로 본문 갱신 필요)

**관련 코드**:
- `pg-service/.../application/service/PgConfirmService.java` (오케스트레이터)
- `pg-service/.../application/service/PgVendorCallService.java` (현재 단일 TX2)
- `pg-service/.../infrastructure/messaging/consumer/PaymentConfirmConsumer.java` (listener 진입점)

---

### TC-11 — product / pg dedupe 테이블 cleanup 스케줄러 부재

장기 운영 시 만료 row 누적으로 쿼리 성능 저하 가능.

**현황**:
- product-service `stock_commit_dedupe` — 만료 row 자동 cleanup 스케줄러 없음
- pg-service `pg_inbox` — 동일
- payment-service 의 Redis dedupe (`EventDedupeStoreRedisAdapter`) 는 TTL 자동 expire — 문제 없음
- ARCHITECTURE.md 의 dedupe 결정 사유 섹션에 한 줄 메모만 존재

**문제점**:
- 시간이 지날수록 테이블 크기 무한 증가
- 인덱스 / 쿼리 성능 저하 (FOR UPDATE 락 길이 / SELECT 풀스캔 위험)
- 운영 환경 모니터링 / 운영 가이드 부재

**도입 후보**:
- (a) `@Scheduled` cleanup 워커 — 주기적 `DELETE WHERE created_at < NOW() - INTERVAL X` (X = Kafka retention + 버퍼 = 8일 정도)
- (b) admin endpoint `/admin/dedupe/cleanup` 수동 트리거
- (c) 별도 인프라 (event-time partitioning 등) — 학습 단계엔 over-engineering

**처리 시점**: Phase 4 부하 측정 시 테이블 누적 영향 측정 → 발현 시 도입 결정. 운영 SLO 데이터 없이 TTL 결정 금지.

**관련 코드**:
- `product-service/.../infrastructure/idempotency/JdbcEventDedupeStore.java`
- `pg-service/.../infrastructure/repository/PgInboxRepositoryImpl.java`
- `docs/context/ARCHITECTURE.md` (Phase 4 후속 검토 메모)

---

## Plan 작성 시 사용 가이드

- 각 T 항목을 새 토픽으로 승격할 때 `docs/topics/<TOPIC>.md` + `docs/<TOPIC>-PLAN.md` 신규
- 본 TODOS 의 항목은 plan 의 "근거" 절에서 인용 가능
- 토픽 종결 시 본 파일에서 해당 항목 삭제 (또는 archive briefing 으로 이전)

## 관련

- 학습된 함정: `PITFALLS.md`
- 알려진 우려: `CONCERNS.md`
- 직전 봉인 토픽 회고: `docs/archive/{msa-transition,pre-phase-4-hardening}/COMPLETION-BRIEFING.md`
