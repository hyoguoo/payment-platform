# plan-domain-4

**Topic**: MSA-TRANSITION
**Round**: 4
**Persona**: Domain Expert

## Reasoning

Round 3 pass 승계 상태에서 Round 4 delta(C-1/C-2/M-3/M-4/M-5 + ARCH R4 RESOLVED) 6건만 독립 관찰한다. 여섯 건 모두 도메인 리스크를 후퇴시키지 않는다. (1) **C-1 Phase-3.1b 사용자 서비스 신설**은 사용자 조회 전용 경로로 상태 전이·멱등성·PG 가면·금전 정확성 경로와 무관. PLAN.md:668 `UserQueryUseCase implements UserQueryService` 겸임 패턴은 현 소스 `src/main/java/com/hyoguoo/paymentplatform/user/application/UserServiceImpl.java:13` `UserServiceImpl implements UserService` 관례와 정확히 대칭이며 ADR-22 product → user 순서를 완성한다. `domain_risk=false` 타당. PII 관점은 본 토픽 비목표(§ 1-3)로 변동 없음. (2) **C-2 Phase-2.3b 결제 서비스 측 어댑터 교체**는 `LocalPgStatusAdapter`→`PgStatusHttpAdapter` / `InternalPaymentGatewayAdapter`→`PaymentGatewayKafkaCommandAdapter`로 구현체를 스왑하고 `PgEventConsumer`를 신설한다. ADR-05 `PgMaskedSuccessHandler`는 **`PgStatusPort` 경로**에만 매여 있고(Round 3 판정 재확인), Phase-1.5의 가면 응답 방어 wiring은 결제 서비스 application 계층이 `PgStatusPort`를 직접 주입해 쓰는 형태로 유지되므로(ARCH R4 RESOLVED에 의해 adapter→adapter 위임 금지 선언) HTTP 어댑터 교체가 방어선을 약화시키는 경로 없음. `@CircuitBreaker` adapter 내부 한정 방침도 유지. `PgEventConsumerTest#consume_DuplicateEvent_ShouldDedupeByEventUuid`(PLAN.md:601)는 결제 서비스 측 이중 수신 방어 계약을 테스트 수준에 명시했고 실제 구현이 `PaymentOutbox`/`PaymentEvent`의 종결 재진입 거부 게이트(CONFIRM-FLOW-ANALYSIS §1-4 ③ `rejectReentry`)를 활용하든 별도 dedupe store를 도입하든 도메인 상태 머신은 이미 이중 전이로부터 PaymentEvent를 보호한다(`markPaymentAsDone` 종결 no-op, `executePaymentFailureCompensationWithOutbox` D12 가드). **돈이 새는 경로는 이미 닫혀 있다**. (3) **M-3 Phase-1.4 재정의**는 "stock-- TX 경계 외부" 선언과 `executePaymentConfirm_WhenStockDecreaseFails_ShouldTransitionToQuarantineWithoutOutbox` 테스트 신설로 오히려 이전보다 방어선이 명확해진다. stock-- 실패 시 **outbox 미생성**(PG 호출 경로 차단) + **QUARANTINED 전이**(종결 상태 → 재진입 거부)로 이중 결제 경로가 원천 차단된다. `payment-order.status=EXECUTING`에서 stock-- 실패 후 outbox 미생성은 `OutboxRelayService`/`OutboxProcessingService`가 해당 orderId를 픽업할 근거 자체를 제거한다는 점에서 보상 이벤트(stock.restore) 발행도 필요 없음 — 이는 "보상 불필요"(PLAN.md:352)와 부합. Phase-1.4 기존 `executePaymentConfirm_CommitsPaymentStateAndOutboxInSingleTransaction` 테스트가 payment_event+payment_outbox 내부 원자성을 여전히 검증(PLAN.md:351). (4) **M-4 Phase-0.1 DB 경계 방침**은 PG 서비스 무상태 + 관리자 읽기 전용 뷰 선언으로 결제 도메인 관점 영향 없음. PG가 DB를 갖지 않는다는 것은 오히려 PG 상태의 단일 진실원이 **PG 벤더(Toss/NicePay)**임을 강제하므로 ADR-15 FCG 불변(`getStatus` 경로가 PG 벤더 API를 직접 조회)과 일관. 관리자 읽기 전용 뷰는 결제 도메인 쓰기 경로에 개입하지 않음. (5) **M-5 토픽 네이밍 규약** `<source-service>.<type>.<action>` + `PaymentTopics`/`PgTopics`/`ProductTopics` 중앙화는 drift 방지 도구. `stock.restore`는 예시에 `product.events.stock-restored`(PLAN.md:569)로 정규화되었는데 Phase-3.3 산출물 표기("stock.restore UUID 키", PLAN.md:51)와 용어 불일치가 있지만 네이밍 규약이 규정된 것이고 consumer dedupe 계약(동일 UUID 2회 수신 → 1회 처리)은 토픽 이름과 독립적 — 멱등성 계약 불변. (6) **ARCH R4 RESOLVED**는 `PaymentGatewayPort`=confirm/cancel 전담, `PgStatusPort`=getStatus 전담으로 scope를 명시적으로 재정의해 adapter→adapter 위임 경로를 원천 차단. 현 소스 `src/main/java/com/hyoguoo/paymentplatform/payment/application/port/PaymentGatewayPort.java:12-25`의 4경로 구조 중 getStatus/getStatusByOrderId는 Phase 2 완료 시점에 `PgStatusPort`로 귀속되며, Phase-1.5 `PgMaskedSuccessHandler`가 사용하는 "PG 재조회 + 금액 일치 검증" 경로는 `PgStatusPort`에 단독 귀속되므로 ADR-05 방어선 약화 경로가 존재하지 않는다. 오히려 Round 3의 "중복 아님" 선언이 scope 분리로 실체화됐다. **도메인 리스크 후퇴 없음 → Round 3 pass 승계**.

