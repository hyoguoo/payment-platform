# PAYMENT-DOUBLE-FAULT-RECOVERY — 실행 계획

> Topic: [docs/topics/PAYMENT-DOUBLE-FAULT-RECOVERY.md](topics/PAYMENT-DOUBLE-FAULT-RECOVERY.md)
> 작성일: 2026-04-09
> Plan Round: 2

---

## 개요

F1~F7 이중 장애(Double Fault) 복구 경로의 critical 체인을 단일 라운드에 해소한다.
레이어 의존 순서: port → domain → application → infrastructure → scheduler

Round 1 대비 변경:
- Task 3 테스트 스펙: domain 테스트가 `TossPaymentErrorCode`를 직접 참조하는 layer 역의존 제거 → String 입력 기반으로 변경
- Task 4 테스트 경로 교정: `paymentgateway/exception/common/` 로 패키지 미러링
- Task 4.5 신규: gateway→domain 매핑 어댑터 수정 태스크 삽입 (F-C-2 orphan 해소)
- Task 5 보강: `PaymentEvent.fail` 허용 source state 명시 (F-D-3)
- Task 8 분할: domain 헬퍼 태스크(8a) + scheduler 분기 태스크(8b) (F-C-5)
- Task 8b 보강: DONE+approvedAt=null budget 소진 시 SUSPENDED 격리 경로 명시 (F-D-1 money-leak 방지)
- Task 12 재정의: Guard-at-caller 단일화, 한 컨텍스트 한 파일 (F-C-1 / F-D-2)
- Task 9 산출물 설명: port↔Jpa 책임 분할 명시 (F-C-6)

---

## 태스크 목록

---

### Task 1 — PaymentOutboxRepository 포트에 CAS 복구 메서드 추가

- **목적**: §4-3 결정 — `PaymentOutboxRepository` 포트에 `recoverTimedOutInFlight` 시그니처 추가. infrastructure 구현보다 소비자(use-case)가 먼저 이 시그니처를 바라보도록 포트를 선행 배치.
- **결정 ID**: §4-3, §4-2 (F4 CAS 전환), §8 트랜잭션 경계
- `tdd: false`
- `domain_risk: false`

**산출물**

- `src/main/java/com/hyoguoo/paymentplatform/payment/application/port/PaymentOutboxRepository.java`
  - 신규 메서드 시그니처 추가:
    ```
    int recoverTimedOutInFlight(LocalDateTime cutoff, LocalDateTime now, Duration nextDelay);
    ```

---

### Task 2 — PaymentErrorCode 에러코드 추가 (DONE_REQUIRES_APPROVED_AT)

- **목적**: §4-1 결정 — `PaymentEvent.done(approvedAt)` 불변식 강화 시 던질 신규 에러코드 도입. 도메인 계층 의존 항목이므로 선행 배치.
- **결정 ID**: §4-1 (F1)
- `tdd: false`
- `domain_risk: false`

**산출물**

- `src/main/java/com/hyoguoo/paymentplatform/payment/exception/common/PaymentErrorCode.java`
  - 신규 에러코드: `DONE_REQUIRES_APPROVED_AT`

---

### Task 3 — PaymentConfirmResultStatus에 ALREADY_PROCESSED 추가

- **목적**: §4-2 결정 — `PaymentConfirmResultStatus` enum에 `ALREADY_PROCESSED` 추가. domain enum이므로 application 계층 use-case보다 선행. `of(String rawCode)` 매핑은 String 입력만 사용하여 domain이 gateway enum에 의존하지 않도록 경계를 유지한다.
- **결정 ID**: §4-2 (F5, D5), §4-3 경계 준수
- `tdd: true`
- `domain_risk: true` — 결제 결과 분기 정의, 결제 상태 전이 근거 enum

**테스트 스펙**

- 파일: `src/test/java/com/hyoguoo/paymentplatform/payment/domain/PaymentConfirmResultStatusTest.java`
- 테스트 메서드:
  - `of_alreadyProcessedCode_returnsAlreadyProcessed`: `of("ALREADY_PROCESSED_PAYMENT")` → `ALREADY_PROCESSED` (String 기반 — gateway enum import 없음)
  - `of_successCode_returnsSuccess`: `of("<isSuccess=true 인 일반 성공 코드>")` → `SUCCESS`
  - `of_retryableFailure_returnsRetryable`: retryable 실패 문자열 코드 → `RETRYABLE_FAILURE`
  - `of_nonRetryableFailure_returnsNonRetryable`: non-retryable 실패 문자열 코드 → `NON_RETRYABLE_FAILURE`

  > 주의: 테스트는 `TossPaymentErrorCode`를 import하지 않는다. `of(String)` 메서드는 문자열 상수만 받으며, `isAlreadyProcessed()` 플래그 판정은 Task 4.5의 매퍼/어댑터 단위 테스트에서 수행한다.

