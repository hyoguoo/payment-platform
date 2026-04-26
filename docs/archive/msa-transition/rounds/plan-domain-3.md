# plan-domain-3

**Topic**: MSA-TRANSITION
**Round**: 3
**Persona**: Domain Expert

## Reasoning

Round 2 pass 승계 상태에서 Round 3는 Critic F-15/F-16/F-17/F-18 대응 delta만 관찰한다. 세 건 모두 **도메인 리스크를 후퇴시키지 않는다**. (1) F-15 Phase-1.0에 추가된 `PaymentGatewayPort` cross-context 복제는 현 소스 `payment/application/port/PaymentGatewayPort.java:12-25`가 이미 `confirm/cancel/getStatus/getStatusByOrderId` 4경로를 포함하는 구조를 그대로 승계하는 것이며 PLAN.md:133이 **Phase-1.1 `PgStatusPort`(getStatus 전용)와의 역할 분리를 명시적으로 선언**한다("이 Port는 PG 호출 전반(confirm·cancel·getStatus)을 담당, Phase-1.1의 `PgStatusPort`는 getStatus 단일 경로 전용 (중복 아님)"). ADR-05 가면 방어 `PgMaskedSuccessHandler`는 `PgStatusPort` 경로(getStatus 재조회)를 사용하므로 `PaymentGatewayPort` 복제가 방어선을 약화시키지 않는다. ADR-15 FCG 불변(timeout → QUARANTINED)도 독립 `PgStatusPort` 타임아웃 경로에 매여 있어 영향 없음. (2) F-16 Phase-1.4c DB 방침은 "결제 전용 DB 빈 상태 시작 + Phase-1.10 전환 시점 수동 이행 스크립트 `chaos/scripts/migrate-pending-outbox.sh`"(PLAN.md:230, 337)로 산출물화. Phase-1.10에서 모놀리스 `OutboxImmediateEventHandler`가 `@ConditionalOnProperty("payment.monolith.confirm.enabled", havingValue="true", matchIfMissing=false)`로 비활성화되므로 이행 스크립트 실행 시점에 모놀리스 측의 동시 발행 경로는 차단된다. 이중 발행 방지 방어선은 Phase-1.10 산출물로 유지. (3) F-17 Phase-3.1 `StockRestoreUseCase implements StockRestoreCommandService` 겸임은 현 코드베이스 관례(`OutboxAsyncConfirmService implements PaymentConfirmService`, src/main/java/.../payment/application/OutboxAsyncConfirmService.java:24)와 **정확히 대칭**이며 ADR-16 consumer dedupe 소유 구조를 전혀 바꾸지 않는다. Dedupe 로직은 `StockRestoreUseCase` 내부가 `EventDedupeStore` port로 호출 — Phase-3.3 테스트 `restore_DuplicateEventUuid_ShouldNoOp`·`restore_AfterDedupeTtlExpiry_ShouldReprocessOnce`·`restore_WhenStockIncreaseFailsMidway_ShouldNotRecordDedupe`가 이중 복원 방지 계약을 보호(PLAN.md:497-499). Consumer는 `StockRestoreCommandService` 인터페이스 타입으로 주입받아 얇음 유지(PLAN.md:465, 502). TX 경계·멱등성·재고 복원 중복 방지 모두 불변. (4) F-18은 ARCH 주석 프로세스 메타 규칙으로 도메인 리스크 무관 — skip. Round 2에서 해소된 4건(Phase-1.5 Toss wiring·Phase-4.1 chaos 확장·Phase-1.11 histogram chaos·Phase-1.10 모놀리스 비활성화)은 Round 3 재작성에서도 그대로 유지됨을 PLAN.md:243-254, 552-559, 554, 335에서 확인. **도메인 리스크 후퇴 없음 → Round 2 pass 승계**.

## Domain risk checklist

