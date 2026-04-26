# discuss-domain-1

**Topic**: MSA-TRANSITION
**Round**: 1
**Persona**: Domain Expert

## Reasoning

산출물은 거시 토폴로지·ADR 인덱스·Phase 분할 수준에서 비교적 탄탄하고, container-per-service·at-least-once·Saga Choreography 기본·FCG/Reconciler 재정의 같은 큰 결정들이 결제 도메인 불변성(= DB=진실의 원천, 멱등성 end-to-end, 보상 단일 지점)과 정렬돼 있다. 다만 **결제 승인 경로의 실제 구현 사실과 어긋나는 인용 한 건**(ADR-13 기술 — `@PublishDomainEvent` 감사 경로는 AOP이지만 청취자는 `BEFORE_COMMIT`으로 같은 TX 안에서 기록), 그리고 **돈이 새는 구체적 레이스 윈도우 중 MSA에서 확대되는 두 건**(PG→결제 상태 조회 API가 물리 분리된 뒤의 race, cross-service 보상 이벤트의 중복 수신 시 D12 가드 한계)이 ADR 차원에서 명시적으로 방어되지 않았다. 상태 전이·정합성의 큰 줄기는 설계돼 있으나, MSA 경계에서 고유하게 생기는 돈 사고 경로를 ADR에 "명시적 수락 기준"으로 박는 보강이 필요하다 — 따라서 **revise**.

## Domain risk checklist

| 항목 | 판정 | 근거 |
|---|---|---|
| 상태 전이 불변성 유지(READY/IN_PROGRESS/RETRYING/DONE/FAILED/CANCELED/EXPIRED/QUARANTINED) | **pass** | § 7 매트릭스에서 `PaymentEvent` 상태 종결 지점이 "각 서비스 로컬 TX"로 축소됨을 명시. `PaymentEventStatus` enum 자체는 서비스 분해 후에도 결제 서비스 단일 소유(container-per-service) — 전이 규칙 훼손 없음. |
| 멱등성 end-to-end(Client→Gateway→결제→PG 어댑터→PG) | **partial / major** | ADR-05가 멱등성 키 선택지를 제시하나 **Toss `ALREADY_PROCESSED_PAYMENT` 가면 방어**(PITFALLS Pitfall 11)와 **NicePay `2201` 중복 승인 보상**(INTEGRATIONS.md:85-88, `NicepayPaymentGatewayStrategy.handleDuplicateApprovalCompensation`)을 ADR 수용 기준으로 못 박지 않음. ADR-29가 "악화 점검"만 약속할 뿐 ADR-05 결정문의 검증 조건에 포함돼야 함. |
| PG 실패 모드 분류(retryable/non-retryable/가면 성공) | **partial / major** | 현 코드의 Toss·NicePay 에러 분류는 어댑터 내부 상수로 국지화돼 있음(`TossPaymentErrorCode`, NicePay `2159/A246/A299/3011~3014/2152/2156`). ADR-12(스키마) · ADR-14(event/command)에 **에러 분류 이벤트 필드 스펙**이 부재 — consumer가 어느 관점에서 재시도/격리를 결정하는지 이벤트 수준 계약이 비어 있다. |
| Race window: outbox claim 경쟁 | **pass** | `claimToInFlight` REQUIRES_NEW TX가 현 서비스 내부에 그대로 남음(토폴로지가 결제 서비스 내부 유지 전제) — § 7 행 #7이 이를 재확인. |
| Race window: 보상 이중 실행(CONCERNS "executePaymentFailureCompensation 무조건 증가 재고") | **partial / critical** | § 7 행 #9가 "cross-service 이벤트 멱등성"만 문구로 언급. 현 상태에서 D12 가드는 **outbox/event 같은 DB 행을 TX 내 재조회**로 작동 — MSA에서는 **상품 서비스**가 재고 복원 주체로 옮겨가면 D12 가드의 소스 테이블이 결제 서비스 DB에 있고 재고는 상품 서비스 DB에 있어 TX 내 재조회가 성립하지 않음. 이건 돈/재고가 새는 구체적 경로이며 ADR-06(Saga)·ADR-16(Idempotency)가 "stock-restore 이벤트 멱등성 키 + 상품 서비스 측 수신 테이블"을 명시해야 함. |
| PII 노출/저장 안전성 | **n/a** | 보안 범위는 명시적으로 비목표(§ 1-3). |
| 금전 정확성(금액 위변조 선검증, 승인-재고 단일 TX) | **pass** | § 7 행 #2가 "결제 서비스 내부 로컬 TX로 축소"를 명시. 금액 위변조 선검증은 TX 진입 전 스테이지에서 유지. |
| FCG·격리의 "종결 판정 단일 지점" 유지 | **partial / major** | ADR-15는 "결제 서비스 내부 유지"를 대안 (a)로 제시했을 뿐 **결정하지 않음**(deferred OK). 그러나 ADR-21(PG 물리 분리)이 (a)를 선택할 경우 FCG의 `getPaymentStatusByOrderId`가 **네트워크 호출로 전환**됨 — FCG 1회 성격을 유지하려면 PG 서비스의 `getStatus`가 **동기 + 짧은 timeout + 실패 시 격리로 흡수**라는 불변을 ADR-15/21 중 한 곳에 박아야 한다. 현재 그 계약이 공백. |
| PaymentHistory 감사 경로 단절 방지 | **partial / critical** | 아래 finding #1 참조. 산출물의 ADR-13 기술이 **실제 구현과 어긋남** — 현 `PaymentHistoryEventListener`는 `BEFORE_COMMIT` (같은 TX). "AOP 주도 = MSA 취약점"이라는 § 1-1 결정 근거가 사실과 일치하지 않는다. ADR-13의 전제가 재조정돼야 한다. |
| Recon vs FCG 역할 분리 | **pass** | ADR-17이 (a)(b)(c) 세 안으로 충분히 열려 있음. |
| 알려진 결함의 MSA 악화 평가 | **pass** | ADR-29가 결함을 "악화/유지/개선"으로 분류하는 축을 마련. 단, ADR-05의 검증 조건에 인라인 반영 권고(위 항목 참조). |

