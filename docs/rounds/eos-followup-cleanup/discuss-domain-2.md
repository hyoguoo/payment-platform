# discuss-domain-2

**Topic**: EOS-FOLLOWUP-CLEANUP
**Round**: 2
**Persona**: Domain Expert

## Reasoning

R1 critical(pg_inbox 종결 행 = confirm 재배달 멱등 SoT 파괴)은 사용자가 pg_inbox cleanup을 범위에서 전면 제거하면서 소멸했다 — 코드/문서 교차 검증상 pg-service에는 어떤 deleteExpired/cleanup 스케줄러도 신설되지 않고 traceparent 컬럼 작업만 남는다(topic line 127 "cleanup 워커 없음", line 239 "pg_inbox에 어떤 백그라운드 삭제도 가하지 않는다", grep 0건 확인). 남은 두 dedupe cleanup은 재수신 안전성이 행의 물리적 존재가 아니라 `expires_at` 가드 자체에 내장돼 있어 만료 행 삭제가 멱등 SoT를 건드리지 않음을 코드로 확정했다(product `existsValid`/`recordIfAbsent`는 `expires_at >= NOW()` 미만 행을 "없는 것"으로 간주, TTL 8d > Kafka retention 7d). R1 major(D-SPLIT-3 교차 불변식)는 신설 결정으로 해소됐고 D7 침묵 DLQ 회귀를 단언 형태로 막을 수 있다. critical 없음 → pass.

## Domain risk checklist

- **멱등성 전략 결정됨**: YES (R1 PARTIAL → 해소). 두 dedupe DELETE는 조건 멱등(`expires_at < now`). 핵심 — 재수신 멱등은 만료 행의 존재가 아니라 가드 산식에 있다. product는 `recordIfAbsent`가 만료 행을 만나면 자체적으로 단건 DELETE 후 INSERT(새 TTL)로 firstSeen=true를 돌려주므로(JdbcEventDedupeStore.java:67-79), 일괄 cleanup이 만료 행을 먼저 지워도 결과 동일. payment는 PK(event_uuid) INSERT IGNORE라 만료 행이 남아 있으면 오히려 재수신을 잘못 "중복"으로 막을 수 있는 구조 — 만료 후 cleanup은 멱등을 개선하거나 무영향. pg_inbox(재배달 시 reemit vs handleAbsent 분기를 가르는 SoT)와 본질적으로 다르다.
- **장애 시나리오 최소 3개**: YES (L-1~L-4, 4개). R1에서 누락 지적했던 "종결 행 삭제 후 retention 내 재배달" 시나리오는 pg_inbox cleanup 제거로 더 이상 적용 대상이 아니다. 남은 cleanup 대상(dedupe)에는 동일 사고 경로가 없다.
- **재시도 정책**: YES. `@Scheduled(fixedDelay)` 다음 주기 = 재시도, best-effort traceparent 무재시도. 적정.
- **PII/민감정보**: YES. traceparent는 불투명 식별자, 금액/카드/고객정보 없음. 변동 없음.
- **(추가) 새 도메인 리스크 유입 여부**: 없음. cleanup 범위 축소(pg 제외)는 리스크를 줄이는 방향이며 새 상태/전이/외부호출을 추가하지 않는다.

## 도메인 관점 추가 검토

1. **[R1 critical 해소 확인] pg_inbox cleanup 전면 제거 — 흔적 없이 사라짐.**
   topic의 in-scope 표(line 110-112)는 cleanup 대상을 payment `payment_event_dedupe` + product `stock_commit_dedupe` 둘로만 명시하고, pg-service 행(line 112)은 `stored_traceparent` 컬럼 + 회수 시 부모 추적 복원만 받는다. 모듈 경계(line 127)는 pg-service에 "cleanup 워커 없음"을 못박았고, §5 호환성(line 239)은 "pg-service는 본 cleanup 대상이 아니므로 confirm 회수 SoT(pg_inbox)에 어떤 백그라운드 삭제도 가하지 않는다"로 명시했다. 코드 grep(`deleteExpired|DELETE FROM pg_inbox|cleanup` in pg-service/src/main, 테스트 제외) 0건 — pg_inbox 종결 행을 지우는 경로가 신설되지 않음을 확인. R1 critical(검토1)의 돈 새는 경로(`PgConfirmService` reemit 우회 → handleAbsent PENDING 재신설 → 벤더 confirm 재호출)는 cleanup이 종결 행을 만지지 않으므로 발생할 수 없다. **소멸 확인.**

