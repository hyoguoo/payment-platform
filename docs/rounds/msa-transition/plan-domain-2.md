# plan-domain-2

**Topic**: MSA-TRANSITION
**Round**: 2
**Persona**: Domain Expert

## Reasoning

Round 1에서 지적한 2 major + 2 minor는 **모두 반영**됐다. (1) Phase-1.5 산출물이 `PgMaskedSuccessHandler` 클래스에 더해 **`TossPaymentGatewayStrategy` ALREADY_PROCESSED_PAYMENT 포착 분기 + `TossPaymentErrorCode.isSuccess()` 반환값 수정 + `TossPaymentGatewayStrategyWiringTest`의 wiring 호출 검증**까지 포함하도록 확장됐고(PLAN.md:231-249), (2) Phase-4.1에 `stock-restore-duplicate.sh`·`fcg-pg-timeout.sh` 2종이 추가돼(PLAN.md:552-553) consumer dedupe · FCG 불변이 통합 수준 재시도 안전성 검증을 받는다. (3) Phase-1.11 histogram 수락 기준이 `kafka-latency.sh`에 연결돼 "p95 ≥ 10s Prometheus 쿼리로 관측"이 chaos 수락 기준으로 명시됐다(PLAN.md:548). (4) Phase-1.10이 "Gateway 라우팅 + 모놀리스 결제 경로 비활성화"로 재정의돼 `OutboxImmediateEventHandler`와 `PaymentController` confirm 엔드포인트에 `@ConditionalOnProperty` 기본=비활성화를 심는 산출물이 박혔다(PLAN.md:320-331). Round 2에 신설된 태스크(Phase-1.0 cross-context port 복제, Phase-1.4b AOP 복제, Phase-1.4c Flyway V1, Phase-2.1b PG AOP, Phase-3.3 `StockRestoreUseCase`/`EventDedupeStore` 분리)는 도메인 리스크 측면에서 **기존 원자성·멱등성·PG 가면 방어선을 약화시키지 않는다** — 오히려 소유권·TX 경계를 선명히 한다. Phase 1 기간 보상 경로 원칙(PLAN.md:114: "`stock.restore` 보상은 결제 서비스 내부 동기 호출 유지, 이벤트화는 Phase 3과 동시")도 명시돼 이행 구간 공백이 닫혔다. Toss 금액 검증 대칭은 Phase-1.5 테스트 `handle_Toss_AlreadyProcessed_VerifiesAmountSymmetry`(PLAN.md:243)로 수락되고 NicePay 2201 대칭도 동일 테스트 블록에 보존됐다. 다만 NicePay 측 `2201` 경로는 현 모놀리스 `NicepayPaymentGatewayStrategy.handleDuplicateApprovalCompensation`(src/main/java/.../nicepay/NicepayPaymentGatewayStrategy.java:102-134)가 **이미 구현되어 있다** — Phase-1.5의 산출물은 "이관 + 검증 테스트 대칭"으로 충분하며 신규 구현 리스크가 아니다. Round 1 findings가 모두 해소되고 남은 관찰 사항은 참고성 minor 수준이므로 **pass**.

## Domain risk checklist

