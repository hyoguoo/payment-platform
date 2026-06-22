# 현재 작업 상태

> 최종 수정: 2026-06-22

## 활성 작업

- **주제**: CONFIRM-APPROVED-RESEND-GAP (비동기 confirm APPROVED 재고 확정 재발행 갭 수정)
- **단계**: execute (plan 완료)
- **이슈/브랜치**: #112 / `#112`
- **활성 태스크**: Task 1 (재발행 관측 메트릭 컴포넌트)

## 재개 메모

**plan 완료 — PLAN.md 게이트 통과(reviewer·domain-expert R1 pass, minor 5건 반영). 다음: execute Task 1부터.**

- **확정된 접근**: 진입 가드의 종결(DONE) 분기에서 `status==DONE && message==APPROVED`(= 재배달 신호)면 `sendStockCommittedEvents` 재발행. RDB DONE 커밋 후 브로커 커밋 유실 시 재배달이 D7 가드에 막혀 재고 확정이 영구 유실되던 갭 복구. 수신측 product가 결정적 키(`derive(orderId,productId)`, message eventUuid 독립)로 멱등 흡수 → over-publish 무해/under-publish만 위험 비대칭 이용.
- **3 태스크 (PLAN.md SSOT)**: ① 재발행 관측 메트릭 컴포넌트(신규 `PaymentConfirmTerminalResendMetrics`, tdd), ② handle 종결 가드 재발행 + affected==0 dead branch 제거 + 단위 6종 신규/기존 2종 제거 + 통합 #3 실효 교체·#4 정상경로만·#5 green 유지(한 커밋, tdd·domain_risk), ③ 결정적 EOS 커밋 실패 주입 실증(임베디드 Kafka에 `commitTransaction` 1회 실패 시드, 복구 차감 1회 + 반복실패 DLQ·중복차감 0, domain_risk).
- **실증 방식 확정**: discuss "Toxiproxy 실증" → 통합 하니스가 임베디드 Kafka라 부적합 → **결정적 주입 시드**로 plan 확정(사용자 승인). topic.md도 동일 정정.
- **문서 정정(CONFIRM-FLOW §5·§16, CONCERNS L-1)**: ship 단계 context-update (execute 태스크 아님).
- **재개 방법**: 브랜치 `#112` 체크아웃 상태에서 execute Task 1부터. SSOT = `docs/CONFIRM-APPROVED-RESEND-GAP-PLAN.md`(태스크별 RED/GREEN/완료 기준) + `docs/topics/CONFIRM-APPROVED-RESEND-GAP.md`(설계) + 이슈 #112.
- **참고**: `docs/CONFIRM-APPROVED-RESEND-GAP-PLAN.md`, `docs/topics/CONFIRM-APPROVED-RESEND-GAP.md`, `PaymentConfirmResultUseCase.handle`, `PaymentConfirmGuardSkipMetrics`(메트릭 패턴), `PaymentEosIntegrationTest` #3·#4·#5.

## 최근 완료

- **RETRY-METRIC-CLEANUP** (payment 재시도 metric 잔재 정리 — payment_event.retry_count 死 metric 전면 제거: max_retry_reached 게이지 경로(게이지+maxRetryCount @Value+max-retry-count 설정키+countByRetryCountGreaterThanEqual) + 데이터 경로(V5 컬럼 DROP+도메인 필드+엔티티 매핑+응답 DTO 2종+admin HTML 2종+테스트) + 재시도 로깅 死 enum 2종. payment_outbox.retry_count/RetryPolicy/stuck_in_progress 보존 — PaymentEvent.retryCount 필드 제거 시 컴파일러가 PaymentOutbox 빌더와 자동 구분. V5 plain DROP COLUMN(MySQL IF EXISTS 미지원). 3태스크, 단위 450+통합 37 PASS, discuss R2·plan R2·ship 1R pass critical0/major0/minor1스킵, 2026-06-22, 이슈/브랜치 #110) — `docs/archive/retry-metric-cleanup/COMPLETION-BRIEFING.md`
- **CLEANUP-BATCH-E** (비동기 confirm 死 코드 정리 + Fake PG 멱등성 시뮬 — payment: RETRYING enum + 상태 머신 가드 브랜치 + toRetrying/markPaymentAsRetrying + 동반 死 RETRY_ATTEMPT 이벤트 체인 + PaymentOutbox.toFailed + 재고 캐시 단건 API 5종 + INVALID_STATUS_TO_FAILED + stock_decrement.lua 제거(retryCount/FAILED enum/INVALID_STATUS_TO_RETRY 보존). pg: main FakePgGatewayStrategy + test mock FakePgGatewayAdapter 양쪽 멱등 시뮬(이벤트+예외 이중 신호) + self-loop 중복 흡수 통합 테스트. 5태스크, payment 450+37 / pg 316+8 PASS, ship R2 pass critical0/major3처리/minor0, 2026-06-21, 이슈/브랜치 #108) — `docs/archive/cleanup-batch-e/COMPLETION-BRIEFING.md`

전체 이력: `docs/archive/README.md` / 구 STATE 이력: `docs/archive/state-history-2026H1.md`
