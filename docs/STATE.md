# 현재 작업 상태

> 최종 수정: 2026-06-22 (Task 3 완료 — execute 종료, ship 전환)

## 활성 작업

- **주제**: CONFIRM-APPROVED-RESEND-GAP (비동기 confirm APPROVED 재고 확정 재발행 갭 수정)
- **단계**: ship
- **이슈/브랜치**: #112 / `#112`
- **활성 태스크**: 없음 — 3 태스크 전부 완료, ship 단계 진입(context-update + 최종 브리핑 대기)

## 재개 메모

**execute 전체 완료(Task 1·2·3) — PLAN.md 3 태스크 체크 완료. 다음: ship(문서 정정 + COMPLETION-BRIEFING + archive).**

- **Task 1 완료**: `PaymentConfirmTerminalResendMetrics` 신규(`PaymentConfirmGuardSkipMetrics` 패턴 차용) — 카운터 `payment_confirm_terminal_resend_total`, 라벨 `status` 1개, eager 등록 DONE 1종. 단위 3 pass, 전체 453 pass 회귀 없음.
- **Task 2 완료**: `PaymentConfirmResultUseCase.handle` 종결 가드 분기에 DONE+APPROVED(재배달 신호) 재발행 추가(`sendStockCommittedEvents` + `terminalResendMetrics.record(DONE)`), affected==0 분기의 dead branch(APPROVED 발행) 제거. 단위 `PaymentConfirmResultUseCaseTest` 신규 6 + 기존 2 제거(457 pass), 통합 `PaymentEosIntegrationTest` #3 실효 교체(발행 책임 이전 단정) + #4 재배달 절 제거(37 pass). Rule 1: Task 1 생성자 변경에 따른 기존 7개 테스트 파일 시그니처 보정.
- **Task 3 완료**: `CommitFailureInjectingProducerPostProcessor`(신규, 테스트 스코프) — `ProducerFactory.addPostProcessor` 경유 동적 프록시로 `commitTransaction()` N회 결정적 실패 주입(운영 코드 무변경). 시나리오 #6(복구) 설계대로 증명 — 1차 실패 후 재배달이 종결 가드로 재진입, 차감 정확히 1회, terminalResend(DONE) +1. 시나리오 #7(반복실패 bound)은 PLAN 가설 2가지를 반증 — (1) DLQ recoverer가 아니라 컨테이너 디폴트 `DefaultAfterRollbackProcessor`(interval 0, maxAttempts 9, 단순 로그) 경로라 DLQ 미진입(단순 스킵), (2) 종결 가드 재발행도 같은 EOS 트랜잭션이라 반복 실패 시 발행 자체가 매번 abort돼 stock-committed가 "중복 0건"이 아니라 "완전 유실 0건". 실제 동작에 맞춰 테스트 재작성(`shouldExhaustAfterRollbackBackoffWithoutDlqAndNoDuplicateStock`). 후속 처방은 TC-13-FOLLOW-7로 TODOS.md 등재(`setAfterRollbackProcessor` 명시 연결 — 운영 코드 변경 필요, 범위 밖). 통합 39 pass(#1~#7), 단위 457 + 통합 39 전체 회귀 없음.

- **확정된 접근**: 진입 가드의 종결(DONE) 분기에서 `status==DONE && message==APPROVED`(= 재배달 신호)면 `sendStockCommittedEvents` 재발행. RDB DONE 커밋 후 브로커 커밋 유실 시 재배달이 D7 가드에 막혀 재고 확정이 영구 유실되던 갭 복구. 수신측 product가 결정적 키(`derive(orderId,productId)`, message eventUuid 독립)로 멱등 흡수 → over-publish 무해/under-publish만 위험 비대칭 이용.
- **3 태스크 (PLAN.md SSOT, 전부 완료)**: ① 재발행 관측 메트릭 컴포넌트, ② handle 종결 가드 재발행 + dead branch 제거, ③ 결정적 EOS 커밋 실패 주입 실증(시나리오 A 증명 / 시나리오 B 가설 반증 + 실제 동작 기준 재작성).
- **문서 정정 대상(ship 단계)**: CONFIRM-FLOW §5·§16, CONCERNS L-1 + Task 3 실증으로 새로 드러난 EOS AfterRollbackProcessor 갭(TC-13-FOLLOW-7) 반영.
- **재개 방법**: 브랜치 `#112` 체크아웃 상태에서 ship 단계 진입. SSOT = `docs/CONFIRM-APPROVED-RESEND-GAP-PLAN.md`(태스크별 완료 결과 기록 완료) + `docs/topics/CONFIRM-APPROVED-RESEND-GAP.md`(설계) + 이슈 #112.
- **참고**: `docs/CONFIRM-APPROVED-RESEND-GAP-PLAN.md`, `docs/topics/CONFIRM-APPROVED-RESEND-GAP.md`, `PaymentConfirmResultUseCase.handle`, `PaymentConfirmGuardSkipMetrics`(메트릭 패턴), `PaymentEosIntegrationTest` #1~#7, `docs/context/TODOS.md` TC-13-FOLLOW-7.

## 최근 완료

- **RETRY-METRIC-CLEANUP** (payment 재시도 metric 잔재 정리 — payment_event.retry_count 死 metric 전면 제거: max_retry_reached 게이지 경로(게이지+maxRetryCount @Value+max-retry-count 설정키+countByRetryCountGreaterThanEqual) + 데이터 경로(V5 컬럼 DROP+도메인 필드+엔티티 매핑+응답 DTO 2종+admin HTML 2종+테스트) + 재시도 로깅 死 enum 2종. payment_outbox.retry_count/RetryPolicy/stuck_in_progress 보존 — PaymentEvent.retryCount 필드 제거 시 컴파일러가 PaymentOutbox 빌더와 자동 구분. V5 plain DROP COLUMN(MySQL IF EXISTS 미지원). 3태스크, 단위 450+통합 37 PASS, discuss R2·plan R2·ship 1R pass critical0/major0/minor1스킵, 2026-06-22, 이슈/브랜치 #110) — `docs/archive/retry-metric-cleanup/COMPLETION-BRIEFING.md`
- **CLEANUP-BATCH-E** (비동기 confirm 死 코드 정리 + Fake PG 멱등성 시뮬 — payment: RETRYING enum + 상태 머신 가드 브랜치 + toRetrying/markPaymentAsRetrying + 동반 死 RETRY_ATTEMPT 이벤트 체인 + PaymentOutbox.toFailed + 재고 캐시 단건 API 5종 + INVALID_STATUS_TO_FAILED + stock_decrement.lua 제거(retryCount/FAILED enum/INVALID_STATUS_TO_RETRY 보존). pg: main FakePgGatewayStrategy + test mock FakePgGatewayAdapter 양쪽 멱등 시뮬(이벤트+예외 이중 신호) + self-loop 중복 흡수 통합 테스트. 5태스크, payment 450+37 / pg 316+8 PASS, ship R2 pass critical0/major3처리/minor0, 2026-06-21, 이슈/브랜치 #108) — `docs/archive/cleanup-batch-e/COMPLETION-BRIEFING.md`

전체 이력: `docs/archive/README.md` / 구 STATE 이력: `docs/archive/state-history-2026H1.md`
