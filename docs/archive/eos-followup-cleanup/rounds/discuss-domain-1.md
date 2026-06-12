# discuss-domain-1

**Topic**: EOS-FOLLOWUP-CLEANUP
**Round**: 1
**Persona**: Domain Expert

## Reasoning

pg_inbox 종결 행 cleanup이 멱등성 SoT 를 파괴해 retention 창(P8D) 내 confirm 재배달이 `reemit`(벤더 호출 0) 경로를 우회하고 `handleAbsent → PENDING INSERT → 워커 벤더 confirm 재호출`로 빠지는 돈 새는 경로를 코드로 확인했다(critical). 설계는 이 위험을 "보존 기간 8일"로 막으려 하나, 종결 행 삭제 자체가 멱등 가드 소실이라 8일+ε 재배달이면 동일하게 발생하므로 보존 기간 값 선택으로 해소되지 않는다. QUARANTINED 종결 행 자동 삭제 시 admin 보정 근거 소실, FOLLOW-5 분리 후 정책 드리프트 시 D7 가드 침묵 붕괴도 major 로 짚는다.

## Domain risk checklist

- **멱등성 전략 결정됨**: PARTIAL. cleanup DELETE 자체는 조건 멱등(맞음). traceparent INSERT IGNORE 최초 고정(맞음). 그러나 pg_inbox 종결 행은 confirm 재배달에 대한 **멱등 판정 SoT** 역할을 겸하는데, 삭제 후 재배달 시 멱등 경로(`reemit`)가 무력화되는 점이 미해소 → 멱등성 전략에 구멍.
- **장애 시나리오 최소 3개**: YES (L-1~L-4, 4개). 단 가장 치명적인 "종결 행 삭제 후 retention 내 재배달" 시나리오가 L-1~L-4에 없다 — completeness 부족.
- **재시도 정책**: YES. cleanup은 @Scheduled 다음 주기 = 재시도, best-effort traceparent 무재시도 — 적정.
- **PII/민감정보**: YES. traceparent(W3C `00-traceid-spanid-flags`)는 불투명 식별자, 금액/카드/고객정보 없음. confirm 판정 무참여 확인됨.

## 도메인 관점 추가 검토

1. **[CRITICAL] pg_inbox 종결 행 삭제 = confirm 재배달 멱등 SoT 파괴.**
   `PgConfirmService.processCommand`(pg-service/.../application/service/PgConfirmService.java:73-89)는 `findByOrderId` 결과로 분기한다 — 행이 있고 terminal 이면 `pgTerminalReemitService.reemit(inbox)`로 **벤더 호출 없이** 저장된 결과를 재발행한다(line 84-88). 그런데 cleanup으로 종결 행이 삭제되면 같은 orderId의 confirm 명령 재배달 시 `inbox == null` 분기(line 77-79) → `handleAbsent` → `PgInboxPendingService.insertPendingAndPublish`로 **PENDING 신설** → 워커가 다시 벤더 confirm 을 호출한다. `payment.commands.confirm` 토픽은 Kafka retention(7일) 안에서 재배달이 가능(INTEGRATIONS.md line 103-104 self-loop + 일반 재배달)하므로, P8D 보존이어도 retention 경계 부근/초과 재배달에서 이 경로가 열린다. 벤더 멱등(`ALREADY_PROCESSED`)이 보통 막지만, 그건 PG 벤더 측 보장이지 우리 시스템의 1차 방어가 아니다. 종결 행은 "이미 처리했다"는 우리 측 멱등 진실원인데 그걸 지우는 것이다. **설계 D-PGINBOX-2 본문(topic line 204)이 정확히 이 위험("retention 내 재배달 메시지가 inbox absent 판정 → 보정 경로 PENDING 우회 재진입")을 인지하면서도 "8일 정렬이 가장 안전"으로 닫았다 — 8일은 retention(7일)보다 1일 길 뿐, 7일+α 지연 재배달(self-loop 누적/DLQ 재처리/운영 재발행)을 못 막는다.** 보존 기간 값이 아니라 "종결 행을 멱등 창 동안 보존한다는 불변식"이 빠졌다.