## 도메인 관점 추가 검토

### 1. ADR-13 전제 오류 — "같은 JVM AOP 주도"가 사실과 부분적으로 어긋남

산출물 § 1-1(`MSA-TRANSITION.md:130`)은 "Spring ApplicationEvent + AOP(`@PublishDomainEvent` 등)가 감사·메트릭 전파의 축이다. **같은 JVM 내 AOP 주도**라는 점이 MSA 전환 시 가장 큰 취약점이다 (PITFALLS Pitfall 10)"라고 서술한다. 실제 구현은 두 갈래다:

- `DomainEventLoggingAspect.publishHistoryEvent`(`DomainEventLoggingAspect.java:31-49`)는 `@PublishDomainEvent` 애노테이션이 붙은 도메인 메서드가 종료된 직후(같은 스레드)에 `PaymentEventPublisher.publishXxx`를 호출 — 이건 AOP 맞음.
- 하지만 감사 레코드를 **실제로 DB에 쓰는 주체**인 `PaymentHistoryEventListener.handlePaymentHistoryEvent`(`PaymentHistoryEventListener.java:20-24`)는 `@TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)` — 같은 TX 경계 안에서 `paymentHistoryService.recordPaymentHistory(event)`를 호출하여 `payment_history` 테이블에 **같은 TX로** 저장한다.

결과적으로 **현 모놀리스에서 PaymentHistory는 상태 전이 TX와 원자적**이다. PITFALLS Pitfall 10이 경고하는 "별도 스레드/TX 경계에서 AOP 누락"은 실제로 현 코드에는 존재하지 않는다(async 워커가 호출하는 도메인 메서드도 `@Transactional`이고 같은 TX 안에서 BEFORE_COMMIT 리스너가 작동).

