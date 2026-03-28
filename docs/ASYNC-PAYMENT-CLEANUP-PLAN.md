# 비동기 결제 시스템 전환 및 스케줄러 클렌징 구현 플랜

> 작성일: 2026-03-28

## 목표

Outbox 전략을 메인 전략으로 확정하고, Sync 전용 복구 코드·불필요한 Toss GET 호출·멱등키 버그·스케줄러 중복을 정리하여 비동기 결제의 데이터 정합성 보장 구조를 완성한다.

## 컨텍스트

- 설계 문서: [docs/topics/ASYNC-PAYMENT-CLEANUP.md](topics/ASYNC-PAYMENT-CLEANUP.md)
- 주요 변경 파일:
  - `TossPaymentGatewayStrategy` (멱등키 수정)
  - `PaymentTransactionCoordinator` (WithOutbox 메서드 추가)
  - `OutboxImmediateEventHandler`, `OutboxWorker` (validateCompletionStatus 제거 + WithOutbox 전환)
  - `PaymentGatewayPort`, `PaymentGatewayStrategy`, `InternalPaymentGatewayAdapter` (getStatus 제거)
  - `PaymentCommandUseCase` (validateCompletionStatus, increaseRetryCount 제거)
  - `PaymentConfirmServiceImpl` (Toss GET 제거, 로컬 검증만 유지)
  - `PaymentEvent` (도메인 메서드 5개 제거)
  - `PaymentScheduler`, `PaymentRecoverServiceImpl`, `PaymentRecoveryUseCase` (삭제)
  - `application.yml` (전략 기본값 변경)

---

## 진행 상황

- [x] Task 1: TossPaymentGatewayStrategy 멱등키 버그 수정
- [x] Task 2: PaymentTransactionCoordinator WithOutbox 메서드 추가
- [x] Task 3: OutboxImmediateEventHandler + OutboxWorker — validateCompletionStatus 제거 + WithOutbox 전환
- [x] Task 4: PaymentGatewayPort + validateCompletionStatus 전면 제거
- [x] Task 5: 복구 시스템 + 관련 의존성 전체 제거
- [ ] Task 6: application.yml 기본 전략 변경
- [ ] Task 7: 컨텍스트 문서 업데이트

---

## 태스크

### Task 1: TossPaymentGatewayStrategy 멱등키 버그 수정 [tdd=true]

**테스트 (RED)**
- 테스트 클래스: `TossPaymentGatewayStrategyTest` (신규)
- `generateIdempotencyKey_confirm_orderId를_그대로_반환한다`: `confirm()` 호출 시 Toss에 전달되는 idempotencyKey가 orderId와 동일한지 검증
- `generateIdempotencyKey_cancel_paymentKey를_그대로_반환한다`: `cancel()` 호출 시 paymentKey와 동일한지 검증
- 접근 방식: `PaymentGatewayInternalReceiver` mock, `ArgumentCaptor`로 전달된 `TossConfirmRequest.idempotencyKey` 캡처

**구현 (GREEN)**
- 파일: `src/main/java/.../payment/infrastructure/gateway/toss/TossPaymentGatewayStrategy.java`
- `generateIdempotencyKey(String baseKey)` 메서드: `return baseKey + "_" + System.currentTimeMillis()` → `return baseKey`

**리팩터 (REFACTOR)**
- 생략 (`generateIdempotencyKey` 메서드 자체가 단순해지므로 인라인 가능하나 가독성상 유지)

**완료 기준**
- TossPaymentGatewayStrategyTest 전체 통과
- `./gradlew test` 회귀 없음

**완료 결과**
> `generateIdempotencyKey(String baseKey)` 메서드를 `return baseKey`로 단순화. 타임스탬프 접미사 제거로 동일 orderId/paymentKey 재요청 시 멱등키가 일치하여 Toss 멱등성 보장.

---

### Task 2: PaymentTransactionCoordinator WithOutbox 메서드 추가 [tdd=true]