## Domain risk checklist

| 항목 | 판정 | 근거 |
|---|---|---|
| C-1 Phase-3.1b user-service 신설 — ADR-13 감사 원자성 후퇴 없음 | **pass** | 사용자 조회 도메인은 payment_history 감사 경로와 무관(결제 서비스 DB 잔류 불변, Phase-1.4c). 사용자 서비스는 자기 DB만 소유(PLAN.md:671). 감사 경로 영향 없음. |
| C-1 Phase-3.1b — ADR-05 가면 방어 영향 | **pass** | 사용자 조회는 PG 경로와 무관. PG 가면 응답 방어선(Phase-1.5)은 결제 서비스 ↔ PG 서비스 사이에만 존재. 영향 없음. |
| C-1 Phase-3.1b — PII 리스크 | **n/a** | 본 토픽 비목표(§ 1-3). 사용자 도메인 이관만 다루고 필드 노출·마스킹·암호화는 scope 밖. 후퇴 판정 근거 아님. |
| C-1 Phase-3.1b `UserQueryUseCase implements UserQueryService` 겸임 | **pass** | 현 `src/main/java/com/hyoguoo/paymentplatform/user/application/UserServiceImpl.java:13` `UserServiceImpl implements UserService` 관례와 정확히 대칭. Phase-3.1 StockRestoreUseCase 겸임 패턴과도 대칭. domain_risk=false 타당. |
| C-2 Phase-2.3b PgStatusHttpAdapter 교체 — ADR-05 가면 방어선 | **pass** | PLAN.md:604 "getStatus 경로는 application 계층이 `PgStatusPort`를 직접 주입해 사용" — Phase-1.5 `PgMaskedSuccessHandler`가 `PgStatusPort`(HTTP 어댑터) 경유 PG 재조회 경로를 그대로 보존. 어댑터 구현체 스왑만이고 port 계약·호출 경로는 불변. |
| C-2 Phase-2.3b PaymentGatewayKafkaCommandAdapter — ADR-15 FCG 불변 | **pass** | ARCH R4 RESOLVED에 의해 `PaymentGatewayPort`는 confirm/cancel 전담으로 scope 재정의. FCG 불변(timeout → QUARANTINED)은 `PgStatusPort` 타임아웃 경로(Phase-1.7 OutboxProcessingService)에 귀속. Kafka 커맨드 어댑터가 FCG 경로에 개입하지 않음. |
| C-2 Phase-2.3b `PgEventConsumer` dedupe — ADR-16 consumer dedupe | **pass** | `PgEventConsumerTest#consume_DuplicateEvent_ShouldDedupeByEventUuid`(PLAN.md:601)가 결제 서비스 측 이중 수신 방어 계약을 명시. 구현 경로는 `PaymentOutbox`/`PaymentEvent` 종결 재진입 거부(CONFIRM-FLOW-ANALYSIS §1-4 ③ `rejectReentry`) + markPaymentAsDone 종결 no-op가 기존 방어선 — 이중 수신 시 돈 경로 열리지 않음. (minor 메모: 결제 서비스 측에 별도 `EventDedupeStore` 테이블을 두는지 `PaymentOutbox` 상태 게이팅으로 대체하는지 PLAN.md 수준에 명시 없음. execute 단계 구현 선택.) |
| C-2 Phase-2.3b `LocalPgStatusAdapter`/`InternalPaymentGatewayAdapter` 퇴역 | **pass** | Phase 2 완료 후 제거(PLAN.md:608). 경계 단절 선명화. 도메인 로직 변경 없음. |
| M-3 Phase-1.4 stock-- TX 경계 외부 방침 | **pass** | PLAN.md:345 명시적 선언. 결제 서비스 내부 TX(payment_event+payment_outbox) 원자성과 stock-- 외부 호출의 실패 모드를 분리. 이중 결제 경로 방어가 오히려 명확. |
| M-3 `executePaymentConfirm_WhenStockDecreaseFails_ShouldTransitionToQuarantineWithoutOutbox` | **pass** | PLAN.md:352 — stock-- 실패 시 **outbox 미생성** + **QUARANTINED 전이**. outbox 미생성은 OutboxRelayService/OutboxProcessingService의 픽업 근거를 원천 제거 → PG 호출 경로 봉쇄. QUARANTINED는 종결 상태로 재진입 거부. 금전 안전성 이중 방어. |
| M-3 stock lock-in 방어 | **pass** | stock-- 실패로 재고 감소가 롤백(CONFIRM-FLOW-ANALYSIS §2-5 `handleStockFailure` 원칙 승계) + PaymentEvent QUARANTINED로 종결 → 같은 orderId 재처리 경로 차단. stock lock-in은 Phase-1.11 `payment.outbox.pending_age_seconds` histogram이 운영 수준에서 감지. |
| M-3 기존 `executePaymentConfirm_CommitsPaymentStateAndOutboxInSingleTransaction` 유지 | **pass** | PLAN.md:351 — payment_event+payment_outbox 단일 TX 원자성 테스트가 stock-- 분리 후에도 결제 서비스 내부 TX 계약을 보호. 커밋 후 외부 호출 실패는 상태 머신이 처리. |
| M-4 PG 무상태 + 관리자 읽기 전용 뷰 | **pass** | PLAN.md:211 — PG는 Toss/NicePay HTTP + Kafka만. PG 상태의 단일 진실원이 PG 벤더 API로 유지돼 ADR-15 FCG 불변(getStatus 직접 조회)과 일관. 관리자 읽기 전용 뷰는 결제 도메인 쓰기 경로에 개입하지 않음. 영향 없음. |
| M-5 토픽 네이밍 규약 `<source-service>.<type>.<action>` | **pass** | PLAN.md:569 — consumer dedupe 계약(UUID 키, TTL 정량화)은 토픽 이름과 독립적. 토픽 이름 drift 방지는 오히려 운영 안전성 증가. `PaymentTopics`/`PgTopics`/`ProductTopics` 상수 중앙화로 이름 오타 → 이벤트 drop 리스크 해소. |
| M-5 `product.events.stock-restored` vs Phase-3.3 "stock.restore" 용어 | **pass** | Phase-3.3 dedupe 계약은 이벤트 UUID 키 기반이고 토픽 이름 변경과 독립적. 용어 일관성은 plan-review 범위(critic)이며 도메인 리스크 후퇴 아님. execute 시점에 `ProductTopics.STOCK_RESTORED = "product.events.stock-restored"`로 통일하면 해소. |
| ARCH R4 RESOLVED `PaymentGatewayPort`=confirm/cancel / `PgStatusPort`=getStatus | **pass** | PLAN.md:286 scope 재정의 — Phase-1.5 `PgMaskedSuccessHandler`의 "PG 재조회 + 금액 일치 검증" 경로는 `PgStatusPort`에 단독 귀속. ADR-05 방어선 약화 경로 부존재. Round 3의 "중복 아님" 선언이 scope 분리로 실체화돼 오히려 경계 선명. |
| Round 3 pass 승계 항목 — F-15/F-16/F-17/F-18 | **pass** | Round 3 재작성 유지 확인: PLAN.md:286-287(F-15 PaymentGatewayPort 복제 + build.gradle), PLAN.md:384(F-16 빈 DB 시작 + migrate-pending-outbox.sh), PLAN.md:647(F-17 StockRestoreUseCase 겸임), 파일 상단(F-18 ARCH 범례). |
| Round 2 pass 승계 항목 | **pass** | Phase-1.5 Toss wiring 완결(PLAN.md:392-408), Phase-4.1 chaos 6종(PLAN.md:756-761), Phase-1.11 histogram p95 ≥ 10s(PLAN.md:756), Phase-1.10 `@ConditionalOnProperty(matchIfMissing=false)`(PLAN.md:489) 유지. |
| discuss risk → 태스크 매핑 | **pass** | PLAN.md:833-842 추적 테이블 10건 매핑(C-1/C-2/M-5 신규 행 포함). `domain_risk=true` 15개(PLAN.md:849-850). Round 4 신규 orphan 없음. |
| 중복 방지 체크 (existsByOrderId/eventUuid) | **pass** | Phase-3.3 UUID dedupe(PLAN.md:699-701) + Phase-2.3 `consume_DuplicateCommand_ShouldDedupeByEventUuid`(PLAN.md:576) + Phase-2.3b `consume_DuplicateEvent_ShouldDedupeByEventUuid`(PLAN.md:601) 3경로. |
| 재시도 안전성 (단위 + 통합) | **pass** | 단위: Phase-1.6 relay_IsIdempotent(PLAN.md:423), Phase-1.7 RetryExhausted_CallsFcgOnce(PLAN.md:442), Phase-3.3 WhenStockIncreaseFailsMidway(PLAN.md:701). 통합: Phase-4.1 stock-restore-duplicate.sh + fcg-pg-timeout.sh(PLAN.md:760-761). |
| 상태 전이 enum 소유권 | **pass** | Phase-1.3 `PaymentEventTest`·`PaymentOutboxTest` @EnumSource 커버(PLAN.md:334-338) 유지. |
| 금전 정확성 | **pass** | Phase-1.5 Toss/NicePay 대칭 금액 검증(PLAN.md:401-402) + M-3 stock-- 실패 시 outbox 미생성 + QUARANTINED로 이중 결제 경로 봉쇄. |
| PII 노출·저장 | **n/a** | 본 토픽 비목표(§ 1-3). |

