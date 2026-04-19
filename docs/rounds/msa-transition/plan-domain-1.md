# plan-domain-1

**Topic**: MSA-TRANSITION
**Round**: 1
**Persona**: Domain Expert

## Reasoning

Plan은 discuss에서 승격된 6개 domain risk를 전부 태스크에 매핑했고 (Phase-1.4/1.5/1.6/1.7/1.11/3.3), Phase 1 이행 구간 보상 경로 소유자(`결제 서비스 내부 동기 호출 유지`)도 line 125에 phase gating으로 박혔다. Toss `ALREADY_PROCESSED_PAYMENT` 금액 대칭은 `handle_Toss_AlreadyProcessed_VerifiesAmountSymmetry` 테스트로 명시되고, dedupe TTL도 "Kafka consumer group offset retention + 1일"로 정량화되었다. 그러나 **돈이 새는 실제 경로** 관점에서 두 건의 major 공백이 남는다 — (1) Phase-1.5가 `PgMaskedSuccessHandler` 신규 클래스 생성만 다루고 **confirm 경로에 실제 wiring**(TossPaymentGatewayStrategy의 short-circuit 제거·`TossPaymentErrorCode.isSuccess()` 경로 차단·OutboxProcessingService 진입점)을 산출물로 선언하지 않아, 클래스가 생기고도 런타임에 우회될 dead-code 리스크가 있다. (2) Phase-4.1 Toxiproxy 시나리오가 `stock.restore` 중복 수신 주입을 포함하지 않아, Phase-3.3의 consumer dedupe가 **통합 수준 재시도 안전성 검증**을 받지 못한다 — plan-ready.md "재시도 안전성 검증 태스크 존재" 항목이 단위 테스트만으로 충족된 상태. major만 있으므로 **revise**.

## Domain risk checklist

