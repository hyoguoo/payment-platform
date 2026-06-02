# review-domain-3

**Topic**: TIME-MODEL-AND-EXPIRY
**Round**: 3
**Persona**: Domain Expert

## Reasoning
T17은 PaymentOutbox 도메인/RetryPolicy 호출 경계/엔티티/저장소를 `LocalDateTime`→`Instant`로 통일하는 정합성 무변경 리팩토링이다. retry backoff, in-flight 타임아웃 회수, outbox 폴링 비교, BaseEntity round-trip 모두 UTC wall-clock 단일 기준으로 보존됨을 소스 교차 검증으로 확인했고 outbox 발행 보장·재시도·정합성에 silent 변화가 없다. 도메인 리스크 차원의 차단 사유 없음 → pass.

## Domain risk checklist
- paymentKey/orderId/카드번호 plaintext 로그 노출: 본 diff는 시각 타입 전환만 — 로깅 추가/변경 없음. **n/a**
- 보상/취소 멱등성 가드: 변경 없음. `incrementRetryCount`의 IN_FLIGHT 가드(L59) 보존, DONE/FAILED 재진입 silent PENDING 재활성화 방어 주석·로직 그대로. **yes**
- PG "이미 처리됨" 특수 응답 정당성 검증: 본 태스크 범위 밖(pg-service). **n/a**
- 상태 전이 불변식 위반: PaymentOutbox 상태 머신(PENDING→IN_FLIGHT→DONE/FAILED, PENDING↔재시도) 전이 가드 미변경. enum·예외 코드 동일. **yes**
- race window 락/격리: `claimToInFlight` CAS UPDATE 조건(`status=PENDING AND (nextRetryAt IS NULL OR nextRetryAt<=:now)`) 미변경. REQUIRES_NEW 선점 그대로. **yes**

## 도메인 관점 추가 검토

1. **retry backoff 결과 동일성 — 보존 확인.** `RetryPolicy.nextDelay`(RetryPolicy.java:17-24)는 `Duration` 반환으로 시각 타입과 독립이다. `PaymentOutbox.incrementRetryCount`(PaymentOutbox.java:64)는 `now.plus(policy.nextDelay(retryCount))`로, `Instant.plus(Duration)`이 기존 `LocalDateTime.plus(Duration)`과 동일한 양의 시간 가산을 수행한다. 신규 테스트 PaymentOutboxInstantTest의 FIXED(5000ms)·EXPONENTIAL(1000×2^3=8000ms) 케이스가 backoff 산식 불변을 단정한다. maxAttempts/exhausted 판정도 retryCount 정수 비교라 무관.

2. **in-flight 타임아웃 회수 시각 비교 — TZ 일관.** `recoverTimedOutInFlightRecords`(PaymentOutboxUseCase.java:57-61)에서 cutoff 계산이 `now.minusMinutes(timeoutMinutes)` → `now.minusSeconds(timeoutMinutes * 60L)`로 바뀌었으나 `timeoutMinutes`(int) × 60L(long 승격)로 산술 동일·overflow 없음. cutoff(Instant)는 RepositoryImpl.toLocalDateTime(L79)에서 `LocalDateTime.ofInstant(_, UTC)`로 변환되어 JPQL `e.inFlightAt < :before`(JpaPaymentOutboxRepository) 비교에 들어간다. DB의 `in_flight_at`은 `from()`(PaymentOutboxEntity.java)에서 동일한 `ofInstant(_, UTC)` wall-clock으로 저장되므로 양변 UTC wall-clock으로 일치 — 회수 윈도우 어긋남 없음.

3. **outbox 폴링/claim/future-pending 비교 — UTC 단일 기준 정합.** `findPendingBatch`의 now(PaymentOutboxRepositoryImpl.java:39), `claimToInFlight`의 inFlightAt/now(L57-60), `countFuturePending`(L70) 모두 `ofInstant(_, UTC)` LocalDateTime으로 변환되어 JPQL의 `nextRetryAt <= :now` / `nextRetryAt > :now` 비교에 사용된다. 저장 측 `next_retry_at`도 `from()`에서 동일 변환. 비교 양변이 동일 TZ 규약이라 미래 예약 재시도 판정·발행 대기 판정에 silent drift 없음.