## previous_round_ref

`docs/rounds/msa-transition/plan-domain-3.md` (Round 3, decision=pass)

## delta

- **newly_passed**:
  - C-1 Phase-3.1b user-service 모듈 신설 — ADR-22 product → user 순서 완성, 현 UserServiceImpl 관례와 대칭 겸임. 감사 원자성·가면 방어·PII 영향 없음.
  - C-2 Phase-2.3b 결제 서비스 측 어댑터 교체 — ADR-05/ADR-15 방어선 unchanged (port scope 분리 ARCH R4 RESOLVED로 실체화). PgEventConsumer 이중 수신 방어 계약 테스트 추가.
  - M-3 Phase-1.4 stock-- TX 경계 외부 방침 명시 + QUARANTINED 전이 테스트 신설. stock-- 실패 시 outbox 미생성으로 이중 결제 경로 원천 차단.
  - M-4 PG 무상태 + 관리자 읽기 전용 뷰 선언 — PG 상태 단일 진실원이 PG 벤더 API로 유지돼 FCG 불변과 일관.
  - M-5 토픽 네이밍 규약 `<source-service>.<type>.<action>` + 토픽 상수 중앙화 — consumer dedupe 계약(UUID 키)과 독립적, drift 방지로 운영 안전성 증가.
  - ARCH R4 RESOLVED `PaymentGatewayPort`=confirm/cancel / `PgStatusPort`=getStatus scope 명시 재정의 — Round 3 "중복 아님" 선언의 실체화. ADR-05 방어선 약화 경로 부존재.
