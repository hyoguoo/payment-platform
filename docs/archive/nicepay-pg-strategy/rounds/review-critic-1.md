# review-critic-1

**Topic**: NICEPAY-PG-STRATEGY
**Round**: 1
**Persona**: Critic

## Reasoning

전체 `./gradlew test` 통과, TDD RED/GREEN 커밋 쌍 확인, STATE.md review 전환 확인됨. 그러나 NicepayPaymentGatewayStrategy 내부에서 Toss 벤더 종속 에러 코드(`PaymentErrorCode.TOSS_RETRYABLE_ERROR`, `TOSS_NON_RETRYABLE_ERROR`)를 그대로 사용하고 있어 범용화 의도에 반하는 major finding 존재. 또한 `catch (Exception e)` 패턴이 `handleUnknownFailure` 경유 없이 새 코드에 등장.

## Checklist judgement

### task execution
- [x] RED 커밋 존재 (tdd=true 태스크: T3, T9, T10, T11, T14, T15) — yes
- [x] GREEN 커밋 존재 — yes
- [x] REFACTOR 커밋 필요 시에만 — n/a (없음)
- [x] 커밋 메시지 포맷 준수 — yes (`feat:`, `test:`, `docs:`)
- [x] STATE.md active task 갱신 — yes (review 단계, 활성 태스크 없음)

### test gate
- [x] `./gradlew test` 통과 — **yes**
- [x] 신규 business logic 테스트 커버리지 — yes (NicepayPaymentGatewayStrategyTest 328줄, InternalPaymentGatewayAdapterTest 112줄, 기존 테스트 수정)
- [x] 새 state machine 전이 `@ParameterizedTest @EnumSource` — yes (PaymentEventTest에 `@EnumSource(PaymentGatewayType.class)` 추가)

### convention
- [x] Lombok 패턴 준수 — yes (`@RequiredArgsConstructor`, `@Getter`, `@Builder`)
- [x] `@AllArgsConstructor(access=PRIVATE) + @Builder` — n/a (새 DTO는 infrastructure 계층 Jackson 역직렬화용으로 `@NoArgsConstructor + @AllArgsConstructor` 사용, 정당)
- [ ] 신규 로깅이 LogFmt 사용 — n/a (NicePay 전략에 별도 로깅 없음)
- [x] `null` 반환 금지, `Optional` 사용 — yes (null 반환 없음)
- [ ] `catch (Exception e)` 없음 — **no** (NicepayPaymentGatewayStrategy:105에 `catch (Exception e)` 존재, `handleUnknownFailure` 미경유)

### execution discipline
- [x] 범위 밖 코드 수정 없음 — yes
- [x] 분석 마비 없음 — n/a (review 단계)

### final task only
- [x] STATE.md stage → review — yes
- [x] `.continue-here.md` 제거 — n/a (존재하지 않았음)

## Findings

### F1
- **severity**: major
- **checklist_item**: convention — 벤더 종속 명칭 제거 일관성
- **location**: `src/main/java/.../gateway/nicepay/NicepayPaymentGatewayStrategy.java` lines 92, 94, 106, 116, 128, 165, 167
- **problem**: NicePay 전략 구현체가 `PaymentErrorCode.TOSS_RETRYABLE_ERROR` / `TOSS_NON_RETRYABLE_ERROR`를 직접 참조한다. T1에서 예외 클래스명을 범용화(`PaymentGateway*`)했으나, ErrorCode enum 값은 여전히 `TOSS_` 접두사를 유지하고 있어 NicePay 경로에서 "Toss 결제에서 재시도 가능한 오류가 발생했습니다" 메시지가 클라이언트에 노출될 수 있다.
- **evidence**: `PaymentErrorCode.java:18-19` — `TOSS_RETRYABLE_ERROR("EO3009", "Toss 결제에서 재시도 가능한 오류가 발생했습니다.")`, `TOSS_NON_RETRYABLE_ERROR("E03010", "Toss 결제에서 재시도 불가능한 오류가 발생했습니다.")`. NicepayPaymentGatewayStrategy 7곳에서 이 코드를 사용.
- **suggestion**: `PaymentErrorCode`에 범용 `GATEWAY_RETRYABLE_ERROR` / `GATEWAY_NON_RETRYABLE_ERROR`를 추가하거나, 기존 `TOSS_*` 값을 rename하여 NicePay 경로에서 Toss 메시지가 노출되지 않도록 한다.

### F2
- **severity**: minor
- **checklist_item**: convention — `catch (Exception e)` 금지
- **location**: `src/main/java/.../gateway/nicepay/NicepayPaymentGatewayStrategy.java` line 105
- **problem**: `handleDuplicateApprovalCompensation` 내 `catch (Exception e)` 블록이 `handleUnknownFailure` 경유 없이 사용되고 있다. 보상 조회 실패를 retryable로 변환하는 의도는 이해되나, 예상 외 RuntimeException(NPE 등)도 삼킨다.
- **evidence**: line 103-107에서 `PaymentGatewayNonRetryableException`을 먼저 catch한 뒤 나머지를 모두 `Exception`으로 catch.
- **suggestion**: `PaymentGatewayApiException`만 catch하거나, 최소한 로깅을 추가하여 예상 외 예외가 묻히지 않도록 한다.