| 항목 | 판정 | 근거 |
|---|---|---|
| discuss risk → 태스크 매핑 (ADR-05/13/15/16/20) | **pass** | PLAN.md line 630-637 추적 테이블. Phase-1.5 (ADR-05), Phase-1.4 (ADR-13), Phase-1.7 (ADR-15), Phase-3.3 (ADR-16), Phase-1.11 (ADR-20) 전부 매핑. `domain_risk=true` 라벨 부여. |
| 중복 방지 체크(existsByOrderId 등) 계획 | **pass** | Phase-3.3 test `consume_DuplicateEventUuid_ShouldNoOp` + dedupe 테이블 `V2__add_event_dedupe_table.sql` + Phase-2.3 `consume_DuplicateCommand_ShouldDedupeByEventUuid` 테스트로 consumer 측 멱등성 키 검증. |
| 재시도 안전성 검증 태스크 | **partial / major** | Phase-1.6 `OutboxRelayServiceTest#relay_IsIdempotent_WhenCalledTwice` + Phase-1.7 `process_RetryExhausted_CallsFcgOnce` + Phase-3.3 `consume_WhenStockIncreaseFailsMidway_ShouldNotRecordDedupe`가 단위 수준에서는 존재. 그러나 **통합 수준**(Phase-4.1 chaos 시나리오)에 `stock.restore` 중복 주입·FCG timeout 주입 시나리오가 빠져 있어 end-to-end 재시도 안전성 검증이 단위 테스트 고립 상태. finding #2 참조. |
| ADR-05 가면 응답 방어 Phase 1 필수 | **partial / major** | Phase-1.5가 `PgMaskedSuccessHandler` 클래스·테스트는 명시하나 **기존 `TossPaymentErrorCode.isSuccess()=true` 경로**(src/main/java/com/hyoguoo/paymentplatform/paymentgateway/exception/common/TossPaymentErrorCode.java:70-72)가 `PaymentConfirmResultStatus.SUCCESS`로 조기 매핑되는 현 구조를 어떻게 차단할지가 태스크에 명시되지 않음. finding #1 참조. |
| ADR-05 금액 검증 Toss/NicePay 대칭 | **pass** | Phase-1.5 test `handle_Toss_AlreadyProcessed_VerifiesAmountSymmetry` + `handle_Nicepay_2201_VerifiesAmountBeforeDecision` 대칭 — NicePay `NicepayPaymentGatewayStrategy.handleDuplicateApprovalCompensation`(src/main/java/…/nicepay/NicepayPaymentGatewayStrategy.java:102-134) 로직을 Toss 쪽에도 요구하는 테스트로 반영됨. |
| ADR-16 보상 이벤트 consumer dedupe | **pass** | Phase-3.3 `stock.restore` consumer + dedupe 테이블이 상품 서비스 소유. TTL = "Kafka consumer group offset retention + 1일" 정량화. 이벤트 UUID 키. discuss-domain-2 finding (ii) 반영 확인. |
| ADR-15 FCG timeout 불변(재시도 래핑 금지) | **pass** | Phase-1.7 `OutboxProcessingServiceTest#process_WhenFcgPgCallTimesOut_ShouldQuarantine` / `process_WhenFcgPgReturns5xx_ShouldQuarantine` / `process_RetryExhausted_CallsFcgOnce` — 불변 3조항 단위 검증. |
| ADR-13 감사 원자성 (payment_history 결제 서비스 DB 잔류) | **pass** | Phase-1.4 `PaymentHistoryEventListenerTest#onPaymentStatusChange_InsertsHistoryBeforeCommit` + BEFORE_COMMIT 사실은 실제 소스(src/main/java/…/listener/PaymentHistoryEventListener.java:20)와 일치. cross-service 이동 금지. |
| ADR-20 pending_age_seconds histogram | **partial / minor** | Phase-1.11 `OutboxPendingAgeMetrics` 클래스·histogram 기록은 단위 테스트로 검증. 그러나 Phase-4.1 Kafka latency chaos 시나리오에서 **지표가 실제로 stock lock-in을 드러내는지** 교차 검증 미포함 — 지표 존재만으로는 관측 가능성 보장 부족. finding #3 참조. |
| 이행 기간 보상 경로 소유자 공백 방지 | **pass** | PLAN.md line 125 "Phase 1 보상 경로 원칙": `stock.restore` 보상은 Phase 1에서는 결제 서비스 내부 동기 호출 유지(`InternalProductAdapter` 방식 승계) + Phase 3과 동시에 이벤트화. discuss-domain-2 finding (iii) 반영. |
| Strangler Fig 기간 모놀리스↔신규 서비스 이벤트 중복 발행 방지 | **partial / minor** | Phase-1.10 Gateway 라우팅 교체(결제 엔드포인트)는 명시되나, **모놀리스가 Phase 1 기간에도 `PaymentConfirmEvent`를 내부 outbox로 발행할 수 있는 잔존 경로**(모놀리스 admin/직접 호출 경유)의 차단 여부가 명시되지 않음. finding #4 참조. |
| 금전 정확성(금액 위변조 선검증 유지) | **pass** | Phase-1.3 도메인 이관, Phase-1.4 TX coordinator가 기존 선검증 경로를 그대로 승계. |
| PII | **n/a** | 본 토픽 비목표. |

## 도메인 관점 추가 검토

### 1. Phase-1.5 `PgMaskedSuccessHandler`의 **wiring 산출물 공백** — ADR-05 방어선이 런타임에 우회될 리스크

Phase-1.5 산출물(PLAN.md:224-225)은 `payment-service/src/main/java/.../payment/application/usecase/PgMaskedSuccessHandler.java`만 명시한다. 그러나 이 핸들러가 실제로 **확신 경로(confirm path)에서 작동**하려면 다음 두 지점이 **동시에** 수정돼야 한다:

