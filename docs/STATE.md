# 현재 작업 상태

> 최종 수정: 2026-08-25

## 활성 작업

### SHARED-RESOURCE-SCALEOUT — 공유 자원 동반 스케일아웃 측정

- 단계: **plan**
- 이슈 / 브랜치: #146
- 설계 문서: `docs/topics/SHARED-RESOURCE-SCALEOUT.md` (상단 요약 브리핑에 결정 요약)
- discuss 게이트 4라운드 종료 — findings 가 전부 문서 수정으로 닫히고 설계 변경이 없어 plan 으로 넘김
- 측정 시작 전 선행: Docker 메모리 20GB 상향 (현재 8.2GB), 장애 전환 검증을 별도 토픽으로 `docs/context/TODOS.md` 에 등재

## 재개 메모

### 별건 — 확정 요청에 멱등키가 없다

- 주문 접수에는 `Idempotency-Key` 헤더가 있으나 확정에는 없다. 재고 게이트 작업에서 주문 단위 선점으로 대응했고 헤더 도입은 여전히 범위 밖

### 별건 — 위키에 남은 끊긴 참조 2곳

- `architecture.md` 의 FCG 상세 링크가 `pg-confirm-flow` 를 가리키는데 그 문서에 FCG 설명이 없다
- `message-delivery-and-dedupe.md` 서두가 "DLQ 처리를 다루며"라고 하는데 본문에 DLQ 절이 없다

### 별건 — 블로그 포스팅 진행 상황

- 1편 **모놀리식 → 4서비스 분리** 완료 (`notes/blog`, `msa-transition-decisions.md`)
- 2편 **재시도 소진 이후 처리** 예정 — 1편 결말이 "payment→pg HTTP 조회 통로를 두지 않은 결정이 넉 달 뒤 문제가 됐다"로 넘어가게 써 뒀다. 소재는 `docs/archive/retry-exhaustion-disposition/`

## 최근 완료

- **STOCK-GATE-PER-PRODUCT** (2026-08-18) — docs/archive/stock-gate-per-product/COMPLETION-BRIEFING.md
- **PG-VENDOR-SIGNAL-CONSOLIDATION** (2026-08-14) — docs/archive/pg-vendor-signal-consolidation/COMPLETION-BRIEFING.md

전체 이력: `docs/archive/README.md` / 구 STATE 이력: `docs/archive/state-history-2026H1.md`
