# Round 5 Architect Synthesis Log — STOCK-COMPENSATION-RECOVERY 대안

> 입력: Round 4 Critic 산출 (`round-4-critic.md` — A4-1 / B4-1 / D4-1 critical + 9 major + 3 minor) + Round 4 Domain 산출 (`round-4-domain.md` — DA4-1 critical + 12 major + 3 minor)
> 작업: critical 4건 흡수안 결정 + Round 4 흡수 후 ALTERNATIVES.md A/B/D 의 `### Round 4/5 refinement` 섹션 보강 + 사용자 결정용 `STOCK-COMPENSATION-RECOVERY-DECISION.md` 신규 작성
> 산출 위치: 본 파일 (결정 로그) + `docs/topics/STOCK-COMPENSATION-RECOVERY-ALTERNATIVES.md` (in-place A/B/D 보강) + `docs/topics/STOCK-COMPENSATION-RECOVERY-DECISION.md` (신규)

---

## 코드 사실 재확인 결과

Round 4 finding 의 코드 인용 핵심 라인 직접 Read 검증.

| 인용 | 재확인 결과 |
|---|---|
| `PaymentConfirmResultUseCase.handleFailed` 258-272 + 호출 순서 | 정확. line 266 `markPaymentAsFail(paymentEvent, reasonCode)` → line 268 `compensateStockCache(paymentEvent, reasonCode)`. handle 메서드 진입은 line 120 `@Transactional(timeout=5)` |
| `PaymentConfirmResultUseCase.compensateStockCache` 304-317 | 정확. for 루프 + try/catch 가 LogFmt.error 만 (swallow). `stockCachePort.increment(order.getProductId(), order.getQuantity())` 호출 line 307 |
| `PaymentConfirmResultUseCase.handle` 120 + processMessageWithLeaseGuard 136-144 | 정확. line 122 markWithLease → line 138 processMessage → line 139 extendLease (성공 시) / line 141 handleRemoveOnFailure (실패 시) |
| `PaymentEvent.java:22` plain POJO (no Spring annotation) | 정확. `@Builder(builderMethodName = "allArgsBuilder", buildMethodName = "allArgsBuild")` + `@AllArgsConstructor(access = AccessLevel.PRIVATE)` + `@Getter` 만. `@Component/@Service` 없음 — D4-1 의 핵심 사실 |
| `PaymentEvent` 도메인 메서드 8개 update 경로 (97-186) | 정확. execute / done / fail / quarantine / resetToReady / toRetrying / expire — A4-3 의 회귀 surface 정확 |

코드 사실 재확인 결과 Round 4 critical 4건 모두 인용 정확 — 흡수안 결정에 의심 없이 진입.

---

## Round 4 critical 흡수 결정

### A4-1 (Critic) + DA4-1 (Domain) — Candidate A 의 race window

**critical 본질 정리**:
- **A4-1**: `markPaymentAsFail` UPDATE TX 커밋 직후 + `compensateStockCache` 호출 직전 process crash → status=FAILED + compensation_status=NULL 영구 잔존. Reconciler 의 `WHERE compensation_status='PENDING'` 쿼리가 NULL 행 못 잡음 → silent under-restore.
- **DA4-1**: handle TX 안에서 markPaymentAsFail UPDATE 가 일어났지만 TX 커밋 전 + Redis INCR 일부 발생 후 process crash → TX rollback 으로 RDB 복원 + Redis INCR 일부 살아있음. dedupe `remove` → Kafka 재배달 → 같은 productId INCR 두 번 = silent over-restore. `compensation_state_version` 가드는 RDB UPDATE 측만 차단.

흡수안 검토:

| 흡수안 | 평가 | 사유 |
|---|---|---|
| (a) 호출 순서 변경 (compensate-first) | 폐기 | Candidate E 의 D-E1 회귀 — 결제 종결 시점이 보상 성공에 종속. 사용자 신호 (d) 와 정면 충돌 |
| (b) handleFailed 진입 즉시 markStockCompensationPending 디폴트 박기 | **채택** | 호출 순서 보존 + status 전이 0 + 같은 TX 묶음 가능. A4-1 NULL-edge 해소 + DA4-1 부분 해소 |
| (c) 두 단계 lease 패턴 모방 (markPaymentAsFail 시 stock-compensation lease 도 박기) | 폐기 | Redis 의존 추가 — 신규 인프라 부담 |

**채택안 (b)**: handleFailed 진입 즉시 PaymentCommandUseCase.markStockCompensationPending 호출. markPaymentAsFail 과 같은 `@Transactional(timeout=5)` TX 묶음. compensateStockCache 호출 순서 그대로. 모든 INCR 성공 시 catch 외부에서 markStockCompensationDone 호출.

