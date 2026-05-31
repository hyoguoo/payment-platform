# discuss-critic-2

**Topic**: TIME-MODEL-AND-EXPIRY
**Round**: 2
**Persona**: Critic

## Reasoning

Round 1의 두 major(raw-JDBC dedupe UTC 누락 / approvedAt offset 정규화 누락)가 D7/D8로 명시 결정되고 AC8/AC9로 **관찰 가능하게** 검증 가능해졌다. D7은 connection 레벨 UTC 강제(`connectionTimeZone=UTC`) + 명시 Calendar 2차 방어로 raw-JDBC 바인딩·DB `NOW()` split-brain을 한 규약으로 수렴시키고, AC8이 비-UTC JVM round-trip 동일성을 Testcontainers로 단정한다. D8은 `.toLocalDateTime()` 폐기 → `.toInstant()` 정규화를 금지 패턴 명문화로 박고 AC9가 KST(+9) → UTC 절대시점 정합을 단정한다. 코드 위치 주장(payment `parseApprovedAt` L230 `.toLocalDateTime()`, pg strategy L240/249/284, product dedupe `NOW()` L39/L45 vs `Instant` deleteExpired L104)을 실제 소스로 전수 확인했고 전부 정확하다. 신규 결정이 D1~D6·non-goal과 모순 없으며, 미해소 적신호 R1~R4는 plan 게이트 소유권이 명확히 지정됐다. Gate checklist 전 항목 yes → pass.

## Checklist judgement

### scope
- TOPIC UPPER-KEBAB-CASE: **yes** (L1 `TIME-MODEL-AND-EXPIRY`)
- 모듈/패키지 경계 명시: **yes** (§1 In-scope, 서비스별 파일 경로까지 — D7/D8 추가분 L121~L123 반영)
- non-goals ≥1: **yes** (NG1~NG6)
- 범위 밖 이슈 위임/포함: **yes** (R2 BaseEntity 일원화 → TODOS 등재 권고로 이연, L308)

### design decisions
- hexagonal layer 배치 명시: **yes** (§3 표, D7 raw-JDBC 규약 / D8 정규화 layer 행 추가됨 L215-216)
- 포트 인터페이스 위치 결정: **yes** (D2(a) `Clock` 빈 = core/infra config, domain 금지; D7 포트 시그니처 무변경 명시 L186)
- 새 상태 추가 시 전이 다이어그램: **n/a** (NG6 새 상태값 추가 금지 — 단, §4에 기존 전이+만료 정책 mermaid 존재)
- 전체 결제 흐름 호환성: **yes** (D8이 pg→payment `approvedAtRaw` contract 무변경 명시 L197, §6 TX 경계 무변경)

### acceptance criteria
- 성공 조건 관찰 가능: **yes** (AC1~AC9, 특히 AC8 round-trip 절대시점 동일성 / AC9 `09:00+09:00 → 00:00Z` 단정)
- 실패 관찰 방법: **yes** (L290 READY gauge / cleanup 카운터 정체 + 재배달 중복 로그)

### verification plan
- 테스트 계층 결정: **yes** (§8 단위 필수 + D7 Testcontainers 통합 + 회귀)
- 벤치마크 지표: **n/a** (NG5 k6 불필요)

### artifact
- "결정 사항" 섹션 존재: **yes** (§2 D1~D8)

### domain risk (참고 — Domain Expert 주판정 영역, Critic도 gate로 확인)
- 멱등성 전략: **yes** (NG3 TTL P8D 불변, D7이 dedupe 윈도우 오염 차단)
- 장애 시나리오 ≥3: **yes** (F1~F6, 6개)
- 재시도 정책: **yes** (L269 기존 패턴 유지 — 새 경로 미도입)
- PII: **yes** (L271 신규 민감정보 없음)

## Findings

없음 (Gate checklist 전 항목 yes/n-a). 참고용 관찰만 기록:

- (참고, minor 미만) D8의 pg `Clock` fallback 경로(`PgVendorCallService.buildApprovedPayload` L252-254: `approvedAtRaw == null`이면 `OffsetDateTime.now(clock).toString()`)는 D8 본문에 명시되진 않았으나, fallback도 offset 포함 raw 문자열을 emit해 payment `.toInstant()` 정규화 contract를 그대로 탄다. D1로 `clock`이 `systemUTC()`가 되면 `Z` offset이 되어 무손실. 결정과 모순 없음 — plan에서 이 fallback 케이스를 AC9 파라미터에 포함하면 더 완결적(suggestion 수준, gate 실패 아님).

