# plan-domain-5

**Topic**: MSA-TRANSITION
**Round**: 5
**Persona**: Domain Expert

## Reasoning

Round 4 pass 승계 상태에서 Round 5 delta(Redis 캐시 차감 도입 + IdempotencyStore Redis 이관 + Reconciler 재고 대조 + StockCommitEvent + warmup)만 독립 관찰한다. 7개 신규 리스크(Redis 원자성 / Redis↔RDB 발산 / 멱등성 Redis 이관 / PG 가면 회귀 / 보상 dedupe 공유 / product→payment Redis 직접 쓰기 race / warmup skew) 모두 PLAN.md에서 태스크·테스트·수락 기준으로 1차 방어선이 구성돼 있다. **돈이 새는 경로(Overselling)는 Phase-1.4d Lua atomic DECR + INCR 복구 + `decrement_Concurrent_ShouldBeAtomicAndNeverGoNegative` 테스트로 0 보장**되고, **DECR 성공 후 PG 실패 경로는 Phase-1.7 QUARANTINED + Phase-1.9 Reconciler INCR 복원**으로 이중 방어가 완결된다. Redis down은 Phase-1.4d에서 예외 전파 → Phase-1.4 `WhenPgTimeout_ShouldTransitionToQuarantineWithoutOutbox` 주석에 명시적으로 "PG timeout·Redis down 등 시스템 장애" 케이스로 묶어 QUARANTINED로 처리 — **DB fallback 경로 없음이 설계 불변으로 유지**(재고 캐시가 진실 소스). IdempotencyStore Phase-0.1a는 현 `IdempotencyStoreImpl.cache.get(key, loader)` Caffeine 원자적 miss-once 계약을 SETNX로 대칭 이관하고 `getOrCreate_ConcurrentMiss_ShouldInvokeCreatorOnce`로 "같은 key 동시 진입 → creator 1회" 불변을 테스트에 박았다(TTL 보존도 별도 테스트). Reconciler는 "RDB를 진실로 Redis 재설정(역방향 금지)" 원칙과 "QUARANTINED DECR은 Reconciler 단독 복원(Phase-1.7 FCG 불변 준수)"을 양쪽 테스트(`scan_WhenStockCacheDivergesFromRdb_...`, `scan_WhenQuarantinedPaymentExists_ShouldRollbackDecrForEach`)에 명시. Phase-3.1c product→payment Redis 직접 SET은 `PaymentRedisStockPort` 포트 층을 두고 `commit_WhenRdbUpdateFails_ShouldNotSetRedis`로 트랜잭션 경계(RDB 실패 시 Redis 미쓰기)를 보호 — at-least-once + EventDedupeStore 공유로 다음 수신 시 재시도되므로 "eventually consistent" 가정과 충돌 없음. **R4까지 확정된 15개 domain risk(ADR-05/ADR-13/ADR-15/ADR-16/ADR-04/FCG/Strangler/감사 원자성 등) 전건 회귀 없음 확인**. 다만 **minor 공백 4건**이 남는다: (i) `StockCacheRedisAdapterTest#decrement_Concurrent_...`가 Mockito만으로는 Lua 원자성을 검증할 수 없다는 사실은 PLAN.md 주석에 명기됐으나 Testcontainers/embedded Redis 선택 자체는 execute로 미룸, (ii) Phase-1.12 warmup과 Phase-3.1 `StockSnapshotPublisher` 모두 `ApplicationReadyEvent` 훅이지만 **snapshot 발행 T와 warmup 시점 T+Δ 사이 누적된 stock-committed/stock-restored 이벤트의 offset replay 순서 계약**이 테스트에 없음(궁극적 Reconciler 보정으로 복원되지만 warmup 완료 시점의 stale 가능성 미명시 — 단 "warmup 완료 전 결제 차단" 원칙으로 사용자 노출은 없음), (iii) Phase-3.1c "RDB UPDATE 직후 Redis SET 전 payment DECR이 들어와 Reconciler가 발산으로 오판"하는 race window의 분류(`divergence_count` 증가가 정상/비정상인지)가 Phase-1.9 스펙에 구분되지 않음, (iv) Phase-1.5 `PgMaskedSuccessHandler`가 QUARANTINED로 분기할 때 Redis DECR 상태 유지 불변이 Phase-1.7 `process_WhenQuarantined_ShouldNotRollbackStockCache`에 귀속되지만 ADR-05 경로(PgMaskedSuccessHandler) 자체의 DECR 유지 테스트가 없음. 네 건 모두 **돈이 새는 경로가 아니라 운영 관측 또는 중복 방어선 보강** 수준이고 execute/테스트 단계에서 보강 가능하므로 critical/major로 승격하지 않는다. 따라서 **Round 4 pass 승계 → Round 5 pass**.

## previous_round_ref

`docs/rounds/msa-transition/plan-domain-4.md` (Round 4, decision=pass)

## Domain risk checklist

