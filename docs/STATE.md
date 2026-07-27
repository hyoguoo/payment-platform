# 현재 작업 상태

> 최종 수정: 2026-07-28

## 활성 작업

- **주제**: 관리자 화면 가시성 확충 — 재시도 이력과 재고 (ADMIN-VISIBILITY)
- **단계**: execute
- **활성 태스크**: Task 6: payment 측 pg 전용 Feign client + 짧은 타임아웃 설정
- **이슈/브랜치**: #126
- **파일**: docs/topics/ADMIN-VISIBILITY.md / docs/ADMIN-VISIBILITY-PLAN.md

## 재개 메모

Task 5(pg 관리자 이력 조회 엔드포인트) 완료 — pg-service 최초의 `@RestController`(`PgAttemptHistoryController`, `GET /api/v1/confirmations/{orderId}/attempts`). 인바운드 포트 `PgAttemptHistoryQueryService` 를 `PgAttemptHistoryService` 가 구현. 이력 없는 주문은 404 아니라 `found=false` 담은 200 정상 응답. 응답 DTO 는 pg 내부 enum 을 문자열로 변환하고 결제키/원문 필드 없음. 13태스크 중 5개 완료.

## 최근 완료

- **DLQ-QUARANTINE-RECOVERY** (2026-07-11) — docs/archive/dlq-quarantine-recovery/COMPLETION-BRIEFING.md
- **DOCS-CONSISTENCY-OVERHAUL** (2026-07-07) — docs/archive/docs-consistency-overhaul/COMPLETION-BRIEFING.md

전체 이력: `docs/archive/README.md` / 구 STATE 이력: `docs/archive/state-history-2026H1.md`
