# plan-domain-2

**Topic**: TIME-MODEL-AND-EXPIRY
**Round**: 2
**Persona**: Domain Expert

## Reasoning
Round 1 major 2건이 Round 2 수정본에서 실제 소스와 정합하게 해소됐다. (1) dedupe 멱등 윈도우의 `expires_at` 계산 소스(`PaymentConfirmResultUseCase.java:126`)와 `occurredAt`(L191)이 T3 산출물 목록에 명시 추가됐고, store 측 `received_at` 소스(`JdbcPaymentEventDedupeStore.java:58`)는 T12가 같은 payment `Clock` 빈(T1 `ClockConfig`, `Clock.systemUTC()`)으로 전환 — 윈도우 양쪽 앵커가 단일 UTC Clock 소스로 수렴해 split이 닫힌다. (2) `PaymentStatusMetricsAspect`(L23/L46)·`DomainEventLoggingAspect`(L32/L86 audit 시각)가 T7 대상+domain_risk=true로 격상됐다. minor #4(NicePay fallback `OffsetDateTime.now()` L296)도 T15 점검 항목으로 매핑. 차단 finding 없음.

## Domain risk checklist
- [yes] major #1(expires_at 앵커 소스 split) 해소 — T3에 `PaymentConfirmResultUseCase` L68(필드)/L126(expiresAt)/L191(occurredAt) `clock.instant()` 전환 명시(PLAN L112). store의 `received_at`(L58)은 T12가 `Clock` 전환. 두 앵커가 payment 단일 `Clock` 빈 공유 — NG3 멱등 TTL 윈도우 일관. T3 테스트 `confirmResult_expiresAt_usesClockInstant`가 `markIfAbsent(id, fixedInstant.plus(TTL))`로 expires_at 소스 결정성 단정.
- [yes] major #2(두 aspect 미매핑→AC1 미달+audit TZ 누수) 해소 — T7 대상에 `PaymentStatusMetricsAspect`(L23/L46), `DomainEventLoggingAspect`(L32/L86) 추가, domain_risk=true. `DomainEventLoggingAspect.occurredAt`(L86)은 `@PublishDomainEvent` 상태전이 감사 시각(PITFALLS §1)이라 TZ 누수 제거가 사고 재구성 정확성에 직결. AC1 grep-0 전수 목록(PLAN L189/L400-422) 15개 클래스에 두 aspect 포함.
- [yes] minor #4(NicePay fallback AC2 사각지대) 매핑 — T15 "NicePay fallback 점검" 불릿이 `NicepayPaymentGatewayStrategy.java:296 OffsetDateTime.now()`를 execute에서 정산 앵커 영향 확인 후 clock 정렬 또는 비차단 주석으로 처리하도록 명시.
- [yes] D7(raw-JDBC UTC + product NOW()/Instant split-brain) — T12/T13에 `connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true` + 명시 UTC `Calendar` + AC8 비-UTC JVM round-trip 통합테스트. product T13 `existsValid_nowBasedOnConnectionUTC_sameBoundaryAsAppInstant`로 split-brain 부재 단정. store 2개(payment `JdbcPaymentEventDedupeStore.java:58-59,73` / product) raw-JDBC 시각 쓰기 경로 전부 커버.
- [yes] D8(approvedAt offset 정규화) — T14 `parseApprovedAt`(payment L226-231) `.toLocalDateTime()`(L230 확인) → `.toInstant()` + AC9(+09:00→Z 9시간 오차 부재). pg는 raw 문자열 보존 권위(T15), payment 단일 앵커 정규화 — 메시지 contract 무변경.
- [yes] D4/D6(만료 경계·READY 가드·2단 연쇄) — T6 cutoff 외부화+AC5 경계(29/31분), T10 `expire()` READY 가드 `@ParameterizedTest @EnumSource`(NG2), T11 reconciler 복원→만료 2단 연쇄. 시각 소스 교체가 cutoff 절대시점 불변이라 "승인됐는데 만료" 위험 재도입 없음.
- [yes] domain_risk=true 플래그 9개(T2/T3/T7/T8/T10/T11/T12/T13/T14) — 시각이 돈/재고/멱등/감사에 닿는 태스크에 정확. T7이 Round 1 대비 신규 격상(audit 시각 포함). 설정/문서 태스크(T1/T4/T5/T6/T9/T15/T16) 중 T15는 domain_risk=true(벤더 정산 앵커), T16(verify 문서)은 false — 적정.