| 항목 | 판정 | 근거 |
|---|---|---|
| Round 1 major #1 (Phase-1.5 Toss wiring 완결) 반영 | **pass** | PLAN.md:231-249 — 산출물 3건(Toss 전략 ALREADY_PROCESSED_PAYMENT 분기 + `TossPaymentErrorCode.isSuccess()` 수정 + wiring 테스트 `TossPaymentGatewayStrategyWiringTest#confirm_WhenAlreadyProcessedPayment_ShouldInvokePgMaskedSuccessHandler`) 전부 추가됨. 제안 3건 1:1 매핑. |
| Round 1 major #2 (Phase-4.1 chaos 시나리오 확장) 반영 | **pass** | PLAN.md:552-553 — `stock-restore-duplicate.sh`(동일 UUID 2회 발행 + 재고 증가량 1회 검증)와 `fcg-pg-timeout.sh`(Toxiproxy PG getStatus timeout 주입 + PaymentEvent QUARANTINED 확인) 두 시나리오 신설. Phase-4.1 제목도 "6종 장애 주입"으로 갱신(PLAN.md:542). |
| Round 1 minor #3 (Phase-1.11 histogram chaos 수락 기준) 반영 | **pass** | PLAN.md:548 — `kafka-latency.sh` 수락 기준에 "`payment.outbox.pending_age_seconds` histogram p95 ≥ 10s가 Prometheus 쿼리로 관측됨" 명시. 단위 테스트(PLAN.md:344)와 chaos 관측 연결 확보. |
| Round 1 minor #4 (Phase-1.10 모놀리스 결제 경로 비활성화) 반영 | **pass** | PLAN.md:320-331 — Phase-1.10 제목·목적·산출물이 "Gateway 라우팅 + 모놀리스 결제 confirm 경로 비활성화"로 재정의. `OutboxImmediateEventHandler`에 `@ConditionalOnProperty("payment.monolith.confirm.enabled", havingValue="true", matchIfMissing=false)`, `PaymentController` confirm 엔드포인트 동일 처리 또는 501 응답 — 이중 발행 경로 차단. |
| discuss risk(ADR-05/13/15/16/20) 태스크 매핑 | **pass** | PLAN.md:625-631 추적 테이블 7건 매핑 유지 + Round 2 신규 태스크(Phase-1.4b/1.4c/Phase-1.10)로 세분화. orphan 없음. `domain_risk=true` 14개(PLAN.md:638). |
| 중복 방지 체크(existsByOrderId/eventUuid) 계획 | **pass** | Phase-3.3 `StockRestoreUseCaseTest#restore_DuplicateEventUuid_ShouldNoOp`·`StockRestoreUseCaseTest#restore_AfterDedupeTtlExpiry_ShouldReprocessOnce`·`restore_WhenStockIncreaseFailsMidway_ShouldNotRecordDedupe`(PLAN.md:491-493) + Phase-2.3 `consume_DuplicateCommand_ShouldDedupeByEventUuid`(PLAN.md:416). |
| 재시도 안전성 검증 태스크 (단위 + 통합) | **pass** | 단위: Phase-1.6 `relay_IsIdempotent_WhenCalledTwice`(PLAN.md:264), Phase-1.7 `process_RetryExhausted_CallsFcgOnce`(PLAN.md:283), Phase-3.3 `restore_WhenStockIncreaseFailsMidway_ShouldNotRecordDedupe`(PLAN.md:493). 통합: Phase-4.1 `stock-restore-duplicate.sh`·`fcg-pg-timeout.sh`(PLAN.md:552-553). |
| ADR-05 가면 응답 방어 Phase 1 필수 산출물 런타임 관철 | **pass** | Phase-1.5가 handler 생성뿐 아니라 Toss 전략 소스 수정·`isSuccess()` 반환값 변경까지 산출물에 명시 — 현 `TossPaymentErrorCode.java:70-72` 경로가 이번 phase에서 실제로 닫힘. `TossPaymentGatewayStrategyWiringTest`가 런타임 호출 경로까지 검증. |
| ADR-05 Toss/NicePay 금액 검증 대칭 | **pass** | Phase-1.5 테스트 `handle_Toss_AlreadyProcessed_VerifiesAmountSymmetry`(PLAN.md:243) — Toss 경로에서도 PG `getStatus` + 금액 일치 검증 수행 요구. NicePay 기존 `handleDuplicateApprovalCompensation`(src/main/java/.../nicepay/NicepayPaymentGatewayStrategy.java:102-134)와 대칭. |
| NicePay 전략 `2201` 경로 동일 유지 | **pass** | PLAN.md:242 `handle_Nicepay_2201_VerifiesAmountBeforeDecision` 테스트가 `PgMaskedSuccessHandler`에 대칭 배치 — 결제 서비스로 이관 후에도 기존 NicePay 로직이 PG 재조회 + 금액 일치 검증을 유지. Phase-2.1에서 `NicepayPaymentGatewayStrategy` 자체가 PG 서비스로 이관될 때(PLAN.md:374)도 로직 변경 요구 없음 — 이관만. |
| ADR-16 보상 이벤트 consumer dedupe (UUID 키, 상품 서비스 소유) | **pass** | Phase-3.3 산출물이 `StockRestoreConsumer`·`JdbcEventDedupeStore`·`JpaEventDedupeRepository`·`V2__add_event_dedupe_table.sql`로 3분할(PLAN.md:495-499). consumer 얇음 검증 `consume_ShouldDelegateToStockRestoreUseCase`(PLAN.md:494). TTL = "Kafka consumer group offset retention + 1일" 정량화(PLAN.md:484). |
| ADR-15 FCG 불변(timeout → QUARANTINED) 단위 + 통합 | **pass** | 단위: Phase-1.7 `process_WhenFcgPgCallTimesOut_ShouldQuarantine`·`process_WhenFcgPgReturns5xx_ShouldQuarantine`·`process_RetryExhausted_CallsFcgOnce`(PLAN.md:280-283). 통합: Phase-4.1 `fcg-pg-timeout.sh`(PLAN.md:553). |
| ADR-13 감사 원자성(payment_history 결제 서비스 DB 잔류) | **pass** | Phase-1.4 `onPaymentStatusChange_InsertsHistoryBeforeCommit`(PLAN.md:196) + Phase-1.4b AOP 복제(PLAN.md:208-212) + Phase-1.4c Flyway V1(PLAN.md:223-225)로 3단 분해. BEFORE_COMMIT 원자성이 결제 서비스 내부 TX에 남음. |
| Phase 1 이행 구간 `stock.restore` 보상 경로 공백 방지 | **pass** | PLAN.md:114 — "Phase 1 보상 경로 원칙: `stock.restore` 보상은 **결제 서비스 내부 동기 호출 유지**(`InternalProductAdapter` 방식 승계). 이벤트화는 Phase 3(상품 분리)과 동시에 진행한다. 이행 구간 이중 복원 방어선 공백 방지." discuss-domain-2 minor finding (iii) 반영. |
| Strangler Fig 이중 발행 방지 | **pass** | Phase-1.10에서 모놀리스 `OutboxImmediateEventHandler` + `PaymentController` confirm 비활성화(PLAN.md:330-331). 게다가 Phase-1.4c(결제 서비스 독립 DB) 이후 모놀리스 DB의 `payment_*` 테이블 소유가 분리되므로 동일 outbox 레코드 이중 발행 경로는 Phase-1.10 비활성화와 함께 차단. ARCH R2 주석(PLAN.md:227)이 "초기 스냅샷 방침 미명시"를 지적 — Critic 영역이며 본 판정 미산입. |
| Round 2 신규 태스크 도메인 안전성 | **pass** | Phase-1.0(InternalProductAdapter/InternalUserAdapter 경계) — 금액/재고 로직 불변, 단순 경계 슬라이스. Phase-1.4b(AOP 복제) — `@PublishDomainEvent`·`@PaymentStatusChange` no-op 방지, 감사 경로 유지. Phase-1.4c(Flyway V1) — 결제 서비스 DB 소유권 확립, `payment_history` 결제 서비스 DB 잔류(ADR-13) 실체화. Phase-2.1b(PG AOP 복제) — 메트릭 경로 복제, 결제 로직 변화 없음. Phase-3.3 3분할 — consumer 얇음 + usecase · dedupe store port 분리는 테스트성·TX 경계를 강화. 모두 도메인 리스크 증가 없음. |
| 상태 전이 enum 소유권 | **pass** | Phase-1.3이 `PaymentEventStatus`·`PaymentOutbox` 상태 전이 테스트를 전 종결 상태(DONE/FAILED/CANCELED/EXPIRED)에 `@ParameterizedTest @EnumSource`로 커버(PLAN.md:177-181). container-per-service에서 결제 서비스 단일 소유. |
| claim race · 재시도 한도 소진 | **pass** | Phase-1.7 `process_RetryExhausted_CallsFcgOnce`(PLAN.md:283) — FCG 1회만 호출. `claimToInFlight` REQUIRES_NEW는 기존 `OutboxProcessingService` 이관에 포함(PLAN.md:284). |
| 금전 정확성 | **pass** | 금액 위변조 선검증은 결제 서비스 로컬 단계 유지(§ 2-2 그림). Phase-1.5가 Toss 경로에도 PG `getStatus` + 금액 일치 검증을 대칭 요구. |
| PII 노출·저장 | **n/a** | 본 토픽 비목표(topic.md § 1-3). |