| 항목 | 판정 | 근거 |
|---|---|---|
| [신규 1-a] Redis Lua atomic DECR로 0 Overselling 보장 | **pass** | Phase-1.4d 스펙(PLAN.md:463) "Lua 스크립트로 `stock:{productId}` 키를 atomic DECR. DECR 결과 음수 → INCR 복구 후 false 반환 (Overselling 엄격 0)". 테스트 `decrement_WhenStockWouldGoNegative_ShouldRollbackAndReturnFalse`(PLAN.md:473), `decrement_Concurrent_ShouldBeAtomicAndNeverGoNegative`(PLAN.md:474), `rollback_ShouldIncrementStock`(PLAN.md:475). |
| [신규 1-b] DECR 성공 → PG 실패 → 복원 경로 | **pass** | Phase-1.4d는 DECR까지만 책임. Phase-1.7 FCG가 PG 실패 시 QUARANTINED 전이(PLAN.md:587-590) + `process_WhenQuarantined_ShouldNotRollbackStockCache`(PLAN.md:591)로 **즉시 INCR 금지 불변**. Phase-1.9 Reconciler `scan_WhenQuarantinedPaymentExists_ShouldRollbackDecrForEach`(PLAN.md:631)로 지연 INCR 복원. 이중 방어 경로 완성. |
| [신규 1-c] Redis down → 결제 진입 거절 (DB fallback 없음) | **pass** | Phase-1.4d `decrement_WhenRedisDown_ShouldPropagateException`(PLAN.md:476) + 실패 분기 정책(PLAN.md:466) "`decrement()` 예외(Redis down) → 호출 측이 PaymentEvent QUARANTINED 전이". Phase-1.4 `executePaymentConfirm_WhenPgTimeout_ShouldTransitionToQuarantineWithoutOutbox` 설명(PLAN.md:452) "PG timeout·Redis down 등 시스템 장애 시 QUARANTINED 전이 + outbox 미생성". **DB fallback 경로 명시적 부재**(재고 캐시가 단일 진실 소스) = 설계 불변 유지. |
| [신규 2-a] Redis ↔ RDB 발산 주기 감지 | **pass** | Phase-1.9 `scan_WhenStockCacheDivergesFromRdb_ShouldResetCacheToRdbValue`(PLAN.md:630). 대조 알고리즘 "`StockCachePort.current()` vs (RDB 재고 − PENDING/QUARANTINED PaymentEvent 합계)"(PLAN.md:618). Phase-1.11 `StockCacheDivergenceMetricsTest` + Phase-4.1 `stock-cache-divergence.sh`(PLAN.md:983)로 통합 검증. |
| [신규 2-b] RDB가 진실, Redis 재설정 (역방향 금지) | **pass** | PLAN.md:619 "**RDB를 진실**로 Redis `stock:{productId}` 재설정(SET). `payment.stock_cache.divergence_count` 카운터 +1." 역방향은 문서 전역에 명시 금지. Phase-4.1 redis-down.sh 수락 기준(PLAN.md:982) "Reconciler 재시작 후 RDB 기준 Redis 재설정". |
| [신규 2-c] QUARANTINED 결제 DECR 복원 귀속 | **pass** | Phase-1.9 Reconciler 단독(PLAN.md:620) "Reconciler 단독 책임, FCG 경로에서 즉시 복원 금지 — Phase-1.7 불변 준수". 테스트 `scan_WhenQuarantinedPaymentExists_ShouldRollbackDecrForEach`(PLAN.md:631). Phase-1.7 `process_WhenQuarantined_ShouldNotRollbackStockCache`(PLAN.md:591)와 대칭. |
| [신규 2-d] TTL 기반 DECR 자동 복원 vs 결제 타임아웃 race | **minor 공백** | Phase-1.9 `scan_WhenStockCacheKeyMissing_ShouldRestoreFromRdb`(PLAN.md:632)로 TTL miss → RDB 기준 재설정은 커버. 그러나 **결제 진행 중 TTL 만료로 인해 Reconciler가 RDB 기준으로 재설정해버리는 race**(결제 확정 직전 타이밍 충돌)의 수락 기준·테스트 없음. Phase-1.9 산출물이 "RDB 기준 = 재고 − PENDING/QUARANTINED 합계"이므로 IN_PROGRESS 미종결 결제는 PENDING으로 포함돼 수학적 보존 가능성은 있으나, 계약 수준 명시 없음. critical 아님 — 궁극적 정합성은 PENDING 포함 계산으로 방어됨. |
| [신규 3-a] IdempotencyStore Redis 이관 기존 TTL 동작 보존 | **pass** | Phase-0.1a 목적(PLAN.md:286) "기존 `IdempotencyProperties`(maximumSize, expireAfterWriteSeconds) 유지 — 저장소만 교체". 테스트 `getOrCreate_ShouldRespectExpireAfterWriteSeconds`(PLAN.md:295). 현 `IdempotencyStoreImpl`(src/.../idempotency/IdempotencyStoreImpl.java:24) Caffeine `expireAfterWrite`와 대칭. |
| [신규 3-b] SETNX로 miss 동시 진입 방어 | **pass** | Phase-0.1a 목적(PLAN.md:286) "동시 miss 진입 방어: Redis `SETNX`(또는 Lua SET NX PX)". 테스트 `getOrCreate_ConcurrentMiss_ShouldInvokeCreatorOnce`(PLAN.md:294) "동일 key 동시 진입(SETNX 경합): creator 1회만 호출 (중복 checkout 방지)". 현 `IdempotencyStoreImpl.cache.get(key, loader)` Caffeine 원자적 loader 호출 계약과 대칭. |
| [신규 3-c] 다중 인스턴스에서 정확히 1회 creator 실행 | **pass** | Phase-0.1a 목적(PLAN.md:286) "MSA 수평 확장 전제(horizontal stateless) 위반. Redis로 교체하여 인스턴스 간 공유 멱등성 보장". `getOrCreate_ConcurrentMiss_ShouldInvokeCreatorOnce`가 동일 프로세스 내 동시성뿐 아니라 Redis SETNX의 원자성 자체를 계약으로 검증. |
| [신규 4-a] Toss ALREADY_PROCESSED + NicePay 2201 회귀 없음 | **pass** | Phase-1.5 유지(PLAN.md:518-537). `PgMaskedSuccessHandlerTest` 5개 테스트, `TossPaymentGatewayStrategyWiringTest#confirm_WhenAlreadyProcessedPayment_ShouldInvokePgMaskedSuccessHandler`(PLAN.md:532), `TossPaymentErrorCode.ALREADY_PROCESSED_PAYMENT.isSuccess()` 수정(PLAN.md:536). R4까지 확정된 방어선 그대로 유지. |
| [신규 4-b] Phase-1.4 FCG 불변 영향 없음 | **pass** | Phase-1.4 테스트가 2개에서 3개로 분할(PLAN.md:450-453): (i) 원자성, (ii) 재고 부족 → FAILED, (iii) 시스템 장애(PG timeout·Redis down) → QUARANTINED. 재고 부족 FAILED는 "복원 불필요"(DECR 후 INCR 복구가 Phase-1.4d 내부에서 이미 실행됨). QUARANTINED 경로는 Phase-1.7 FCG 불변과 Phase-1.9 Reconciler 복원에 귀속. |
| [신규 4-c] QUARANTINED 결제 Redis DECR 잔존 불변 | **pass** | Phase-1.7 `process_WhenQuarantined_ShouldNotRollbackStockCache`(PLAN.md:591) 명시 "QUARANTINED 전이 시 `FakeStockCachePort.rollback()` 호출 없음 검증 (DECR 상태 Reconciler 위임)". Phase-1.7 목적 본문(PLAN.md:581) "QUARANTINED 전이 시 Redis stock cache DECR 상태를 즉시 INCR 복구하지 않는다". |
| [신규 4-d] PgMaskedSuccessHandler QUARANTINED 분기 시 Redis DECR 상태 유지 | **minor 공백** | Phase-1.5 `handle_Toss_AlreadyProcessed_WhenStatusIsNotDone_ShouldQuarantine`(PLAN.md:528), `handle_Nicepay_2201_VerifiesAmountBeforeDecision`(PLAN.md:529), `handle_WhenPgStatusCallFails_ShouldQuarantine`(PLAN.md:531)에서 QUARANTINED 분기는 검증. 그러나 **ADR-05 경로 자체**의 Redis DECR rollback 호출 여부(Phase-1.7은 FCG 경로 커버)에 대한 테스트 어서션이 명시되지 않음. 호출 주체가 동일 `PaymentTransactionCoordinator.executePaymentQuarantine...`이면 Phase-1.7 불변에 흡수되나, 계약 수준 명시 없음. critical 아님 — 같은 QUARANTINED 전이 coordinator 경로 공유로 흡수 기대 가능. |
| [신규 5-a] 보상 dedupe(StockRestore) + StockCommit dedupe 공유 | **pass** | Phase-3.3(PLAN.md:909) `stock.restore` UUID dedupe + Phase-3.1c `commit_DuplicateEventUuid_ShouldNoOp`(PLAN.md:891) 대칭. Phase-3.2가 `FakeEventDedupeStore` 공용 mock 제공(PLAN.md:874). Phase-3.1c 산출물 "EventDedupeStore dedupe 적용 (Phase-3.3 패턴)"(PLAN.md:898) + V3 마이그레이션(PLAN.md:899) "stock-committed dedupe 테이블 (또는 Phase-3.3의 event_dedupe 테이블 재사용 방침 명시)". |
| [신규 5-b] 두 consumer EventDedupeStore 패턴 공유 | **pass** | Phase-3.1c와 Phase-3.3 모두 `EventDedupeStore` port(Phase-3.1 산출물 PLAN.md:832)를 사용. 포트 위치(`product-service/.../port/out/EventDedupeStore.java`)가 단일 선언. |
| [신규 5-c] Phase-3.1c RDB UPDATE 실패 → Redis SET 미호출 = eventually consistent 가정 충돌 없음 | **pass** | Phase-3.1c `commit_WhenRdbUpdateFails_ShouldNotSetRedis`(PLAN.md:892) 명시 "RDB UPDATE 실패 시 Redis SET 미호출 검증 (정합성 보호)". at-least-once + dedupe로 다음 이벤트 수신 시 재처리됨. Reconciler(Phase-1.9)가 이후 주기에서 발산 감지 시 RDB 기준 재설정하므로 궁극적 복원 경로 2중. |
| [신규 6] product → payment Redis 직접 쓰기 race window | **minor 공백** | Phase-3.1c 테스트 `commit_ShouldUpdateRdbAndSetPaymentRedis`(PLAN.md:890)는 "RDB UPDATE + Redis SET 원자적 호출 검증"이지만 실제 두 연산은 별도 리소스이므로 시간 간극 존재. 이 간극에서 payment DECR이 들어오면 Redis는 "이전 값" → DECR 성공이 overselling이 아닐 수 있으나 RDB와 일시 괴리. Reconciler가 이를 정상 race window로 분류하는지 "비정상 발산"으로 `divergence_count` 증가시키는지 Phase-1.9 스펙에 구분 서술 없음. 안전성은 유지(오버셀링 없음)되나 **운영 메트릭 노이즈 리스크**. critical 아님 — execute 단계에서 "발산 지속 시간 임계값" 또는 "divergence 분류" 보강 가능. |
| [신규 7] warmup skew (snapshot T → warmup T+Δ) | **minor 공백** | Phase-1.12 테스트(PLAN.md:686-688)가 "snapshot 토픽 → Redis SET" 기본 동작만 커버. **snapshot 발행 이후 쌓인 stock-committed/stock-restored 이벤트의 replay offset 정렬**은 계약 수준 명시 없음. Phase-3.1 `StockSnapshotPublisher`와 Phase-1.12 `StockCacheWarmupService` 모두 `ApplicationReadyEvent` 기반이라 snapshot이 최신이라는 암묵적 가정이 존재. "warmup 완료 전 결제 차단" 원칙(PLAN.md:680)으로 사용자 노출은 없고, Phase-1.9 Reconciler 첫 틱에서 RDB 기준 재설정하므로 궁극적 복원 보장. critical 아님. |
| [R4 회귀] ADR-05 가면 방어(Phase-1.5) | **pass** | PLAN.md:518-537 스펙 유지. Phase-1.4 재고 캐시 도입이 Phase-1.5 PG 경로에 끼어들지 않음(ADR-05는 `PgStatusPort` 단독 귀속, ARCH R4 RESOLVED). |
| [R4 회귀] ADR-13 감사 원자성(Phase-1.4/1.4b/1.4c) | **pass** | Phase-1.4 스펙에 "**Phase-1.4c 분리 이후 재고 캐시 차감은 외부 호출이므로 '단일 TX' 가정은 결제 서비스 DB 내부(payment_event + payment_outbox)에만 적용**"(PLAN.md:444) 명시. `executePaymentConfirm_CommitsPaymentStateAndOutboxInSingleTransaction`(PLAN.md:450) 계약 유지. |
| [R4 회귀] ADR-15 FCG 불변(Phase-1.7) | **pass** | Phase-1.7 스펙(PLAN.md:581)에서 "FCG timeout·네트워크 에러·5xx 발생 시 재시도 래핑 없이 무조건 QUARANTINED 전이" 불변 유지 + Redis DECR 잔존 불변 추가. |
| [R4 회귀] ADR-16 보상 dedupe(Phase-3.3) | **pass** | PLAN.md:909-928 스펙 유지. TTL = consumer group offset retention + 1일(PLAN.md:912) 정량 기준 유지. `restore_AfterDedupeTtlExpiry_ShouldReprocessOnce`(PLAN.md:920) 유지. |
| [R4 회귀] Phase 1 보상 경로 원칙(discuss-domain-2 minor 이행구간) | **pass** | PLAN.md:359 "Phase 1 기간 `stock.restore` 보상은 결제 서비스 내부 동기 호출 유지(`InternalProductAdapter` 승계), 이벤트화는 Phase 3과 동시". 회귀 없음. |
| [R4 회귀] C-1/C-2/M-3/M-4/M-5 + ARCH R4 RESOLVED | **pass** | Phase-3.1b(PLAN.md:845-862), Phase-2.3b(PLAN.md:775-796), Phase-1.4 재정의(PLAN.md:441-456), PG 무상태(PLAN.md:268), 토픽 네이밍 규약(PLAN.md:756), `PaymentGatewayPort`=confirm/cancel / `PgStatusPort`=getStatus scope 재정의(PLAN.md:377) 전건 유지. |
| 중복 방지 체크 (existsByOrderId/eventUuid) | **pass** | Phase-3.3 UUID dedupe(PLAN.md:919) + Phase-3.1c `commit_DuplicateEventUuid_ShouldNoOp`(PLAN.md:891) + Phase-2.3 `consume_DuplicateCommand_ShouldDedupeByEventUuid`(PLAN.md:763) + Phase-2.3b `consume_DuplicateEvent_ShouldDedupeByEventUuid`(PLAN.md:788) + Phase-0.1a `getOrCreate_ConcurrentMiss_ShouldInvokeCreatorOnce`(PLAN.md:294) 5경로. |
| 재시도 안전성 (단위 + 통합) | **pass** | 단위: Phase-1.6 `relay_IsIdempotent`(PLAN.md:571), Phase-1.7 `process_RetryExhausted_CallsFcgOnce`(PLAN.md:590), Phase-3.3 `restore_WhenStockIncreaseFailsMidway_ShouldNotRecordDedupe`(PLAN.md:921), Phase-1.5b `publish_IsIdempotent_WhenCalledTwice`(PLAN.md:551). 통합: Phase-4.1 `stock-restore-duplicate.sh` + `fcg-pg-timeout.sh` + `redis-down.sh` + `stock-cache-divergence.sh`(PLAN.md:980-983) 4종. |
| 상태 전이 enum 소유권 | **pass** | Phase-1.3 `PaymentEventTest`·`PaymentOutboxTest` @EnumSource 커버(PLAN.md:431-437) 유지. Phase-1.4의 재고 캐시 차감 실패 분기(FAILED vs QUARANTINED)가 @EnumSource 기반 상태 전이 계약과 일관. |
| 금전 정확성 | **pass** | Phase-1.5 Toss/NicePay 대칭 금액 검증(PLAN.md:529-530) + Phase-1.4d Lua atomic DECR Overselling 0 + Phase-1.4 FAILED vs QUARANTINED 분기로 이중 결제·이중 복원 경로 봉쇄. |
| PII 노출·저장 | **n/a** | 본 토픽 비목표(§ 1-3). |
| discuss risk → 태스크 매핑 (orphan 없음) | **pass** | PLAN.md:1056-1070 추적 테이블 — R4 10건 + Round 5 신규 S-1/S-2/S-3/S-4 4건 추가. domain_risk=true 19개(PLAN.md:1077-1078). Round 5 orphan 없음. |

