# review-critic-1

**Topic**: PAYMENT-DOUBLE-FAULT-RECOVERY
**Round**: 1
**Persona**: Critic

## Reasoning

전체 `./gradlew test` 통과, TDD 커밋 구조(RED->GREEN) 준수, 핵심 복구 사이클 로직과 RecoveryDecision 값 객체의 구현이 PLAN 플로우차트와 일치한다. 다만 GUARD_MISSING_APPROVED_AT 경로에서 무한 재시도 가능성, LOCAL_TERMINAL_STATUSES 상수 중복 정의, 그리고 RecoveryReason 미사용 enum 값 존재 등 major/minor 수준의 이슈가 식별되었다.

## Checklist judgement

### task execution (태스크 실행)
- [x] 현재 태스크의 RED 커밋이 존재 — yes. Task 1~9 모두 `test:` RED 커밋 존재 (예: `2363a8d`, `c5f048e`, `9206df3`, `24ade11`, `1599362`, `ce0fcd8`, `3e47114`, `b477300`)
- [x] 현재 태스크의 GREEN 커밋이 존재 — yes. 각 Task에 대응하는 `feat:` 커밋 존재
- [x] REFACTOR 커밋은 필요한 경우에만 존재 — yes/n/a
- [x] 커밋 메시지가 `feat:` / `test:` / `refactor:` 포맷 준수 — yes. `chore:` 사용은 Task 10 (스키마 불필요 확인)으로 적절
- [x] STATE.md의 active task가 올바르게 갱신됨 — yes. `docs/STATE.md` line 10: `review` 단계, Task 11 완료 표기

### test gate (결정론적 백본)
- [x] **전체 `./gradlew test` 통과** — yes. BUILD SUCCESSFUL
- [x] 신규/수정된 business logic에 테스트 커버리지 존재 — 부분적 yes (아래 finding F-02 참조)
- [x] 새 state machine 전이가 `@ParameterizedTest @EnumSource`로 커버됨 — yes. `RecoveryDecisionTest` line 68-76, `PaymentEventTest` 상태 전이 테스트

### convention (관례)
- [x] Lombok 패턴 준수 — yes. `@RequiredArgsConstructor`, `@Getter`, `@Builder` + `@AllArgsConstructor(access = AccessLevel.PRIVATE)` 사용
- [x] `@AllArgsConstructor(access = AccessLevel.PRIVATE)` + `@Builder` 패턴 준수 — yes
- [x] 신규 로깅이 LogFmt 사용 — yes. `OutboxProcessingService` 전체에서 `LogFmt.warn` / `LogFmt.error` 사용
- [x] `null` 반환 금지, `Optional` 사용 — yes. `claimToInFlight`, `loadPaymentEvent` 등 `Optional` 반환
- [ ] `catch (Exception e)` 없음 — **no**. `OutboxProcessingService.java` line 297: `catch (Exception e)` 사용. 주석으로 의도적임을 표시했으나 `handleUnknownFailure` 경유가 아님 (F-03 참조)

### execution discipline (실행 규율)
- [x] 범위 밖 코드 수정 없음 — yes
- [x] 분석 마비 없음 — n/a (review 단계)

### final task only (마지막 태스크일 때만)
- [x] STATE.md stage -> `review`로 전환됨 — yes. `docs/STATE.md` line 9
- [x] `.continue-here.md` 제거됨 — n/a (존재하지 않았음)

## Findings

### F-01 (major): GUARD_MISSING_APPROVED_AT 경로 무한 재시도 위험
- **checklist_item**: 보상/취소 로직에 멱등성 가드 존재 (domain risk)
- **location**: `src/main/java/.../scheduler/OutboxProcessingService.java` lines 168-174
- **problem**: GUARD_MISSING_APPROVED_AT decision이 발생하면 `executePaymentRetryWithOutbox`를 호출해 retryCount를 증가시키고 다음 틱에서 재시도한다. 그러나 PG가 계속 DONE+approvedAt=null을 반환하면 retryCount가 소진될 때까지 반복된 후, RETRY_LATER의 소진 분기(line 153-154)에서 FCG로 넘어가 결국 quarantine된다. 이 자체는 결국 종결되지만, RecoveryDecision.from() (line 69-72)에서 GUARD_MISSING_APPROVED_AT를 반환할 때 retryCount/maxRetries를 고려하지 않으므로, FCG 내부(resolveFcgStatusAndDecision, line 274)에서도 GUARD_MISSING_APPROVED_AT가 반환되어 FCG의 default branch에서 quarantine된다. 이 경로는 동작하지만 PLAN 플로우차트(line 48)에서 GUARD_MISSING_APPROVED_AT는 `executePaymentRetryWithOutbox`로만 연결되어 있고, 소진 시 FCG 경유 quarantine까지의 경로가 명시되어 있지 않다. PLAN과 구현의 불일치이며, 이 경로에 대한 통합 테스트도 없다.
- **evidence**: `OutboxProcessingServiceTest.java` 전체 검색에서 "GUARD_MISSING" 또는 "approvedAt.*null" 매치 0건
- **suggestion**: (1) GUARD_MISSING_APPROVED_AT 경로의 소진->FCG->quarantine 시나리오를 테스트로 추가하거나, (2) RecoveryDecision.from()에서 retryCount >= maxRetries일 때 GUARD_MISSING_APPROVED_AT 대신 QUARANTINE을 직접 반환하여 경로를 단순화