## 도메인 관점 추가 검토

### 1. Round 1 findings 반영 상태 — 4건 전부 해소

- **major #1 (Phase-1.5 Toss wiring)**: Round 1 suggestion 3건이 1:1로 산출물에 박혔다.
  - `TossPaymentGatewayStrategy.java` ALREADY_PROCESSED_PAYMENT 포착 분기 추가 (PLAN.md:248 — "NicePay `handleDuplicateApprovalCompensation` 대칭")
  - `TossPaymentErrorCode.java` `isSuccess()` 반환값 수정 (PLAN.md:249 — "가면 응답을 success로 취급하지 않음")
  - `TossPaymentGatewayStrategyWiringTest#confirm_WhenAlreadyProcessedPayment_ShouldInvokePgMaskedSuccessHandler` (PLAN.md:245 — "Toss confirm 경로가 `ALREADY_PROCESSED_PAYMENT` 수신 시 `PgMaskedSuccessHandler.handle()` 1회 호출 검증")

  현 소스(`src/main/java/com/hyoguoo/paymentplatform/paymentgateway/exception/common/TossPaymentErrorCode.java:70-72` `isSuccess` 반환 `true`)가 이번 phase에서 실제로 닫힘을 확인했다. `TossPaymentGatewayStrategy.determineConfirmResultStatus`(소스 라인 126-141)의 `STATUS_DONE → SUCCESS` 매핑 경로는 유지되지만, 이건 Toss가 합법적으로 `DONE`을 반환한 경우이며 가면 응답(`ALREADY_PROCESSED_PAYMENT`) 경로는 ErrorCode 분기로 들어온다. 분기 수정으로 가면 경로가 닫히면 ADR-05 수락 기준 1-4가 런타임에 관철된다. **수락**.