**산출물**

- `src/main/java/com/hyoguoo/paymentplatform/payment/domain/dto/enums/PaymentConfirmResultStatus.java`

---

### Task 4 — TossPaymentErrorCode에 isAlreadyProcessed() 플래그 추가

- **목적**: §4-3 결정 옵션(ii) — `isSuccess()` 및 기존 `isFailure()/isRetryable()` 연동 무변경 유지. `ALREADY_PROCESSED_PAYMENT`에만 `isAlreadyProcessed=true` 추가. gateway enum 수정 범위를 단일 항목으로 한정.
- **결정 ID**: §4-3 (D5), §4-2 매핑 분리
- `tdd: true`
- `domain_risk: false`

**테스트 스펙**

- 파일: `src/test/java/com/hyoguoo/paymentplatform/paymentgateway/exception/common/TossPaymentErrorCodeTest.java`
  - (production 패키지 `paymentgateway/exception/common/`과 미러링)
- 테스트 메서드:
  - `isAlreadyProcessed_alreadyProcessedPayment_returnsTrue`: `ALREADY_PROCESSED_PAYMENT.isAlreadyProcessed()` → `true`
  - `isAlreadyProcessed_otherCodes_returnsFalse`: `@ParameterizedTest @EnumSource`(mode=EXCLUDE, `ALREADY_PROCESSED_PAYMENT`) 전 코드 → `false`
  - `isSuccess_alreadyProcessedPayment_unchanged`: `ALREADY_PROCESSED_PAYMENT.isSuccess()` 기존 동작 불변 검증

**산출물**

- `src/main/java/com/hyoguoo/paymentplatform/paymentgateway/exception/common/TossPaymentErrorCode.java`

---

### Task 4.5 — gateway→domain 매핑 어댑터에 ALREADY_PROCESSED 분기 추가 (F5 체인 완결)

- **목적**: F5 체인 완결 — `TossPaymentErrorCode.isAlreadyProcessed()` 플래그(Task 4)가 실제로 `PaymentConfirmResultStatus.ALREADY_PROCESSED`(Task 3)로 전달되려면 gateway→domain 매핑 지점에 분기가 추가되어야 한다. 현재 매핑을 수행하는 세 곳 중 하나 이상에 `isAlreadyProcessed()` 검사를 삽입하고, 해당 어댑터 단위 테스트로 매핑 정확성을 검증한다.
- **결정 ID**: §4-2 (F5), §4-3 gateway/domain 경계(D5)
- `tdd: true`
- `domain_risk: true` — 외부 PG 연동 결과 분기, 결제 상태 전이 근거

**수정 위치** (다음 세 파일 중 매핑이 실제 이루어지는 위치 확인 후 해당 파일 수정):
- `src/main/java/com/hyoguoo/paymentplatform/payment/infrastructure/gateway/toss/TossPaymentGatewayStrategy.java`
- `src/main/java/com/hyoguoo/paymentplatform/payment/infrastructure/PaymentInfrastructureMapper.java`
- `src/main/java/com/hyoguoo/paymentplatform/paymentgateway/application/usecase/TossApiFailureUseCase.java`

  execute 단계에서 위 세 파일 중 실제 `PaymentConfirmResultStatus.of(...)` 호출이 이루어지는 파일을 특정하고, 해당 파일에서만 `isAlreadyProcessed()` 분기를 추가한다.

**테스트 스펙**

- 파일: 수정한 어댑터/매퍼에 대응하는 기존 또는 신규 단위 테스트 파일
  - 예시 후보: `TossPaymentGatewayStrategyTest.java` 또는 `PaymentInfrastructureMapperTest.java` 또는 `TossApiFailureUseCaseTest.java`
- 테스트 메서드:
  - `map_alreadyProcessedErrorCode_returnsAlreadyProcessed`: gateway 응답에 `ALREADY_PROCESSED_PAYMENT` 코드가 담겼을 때 → `PaymentConfirmResultStatus.ALREADY_PROCESSED` 반환 검증
  - `map_successCode_returnsSuccess`: 일반 success 코드 → `SUCCESS` (회귀 방지)
  - `map_retryableErrorCode_returnsRetryable`: retryable 실패 코드 → `RETRYABLE_FAILURE` (회귀 방지)

