# plan-domain-2

**Topic**: CLEANUP-BATCH-A
**Round**: 2
**Persona**: Domain Expert

## Reasoning
Round 1 의 4건 (D1 major + D2/D3/D4 minor) 흡수가 PLAN.md 안에 정확히 박혔다 — CBA-1 산출물에 CONFIRM-FLOW.md §7/§13 정정 본문 + acceptance grep, CBA-9 호출처 표가 실제 grep (create×9 + createWithAvailableAt×1 = 10건, 5 파일) 과 정확 일치, CBA-12 [NET-RETRY] 본문에 "그 외 5xx (502/504) → IllegalStateException → 500 잔존" 명시, CBA-8 acceptance 에 어댑터 회귀 (`PgInboxRepositoryImplTest` 또는 `transitDirectToTerminal` 회귀) 명시. 단 Round 2 도메인 재검토에서 PAYMENT-FLOW.md:402 의 amount 양방향 방어 인덱스 표 라인이 CBA-1 docs 정정 목록·acceptance grep 범위에서 빠져 dangling reference 가 부분적으로 잔존한다 — minor.

## Domain risk checklist

- D1 (major, Round 1) 흡수 점검:
  - CBA-1 산출물 line 68-72 — CONFIRM-FLOW.md §7 (line 254) + §13 (line 430) 정정 전/후 본문 명시. 1단 방어 주체 = `AmountConverter.fromBigDecimalStrict` (`PgInboxRepositoryImpl.insertPending` 경로 + `DuplicateApprovalHandler.amountMismatch` 경로) 로 교체.
  - CBA-1 acceptance line 74 — `grep "PgInboxAmountService" docs/context/CONFIRM-FLOW.md` = 0건 명시.
  - 단, `docs/context/PAYMENT-FLOW.md:402` ("amount 양방향 방어 | `pg-service/.../application/service/PgInboxAmountService.java`") 가 CBA-1 docs 정정 목록 + acceptance grep 범위 양쪽에서 빠짐 — D1 부분 흡수 (minor 신규 finding).
  - 검증: pg-service src/main grep 결과 `PgInboxAmountService` 잔존은 service 본체 (`application/service/PgInboxAmountService.java:33`) + test 본체 (`PgInboxAmountStorageTest.java:33,38` 등) 만 — CBA-1 삭제 후 src 측 dangling 0 보장. docs 측은 CONFIRM-FLOW.md 2건 + PAYMENT-FLOW.md 1건 + TODOS.md 3건 (TC-16 본문 — CBA-12 [PR A] 제거로 자연 해소). PAYMENT-FLOW.md 만 미흡수.

- D2 (minor, Round 1) 흡수 점검:
  - CBA-9 line 291 본문 — "main 호출처 10건 모두 null" 명시.
  - CBA-9 line 297 (Round 2 흡수 D2 인용 블록) — `PgVendorCallService.java:164,178,215,230` / `PgFinalConfirmationGate.java:162,178,194` / `PgDlqService.java:88` / `PgTerminalReemitService.java:52` / `DuplicateApprovalHandler.java:304` = 합 10건 5 파일 명시.
  - 실 grep 결과 (Round 2 재검증) 와 정확 일치. id 인자 제거 시 컴파일 에러로 누락 자동 방어 + plan 인벤토리도 정확.

- D3 (minor, Round 1) 흡수 점검:
  - CBA-12 line 380 본문 — "ErrorDecoder 4 분기 중 '그 외 5xx (500/502/504) → IllegalStateException → GlobalExceptionHandler 500 응답' 경로가 본 토픽으로 변경되지 않아, vendor 게이트웨이 504/502 같은 비-503 5xx 도 500 으로 노출되는 갭 잔존. 후속 토픽에서 (1) ErrorDecoder 단계에 status code 별 분기 예외 타입 도입 + PaymentExceptionHandler 의 503/429 분리 매핑 (2) 비-503 5xx 전용 매핑 결정 (예: 502/504 → 503 또는 별도 응답 코드) 이 필요 [NET-RETRY]" 명시.
  - 실 코드 (`ProductFeignConfig.java:61` 의 `new IllegalStateException(...)` 분기 + `GlobalExceptionHandler.catchRuntimeException` 의 500 응답) 와 정합. 후속 토픽 범위 가시화 충분.

