# review-domain-1

**Topic**: PAYMENT-DOUBLE-FAULT-RECOVERY
**Round**: 1
**Persona**: Domain Expert

## Reasoning

이중장애 복구 전체 diff를 결제 도메인 리스크(상태 전이, 멱등성, race window, 재고 정합성, PG 실패 모드) 관점에서 검토했다. 핵심 변경인 `OutboxProcessingService` 재작성, `RecoveryDecision` 도메인 값 객체, D12 재고 가드, FCG 경로, 상태 모델 확장(`QUARANTINED`, `UNKNOWN` 제거)을 대상으로 실제 소스를 교차 검증했다.

## Domain risk checklist

- [x] `paymentKey` / `orderId` / 카드번호 등이 plaintext 로그에 노출되지 않음 — `LogFmt` 기반 구조화 로그 사용. orderId는 운영 식별 목적으로 로그 메시지에 포함되나 PII가 아님. paymentKey는 로그에 직접 출력되지 않음.
- [x] 보상 / 취소 로직에 멱등성 가드 존재 — D12 가드(`executePaymentFailureCompensationWithOutbox`)가 TX 내 재조회로 outbox IN_FLIGHT + event non-terminal 조건 확인 후에만 재고 복구. `fail()` no-op으로 종결 재호출 방어.
- [x] PG가 반환하는 "이미 처리됨" 계열 특수 응답이 맹목 수용되지 않고 정당성 검증을 거침 — `RecoveryDecision.from()`이 PG DONE 시 `approvedAt` 존재 여부를 분기(`COMPLETE_SUCCESS` vs `GUARD_MISSING_APPROVED_AT`). `UNKNOWN` 제거로 매핑 실패 시 예외 전파.
- [x] 상태 전이가 불변식을 위반하지 않음 — `quarantine()`은 READY/IN_PROGRESS/RETRYING에서만, `done()`은 approvedAt null 가드, `fail()`은 종결 no-op, `toRetrying()`은 READY 확장. 모두 `@ParameterizedTest @EnumSource`로 커버.
- [x] race window가 있는 경로에 락 / 트랜잭션 격리 고려됨 — `claimToInFlight` 원자 선점 유지, D12 가드 TX 내 재조회, FCG 별도 getStatus 호출.

## 도메인 관점 추가 검토

1. **[minor] `GUARD_MISSING_APPROVED_AT` 무한 재시도 가능성** — `OutboxProcessingService:168-173` (`applyDecision` GUARD_MISSING_APPROVED_AT 분기). PG가 DONE인데 approvedAt이 지속적으로 null인 경우(PG 계약 위반 지속), 이 분기는 `executePaymentRetryWithOutbox`를 호출하되 소진 검사를 하지 않는다. `RecoveryDecision.from()`에서 `retryCount < maxRetries`이면 `GUARD_MISSING_APPROVED_AT`가 아닌 다른 분기로 가겠지만, 정작 `GUARD_MISSING_APPROVED_AT`는 retryCount/maxRetries 비교 없이 무조건 반환된다(`RecoveryDecision.java:73-75`). 결과적으로 PG가 매번 `DONE + null approvedAt`을 주면, retryCount가 maxRetries에 도달한 후에도 `resolveStatusAndDecision`에서 `GUARD_MISSING_APPROVED_AT`가 반환되고, `applyDecision` switch에서 소진 체크 없이 `executePaymentRetryWithOutbox`가 호출된다. `incrementRetryCount()`는 outbox를 PENDING으로 되돌리므로 다음 틱에 다시 선점되어 무한 루프가 된다. PLAN의 D7 FCG 경로는 "한도 소진 시 최종 getStatus 재확인"을 규정하지만, `GUARD_MISSING_APPROVED_AT`는 FCG로 가지 않고 직접 retry한다. 돈이 새는 경로는 아니지만(DONE 상태를 확인했으므로 PG 측 승인은 완료), outbox가 무한 사이클에 빠져 다른 PENDING 건의 처리를 지연시킬 수 있다.