**산출물**

- 위 세 파일 중 실제 매핑 위치 단일 파일 수정

---

### Task 5 — PaymentEvent.done() 불변식 강화 + fail() source state 정책 명시 (F1, D1, D3, F-D-3)

- **목적**: §4-1 결정 — `done(approvedAt)` 호출 시 `approvedAt == null` 거부 + DONE→DONE 자기루프 금지. READY-only execute 가드(F6)도 동시 적용. `fail()` 허용 source state(READY/IN_PROGRESS/RETRYING)를 테스트로 명시하여 보상 경로 일관성 확보(F-D-3).
- **결정 ID**: §4-1 (F1, F6, D1), §5-1 상태 다이어그램, F-D-3
- `tdd: true`
- `domain_risk: true` — 결제 상태 전이, 도메인 불변식

**테스트 스펙**

- 파일: `src/test/java/com/hyoguoo/paymentplatform/payment/domain/PaymentEventTest.java` (기존 파일 확장)
- 테스트 메서드:
  - `done_nullApprovedAt_throwsPaymentStatusException`: `approvedAt=null` 전달 → `PaymentStatusException` (`DONE_REQUIRES_APPROVED_AT`)
  - `done_nonNullApprovedAt_fromInProgress_succeeds`: IN_PROGRESS + non-null → DONE 전환 성공
  - `done_nonNullApprovedAt_fromRetrying_succeeds`: RETRYING + non-null → DONE 전환 성공
  - `done_reentryFromDone_throwsPaymentStatusException`: DONE→DONE 재진입 → `PaymentStatusException` (D1)
  - `done_invalidSourceStates_throwsPaymentStatusException`: `@ParameterizedTest @EnumSource`(READY, FAILED, EXPIRED) → `PaymentStatusException`
  - `execute_readyOnly_succeeds`: READY → IN_PROGRESS 성공 (F6)
  - `execute_nonReadyStates_throwsPaymentStatusException`: `@ParameterizedTest @EnumSource`(IN_PROGRESS, RETRYING, DONE, FAILED, EXPIRED) → `PaymentStatusException` (F6)
  - `fail_allowedSourceStates_succeeds`: `@ParameterizedTest @EnumSource`(READY, IN_PROGRESS, RETRYING) → FAILED 전환 성공 (F-D-3 명시)
  - `fail_invalidSourceStates_throwsPaymentStatusException`: `@ParameterizedTest @EnumSource`(DONE, FAILED, EXPIRED) → `PaymentStatusException` (F-D-3 경계 확인)

**산출물**

- `src/main/java/com/hyoguoo/paymentplatform/payment/domain/PaymentEvent.java`

---

### Task 6 — PaymentCommandUseCase.confirmPaymentWithGateway 실패 응답 정정 (F2)

- **목적**: §4-2 결정 — `SUCCESS`가 아닌 결과 status에서 `PaymentDetails` null 조립. "유령 DONE" DTO 상류 전파 제거.
- **결정 ID**: §4-2 (F2)
- `tdd: true`
- `domain_risk: true` — 결제 상태 전이 근거 DTO 조립, 외부 PG 연동 결과 처리

**테스트 스펙**

- 파일: `src/test/java/com/hyoguoo/paymentplatform/payment/application/usecase/PaymentCommandUseCaseTest.java` (기존 파일 확장)
- 테스트 메서드:
  - `confirmPaymentWithGateway_failureResponse_paymentDetailsIsNull`: `RETRYABLE_FAILURE` 응답 → `PaymentDetails == null` 검증 (F2)
  - `confirmPaymentWithGateway_nonRetryableFailure_paymentDetailsIsNull`: `NON_RETRYABLE_FAILURE` 응답 → `PaymentDetails == null`
  - `confirmPaymentWithGateway_alreadyProcessed_paymentDetailsIsNull`: `ALREADY_PROCESSED` 응답 → `PaymentDetails == null`
  - `confirmPaymentWithGateway_success_paymentDetailsPresent`: `SUCCESS` + non-null approvedAt → `PaymentDetails` 정상 조립

**산출물**

- `src/main/java/com/hyoguoo/paymentplatform/payment/application/usecase/PaymentCommandUseCase.java`

---

### Task 7 — PaymentOutboxUseCase.recoverTimedOutInFlightRecords CAS 전환 (F4)

