# review-domain-1

**Topic**: NICEPAY-PG-STRATEGY
**Round**: 1
**Persona**: Domain Expert

## Reasoning

NicePay PG 전략 추가 구현은 결제 도메인의 핵심 불변식(상태 전이, 멱등성 보상, 복구 사이클 PG 라우팅)을 올바르게 유지하고 있다. 2201 기승인존재 에러의 조회 보상 패턴과 금액 교차 검증이 설계 결정(D3)대로 구현되었으며, 복구 사이클에서 `paymentEvent.getGatewayType()`으로 올바른 PG를 선택하는 경로가 확인되었다. `./gradlew test` 전체 통과. minor 수준의 명명 이슈 1건을 제외하면 도메인 리스크 관점에서 안전하다.

## Domain risk checklist

- [x] `paymentKey` / `orderId` / 카드번호 등이 plaintext 로그에 노출되지 않음 -- NicePay 전략 내 로깅은 LogFmt 경유, tid/orderId는 결제 운영 식별자로 민감 PII에 해당하지 않음
- [x] 보상 / 취소 로직에 멱등성 가드 존재 -- 2201 에러 시 `handleDuplicateApprovalCompensation`이 조회 후 금액 교차 검증 수행, D12 가드는 기존 `executePaymentFailureCompensationWithOutbox`에 그대로 유지
- [x] PG가 반환하는 "이미 처리됨" 계열 특수 응답이 맹목 수용되지 않고 정당성 검증을 거침 -- NicePay 2201 에러 시 조회 API 호출 후 `status == "paid"` AND `amount` 일치를 검증, 불일치 시 `NonRetryableException`으로 격리 경로 진입
- [x] 상태 전이가 불변식을 위반하지 않음 -- `PaymentEvent`의 상태 머신 로직 변경 없음, `gatewayType` 필드 추가만 수행
- [x] race window가 있는 경로에 락 / 트랜잭션 격리 고려됨 -- 기존 `claimToInFlight` 원자적 UPDATE + REQUIRES_NEW 트랜잭션 구조 유지, NicePay 경로도 동일 `OutboxProcessingService.process()` 진입

## 도메인 관점 추가 검토

1. **복구 사이클 gatewayType 전파**: `OutboxProcessingService.resolveStatusAndDecision()` (line ~100)와 `resolveFcgStatusAndDecision()` (line ~301) 모두 `paymentEvent.getGatewayType()`을 `getPaymentStatusByOrderId`에 전달함을 확인. `handleAttemptConfirm()` (line ~184)도 `paymentEvent.getGatewayType()`을 command에 설정. 전파 누락 경로 없음.

2. **2201 보상 로직 금액 교차 검증**: `NicepayPaymentGatewayStrategy.resolveCompensationResult()` (line 114-115)에서 `request.amount().compareTo(statusResponse.getAmount()) != 0` 시 `NonRetryableException` throw. BigDecimal compareTo 사용으로 스케일 차이에도 안전(0.00 == 0 → true). 금액 불일치 시 격리 경로 정상 진입.

3. **에러 코드 분류 완전성**: 재시도 가능(2159, A246, A299), 재시도 불가(3011-3014, 2152, 2156), 2201(보상 조회)로 분류. 미분류 에러는 기본적으로 `NonRetryableException`으로 처리되어 안전 측 기본값(fail-safe). 다만 에러 코드 목록이 NicePay 전체 에러 코드에 비해 최소 집합이므로 운영 중 예상치 못한 코드가 non-retryable로 처리될 수 있으나, 이는 QUARANTINE으로 귀결되므로 돈이 새는 경로는 아님.

4. **`getPaymentInfoByTid` 예외 미처리**: `HttpNicepayOperator.getPaymentInfoByTid()` (line 80-92)에 try-catch가 없어 HTTP 에러 시 uncaught RuntimeException 발생. 하지만 호출부인 `NicepayPaymentGatewayStrategy.handleDuplicateApprovalCompensation()` (line 105)의 `catch (Exception e)` 블록이 모든 RuntimeException을 `RetryableException`으로 변환하므로 복구 사이클이 재시도로 처리. 돈이 새지 않는 안전한 경로.

5. **`PaymentErrorCode` 명명**: NicePay 전략 내에서 `PaymentErrorCode.TOSS_RETRYABLE_ERROR` / `TOSS_NON_RETRYABLE_ERROR`를 그대로 사용 (NicepayPaymentGatewayStrategy line 92, 94 등). 에러 메시지가 "Toss 결제에서..."로 시작하여 NicePay 결제건 장애 조사 시 혼란 가능. 운영 모니터링/로그 분석에 영향.

