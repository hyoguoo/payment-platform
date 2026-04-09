# plan-critic-1

**Topic**: PAYMENT-DOUBLE-FAULT-RECOVERY
**Round**: 1
**Persona**: Critic

## Reasoning
PLAN은 12개 태스크로 F1~F7 및 discuss findings를 traceability 테이블과 함께 매핑했으나, Task 12가 payment/product 두 컨텍스트 사이에서 산출물과 테스트 스펙이 모순되어 한 태스크 한 파일 원칙을 위반하고 ProductPort 우회 위험이 있다(critical). 또한 Task 3 domain 테스트가 gateway enum을 참조해 layer 역의존을 유발하며, F5 매핑 체인의 핵심인 gateway→domain 어댑터(`PaymentInfrastructureMapper`/`TossPaymentGatewayStrategy`/`TossApiFailureUseCase`) 수정 태스크가 어디에도 없다(critical orphan). Task 4 패키지 미러링 불일치와 Task 8 scheduler 응집도/사이즈 초과는 major.

## Checklist judgement

- traceability: orphan 존재 (gateway→domain 매퍼 수정 태스크 없음) → no
- task quality(산출물 명시): Task 12 "또는" 모호 → no
- task quality(≤2시간): Task 8 12개 테스트 + 9-way switch → no
- TDD spec: yes
- dependency ordering: layer 순서 yes, orphan port 없음 yes
- architecture fit: Task 3 domain→gateway 역의존, Task 12 ProductPort 우회 가능성 → no
- artifact: PLAN.md 존재 → yes

## Findings

1. **F-1 critical** — Task 12 cross-context 모호 (PLAN.md line 270-288). 산출물이 `PaymentTransactionCoordinator.java` "또는" `product 컨텍스트 어댑터`로 두 갈래. 테스트는 product 측 보장(`StockServiceIdempotencyTest`), 산출물은 payment 측 guard. 한 태스크 = 한 컨텍스트 원칙 위반.
2. **F-2 critical** — gateway→domain 매핑 어댑터 orphan. F5 체인을 닫으려면 `PaymentInfrastructureMapper`(~line 46), `TossPaymentGatewayStrategy`(~line 127-134), `TossApiFailureUseCase`(~line 16) 중 어딘가에서 `isAlreadyProcessed → ALREADY_PROCESSED` 분기를 추가해야 하나 PLAN의 어떤 태스크 산출물에도 없음.
3. **F-3 critical** — Task 3 layer 역의존 (PLAN.md line 65-67). domain 패키지의 `PaymentConfirmResultStatusTest.of_alreadyProcessed_returnsAlreadyProcessed`가 `TossPaymentErrorCode.isAlreadyProcessed()` 직접 참조 → domain → paymentgateway import.
4. **F-4 major** — Task 4 패키지 불일치 (line 90 vs line 98). production은 `paymentgateway/exception/common/`, 테스트는 `paymentgateway/exception/`.
5. **F-5 major** — Task 8 scheduler 응집도/크기 초과 (line 175-202). 9-value exhaustive switch를 scheduler가 소유, 12개 테스트 메서드 단일 커밋 초과.
6. **F-6 minor** — Task 9 책임 분할 미명시 (line 207-220). port(`Duration nextDelay`)와 Jpa(`LocalDateTime nextRetryAt`) 위임 계산 위치 미기술.