## delta

- **newly_passed**:
  - S-1 재고 캐시 차감 전략(Phase-0.1 + Phase-1.0 + Phase-1.2 + Phase-1.4 + Phase-1.4d + Phase-1.7) — Lua atomic DECR로 Overselling 0 보장 + Redis down→QUARANTINED + FAILED vs QUARANTINED 분기.
  - S-2 StockCommitEvent 발행(Phase-1.1 + Phase-1.5b + Phase-3.1c) — 결제 확정 → product RDB + payment Redis SET 경로 완결, dedupe 대칭.
  - S-3 Reconciler 확장(Phase-1.9 + Phase-1.12 + Phase-3.1 + Phase-4.1) — Redis↔RDB 대조, RDB 진실, QUARANTINED DECR 복원, warmup 경로.
  - S-4 멱등성 Redis 이관(Phase-0.1a) — Caffeine 원자적 miss-once 계약을 SETNX로 대칭 이관, MSA 수평 확장 대응.
  - Phase-1.7 QUARANTINED Redis DECR 잔존 불변 테스트(`process_WhenQuarantined_ShouldNotRollbackStockCache`) — FCG 경로에서 즉시 INCR 금지, Reconciler 단독 복원 경로 확립.
  - Phase-4.1 chaos 2종 추가(`redis-down.sh`, `stock-cache-divergence.sh`) — 통합 수준 Redis 장애 검증.
- **newly_failed**: []
- **still_failing**: []

## 도메인 관점 추가 검토

