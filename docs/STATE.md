# 현재 작업 상태

> 최종 수정: 2026-05-09 — PCS-6 완료 (PgVendorCallService invokeVendor + applyOutcome TX 경계 분리, 활성 태스크 PCS-7)

## 활성 작업
- **주제**: PG-CONFIRM-LISTENER-SPLIT (pg-service vendor 호출 listener thread 분리, pg-confirm-flow.md 위키 정합)
- **단계**: execute (활성 태스크 PCS-7 — `PgInboxPendingService` 신규, listener TX 경계 봉인)
- **채택안**: 위키 분리 안 정합 — `PgInboxPendingService` 신규 `@Transactional` listener TX 경계 + `PgInboxChannel` (cap=1024) + `PgInboxImmediateWorker` (worker=5, processPending/processInProgressZombie 분리) + `PgInboxPollingWorker` (60s 통일, 새 root span) + `PgInboxStatus` PENDING 추가 + NONE 폐기 + 보정 경로 PENDING 우회 룰 (§1.8 신규 repo 메서드 4종)
- **이슈**: #73
- **브랜치**: #73

## 파일 링크
- **토픽 본문 (채택안 권위)**: docs/topics/PG-CONFIRM-LISTENER-SPLIT.md (§1.1~§1.9 + §2.1/§2.2 + §3 인벤토리 + §4 검증 plan + §7 acceptance + §8 흡수 노트)
- 라운드 산출물:
  - docs/rounds/pg-confirm-listener-split/discuss-interview-0.md (Round 0 ledger)
  - docs/rounds/pg-confirm-listener-split/discuss-critic-1.md (Round 1 — major 3 + minor 3, revise)
  - docs/rounds/pg-confirm-listener-split/discuss-domain-1.md (Round 1 — major 3 + minor 2, revise)
  - docs/rounds/pg-confirm-listener-split/discuss-critic-2.md (Round 2 — minor 1, pass)
  - docs/rounds/pg-confirm-listener-split/discuss-domain-2.md (Round 2 — finding 0, pass)
- 플랜: docs/PG-CONFIRM-LISTENER-SPLIT-PLAN.md (plan 단계 산출 예정)

## 단계 진행
- [x] discuss
- [x] plan
- [x] plan-review
- [ ] execute
- [ ] review
- [ ] verify

## 직전 봉인

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
- 봉인 시점 코드 상태: 4서비스(payment/pg/product/user) + Eureka + Gateway, Kafka 양방향 confirm, AMOUNT_MISMATCH 양방향 방어, payment-service 측 dedupe = Lua atomic dedup token (orderId 단위 P8D) + Spring Kafka native `DefaultErrorHandler`, client-side LB (LoadBalanced WebClient + OpenFeign), Redis `appendfsync=always`

## 다음 토픽 후보

- **`PHASE-4`** — Toxiproxy 8종 장애 주입 시나리오 + k6 시나리오 재설계 + 로컬 오토스케일러
- **`STOCK-COMPENSATION-OTHER-PATHS`** (후속) — `OutboxAsyncConfirmService.compensateStock` / `PaymentTransactionCoordinator.compensateStockCacheGuarded` 의 동일 silent loss 패턴 회복 (review Domain D1 인지: confirm TX 실패 보상 시 `decrement:done` token 정합 — token DEL 또는 compensation token 박기 정책 정밀화 필요)
- **`TC-13`** — payment-service confirmed consumer EOS 전환 (위키-코드 sync 잔여 갭, stock_outbox 묶음 제거 + Kafka tx)