4. **BaseEntity round-trip 정합 — PaymentEvent(T4/T5)와 저장 표현 동일.** PaymentOutbox는 엔티티 필드를 LocalDateTime으로 유지(NG4)하고 `from()/toDomain()`에서 수동 UTC 변환, PaymentEvent는 비즈니스 시각을 엔티티 Instant로 보유하고 Hibernate `time_zone: UTC`(application.yml:41)가 DATETIME↔Instant 매핑을 담당한다. 방식은 다르나 DATETIME 컬럼에 기록되는 값은 둘 다 UTC wall-clock으로 동일. auditing `createdAt/updatedAt`도 `clockDateTimeProvider`(JpaConfig.java)가 `ofInstant(clock.instant(), UTC)`로 채워 동일 기준 — `findOldestPendingCreatedAt`의 `MIN(createdAt)` → `toInstant(UTC)` 환원(L75)이 손실 없이 round-trip한다. Flyway DDL 무변경(DATETIME 유지)도 정합.

5. **발행 보장 사슬 무변화.** outbox PENDING INSERT → AFTER_COMMIT relay → claimToInFlight CAS → Kafka send → toDone, 그리고 워커 타임아웃 회수 경로의 메서드 시그니처는 인자 타입만 바뀌었을 뿐 호출 순서·트랜잭션 경계·예외 전파가 동일하다. 회신 결과 적용 경로(PaymentConfirmResultUseCase) 및 D12 재고 복구 가드는 본 diff에 미포함 — outbox 발행/재시도/정합 보장에 silent 변화 없음.

## Findings
없음 (도메인 리스크 차단 사유 0건).

## JSON
```json
{
  "stage": "code",
  "persona": "domain-expert",
  "round": 3,
  "task_id": "T17",

  "decision": "pass",
  "reason_summary": "PaymentOutbox Instant 전환은 retry backoff·in-flight 타임아웃·outbox 폴링·BaseEntity round-trip 모두 UTC wall-clock 단일 기준으로 정합 보존됨을 소스로 확인. 발행 보장·재시도·정합성에 silent 변화 없음.",

  "checklist": {
    "source": "_shared/checklists/code-ready.md",
    "items": [
      {
        "section": "domain risk",
        "item": "보상/취소 로직에 멱등성 가드 존재 (incrementRetryCount IN_FLIGHT 가드 보존)",
        "status": "yes",
        "evidence": "PaymentOutbox.java:59-61 IN_FLIGHT 가드 + 주석 미변경"
      },
      {
        "section": "domain risk",
        "item": "상태 전이가 불변식을 위반하지 않음 (outbox 상태 머신 전이 가드 미변경)",
        "status": "yes",
        "evidence": "PaymentOutbox.java:34-65 toInFlight/toDone/toFailed/incrementRetryCount 가드 동일"
      },
      {
        "section": "domain risk",
        "item": "race window 경로에 락/격리 고려 (claimToInFlight CAS 미변경)",
        "status": "yes",
        "evidence": "JpaPaymentOutboxRepository.claimToInFlight UPDATE WHERE status=PENDING 조건 미변경"
      },
      {
        "section": "domain risk",
        "item": "시각 비교 TZ 일관 (저장/조회/cutoff 모두 UTC wall-clock)",
        "status": "yes",
        "evidence": "PaymentOutboxRepositoryImpl.java:79 toLocalDateTime + PaymentOutboxEntity.from toLocalDateTime + JpaConfig clockDateTimeProvider 모두 ofInstant(_, UTC)"
      },
      {
        "section": "test gate",
        "item": "신규/수정 business logic 테스트 커버리지 + state machine @EnumSource",
        "status": "yes",
        "evidence": "PaymentOutboxInstantTest 신규 (toInFlight/incrementRetryCount @EnumSource 무효 상태 + FIXED/EXPONENTIAL backoff 단정). 관련 단위 63 tests passed."
      },
      {
        "section": "domain risk",
        "item": "PII plaintext 로그 노출",
        "status": "n/a",
        "evidence": "시각 타입 전환만 — 로깅 추가/변경 없음"
      }
    ],
    "total": 6,
    "passed": 5,
    "failed": 0,
    "not_applicable": 1
  },

  "scores": {
    "correctness": 0.93,
    "conventions": 0.90,
    "discipline": 0.92,
    "test_coverage": 0.88,
    "domain": 0.94,
    "mean": 0.914
  },

  "findings": [],

  "previous_round_ref": "review-domain-2.md",
  "delta": {
    "newly_passed": ["PaymentOutbox 시각 타입 Instant 통일 정합 보존 검증"],
    "newly_failed": [],
    "still_failing": []
  },

  "unstuck_suggestion": null
}
```