### F-02 (major): LOCAL_TERMINAL_STATUSES 상수 중복 정의 — 불변식 동기화 위험
- **checklist_item**: 상태 전이가 불변식을 위반하지 않음 (domain risk)
- **location**: `RecoveryDecision.java` lines 18-25, `OutboxProcessingService.java` lines 36-43
- **problem**: 동일한 종결 상태 집합이 domain 계층(`RecoveryDecision`)과 scheduler 계층(`OutboxProcessingService`)에 독립적으로 중복 정의되어 있다. 향후 종결 상태가 추가/변경될 때 한쪽만 갱신하면 `RecoveryDecision.from()`의 REJECT_REENTRY 판정과 `OutboxProcessingService.process()`의 로컬 종결 체크가 불일치하여 비종결 이벤트가 PG 조회 없이 종결되거나, 종결 이벤트가 PG 조회를 유발할 수 있다.
- **evidence**: 두 파일의 Set 내용은 현재 동일하지만, `PaymentTransactionCoordinator.java` lines 23-27에도 `NON_TERMINAL_STATUSES`라는 역방향 정의가 별도 존재하여 총 3곳에서 상태 분류를 관리
- **suggestion**: `PaymentEventStatus`에 `isTerminal()` 메서드를 추가하여 단일 진실 원천(single source of truth)으로 통합하거나, domain 계층의 상수를 공유 참조

### F-03 (minor): loadPaymentEvent의 catch (Exception e) — 체크리스트 convention 위반
- **checklist_item**: `catch (Exception e)` 없음 (convention)
- **location**: `OutboxProcessingService.java` line 297
- **problem**: 체크리스트는 `catch (Exception e)` 사용 시 `handleUnknownFailure` 경유를 요구하나, 이 catch 블록은 해당 패턴을 따르지 않는다. 주석("intentionally broad")으로 의도를 표시했지만 체크리스트 기준으로는 위반.
- **evidence**: line 297-301에서 `catch (Exception e)` 후 직접 `LogFmt.error` + `incrementRetryOrFail` 호출
- **suggestion**: 기존 코드에서 이미 존재하던 패턴이라면 범위 밖 이슈로 `TODOS.md`에 기록. 신규 코드라면 구체적 예외 타입으로 좁히는 것을 권장

### F-04 (minor): RecoveryReason 미사용 enum 값 2개 — dead code
- **checklist_item**: 범위 밖 코드 수정 없음 (execution discipline 관점 역방향)
- **location**: `RecoveryReason.java` lines 5, 9 (`PG_NOT_FOUND`, `CONFIRM_RETRYABLE_FAILURE`)
- **problem**: `PG_NOT_FOUND`는 `RecoveryDecision`의 어떤 분기에서도 할당되지 않는다. `CONFIRM_RETRYABLE_FAILURE`도 마찬가지. production 코드에서 사용되지 않는 enum 값이 존재한다.
- **evidence**: `grep -r "PG_NOT_FOUND" src/main/java` 결과 `RecoveryReason.java:5`만 매치. `grep -r "CONFIRM_RETRYABLE_FAILURE" src/main/java` 결과 `RecoveryReason.java:9`만 매치
- **suggestion**: 사용 예정이 아니라면 제거하거나, PLAN에서 명시적으로 예약(reserved) 표기

### F-05 (minor): NonRetryable 예외 시 ATTEMPT_CONFIRM이 retryCount 무관 — PLAN 매핑 모호
- **checklist_item**: 상태 전이가 불변식을 위반하지 않음 (domain risk)
- **location**: `RecoveryDecision.java` lines 104-105
- **problem**: `fromException()`에서 `PaymentTossNonRetryableException` 발생 시 retryCount/maxRetries와 무관하게 항상 `ATTEMPT_CONFIRM`을 반환한다. 이는 PLAN 플로우차트(line 43)의 "PG_NOT_FOUND + 재시도 여력" 조건과 다르다. retryCount가 소진된 상태에서도 confirm을 시도하게 되는데, 이는 의도적 설계일 수 있으나 PLAN과 코드 사이의 불일치.
- **evidence**: PLAN line 43: `GS -->|PG_NOT_FOUND + 재시도 여력| RD4["RecoveryDecision.ATTEMPT_CONFIRM"]`이지만 코드는 재시도 여력 조건 없음
- **suggestion**: PLAN의 "재시도 여력" 조건이 confirm 시도에 적용되지 않는 이유를 PLAN 또는 코드 주석에 명시

