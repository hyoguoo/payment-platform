# discuss-critic-1

**Topic**: CLEANUP-BATCH-B
**Round**: 1
**Persona**: Critic

## Reasoning
설계 문서가 Gate checklist 의 scope/design-decisions/acceptance/verification/artifact/domain-risk 전 항목을 충족한다. Round 0 사용자 확정 결정(측정 정책 유지·spotbugs 코드 정정·4서비스 공통화·통합테스트 합산)을 전제로 §1~§7 이 layer 배치, 멱등성 위험(GET 멱등 + Idempotency-Key), 장애 3종, baseline 산정 선후 절차까지 관찰 가능한 형태로 쌓았고 코드 사실관계도 검증상 일치한다. critical/major 없음 → pass. 단 §3-3 의 "integrationTest 는 payment-service 에만 존재" 서술은 실제(payment/product/pg 3서비스 보유)와 어긋나는 사실 오류 — 조건부 wiring 설계 자체는 무관하게 정상이라 minor.

## Checklist judgement

### scope
- TOPIC UPPER-KEBAB-CASE 확정 — **yes** (L1 `# CLEANUP-BATCH-B`)
- 모듈/패키지 경계 명시 — **yes** (§3-2 hexagonal layer 배치, §2 in-scope 파일 경로)
- non-goals 1개 이상 — **yes** (§2 non-goals 5개)
- 범위 밖 이슈 위임/포함 — **yes** (§2: 측정 대상 확대·PR 가시성·CircuitBreaker 를 별도 토픽/Phase 4 로 위임)

### design decisions
- hexagonal layer 배치 명시 — **yes** (§3-2 ErrorDecoder=infrastructure 출력어댑터 / exception 패키지 / PaymentExceptionHandler=presentation, 의존 방향 서술)
- 포트 인터페이스 위치 결정 — **yes** (§3-2 "포트 인터페이스 신설·이동 없음" 명시, 기존 출력 포트 뒤 위치 유지)
- 새 상태 추가 시 전이 다이어그램 — **n/a** (§3 "새 결제 상태 추가 없음. 상태 전이 다이어그램 불필요")
- 전체 결제 흐름 호환성 검토 — **yes** (§7: Kafka 비동기 confirm 과 무관, GET 조회 경로 한정, checkout Idempotency-Key 멱등 보장)

### acceptance criteria
- 성공 조건 관찰 가능 — **yes** (§5: build GREEN/spotbugsTest exit≠0/단위테스트 red/jacocoTestCoverageVerification 실행·fail 음성검증)
- 실패 관찰 방법 명시 — **yes** (§5 각 항목 "실패 관찰:" 명시, §6 검증 계획)

### verification plan
- 테스트 계층 결정 — **yes** (§6 단위 위주, ErrorDecoder Mockito, 게이트 self-검증 절차)
- 벤치마크 지표 — **n/a** (§6 "k6/벤치마크 불필요 — TPS/latency 영향 없음" 명시적 비대상 판단)

### artifact
- "결정 사항" 섹션 존재 — **yes** (§3 설계 결정 + §4 결정 ID 목록 D-COV1~3/D-SB1/D-NR1a-c/D-SB1-EI)

### domain risk (Domain Expert 전용이나 참조 판정)
- 멱등성 전략 결정 — **yes** (§7: GET 멱등 + checkout Idempotency-Key redis-dedupe store, 503 은 레코드 생성 전)
- 장애 시나리오 3개+ — **yes** (§7: 502 롤링교체 / 504 과부하 / 500 영구오류)
- 재시도 정책 — **yes** (Retry-After:5 고정, 자동재시도/백오프/CircuitBreaker 는 Phase 4 비대상으로 명시)
- PII/민감정보 — **yes** (§7 "새 로깅·저장 경로 도입 없음, body 로깅 기존과 동일")

## Findings

### F1 (minor)
- **checklist_item**: design decisions — hexagonal/공통화 설계의 사실 정확성
- **location**: `docs/topics/CLEANUP-BATCH-B.md` §3-3 D-COV3 (L181)
- **problem**: "`integrationTest` 태스크는 현재 payment-service 에만 존재" 서술이 사실과 다르다. 실제로는 payment/product/pg 3개 서비스 build.gradle 에 `task integrationTest` 가 존재한다(user/gateway/eureka 만 없음).
- **evidence**: `grep -rln "task integrationTest" --include=build.gradle .` → product-service/build.gradle, payment-service/build.gradle, pg-service/build.gradle 3건.
- **suggestion**: §3-3 서술을 "payment/product/pg 3서비스에 존재, user/gateway/eureka 에는 없음"으로 정정. 조건부 wiring(`tasks.findByName('integrationTest')` 가드) 설계 자체는 분포와 무관하게 정상이므로 메커니즘 변경 불필요 — 근거 문장만 정정.

