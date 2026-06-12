# discuss-domain-1

**Topic**: TIME-MODEL-AND-EXPIRY
**Round**: 1
**Persona**: Domain Expert

## Reasoning
시간 표준을 `LocalDateTime`→`Clock`+`Instant`로 수렴하는 설계는 만료/정합/멱등 경로의 리스크를 대체로 인식(F1~F5, R1~R4)했고 메커니즘 무변경(NG1~NG6) 가드도 적절하다. 그러나 D3의 핵심 메커니즘 주장(`hibernate.jdbc.time_zone=UTC` 하나로 모든 시각 I/O가 UTC 일관)이 실제 소스와 어긋난다 — dedupe 경로는 raw JDBC + `Timestamp.from(Instant)`라 `hibernate.jdbc.time_zone` 지배를 받지 않고, `approvedAt`(PG 승인 시각, 돈 기록 앵커)은 현재 `OffsetDateTime.parse().toLocalDateTime()`로 offset을 버리는데 이 의미 변화가 설계에 누락됐다. 두 항목 모두 돈/멱등 정확성에 직결되어 plan 진입 전 명문화가 필요하다.

## Domain risk checklist
- [no] 멱등성 전략이 결정됨 (idempotency key 소스/수명/충돌) — TTL 값·키 소스는 NG3로 보존 결정. 그러나 dedupe `received_at`/`expires_at`의 시각 *I/O 경계*가 D3 메커니즘으로 커버되지 않음(아래 1).
- [yes] 장애 시나리오 최소 3개 식별됨 — F1(컨테이너 TZ 만료 오판), F3(정합 스캐너 조기 복원→이중 승인), F4(DB 시각 해석), F5(스케줄러 키 전환 중 만료 비활성) 등 시각 전환 맥락의 시나리오 5개 식별. 요구 기준 충족.
- [yes] 재시도 정책 — 신규 재시도 경로 없음. 기존 패턴 유지 명시(§5). 적용.
- [n/a] PII — 새 민감정보 도입 없음(시각 데이터만). 명시됨(§5).

추가 점검:
- [no] 돈 기록 앵커(`approvedAt`)의 타입/의미 보존 — `OffsetDateTime→LocalDateTime`(offset drop)→`Instant` 전환 시 의미 변화 미검토(아래 2).
- [partial] 만료/복원 cutoff의 TZ 결정성 — D1/D3 의도는 옳으나 payment 엔티티는 pg와 달리 명시적 `ZoneOffset.UTC` 변환이 없어 `hibernate.jdbc.time_zone` 단일 의존(아래 1·3).

## 도메인 관점 추가 검토

1. **D3 메커니즘 과대주장 — dedupe 경로는 `hibernate.jdbc.time_zone` 비적용** (`JdbcPaymentEventDedupeStore.java:53-61,71-76`, `V2__payment_event_dedupe.sql:13-14`). dedupe는 Hibernate가 아닌 `NamedParameterJdbcTemplate` + `Timestamp.from(Instant)`로 `TIMESTAMP` 컬럼에 쓴다. `Timestamp.from`이 만드는 `java.sql.Timestamp`는 JDBC 드라이버가 세션/JVM TZ로 해석하므로, `hibernate.jdbc.time_zone=UTC`(Hibernate 매핑 경로 한정)는 이 경로를 지배하지 못한다. D3은 "엔티티 매핑+규약 고정만으로 일관"이라 주장하지만, payment 안에서 만료(`payment_event` DATETIME, Hibernate)와 dedupe(`payment_event_dedupe` TIMESTAMP, raw JDBC)가 서로 다른 변환 규칙을 타게 된다. dedupe `expires_at < now` 비교가 어긋나면 TTL 윈도우가 좁아지거나(조기 삭제→재배달 중복 처리) 넓어진다 — F2가 경계한 바로 그 멱등 사고. NG3은 "값/의미 보존"을 약속하나, 보존을 보장하는 *메커니즘*은 D3이 커버하지 못한다.

