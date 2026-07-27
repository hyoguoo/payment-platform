# 현재 작업 상태

> 최종 수정: 2026-07-28

## 활성 작업

- **주제**: 관리자 화면 가시성 확충 — 재시도 이력과 재고 (ADMIN-VISIBILITY)
- **단계**: execute
- **활성 태스크**: Task 3: outbox 주문번호별 이력 행 조회 포트
- **이슈/브랜치**: #126
- **파일**: docs/topics/ADMIN-VISIBILITY.md / docs/ADMIN-VISIBILITY-PLAN.md

## 재개 메모

Task 2(`pg_outbox` 주문번호 조회 인덱스) 완료 — Flyway V6 로 `idx_pg_outbox_key_topic(\`key\`, topic)` 인덱스 추가, 발행 큐 폴링 인덱스와 용도 구분 주석 남김. 13태스크 중 2개 완료.

## 최근 완료

- **DLQ-QUARANTINE-RECOVERY** (2026-07-11) — docs/archive/dlq-quarantine-recovery/COMPLETION-BRIEFING.md
- **DOCS-CONSISTENCY-OVERHAUL** (2026-07-07) — docs/archive/docs-consistency-overhaul/COMPLETION-BRIEFING.md

전체 이력: `docs/archive/README.md` / 구 STATE 이력: `docs/archive/state-history-2026H1.md`