2. **[major] FCG `COMPLETE_SUCCESS` 경로에서 stale `paymentEvent` 사용** — `OutboxProcessingService:244-246` (`handleFinalConfirmationGate` 내 COMPLETE_SUCCESS 분기). `executePaymentSuccessCompletionWithOutbox(paymentEvent, approvedAt, outbox)`가 호출될 때, `paymentEvent`는 `process()` Step 2에서 로드한 객체다. FCG에 도달하기까지 retry 경로를 거쳤다면 `transactionCoordinator.executePaymentRetryWithOutbox`가 DB의 `PaymentEvent`를 이미 `RETRYING`으로 전이했을 수 있지만, 메모리의 `paymentEvent`는 여전히 `IN_PROGRESS`다. 다만 `executePaymentSuccessCompletionWithOutbox` 내부에서 `markPaymentAsDone(paymentEvent, approvedAt)`이 호출되고, `PaymentEvent.done()`은 `IN_PROGRESS`와 `RETRYING` 모두 허용하므로 상태 전이 자체는 성공한다. 그러나 **JPA 영속성 컨텍스트가 stale 객체를 사용하면 `markPaymentAsDone` 내부의 `saveOrUpdate`가 DB에서 이미 `RETRYING`인 행을 `IN_PROGRESS` 기반 스냅샷으로 덮어쓸 수 있다**. `executePaymentRetryWithOutbox`와 `executePaymentSuccessCompletionWithOutbox`는 모두 `@Transactional`이므로 별도 TX에서 실행되고, stale 객체의 JPA merge 동작에 따라 `RETRYING` -> `DONE` 대신 `IN_PROGRESS` -> `DONE`이 기록될 수 있다. 이는 `PaymentHistory` AOP 기록에 잘못된 `previousStatus`를 남긴다. 직접적인 돈 손실은 아니나, audit trail 정합성 위반이며 복구 판단을 어렵게 한다. **같은 패턴이 `applyDecision` COMPLETE_SUCCESS 분기(line 139), `handleAttemptConfirm` SUCCESS 분기(line 199)에도 존재하나, 이 두 경로는 retry를 거치지 않으므로 stale 가능성이 낮다. FCG 경로만 실질적 위험이다.**

3. **[minor] FCG `COMPLETE_FAILURE` 경로에서 stale `paymentOrderList` 전달** — `OutboxProcessingService:251-252`. `handleFinalConfirmationGate`에서 `paymentEvent.getPaymentOrderList()`를 전달하지만, D12 가드(`executePaymentFailureCompensationWithOutbox`)는 TX 내에서 `freshEvent`를 재조회하므로 재고 복구 판단에는 영향이 없다. 그러나 `increaseStockForOrders(paymentOrderList)`에 전달되는 `paymentOrderList`는 여전히 process() 진입 시의 stale 객체다. `PaymentOrder`의 `quantity`나 `productId`가 TX 사이에 변할 가능성은 현재 구조에서 없으므로 실질적 위험은 낮다.

4. **[minor] `rejectReentry`에서 outbox 상태 변경이 트랜잭션 경계 밖에서 도메인 객체에 적용됨** — `OutboxProcessingService:290-292`. `outbox.toDone()` 호출 후 `paymentOutboxUseCase.save(outbox)`가 별도 `@Transactional`로 실행된다. `toDone()`은 IN_FLIGHT에서만 가능한데, `save()` TX 시작 시점에 다른 스레드가 이미 해당 outbox를 변경했다면 stale 상태에서 toDone()이 통과한 뒤 save에서 optimistic lock 없이 덮어쓸 수 있다. 다만 `claimToInFlight`가 선행하므로 동일 outbox에 대한 동시 처리는 이미 배제된 상태이다. 실질 위험은 낮다.

5. **[minor] `RecoveryDecision.fromException` — `PaymentTossNonRetryableException`이 checked exception이지만 `Exception` 파라미터로 수신** — `RecoveryDecision.java:98-106`. `PaymentTossNonRetryableException`은 `Exception extends`(checked)인데, `fromException`의 시그니처가 `Exception exception`으로 받는다. 타입 안전성은 컴파일러가 보장하지 못하며, 미래에 다른 checked exception이 추가되면 catch 블록 누락 위험이 있다. 도메인 결정 로직의 방어성 관점에서 sealed interface 또는 marker interface를 고려할 수 있다.

