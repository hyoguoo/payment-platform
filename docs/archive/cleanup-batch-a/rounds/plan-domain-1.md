# plan-domain-1

**Topic**: CLEANUP-BATCH-A
**Round**: 1
**Persona**: Domain Expert

## Reasoning
플랜은 청소 위주라 자금·멱등성·race 가 도메인에서 직접 다뤄지진 않지만, §1.1 `PgInboxAmountService` 삭제가 `CONFIRM-FLOW.md` §7 / §13 의 "1단 amount 방어" 명시와 충돌해 docs 부정확 상태를 남기고, §1.2 호출처 인벤토리는 main `PgOutbox.create` 카운트가 실제(10)와 plan 표(8)가 다르다. §1.4 503 일괄 매핑은 retry 시그널 측면에서 안전하지만 멱등성 면에선 ErrorDecoder 인입 4 분기(404 / 429 / 503 / 그 외 5xx) 중 "그 외 5xx → IllegalStateException → 500"이 본 토픽에 의해 변경되지 않음을 명시 안 함 — 후속 정합 메모만 보강 권고.

## Domain risk checklist

- discuss 식별 domain risk 대응 매핑:
  - D1 (createDirectTerminal 어댑터 가드 / 도메인 가드 이중화) → CBA-8 의 `createDirectTerminal_nonTerminalStatus_throwsIllegalArgument` 케이스로 가드 보존 검증. 어댑터 가드(`PgInboxRepositoryImpl:150`) 손대지 않는 룰 §1.2 / CBA-8 모두 명시. OK
  - D2 (PgInbox.create 4 오버로드 main 호출처 0) → §1.2 line 229 + CBA-8 산출물 JavaDoc 명시 권고. OK
  - D3 (429 시그널 손실 후속) → CBA-12 TODOS.md [NET-RETRY] 등재. OK
  - D4 (named volume 재사용 missing-migration) → CBA-7 Testcontainers + CBA-10 STACK.md 3-step. OK
- 결제 상태 전이 보존: PgInbox 상태 머신(PENDING/IN_PROGRESS/APPROVED/FAILED/QUARANTINED) + 5 전이 메서드(markInProgress / markApproved / markFailed / markQuarantined + isTerminal 가드)가 builder 전환과 무관. 시그니처/가드 본문 변경 없음 — 회귀 위험 0. OK
- 멱등성 보장 영향: §1.2 는 도메인 POJO 내부 구조만 변경 → 멱등성 메커니즘(Lua dedup token, claimToInFlight CAS, pg_inbox UNIQUE) 모두 영향 없음. §1.4 핸들러 추가도 멱등성 영향 없음. §1.3 Flyway 분리는 schema_history 단일 진실 원천 — Flyway 자체 베이스라인 정책 변경 없음. OK
- PII / 금전 정확성: `AmountConverter.fromBigDecimalStrict` (scale·음수 검증) 의 위치/호출 변화 없음, `PgInbox.amount` Long 필드 보존, `PgOutbox.payload` 직렬화 변경 없음. OK
- PG 실패 모드: §1.4 가 503 + Retry-After:5 일괄 매핑. Toss/NicePay 회복성과는 직접 무관 (Feign 분기는 본 토픽 비범위로 명시). OK
- race window: §1.2 builder 전환은 객체 생성 시점만 — TX 경계(REQUIRES_NEW claimToInFlight / SKIP LOCKED) 영향 0. OK

## 도메인 관점 추가 검토

1. **`CONFIRM-FLOW.md` 정정 누락** (major) — `docs/context/CONFIRM-FLOW.md:254` 와 `:430` 가 "pg 측 방어 (1단)" 로 `PgInboxAmountService` 를 명시 참조한다 (`PgInboxAmountService / AmountConverter.fromBigDecimalStrict — scale·음수 검증` / `pg 발행 시 non-null 강제 + payment 수신 시 대조 | PgInboxAmountService (pg) + isAmountMismatch (payment)`). CBA-1 이 본 service 본체+테스트를 삭제하면 영구 문서의 1단 방어 주체가 dangling reference 가 된다. 본 토픽 CBA-10/11/12 영구 문서 갱신 목록에 CONFIRM-FLOW.md 정정이 빠져 있다. PG-CONFIRM-LISTENER-SPLIT 봉인 시점에 1단 방어가 어디로 이전됐는지 (실제 amount 검증 = `PgInboxRepositoryImpl.insertPending` 의 amount 컬럼 INSERT + `DuplicateApprovalHandler` 의 `amountMismatch` 경로) 신규 주체를 docs 에 박아 두지 않으면 amount 양방향 방어 ownership 이 흐려진다 — 결제 도메인 핵심 가드의 docs trace 손상. CBA-1 산출물에 `docs/context/CONFIRM-FLOW.md §7 / §13 의 PgInboxAmountService 참조 정정` 추가 필요. (location: `docs/context/CONFIRM-FLOW.md:254,430`)

