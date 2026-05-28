# 현재 작업 상태

> 최종 수정: 2026-05-29 — EOS-FOLLOWUP-CLEANUP B-1 GREEN 완료. 이슈 #79, 브랜치 #79, stage=execute, Task B-2 대기.

## 활성 작업

- **EOS-FOLLOWUP-CLEANUP** (이슈 #79, 브랜치 #79, stage=**execute**, 현재 Task **B-2** 대기) — EOS 전환 후속 정합 + 결제 비동기 경로 청소. 5작업군: A-1~A-3, B-1 완료.
  - FOLLOW-6 — `PaymentConfirmResultUseCase.handle`에 TM qualifier 명시(나머지 13개+ 무변경) + deprecated `setTransactionManager` → `setKafkaAwareTransactionManager` 교체 + best-effort 1PC 한계 문서화
  - FOLLOW-5 — 겸용 판별 메서드를 `canApplyConfirmResult` / `canCompensateStock`로 분리 + 두 메서드 종결/QUARANTINED/EXPIRED 답 동조 교차 불변식 회귀 테스트(D-SPLIT-3)
  - FOLLOW-2 — `payment_event_dedupe` 만료 행 cleanup 스케줄러
  - TC-11 — product `stock_commit_dedupe` 만료 행 cleanup 스케줄러
  - TC-15 항목3 — `pg_inbox.stored_traceparent` 컬럼(Flyway) + 폴링 회수 시 부모 추적 복원
  - pg_inbox 청소는 종결 행이 confirm 재배달 멱등 SoT라 범위 제외(Round 1 Domain Expert critical 대응).
  - 확인 의무 2건 해소: `setKafkaAwareTransactionManager`는 spring-kafka 3.3.4에 실재(메서드명만 교체) / traceparent 추출은 OTel `Context.current()` 확정.
  - plan 완료 — 14 태스크(작업군 A~E, 서로 독립 병렬 가능). E 작업군은 Round 1 critical(application→infra 역의존) → 추출을 consumer로 재배치해 해소.
  - 설계: `docs/topics/EOS-FOLLOWUP-CLEANUP.md`. 계획: `docs/EOS-FOLLOWUP-CLEANUP-PLAN.md`. 라운드: `docs/rounds/eos-followup-cleanup/`.

## 직전 봉인

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

- **`PR B (TC-4 + TC-8)`** — 도메인 결정 묶음 (EXPIRED 만료 스케줄러 정책 + Clock/Instant/LocalDateTime 시간 추상화 통합)
- **`[NET-RETRY]`** (CLEANUP-BATCH-A 후속) — Feign ErrorDecoder 429/503 분기 보존 + 비-503 5xx (500/502/504) 매핑
- **`[FLYWAY-USER-SEED-GAP]`** (CLEANUP-BATCH-A 후속) — user-service Testcontainers 동등 검증
- **`TC-13-FOLLOW`** (PAYMENT-EOS-TRANSITION 후속) — multi-instance hostname 충돌 / TTL 정리 스케줄러 / tx coordinator 모니터링 / D7 분기 알람 SLO / D7 SSOT 정리 / `@Transactional` 한정자 또는 Chained KTM
- **`PHASE-4`** — Toxiproxy 8종 장애 주입 시나리오 + k6 시나리오 재설계 + 로컬 오토스케일러
- **`STOCK-COMPENSATION-OTHER-PATHS`** (후속) — `OutboxAsyncConfirmService.compensateStock` / `PaymentTransactionCoordinator.compensateStockCacheGuarded` 의 동일 silent loss 패턴 회복
- **`TC-15`** — PG-CONFIRM-LISTENER-SPLIT PHASE2 정밀화