- **major #2 (Phase-4.1 chaos 확장)**: Round 1 suggestion 2건이 1:1로 산출물에 박혔다.
  - `chaos/scenarios/stock-restore-duplicate.sh` — 동일 UUID 2회 발행 + 재고 증가 1회 확인 (PLAN.md:552)
  - `chaos/scenarios/fcg-pg-timeout.sh` — PG getStatus timeout 주입 + PaymentEvent QUARANTINED 확인 (PLAN.md:553)

  Phase-4.1 제목이 "6종 장애 주입"으로 갱신됨. **수락**.

- **minor #3 (Phase-1.11 histogram chaos)**: `kafka-latency.sh` 수락 기준에 "histogram p95 ≥ 10s Prometheus 쿼리 관측" 명시(PLAN.md:548). 단위 기록과 chaos 관측이 연결됐다. **수락**.

- **minor #4 (모놀리스 결제 경로 비활성화)**: Phase-1.10 제목·목적·산출물이 재정의돼 `@ConditionalOnProperty("payment.monolith.confirm.enabled", havingValue="true", matchIfMissing=false)`로 기본 비활성화를 강제. Admin UI 경유 직접 호출 경로도 이 프로퍼티 하나로 차단. **수락**.

### 2. Round 2 신규 태스크(5건) 도메인 리스크 평가

다섯 건 모두 **도메인 리스크를 증가시키지 않는다**.