- **newly_failed**: []
- **still_failing**: []

## 도메인 관점 추가 검토

### 1. C-1 Phase-3.1b 사용자 서비스 — 감사·가면·PII 영향 없음

user-service 분리는 사용자 조회(`GET /api/v1/users/{id}`) 단일 경로. 결제 상태 머신·PG 호출 경로·payment_history 감사 경로·outbox 릴레이와 무관. PLAN.md:665-671 산출물은 도메인 엔티티·port·Controller·Flyway V1 + 전용 MySQL. 현 `src/main/java/com/hyoguoo/paymentplatform/user/application/UserServiceImpl.java:13` `UserServiceImpl implements UserService` 관례(전체 프로젝트의 application service가 presentation port를 구현 겸임) 그대로 승계. `domain_risk=false` 타당.

**판정**: 도메인 리스크 후퇴 없음.

### 2. C-2 Phase-2.3b 결제 서비스 측 어댑터 교체 — 돈 경로 방어선 유지

세 구현체 교체가 동시에 일어난다. (i) `PgStatusHttpAdapter`(LocalPgStatusAdapter 퇴역) — `PgStatusPort` HTTP 구현. (ii) `PaymentGatewayKafkaCommandAdapter`(InternalPaymentGatewayAdapter 퇴역) — `PaymentGatewayPort` confirm/cancel Kafka 커맨드 구현. (iii) `PgEventConsumer` — PG 서비스 역방향 이벤트 수신.

도메인 관점 검증:

- **ADR-05 가면 응답 방어선(Phase-1.5)**: 현 `src/main/java/com/hyoguoo/paymentplatform/payment/infrastructure/gateway/nicepay/NicepayPaymentGatewayStrategy.java:102-107` `handleDuplicateApprovalCompensation`이 PG 재조회(`getPaymentInfoByTid`) 후 status + 금액 일치를 검증. 이 로직이 Phase-1.5에서 `PgMaskedSuccessHandler`로 이관되고, 재조회 경로는 `PgStatusPort`를 경유한다. ARCH R4 RESOLVED(PLAN.md:604)에 의해 `PaymentGatewayPort`(Kafka 커맨드)는 getStatus 메서드 비보유 — application 계층이 `PgStatusPort`(HTTP)를 직접 주입해 사용. 즉 Kafka 어댑터 교체가 가면 방어 재조회 경로에 끼어들 수 없음.
- **ADR-15 FCG 불변**: Phase-1.7 `OutboxProcessingService`의 FCG는 `PgStatusPort` 타임아웃 경로에 매여 있음. `PgStatusHttpAdapter`가 HTTP 503/timeout을 `RetryableException`으로 전파(PLAN.md:598)하고 `@CircuitBreaker`가 adapter 내부 메서드에만 부여되므로 FCG 경로 진입 시 timeout → QUARANTINED 불변이 유지된다.
- **`PgEventConsumer` 이중 수신 방어**: `consume_DuplicateEvent_ShouldDedupeByEventUuid`(PLAN.md:601)가 동일 eventUUID 2회 수신 시 상태 전이 1회만 보장. 실제 구현은 (a) 결제 서비스 측 `EventDedupeStore` 테이블 도입 또는 (b) 기존 `PaymentOutbox`/`PaymentEvent` 종결 재진입 거부 게이트(CONFIRM-FLOW-ANALYSIS §1-4 ③ `rejectReentry`, PITFALLS §1-1 종결 no-op) 활용 두 경로가 있다. PLAN.md가 어느 쪽인지 명시하지 않았지만, **두 경로 모두 이중 DONE·이중 FAILED 전이를 방지**한다 — `markPaymentAsDone` 종결 no-op, `executePaymentFailureCompensationWithOutbox` D12 가드가 이미 보호. 돈이 새는 경로는 closed. **plan 수준 구현 선택 모호성은 minor 메모 수준**이며 Round 3 pass 대비 도메인 리스크 후퇴로 승격할 근거는 없다.
- **`@CircuitBreaker` 경계**: PLAN.md:591 "`@CircuitBreaker`는 adapter 구현 내부 메서드에만 부여(port 인터페이스 오염 방지)" — 이전 Round에서 이미 선언된 원칙이 Phase-2.3b에도 일관 적용.

**판정**: 도메인 리스크 후퇴 없음.

### 3. M-3 Phase-1.4 재정의 — stock-- TX 경계 외부 + QUARANTINED 방어선

Phase-1.4가 이전에는 "stock-- + outbox 단일 TX" 개념을 모놀리스에서 승계하는 뉘앙스였다면, Round 4에서 명시적으로 **"stock-- 는 외부 호출 분리"**(PLAN.md:345)로 방침을 고정. 테스트 2건으로 분할(PLAN.md:351-352).

- **단일 TX 범위**: payment_event + payment_outbox (결제 서비스 DB 내부). `executePaymentConfirm_CommitsPaymentStateAndOutboxInSingleTransaction`이 이 계약 보호.
- **stock-- 실패 경로**: 외부 HTTP 호출(Phase-3.4 `ProductHttpAdapter`) 실패 → 결제 서비스 내부 TX 롤백 또는 QUARANTINED 전이 + **outbox 미생성** + 보상 이벤트(stock.restore) 발행 없음(stock-- 이 애초에 성공하지 않았으므로 복원 불필요). `executePaymentConfirm_WhenStockDecreaseFails_ShouldTransitionToQuarantineWithoutOutbox`가 이 계약 보호.

