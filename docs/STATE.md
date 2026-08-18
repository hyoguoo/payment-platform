# 현재 작업 상태

> 최종 수정: 2026-08-19

## 활성 작업

없음 (idle)

## 재개 메모

### 보류 — SHARED-RESOURCE-SCALEOUT (공유 자원 동반 스케일아웃 측정)

- 설계 문서: `docs/topics/SHARED-RESOURCE-SCALEOUT.md` (하단 "보류 결정" 절에 정정·게이트 findings·재개 조건 정리)
- discuss 1라운드 게이트까지 진행 — reviewer revise / domain-expert fail. findings 미반영 상태
- **보류 사유였던 선행 조건은 해소됐다** — 재고 선차감 게이트가 상품 단위로 분해돼 키가 상품 기준 해시태그로 묶였고, 한 상품의 키가 같은 슬롯에 모여 노드를 나눠도 스크립트가 원자적으로 돈다
- 재개 시: 재고·멱등 저장소 분산을 다시 범위에 넣고 findings 반영해 재게이트. 캐시 왕복이 주문당 1회에서 선점 1 + 상품 N + 해제 1로 늘어난 것(왕복당 약 5ms)이 측정 설계에 반영돼야 하고, 병목으로 드러나면 노드별 묶음 처리(`TODOS.md`)가 후보

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
