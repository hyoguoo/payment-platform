# plan-domain-1

**Topic**: TIME-MODEL-AND-EXPIRY
**Round**: 1
**Persona**: Domain Expert

## Reasoning
discuss에서 잡은 major 2건(D7 raw-JDBC dedupe TZ 누수, D8 approvedAt offset-drop)은 T12/T13·T14/T15로 정확히 매핑됐고 실제 소스(`PgConfirmResult.approvedAtRaw` 7번째 인자가 offset 보존, payment `parseApprovedAt` L226-230 `.toLocalDateTime()`)와 교차 검증해 처방 방향이 옳다. 다만 dedupe 멱등성 윈도우의 *반대쪽 앵커*인 `payment_event_dedupe.expires_at` 시각 소스(`PaymentConfirmResultUseCase.L126 localDateTimeProvider.nowInstant().plus(TTL)`)가 어느 `Clock` 전환 태스크에도 매핑되지 않아, T1의 `LocalDateTimeProvider` 삭제 후 이 멱등 TTL 계산 소스가 미전환·빌드 깨짐으로 남는다 — 멱등성 정확성에 직결되는 누락이라 revise.

## Domain risk checklist
- [yes] discuss major #1 (raw-JDBC dedupe TZ) → T12(payment)/T13(product) 매핑. 양쪽 store + `connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true` + 명시 UTC `Calendar` + AC8 비-UTC JVM round-trip 통합테스트. 실제 store 2개(`JdbcPaymentEventDedupeStore.java:58-59,73`, `JdbcEventDedupeStore.java:39,45,84,106`)가 raw-JDBC 시각 쓰기 경로 전부임을 grep 재확인 — 누락 경로 없음.
- [yes] product `NOW()` vs `Instant` split-brain → T13에 `existsValid`/`SQL_DELETE_EXPIRED_BY_UUID`의 `NOW()`(L39/L45)와 앱 `Instant`(L106) 동일 만료 경계 검증 케이스(`existsValid_nowBasedOnConnectionUTC_sameBoundaryAsAppInstant`) 포함.
- [yes] discuss major #2 (approvedAt offset 정규화) → T14(`.toInstant()`) + AC9(KST +09:00 → Z 9시간 오차 부재) + `.toLocalDateTime()` 잔존 0건 grep. pg 측 raw 보존(T15) 확인 태스크 존재.
- [yes] `expire()` READY 가드 유지 → T10 `@ParameterizedTest @EnumSource` exhaustive (NG2 회귀 가드). 실제 가드 `PaymentEvent.java:137-139 INVALID_STATUS_TO_EXPIRE` 확인.
- [yes] 2단 연쇄(정합 스캐너 복원 → 만료) 보존 → T11 `PaymentReconcilerTest` + `PaymentExpirationServiceImplTest` Instant 전환.
- [yes] dedupe TTL 의미 보존(NG3) → 시각 소스만 교체, `expires_at = received_at + P8D` 식 불변 명시(T12/T13). 단 아래 추가검토 1의 `expiresAt` 계산 소스 누락이 이 보존 사슬에 구멍을 냄.
- [partial] domain_risk=true 플래그 부여 — 시각이 돈/재고/멱등에 닿는 태스크에 대체로 정확(T2/T3/T8/T10/T11/T12/T13/T14). 그러나 멱등 TTL 소스를 계산하는 `PaymentConfirmResultUseCase`(L126)가 어느 태스크 산출물에도 없어 domain_risk 경로 하나가 통째로 미매핑.
- [n/a] 만료 임계 경계(29/31분) 테스트 → T6 `getReadyPaymentsOlder_29min_notIncluded`/`_31min_included`(AC5) 존재.

## 도메인 관점 추가 검토

1. **[major] 멱등 TTL 앵커 `expires_at` 계산 소스가 미매핑** (`PaymentConfirmResultUseCase.java:126` `Instant expiresAt = localDateTimeProvider.nowInstant().plus(STOCK_COMMITTED_TTL)`). 이 줄이 `payment_event_dedupe.expires_at`(D7/NG3가 보호하려는 바로 그 멱등 윈도우)을 만든다. T12는 store 내부의 `received_at` 쓰기 측(`Timestamp.from` + Calendar)과 URL만 다루고, 이 `expiresAt` *값 생성* 소스는 application(`PaymentConfirmResultUseCase`)에 있다. T3의 application Clock 전환 대상 목록에 이 클래스가 **빠져 있고**(T3: PaymentCommandUseCase/PaymentLoadUseCase/PaymentCreateUseCase/PaymentOutboxUseCase/OutboxRelayService/PaymentReconciler), T14는 같은 파일을 건드리지만 `parseApprovedAt`/`markPaymentAsDone`만 범위로 둔다. 결과: T1이 `LocalDateTimeProvider`를 삭제하면 L126이 컴파일 깨지고, 더 중요하게는 dedupe 윈도우의 한쪽 앵커(`received_at`, T12 전환)와 다른쪽 앵커(`expires_at`, 미전환)의 시각 소스가 갈린다. 처방: T14 산출물에 L126/L191 `localDateTimeProvider.nowInstant()` → `clock.instant()` 전환을 명시하거나, T3 대상 목록에 `PaymentConfirmResultUseCase`를 추가.