### 1. Redis Lua atomic DECR — Overselling 0 방어선 (신규 리스크 1)

Phase-1.4d는 재고 캐시 차감의 핵심 방어선이다. Lua 스크립트 "DECR → 음수 검사 → INCR 복구 → 결과 반환"(PLAN.md:479)이 Redis 단일 명령으로 실행돼 race window가 존재하지 않는다. `decrement_Concurrent_ShouldBeAtomicAndNeverGoNegative`(PLAN.md:474)가 동시 다중 DECR 시 재고 0 이하 불가를 검증하므로 Overselling 경로는 돈이 새는 경로 기준으로 closed. DECR 음수 → INCR 복구는 Lua 스크립트 내부에서 완료되므로 "DECR 성공 후 별도 복구 호출 누락" 경로도 없다.

**DECR 성공 → PG 실패 경로**: Phase-1.4d 범위 밖. Phase-1.7 `process_WhenQuarantined_ShouldNotRollbackStockCache`(PLAN.md:591)가 FCG QUARANTINED 전이 시 `StockCachePort.rollback()` 호출 없음을 보장하고, Phase-1.9 `scan_WhenQuarantinedPaymentExists_ShouldRollbackDecrForEach`(PLAN.md:631)가 Reconciler 단독으로 지연 INCR 복원한다. 이중 방어 경로 완성.

**Redis down 경로**: Phase-1.4d `decrement_WhenRedisDown_ShouldPropagateException`(PLAN.md:476) + 실패 분기 정책(PLAN.md:466) "Redis down → QUARANTINED". Phase-1.4 `executePaymentConfirm_WhenPgTimeout_ShouldTransitionToQuarantineWithoutOutbox` 본문이 "PG timeout·Redis down 등 시스템 장애"(PLAN.md:452)로 두 케이스를 묶어 QUARANTINED로 처리. DB fallback 경로는 문서에 명시되지 않고(재고 캐시 단일 진실원 원칙) Phase-4.1 `redis-down.sh` 수락 기준(PLAN.md:982) "Redis down 기간 중 Overselling 0 보장"으로 통합 검증. **설계 의도가 일관**.

**판정**: 태스크로 대응됨. 도메인 리스크 후퇴 없음.

### 2. Redis ↔ RDB 발산 감지와 RDB 진실 원칙 (신규 리스크 2)

Phase-1.9의 대조 알고리즘(PLAN.md:617-621)이 핵심이다. "`StockCachePort.current()` vs (RDB 재고 − PENDING/QUARANTINED PaymentEvent 합계)"는 **미종결 결제가 진행 중이어도 RDB 기준값을 수학적으로 보존**하는 공식이다. 즉 Redis DECR이 이미 발생했으나 PaymentEvent가 아직 PENDING/QUARANTINED인 상태에서도 "RDB − 미종결 합계"가 "Redis 현재값"과 일치해야 하므로, 결제 타임아웃 race에서 TTL이 만료되더라도 miss → RDB 기준 재설정(`scan_WhenStockCacheKeyMissing_ShouldRestoreFromRdb`, PLAN.md:632)이 이 공식을 따라 동작하면 결제 진행 중인 수량이 자동 보존된다.

**RDB 진실 원칙**: PLAN.md:619 "**RDB를 진실**로 Redis `stock:{productId}` 재설정(SET)". 역방향(Redis → RDB) 경로는 문서 전역에서 명시 금지. Phase-3.1c도 Redis SET은 RDB UPDATE 완료 **이후**에만 일어나는 one-way 경로(PLAN.md:884)로 설계돼 역방향 침투 없음.

**QUARANTINED DECR 복원 귀속**: Phase-1.9 Reconciler 단독(PLAN.md:620). Phase-1.7 `process_WhenQuarantined_ShouldNotRollbackStockCache`(PLAN.md:591)와 대칭으로 "FCG에서 즉시 복원 금지" 불변을 양쪽에 박았다.

**minor 공백**: 결제 진행 중 TTL 만료 race 시나리오의 **수락 기준 명시**가 없다. 수학적으로는 "RDB − PENDING/QUARANTINED 합계" 공식이 해결하지만, `scan_WhenStockCacheKeyMissing_ShouldRestoreFromRdb` 테스트가 이 시나리오를 명시적 assertion으로 다루지 않는다. execute 단계에서 "TTL miss 시점에 PENDING PaymentEvent가 존재하는 경우 RDB − PENDING 합계가 세트된다"를 명시한 테스트 variant를 추가하는 것을 권고.

**판정**: 태스크로 대응됨. minor 공백 1건(TTL vs 결제 타임아웃 race 테스트 변형 권고).

### 3. 멱등성 저장소 Redis 이관 (신규 리스크 3)

현 `IdempotencyStoreImpl`(src/main/java/com/hyoguoo/paymentplatform/payment/infrastructure/idempotency/IdempotencyStoreImpl.java:22-24)의 핵심 계약은 Caffeine `cache.get(key, loader)` 단일 호출 — 이는 "같은 key 동시 진입 시 loader 정확히 1회"라는 원자적 miss-once 계약을 보장한다(Caffeine 공식 문서 기반). Phase-0.1a는 이 계약을 Redis SETNX로 이관한다.

- **TTL 보존**: Phase-0.1a 목적(PLAN.md:286) "기존 `IdempotencyProperties`(maximumSize, expireAfterWriteSeconds) 유지". 테스트 `getOrCreate_ShouldRespectExpireAfterWriteSeconds`(PLAN.md:295). 현 `IdempotencyProperties.expireAfterWriteSeconds` 기본값 10초(src/.../IdempotencyProperties.java:16)가 Redis TTL로 이관.
- **SETNX 동시 진입 방어**: `getOrCreate_ConcurrentMiss_ShouldInvokeCreatorOnce`(PLAN.md:294). 설명 본문 "동일 key 동시 진입(SETNX 경합): creator 1회만 호출 (중복 checkout 방지)"가 Caffeine loader 1회 계약과 정확히 대칭.
- **다중 인스턴스 대응**: Phase-0.1a 목적 명시(PLAN.md:286) "현 `IdempotencyStoreImpl`(Caffeine 로컬 캐시)은 Phase-4.3 오토스케일러로 payment-service가 다중 인스턴스 확장될 때 stateful하여 중복 checkout을 허용. MSA 수평 확장 전제(horizontal stateless) 위반. Redis로 교체하여 인스턴스 간 공유 멱등성 보장". 구조적 돈 사고 경로(중복 checkout → 이중 결제 요청)를 직접 차단.

**판정**: 태스크로 대응됨. 도메인 리스크 후퇴 없음.

### 4. PG 가면 방어 회귀 없음 (신규 리스크 4)

Phase-1.5 스펙(PLAN.md:518-537)은 R4까지 확정된 상태 그대로 유지. 재고 캐시 도입이 PG 경로에 침투하지 않음은 두 가지 증거로 확인:

- **Port 분리**: ADR-05 경로의 PG 재조회(`PgMaskedSuccessHandler`)는 `PgStatusPort` 단독 귀속(ARCH R4 RESOLVED, PLAN.md:377). 재고 캐시 차감은 `StockCachePort` 단독 귀속(PLAN.md:373). 두 포트는 상호 독립.
- **Phase-1.4 테스트 분할**: 재고 캐시 도입으로 Phase-1.4 테스트가 "재고 부족 FAILED vs 시스템 장애 QUARANTINED" 2개로 분할됐으나(PLAN.md:451-452), PG 가면 방어 wiring(Phase-1.5 `TossPaymentGatewayStrategyWiringTest`, PLAN.md:532)과는 별도 경로.

**QUARANTINED 결제의 Redis DECR 잔존 불변 vs FCG 경로 검증**: Phase-1.7 `process_WhenQuarantined_ShouldNotRollbackStockCache`(PLAN.md:591)가 커버. Phase-4.1 `fcg-pg-timeout.sh`(PLAN.md:981)가 통합 수준 검증.