이 방침은 이중 결제 경로를 두 층으로 차단:
1. outbox 미생성 → `OutboxRelayService`/`OutboxProcessingService`의 orderId 픽업 경로 원천 제거 → Toss/NicePay API 호출 자체 차단
2. PaymentEvent QUARANTINED → 종결 상태 머신 재진입 거부 → 같은 orderId에 대한 confirm 재시도 경로 차단

PITFALLS §1(Outbox Worker Runs Before Transaction Commits)은 outbox 레코드를 TX 안에서 insert하라는 원칙인데, Phase-1.4 재정의가 이 원칙을 여전히 준수한다 — stock-- 실패 시 outbox 애초에 생성 안 함. TX commit 후 stock-- 성공/실패 모두 outbox에 PENDING 레코드 존재 → 릴레이가 Kafka로 publish → PG 서비스 confirm 경로 진입. 이 경로에서 stock-- 는 이미 성공한 상태이므로 PG confirm 실패 시 기존 보상 이벤트 `stock.restore`(Phase-3.3) 발행으로 복원.

**판정**: 도메인 리스크 후퇴 없음. 오히려 방어선이 명시화.

### 4. M-4 PG 무상태 + 관리자 읽기 전용 뷰

PG 서비스가 DB를 갖지 않는다는 것은 PG 상태의 **단일 진실원이 PG 벤더 API(Toss/NicePay)**임을 구조적으로 강제한다. 이는 ADR-15 FCG 불변 "timeout → QUARANTINED"와 일관: PG 자체 상태 캐시가 없으므로 결제 서비스의 `getStatus` 호출은 무조건 PG 벤더에 투과 → 벤더 timeout 시 FCG가 직접 QUARANTINED 전이. 관리자 읽기 전용 뷰는 결제 도메인 쓰기 경로에 개입하지 않음.

**판정**: 도메인 리스크 후퇴 없음.

### 5. M-5 토픽 네이밍 규약

`<source-service>.<type>.<action>` 규약과 `PaymentTopics`/`PgTopics`/`ProductTopics` 상수 중앙화는 토픽 이름 오타 → 이벤트 drop을 차단하는 운영 도구. consumer dedupe 계약(UUID 키, TTL 정량화, Phase-3.3)은 토픽 이름과 독립 — 멱등성 계약 불변.

Phase-3.3 산출물 표기 "stock.restore UUID 키"(PLAN.md:51)와 Phase-2.3 예시 "product.events.stock-restored"(PLAN.md:569) 용어 일관성은 critic 범위. 도메인 리스크 관점에서는 어느 이름이든 UUID 키 dedupe가 계약.

**판정**: 도메인 리스크 후퇴 없음. 오히려 drift 방지로 증가.

### 6. ARCH R4 RESOLVED `PaymentGatewayPort`/`PgStatusPort` scope 재정의

Round 3에서 PLAN.md:133 "중복 아님" 선언이 있었으나 실제 scope가 plan 수준에서 선명하지 않았다. Round 4에서 Phase-1.0이 "`PaymentGatewayPort`는 confirm / cancel 전담 (command 경로), `PgStatusPort`는 getStatus 단일 경로 전담 (FCG 격리용 조회 경로). 두 포트는 역할이 분리되며 상호 포함 관계 없음 — adapter→adapter 위임 금지."(PLAN.md:286)로 scope를 실체화. Phase-2.3b `PaymentGatewayKafkaCommandAdapter`는 getStatus 메서드 비보유(PLAN.md:604)로 이 원칙이 구현 수준에 반영.

도메인 관점:
- ADR-05 `PgMaskedSuccessHandler`의 PG 재조회 경로는 `PgStatusPort`에 단독 귀속 — Kafka 커맨드 어댑터와 섞일 여지 없음.
- ADR-15 FCG 불변은 `PgStatusPort` 타임아웃 경로에 귀속 — 동일.
- 현 소스 `src/main/java/com/hyoguoo/paymentplatform/payment/application/port/PaymentGatewayPort.java:12-25`의 4경로(confirm/cancel/getStatus/getStatusByOrderId) 중 getStatus 계열은 Phase 2 완료 시점에 `PgStatusPort`로 귀속되며 `PaymentGatewayPort`는 2경로(confirm/cancel)로 축소. port 책임 분리가 선명.

**판정**: 도메인 리스크 후퇴 없음. 오히려 경계 강화.

### 7. 참고 관찰 — minor 메모 (승격 없음)

- **Phase-2.3b `PgEventConsumer` dedupe 저장소 위치 모호**: 결제 서비스 측에 별도 `EventDedupeStore` 테이블을 두는지 `PaymentOutbox`/`PaymentEvent` 종결 재진입 거부로 대체하는지 PLAN.md에 명시 없음. 두 경로 모두 이중 DONE·이중 FAILED 전이 방어가 이미 작동(markPaymentAsDone 종결 no-op, D12 가드)하므로 돈 경로는 closed. execute 단계 구현 선택.
- **Phase-3.3 "stock.restore" vs Phase-2.3 "product.events.stock-restored" 용어 불일치**: critic 범위. 도메인 dedupe 계약은 UUID 키 기반으로 토픽 이름과 독립. execute 시점에 `ProductTopics.STOCK_RESTORED`로 통일.

두 건 모두 Round 3 pass 승계 범위에서도 허용 가능한 수준의 세부 모호성이며 Round 4 delta로 인해 도메인 리스크가 후퇴한 것이 아님.

## Findings