## JSON

```json
{
  "stage": "discuss",
  "persona": "critic",
  "round": 2,
  "task_id": null,

  "decision": "pass",
  "reason_summary": "Round 1 major 2건이 D7/D8로 결정되고 AC8/AC9로 관찰 가능하게 검증 가능. 코드 위치 주장 전수 확인 정확, D1~D6·non-goal과 모순 없음, 적신호 R1~R4 plan 게이트 소유권 명확. Gate 전 항목 yes.",

  "checklist": {
    "source": "_shared/checklists/discuss-ready.md",
    "items": [
      { "section": "scope", "item": "TOPIC UPPER-KEBAB-CASE 확정", "status": "yes", "evidence": "TIME-MODEL-AND-EXPIRY.md L1" },
      { "section": "scope", "item": "모듈/패키지 경계 명시", "status": "yes", "evidence": "§1 In-scope, D7/D8 추가분 L121-123" },
      { "section": "scope", "item": "non-goals >=1", "status": "yes", "evidence": "NG1-NG6 (L127-132)" },
      { "section": "scope", "item": "범위 밖 이슈 위임/포함", "status": "yes", "evidence": "R2 일원화 이연 + TODOS 등재 권고 L308" },
      { "section": "design", "item": "hexagonal layer 배치 명시", "status": "yes", "evidence": "§3 표 + D7/D8 layer 행 L215-216" },
      { "section": "design", "item": "포트 인터페이스 위치 결정", "status": "yes", "evidence": "D2(a) L145; D7 포트 시그니처 무변경 L186" },
      { "section": "design", "item": "새 상태 추가 시 전이 다이어그램", "status": "n/a", "evidence": "NG6 새 상태 금지; 기존 전이+만료 mermaid는 §4 존재" },
      { "section": "design", "item": "전체 결제 흐름 호환성 검토", "status": "yes", "evidence": "D8 approvedAtRaw contract 무변경 L197; §6 TX 무변경" },
      { "section": "acceptance", "item": "성공 조건 관찰 가능", "status": "yes", "evidence": "AC1-AC9; AC8 round-trip 동일성 L288, AC9 09:00+09:00->00:00Z L289" },
      { "section": "acceptance", "item": "실패 관찰 방법 명시", "status": "yes", "evidence": "L290 READY gauge / cleanup 카운터 정체 + 중복 로그" },
      { "section": "verification", "item": "테스트 계층 결정", "status": "yes", "evidence": "§8 단위+D7 Testcontainers 통합+회귀" },
      { "section": "verification", "item": "벤치마크 지표", "status": "n/a", "evidence": "NG5 k6 불필요" },
      { "section": "artifact", "item": "결정 사항 섹션 존재", "status": "yes", "evidence": "§2 D1-D8" },
      { "section": "domain", "item": "멱등성 전략 결정", "status": "yes", "evidence": "NG3 TTL P8D 불변 + D7 dedupe 윈도우 보호" },
      { "section": "domain", "item": "장애 시나리오 >=3", "status": "yes", "evidence": "F1-F6 6개 (§5)" },
      { "section": "domain", "item": "재시도 정책", "status": "yes", "evidence": "L269 기존 패턴 유지" },
      { "section": "domain", "item": "PII 검토", "status": "yes", "evidence": "L271 신규 민감정보 없음" }
    ],
    "total": 17,
    "passed": 15,
    "failed": 0,
    "not_applicable": 2
  },

  "scores": {
    "clarity": 0.92,
    "completeness": 0.93,
    "risk": 0.90,
    "testability": 0.91,
    "fit": 0.94,
    "mean": 0.92
  },

  "findings": [],

  "previous_round_ref": "discuss-critic-1.md",
  "delta": {
    "newly_passed": [
      "raw-JDBC dedupe UTC 규약 명문화 (D7/AC8)",
      "approvedAt offset 정규화 결정 (D8/AC9)",
      "raw-JDBC round-trip 통합 테스트 계층 결정 (§8 Testcontainers + 비-UTC JVM 프로파일)"
    ],
    "newly_failed": [],
    "still_failing": []
  },

  "unstuck_suggestion": null
}
```