**minor 공백**: Phase-1.5 `PgMaskedSuccessHandler`가 QUARANTINED로 분기할 때 Redis DECR rollback 호출 여부에 대한 명시적 테스트 어서션이 없다. 실제 구현에서 `PgMaskedSuccessHandler`가 `PaymentTransactionCoordinator.executePaymentQuarantine...` 경로를 공유하면 Phase-1.7 불변에 흡수되지만, 계약 수준 명시는 부재. execute 단계에서 `PgMaskedSuccessHandlerTest`에 `FakeStockCachePort.rollback()` 미호출 어서션을 추가하는 것을 권고.

**판정**: 태스크로 대응됨. minor 공백 1건(`PgMaskedSuccessHandlerTest` 어서션 변형 권고).

### 5. 보상 이벤트 dedupe 공유 (신규 리스크 5)

Phase-3.3 `StockRestoreConsumer`(PLAN.md:924) + Phase-3.1c `StockCommitConsumer`(PLAN.md:897) 두 consumer 모두 `EventDedupeStore` port(Phase-3.1 산출물, PLAN.md:832) 공유. Phase-3.2 Fake도 공용(`FakeEventDedupeStore`, PLAN.md:874).

- **dedupe 대칭**: `commit_DuplicateEventUuid_ShouldNoOp`(PLAN.md:891) vs `restore_DuplicateEventUuid_ShouldNoOp`(PLAN.md:919) — 동일 UUID 2회 수신 → 1회 처리라는 계약을 양쪽이 동일하게 선언.
- **테이블 방침**: Phase-3.1c 산출물 "V3__add_stock_commit_dedupe.sql — stock-committed dedupe 테이블 (또는 Phase-3.3의 event_dedupe 테이블 재사용 방침 명시)"(PLAN.md:899). 분리/공유 선택지를 execute로 열어둠.
- **Phase-3.1c "RDB UPDATE 실패 → Redis SET 미호출" 트랜잭션 경계**: `commit_WhenRdbUpdateFails_ShouldNotSetRedis`(PLAN.md:892). at-least-once + dedupe 전제에서 다음 이벤트 재수신 시 재처리됨 — "eventually consistent" 가정과 충돌 없음. 재시도 후에도 실패하면 Reconciler(Phase-1.9)가 다음 주기에 RDB vs Redis 발산 감지로 curing 경로 제공.

**판정**: 태스크로 대응됨. 도메인 리스크 후퇴 없음.

### 6. product-service → payment Redis 직접 쓰기 race window (신규 리스크 6)

Phase-3.1c 산출물 본문(PLAN.md:884) "product-service가 RDB UPDATE 완료 직후 payment Redis에 직접 SET"은 두 연산이 **별개 리소스**이므로 시간 간극이 존재한다. 이 간극에서 payment DECR이 들어오면:

- Redis는 "이전 값" 상태 → DECR는 이전 값 기준으로 계산. 재고 부족이면 FAILED(정당한 거절), 충분하면 DECR 성공 후 결제 진행.
- Overselling은 발생하지 않음 — RDB의 "새로운 값"은 product-service가 이미 본 값으로 Redis SET되므로, 이전 값이 새로운 값보다 크지 않다면(재고 감소 방향) 문제 없음. 재고 증가 방향(admin 조정)에서만 "더 많이 팔 수 있는 기회 손실"이 있으나 이는 돈이 새는 경로 아님.

**Reconciler 발산 분류**: Phase-1.9 `scan_WhenStockCacheDivergesFromRdb_ShouldResetCacheToRdbValue`(PLAN.md:630)는 "다를 때 Redis 재설정 + divergence_count +1"이라는 단순 규칙으로 race window도 "발산"으로 오판할 수 있다. `payment.stock_cache.divergence_count` 카운터가 noise 증가(거짓 양성)할 가능성.

**minor 공백**: Phase-1.9 스펙이 "일시적 race 발산 vs 지속적 divergence"를 시간 임계값으로 구분하지 않음. execute 단계에서 "연속 N회 스캔에서 발산 지속 시에만 `divergence_count` 증가" 또는 "product-service 측 event publish → payment Redis SET을 outbox 패턴으로 트랜잭션 경계 안에 묶기" 중 하나를 선택하는 것을 권고. 단 **돈이 새는 경로가 아니므로 critical/major 승격 불필요**.

**판정**: 태스크로 대응됨. minor 공백 1건(race window 분류 보강 권고).

### 7. warmup skew (신규 리스크 7)

Phase-1.12 `StockCacheWarmupService`(PLAN.md:677-694)와 Phase-3.1 `StockSnapshotPublisher`(PLAN.md:838) 모두 `ApplicationListener<ApplicationReadyEvent>` 기반이다.

- **skew 경로**: product-service 기동 시점 T에 `stock-snapshot` 발행 → payment-service 재기동 T+Δ에 warmup이 snapshot을 replay. 이 Δ 구간에 발생한 `stock-committed`/`stock-restored` 이벤트는 Kafka 로그에 누적되므로 Kafka offset replay로 이론상 복원 가능하나, Phase-1.12 테스트(PLAN.md:686-688)는 snapshot 단일 replay만 커버하고 **이후 누적 이벤트의 offset 정렬**은 계약 수준 명시 없음.
- **사용자 노출 방어**: PLAN.md:680 "warmup 완료 전까지 결제 차감 요청은 차단(또는 RDB fallback)". 사용자가 stale Redis 값으로 결제하는 경로는 차단. Reconciler(Phase-1.9) 첫 틱에서 RDB 기준 재설정하므로 궁극적 복원 보장.

**minor 공백**: warmup 완료 선언 시점의 Redis 값이 stale할 수 있다는 사실 자체가 명시되지 않고, Reconciler 첫 틱까지의 gap이 warmup 완료 ~ 실제 정합성 복원 사이에 존재. 단 "warmup 완료 전 결제 차단" 원칙으로 사용자 노출은 없음. execute 단계에서 "warmup 이후 첫 Reconciler 스캔 완료까지 결제 차단 연장"을 고려할 수 있으나, RDB - PENDING 계산이 수학적으로 보존하므로 critical 아님.

**판정**: 태스크로 대응됨. minor 공백 1건(warmup skew 수학적 보존 계약 명시 권고).

### 8. R4까지 확정된 15개 domain risk 회귀 검토

R4 pass 승계 범위 전건을 Round 5 PLAN.md에서 재확인:

- **ADR-05 가면 방어 (Phase-1.5)**: PLAN.md:518-537 유지. 재고 캐시 도입이 PG 재조회 경로에 침투하지 않음(port 분리).
- **ADR-13 감사 원자성 (Phase-1.4/1.4b/1.4c)**: Phase-1.4 스펙이 "재고 캐시 차감은 외부 호출 → 단일 TX 가정은 payment_event + payment_outbox에만 적용"(PLAN.md:444)으로 명시적 재정의. `executePaymentConfirm_CommitsPaymentStateAndOutboxInSingleTransaction`(PLAN.md:450)이 이 계약 보호.
- **ADR-15 FCG 불변 (Phase-1.7)**: PLAN.md:581 "timeout → QUARANTINED 무조건" + "Redis DECR 상태 유지" 이중 불변. 회귀 없음.
- **ADR-16 보상 dedupe (Phase-3.3)**: PLAN.md:909-928 유지. TTL 정량 기준(offset retention + 1일) 유지. 추가로 Phase-3.1c StockCommit dedupe가 동일 패턴 재사용.
- **Phase 1 보상 경로 원칙 (discuss-domain-2 minor)**: PLAN.md:359 "Phase 1 기간 `stock.restore` 보상은 결제 서비스 내부 동기 호출 유지" 회귀 없음.
- **ADR-04 + RetryPolicy (Phase-1.6/4.1)**: 유지.
- **Strangler Fig 이중 발행 방지 (Phase-1.10)**: 유지.
- **ADR-21/ADR-22/ADR-23/ADR-12**: Phase-2.3b/Phase-3.1b/Phase-3.1/Phase-2.3 전건 유지.
- **C-1/C-2/M-3/M-4/M-5 + ARCH R4 RESOLVED**: 전건 유지.

