# 결제 재시도 상태 전략 구현 플랜

> 작성일: 2026-04-07

## 목표

재시도 중 상태(RETRYING) 추가, RetryPolicy 도메인 분리, Backoff 지원(nextRetryAt), 실패 분류 예외 변환 제거를 통해
재시도 흐름이 명확하고 정책 변경 없이 설정으로 제어 가능한 구조를 완성한다.

## 컨텍스트

- 설계 문서: [docs/topics/PAYMENT-RETRY-STATE.md](topics/PAYMENT-RETRY-STATE.md)
- 주요 변경 파일:
  - `payment/domain/enums/PaymentEventStatus`
  - `payment/domain/PaymentEvent`
  - `payment/domain/PaymentOutbox`
  - `payment/application/usecase/PaymentCommandUseCase`
  - `payment/application/usecase/PaymentOutboxUseCase`
  - `payment/application/usecase/PaymentTransactionCoordinator`
  - `payment/infrastructure/entity/PaymentOutboxEntity`
  - `payment/infrastructure/repository/JpaPaymentOutboxRepository`
  - `payment/scheduler/OutboxProcessingService`

---

## 진행 상황

<!-- execute 단계에서 각 태스크 완료 시 체크 -->
- [x] Task 1: BackoffType 열거형 + PaymentEventStatus.RETRYING 추가
- [ ] Task 2: RetryPolicy 도메인 레코드
- [ ] Task 3: PaymentEvent 상태 전환 개선
- [ ] Task 4: PaymentOutbox nextRetryAt + incrementRetryCount 개선
- [ ] Task 5: RetryPolicyProperties + application.yml 업데이트
- [ ] Task 6: PaymentOutboxEntity + DB 마이그레이션
- [ ] Task 7: Repository 쿼리 업데이트
- [ ] Task 8: PaymentCommandUseCase 개선
- [ ] Task 9: PaymentTransactionCoordinator — executePaymentRetryWithOutbox 추가
- [ ] Task 10: PaymentOutboxUseCase 개선
- [ ] Task 11: OutboxProcessingService 개선
- [ ] Task 12: 불필요한 예외 클래스 제거

---

## 태스크

### Task 1: BackoffType 열거형 + PaymentEventStatus.RETRYING 추가 [tdd=false]

**구현**
- `payment/domain/enums/BackoffType.java` 신규: `FIXED`, `EXPONENTIAL`
- `payment/domain/enums/PaymentEventStatus.java`: `RETRYING` 추가

**완료 기준**
- 컴파일 오류 없음
- 기존 테스트 통과

**완료 결과**
> `BackoffType.java` 신규, `PaymentEventStatus`에 `RETRYING` 추가. 기존 226개 테스트 전체 통과.

---

### Task 2: RetryPolicy 도메인 레코드 [tdd=true]

**테스트 (RED)**
- `RetryPolicyTest`
  - `isExhausted_retryCount가_maxAttempts_미만이면_false`
  - `isExhausted_retryCount가_maxAttempts_이상이면_true`
  - `nextDelay_FIXED_항상_baseDelayMs_반환`
  - `nextDelay_EXPONENTIAL_retryCount에_따라_지수_증가`
  - `nextDelay_EXPONENTIAL_maxDelayMs_초과하지_않음`

**구현 (GREEN)**
- `payment/domain/RetryPolicy.java` 신규 (record)
  ```
  int maxAttempts, BackoffType backoffType, long baseDelayMs, long maxDelayMs
  boolean isExhausted(int retryCount)
  Duration nextDelay(int retryCount)
  ```

**완료 기준**
- `RetryPolicyTest` 전체 통과
- `./gradlew test` 회귀 없음

**완료 결과**
> (완료 후 작성)

---

### Task 3: PaymentEvent 상태 전환 개선 [tdd=true]

**테스트 (RED)**
- `PaymentEventTest` 신규/확장 — `@ParameterizedTest @EnumSource` 패턴으로 유효/무효 상태 전체 커버
  - `toRetrying_성공` — `@EnumSource(names={"IN_PROGRESS","RETRYING"})` 상태에서 RETRYING 전환 확인
  - `toRetrying_실패` — `@EnumSource(names={"READY","DONE","FAILED","CANCELED","PARTIAL_CANCELED","EXPIRED"})` 에서 예외 발생 확인
  - `toRetrying_호출_시_retryCount_증가`
  - `done_RETRYING_포함_성공` — `@EnumSource(names={"IN_PROGRESS","RETRYING","DONE"})` 에서 DONE 전환 확인 (guard 확장)
  - `fail_RETRYING_포함_성공` — `@EnumSource(names={"READY","IN_PROGRESS","RETRYING"})` 에서 FAILED 전환 확인 (guard 확장)

