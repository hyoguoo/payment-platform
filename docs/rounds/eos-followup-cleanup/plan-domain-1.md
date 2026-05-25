# plan-domain-1

**Topic**: EOS-FOLLOWUP-CLEANUP
**Round**: 1
**Persona**: Domain Expert

## Reasoning

discuss에서 확정된 세 도메인 불변식(D-SPLIT-3 교차 불변식, dedupe cleanup 멱등 SoT 비파괴, traceparent 관측성 격리)이 모두 PLAN의 구체 태스크·수락조건으로 박혔음을 소스 교차검증으로 확인했다. D-SPLIT-3은 A-2가 단순 EnumSource(각 메서드 현재값 고정)를 넘어 `canApplyConfirmResult==canCompensateStock` 관계 단언(`isEqualTo`)을 QUARANTINED/EXPIRED에 명시해 D7 침묵 DLQ 드리프트를 빌드 RED로 잡고, A-3이 실제 두 호출처(`PaymentConfirmResultUseCase.java:94` 컨슈머 가드, `PaymentTransactionCoordinator.java:141` 보상 가드)를 동시 갱신 + grep 0건으로 D7 방어선 약화를 막는다. cleanup 삭제 조건은 두 태스크 모두 `expires_at < now`로 만료 사실 기록만 지우고 멱등 SoT(미만료 가드 행)는 보존하며, payment TTL≥retention 보존 정책이 작업군 C 헤더·교차표에 명문화됐다. critical/major 없음 → pass. 다만 E-3 layer 역방향 의존 미해소와 E-3/E-4 읽기 경로 누락이 traceparent 격리·회수 정확성을 흔들 minor 두 건은 plan-review에서 다음 라운드에 닫는 것을 권고한다.

## Domain risk checklist

- **discuss domain risk → 대응 태스크 존재**: YES. D-SPLIT-1/2/3(A-1/A-2/A-3), D-CLEAN-1~4(C/D 작업군), D-TRACE-1~3(E 작업군), discuss-domain-2 검토6 보존정책(작업군 C 헤더 + 교차표) 전부 매핑. 리스크→태스크 교차표(PLAN line 591-611)로 추적 가능.
- **중복 방지 체크가 필요 경로에 계획됨**: YES. dedupe 멱등은 신규가 아니라 기존(payment PK INSERT IGNORE / product expires_at>=NOW() 가드). cleanup은 그 가드의 유효창 밖 행만 삭제. traceparent는 INSERT IGNORE 최초기록 고정(E-3 수락조건).
- **재시도 안전성 검증 태스크**: YES(해당 범위). cleanup은 @Scheduled 다음 주기=재시도(L-1), traceparent는 best-effort 폴백(L-3). 명시 재시도 정책 없음이 적정 — 추가 검증 태스크 불요.
- **(추가) D7 침묵 DLQ 회귀 방어선 보존**: YES. A-2 교차 불변식이 관계 단언(isEqualTo) 형태 + A-3 grep 0건 + PaymentEosIntegrationTest 회귀 green 의무로 PLAN에서 강화됨(약화 없음).
- **(추가) 두 호출처 동시 갱신으로 D7 사고 재현 경로 방어**: YES. A-3가 두 호출처를 한 태스크에서 동시 교체 + `isCompensatableByFailureHandler` grep 0건을 수락조건으로 박아 "한쪽만 갱신" 표류를 구조적으로 차단.

## 도메인 관점 추가 검토

1. **[검증 OK] FOLLOW-5 두 호출처가 실제로 D7 가드와 보상 가드 단일 메서드 공유 — A-3 분해가 정확히 겨냥.**
   소스 확인: `PaymentConfirmResultUseCase.java:94`가 EOS 컨슈머 진입 가드(`if (!...isCompensatableByFailureHandler())`), `PaymentTransactionCoordinator.java:141`이 보상 가드(`eventCompensatable` → `compensateStockCacheGuarded`). enum Javadoc(PaymentEventStatus.java:39-41)이 "QUARANTINED를 true로 바꾸면 늦은 APPROVED가 markPaymentAsDone not-retryable → DLQ silent"를 명시 — PITFALLS §21과 일치. A-3 수락조건(grep 0건 + 두 호출처 교체 + PaymentEosIntegrationTest 회귀 green)이 정확히 이 두 지점을 동시 갱신한다. D7 방어선 약화 없음 확인.

