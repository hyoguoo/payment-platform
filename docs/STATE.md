# 현재 작업 상태

> 최종 수정: 2026-08-14

## 활성 작업

- **주제**: 벤더 응답 신호 경로 단일화 (PG-VENDOR-SIGNAL-CONSOLIDATION)
- **단계**: execute
- **활성 태스크**: Task 12: 접수대장 유일 제약 동시 삽입 검증
- **이슈/브랜치**: #142
- **파일**: docs/topics/PG-VENDOR-SIGNAL-CONSOLIDATION.md / docs/PG-VENDOR-SIGNAL-CONSOLIDATION-PLAN.md

## 재개 메모

**완료**: Task 1~11. 중복 승인 이벤트 갈래 제거, 관문 판정·가드·트랜잭션·지표 정비, 결제 쪽 부분 취소 재고 게이트, 모의 벤더 시나리오, 실패 대기열의 관문 위임 배선, 대기열 소비부터 종결까지의 실제 DB 통합 검증, 소진 도달 알람 표현식 재정의까지 커밋됐다. 격리 도달 카운터는 `PgDlqService` 대신 `PgFinalConfirmationGate.handleQuarantined` 의 전이 반영 지점에서 증가한다. `DlqAppCounterRising` 의 pg 분기는 이제 관문 결과 카운터(`pg_final_confirmation_outcome_total`) 태그 6종 합산이다.

**남은 태스크**: Task 12(접수대장 동시 삽입 검증) — 이게 이 토픽의 마지막 태스크. 완료 시 STATE stage 를 ship 으로 전환.

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