2. **[major] AC1(`LocalDateTimeProvider` grep 0건) 미달 — 2개 aspect 미매핑** (`PaymentStatusMetricsAspect.java:23,46`, `DomainEventLoggingAspect.java:32,86` 모두 `LocalDateTimeProvider localDateTimeProvider` 필드 주입 + `.now()` 사용). T7(infrastructure metrics/scheduler/repo)·T3 어디에도 `infrastructure/aspect/*`가 없다. 두 aspect가 미전환이면 T1의 포트 삭제로 컴파일이 깨지고 AC1 grep-0 완료 기준이 충족 불가. 도메인 영향: `DomainEventLoggingAspect`의 `occurredAt`(L86)은 `@PublishDomainEvent` 상태전이 감사 시각(PITFALLS §1 audit trail)이라 시각 소스가 컨테이너 TZ에 남으면 사고 재구성 시 전이 시각이 어긋난다. 처방: T7 대상에 두 aspect 추가.

3. **[minor] 멱등 마킹 발행 시각 `occurredAt`(L191)도 동일 미매핑** (`PaymentConfirmResultUseCase.java:191` `Instant occurredAt = localDateTimeProvider.nowInstant()`). 추가검토 1과 같은 파일·같은 원인. 1과 함께 처방하면 해소.

4. **[확인, 차단 아님] D8 앵커 처방이 실제 contract와 정합** (`PgConfirmResult.java:21 approvedAtRaw`(String, offset 보존) → `ConfirmedEventPayload.approved()`로 전달, Toss `TossPaymentGatewayStrategy.java:213` raw 그대로 / NicePay `NicepayPaymentGatewayStrategy.java:251 parsedPaidAt.toString()` ISO_OFFSET 보존). payment `parseApprovedAt` L230만 `.toLocalDateTime()`로 offset을 버리므로, T14의 `.toInstant()` 단일 전환이 9시간 오차의 실제 단일 차단점이 맞다. 메시지 직렬화 무변경(raw 문자열 유지)도 성립. T15의 "raw 보존 확인"은 회귀 단정 없는 tdd:false라 architect 지적대로 contract 불변 단정 보강 권고이나, 도메인 차단 사유는 아님.

5. **[확인, 차단 아님] 벤더 파싱 실패 fallback의 TZ 누수는 본 토픽 처방에 포함됨** (`TossPaymentGatewayStrategy.java:244 LocalDateTime.now()` → T15가 명시 제거 대상, `NicepayPaymentGatewayStrategy.java:296 OffsetDateTime.now()`(시스템 TZ) fallback). Toss fallback은 T15·AC2 grep으로 잡힌다. NicePay fallback `OffsetDateTime.now()`는 `Instant.now()`/`LocalDateTime.now()` grep 패턴에 안 걸려 AC2 grep을 통과해 버릴 수 있으나, 이는 파싱 실패라는 예외 경로의 raw-now 주입이라 정상 정산 앵커 경로의 돈 사고는 아님 — 경미, 본 라운드 차단 아님(execute 시 확인 권고).

6. **[확인] D6 연쇄·race 부재 재확인** (`PaymentReconciler.java:55 now.minusSeconds(timeout)` → T3가 `Clock` 전환, `PaymentEvent.expire` READY 가드 / `resetToReady` IN_PROGRESS 가드 유지). 시각 소스 교체는 cutoff 절대시점을 바꾸지 않아 "실제 승인됐는데 만료" 위험 재도입 없음. 만료/복원이 별개 프로퍼티(T9: `scheduler.payment-expiration.*` vs `reconciler.*`)로 독립 운영돼 새 race window 없음.

## Findings
- **major #1** — 멱등 TTL 앵커 `expires_at` 계산 소스(`PaymentConfirmResultUseCase.java:126`)가 어느 Clock 전환 태스크에도 미매핑. T1의 `LocalDateTimeProvider` 삭제 시 dedupe 윈도우 한쪽 앵커가 미전환·빌드 깨짐 + 멱등 TTL 소스 split. 추가검토 1.
- **major #2** — `PaymentStatusMetricsAspect`/`DomainEventLoggingAspect`의 `LocalDateTimeProvider` 주입이 미매핑 → AC1(grep 0건) 미달 + audit 전이 시각 TZ 누수. 추가검토 2.
- **minor #3** — `PaymentConfirmResultUseCase.java:191 occurredAt` 동일 미매핑(추가검토 1과 동일 처방으로 해소). 추가검토 3.
- **minor #4** — NicePay 파싱 실패 fallback `OffsetDateTime.now()`(L296)가 AC2 grep 패턴 사각지대. 예외 경로라 경미, execute 확인 권고. 추가검토 5.