## Findings

1. **severity**: minor
   **checklist_item**: n/a (에러코드 명명 일관성)
   **location**: `NicepayPaymentGatewayStrategy.java:92`, `NicepayPaymentGatewayStrategy.java:94`, `PaymentErrorCode.java:18-19`
   **problem**: NicePay 전략에서 `PaymentErrorCode.TOSS_RETRYABLE_ERROR` / `TOSS_NON_RETRYABLE_ERROR`를 사용하고 있어, 에러 메시지가 "Toss 결제에서 재시도 가능한 오류"로 기록된다. NicePay 결제건 장애 시 로그/메트릭에서 Toss 에러로 오인될 수 있다.
   **evidence**: `PaymentErrorCode.java` line 18: `TOSS_RETRYABLE_ERROR("EO3009", "Toss 결제에서 재시도 가능한 오류가 발생했습니다.")`, NicePay 전략 line 92에서 동일 코드 사용
   **suggestion**: `GATEWAY_RETRYABLE_ERROR` / `GATEWAY_NON_RETRYABLE_ERROR`로 rename하거나, PG 벤더별 에러 코드를 분리. 현재 범위 밖이면 TODOS.md에 기록.

```json
{
  "stage": "review",
  "persona": "domain-expert",
  "round": 1,
  "task_id": null,

  "decision": "pass",
  "reason_summary": "2201 보상 조회 + 금액 교차 검증, 복구 사이클 gatewayType 전파, 에러 코드 분류가 모두 설계 결정대로 구현됨. minor 명명 이슈 1건 외 도메인 리스크 없음.",

  "checklist": {
    "source": "_shared/checklists/code-ready.md",
    "items": [
      {
        "section": "domain risk",
        "item": "paymentKey / orderId / 카드번호 등이 plaintext 로그에 노출되지 않음",
        "status": "yes",
        "evidence": "NicePay 전략 내 LogFmt 사용, 직접 로깅 없음"
      },
      {
        "section": "domain risk",
        "item": "보상 / 취소 로직에 멱등성 가드 존재",
        "status": "yes",
        "evidence": "NicepayPaymentGatewayStrategy.handleDuplicateApprovalCompensation + resolveCompensationResult에서 status/금액 검증"
      },
      {
        "section": "domain risk",
        "item": "PG가 반환하는 이미 처리됨 계열 특수 응답이 맹목 수용되지 않고 정당성 검증을 거침",
        "status": "yes",
        "evidence": "2201 에러 → getPaymentInfoByTid → status==paid AND amount 일치 검증 후에만 SUCCESS 반환"
      },
      {
        "section": "domain risk",
        "item": "상태 전이가 불변식을 위반하지 않음",
        "status": "yes",
        "evidence": "PaymentEvent 상태 머신 로직 변경 없음, gatewayType 필드 추가만"
      },
      {
        "section": "domain risk",
        "item": "race window가 있는 경로에 락 / 트랜잭션 격리 고려됨",
        "status": "yes",
        "evidence": "claimToInFlight 원자적 UPDATE + REQUIRES_NEW 구조 유지"
      }
    ],
    "total": 5,
    "passed": 5,
    "failed": 0,
    "not_applicable": 0
  },

  "scores": {
    "correctness": 0.92,
    "conventions": 0.85,
    "discipline": 0.90,
    "test_coverage": 0.88,
    "domain": 0.91,
    "mean": 0.89
  },

  "findings": [
    {
      "severity": "minor",
      "checklist_item": "n/a",
      "location": "NicepayPaymentGatewayStrategy.java:92, PaymentErrorCode.java:18-19",
      "problem": "NicePay 전략에서 TOSS_RETRYABLE_ERROR / TOSS_NON_RETRYABLE_ERROR 에러 코드를 사용하여 에러 메시지에 Toss 벤더명이 포함됨. NicePay 결제건 장애 조사 시 로그/메트릭에서 오인 가능.",
      "evidence": "PaymentErrorCode.java line 18: TOSS_RETRYABLE_ERROR(\"EO3009\", \"Toss 결제에서 재시도 가능한 오류가 발생했습니다.\"), NicepayPaymentGatewayStrategy line 92에서 동일 코드 사용",
      "suggestion": "GATEWAY_RETRYABLE_ERROR / GATEWAY_NON_RETRYABLE_ERROR로 rename하거나 TODOS.md에 기록"
    }
  ],

  "previous_round_ref": null,
  "delta": null,

  "unstuck_suggestion": null
}
```