| 항목 | 판정 | 근거 |
|---|---|---|
| F-15 Phase-1.0 `PaymentGatewayPort` cross-context 복제 — ADR-05 가면 방어선 유지 | **pass** | PLAN.md:133 "이 Port는 PG 호출 전반(confirm·cancel·getStatus)을 담당, Phase-1.1의 `PgStatusPort`는 getStatus 단일 경로 전용 (중복 아님)" — 역할 분리 선언. 현 `payment/application/port/PaymentGatewayPort.java:12-25` 4경로 구조를 그대로 승계. Phase-1.5 `PgMaskedSuccessHandler`는 `PgStatusPort`(getStatus 재조회 + 금액 일치 검증)를 사용하므로 `PaymentGatewayPort` 복제가 ADR-05 방어선에 영향을 주지 않음. |
| F-15 Phase-1.0 `PaymentGatewayPort` — ADR-15 FCG 불변 유지 | **pass** | ADR-15 timeout → QUARANTINED는 `PgStatusPort`(FCG 경로, Phase-1.7 `OutboxProcessingService`) 전용 계약. `PaymentGatewayPort`(confirm/cancel)는 별도 경로. Phase-1.7 테스트 `process_WhenFcgPgCallTimesOut_ShouldQuarantine`·`process_WhenFcgPgReturns5xx_ShouldQuarantine`(PLAN.md:285-286)가 `PgStatusPort` 기반으로 불변 보호 유지. |
| F-15 Phase-1.0 build.gradle compile 의존 제거 — 경계 단절 | **pass** | PLAN.md:134 "`payment-service/build.gradle`에서 모놀리스 `paymentgateway` 패키지 compile 의존을 제거한다 (gradle 양방향 의존 차단)". 소유권 선명. 도메인 로직 변경 없음. |
| F-16 Phase-1.4c 결제 전용 DB 빈 상태 시작 — 미종결 PENDING 레코드 처리 주체 | **pass** | PLAN.md:230 "결제 전용 DB는 빈 상태로 시작. 모놀리스 DB의 미종결 `payment_outbox`·`payment_event` 레코드는 Phase-1.10 전환 전까지 모놀리스 컨테이너에서만 처리한다." — 소유 주체가 Phase별로 명시되어 동일 레코드를 두 서비스가 동시 처리할 경로 없음. |
| F-16 migrate-pending-outbox.sh 이중 발행 방지 | **pass** | PLAN.md:337 스크립트를 Phase-1.10 산출물로 배치 — 동일 Phase에서 모놀리스 `OutboxImmediateEventHandler`가 `@ConditionalOnProperty("payment.monolith.confirm.enabled", havingValue="true", matchIfMissing=false)`로 비활성화(PLAN.md:335). 이행 스크립트 실행 시점에 모놀리스 측 동시 발행 경로는 차단된다. |
| F-17 Phase-3.1 `StockRestoreUseCase implements StockRestoreCommandService` 겸임 — ADR-16 consumer dedupe 경로 | **pass** | PLAN.md:465, 502 — consumer는 `StockRestoreCommandService` 인터페이스 타입 주입. Dedupe 로직은 `StockRestoreUseCase` 내부에서 `EventDedupeStore` port 호출. Phase-3.3 테스트 `restore_DuplicateEventUuid_ShouldNoOp`(PLAN.md:497)이 이중 복원 방지 계약 보호. TX 경계·멱등성 불변. |
| F-17 겸임 패턴 — 프로젝트 관례 대칭 | **pass** | 현 `src/main/java/.../payment/application/OutboxAsyncConfirmService.java:24` `OutboxAsyncConfirmService implements PaymentConfirmService` 관례와 정확히 대칭. ARCHITECTURE.md "application service가 inbound port 겸임 구현" 패턴 준수. 도메인 로직 영향 없음. |
| F-17 consumer 얇음 — `consume_ShouldDelegateToStockRestoreUseCase` | **pass** | PLAN.md:500 테스트가 consumer → usecase 1회 위임을 검증. Phase-3.3 consumer에 직접 dedupe·stock 로직 삽입 금지 원칙(PLAN.md:490) 유지. |
| Round 2 승계 — Phase-1.5 Toss wiring 완결 (ADR-05) | **pass** | PLAN.md:243-254 — `TossPaymentGatewayStrategy` ALREADY_PROCESSED_PAYMENT 분기 + `TossPaymentErrorCode.isSuccess()` 수정 + `TossPaymentGatewayStrategyWiringTest` 유지. 현 `src/main/java/.../paymentgateway/exception/common/TossPaymentErrorCode.java:70-72` isSuccess==true 경로가 이번 phase에서 차단됨 변동 없음. |
| Round 2 승계 — Phase-1.5 NicePay 2201 대칭 | **pass** | PLAN.md:247 `handle_Nicepay_2201_VerifiesAmountBeforeDecision` + PLAN.md:248 `handle_Toss_AlreadyProcessed_VerifiesAmountSymmetry` 유지. 현 `src/main/java/.../nicepay/NicepayPaymentGatewayStrategy.java:87-88, 102` `handleDuplicateApprovalCompensation`(PG 재조회 + 금액 일치 검증) 로직을 결제 서비스로 이관만 — 신규 구현 리스크 없음. |
| Round 2 승계 — Phase-4.1 chaos 6종 | **pass** | PLAN.md:554-559 — `kafka-latency.sh`(histogram p95 ≥ 10s 수락 기준)·`db-latency.sh`·`process-kill.sh`·`verify-consistency.sh`·`stock-restore-duplicate.sh`(consumer dedupe 통합 검증)·`fcg-pg-timeout.sh`(FCG 불변 통합 검증) 유지. |
| Round 2 승계 — Phase-1.10 모놀리스 결제 경로 비활성화 | **pass** | PLAN.md:328, 335-336 — `@ConditionalOnProperty("payment.monolith.confirm.enabled", havingValue="true", matchIfMissing=false)` + `PaymentController` confirm 엔드포인트 동일 처리 또는 HTTP 501 — Strangler Fig 이중 발행 경로 차단 유지. |
| Round 2 승계 — Phase 1 `stock.restore` 보상 경로 원칙 | **pass** | PLAN.md:116 "Phase 1에서 상품 서비스는 아직 모놀리스 안에 있다. `stock.restore` 보상은 결제 서비스 내부 동기 호출 유지(`InternalProductAdapter` 방식 승계). 이벤트화는 Phase 3(상품 분리)과 동시에 진행한다." — 이행 구간 공백 없음. |
| Round 2 승계 — Phase-1.4 BEFORE_COMMIT 원자성 | **pass** | PLAN.md:200 `onPaymentStatusChange_InsertsHistoryBeforeCommit` + Phase-1.4b AOP 복제(PLAN.md:211-216) + Phase-1.4c Flyway V1(PLAN.md:227-230) 유지. ADR-13 결제 서비스 DB 잔류 실체화. |
| discuss risk → 태스크 매핑 + `domain_risk=true` 수 | **pass** | PLAN.md:629-637 추적 테이블 7건 매핑 유지. `domain_risk=true` 14개(PLAN.md:644-645). Round 3 신규 orphan 없음. |
| 중복 방지 체크(existsByOrderId/eventUuid) | **pass** | Phase-3.3 `restore_DuplicateEventUuid_ShouldNoOp`·`restore_AfterDedupeTtlExpiry_ShouldReprocessOnce`·`restore_WhenStockIncreaseFailsMidway_ShouldNotRecordDedupe`(PLAN.md:497-499) + Phase-2.3 `consume_DuplicateCommand_ShouldDedupeByEventUuid`(PLAN.md:422) 유지. |
| 재시도 안전성 (단위 + 통합) | **pass** | 단위: Phase-1.6 `relay_IsIdempotent_WhenCalledTwice`(PLAN.md:269), Phase-1.7 `process_RetryExhausted_CallsFcgOnce`(PLAN.md:288), Phase-3.3 `restore_WhenStockIncreaseFailsMidway_ShouldNotRecordDedupe`. 통합: Phase-4.1 `stock-restore-duplicate.sh`·`fcg-pg-timeout.sh`. |
| 상태 전이 enum 소유권 | **pass** | Phase-1.3 `PaymentEventTest`·`PaymentOutboxTest`(PLAN.md:181-185) 종결 상태 `@EnumSource` 커버 유지. |
| 금전 정확성 | **pass** | Phase-1.5가 Toss/NicePay 경로 양쪽에서 PG 재조회 + 금액 일치 검증 대칭 요구(PLAN.md:247-248). |
| PII 노출·저장 | **n/a** | 본 토픽 비목표. |

