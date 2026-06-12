# review-domain-3

**Topic**: OBSERVABILITY-COMPLETION
**Round**: 3
**Persona**: Domain Expert

## Reasoning

review-2 minor 3건이 모두 정확한 방식으로 처리됐다 — Fix-1 은 aspect 의 || 체인을 `PaymentEventStatus.isTerminal()` SSOT 위임으로 교체했고 enum 의 exhaustive switch 가 동일 집합(DONE/FAILED/CANCELED/PARTIAL_CANCELED/EXPIRED=true, QUARANTINED 포함 4종=false)을 반환함을 소스로 직접 확인 — 종결 카운트 동작 무변경에 미래 드리프트는 컴파일 강제로 차단된다. Fix-2 9케이스 테스트가 그 동작 보존을 회귀 가드로 고정했고(GREEN 실증), Fix-3 패널 description 이 근사값 한계를 명시했으며, minor 3 은 TODOS [GUARD-SKIP-EAGER-REGISTER] 로 등재(승인된 이연). 결제 정합성 영향 0 — open finding 없음, pass.

## Domain risk checklist

| 항목 | 판정 | 근거 |
|---|---|---|
| paymentKey/orderId/카드번호 plaintext 노출 없음 | yes | 수정 3건 모두 신규 로그/라벨 0건. 테스트 fixture 의 orderId 는 테스트 코드 내부 — 메트릭 라벨·로그 미노출. 카운터 무라벨(D7) 불변 |
| 보상/취소 멱등성 가드 | n/a | 보상·취소 경로 미접촉 — aspect 종결 판별 위임 + 테스트 + 대시보드 description 만 변경 |
| PG "이미 처리됨" 응답 맹목 수용 없음 | n/a | PG 연동 계약 무변경 |
| 상태 전이 불변식 위반 없음 | yes | Fix-1 은 판별 로직 출처만 교체 — 전이 자체·AOP proceed 결과 반환 무간섭. `PaymentEventStatus.isTerminal()` switch 가 삭제된 || 체인과 정확히 동일 집합 (enum 소스 직접 대조) |
| race window 락/트랜잭션 격리 | yes | Micrometer Counter 직교성 불변. 비트랜잭셔널 카운터의 절대값 한계는 Fix-3 description 으로 운영 해석 안내 완료 |

## 도메인 관점 추가 검토

1. **Fix-1 동작 보존 실증** — `PaymentEventStatus.java:21-26` `isTerminal()` exhaustive switch: `DONE, FAILED, CANCELED, PARTIAL_CANCELED, EXPIRED -> true` / `READY, IN_PROGRESS, RETRYING, QUARANTINED -> false`. 삭제된 `isTerminalStatus` || 체인(9478d542 diff)과 집합 동일 — 종결 카운트 1건도 변하지 않는다. 향후 상태 추가 시 enum switch 가 컴파일 에러로 강제 — review-2 에서 지적한 silent funnel drift 벡터 제거. `canApplyConfirmResult`/`canCompensateStock`(D7 가드)는 미접촉 — 결제 정합성 무영향.
2. **Fix-2 회귀 가드 적합성** — `PaymentStatusMetricsAspectTerminalTest` 9케이스가 종결 5종 카운터 1.0 / 비종결 4종(QUARANTINED 포함) 미증가를 SimpleMeterRegistry 로 직접 단언. QUARANTINED 가 "비종결 → 카운터 미증가" 케이스로 명시 고정돼, 격리 결제가 in-flight 잔차에 남는 의도된 알람 의미(PITFALLS §19 동조)가 테스트로 봉인됨. 실행 실증: 35 tests GREEN (`:payment-service:test --tests *PaymentStatusMetricsAspectTerminalTest* --tests *PaymentEventStatus*`).
3. **Fix-3 한계 명시 충분성** — `business-dashboard.json` in-flight stat description 에 at-least-once 재배달 중복·재시작 카운터 리셋 + "추세(증감)로 해석" 추가. review-2 minor 2 의 세 드리프트 경로 중 (a)(c) 명시, (b) create TX 커밋 실패 과대는 "근사값·추세 해석" 안내에 포섭 — 운영자 오독 위험 해소로 충분. gauge 보조 단정은 T10 라이브 스모크 재량 사항으로 잔존해도 무방.
4. **minor 3 이연 처리** — `docs/context/TODOS.md` [GUARD-SKIP-EAGER-REGISTER] 등재 확인 (이론 경로 + 이연 사유 + 관련 코드 명시). 가드 스킵 카운터 lazy register 는 이름/태그셋 충돌이라는 이론 조건에서만 발화하는 경로라 이연 수용이 도메인 리스크 관점에서도 타당.
5. **수정 범위 규율** — 9478d542 변경 5파일: aspect(판별 위임) + 신규 테스트 + 대시보드 description 1줄 + PLAN/TODOS 문서. 결제 상태 전이·트랜잭션 경계·멱등성 코드 0줄 접촉 — 최종 재리뷰에서 새 도메인 리스크 유입 없음.