### F3
- **severity**: minor
- **checklist_item**: convention — `getStatus` 인터페이스에 불필요한 gatewayType 파라미터
- **location**: `PaymentGatewayStrategy.java` lines 14, 17 / `NicepayPaymentGatewayStrategy.java` line 145 / `TossPaymentGatewayStrategy.java` line 186
- **problem**: 전략 패턴으로 이미 dispatch된 후 개별 전략의 `getStatus(paymentKey, gatewayType)` / `getStatusByOrderId(orderId, gatewayType)`에 gatewayType을 다시 전달하는 것은 불필요하다. 두 구현체 모두 이 파라미터를 사용하지 않는다.
- **evidence**: NicepayPaymentGatewayStrategy와 TossPaymentGatewayStrategy 모두 gatewayType 파라미터를 무시.
- **suggestion**: 전략 인터페이스에서 gatewayType 파라미터를 제거하고 어댑터에서만 dispatch에 사용. 단, 이는 인터페이스 변경이므로 후속 리팩터링으로 분류해도 됨.

```json
{
  "stage": "code",
  "persona": "critic",
  "round": 1,
  "task_id": null,

  "decision": "revise",
  "reason_summary": "전체 테스트 통과, TDD 커밋 구조 준수. 그러나 NicePay 전략이 Toss 벤더 종속 ErrorCode를 7곳에서 사용하여 범용화 의도에 반함 (major). catch(Exception e) 패턴과 불필요한 인터페이스 파라미터는 minor.",

  "checklist": {
    "source": "_shared/checklists/code-ready.md",
    "items": [
      {
        "section": "convention",
        "item": "벤더 종속 명칭 제거 일관성 (ErrorCode enum에 TOSS_ prefix 잔존)",
        "status": "no",
        "evidence": "PaymentErrorCode.java:18-19 TOSS_RETRYABLE_ERROR/TOSS_NON_RETRYABLE_ERROR → NicepayPaymentGatewayStrategy 7곳에서 참조"
      },
      {
        "section": "convention",
        "item": "catch (Exception e) 없음 (있다면 handleUnknownFailure 경유)",
        "status": "no",
        "evidence": "NicepayPaymentGatewayStrategy.java:105 catch(Exception e) without handleUnknownFailure"
      },
      {
        "section": "convention",
        "item": "getStatus 인터페이스에 불필요한 gatewayType 파라미터",
        "status": "no",
        "evidence": "PaymentGatewayStrategy.java:14,17 — 두 구현체 모두 파라미터 무시"
      }
    ],
    "total": 17,
    "passed": 13,
    "failed": 3,
    "not_applicable": 1
  },

  "scores": {
    "correctness": 0.90,
    "conventions": 0.68,
    "discipline": 0.88,
    "test_coverage": 0.85,
    "domain": 0.82,
    "mean": 0.83
  },

  "findings": [
    {
      "severity": "major",
      "checklist_item": "convention — 벤더 종속 명칭 제거 일관성",
      "location": "src/main/java/.../gateway/nicepay/NicepayPaymentGatewayStrategy.java lines 92,94,106,116,128,165,167",
      "problem": "NicePay 전략이 PaymentErrorCode.TOSS_RETRYABLE_ERROR / TOSS_NON_RETRYABLE_ERROR를 사용. 클라이언트에 'Toss 결제에서...' 메시지 노출 가능.",
      "evidence": "PaymentErrorCode.java:18-19 TOSS_ prefix, NicepayPaymentGatewayStrategy 7곳 참조",
      "suggestion": "GATEWAY_RETRYABLE_ERROR / GATEWAY_NON_RETRYABLE_ERROR로 rename 또는 신규 추가"
    },
    {
      "severity": "minor",
      "checklist_item": "convention — catch (Exception e) 없음",
      "location": "src/main/java/.../gateway/nicepay/NicepayPaymentGatewayStrategy.java:105",
      "problem": "handleDuplicateApprovalCompensation에서 catch(Exception e)가 handleUnknownFailure 미경유. NPE 등 예상 외 예외를 삼킬 수 있음.",
      "evidence": "line 103-107 catch block",
      "suggestion": "PaymentGatewayApiException만 catch하거나 로깅 추가"
    },
    {
      "severity": "minor",
      "checklist_item": "convention — 인터페이스 파라미터 불필요",
      "location": "PaymentGatewayStrategy.java:14,17",
      "problem": "전략 dispatch 후 getStatus/getStatusByOrderId에 gatewayType을 다시 전달하나 구현체에서 무시됨.",
      "evidence": "NicepayPaymentGatewayStrategy, TossPaymentGatewayStrategy 모두 gatewayType 미사용",
      "suggestion": "후속 리팩터링으로 인터페이스에서 gatewayType 제거"
    }
  ],

  "previous_round_ref": null,
  "delta": null,

  "unstuck_suggestion": null
}
```