- **Phase-1.0 (cross-context port 복제 + InternalAdapter 승계)**: 결제 서비스가 모놀리스 `product/`·`user/` 패키지를 직접 import하는 경계를 차단. Phase 1 기간에는 `InternalProductAdapter`가 모놀리스 내부 Java 호출을 래핑하므로 **금액 검증·재고 감소 로직이 전혀 변경되지 않는다**. Phase 3에서 HTTP 어댑터로 교체될 때 TX 경계가 분리되지만 이는 Phase-3.4 테스트와 Phase-4.1 `stock-restore-duplicate.sh`로 방어된다. 또 `paymentgateway` 경계 단절은 ARCH R2 주석(PLAN.md:132)에 지적되어 Architect 영역에서 처리 중 — 도메인 관점으로는 Phase 2에서 PG 서비스 분리 시 네트워크 홉이 생기므로 ADR-15 × ADR-21 상호 참조(topic.md:499-509)가 이 경계를 흡수한다.

- **Phase-1.4b (AOP 축 복제)**: `@PublishDomainEvent`·`@PaymentStatusChange` 어노테이션이 결제 서비스 패키지로 복제되고 `DomainEventLoggingAspect`·`PaymentStatusMetricsAspect`도 복제. **감사 원자성은 `PaymentHistoryEventListener` BEFORE_COMMIT가 담당**하므로(topic.md:489 재해석 리스크) AOP 복제 자체는 원자성을 건드리지 않는다. 오히려 cross-service 공유 금지로 서비스별 소유권이 명확해진다. **도메인 리스크 증가 없음**.

- **Phase-1.4c (Flyway V1)**: `payment_event`·`payment_order`·`payment_outbox`·`payment_history` 테이블 DDL을 결제 서비스 Flyway V1으로 실체화. ADR-13 "결제 서비스 DB 잔류"의 실체 산출물. 다만 ARCH R2 주석(PLAN.md:227)이 "모놀리스 DB → 결제 서비스 DB 데이터 이행 절차 또는 초기 스냅샷 방침" 미명시를 지적 — 도메인 관점에서 보면, 이행 방침이 **미종결 `IN_FLIGHT` 레코드를 수동 재처리 없이 빈 상태로 시작**하는 경우 결제 서비스가 기존 미종결 건을 복구하지 못할 가능성이 있다. 그러나 이 리스크는 Strangler Fig 기간 Gateway 라우팅 전환 타이밍과 함께 처리되며, Phase-1.10에서 모놀리스 confirm 경로 비활성화 타이밍에 미종결 잔여 건이 없도록 운영 전환 절차를 plan이 아닌 operational runbook에 둘 수 있다. **본 라운드에서는 데이터 이행 절차 부재가 도메인 리스크 관점에서 `critical`로 승격될 만큼의 근거가 부족**하며(Phase 1 완료 시점에 결제 서비스는 신규 트래픽만 처리하면 되므로), `minor` 수준 참고 사항으로 남긴다.

- **Phase-2.1b (PG AOP 복제)**: `@TossApiMetric`·`TossApiMetricsAspect`의 PG 서비스 소유. 메트릭 경로만 복제. **도메인 리스크 증가 없음**.

- **Phase-3.3 3분할 (`StockRestoreUseCase` + `EventDedupeStore` port 분리)**: consumer 얇음 원칙이 강화돼 dedupe 로직이 port 경계 뒤로 숨는다. 테스트는 `FakeEventDedupeStore`로 TTL 만료를 시뮬레이션 가능(PLAN.md:477). **도메인 리스크 감소** — 이전 모놀리식 consumer 구조보다 테스트성·재시도 안전성이 증가.

### 3. NicePay 전략 대칭 확인