**테스트 (RED)**
- 테스트 클래스: `PaymentTransactionCoordinatorTest` (기존 파일에 Nested 클래스 추가)
- `executePaymentSuccessCompletionWithOutbox`:
  - `성공_시_PaymentEvent_DONE_및_outbox_toDone_저장`: `markPaymentAsDone` 호출 + `paymentOutboxUseCase.save()` 호출 검증
  - `completeJob_절대_호출하지_않는다`: existsByOrderId 분기 없음 → `paymentProcessUseCase.completeJob()` 호출 0회 검증
- `executePaymentFailureCompensationWithOutbox`:
  - `성공_시_재고복원_PaymentEvent_FAILED_outbox_toFailed_저장`: 세 작업(`save`, `increaseStock`, `markFail`) 모두 수행 검증
  - `failJob_절대_호출하지_않는다`: existsByOrderId 분기 없음 → `paymentProcessUseCase.failJob()` 호출 0회 검증
- 패턴: `@ExtendWith(MockitoExtension.class)`, BDD Mockito, `then().should()`

**구현 (GREEN)**
- 파일: `src/main/java/.../payment/application/usecase/PaymentOutboxUseCase.java`
  - `@Transactional public void save(PaymentOutbox outbox)` 메서드 추가 (단순 `paymentOutboxRepository.save(outbox)` delegate)
- 파일: `src/main/java/.../payment/application/usecase/PaymentTransactionCoordinator.java`
- 신규 메서드 추가:
  ```java
  @Transactional
  public PaymentEvent executePaymentSuccessCompletionWithOutbox(
      PaymentEvent paymentEvent, LocalDateTime approvedAt, PaymentOutbox outbox
  ) {
      outbox.toDone();
      paymentOutboxUseCase.save(outbox);
      return paymentCommandUseCase.markPaymentAsDone(paymentEvent, approvedAt);
  }

  @Transactional
  public PaymentEvent executePaymentFailureCompensationWithOutbox(
      PaymentEvent paymentEvent,
      List<PaymentOrder> paymentOrderList, String failureReason, PaymentOutbox outbox
  ) {
      outbox.toFailed();
      paymentOutboxUseCase.save(outbox);
      orderedProductUseCase.increaseStockForOrders(paymentOrderList);
      return paymentCommandUseCase.markPaymentAsFail(paymentEvent, failureReason);
  }
  ```
  - `orderId` 파라미터 불필요 — `outbox.getOrderId()`로 충분하여 시그니처에서 제거
  - `paymentOutboxUseCase`는 이미 주입되어 있으므로 별도 의존성 추가 불필요
  - 기존 `executePaymentSuccessCompletion/executePaymentFailureCompensation`은 Sync용으로 유지

**완료 기준**
- PaymentTransactionCoordinatorTest — WithOutbox 관련 새 테스트 통과
- 기존 테스트(`executePaymentSuccessCompletion`, `executePaymentAndStockDecrease*`) 회귀 없음
- `./gradlew test` 전체 통과

**완료 결과**
> `PaymentOutboxUseCase.save(PaymentOutbox)` 신규 추가. `PaymentTransactionCoordinator`에 `executePaymentSuccessCompletionWithOutbox`, `executePaymentFailureCompensationWithOutbox` 메서드 추가 — `outbox.toDone()/toFailed()` + `save()` + 기존 완료/보상 로직을 단일 트랜잭션으로 묶음.

---

### Task 3: OutboxImmediateEventHandler + OutboxWorker — validateCompletionStatus 제거 + WithOutbox 전환 [tdd=true]

**테스트 (RED)**
- `OutboxImmediateEventHandlerTest` 수정:
  - 기존 `validateCompletionStatus` 호출 검증 제거 (예: `handle_validation_실패_시` 테스트 삭제)
  - `executePaymentSuccessCompletion + markDone` → `executePaymentSuccessCompletionWithOutbox` 단일 호출로 변경
  - `executePaymentFailureCompensation + markFailed` → `executePaymentFailureCompensationWithOutbox` 단일 호출로 변경
  - 검증: `then(mockPaymentOutboxUseCase).should(times(0)).markDone(any())`