- **[n/a]** C-1 Phase-3.1b user-service 신설 — ADR-22 product → user 순서 완성. 현 UserServiceImpl 관례와 대칭 겸임. 감사·가면·PII 경로 영향 없음. 도메인 리스크 후퇴 없음.
- **[n/a]** C-2 Phase-2.3b 결제 서비스 측 어댑터 교체 — ADR-05(PgStatusPort 단독 귀속) / ADR-15(FCG PgStatusPort 경로) / ADR-16(PaymentOutbox 종결 게이트 + 옵션 dedupe store) 방어선 전부 유지. 도메인 리스크 후퇴 없음.
- **[n/a]** M-3 Phase-1.4 재정의 — stock-- TX 경계 외부 명시 + outbox 미생성 + QUARANTINED 이중 방어선으로 이중 결제 경로 원천 차단. 도메인 리스크 방어선 강화.
- **[n/a]** M-4 PG 무상태 + 관리자 읽기 전용 뷰 — PG 상태 단일 진실원 PG 벤더 API 유지로 FCG 불변과 일관. 영향 없음.
- **[n/a]** M-5 토픽 네이밍 규약 + 토픽 상수 중앙화 — consumer dedupe UUID 키 계약과 독립. drift 방지로 운영 안전성 증가.
- **[n/a]** ARCH R4 RESOLVED PaymentGatewayPort=confirm/cancel / PgStatusPort=getStatus scope 재정의 — Round 3 "중복 아님" 선언의 실체화. ADR-05 방어선 약화 경로 부존재.
- **[n/a]** Round 3 pass 승계 항목(F-15/F-16/F-17/F-18) — Round 4 재작성에서 유지.
- **[n/a]** Round 2 pass 승계 항목(Phase-1.5 Toss wiring / Phase-4.1 chaos 6종 / Phase-1.11 histogram / Phase-1.10 비활성화) — Round 4에서도 유지.
- **[n/a]** PII·보안 — 본 토픽 비목표(§ 1-3).

