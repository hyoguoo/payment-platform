# 현재 작업 상태

> 최종 수정: 2026-05-09 — PG-CONFIRM-LISTENER-SPLIT 봉인 (PCS-1~16 16태스크 + review 라운드 2 양쪽 pass + 294 PASS / 0 FAIL). PR 생성 대기.

## 활성 작업

없음. 다음 토픽 후보 참고.

## 직전 봉인

- **PG-CONFIRM-LISTENER-SPLIT** (pg-service Kafka listener TX 에서 벤더 호출 분리, 2026-05-09) — `docs/archive/pg-confirm-listener-split/`
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
- 봉인 시점 코드 상태: 4서비스(payment/pg/product/user) + Eureka + Gateway, Kafka 양방향 confirm, AMOUNT_MISMATCH 양방향 방어, payment-service 측 dedupe = Lua atomic dedup token (orderId 단위 P8D) + Spring Kafka native `DefaultErrorHandler`, client-side LB (LoadBalanced WebClient + OpenFeign), Redis `appendfsync=always`, **pg-service listener TX 분리 (PgInboxPendingService) + inbox 작업 큐 (cap=1024) + VT 워커 5 + 60s 좀비 폴링 (PENDING/IN_PROGRESS) + 보정 경로 PENDING 우회 + PgTerminalReemitService 별 빈**

## 다음 토픽 후보

- **`PHASE-4`** — Toxiproxy 8종 장애 주입 시나리오 + k6 시나리오 재설계 + 로컬 오토스케일러
- **`STOCK-COMPENSATION-OTHER-PATHS`** (후속) — `OutboxAsyncConfirmService.compensateStock` / `PaymentTransactionCoordinator.compensateStockCacheGuarded` 의 동일 silent loss 패턴 회복 (review Domain D1 인지: confirm TX 실패 보상 시 `decrement:done` token 정합 — token DEL 또는 compensation token 박기 정책 정밀화 필요)
- **`TC-13`** — payment-service confirmed consumer EOS 전환 (위키-코드 sync 잔여 갭, stock_outbox 묶음 제거 + Kafka tx)
- **`TC-15`** — PG-CONFIRM-LISTENER-SPLIT PHASE2 정밀화 (워커 VT 풀 / 채널 cap / 좀비 임계 측정 기반 정밀화, 멀티 인스턴스 worker concurrency 검증, 좀비 폴링 traceparent 이어붙이기)
- **`TC-16`** — `PgInboxAmountService` dead service 제거