## 도메인 관점 추가 검토
1. **[해소 확인] 멱등 윈도우 양쪽 앵커 단일 Clock 수렴** — `PaymentConfirmResultUseCase.java:126`(expiresAt=`localDateTimeProvider.nowInstant().plus(STOCK_COMMITTED_TTL)`)/L191(occurredAt)이 T3에 명시 추가. store `received_at`(`JdbcPaymentEventDedupeStore.java:58` `Timestamp.from(localDateTimeProvider.nowInstant())`)는 T12 전환. 둘 다 payment 단일 `Clock`(T1)에서 나오므로 `expires_at = nowInstant + P8D`와 `received_at = nowInstant`가 같은 시계 tick 기준으로 일관 — Round 1이 지적한 "received_at 전환 / expires_at 미전환" split이 코드 레벨에서 닫혔다. T1 `LocalDateTimeProvider` 삭제 시 L126/L191 컴파일 깨짐도 T3가 동반 그린화로 해소.
2. **[해소 확인] audit 전이 시각 TZ 누수 차단** — `DomainEventLoggingAspect.java:86 LocalDateTime occurredAt = localDateTimeProvider.now()`가 `publishStatusChange/publishRetryAttempt/publishCreated`(L94/98/102)로 흘러 `payment_history` 감사 시각이 된다. T7이 이 소스를 `clock.instant()` 기반으로 전환 — 컨테이너 비-UTC TZ에서 상태 전이 시각이 어긋나 사고 재구성이 틀어지는 PITFALLS §1 경로를 닫는다.
3. **[확인, 차단 아님] D8 단일 앵커 정합 재확인** — payment `parseApprovedAt`(L230) `.toLocalDateTime()`만 offset을 버린다. pg Toss(`approvedAt` raw 보존 L213)·NicePay(`parsedPaidAt.toString()` ISO offset 보존 L251)는 raw 문자열을 깎지 않는다. T14 `.toInstant()` 단일 전환이 9시간 오차의 실제 단일 차단점. T15 contract 불변 단정은 tdd:false라 architect 권고대로 execute에서 회귀 단정 확인 권고(차단 아님).
4. **[확인, 차단 아님] 벤더 파싱 실패 fallback TZ** — Toss `LocalDateTime.now()`(L244)는 T15·AC2 grep으로 잡힘. NicePay `OffsetDateTime.now()`(L296)는 grep 사각지대지만 T15 점검 항목으로 명시 매핑됨. 파싱 실패 예외 경로의 raw-now 주입이라 정상 정산 앵커 경로의 돈 사고 아님 — execute 확인으로 충분.
5. **[확인] D6 race 부재 유지** — 만료(`scheduler.payment-expiration.*`)·복원(`reconciler.*`) 별개 프로퍼티 독립 운영(T9/T11). `expire()` READY 가드·`resetToReady()` IN_PROGRESS 가드 유지. 시각 소스 교체가 cutoff 절대시점을 바꾸지 않아 새 race window 없음.

## Findings
(차단/major 없음. 참고 사항만)
- **minor (참고)** — T15의 NicePay `OffsetDateTime.now()`(L296)·Toss `approvedAtRaw` contract 불변은 tdd:false 점검 항목이라 회귀 단정이 없다. execute에서 raw 문자열 무변환 전달 단정 또는 기존 contract test 커버 확인 권고. 예외/확인 경로라 본 라운드 차단 아님.

