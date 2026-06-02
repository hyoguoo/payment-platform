# review-domain-final

**Topic**: TIME-MODEL-AND-EXPIRY
**Round**: final
**Persona**: Domain Expert

## Reasoning
누적 diff(`main...HEAD`, T1~T17 + DM1/DM2 + 가드)를 돈/재고 정합 관점에서 전체 재점검했다. 만료 경로(created_at UTC wall-clock vs Instant cutoff), 두 dedupe 멱등 윈도우(payment UTC Calendar + product connectionTimeZone=UTC), 벤더 승인 시각(approvedAtRaw 보존 + payment `.toInstant()` 정규화), PaymentOutbox/PgOutbox 재시도 backoff·in-flight 타임아웃을 모두 실제 소스로 교차 검증했고, DM1/DM2가 닫혔음을 재확인했다. 시각 타입 전환이 상태 전이·멱등성·발행 보장·정합 스캐너 타이밍에 silent 변화를 주지 않는다. 도메인 리스크 차원의 차단 사유 0건 → pass.

## Domain risk checklist
- paymentKey/orderId/카드번호 plaintext 로그 노출: 시각 타입 전환만 — 신규 PII 로깅 없음. fallback 로그도 시각 문자열만. **n/a**
- 보상/취소 멱등성 가드: 변경 없음. Lua dedup token(P8D), PaymentOutbox `incrementRetryCount` IN_FLIGHT 가드, D12 재고 복구 이중 가드 모두 미변경. **yes**
- PG "이미 처리됨" 특수 응답 정당성: `DuplicateApprovalHandler`/`PgTerminalReemitService`/FCG 로직 무변경 — clock.instant() 인자 주입만. terminal 가드(`createDirectTerminal` isTerminal 검증) 보존. **yes**
- 상태 전이 불변식 위반: `PaymentEvent.expire()` READY 가드(NG2), `done()` IN_PROGRESS/RETRYING 가드 + DONE→DONE no-op, terminal 재진입 가드, enum 불변(NG6) 모두 유지. **yes**
- race window 락/격리: 만료 cutoff 비교(created_at UTC vs :before Instant), outbox `claimToInFlight` CAS, dedupe INSERT IGNORE 모두 단일 UTC 기준 유지 — 시각 전환이 윈도우 경계를 흔들지 않음. **yes**

## 도메인 관점 추가 검토

1. **벤더 승인 시각(돈 앵커) — 오프셋 보존 정규화 end-to-end 확인.** payment `PaymentConfirmResultUseCase.parseApprovedAt`(L232-236)가 `OffsetDateTime.parse(approvedAtRaw).toInstant()`로 전환돼 KST(+09:00) 입력도 9시간 오차 없이 UTC 절대시점으로 수렴(D8). pg 측 `buildApprovedPayload`(PgVendorCallService.java:252-256)는 여전히 `result.approvedAtRaw()`(원본 offset 문자열)를 `ConfirmedEventPayload.approved`에 실어 발행 — 즉 Kafka 메시지 contract(`approvedAt` = ISO_OFFSET_DATE_TIME 문자열) 무변경. NicePay strategy L254의 `parsedPaidAt.toLocalDateTime()`은 pg 내부 `PgConfirmResult.approvedAt` 필드에만 쓰이고 cross-service 앵커가 아님(L256 `.toString()`이 raw 보존). 정규화 권위가 payment 단일 지점에 있어 일관. `parseApprovedAt`의 ApprovedAtTest(95줄 신규)가 KST→UTC 9시간 정합을 단정.