## Findings

| # | Severity | Category | Location | Description |
|---|----------|----------|----------|-------------|
| 1 | major | stale entity / audit trail 정합성 | `OutboxProcessingService.java:244-246` (FCG COMPLETE_SUCCESS) | FCG 경로에서 process() 진입 시 로드한 stale `paymentEvent`를 `executePaymentSuccessCompletionWithOutbox`에 전달. retry TX를 거친 후 DB 상태는 RETRYING이지만 메모리 객체는 IN_PROGRESS. JPA merge 시 audit trail(`PaymentHistory`)에 잘못된 previousStatus 기록 가능. |
| 2 | minor | 무한 재시도 | `OutboxProcessingService.java:168-173`, `RecoveryDecision.java:73-75` | `GUARD_MISSING_APPROVED_AT` 분기가 retryCount 소진 여부를 확인하지 않아, PG가 지속적으로 DONE+null approvedAt을 반환하면 무한 retry 루프 발생 가능. |
| 3 | minor | stale entity | `OutboxProcessingService.java:251-252` (FCG COMPLETE_FAILURE) | FCG에서 stale `paymentOrderList`를 D12 가드에 전달. 현재 구조에서 실질 위험 낮음. |
| 4 | minor | 트랜잭션 경계 | `OutboxProcessingService.java:290-292` (rejectReentry) | `outbox.toDone()` 도메인 변경이 TX 밖에서 수행. claimToInFlight 선점으로 동시성 위험은 배제되나 패턴 일관성 결여. |
| 5 | minor | 타입 안전성 | `RecoveryDecision.java:98` (fromException) | checked/unchecked 예외 혼합 수신. 미래 예외 추가 시 catch 누락 위험. |

## JSON
```json
{
  "stage": "review",
  "topic": "PAYMENT-DOUBLE-FAULT-RECOVERY",
  "round": 1,
  "persona": "domain-expert",
  "verdict": "revise",
  "findings": [
    {
      "id": "DE-1",
      "severity": "major",
      "category": "stale entity / audit trail",
      "location": "OutboxProcessingService.java:244-246",
      "description": "FCG COMPLETE_SUCCESS 경로에서 stale paymentEvent를 executePaymentSuccessCompletionWithOutbox에 전달. retry TX 후 DB는 RETRYING이나 메모리는 IN_PROGRESS. JPA merge 시 PaymentHistory에 잘못된 previousStatus 기록 가능."
    },
    {
      "id": "DE-2",
      "severity": "minor",
      "category": "무한 재시도",
      "location": "OutboxProcessingService.java:168-173, RecoveryDecision.java:73-75",
      "description": "GUARD_MISSING_APPROVED_AT 분기가 retryCount 소진 여부 미확인. PG가 지속 DONE+null approvedAt 반환 시 무한 retry 루프."
    },
    {
      "id": "DE-3",
      "severity": "minor",
      "category": "stale entity",
      "location": "OutboxProcessingService.java:251-252",
      "description": "FCG COMPLETE_FAILURE에서 stale paymentOrderList를 D12 가드에 전달. 현재 구조에서 실질 위험 낮음."
    },
    {
      "id": "DE-4",
      "severity": "minor",
      "category": "트랜잭션 경계",
      "location": "OutboxProcessingService.java:290-292",
      "description": "rejectReentry에서 outbox.toDone() 도메인 변경이 TX 밖 수행. claimToInFlight 선점으로 동시성 위험 배제되나 패턴 불일치."
    },
    {
      "id": "DE-5",
      "severity": "minor",
      "category": "타입 안전성",
      "location": "RecoveryDecision.java:98",
      "description": "fromException이 checked/unchecked 예외를 Exception으로 혼합 수신. 미래 예외 추가 시 catch 누락 위험."
    }
  ],
  "summary": "major 1건(FCG stale entity로 인한 audit trail 정합성 위험), minor 4건. critical 없음. 판정: revise."
}
```
