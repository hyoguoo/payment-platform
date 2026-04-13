```json
{
  "stage": "plan",
  "round": 1,
  "persona": "domain-expert",
  "topic": "NICEPAY-PG-STRATEGY",
  "findings": [
    {"id": "DE-F1", "severity": "major", "category": "state-mapping", "description": "T9 domain_risk=false이나 상태 매핑은 복구 사이클 RecoveryDecision 판정을 좌우하는 돈 관련 로직. domain_risk: true 상향 필요."},
    {"id": "DE-F2", "severity": "major", "category": "idempotency", "description": "T10 금액 불일치 시 RetryableException은 retry budget을 불필요하게 소모. NonRetryableException으로 변경하여 즉시 종결해야 한다."}
  ],
  "decision": "revise",
  "summary": "major 2건. T9 domain_risk 상향, T10 금액 불일치 시 NonRetryableException."
}
```