2. **[검증 OK] D-SPLIT-3(A-2)가 EnumSource 한계를 넘어 관계 불변식을 명시 단언 — D7 드리프트를 빌드 RED로 잡는 형태.**
   A-2 스펙(PLAN line 84-109)은 `bothGuards_종결및격리상태_둘다false`에서 `assertThat(canApplyConfirmResult()).isEqualTo(canCompensateStock())`를 QUARANTINED/EXPIRED 포함 6종결상태에 적용하고, QUARANTINED/EXPIRED 단독 `isEqualTo` 단언 2건을 추가. 9상태×2메서드 EnumSource(A-1)는 각 메서드 현재값만 고정하므로 "두 메서드 답 관계"를 보호 못 한다는 discuss-domain-2 검토4 지적이 그대로 반영됐다. 한쪽만 true 드리프트 시 isEqualTo가 RED — D7 침묵 DLQ 1차 방어선이 PLAN에서 약화되지 않고 오히려 단언 형태로 구체화됨.

3. **[검증 OK] dedupe cleanup 삭제 조건이 "만료 사실 기록만 삭제"로 멱등 SoT 비파괴.**
   C-2/D-2 쿼리 모두 `DELETE ... WHERE expires_at < :now LIMIT :batchSize`. 소스 확인 — product `JdbcEventDedupeStore`(:32-40)는 `existsValid`/`recordIfAbsent` 모두 `expires_at >= NOW()` 가드 내장이라 cleanup이 만료 행을 먼저 지워도 미만료(유효) 가드 행 불변, 재고 이중차감 경로 없음. D-2 테스트 `deleteExpired_existsValid미만료행_불영향`(반환 0 + existsValid==true)이 이 SoT 비파괴를 직접 단언. payment `JdbcPaymentEventDedupeStore`(:26-56)는 만료가드 없는 PK INSERT IGNORE라 만료 행 삭제는 stale 행이 재수신을 잘못 중복판정하는 것을 막는 방향(개선 or 무영향). pg_inbox(reemit vs handleAbsent SoT)는 cleanup 대상에서 제거 — pg-service/src/main grep 0건 재확인.

4. **[검증 OK] 보존 기간(TTL ≥ Kafka retention) 명문화 확인.**
   작업군 C 헤더(PLAN line 173)에 "payment_event_dedupe cleanup 보존 기간은 Kafka retention(7일) 이상 유지(단축 금지). TTL(8d) > retention(7d) 불변식 아래에서만 만료 행 삭제가 재배달 멱등에 무해" 명시. 교차표(line 607)에 discuss-domain-2 검토6 MINOR → C-3 Javadoc + 보존정책 근거로 매핑. V2__payment_event_dedupe.sql expires_at TIMESTAMP + idx_expires_at 인덱스 실재 확인. discuss-domain-2의 유일 잔여 MINOR가 PLAN에 흡수됨.

5. **[검증 OK] traceparent 관측성 격리 — confirm 판정 비참여 + NULL 폴백이 회수를 막지 않음.**
   E-1 컬럼 `stored_traceparent VARCHAR(64) NULL`, topic §5/PLAN E-4는 NULL/형식오류 시 새 root span 폴백으로 회수 정상 완료를 수락조건화(E-4 `recoverPendingZombies_traceparent없음_새rootSpan폴백`, `_형식오류_폴백처리완료`). E-5 통합테스트가 NULL INSERT 성공 + 형식오류 폴백을 검증. 금액·상태·멱등 판정 경로에 stored_traceparent가 전혀 끼지 않음(별도 인덱스도 없음, 조회조건 아님). L-3 best-effort 정책이 "추적 끊김=관측성 손실일 뿐 비즈니스 무영향"을 못박음. 관측성 격리 보존 확인.

