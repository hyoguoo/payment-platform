# 현재 작업 상태

> 최종 수정: 2026-07-28

## 활성 작업

- **주제**: 관리자 화면 가시성 확충 — 재시도 이력과 재고 (ADMIN-VISIBILITY)
- **단계**: ship
- **이슈/브랜치**: #126
- **파일**: docs/topics/ADMIN-VISIBILITY.md / docs/ADMIN-VISIBILITY-PLAN.md

## 재개 메모

execute 완료 (14태스크 전건, Task 14 는 ship 게이트에서 사용자 요청으로 추가된 `pg_outbox.attempt` 죽은 컬럼 제거). Task 1~13 라이브 검증 3항목 모두 실제 기동 환경에서 관찰 확인했고, 그 과정에서 찾은 화면 표시 결함 2건은 수정 후 재확인했다. 검증용 시드 데이터는 전건 삭제. Task 14 는 Flyway V7 로 컬럼 drop + 도메인/엔티티/메트릭 정리, 3서비스 전체 테스트·`:pg-service:integrationTest`(V1~V7 부팅 확인)·린트 4종 전건 통과. 로컬 도커 스택은 아직 기동 상태다 — 필요 없으면 `bash scripts/compose-up.sh --down`. ship 미착수.

## 최근 완료

- **DLQ-QUARANTINE-RECOVERY** (2026-07-11) — docs/archive/dlq-quarantine-recovery/COMPLETION-BRIEFING.md
- **DOCS-CONSISTENCY-OVERHAUL** (2026-07-07) — docs/archive/docs-consistency-overhaul/COMPLETION-BRIEFING.md

전체 이력: `docs/archive/README.md` / 구 STATE 이력: `docs/archive/state-history-2026H1.md`