## Findings

(없음 — review-2 minor 1·2 해소, minor 3 은 사용자 승인 이연으로 TODOS 등재 완료)

## JSON

```json
{
  "stage": "review",
  "persona": "domain-expert",
  "round": 3,
  "task_id": null,

  "decision": "pass",
  "reason_summary": "Fix-1 SSOT 위임은 enum isTerminal() exhaustive switch 와 삭제된 || 체인의 집합 동일성을 소스 대조로 확인 — 종결 카운트 동작 무변경 + 미래 드리프트 컴파일 차단. Fix-2 9케이스 테스트 GREEN 실증, Fix-3 근사값 한계 description 반영, minor 3 TODOS 이연 등재. 결제 상태 전이·멱등성·트랜잭션 경계 미접촉 — open finding 0건.",

  "checklist": {
    "source": "_shared/checklists/code-ready.md (domain risk 섹션) + 결제 도메인 추가 점검",
    "items": [
      {
        "section": "domain risk",
        "item": "Fix-1 이 종결 카운트 동작을 바꾸지 않음 (SSOT isTerminal() == 기존 || 체인 집합)",
        "status": "yes",
        "evidence": "PaymentEventStatus.java:21-26 switch: DONE/FAILED/CANCELED/PARTIAL_CANCELED/EXPIRED→true, READY/IN_PROGRESS/RETRYING/QUARANTINED→false — 9478d542 에서 삭제된 isTerminalStatus || 체인과 동일 집합. exhaustive switch 라 상태 추가 시 컴파일 강제"
      },
      {
        "section": "domain risk",
        "item": "상태 전이 불변식·D7 가드·멱등성 미접촉",
        "status": "yes",
        "evidence": "변경 5파일 중 결제 로직 접촉은 aspect 판별 출처 교체 1건뿐 — proceed 결과 반환·canApplyConfirmResult/canCompensateStock·dedupe 무변경"
      },
      {
        "section": "domain risk",
        "item": "QUARANTINED 비종결 의미가 테스트로 봉인됨",
        "status": "yes",
        "evidence": "PaymentStatusMetricsAspectTerminalTest — 비종결 @EnumSource 에 QUARANTINED 포함, 카운터 미증가 단언. 격리=in-flight 잔차 잔존(의도된 알람, PITFALLS §19) 회귀 가드"
      },
      {
        "section": "domain risk (review-2 minor 2 해소)",
        "item": "in-flight 근사값 한계가 운영자에게 노출됨",
        "status": "yes",
        "evidence": "business-dashboard.json in-flight stat description — at-least-once 재배달 중복·재시작 카운터 리셋·추세 해석 안내 추가"
      },
      {
        "section": "domain risk (review-2 minor 3 이연)",
        "item": "가드 스킵 lazy register 이론 경로가 추적 가능하게 등재됨",
        "status": "yes",
        "evidence": "docs/context/TODOS.md [GUARD-SKIP-EAGER-REGISTER] — 경로·사유·관련 코드 명시, 사용자 승인 이연"
      },
      {
        "section": "test gate",
        "item": "신규 aspect 종결 테스트 GREEN",
        "status": "yes",
        "evidence": "gradlew :payment-service:test --tests *PaymentStatusMetricsAspectTerminalTest* --tests *PaymentEventStatus* → 35 tests SUCCESS"
      }
    ],
    "total": 6,
    "passed": 6,
    "failed": 0,
    "not_applicable": 0
  },

  "scores": {
    "correctness": 0.95,
    "conventions": 0.93,
    "discipline": 0.95,
    "test_coverage": 0.95,
    "domain": 0.95,
    "mean": 0.946
  },

  "findings": [],

  "previous_round_ref": "review-domain-2.md",
  "delta": {
    "newly_passed": [
      "terminal 판별 SSOT 위임 (review-2 minor 1)",
      "in-flight 근사값 한계 명시 (review-2 minor 2)",
      "가드 스킵 lazy register TODOS 등재 (review-2 minor 3 이연)"
    ],
    "newly_failed": [],
    "still_failing": []
  },

  "unstuck_suggestion": null
}
```
