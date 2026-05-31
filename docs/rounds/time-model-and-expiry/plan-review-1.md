```json
{
  "stage": "plan-review",
  "persona": "plan-reviewer",
  "round": 1,
  "task_id": null,

  "decision": "pass",
  "reason_summary": "D1~D8 전부 태스크 매핑(orphan 없음), tdd=true 10개 전부 테스트 메서드 스펙 명시, T1→[T2+T4+T5] 단일 커밋 묶음→T10→T3→T6→T7 빌드 그린 배열 확인, AC1 전수 표 15개 클래스 닫힘. critical/major 없음 — minor 3건(표기 오기 2건+F6 이월 1건) 판정 비차단.",

  "checklist": {
    "source": "_shared/checklists/plan-ready.md",
    "items": [
      { "section": "traceability", "item": "PLAN.md가 topic.md 결정 참조", "status": "yes", "evidence": "PLAN.md 링크 + Traceability 표 D1~D8 매핑" },
      { "section": "traceability", "item": "모든 태스크가 결정에 매핑(orphan 없음)", "status": "yes", "evidence": "T1~T16 전부 D1~D8/R1~R4/discuss minor 매핑" },
      { "section": "task quality", "item": "객관적 완료 기준 보유", "status": "yes", "evidence": "T7/T8/T13/T15 grep 0건, T5/T6/T11/T12/T13 테스트 단정, T9 빈 바인딩 확인" },
      { "section": "task quality", "item": "태스크 크기 ≤ 2h(한 커밋 분해)", "status": "yes", "evidence": "주의사항3 T2+T4+T5 단일 논리 커밋 명시" },
      { "section": "task quality", "item": "관련 소스 파일/패턴 언급(정확)", "status": "yes", "evidence": "AC1 표 15개 클래스 전수, 라인 번호 명시" },
      { "section": "TDD specification", "item": "tdd=true 테스트 클래스+메서드 명시", "status": "yes", "evidence": "T2/T3/T5/T6/T8/T10/T11/T12/T13/T14 전부 명시" },
      { "section": "TDD specification", "item": "tdd=false 산출물 명시", "status": "yes", "evidence": "T1/T4/T7/T9/T15/T16 산출물 명시" },
      { "section": "TDD specification", "item": "TDD 분류 합리성", "status": "yes", "evidence": "상태전이/멱등성/offset 정규화 tdd=true, config wiring tdd=false" },
      { "section": "dependency ordering", "item": "layer 의존 순서 준수", "status": "yes", "evidence": "T1→[T2+T4+T5]→T10→T3→T6→T7; T3 완료 전 T14 금지; yml 충돌 방지" },
      { "section": "dependency ordering", "item": "Fake가 소비처보다 먼저", "status": "n/a", "evidence": "신규 Fake 없음" },
      { "section": "dependency ordering", "item": "orphan port 없음", "status": "yes", "evidence": "포트 시그니처 무변경; javadoc 죽은 참조 T12 정정" },
      { "section": "architecture fit", "item": "ARCHITECTURE layer 규칙 충돌 없음", "status": "yes", "evidence": "Clock config D2 준수; domain 인자 주입 원칙" },
      { "section": "architecture fit", "item": "모듈 호출 port/InternalReceiver 경유", "status": "yes", "evidence": "contract 무변경 주의사항7" },
      { "section": "architecture fit", "item": "CONVENTIONS 패턴 준수 계획", "status": "yes", "evidence": "Lombok/예외/로깅 변경 없음" },
      { "section": "artifact", "item": "docs/<TOPIC>-PLAN.md 존재", "status": "yes", "evidence": "docs/TIME-MODEL-AND-EXPIRY-PLAN.md" }
    ],
    "total": 15,
    "passed": 14,
    "failed": 0,
    "not_applicable": 1
  },

  "scores": {
    "traceability": 0.98,
    "decomposition": 0.90,
    "ordering": 0.92,
    "specificity": 0.93,
    "risk_coverage": 0.91,
    "mean": 0.928
  },

  "findings": [
    {
      "severity": "minor",
      "checklist_item": "각 태스크에 관련 소스 파일/패턴이 언급됨(완료 기준 강건성)",
      "location": "T15",
      "problem": "T15(tdd=false)는 pg가 approvedAtRaw raw 문자열을 깎지 않고 전달함을 확인하라고만 하고, 직렬화 contract 불변을 단정하는 회귀 가드 테스트가 없다.",
      "evidence": "T15 완료기준은 grep만; Architect 주석도 동일 지적. plan-critic-2 F6 이월.",
      "suggestion": "execute에서 기존 contract test 커버 여부 명시 확인 또는 approvedAtRaw 직렬화 불변 최소 테스트 추가."
    },
    {
      "severity": "minor",
      "checklist_item": "관련 소스 파일/패턴 언급",
      "location": "T7 완료 기준",
      "problem": "T7 완료 기준 '총 14개 클래스'는 산술 오기. 실제 T3(7)+T12(1)+T7(7)=15개. AC1 전수 표는 15개로 정확.",
      "evidence": "T7 완료기준 '14개' vs AC1 표 '15개'.",
      "suggestion": "'총 14개' → '총 15개' 정정(cosmetic)."
    },
    {
      "severity": "minor",
      "checklist_item": "모든 태스크가 설계 결정에 매핑됨",
      "location": "반환 요약 domain_risk 목록",
      "problem": "반환 요약 domain_risk 목록 '9 (T2,T3,T7,T8,T10,T11,T12,T13,T14)'에 T15 누락. T15 본문은 domain_risk:true.",
      "evidence": "T15 domain_risk:true vs 요약 목록 누락. plan-domain-2도 T15 domain_risk=true 확인.",
      "suggestion": "목록에 T15 추가, 카운트 9→10(cosmetic)."
    }
  ],

  "previous_round_ref": "plan-critic-2.md",
  "delta": { "newly_passed": [], "newly_failed": [], "still_failing": [] },
  "unstuck_suggestion": null
}
```

---

판정: **pass** (critical 0 / major 0 / minor 3 — F6 이월 1건 + 표기 오기 2건, 비차단)

> 메인 오케스트레이터 기록: Plan Reviewer 서브에이전트가 시스템 지침으로 파일 대신 텍스트로 결과를 반환하여, 오케스트레이터가 그 JSON을 본 파일로 저장함. minor P1/P2(표기 오기)는 PLAN.md에서 직접 정정 완료. F6(T15 contract 회귀 가드)는 execute T15에서 확인.
