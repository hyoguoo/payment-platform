# 현재 작업 상태

> 최종 수정: 2026-05-17 — PET-4 완료 (Flyway V2 `payment_event_dedupe` 테이블 신설, D5 결정 그대로). 활성 태스크 PET-5.

## 활성 작업

- **PAYMENT-EOS-TRANSITION** (payment-service 결제 결과 컨슈머 EOS 전환, 위키 정합 잔여 갭) — `docs/topics/PAYMENT-EOS-TRANSITION.md` + `docs/PAYMENT-EOS-TRANSITION-PLAN.md`
  - stage: **execute** (plan-review pass → execute 진입)
  - 활성 태스크: **PET-5** (`JdbcEventDedupeStore` 어댑터 TDD — Testcontainers MySQL)
  - 이슈: [#77](https://github.com/hyoguoo/payment-platform/issues/77)
  - 브랜치: `#77`
  - discuss 라운드 합의: Round 2 양쪽 pass (Round 1 critical 1 + high 3 + medium 3 흡수)
  - plan 라운드 합의: Round 1 Critic pass (minor 4) / Domain Expert pass (minor 2) — 1라운드 만에 양쪽 pass
  - plan-review 합의: **Plan Reviewer pass (minor 2)** — PD1-1 / PD1-2 모두 execute 단계 implementer 판단으로 forward-fix
  - 14개 태스크 (TDD 4 / non-TDD 10) — PET-1 ~ PET-14
  - 8개 핵심 결정 (D1~D8): 위키 EOS 안 채택 / 빅뱅 1 PR / 가용성 트레이드오프 수용 / `transactional.id` 정책 / `payment_event_dedupe` 스키마 / product-service `read_committed` / `handle` 진입 가드 / 두 종류 UUID 역할 분리
  - 영향 모듈: payment-service (application/usecase + application/port/out + infrastructure/dedupe + infrastructure/config + Flyway V2/V3) + product-service (application-*.yml)
  - 삭제 대상: 16+ 파일 (`StockOutbox` 묶음 + 테스트) + `payment_stock_outbox` 테이블 drop
  - 유지 대상: `StockEventUuidDeriver` (multi-product idempotencyKey 결정성 도출)
  - plan minor (forward-fix 가능): PD1-1 EventDedupeStore 동명 재사용 / PD1-2 EOS wiring 단위 검증 부재

## 직전 봉인

## 직전 봉인

- **CLEANUP-BATCH-A** (PR 묶음 A — 코드 청소 4건, 2026-05-12) — `docs/archive/cleanup-batch-a/`
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
- 봉인 시점 코드 상태: 4서비스(payment/pg/product/user) + Eureka + Gateway, Kafka 양방향 confirm, AMOUNT_MISMATCH 양방향 방어, payment-service 측 dedupe = Lua atomic dedup token (orderId 단위 P8D) + Spring Kafka native `DefaultErrorHandler`, client-side LB (LoadBalanced WebClient + OpenFeign), Redis `appendfsync=always`, pg-service listener TX 분리 (PgInboxPendingService) + inbox 작업 큐 (cap=1024) + VT 워커 5 + 60s 좀비 폴링 (PENDING/IN_PROGRESS) + 보정 경로 PENDING 우회 + PgTerminalReemitService 별 빈, **pg-service `PgInbox` / `PgOutbox` 의 `@Builder + @AllArgsConstructor(PRIVATE)` 통일 + Flyway db/schema+db/seed 환경 분리 + payment-service `Retryable` 예외 503+Retry-After 매핑**

## 다음 토픽 후보

- **`PR B (TC-4 + TC-8)`** — 도메인 결정 묶음 (EXPIRED 만료 스케줄러 정책 + Clock/Instant/LocalDateTime 시간 추상화 통합)
- **`PR C (TC-13)`** — payment-service EOS 전환 (위키 정합 잔여 갭, stock_outbox 묶음 제거 + Kafka tx)
- **`[NET-RETRY]`** (CLEANUP-BATCH-A 후속) — Feign ErrorDecoder 429/503 분기 보존 + 비-503 5xx (500/502/504) 매핑
- **`[FLYWAY-USER-SEED-GAP]`** (CLEANUP-BATCH-A 후속) — user-service Testcontainers 동등 검증
- **`PHASE-4`** — Toxiproxy 8종 장애 주입 시나리오 + k6 시나리오 재설계 + 로컬 오토스케일러
- **`STOCK-COMPENSATION-OTHER-PATHS`** (후속) — `OutboxAsyncConfirmService.compensateStock` / `PaymentTransactionCoordinator.compensateStockCacheGuarded` 의 동일 silent loss 패턴 회복
- **`TC-15`** — PG-CONFIRM-LISTENER-SPLIT PHASE2 정밀화