**판정**: R4까지 확정된 도메인 리스크 **전건 회귀 없음**.

## Findings

- **[n/a]** S-1 재고 캐시 차감 전략 — Phase-0.1 + Phase-1.0 + Phase-1.2 + Phase-1.4 + Phase-1.4d + Phase-1.7로 Overselling 0 보장, Redis down→QUARANTINED 분기, FAILED vs QUARANTINED 분기 완결. 도메인 리스크 후퇴 없음.
- **[n/a]** S-2 StockCommitEvent 발행·소비 — Phase-1.1(port) + Phase-1.5b(발행) + Phase-3.1c(consumer + RDB UPDATE + Redis SET + dedupe)로 결제 → 상품 재고 확정 경로 완결.
- **[n/a]** S-3 Reconciler 확장 — Phase-1.9(Redis↔RDB 대조 + QUARANTINED INCR 복원 + TTL 복원) + Phase-1.12(warmup) + Phase-3.1(snapshot 발행) + Phase-4.1(chaos 2종) 4층 방어.
- **[n/a]** S-4 멱등성 Redis 이관 — Phase-0.1a SETNX로 Caffeine 원자적 miss-once 계약 대칭 이관. 다중 인스턴스 중복 checkout 경로 차단.
- **[minor]** Phase-1.9 `scan_WhenStockCacheKeyMissing_ShouldRestoreFromRdb` 테스트가 **TTL 만료 + PENDING 결제 진행 중 race 시나리오**의 수학적 보존("RDB − PENDING 합계")을 명시적 assertion으로 다루지 않음. 수학적으로는 공식이 보장하나 계약 수준 명시 부재. execute 단계에서 test variant 추가 권고.
- **[minor]** Phase-1.5 `PgMaskedSuccessHandlerTest` QUARANTINED 분기에서 `FakeStockCachePort.rollback()` 미호출 어서션이 없음. Phase-1.7 불변이 같은 coordinator 경로를 공유할 가능성이 높으나 계약 수준 명시 부재. execute 단계에서 어서션 추가 권고.
- **[minor]** Phase-3.1c RDB UPDATE → Redis SET 사이 race window가 Phase-1.9 Reconciler에 의해 "발산"으로 오판되면 `divergence_count` 거짓 양성 증가 가능. 돈이 새는 경로 아님(Overselling 미발생). execute 단계에서 "연속 N회 스캔 지속 시에만 카운터 증가" 또는 outbox 패턴 중 선택 권고.
- **[minor]** Phase-1.12 warmup skew — snapshot 발행 T와 warmup 시점 T+Δ 사이 누적 이벤트의 offset replay 계약 명시 없음. "warmup 완료 전 결제 차단" 원칙으로 사용자 노출은 없음. Reconciler 첫 틱에서 복원. execute 단계에서 skew 수학적 보존 계약 명시 권고.
- **[n/a]** R4까지 확정된 15개 domain risk(ADR-05/ADR-13/ADR-15/ADR-16/ADR-04/FCG/Strangler/감사 원자성/C-1/C-2/M-3/M-4/M-5/ARCH R4 RESOLVED/Phase 1 보상 경로 원칙) 전건 회귀 없음.
- **[n/a]** PII·보안 — 본 토픽 비목표(§ 1-3).

