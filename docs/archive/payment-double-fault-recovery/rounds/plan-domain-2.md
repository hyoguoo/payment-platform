# plan-domain-2

**Topic**: PAYMENT-DOUBLE-FAULT-RECOVERY
**Round**: 2
**Persona**: Domain Expert

## Reasoning

Round 2 계획은 Round 1의 minor 4건을 전수 반영했다. Task 4 RecoveryDecision 시그니처가 정적 팩토리 2개(from/fromException)로 확정되어 테스트 스펙과 일치하고, Task 6에 PaymentLoadUseCase 의존이 명시되었으며, Task 9에 PaymentCommandUseCase.getPaymentStatusByOrderId 위임 메서드가 변경 파일에 추가되었고, Task 8이 domain_risk=true로 상향되었다. 핵심 돈 안전성 설계(getStatus 선행, D12 재고 가드 TX 내 재조회, FCG 카운터 비증가, QUARANTINED 격리 시 재고 미복구, fail() no-op)는 Round 1과 동일하게 올바르다.

## Domain risk checklist

- [x] **상태 전이 정합성**: QUARANTINED 허용 source(READY/IN_PROGRESS/RETRYING) Task 1. done() null 가드(Task 2). fail() no-op(Task 2). toRetrying() READY 허용(Task 7). 모든 전이가 테스트 스펙으로 검증됨.
- [x] **멱등성/재진입 방지**: REJECT_REENTRY(Task 4/9)가 종결 건 상태머신 재접촉 차단. fail() no-op이 D12 TX2N 경로 예외 전파 방지. claimToInFlight 원자 선점(D9) 유지.
- [x] **재고 이중 복구 방지**: D12 가드(Task 6)가 TX 내 재조회 방식으로 outbox.status==IN_FLIGHT AND event 비종결 조건 검증. PaymentLoadUseCase 의존이 변경 파일에 명시됨(Round 1 minor 2 해소).
- [x] **PG 실패 모드 분류**: Task 8 domain_risk=true 상향(Round 1 minor 4 해소). getStatusByOrderId 예외 매핑이 retryable/non-retryable/unmapped 3분류. UNKNOWN enum 제거(Task 3)로 조용한 흡수 차단.
- [x] **FCG(한도 소진 최종 확인)**: Task 9 테스트 스펙에 retryCount 미증가 + getStatusByOrderId 2회 호출 검증 명시.
- [x] **QUARANTINED 격리 시 재고 미복구**: Task 5 완료 조건 및 테스트에 increaseStockForOrders 미호출 검증.
- [x] **Race window**: claimToInFlight 원자 선점 유지. 분산락은 non-goal로 명시적 범위 밖.
- [x] **Idempotency-Key**: ATTEMPT_CONFIRM 경로에서 기존 confirm 재호출 시 orderId pass-through 유지.
- [x] **RecoveryDecision 시그니처**: from/fromException 2개 팩토리로 확정(Round 1 minor 3 해소). 테스트 스펙이 양쪽 팩토리를 모두 커버.
- [x] **Hexagonal 위임 경로**: Task 9 변경 파일에 PaymentCommandUseCase.getPaymentStatusByOrderId 위임 메서드 명시(Round 1 minor 1 해소). scheduler가 port를 직접 주입하지 않음.

## 도메인 관점 추가 검토

1. **Task 3/8 경로: mapToPaymentStatus의 default 분기 처리 확인 필요**: 소스 확인 결과 `TossPaymentGatewayStrategy.mapToPaymentStatus` (line 234)에 `default -> PaymentStatus.UNKNOWN`이 존재한다. Task 3에서 `PaymentStatus.UNKNOWN`을 제거하면 이 switch도 컴파일 에러가 발생하므로 수정이 강제된다. Task 3 완료 조건 "기존 mapper 컴파일 에러 없음"이 이를 커버하고, Task 3 변경 파일에 `TossPaymentGatewayStrategy.java`가 포함되어 있다. 다만 default 분기를 `PaymentGatewayStatusUnmappedException` throw로 교체할지, 아니면 `mapToPaymentStatus` 자체를 `PaymentStatus.of()`로 대체할지 구현 판단이 필요하다. 돈 안전성 관점에서는 어느 방식이든 "조용한 흡수"가 사라지므로 안전하다. **severity: n/a** -- 구현 시 자동 해소되는 사항.

2. **Task 8: getStatusByOrderId의 예외 래핑 부재**: 소스 확인 결과 `TossPaymentGatewayStrategy.getStatusByOrderId` (line 193-198)에는 try-catch가 없다. `paymentGatewayInternalReceiver.getPaymentInfoByOrderId(orderId)` 호출에서 발생하는 HTTP 예외(타임아웃, 5xx, 404)가 `PaymentTossRetryableException`/`PaymentTossNonRetryableException`으로 분류되는 지점이 이 메서드 바깥(paymentgateway 모듈 내부 또는 HTTP 클라이언트 레벨)에 있어야 한다. Task 8 설명이 "confirm 경로와 동일 패턴 적용 확인/추가"를 명시하고, domain_risk=true로 상향되었으므로 Domain Expert가 구현 시 재검증한다. 현재 계획 수준에서는 적절히 지시되어 있다. **severity: n/a** -- Task 8 완료 조건이 이미 "타임아웃/5xx -> RetryableException, 404 -> NonRetryableException 분류 확인"을 포함.

## Findings

| # | severity | description | location |
|---|----------|-------------|----------|
| -- | -- | Round 1 minor 4건 전수 해소 확인. 신규 critical/major/minor 없음. | -- |

## JSON
```json
{
  "topic": "PAYMENT-DOUBLE-FAULT-RECOVERY",
  "stage": "plan",
  "round": 2,
  "persona": "domain-expert",
  "decision": "pass",
  "severity_counts": {
    "critical": 0,
    "major": 0,
    "minor": 0,
    "n/a": 2
  },
  "findings": [],
  "summary": "Round 1 minor 4건(Task 4 시그니처 확정, Task 6 PaymentLoadUseCase 의존, Task 9 hexagonal 위임 경로, Task 8 domain_risk 상향)이 전수 반영됨. 16개 이중장애 케이스의 돈 안전성 설계(getStatus 선행, D12 재고 가드 TX 내 재조회, FCG 카운터 비증가, QUARANTINED 격리, fail() no-op, REJECT_REENTRY)가 올바르게 유지됨. 신규 도메인 리스크 없음."
}
```