2. **만료 경로 — created_at(UTC wall-clock) vs Instant cutoff 단일 기준(DM1 닫힘 재확인).** `PaymentLoadUseCase.getReadyPaymentsOlder`(L42)가 `clock.instant().minus(Duration.ofMinutes(timeoutMinutes))`로 cutoff를 만들어 native 쿼리 `WHERE status='READY' AND created_at < :before`(JpaPaymentEventRepository.java:20)의 `:before(Instant)`에 바인딩. 쓰기 측 `created_at`은 `clockDateTimeProvider`(Clock.systemUTC 기반 UTC wall-clock)가 채우고 `hibernate.jdbc.time_zone=UTC`(application.yml:41)로 Instant 바인딩이 UTC 고정. 두 operand 동일 UTC 기준 → 비-UTC JVM 조기/지연 만료 차단. `expire()` READY 가드(NG2)와 2단 연쇄(D6) 유지. D4(임계 외부화 `payment.expiration.ready-timeout-minutes:30`)/D5(`scheduler.payment-expiration.fixed-rate` 주키 + 구키 fallback) 운영 무중단 확인.

3. **dedupe 멱등 윈도우 — 두 store 모두 UTC 일관(DM2 닫힘 재확인).** payment `JdbcPaymentEventDedupeStore`는 received_at/expires_at를 명시 UTC Calendar로 쓰고(L116-117) 만료 판정은 앱 주입 `Instant`(`expires_at < :now`)라 DB 시계 비의존 — 견고. product `JdbcEventDedupeStore`는 write는 UTC Calendar(L98-105), `existsValid`/`SQL_DELETE_EXPIRED_BY_UUID`는 DB `NOW()`인데 default/docker 양 프로필 URL에 `connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true`가 적용돼 `NOW()`가 UTC 세션 시각 → split-brain 해소. TTL P8D 의미(NG3) 보존. product NOW() 경로가 URL 파라미터 1줄에 단일 의존하는 구조적 약점은 round-2 minor로 이미 기록·D7 이연 — 비차단.

4. **PaymentOutbox/PgOutbox 재시도·in-flight 타임아웃 — 발행 보장 무변화.** PaymentOutbox는 `Instant.plus(Duration)` backoff(FIXED/EXPONENTIAL 산식 불변), `recoverTimedOutInFlightRecords` cutoff가 UTC wall-clock 양변 일치(round-3 검증). PgOutbox 자체 retry는 `availableAt = now.plus(RetryPolicy.computeBackoff(...))`(PgVendorCallService.java:211-212)로 backoff 산식·attempt<4 한도·DLQ 분기 모두 무변경 — `PgOutbox.create`/`createWithAvailableAt`에 `clock.instant()` 인자 주입만. Kafka self-loop 지연 발행(available_at 기반) 타이밍 보존.

5. **교차 영향 — Kafka confirm 사이클/보상/정합 스캐너 silent drift 없음.** 세 서비스 모두 직접 `now()` 호출이 comment 외 live 코드에서 제거됨(grep 확인 — 매치는 전부 Javadoc/주석). EOS confirm 사이클(pg↔payment), D12 재고 복구 가드, `PaymentReconciler`(executed_at 기반, 이미 Instant) 비교는 본 diff에서 로직 미변경. 신규 race window/silent loss/돈·재고 정합 구멍 없음. 단위 테스트 129건(ApprovedAt/PaymentEvent/Clock/Scheduler 포함) 통과 확인(filtered run의 jacoco 게이트 미달은 부분 실행 산물로 회귀 아님).

## Findings
없음 (도메인 리스크 차단 사유 0건). round-2 minor 2건(product NOW() URL 단일 의존 / 컨테이너 TZ=UTC backstop 미명시)은 D7 이연·운영 게이트 권고로 이미 기록된 비차단 잔존 항목.

