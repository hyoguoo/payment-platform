# 현재 작업 상태

> 최종 수정: 2026-06-20

## 활성 작업

- **주제**: STOCK-COMPENSATION-OTHER-PATHS (재고 보상 경로 정리)
- **단계**: execute
- **활성 태스크**: Task 2: 경로 1 보상 폐기 + 미복구 가시화 (Task 1 완료 — coordinator outbox 死 코드 4메서드 + canCompensateStock 가드 제거)
- **이슈/브랜치**: #106
- **파일**: docs/topics/STOCK-COMPENSATION-OTHER-PATHS.md / docs/STOCK-COMPENSATION-OTHER-PATHS-PLAN.md

## 재개 메모

(없음)

## 최근 완료

- **CAPACITY-AND-SCALEOUT** (결제 처리량 부하 측정 2페이즈 + scale-out 검증 — 페이즈 1 병목=Hikari 풀(knee 450), 페이즈 2 scale-out 기각(confirm 1.0×/e2e 1.3×, 병목이 풀→공유 DB 경합으로 이동), transactional.id 고유화 fencing 안전·정합 완벽, USL N≤2 한계. DLT `.dlq` C-12 해소 + `scripts/usl-fit.py` 신규, 2026-06-19, 이슈/브랜치 #104) — `docs/archive/capacity-and-scaleout/COMPLETION-BRIEFING.md`
- **K6-ASYNC-BENCHMARK** (비동기 결제 경로 k6 부하 측정 자산 + 병목 분석 — 비동기 흡수 입증 + 동기 confirm Hikari 풀 병목 처방(knee 150→300), DLT suffix 갭(C-12) 발견, 2026-06-15, 이슈/브랜치 #102) — `docs/archive/k6-async-benchmark/COMPLETION-BRIEFING.md`

전체 이력: `docs/archive/README.md` / 구 STATE 이력: `docs/archive/state-history-2026H1.md`