6. **[MINOR / plan-review 권고] E-3 layer 역방향 의존 미해소 — application 서비스가 infrastructure(OTel) 직접 호출 서술.**
   E-3는 `PgInboxPendingService`(application/service)가 `TraceparentExtractor.extractFromCurrentContext()`(infrastructure/trace)를 직접 호출해 인자 전달하도록 서술(PLAN line 493, 501). 이는 D-TRACE-2의 핵심 결정 "추출은 infrastructure에서 수행, application 서비스는 불투명 문자열만 전달"(topic line 280-281)을 위반한다 — application이 OTel 인프라에 결합되면 traceparent 격리(추적 백엔드 교체 시 application 무변경) 자체가 깨진다. architect 인라인 주석(E-3)이 이미 동일 긴장을 지적하며 (a) consumer/listener에서 추출해 인자 주입 또는 (b) port.out 추상 경유를 제안. 도메인 격리 가치(관측성 관심사가 결제 흐름 코드에 섞이지 않음)에 직결되나 동작·금전·멱등 무영향이라 minor. plan-review에서 추출 호출 계층을 (a)/(b) 중 하나로 확정 권고.

7. **[MINOR / plan-review 권고] E-3/E-4 stored_traceparent 읽기 경로가 산출물에 누락 — 회수 정확성의 데이터 출처 불명.**
   E-4(폴링 회수 시 부모 복원)는 `stored_traceparent`를 읽어야 하나 E-3 산출물에는 쓰기(insertPending 파라미터)만 있고 읽기 시그니처(findById 확장 또는 전용 조회)가 빠졌다. architect 인라인 주석 2건이 동일 지적. 또한 §5에서 "관측성 전용, 비즈니스 판정 비참여"로 못박은 컬럼을 domain `PgInbox` 엔티티에 "필요 시" 넣는 것(E-3 line 502)은 순수 도메인에 관측성 관심사를 섞을 위험 — infrastructure entity/조회 DTO에 가두는 쪽 우선. 읽기 경로가 명시되지 않으면 implementer가 domain 엔티티에 임의로 필드를 박아 격리가 깨질 수 있다. 회수 추적 정확성의 근거 데이터 경로이므로 plan-review에서 읽기 계층·배치를 E-3 또는 E-4에 명시 권고. 비즈니스 판정 무참여라 minor.

## Findings

- **MINOR** — E-3: `PgInboxPendingService`(application/service)가 `TraceparentExtractor`(infrastructure/trace)를 직접 호출하도록 서술돼 D-TRACE-2의 "application은 불투명 문자열만 전달, OTel 추출은 infrastructure 격리" 결정을 위반한다. 추적 격리(관측성 관심사가 결제 흐름 application 코드에 결합되지 않음)가 도메인 격리 가치이나 동작·금전·멱등 무영향. plan-review에서 추출 호출 계층을 consumer/listener(infrastructure) 또는 port.out 추상으로 확정 권고.
- **MINOR** — E-3/E-4: 회수 시 부모 추적 복원에 필요한 `stored_traceparent` 읽기 경로(조회 시그니처)가 산출물에 누락. domain `PgInbox` 엔티티에 "필요 시" 필드 추가(E-3 line 502)는 관측성 전용 컬럼을 순수 도메인에 섞을 위험. 회수 추적 정확성의 데이터 출처이나 비즈니스 판정 비참여라 minor. plan-review에서 읽기 계층·배치 명시 권고.

(critical/major 0. discuss 도메인 불변식 3종 + D7 방어선 모두 PLAN에 정착·강화 확인. 위 2 minor는 traceparent layer 격리 정밀화로 다음 라운드 흡수 권고.)