- `OutboxWorkerTest` (기존) 수정:
  - `validateCompletionStatus` 호출 검증 제거
  - `executePaymentSuccessCompletion + markDone` → `executePaymentSuccessCompletionWithOutbox` 단일 호출로 변경
  - `executePaymentFailureCompensation + markFailed` → `executePaymentFailureCompensationWithOutbox` 단일 호출로 변경
  - 검증: `then(mockPaymentOutboxUseCase).should(times(0)).markDone(any())`
- `PaymentTransactionCoordinatorTest` — `executePaymentSuccessCompletion` 테스트에서 `markDone` 관련 검증 제거

**구현 (GREEN)**
- 파일: `src/main/java/.../payment/listener/OutboxImmediateEventHandler.java`
  - `paymentCommandUseCase.validateCompletionStatus()` 호출 줄 제거
  - catch 블록: `PaymentValidException | PaymentStatusException` 제거
    - `PaymentValidException`: `validateCompletionStatus` 제거로 발생 경로 소멸
    - `PaymentStatusException`: `executePaymentSuccessCompletionWithOutbox` 내부에서 발생하면 도메인 상태 가드 위반(프로그래밍 오류)이므로 보상 처리 대상이 아님 → 비정상 예외로 전파
  - `transactionCoordinator.executePaymentSuccessCompletion()` + `paymentOutboxUseCase.markDone()` → `transactionCoordinator.executePaymentSuccessCompletionWithOutbox(paymentEvent, approvedAt, outbox)` 단일 호출
  - `transactionCoordinator.executePaymentFailureCompensation()` + `paymentOutboxUseCase.markFailed()` → `transactionCoordinator.executePaymentFailureCompensationWithOutbox(paymentEvent, orderList, reason, outbox)` 단일 호출
- 파일: `src/main/java/.../payment/scheduler/OutboxWorker.java`
  - 동일 패턴 적용

**완료 기준**
- OutboxImmediateEventHandlerTest, OutboxWorkerTest 전체 통과
- `./gradlew test` 회귀 없음

**완료 결과**
> `OutboxImmediateEventHandler`와 `OutboxWorker` 양쪽에서 `validateCompletionStatus` 호출 제거. 성공 경로: `executePaymentSuccessCompletion + markDone` → `executePaymentSuccessCompletionWithOutbox` 단일 호출. 실패 경로: `executePaymentFailureCompensation + markFailed` → `executePaymentFailureCompensationWithOutbox` 단일 호출. `PaymentValidException | PaymentStatusException` catch 블록 제거.

---

### Task 4: PaymentGatewayPort + validateCompletionStatus 전면 제거 [tdd=false]

**테스트 (RED) — 신규 비즈니스 로직 선작성**

*OutboxAsyncConfirmService LVAL 검증 (구현 전 먼저 작성):*
- 테스트 클래스: `OutboxAsyncConfirmServiceTest` (기존 파일에 케이스 추가)
- `금액_일치_시_정상_플로우를_진행한다`: command.amount == paymentEvent.totalAmount → `executePaymentAndStockDecreaseWithOutbox` 호출 검증
- `금액_불일치_시_PaymentValidException을_던진다`: command.amount != paymentEvent.totalAmount → `PaymentValidException` throw 검증, `executePaymentAndStockDecreaseWithOutbox` 미호출 검증

*PaymentConfirmServiceImpl validateLocalPaymentRequest 검증 (구현 전 먼저 작성):*
- 테스트 클래스: `PaymentConfirmServiceImplTest` (기존 파일에 케이스 추가)
- `buyerId_불일치_시_PaymentValidException을_던진다`
- `amount_불일치_시_PaymentValidException을_던진다`
- `orderId_불일치_시_PaymentValidException을_던진다`
- `paymentKey_불일치_시_PaymentValidException을_던진다`

**구현 (GREEN)**