## 도메인 관점 추가 검토

### 1. F-15 delta — `PaymentGatewayPort` cross-context 복제

Round 3 Phase-1.0 산출물(PLAN.md:133-134)에 `PaymentGatewayPort` cross-context 복제와 `InternalPaymentGatewayAdapter` 이관 경로, build.gradle compile 의존 제거 방침이 명시됐다. 현 소스(`src/main/java/.../payment/application/port/PaymentGatewayPort.java:12-25`)는 이미 `confirm/cancel/getStatus/getStatusByOrderId` 4경로를 가진다. 결제 서비스가 독립 모듈이 될 때 이 포트를 그대로 승계하면 된다.

도메인 관점 검증 포인트:
- **Phase-1.1 `PgStatusPort`와의 역할 분리**: PLAN.md:133에 "`PaymentGatewayPort`는 PG 호출 전반(confirm·cancel·getStatus)을 담당, `PgStatusPort`는 getStatus 단일 경로 전용 (중복 아님)"이 선언돼 역할 경계 명확. Phase-1.5 `PgMaskedSuccessHandler`는 `PgStatusPort`를 사용(재조회 + 금액 검증), Phase-1.7 `OutboxProcessingService` FCG 불변(`timeout → QUARANTINED`)도 `PgStatusPort` 타임아웃 경로에 귀속. 즉 ADR-05·ADR-15 방어선은 전부 `PgStatusPort` 쪽에 걸려 있어 `PaymentGatewayPort` 복제가 이를 약화시킬 경로 없음.
- **`InternalPaymentGatewayAdapter` 이관** (`src/main/java/.../payment/infrastructure/internal/InternalPaymentGatewayAdapter.java:25-51`): 현 구현이 `PaymentGatewayFactory` → 전략 호출만 래핑하는 얇은 어댑터. Phase 1 이관 시 모놀리스 `paymentgateway/presentation` 내부 호출을 이 어댑터 경계 안에 국한하고 build.gradle에서 compile 의존 제거 — 경계 단절 달성. Phase 2에서 HTTP 어댑터로 스왑 시에도 이 경계 안에서만 바뀜.