- **목적**: §4-2 결정 — read-then-save 제거, 신규 포트 메서드 `recoverTimedOutInFlight` 위임. bulk CAS UPDATE를 통해 다중 워커 경합 해소.
- **결정 ID**: §4-2 (F4), §4-3, §8 트랜잭션 경계
- `tdd: true`
- `domain_risk: true` — race window, 멱등성 보장

**테스트 스펙**

- 파일: `src/test/java/com/hyoguoo/paymentplatform/payment/application/usecase/PaymentOutboxUseCaseTest.java` (기존 파일 확장)
- 테스트 메서드:
  - `recoverTimedOutInFlightRecords_delegatesToCasPort_returnsUpdatedCount`: Mock 포트 호출 검증 — `recoverTimedOutInFlight(cutoff, now, nextDelay)` 1회 호출, 반환값 로그
  - `recoverTimedOutInFlightRecords_zeroUpdated_logsInfo`: `updatedRows=0` → 로그 확인 (alert 없음)
  - `recoverTimedOutInFlightRecords_positiveUpdated_logsRecovery`: `updatedRows=3` → `cas_recovered=3` 로그 키 확인

**산출물**

- `src/main/java/com/hyoguoo/paymentplatform/payment/application/usecase/PaymentOutboxUseCase.java`

---

### Task 8a — PaymentOutboxStatus에 SUSPENDED 추가 + PaymentStatusResult 분류 헬퍼 (F-D-1, D4)

- **목적**: F-D-1 money-leak 방지 — `DONE+approvedAt=null` 레코드가 retry budget 소진 시 일반 `RETRYABLE_FAILURE → executePaymentFailureCompensationWithOutbox` 경로로 흘러 실제 승인된 결제가 FAILED+재고복구되는 money-leak를 차단한다. `PaymentOutboxStatus.SUSPENDED` 신규 상태로 격리하여 보상 경로 진입을 원천 차단. 또한 `PaymentStatus` 9-value exhaustive 분기를 scheduler가 직접 소유하지 않도록 `PaymentStatusResult`에 분류 헬퍼를 추가한다(§4-3에서 `isDone()` 단일 판정 금지, 분기 추가는 허용).
- **결정 ID**: §4-2 (F-D-1, D4), §6 장애 시나리오 3번
- `tdd: true`
- `domain_risk: true` — money-leak, 결제 상태 전이, 멱등성

**설계 결정: DONE+approvedAt=null budget 소진 처리**

topic.md §4-2 매핑표 2행(`DONE`+null → RETRYABLE+alert)은 budget 소진 이후 경로를 명시하지 않는다. 본 태스크에서 다음으로 확정한다:
- `DONE+approvedAt=null` 분기에서 budget 소진 시 `PaymentOutbox.toSuspended(reason=DONE_NULL_APPROVED_AT)` 전환. `PaymentEvent` 상태는 **변경하지 않는다** (IN_PROGRESS/RETRYING 유지). 보상 경로(`executePaymentFailureCompensationWithOutbox`)는 절대 호출하지 않는다.
- 이 레코드는 `status=SUSPENDED` 조회 쿼리로 운영 수동 복구 대상이 된다.
- 일반 budget 소진(`RETRYABLE_FAILURE` 축적 시)은 기존대로 `PaymentEvent.fail` + `PaymentOutbox.toFailed(RETRY_BUDGET_EXHAUSTED)`.

**추가 헬퍼 (PaymentStatusResult)**

`PaymentStatusResult`에 다음 분류 헬퍼를 추가하여 scheduler의 9-value switch 응집도를 개선한다:
- `isSuccessfullyDone()`: `PaymentStatus.DONE && approvedAt != null`
- `isDoneWithNullApprovedAt()`: `PaymentStatus.DONE && approvedAt == null`
- `isTerminalFailure()`: `CANCELED || ABORTED || EXPIRED`
- `isStillPending()`: `IN_PROGRESS || WAITING_FOR_DEPOSIT || READY || PARTIAL_CANCELED`

scheduler는 이 4분류만 사용한다.

**테스트 스펙**

- 파일 A: `src/test/java/com/hyoguoo/paymentplatform/payment/domain/PaymentStatusResultTest.java` (신규)
  - `isSuccessfullyDone_doneWithNonNullApprovedAt_returnsTrue`
  - `isSuccessfullyDone_doneWithNullApprovedAt_returnsFalse`
  - `isDoneWithNullApprovedAt_doneNullApprovedAt_returnsTrue`
  - `isTerminalFailure_canceledAbortedExpired_returnsTrue`: `@ParameterizedTest @EnumSource`(CANCELED, ABORTED, EXPIRED)
  - `isTerminalFailure_otherStatuses_returnsFalse`: `@ParameterizedTest @EnumSource`(mode=EXCLUDE, CANCELED/ABORTED/EXPIRED)
  - `isStillPending_pendingStatuses_returnsTrue`: `@ParameterizedTest @EnumSource`(IN_PROGRESS, WAITING_FOR_DEPOSIT, READY, PARTIAL_CANCELED)