**구현 (GREEN)**
- `PaymentEvent.java`
  - `RETRYABLE_LIMIT` 상수 제거
  - `toRetrying()` 신규: `IN_PROGRESS || RETRYING` guard, `retryCount++`, `status=RETRYING`
  - `done()` guard: `IN_PROGRESS || RETRYING || DONE` 으로 확장
  - `fail()` guard: `READY || IN_PROGRESS || RETRYING` 으로 확장

**완료 기준**
- 신규 테스트 통과
- 기존 `PaymentEventTest` 회귀 없음
- `./gradlew test` 통과

**완료 결과**
> (완료 후 작성)

---

### Task 4: PaymentOutbox nextRetryAt + incrementRetryCount 개선 [tdd=true]

**테스트 (RED)**
- `PaymentOutboxTest` 신규/확장
  - `incrementRetryCount_retryCount_증가_및_PENDING_복원`
  - `incrementRetryCount_FIXED_backoff_nextRetryAt_설정`
  - `incrementRetryCount_EXPONENTIAL_backoff_nextRetryAt_지수_증가`
  - `incrementRetryCount_호출_후_status가_PENDING`

**구현 (GREEN)**
- `PaymentOutbox.java`
  - `RETRYABLE_LIMIT` 상수 제거
  - `nextRetryAt` 필드 추가 (`LocalDateTime`, 초기값 null)
  - `incrementRetryCount(RetryPolicy policy, LocalDateTime now)` 시그니처 변경:
    `retryCount++`, `status=PENDING`, `nextRetryAt = now.plus(policy.nextDelay(retryCount))`

**완료 기준**
- 신규 테스트 통과
- 기존 `PaymentOutboxTest` 회귀 없음
- `./gradlew test` 통과

**완료 결과**
> (완료 후 작성)

---

### Task 5: RetryPolicyProperties + application.yml 업데이트 [tdd=false]

**구현**
- `payment/application/config/RetryPolicyProperties.java` 신규
  - `@ConfigurationProperties("payment.retry")`
  - 필드: `maxAttempts`, `backoffType` (BackoffType), `baseDelayMs`, `maxDelayMs`
  - application 계층에 배치 — `PaymentOutboxUseCase`가 infra에 의존하는 역방향 의존성 방지
- `application.yml` — 아래 섹션 추가:
  ```yaml
  payment:
    retry:
      max-attempts: 5
      backoff-type: FIXED
      base-delay-ms: 5000
      max-delay-ms: 60000
  ```

**완료 기준**
- 컴파일 오류 없음
- 애플리케이션 컨텍스트 로드 오류 없음
- `./gradlew test` 통과

**완료 결과**
> (완료 후 작성)

---

### Task 6: PaymentOutboxEntity + DB 마이그레이션 [tdd=false]

**구현**
- `PaymentOutboxEntity.java`
  - `next_retry_at` 컬럼 추가 (`LocalDateTime nextRetryAt`, `@Column(nullable = true)`)
  - 기존 인덱스 `idx_payment_outbox_status_created (status, created_at)` 변경
    → `idx_payment_outbox_status_retry_created (status, next_retry_at, created_at)`
  - `toPaymentOutbox()` 및 `from()` 변환 메서드에 `nextRetryAt` 포함
- Flyway/Liquibase 마이그레이션 스크립트 추가 (기존 마이그레이션 관리 방식 확인 후 적용)
  ```sql
  ALTER TABLE payment_outbox ADD COLUMN next_retry_at DATETIME(6) NULL;
  DROP INDEX idx_payment_outbox_status_created ON payment_outbox;
  CREATE INDEX idx_payment_outbox_status_retry_created ON payment_outbox (status, next_retry_at, created_at);
  ```

**완료 기준**
- 컴파일 오류 없음
- 기존 DB 연동 테스트 통과
- `./gradlew test` 통과

**완료 결과**
> (완료 후 작성)

---

### Task 7: Repository 쿼리 업데이트 [tdd=false]

**구현**
- `JpaPaymentOutboxRepository.java`
  - `findPendingBatch` 쿼리 변경:
    기존 `WHERE status = 'PENDING'`
    → `WHERE status = 'PENDING' AND (next_retry_at IS NULL OR next_retry_at <= NOW())`
  - `claimToInFlight` 쿼리 변경:
    기존 `WHERE order_id = :orderId AND status = :fromStatus`
    → `WHERE order_id = :orderId AND status = :fromStatus AND (next_retry_at IS NULL OR next_retry_at <= NOW())`

**완료 기준**
- 쿼리 실행 오류 없음
- 기존 Repository 테스트 통과 (또는 조건에 맞게 수정)
- `./gradlew test` 통과

**완료 결과**
> (완료 후 작성)

---

### Task 8: PaymentCommandUseCase 개선 [tdd=true]