**잔여 위험 정직 명시**: handle TX 가 markPaymentAsFail + markStockCompensationPending + Redis INCR 일부 (실패) 를 같은 TX 시작에 일으킨 후 mid-INCR crash 시 TX rollback + dedupe remove + Kafka 재배달 → 재진입 시 같은 productId 가 또 INCR. PaymentOrder.compensated_at 컬럼은 PHASE2 deferred — 본 토픽 단독 채택 시 DA4-1 over-restore 잔여. 발생 빈도는 process crash × mid-INCR 위치 확률로 낮지만 발산 방향 위험.

### B4-1 — Candidate B 의 회귀 surface

**critical 본질 정리**: claimToInFlight 시그니처 변경의 회귀 surface 가 Round 3 추정 4 layer → 6 layer + 6 테스트 파일 + PaymentConfirmEvent 도메인 이벤트 변경. 본 토픽 핵심 가드 (정상 결제 처리에 회복 layer 가 영향 0) 와 정면 충돌.

흡수안 검토:

| 흡수안 | 평가 | 사유 |
|---|---|---|
| (a) 시그니처 변경 폐기 + 다른 분리 방식 (별 outbox repo / 별 워커 / 별 테이블) | 폐기 | Candidate 0 화 — 본 candidate 의 핵심 가치 (payment_outbox 한 테이블만 보면 됨) 손실 |
| (b) 시그니처 변경 명시 인정 + 회귀 surface 6 layer 정량 비용으로 사용자 제시 | partial | 정직 인정만으로는 사용자 신호 (d) 의 위협 정도 그대로 |
| (c) PaymentConfirmEvent payload 보존 + DB 측만 변경 (claimToInFlight 4 인자) | **채택** | 도메인 layer 침투 회피 + 회귀 surface 6 → 5 layer 감축 |

**채택안 (c)**: PaymentConfirmEvent payload 그대로 (orderId 보존). claimToInFlight 시그니처는 `(orderId, messageType, dedupKey, inFlightAt)` 4 인자로 변경. JPA `@Modifying UPDATE` 쿼리 4 인자. OutboxRelayService.relay 시그니처는 `relay(orderId)` 그대로지만 내부에서 messageType 분기 + Strategy 패턴 도입.

**잔여 위험 정직 명시**: (c) 채택 후에도 OutboxRelayService 내부 분기 + Strategy dispatch overhead + claimToInFlight 4 인자 UPDATE 쿼리는 그대로. happy path (CONFIRM_COMMAND) 도 분기 진입. **본 candidate 의 가장 큰 위협 그대로** — 사용자 confirm 필요. JPQL `COALESCE` NULL 처리가 MySQL UNIQUE NULL 중복 허용과 정합되는지 plan 단계 검증 필요.

### D4-1 — Candidate D 의 AOP 미작동

**critical 본질 정리**: 옵션 D2 의 핵심 가정 (`paymentEvent.markStockCompensateFailed` 가 PaymentEvent (plain POJO) 의 도메인 메서드 + 별 Aspect `@Around` 가로채기) 이 Spring AOP proxy-based 메커니즘에 작동 불가. PaymentEvent.java:22 가 plain POJO. 옵션 D2 채택 시 별 Aspect 발화 0 → payment_history INSERT 0 → 회복 layer 자체 작동 안 함.

흡수안 검토:

| 흡수안 | 평가 | 사유 |
|---|---|---|
| (a) 옵션 D2 폐기 + 별 application 빈 wrapper 도입 | **채택** | application 빈 메서드라 Spring AOP 가로채기 작동 OK. 도메인 layer 침투 0 |
| (b) AOP 대신 명시 호출만으로 audit append (DomainEventLoggingAspect 패턴 폐기) | 폐기 | audit 자동성 손실 — 본 candidate 의 핵심 가치 (audit-driven 회복 SoT) 와 정면 충돌 |
| (c) Candidate D 폐기 | 폐기 | 사용자 선택지 축소 |

**채택안 (a)**: PaymentCommandUseCase 에 wrapper 메서드 2종 (`markStockCompensateFailed` / `markStockCompensateRecovered`) 추가. PaymentEvent 의 도메인 메서드는 status 전이 0 + simple setter 만. 별 Aspect (`StockCompensationLoggingAspect`) + 별 어노테이션 (`@PublishStockCompensationEvent`) 그대로 — 단 가로채기 대상이 application 빈 wrapper 메서드.