**판정**: 도메인 리스크 후퇴 없음. 오히려 경계가 선명해진다.

### 2. F-16 delta — Phase-1.4c DB 방침 + migrate-pending-outbox.sh

PLAN.md:230이 세 가지를 명시했다. (i) 결제 전용 DB는 빈 상태로 시작. (ii) 모놀리스 DB의 미종결 `payment_outbox`·`payment_event` 레코드는 Phase-1.10 전환 전까지 모놀리스 컨테이너에서만 처리. (iii) 전환 시점 수동 이행 스크립트 `chaos/scripts/migrate-pending-outbox.sh`를 Phase-1.10 산출물로 제공(PLAN.md:337).

도메인 관점 검증 포인트:
- **이행 중 이중 처리 방지**: 동일 PENDING 레코드를 모놀리스와 결제 서비스가 동시에 픽업할 위험이 핵심인데, PLAN.md:335 Phase-1.10 산출물에서 `OutboxImmediateEventHandler`가 `@ConditionalOnProperty(matchIfMissing=false)` 처리되어 모놀리스 측 confirm 경로가 비활성화된다. migrate 스크립트 실행 시점에 모놀리스는 이미 confirm 경로가 죽은 상태이므로, 이행된 PENDING 레코드를 결제 서비스만 처리하는 싱글 페스(single-path) 보장이 성립한다.
- **폴백 OutboxWorker 관찰(minor 메모)**: Round 3 산출물은 `OutboxImmediateEventHandler`와 `PaymentController` confirm 엔드포인트만 비활성화 대상으로 명시한다. 그러나 현 모놀리스에는 `OutboxWorker`(fixedDelay 폴백 폴링)가 존재(CONFIRM-FLOW-ANALYSIS §1-4, PITFALLS §1)하며, 이 워커도 PENDING 레코드를 픽업할 수 있다. PLAN.md:335의 `@ConditionalOnProperty` 프로퍼티 하나가 `OutboxImmediateEventHandler`만 덮는지, 아니면 `OutboxWorker`·`OutboxProcessingService`를 포함한 전체 confirm 처리 축을 덮는지 plan 수준에서 **명시적이지 않다**. 운영 관점에서는 `payment.monolith.confirm.enabled`를 단일 모듈 활성 스위치로 설계해 `OutboxWorker`/폴백 스캔까지 동일 프로퍼티로 꺼지도록 구현할 수 있고, 이는 execute 단계에서 정의 가능한 사안이다. **승격하지 않고 minor 메모로 남김** — Round 2 승계 scope에서도 동일한 모호성이 존재했고 Round 3에서 후퇴한 것이 아니다.
- **수동 이행 스크립트의 원자성**: 스크립트가 모놀리스 DB에서 PENDING 레코드를 읽어 결제 서비스 DB로 insert할 때 원자성(읽기 시점에 해당 레코드가 이미 IN_FLIGHT로 전이되지 않을 것) 보장 방식은 PLAN.md 수준에 없다. 이는 operational runbook 수준 세부이며 Phase-1.10이 전환 당일 작업 절차이므로 plan 수준 공백으로 승격할 만하지 않다. **minor 메모**.