2. **[MAJOR] QUARANTINED 종결 행 자동 삭제 = admin 보정 근거 소실.**
   pg `PgInboxStatus.isTerminal()`(pg-service/.../domain/enums/PgInboxStatus.java:20-24)은 QUARANTINED를 terminal로 본다 — 설계가 삭제 대상에 넣은 건 코드상 맞다(검증 완료). 그러나 QUARANTINED는 "수동 확인 필요" 격리 상태이고 `storedStatusResult`/`reasonCode`(벤더 응답 JSON + 격리 사유)를 보관한다(PgInbox.java:38-39, markQuarantined line 406-415). payment 측 QUARANTINED는 `isTerminal()=false`로 admin 강제 전이 전까지 폴링 PROCESSING(PITFALLS.md §19, CONFIRM-FLOW.md line 369-376)인데, 그 대응 pg 행을 8일 후 삭제하면 admin 이 격리 원인을 재구성할 1차 근거가 사라진다. APPROVED/FAILED와 QUARANTINED를 동일 보존 기간으로 묶는 것이 위험 — QUARANTINED는 더 길게 보존하거나 cleanup 제외를 검토해야 한다.

3. **[MAJOR] FOLLOW-5 분리 후 두 가드 정책 드리프트 = D7 침묵 붕괴 재현.**
   현 `isCompensatableByFailureHandler()`(payment-service/.../domain/enums/PaymentEventStatus.java:45-50)는 READY/IN_PROGRESS/RETRYING=true로 두 호출처(`PaymentConfirmResultUseCase.handle` line 94 컨슈머 종결 가드 / `PaymentTransactionCoordinator.executePaymentFailureCompensationWithOutbox` line 141 보상 가드)가 공유한다. 정답표가 현재 동일하므로 분리 자체는 안전(검증 완료). 위험은 분리 **후**다 — enum Javadoc(line 39-41)이 명시하듯 "QUARANTINED를 컨슈머에서 true로 바꾸면 늦은 APPROVED가 markPaymentAsDone not-retryable 예외 → DLQ silent"(PITFALLS.md §21 D7 가드와 동일 사고). 분리하면 컴파일러가 한쪽만 바뀐 걸 못 막는다. 설계 D-SPLIT-1(topic line 156-168)은 분리 이점만 강조하고, **두 메서드가 서로 다른 정답표로 갈라질 때 D7 가드(QUARANTINED=false)와 D12 보상 가드(QUARANTINED=false)가 동시에 유지돼야 한다는 불변식을 회귀 테스트로 묶는 의무를 명시하지 않았다.** 9상태×2메서드 EnumSource(line 168)는 "현재 값"만 고정할 뿐, "두 메서드의 QUARANTINED/EXPIRED 답이 의도적으로 같아야 한다"는 교차 불변식은 보호하지 못한다.

4. **[MINOR] cleanup DELETE의 updated_at 필터에 인덱스 부재 — 풀스캔 위험.**
   D-CLEAN-3(topic line 191)은 `status IN (...) AND updated_at < :threshold`로 삭제하나, V1 스키마(pg-service/.../db/migration/V1__pg_schema.sql:21-23)에는 `ux_pg_inbox_order_id`(UNIQUE)와 `idx_pg_inbox_status`(status 단일)만 있고 updated_at 인덱스가 없다. status 인덱스만으로는 종결 행이 많아질수록 updated_at 비교가 행별 평가 → 부하. 도메인 영향은 낮으나(백그라운드 삭제), cleanup이 confirm 본류 DB와 같은 인스턴스를 공유하면 락/IO 경합 가능. 복합 인덱스(status, updated_at) 검토를 plan에 남길 것. (성능 자체는 Critic 영역에 가까우나 confirm 경합 가능성 때문에 기록.)