- **Toss 쪽 short-circuit 제거**: 현 `TossPaymentErrorCode.ALREADY_PROCESSED_PAYMENT.isSuccess() == true`(src/main/java/com/hyoguoo/paymentplatform/paymentgateway/exception/common/TossPaymentErrorCode.java:11, 70-72)가 `TossPaymentGatewayStrategy.determineConfirmResultStatus`(src/main/java/.../payment/infrastructure/gateway/toss/TossPaymentGatewayStrategy.java:130-140) 이전에 confirm 결과를 `PaymentConfirmResultStatus.SUCCESS`로 매핑한다 — **즉 현재 Toss 경로에는 `ALREADY_PROCESSED_PAYMENT`를 감지할 명시적 분기가 없다**. `PgMaskedSuccessHandler`를 아무리 만들어도 호출되지 않는다.
- **OutboxProcessingService / PaymentCommandUseCase 진입점**: confirm 경로에서 PG 응답이 "ALREADY_PROCESSED" 계열이면 handler로 위임하는 분기가 필요. 현 NicePay 쪽은 `handleDuplicateApprovalCompensation`(src/main/java/.../nicepay/NicepayPaymentGatewayStrategy.java:102-134) 안에서 자체적으로 PG 재조회 + 금액 검증을 수행 — 이것을 Toss 쪽에도 대칭으로 심는다는 계약이 있는데, Phase-1.5 산출물은 신규 클래스 하나만 만들고 **전략 어댑터 수정·호출자 wiring을 산출물로 선언하지 않는다**.

**영향**: Phase 1 완료 시점에 `PgMaskedSuccessHandler.java`만 존재하고 Toss confirm은 여전히 `ALREADY_PROCESSED_PAYMENT → SUCCESS` 매핑을 타면, ADR-05 수락 기준 4조항 중 **1~4번 전부가 런타임에 관철되지 않는다**. 이는 "가면 응답이 consumer 멱등성 방어선을 통과시키는" 돈 사고 경로가 그대로 남는다는 뜻. discuss-domain-2에서 "Phase 1 전제로 승격"시킨 의도가 형해화된다.

**보강 제안**(major): Phase-1.5 산출물에 다음 3건 추가 요청:
- `payment-service/.../infrastructure/gateway/toss/TossPaymentGatewayStrategy.java` — `ALREADY_PROCESSED_PAYMENT` 포착 분기 추가 (NicePay `handleDuplicateApprovalCompensation` 대칭)
- `payment-service/.../paymentgateway/exception/common/TossPaymentErrorCode.java` — `isSuccess()` 메서드 수정 또는 제거 (가면 응답을 success로 취급하지 않음)
- Phase-1.5 테스트에 "Toss confirm 경로가 `ALREADY_PROCESSED_PAYMENT` 수신 시 `PgMaskedSuccessHandler`를 반드시 호출한다"는 wiring 검증 테스트 추가

### 2. Phase-4.1 chaos 시나리오의 **보상 이벤트 중복 주입 누락** — consumer dedupe 통합 검증 공백

Phase-4.1 `chaos/scenarios/`(PLAN.md:550-553) 4종 시나리오는 Kafka latency / DB latency / process-kill / verify-consistency다. 이들은 **메시지 유실·publisher 지연·crash recovery**를 주입한다. 그러나 다음 핵심 시나리오가 빠져 있다:

- **`stock.restore` 중복 주입**: at-least-once consumer 전제에서 **같은 이벤트 UUID가 두 번 주입**되는 재시도 시나리오. Phase-3.3의 단위 테스트는 `FakeDedupeRepository`로만 검증 — 실제 Kafka 파티션 재할당·consumer group rebalance로 과거 offset이 재처리되는 상황이 실 브로커 환경에서 dedupe 테이블을 거쳐 no-op 되는지는 검증되지 않는다.
- **FCG timeout 시나리오**: Phase-1.7 단위 테스트는 `FakePgStatusAdapter` timeout만 검증. ADR-15 불변 "timeout → QUARANTINED"가 **실 PG 서비스(또는 monolith `paymentgateway`) latency 주입** 환경에서 실제로 지켜지는지는 통합 수준 재현 없음. Phase-2(PG 서비스 분리) 이후에는 이 경로가 네트워크 홉을 타므로 더욱 중요.

**영향**: plan-ready.md "재시도 안전성 검증 태스크 존재" 항목이 단위 테스트만으로 충족된 상태. at-least-once 전제 하에 **실제 중복 이벤트가 dedupe를 우회해 이중 복원을 일으키는 경로**가 통합 수준에서 재현되지 않으면, Phase-3.3의 dedupe는 설계 수준 방어에 그친다. discuss-domain-1 critical-2 (compensation dedupe)가 단위 검증으로 환원됨.