2. **`approvedAt` 돈 기록 앵커의 의미 변화 누락** (`PaymentConfirmResultUseCase.java:230` `OffsetDateTime.parse(approvedAtRaw).toLocalDateTime()`, `PaymentEvent.java:97-111` `done(approvedAt,...)`). 벤더가 회신한 승인 시각은 현재 offset을 버린 wall-clock `LocalDateTime`으로 저장된다(`approved_at DATETIME(6)`). 이를 `Instant`로 전환하면 "offset 버린 wall-clock"과 "절대 시점 UTC"는 **다른 값**이다. 비-UTC offset(예: NicePay `+09:00`)을 그대로 `Instant`로 해석하면 승인 시각이 9시간 어긋나 정산/감사 기록이 틀어진다. 설계 어디에도 `approvedAt`만은 벤더 offset을 살려 `Instant`로 정규화해야 한다는 결정이 없다. PITFALLS §13(NicePay paidAt offset)이 정확히 이 경계를 다뤘는데 본 설계가 이를 시각 전환 맥락에서 재검토하지 않았다.

3. **payment 엔티티 변환 경계가 pg와 비대칭** (`PgInboxEntity.java:90-91,105-106` 등은 `LocalDateTime.ofInstant(now, ZoneOffset.UTC)` 명시 / payment `PaymentEventEntity.java:64-77`은 `LocalDateTime` 직접 매핑). pg는 코드 레벨에서 UTC 변환을 못박아 TZ 무관하다. payment를 `Instant` 매핑+`hibernate.jdbc.time_zone=UTC`로만 가면 변환이 설정 프로퍼티 1개에 묶여, 누락/오버라이드 시 조용히 어긋난다. F1/F4가 위험은 짚었으나, "pg 방식(명시 변환)으로 통일할지 vs Hibernate 프로퍼티에 의존할지"라는 결정이 D3에 없다 — plan에서 갈릴 설계 분기인데 discuss에서 미확정.

4. **F3 이중 승인 윈도우의 정량 미검토(경미)** (`PaymentReconciler.java:55` `now.minusSeconds(inFlightTimeoutSeconds)`). 시계 교체 자체는 절대 시점을 바꾸지 않으므로 cutoff 값은 동일하다는 점은 맞다. 다만 전환 중 *부분 배포*(일부 인스턴스 구 LocalDateTime/시스템 TZ, 일부 신 Instant/UTC)에서 cutoff 해석이 인스턴스마다 달라 IN_PROGRESS 조기 복원→재confirm→PG 재호출이 가능하다. F1이 "비-UTC 컨테이너"는 짚었으나 "전환 중 혼재 배포" 시나리오(인스턴스 간 시각 해석 불일치)는 명시 안 됨. 멱등 layer(PG/redis token)가 일반 차단하므로 경미.

5. **D6 연쇄 정책 — silent loss/신규 race 없음(확인)** (`PaymentEvent.java:137-144` expire READY 가드, `:180` resetToReady IN_PROGRESS 가드). IN_PROGRESS 직접 만료 금지 + 정합 스캐너 복원 후 만료 2단 연쇄는 "실제 승인됐는데 만료" 위험을 회피하는 의도된 설계로 명문화가 타당하다. 두 스케줄러가 별개 프로퍼티(`reconciler.*` vs `payment-expiration.*`)로 독립 운영되어 새 race window를 만들지 않는다. 도메인 가드가 코드로 존재함을 확인.

## Findings
- **major #1** — D3 메커니즘이 raw-JDBC dedupe 경로를 커버하지 못함. dedupe TTL 윈도우 오차→중복 처리(멱등 사고). 위 검토 1.
- **major #2** — `approvedAt`(돈 기록 앵커) offset-drop→Instant 의미 변화 미결정. 정산/감사 시각 틀어짐 위험. 위 검토 2.
- **minor #3** — payment 엔티티 UTC 변환을 pg식 명시 변환 vs Hibernate 프로퍼티 의존 중 어느 쪽으로 통일할지 미결정. 위 검토 3.
- **minor #4** — 전환 중 혼재 배포 시 인스턴스 간 시각 해석 불일치로 F3 이중 승인 윈도우. 위 검토 4.