## JSON
```json
{
  "stage": "review",
  "persona": "domain-expert",
  "round": 99,
  "task_id": null,

  "decision": "pass",
  "reason_summary": "누적 diff를 돈/재고 정합 관점에서 전체 재점검 — 벤더 승인 시각 오프셋 보존 정규화(approvedAtRaw 보존+payment .toInstant()), 만료 cutoff UTC 단일 기준(DM1 닫힘), dedupe 윈도우 UTC 일관(DM2 닫힘), outbox 재시도·타임아웃 무변화를 소스로 확인. 상태 전이·멱등성·발행 보장에 silent 변화 없음. 도메인 차단 사유 0건.",

  "checklist": {
    "source": "_shared/checklists/code-ready.md",
    "items": [
      {
        "section": "domain risk",
        "item": "상태 전이 불변식 보존 — expire() READY 가드, done() IN_PROGRESS/RETRYING 가드, terminal 재진입 가드(NG2/NG6)",
        "status": "yes",
        "evidence": "PaymentEvent.java:135-143 expire() READY 가드, L95-114 done() 가드 + DONE→DONE no-op. enum 불변. PgInbox.createDirectTerminal isTerminal 검증 유지."
      },
      {
        "section": "domain risk",
        "item": "벤더 승인 시각(돈 앵커) 오프셋 보존 정규화 — 9시간 오차 차단(D8)",
        "status": "yes",
        "evidence": "PaymentConfirmResultUseCase.java:236 OffsetDateTime.parse(approvedAtRaw).toInstant(). pg buildApprovedPayload(PgVendorCallService.java:252-256)는 approvedAtRaw 원본 offset 문자열 보존 발행 — 메시지 contract 무변경. ApprovedAtTest KST→UTC 단정."
      },
      {
        "section": "domain risk",
        "item": "만료 경로 시각 비교 UTC 단일 기준 — 비-UTC JVM 조기/지연 만료 차단(DM1)",
        "status": "yes",
        "evidence": "PaymentLoadUseCase.java:42 cutoff=clock.instant().minus(...). JpaPaymentEventRepository.java:20 created_at<:before(Instant). clockDateTimeProvider(UTC)+hibernate.jdbc.time_zone=UTC(application.yml:41) 두 operand UTC wall-clock 일치."
      },
      {
        "section": "domain risk",
        "item": "dedupe 멱등 윈도우 UTC 일관 — TTL 의미 보존(NG3, DM2)",
        "status": "yes",
        "evidence": "payment JdbcPaymentEventDedupeStore.java:116-117 UTC Calendar 쓰기+앱 Instant 만료판정. product JdbcEventDedupeStore.java:98-105 UTC Calendar 쓰기, NOW() 읽기는 default/docker URL connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true(application.yml/docker)로 UTC 세션."
      },
      {
        "section": "domain risk",
        "item": "보상/재시도 멱등성·발행 보장 무변화 — outbox backoff/in-flight 타임아웃/Lua dedup token",
        "status": "yes",
        "evidence": "PgVendorCallService.java:211-212 availableAt=now.plus(computeBackoff) 산식 불변, attempt<4/DLQ 분기 무변경. PgOutbox.create에 clock.instant() 인자 주입만. PaymentOutbox Instant.plus(Duration) backoff 불변(round-3). Lua dedup token P8D 무변경."
      },
      {
        "section": "test gate",
        "item": "신규/수정 business logic 테스트 커버리지 + state machine @EnumSource",
        "status": "yes",
        "evidence": "filtered run 129 tests passed (ApprovedAt/PaymentEvent @EnumSource/Clock/Scheduler). jacoco 미달은 부분 실행 산물(회귀 아님) — 전체 게이트는 verify에서 확인."
      },
      {
        "section": "domain risk",
        "item": "PII plaintext 로그 노출",
        "status": "n/a",
        "evidence": "시각 타입 전환만 — 신규 PII 로깅 없음. fallback 로그도 시각 문자열만."
      }
    ],
    "total": 7,
    "passed": 6,
    "failed": 0,
    "not_applicable": 1
  },

  "scores": {
    "correctness": 0.94,
    "conventions": 0.91,
    "discipline": 0.92,
    "test-coverage": 0.88,
    "domain": 0.95,
    "mean": 0.92
  },

  "findings": [],

  "previous_round_ref": "review-domain-3.md",
  "delta": {
    "newly_passed": [
      "벤더 승인 시각 오프셋 보존 정규화 end-to-end 확인(D8)",
      "outbox 재시도·in-flight 타임아웃 발행 보장 무변화 전체 맥락 재확인"
    ],
    "newly_failed": [],
    "still_failing": []
  },

  "unstuck_suggestion": null
}
```