**보강 제안**(major): Phase-4.1 산출물에 다음 2건 추가 요청:
- `chaos/scenarios/stock-restore-duplicate.sh` — `stock.restore` 이벤트를 동일 UUID로 두 번 발행 + 상품 서비스 DB의 재고 증가량이 1회만 반영됐는지 검증
- `chaos/scenarios/fcg-pg-timeout.sh` — Toxiproxy로 PG `getStatus` 엔드포인트 timeout 주입 + PaymentEvent가 QUARANTINED로 전이됐는지 (FAILED/DONE 아님) 검증

### 3. Phase-1.11 `pending_age_seconds` histogram의 **실제 관측 검증 공백**

Phase-1.11 test `record_ShouldEmitHistogramForEachPendingRecord`는 MeterRegistry 직접 검증이지만, ADR-20의 **원래 목적**(Kafka publisher 지연에 따른 stock lock-in 감지)이 **chaos 시나리오에서 실제로 관측되는가**가 Phase-4.1에 연결되지 않는다. 즉 "지표는 기록된다"와 "지표가 stock lock-in을 드러낸다"는 다르다.

**영향**: Phase 4 장애 주입에서 Kafka publisher에 지연을 주입해도 histogram 관측이 공식 수락 조건이 아니라면, 프로덕션 장애 시 동일 지표가 경보 역할을 할 수 있는지가 plan 단계에서 확신되지 않는다 — ADR-20의 수락 기준 자체는 지표 추가만이므로 OK이지만, **도메인 관점에서는 stock lock-in 실전 검출이 목적**이었다.

**보강 제안**(minor): Phase-4.1 `chaos/scenarios/kafka-latency.sh` 수락 기준에 "`payment.outbox.pending_age_seconds` histogram의 p95가 임계값(예: 10초) 이상 기록되는지 Prometheus 쿼리로 확인"을 명시적으로 추가. 단위 테스트가 아닌 chaos 관측 지표로서의 역할 증명.

### 4. Strangler Fig 기간 **모놀리스 잔존 confirm 경로 차단 미명시**

Phase-1.10 Gateway 라우팅은 `/api/v1/payments/**`를 결제 서비스로 라우팅한다. 그러나:
- **모놀리스 내부에 남아있는 `PaymentConfirmEvent` outbox 발행 경로**가 Admin UI 경유 직접 호출 또는 모놀리스 내부 Java 호출(예: `OutboxImmediateEventHandler`)로 여전히 동작 가능한지가 PLAN.md에 명시되지 않는다.
- 만약 모놀리스와 결제 서비스가 **둘 다 동일 `payment_outbox` 테이블에 접근**하면(Phase-1.4의 Flyway 이관이 명시되지 않은 공백과 연동) 같은 outbox 레코드가 양쪽에서 발행되어 **이벤트 이중 발행**이 일어난다. ADR-13 감사 원자성 경로는 OK이나, `PaymentConfirmedEvent` 같은 cross-service 알림은 중복 발행 경로가 생긴다.

**영향**: Phase 1 완료 후 모놀리스를 즉시 폐쇄하지 않는 이상(PLAN.md line 14 "Phase 5 완료 전까지 공존") 이 공백이 Strangler Fig 전체 기간 유지. `stock.restore` 같은 보상 이벤트가 모놀리스 쪽에서도 발행되면 Phase-3.3 consumer dedupe TTL과 무관하게 **서로 다른 이벤트 UUID**로 발행되어 dedupe를 통과한다.

**보강 제안**(minor): Phase-1.10 또는 신규 Phase-1.10b 태스크에 "모놀리스의 결제 endpoint/`OutboxImmediateEventHandler`/`PaymentCommandUseCase` 직접 호출 경로를 비활성화(`@ConditionalOnProperty` 또는 Spring profile 분기)하여 단일 발행자 보장"을 산출물로 명시. 또는 topic.md § 6 Phase 1에 **"모놀리스는 Phase 1 완료 시점에 결제 confirm 경로를 완전히 끈다"**는 phase gating 조항 추가.