## JSON
```json
{
  "stage": "plan",
  "persona": "critic",
  "round": 1,
  "task_id": null,
  "decision": "fail",
  "reason_summary": "Task 12 cross-context 경계 모호, gateway→domain 매핑 어댑터 수정 태스크 orphan, Task 3 domain 테스트의 layer 역의존 등 critical 결함 3건. plan 재작성 필요.",
  "checklist": {
    "source": "_shared/checklists/plan-ready.md",
    "items": [
      {"section": "traceability", "item": "모든 태스크가 설계 결정에 매핑됨 (orphan 없음)", "status": "no", "evidence": "PLAN.md 전역 — PaymentInfrastructureMapper/TossPaymentGatewayStrategy/TossApiFailureUseCase 수정 태스크 부재로 F5 체인 끊김"},
      {"section": "task quality", "item": "각 태스크에 관련 소스 파일/패턴이 언급됨", "status": "no", "evidence": "PLAN.md line 287 — Task 12 산출물 'PaymentTransactionCoordinator 또는 product 컨텍스트 어댑터'로 모호"},
      {"section": "task quality", "item": "태스크 크기 ≤ 2시간", "status": "no", "evidence": "PLAN.md line 184-198 — Task 8 테스트 메서드 12개 + 9-value exhaustive switch + LogFmt + null guard + retry budget"},
      {"section": "TDD specification", "item": "tdd=true 태스크는 테스트 클래스 + 메서드 스펙 명시", "status": "yes", "evidence": "Task 3,5,6,7,8,10,11,12 모두 명시"},
      {"section": "dependency ordering", "item": "layer 의존 순서 준수", "status": "yes", "evidence": "Task 1(port)→2(error)→3,4,5(domain)→6,7(application)→9(infra)→8(scheduler)"},
      {"section": "dependency ordering", "item": "orphan port 없음", "status": "yes", "evidence": "Task 1 port → Task 9 Impl 위임"},
      {"section": "architecture fit", "item": "ARCHITECTURE.md layer 규칙 준수", "status": "no", "evidence": "PLAN.md line 67 — Task 3 domain 테스트가 TossPaymentErrorCode 참조 → domain→paymentgateway 역의존"},
      {"section": "architecture fit", "item": "모듈 간 호출이 port를 통함", "status": "no", "evidence": "PLAN.md line 270-288 — Task 12 product의 StockService 직접 수정 가능성, ProductPort 우회 위험"},
      {"section": "artifact", "item": "docs/<TOPIC>-PLAN.md 존재", "status": "yes", "evidence": "docs/PAYMENT-DOUBLE-FAULT-RECOVERY-PLAN.md"}
    ],
    "total": 14,
    "passed": 9,
    "failed": 5,
    "not_applicable": 0
  },
  "scores": {
    "traceability": 0.70,
    "decomposition": 0.55,
    "ordering": 0.85,
    "specificity": 0.60,
    "risk_coverage": 0.80,
    "mean": 0.70
  },
  "findings": [
    {
      "severity": "critical",
      "checklist_item": "각 태스크에 관련 소스 파일/패턴이 언급됨 / 모듈 간 호출이 port를 통함",
      "location": "docs/PAYMENT-DOUBLE-FAULT-RECOVERY-PLAN.md line 270-288 (Task 12)",
      "problem": "산출물이 'PaymentTransactionCoordinator.java 또는 product 컨텍스트 연동 어댑터'로 두 컨텍스트에 걸쳐 모호. 테스트 스펙(StockServiceIdempotencyTest)은 product 측 멱등성 보장을, 산출물 후보는 payment 측 guard를 시사하여 모순. 한 태스크 한 파일 원칙 위반 + ProductPort 우회 위험.",
      "evidence": "line 287 '또는' 표현, line 281 StockService 직접 테스트 메서드",
      "suggestion": "Guard-at-caller(payment 측 PaymentTransactionCoordinator)로 단일화하거나 payment-task와 product-task 두 개로 분할"
    },
    {
      "severity": "critical",
      "checklist_item": "모든 태스크가 설계 결정 중 하나 이상에 매핑됨 (orphan 태스크 없음)",
      "location": "docs/PAYMENT-DOUBLE-FAULT-RECOVERY-PLAN.md (전역, F5 체인)",
      "problem": "F5 매핑 체인 (gateway enum 플래그 → gateway→domain 어댑터 분기 → domain enum → use-case)에서 어댑터 단계가 누락. PaymentInfrastructureMapper, TossPaymentGatewayStrategy, TossApiFailureUseCase 어느 것도 PLAN의 산출물에 등장하지 않아 execute 단계에서 매핑 체인이 끊어진다.",
      "evidence": "PLAN.md 전역 — 위 3개 파일이 산출물 어디에도 없음; line 51-55 architect 인라인 주석에서 명시적으로 누락 지적",
      "suggestion": "Task 4와 Task 3 사이에 'gateway→domain 매핑 어댑터에서 isAlreadyProcessed 분기 추가' 신규 태스크 삽입"
    },
    {
      "severity": "critical",
      "checklist_item": "ARCHITECTURE.md layer 규칙과 충돌 없음",
      "location": "docs/PAYMENT-DOUBLE-FAULT-RECOVERY-PLAN.md line 65-67 (Task 3 테스트 스펙)",
      "problem": "domain 패키지의 PaymentConfirmResultStatusTest가 TossPaymentErrorCode.isAlreadyProcessed()를 직접 참조하면 domain → paymentgateway import가 발생하여 layer 역의존. domain 테스트는 gateway enum을 몰라야 한다.",
      "evidence": "line 65 'payment/domain/PaymentConfirmResultStatusTest.java' 패키지 + line 67 'TossPaymentErrorCode.isAlreadyProcessed() = true 인 코드' 호출 묘사",
      "suggestion": "domain 테스트는 String 입력으로 of(...) 매핑만 검증하고, gateway enum→domain enum 매핑은 gateway 매퍼 단위 테스트(F-2의 신규 태스크)로 이관"
    },
    {
      "severity": "major",
      "checklist_item": "각 태스크에 관련 소스 파일/패턴이 언급됨",
      "location": "docs/PAYMENT-DOUBLE-FAULT-RECOVERY-PLAN.md line 90 vs line 98 (Task 4)",
      "problem": "production은 paymentgateway/exception/common/TossPaymentErrorCode.java, 테스트는 paymentgateway/exception/TossPaymentErrorCodeTest.java로 패키지 미러링 불일치.",
      "evidence": "line 90 vs line 98 경로 비교; line 78-80 architect 주석",
      "suggestion": "테스트 경로를 paymentgateway/exception/common/TossPaymentErrorCodeTest.java로 정정"
    },
    {
      "severity": "major",
      "checklist_item": "태스크 크기 ≤ 2시간 / 응집도",
      "location": "docs/PAYMENT-DOUBLE-FAULT-RECOVERY-PLAN.md line 175-202 (Task 8)",
      "problem": "scheduler가 PaymentStatus 9-value exhaustive switch를 직접 소유 — 향후 PG 추가 시 scheduler 재수정 강제. 12개 테스트 메서드 + null guard + LogFmt + retry budget을 단일 커밋(≤2시간)에 담기 어려움.",
      "evidence": "line 184-198 테스트 12종, line 198 @ParameterizedTest 전체 enum 분기; line 172-174 architect 응집도 지적",
      "suggestion": "(8a) PaymentStatus/PaymentStatusResult에 isDone/isTerminalFailure/isStillPending 헬퍼 추가 domain 태스크 + (8b) scheduler 3-way 분기 태스크로 분할"
    },
    {
      "severity": "minor",
      "checklist_item": "각 태스크에 관련 소스 파일/패턴이 언급됨",
      "location": "docs/PAYMENT-DOUBLE-FAULT-RECOVERY-PLAN.md line 207-220 (Task 9)",
      "problem": "port(Duration nextDelay)와 Jpa(LocalDateTime nextRetryAt) 사이의 책임 분할(Impl에서 now.plus(nextDelay) 계산 후 위임)이 산출물 설명에 명시되지 않음 — execute 단계에서 retry 정책이 infrastructure로 누수될 위험.",
      "evidence": "line 218-220 산출물 설명; line 149-150, 206 architect 주석",
      "suggestion": "Task 9 산출물에 'PaymentOutboxRepositoryImpl에서 now.plus(nextDelay) 계산 후 Jpa 메서드에 위임' 명시"
    }
  ],
  "previous_round_ref": null,
  "delta": null,
  "unstuck_suggestion": null
}
```
