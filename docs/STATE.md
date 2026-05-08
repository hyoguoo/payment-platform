# 현재 작업 상태

> 최종 수정: 2026-05-08 — SCR-6 완료, 활성 태스크 SCR-7

## 활성 작업
- **주제**: STOCK-COMPENSATION-RECOVERY (결제 결과 보상 실패 자동 회복 layer)
- **단계**: execute (활성 태스크 SCR-7 — EventDedupeStore + PaymentConfirmDlqPublisher port/adapter 폐기)
- **채택안**: Round 7 — `stock_decrement_atomic.lua` + `stock_compensation_atomic.lua` + Spring Kafka `DefaultErrorHandler` 위임 + dedupe lease / `PaymentConfirmDlqPublisher` port 폐기
- **이슈**: #71
- **브랜치**: #71

## 파일 링크
- **결정 (채택안 권위)**: docs/topics/STOCK-COMPENSATION-RECOVERY-DECISION.md
- 대안 본문 (이력): docs/topics/STOCK-COMPENSATION-RECOVERY-ALTERNATIVES.md
- 원본 사전 브리핑 (Baseline 0, 비교 baseline): docs/topics/STOCK-COMPENSATION-RECOVERY.md
- 라운드 산출물:
  - docs/rounds/stock-compensation-recovery/ (Round 0~2 — Baseline 0, plan-review-1)
  - docs/rounds/stock-compensation-recovery-alternatives/ (Round 1~6 — D 결정 후 Round 7 폐기)
- 플랜: docs/STOCK-COMPENSATION-RECOVERY-PLAN.md (10 태스크, Round 1 critic + domain finding 흡수, Round 2 둘 다 pass, plan-review-1 pass)

## 단계 진행
- [x] discuss
- [x] plan
- [x] plan-review
- [ ] execute
- [ ] review
- [ ] verify

## 직전 봉인

- **CLIENT-SIDE-LB** (Phase A LoadBalanced WebClient + Phase B OpenFeign, 2026-04-28 PR #70 머지) — `docs/archive/client-side-lb/`
  - round-robin 분산 검증: product-service 두 인스턴스 / Feign 7건+8건
  - `./gradlew test` 589 PASS (payment 358 / pg 207 / product 19 / user 1 / gateway 3 / eureka 1)
  - context 정합성 정리 동시 수행 — Phase B OpenFeign 도입을 STACK / INTEGRATIONS / TESTING / CONCERNS 에 반영, PITFALL 17 isTerminal 표기 정정, ARCHITECTURE dedupe 표 pg layer 보강
- **MSA-TRANSITION** (Phase 0~3.5 완료) — `docs/archive/msa-transition/`
- **PRE-PHASE-4-HARDENING** (3축 19태스크 + K1~K15) — `docs/archive/pre-phase-4-hardening/`
- **PHASE-4-READINESS-SWEEP** (Self-loop 정리, 2026-04-27) — `docs/archive/phase-4-readiness-sweep/`
- 봉인 시점 코드 상태: 4서비스(payment/pg/product/user) + Eureka + Gateway, Kafka 양방향 confirm, AMOUNT_MISMATCH 양방향 방어, dedupe two-phase lease + DLQ, client-side LB (LoadBalanced WebClient + OpenFeign)

## 다음 토픽 후보

- **`PHASE-4`** — Toxiproxy 8종 장애 주입 시나리오 + k6 시나리오 재설계 + 로컬 오토스케일러
- **`STOCK-COMPENSATION-OTHER-PATHS`** (후속) — `OutboxAsyncConfirmService.compensateStock` / `PaymentTransactionCoordinator.compensateStockCacheGuarded` 의 동일 silent loss 패턴 회복
