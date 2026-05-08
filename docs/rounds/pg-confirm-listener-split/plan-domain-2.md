# plan-domain-2

**Topic**: PG-CONFIRM-LISTENER-SPLIT
**Round**: 2
**Persona**: Domain Expert

## Reasoning

Round 1 domain-1 가 제기한 결제 도메인 핵심 race window 4건 (D-F1 listener active TX publishEvent / D-F2 handleVendorIndeterminate atomicity / D-F3 handleTerminal TX 경계 / D-F4 호출처 인벤토리 + dead service) 이 모두 PCS-7 / PCS-9 산출물 + 테스트 명세 + 메타박스 정정에 명시적으로 흡수되었다. 흡수된 finding 을 Round 2 에서 재제기하지 않는다는 격리 원칙에 따라, 신규 도메인 리스크가 발견되지 않으므로 `pass` 로 판정한다.

## Domain risk checklist

- [yes] 중복 방지 체크: `insertPending` UNIQUE 충돌 IGNORE + 기존 inboxId 반환 — PCS-3 시그니처 + PCS-4 테스트 정합 (Round 1 그대로 yes)
- [yes] 결제 상태 전이: PENDING / IN_PROGRESS / APPROVED / FAILED / QUARANTINED 5상태 — PCS-2 도메인 갱신 + PCS-4 SKIP LOCKED 정합
- [yes] 보정 경로 PENDING 우회 룰 (§1.8): `transitDirectToInProgress` / `transitDirectToTerminal` 신설 — PCS-3 / PCS-9 정합
- [yes] **listener TX 경계 검증** (§1.1 / D-F1 흡수): PCS-7 line 196 `insertPendingAndPublish_publishesEventInsideActiveTransaction` (`TransactionSynchronizationManager.isActualTransactionActive()` 검증) + line 197 `insertPendingAndPublish_afterCommitListenerFires` (`@DataJpaTest` AFTER_COMMIT 발화 검증) 두 테스트로 acceptance A5 ↔ PCS-7 1:1 매핑 봉인. PITFALLS §3 짝패턴 가드 가시화 OK.
- [yes] **`handleVendorIndeterminate` 두 호출 atomicity** (D-F2 흡수): PCS-9 line 234-235 "TX 경계 룰" 절에 `@Transactional` 보존 필수 + 묶이지 않을 시 좀비 무한 루프 위험 명시. line 253 `handleVendorIndeterminate_atomicity_singleTransaction` 테스트가 두 호출의 단일 TX 봉인을 검증. 무한 루프 race 차단 가시화 OK.
- [yes] **terminal 재수신 listener 직접 처리 TX 경계** (D-F3 흡수): PCS-9 line 234-236 "TX 경계 룰" 절 두 번째 bullet — `handleTerminal` `@Transactional` 명시 + AFTER_COMMIT 미등록 시 §1.6 안 B 채택 사유 (latency 우위) 무효화 위험 적시. line 256 산출물 본문에 `handleTerminal` `@Transactional` 명시. latency 우위 봉인 OK.
- [yes] 멱등성 layer 연계: 좀비 회수 → `processInProgressZombie` → 벤더 ALREADY_PROCESSED → DuplicateApprovalHandler — PCS-8 line 220 + PCS-9 line 252 + PCS-15 매핑
- [yes] race window 가드: SKIP LOCKED + WHERE status=? 두 layer — PCS-4 + PCS-15
- [yes] PG 벤더 실패 모드 cover: timeout / 5xx / ALREADY_PROCESSED / INDETERMINATE — `applyOutcome` 5분기 PCS-6 cover
- [yes] **호출처 인벤토리 정확성** (D-F4 흡수): 메타박스 line 19 "6곳" (이전 5곳 → 정정) + PCS-9 line 230 / 238 `PgInboxAmountService` dead service 명시 + 본 토픽 범위는 컴파일 에러 해소만, dead service 제거는 별 토픽 / 사용자 확인 후 처리 명시. memory `feedback_dead_code_requires_user_confirmation.md` 정합 OK.
- [n/a] PII 처리: 본 토픽 PII 노출 surface 0
- [n/a] 금전 정확성: amount 컬럼 / scale 변경 없음

## 도메인 관점 추가 검토

Round 1 finding 4건 모두 흡수 OK. 신규 도메인 리스크 발견 없음.

흡수 검증 (격리 원칙: Round 1 본인 finding 중복 들지 않음, 신규 finding 만 들 수 있음):

### D-F1 (major) → 흡수 OK
- PCS-7 테스트 메서드 line 196 + 197 두 건 추가. `TransactionSynchronizationManager.isActualTransactionActive()` 직접 검증 + `@DataJpaTest` 환경 AFTER_COMMIT 발화 검증 둘 다 적시. Round 1 의 핵심 우려 (silent latency 5s) 가시화 완료.

