# 현재 작업 상태

> 최종 수정: 2026-09-28

## 활성 작업

- **CI-INTEGRATION-TEST-SHARDING — payment 통합 테스트 shard 균형 재조정**
  - 단계: execute (T6 — 3개 runner 안에서 느린 테스트 클래스 분산, PR CI 실측)
  - 이슈: [#158](https://github.com/hyoguoo/payment-platform/issues/158), PR: [#159](https://github.com/hyoguoo/payment-platform/pull/159), 브랜치: `#158`
  - 계획: [docs/archive/ci-integration-test-sharding/CI-INTEGRATION-TEST-SHARDING-PLAN.md](archive/ci-integration-test-sharding/CI-INTEGRATION-TEST-SHARDING-PLAN.md)
  - 재개 지점: 첫 개선은 모든 게이트 통과(전체 7분 59초·8분 34초). T6 후보 seed의 전수 배정과 PR CI 완료 시간·테스트 수를 확인한다.

## 재개 메모

### 별건 — 확정 요청에 멱등키가 없다

- 주문 접수에는 `Idempotency-Key` 헤더가 있으나 확정에는 없다. 재고 게이트 작업에서 주문 단위 선점으로 대응했고 헤더 도입은 여전히 범위 밖

### 별건 — 위키에 남은 끊긴 참조 2곳

- `architecture.md` 의 FCG 상세 링크가 `pg-confirm-flow` 를 가리키는데 그 문서에 FCG 설명이 없다
- `message-delivery-and-dedupe.md` 서두가 "DLQ 처리를 다루며"라고 하는데 본문에 DLQ 절이 없다
- 정리 배치가 끝나면 이어서 처리하기로 했다 (별도 저장소라 같은 PR 에 못 넣는다)

### 별건 — 블로그 포스팅 진행 상황

- 1편 **모놀리식 → 4서비스 분리** 완료 (`notes/blog`, `msa-transition-decisions.md`)
- 2편 **재시도 소진 이후 처리** 예정 — 1편 결말이 "payment→pg HTTP 조회 통로를 두지 않은 결정이 넉 달 뒤 문제가 됐다"로 넘어가게 써 뒀다. 소재는 `docs/archive/retry-exhaustion-disposition/`

## 최근 완료

- **CLEANUP-BATCH-F** (2026-09-11) — docs/archive/cleanup-batch-f/COMPLETION-BRIEFING.md
- **AI-CHANGE-GUARDRAILS** (2026-09-08) — docs/archive/ai-change-guardrails/COMPLETION-BRIEFING.md

전체 이력: `docs/archive/README.md` / 구 STATE 이력: `docs/archive/state-history-2026H1.md`