- 파일 B: `src/test/java/com/hyoguoo/paymentplatform/payment/domain/PaymentOutboxTest.java` (기존 확장)
  - `toSuspended_fromInFlight_setsSuspendedStatus`: IN_FLIGHT 상태 outbox → `toSuspended(DONE_NULL_APPROVED_AT)` 호출 → status=SUSPENDED 확인
  - `toSuspended_reason_isPreserved`: reason 필드 보존 확인

**산출물**

- `src/main/java/com/hyoguoo/paymentplatform/payment/domain/dto/PaymentStatusResult.java` (헬퍼 추가)
- `src/main/java/com/hyoguoo/paymentplatform/payment/domain/PaymentOutbox.java` (`toSuspended` 메서드 추가, `SUSPENDED` 상태 추가)

---

### Task 8b — OutboxProcessingService ALREADY_PROCESSED 분기 + getStatus 폴백 + 3-way 분기 (F3, F5, F7, D4)

- **목적**: §4-2 결정 — `ALREADY_PROCESSED` 분기 추가 및 `getStatusByOrderId` 폴백 호출. Task 8a의 `PaymentStatusResult` 헬퍼(isSuccessfullyDone/isDoneWithNullApprovedAt/isTerminalFailure/isStillPending)를 사용한 3-way 분기로 exhaustive switch 응집도 확보. F3 null 가드, C2 LogFmt 키 확정. F-D-1 money-leak 방지 경로 포함.
- **결정 ID**: §4-2 (F3, F5, F7, D4), §6 장애 시나리오, §7 검증 전략, §8 TX 경계
- `tdd: true`
- `domain_risk: true` — 외부 PG 연동, 결제 상태 전이, 멱등성, 정합성, race window, money-leak

**테스트 스펙**

- 파일: `src/test/java/com/hyoguoo/paymentplatform/payment/scheduler/OutboxProcessingServiceTest.java` (기존 파일 확장)
- 테스트 메서드:
  - `process_success_executeSuccessCompletion`: `SUCCESS` + non-null approvedAt → `executePaymentSuccessCompletionWithOutbox` 1회 호출
  - `process_success_nullApprovedAt_domainExceptionPropagates`: `SUCCESS` + approvedAt null → `PaymentStatusException` 전파 (F1 이중 방패, Task 5 연동)
  - `process_alreadyProcessed_pgStatusDone_nonNullApprovedAt_executesSuccess`: `ALREADY_PROCESSED` → `getStatusByOrderId` 호출 → `isSuccessfullyDone()=true` → `executePaymentSuccessCompletionWithOutbox` (F5/F7)
  - `process_alreadyProcessed_pgStatusDone_nullApprovedAt_incrementsRetryAndAlerts`: `ALREADY_PROCESSED` → `isDoneWithNullApprovedAt()=true` → skip + `incrementRetry` + LogFmt `alert=true` (§4-2 매핑표 2행, F3)
  - `process_alreadyProcessed_pgStatusDone_nullApprovedAt_budgetExhausted_suspends`: `isDoneWithNullApprovedAt()=true` + budget 소진 → `PaymentOutbox.toSuspended(DONE_NULL_APPROVED_AT)` + `PaymentEvent` 상태 불변 (F-D-1 money-leak 방지)
  - `process_alreadyProcessed_pgStatusTerminalFailure_executesFailureCompensation`: `isTerminalFailure()=true` (CANCELED/ABORTED/EXPIRED) → `executePaymentFailureCompensationWithOutbox` (D4, money-risk) `@ParameterizedTest`
  - `process_alreadyProcessed_pgStatusStillPending_skipAndIncrementRetry`: `isStillPending()=true` (IN_PROGRESS/WAITING_FOR_DEPOSIT/READY/PARTIAL_CANCELED) → skip + incrementRetry `@ParameterizedTest`
  - `process_alreadyProcessed_getStatusThrows_retryableFallback`: `getStatusByOrderId` 예외 → RETRYABLE 폴백 (§6 장애 2번)
  - `process_retryBudgetExhausted_marksOutboxAndEventFailed`: 일반 RETRYABLE retry count >= max → `PaymentOutbox.toFailed(RETRY_BUDGET_EXHAUSTED)` + `PaymentEvent.fail` (C1)
  - `process_alreadyProcessed_pgStatusReady_alert`: `READY` 분기 → skip + incrementRetry + alert LogFmt 포함 확인

