# 현재 작업 상태

> 최종 수정: 2026-06-21

## 활성 작업

- **주제**: CLEANUP-BATCH-E (비동기 confirm 死 코드 정리 + Fake PG 멱등성 시뮬)
- **단계**: execute
- **활성 태스크**: Task 1: RETRYING 상태 전이 死 코드 제거
- **이슈/브랜치**: #108
- **파일**: docs/topics/CLEANUP-BATCH-E.md / docs/CLEANUP-BATCH-E-PLAN.md

## 재개 메모

(없음)

## 최근 완료

- **STOCK-COMPENSATION-OTHER-PATHS** (재고 보상 경로 정리 — 경로 2 + ADR-04 형제 outbox 死 코드 4메서드 제거, 경로 1 확정 진입 보상 폐기=재고 차감 유지로 과매도 0 + 미복구 가시화(StockRetentionMetrics). '롤백(토큰 DEL)'안은 confirm 동시성 직렬화 부재로 동시 confirm·롤백실패 과매도를 열어 게이트에서 기각. 통합 테스트 3종 과매도 0 회귀 가드. 단위 490+통합 37 PASS, 2026-06-21, 이슈/브랜치 #106) — `docs/archive/stock-compensation-other-paths/COMPLETION-BRIEFING.md`
- **CAPACITY-AND-SCALEOUT** (결제 처리량 부하 측정 2페이즈 + scale-out 검증 — 페이즈 1 병목=Hikari 풀(knee 450), 페이즈 2 scale-out 기각(confirm 1.0×/e2e 1.3×, 병목이 풀→공유 DB 경합으로 이동), transactional.id 고유화 fencing 안전·정합 완벽, USL N≤2 한계. DLT `.dlq` C-12 해소 + `scripts/usl-fit.py` 신규, 2026-06-19, 이슈/브랜치 #104) — `docs/archive/capacity-and-scaleout/COMPLETION-BRIEFING.md`

전체 이력: `docs/archive/README.md` / 구 STATE 이력: `docs/archive/state-history-2026H1.md`
