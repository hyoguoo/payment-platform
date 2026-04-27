# 현재 작업 상태

> 최종 수정: 2026-04-27 — Phase 4 진입 전 self-loop sweep 봉인

## 활성 작업

- **CLIENT-SIDE-LB** — Phase A (LoadBalanced WebClient) → Phase B (OpenFeign) 순차 도입
  - discuss: `docs/topics/CLIENT-SIDE-LB.md`
  - plan: `docs/CLIENT-SIDE-LB-PLAN.md`
  - active task: **A6** (A5 완료 — docker-compose cross-service URL override 제거)

## 직전 봉인

- **MSA-TRANSITION** (Phase 0~3.5 완료) — `docs/archive/msa-transition/`
- **PRE-PHASE-4-HARDENING** (3축 19태스크 + K1~K15) — `docs/archive/pre-phase-4-hardening/`
- **PHASE-4-READINESS-SWEEP** (Self-loop 정리, 2026-04-27) — `docs/archive/phase-4-readiness-sweep/`
- 종결 기준: `./gradlew test` 578/578 PASS, working tree clean
- 봉인 시점 코드 상태: 4서비스(payment/pg/product/user) + Eureka + Gateway, Kafka 양방향 confirm, AMOUNT_MISMATCH 양방향 방어, dedupe two-phase lease + DLQ, 잔재 식별자 0건

## 다음 토픽 후보

- **`PHASE-4`** — Toxiproxy 8종 장애 주입 시나리오 + k6 시나리오 재설계 + 로컬 오토스케일러
- 진입 시 새 이슈 + 새 브랜치 + `docs/topics/PHASE-4.md` + `docs/PHASE-4-PLAN.md` 신규