## JSON
```json
{
  "stage": "plan",
  "persona": "domain-expert",
  "round": 4,
  "task_id": null,
  "decision": "pass",
  "reason_summary": "Round 3 pass 승계. Round 4 delta 6건(C-1/C-2/M-3/M-4/M-5/ARCH R4 RESOLVED) 모두 도메인 리스크를 후퇴시키지 않는다. (C-1) Phase-3.1b user-service는 사용자 조회 전용으로 감사 원자성·가면 방어·PII 경로와 무관하며 현 UserServiceImpl implements UserService 관례와 겸임 패턴 대칭. (C-2) Phase-2.3b 결제 서비스 측 어댑터 교체는 ADR-05 가면 방어선(PgStatusPort 단독 귀속)·ADR-15 FCG 불변(PgStatusPort 타임아웃 경로)이 보존되고 PgEventConsumer 이중 수신 방어는 PaymentOutbox/PaymentEvent 종결 재진입 거부(rejectReentry + markPaymentAsDone 종결 no-op) 기존 방어선으로 이중 DONE/FAILED 전이가 이미 차단 — 돈 경로 closed. (M-3) Phase-1.4 stock-- TX 경계 외부 방침 + executePaymentConfirm_WhenStockDecreaseFails_ShouldTransitionToQuarantineWithoutOutbox 테스트는 outbox 미생성(릴레이 픽업 경로 원천 제거) + QUARANTINED 전이(상태 머신 재진입 거부) 이중 방어로 이중 결제 경로를 오히려 명시적으로 차단. (M-4) PG 무상태 + 관리자 읽기 전용 뷰는 PG 상태 단일 진실원을 PG 벤더 API로 고정해 FCG 불변과 일관. (M-5) 토픽 네이밍 규약 <source-service>.<type>.<action> + 토픽 상수 중앙화는 consumer dedupe UUID 키 계약과 독립적이며 drift 방지 효과만 존재. (ARCH R4 RESOLVED) PaymentGatewayPort=confirm/cancel / PgStatusPort=getStatus scope 명시 재정의로 Round 3 '중복 아님' 선언이 실체화되고 adapter→adapter 위임 경로 원천 차단. Round 3/2 pass 승계 항목 전부 Round 4에서 유지.",
  "checklist": {
    "source": "_shared/checklists/plan-ready.md",
    "items": [
      {
        "section": "domain risk",
        "item": "discuss에서 식별된 domain risk가 각각 대응 태스크를 가짐",
        "status": "yes",
        "evidence": "docs/MSA-TRANSITION-PLAN.md:833-842 추적 테이블 10건 매핑(C-1/C-2/M-5 신규 행 포함). domain_risk=true 15개(PLAN.md:849-850). Round 4 신규 orphan 없음."
      },
      {
        "section": "domain risk",
        "item": "중복 방지 체크가 필요한 경로에 계획됨",
        "status": "yes",
        "evidence": "Phase-3.3 UUID dedupe(PLAN.md:699-701) + Phase-2.3 consume_DuplicateCommand_ShouldDedupeByEventUuid(PLAN.md:576) + Phase-2.3b consume_DuplicateEvent_ShouldDedupeByEventUuid(PLAN.md:601) 3경로. 추가로 PaymentOutbox/PaymentEvent 종결 재진입 거부(rejectReentry) 기존 방어선이 결제 서비스 측 이중 수신 시 돈 경로를 closed 상태로 유지."
      },
      {
        "section": "domain risk",
        "item": "재시도 안전성 검증 태스크 존재 (단위 + 통합)",
        "status": "yes",
        "evidence": "단위: Phase-1.6 relay_IsIdempotent_WhenCalledTwice(PLAN.md:423), Phase-1.7 process_RetryExhausted_CallsFcgOnce(PLAN.md:442), Phase-3.3 restore_WhenStockIncreaseFailsMidway_ShouldNotRecordDedupe(PLAN.md:701), Phase-1.4 executePaymentConfirm_WhenStockDecreaseFails_ShouldTransitionToQuarantineWithoutOutbox(PLAN.md:352). 통합: Phase-4.1 stock-restore-duplicate.sh + fcg-pg-timeout.sh(PLAN.md:760-761)."
      }
    ],
    "total": 3,
    "passed": 3,
    "failed": 0,
    "not_applicable": 0
  },
  "scores": {
    "traceability": 0.96,
    "decomposition": 0.92,
    "ordering": 0.91,
    "specificity": 0.93,
    "risk_coverage": 0.94,
    "mean": 0.932
  },
  "findings": [
    {
      "severity": "n/a",
      "checklist_item": "C-1 Phase-3.1b user-service 모듈 신설 — 감사·가면·PII 후퇴 없음",
      "location": "docs/MSA-TRANSITION-PLAN.md:656-672 (Phase-3.1b)",
      "problem": "도메인 리스크 후퇴 없음.",
      "evidence": "사용자 조회 전용 경로(GET /api/v1/users/{id})로 결제 상태 머신·PG 호출·payment_history 감사 경로와 무관. 현 src/main/java/com/hyoguoo/paymentplatform/user/application/UserServiceImpl.java:13 'UserServiceImpl implements UserService' 관례와 PLAN.md:668 'UserQueryUseCase implements UserQueryService' 겸임이 정확히 대칭. domain_risk=false 타당. ADR-22 product → user 순서 완성.",
      "suggestion": "해당 없음."
    },
    {
      "severity": "n/a",
      "checklist_item": "C-2 Phase-2.3b 결제 서비스 측 어댑터 교체 — ADR-05/ADR-15/ADR-16 방어선 유지",
      "location": "docs/MSA-TRANSITION-PLAN.md:588-608 (Phase-2.3b)",
      "problem": "도메인 리스크 후퇴 없음.",
      "evidence": "ADR-05 PgMaskedSuccessHandler의 PG 재조회 경로는 PgStatusPort 단독 귀속(ARCH R4 RESOLVED에 의해 PaymentGatewayKafkaCommandAdapter는 getStatus 메서드 비보유). ADR-15 FCG 불변은 PgStatusPort 타임아웃 경로(Phase-1.7). PgEventConsumer 이중 수신 방어(PLAN.md:601 consume_DuplicateEvent_ShouldDedupeByEventUuid)는 구현 경로로 (a) 결제 서비스 측 EventDedupeStore 테이블 또는 (b) PaymentOutbox/PaymentEvent 종결 재진입 거부(CONFIRM-FLOW-ANALYSIS §1-4 ③ rejectReentry + markPaymentAsDone 종결 no-op + D12 가드) 선택 가능 — 두 경로 모두 이중 DONE/FAILED 전이를 차단하므로 돈 경로 closed. @CircuitBreaker adapter 내부 한정 원칙 유지(PLAN.md:591).",
      "suggestion": "minor 메모: PgEventConsumer dedupe 저장소 위치(결제 서비스 측 별도 테이블 vs PaymentOutbox 상태 게이팅)는 execute 단계 구현 선택. 도메인 안전성은 두 경로 모두 충족."
    },
    {
      "severity": "n/a",
      "checklist_item": "M-3 Phase-1.4 stock-- TX 경계 외부 + QUARANTINED 방어선",
      "location": "docs/MSA-TRANSITION-PLAN.md:342-355 (Phase-1.4)",
      "problem": "도메인 리스크 후퇴 없음. 방어선이 오히려 명시화.",
      "evidence": "PLAN.md:345 'Phase-1.4c 분리 이후 stock-- 는 외부 호출이므로 단일 TX 가정은 결제 서비스 DB 내부(payment_event + payment_outbox)에만 적용. stock-- 실패는 TX 경계 바깥이므로 QUARANTINED 전이로 방어.' PLAN.md:351-352 테스트 2건 분할: (a) executePaymentConfirm_CommitsPaymentStateAndOutboxInSingleTransaction — payment_event+payment_outbox 단일 TX 원자성 검증, (b) executePaymentConfirm_WhenStockDecreaseFails_ShouldTransitionToQuarantineWithoutOutbox — stock-- 실패 시 outbox 미생성 + QUARANTINED 전이 + 보상 불필요 검증. outbox 미생성 → OutboxRelayService/OutboxProcessingService 픽업 경로 원천 제거 → PG 호출 경로 봉쇄. QUARANTINED → 종결 상태 재진입 거부 → 같은 orderId 재처리 경로 차단. 이중 결제 경로를 두 층으로 차단.",
      "suggestion": "해당 없음."
    },
    {
      "severity": "n/a",
      "checklist_item": "M-4 PG 무상태 + 관리자 읽기 전용 뷰 — FCG 불변과 일관",
      "location": "docs/MSA-TRANSITION-PLAN.md:211 (Phase-0.1 DB 경계 방침)",
      "problem": "도메인 리스크 후퇴 없음.",
      "evidence": "PG는 Toss/NicePay HTTP + Kafka만, DB 미보유 — PG 상태 단일 진실원이 PG 벤더 API로 구조적으로 고정돼 ADR-15 FCG 불변(getStatus 직접 조회 + timeout → QUARANTINED)과 일관. 관리자 읽기 전용 뷰는 결제 도메인 쓰기 경로에 개입하지 않음.",
      "suggestion": "해당 없음."
    },
    {
      "severity": "n/a",
      "checklist_item": "M-5 토픽 네이밍 규약 + 토픽 상수 중앙화",
      "location": "docs/MSA-TRANSITION-PLAN.md:569, 582-584 (Phase-2.3)",
      "problem": "도메인 리스크 후퇴 없음.",
      "evidence": "consumer dedupe 계약(UUID 키, TTL 정량화, Phase-3.3)은 토픽 이름과 독립적. 토픽 이름 drift 방지로 이벤트 drop 리스크 감소. PaymentTopics/PgTopics/ProductTopics 상수 중앙화(PLAN.md:582-584)가 drift 방지 도구.",
      "suggestion": "minor 메모: Phase-3.3 'stock.restore' 표기(PLAN.md:51)와 Phase-2.3 'product.events.stock-restored' 예시(PLAN.md:569) 용어 일관성은 critic 범위. 도메인 dedupe UUID 키 계약은 이름과 독립."
    },
    {
      "severity": "n/a",
      "checklist_item": "ARCH R4 RESOLVED PaymentGatewayPort / PgStatusPort scope 재정의",
      "location": "docs/MSA-TRANSITION-PLAN.md:286, 604-605 (Phase-1.0, Phase-2.3b)",
      "problem": "도메인 리스크 후퇴 없음. Round 3 '중복 아님' 선언의 실체화로 경계 강화.",
      "evidence": "PLAN.md:286 'PaymentGatewayPort는 confirm / cancel 경로 전담 (command 경로). PgStatusPort는 getStatus 단일 경로 전담 (FCG 격리용 조회 경로). 두 포트는 역할이 분리되며 상호 포함 관계 없음 — adapter→adapter 위임 금지.' PLAN.md:604 PaymentGatewayKafkaCommandAdapter는 getStatus 메서드 비보유 — application 계층이 PgStatusPort를 직접 주입해 사용. 현 src/main/java/com/hyoguoo/paymentplatform/payment/application/port/PaymentGatewayPort.java:12-25의 4경로 중 getStatus/getStatusByOrderId는 Phase 2 완료 시점에 PgStatusPort로 귀속. Phase-1.5 PgMaskedSuccessHandler의 PG 재조회 경로는 PgStatusPort 단독 귀속이 되어 Kafka 어댑터 개입 경로 원천 차단.",
      "suggestion": "해당 없음."
    },
    {
      "severity": "n/a",
      "checklist_item": "Round 3 pass 승계 — F-15/F-16/F-17/F-18",
      "location": "docs/MSA-TRANSITION-PLAN.md:286-287, 384, 647, 11",
      "problem": "Round 3 pass 판정 근거 Round 4에서 유지.",
      "evidence": "F-15 PaymentGatewayPort 복제 + build.gradle compile 의존 제거(PLAN.md:286-287), F-16 빈 DB 시작 + migrate-pending-outbox.sh(PLAN.md:384), F-17 StockRestoreUseCase 겸임(PLAN.md:647), F-18 ARCH 범례 파일 상단.",
      "suggestion": "해당 없음."
    },
    {
      "severity": "n/a",
      "checklist_item": "Round 2 pass 승계 — Phase-1.5/1.10/1.11/4.1",
      "location": "docs/MSA-TRANSITION-PLAN.md:392-408, 489, 756, 760-761",
      "problem": "Round 2 pass 판정 근거 Round 4에서도 유지.",
      "evidence": "Phase-1.5 Toss 전략·ErrorCode 수정 + TossPaymentGatewayStrategyWiringTest(PLAN.md:392-408), Phase-4.1 stock-restore-duplicate.sh·fcg-pg-timeout.sh(PLAN.md:760-761), Phase-1.11 histogram p95 ≥ 10s(PLAN.md:756), Phase-1.10 @ConditionalOnProperty(matchIfMissing=false)(PLAN.md:489).",
      "suggestion": "해당 없음."
    },
    {
      "severity": "n/a",
      "checklist_item": "PII·보안",
      "location": "docs/topics/MSA-TRANSITION.md § 1-3",
      "problem": "본 토픽 비목표.",
      "evidence": "§ 1-3에서 보안 범위 명시적 비목표 선언. Phase-3.1b user-service 신설도 사용자 필드 노출·마스킹·암호화는 scope 밖.",
      "suggestion": "해당 없음."
    }
  ],
  "previous_round_ref": "docs/rounds/msa-transition/plan-domain-3.md",
  "delta": {
    "newly_passed": [
      "C-1 Phase-3.1b user-service 모듈 신설 — ADR-22 product → user 순서 완성, 현 UserServiceImpl 관례와 대칭 겸임",
      "C-2 Phase-2.3b 결제 서비스 측 어댑터 교체 — ADR-05/ADR-15 방어선 유지(PgStatusPort 단독 귀속), PgEventConsumer 이중 수신 방어 계약 추가",
      "M-3 Phase-1.4 stock-- TX 경계 외부 방침 명시 + QUARANTINED 전이 테스트 신설 — 이중 결제 경로 원천 차단",
      "M-4 PG 무상태 + 관리자 읽기 전용 뷰 — FCG 불변과 일관",
      "M-5 토픽 네이밍 규약 + 토픽 상수 중앙화 — consumer dedupe 계약과 독립적, drift 방지",
      "ARCH R4 RESOLVED PaymentGatewayPort=confirm/cancel / PgStatusPort=getStatus scope 재정의 — ADR-05 방어선 약화 경로 부존재"
    ],
    "newly_failed": [],
    "still_failing": []
  },
  "unstuck_suggestion": null
}
```