**잔여 위험 정직 명시**: (a) 채택 후 본 candidate 가 옵션 D1 (기존 Aspect 보강) 과 의미상 거의 같음 — 단 별 Aspect / 별 어노테이션 분리는 유지 → PITFALLS #1 회피. 차별점 약화: Candidate A 와 application 빈 layer 변경 동등 (PaymentCommandUseCase 에 wrapper 추가). 차별점은 회복 SoT (audit row vs compensation_status 컬럼) 만.

---

## 흡수 후 3 candidate 비교 차원 (Architect 관점)

| 차원 | Candidate A | Candidate B | Candidate D |
|---|---|---|---|
| 사용자 신호 (a/b/c/d) 회피 | 4/4 fit | (a) 부분 위반 | (d) 부분 위반 |
| happy path 영향 0 가드 | partial fit (FAILED 경로 +1~2ms) | 위협 (relay 분기 + Strategy + claimToInFlight 4 인자 UPDATE) | fit (기존 Aspect 변경 0) |
| 회귀 surface | 작음 — JPA `@Version` + 도메인 8 + wrapper 3 | 중간~큼 — claimToInFlight 5 layer + Strategy + 5 테스트 파일 | 중간~큼 — 별 Aspect + 별 어노테이션 + 별 Reconciler + wrapper 2 + Flyway 2 |
| Silent over-restore 차단 | partial — RDB version 가드 + DA4-1 over-restore 잔여 (mid-INCR crash) | partial — claimToInFlight CAS + IN_FLIGHT timeout + SETNX (under 형태 race) | partial — A 와 동일 한계 |
| Silent under-restore 차단 | fit — Reconciler 별 메서드 5초 fixed-delay | fit — outbox PENDING 영구 보존 | fit — Reconciler NOT EXISTS scan + cutoff 24h |
| 다른 보상 경로 일반화 (PHASE2) | break — PaymentEvent 결합 | fit — payment_outbox 작업 큐 | fit — payment_history audit |
| 운영자 mental model | 1.5트랙 (PaymentEvent 두 컬럼) | 1트랙 (payment_outbox 한 곳) | 1.5트랙 (PaymentEvent + payment_history) |
| 작업량 | 중간 | 큼 | 큼 |

---

## Architect 최종 추천 (Round 5)

### 1순위 — Candidate A

근거 (Architect 관점 = 삭제·교체 비용 + 포트/어댑터 경계 무결성):
- **사용자 신호 4개 모두 fit** — 본 토픽 일관 가드. 다른 두 후보는 partial 위반.
- **회귀 surface 가장 작음** — payment_event 컬럼 3 + JPA `@Version` + PaymentCommandUseCase wrapper 3. 다른 두 후보 (B 의 5 layer + Strategy 패턴 / D 의 별 Aspect + 별 Reconciler) 보다 응축. **나중에 떼어내기 쉬운 경계** 우위.
- **happy path 영향 0 가드 무결성 가장 안전** — Candidate B 의 정면 충돌 위협 회피. Candidate D 와 동등하지만 Candidate D 는 별 Aspect + 별 어노테이션 신설로 횡단 관심사 증가.
- **포트/어댑터 경계 흐림 0** — `StockCachePort` 시그니처 보존 + 신규 port 0 + Lua 0. Candidate B 는 OutboxRelayService 내부 Strategy dispatch 도입으로 application layer 분기 추가, Candidate D 는 별 Aspect + 별 어노테이션 횡단 관심사 추가.

채택 시 알려진 한계 정직 명시:
- **DA4-1 over-restore 잔여** — handle TX mid-INCR crash 시 같은 productId 가 INCR 두 번 가능. PaymentOrder.compensated_at 컬럼 도입 PHASE2 deferred. 발생 빈도 낮지만 발산 방향 위험.
- **다른 두 silent loss 경로 일반화 break** — PHASE2 가 별 모델 (B 또는 D 패턴) 도입 필요 = mental model 분기.

### 2순위 — Candidate D

근거:
- **다른 두 silent loss 경로 일반화 fit** — PHASE2 토픽이 같은 audit-driven 모델 재사용. mental model 분기 0.
- **happy path 영향 0 가드 fit** — 기존 `DomainEventLoggingAspect` 변경 0.
- **append-only audit retry count = 행 수** 발상이 도메인적으로 신선.

채택 시 trade-off:
- 차별점 약화 — Candidate A 와 application 빈 wrapper layer 변경 동등.
- 작업량 합계 D > A (Flyway 2 + 별 Aspect + 별 어노테이션 + 별 Reconciler).