### 5. 그 외 도메인 관점 확인

- **상태 전이 enum 소유권**: Phase-1.3에서 `PaymentEventStatus`가 결제 서비스 도메인 레이어로 이관 — container-per-service 원칙 유지. **OK**.
- **claim race**: `claimToInFlight` REQUIRES_NEW는 Phase-1.7의 OutboxProcessingService 이관에 암묵 포함(기존 파일 이관). **OK** — 다만 테스트 메서드에 race 재현이 없다는 점은 현 모놀리스와 동일 수준.
- **종결 상태 dedupe**: Phase-1.3 `PaymentEventTest#execute_ThrowsException_WhenTerminalStatus` + Phase-1.4 TX coordinator 테스트로 DONE/FAILED/CANCELED/EXPIRED에서 추가 전이 차단 검증. **OK**.
- **재시도 한도 소진 시 FCG**: Phase-1.7 `process_RetryExhausted_CallsFcgOnce` — retryCount=maxRetries 소진 시 FCG 1회만 호출 검증. **OK**.
- **ADR-13 `payment_history` Flyway**: Phase-1.4가 테이블 "결제 서비스 DB 잔류"를 명시하나 Flyway V1 마이그레이션 산출물은 Architect 인라인 주석(PLAN.md:205)에 지적되고 있음 — 도메인 관점에서는 데이터 연속성 리스크(모놀리스 → 결제 서비스 DB로 `payment_history` 이행 시 기존 감사 레코드 소실 방지)지만 Critic 영역 주제로 판단. 본 판정 산입 제외.

## Findings

- **[major]** Phase-1.5 `PgMaskedSuccessHandler` 산출물이 **wiring 누락**. `TossPaymentErrorCode.isSuccess()=true`(src/main/java/com/hyoguoo/paymentplatform/paymentgateway/exception/common/TossPaymentErrorCode.java:70-72)가 `PaymentConfirmResultStatus.SUCCESS`로 조기 매핑되는 현 경로가 유지되면 handler가 호출되지 않는다. Toss 전략·에러 코드 enum 수정과 호출자 wiring 테스트가 Phase-1.5 산출물에 추가돼야 ADR-05 수락 기준 1~4가 런타임에 관철된다.
- **[major]** Phase-4.1 chaos 시나리오에 **`stock.restore` 중복 주입·FCG PG timeout 주입**이 빠져 있어 Phase-3.3 consumer dedupe(critical-2)와 Phase-1.7 FCG 불변(ADR-15)이 통합 수준 재시도 안전성 검증을 받지 않는다. `chaos/scenarios/stock-restore-duplicate.sh` + `chaos/scenarios/fcg-pg-timeout.sh` 추가 필요.
- **[minor]** Phase-1.11 `pending_age_seconds` histogram이 Phase-4.1 Kafka latency 시나리오에서 **실 관측 지표로서의 역할**을 증명하는 수락 기준 공백. 단위 기록만 검증됨. `kafka-latency.sh` 수락 기준에 histogram p95 임계값 관측 추가 권고.
- **[minor]** Phase-1.10 Gateway 라우팅 교체 후 **모놀리스 내부 결제 confirm 경로**(`OutboxImmediateEventHandler` / `PaymentCommandUseCase` 직접 호출)의 비활성화 산출물이 없어 Strangler Fig 기간 이중 발행 경로가 열릴 리스크. Phase-1.10 또는 Phase-1.10b에 모놀리스 결제 경로 비활성화 태스크 추가 권고.
- **[n/a]** PII·보안 경로는 본 토픽 비목표.

