# 현재 작업 상태

> 최종 수정: 2026-06-24 (DLQ-REACHABILITY execute Task 3 완료 → Task 4 대기)

## 활성 작업

- **주제**: DLQ-REACHABILITY (장애 지속 시 DLQ 도달 보장 — pg self-loop 무한 반복 + payment EOS 커밋 실패 유실)
- **단계**: execute
- **활성 태스크**: Task 4 (payment AfterRollbackProcessor 명시 연결 + EOS 커밋 실패 격리 metric + #7 전환)
- **이슈/브랜치**: #114

## 재개 메모

- plan 완료(2026-06-24). 플랜 SSOT: `docs/DLQ-REACHABILITY-PLAN.md` (상단 요약 브리핑 + 4태스크 + 결정 노트). 설계: `docs/topics/DLQ-REACHABILITY.md`.
- plan 게이트 통과: reviewer R2 pass(minor 1 반영), domain-expert R2 pass(minor 2 반영).
- Task 1 완료(2026-06-24): `pg_inbox.attempt` 컬럼(Flyway V5) + 도메인/엔티티 매핑 + `PgInboxRepository.incrementAttempt` 포트·JPA/Fake 구현. Fake의 `transitPendingToInProgress`가 attempt를 리셋하던 버그를 mutate 방식으로 수정해 attempt 보존. 316/316 PASS.
- Task 2 완료(2026-06-24): `PgInboxProcessor.resolveAttempt`가 `inbox.getAttempt()` 반환(하드코딩 1 제거). `PgVendorCallService` 재시도 분기에서 `incrementAttempt`를 같은 TX_B에서 호출(DLQ 분기는 미호출). 신규 `PgDlqReachMetrics`(`pg_retry_exhausted_quarantine_total`)를 `PgDlqService`의 QUARANTINED 전이 성공 지점(non-terminal CAS true)에 연결 — 멱등. 좀비 임계 60s ↔ updated_at 갱신 상호작용 확인 완료(의도된 동작, 변경 불필요 — 결론은 PLAN Task 2 완료 결과 참조). 324/324 PASS.
- Task 3 완료(2026-06-24): `PgSelfLoopRetryExhaustionIntegrationTest` 신규(Testcontainers MySQL + Kafka 비활성 + `PgEventPublisherPort` 테스트 더블로 self-loop 인메모리 재현). 운영 코드 변경 없음(Task 1·2로 충족). `RetryPolicy.computeBackoff`가 nextAttempt 기준이라 attempt 1→2→3→4 백오프 누적 실측 최악 97.5s — await 타임아웃 100s로 확정. QUARANTINED 전이 시점 in-flight 재시도 1개의 무해한 terminal reemit(범위 밖, 결정 노트)을 흡수하도록 안정화 단정 완화. 단위 324/324 + 통합 9/9 PASS.
- 남은 1태스크: T4 payment AfterRollbackProcessor 연결+EOS 커밋 실패 metric+#7 전환.
- 결정 노트 핵심: metric은 QUARANTINED 전이 지점(멱등, Task 2에서 구현 완료), attempt over-count는 안전 방향 수용(조기 격리, Task 3 통합 테스트로 종단 확인 완료), payment backoff는 `payment.kafka.after-rollback.backoff.*` 신규 키(기본 1000ms×5)로 #7 await 갱신.
- execute 중 확인 항목(남음): #7 await off-by-one 실측(Task 4).
- 다음: execute — Task 4 implementer dispatch (마지막 태스크, GREEN 커밋 안에서 stage를 ship으로 전환).

## 최근 완료

- **CONFIRM-APPROVED-RESEND-GAP** (비동기 confirm APPROVED 재고 확정 재발행 갭 — RDB DONE 커밋 후 EOS 발행 유실 시 재배달이 D7 종결 가드에 막혀 영구 유실되던 갭을 종결 가드 DONE+APPROVED 재발행으로 복구. 설계 SSOT가 믿던 affected==0 발행 분기는 dedupe+종결전이 원자 커밋이라 도달 불가 dead branch → 제거. product 결정적 키 멱등 흡수로 차감 1회(under-publish 위험/over-publish 무해). 신규 `PaymentConfirmTerminalResendMetrics`. 결정적 주입 시드(`commitTransaction` 1회 실패)로 #6 복구 실증; #7이 설계 S2 가설 반증 — EOS 커밋 실패는 AfterRollbackProcessor(9회·DLQ 미진입) 경로 + 재발행도 같은 EOS tx라 지속 실패 시 완전 유실 → TC-13-FOLLOW-7. 3태스크, 단위 457+통합 39 PASS, discuss R2·plan R1·ship pass critical0/major1 doc-sync/minor3, 2026-06-22, 이슈/브랜치 #112) — `docs/archive/confirm-approved-resend-gap/COMPLETION-BRIEFING.md`
- **RETRY-METRIC-CLEANUP** (payment 재시도 metric 잔재 정리 — payment_event.retry_count 死 metric 전면 제거: max_retry_reached 게이지 경로(게이지+maxRetryCount @Value+max-retry-count 설정키+countByRetryCountGreaterThanEqual) + 데이터 경로(V5 컬럼 DROP+도메인 필드+엔티티 매핑+응답 DTO 2종+admin HTML 2종+테스트) + 재시도 로깅 死 enum 2종. payment_outbox.retry_count/RetryPolicy/stuck_in_progress 보존 — PaymentEvent.retryCount 필드 제거 시 컴파일러가 PaymentOutbox 빌더와 자동 구분. V5 plain DROP COLUMN(MySQL IF EXISTS 미지원). 3태스크, 단위 450+통합 37 PASS, discuss R2·plan R2·ship 1R pass critical0/major0/minor1스킵, 2026-06-22, 이슈/브랜치 #110) — `docs/archive/retry-metric-cleanup/COMPLETION-BRIEFING.md`

전체 이력: `docs/archive/README.md` / 구 STATE 이력: `docs/archive/state-history-2026H1.md`
