# 현재 작업 상태

> 최종 수정: 2026-09-04 (SHARED-RESOURCE-SCALEOUT ship 완료)

## 활성 작업

없음.

## 재개 메모

### 별건 — 확정 요청에 멱등키가 없다

- 주문 접수에는 `Idempotency-Key` 헤더가 있으나 확정에는 없다. 재고 게이트 작업에서 주문 단위 선점으로 대응했고 헤더 도입은 여전히 범위 밖

### 별건 — 위키에 남은 끊긴 참조 2곳

- `architecture.md` 의 FCG 상세 링크가 `pg-confirm-flow` 를 가리키는데 그 문서에 FCG 설명이 없다
- `message-delivery-and-dedupe.md` 서두가 "DLQ 처리를 다루며"라고 하는데 본문에 DLQ 절이 없다

### 별건 — 블로그 포스팅 진행 상황

- 1편 **모놀리식 → 4서비스 분리** 완료 (`notes/blog`, `msa-transition-decisions.md`)
- 2편 **재시도 소진 이후 처리** 예정 — 1편 결말이 "payment→pg HTTP 조회 통로를 두지 않은 결정이 넉 달 뒤 문제가 됐다"로 넘어가게 써 뒀다. 소재는 `docs/archive/retry-exhaustion-disposition/`

### 다음 후보 — L-19 수정

- `TODOS.md` 의 `CONFIRM-RESULT-NONRETRYABLE-STATUS` 부터. 확정 결과 거부 예외가 비재시도로 분류돼 있지 않아 건당 5초를 쓰고 파티션을 막는다
- 예외 타입을 통째로 비재시도로 묶으면 안 된다 — 에러코드 15종이고 CAS 충돌은 재시도가 유효하다
- 근본 해결은 `RECONCILER-SLOW-VS-STUCK` (리컨실러가 "느림"과 "멈춤"을 구분하지 못한다)이며 돈 경로 상태 전이 설계가 필요하다

## 최근 완료

- **SHARED-RESOURCE-SCALEOUT** (2026-09-04) — docs/archive/shared-resource-scaleout/COMPLETION-BRIEFING.md
- **STOCK-GATE-PER-PRODUCT** (2026-08-18) — docs/archive/stock-gate-per-product/COMPLETION-BRIEFING.md

전체 이력: `docs/archive/README.md` / 구 STATE 이력: `docs/archive/state-history-2026H1.md`