2. **[R1 major 해소 확인] QUARANTINED pg_inbox 보정 근거 소실(검토2)도 동반 소멸.**
   admin 격리 보정 근거(`storedStatusResult`/`reasonCode`) 소실 우려는 pg_inbox 종결 행을 자동 삭제할 때만 성립한다. pg cleanup이 제거됐으므로 격리 행은 보존된다. **소멸 확인.**

3. **[R1 minor 해소 확인] updated_at 인덱스 부재(검토4)도 소멸.**
   R1 minor는 pg_inbox cleanup의 `status IN(...) AND updated_at < threshold` 필터에 인덱스가 없다는 지적이었다. 남은 두 dedupe DELETE는 `WHERE expires_at < :now`이고, 두 테이블 모두 `INDEX idx_expires_at (expires_at)`를 보유함을 schema로 확인(payment V2__payment_event_dedupe.sql, product stock_commit_dedupe). 인덱스 적중하므로 풀스캔 우려 없음. **소멸 확인.**

4. **[R1 major 해소 확인] D-SPLIT-3 교차 불변식 회귀 테스트 신설(검토3).**
   R2가 D-SPLIT-3(topic line 165-167)를 신설해 두 메서드(`canApplyConfirmResult`/`canCompensateStock`)가 QUARANTINED/EXPIRED 및 종결 상태에서 "둘 다 false로 동조"함을 명시 단언하는 교차 불변식 테스트를 의무화했고, 수락조건(line 272)·검증계획(line 289)·장애시나리오 L-2(line 255)에 모두 박았다. 현 enum의 `isCompensatableByFailureHandler()`(PaymentEventStatus.java:45-50, 두 호출처 공유)와 Javadoc(line 39-41)이 명시하는 D7 사고 경로(QUARANTINED를 컨슈머에서 true로 드리프트 → 늦은 APPROVED가 markPaymentAsDone not-retryable 예외 → DLQ silent, PITFALLS §21)를 정확히 겨냥한다. 9상태×2메서드 EnumSource는 각 메서드의 현재 값만 고정하므로 "두 메서드 답 관계"를 보호 못 한다는 R1 지적이 그대로 반영됐다. **이 형태(관계 단언)는 D7 침묵 DLQ를 빌드 단계에서 실제로 막는다 — 한쪽만 드리프트하면 동조 단언이 RED.** 해소 확인.

5. **[검증 OK] 남은 cleanup의 "만료 직전 행 삭제 + 같은 키 재수신" race가 돈 새는 경로를 열지 않음.**
   - product: `recordIfAbsent`(JdbcEventDedupeStore.java:67-79)는 호출자 `StockCommitUseCase.commit`의 `@Transactional`(line 62-72) 안에서 `SQL_DELETE_EXPIRED(event_uuid AND expires_at < NOW())` 단건 삭제 후 INSERT IGNORE를 수행한다. 일괄 cleanup이 동일 만료 행을 먼저 지워도, recordIfAbsent의 단건 DELETE는 0 row affected가 될 뿐이고 INSERT IGNORE는 동일하게 1 row(firstSeen=true)를 돌려준다 — 양쪽 다 "만료 행만" 대상이라 유효(미만료) dedupe 가드를 절대 건드리지 않는다. 재고 이중 차감 경로 없음.
   - payment: `markIfAbsent`(JdbcPaymentEventDedupeStore.java:48-56)는 PK(event_uuid) 단순 INSERT IGNORE. cleanup이 만료 행을 지우는 것은 오히려 stale 행이 같은 event_uuid 재수신을 잘못 중복 판정하는 것을 막는 방향이다. 단 이 정합성은 TTL(8d) > Kafka retention(7d)에 의존 — 만료 시점엔 메시지가 이미 retention 밖이라 재배달 자체가 불가. cleanup이 이 가정을 바꾸지 않음(만료 조건만 삭제). race로 돈 새는 경로 없음.

6. **[MINOR / 검증 권고] payment dedupe 행 만료-cleanup 정합성은 "TTL ≥ retention" 가정에 묶여 있음 — plan에서 명문화.**
   payment `markIfAbsent`는 product와 달리 만료 비교 가드(`expires_at >= NOW()`) 없이 PK INSERT IGNORE만 한다. 따라서 "만료 행이 아직 안 지워졌는데 같은 event_uuid가 재배달"되면 신규를 잘못 중복(0 row)으로 처리할 이론적 창이 존재한다. 현재는 TTL=8d > Kafka retention=7d라 만료 후 재배달이 구조적으로 불가능하므로 무해하고, cleanup은 이 불변식을 바꾸지 않는다(만료 조건만 삭제, 미만료 행 불변). 도메인 사고 경로는 아니나, cleanup 도입 plan에서 "payment dedupe cleanup은 TTL≥retention 불변식 하에서만 안전(보존 기간 단축 금지)"을 보존 정책 근거로 1줄 남겨 두면 향후 TTL 단축 변경이 재배달 멱등을 깨는 회귀를 예방한다. (FOLLOW-2 cleanup 자체 동작에는 영향 없음 → minor.)