## JSON
```json
{
  "stage": "plan",
  "persona": "domain-expert",
  "round": 1,
  "task_id": null,

  "decision": "pass",
  "reason_summary": "discuss 확정 도메인 불변식 3종(D-SPLIT-3 교차 불변식, dedupe cleanup 멱등 SoT 비파괴+TTL≥retention 보존정책, traceparent 관측성 격리)이 모두 구체 태스크·수락조건으로 정착됨을 소스로 교차검증. A-2가 isEqualTo 관계 단언으로 D7 침묵 DLQ 드리프트를 빌드 RED로 잡고, A-3가 실제 두 호출처(컨슈머 가드 line94 + 보상 가드 line141) 동시 갱신+grep0건으로 D7 방어선 강화. cleanup은 expires_at<now 만료 사실 기록만 삭제. critical/major 0 → pass. E-3 layer 역방향 의존·읽기경로 누락 2 minor는 plan-review 권고.",

  "checklist": {
    "source": "_shared/checklists/plan-ready.md",
    "items": [
      {
        "section": "domain risk",
        "item": "discuss에서 식별된 domain risk가 각각 대응 태스크를 가짐 (멱등성 검증 테스트, 상태 전이 테스트 등)",
        "status": "yes",
        "evidence": "PLAN 리스크→태스크 교차표 line 591-611. D-SPLIT-1/2/3→A-1/A-2/A-3, D-CLEAN-1~4→C/D, D-TRACE-1~3→E, discuss-domain-2 검토6→작업군 C 헤더 line 173. A-2가 D-SPLIT-3 교차 불변식(isEqualTo 관계 단언, QUARANTINED/EXPIRED 동조)을 PLAN line 84-109로 구체화."
      },
      {
        "section": "domain risk",
        "item": "중복 방지 체크(예: existsByOrderId)가 필요한 경로에 계획됨",
        "status": "yes",
        "evidence": "dedupe 멱등은 기존(payment PK INSERT IGNORE JdbcPaymentEventDedupeStore.java:26-56, product expires_at>=NOW() 가드 JdbcEventDedupeStore.java:32-40). cleanup(C-2/D-2 expires_at<now)은 가드 유효창 밖 행만 삭제 → SoT 비파괴. D-2 테스트 deleteExpired_existsValid미만료행_불영향이 직접 단언. traceparent INSERT IGNORE 최초기록 고정(E-3 수락조건)."
      },
      {
        "section": "domain risk",
        "item": "재시도 안전성 검증 태스크 존재 (재시도 정책이 있는 경우만)",
        "status": "yes",
        "evidence": "cleanup=@Scheduled fixedDelay 다음 주기 재시도(L-1, C-3/D-3 cleanup_예외시_전파하지않음 테스트), traceparent=best-effort 폴백(L-3, E-4 폴백 테스트 2건). 명시 재시도 정책 없음이 적정 — 별도 검증 태스크 불요."
      },
      {
        "section": "task quality",
        "item": "모든 태스크가 객관적 완료 기준을 가짐",
        "status": "yes",
        "evidence": "A-3 grep 0건+PaymentEosIntegrationTest green, C-2/D-2 반환값·잔존수 단언, E-5 trace-id 연속성. 도메인 핵심 태스크 전부 관찰 가능 수락조건 보유."
      },
      {
        "section": "domain risk",
        "item": "traceparent layer 격리(D-TRACE-2)가 application→infrastructure 역방향 의존 없이 계획됨",
        "status": "no",
        "evidence": "E-3 PLAN line 493,501이 PgInboxPendingService(application)에서 TraceparentExtractor(infrastructure) 직접 호출 서술 — topic line 280-281 'application은 문자열만 전달' 위반. architect 인라인 주석도 동일 지적. 동작·금전·멱등 무영향이라 minor."
      },
      {
        "section": "task quality",
        "item": "stored_traceparent 읽기 경로가 회수 태스크 산출물에 명시됨",
        "status": "no",
        "evidence": "E-4 부모 복원에 필요한 읽기 시그니처(findById 확장/전용 조회)가 E-3/E-4 산출물에서 누락. domain PgInbox '필요 시' 필드(E-3 line 502)는 관측성 컬럼을 순수 도메인에 섞을 위험. 비즈니스 판정 비참여라 minor."
      }
    ],
    "total": 6,
    "passed": 4,
    "failed": 2,
    "not_applicable": 0
  },

  "scores": {
    "traceability": 0.92,
    "decomposition": 0.88,
    "ordering": 0.86,
    "specificity": 0.85,
    "risk_coverage": 0.84,
    "mean": 0.87
  },

  "findings": [
    {
      "severity": "minor",
      "checklist_item": "traceparent layer 격리(D-TRACE-2)가 역방향 의존 없이 계획됨",
      "location": "docs/EOS-FOLLOWUP-CLEANUP-PLAN.md E-3 (line 490-507) / pg-service/.../application/service/PgInboxPendingService.java",
      "problem": "E-3가 PgInboxPendingService(application/service)에서 TraceparentExtractor(infrastructure/trace)를 직접 호출해 인자 전달하도록 서술(line 493,501). topic §D-TRACE-2(line 280-281)는 'OTel 추출은 infrastructure, application은 불투명 문자열만 전달'로 명시 — application이 OTel 인프라에 직접 결합되면 추적 격리(백엔드 교체 시 application 무변경)가 깨진다.",
      "evidence": "PLAN line 493 'PgInboxPendingService.insertPendingAndPublish는 TraceparentExtractor.extractFromCurrentContext() 결과를 인자로 전달', line 501 산출물에 PgInboxPendingService가 TraceparentExtractor 호출. E-3 architect 인라인 주석(line 492)이 동일 layer 역방향 의존 긴장을 이미 지적하며 (a) consumer/listener 추출 또는 (b) port.out 추상을 권고. PgInboxChannel이 Context.current()를 잡는 계층이 infrastructure임을 INTEGRATIONS/CONFIRM-FLOW §17로 확인.",
      "suggestion": "plan-review에서 추출 호출 계층을 consumer(infrastructure/messaging) 또는 InboxReadyEventHandler(infrastructure/listener)로 옮겨 application 메서드는 String storedTraceparent 인자만 받게 하거나, port.out 추상(TraceTokenProvider) 경유로 확정. application이 TraceparentExtractor를 import하지 않도록 산출물·서술 수정."
    },
    {
      "severity": "minor",
      "checklist_item": "stored_traceparent 읽기 경로가 회수 태스크 산출물에 명시됨",
      "location": "docs/EOS-FOLLOWUP-CLEANUP-PLAN.md E-3 (line 498-504) / E-4 (line 511-520)",
      "problem": "E-4(폴링 회수 시 부모 복원)는 stored_traceparent를 읽어야 하나 E-3 산출물에는 쓰기(insertPending 파라미터)만 있고 읽기 시그니처(findById 확장/전용 조회)가 빠졌다. §5에서 '관측성 전용, 비즈니스 판정 비참여'로 못박은 컬럼을 domain PgInbox 엔티티에 '필요 시'(E-3 line 502) 넣으면 순수 도메인에 관측성 관심사가 섞일 수 있다.",
      "evidence": "PLAN E-3 산출물 line 498-499는 insertPending 쓰기 시그니처만 변경, 읽기 경로 없음. E-3 line 502 'PgInbox.java 필드 추가(필요 시)' + architect 인라인 주석 2건(line 503,504)이 읽기 경로 누락과 domain 배치 위험을 지적. topic §5 line 252 'stored_traceparent는 confirm 비즈니스 판정에 일절 참여하지 않는다'.",
      "suggestion": "plan-review에서 stored_traceparent 읽기 경로(PgInboxRepository 조회 시그니처)를 E-3 또는 E-4에 명시하고, 컬럼을 infrastructure entity(PgInboxEntity) 또는 조회 DTO에 가두어 domain PgInbox 순수성을 보존. domain에 불가피하게 둘 경우 상태 전이·멱등·금액 검증 어디에도 참여 안 함을 보장(읽기 전용 메타) 명시."
    }
  ],

  "previous_round_ref": null,
  "delta": null,

  "unstuck_suggestion": null
}
```