*포트 및 인프라 정리:*
- `PaymentGatewayPort.java`: `getStatus(String)`, `getStatusByOrderId(String)` 메서드 제거
- `PaymentGatewayStrategy.java`: 동일 메서드 제거
- `InternalPaymentGatewayAdapter.java`: `getStatus()`, `getStatusByOrderId()` 구현 제거
- `TossPaymentGatewayStrategy.java`: `getStatus()`, `getStatusByOrderId()` 구현 제거

*애플리케이션 유스케이스 정리:*
- `PaymentCommandUseCase.java`: `validateCompletionStatus()` 메서드 전체 제거 (→ `paymentGatewayPort` 필드 의존성 제거 가능한지 확인)
- `PaymentEvent.java`: `validateCompletionStatus(PaymentConfirmCommand, PaymentGatewayInfo)` 메서드 제거

*Outbox 전략 로컬 검증 추가:*
- `OutboxAsyncConfirmService.java`: `confirm()` 내 `paymentEvent` 로드 직후, TX 진입 전에 로컬 금액 검증 추가:
  - `command.getAmount().compareTo(paymentEvent.getTotalAmount()) != 0` → `PaymentValidException` throw (금액 위변조 감지)
  - 플로우차트 `LVAL` 노드에 해당; 핸들러에서 command는 DB값으로 재구성되므로 HTTP 단계에서만 유의미한 비교임

*Sync 전략 조정:*
- `PaymentConfirmServiceImpl.java`: `processPayment()` 내 `paymentCommandUseCase.validateCompletionStatus()` 호출 제거 → `private void validateLocalPaymentRequest(PaymentEvent, PaymentConfirmCommand)` 신규 메서드 추가:
  - buyerId == command.userId 검증
  - command.amount == paymentEvent.totalAmount 검증
  - command.orderId == paymentEvent.orderId 검증
  - command.paymentKey == paymentEvent.paymentKey 검증 (DB값 비교)
  - 불일치 시 기존과 동일한 `PaymentValidException` throw

*미사용 도메인 DTO 제거:*
- `PaymentStatusResult.java`: 사용처가 없어진 경우 제거 (grep으로 확인 후)

*테스트 정리:*
- `PaymentCommandUseCaseTest.java`: `validateCompletionStatus` 관련 테스트 케이스 제거
- `PaymentConfirmServiceImplTest.java`: validateCompletionStatus mock 제거 (RED 단계에서 이미 새 검증 케이스 추가됨)

**완료 기준**
- 컴파일 오류 없음
- `./gradlew test` 회귀 없음

**완료 결과**
> `PaymentGatewayPort/Strategy`에서 `getStatus`, `getStatusByOrderId` 제거. `PaymentCommandUseCase`에서 `validateCompletionStatus` 제거. `OutboxAsyncConfirmService.confirm()`에 LVAL 금액 검증 추가(TX 진입 전). `PaymentConfirmServiceImpl`에 `validateLocalPaymentRequest` 신규 추가 — buyerId/amount/orderId/paymentKey 로컬 검증, `doConfirm()` 첫 단계에서 호출하여 catch(Exception e) 블록에 걸리지 않도록 위치 확정. Task 5 컴파일 의존성으로 동시 진행.

---

### Task 5: 복구 시스템 + 관련 의존성 전체 제거 [tdd=false]

**구현**

*파일 전체 삭제:*
- `PaymentRecoverServiceImpl.java` (`application/`)
- `PaymentRecoveryUseCase.java` (`application/usecase/`)
- `scheduler/port/PaymentRecoverService.java`
- `PaymentRetryableValidateException.java`
- `JpaPaymentEventRepository` 커스텀 쿼리 메서드: `findByInProgressWithTimeConstraintOrUnknown()` 제거
- 테스트 파일 삭제: `PaymentRecoverServiceImplTest.java`, `PaymentRecoveryUseCaseTest.java`

*메서드 제거:*
- `PaymentScheduler.java`:
  - `PaymentRecoverService paymentRecoverService` 필드 제거
  - `recoverRetryablePayment()` 메서드 제거
  - `recoverStuckPayments()` 메서드 제거
  - `expireOldReadyPayments()`: `@ConditionalOnProperty` 제거 (항상 실행, 버그 수정)