**판정**: 도메인 리스크 후퇴 없음.

### 3. F-17 delta — Phase-3.1 겸임 패턴

PLAN.md:465이 "Phase-3.1 `StockRestoreUseCase implements StockRestoreCommandService` 겸임 채택. ARCHITECTURE.md 관례(`PaymentConfirmService ← OutboxAsyncConfirmService`) 준수. Phase-3.3 `StockRestoreConsumer`는 `StockRestoreCommandService` 인터페이스 타입으로 주입받는다"를 명시.

도메인 관점 검증 포인트:
- **현 관례 대칭 확인**: `src/main/java/.../payment/application/OutboxAsyncConfirmService.java:24`가 실제로 `implements PaymentConfirmService`로 선언된 것을 확인. Phase-3.1 겸임 패턴은 이 관례와 완전 대칭이며 Phase 단위 컨벤션 불일치 아님.
- **ADR-16 consumer dedupe 책임 불변**: `EventDedupeStore` port는 Phase-3.1에서 선언(PLAN.md:463), 구현체 `JdbcEventDedupeStore`·`JpaEventDedupeRepository`·Flyway V2는 Phase-3.3에서 배치(PLAN.md:504-506). Dedupe 호출은 `StockRestoreUseCase` 내부가 담당하고 consumer는 `StockRestoreCommandService` 인터페이스만 경유. **TX 경계**(`recordIfAbsent(eventUuid, expiresAt)` + stock 증가)가 application 계층 usecase 안에 닫혀 있어 consumer에서 유출될 위험 없음. Phase-3.3 테스트 `restore_WhenStockIncreaseFailsMidway_ShouldNotRecordDedupe`(PLAN.md:499)가 stock 증가 실패 시 dedupe 미기록 = 재시도 시 재처리 = 원자성 보호 계약을 검증.
- **겸임과 Phase-3.3 consumer 얇음 테스트**: `consume_ShouldDelegateToStockRestoreUseCase`(PLAN.md:500)가 consumer → usecase 1회 위임 검증. 겸임 패턴에서도 consumer는 여전히 `StockRestoreCommandService` 타입만 알고 있으므로 Phase-3.3에서 `StockRestoreUseCase` 내부 구조를 변경해도 consumer 테스트 계약이 깨지지 않는다. 멱등성 계약 불변.
- **TTL 정량화 유지**: PLAN.md:490 "TTL = Kafka consumer group offset retention + 1일" 유지. Phase-3.3 테스트 `restore_AfterDedupeTtlExpiry_ShouldReprocessOnce`(PLAN.md:498)가 만료 시 재처리 허용 계약 보호.

**판정**: 도메인 리스크 후퇴 없음. 오히려 관례 대칭으로 유지보수성 증가.

### 4. F-18 — 도메인 무관

ARCH 주석 프로세스 메타 규칙. 도메인 리스크 관련 없음. skip.

### 5. Round 2 승계 항목 확인

Round 2 pass 기준 모든 finding이 Round 3 재작성에서도 유지됨을 교차 확인:

- Phase-1.5 `TossPaymentGatewayStrategyWiringTest#confirm_WhenAlreadyProcessedPayment_ShouldInvokePgMaskedSuccessHandler` — PLAN.md:250 유지
- Phase-1.5 Toss 전략 수정(ALREADY_PROCESSED_PAYMENT 포착) — PLAN.md:253 유지
- Phase-1.5 `TossPaymentErrorCode.isSuccess()` 수정 — PLAN.md:254 유지
- Phase-4.1 `stock-restore-duplicate.sh`·`fcg-pg-timeout.sh` — PLAN.md:558-559 유지
- Phase-1.11 kafka-latency.sh 수락 기준 "histogram p95 ≥ 10s Prometheus" — PLAN.md:554 유지
- Phase-1.10 `@ConditionalOnProperty(matchIfMissing=false)` — PLAN.md:335 유지
- Phase 1 `stock.restore` 보상 내부 동기 호출 유지 원칙 — PLAN.md:116 유지
- AOP 복제(Phase-1.4b) — PLAN.md:204-216 유지
- Flyway V1(Phase-1.4c) — PLAN.md:220-230 유지

모두 유지. **후퇴 없음**.

### 6. 참고 관찰 — 승격 없음 (minor 메모)

- **Phase-1.10 confirm 경로 비활성화 범위**: `OutboxImmediateEventHandler`와 `PaymentController` confirm 엔드포인트만 산출물에 명시되어 있고, `OutboxWorker`(폴링 폴백)의 동일 프로퍼티 커버 여부는 plan 수준에서 암묵적. execute 단계에서 단일 프로퍼티가 `@EnableScheduling` 축 전체를 덮도록 구현하면 해소. **plan 수준 승격 근거 부족**.
- **migrate-pending-outbox.sh 원자성**: 스크립트가 모놀리스 DB → 결제 서비스 DB 이행 중 모놀리스 측에서 해당 레코드가 IN_FLIGHT로 전이되지 않음을 어떻게 보장하는지 plan 수준에서 미명시. 운영 runbook 수준 세부.

두 건 모두 Round 2 pass 승계 범위에서도 존재했던 수준의 관찰이며 Round 3 delta에서 후퇴한 것이 아님. **본 판정에 영향 없음**.

## Findings

- **[n/a]** F-15 `PaymentGatewayPort` cross-context 복제 — Phase-1.0 산출물이 `PgStatusPort`와 역할 분리 선언. ADR-05/ADR-15 방어선은 `PgStatusPort` 쪽에 있어 영향 없음. build.gradle compile 의존 제거로 경계 단절 달성. 도메인 리스크 후퇴 없음.
- **[n/a]** F-16 Phase-1.4c DB 방침 — 결제 전용 DB 빈 상태 시작 + Phase-1.10 `@ConditionalOnProperty(matchIfMissing=false)` 비활성화 + migrate-pending-outbox.sh 수동 이행. 이행 시점 이중 발행 경로 차단. 도메인 리스크 후퇴 없음.
- **[n/a]** F-17 Phase-3.1 겸임 패턴 — 현 `OutboxAsyncConfirmService implements PaymentConfirmService` 관례와 대칭. Consumer는 인터페이스 타입 주입, dedupe는 usecase 내부. ADR-16 TX 경계·멱등성 불변. 도메인 리스크 후퇴 없음.
- **[n/a]** F-18 ARCH 주석 프로세스 — 도메인 리스크 무관.
- **[n/a]** Round 2 승계 항목 4건(Phase-1.5/1.10/1.11/4.1) — Round 3 재작성에서 유지.
- **[n/a]** PII·보안은 본 토픽 비목표.