### D-F2 (major) → 흡수 OK
- PCS-9 line 234-235 본문 룰 명시 + line 253 atomicity 테스트 추가. Round 1 권고 (a) `@Transactional` 보존 명시 채택 (권고 (b) `transitDirectToTerminal(QUARANTINED)` 단일 압축은 미채택이지만 도메인 안전성은 (a) 로도 봉인됨 — 무한 루프 race 차단 OK).

### D-F3 (major) → 흡수 OK
- PCS-9 line 234-236 본문 룰 명시 + line 256 산출물 갱신 명시. Round 1 권고 (a) `@Transactional` 봉인 채택 (권고 (b) 별도 `PgInboxTerminalReemitService` 신규는 미채택이지만 latency 우위 봉인은 (a) 로도 충족).

### D-F4 (minor) → 흡수 OK
- 메타박스 line 19 정정 + PCS-9 본문 dead service 명시 + 본 토픽 범위 명시. Round 1 권고 (a) dead service 명시 + 별 토픽 처리 채택 — 사용자 확인 룰 정합.

## Findings

| ID | Severity | Location | Problem | Suggestion |
|---|---|---|---|---|
| (none) | — | — | Round 1 finding 4건 모두 흡수 — 신규 도메인 리스크 발견 없음 | — |

## JSON

```json
{
  "stage": "plan",
  "persona": "domain-expert",
  "round": 2,
  "task_id": null,

  "decision": "pass",
  "reason_summary": "Round 1 domain-1 의 도메인 리스크 4건 (listener active TX publishEvent / handleVendorIndeterminate atomicity / handleTerminal TX 경계 / 호출처 인벤토리 + dead service) 모두 PCS-7 테스트 2건 추가 + PCS-9 본문 TX 경계 룰 명시 + atomicity 테스트 추가 + handleTerminal @Transactional 명시 + 메타박스 6곳 정정 + dead service 명시로 명시적으로 흡수됨. 신규 도메인 리스크 발견 없음.",

  "checklist": {
    "source": "_shared/checklists/plan-ready.md (Gate checklist — domain risk 섹션)",
    "items": [
      {
        "section": "domain risk",
        "item": "discuss에서 식별된 domain risk가 각각 대응 태스크를 가짐 (멱등성 검증 테스트, 상태 전이 테스트 등)",
        "status": "yes",
        "evidence": "PCS-7 line 196 active TX 검증 테스트 + line 197 AFTER_COMMIT 발화 테스트 (D-F1 흡수); PCS-9 line 253 atomicity 테스트 (D-F2 흡수); PCS-9 line 256 handleTerminal @Transactional 명시 (D-F3 흡수)"
      },
      {
        "section": "domain risk",
        "item": "중복 방지 체크(예: existsByOrderId)가 필요한 경로에 계획됨",
        "status": "yes",
        "evidence": "PCS-3 insertPending UNIQUE 충돌 IGNORE 시그니처 + PCS-4 duplicate test"
      },
      {
        "section": "domain risk",
        "item": "재시도 안전성 검증 태스크 존재 (재시도 정책이 있는 경우만)",
        "status": "yes",
        "evidence": "PCS-15 zombieRecovery_afterWorkerCrash_completesProcessing 통합 테스트 + PCS-9 보정 경로 단위 테스트"
      },
      {
        "section": "task quality (도메인 안전성 영향)",
        "item": "보정 경로 PENDING 우회 룰의 atomicity 봉인",
        "status": "yes",
        "evidence": "PCS-9 line 234-235 TX 경계 룰 + line 253 atomicity 테스트 (D-F2 흡수)"
      },
      {
        "section": "task quality (도메인 안전성 영향)",
        "item": "terminal 재수신 listener 직접 처리의 TX 경계 봉인",
        "status": "yes",
        "evidence": "PCS-9 line 234-236 TX 경계 룰 + line 256 handleTerminal @Transactional 명시 (D-F3 흡수)"
      },
      {
        "section": "traceability",
        "item": "호출처 인벤토리 정확성",
        "status": "yes",
        "evidence": "메타박스 line 19 6곳 정정 + PCS-9 line 230 / 238 dead service 명시 + 별 토픽 처리 방침 (D-F4 흡수)"
      }
    ],
    "total": 6,
    "passed": 6,
    "failed": 0,
    "not_applicable": 0
  },

  "scores": {
    "traceability": 0.95,
    "decomposition": 0.90,
    "ordering": 0.92,
    "specificity": 0.92,
    "risk_coverage": 0.95,
    "mean": 0.928
  },

  "findings": [],

  "previous_round_ref": "docs/rounds/pg-confirm-listener-split/plan-domain-1.md",
  "delta": "Round 1 finding 4건 (D-F1 / D-F2 / D-F3 / D-F4) 모두 흡수 확인. 신규 finding 0.",

  "unstuck_suggestion": null
}
```