## JSON
```json
{
  "stage": "discuss",
  "persona": "critic",
  "round": 1,
  "task_id": null,

  "decision": "pass",
  "reason_summary": "Gate checklist 전 항목 yes/n-a. Round 0 확정 결정 전제 위에 layer 배치·멱등성 위험·장애 3종·baseline 선후 절차가 관찰 가능한 형태로 완결됐고 코드 사실관계 일치. critical/major 없음, integrationTest 분포 서술 사실오류 1건만 minor.",

  "checklist": {
    "source": "_shared/checklists/discuss-ready.md",
    "items": [
      { "section": "scope", "item": "TOPIC UPPER-KEBAB-CASE 확정", "status": "yes", "evidence": "CLEANUP-BATCH-B.md L1" },
      { "section": "scope", "item": "모듈/패키지 경계 명시", "status": "yes", "evidence": "§3-2 layer 배치 + §2 in-scope 경로" },
      { "section": "scope", "item": "non-goals 1개 이상", "status": "yes", "evidence": "§2 non-goals 5개" },
      { "section": "scope", "item": "범위 밖 이슈 위임/포함", "status": "yes", "evidence": "§2 측정확대·PR가시성·CircuitBreaker 를 별도 토픽/Phase4 위임" },
      { "section": "design decisions", "item": "hexagonal layer 배치 명시", "status": "yes", "evidence": "§3-2 infrastructure/exception/presentation 배치 + 의존방향" },
      { "section": "design decisions", "item": "포트 인터페이스 위치 결정", "status": "yes", "evidence": "§3-2 포트 신설·이동 없음 명시" },
      { "section": "design decisions", "item": "새 상태 전이 다이어그램", "status": "n/a", "evidence": "§3 새 결제 상태 추가 없음" },
      { "section": "design decisions", "item": "전체 결제 흐름 호환성 검토", "status": "yes", "evidence": "§7 Kafka confirm 무관 + GET 조회 한정 + Idempotency-Key" },
      { "section": "acceptance criteria", "item": "성공 조건 관찰 가능", "status": "yes", "evidence": "§5 build GREEN/exit code/red test/게이트 실행검증" },
      { "section": "acceptance criteria", "item": "실패 관찰 방법 명시", "status": "yes", "evidence": "§5 각 항목 실패 관찰 명시" },
      { "section": "verification plan", "item": "테스트 계층 결정", "status": "yes", "evidence": "§6 단위 위주 + ErrorDecoder Mockito + 게이트 self-검증" },
      { "section": "verification plan", "item": "벤치마크 지표", "status": "n/a", "evidence": "§6 k6 불필요 명시적 비대상" },
      { "section": "artifact", "item": "결정 사항 섹션 존재", "status": "yes", "evidence": "§3 + §4 결정 ID 목록" },
      { "section": "domain risk", "item": "멱등성 전략 결정", "status": "yes", "evidence": "§7 GET 멱등 + checkout Idempotency-Key" },
      { "section": "domain risk", "item": "장애 시나리오 3개+", "status": "yes", "evidence": "§7 502/504/500 3종" },
      { "section": "domain risk", "item": "재시도 정책", "status": "yes", "evidence": "§7 Retry-After:5, 자동재시도 Phase4 비대상" },
      { "section": "domain risk", "item": "PII/민감정보", "status": "yes", "evidence": "§7 신규 로깅·저장 경로 없음" }
    ],
    "total": 17,
    "passed": 15,
    "failed": 0,
    "not_applicable": 2
  },

  "scores": {
    "clarity": 0.90,
    "completeness": 0.88,
    "risk": 0.85,
    "testability": 0.90,
    "fit": 0.92,
    "mean": 0.89
  },

  "findings": [
    {
      "severity": "minor",
      "checklist_item": "hexagonal/공통화 설계의 사실 정확성",
      "location": "docs/topics/CLEANUP-BATCH-B.md#3-3 (L181)",
      "problem": "'integrationTest 태스크는 현재 payment-service 에만 존재' 서술이 사실과 다름. 실제 payment/product/pg 3서비스에 존재.",
      "evidence": "grep -rln 'task integrationTest' --include=build.gradle . → product/payment/pg 3건",
      "suggestion": "서술을 '3서비스 존재, user/gateway/eureka 없음'으로 정정. 조건부 wiring 설계는 분포 무관하게 정상이라 메커니즘 변경 불요."
    }
  ],

  "previous_round_ref": null,
  "delta": null,

  "unstuck_suggestion": null
}
```
