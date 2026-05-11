# discuss-critic-1

**Topic**: CLEANUP-BATCH-A
**Round**: 1
**Persona**: Critic

## Reasoning

4 sub-section (TC-16 / TC-10 / TC-2 / TC-5) 모두 layer 경계가 분명하고 cross 의존 0이 §1.5 매트릭스로 검증됐다. 사용자 답변 4건 (단일 토픽 / builder factory only / db/schema+db/seed / 503 일괄)이 본문에 반영됐고, 각 항목에 별 토픽 위임 (ErrorDecoder 분기, prod profile, ArchUnit, 동적 backoff, 위키 동기화)이 명시되어 범위 누수 없다. 장애 시나리오 §4 3건 + §7 트레이드오프 4건이 핵심 위험 (builder 가드 우회, Flyway missing migration, client 무한 retry, INSERT IGNORE 안전망)을 빠짐없이 짚고, PG-CONFIRM-LISTENER-SPLIT 봉인 factory 4종 의도가 §1.2 factory 표로 보존된다. 컴파일러 강제 불가 메모리는 §7.3에 그대로 인지되어 있다. 체크리스트 critical / major 없음 — pass.

## Checklist judgement

### scope (4/4 yes)
- TOPIC UPPER-KEBAB-CASE: yes — line 1 `CLEANUP-BATCH-A`
- 모듈/패키지 경계 명시: yes — §1.1 (pg-service application) / §1.2 (pg-service domain) / §1.3 (product+user-service infrastructure) / §1.4 (payment-service presentation), §3 인벤토리 line 331~358
- non-goals: yes — §0 비범위 7건 line 111~118
- 범위 밖 이슈 위임: yes — TC-13 (위키, line 118), ErrorDecoder 분기 (line 112), prod profile (line 113), 동적 backoff (line 114), ArchUnit (line 444), dedupe 스케줄러 (line 117) 모두 별 토픽 위임 명시

### design decisions (2 yes / 2 n/a)
- hexagonal layer 배치: yes — 4 sub-section 헤더가 각각 application / domain / infrastructure / presentation layer 명시
- 포트 인터페이스 위치: n/a — 신규 포트 도입 없음 (dead service 제거 / 도메인 POJO 패턴 통일 / yml 설정 / advice 매핑)
- 상태 전이 다이어그램: n/a — 새 도메인 상태 추가 없음 (§6 line 426 "새 트랜잭션 / 외부 I/O 경계 도입 0" 명시)
- 결제 흐름 호환성: yes — §1.2 PG-CONFIRM-LISTENER-SPLIT m1 봉인 시나리오 의도 보존 검토 (line 155~166), §1.4 advice 우선순위 검토 (line 283~285)

### acceptance criteria (2/2 yes)
- 관찰 가능 성공 조건: yes — §2 line 314~325 acceptance 신호 표 (`./gradlew test PASS`, `grep returns 0 lines`, `status 503 + Retry-After: 5` 헤더 검증, 정적 분석 PASS)
- 실패 관찰 방법: yes — 각 acceptance 항목에 검증 명령 + 통과 기준 명시

### verification plan (1 yes / 1 n/a)
- 테스트 계층 결정: yes — §5 line 405~420 단위 RED→GREEN + 회귀 + 수동 스모크 (옵션 갭 (b)/(c) 명시)
- 벤치마크 지표: n/a — §6 line 426 코드 청소만 + TPS/latency 영향 0

### artifact (1/1 yes)
- 결정 사항 섹션: yes — §1 line 125~308

### domain risk (Domain Expert 전용 — 참고)
- 멱등성 전략: n/a — 신규 멱등 키 도입 없음. §7.4가 기존 INSERT IGNORE 멱등성 trade-off 명시
- 장애 시나리오 3+: yes — §4 line 372~399 (4.1 Flyway missing migration / 4.2 client 무한 retry / 4.3 builder 가드 우회) 정확히 3건
- 재시도 정책: yes — §1.4 `Retry-After: 5` 고정 + 동적 backoff 비범위 명시 (line 114)
- PII/민감정보: n/a — 새 PII 도입 0

## Findings

(없음 — critical / major 0건)

### Minor (참고만, 판정 영향 없음)

- **m1** — §1.3 검증 방식 (Testcontainers vs 수동 스모크 vs healthcheck) 최종 1개 미확정
  - location: `docs/topics/CLEANUP-BATCH-A.md` line 243, 321
  - 관찰: §1.3 acceptance "옵션 — Testcontainers 통합 테스트 권장" + "본 결정은 §2 acceptance 의 verification 트랙에서 plan 단계에 검증 비용 vs 가치 재평가 후 확정" 으로 plan 단계 결정 위임. 사용자 답변 4건이 검증 방식까지 포함 안 했고 Round 0 ledger #8이 사용자 판단으로 남긴 항목.
  - 영향: discuss-ready 의 "테스트 계층 결정됨" 은 §5에 단위 / 통합 / 수동 스모크 3층이 정의돼 있어 충족. (a)/(b)/(c) 중 어떤 길로 갈지는 plan 단계 acceptance 비용 분석으로 자연스럽게 결정 가능. discuss 라운드 차단 사유 아님.
  - 권고: plan 단계 초입에 docker-compose 의 `SPRING_PROFILES_ACTIVE=docker` 실제 활성 여부 사전 확인 (§7.2 line 441) + 검증 비용 < 학습 가치 판단해서 (a) Testcontainers 1건 선택 권고

## JSON