## JSON
```json
{
  "stage": "plan",
  "persona": "domain-expert",
  "round": 5,
  "task_id": null,
  "decision": "pass",
  "reason_summary": "Round 4 pass 승계. Round 5 신규 리스크 7건(Redis 원자성/Redis↔RDB 발산/멱등성 Redis 이관/PG 가면 회귀/보상 dedupe 공유/product→payment Redis 직접 쓰기 race/warmup skew) 모두 PLAN.md에 태스크·테스트·수락 기준으로 1차 방어선 구성됨. (1) Phase-1.4d Lua atomic DECR + INCR 복구 + decrement_Concurrent_ShouldBeAtomicAndNeverGoNegative로 Overselling 0 보장. DECR 성공 후 PG 실패 경로는 Phase-1.7 QUARANTINED(process_WhenQuarantined_ShouldNotRollbackStockCache) + Phase-1.9 Reconciler INCR 복원(scan_WhenQuarantinedPaymentExists_ShouldRollbackDecrForEach) 이중 방어. Redis down은 예외 전파 → QUARANTINED(decrement_WhenRedisDown_ShouldPropagateException, executePaymentConfirm_WhenPgTimeout_ShouldTransitionToQuarantineWithoutOutbox에 'PG timeout·Redis down 등 시스템 장애' 명기), DB fallback 없음이 설계 불변으로 유지. (2) Phase-1.9 대조 알고리즘 'RDB 재고 − PENDING/QUARANTINED 합계' 공식으로 RDB를 진실로 Redis 재설정(역방향 금지 명시), QUARANTINED DECR은 Reconciler 단독 복원, TTL miss → RDB 기준 재설정. (3) Phase-0.1a IdempotencyStore Caffeine cache.get(key,loader) 원자적 miss-once 계약을 SETNX로 대칭 이관, getOrCreate_ConcurrentMiss_ShouldInvokeCreatorOnce + getOrCreate_ShouldRespectExpireAfterWriteSeconds로 다중 인스턴스 수평 확장 대응. (4) Phase-1.5 ADR-05 가면 방어 유지, 재고 캐시 도입이 PgStatusPort 단독 귀속 경로에 침투하지 않음(ARCH R4 RESOLVED port 분리). (5) Phase-3.3 + Phase-3.1c가 EventDedupeStore 패턴 공유, FakeEventDedupeStore 공용. (6) Phase-3.1c commit_WhenRdbUpdateFails_ShouldNotSetRedis로 트랜잭션 경계 보호, at-least-once + dedupe로 eventually consistent 가정 유지. (7) Phase-1.12 warmup은 ApplicationReadyEvent 기반, warmup 완료 전 결제 차단 원칙으로 사용자 노출 없음 + Reconciler 첫 틱에서 복원. R4까지 15개 domain risk 전건 회귀 없음. minor 공백 4건(TTL vs 결제 타임아웃 race 테스트 변형, PgMaskedSuccessHandlerTest rollback 어서션, race window 분류, warmup skew 계약 명시)은 execute 단계에서 보강 가능한 수준이며 돈이 새는 경로 아님 — critical/major 승격 불필요.",
  "checklist": {
    "source": "_shared/checklists/plan-ready.md",
    "items": [
      {
        "section": "domain risk",
        "item": "discuss에서 식별된 domain risk가 각각 대응 태스크를 가짐",
        "status": "yes",
        "evidence": "PLAN.md:1056-1070 추적 테이블 14건 매핑(R4 10건 + Round 5 신규 S-1/S-2/S-3/S-4 4건). domain_risk=true 19개(PLAN.md:1077-1078). Round 5 orphan 없음. R4까지 확정된 15개 domain risk 전건 Round 5에서 유지."
      },
      {
        "section": "domain risk",
        "item": "중복 방지 체크가 필요한 경로에 계획됨",
        "status": "yes",
        "evidence": "Phase-0.1a getOrCreate_ConcurrentMiss_ShouldInvokeCreatorOnce(PLAN.md:294, SETNX), Phase-2.3 consume_DuplicateCommand_ShouldDedupeByEventUuid(PLAN.md:763), Phase-2.3b consume_DuplicateEvent_ShouldDedupeByEventUuid(PLAN.md:788), Phase-3.1c commit_DuplicateEventUuid_ShouldNoOp(PLAN.md:891), Phase-3.3 restore_DuplicateEventUuid_ShouldNoOp(PLAN.md:919) 5경로. 결제 서비스 측 이중 수신은 PaymentOutbox/PaymentEvent 종결 재진입 거부(rejectReentry) 기존 방어선 유지."
      },
      {
        "section": "domain risk",
        "item": "재시도 안전성 검증 태스크 존재 (단위 + 통합)",
        "status": "yes",
        "evidence": "단위: Phase-1.6 relay_IsIdempotent(PLAN.md:571), Phase-1.7 process_RetryExhausted_CallsFcgOnce(PLAN.md:590), Phase-1.5b publish_IsIdempotent_WhenCalledTwice(PLAN.md:551), Phase-3.3 restore_WhenStockIncreaseFailsMidway_ShouldNotRecordDedupe(PLAN.md:921), Phase-3.1c commit_WhenRdbUpdateFails_ShouldNotSetRedis(PLAN.md:892). 통합: Phase-4.1 chaos 8종 중 재시도·dedupe·Redis 4종(stock-restore-duplicate.sh / fcg-pg-timeout.sh / redis-down.sh / stock-cache-divergence.sh, PLAN.md:980-983)."
      }
    ],
    "total": 3,
    "passed": 3,
    "failed": 0,
    "not_applicable": 0
  },
  "scores": {
    "traceability": 0.96,
    "decomposition": 0.93,
    "ordering": 0.92,
    "specificity": 0.92,
    "risk_coverage": 0.94,
    "mean": 0.934
  },
  "findings": [
    {
      "severity": "n/a",
      "checklist_item": "S-1 재고 캐시 차감 전략 — Lua atomic DECR + Overselling 0 + Redis down→QUARANTINED",
      "location": "docs/MSA-TRANSITION-PLAN.md:460-482 (Phase-1.4d), :441-456 (Phase-1.4), :578-594 (Phase-1.7)",
      "problem": "도메인 리스크 후퇴 없음.",
      "evidence": "Phase-1.4d Lua 스크립트 'DECR → 음수 검사 → INCR 복구 → 결과 반환'(PLAN.md:479)이 Redis 단일 명령 원자성. decrement_Concurrent_ShouldBeAtomicAndNeverGoNegative(PLAN.md:474)가 동시 DECR 시 재고 0 이하 불가 검증. decrement_WhenRedisDown_ShouldPropagateException(PLAN.md:476) + 실패 분기 정책(PLAN.md:466)로 Redis down → QUARANTINED 경로 확정. Phase-1.4 executePaymentConfirm_WhenPgTimeout_ShouldTransitionToQuarantineWithoutOutbox 본문(PLAN.md:452)에 'PG timeout·Redis down 등 시스템 장애' 묶음 명시. DB fallback 경로 없음(재고 캐시 단일 진실원).",
      "suggestion": "해당 없음."
    },
    {
      "severity": "n/a",
      "checklist_item": "S-2 StockCommitEvent 발행·소비 — RDB UPDATE + Redis SET + dedupe 공유",
      "location": "docs/MSA-TRANSITION-PLAN.md:385-403 (Phase-1.1), :540-556 (Phase-1.5b), :881-904 (Phase-3.1c)",
      "problem": "도메인 리스크 후퇴 없음.",
      "evidence": "Phase-1.1 StockCommitEventPublisherPort 선언(PLAN.md:396). Phase-1.5b publish_IsIdempotent_WhenCalledTwice(PLAN.md:551)로 at-least-once outbox 멱등성. Phase-3.1c commit_ShouldUpdateRdbAndSetPaymentRedis + commit_DuplicateEventUuid_ShouldNoOp + commit_WhenRdbUpdateFails_ShouldNotSetRedis(PLAN.md:890-893) 3테스트로 정합성 보호. EventDedupeStore 패턴 Phase-3.3와 공유(PLAN.md:898).",
      "suggestion": "해당 없음."
    },
    {
      "severity": "n/a",
      "checklist_item": "S-3 Reconciler 확장 — Redis↔RDB 대조 + QUARANTINED INCR 복원 + warmup",
      "location": "docs/MSA-TRANSITION-PLAN.md:613-636 (Phase-1.9), :677-694 (Phase-1.12), :820-841 (Phase-3.1), :968-985 (Phase-4.1)",
      "problem": "도메인 리스크 후퇴 없음.",
      "evidence": "Phase-1.9 대조 알고리즘 'current() vs (RDB − PENDING/QUARANTINED 합계)'(PLAN.md:618), RDB 진실 원칙 + Redis 재설정(PLAN.md:619), QUARANTINED DECR Reconciler 단독 복원(PLAN.md:620), TTL miss → RDB 재설정(PLAN.md:621). scan_WhenStockCacheDivergesFromRdb_ShouldResetCacheToRdbValue + scan_WhenQuarantinedPaymentExists_ShouldRollbackDecrForEach + scan_WhenStockCacheKeyMissing_ShouldRestoreFromRdb(PLAN.md:630-632) 3테스트. Phase-1.12 warmup은 ApplicationReadyEvent 기반 + 'warmup 완료 전 결제 차단'(PLAN.md:680). Phase-4.1 redis-down.sh + stock-cache-divergence.sh(PLAN.md:982-983) 통합 검증.",
      "suggestion": "해당 없음."
    },
    {
      "severity": "n/a",
      "checklist_item": "S-4 IdempotencyStore Redis 이관 — SETNX + TTL 보존 + 다중 인스턴스 대응",
      "location": "docs/MSA-TRANSITION-PLAN.md:283-299 (Phase-0.1a)",
      "problem": "도메인 리스크 후퇴 없음.",
      "evidence": "현 IdempotencyStoreImpl.cache.get(key,loader) Caffeine 원자적 miss-once 계약(src/main/java/com/hyoguoo/paymentplatform/payment/infrastructure/idempotency/IdempotencyStoreImpl.java:22-24)을 SETNX로 대칭 이관. getOrCreate_ConcurrentMiss_ShouldInvokeCreatorOnce(PLAN.md:294) '동일 key 동시 진입(SETNX 경합): creator 1회만 호출'로 Caffeine loader 1회 계약과 대칭. getOrCreate_ShouldRespectExpireAfterWriteSeconds(PLAN.md:295)로 TTL 보존. 기존 IdempotencyProperties(maximumSize, expireAfterWriteSeconds) 유지(PLAN.md:286).",
      "suggestion": "해당 없음."
    },
    {
      "severity": "minor",
      "checklist_item": "Phase-1.9 TTL vs 결제 타임아웃 race 테스트 변형",
      "location": "docs/MSA-TRANSITION-PLAN.md:632 (scan_WhenStockCacheKeyMissing_ShouldRestoreFromRdb)",
      "problem": "결제 진행 중 TTL 만료 race 시나리오에서 Phase-1.9 대조 공식 'RDB − PENDING/QUARANTINED 합계'가 수학적으로 진행 중 수량을 보존하지만, 테스트가 'TTL miss 시점에 PENDING PaymentEvent가 존재하는 경우' variant를 명시적으로 다루지 않아 계약 수준 명시 부재.",
      "evidence": "Phase-1.9 대조 알고리즘(PLAN.md:618) '재고 − PENDING/QUARANTINED PaymentEvent 합계'는 수학적으로 진행 중 결제의 DECR 수량을 보존하지만, scan_WhenStockCacheKeyMissing_ShouldRestoreFromRdb(PLAN.md:632)는 '키 miss → RDB 기준값 SET' 기본 동작만 어서션.",
      "suggestion": "execute 단계에서 PaymentReconcilerTest에 scan_WhenStockCacheKeyMissing_WhilePendingPaymentsExist_ShouldRestoreWithPendingSubtracted 등 test variant 추가로 공식의 수학적 보존 계약을 assertion 수준에 명시 권고."
    },
    {
      "severity": "minor",
      "checklist_item": "PgMaskedSuccessHandlerTest QUARANTINED 분기 시 Redis DECR rollback 미호출 어서션",
      "location": "docs/MSA-TRANSITION-PLAN.md:527-531 (Phase-1.5 PgMaskedSuccessHandlerTest)",
      "problem": "Phase-1.7 FCG 경로의 process_WhenQuarantined_ShouldNotRollbackStockCache(PLAN.md:591)는 QUARANTINED 전이 시 StockCachePort.rollback() 미호출을 검증하지만, ADR-05 경로인 PgMaskedSuccessHandler가 QUARANTINED로 분기할 때 동일 불변이 유지되는지에 대한 명시적 테스트 어서션이 없음.",
      "evidence": "PgMaskedSuccessHandlerTest 5개 테스트(PLAN.md:527-531) 모두 상태 전이(DONE/QUARANTINED/no-op) 결과만 어서션하고 StockCachePort 호출 여부 어서션 없음. 실제 구현에서 PaymentTransactionCoordinator.executePaymentQuarantineWithOutbox 경로를 공유하면 Phase-1.7 불변에 흡수되나 계약 수준 명시 부재.",
      "suggestion": "execute 단계에서 handle_Toss_AlreadyProcessed_WhenStatusIsNotDone_ShouldQuarantine 등 QUARANTINED 분기 테스트에 'FakeStockCachePort.rollback() 미호출' 어서션 추가 권고."
    },
    {
      "severity": "minor",
      "checklist_item": "Phase-3.1c RDB UPDATE → Redis SET 사이 race window의 Reconciler 발산 분류",
      "location": "docs/MSA-TRANSITION-PLAN.md:881-904 (Phase-3.1c), :630 (Phase-1.9 scan_WhenStockCacheDivergesFromRdb_ShouldResetCacheToRdbValue)",
      "problem": "product-service RDB UPDATE 완료 ~ payment Redis SET 사이 시간 간극에 발생하는 일시적 발산을 Phase-1.9 Reconciler가 'divergence'로 오판해 payment.stock_cache.divergence_count 거짓 양성 증가 가능. Overselling은 발생하지 않으므로 돈이 새는 경로 아니나 운영 메트릭 노이즈.",
      "evidence": "Phase-3.1c 산출물 본문(PLAN.md:884) 'RDB UPDATE 완료 직후 payment Redis에 직접 SET'은 두 별개 리소스 연산. Phase-1.9 scan_WhenStockCacheDivergesFromRdb_ShouldResetCacheToRdbValue(PLAN.md:630)는 단순히 '다를 때 Redis 재설정 + divergence_count +1'로 일시적 race와 지속 divergence를 구분하지 않음.",
      "suggestion": "execute 단계에서 (a) '연속 N회 스캔에서 발산 지속 시에만 divergence_count 증가' 시간 임계값 도입 또는 (b) product-service 측 event publish + Redis SET을 outbox 패턴으로 트랜잭션 경계에 묶기 중 선택. 돈이 새는 경로 아니므로 현 plan 수준 방어 충분하나 운영 품질 향상 권고."
    },
    {
      "severity": "minor",
      "checklist_item": "Phase-1.12 warmup skew 수학적 보존 계약 명시",
      "location": "docs/MSA-TRANSITION-PLAN.md:677-694 (Phase-1.12), :820-841 (Phase-3.1 StockSnapshotPublisher)",
      "problem": "Phase-3.1 StockSnapshotPublisher 시점 T와 Phase-1.12 warmup 시점 T+Δ 사이 누적 stock-committed/stock-restored 이벤트의 offset replay 계약이 테스트 수준에 명시 없음. 'warmup 완료 전 결제 차단' 원칙(PLAN.md:680)으로 사용자 노출은 없고 Reconciler 첫 틱에서 복원되나 계약 수준 설명 부재.",
      "evidence": "Phase-1.12 테스트 3개(PLAN.md:686-688)는 snapshot 단일 replay 기본 동작만 커버 — 'snapshot 발행 이후 누적 이벤트의 offset 정렬' assertion 부재. 'warmup 완료 전 결제 차단'으로 사용자 노출은 없음.",
      "suggestion": "execute 단계에서 (a) warmup_WithSubsequentStockEvents_ShouldReplayInOffsetOrder test variant 추가 또는 (b) 'warmup 이후 첫 Reconciler 스캔 완료까지 결제 차단 연장' 운영 정책 명시 중 선택. 돈이 새는 경로 아님."
    },
    {
      "severity": "n/a",
      "checklist_item": "R4까지 확정된 15개 domain risk 회귀 없음",
      "location": "docs/MSA-TRANSITION-PLAN.md:1056-1070 (추적 테이블)",
      "problem": "Round 4 pass 판정 근거 Round 5에서 전건 유지.",
      "evidence": "ADR-05(Phase-1.5 유지), ADR-13(Phase-1.4 재고 캐시 분리 재정의로 TX 경계 명시화, :444), ADR-15(Phase-1.7 Redis DECR 잔존 불변 추가, :581), ADR-16(Phase-3.3 TTL 정량 기준 유지 + Phase-3.1c dedupe 공유), ADR-04/RetryPolicy(Phase-1.6 유지), Strangler(Phase-1.10 유지), C-1(Phase-3.1b 유지), C-2(Phase-2.3b 유지), M-3(Phase-1.4 재정의 유지), M-4(PG 무상태 유지), M-5(토픽 네이밍 유지), ARCH R4 RESOLVED(PaymentGatewayPort/PgStatusPort scope 유지), Phase 1 보상 경로 원칙(:359 유지).",
      "suggestion": "해당 없음."
    },
    {
      "severity": "n/a",
      "checklist_item": "PII·보안",
      "location": "docs/topics/MSA-TRANSITION.md § 1-3",
      "problem": "본 토픽 비목표.",
      "evidence": "§ 1-3에서 보안 범위 명시적 비목표 선언. Redis 저장 대상(stock:{productId} 정수, idem:{key} CheckoutResult)은 PII 미포함.",
      "suggestion": "해당 없음."
    }
  ],
  "previous_round_ref": "docs/rounds/msa-transition/plan-domain-4.md",
  "delta": {
    "newly_passed": [
      "S-1 재고 캐시 차감 전략(Phase-0.1 + Phase-1.0 + Phase-1.2 + Phase-1.4 + Phase-1.4d + Phase-1.7) — Lua atomic DECR로 Overselling 0 보장, Redis down→QUARANTINED, FAILED vs QUARANTINED 분기",
      "S-2 StockCommitEvent 발행·소비(Phase-1.1 + Phase-1.5b + Phase-3.1c) — 결제 확정 → product RDB + payment Redis SET 경로 완결, dedupe 대칭",
      "S-3 Reconciler 확장(Phase-1.9 + Phase-1.12 + Phase-3.1 + Phase-4.1) — Redis↔RDB 대조, RDB 진실, QUARANTINED DECR 복원, warmup",
      "S-4 IdempotencyStore Redis 이관(Phase-0.1a) — SETNX로 Caffeine 원자적 miss-once 계약 대칭 이관, 다중 인스턴스 수평 확장 대응",
      "Phase-1.7 QUARANTINED Redis DECR 잔존 불변 테스트(process_WhenQuarantined_ShouldNotRollbackStockCache) — FCG 즉시 INCR 금지, Reconciler 단독 복원",
      "Phase-4.1 chaos 2종 추가(redis-down.sh, stock-cache-divergence.sh) — 통합 수준 Redis 장애 검증"
    ],
    "newly_failed": [],
    "still_failing": []
  },
  "unstuck_suggestion": null
}
```
