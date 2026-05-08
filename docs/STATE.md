# 현재 작업 상태

> 최종 수정: 2026-05-09 — PG-CONFIRM-LISTENER-SPLIT 토픽 시작, discuss 진입 대기

## 활성 작업
- **주제**: PG-CONFIRM-LISTENER-SPLIT (pg-service vendor 호출 listener thread 분리, pg-confirm-flow.md 위키 정합)
- **단계**: discuss 대기
- **이슈**: #73
- **브랜치**: #73

## 파일 링크
- **사전 브리핑 (Baseline 0)**: docs/topics/PG-CONFIRM-LISTENER-SPLIT.md
- 라운드 산출물: docs/rounds/pg-confirm-listener-split/ (discuss 단계 진입 시 생성)

## 단계 진행
- [ ] discuss
- [ ] plan
- [ ] plan-review
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
