```json
{
  "stage": "plan",
  "round": 1,
  "persona": "critic",
  "topic": "NICEPAY-PG-STRATEGY",
  "findings": [
    {"id": "F1", "severity": "major", "description": "T9 domain_risk=false이나 상태 매핑은 돈 관련 로직"},
    {"id": "F2", "severity": "major", "description": "T3/T13 PaymentEventEntity 산출물 중복"},
    {"id": "F3", "severity": "major", "description": "T5/T14 confirm() 전략 선택 산출물 중복"},
    {"id": "F4", "severity": "major", "description": "T1에 PaymentTossConfirmException rename 누락"},
    {"id": "F5", "severity": "minor", "description": "T11 NicepayErrorCode 크로스 모듈 참조"},
    {"id": "F6", "severity": "minor", "description": "T6/T12/T14 의존 과도"}
  ],
  "decision": "revise",
  "summary": "major 4건, minor 2건. Architect 주석의 지적이 PLAN 본문에 반영되지 않았다."
}
```
