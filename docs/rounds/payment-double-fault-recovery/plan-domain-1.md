# plan-domain-1

**Topic**: PAYMENT-DOUBLE-FAULT-RECOVERY
**Round**: 1
**Persona**: Domain Expert

## Reasoning

계획은 16개 이중장애 케이스를 체계적으로 커버하며, getStatus 선행 + RecoveryDecision 단일 분기 + QUARANTINED 격리라는 핵심 설계가 돈 안전성을 올바르게 보호한다. D7 FCG 카운터 비증가는 Task 9 테스트 스펙에 명시되어 있고, D12 가드의 TX 내 재조회는 Task 6에 반영되어 있다. 다만 Architect가 적신호로 남긴 두 가지 변경 파일 누락(Task 9의 getStatusByOrderId 위임 메서드, Task 6의 PaymentLoadUseCase 의존 추가)이 Planner에 의해 해소되지 않았다. 이는 구현 시점에 "어디에 위임 메서드를 둘지" 판단이 불확실해져 hexagonal 위반 또는 구현 차질을 유발할 수 있으나, 돈이 새는 경로를 여는 것은 아니다.

## Domain risk checklist

- [x] **상태 전이 정합성**: QUARANTINED 허용 source(READY/IN_PROGRESS/RETRYING)가 Task 1에 명시. done() null 가드(Task 2), fail() no-op(Task 2), toRetrying() READY 허용(Task 7) 모두 적절.
- [x] **멱등성/재진입 방지**: REJECT_REENTRY(Task 4/9)가 이미 종결된 건의 상태머신 재접촉 차단. fail() no-op이 D12 TX2N 경로의 예외 전파 방지.
- [x] **재고 이중 복구 방지**: D12 가드(Task 6)가 outbox.status==IN_FLIGHT AND event 비종결 조건으로 increaseStockForOrders 실행 제한. TX 내 재조회 방식 채택.
- [x] **PG 실패 모드 분류**: getStatusByOrderId 예외 매핑(Task 8)이 retryable/non-retryable/unmapped 3분류. UNKNOWN enum 제거(Task 3)로 매핑 누락 조용한 흡수 차단.
- [x] **FCG(한도 소진 최종 확인)**: Task 9 테스트 스펙에 retryCount 미증가 + getStatusByOrderId 2회 호출 검증 명시.
- [x] **QUARANTINED 격리 시 재고 미복구**: Task 5 완료 조건 및 테스트에 increaseStockForOrders 미호출 검증 포함.
- [x] **Race window**: claimToInFlight 원자 선점 유지(D9). 분산락은 non-goal로 명시적 범위 밖.
- [x] **Idempotency-Key**: D8로 orderId pass-through 유지 확인. ATTEMPT_CONFIRM 경로에서 기존 confirm 재호출 안전성 보장.

## 도메인 관점 추가 검토

1. **Task 9 변경 파일 누락 -- getStatusByOrderId 위임 메서드**: Architect 적신호가 해소되지 않았다. `OutboxProcessingService`(scheduler)는 `PaymentGatewayPort`를 직접 주입할 수 없다(hexagonal 규칙 위반). 현재 `PaymentCommandUseCase`가 `PaymentGatewayPort`를 보유하므로 여기에 위임 메서드를 두는 것이 자연스럽지만, Task 9의 변경 파일에 `PaymentCommandUseCase.java`가 포함되어 있지 않다. 구현자가 이 누락을 인지하지 못하면 scheduler에서 port를 직접 주입하거나, 변경 범위를 임의로 확장하는 사고가 발생한다. 파일: `docs/PAYMENT-DOUBLE-FAULT-RECOVERY-PLAN.md` Task 9 변경 파일 섹션. **severity: minor** -- 돈 경로에 직접 영향은 없으나, 구현 시 혼선 유발.

2. **Task 6 변경 파일 누락 -- PaymentLoadUseCase 의존 추가**: `PaymentTransactionCoordinator`는 현재 `PaymentLoadUseCase`를 주입받지 않는다(소스 확인: `PaymentTransactionCoordinator.java:20-22`에 `OrderedProductUseCase`, `PaymentCommandUseCase`, `PaymentOutboxUseCase`만 존재). TX 내 event 재조회에 이 의존이 필수인데, 변경 파일 목록에 명시되지 않았다. Architect 주석이 이를 지적했으나 Planner가 변경 파일에 반영하지 않았다. 파일: `docs/PAYMENT-DOUBLE-FAULT-RECOVERY-PLAN.md` Task 6 변경 파일 섹션. **severity: minor** -- D12 가드 자체는 올바르게 설계되어 있고, 누락은 구현 가이드 불완전에 해당.