### 3순위 / 추천 거부 — Candidate B

근거 (거부):
- **happy path 영향 0 가드 정면 충돌** — Round 4/5 흡수안 (c) 채택 후도 5 layer 회귀 surface + Strategy dispatch + claimToInFlight 4 인자 UPDATE 그대로. 본 토픽 핵심 가드 위협이 본 토픽 단독 결정 범위 초과.
- **outbox 의미 작업 큐 일반화의 도메인 결정 무게** — payment_outbox 가 향후 다른 비동기 작업도 자연 진입. 본 토픽 단독 결정으로 outbox 패턴 의미 변경.

단, 사용자가 (i) outbox 일반화 무게 (ii) 5 layer 회귀 surface 둘 다 받아들이면 일반화 fit + 1트랙 mental model 강점 살아남음 — 명시적 사용자 confirm 필요.

---

## Architect 1순위 vs Critic Round 4 / Domain Round 4 권고 차이

본 Round 5 Architect 의 1순위 (A) 는 Critic Round 4 권고 (A → D → B 조건부) 와 일치, Domain Round 4 권고 (B → D → A) 와는 분기.

**Domain 권고 B 와의 분기 사유**:
- Domain 의 1순위 B 근거: 도메인 안전성 차단 layer 가 가장 두꺼움 (claimToInFlight CAS + IN_FLIGHT timeout + SETNX 3 layer) + 돈이 새는 방향 비대칭 (under = 회복 가능 vs over = 발산) + 일반화 fit + mental model 1트랙.
- Architect 의 분기점: **happy path 영향 0 가드 무결성 + 회귀 surface 작음 + 사용자 신호 4 회피** 를 도메인 안전성보다 우선 평가. 이는 본 토픽의 핵심 가드 (사용자가 4 이질 신호로 baseline 0 거부한 결정) 와 정합.
- Domain 의 "돈이 새는 방향 under vs over 비대칭" 은 정직한 도메인 관점이지만 **본 토픽 단독 결정 범위에서는 happy path 가드가 더 무거움** — 사용자 결정 시 본 비대칭을 명시적으로 인정하고 A 채택 시 PaymentOrder.compensated_at PHASE2 도입을 안전 보강책으로 명시.

본 권고 차이를 사용자에게 정직하게 제시한다 — DECISION.md 의 §페르소나 추천 차이 표가 이 분기점을 가시화.

---

## Round 5 산출물 위치

| 산출물 | 위치 | 용도 |
|---|---|---|
| 결정 로그 (본 파일) | `docs/rounds/stock-compensation-recovery-alternatives/round-5-architect.md` | Round 4 critical 흡수 결정 기록 |
| ALTERNATIVES.md A/B/D 보강 | `docs/topics/STOCK-COMPENSATION-RECOVERY-ALTERNATIVES.md` (in-place edit) | 각 candidate 의 `### Round 4/5 refinement` 섹션 추가 |
| 사용자 결정용 DECISION.md (신규) | `docs/topics/STOCK-COMPENSATION-RECOVERY-DECISION.md` | 사용자가 채택안 1개 선택 |

---

## 사용자에게 제시할 핵심 결정 포인트

DECISION.md 의 §사용자 결정 입력 5 선택지 + §페르소나 추천 차이 표를 기반으로 사용자가 결정.

**사용자 결정 시 핵심 분기점 3개**:

1. **본 토픽 단독 vs PHASE2 묶음 모델 일관성**:
   - A 채택 → PHASE2 가 별 모델 (B 또는 D 패턴) 도입 필요 = mental model 분기
   - B 또는 D 채택 → 같은 모델 재사용 = mental model 일관

2. **돈이 새는 방향 비대칭** (Domain 검증 핵심):
   - A: over-restore 발산 가능 (admin 도구 reset 필요)
   - B: under-restore 회복 가능 (admin reset + token DEL)
   - D: audit SoT trade-off (운영자 임의 audit row 개입 위험)

3. **happy path 영향 0 vs silent loss 차단 layer 두께** (Architect/Critic 검증 핵심):
   - A/D: happy path fit + 차단 partial
   - B: happy path 위협 (정면 충돌) + 차단 두꺼움 (3 layer)

본 3 분기점을 사용자가 본 프로젝트의 도메인 우선순위 (결제 도메인 SLA, 운영 mental model 단순성, PHASE2 일관성) 와 대조해 결정.

채택 후 PLAN 단계 진입 — `docs/STOCK-COMPENSATION-RECOVERY-PLAN.md` 작성. PHASE2 토픽 위치 명시 (본 토픽 plan 의 non-goal) 필요.