## JSON
```json
{
  "stage": "plan",
  "persona": "domain-expert",
  "round": 3,
  "task_id": null,
  "decision": "pass",
  "reason_summary": "Round 2 pass 승계. Critic F-15/F-16/F-17 대응 delta는 모두 도메인 리스크를 후퇴시키지 않는다. (F-15) Phase-1.0 PaymentGatewayPort 복제는 PLAN.md:133의 역할 분리 선언으로 PgStatusPort(getStatus 전용)와 중복 아님이 명시되고 ADR-05/ADR-15 방어선은 PgStatusPort에 귀속되어 영향 없음. (F-16) Phase-1.4c 빈 DB 시작 + Phase-1.10 @ConditionalOnProperty(matchIfMissing=false) + migrate-pending-outbox.sh로 이행 시점 이중 발행 경로 차단. (F-17) Phase-3.1 StockRestoreUseCase implements StockRestoreCommandService 겸임은 현 OutboxAsyncConfirmService implements PaymentConfirmService 관례와 대칭이며 consumer는 인터페이스 타입 주입으로 얇음 유지, dedupe는 usecase 내부 EventDedupeStore port 경유 — TX 경계·멱등성 불변. (F-18) ARCH 프로세스 규칙, 도메인 무관. Round 2 승계 4건(Phase-1.5 Toss wiring·Phase-4.1 chaos 6종·Phase-1.11 histogram·Phase-1.10 비활성화) 모두 Round 3 재작성에서 유지됨.",
  "checklist": {
    "source": "_shared/checklists/plan-ready.md",
    "items": [
      {
        "section": "domain risk",
        "item": "discuss에서 식별된 domain risk가 각각 대응 태스크를 가짐",
        "status": "yes",
        "evidence": "docs/MSA-TRANSITION-PLAN.md:629-637 추적 테이블 7건 매핑 유지. domain_risk=true 14개(PLAN.md:644-645)."
      },
      {
        "section": "domain risk",
        "item": "중복 방지 체크가 필요한 경로에 계획됨",
        "status": "yes",
        "evidence": "Phase-3.3 restore_DuplicateEventUuid_ShouldNoOp·restore_AfterDedupeTtlExpiry_ShouldReprocessOnce·restore_WhenStockIncreaseFailsMidway_ShouldNotRecordDedupe(PLAN.md:497-499) + Phase-2.3 consume_DuplicateCommand_ShouldDedupeByEventUuid(PLAN.md:422) + Phase-3.3 EventDedupeStore port/JdbcEventDedupeStore/JpaEventDedupeRepository/V2 dedupe 테이블 + Phase-3.1 StockRestoreUseCase 겸임(consumer 얇음 유지)"
      },
      {
        "section": "domain risk",
        "item": "재시도 안전성 검증 태스크 존재 (단위 + 통합)",
        "status": "yes",
        "evidence": "단위: Phase-1.6 relay_IsIdempotent_WhenCalledTwice(PLAN.md:269), Phase-1.7 process_RetryExhausted_CallsFcgOnce(PLAN.md:288), Phase-3.3 restore_WhenStockIncreaseFailsMidway_ShouldNotRecordDedupe(PLAN.md:499). 통합: Phase-4.1 chaos/scenarios/stock-restore-duplicate.sh + fcg-pg-timeout.sh(PLAN.md:558-559)"
      }
    ],
    "total": 3,
    "passed": 3,
    "failed": 0,
    "not_applicable": 0
  },
  "scores": {
    "traceability": 0.95,
    "decomposition": 0.91,
    "ordering": 0.90,
    "specificity": 0.92,
    "risk_coverage": 0.93,
    "mean": 0.922
  },
  "findings": [
    {
      "severity": "n/a",
      "checklist_item": "F-15 Phase-1.0 PaymentGatewayPort cross-context 복제 — ADR-05/ADR-15 방어선 유지",
      "location": "docs/MSA-TRANSITION-PLAN.md:133-134 (Phase-1.0)",
      "problem": "도메인 리스크 후퇴 없음.",
      "evidence": "PLAN.md:133 'Phase-1.1의 PgStatusPort는 getStatus 단일 경로 전용 (중복 아님)' — 역할 분리 선언. 현 payment/application/port/PaymentGatewayPort.java:12-25 4경로 구조 승계. ADR-05 PgMaskedSuccessHandler는 PgStatusPort 기반(Phase-1.5), ADR-15 FCG 불변은 PgStatusPort 타임아웃 경로(Phase-1.7). PaymentGatewayPort 복제가 방어선 약화 없음.",
      "suggestion": "해당 없음."
    },
    {
      "severity": "n/a",
      "checklist_item": "F-16 Phase-1.4c 결제 전용 DB 방침 + migrate-pending-outbox.sh 이중 발행 방지",
      "location": "docs/MSA-TRANSITION-PLAN.md:230, 335, 337 (Phase-1.4c + Phase-1.10)",
      "problem": "도메인 리스크 후퇴 없음.",
      "evidence": "PLAN.md:230 '결제 전용 DB는 빈 상태로 시작. 모놀리스 DB의 미종결 payment_outbox·payment_event 레코드는 Phase-1.10 전환 전까지 모놀리스 컨테이너에서만 처리'. PLAN.md:335 Phase-1.10 '@ConditionalOnProperty(payment.monolith.confirm.enabled, havingValue=true, matchIfMissing=false)'로 모놀리스 OutboxImmediateEventHandler 비활성화 + PLAN.md:337 'migrate-pending-outbox.sh' — 이행 시점 이중 발행 경로 차단.",
      "suggestion": "해당 없음. (minor 메모: OutboxWorker 폴백 스캐너의 동일 프로퍼티 커버 여부는 execute 단계 구현 사안)"
    },
    {
      "severity": "n/a",
      "checklist_item": "F-17 Phase-3.1 StockRestoreUseCase 겸임 패턴 — ADR-16 consumer dedupe 불변",
      "location": "docs/MSA-TRANSITION-PLAN.md:465, 500, 502 (Phase-3.1, Phase-3.3)",
      "problem": "도메인 리스크 후퇴 없음.",
      "evidence": "PLAN.md:465 'StockRestoreUseCase implements StockRestoreCommandService' — 현 src/main/java/.../payment/application/OutboxAsyncConfirmService.java:24 'OutboxAsyncConfirmService implements PaymentConfirmService' 관례와 대칭. PLAN.md:500 consume_ShouldDelegateToStockRestoreUseCase(consumer 얇음) + PLAN.md:502 consumer는 StockRestoreCommandService 인터페이스 타입 주입. Dedupe 로직은 StockRestoreUseCase 내부 EventDedupeStore port 경유, Phase-3.3 restore_DuplicateEventUuid_ShouldNoOp·restore_AfterDedupeTtlExpiry_ShouldReprocessOnce·restore_WhenStockIncreaseFailsMidway_ShouldNotRecordDedupe(PLAN.md:497-499)가 이중 복원 방지·TTL·재시도 안전성 계약 보호. TX 경계·멱등성 불변.",
      "suggestion": "해당 없음."
    },
    {
      "severity": "n/a",
      "checklist_item": "F-18 ARCH 주석 프로세스",
      "location": "docs/MSA-TRANSITION-PLAN.md:9-11",
      "problem": "도메인 리스크 무관.",
      "evidence": "ARCH tag 범례는 Architect/Critic 프로세스 메타 규칙. 도메인 관점 범위 밖.",
      "suggestion": "해당 없음."
    },
    {
      "severity": "n/a",
      "checklist_item": "Round 2 승계 — Phase-1.5/1.10/1.11/4.1 Round 3 재작성 유지",
      "location": "docs/MSA-TRANSITION-PLAN.md:243-254, 335, 554, 558-559",
      "problem": "Round 2 pass 판정 근거 유지.",
      "evidence": "PLAN.md:253-254 Toss 전략·ErrorCode 수정 유지, PLAN.md:250 TossPaymentGatewayStrategyWiringTest 유지, PLAN.md:558-559 stock-restore-duplicate.sh·fcg-pg-timeout.sh 유지, PLAN.md:554 kafka-latency.sh histogram p95 수락 기준 유지, PLAN.md:335 @ConditionalOnProperty(matchIfMissing=false) 유지.",
      "suggestion": "해당 없음."
    },
    {
      "severity": "n/a",
      "checklist_item": "PII·보안",
      "location": "docs/topics/MSA-TRANSITION.md § 1-3",
      "problem": "본 토픽 비목표",
      "evidence": "§ 1-3에서 보안 범위 명시적 비목표 선언",
      "suggestion": "해당 없음."
    }
  ],
  "previous_round_ref": "docs/rounds/msa-transition/plan-domain-2.md",
  "delta": {
    "newly_passed": [
      "F-15 Phase-1.0 PaymentGatewayPort cross-context 복제 — PgStatusPort와 역할 분리 선언(PLAN.md:133) + build.gradle compile 의존 제거",
      "F-16 Phase-1.4c 결제 전용 DB 빈 상태 시작 + migrate-pending-outbox.sh 수동 이행 + Phase-1.10 비활성화로 이중 발행 차단",
      "F-17 Phase-3.1 StockRestoreUseCase implements StockRestoreCommandService 겸임 — 현 OutboxAsyncConfirmService 관례 대칭, consumer 얇음·dedupe TX 경계 불변"
    ],
    "newly_failed": [],
    "still_failing": []
  },
  "unstuck_suggestion": null
}
```
