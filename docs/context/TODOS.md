# Planned Cleanup / Future Work

> 최종 갱신: 2026-06-14 (CLEANUP-BATCH-D + ship 후 stale 대청소 — [FLYWAY-USER-SEED-GAP]·[CLEANUP-FAILURE-COUNTER]·TC-1·커버리지 게이트 해소 반영, TC-13-FOLLOW-3/4 부분해소(대시보드 O·알람 rule X) 정정).
> 분류 룰: **현재 과업** = 측정 / Toxiproxy / 멀티 인스턴스 환경 의존 없는 작업. **Phase 5** = 부하 측정 결과 또는 인프라 환경 필요.
> discuss 단계 시작 시 다음 작업을 고를 때 이 파일을 참고한다.

---

## 토픽 묶음 계획 (PR 단위)

현재 과업 7개를 3 PR 로 진행. 작은 청소 → 도메인 결정 → 큰 정합성 순서.

| PR | 묶음명 | 토픽 | 크기 | 성격 | 상태 |
|---|---|---|---|---|---|
| **A** | 코드 청소 4건 | TC-16, TC-10, TC-2, TC-5 | ~2일 | 도메인 결정 없음, 영역 분리 | ✅ 완료 (브랜치 #75, PR 생성 예정) |
| **B** | 도메인 결정 묶음 | TC-4, TC-8 | ~3~4일 | EXPIRED 정책 + 시간 추상화 표준 결정 동반 | ✅ 완료 (TIME-MODEL-AND-EXPIRY, 이슈/브랜치 #83) |
| **C** | EOS 전환 (위키 정합) | TC-13 | ~1주+ | 가용성 결 트레이드오프 결정 동반 | ✅ 완료 (브랜치 #77, PR #77) |

### 묶음 근거

- **PR A** — 모두 작은 청소. 도메인 결정 없고 영역이 분리됨 (pg-service 도메인 / Flyway / ControllerAdvice / Lombok 패턴). plan / 리뷰 부담 적음.
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

#### ~~TC-13-FOLLOW-2 — `payment_event_dedupe` TTL 정리 스케줄러 (TC-11 통합)~~ ✅ 완료 (EOS-FOLLOWUP-CLEANUP, 2026-05-29)

`DedupeCleanupWorker` (`@Scheduled`) 가 `payment_event_dedupe` 의 `expires_at < now` 만료 행을 `deleteExpired(Instant, int)` 로 일괄 DELETE. product `stock_commit_dedupe` 청소(TC-11)도 동시 처리. 단, product 측 `SchedulerConfig` 게이트는 구현됐으나 `application-docker.yml` `scheduler.enabled` 플래그 누락으로 운영 미작동이었음 → CLEANUP-BATCH-D Task 3 에서 플래그 추가로 정상화. 상세는 ## 완료 섹션.

#### TC-13-FOLLOW-3 — Kafka tx coordinator 가용성 모니터링 (대시보드 ✅ / 알람 rule 후속)

- **문제**: EOS 전환 이후 Kafka tx coordinator 의존 (L1). coordinator 장애 시 처리 멈춤 조기 탐지 수단.
- **대시보드 해소 (OBSERVABILITY-COMPLETION, 2026-06-11)**: `business-dashboard.json` 에 코디네이터 / `kafka_producer_txn_*` 패널 추가로 가시화 확보.
- **잔여**: Prometheus alerting rule 인프라 자체가 미구축(`prometheus.yml` `rule_files`/`alerting` 미설정) — 임계 알람 자동화는 후속.

#### TC-13-FOLLOW-4 — D7 가드 분기 모니터링 (메트릭·대시보드 ✅ / 알람 SLO 후속)

- **문제**: `canApplyConfirmResult()` 가 false 로 noop 한 케이스 (QUARANTINED 늦은 APPROVED 등) 가 운영 시 얼마나 발생하는지 모니터링 수단.
- **메트릭·대시보드 해소 (OBSERVABILITY-COMPLETION, 2026-06-11)**: `payment_confirm_guard_skip_total{status}` 카운터(eager 6종 등록, [GUARD-SKIP-EAGER-REGISTER]) + `business-dashboard.json` guard_skip 패널.
- **잔여**: alerting rule 미구축이라 SLO 자동 알람은 후속(TC-13-FOLLOW-3 과 동일 alerting 인프라 의존).

#### TC-13-FOLLOW-6 — `@Transactional` qualifier 명시 ✅ 완료 / ChainedKafkaTransactionManager 검토 (미채택) (RD1-2)

- **문제**: `PaymentConfirmResultUseCase.handle` 의 `@Transactional(timeout=5)` 가 qualifier 미명시로 `@Primary JpaTransactionManager` 를 선택. `KafkaTransactionManager(EOS)` 와 별개 TM 으로 동작해 crash 시 at-least-once 재배달이 발생 가능.
- **정합 SSOT**: "중복 시 발행 항상 진행 (위키 line 141)" + product-service dedupe 조합 (CONCERNS.md L-1, CONFIRM-FLOW.md §5).
- **완료 부분 (EOS-FOLLOWUP-CLEANUP, 2026-05-29)**: (a) `@Transactional(transactionManager = "transactionManager", timeout = 5)` qualifier 명시 — 다중 TM 환경에서 `@Primary JpaTransactionManager` 명시 고정. best-effort 1PC 한계 + TM 분리 원칙 Javadoc 추가. `KafkaConsumerConfig` deprecated `setTransactionManager` → `setKafkaAwareTransactionManager` 교체.
- **미채택 (잔여)**: (b) `ChainedKafkaTransactionManager` 도입 — JPA TM 과 Kafka TM 체인으로 원자성 강화. 운영 환경에서 at-least-once 허용 불가 수준의 중복 발생 시 재검토.

#### ~~TC-13-FOLLOW-5 — D7 `isCompensatableByFailureHandler` 시맨틱 SSOT 정리 (DM2-2 후속)~~ ✅ 완료 (EOS-FOLLOWUP-CLEANUP, 2026-05-29)

겸용 `isCompensatableByFailureHandler()` 를 `canApplyConfirmResult()` (confirm 결과 적용 진입 가드) + `canCompensateStock()` (보상 가드) 두 메서드로 분리. 두 메서드 모두 READY/IN_PROGRESS/RETRYING 만 true, 종결/QUARANTINED/EXPIRED 동조 false. 기존 메서드는 코드베이스에서 완전 제거 (grep 0건). `PaymentEventStatusSplitMethodTest` + `PaymentEventStatusCrossInvariantTest` 로 회귀 가드. 상세는 ## 완료 섹션.

---

### B. 코드 청결도 (측정 무관)

#### ~~TC-4 — EXPIRED 만료 스케줄러 정책 명확화~~ ✅ 완료 (TIME-MODEL-AND-EXPIRY, 2026-06-03)

만료 정책을 명문화. "READY 만 직접 만료(`expire()` READY 가드) + IN_PROGRESS 정체분은 정합 스캐너(PaymentReconciler)가 READY 복원 후 만료"라는 2단 연쇄를 의도된 정책으로 확정. 만료 임계 30분을 `payment.expiration.ready-timeout-minutes`(기본 30)로 외부화, 스케줄러 키를 `scheduler.payment-expiration.*`로 정정(`payment-status-sync.*` fallback 체인, 운영 무중단). 상세는 ## 완료.

#### ~~TC-8 — 시간 추상화 통합 (Clock / Instant / LocalDateTime 혼용 정리)~~ ✅ 완료 (TIME-MODEL-AND-EXPIRY, 2026-06-03)

표준 (a) `Clock` 빈 + `Instant` 채택. payment 자체 포트 `LocalDateTimeProvider`/`SystemLocalDateTimeProvider` 폐기, 4서비스 `Clock` 통일. 도메인은 `Instant` 인자 주입(now() 직접 호출 금지). UTC 저장 일관(hibernate.jdbc.time_zone=UTC + raw-JDBC connectionTimeZone=UTC + 명시 UTC Calendar). 벤더 승인 시각 `.toInstant()` 정규화. payment 도메인(PaymentEvent/PaymentOutbox) Instant 통일. 상세는 ## 완료 + PITFALLS §6/§13.

### C. CLEANUP-BATCH-A 후속 등재

#### ~~[NET-RETRY] — Feign ErrorDecoder 5xx 분기~~ ✅ 완료 (CLEANUP-BATCH-B, 2026-05-31)

`ProductFeignConfig`/`UserFeignConfig` ErrorDecoder 에 502/504 → `*ServiceRetryableException` 승격(503 + Retry-After:5), 500 및 그 외 5xx 는 `IllegalStateException` 유지, 429/503 단일 예외 유지(예외 증식 최소화, 구분은 로그 status). cross-service 호출이 GET 단건 조회 전용이라 비멱등 재시도 위험 없음. PR #81.

#### ~~[FLYWAY-USER-SEED-GAP] — user-service Testcontainers 검증 부재~~ ✅ 완료 (CI-PIPELINE-REDESIGN, 2026-06-08)

user-service `FlywayDockerProfileTest`(product 동형 — docker profile seed 차단 회귀 가드)가 추가돼 `spring.flyway.locations: classpath:db/schema` override 가 무방어 상태 해소. `UserQueryUseCaseTest` 와 함께 user-service 가 통합테스트 보유 서비스로 전환. (CI fan-out 재설계 D8)

### D. EOS-FOLLOWUP-CLEANUP 후속 등재

#### ~~[PRODUCT-TIME-ABSTRACTION] — product-service 시간 추상화 부재~~ ✅ 완료 (TIME-MODEL-AND-EXPIRY, 2026-06-03)

product-service 에 `Clock.systemUTC()` 빈(`infrastructure/config/ClockConfig`) 도입. `DedupeCleanupWorker`/`StockCommitConsumer` 의 `Instant.now()` → `clock.instant()` 전환(grep 0건). `JdbcEventDedupeStore` raw-JDBC UTC 규약(connectionTimeZone=UTC default/docker + 명시 UTC Calendar) 적용으로 `NOW()` vs 앱 `Instant` split-brain 해소. 상세는 ## 완료.

#### ~~[TIME-PRODUCT-NOW-UNIFY] — product dedupe `NOW()` → 앱 주입 `Instant` 통일~~ ✅ 완료 (TIME-MODEL-FOLLOWUP, 2026-06-07)

`JdbcEventDedupeStore.recordIfAbsent` 의 만료행 삭제 SQL `expires_at < NOW()` 를 호출자 주입 `Instant`(`expires_at < ?`)로 통일해 DB 시계 의존 제거. 포트 `recordIfAbsent` 에 `now` 인자 추가, 진입점(`StockCommitConsumer`)이 `now` 1회 산출 후 전 경로 동일 전달. 실사용 0건 `existsValid`·`SQL_EXISTS_VALID`·미사용 `Clock` 필드 전건 제거. `connectionTimeZone=UTC` 는 raw-JDBC 바인딩 backstop 으로 존치. 상세: `docs/archive/time-model-followup/COMPLETION-BRIEFING.md`.

#### ~~[TZ-UTC-BACKSTOP] — 컨테이너/JVM TZ=UTC 명시~~ ✅ 완료 (TIME-MODEL-FOLLOWUP, 2026-06-07)

6개 서비스 TZ backstop 3겹 적용 — Dockerfile `ENV TZ=UTC` + `ENTRYPOINT` JVM `-Duser.timezone=UTC` + compose `environment.TZ=UTC`(eureka 는 `docker-compose.infra.yml`). 동일값 멱등 defense-in-depth, auditing UTC化와 별개 안전망.

#### ~~[BASEENTITY-AUDIT-SOURCE] — BaseEntity auditing 소스 일원화~~ ✅ 완료 (TIME-MODEL-FOLLOWUP, 2026-06-07)

payment `BaseEntity` audit 컬럼(`created_at/updated_at/deleted_at`) `LocalDateTime` → `Instant` + Flyway V4 `DATETIME` → `DATETIME(6)` 승급 + `clockDateTimeProvider` `Instant` 반환. 엔티티 매핑 경계 수동 `.toInstant(UTC)` 변환 제거, `createdAt updatable=false` 보존. (pg/product/user 는 auditing superclass 부재 — "다른 서비스 일원화" 대상 없음 확인.)

#### ~~[SCHEDULER-ENABLED-GATE] — dedupe cleanup worker 활성화 정책 문서화~~ ✅ 완료 (CLEANUP-BATCH-D, 2026-06-14)

payment 는 `application-docker.yml` / `application-benchmark.yml` 에 `scheduler.enabled: true` 존재(기존), product 는 `application-docker.yml` 에 `scheduler.enabled: true` 추가(CLEANUP-BATCH-D Task 3). 활성화 정책(게이트 = `SchedulerConfig` / 서비스별 매트릭스)은 `STACK.md` "스케줄러 활성화 정책" 절에 문서화.

#### ~~[CLEANUP-FAILURE-COUNTER] — dedupe cleanup 실패 메트릭 부재~~ ✅ 완료 (OBSERVABILITY-COMPLETION, 2026-06-11)

payment `DedupeCleanupWorker` `payment_event_dedupe.cleanup_failed_total` + product `DedupeCleanupWorker` `stock_commit_dedupe.cleanup_failed_total` Micrometer Counter 추가 — `deleteExpired` 실패 시 ERROR 로그 + 카운터 increment 로 실패 누적 가시화. (OBSERVABILITY-COMPLETION D14)

#### ~~[GUARD-SKIP-EAGER-REGISTER]~~ ✅ 완료 (OBSERVABILITY-COMPLETION verify, 2026-06-11)

`PaymentConfirmGuardSkipMetrics` 생성자에서 `canApplyConfirmResult()==false` 6종(DONE/FAILED/CANCELED/PARTIAL_CANCELED/EXPIRED/QUARANTINED) 카운터를 eager 등록으로 전환. 기동 즉시 0 시리즈 노출 보장. verify 라이브 보정(Fix-3) 커밋에 포함.

#### ~~[SPOTBUGS-TEST-DEBT] — payment-service `spotbugsTest` 사전 부채~~ ✅ 완료 (CLEANUP-BATCH-B, 2026-05-31)

NP_NULL 4건 + EI_EXPOSE_REP2 1건을 **전부 코드 정정으로 해소(억제 0건)**. NP_NULL 은 명시적 `if (x == null) throw` 가드(`Objects.requireNonNull` 은 SpotBugs 6.0.9 가 null-억제자로 인식 못 함), EI_EXPOSE_REP2 는 `FakeMessagePublisher` 저장 타입을 `Throwable` → `Supplier<? extends Throwable>` 전환. build 게이트 회복. PR #81.

#### [CLEANUP-BATCH-B 후속] — 커버리지 게이트 / 빌드 스크립트 잔여 (CLEANUP-BATCH-B, 2026-05-31)

- ~~**user-service / product-service 커버리지 게이트 무실효(user 0.0 / product 0.40)**~~ ✅ 해소 (CI-PIPELINE-REDESIGN 외, 실측 2026-06-14) — 현재 `jacoco.lineCoverageMinimum` user 0.97 / product 0.97 로 상향돼 실효 게이트. user 는 `UserQueryUseCaseTest` 보강으로 측정 대상 확보. gateway·eureka 만 0.0(측정 대상 클래스 0 — 라우팅/디스커버리 전용이라 불가피).
- ~~**deprecated Groovy space-assignment 문법**~~ ✅ 해소 (CLEANUP-BATCH-D Task 2, 2026-06-14) — 루트 + 4서비스 `build.gradle` 5곳 `events "..."` → `events = ['...']` 리스트 할당 전환 완료.
- **infra 커버리지 집계 제외** — `**/infrastructure/**` 제외로 EOS `ConfirmedEventConsumer`/dedupe 어댑터가 커버리지 집계에서 빠짐(측정 대상 정책 유지, G1). `PaymentEosIntegrationTest` 가 실행되어 회귀 가드는 유효하므로 도메인 위험 아님. 측정 대상 확대는 별도 토픽 여지.
- ~~**GitHub Actions Node.js 20 deprecated**~~ ✅ 이미 해소 — CI 액션이 `actions/checkout@v6` 등 Node.js 24 지원 버전으로 이미 업그레이드 완료됨(CLEANUP-BATCH-D 착수 전 기준). 잔류 stale 항목.

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

#### ~~TC-1 — observability 대시보드 현행화~~ ✅ 완료 (OBSERVABILITY-COMPLETION, 2026-06-11)

- 옛 `payment-dashboard.json` 폐기 + `business-dashboard.json` / `system-dashboard.json` 2분할 신설, 메트릭 이름 현행 코드 정합. (CONCERNS C-9 동반 해소)

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

#### TC-7 — payment_outbox retry 정책 재검토

`stock_outbox` 는 PAYMENT-EOS-TRANSITION 봉인으로 폐기됨 (PR #77). `payment_outbox` retry 정책만 측정 검증 대상으로 남음.

**현황**:
- `payment_outbox`: `RetryPolicy` 활용 중 — `RetryPolicyProperties` (env 주입) + maxAttempts=5 + FIXED 5s default. `incrementRetryCount(policy, now)` 호출 + `nextRetryAt` 시각 표현 + 한도 초과 시 종결

**조정 필요 사항**:
1. **payment_outbox 정책 재검토** — 현재 maxAttempts=5 + FIXED 5s 가 SLO 기준 적절한지 측정 검증. backoff 가 EXPONENTIAL 가 더 적합한 시나리오인지 검토 (Phase 5 자물쇠 — k6 측정 후)

**관련 코드**:
- `payment-service/.../domain/PaymentOutbox.java` — retryCount + incrementRetryCount
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

#### TC-11 — product / pg dedupe 테이블 cleanup 스케줄러 (product ✅ 완료 + 운영 활성화 정상화 / pg 범위 제외)

장기 운영 시 만료 row 누적으로 쿼리 성능 저하 가능.

**현황**:
- product-service `stock_commit_dedupe` — ✅ `DedupeCleanupWorker` (`@Scheduled`) 도입 완료 (EOS-FOLLOWUP-CLEANUP, 2026-05-29). `deleteExpired` 만료 행 일괄 DELETE + `SchedulerConfig` 활성 게이트. 단, worker 와 `SchedulerConfig` 게이트는 구현됐으나 `application-docker.yml` 에 `scheduler.enabled: true` 플래그가 누락돼 운영 docker 포함 어떤 배포에서도 실제 미기동 상태였음 → CLEANUP-BATCH-D Task 3 에서 플래그 추가로 정상화.
- pg-service `pg_inbox` — **범위 제외**. 종결 행이 confirm 재배달 멱등 SoT 라 청소 대상 아님 (terminal row 보존이 멱등성 보장의 본질)
- payment-service `payment_event_dedupe` — ✅ `DedupeCleanupWorker` 도입 완료 (TC-13-FOLLOW-2)
- payment-service 의 Redis dedupe (재고 차감/보상 token) 는 TTL 자동 expire — 문제 없음

**관련 코드**:
- `product-service/.../infrastructure/idempotency/JdbcEventDedupeStore.java`
- `product-service/.../infrastructure/scheduler/DedupeCleanupWorker.java`

#### TC-12 — pg-service Worker.stop 채널 drain 도입 ⏸️ 보류 (2026-06-14, 실익 대비 복잡도 부적합)

**보류 결정 (PG-WORKER-GRACEFUL-DRAIN discuss 사전 브리핑 단계)**: 채널 잔여는 RDB SoT(`pg_outbox`/`pg_inbox`) + 폴링 회수로 **유실 0 이 이미 보장**된다. drain 의 실익은 "종료 시 인메모리 잔여 즉시 처리 → 재기동 후 폴링 지연 단축"이라는 graceful 품질 개선에 한정. 학습 프로젝트에서 이 한계 이득이 동반 복잡도(① 새 유입 차단을 위한 Kafka consumer→워커→채널 SmartLifecycle phase 순서 정합, ② outbox/inbox 공통 base 대칭 처리 — inbox 는 벤더 호출 in-flight, ③ drain-timeout + 폴백 + K8s grace period 정합)를 정당화하지 못한다고 판단. 운영 환경에서 종료 지연이 실제 문제로 측정되면 재검토.

**참고 — 코드 현황 (재검토 시 출발점)**:
- stop 로직은 CLEANUP-BATCH-C 에서 `AbstractImmediateWorker.stop(Runnable)` 로 공통화됨 (outbox/inbox 즉시 워커 공유). 현재 `running=false` → 워커 `interrupt` → `join(10s)` → executor `awaitTermination(10s)→shutdownNow`. 채널 잔여 drain 단계 없음.
- 이미 `executor.submit` 된 in-flight 는 executor graceful shutdown 으로 완료 대기됨. 미take 채널 잔여만 종료 시 메모리 소멸 → 폴링 회수.
- 채널(`PgOutboxChannel`/`PgInboxChannel`)은 SmartLifecycle 아님(단순 `LinkedBlockingQueue` 빈). `AbstractImmediateWorker.getPhase()` 주석의 "채널 나중 stop drain" 의도는 채널이 lifecycle 이 아니라 미실현 — 재검토 시 이 갭부터 정리.

**관련 코드**:
- `pg-service/.../infrastructure/scheduler/AbstractImmediateWorker.java` (`stop(Runnable)` 공통 base)
- `pg-service/.../infrastructure/channel/{PgOutboxChannel,PgInboxChannel}.java`
- `pg-service/.../infrastructure/scheduler/{PgOutboxPollingWorker,PgInboxPollingWorker}.java` (RDB 폴백)

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

**항목 3 — 좀비 폴링 회수 traceparent 이어붙이기**: ✅ 완료 (EOS-FOLLOWUP-CLEANUP, 2026-05-29). 상세는 ## 완료 섹션.

**관련 코드**:
- `pg-service/.../infrastructure/scheduler/PgInboxImmediateWorker.java`
- `pg-service/.../infrastructure/scheduler/PgInboxPollingWorker.java`
- `pg-service/.../infrastructure/channel/PgInboxChannel.java`
- `pg-service/src/main/resources/application.yml` (inbox 설정 키)

---

## 완료

- ✅ **TIME-MODEL-AND-EXPIRY** (PR B) — 시간 모델 Clock/Instant 통일 + 결제 만료 정책 명문화 (이슈/브랜치 #83, 2026-06-03). T1~T17 + DM1/DM2 + 가드. `docs/archive/time-model-and-expiry/COMPLETION-BRIEFING.md`
  - **TC-8 해소**: 4서비스 시간 표준 = `Clock` 빈 + `Instant`. payment 자체 포트 `LocalDateTimeProvider`/`SystemLocalDateTimeProvider` 폐기(grep 0). 도메인은 `Instant` 인자 주입(now() 직접 호출 0). UTC 저장 일관 — ORM `hibernate.jdbc.time_zone=UTC` + raw-JDBC `connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true` + 명시 UTC Calendar(payment/product dedupe). payment 도메인 `PaymentEvent`+`PaymentOutbox` 모두 Instant(T17).
  - **TC-4 해소**: 만료 정책 명문화 — READY 만 직접 만료(`expire()` 가드), IN_PROGRESS 정체분은 정합 스캐너 복원 후 만료(2단 연쇄). 임계 외부화 `payment.expiration.ready-timeout-minutes`(기본 30), 스케줄러 키 `scheduler.payment-expiration.*` 정정(fallback 체인).
  - **D8/PITFALLS §13**: 벤더 승인 시각 `OffsetDateTime.parse(approvedAtRaw).toInstant()` 정규화(offset 보존, 정산 9시간 오차 차단). approvedAtRaw Kafka contract 무변경.
  - **DM1**: `@EnableJpaAuditing(dateTimeProviderRef="clockDateTimeProvider")` + Clock 기반 DateTimeProvider 로 created_at(만료 cutoff 기준) UTC 일관. **DM2**: product default/docker 양쪽 connectionTimeZone=UTC 로 dedupe split-brain 해소.
  - `./gradlew test` 846 PASS, jacoco 게이트 통과. 후속 등재: [TIME-PRODUCT-NOW-UNIFY] / [TZ-UTC-BACKSTOP] / [BASEENTITY-AUDIT-SOURCE].
- ✅ **EOS-FOLLOWUP-CLEANUP** — EOS 전환 후속 청소 (2026-05-29). 4묶음:
  - (A) `PaymentEventStatus.isCompensatableByFailureHandler()` → `canApplyConfirmResult()` (confirm 결과 적용 진입 가드) + `canCompensateStock()` (보상 가드) 두 메서드 분리. 둘 다 READY/IN_PROGRESS/RETRYING 만 true, 종결/QUARANTINED/EXPIRED 동조 false. 기존 메서드 완전 제거 (grep 0건). `PaymentEventStatusEosGuardTest` 삭제 → `PaymentEventStatusSplitMethodTest` + `PaymentEventStatusCrossInvariantTest` 로 역할 이전 (TC-13-FOLLOW-5 해소).
  - (B) `PaymentConfirmResultUseCase.handle` `@Transactional(transactionManager = "transactionManager", timeout = 5)` TM qualifier 명시 + best-effort 1PC 한계 Javadoc. `KafkaConsumerConfig` deprecated `setTransactionManager` → `setKafkaAwareTransactionManager` 교체 (TC-13-FOLLOW-6 qualifier 부분, ChainedKTM 미채택).
  - (C/D) payment `payment_event_dedupe` + product `stock_commit_dedupe` 만료 행 청소 — `deleteExpired(Instant, int)` 포트 + `JdbcXxx` 구현 + `DedupeCleanupWorker` (`@Scheduled`) 신설. product 는 `SchedulerConfig` (`@EnableScheduling` + `@ConditionalOnProperty scheduler.enabled`) 동반 (TC-13-FOLLOW-2 + TC-11 product 해소).
  - (E) pg `pg_inbox.stored_traceparent` 컬럼 추가 (Flyway V4) + `TraceparentExtractor` (OTel W3CTraceContextPropagator 래핑) 신설. consumer 추출 → RDB 저장 → `PgInboxPollingWorker` 폴링 회수 시 부모 추적 복원 (TC-15 항목 3 해소). traceparent 는 관측성 전용, 비즈니스 비참여.
- ✅ **TC-13** — payment-service EOS 전환 (PR #77, 2026-05-17, 브랜치 #77). PET-1~PET-14 14개 태스크. `payment_event_dedupe` 신설 + `KafkaTransactionManager` 통합 + `StockOutbox` 묶음 16+파일 + `payment_stock_outbox` DROP + product-service `read_committed` + Testcontainers 5 시나리오 GREEN. 후속: TC-13-FOLLOW-1~5.
- ✅ **TC-14** — pg-service vendor 호출 listener thread 분리 (PR #74, 2026-05-09). 상세: `docs/archive/pg-confirm-listener-split/COMPLETION-BRIEFING.md`
- ✅ **TC-16** — PgInboxAmountService dead service 제거 (CLEANUP-BATCH-A CBA-1, 브랜치 #75). main 호출처 0건 확인 후 본체 + PgInboxAmountStorageTest 삭제. CONFIRM-FLOW.md / PAYMENT-FLOW.md dangling reference 정정.
- ✅ **TC-2** — Seed 데이터 분리 (CLEANUP-BATCH-A CBA-2~5, 브랜치 #75). product/user-service `db/migration/` → `db/schema/` + `db/seed/` 물리 분리. docker profile `classpath:db/schema` 만 적용.
- ✅ **TC-5** — Retryable 예외 ControllerAdvice 매핑 보강 (CLEANUP-BATCH-A CBA-6, 브랜치 #75). `ProductServiceRetryableException` / `UserServiceRetryableException` → 503 + `Retry-After: 5` 매핑 추가.
- ✅ **TC-10** — pg-service 도메인 객체 생성자 패턴 통일 (CLEANUP-BATCH-A CBA-8/9, 브랜치 #75). PgInbox / PgOutbox `@Builder(allArgsBuilder/allArgsBuild) + @AllArgsConstructor(PRIVATE)` + factory only 노출. PgOutbox.create/createWithAvailableAt dead id 파라미터 제거.
- ✅ **CLEANUP-BATCH-B** — 빌드·테스트 게이트 위생 (PR #81, 브랜치 #81, 2026-05-31). 6태스크. [SPOTBUGS-TEST-DEBT] 5건 코드 정정(억제 0, if-throw + Supplier 전환) + [NET-RETRY] 502/504 retryable 승격 + JaCoCo 게이트 실효화(루트 subprojects 공통화 + integrationTest 합산 + 서비스별 LINE minimum) + Gradle 8.14.4(Java 24 호환). 후속: `[CLEANUP-BATCH-B 후속]`(user 게이트/Groovy 문법/infra 집계).

---

## Plan 작성 시 사용 가이드

- 각 T 항목을 새 토픽으로 승격할 때 `docs/topics/<TOPIC>.md` + `docs/<TOPIC>-PLAN.md` 신규
- 본 TODOS 의 항목은 plan 의 "근거" 절에서 인용 가능
- 토픽 종결 시 본 파일에서 해당 항목 삭제 (또는 archive briefing 으로 이전)

## 관련

- 학습된 함정: `PITFALLS.md`
- 알려진 우려: `CONCERNS.md`
- 직전 봉인 토픽 회고: `docs/archive/{msa-transition,pre-phase-4-hardening,stock-compensation-recovery,pg-confirm-listener-split}/COMPLETION-BRIEFING.md`