## JSON

```json
{
  "stage": "code",
  "persona": "critic",
  "round": 1,
  "task_id": null,
  "decision": "revise",
  "reason_summary": "빌드 통과, TDD 구조 준수, 핵심 로직 PLAN 일치. 그러나 GUARD_MISSING_APPROVED_AT 테스트 부재와 LOCAL_TERMINAL_STATUSES 3중 중복이 major이므로 revise 판정.",
  "checklist": {
    "source": "_shared/checklists/code-ready.md",
    "items": [
      {
        "section": "test gate",
        "item": "신규/수정된 business logic에 테스트 커버리지 존재",
        "status": "no",
        "evidence": "GUARD_MISSING_APPROVED_AT 소진->FCG->quarantine 경로 테스트 부재 (OutboxProcessingServiceTest에서 해당 키워드 0건 매치)"
      },
      {
        "section": "convention",
        "item": "catch (Exception e) 없음",
        "status": "no",
        "evidence": "OutboxProcessingService.java line 297"
      },
      {
        "section": "domain risk",
        "item": "상태 전이가 불변식을 위반하지 않음",
        "status": "no",
        "evidence": "LOCAL_TERMINAL_STATUSES 3곳 중복 정의 — RecoveryDecision.java:18, OutboxProcessingService.java:36, PaymentTransactionCoordinator.java:23 (역방향)"
      }
    ],
    "total": 17,
    "passed": 14,
    "failed": 3,
    "not_applicable": 0
  },
  "scores": {
    "correctness": 0.82,
    "conventions": 0.75,
    "discipline": 0.88,
    "test_coverage": 0.72,
    "domain": 0.78,
    "mean": 0.79
  },
  "findings": [
    {
      "id": "F-01",
      "severity": "major",
      "checklist_item": "신규/수정된 business logic에 테스트 커버리지 존재",
      "location": "OutboxProcessingService.java lines 168-174, OutboxProcessingServiceTest.java (전체)",
      "problem": "GUARD_MISSING_APPROVED_AT 경로(PG DONE + approvedAt null)에 대한 OutboxProcessingService 테스트 없음. 소진->FCG->quarantine 시나리오 미검증.",
      "evidence": "OutboxProcessingServiceTest.java 전체 검색에서 GUARD_MISSING 또는 approvedAt null 매치 0건",
      "suggestion": "GUARD_MISSING_APPROVED_AT 발생 시 retry, 소진 시 FCG quarantine 2개 테스트 케이스 추가"
    },
    {
      "id": "F-02",
      "severity": "major",
      "checklist_item": "상태 전이가 불변식을 위반하지 않음",
      "location": "RecoveryDecision.java:18-25, OutboxProcessingService.java:36-43, PaymentTransactionCoordinator.java:23-27",
      "problem": "종결 상태 집합이 3곳에서 독립 중복 정의되어 동기화 실패 시 불변식 위반 위험",
      "evidence": "RecoveryDecision LOCAL_TERMINAL_STATUSES, OutboxProcessingService LOCAL_TERMINAL_STATUSES, PaymentTransactionCoordinator NON_TERMINAL_STATUSES 3곳",
      "suggestion": "PaymentEventStatus.isTerminal() 메서드로 단일 진실 원천 통합"
    },
    {
      "id": "F-03",
      "severity": "minor",
      "checklist_item": "catch (Exception e) 없음",
      "location": "OutboxProcessingService.java:297",
      "problem": "catch (Exception e) 사용이 convention 체크리스트 위반. handleUnknownFailure 경유 아님.",
      "evidence": "line 297: catch (Exception e) — 주석 intentionally broad",
      "suggestion": "구체적 예외 타입으로 좁히거나 기존 패턴이면 TODOS.md 기록"
    },
    {
      "id": "F-04",
      "severity": "minor",
      "checklist_item": "범위 밖 코드 수정 없음",
      "location": "RecoveryReason.java:5,9",
      "problem": "PG_NOT_FOUND, CONFIRM_RETRYABLE_FAILURE enum 값이 production 코드에서 미사용 (dead code)",
      "evidence": "grep 결과 RecoveryReason.java 선언부만 매치",
      "suggestion": "사용 예정 아니면 제거, 예약이면 PLAN에 명시"
    },
    {
      "id": "F-05",
      "severity": "minor",
      "checklist_item": "상태 전이가 불변식을 위반하지 않음",
      "location": "RecoveryDecision.java:104-105",
      "problem": "NonRetryable 예외 시 retryCount 무관 ATTEMPT_CONFIRM 반환 — PLAN의 재시도 여력 조건과 불일치",
      "evidence": "PLAN line 43 조건 vs RecoveryDecision.fromException() 무조건 분기",
      "suggestion": "의도적이면 PLAN 또는 코드 주석에 근거 명시"
    }
  ],
  "previous_round_ref": null,
  "delta": null,
  "unstuck_suggestion": null
}
```