## JSON
```json
{
  "stage": "discuss",
  "persona": "domain-expert",
  "round": 1,
  "task_id": null,

  "decision": "revise",
  "reason_summary": "장애 시나리오·멱등 TTL 보존 의도는 식별됐으나, D3 'hibernate.jdbc.time_zone=UTC' 메커니즘이 raw-JDBC dedupe 경로를 커버 못 하고 approvedAt(돈 기록) offset-drop→Instant 의미 변화가 미결정 — 둘 다 돈/멱등 정확성 직결이라 plan 진입 전 명문화 필요.",

  "checklist": {
    "source": "_shared/checklists/discuss-ready.md",
    "items": [
      {
        "section": "domain risk",
        "item": "멱등성 전략이 결정됨 (idempotency key 소스/수명/충돌 처리)",
        "status": "no",
        "evidence": "JdbcPaymentEventDedupeStore.java:53-61 Timestamp.from(Instant) + V2__payment_event_dedupe.sql:13-14 TIMESTAMP 컬럼은 raw JDBC 경로 — D3의 hibernate.jdbc.time_zone=UTC 규약이 지배하지 못해 TTL 비교 시각 일관성 미보장"
      },
      {
        "section": "domain risk",
        "item": "장애 시나리오 최소 3개 식별됨",
        "status": "yes",
        "evidence": "docs/topics/TIME-MODEL-AND-EXPIRY.md §5 F1~F5 (컨테이너 TZ 만료 오판 / dedupe TTL / 정합 스캐너 조기 복원 / DB 해석 / 스케줄러 키 전환) 5개"
      },
      {
        "section": "domain risk",
        "item": "재시도 정책이 정의됨",
        "status": "yes",
        "evidence": "§5 '재시도 정책' — 신규 경로 없음, DedupeCleanupWorker 기존 패턴(전파 안 함+ERROR 로그+다음 주기) 유지 명시"
      },
      {
        "section": "domain risk",
        "item": "PII/민감정보 새로 도입 시 로깅·저장·전송 경로 검토",
        "status": "n/a",
        "evidence": "§5 'PII: 새로 도입되는 민감정보 없음 — 시각 데이터만'"
      },
      {
        "section": "domain risk (추가)",
        "item": "돈 기록 앵커(approvedAt) 타입·의미 보존",
        "status": "no",
        "evidence": "PaymentConfirmResultUseCase.java:230 OffsetDateTime.parse().toLocalDateTime() offset drop → Instant 전환 시 의미 변화가 설계에 미결정"
      }
    ],
    "total": 5,
    "passed": 2,
    "failed": 2,
    "not_applicable": 1
  },

  "scores": {
    "clarity": 0.80,
    "completeness": 0.62,
    "risk": 0.60,
    "testability": 0.78,
    "fit": 0.82,
    "mean": 0.72
  },

  "findings": [
    {
      "severity": "major",
      "checklist_item": "멱등성 전략이 결정됨 (idempotency key 소스/수명/충돌 처리)",
      "location": "docs/topics/TIME-MODEL-AND-EXPIRY.md#D3 / JdbcPaymentEventDedupeStore.java:53-61,71-76 / V2__payment_event_dedupe.sql:13-14",
      "problem": "D3은 'hibernate.jdbc.time_zone=UTC + 엔티티 Instant 매핑'만으로 모든 시각 I/O가 UTC 일관이라 주장하나, payment_event_dedupe는 NamedParameterJdbcTemplate + Timestamp.from(Instant)로 TIMESTAMP 컬럼에 쓰는 raw-JDBC 경로라 hibernate.jdbc.time_zone 지배 밖이다. 만료(Hibernate DATETIME)와 dedupe(raw JDBC TIMESTAMP)가 서로 다른 TZ 변환을 타면 expires_at<now 비교가 어긋나 TTL 윈도우가 좁아지고(조기 삭제→재배달 중복 처리=돈 새는 경로) NG3의 'TTL 의미 보존' 약속이 메커니즘 부재로 무너진다.",
      "evidence": "JdbcPaymentEventDedupeStore.java:58-59 Timestamp.from(...); V2 컬럼은 TIMESTAMP(세션 TZ 해석), V1 payment_event는 DATETIME(TZ 없음) — 두 테이블 변환 규칙 비대칭. F2가 위험은 짚었으나 보존 메커니즘은 미명시.",
      "suggestion": "D3에 'dedupe(raw JDBC) 경로는 Timestamp.from 대신 명시적 UTC 변환(예: connectionTimeZone 또는 OffsetDateTime/UTC 바인딩)으로 hibernate 매핑 경로와 동일 규칙을 강제한다'를 추가하고, AC에 dedupe TTL 경계 테스트를 비-UTC JVM TZ에서도 결정적 통과하도록 명시."
    },
    {
      "severity": "major",
      "checklist_item": "돈 기록 앵커(approvedAt) 타입·의미 보존",
      "location": "docs/topics/TIME-MODEL-AND-EXPIRY.md#D1,#D3 / PaymentConfirmResultUseCase.java:226-230 / PaymentEvent.java:97-111",
      "problem": "PG 승인 시각(approvedAt)은 정산·감사의 돈 기록 앵커인데 현재 OffsetDateTime.parse(raw).toLocalDateTime()로 offset을 버린 wall-clock LocalDateTime으로 저장된다. 이를 Instant(절대 UTC)로 전환할 때 벤더 offset(예: NicePay +09:00)을 살려 정규화하지 않으면 승인 시각이 최대 offset만큼(KST 9시간) 어긋나 결제 시각 기록이 틀어진다. 설계에 approvedAt만의 offset 정규화 결정이 전무하다.",
      "evidence": "PaymentConfirmResultUseCase.java:230 .toLocalDateTime() (offset drop); PITFALLS §13 NicePay paidAt offset 정규화가 동일 경계를 다뤘으나 본 설계는 시각 전환 맥락에서 재검토 안 함.",
      "suggestion": "D1/D3에 'approvedAt(및 paidAt 유래 벤더 시각)은 raw offset을 살려 OffsetDateTime.parse(raw).toInstant()로 정규화한다 — toLocalDateTime() 경유 금지'를 결정으로 추가하고, AC에 비-UTC offset raw 입력→저장 Instant 동치 테스트를 명시."
    },
    {
      "severity": "minor",
      "checklist_item": "design decisions (UTC 변환 경계)",
      "location": "docs/topics/TIME-MODEL-AND-EXPIRY.md#D3 / PgInboxEntity.java:90-91,105-106 / PaymentEventEntity.java:64-77",
      "problem": "pg는 엔티티 경계에서 LocalDateTime.ofInstant(now, ZoneOffset.UTC)로 UTC 변환을 코드에 못박아 TZ 무관하다. payment를 Instant 매핑+hibernate.jdbc.time_zone=UTC 단일 프로퍼티에만 의존하면 누락/오버라이드 시 조용히 어긋난다. '명시 변환(pg식)' vs '프로퍼티 의존(D3)' 통일 방향이 discuss에서 미확정.",
      "evidence": "PgInboxEntity.java:90-91 명시 UTC 변환 vs PaymentEventEntity는 LocalDateTime 직접 매핑. F1/F4가 위험만 짚고 변환 메커니즘 통일 결정은 없음.",
      "suggestion": "D3 또는 신규 결정으로 4서비스 엔티티 시각 변환을 pg식 명시 ZoneOffset.UTC 변환으로 통일할지 vs Hibernate 프로퍼티 의존으로 갈지 plan 진입 전 택일 명시."
    },
    {
      "severity": "minor",
      "checklist_item": "장애 시나리오 (전환 중 혼재 배포)",
      "location": "docs/topics/TIME-MODEL-AND-EXPIRY.md#F1,#F3 / PaymentReconciler.java:55",
      "problem": "시계 교체가 절대 시점을 안 바꾼다는 전제는 옳으나, 전환 중 부분 배포(구 LocalDateTime/시스템TZ 인스턴스 + 신 Instant/UTC 인스턴스 공존)에서 cutoff 해석이 인스턴스마다 달라 IN_PROGRESS 조기 복원→재confirm→PG 재호출 윈도우가 생긴다. 멱등 layer가 일반 차단하나 시나리오 명시 가치.",
      "evidence": "PaymentReconciler.java:55 now.minusSeconds(timeout); F1은 비-UTC 컨테이너만 다룸, 전환 중 인스턴스 간 해석 불일치는 §5 미기재.",
      "suggestion": "§5에 F6(전환 중 혼재 배포 — 인스턴스 간 시각 해석 불일치)을 추가하고, 배포 전략(전 인스턴스 동시 전환 vs rolling 시 TZ 영향)을 plan에서 명시. D6의 PITFALLS §23 deploy 순서 룰과 정합 확인."
    }
  ],

  "previous_round_ref": null,
  "delta": null,

  "unstuck_suggestion": null
}
```