## Findings

- **MINOR** — payment `payment_event_dedupe`는 만료 비교 가드 없는 PK INSERT IGNORE(JdbcPaymentEventDedupeStore.java:48-56)라 재수신 멱등이 "TTL(8d) ≥ Kafka retention(7d)" 불변식에 의존한다. cleanup은 이 불변식을 바꾸지 않으나, plan에 "cleanup 보존 기간은 retention 이상 유지(단축 금지)"를 보존 정책 근거로 명문화하면 향후 TTL 단축 회귀를 예방(검토6). product는 `expires_at >= NOW()` 가드 내장이라 무관.

(R1 critical 1 + major 2 + minor 1 = 전부 해소/소멸. 신규 critical/major 없음.)

## JSON
```json
{
  "stage": "discuss",
  "persona": "domain-expert",
  "round": 2,
  "task_id": null,

  "decision": "pass",
  "reason_summary": "R1 critical(pg_inbox 종결 행 cleanup = confirm 재배달 멱등 SoT 파괴)이 pg_inbox cleanup 전면 제거로 소멸(코드 grep 0건 + topic §5/§2-3 명시). R1 major 2건(QUARANTINED pg_inbox 보정 근거 소실 동반 소멸, D-SPLIT-3 교차 불변식 신설로 해소) + minor 1건(updated_at 인덱스, pg cleanup 제거로 소멸) 모두 해소. 남은 두 dedupe cleanup은 재수신 멱등이 expires_at 가드 자체에 내장돼 멱등 SoT를 건드리지 않음을 코드로 확인. critical/major 0 → pass.",

  "checklist": {
    "source": "_shared/checklists/discuss-ready.md",
    "items": [
      {
        "section": "domain risk",
        "item": "멱등성 전략이 결정됨 (idempotency key 소스/수명/충돌 처리)",
        "status": "yes",
        "evidence": "topic line 110-112,239,246. cleanup 대상이 dedupe 둘로 한정. 재수신 멱등은 만료 행 존재가 아니라 expires_at 가드 산식에 내장 — product JdbcEventDedupeStore.java:32-79(existsValid/recordIfAbsent 모두 expires_at>=NOW() 기준), payment TTL 8d>retention 7d. pg_inbox(reemit vs handleAbsent 분기 SoT)는 cleanup 대상에서 제거됨(grep 0건)."
      },
      {
        "section": "domain risk",
        "item": "장애 시나리오 최소 3개 식별됨",
        "status": "yes",
        "evidence": "topic line 254-257 L-1~L-4. R1에서 누락 지적한 '종결 행 삭제 후 retention 내 재배달'은 pg_inbox cleanup 제거로 적용 대상 소멸."
      },
      {
        "section": "domain risk",
        "item": "재시도 정책이 정의됨",
        "status": "yes",
        "evidence": "topic line 261-262. @Scheduled fixedDelay 다음 주기=재시도, best-effort traceparent 무재시도. 적정."
      },
      {
        "section": "domain risk",
        "item": "PII/민감정보 로깅·저장·전송 경로 검토됨",
        "status": "yes",
        "evidence": "topic line 266. W3C traceparent 불투명 식별자, 민감정보 없음. 변동 없음."
      },
      {
        "section": "design decisions",
        "item": "전체 결제 흐름과의 호환성이 검토됨",
        "status": "yes",
        "evidence": "topic line 238-240 §5. R1에서 비었던 'pg_inbox 종결 행 삭제 후 재배달 absent 분기 진입'이 cleanup 제거로 해소. 남은 dedupe cleanup은 expires_at 경과 행만 삭제 → 진행 중 결제/미만료 행 무영향 명시, 코드로 확인."
      }
    ],
    "total": 5,
    "passed": 5,
    "failed": 0,
    "not_applicable": 0
  },

  "scores": {
    "clarity": 0.90,
    "completeness": 0.88,
    "risk": 0.86,
    "testability": 0.85,
    "fit": 0.88,
    "mean": 0.87
  },

  "findings": [
    {
      "severity": "minor",
      "checklist_item": "멱등성 전략이 결정됨",
      "location": "docs/topics/EOS-FOLLOWUP-CLEANUP.md line 188 / payment-service/src/main/java/com/hyoguoo/paymentplatform/payment/infrastructure/dedupe/JdbcPaymentEventDedupeStore.java:48-56",
      "problem": "payment payment_event_dedupe는 product와 달리 만료 비교 가드(expires_at>=NOW()) 없이 PK(event_uuid) INSERT IGNORE만 수행한다. 따라서 만료 행이 cleanup 전이고 같은 event_uuid가 재배달되면 신규를 중복(0 row)으로 잘못 판정할 이론적 창이 있다. 현재는 TTL(8d)>Kafka retention(7d)이라 만료 후 재배달이 구조적으로 불가능해 무해하고 cleanup은 이 불변식을 바꾸지 않으나(만료 조건만 삭제), 보존 정책이 명문화돼 있지 않으면 향후 TTL/보존 기간 단축이 재배달 멱등을 깰 수 있다.",
      "evidence": "JdbcPaymentEventDedupeStore.java:26-29 INSERT IGNORE(만료 가드 없음) vs product JdbcEventDedupeStore.java:32-40(SQL_EXISTS_VALID/SQL_DELETE_EXPIRED 모두 expires_at 기준). PaymentConfirmResultUseCase.java:49 STOCK_COMMITTED_TTL=8d, V2__payment_event_dedupe.sql TTL 주석 'Kafka retention 7d + 버퍼 1d'. cleanup 대상은 expires_at<now 행뿐(topic line 188).",
      "suggestion": "FOLLOW-2 cleanup plan에 'payment dedupe 보존 기간은 Kafka retention 이상 유지(단축 금지) — 그래야 만료 행이 retention 밖 메시지에만 대응돼 cleanup이 재배달 멱등을 깨지 않음'을 보존 정책 근거 1줄로 명문화. 코드 변경 불요, cleanup 동작 자체 영향 없음."
    }
  ],

  "previous_round_ref": "docs/rounds/eos-followup-cleanup/discuss-domain-1.md",
  "delta": [
    {
      "r1_finding": "CRITICAL — pg_inbox 종결 행 cleanup이 confirm 재배달 멱등 SoT 파괴(reemit 우회 → PENDING 재신설 → 벤더 confirm 재호출)",
      "status": "resolved",
      "evidence": "pg_inbox cleanup이 범위에서 전면 제거(topic line 110-112 cleanup 대상=payment/product dedupe 둘, line 127 'pg-service cleanup 워커 없음', line 239 'pg_inbox에 어떤 백그라운드 삭제도 가하지 않는다'). pg-service/src/main grep(deleteExpired|DELETE FROM pg_inbox|cleanup) 0건. 종결 행을 만지는 경로 부재 → 돈 새는 경로 발생 불가."
    },
    {
      "r1_finding": "MAJOR — QUARANTINED pg_inbox 종결 행 자동 삭제 시 admin 격리 보정 근거(storedStatusResult/reasonCode) 소실",
      "status": "resolved",
      "evidence": "pg cleanup 제거로 격리 행 보존. 자동 삭제 경로 자체가 사라져 우려 소멸."
    },
    {
      "r1_finding": "MAJOR — FOLLOW-5 분리 후 두 가드 정답표 드리프트 → D7 침묵 DLQ 재현, 교차 불변식 회귀 테스트 의무 부재",
      "status": "resolved",
      "evidence": "D-SPLIT-3 신설(topic line 165-167) — canApplyConfirmResult/canCompensateStock의 QUARANTINED/EXPIRED/종결 답 '둘 다 false 동조'를 명시 단언하는 교차 불변식 테스트를 수락조건(line 272)·검증계획(line 289)·L-2(line 255)에 의무화. PaymentEventStatus.java:45-50 + Javadoc line 39-41이 겨냥하는 D7 사고(PITFALLS §21)를 빌드 단계 RED로 차단하는 형태로 적정."
    },
    {
      "r1_finding": "MINOR — pg_inbox cleanup의 status IN(...) AND updated_at<threshold 필터에 인덱스 부재(풀스캔 → confirm 본류 경합)",
      "status": "resolved",
      "evidence": "pg_inbox cleanup 제거로 소멸. 남은 두 dedupe DELETE는 WHERE expires_at<:now이고 두 테이블 모두 INDEX idx_expires_at(expires_at) 보유(V2__payment_event_dedupe.sql, stock_commit_dedupe schema) → 인덱스 적중."
    }
  ],

  "unstuck_suggestion": null
}
```
