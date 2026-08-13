# 현재 작업 상태

> 최종 수정: 2026-08-14

## 활성 작업

- **주제**: 벤더 응답 신호 경로 단일화 (PG-VENDOR-SIGNAL-CONSOLIDATION)
- **단계**: execute
- **활성 태스크**: Task 9: 실패 대기열 처리에서 격리 직행을 관문 호출로 교체
- **이슈/브랜치**: #142
- **파일**: docs/topics/PG-VENDOR-SIGNAL-CONSOLIDATION.md / docs/PG-VENDOR-SIGNAL-CONSOLIDATION-PLAN.md

## 재개 메모

### Task 9 중단 — 관문 배선 WIP (구현자 세션 한도로 중간에 끊김)

**완료**: Task 1~8. 중복 승인 이벤트 갈래 제거, 관문 판정·가드·트랜잭션·지표 정비, 결제 쪽 부분 취소 재고 게이트, 모의 벤더 시나리오까지 커밋됐다.

**Task 9 현재 상태** — `PgDlqService` 프로덕션 코드만 고쳐진 채 `wip:` 커밋에 담겼다. RED 테스트 커밋이 없으므로 재개 시 TDD 순서를 다시 세운다.

- 메인 소스는 컴파일된다. **테스트 소스는 컴파일 실패** — `PgDlqServiceTest:55`, `PaymentConfirmDlqConsumerTest:55` 두 곳이 옛 6인자 생성자를 부른다
- **격리 도달 카운터가 아무 데서도 안 올라간다** — `PgDlqService` 에서 `PgDlqReachMetrics.record()` 호출이 빠졌는데 관문으로 아직 옮기지 못했다. PLAN Task 9 의 명시 항목이다
- **고아 상수 2개** 남음 — `EventType.PG_DLQ_PREEMPTED`, `EventType.PG_DLQ_QUARANTINED`
- **`PgSelfLoopRetryExhaustionIntegrationTest` 미확인** — 컴파일은 되지만 소진 사유를 `RETRY_EXHAUSTED` 로 단언한다. 배선 후 조회 실패 사유로 바뀌므로 런타임 실패 예정. 단위 테스트만 돌리면 안 잡힌다

**재개 방법**: `docs/PG-VENDOR-SIGNAL-CONSOLIDATION-PLAN.md` Task 9 본문의 "숨은 소비처 두 곳"과 완료 기준(`:pg-service:test :pg-service:integrationTest`)을 그대로 따른다. 남은 태스크는 Task 10(대기열 소비부터 종결까지 통합 검증), 11(소진 도달 알람 재정의), 12(접수대장 동시 삽입 검증).

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