**테스트 (RED)**
- `PaymentCommandUseCaseTest` 신규/확장
  - `markPaymentAsRetrying_PaymentEvent_toRetrying_호출_및_저장`
  - `confirmPaymentWithGateway_SUCCESS_결과_반환`
  - `confirmPaymentWithGateway_RETRYABLE_FAILURE_결과_반환` (예외 아닌 결과 반환 확인)
  - `confirmPaymentWithGateway_NON_RETRYABLE_FAILURE_결과_반환`
  - 기존 `confirmPaymentWithGateway` 예외 관련 테스트 제거

**구현 (GREEN)**
- `PaymentCommandUseCase.java`
  - `markPaymentAsRetrying(orderId)` 신규: `PaymentEvent.toRetrying()` + `paymentEventPort.save()`
  - `confirmPaymentWithGateway()` 반환 타입 변경: `void` → `PaymentConfirmResult`
    예외 변환 switch 제거, 결과를 그대로 반환

**완료 기준**
- 신규 테스트 통과
- 기존 `PaymentCommandUseCaseTest` 회귀 없음
- `./gradlew test` 통과

**완료 결과**
> (완료 후 작성)

---

### Task 9: PaymentTransactionCoordinator — executePaymentRetryWithOutbox 추가 [tdd=true]

**테스트 (RED)**
- `PaymentTransactionCoordinatorTest` 신규/확장
  - `executePaymentRetryWithOutbox_Outbox_PENDING_복원_및_PaymentEvent_RETRYING_전환`
  - `executePaymentRetryWithOutbox_단일_트랜잭션_내_실행` (TX 전파 확인, integration-level)

**구현 (GREEN)**
- `PaymentTransactionCoordinator.java`
  - `executePaymentRetryWithOutbox(String orderId, RetryPolicy policy, LocalDateTime now)` 신규
    `@Transactional`: `outbox.incrementRetryCount(policy, now)` → `paymentEvent.toRetrying()` → 두 aggregate 저장

**완료 기준**
- 신규 테스트 통과
- `./gradlew test` 통과

**완료 결과**
> (완료 후 작성)

---

### Task 10: PaymentOutboxUseCase 개선 [tdd=true]

**테스트 (RED)**
- `PaymentOutboxUseCaseTest` 신규/확장
  - `incrementRetryOrFail_미소진_시_executePaymentRetryWithOutbox_호출`
  - `incrementRetryOrFail_소진_시_executePaymentFailureCompensationWithOutbox_호출`
  - `recoverTimedOutInFlightRecords_incrementRetryCount에_RetryPolicy_전달`

**구현 (GREEN)**
- `PaymentOutboxUseCase.java`
  - `RetryPolicyProperties` 주입, 내부에서 `RetryPolicy` 인스턴스 생성
  - `incrementRetryOrFail(orderId)` 개선:
    - `RetryPolicy.isExhausted(retryCount)` 로 소진 여부 판단
    - 미소진: `coordinator.executePaymentRetryWithOutbox(orderId, policy, now)`
    - 소진: `coordinator.executePaymentFailureCompensationWithOutbox(orderId)`
  - `recoverTimedOutInFlightRecords()`: `outbox.incrementRetryCount(policy, now)` 시그니처 맞춤

**완료 기준**
- 신규 테스트 통과
- 기존 `PaymentOutboxUseCaseTest` 회귀 없음
- `./gradlew test` 통과

**완료 결과**
> (완료 후 작성)

---

### Task 11: OutboxProcessingService 개선 [tdd=true]

**테스트 (RED)**
- `OutboxProcessingServiceTest` 신규/확장
  - `process_SUCCESS_결과_시_성공_완료_처리`
  - `process_RETRYABLE_FAILURE_결과_시_incrementRetryOrFail_호출`
  - `process_NON_RETRYABLE_FAILURE_결과_시_보상_처리`
  - `process_예외_기반_분기_테스트_제거` (PaymentTossRetryableException catch 테스트)

**구현 (GREEN)**
- `OutboxProcessingService.java`
  - `try-catch(PaymentTossRetryableException | PaymentTossNonRetryableException)` 제거
  - `confirmPaymentWithGateway()` 반환값(`PaymentConfirmResult`)으로 switch 분기:
    - `SUCCESS` → `executePaymentSuccessCompletionWithOutbox()`
    - `RETRYABLE_FAILURE` → `paymentOutboxUseCase.incrementRetryOrFail()`
    - `NON_RETRYABLE_FAILURE` → `executePaymentFailureCompensationWithOutbox()`

**완료 기준**
- 신규 테스트 통과
- 기존 `OutboxProcessingServiceTest` 회귀 없음
- `./gradlew test` 통과

**완료 결과**
> (완료 후 작성)

---

### Task 12: 불필요한 예외 클래스 제거 [tdd=false]

**구현**
- `payment/exception/PaymentTossRetryableException.java` 삭제
- `payment/exception/PaymentTossNonRetryableException.java` 삭제
- 삭제 후 컴파일 오류 발생 시 잔여 참조 제거

**완료 기준**
- 컴파일 오류 없음
- `./gradlew test` 통과

**완료 결과**
> (완료 후 작성)