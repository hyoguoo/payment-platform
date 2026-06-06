# 현재 작업 상태

> 최종 수정: 2026-06-06 — TIME-MODEL-FOLLOWUP execute P6 완료 → P7 진행.
> **다음 세션 진입점**: TIME-MODEL-FOLLOWUP execute 단계, **Task P7**(JdbcEventDedupeStoreCleanupTest existsValid 잔재 제거)부터. PLAN: `docs/TIME-MODEL-FOLLOWUP-PLAN.md`.

## 활성 작업

- **TIME-MODEL-FOLLOWUP** (stage: **execute**, 활성 태스크 **P7**, 이슈/브랜치 #89) — TIME-MODEL-AND-EXPIRY(#83) 이연 후속 3건 한 PR 묶음
  - discuss 완료 (Critic·Domain Expert pass). plan 완료 (Critic·Domain Expert pass — 도중 Domain Expert critical 1[P14 BaseEntity 태스크 본문 소실] 잡아 복원 + major[P13/P14 순서]·minor 반영). plan-review pass(minor 2 정정). 설계: `docs/topics/TIME-MODEL-FOLLOWUP.md`, PLAN: `docs/TIME-MODEL-FOLLOWUP-PLAN.md`(18 태스크)
  - 핵심 결정 — D1 product `recordIfAbsent` 만료 삭제 `NOW()` → 앱 주입 `Instant` 통일(포트 `now` 인자) / D2 `existsValid` 전건 제거(라이브 0건) / D3 TZ backstop 3겹(Dockerfile+JVM+compose UTC) / D4 payment `BaseEntity` `LocalDateTime` → `Instant` + Flyway V4 `DATETIME` → `DATETIME(6)` 승급 / D5 product `connectionTimeZone=UTC` 존치 / D6 AC8 → `recordIfAbsent` DELETE 경계 검증 재배치 / D7 단일 PR
  - 태스크 18개 3묶음: 멱등 만료 시각 통일(P1~P7) / TZ UTC 3겹(P8~P10) / 감사 컬럼 Instant 전환(P11~P18). **순서 불변**: P13(V4 DDL 정밀도 승급) → P14(BaseEntity 타입 전환) — validate 부팅 정합
  - plan 확인 포인트 — `clockDateTimeProvider` Instant 반환 후 auditing wiring 회귀 가드(#83 review 전례, P11/P18) / eureka compose TZ 위치 = `docker-compose.infra.yml`(P9 확정)

## 직전 봉인

- **TIME-MODEL-AND-EXPIRY** (PR B — 시간 모델 Clock/Instant 통일 + 결제 만료 정책 명문화, 이슈/브랜치 #83, 2026-06-03) — `docs/archive/time-model-and-expiry/COMPLETION-BRIEFING.md`
  - 17태스크(T1~T17) + DM1/DM2 + 회귀 가드, 27커밋. `./gradlew test` 846 PASS, 최종 리뷰 critical/major 0.
  - **TC-8 해소**: 4서비스 `Clock` 빈 + `Instant` 통일. `LocalDateTimeProvider`/`SystemLocalDateTimeProvider` 폐기(grep 0), 도메인 `Instant` 인자 주입(now() 직접 0). UTC 저장 일관(ORM hibernate.jdbc.time_zone=UTC + raw-JDBC connectionTimeZone=UTC + 명시 UTC Calendar). payment 도메인 PaymentEvent+PaymentOutbox 모두 Instant(T17, 경계 ofInstant 6곳 제거).
  - **TC-4 해소**: 만료 정책 명문화 — READY 직접 만료(`expire()` 가드) + 정합 스캐너 2단 연쇄. 임계 외부화(`payment.expiration.ready-timeout-minutes` 기본30) + 스케줄러 키 정정(fallback).
  - **DM1/DM2/D8**: auditing `clockDateTimeProvider` UTC化(만료 cutoff 정합) / product default+docker connectionTimeZone=UTC(dedupe split-brain 해소) / 벤더 승인 시각 `.toInstant()` 정규화(정산 9시간 오차 차단, approvedAtRaw contract 무변경).
  - 영구 문서 5개 갱신(PITFALLS §6/§13 / ARCHITECTURE / INTEGRATIONS / CONVENTIONS / TODOS). 후속: [TIME-PRODUCT-NOW-UNIFY] / [TZ-UTC-BACKSTOP] / [BASEENTITY-AUDIT-SOURCE].
  - 잔여: wip 커밋(94a4053f) PR squash merge로 흡수.

- **CLEANUP-BATCH-B** (빌드·테스트 게이트 위생 — spotbugs 위반 회복 + NET-RETRY 5xx 매핑 + JaCoCo 게이트 실효화, 이슈 #81, 브랜치 #81, PR #82, 2026-05-31) — `docs/archive/cleanup-batch-b/COMPLETION-BRIEFING.md`
  - 6태스크 16커밋. spotbugs 5건 **전부 코드 정정**(억제 0; NP_NULL은 `if-null-throw`—`requireNonNull`을 SpotBugs 6.0.9가 미인식, EI_EXPOSE_REP2는 `FakeMessagePublisher` Throwable→Supplier) + 502/504 retryable 승격(500 유지·429/503 단일) + JaCoCo 게이트 실효화(루트 subprojects 공통화 + integrationTest 합산 + 서비스별 LINE minimum, element=BUNDLE) + Gradle 8.14.4(Java 24)
  - review major 1(C-2 범위 밖 부채 혼입→커밋 분리)/minor 4, Domain Expert pass(결제 정합성 위험 0)
  - **verify CI 단계 발견·수정**: C-1(jacocoTestReport→integrationTest dependsOn)이 CI 에서 통합테스트를 처음 실행 → `PaymentEosIntegrationTest` #1 cold-start flaky 노출(consumer assignment 미대기). setUp 에 partition assignment 대기 추가로 해소 + ci.yml JUnit 리포트 액션(테스트 실패 PR 가시성). CI 전체 GREEN
  - 영구 문서 4개 갱신(INTEGRATIONS / STACK / TESTING / TODOS). 후속: `[CLEANUP-BATCH-B 후속]`(user 게이트 0.0 / Groovy space-assignment 문법 / infra 커버리지 집계 / Node.js 20 액션 deprecated)
- **EOS-FOLLOWUP-CLEANUP** (EOS 전환 후속 정합 + 결제 비동기 경로 청소, **PR #80** Closes #79, 2026-05-29, 브랜치 #79) — `docs/archive/eos-followup-cleanup/COMPLETION-BRIEFING.md`
  - 14 태스크(A-1~A-3, B-1~B-2, C-1~C-3, D-1~D-3, E-1~E-5) 31 커밋
  - FOLLOW-6: `handle`에 `@Transactional(transactionManager="transactionManager")` qualifier 명시(위험 지점 1곳만) + deprecated→`setKafkaAwareTransactionManager` 교체 + 1PC 한계 Javadoc
  - FOLLOW-5: `isCompensatableByFailureHandler` → `canApplyConfirmResult`/`canCompensateStock` 분리 + 교차 불변식 회귀 테스트(D7 침묵 DLQ 드리프트 가드) + `PaymentEventStatusEosGuardTest` 삭제
  - FOLLOW-2/TC-11: payment·product dedupe 만료행 청소 `DedupeCleanupWorker`(@Scheduled, `deleteExpired` 추가, TTL P8D>retention 7d 멱등 무해), pg_inbox는 재배달 멱등 SoT라 제외
  - TC-15 항목3: Flyway V4 `pg_inbox.stored_traceparent` + `TraceparentExtractor`(OTel infra 격리) — consumer 추출→RDB 보관→폴링 회수 시 원본 confirm parent 복원
  - 리뷰 critical/major 0, minor 3 후속 등재(PRODUCT-TIME-ABSTRACTION / SCHEDULER-ENABLED-GATE / CLEANUP-FAILURE-COUNTER, TODOS.md)
  - `./gradlew test` 746 PASS, 영구 문서 5개 갱신(CONFIRM-FLOW / ARCHITECTURE / STRUCTURE / PITFALLS / TODOS)
- **PAYMENT-EOS-TRANSITION** (payment-service 결제 결과 컨슈머 EOS 전환, PR 생성 대기, 2026-05-18) — `docs/archive/payment-eos-transition/`

- **PAYMENT-EOS-TRANSITION** (payment-service 결제 결과 컨슈머 EOS 전환, PR 생성 대기, 2026-05-18) — `docs/archive/payment-eos-transition/`
  - 발행 보장 모델을 outbox → Kafka EOS 빅뱅 전환 (1 PR 안에 `StockOutbox` 묶음 22 파일 삭제 + `KafkaTransactionManager` + `payment_event_dedupe` UNIQUE INSERT IGNORE + product-service `read_committed` 동시 적용)
  - 14 PET (PET-1~PET-14) + review R1 fix 4 커밋 + R2 산출물
  - 8개 핵심 결정 (D1~D8): 위키 EOS 안 채택 / 빅뱅 1 PR / 가용성 트레이드오프 수용 / `transactional.id` 정책 / `payment_event_dedupe` 스키마 / product-service `read_committed` / `handle` 진입 가드 / 두 종류 UUID 역할 분리
  - 통합 5 시나리오 (정상 commit / abort invisibility / 중복 INSERT IGNORE / multi-product / QUARANTINED 가드) PASS
  - `StockEventUuidDeriver` 보존 (DR-1 multi-product idempotencyKey 결정성)
  - review R1 fix 부수효과 hidden bug 3건 clean fix (gatewayType=null DB NOT NULL / CheckoutResult Jackson is-prefix / IdempotencyStoreRedisAdapter race)
  - 위키 4개 파일 Phase 6 마커 제거 + EOS 정합 봉인
  - 영구 문서 8개 갱신 (CONFIRM-FLOW / ARCHITECTURE / STRUCTURE / PITFALLS / CONCERNS / TODOS / CONVENTIONS / PAYMENT-FLOW), CONCERNS L-1 + CONFIRM-FLOW §5 + TODOS TC-13-FOLLOW-6 에 "위키 line 141 룰이 EOS atomicity 가 아닌 안전성 SSOT" 명시
  - 후속 등재 — TC-13-FOLLOW-1 (multi-instance 확장) / TC-13-FOLLOW-2 (TTL 스케줄러) / TC-13-FOLLOW-3 (tx coordinator 모니터링) / TC-13-FOLLOW-4 (D7 분기 알람 SLO) / TC-13-FOLLOW-5 (D7 SSOT 정리) / TC-13-FOLLOW-6 (Transactional 한정자 / Chained KTM)
  - `./gradlew test` 708 PASS + `:payment-service:integrationTest` 23/23 PASS
- **CLEANUP-BATCH-A** (PR 묶음 A — 코드 청소 4건, 2026-05-12 PR #76 머지) — `docs/archive/cleanup-batch-a/`
  - §1.1 TC-16 `PgInboxAmountService` dead service 본체 + 단독 테스트 2 파일 삭제 + 영구 문서 dangling 정정
  - §1.2 TC-10 `PgInbox` / `PgOutbox` `@Builder(allArgsBuilder/allArgsBuild) + @AllArgsConstructor(PRIVATE)` 통일, factory only 노출, `PgOutbox` `Long id` dead parameter 제거 (호출처 5 파일 정정), payment-service `PaymentOutbox` 패턴과 정합
  - §1.3 TC-2 product / user-service Flyway `db/migration/` → `db/schema/` + `db/seed/` 분리, `application-docker.yml` 의 `spring.flyway.locations: classpath:db/schema` override 로 운영 seed 차단, `FlywayDockerProfileTest` Testcontainers 검증 (product-service)
  - §1.4 TC-5 payment-service `PaymentExceptionHandler` 에 `ProductServiceRetryableException` / `UserServiceRetryableException` → 503 + `Retry-After: 5` 헤더 일괄 매핑
  - 영구 문서 6개 갱신 (CONFIRM-FLOW / PAYMENT-FLOW / STACK / CONVENTIONS / STRUCTURE / TODOS)
  - 후속 등재 — `[NET-RETRY]` (Feign ErrorDecoder 429/503 분기) / `[FLYWAY-USER-SEED-GAP]` (user-service Testcontainers 동등)
  - `./gradlew test` 698 → 702 PASS / 0 FAIL (+4)
- **PG-CONFIRM-LISTENER-SPLIT** (pg-service Kafka listener TX 에서 벤더 호출 분리, 2026-05-09 PR #74 머지) — `docs/archive/pg-confirm-listener-split/`
  - `PgInboxPendingService` (listener TX timeout=5s, INSERT IGNORE + publishEvent) + `PgInboxChannel` (LinkedBlockingQueue cap=1024) + `PgInboxImmediateWorker` (VT 5, status 4분기 dispatch) + `PgInboxPollingWorker` (60s 통일, PENDING/IN_PROGRESS 두 경로, 새 OTel root span)
  - `PgInboxStatus` PENDING 추가 + NONE 폐기 + Flyway V2~V3 (PENDING enum + paymentKey/vendorType 컬럼)
  - 보정 경로 PENDING 우회 룰 (`DuplicateApprovalHandler` `transitDirectToTerminal` / `transitDirectToInProgress`)
  - native query `FOR UPDATE SKIP LOCKED` (PENDING + IN_PROGRESS 양쪽)
  - `PgTerminalReemitService` 별 빈 분리 (review M2 흡수 — self-invocation proxy 우회 차단)
  - `pg_inbox.zombie_recovered_total{status}` / `listener_tx_timeout_total` Counter 신규
  - 위키 (`payment-platform.wiki/pg-confirm-flow.md` + `outbox-channel-dispatch.md`) + 영구 문서 (`docs/context/ARCHITECTURE/CONFIRM-FLOW/STRUCTURE/TODOS.md`) 동기화
  - `./gradlew test` pg-service 281 → 294 PASS / 0 FAIL (+13)
- **STOCK-COMPENSATION-RECOVERY** (결제 결과 보상 silent loss 회복 layer, 2026-05-08 PR #72 머지) — `docs/archive/stock-compensation-recovery/`
  - Lua atomic dedup token (`decrement:done` / `compensation:done` SETNX P8D) + Spring Kafka native `DefaultErrorHandler` 위임 + dedupe lease / `PaymentConfirmDlqPublisher` 두 orphan port 폐기
  - `handleFailed` 호출 순서 뒤집기 (보상 → markPaymentAsFail) — 모든 crash 지점에서 정합 회복 보장
  - Redis `appendfsync=always` 강제 (AOF race window 완화)
  - `./gradlew test` 607 PASS / 0 FAIL (line 89.77% / branch 95.42%)
  - 11개 context 문서 갱신
- **CLIENT-SIDE-LB** (Phase A LoadBalanced WebClient + Phase B OpenFeign, 2026-04-28 PR #70 머지) — `docs/archive/client-side-lb/`
- **MSA-TRANSITION** (Phase 0~3.5 완료) — `docs/archive/msa-transition/`
- **PRE-PHASE-4-HARDENING** (3축 19태스크 + K1~K15) — `docs/archive/pre-phase-4-hardening/`
- **PHASE-4-READINESS-SWEEP** (Self-loop 정리, 2026-04-27) — `docs/archive/phase-4-readiness-sweep/`
- 봉인 시점 코드 상태: 4서비스(payment/pg/product/user) + Eureka + Gateway, Kafka 양방향 confirm, AMOUNT_MISMATCH 양방향 방어, payment-service 측 dedupe = (1) Lua atomic dedup token (재고/보상, orderId 단위 P8D) + (2) `payment_event_dedupe` MySQL INSERT IGNORE (메시지 단위, EOS 트랜잭션 안) + Spring Kafka `DefaultErrorHandler` + `KafkaTransactionManager`, client-side LB (LoadBalanced WebClient + OpenFeign), Redis `appendfsync=always`, pg-service listener TX 분리 (PgInboxPendingService) + inbox 작업 큐 (cap=1024) + VT 워커 5 + 60s 좀비 폴링 + 보정 경로 PENDING 우회 + PgTerminalReemitService 별 빈, **payment-service `StockOutbox` 묶음 폐기 + EOS 직접 발행 (multi-product loop 안에서 `StockEventUuidDeriver.derive` + `producer.send(stock-committed)` Kafka tx) + product-service `isolation.level=read_committed`**

## 다음 토픽 후보

> **진행 상황 (2026-05-30)**: 토픽 A `CLEANUP-BATCH-B` 는 discuss 완료 후 활성 작업으로 승격(위 `## 활성 작업` 참조). 토픽 B 만 후보로 남음.
>
> - **토픽 B `PR B`** (도메인 설계, 묵직 — 토픽 A 이후) — discuss 제대로 거칠 묶음:
>   - TC-4 (EXPIRED 만료 스케줄러 정책: 언제·어떤 상태에서 만료 전이) + TC-8 (Clock/Instant/LocalDateTime 시간 추상화 통합) + `[PRODUCT-TIME-ABSTRACTION]`(product-service `LocalDateTimeProvider`/Clock 부재 — TC-8에 흡수) 함께.

- **`[FLYWAY-USER-SEED-GAP]`** (CLEANUP-BATCH-A 후속) — user-service Testcontainers 동등 검증
- **`TC-13-FOLLOW`** (PAYMENT-EOS-TRANSITION 후속) — multi-instance hostname 충돌 / TTL 정리 스케줄러 / tx coordinator 모니터링 / D7 분기 알람 SLO / D7 SSOT 정리 / `@Transactional` 한정자 또는 Chained KTM
- **`PHASE-4`** — Toxiproxy 8종 장애 주입 시나리오 + k6 시나리오 재설계 + 로컬 오토스케일러
- **`STOCK-COMPENSATION-OTHER-PATHS`** (후속) — `OutboxAsyncConfirmService.compensateStock` / `PaymentTransactionCoordinator.compensateStockCacheGuarded` 의 동일 silent loss 패턴 회복
- **`TC-15`** — PG-CONFIRM-LISTENER-SPLIT PHASE2 정밀화