- D4 (minor, Round 1) 흡수 점검:
  - CBA-8 acceptance line 283 — "기존 PgInboxTest 회귀 0 + 신규 케이스 PASS + `PgInboxRepositoryImplTest` (또는 `transitDirectToTerminal` 통합 테스트) 회귀 PASS — builder 전환 후 `PgInbox.of(...)` 7-arg 호출이 status terminal + amount + reasonCode 보존하는지 확인" 명시.
  - line 285 (Round 2 흡수 F1·F5·D4 인용 블록) — silent corruption 우려 + `PgInboxRepositoryImpl.transitDirectToTerminal:155` 의 7-arg 호출 경로 명시 + 어댑터 회귀 케이스 신규 추가 룰 명시. F5 와 동일 영역 처리.

- 결제 상태 전이 보존: 모든 흡수가 builder 전환 / 어댑터 가드 / factory 가드 시그니처 보존 룰 안. PgInbox 상태 머신 (PENDING/IN_PROGRESS/APPROVED/FAILED/QUARANTINED) + 5 전이 메서드 + `isTerminal()` 가드 영향 0. OK
- 멱등성 보장 영향: PgOutbox builder 전환은 객체 생성 시점만, Lua dedup token / claimToInFlight CAS / pg_inbox UNIQUE 모두 영향 0. OK
- PII / 금전 정확성: `AmountConverter.fromBigDecimalStrict` 위치/호출 변화 없음. CBA-1 docs 정정이 1단 방어 주체를 `AmountConverter` 로 명시화해 ownership 명확화. OK
- race window: builder 전환은 TX 경계 (REQUIRES_NEW claimToInFlight / SKIP LOCKED) 영향 0. OK

## 도메인 관점 추가 검토

1. **PAYMENT-FLOW.md:402 의 amount 양방향 방어 인덱스 라인 누락** (minor) — D1 흡수가 `docs/context/CONFIRM-FLOW.md` §7 (line 254) + §13 (line 430) 정정에만 한정됐다. `docs/context/PAYMENT-FLOW.md:402` 의 "amount 양방향 방어 | `pg-service/.../application/service/PgInboxAmountService.java`" 도 dead service 파일 경로를 직접 참조한다 — CBA-1 삭제 후 dangling. CBA-1 산출물 docs 정정 목록 (line 68-72) 과 acceptance grep (line 74) 양쪽에서 PAYMENT-FLOW.md 가 빠져 implementer 가 잔존을 자동으로 못 잡을 수 있다. 결제 도메인 핵심 가드 (amount 양방향 방어) 의 docs 경로 추적성 — sev minor (인덱스 표 1줄로 mermaid 다이어그램 / AMOUNT_MISMATCH 가드 행과 달리 ownership 서술이 아닌 카탈로그). CBA-1 산출물에 "PAYMENT-FLOW.md:402 의 amount 양방향 방어 행 — `PgInboxAmountService.java` 참조를 `pg-service/.../infrastructure/converter/AmountConverter.java` + `pg-service/.../infrastructure/repository/PgInboxRepositoryImpl.java` (insertPending 경로) 로 교체" 추가 + acceptance grep 범위에 `docs/context/PAYMENT-FLOW.md` 도 포함 권고. (location: `docs/context/PAYMENT-FLOW.md:402`; CBA-1 산출물 누락 `docs/CLEANUP-BATCH-A-PLAN.md:68-74`)

## Findings

- (minor) CBA-1 docs 정정 부분 흡수 — `docs/context/PAYMENT-FLOW.md:402` 의 amount 양방향 방어 인덱스 라인이 산출물 + acceptance grep 양쪽에서 누락. dangling reference 자동 차단 미흡.

새 critical / major 없음. Round 1 4건 모두 정확히 흡수. 신규 minor 1건은 산출물 1줄 + acceptance grep 1줄 추가로 즉시 해결 가능 — 도메인 핵심 가드 docs 경로 손상 0 위협.