5. **[검증 OK] cleanup의 updated_at 기반 보존 시계 vs IN_PROGRESS 좀비 회수의 updated_at 갱신.**
   cleanup은 종결 상태만 지우므로(WHERE status IN APPROVED/FAILED/QUARANTINED) PENDING/IN_PROGRESS 좀비는 안 건드린다 — 회수 SoT 보존 가드 성립. 종결 전이 시 updated_at이 갱신되므로(PgInbox.markApproved/markFailed/markQuarantined line 334-434) 보존 시계는 "종결 시점"부터 P8D — 적정. 좀비 회수가 updated_at(IN_PROGRESS) 기준이고 cleanup은 종결 행만 대상이라 두 시계가 충돌하지 않음 — 안전.

6. **[검증 OK] traceparent 관측성 격리 + parent 복원 선택.**
   stored_traceparent는 confirm 비즈니스 판정(상태 전이/금액 검증/멱등)에 일절 참여하지 않고(topic line 254), NULL 폴백 시 새 root span으로 회수 정상 동작 — 격리 성립. §9 D-TRACE-3의 "parent vs span link" 질문: 좀비 회수는 원본 confirm의 **인과적 연속**(같은 결제 처리의 지연된 후속)이므로 parent 복원이 도메인상 맞다. span link는 서로 다른 trace 간 약한 연관에 적합 — 여기선 동일 작업의 지연 처리이므로 parent 가 정확. 다만 폴링 워커 Javadoc(PgInboxPollingWorker.java:29-31, 94-95 "의도적 새 root span 분리")을 반드시 갱신해야 다음 변경자 혼란 차단 — 설계 D-TRACE-3(topic line 219)이 이미 명시함, OK.

## Findings

- **CRITICAL** — pg_inbox 종결 행 cleanup이 confirm 재배달 멱등 SoT를 파괴. retention 창 내/경계 재배달이 `reemit`(벤더 호출 0)을 우회해 PENDING 재신설 + 벤더 confirm 재호출 경로로 빠짐. 보존 기간 8일로 닫을 수 없는 구조적 갭(검토 1).
- **MAJOR** — QUARANTINED 종결 행을 APPROVED/FAILED와 동일 8일로 자동 삭제 시 admin 격리 보정 근거(storedStatusResult/reasonCode) 소실(검토 2).
- **MAJOR** — FOLLOW-5 메서드 분리 후 두 가드 정답표가 갈라질 때 D7 침묵 DLQ 사고가 재현 가능. 교차 불변식(두 메서드의 QUARANTINED/EXPIRED 답 동조)을 보호하는 회귀 테스트 의무가 설계에 없음(검토 3).
- **MINOR** — pg_inbox cleanup의 updated_at 필터에 인덱스 부재, 종결 행 누적 시 풀스캔 → confirm 본류 DB 경합 가능(검토 4).

