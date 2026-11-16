# 진행 상태

> 진행 중인 토픽의 목차다. 토픽마다 제목과 다섯 줄만 쓴다. 측정 결과, 판단 근거, 상세 재개 메모는
> 토픽 폴더(DESIGN의 "진행 기록", EXPERIMENTS)에 쓴다. 형식은 [workflow 스킬](../.agents/skills/workflow/SKILL.md#문서와-state)을 따른다.
> 범위 밖 할 일은 [TODOS](TODOS.md)에 둔다.

## 진행 중

### PAYMENT-THROUGHPUT — 결제 처리량 한계 측정과 병목 개선
- 규모·단계: 실험 · execute — #165(결과 소비 동시성 3) 완료. #166(pg 명령 소비 동시성 3, 워커 동시 처리 상한 복원) 구현·검토·커밋 완료. 2026-11-16 측정으로 outbox 워커 8·inbox 워커 64 확정(160/s 부하 중 회복, user-quality 100/s 통과)
- 추적: [#162](https://github.com/hyoguoo/payment-platform/issues/162)(하위 [#165](https://github.com/hyoguoo/payment-platform/issues/165) · [#166](https://github.com/hyoguoo/payment-platform/issues/166)) · 브랜치 `#162`
- 다음: T5 ship(마무리 검토·문서 동기화·완료 요약). 푸시·PR은 개발자 요청 시. 처리 용량 경계(160/s 위)와 초기 약 50초 적체 원인은 아직 재지 않았다
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
