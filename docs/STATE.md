# 현재 작업 상태

> 최종 수정: 2026-08-18

## 활성 작업

- **주제**: STOCK-GATE-PER-PRODUCT (재고 선차감 게이트 상품 단위 분해)
- **단계**: execute (plan 완료)
- **활성 태스크**: Task 13 — 상품 서비스 격리 토픽과 에러 핸들러 (Task 1~12 완료. Task 12 는 `StockCommitUseCase.commitToRdb` 에 잔량 음수 가드를 추가했다 — `StockCommandUseCase` 가 쓰던 것과 같은 `ProductStockException.of(NOT_ENOUGH_STOCK)` 을 재고 row 미존재용 `IllegalStateException` 과 분리해 재사용했고(Task 13 이 재고 부족만 재시도 제외 목록에 등재해야 하므로), Javadoc 에 동시성 안전 근거(재고 확정 통지가 상품번호를 Kafka 파티션 키로 써서 같은 상품 커밋이 단일 컨슈머 스레드로 직렬화됨 — 이 나눔 기준을 바꾸면 lost update 재발)를 명시했다. **아직 이 예외를 받을 곳이 없다** — product-service 는 Kafka 에러 핸들러 커스터마이즈가 0건이라 지금은 기본 설정대로 재시도 후 로그만 남기고 메시지가 사라진다. `./gradlew :product-service:test` 60건 전체 pass(신규 2건), checkstyle·spotbugs 클린)
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

- **초과 판매를 막는 장치가 게이트 하나뿐이다.** 재고 확정 차감(`StockCommitUseCase.commitToRdb`)에 음수 가드가 없고, 검증이 있는 `Product.decrementStock` 은 프로덕션 호출처가 0 이다 — 활성 토픽 범위에 포함
- **상품 서비스에 소비 실패를 받을 곳이 없다.** 에러 핸들러 커스터마이즈가 0 건이라 기본 설정대로 재시도 후 로그만 남기고 메시지가 사라진다 — 활성 토픽에서 격리 토픽과 함께 신설
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