## JSON
```json
{
  "stage": "discuss",
  "persona": "domain-expert",
  "round": 1,
  "task_id": null,

  "decision": "fail",
  "reason_summary": "pg_inbox 종결 행 cleanup이 confirm 재배달 멱등 SoT를 파괴해 retention 창 내 재배달이 벤더 confirm 재호출 경로(돈 새는 경로)로 빠진다. 보존 기간 8일로는 해소되지 않는 구조적 갭이라 critical 1건 → fail.",

  "checklist": {
    "source": "_shared/checklists/discuss-ready.md",
    "items": [
      {
        "section": "domain risk",
        "item": "멱등성 전략이 결정됨 (idempotency key 소스/수명/충돌 처리)",
        "status": "no",
        "evidence": "topic line 200-205 (D-PGINBOX-1/2). cleanup DELETE 자체는 멱등이나, pg_inbox 종결 행이 confirm 재배달 멱등 SoT를 겸하는데 삭제 후 PgConfirmService.java:77-89 의 reemit 멱등 경로가 무력화됨 — 멱등 전략에 구멍"
      },
      {
        "section": "domain risk",
        "item": "장애 시나리오 최소 3개 식별됨",
        "status": "yes",
        "evidence": "topic line 266-271 L-1~L-4 (4개). 단 종결 행 삭제 후 retention 내 재배달 시나리오 누락"
      },
      {
        "section": "domain risk",
        "item": "재시도 정책이 정의됨",
        "status": "yes",
        "evidence": "topic line 274-276. @Scheduled 다음 주기 = 재시도, best-effort traceparent 무재시도 — 적정"
      },
      {
        "section": "domain risk",
        "item": "PII/민감정보 로깅·저장·전송 경로 검토됨",
        "status": "yes",
        "evidence": "topic line 280. W3C traceparent 불투명 식별자, 금액/카드/고객정보 없음, confirm 판정 무참여 확인"
      },
      {
        "section": "design decisions",
        "item": "전체 결제 흐름과의 호환성이 검토됨",
        "status": "no",
        "evidence": "topic line 250-254 §5. pg_inbox cleanup 호환성 검토가 'PENDING/IN_PROGRESS 미삭제'만 다루고 종결 행 삭제 후 재배달 시 PgConfirmService absent 분기 진입은 누락"
      }
    ],
    "total": 5,
    "passed": 3,
    "failed": 2,
    "not_applicable": 0
  },

  "scores": {
    "clarity": 0.85,
    "completeness": 0.62,
    "risk": 0.55,
    "testability": 0.78,
    "fit": 0.80,
    "mean": 0.72
  },

  "findings": [
    {
      "severity": "critical",
      "checklist_item": "멱등성 전략이 결정됨",
      "location": "docs/topics/EOS-FOLLOWUP-CLEANUP.md line 191,200-205 / pg-service/src/main/java/com/hyoguoo/paymentplatform/pg/application/service/PgConfirmService.java:73-89",
      "problem": "pg_inbox 종결 행 cleanup이 confirm 재배달에 대한 멱등 판정 SoT를 삭제한다. PgConfirmService.processCommand는 findByOrderId 결과가 terminal이면 reemit(벤더 호출 0)으로 처리하지만, 종결 행 삭제 후 같은 orderId 재배달 시 inbox==null 분기 → handleAbsent → PENDING 신설 → 워커가 벤더 confirm 재호출로 빠진다. payment.commands.confirm은 Kafka retention(7일) 내 재배달이 가능하므로 P8D 보존이어도 retention 경계/초과(self-loop 누적, DLQ 재처리, 운영 재발행) 지연 재배달에서 이 경로가 열린다.",
      "evidence": "PgConfirmService.java:84-88 reemit 경로 vs line 77-79 handleAbsent 경로. INTEGRATIONS.md line 103-104 self-loop 재발행. topic line 204가 동일 위험을 인지하면서 '8일 정렬'로 닫음 — 8일은 retention 7일+1일이라 7일+α 지연 재배달을 못 막는다.",
      "suggestion": "종결 행 삭제를 '멱등 창 동안 보존' 불변식으로 재정의하라. (a) 종결 행을 retention 이상 충분히 보존(updated_at 기준이므로 종결 시점부터 최소 retention+버퍼)하되 그 근거를 '재배달 멱등 SoT 보존'으로 명문화, 또는 (b) 삭제하더라도 reemit 멱등을 대체할 경량 종결 마커(payment_event_dedupe류)를 남겨 absent 우회를 차단, 또는 (c) handleAbsent 경로가 벤더 재호출 전 추가 멱등 가드를 거치도록 보강. 단순 보존 기간 값 조정으로는 닫히지 않음을 plan에 명시."
    },
    {
      "severity": "major",
      "checklist_item": "전체 결제 흐름과의 호환성이 검토됨",
      "location": "docs/topics/EOS-FOLLOWUP-CLEANUP.md line 191,200,203-205 / pg-service/src/main/java/com/hyoguoo/paymentplatform/pg/domain/PgInbox.java:406-415",
      "problem": "QUARANTINED 종결 행을 APPROVED/FAILED와 동일한 8일로 자동 삭제하면 admin 격리 보정 근거(storedStatusResult 벤더 응답 + reasonCode 격리 사유)가 소실된다. QUARANTINED는 '수동 확인 필요' 상태이고 payment 측 대응 행은 isTerminal=false로 admin 강제 전이 전까지 폴링 PROCESSING이다(PITFALLS.md §19).",
      "evidence": "PgInboxStatus.isTerminal()은 QUARANTINED=terminal(line 20-24)이라 삭제 대상 포함은 코드상 맞으나, PgInbox.markQuarantined(line 406-415)가 보관하는 reasonCode/storedStatusResult가 admin 재구성의 1차 근거. topic line 201은 'QUARANTINED는 회수 대상 아님'만 논하고 admin 보정 근거 소실은 미검토.",
      "suggestion": "QUARANTINED 종결 행은 APPROVED/FAILED와 분리해 더 긴 보존 기간을 두거나 cleanup 제외를 검토. 최소한 삭제 전 격리 행을 별도 보존/아카이브하는 정책을 plan에서 결정."
    },
    {
      "severity": "major",
      "checklist_item": "새 상태/판별 분리 시 도메인 불변식 보호",
      "location": "docs/topics/EOS-FOLLOWUP-CLEANUP.md line 156-168 / payment-service/src/main/java/com/hyoguoo/paymentplatform/payment/domain/enums/PaymentEventStatus.java:45-50",
      "problem": "isCompensatableByFailureHandler를 두 메서드로 분리한 후 한쪽 정답표만 바뀌면 컴파일러가 못 막는다. 컨슈머 가드에서 QUARANTINED를 true로 바꾸면 늦은 APPROVED가 markPaymentAsDone not-retryable 예외 → DLQ silent(D7 사고, PITFALLS.md §21). 설계는 분리 이점만 강조하고 두 메서드의 QUARANTINED/EXPIRED 답이 의도적으로 동조돼야 한다는 교차 불변식을 보호하는 회귀 테스트 의무를 명시하지 않았다.",
      "evidence": "PaymentEventStatus.java:39-41 Javadoc이 QUARANTINED true 전환 위험을 명시. handle(PaymentConfirmResultUseCase.java:94)와 보상 가드(PaymentTransactionCoordinator.java:141) 두 호출처 공유. topic line 168의 9상태x2메서드 EnumSource는 '현재 값'만 고정, 교차 불변식 미보호.",
      "suggestion": "분리하되 두 메서드의 종결/QUARANTINED/EXPIRED 답 동조를 명시적으로 단언하는 회귀 테스트(또는 D7/D12 가드 통합 테스트 PaymentEosIntegrationTest #5 연계)를 plan 수락조건에 추가. 명명도 '컨슈머 결과반영 가능'과 '보상 정당'의 의미 차이가 드러나도록 확정."
    },
    {
      "severity": "minor",
      "checklist_item": "cleanup 쿼리 성능과 confirm 본류 경합",
      "location": "docs/topics/EOS-FOLLOWUP-CLEANUP.md line 191,193 / pg-service/src/main/resources/db/migration/V1__pg_schema.sql:21-23",
      "problem": "pg_inbox cleanup의 status IN(...) AND updated_at < threshold 필터에 updated_at 인덱스가 없다. V1 스키마는 ux_pg_inbox_order_id와 idx_pg_inbox_status(status 단일)만 보유. 종결 행 누적 시 updated_at 비교가 행별 평가 → confirm 본류 DB 경합 가능.",
      "evidence": "V1__pg_schema.sql:21-23 인덱스 목록. topic line 193은 'idx_pg_inbox_status 기존재로 풀스캔 회피'라 주장하나 status 인덱스만으로 updated_at 범위 조건은 커버 안 됨.",
      "suggestion": "복합 인덱스 (status, updated_at) 추가를 plan 마이그레이션(이미 V4 traceparent 추가 예정)에 합쳐 검토. 부하 측정은 non-goal이므로 인덱스 보강만."
    }
  ],

  "previous_round_ref": null,
  "delta": null,

  "unstuck_suggestion": null
}
```
