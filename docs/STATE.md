# 현재 작업 상태

> 최종 수정: 2026-06-22

## 활성 작업

- **주제**: payment 재시도 metric 잔재 정리 (RETRY-METRIC-CLEANUP)
- **단계**: execute
- **활성 태스크**: Task 3: 재시도 로깅 死 enum 제거 (EventType.PAYMENT_RETRY_COUNT_INCREASED + PAYMENT_RETRY_START 제거)
- **이슈/브랜치**: #110
- **파일**: docs/topics/RETRY-METRIC-CLEANUP.md (discuss 완료) / docs/RETRY-METRIC-CLEANUP-PLAN.md

## 재개 메모

(없음)

## 최근 완료

- **CLEANUP-BATCH-E** (비동기 confirm 死 코드 정리 + Fake PG 멱등성 시뮬 — payment: RETRYING enum + 상태 머신 가드 브랜치 + toRetrying/markPaymentAsRetrying + 동반 死 RETRY_ATTEMPT 이벤트 체인 + PaymentOutbox.toFailed + 재고 캐시 단건 API 5종 + INVALID_STATUS_TO_FAILED + stock_decrement.lua 제거(retryCount/FAILED enum/INVALID_STATUS_TO_RETRY 보존). pg: main FakePgGatewayStrategy + test mock FakePgGatewayAdapter 양쪽 멱등 시뮬(이벤트+예외 이중 신호) + self-loop 중복 흡수 통합 테스트. 5태스크, payment 450+37 / pg 316+8 PASS, ship R2 pass critical0/major3처리/minor0, 2026-06-21, 이슈/브랜치 #108) — `docs/archive/cleanup-batch-e/COMPLETION-BRIEFING.md`
- **STOCK-COMPENSATION-OTHER-PATHS** (재고 보상 경로 정리 — 경로 2 + ADR-04 형제 outbox 死 코드 4메서드 제거, 경로 1 확정 진입 보상 폐기=재고 차감 유지로 과매도 0 + 미복구 가시화(StockRetentionMetrics). '롤백(토큰 DEL)'안은 confirm 동시성 직렬화 부재로 동시 confirm·롤백실패 과매도를 열어 게이트에서 기각. 통합 테스트 3종 과매도 0 회귀 가드. 단위 490+통합 37 PASS, 2026-06-21, 이슈/브랜치 #106) — `docs/archive/stock-compensation-other-paths/COMPLETION-BRIEFING.md`

전체 이력: `docs/archive/README.md` / 구 STATE 이력: `docs/archive/state-history-2026H1.md`