**산출물**

- `src/main/java/com/hyoguoo/paymentplatform/payment/scheduler/OutboxProcessingService.java`

---

### Task 9 — JpaPaymentOutboxRepository CAS 쿼리 구현 (F4)

- **목적**: §4-3 결정 — `@Modifying @Query` bulk UPDATE 쿼리 구현. `PaymentOutboxRepositoryImpl`에서 포트 메서드 위임.
  - 책임 분할: 포트 시그니처(`Duration nextDelay`)와 Jpa 쿼리 파라미터(`LocalDateTime nextRetryAt`)를 분리한다. `PaymentOutboxRepositoryImpl`이 `now.plus(nextDelay)` 계산을 수행하고 Jpa 메서드에 `LocalDateTime`으로 전달한다. retry 정책(`Duration nextDelay`)은 application 계층(use-case)에 머물고, infrastructure는 "언제까지"(`LocalDateTime`)만 알게 된다.
- **결정 ID**: §4-3 (F4), §8 트랜잭션 경계
- `tdd: false`
- `domain_risk: false`

**산출물**

- `src/main/java/com/hyoguoo/paymentplatform/payment/infrastructure/repository/JpaPaymentOutboxRepository.java`
  - 신규 `@Modifying @Query` 벌크 UPDATE 메서드 (`LocalDateTime nextRetryAt` 파라미터)
- `src/main/java/com/hyoguoo/paymentplatform/payment/infrastructure/repository/PaymentOutboxRepositoryImpl.java`
  - 포트 `recoverTimedOutInFlight(cutoff, now, nextDelay)` 구현: `now.plus(nextDelay)` 계산 후 Jpa 메서드에 `nextRetryAt`으로 위임

---

### Task 10 — PaymentOutboxUseCaseConcurrentRecoverIT 통합 테스트 (F4 CAS 동시성)

- **목적**: §7 검증 전략 — 2개 스레드가 동일 `recoverTimedOutInFlight`를 동시 호출할 때 CAS로 정확히 1회만 PENDING 전환됨을 실제 DB로 검증. F4 회귀 방지.
- **결정 ID**: §7 통합 테스트, §6 장애 시나리오 4번
- `tdd: true`
- `domain_risk: true` — race window, 멱등성

**테스트 스펙**

- 파일: `src/test/java/com/hyoguoo/paymentplatform/payment/application/usecase/PaymentOutboxUseCaseConcurrentRecoverIT.java` (신규, `BaseIntegrationTest` 상속)
- 테스트 메서드:
  - `recoverTimedOutInFlight_concurrentWorkers_onlyOneSucceeds`: 2개 스레드 동시 호출 → 총 `updatedRows` 합 = IN_FLIGHT 레코드 수 (중복 없음)

**산출물**

- `src/test/java/com/hyoguoo/paymentplatform/payment/application/usecase/PaymentOutboxUseCaseConcurrentRecoverIT.java`

---

### Task 11 — OutboxDoubleFaultRecoveryIT 통합 테스트 (전체 복구 경로)

- **목적**: §7 검증 전략 — IN_FLIGHT 레코드 주입 → recover → ALREADY_PROCESSED 시나리오(Fake PG) → DONE + approvedAt 실제 값 확정 엔드-투-엔드 검증.
- **결정 ID**: §7 통합 테스트, §5-3 시퀀스, §6 장애 시나리오 1~6번
- `tdd: true`
- `domain_risk: true` — 결제 상태 전이, 멱등성, 외부 PG 연동, 정합성

**테스트 스펙**

- 파일: `src/test/java/com/hyoguoo/paymentplatform/payment/scheduler/OutboxDoubleFaultRecoveryIT.java` (신규, `BaseIntegrationTest` 상속)
- 테스트 메서드:
  - `doubleFaultRecovery_alreadyProcessed_pgDone_completesWithApprovedAt`: IN_FLIGHT 레코드 → recover → confirm returns `ALREADY_PROCESSED` → PG status `DONE`+approvedAt → `PaymentEvent.status=DONE`, `PaymentEvent.approvedAt != null` 검증
  - `doubleFaultRecovery_alreadyProcessed_pgCanceled_completesWithFailed`: `CANCELED` 응답 → `PaymentEvent.status=FAILED` + 재고 복구 1회
  - `doubleFaultRecovery_alreadyProcessed_pgDoneNullApprovedAt_remainsPending`: `DONE`+approvedAt null → 레코드 PENDING 유지, DONE 미전환