- `PaymentCommandUseCase.java`: `increaseRetryCount()` 제거
- `PaymentLoadUseCase.java`: `getRetryablePaymentEvents()` 제거
- `PaymentProcessUseCase.java`: `findAllProcessingJobs()` 제거
- `PaymentEventRepository.java` (포트): `findDelayedInProgressOrUnknownEvents()` 제거
- `PaymentEventRepositoryImpl.java`: 위 포트 구현 제거
- `PaymentEvent.java`: `RETRYABLE_MINUTES_FOR_IN_PROGRESS` 상수, `increaseRetryCount()`, `isRetryable(LocalDateTime)`, `isRetryableInProgress()` 제거

*설정 파일 dead config 정리:*
- `src/main/resources/application-docker.yml`: 삭제된 스케줄러 메서드와 연관된 설정 키 제거
  - `scheduler.payment-status-sync` 블록 전체 제거
  - `scheduler.payment-recovery` 블록 전체 제거

*OutboxUseCase markDone/markFailed 정리:*
- Task 3 이후 `markDone()`은 외부 호출 없음 → 삭제
- `markFailed()`는 `incrementRetryOrFail()` 내부에서만 사용 → private으로 변경

*테스트 정리:*
- `PaymentSchedulerTest.java`: 삭제된 두 메서드 테스트 케이스 제거, expireOldReadyPayments 테스트 유지
- `PaymentLoadUseCaseTest.java`: `getRetryablePaymentEvents` 테스트 제거
- `PaymentEventTest.java`: `isRetryable`, `increaseRetryCount` 관련 테스트 제거
- `PaymentOutboxUseCaseTest.java`: `markDone`, `markFailed` 외부 호출 테스트 제거
- `OutboxImmediateEventHandlerTest.java`: Task 3에서 추가된 `should(times(0)).markDone(any())` 검증 라인 제거 (`markDone` 메서드 자체가 삭제되므로 컴파일 오류 방지)
- `OutboxWorkerTest.java`: 동일

**완료 기준**
- 컴파일 오류 없음
- `./gradlew test` 회귀 없음

**완료 결과**
> `PaymentRecoverServiceImpl`, `PaymentRecoveryUseCase`, `PaymentRecoverService`(포트), `PaymentRetryableValidateException`, `PaymentStatusResult`, `PaymentStatus` 전체 삭제. `PaymentScheduler`에서 recovery 관련 필드/메서드 제거, `expireOldReadyPayments()`의 `@ConditionalOnProperty` 제거. `PaymentEvent` 도메인에서 retry 관련 상수/메서드 5개 제거. `application-docker.yml` dead config 제거. Task 4 컴파일 의존성으로 동시 진행. `STATUS_UNKNOWN` 상수 누락 버그(Deviation Rule 1) 수정 병행.

---

### Task 6: application.yml 기본 전략 변경 [tdd=false]

**구현**
- 파일: `src/main/resources/application.yml`
  - `spring.payment.async-strategy: sync` → `spring.payment.async-strategy: outbox`
- `PaymentConfirmServiceImpl.java` `@ConditionalOnProperty`:
  - `matchIfMissing = true` 제거 (outbox가 기본값이 됨)
  - `havingValue = "sync"` 유지

**테스트 정리**
- `PaymentConfirmServiceImplTest.java`: `@ConditionalOnProperty` 어노테이션 검증 테스트 수정
  - `annotation.matchIfMissing()).isTrue()` → `annotation.matchIfMissing()).isFalse()`
  - `@DisplayName` 문자열에서 `matchIfMissing=true` 부분 제거
- `src/test/resources/application-test.yml`: `spring.payment.async-strategy: sync` 추가 — `matchIfMissing` 제거 후 통합 테스트 환경이 의도치 않게 outbox 어댑터로 전환되지 않도록 고정

**완료 기준**
- 서버 기동 시 `OutboxAsyncConfirmService` 빈이 활성화됨
- `./gradlew test` 회귀 없음

**완료 결과**
> (완료 후 작성)
