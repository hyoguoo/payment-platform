# 현재 작업 상태

> 최종 수정: 2026-06-22 (CONFIRM-APPROVED-RESEND-GAP ship 완료)

## 활성 작업

- **주제**: 없음 (idle)
- **단계**: —

## 재개 메모

(없음)

## 최근 완료

- **CONFIRM-APPROVED-RESEND-GAP** (비동기 confirm APPROVED 재고 확정 재발행 갭 — RDB DONE 커밋 후 EOS 발행 유실 시 재배달이 D7 종결 가드에 막혀 영구 유실되던 갭을 종결 가드 DONE+APPROVED 재발행으로 복구. 설계 SSOT가 믿던 affected==0 발행 분기는 dedupe+종결전이 원자 커밋이라 도달 불가 dead branch → 제거. product 결정적 키 멱등 흡수로 차감 1회(under-publish 위험/over-publish 무해). 신규 `PaymentConfirmTerminalResendMetrics`. 결정적 주입 시드(`commitTransaction` 1회 실패)로 #6 복구 실증; #7이 설계 S2 가설 반증 — EOS 커밋 실패는 AfterRollbackProcessor(9회·DLQ 미진입) 경로 + 재발행도 같은 EOS tx라 지속 실패 시 완전 유실 → TC-13-FOLLOW-7. 3태스크, 단위 457+통합 39 PASS, discuss R2·plan R1·ship pass critical0/major1 doc-sync/minor3, 2026-06-22, 이슈/브랜치 #112) — `docs/archive/confirm-approved-resend-gap/COMPLETION-BRIEFING.md`
- **RETRY-METRIC-CLEANUP** (payment 재시도 metric 잔재 정리 — payment_event.retry_count 死 metric 전면 제거: max_retry_reached 게이지 경로(게이지+maxRetryCount @Value+max-retry-count 설정키+countByRetryCountGreaterThanEqual) + 데이터 경로(V5 컬럼 DROP+도메인 필드+엔티티 매핑+응답 DTO 2종+admin HTML 2종+테스트) + 재시도 로깅 死 enum 2종. payment_outbox.retry_count/RetryPolicy/stuck_in_progress 보존 — PaymentEvent.retryCount 필드 제거 시 컴파일러가 PaymentOutbox 빌더와 자동 구분. V5 plain DROP COLUMN(MySQL IF EXISTS 미지원). 3태스크, 단위 450+통합 37 PASS, discuss R2·plan R2·ship 1R pass critical0/major0/minor1스킵, 2026-06-22, 이슈/브랜치 #110) — `docs/archive/retry-metric-cleanup/COMPLETION-BRIEFING.md`

전체 이력: `docs/archive/README.md` / 구 STATE 이력: `docs/archive/state-history-2026H1.md`