**영향**: ADR-13의 대안 (a)/(b)/(c) 평가 기준이 잘못 설정돼 있다. "AOP를 각 서비스 내부에 유지"가 의미하는 바가 모호해짐 — 현 구조는 AOP가 아니라 **같은 TX 이벤트 리스너**로 감사 원자성을 보장하고 있기 때문. MSA 전환 시 실제 리스크는 "AOP가 안 불린다"가 아니라 **PaymentHistory 테이블이 cross-service가 되면 감사-상태 원자성이 깨진다** 쪽이다.

**보강 제안**: ADR-13 결정문에 (i) 현 `PaymentHistoryEventListener`가 BEFORE_COMMIT 같은 TX라는 사실을 적시, (ii) 대안 (c) "PaymentHistory를 별도 서비스로 분리"가 **감사 원자성을 포기**한다는 tradeoff를 명시, (iii) 결제 서비스 내부에 `payment_history` 테이블을 유지하는 (a')를 추가 제안.

### 2. 보상(재고 복원) 이벤트 멱등성의 이중 방어선 공백 — CONCERNS "compensation 이중 실행"이 MSA에서 완화되지 않는다

현 코드의 방어선:
- 1차: `claimToInFlight` REQUIRES_NEW UPDATE(PENDING→IN_FLIGHT) (`OutboxProcessingService` 흐름 §1-4).
- 2차: `executePaymentFailureCompensationWithOutbox`의 **D12 가드**(`PaymentTransactionCoordinator`) — TX 내 outbox/event 재조회 후 `outboxInFlight AND eventNonTerminal` 조건 시에만 `increaseStockForOrders` 호출.

MSA 전환 후 ADR-01(b/c)에서 **상품 서비스가 분리**되면 `increaseStockForOrders`는 결제 서비스가 직접 호출하지 않고 `stock.restore` 이벤트로 전환된다. 이 시점에서:
- D12 가드는 **결제 서비스 로컬 TX** 안에서 outbox/event 재조회를 하지만, 실제 재고 복원은 **상품 서비스가 이벤트 수신 후 자기 DB TX로 실행**한다.
- at-least-once consumer 전제(§ 2-3)에서 `stock.restore` 이벤트가 중복 수신되면 상품 서비스 쪽에도 **멱등성 키**와 **이미 복원됨 판정** 로직이 필요하다.
- CONCERNS.md `executePaymentFailureCompensation`의 현 결함(동일 orderId 두 경로 동시 호출 시 이중 복원)은 **결제 서비스 단일 DB 전제에서도 이미 존재**하며, cross-service 이벤트로 풀면 같은 문제가 **"이벤트 UUID로 상품 서비스 측 수신 테이블에 dedupe"** 형태로 **재방어돼야** 한다.

산출물의 § 7 행 #9는 "cross-service 이벤트 멱등성 (재고 복원 멱등성)"이라고 한 줄로만 언급 — ADR-16(Idempotency 저장소 분산) 결정문에 **"compensation 이벤트는 consumer 측에 dedupe 테이블을 반드시 가진다"**를 **수락 기준**으로 명시해야 한다. 현재 ADR-16은 대안 (a)(b)(c)만 나열하고 compensation-specific 요구를 걸지 않았다.

**보강 제안**: ADR-16에 "compensation 이벤트(stock.restore, refund 등)는 (i) 이벤트 UUID 키가 필수이고 (ii) consumer 측 dedupe 테이블이 결정 대안에 포함돼야 한다"는 하위 제약을 추가. ADR-06(Saga)에도 **"보상의 이중 실행을 방어하는 책임은 consumer 측"**임을 못 박을 것.

### 3. FCG → PG 서비스 분리 시 "종결 판정 단일 지점" 침식 가능성

`OutboxProcessingService.process()` §1-4의 ⑥ FCG는 **"retryCount=0, maxRetries=1로 고정한 getPaymentStatusByOrderId 1회 재호출"**이다. 현재 이 호출은 같은 JVM 내 PG 어댑터(`PaymentGatewayStrategy` → `HttpTossOperator`/`HttpNicepayOperator`)로 들어간다.

ADR-21이 PG 서비스 물리 분리를 선택하면 FCG는 **네트워크 호출**로 전환된다. 이때 다음 세 가지 리스크:
- **타임아웃 선택**: FCG는 "1회 + 판단 불가 시 격리"인데, PG 서비스 호출이 timeout으로 실패하면 `RecoveryDecision.fromException`이 FCG 자체를 호출한 뒤 다시 timeout → 무한 재귀 방지 장치가 현 코드에는 "retryCount/maxRetries 고정"만이다. 이건 로컬 call에서는 OK지만 네트워크에서는 **결제 서비스가 PG 서비스 죽음에 끌려 들어감**.
- **PG 서비스 크래시 시 "판단 불가" 과다 격리**: 현 `PaymentQuarantineMetrics`는 격리 빈도 모니터링이지만, PG 서비스 장애가 **정상 결제까지 격리시키는** 시나리오가 MSA에서 비대해진다.
- **Toss `ALREADY_PROCESSED_PAYMENT`의 의미**가 PG 서비스 API 응답 스펙에 담기는 방식: 현 `TossPaymentErrorCode.isSuccess()`가 이를 success로 취급하는 가면 문제(PITFALLS Pitfall 11)가 PG 서비스의 `getStatus` API 응답으로 그대로 유출될 우려.

산출물 ADR-15/17/21 어느 결정문에도 **"FCG 호출 경로에서 PG 서비스의 timeout/retry는 결제 서비스가 제어한다 + PG 서비스는 가면 응답을 쓰지 않는다"**라는 계약이 없다.

**보강 제안**: ADR-21 결정문에 **"PG 서비스의 `getStatus` API는 가면 응답(success 취급) 없이 raw state(DONE/IN_PROGRESS/FAILED/NOT_FOUND/DUPLICATE_ATTEMPT)를 반환"**을 수락 기준으로 박을 것. ADR-15에 **"FCG는 PG 서비스 호출 timeout 시 무조건 격리 전이"**를 불변으로 박을 것.

### 4. 크래시 매트릭스에서 "결제 TX 커밋 ∩ Kafka publish 실패 후 reconciliation 지연" 시 stock lock-in

§ 7 행 #4가 "outbox 재발행 루프 + producer retry"로 방어한다 했지만, **"outbox PENDING이 오래 남아 있는 동안 stock은 이미 감소된 상태"**다. 이건 `payment_event.status = IN_PROGRESS` + `payment_outbox.status = PENDING` 조합에서 재고가 lock-in되는 상태다. 모놀리스에서는 OutboxWorker polling 2초로 드물지만, MSA에서 **Kafka publisher의 지연**이 reconciliation과 엮이면 stock lock-in 시간이 **분 단위**로 늘어난다.

**영향**: 정합성 자체는 깨지지 않으나, **판매자 입장에서 재고가 팔린 것도 팔리지 않은 것도 아닌 상태**가 장시간 지속 — 이 상태를 나타내는 **PaymentQuarantine 이전 경보 지표**(ADR-20 메트릭 네이밍)가 필요하다. 현 `PaymentHealthMetrics`가 stuck IN_PROGRESS 감지를 하지만, MSA에서는 PENDING-timestamp 기반 stuck outbox 감지도 있어야 한다.

**보강 제안**: ADR-20에 **"outbox PENDING 지속 시간 기반 `payment.outbox.pending_age_seconds`(histogram) 추가"**를 메트릭 네이밍 규약 수락 기준에 포함.

### 5. `ALREADY_PROCESSED_PAYMENT`/NicePay `2201` 방어의 ADR 귀속 공백

산출물은 § 8-4에서 "`ALREADY_PROCESSED_PAYMENT` 가면 문제를 ADR-29에서 악화 점검만 하고 수정은 후속 토픽으로 분리하는 방침이 옳은지 Critic 재검토 필요"라고 인정한다. 도메인 관점에서는 **이 결함이 MSA에서 악화**된다. 이유:
- consumer 멱등성이 at-least-once 전제의 핵심 방어선인데, PG 응답의 가면이 **그 방어선을 통과해버린다** — 상품 서비스/결제 서비스 모두 "success"를 받는다.
- 현 NicePay `2201` 경로(INTEGRATIONS.md:87, `NicepayPaymentGatewayStrategy.handleDuplicateApprovalCompensation`)는 **tid로 PG 재조회 → status==paid AND 금액 일치 검증**이라는 이중 검증을 이미 구현 — Toss 쪽도 이와 대칭이어야 한다는 설계가 아직 없다.

**보강 제안**: ADR-05(멱등성·중복) 또는 ADR-29 결정문 수락 기준에 **"ALREADY_PROCESSED_PAYMENT / 2201 유사 응답은 PG 재조회 + 금액 일치 검증 통과 후에만 success로 해석"**을 명시. 이건 "후속 토픽"이 아니라 **MSA 전환 phase 1의 전제**다.

### 6. NICE-TO-HAVE: 이벤트 스키마(ADR-12)와 PG별 에러 코드 분류의 국지화

ADR-14(event vs command)가 네이밍만 다루고 **payload 스펙**은 ADR-12 스키마 관리에 맡기는 구조다. 결제 실패 이벤트의 payload에 PG별 에러 코드가 날 것(`TOSS_ERR_3011`, `NICE_2201` 등)인지, **도메인 중립 enum**(`NON_RETRYABLE_INSUFFICIENT_FUNDS`, `DUPLICATE_APPROVAL` 등)으로 매핑해 발행할 것인지가 결정돼야 한다. 현 `PaymentErrorCode`는 결제 서비스 내부 범용 enum — 이걸 이벤트 스키마로 승격할지 여부가 미지정.

**보강 제안**: ADR-12 결정 질문에 **"PG 에러 코드는 이벤트 payload에서 어댑터-local로 유지하는가, 도메인 중립 enum으로 매핑 발행하는가"**를 추가.

## Findings

- **[critical]** ADR-13 결정 전제가 실제 구현과 어긋남. `PaymentHistoryEventListener`는 `BEFORE_COMMIT` (같은 TX, `PaymentHistoryEventListener.java:20`)로 감사 원자성을 보장 — 산출물이 말하는 "AOP 주도의 MSA 취약점"은 감사가 아니라 `DomainEventLoggingAspect`의 transition publish에 국한된다. ADR-13 대안 세트(특히 (c) 분리)의 **tradeoff 재서술** + "(a')결제 서비스 내부 `payment_history` 유지" 대안 추가 필요.
- **[critical]** `stock.restore` / 보상 이벤트의 **consumer 측 dedupe 책임**이 ADR-06/ADR-16 수락 기준에 부재. 현 D12 가드가 cross-service에서 그대로 성립하지 않음. CONCERNS "executePaymentFailureCompensation 이중 복원" 결함이 MSA에서 **재현 + 확대**되는 경로가 열려 있다. ADR-16에 "compensation 이벤트는 이벤트 UUID 키 + consumer dedupe 테이블"을 하위 제약으로 명시 요구.
- **[major]** FCG(ADR-15)가 PG 서비스 분리(ADR-21) 선택 시 네트워크 경계를 타는 사실이 ADR 본문에 명시되지 않음. PG 서비스 `getStatus` API의 **가면 응답 금지 + raw state 반환**, FCG timeout 시 **무조건 격리**라는 두 불변을 ADR-15/21에 박을 것.
- **[major]** `ALREADY_PROCESSED_PAYMENT` / NicePay `2201` 가면 방어가 ADR-05 또는 ADR-29 수락 기준에 누락. "후속 토픽 분리"는 부적절 — **phase 1 전제**로 승격 권고.
- **[major]** Toss/NicePay 에러 분류의 이벤트 스키마 귀속(ADR-12): PG-local 코드 유지 vs 도메인 중립 enum 매핑 발행 중 어느 것인지 결정 질문 추가 필요.
- **[minor]** § 7 행 #4(Kafka publisher 지연 시 stock lock-in)에 대한 관측 지표가 ADR-20에 명시되지 않음. `payment.outbox.pending_age_seconds`(histogram) 추가 권고.
- **[minor]** § 8-4 "ARCHITECTURE.md vs INTEGRATIONS.md 문서 간 경미한 불일치"를 후속 TODOS로 분리한 방침은 적절하나, 본 토픽의 ADR-13 결정 근거에 영향을 주므로 **본 라운드 내에서** INTEGRATIONS.md가 말하는 `@Async` 설명을 보정하고 ADR-13 전제를 다시 설 것.
- **[n/a]** PII·보안 경로는 본 토픽 비목표.

## JSON
```json
{
  "topic": "MSA-TRANSITION",
  "stage": "discuss",
  "round": 1,
  "persona": "domain-expert",
  "artifact_ref": "docs/topics/MSA-TRANSITION.md",
  "previous_round_ref": "docs/rounds/msa-transition/discuss-interview-0.md",
  "decision": "revise",
  "findings": [
    {
      "severity": "critical",
      "area": "audit-atomicity",
      "summary": "ADR-13 전제 오류 — PaymentHistoryEventListener가 BEFORE_COMMIT(같은 TX)로 감사 원자성을 보장하는 사실이 산출물의 'AOP 주도 = 최대 취약점' 서술과 어긋남",
      "evidence": [
        "src/main/java/com/hyoguoo/paymentplatform/payment/listener/PaymentHistoryEventListener.java:20-24",
        "src/main/java/com/hyoguoo/paymentplatform/core/common/aspect/DomainEventLoggingAspect.java:31-49",
        "docs/topics/MSA-TRANSITION.md:130"
      ],
      "fix": "ADR-13 대안 세트 재구성: (a) 각 서비스 내부 AOP 유지 (b) AOP 폐기 + 도메인 반환 (c) PaymentHistory 별도 서비스 분리 — (c)가 감사-상태 원자성 포기라는 tradeoff 명시 + (a') 결제 서비스 내부 payment_history 테이블 유지안 추가"
    },
    {
      "severity": "critical",
      "area": "compensation-idempotency",
      "summary": "cross-service 보상 이벤트(stock.restore 등)의 consumer 측 dedupe 책임이 ADR-06/ADR-16 수락 기준에 부재. CONCERNS 'executePaymentFailureCompensation 이중 복원' 결함이 MSA에서 재현·확대되는 경로",
      "evidence": [
        "docs/context/CONCERNS.md:52-57",
        "docs/topics/MSA-TRANSITION.md:484 (§7 #9)",
        "src/main/java/com/hyoguoo/paymentplatform/payment/application/usecase/PaymentTransactionCoordinator.java"
      ],
      "fix": "ADR-16 결정문에 'compensation 이벤트는 (i) 이벤트 UUID 키 필수 (ii) consumer 측 dedupe 테이블을 모든 채택 대안에 포함'을 수락 기준으로 박을 것. ADR-06에도 '보상의 이중 실행 방어는 consumer 책임' 명시"
    },
    {
      "severity": "major",
      "area": "fcg-cross-service-contract",
      "summary": "PG 서비스 물리 분리(ADR-21) 시 FCG의 getStatus 호출이 네트워크 경계를 타는 사실과, 가면 응답(ALREADY_PROCESSED_PAYMENT 등) 금지·timeout 시 격리라는 불변이 ADR 본문에 없음",
      "evidence": [
        "docs/context/CONFIRM-FLOW-ANALYSIS.md:143-151",
        "docs/topics/MSA-TRANSITION.md:331-334 (ADR-15)",
        "docs/topics/MSA-TRANSITION.md:348 (ADR-21)"
      ],
      "fix": "ADR-21 수락 기준: 'PG 서비스 getStatus API는 raw state(DONE/IN_PROGRESS/FAILED/NOT_FOUND/DUPLICATE_ATTEMPT)만 반환, success 가면 없음'. ADR-15 불변: 'FCG는 PG 서비스 timeout 시 무조건 QUARANTINED'"
    },
    {
      "severity": "major",
      "area": "already-processed-masking",
      "summary": "Toss ALREADY_PROCESSED_PAYMENT·NicePay 2201 가면 방어가 MSA에서 at-least-once consumer 멱등성을 뚫는 핵심 경로인데 ADR-05/29 수락 기준에 명시되지 않고 '후속 토픽 분리' 방침",
      "evidence": [
        "docs/context/PITFALLS.md:118-129 (Pitfall 11)",
        "docs/context/INTEGRATIONS.md:85-88 (NicePay 2201)",
        "docs/topics/MSA-TRANSITION.md:513-514 (§8-4)",
        "src/main/java/com/hyoguoo/paymentplatform/payment/infrastructure/gateway/nicepay/NicepayPaymentGatewayStrategy.java"
      ],
      "fix": "ADR-05 또는 ADR-29 결정문에 'ALREADY_PROCESSED_PAYMENT/2201 유사 응답은 PG 재조회 + 금액 일치 검증 통과 후에만 success로 해석'을 수락 기준으로 승격. phase 1 전제로 배치"
    },
    {
      "severity": "major",
      "area": "error-code-event-schema",
      "summary": "PG별 에러 코드(Toss/NicePay)의 이벤트 payload 귀속(어댑터-local vs 도메인 중립 enum 매핑 발행)이 ADR-12 결정 질문에 없음",
      "evidence": [
        "docs/topics/MSA-TRANSITION.md:324 (ADR-12)",
        "src/main/java/com/hyoguoo/paymentplatform/payment/exception/common/PaymentErrorCode.java",
        "src/main/java/com/hyoguoo/paymentplatform/paymentgateway/exception/common/TossPaymentErrorCode.java"
      ],
      "fix": "ADR-12에 'PG 에러 코드는 이벤트 payload에서 어댑터-local로 유지하는가, 도메인 중립 enum(DUPLICATE_APPROVAL 등)으로 매핑 발행하는가'를 결정 질문으로 추가"
    },
    {
      "severity": "minor",
      "area": "observability-pending-age",
      "summary": "Kafka publisher 지연 시 stock lock-in 상태(PENDING 장시간 체류) 감지 지표가 ADR-20에 명시되지 않음",
      "evidence": [
        "docs/topics/MSA-TRANSITION.md:479 (§7 #4)",
        "docs/topics/MSA-TRANSITION.md:342 (ADR-20)"
      ],
      "fix": "ADR-20에 payment.outbox.pending_age_seconds(histogram) 포함"
    },
    {
      "severity": "minor",
      "area": "doc-consistency",
      "summary": "ARCHITECTURE.md vs INTEGRATIONS.md의 @TransactionalEventListener 설명 불일치가 ADR-13 전제 평가에 직접 영향 — '후속 TODOS 분리'로는 늦음",
      "evidence": [
        "docs/topics/MSA-TRANSITION.md:515 (§8-4 마지막 bullet)",
        "docs/context/INTEGRATIONS.md:60-65"
      ],
      "fix": "본 라운드 내에서 ADR-13 전제 재서술 및 INTEGRATIONS.md 설명 최소 보정 — 적어도 ADR-13 본문에 현 BEFORE_COMMIT/AFTER_COMMIT 구분을 명시"
    }
  ],
  "counts": {
    "critical": 2,
    "major": 3,
    "minor": 2,
    "n/a": 1
  }
}
```
