# 현재 작업 상태

> 최종 수정: 2026-06-22

## 활성 작업

- **주제**: CONFIRM-APPROVED-RESEND-GAP (비동기 confirm APPROVED 재고 확정 재발행 갭 수정)
- **단계**: plan (discuss 완료)
- **이슈/브랜치**: #112 / `#112`

## 재개 메모

**discuss 완료 — 설계 문서 게이트 통과(reviewer·domain-expert R2 pass). 다음: plan.**

- **확정된 접근**: 진입 가드의 종결(DONE) 분기에서 `status==DONE && message==APPROVED`(= 재배달 신호)면 `sendStockCommittedEvents` 재발행. 첫 처리 때 RDB DONE 커밋 후 브로커 커밋 유실 시 재배달이 D7 가드에 막혀 재고 확정이 영구 유실되던 갭 복구. 수신측 product가 결정적 키(`derive(orderId,productId)`)로 멱등 흡수 → over-publish 무해/under-publish만 위험 비대칭 이용.
- **dead branch 처리**: 도달 불가한 affected==0 발행 분기(dedupe 마킹+종결 전이 원자 커밋이라 "dedupe됨+비종결" 불가) 제거 → 종결 가드로 재발행 일원화. 순서 뒤집기(발행 먼저)는 producer tx buffer라 원자성 경계 불변 → 미채택.
- **plan 핵심 태스크 후보**: ① handle 종결 가드 재발행 분기 + 관측 메트릭/로그(dedupe 미경유 silent 반복 발행 가시화), ② affected==0 분기 단순 skip화, ③ 단위 2종(`shouldSkipBusiness...ReturnsZero`) 교체 + `PaymentEosIntegrationTest` #3 실효 시나리오 교체(#5 QUARANTINED green 유지), ④ Toxiproxy 장애 주입 실증 하니스("재발행 반복 실패 → 5회 후 DLQ + 중복 차감 0"), ⑤ 문서 정정(CONFIRM-FLOW §5·§16, CONCERNS L-1)은 ship context-update.
- **검증 범위**: 단위 교체 + 통합 교체 + **Toxiproxy 실증 포함**(사용자 확정).
- **치명도**: 중간 — 돈 직접 손실 아님(결제 정상 DONE), 재고 오버셀 리스크. 정적 분석 기반 추론, 실증은 Toxiproxy 필요.
- **재개 방법**: 브랜치 `#112` 체크아웃 상태에서 plan 시작. SSOT = `docs/topics/CONFIRM-APPROVED-RESEND-GAP.md`(요약 브리핑 + 결정 테이블 + 장애 시나리오 + 검증 전략) + 이슈 #112.
- **참고**: `docs/topics/CONFIRM-APPROVED-RESEND-GAP.md`, 이슈 #112, `PaymentConfirmResultUseCase.handle`, `PaymentEventStatus.canApplyConfirmResult()`, `PaymentEosIntegrationTest` #3·#4·#5, `docs/context/CONFIRM-FLOW.md §5·§16`, `docs/context/CONCERNS.md L-1`.

## 최근 완료

- **RETRY-METRIC-CLEANUP** (payment 재시도 metric 잔재 정리 — payment_event.retry_count 死 metric 전면 제거: max_retry_reached 게이지 경로(게이지+maxRetryCount @Value+max-retry-count 설정키+countByRetryCountGreaterThanEqual) + 데이터 경로(V5 컬럼 DROP+도메인 필드+엔티티 매핑+응답 DTO 2종+admin HTML 2종+테스트) + 재시도 로깅 死 enum 2종. payment_outbox.retry_count/RetryPolicy/stuck_in_progress 보존 — PaymentEvent.retryCount 필드 제거 시 컴파일러가 PaymentOutbox 빌더와 자동 구분. V5 plain DROP COLUMN(MySQL IF EXISTS 미지원). 3태스크, 단위 450+통합 37 PASS, discuss R2·plan R2·ship 1R pass critical0/major0/minor1스킵, 2026-06-22, 이슈/브랜치 #110) — `docs/archive/retry-metric-cleanup/COMPLETION-BRIEFING.md`
- **CLEANUP-BATCH-E** (비동기 confirm 死 코드 정리 + Fake PG 멱등성 시뮬 — payment: RETRYING enum + 상태 머신 가드 브랜치 + toRetrying/markPaymentAsRetrying + 동반 死 RETRY_ATTEMPT 이벤트 체인 + PaymentOutbox.toFailed + 재고 캐시 단건 API 5종 + INVALID_STATUS_TO_FAILED + stock_decrement.lua 제거(retryCount/FAILED enum/INVALID_STATUS_TO_RETRY 보존). pg: main FakePgGatewayStrategy + test mock FakePgGatewayAdapter 양쪽 멱등 시뮬(이벤트+예외 이중 신호) + self-loop 중복 흡수 통합 테스트. 5태스크, payment 450+37 / pg 316+8 PASS, ship R2 pass critical0/major3처리/minor0, 2026-06-21, 이슈/브랜치 #108) — `docs/archive/cleanup-batch-e/COMPLETION-BRIEFING.md`

전체 이력: `docs/archive/README.md` / 구 STATE 이력: `docs/archive/state-history-2026H1.md`
