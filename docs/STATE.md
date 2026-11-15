# 진행 상태

> 진행 중인 토픽의 목차다. 토픽마다 제목과 다섯 줄만 쓴다. 측정 결과, 판단 근거, 상세 재개 메모는
> 토픽 폴더(DESIGN의 "진행 기록", EXPERIMENTS)에 쓴다. 형식은 [workflow 스킬](../.agents/skills/workflow/SKILL.md#문서와-state)을 따른다.
> 범위 밖 할 일은 [TODOS](TODOS.md)에 둔다.

## 진행 중

### PAYMENT-THROUGHPUT — 결제 처리량 한계 측정과 병목 개선
- 규모·단계: 실험 · execute — T2 완료(2026-11-15). T3 결과 소비 동시성 3 채택 판단 대기. 동시성 3에서 사용자 품질 100/s 통과, 처리 용량 경계 140~160/s. 동시성 1 기준선: 처리 용량 120/s(10분), 사용자 품질 80/s 통과·100/s 미달
- 추적: [#162](https://github.com/hyoguoo/payment-platform/issues/162) · 브랜치 `#162`
- 다음: 동시성 3 채택 판단(개발자 확인) → 채택하면 기본값 1 → 3과 회귀 검증·검토 → pg 워커 동시 처리 상한 변경(2026-11-08 결정) → 비교 측정. 순서와 조건은 DESIGN 진행 기록의 2026-11-15 항목. "중단됐던 작업 진행" 같은 재개 요청이면 이 토픽을 먼저 재개한다
- 막힘: 없음. 회차는 `live-drill/throughput-split-measurements-20261106/`의 v3 드라이버와 `finish-round.py`로 돌린다(EXPERIMENTS 고정 조건, RUNBOOK "회차마다 지킬 것")
- 문서: [BRIEF](topics/PAYMENT-THROUGHPUT/BRIEF.md) · [DESIGN](topics/PAYMENT-THROUGHPUT/DESIGN.md) · [EXPERIMENTS](topics/PAYMENT-THROUGHPUT/EXPERIMENTS.md)

### CHECKOUT-RECEIPT-TRACKING — 서버 중단 후 요청 추적과 안전한 재요청
- 규모·단계: 큼 · discuss — 사전 브리핑 승인 대기. 범위(두 목표 함께)와 도달 증거 유실 시 미확인 허용은 확정
- 추적: 이슈·브랜치 미생성(discuss 진행 중)
- 다음: 남은 질문(실패 시도와 trace 연결, 구매 키 계약, DB 경합·커밋 불명 처리, 조회 API 필요성) 논의. 일반 요청의 trace 연속성 실행 검증
- 막힘: 없음
- 문서: [BRIEF](topics/CHECKOUT-RECEIPT-TRACKING/BRIEF.md)

## 최근 완료

- **AGENT-STRUCTURE-OVERHAUL** (2026-11-06) — [완료 요약](archive/agent-structure-overhaul/BRIEF.md#완료-요약)
- **CI-INTEGRATION-TEST-SHARDING** (2026-09-28) — docs/archive/ci-integration-test-sharding/COMPLETION-BRIEFING.md

전체 이력: `docs/archive/README.md` / 구 STATE 이력: `docs/archive/state-history-2026H1.md`
