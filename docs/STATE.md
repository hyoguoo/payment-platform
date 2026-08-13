# 현재 작업 상태

> 최종 수정: 2026-08-13

## 활성 작업

- **주제**: 벤더 응답 신호 경로 단일화 (PG-VENDOR-SIGNAL-CONSOLIDATION)
- **단계**: execute
- **활성 태스크**: Task 2: 중복 승인 이벤트 타입·수신 메서드 삭제와 발행 건수 단언
- **이슈/브랜치**: #142
- **파일**: docs/topics/PG-VENDOR-SIGNAL-CONSOLIDATION.md / docs/PG-VENDOR-SIGNAL-CONSOLIDATION-PLAN.md

## 재개 메모

### 별건 — 위키에 남은 끊긴 참조 2곳

- `architecture.md` 의 FCG 상세 링크가 `pg-confirm-flow` 를 가리키는데 그 문서에 FCG 설명이 없다
- `message-delivery-and-dedupe.md` 서두가 "DLQ 처리를 다루며"라고 하는데 본문에 DLQ 절이 없다

### 별건 — 블로그 포스팅 진행 상황

- 1편 **모놀리식 → 4서비스 분리** 완료 (`notes/blog`, `msa-transition-decisions.md`)
- 2편 **재시도 소진 이후 처리** 예정 — 1편 결말이 "payment→pg HTTP 조회 통로를 두지 않은 결정이 넉 달 뒤 문제가 됐다"로 넘어가게 써 뒀다. 소재는 `docs/archive/retry-exhaustion-disposition/`

## 최근 완료

- **PG-DUPLICATE-APPROVAL-SETTLEMENT** (2026-08-13) — docs/archive/pg-duplicate-approval-settlement/COMPLETION-BRIEFING.md
- **PG-MESSAGE-DEDUPE-LAYER-REMOVAL** (2026-08-11) — docs/archive/pg-message-dedupe-layer-removal/COMPLETION-BRIEFING.md

전체 이력: `docs/archive/README.md` / 구 STATE 이력: `docs/archive/state-history-2026H1.md`
