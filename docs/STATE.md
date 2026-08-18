# 현재 작업 상태

> 최종 수정: 2026-08-18

## 활성 작업

- **주제**: STOCK-GATE-PER-PRODUCT (재고 선차감 게이트 상품 단위 분해)
- **단계**: execute (plan 완료)
- **활성 태스크**: Task 16c — 수렴 체인과 정합 검증 (Task 1~16b 완료. Task 16b 는 동시 중복 확정·완료된 결제 재확정 시나리오는 이미 Task 6/7 이 실제 값 단정까지 검증해 뒀음을 확인하고 재검증만 했다. 남은 둘(거절 후 재시도 정합, 닫기 경합)은 신규 `StockGateConcurrentRetryIntegrationTest`로 다뤘다 — 거절 후 재시도는 1주기(직접 차감 → 거절 전용 되돌리기) 뒤 2주기(재시도 차감 → 조건부 되돌리기)를 실제로 태워 Redis 재고 값 자체가 원래대로 복원되는지 단정(기록 상태만 보는 검증은 되돌리기 표시가 앞 사이클에 남는 구멍을 못 잡는다). 닫기 경합은 `StockHoldReverter`에 되돌리기-닫기 사이 protected 훅 `beforeClose`를 신설해(운영 기본값 no-op, 이번 태스크의 유일한 신규 프로덕션 코드) 테스트가 그 창에 새 차감을 결정적으로 끼워 넣고, 뒤늦은 닫기가 옛 사이클 식별 값 때문에 반영되지 않는 것을 확인 — Task 5 의 사이클 식별 값 조건부 닫기가 실제 흐름에서 처음 검증됨. 두 테스트 모두 같은 주문·상품 조합에 `openHold`를 두 사이클 걸쳐 부르는데, 다른 재고 게이트 테스트가 쓰는 `BaseIntegrationTest`(ddl-auto: create-drop + Flyway 비활성)는 유일 제약이 실제로 서지 않아 중복 삽입이 나는 것을 발견해 Task 5 방식(Flyway 활성 + ddl-auto: validate)으로 전환. 닫기 경합 테스트는 `@DataJpaTest` 기본 트랜잭션이 스레드 간 락 경합을 만들어 Task 5 와 같은 `@Transactional(NOT_SUPPORTED)`로 우회)
- **이슈·브랜치**: #144
- **설계 문서**: `docs/topics/STOCK-GATE-PER-PRODUCT.md`
- **구현 플랜**: `docs/STOCK-GATE-PER-PRODUCT-PLAN.md` (22 태스크)
- **discuss 경과**: 게이트 9라운드. 키 전환 접근이 네 라운드 연속 critical 을 내다가, 스택을 새로 띄우며 전환하는 전제로 바꾸면서 통째로 단순해졌다. 이후 상태 모델(확정 예외·되돌리기 후보 판정·사이클 식별)과 동시성 경계(주문 단위 선점·해제 전략)를 다듬어 마무리
- **plan 경과**: 게이트 3라운드 양쪽 pass. 포트를 한 번에 바꾸면 호출부 넷이 동시에 깨지고 무계획한 임시 코드가 들어간다는 지적으로 **점진 전환**(상품 단위 메서드 추가 → 호출부 하나씩 이관 → 옛 메서드 제거)으로 재구성했다

## 재개 메모

### 보류 — SHARED-RESOURCE-SCALEOUT (공유 자원 동반 스케일아웃 측정)

- 설계 문서: `docs/topics/SHARED-RESOURCE-SCALEOUT.md` (하단 "보류 결정" 절에 정정·게이트 findings·재개 조건 정리)
- discuss 1라운드 게이트까지 진행 — reviewer revise / domain-expert fail. findings 미반영 상태
- **보류 사유**: 재고 캐시를 노드로 나누려면 주문당 상품 1개를 전제해야 하는데, 그건 측정 편의로 결제 도메인 보장을 깎는 순서다. 재고 선차감 게이트의 상품 단위 분해가 선행이어야 한다
- 재개 조건: 선행 토픽 완료 후 재고·멱등 저장소 분산을 다시 넣고 findings 반영해 재게이트

### 별건 — 활성 토픽 조사에서 나온 사실

- **확정 요청에 멱등키가 없다.** 주문 접수에는 `Idempotency-Key` 헤더가 있으나 확정에는 없다. 이번에는 주문 단위 선점으로 대응하고 헤더 도입은 범위 밖

### 별건 — 위키에 남은 끊긴 참조 2곳

- `architecture.md` 의 FCG 상세 링크가 `pg-confirm-flow` 를 가리키는데 그 문서에 FCG 설명이 없다
- `message-delivery-and-dedupe.md` 서두가 "DLQ 처리를 다루며"라고 하는데 본문에 DLQ 절이 없다

### 별건 — 블로그 포스팅 진행 상황

- 1편 **모놀리식 → 4서비스 분리** 완료 (`notes/blog`, `msa-transition-decisions.md`)
- 2편 **재시도 소진 이후 처리** 예정 — 1편 결말이 "payment→pg HTTP 조회 통로를 두지 않은 결정이 넉 달 뒤 문제가 됐다"로 넘어가게 써 뒀다. 소재는 `docs/archive/retry-exhaustion-disposition/`

## 최근 완료

- **PG-VENDOR-SIGNAL-CONSOLIDATION** (2026-08-14) — docs/archive/pg-vendor-signal-consolidation/COMPLETION-BRIEFING.md
- **PG-DUPLICATE-APPROVAL-SETTLEMENT** (2026-08-13) — docs/archive/pg-duplicate-approval-settlement/COMPLETION-BRIEFING.md

전체 이력: `docs/archive/README.md` / 구 STATE 이력: `docs/archive/state-history-2026H1.md`