## JSON
```json
{
  "stage": "plan",
  "persona": "domain-expert",
  "round": 1,
  "task_id": null,
  "decision": "revise",
  "reason_summary": "discuss 6개 domain risk는 전부 태스크 매핑 완료이나, Phase-1.5 PgMaskedSuccessHandler wiring 누락과 Phase-4.1 보상 이벤트 중복 주입 시나리오 누락 2건의 major가 남는다 — 클래스는 생성되지만 런타임에 ADR-05 방어선이 우회되거나 consumer dedupe 통합 검증이 결여됨.",
  "checklist": {
    "source": "_shared/checklists/plan-ready.md",
    "items": [
      {
        "section": "domain risk",
        "item": "discuss에서 식별된 domain risk가 각각 대응 태스크를 가짐",
        "status": "yes",
        "evidence": "docs/MSA-TRANSITION-PLAN.md:630-637 추적 테이블 6건 매핑 완료"
      },
      {
        "section": "domain risk",
        "item": "중복 방지 체크가 필요한 경로에 계획됨",
        "status": "yes",
        "evidence": "Phase-3.3 dedupe 테이블 + Phase-2.3 consume_DuplicateCommand_ShouldDedupeByEventUuid"
      },
      {
        "section": "domain risk",
        "item": "재시도 안전성 검증 태스크 존재",
        "status": "no",
        "evidence": "Phase-1.6/1.7/3.3 단위 테스트는 존재하나 Phase-4.1 chaos에 stock.restore 중복 주입·FCG timeout 주입 통합 시나리오 부재"
      }
    ],
    "total": 3,
    "passed": 2,
    "failed": 1,
    "not_applicable": 0
  },
  "scores": {
    "traceability": 0.90,
    "decomposition": 0.85,
    "ordering": 0.82,
    "specificity": 0.70,
    "risk_coverage": 0.72,
    "mean": 0.798
  },
  "findings": [
    {
      "severity": "major",
      "checklist_item": "ADR-05 가면 응답 방어가 런타임에 관철됨",
      "location": "docs/MSA-TRANSITION-PLAN.md:210-228 (Phase-1.5)",
      "problem": "PgMaskedSuccessHandler 클래스 신규 생성만 산출물에 있고, Toss 경로의 `ALREADY_PROCESSED_PAYMENT → SUCCESS` 조기 매핑(TossPaymentErrorCode.isSuccess()=true) 차단·호출자 wiring이 누락. handler가 생성되어도 confirm 런타임에 호출되지 않을 위험 → ADR-05 수락 기준 1-4 전부 관철되지 않는 돈 사고 경로.",
      "evidence": "src/main/java/com/hyoguoo/paymentplatform/paymentgateway/exception/common/TossPaymentErrorCode.java:70-72 (isSuccess return true for ALREADY_PROCESSED_PAYMENT); src/main/java/com/hyoguoo/paymentplatform/payment/infrastructure/gateway/toss/TossPaymentGatewayStrategy.java:130-140 (determineConfirmResultStatus). NicePay 쪽은 src/main/java/.../nicepay/NicepayPaymentGatewayStrategy.java:102-134 handleDuplicateApprovalCompensation으로 대칭 분기 구현 존재.",
      "suggestion": "Phase-1.5 산출물에 (1) TossPaymentGatewayStrategy에 ALREADY_PROCESSED_PAYMENT 포착 분기 추가 (NicePay handleDuplicateApprovalCompensation 대칭) (2) TossPaymentErrorCode.isSuccess()가 ALREADY_PROCESSED_PAYMENT를 success로 취급하지 않도록 수정 또는 제거 (3) 'Toss confirm이 ALREADY_PROCESSED 수신 시 PgMaskedSuccessHandler를 반드시 호출한다'는 wiring 통합 테스트 추가"
    },
    {
      "severity": "major",
      "checklist_item": "재시도 안전성 검증 태스크 존재 (재시도 정책 관련)",
      "location": "docs/MSA-TRANSITION-PLAN.md:544-553 (Phase-4.1)",
      "problem": "chaos 시나리오 4종(Kafka/DB latency, process-kill, verify-consistency)에 at-least-once 전제의 핵심인 `stock.restore` 이벤트 중복 주입과 FCG의 PG getStatus timeout 주입이 빠져 있음. Phase-3.3 consumer dedupe와 Phase-1.7 FCG 불변(ADR-15)이 단위 테스트 범위에서만 검증되어 통합 수준 재시도 안전성이 확신되지 않음.",
      "evidence": "Phase-3.3 test들은 FakeDedupeRepository 기반 (PLAN.md:475-478); Phase-1.7 test들은 FakePgStatusAdapter timeout (PLAN.md:264-267) — 실 Kafka 파티션 재할당·실 PG 서비스 latency 경로 재현 없음",
      "suggestion": "Phase-4.1 산출물에 (1) chaos/scenarios/stock-restore-duplicate.sh — 동일 UUID stock.restore 이벤트 2회 발행, 상품 서비스 DB 재고 증가 1회만 확인 (2) chaos/scenarios/fcg-pg-timeout.sh — PG getStatus 엔드포인트 Toxiproxy latency 주입, PaymentEvent QUARANTINED 전이 확인"
    },
    {
      "severity": "minor",
      "checklist_item": "ADR-20 stock lock-in 관측 지표의 실전 관측 검증",
      "location": "docs/MSA-TRANSITION-PLAN.md:321-332 (Phase-1.11), 544-553 (Phase-4.1)",
      "problem": "pending_age_seconds histogram은 단위 테스트(Phase-1.11 `record_ShouldEmitHistogramForEachPendingRecord`)로 기록만 검증 — Phase-4.1 kafka-latency.sh에 '실 publisher 지연 주입 시 histogram p95가 임계값 이상 관측된다'는 수락 기준 공백. ADR-20 원래 목적(stock lock-in 감지)이 장애 주입에서 실전 관측되는지 증명되지 않음.",
      "evidence": "Phase-1.11 테스트가 MeterRegistry 직접 검증이나 chaos 시나리오 관측 기준과 연결되지 않음 (PLAN.md:328-332 vs PLAN.md:550)",
      "suggestion": "chaos/scenarios/kafka-latency.sh 수락 기준에 'Prometheus 쿼리로 payment.outbox.pending_age_seconds p95 임계값(예: 10s) 이상 기록 확인' 추가"
    },
    {
      "severity": "minor",
      "checklist_item": "Strangler Fig 기간 이벤트 중복 발행 방지",
      "location": "docs/MSA-TRANSITION-PLAN.md:307-317 (Phase-1.10)",
      "problem": "Gateway 라우팅은 `/api/v1/payments/**`를 결제 서비스로 전환하나, 모놀리스 내부의 결제 confirm 경로(OutboxImmediateEventHandler, PaymentCommandUseCase 직접 호출, Admin UI 경유 호출)를 비활성화하는 산출물 부재. Phase 5 완료 전까지 공존 기간(PLAN.md:14)에 동일 payment_outbox 테이블 접근 또는 양쪽 서비스 발행으로 이벤트 이중 발행 경로 가능.",
      "evidence": "PLAN.md:312-316 Phase-1.10 산출물은 gateway application.yml 라우트만 명시; 모놀리스 쪽 차단 언급 없음",
      "suggestion": "Phase-1.10에 '모놀리스의 결제 confirm 경로 비활성화(@ConditionalOnProperty 또는 Spring profile 분기)' 산출물 추가 또는 별도 Phase-1.10b 태스크 분리"
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
  "previous_round_ref": "docs/rounds/msa-transition/discuss-domain-2.md",
  "delta": {
    "newly_passed": [
      "ADR-05 Toss/NicePay 금액 검증 대칭 (discuss-domain-2 finding (i) 반영)",
      "ADR-16 dedupe TTL 정량화 (discuss-domain-2 finding (ii) 반영)",
      "Phase 1 이행 기간 보상 경로 소유자 명시 (discuss-domain-2 finding (iii) 반영)"
    ],
    "newly_failed": [
      "Phase-1.5 PgMaskedSuccessHandler wiring (신규 major — 클래스만 생성하고 Toss 전략 수정 없음)",
      "Phase-4.1 chaos 시나리오의 보상 이벤트 중복 주입 누락 (신규 major — 단위 테스트만 존재)"
    ],
    "still_failing": []
  },
  "unstuck_suggestion": null
}
```