2. **§1.2 `PgOutbox` main 호출처 카운트 불일치** (minor) — plan 본문 line 245 "호출처 7건 (...) 모두 null 박힘" 및 line 253-254 의 호출처 변경 요약 표 ("5 파일 (호출 7건)" + "1 파일 (호출 1건)" = 8) 와 §3 인벤토리 line 405 "main 8 + test 1" 이 일치하나, 실제 grep 결과는 main 호출 **10건** (PgFinalConfirmationGate ×3, PgVendorCallService ×4 [create ×3 + createWithAvailableAt ×1], PgDlqService ×1, PgTerminalReemitService ×1, DuplicateApprovalHandler ×1). 시그니처에서 id 인자 제거 시 누락된 2건 (PgFinalConfirmationGate 의 한 호출 + PgVendorCallService 의 `insertDlqOutbox:230` 호출) 이 컴파일 에러로 잡히겠지만, plan 인벤토리가 실제 분포와 맞지 않으면 implementer 가 "8건만 수정하면 됨" 으로 오인할 수 있다. CBA-9 산출물에 "main 호출처 9 (create) + 1 (createWithAvailableAt) = 10건, 5 파일" 로 정정 권고. (location: `docs/CLEANUP-BATCH-A-PLAN.md:286,300-315`; 실제 호출처는 `PgVendorCallService.java:164,178,215,230` / `PgFinalConfirmationGate.java:162,178,194` / `PgDlqService.java:88` / `PgTerminalReemitService.java:52` / `DuplicateApprovalHandler.java:304`)

3. **§1.4 ErrorDecoder "그 외 5xx → IllegalStateException → 500" 경로 잔존** (minor) — INTEGRATIONS.md §Cross-service HTTP 의 ErrorDecoder 분기가 4종 (404 / 429 / 503 / 그 외 5xx → IllegalStateException). CBA-6 가 추가하는 503 매핑은 `*ServiceRetryableException` (429 + 503 통합) 만 잡고, "그 외 5xx"(500, 502, 504) 가 `IllegalStateException` 으로 변환돼 `GlobalExceptionHandler.catchRuntimeException` 의 500 으로 떨어진다 — 본 토픽 비범위로 의도된 동작이지만, 외부에서 보면 "503 정확 매핑 토픽" 이 끝났을 때도 502/504 같은 vendor 게이트웨이 timeout 이 500 으로 노출되는 갭이 남는다. D3 후속(CBA-12 `[NET-RETRY]`) 본문에 "ErrorDecoder 분기 5xx 비-503 → IllegalStateException → 500 잔존" 도 함께 등재해 두면 nominal trade-off 가 시야에서 사라지지 않는다. CBA-12 산출물 본문 보강 권고. (location: `docs/CLEANUP-BATCH-A-PLAN.md:373`)

4. **§1.2 어댑터 회귀 테스트 명시 권고 (architect 인라인과 동조)** (minor) — `PgInboxRepositoryImpl.transitDirectToTerminal:155` 가 `PgInbox.of(orderId, terminalStatus, amount, storedStatusResult, reasonCode, now, now)` 7-arg 직접 호출 경로다. CBA-8 acceptance 가 `:pg-service:test` 전체 PASS 로 보호되지만, builder 전환 후 lombok `@AllArgsConstructor(PRIVATE)` 가 잡는 인자 순서 (`id, orderId, status, amount, storedStatusResult, reasonCode, createdAt, updatedAt, paymentKey, vendorType` 10-arg) 와 `of` 7-arg 의 매핑이 어긋날 경우 `PgInboxEntity#toDomain()` (`ofWithId`) 의 reflection / 직렬화 경로에 영향 가능. 도메인 관점 핵심 우려는 **terminal 상태가 비 terminal 로 잘못 저장되거나 amount 가 null 로 들어가는 silent corruption** — 영향 시 보상 경로가 헷갈리는 inbox row 를 만난다. `PgInboxRepositoryImplTest` 또는 `transitDirectToTerminal` 통합 테스트로 7-arg `of` 호출 결과 (status terminal + amount non-null + reasonCode 보존) 가 builder 전환 후에도 동일하게 저장되는지 회귀 cover 가 acceptance 에 명시되면 안전. CBA-8 acceptance 보강 권고. (location: `docs/CLEANUP-BATCH-A-PLAN.md:278`)