## JSON
```json
{
  "stage": "plan",
  "persona": "domain-expert",
  "round": 2,
  "task_id": null,

  "decision": "pass",
  "reason_summary": "Round 1 major 2건이 Round 2에서 소스 정합하게 해소: dedupe 멱등 윈도우의 expires_at(L126)/occurredAt(L191) 소스가 T3에 명시 추가되고 received_at(L58)은 T12가 동일 payment Clock 빈으로 전환해 윈도우 양쪽 앵커가 단일 UTC Clock으로 수렴(NG3 일관). 두 aspect(audit 시각 L86 포함)는 T7 대상+domain_risk=true로 격상돼 AC1 grep-0과 audit TZ 누수가 동시 해소. minor #4(NicePay fallback)도 T15 점검 항목 매핑. 차단 finding 없음.",

  "checklist": {
    "source": "_shared/checklists/plan-ready.md",
    "items": [
      {
        "section": "domain risk",
        "item": "dedupe TTL 의미 보존(NG3) — expires_at/received_at 시각 소스가 단일 Clock으로 일관 전환",
        "status": "yes",
        "evidence": "T3에 PaymentConfirmResultUseCase L68/L126(expiresAt)/L191(occurredAt) clock.instant() 명시(PLAN L112). T12가 JdbcPaymentEventDedupeStore.java:58 received_at 소스를 Clock 전환. 둘 다 payment 단일 Clock 빈(T1 ClockConfig). 소스 확인: L126 expiresAt, L191 occurredAt, store L58 received_at 실재."
      },
      {
        "section": "domain risk",
        "item": "domain_risk=true 플래그가 시각이 돈/재고/멱등/감사에 닿는 태스크에 정확히 부여됨",
        "status": "yes",
        "evidence": "9개(T2/T3/T7/T8/T10/T11/T12/T13/T14) 정확. T7이 Round 1 대비 신규 격상 — DomainEventLoggingAspect.java:86 audit 전이 시각 TZ 누수(PITFALLS §1) 포함. T15도 domain_risk=true(벤더 정산 앵커)."
      },
      {
        "section": "task quality",
        "item": "AC1(LocalDateTimeProvider grep 0건) 완료 기준이 실제 참조처를 전부 커버",
        "status": "yes",
        "evidence": "T7 대상에 PaymentStatusMetricsAspect.java:23,46 + DomainEventLoggingAspect.java:32,86 추가. AC1 전수 목록(PLAN L189/L400-422) 15개 클래스에 두 aspect 포함. 소스 확인: 두 파일의 LocalDateTimeProvider 필드+.now() 실재."
      },
      {
        "section": "domain risk",
        "item": "discuss major #2(approvedAt offset) 대응 + .toLocalDateTime() 금지",
        "status": "yes",
        "evidence": "T14 parseApprovedAt(L226-231) .toInstant() + AC9. payment L230 .toLocalDateTime()가 유일 offset-drop 지점, pg(Toss L213 raw 보존 / NicePay L251 toString) raw 보존. 단일 앵커 정규화."
      },
      {
        "section": "domain risk",
        "item": "expire() READY 가드 + 2단 연쇄 + 만료 경계 검증 태스크",
        "status": "yes",
        "evidence": "T10 @ParameterizedTest @EnumSource(NG2), T11 reconciler 2단 연쇄, T6 AC5 경계(29/31분). 시각 소스 교체가 cutoff 절대시점 불변."
      },
      {
        "section": "domain risk",
        "item": "minor #4 NicePay fallback OffsetDateTime.now() AC2 사각지대 매핑",
        "status": "yes",
        "evidence": "T15 'NicePay fallback 점검' 불릿이 NicepayPaymentGatewayStrategy.java:296을 execute 확인 항목으로 명시(정산 앵커 영향 시 clock 정렬, 아니면 비차단 주석)."
      }
    ],
    "total": 6,
    "passed": 6,
    "failed": 0,
    "not_applicable": 0
  },

  "scores": {
    "clarity": 0.9,
    "completeness": 0.88,
    "risk": 0.87,
    "testability": 0.86,
    "fit": 0.86,
    "mean": 0.874
  },

  "findings": [],

  "previous_round_ref": "plan-domain-1.md",
  "delta": {
    "newly_passed": [
      "dedupe TTL 의미 보존(NG3) — expires_at/received_at 단일 Clock 일관",
      "AC1(LocalDateTimeProvider grep 0건) 완료 기준이 실제 참조처 전부 커버 (두 aspect 추가)",
      "domain_risk=true 플래그 정확성 (T7 audit aspect 격상으로 미매핑 경로 해소)"
    ],
    "newly_failed": [],
    "still_failing": []
  },

  "unstuck_suggestion": null
}
```