**산출물**

- `src/test/java/com/hyoguoo/paymentplatform/payment/scheduler/OutboxDoubleFaultRecoveryIT.java`

---

### Task 12 — PaymentTransactionCoordinator Guard-at-caller 보상 멱등 가드 (F-C-1, F-D-2, §6-9)

- **목적**: F-D-2 — CANCELED/ABORTED/EXPIRED 분기(Task 8b 신설)에서 `executePaymentFailureCompensationWithOutbox` 중복 호출 시 재고 이중 복구 차단. **Guard-at-caller** 전략: `PaymentTransactionCoordinator.executePaymentFailureCompensationWithOutbox` 진입 시 `PaymentEvent.status == FAILED`이면 `ProductPort` 호출 자체를 skip. product 컨텍스트는 수정하지 않으며, payment 컨텍스트 단일 파일(PaymentTransactionCoordinator)만 수정한다.
- **결정 ID**: §6 장애 시나리오 9번, §11-4 plan 이월 항목
- `tdd: true`
- `domain_risk: true` — 멱등성, 정합성, 재고 데이터 일관성

**Guard 설계**:
- `executePaymentFailureCompensationWithOutbox(PaymentEvent event, PaymentOutbox outbox, ...)` 내부에서 `event.getStatus() == PaymentEventStatus.FAILED`이면 `ProductPort.increaseStockForOrders` 및 `PaymentEvent.fail` 재호출을 skip하고 `PaymentOutbox.toFailed`만 수행.
- "이미 FAILED인 이벤트에 대해서는 재고 복구 없이 outbox만 terminal로 닫는다"는 불변식을 payment 컨텍스트 application 계층에서 보장한다.

**테스트 스펙**

- 파일: `src/test/java/com/hyoguoo/paymentplatform/payment/application/usecase/PaymentTransactionCoordinatorTest.java` (기존 파일 확장)
- 테스트 메서드:
  - `executePaymentFailureCompensation_alreadyFailed_skipsStockRestore`: `PaymentEvent.status=FAILED` 레코드에 `executePaymentFailureCompensationWithOutbox` 호출 → `ProductPort.increaseStockForOrders` 0회 호출 검증
  - `executePaymentFailureCompensation_alreadyFailed_closesOutbox`: `PaymentEvent.status=FAILED` → `PaymentOutbox.toFailed` 1회 호출 확인 (outbox는 닫힌다)
  - `executePaymentFailureCompensation_inProgress_executesFullCompensation`: `PaymentEvent.status=IN_PROGRESS` → 정상 보상 경로 수행 (회귀 방지)

**산출물**

- `src/main/java/com/hyoguoo/paymentplatform/payment/application/usecase/PaymentTransactionCoordinator.java`

---

## discuss 리스크 → 태스크 교차 참조 테이블