plan-domain-1의 Phase-1.5에는 `handle_Nicepay_2201_VerifiesAmountBeforeDecision`(PLAN.md:242)이 보존됐다. 이 경로는 현 모놀리스 `NicepayPaymentGatewayStrategy.handleDuplicateApprovalCompensation`(src/main/java/.../nicepay/NicepayPaymentGatewayStrategy.java:102-134)를 결제 서비스로 이관하는 형태로, 기존 로직(`NICEPAY_ERROR_CODE_DUPLICATE_APPROVAL` 캐치 → `getPaymentInfoByTid` 재조회 → `NICEPAY_STATUS_PAID` AND 금액 일치 → `SUCCESS`)이 그대로 유지된다. Phase-2.1에서 `NicepayPaymentGatewayStrategy`가 PG 서비스로 이관될 때(PLAN.md:374)도 변경 요구 없음. **Toss는 신규 구현, NicePay는 이관만** — 비대칭 구현 리스크 없음.

### 4. Phase 1 `stock.restore` 보상 경로 공백 확인

PLAN.md:114에 "Phase 1 보상 경로 원칙: `stock.restore` 보상은 결제 서비스 내부 동기 호출 유지(`InternalProductAdapter` 방식 승계). 이벤트화는 Phase 3(상품 분리)과 동시에 진행"이 명시됐다. 이는 discuss-domain-2의 minor finding (iii)(§ 7 행 #9 이행 구간 공백)에 정확히 대응한다. Phase-1.0 `InternalProductAdapter`가 모놀리스 내부 Java 호출을 래핑하므로 Phase 1 기간 보상은 **결제 서비스 로컬 TX + 모놀리스 상품 Java 호출**로 이뤄진다 — 이중 복원 경로가 열리지 않는다. Phase 3 이벤트화와 동시에 consumer dedupe가 활성화되므로 **이행 구간 공백이 존재하지 않는다**. **수락**.

### 5. 참고 관찰 — 승격 없음 (minor 수준 메모)

- Phase-1.4c Flyway V1에서 "모놀리스 DB → 결제 서비스 DB 데이터 이행 절차"가 plan에 명시되지 않음. ARCH R2 주석(PLAN.md:227)이 동일 지적. 도메인 관점에서는 미종결 `IN_FLIGHT` 레코드가 존재한다면 Phase 1 전환 시점에 운영 절차로 처리해야 하나, 이는 plan 수준이 아닌 operational runbook 수준으로 판단. 본 판정에 영향 없음.
- Phase-2.3 PG 서비스 consumer의 `DUPLICATE_ATTEMPT` 매핑(PLAN.md:417)은 도메인 중립 enum 방향을 잘 따른다. NicePay `2201` → `DUPLICATE_ATTEMPT` 매핑 시 현 `handleDuplicateApprovalCompensation`의 "tid 재조회 + 금액 일치 검증" 책임이 PG 서비스 쪽에 남는지 결제 서비스 쪽에 남는지가 Phase-2.1 목적문(PLAN.md:362 — "application 서비스(`PgStatusServiceImpl`)에 귀속")으로 명시됨. **OK**.
- Phase-1.5와 Phase-2.1의 PG 가면 방어 경계: Phase 1에는 결제 서비스 내부 `PgMaskedSuccessHandler`가 방어하고, Phase 2에서 PG 서비스 분리 시 `DUPLICATE_ATTEMPT` 매핑은 PG 서비스 application 계층으로 이동. 이 경계 이동에서 결제 서비스 `PgMaskedSuccessHandler`는 Phase 2 이후에도 "DB 재조회 + QUARANTINED" 방어를 유지해야 한다(`DUPLICATE_ATTEMPT` 이벤트 수신 → consumer 측에서 DB 재조회). Phase-2.3 테스트 `consume_WhenPgReturnsAlreadyProcessed_ShouldMapToDuplicateAttempt`(PLAN.md:417)가 매핑 자체는 검증하나, **결제 서비스 consumer의 후속 DB 재조회 + QUARANTINED 방어선**은 Phase-2.3 테스트에 별도로 드러나지 않는다. 다만 Phase-1.5의 `PgMaskedSuccessHandler`가 이미 `PgStatusPort` 경유로 PG 재조회 + 금액 일치 검증 경로를 가지므로, Phase 2 이후에도 동일 핸들러를 재사용하면 방어선이 유지된다. **OK** — plan 수준에서 지적할 공백 없음.

## Findings

- **[n/a]** Round 1 major #1 (Phase-1.5 Toss wiring) — 반영 완료. Toss 전략 수정·`isSuccess()` 수정·wiring 테스트 3건 모두 산출물에 추가(PLAN.md:245-249).
- **[n/a]** Round 1 major #2 (Phase-4.1 chaos 확장) — 반영 완료. `stock-restore-duplicate.sh`·`fcg-pg-timeout.sh` 2건 산출물에 추가(PLAN.md:552-553).
- **[n/a]** Round 1 minor #3 (histogram chaos 수락 기준) — 반영 완료. `kafka-latency.sh`에 "p95 ≥ 10s Prometheus 쿼리 관측"(PLAN.md:548).
- **[n/a]** Round 1 minor #4 (모놀리스 결제 경로 비활성화) — 반영 완료. `@ConditionalOnProperty` 기본=비활성화 산출물(PLAN.md:330-331).
- **[n/a]** PII·보안은 본 토픽 비목표.

## JSON
```json
{
  "stage": "plan",
  "persona": "domain-expert",
  "round": 2,
  "task_id": null,
  "decision": "pass",
  "reason_summary": "Round 1 findings 2 major + 2 minor가 모두 Round 2 PLAN에 1:1로 반영됐다. Phase-1.5는 Toss 전략·ErrorCode 수정 + wiring 테스트까지 산출물에 포함, Phase-4.1은 stock-restore-duplicate.sh·fcg-pg-timeout.sh 2종 신규, Phase-1.11은 kafka-latency.sh 수락 기준에 histogram p95 관측 연결, Phase-1.10은 모놀리스 confirm 경로 @ConditionalOnProperty 비활성화를 명시. Phase 1 보상 경로 원칙(결제 서비스 내부 동기 호출 유지)도 PLAN.md:114에 phase gating으로 박혔고 Round 2 신규 태스크 5건(Phase-1.0/1.4b/1.4c/2.1b/3.3 3분할)은 도메인 리스크를 증가시키지 않는다.",
  "checklist": {
    "source": "_shared/checklists/plan-ready.md",
    "items": [
      {
        "section": "domain risk",
        "item": "discuss에서 식별된 domain risk가 각각 대응 태스크를 가짐",
        "status": "yes",
        "evidence": "docs/MSA-TRANSITION-PLAN.md:625-631 추적 테이블 7건 매핑. domain_risk=true 14개(PLAN.md:638)."
      },
      {
        "section": "domain risk",
        "item": "중복 방지 체크가 필요한 경로에 계획됨",
        "status": "yes",
        "evidence": "Phase-3.3 dedupe 테이블(JdbcEventDedupeStore/JpaEventDedupeRepository/V2 마이그레이션) + Phase-3.3 테스트 restore_DuplicateEventUuid_ShouldNoOp·restore_AfterDedupeTtlExpiry_ShouldReprocessOnce·restore_WhenStockIncreaseFailsMidway_ShouldNotRecordDedupe + Phase-2.3 consume_DuplicateCommand_ShouldDedupeByEventUuid"
      },
      {
        "section": "domain risk",
        "item": "재시도 안전성 검증 태스크 존재 (단위 + 통합)",
        "status": "yes",
        "evidence": "단위: Phase-1.6 relay_IsIdempotent_WhenCalledTwice, Phase-1.7 process_RetryExhausted_CallsFcgOnce, Phase-3.3 restore_WhenStockIncreaseFailsMidway_ShouldNotRecordDedupe. 통합: Phase-4.1 chaos/scenarios/stock-restore-duplicate.sh + fcg-pg-timeout.sh (PLAN.md:552-553)"
      }
    ],
    "total": 3,
    "passed": 3,
    "failed": 0,
    "not_applicable": 0
  },
  "scores": {
    "traceability": 0.94,
    "decomposition": 0.90,
    "ordering": 0.88,
    "specificity": 0.90,
    "risk_coverage": 0.92,
    "mean": 0.908
  },
  "findings": [
    {
      "severity": "n/a",
      "checklist_item": "Round 1 major #1 — Phase-1.5 Toss wiring 완결",
      "location": "docs/MSA-TRANSITION-PLAN.md:231-249 (Phase-1.5)",
      "problem": "반영 완료.",
      "evidence": "PLAN.md:248 (TossPaymentGatewayStrategy ALREADY_PROCESSED_PAYMENT 포착 분기 추가); PLAN.md:249 (TossPaymentErrorCode.isSuccess 반환값 수정); PLAN.md:245 (TossPaymentGatewayStrategyWiringTest#confirm_WhenAlreadyProcessedPayment_ShouldInvokePgMaskedSuccessHandler)",
      "suggestion": "해당 없음 — Round 1 제안 3건 1:1 매핑 확인."
    },
    {
      "severity": "n/a",
      "checklist_item": "Round 1 major #2 — Phase-4.1 chaos 시나리오 확장",
      "location": "docs/MSA-TRANSITION-PLAN.md:552-553 (Phase-4.1)",
      "problem": "반영 완료.",
      "evidence": "PLAN.md:552 (chaos/scenarios/stock-restore-duplicate.sh — 동일 UUID 2회 발행, 재고 증가 1회 확인); PLAN.md:553 (chaos/scenarios/fcg-pg-timeout.sh — Toxiproxy PG getStatus timeout, QUARANTINED 확인); Phase-4.1 제목도 '6종 장애 주입'으로 갱신(PLAN.md:542)",
      "suggestion": "해당 없음."
    },
    {
      "severity": "n/a",
      "checklist_item": "Round 1 minor #3 — Phase-1.11 histogram chaos 수락 기준",
      "location": "docs/MSA-TRANSITION-PLAN.md:548 (Phase-4.1 kafka-latency.sh)",
      "problem": "반영 완료.",
      "evidence": "PLAN.md:548 — 'payment.outbox.pending_age_seconds histogram p95 ≥ 10s가 Prometheus 쿼리로 관측됨' 수락 기준 명시",
      "suggestion": "해당 없음."
    },
    {
      "severity": "n/a",
      "checklist_item": "Round 1 minor #4 — 모놀리스 결제 경로 비활성화",
      "location": "docs/MSA-TRANSITION-PLAN.md:320-331 (Phase-1.10)",
      "problem": "반영 완료.",
      "evidence": "PLAN.md:330 (OutboxImmediateEventHandler에 @ConditionalOnProperty('payment.monolith.confirm.enabled', havingValue='true', matchIfMissing=false)); PLAN.md:331 (PaymentController confirm 엔드포인트 동일 처리 또는 HTTP 501 응답)",
      "suggestion": "해당 없음."
    },
    {
      "severity": "n/a",
      "checklist_item": "PII·보안",
      "location": "docs/topics/MSA-TRANSITION.md:141 (§ 1-3)",
      "problem": "본 토픽 비목표",
      "evidence": "§ 1-3에서 보안 범위 명시적 비목표 선언",
      "suggestion": "해당 없음"
    }
  ],
  "previous_round_ref": "docs/rounds/msa-transition/plan-domain-1.md",
  "delta": {
    "newly_passed": [
      "Phase-1.5 PgMaskedSuccessHandler wiring — Toss 전략 분기·isSuccess 수정·wiring 테스트 3건 산출물 추가",
      "Phase-4.1 chaos 시나리오 보상 이벤트 중복 주입·FCG PG timeout — 2건 시나리오 신설",
      "Phase-1.11 pending_age_seconds histogram chaos 수락 기준 — kafka-latency.sh p95 임계값 관측 명시",
      "Phase-1.10 모놀리스 결제 경로 비활성화 — @ConditionalOnProperty 기본=비활성화 산출물 추가"
    ],
    "newly_failed": [],
    "still_failing": []
  },
  "unstuck_suggestion": null
}
```