3. **Task 4 RecoveryDecision.from() 시그니처 불일치**: Architect가 지적한 대로, 설명에는 `from(PaymentEvent, PaymentStatusResult)`로 되어 있으나 테스트 스펙은 retryCount, maxAttempts, 예외 타입을 모두 사용한다. 시그니처 오버로드 또는 확장 record가 필요하지만 결정되지 않았다. 파일: `docs/PAYMENT-DOUBLE-FAULT-RECOVERY-PLAN.md` Task 4 설명. **severity: minor** -- 도메인 판단 로직 자체는 테스트 스펙으로 충분히 정의되어 있으므로 구현 시 해소 가능.

4. **Task 8 tdd=false, domain_risk=false 배정**: getStatusByOrderId의 예외 분류가 전체 RecoveryDecision의 입력을 결정한다. 오분류(예: 5xx를 non-retryable로 매핑)하면 ATTEMPT_CONFIRM이 잘못 발행되어 PG에 중복 승인 시도가 나간다(Idempotency-Key로 금전 안전은 유지되지만, 불필요한 PG 호출 + 잘못된 RecoveryDecision 경로). Architect도 "FakeTossOperator에 시나리오 추가 또는 Task 9 통합 테스트에서 커버 확인" 을 요구했다. Task 9 테스트가 mock 기반이라 이 경로의 실제 예외 분류는 검증되지 않는다. **severity: minor** -- Idempotency-Key가 금전 이중 과금을 막으므로 money-leak은 아니지만, 복구 경로 오판 위험.

## Findings

| # | severity | description | location |
|---|----------|-------------|----------|
| 1 | minor | Task 9 변경 파일에 getStatusByOrderId 위임 메서드 추가 대상(PaymentCommandUseCase 또는 PaymentLoadUseCase)이 누락. Architect 적신호 미해소. | PLAN.md Task 9 변경 파일 |
| 2 | minor | Task 6 변경 파일에 PaymentLoadUseCase 의존 추가가 누락. TX 내 재조회에 필수. Architect 적신호 미해소. | PLAN.md Task 6 변경 파일 |
| 3 | minor | Task 4 RecoveryDecision.from() 시그니처가 설명과 테스트 스펙 간 불일치. 오버로드/확장 방식 미결정. | PLAN.md Task 4 설명 |
| 4 | minor | Task 8 domain_risk=false이나 예외 분류가 RecoveryDecision 정확성에 직결. 별도 테스트 커버리지 미확보. | PLAN.md Task 8 |

## JSON
```json
{
  "topic": "PAYMENT-DOUBLE-FAULT-RECOVERY",
  "stage": "plan",
  "round": 1,
  "persona": "domain-expert",
  "decision": "pass",
  "severity_counts": {
    "critical": 0,
    "major": 0,
    "minor": 4,
    "n/a": 0
  },
  "findings": [
    {
      "id": 1,
      "severity": "minor",
      "description": "Task 9 변경 파일에 getStatusByOrderId 위임 메서드 추가 대상 파일 누락 (Architect 적신호 미해소)",
      "location": "PLAN.md Task 9"
    },
    {
      "id": 2,
      "severity": "minor",
      "description": "Task 6 변경 파일에 PaymentLoadUseCase 의존 추가 누락 (Architect 적신호 미해소)",
      "location": "PLAN.md Task 6"
    },
    {
      "id": 3,
      "severity": "minor",
      "description": "Task 4 RecoveryDecision.from() 시그니처 설명-테스트 불일치, 오버로드 방식 미결정",
      "location": "PLAN.md Task 4"
    },
    {
      "id": 4,
      "severity": "minor",
      "description": "Task 8 domain_risk=false이나 예외 분류가 RecoveryDecision 정확성에 직결, 테스트 미확보",
      "location": "PLAN.md Task 8"
    }
  ],
  "summary": "16개 이중장애 케이스의 돈 안전성 설계(getStatus 선행, D12 재고 가드, FCG 카운터 비증가, QUARANTINED 격리)가 올바르게 구성됨. Architect 적신호 2건(변경 파일 누락)은 구현 가이드 불완전이지 돈 경로 결함이 아님."
}
```