```json
{
  "stage": "discuss",
  "persona": "critic",
  "round": 1,
  "task_id": null,

  "decision": "pass",
  "reason_summary": "4 sub-section cross 의존 0이 §1.5 매트릭스로 검증되고, 사용자 답변 4건이 본문에 반영되었으며, 장애 시나리오 3건 + 트레이드오프 4건이 핵심 위험을 모두 짚는다. 체크리스트 critical/major 0건, n/a 4건은 본 토픽 청소 성격상 자연스러운 비활성.",

  "checklist": {
    "source": ".claude/skills/_shared/checklists/discuss-ready.md",
    "items": [
      {
        "section": "scope",
        "item": "TOPIC이 UPPER-KEBAB-CASE로 확정됨",
        "status": "yes",
        "evidence": "docs/topics/CLEANUP-BATCH-A.md line 1"
      },
      {
        "section": "scope",
        "item": "모듈/패키지 경계 명시",
        "status": "yes",
        "evidence": "§1.1~§1.4 헤더 (line 127, 146, 205, 259) + §3 인벤토리 line 331~358"
      },
      {
        "section": "scope",
        "item": "non-goals 1개 이상",
        "status": "yes",
        "evidence": "§0 비범위 7건 line 111~118"
      },
      {
        "section": "scope",
        "item": "범위 밖 이슈 위임",
        "status": "yes",
        "evidence": "TC-13 / ErrorDecoder / prod profile / 동적 backoff / ArchUnit / dedupe 모두 별 토픽 명시 (line 112~118, 444)"
      },
      {
        "section": "design",
        "item": "hexagonal layer 배치 명시",
        "status": "yes",
        "evidence": "§1.1 application / §1.2 domain / §1.3 infrastructure / §1.4 presentation"
      },
      {
        "section": "design",
        "item": "포트 인터페이스 위치",
        "status": "n/a",
        "evidence": "신규 포트 도입 없음 — dead service 제거 / 도메인 POJO 패턴 / yml / advice 매핑만"
      },
      {
        "section": "design",
        "item": "상태 전이 다이어그램",
        "status": "n/a",
        "evidence": "새 도메인 상태 추가 없음 — §6 line 426 TX 경계 영향 0 명시"
      },
      {
        "section": "design",
        "item": "결제 흐름 호환성 검토",
        "status": "yes",
        "evidence": "§1.2 PG-CONFIRM-LISTENER-SPLIT m1 봉인 의도 보존 (line 155~166), §1.4 advice 우선순위 (line 283~285)"
      },
      {
        "section": "acceptance",
        "item": "관찰 가능 성공 조건",
        "status": "yes",
        "evidence": "§2 line 314~325 acceptance 신호 표 — gradle test PASS / grep returns 0 / status 503 + Retry-After 헤더 검증"
      },
      {
        "section": "acceptance",
        "item": "실패 관찰 방법",
        "status": "yes",
        "evidence": "§2 각 항목에 검증 명령 + 통과 기준 명시"
      },
      {
        "section": "verification",
        "item": "테스트 계층 결정",
        "status": "yes",
        "evidence": "§5 line 405~420 단위 RED→GREEN + 회귀 + 수동 스모크. §1.3 검증 옵션 (a/b/c) 은 plan 단계 결정 (minor m1 참조)"
      },
      {
        "section": "verification",
        "item": "벤치마크 지표",
        "status": "n/a",
        "evidence": "코드 청소 — TPS/latency 영향 0, §6 line 426"
      },
      {
        "section": "artifact",
        "item": "결정 사항 섹션 존재",
        "status": "yes",
        "evidence": "§1 line 125~308"
      },
      {
        "section": "domain-risk",
        "item": "멱등성 전략",
        "status": "n/a",
        "evidence": "신규 멱등 키 도입 없음. §7.4 line 446이 기존 INSERT IGNORE 멱등성 trade-off로 보강"
      },
      {
        "section": "domain-risk",
        "item": "장애 시나리오 3개 이상",
        "status": "yes",
        "evidence": "§4 line 372~399 — 4.1 Flyway missing migration / 4.2 client 무한 retry / 4.3 builder 가드 우회"
      },
      {
        "section": "domain-risk",
        "item": "재시도 정책",
        "status": "yes",
        "evidence": "§1.4 Retry-After 5 고정 (line 273~275) + 동적 backoff 비범위 (line 114)"
      },
      {
        "section": "domain-risk",
        "item": "PII/민감정보",
        "status": "n/a",
        "evidence": "새 PII 도입 없음"
      }
    ],
    "total": 17,
    "passed": 11,
    "failed": 0,
    "not_applicable": 6
  },

  "scores": {
    "clarity": 0.90,
    "completeness": 0.88,
    "risk": 0.85,
    "testability": 0.80,
    "fit": 0.92,
    "mean": 0.87
  },

  "findings": [
    {
      "severity": "minor",
      "checklist_item": "테스트 계층 결정 (verification plan)",
      "location": "docs/topics/CLEANUP-BATCH-A.md line 243, 321",
      "problem": "§1.3 (Flyway docker profile seed 차단) 검증 방식이 (a) Testcontainers @ActiveProfiles 통합 테스트 / (b) 수동 스모크 / (c) infra-healthcheck script 중 하나로 확정되지 않고 plan 단계에 위임됨.",
      "evidence": "line 243 '본 결정은 §2 acceptance 의 verification 트랙에서 plan 단계에 검증 비용 vs 가치 재평가 후 확정', line 321 '(옵션) 채택 시'",
      "suggestion": "plan 단계 초입에 (1) docker-compose의 SPRING_PROFILES_ACTIVE=docker 실제 활성 여부 사전 확인(§7.2 line 441), (2) Testcontainers 비용 vs 학습 가치 판단해서 (a) 1건 채택 권고. discuss 라운드 차단 사유 아님 — §5에 fallback (수동 스모크 §5 line 417~420) 명시되어 검증 갭 자체는 없음."
    }
  ],

  "previous_round_ref": null,
  "delta": null,

  "unstuck_suggestion": null
}
```