## JSON
```json
{
  "stage": "plan",
  "persona": "domain-expert",
  "round": 1,
  "task_id": null,

  "decision": "revise",
  "reason_summary": "discuss major 2건(D7 dedupe TZ / D8 approvedAt offset)은 T12·T13·T14·T15로 정확히 매핑되고 소스 교차검증도 통과. 그러나 dedupe 멱등 윈도우의 expires_at 시각 소스(PaymentConfirmResultUseCase.L126)와 2개 aspect(L86 audit 시각 포함)의 LocalDateTimeProvider 주입이 어느 Clock 전환 태스크에도 미매핑 — T1 포트 삭제 후 미전환·빌드 깨짐 + 멱등 TTL 소스 split + AC1 grep-0 미달. 멱등/감사 정확성 직결이라 revise.",

  "checklist": {
    "source": "_shared/checklists/plan-ready.md",
    "items": [
      {
        "section": "domain risk",
        "item": "discuss major #1(raw-JDBC dedupe TZ)이 대응 태스크를 가짐",
        "status": "yes",
        "evidence": "T12/T13 — JdbcPaymentEventDedupeStore.java:58-59,73 + JdbcEventDedupeStore.java:39,45,84,106 양쪽 + connectionTimeZone=UTC + 명시 Calendar + AC8 비-UTC JVM round-trip. raw-JDBC 시각 쓰기 경로 2개가 전부임을 grep 재확인."
      },
      {
        "section": "domain risk",
        "item": "discuss major #2(approvedAt offset)이 대응 태스크를 가짐",
        "status": "yes",
        "evidence": "T14 .toInstant() + AC9(+09:00→Z) + .toLocalDateTime() grep 0건. PgConfirmResult.approvedAtRaw(String) offset 보존이 payment 단일 앵커 .toInstant()로 정확히 복원됨을 소스 확인."
      },
      {
        "section": "domain risk",
        "item": "expire() READY 가드 + 2단 연쇄 보존 검증 태스크",
        "status": "yes",
        "evidence": "T10 @ParameterizedTest @EnumSource(NG2) + T11 PaymentReconcilerTest 2단 연쇄. PaymentEvent.java:137-139 INVALID_STATUS_TO_EXPIRE 가드 실재 확인."
      },
      {
        "section": "domain risk",
        "item": "dedupe TTL 의미 보존(NG3) — 시각 소스 전환이 멱등 윈도우 값을 바꾸지 않음",
        "status": "no",
        "evidence": "PaymentConfirmResultUseCase.java:126 expiresAt=localDateTimeProvider.nowInstant().plus(TTL) 가 payment_event_dedupe.expires_at 을 생성하나 T3/T14 어느 산출물에도 Clock 전환 미명시 → received_at(T12 전환)과 expires_at(미전환) 시각 소스 split + T1 삭제 시 빌드 깨짐."
      },
      {
        "section": "domain risk",
        "item": "domain_risk=true 플래그가 시각이 돈/재고/멱등에 닿는 태스크에 정확히 부여됨",
        "status": "partial",
        "evidence": "T2/T3/T8/T10/T11/T12/T13/T14 부여는 정확. 그러나 멱등 TTL 소스를 계산하는 PaymentConfirmResultUseCase L126/L191 + audit 시각 aspect(DomainEventLoggingAspect L86)가 어느 태스크에도 없어 domain_risk 경로 일부가 통째 미매핑."
      },
      {
        "section": "task quality",
        "item": "AC1(LocalDateTimeProvider grep 0건) 완료 기준이 실제 참조처를 전부 커버",
        "status": "no",
        "evidence": "PaymentStatusMetricsAspect.java:23,46 + DomainEventLoggingAspect.java:32,86 가 LocalDateTimeProvider 주입+사용하나 T7/T3 대상 목록에 infrastructure/aspect/* 없음 → AC1 미달."
      }
    ],
    "total": 6,
    "passed": 3,
    "failed": 2,
    "not_applicable": 0
  },

  "scores": {
    "clarity": 0.86,
    "completeness": 0.66,
    "risk": 0.64,
    "testability": 0.84,
    "fit": 0.82,
    "mean": 0.764
  },

  "findings": [
    {
      "severity": "major",
      "checklist_item": "dedupe TTL 의미 보존(NG3) — 시각 소스 전환",
      "location": "docs/TIME-MODEL-AND-EXPIRY-PLAN.md#T3,#T14 / payment-service/.../PaymentConfirmResultUseCase.java:126",
      "problem": "payment_event_dedupe.expires_at 의 시각 소스 expiresAt=localDateTimeProvider.nowInstant().plus(STOCK_COMMITTED_TTL)(L126)가 어느 Clock 전환 태스크에도 매핑되지 않았다. T12는 store의 received_at 쓰기 측(Timestamp+Calendar)·URL만 다루고, expires_at 값 생성은 application(PaymentConfirmResultUseCase)에 있는데 T3 대상 목록에서 이 클래스가 빠졌고 T14는 같은 파일의 parseApprovedAt/markPaymentAsDone만 범위로 둔다. T1이 LocalDateTimeProvider를 삭제하면 L126이 컴파일 깨지고, dedupe 멱등 윈도우의 received_at(T12 전환)과 expires_at(미전환) 시각 소스가 갈려 D7/NG3가 보호하려는 멱등 TTL 일관성에 구멍이 생긴다.",
      "evidence": "PaymentConfirmResultUseCase.java:126 expiresAt 계산; T3 application 대상 목록에 PaymentConfirmResultUseCase 부재; T14 산출물 목록은 L226-231·markPaymentAsDone 한정.",
      "suggestion": "T14 산출물에 L126/L191 localDateTimeProvider.nowInstant() → clock.instant() 전환을 명시하거나 T3 application Clock 전환 대상 목록에 PaymentConfirmResultUseCase 추가. AC8/AC 회귀에 expires_at 소스도 fixed Clock 결정성 단정 포함."
    },
    {
      "severity": "major",
      "checklist_item": "AC1(LocalDateTimeProvider grep 0건) 완료 기준 커버리지",
      "location": "docs/TIME-MODEL-AND-EXPIRY-PLAN.md#T7 / payment-service/.../infrastructure/aspect/PaymentStatusMetricsAspect.java:23,46 / DomainEventLoggingAspect.java:32,86",
      "problem": "두 aspect가 LocalDateTimeProvider 를 필드 주입하고 .now()를 사용하나 T7(infrastructure metrics/scheduler/repo)·T3 어디에도 infrastructure/aspect/* 가 없다. T1의 포트 삭제 시 컴파일 깨짐 + AC1(grep 0건) 완료 기준 충족 불가. DomainEventLoggingAspect.occurredAt(L86)은 @PublishDomainEvent 상태전이 감사 시각(PITFALLS §1 audit trail)이라 시각 소스가 컨테이너 TZ에 남으면 사고 재구성 시 전이 시각이 어긋난다.",
      "evidence": "PaymentStatusMetricsAspect.java:46 localDateTimeProvider.now(); DomainEventLoggingAspect.java:86 localDateTimeProvider.now(); 두 파일 모두 PLAN 어느 태스크 산출물 목록에도 없음.",
      "suggestion": "T7 대상에 infrastructure/aspect/PaymentStatusMetricsAspect, DomainEventLoggingAspect 를 추가하고 Clock 전환 명시. AC1 grep 검증 범위에 aspect 포함."
    },
    {
      "severity": "minor",
      "checklist_item": "domain_risk 경로 시각 소스 전환 완전성",
      "location": "payment-service/.../PaymentConfirmResultUseCase.java:191",
      "problem": "occurredAt = localDateTimeProvider.nowInstant()(L191, 멱등 마킹/발행 시각) 도 major #1과 동일 파일·동일 원인으로 미매핑.",
      "evidence": "PaymentConfirmResultUseCase.java:191.",
      "suggestion": "major #1 처방(이 파일 전체 Clock 전환)에 함께 포함하면 해소."
    },
    {
      "severity": "minor",
      "checklist_item": "장애 시나리오 / AC2 grep 사각지대",
      "location": "pg-service/.../nicepay/NicepayPaymentGatewayStrategy.java:296",
      "problem": "paidAt 파싱 실패 fallback OffsetDateTime.now()(시스템 TZ)는 AC2의 Instant.now()/LocalDateTime.now() grep 패턴에 안 걸려 통과될 수 있다. 정상 정산 앵커 경로가 아니라 예외 경로의 raw-now 주입이라 경미.",
      "evidence": "NicepayPaymentGatewayStrategy.java:296 return OffsetDateTime.now();",
      "suggestion": "execute에서 AC2 grep 패턴에 OffsetDateTime.now() 포함 또는 fallback을 clock 기반으로 정렬 권고(차단 아님)."
    }
  ],

  "previous_round_ref": null,
  "delta": null,

  "unstuck_suggestion": null
}
```
