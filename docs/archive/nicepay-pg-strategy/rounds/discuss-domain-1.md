```json
{
  "stage": "discuss",
  "round": 1,
  "persona": "domain-expert",
  "topic": "NICEPAY-PG-STRATEGY",
  "checklist_items": [
    {"id": "domain-risk-1", "label": "멱등성 전략이 결정됨", "result": "pass", "note": "D3에서 2201 보상 패턴 설계"},
    {"id": "domain-risk-2", "label": "장애 시나리오 최소 3개 식별됨", "result": "pass", "note": "R1-R5 식별"},
    {"id": "domain-risk-3", "label": "재시도 정책 정의됨", "result": "pass", "note": "기존 retry/FCG 경로 재사용"},
    {"id": "domain-risk-4", "label": "PII/민감정보 검토됨", "result": "pass", "note": "새 PII 도입 없음"}
  ],
  "findings": [
    {"id": "DE1-1", "severity": "major", "category": "상태 전이 / race window", "description": "복구 사이클의 getStatusByOrderId 경로에 gatewayType 라우팅 미설계"},
    {"id": "DE1-2", "severity": "minor", "category": "상태 매핑", "description": "NicePay failed -> ABORTED -> FAILED 변환 경로 호환성 명시 필요"},
    {"id": "DE1-3", "severity": "minor", "category": "멱등성 / 금전 정확성", "description": "2201 보상 조회 시 금액 교차 검증 미언급"}
  ],
  "decision": "revise",
  "summary": "major 1건: 복구 사이클에서 gatewayType 라우팅이 설계되지 않아 NicePay 결제건이 Toss API로 잘못 조회될 위험."
}
```
