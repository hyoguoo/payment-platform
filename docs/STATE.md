# 현재 작업 상태

> 최종 수정: 2026-08-18

## 활성 작업

- **주제**: STOCK-GATE-PER-PRODUCT (재고 선차감 게이트 상품 단위 분해)
- **단계**: execute (plan 완료)
- **활성 태스크**: Task 12 — 상품 서비스 재고 확정 음수 가드 (Task 1~11 완료. Task 11 은 `StockHoldReverter` 를 정적 유틸에서 `MeterRegistry` 를 주입받는 `@Component` 로 전환하고, 되돌리기 네 경로(확정 실패·격리 진입·관리자 종결·회수 판정)가 공유하는 `revertProductHold` 에 결과별 카운터 3종(`stock_hold_revert.result_total`, 태그 reverted/no_trace/already_done)과 이상 결과(흔적 없음·이미 처리됨)에만 WARN 로그를 추가했다. `StockHoldRecordRepository.countNoise()` 신규 + `infrastructure/scheduler/StockHoldRecoveryWorker` 신설 — 주기(기본 60초)·배치 크기(기본 100) 외부화, 실행마다 `stock_hold_recovery.run_total` 카운터, 호출 직후 `stock_hold_recovery.outstanding_count` 게이지. `StockHoldRecoveryWorkerBootTest` 가 DB/Kafka/Redis 없이 `SchedulerConfig` + worker 만 등록한 최소 컨텍스트로 `fixed-delay-ms` 를 짧게 오버라이드해 스케줄러가 실제로 반복 기동하는지 Awaitility 로 확인(활성화 플래그 누락 선례에 대한 회귀 방지). 쓰이지 않게 된 `lua/stock_compensation_atomic.lua` + raw 테스트 삭제(참조 0). `./gradlew :payment-service:test` 673건 전체 pass(신규 12건), checkstyle·spotbugs 클린)
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