## JSON

```json
{
  "stage": "plan",
  "persona": "domain-expert",
  "round": 2,
  "task_id": null,

  "decision": "pass",
  "reason_summary": "Round 1 4건 (D1 major + D2/D3/D4 minor) 모두 PLAN.md 본문에 정확히 흡수. Round 2 신규 발견은 minor 1건 (PAYMENT-FLOW.md:402 amount 양방향 방어 인덱스 라인이 CBA-1 docs 정정 목록 누락) — 판정 규칙상 minor 만이면 pass.",

  "checklist": {
    "source": "_shared/checklists/plan-ready.md",
    "items": [
      {
        "section": "traceability",
        "item": "PLAN.md가 docs/topics/<TOPIC>.md의 결정 사항을 참조함",
        "status": "yes",
        "evidence": "docs/CLEANUP-BATCH-A-PLAN.md:3 — 토픽 명시 + §1.1~§1.4 sub-section 매핑 + 추적 테이블 line 420-434 모든 결정 매핑"
      },
      {
        "section": "traceability",
        "item": "모든 태스크가 설계 결정 중 하나 이상에 매핑됨 (orphan 태스크 없음)",
        "status": "yes",
        "evidence": "12 태스크 모두 §1.x 또는 cross 결정 매핑 (메타 line 22-35) — 미매핑 0건"
      },
      {
        "section": "task quality",
        "item": "객관적 완료 기준",
        "status": "yes",
        "evidence": "각 태스크 acceptance — gradle test PASS / grep 0건 / 신규 케이스 PASS / 파일 위치 확인 등 명시. CBA-1 line 74 grep 0건 검증 등"
      },
      {
        "section": "task quality",
        "item": "태스크 크기 ≤ 2시간",
        "status": "yes",
        "evidence": "12 태스크 모두 단일 sub-section 안 1 commit 단위 분해 가능. CBA-2+CBA-4 / CBA-3+CBA-5 commit 묶음 정책 (F1·F2 흡수) 명시"
      },
      {
        "section": "TDD specification",
        "item": "tdd=true 태스크는 테스트 클래스+메서드 스펙 명시",
        "status": "yes",
        "evidence": "CBA-6 (line 154-159) / CBA-7 (line 198-202) / CBA-8 (line 260-270) / CBA-9 (line 299-305) 모두 테스트 클래스 + 메서드 표 명시"
      },
      {
        "section": "dependency ordering",
        "item": "layer 의존 순서 준수",
        "status": "yes",
        "evidence": "CBA-2/3 (resource 이동) → CBA-4/5 (yml override) 순서 + commit 묶음 정책. CBA-8/9 (domain) 가 어댑터/호출처 변경과 단일 커밋 묶음"
      },
      {
        "section": "architecture fit",
        "item": "ARCHITECTURE.md layer 룰 정합",
        "status": "yes",
        "evidence": "CBA-1 (application service 삭제) / CBA-6 (presentation advice) / CBA-7 (infrastructure 패키지 배치, F3 흡수) / CBA-8/9 (domain POJO) — 신규 cross-layer 의존 0"
      },
      {
        "section": "artifact",
        "item": "docs/<TOPIC>-PLAN.md 존재",
        "status": "yes",
        "evidence": "docs/CLEANUP-BATCH-A-PLAN.md 존재"
      },
      {
        "section": "domain risk",
        "item": "discuss 식별 domain risk 가 각각 대응 태스크를 가짐",
        "status": "yes",
        "evidence": "D1→CBA-8 (가드 보존 케이스), D2→CBA-8 (JavaDoc 명시), D3→CBA-12 (TODOS [NET-RETRY]), D4→CBA-7 + CBA-10. 추적 표 line 392-404"
      },
      {
        "section": "domain risk",
        "item": "재시도 안전성 검증 태스크 존재",
        "status": "yes",
        "evidence": "CBA-6 — 503 + Retry-After:5 표준화. RetryPolicy 자체는 본 토픽 비범위 — D3 후속 가시화로 CBA-12 [NET-RETRY] 등재"
      },
      {
        "section": "task quality",
        "item": "관련 소스 파일/패턴 언급",
        "status": "yes",
        "evidence": "§3 인벤토리 + 태스크별 산출물 절대 경로 명시. CBA-9 line 297 호출처 파일:line 정확 인덱싱 (D2 흡수)"
      },
      {
        "section": "domain risk (추가 검토)",
        "item": "영구 문서 (CONFIRM-FLOW.md / PAYMENT-FLOW.md) 의 dead service 참조 정정 동반",
        "status": "no",
        "evidence": "CBA-1 산출물 line 68-72 가 CONFIRM-FLOW.md §7 + §13 정정 명시했으나, PAYMENT-FLOW.md:402 ('amount 양방향 방어' 인덱스 표 행) 의 PgInboxAmountService.java 경로 참조가 누락. acceptance grep 범위 (line 74) 도 CONFIRM-FLOW.md 만 — minor (인덱스 1줄, ownership 서술 아님)"
      }
    ],
    "total": 12,
    "passed": 11,
    "failed": 1,
    "not_applicable": 0
  },

  "scores": {
    "traceability": 0.88,
    "decomposition": 0.92,
    "ordering": 0.90,
    "specificity": 0.88,
    "risk_coverage": 0.85,
    "mean": 0.886
  },

  "findings": [
    {
      "severity": "minor",
      "checklist_item": "영구 문서의 dead service 참조 정정 동반",
      "location": "docs/context/PAYMENT-FLOW.md:402 (참조 위치) + docs/CLEANUP-BATCH-A-PLAN.md:68-74 (CBA-1 산출물 + acceptance 누락)",
      "problem": "Round 1 D1 (major) 흡수가 CONFIRM-FLOW.md §7 (line 254) + §13 (line 430) 정정에만 한정됐다. docs/context/PAYMENT-FLOW.md:402 의 'amount 양방향 방어 | pg-service/.../application/service/PgInboxAmountService.java' 도 dead service 파일 경로를 직접 참조 — CBA-1 삭제 후 dangling reference 잔존. CBA-1 산출물 docs 정정 목록 (line 68-72) + acceptance grep (line 74) 양쪽에서 PAYMENT-FLOW.md 누락으로 implementer 가 자동으로 못 잡을 수 있음.",
      "evidence": "grep 결과 docs/context/PAYMENT-FLOW.md:402 — '| amount 양방향 방어 | `pg-service/.../application/service/PgInboxAmountService.java` |'. CBA-1 산출물 (CLEANUP-BATCH-A-PLAN.md:68-72) 는 CONFIRM-FLOW.md 만 명시. acceptance line 74 grep 범위도 CONFIRM-FLOW.md 만",
      "suggestion": "CBA-1 산출물 docs 정정 목록에 'PAYMENT-FLOW.md:402 의 amount 양방향 방어 행 — PgInboxAmountService.java 참조를 pg-service/.../infrastructure/converter/AmountConverter.java + pg-service/.../infrastructure/repository/PgInboxRepositoryImpl.java (insertPending 경로) 로 교체' 항목 추가. acceptance grep 범위에 docs/context/PAYMENT-FLOW.md 도 포함: 'grep -r \"PgInboxAmountService\" docs/context/' 결과 0건 (전체 context 디렉토리 grep) 으로 확장 권고. severity minor — 산출물 1줄 + acceptance grep 1줄 추가로 즉시 해결"
    }
  ],

  "previous_round_ref": "plan-domain-1.md",
  "delta": {
    "newly_passed": [
      "CONFIRM-FLOW.md §7/§13 PgInboxAmountService 참조 정정 (D1 흡수)",
      "CBA-9 호출처 카운트 10건 (5 파일) 정확 인벤토리 (D2 흡수)",
      "CBA-12 [NET-RETRY] 본문에 비-503 5xx → 500 잔존 갭 가시화 (D3 흡수)",
      "CBA-8 acceptance 어댑터 회귀 (`PgInboxRepositoryImplTest` / `transitDirectToTerminal`) 명시 (D4·F5 흡수)"
    ],
    "newly_failed": [],
    "still_failing": []
  },

  "unstuck_suggestion": null
}
```