| 리스크 ID | 심각도 | 내용 요약 | 대응 태스크 |
|-----------|--------|-----------|-------------|
| F1 | Critical | `PaymentEvent.done()` approvedAt=null 무조건 수용 | Task 5 |
| F2 | Moderate | 실패/재시도 응답에서 `PaymentDetails` DONE 하드코딩 | Task 6 |
| F3 | Critical | `OutboxProcessingService` null 가드 없이 approvedAt 전달 | Task 8b |
| F4 | Major | `recoverTimedOutInFlightRecords` read-then-save, 다중 워커 경합 | Task 7, Task 9, Task 10 |
| F5 | Critical | `ALREADY_PROCESSED_PAYMENT.isSuccess()=true` → approvedAt=null DONE 확정 | Task 3, Task 4, Task 4.5, Task 8b |
| F6 | Moderate | `execute()` READY/IN_PROGRESS 모두 허용, 재진입 방어 없음 | Task 5 |
| F7 | Major | 복구 시 무조건 재confirm, `getStatusByOrderId` 미사용 | Task 8b |
| C1 (critic-1) | Major | retry budget 포기 조건 미결 | Task 8b (budget 소진 → `RETRY_BUDGET_EXHAUSTED`) |
| C2 (critic-1) | Minor | 런타임 관측 수단 미확정 | Task 7 (cas_recovered), Task 8b (already_processed/alert 로그) |
| D1 (domain-1) | Minor | `done()` DONE→DONE 자기루프 허용 | Task 5 |
| D2 (domain-1) | Minor | 복구 시 PaymentEvent 상태 불변 비명시 | Task 8b (상태 불변 테스트) |
| D3 (domain-1) | Minor | recover 외 read-then-save 잔존 — plan 전수 감사 | Task 7 (use-case 경로 감사), §11 non-goal 이월 확인 |
| D4 (domain-1) | Major | `getStatusByOrderId` 응답 취소류 분기 누락 | Task 8a (헬퍼), Task 8b (3-way 분기) |
| D5 (domain-1) | Minor | gateway/domain enum 수정 경계 불명확 | Task 4 (isAlreadyProcessed 플래그 분리), Task 3 (String 기반), Task 4.5 (매퍼) |
| F-C-1 (critic-1) | Critical | Task 12 cross-context 모호, ProductPort 우회 위험 | Task 12 (Guard-at-caller 단일화) |
| F-C-2 (critic-1) | Critical | gateway→domain 매핑 어댑터 orphan | Task 4.5 (신규) |
| F-C-3 (critic-1) | Critical | Task 3 domain 테스트 → gateway enum 역의존 | Task 3 (String 기반으로 교정) |
| F-C-4 (critic-1) | Major | Task 4 테스트 패키지 불일치 | Task 4 (경로 교정) |
| F-C-5 (critic-1) | Major | Task 8 scheduler 응집도 초과, 2시간 초과 | Task 8a + 8b 분할 |
| F-C-6 (critic-1) | Minor | Task 9 책임 분할 미명시 | Task 9 (산출물 설명 명시) |
| F-D-1 (domain-1) | Critical | DONE+approvedAt=null budget 소진 → money-leak | Task 8a (SUSPENDED), Task 8b (분기 테스트) |
| F-D-2 (domain-1) | Major | restoreForOrders 멱등성 가드 위치 미결 | Task 12 (Guard-at-caller 확정) |
| F-D-3 (domain-1) | Minor | PaymentEvent.fail 허용 source state 미명시 | Task 5 (테스트 추가) |
| F-D-4 (domain-1) | Minor | getStatusByOrderId 폴백 timeout 상한 미명시 | 운영 설정값 문제, plan non-goal — execute 단계에서 기존 HTTP 클라이언트 timeout 설정 확인으로 한정 |
| D-R2-1 (domain-2) | Minor | DONE+approvedAt=null → budget 소진 → FAILED → money-leak | Task 8a (SUSPENDED 격리), Task 8b (테스트) |
| D-R2-2 (domain-2) | Minor | WAITING_FOR_DEPOSIT RETRYABLE → budget 소진 필연 | Task 8b (RETRYABLE 유지 + alert; 별도 큐는 non-goal) |
| D-R2-3 (domain-2) | Minor | StockService.restoreForOrders 멱등성 가정 미검증 | Task 12 |

---

## plan 이월 항목 (non-goal 확인)

| §11 항목 | 처리 |
|----------|------|
| §11-1 스키마 변경 허용? | non-goal 확인 — 기존 컬럼만 사용. `SUSPENDED` 상태는 enum 값 추가이며 DB 컬럼 변경 없음 |
| §11-2 execute 상태 가드 축소 부작용 | Task 5 내에서 call-site 전수 확인 포함 |
| §11-3 per-row CAS vs bulk UPDATE 트레이드오프 | Task 9에서 bulk UPDATE 채택 (§4-2 권고안) |
| §11-4 StockService 멱등성 | Task 12 Guard-at-caller로 매핑 (product 컨텍스트 수정 없음) |
| §11-5 PARTIAL_CANCELED 정책 | Task 8b에서 RETRYABLE+alert 유지 (별도 NON_RETRYABLE 전환은 non-goal) |
| §11-6 잔존 read-then-save 전수 감사 | Task 7, Task 8b 내 범위 한정 감사. 타 경로 전면 감사는 non-goal |
| §11-7 Micrometer 카운터 | non-goal — LogFmt 최소 관측(Task 7, Task 8b)만 in-scope |
| F-D-4 timeout 상한 | non-goal — execute 단계에서 기존 HTTP client timeout 확인으로 한정 |

---

## 반환 요약

- **태스크 총 개수**: 14 (Task 1~12, Task 4.5, Task 8a/8b로 분할)
- **domain_risk 태스크 개수**: 10 (Task 3, 4.5, 5, 6, 7, 8a, 8b, 10, 11, 12)
- **topic.md 결정 중 태스크로 매핑하지 못한 항목**: 없음 (pass)