## Findings

- (major) CBA-1 영구 문서 갱신 누락 — `docs/context/CONFIRM-FLOW.md §7 / §13` 가 `PgInboxAmountService` 를 1단 amount 방어로 참조. dead service 삭제 시 docs 정정 동반 필요.
- (minor) CBA-9 main 호출처 카운트 불일치 — 실제 10건(5 파일), plan 표 8건. implementer 인지용 정정.
- (minor) CBA-12 D3 후속 본문에 "ErrorDecoder 그 외 5xx → 500" 잔존 갭 함께 등재 권고.
- (minor) CBA-8 acceptance — 어댑터 7-arg `of` 회귀 테스트 명시 권고 (architect 인라인 주석 line 280 과 동조).

## JSON

```json
{
  "stage": "plan",
  "persona": "domain-expert",
  "round": 1,
  "task_id": null,

  "decision": "revise",
  "reason_summary": "도메인 가드 보존 / 멱등성 / race 영향은 0 이지만, CBA-1 dead service 삭제가 CONFIRM-FLOW.md 의 amount 1단 방어 참조와 충돌해 docs trace 손상이 남는다. 다른 발견은 minor 수준의 인벤토리 정정 / 후속 trade-off 가시화.",

  "checklist": {
    "source": "_shared/checklists/plan-ready.md",
    "items": [
      {
        "section": "traceability",
        "item": "PLAN.md가 docs/topics/<TOPIC>.md의 결정 사항을 참조함",
        "status": "yes",
        "evidence": "docs/CLEANUP-BATCH-A-PLAN.md:3 — 토픽 명시 + §1.1~§1.4 sub-section 매핑 표 + 추적 테이블 line 397-413 모든 결정 매핑 0 미매핑"
      },
      {
        "section": "traceability",
        "item": "모든 태스크가 설계 결정 중 하나 이상에 매핑됨 (orphan 태스크 없음)",
        "status": "yes",
        "evidence": "12 태스크 모두 §1.x 또는 cross 결정 매핑 (메타 line 22-35)"
      },
      {
        "section": "task quality",
        "item": "객관적 완료 기준",
        "status": "yes",
        "evidence": "각 태스크 acceptance — gradle test PASS / grep 0건 / 신규 케이스 PASS / 파일 위치 확인 등 명시"
      },
      {
        "section": "task quality",
        "item": "태스크 크기 ≤ 2시간",
        "status": "yes",
        "evidence": "12 태스크 모두 단일 sub-section 안 1 commit 단위로 분해 가능 (파일 삭제 / yml 수정 / 단일 핸들러 추가 / 단일 도메인 클래스 builder 전환)"
      },
      {
        "section": "TDD specification",
        "item": "tdd=true 태스크는 테스트 클래스+메서드 스펙 명시",
        "status": "yes",
        "evidence": "CBA-6 (line 149-153) / CBA-7 (line 193-194) / CBA-8 (line 256-264) / CBA-9 (line 294-297) 모두 테스트 클래스 + 메서드 표 명시"
      },
      {
        "section": "dependency ordering",
        "item": "layer 의존 순서 준수",
        "status": "yes",
        "evidence": "CBA-2/3 (resource 이동) → CBA-4/5 (yml override) 순서. CBA-8/9 (domain) 가 어댑터 / 호출처 변경과 묶임 — 단일 커밋 단위 atomic"
      },
      {
        "section": "architecture fit",
        "item": "ARCHITECTURE.md layer 룰 정합",
        "status": "yes",
        "evidence": "CBA-1 (application service 삭제) / CBA-6 (presentation advice) / CBA-8/9 (domain POJO) — 모두 hexagonal layer 안 자기 위치 변경. 신규 cross-layer 의존 0"
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
        "evidence": "D1→CBA-8 (가드 보존 케이스), D2→CBA-8 (JavaDoc 명시), D3→CBA-12 (TODOS [NET-RETRY]), D4→CBA-7 + CBA-10. 추적 표 line 383-388"
      },
      {
        "section": "domain risk",
        "item": "재시도 안전성 검증 태스크 존재",
        "status": "yes",
        "evidence": "CBA-6 — 503 + Retry-After:5 표준화. RetryPolicy 자체는 본 토픽 비범위 (pg-service self-loop / payment-service outbox 회복) 변경 0"
      },
      {
        "section": "task quality",
        "item": "관련 소스 파일 / 패턴 언급",
        "status": "yes",
        "evidence": "§3 인벤토리 (line 400-427) — 변경 파일 모두 절대 경로 명시"
      },
      {
        "section": "domain risk (추가 검토)",
        "item": "영구 문서 (CONFIRM-FLOW.md) 의 dead service 참조 정정 동반",
        "status": "no",
        "evidence": "docs/context/CONFIRM-FLOW.md:254,430 가 PgInboxAmountService 를 amount 1단 방어 주체로 참조. CBA-1 삭제 + CBA-10/11/12 영구 문서 갱신 목록에 CONFIRM-FLOW.md 정정 없음"
      }
    ],
    "total": 12,
    "passed": 11,
    "failed": 1,
    "not_applicable": 0
  },

  "scores": {
    "traceability": 0.85,
    "decomposition": 0.90,
    "ordering": 0.88,
    "specificity": 0.80,
    "risk_coverage": 0.78,
    "mean": 0.842
  },

  "findings": [
    {
      "severity": "major",
      "checklist_item": "영구 문서의 dead service 참조 정정 동반",
      "location": "docs/context/CONFIRM-FLOW.md:254,430 (참조 위치) + docs/CLEANUP-BATCH-A-PLAN.md CBA-1 산출물 / CBA-10~12 영구 문서 갱신 목록",
      "problem": "CBA-1 이 PgInboxAmountService 본체+테스트를 삭제하지만 CONFIRM-FLOW.md §7 (line 254) 와 §13 (line 430) 가 본 서비스를 'pg 측 amount 1단 방어' 주체로 명시 참조. plan 의 영구 문서 갱신 목록 (CBA-10 STACK.md / CBA-11 CONVENTIONS.md / CBA-12 TODOS.md) 에 CONFIRM-FLOW.md 정정이 빠져 있어 삭제 후 dangling reference 가 남는다. 결제 도메인 핵심 가드 (amount 양방향 방어) 의 docs trace 손상은 사고 시 재구성 난이도와 직결.",
      "evidence": "CONFIRM-FLOW.md:254 — 'pg 측 방어 (1단): PgInboxAmountService / AmountConverter.fromBigDecimalStrict — scale·음수 검증'. CONFIRM-FLOW.md:430 — 'AMOUNT_MISMATCH | 양방향 방어 — pg 발행 시 non-null 강제 + payment 수신 시 대조 | PgInboxAmountService (pg) + isAmountMismatch (payment)'. CLEANUP-BATCH-A-PLAN.md §3 line 429-435 의 context 문서 갱신 목록에 CONFIRM-FLOW.md 미포함",
      "suggestion": "CBA-1 산출물에 'docs/context/CONFIRM-FLOW.md §7 / §13 의 PgInboxAmountService 참조 정정 (실제 1단 방어 주체로 교체)' 항목 추가. 정정 내용: PG-CONFIRM-LISTENER-SPLIT 봉인 시점의 실제 amount 방어 = AmountConverter.fromBigDecimalStrict (PgInboxRepositoryImpl.insertPending 측 amount 컬럼 INSERT 단계 + DuplicateApprovalHandler 의 amountMismatch 경로). 또는 별 신규 태스크 CBA-13 으로 분리 (CBA-1 dependency)"
    },
    {
      "severity": "minor",
      "checklist_item": "§1.2 호출처 인벤토리 카운트 정합",
      "location": "docs/CLEANUP-BATCH-A-PLAN.md:245,253-254,286,300-315,405",
      "problem": "plan 본문이 PgOutbox.create main 호출처를 '7건 (5 파일)' (line 245 + line 253) + createWithAvailableAt 1건 = 합 8건 으로 표기하지만 실제 grep 결과 main 호출은 10건 (PgFinalConfirmationGate ×3 line 162/178/194, PgVendorCallService ×4 line 164/178/215/230 [create ×3 + createWithAvailableAt ×1], PgDlqService ×1 line 88, PgTerminalReemitService ×1 line 52, DuplicateApprovalHandler ×1 line 304). 시그니처 변경 시 컴파일 에러로 잡히겠지만 implementer 가 '8건만 수정' 으로 인지하면 누락 가능.",
      "evidence": "grep 결과: pg-service/src/main/java/.../service/PgFinalConfirmationGate.java 3건, PgVendorCallService.java 4건, PgDlqService.java 1건, PgTerminalReemitService.java 1건, DuplicateApprovalHandler.java 1건",
      "suggestion": "CBA-9 산출물 main 호출처 표를 'create ×9 (5 파일) + createWithAvailableAt ×1 (1 파일) = 합 10건' 으로 정정. plan §3 인벤토리 line 405 의 'main 8 + test 1' 도 'main 10 + test 1' 로 정정"
    },
    {
      "severity": "minor",
      "checklist_item": "D3 trade-off 후속 가시화 범위",
      "location": "docs/CLEANUP-BATCH-A-PLAN.md:373 (CBA-12 신규 등재 본문)",
      "problem": "CBA-12 의 [NET-RETRY] 신규 항목은 'ErrorDecoder 429/503 분기 보존' 만 등재한다. 그러나 INTEGRATIONS.md 의 ErrorDecoder 4 분기 중 '그 외 5xx → IllegalStateException → 500' 경로가 본 토픽으로 변경되지 않아, 503 정확 매핑이 끝나도 vendor 게이트웨이 502/504/500 같은 비-503 5xx 는 여전히 500 으로 노출된다. 후속 토픽이 503/429 분리만 다루면 비-503 5xx 갭이 그대로 가시화 0 으로 남는다.",
      "evidence": "payment-service/src/main/java/.../feign/ProductFeignConfig.java line 57 (503 매핑) + 그 외 5xx 분기는 IllegalStateException 으로 wrap. GlobalExceptionHandler.catchRuntimeException 이 500 응답 (line 26-36)",
      "suggestion": "CBA-12 신규 [NET-RETRY] 항목 본문에 'ErrorDecoder 그 외 5xx (500/502/504) → IllegalStateException → GlobalExceptionHandler 500 응답 잔존' 도 함께 등재. 후속 토픽 범위가 503/429 분리 + 비-503 5xx 매핑 결정까지 묶이도록 가시화"
    },
    {
      "severity": "minor",
      "checklist_item": "§1.2 어댑터 회귀 검증 명시",
      "location": "docs/CLEANUP-BATCH-A-PLAN.md:278 (CBA-8 acceptance)",
      "problem": "PgInboxRepositoryImpl.transitDirectToTerminal:155 가 PgInbox.of 7-arg 직접 호출. builder 전환 후 @AllArgsConstructor(PRIVATE) 의 10-arg 인자 순서와 of 7-arg 가 매핑 어긋날 경우 amount/reasonCode/status 가 잘못 저장될 silent corruption 가능 — 보정 경로 결과 박는 핵심 호출이라 도메인 영향이 큰 surface. acceptance 가 `:pg-service:test` PASS 만 명시.",
      "evidence": "pg-service/src/main/java/.../infrastructure/repository/PgInboxRepositoryImpl.java:155. architect 인라인 주석 (PLAN.md:280) 도 동일 우려 제기",
      "suggestion": "CBA-8 acceptance 에 'PgInboxRepositoryImplTest 또는 transitDirectToTerminal 회귀 테스트 — builder 전환 후 of 7-arg 호출 결과 (status terminal + amount + reasonCode 보존) 검증' 명시. 또는 CBA-8 테스트 케이스 표에 `of_sevenArg_constructsCorrectly` 가 이미 있으나 어댑터 통과 경로까지 검증하는 1 케이스 추가 권고"
    }
  ],

  "previous_round_ref": null,
  "delta": null,

  "unstuck_suggestion": null
}
```
