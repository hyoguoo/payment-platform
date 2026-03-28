# Outbox Immediate Dispatch 구현 플랜

> 작성일: 2026-03-28

## 목표

Outbox 전략의 e2e latency를 scheduler 주기 의존에서 벗어나게 한다.
TX 커밋 직후 `@TransactionalEventListener(AFTER_COMMIT) + @Async`로 Toss API를 즉시 호출하고,
OutboxWorker는 크래시/재시도 전용 recovery worker로 격하한다.

## 컨텍스트

- 설계 문서: [docs/topics/OUTBOX-IMMEDIATE-DISPATCH.md](../topics/OUTBOX-IMMEDIATE-DISPATCH.md)
- 주요 변경 파일:
  - `payment/domain/event/PaymentConfirmEvent.java` (신규)
  - `payment/infrastructure/publisher/OutboxImmediatePublisher.java` (신규)
  - `payment/listener/OutboxImmediateEventHandler.java` (신규)
  - `core/config/AsyncConfig.java` (신규 — @EnableAsync)
  - `payment/application/usecase/PaymentOutboxUseCase.java` (findByOrderId 추가)
  - `payment/application/OutboxAsyncConfirmService.java` (@Transactional + publish 호출 추가)
  - `payment/scheduler/OutboxWorker.java` (fixed-delay 기본값 변경)
  - `src/main/resources/application.yml` (virtual.enabled + fixed-delay-ms 값 변경)

---

## 진행 상황

<!-- execute 단계에서 각 태스크 완료 시 체크 -->
- [x] Task 1: PaymentConfirmEvent 도메인 이벤트 생성
- [x] Task 2: OutboxImmediatePublisher 구현
- [x] Task 3: OutboxImmediateEventHandler 구현
- [x] Task 4: OutboxAsyncConfirmService에 publish 호출 추가
- [x] Task 5: OutboxWorker fixed-delay 기본값 및 application.yml 수정

---

## 태스크

### Task 1: PaymentConfirmEvent 도메인 이벤트 생성 [tdd=false]

**구현**
- `payment/domain/event/PaymentConfirmEvent.java`
  - `orderId` 필드 하나
  - `@Value` (Lombok) + 정적 팩토리 `of(String orderId)`
  - 기존 `PaymentCreatedEvent` 등의 패턴 참고

**완료 기준**
- 컴파일 오류 없음
- 기존 테스트 통과

**완료 결과**
> `@Value` + 정적 팩토리 `of(String orderId)`로 구현. `PaymentHistoryEvent` 계열과 무관한 독립 VO.

---

### Task 2: OutboxImmediatePublisher 구현 [tdd=true]

**테스트 (RED)**
- `OutboxImmediatePublisherTest`
  - `publish_ApplicationEventPublisher_publishEvent를_1회_호출한다` — `applicationEventPublisher.publishEvent(any())` 1회 호출
  - `publish_PaymentConfirmEvent에_orderId가_담겨_발행된다` — `publishEvent` argument가 `PaymentConfirmEvent`이고 `orderId` 일치
  - `outboxImmediatePublisher_ConditionalOnProperty_outbox_선언되어_있다` — `@ConditionalOnProperty(havingValue="outbox")` 애노테이션 존재 검증

**구현 (GREEN)**
- `payment/infrastructure/publisher/OutboxImmediatePublisher.java`
  - `PaymentConfirmPublisherPort` 구현
  - `ApplicationEventPublisher` 주입
  - `publish(orderId)` → `applicationEventPublisher.publishEvent(PaymentConfirmEvent.of(orderId))`
  - `@Component`, `@RequiredArgsConstructor`
  - `@ConditionalOnProperty(name = "spring.payment.async-strategy", havingValue = "outbox")` — 향후 `KafkaConfirmPublisher` 추가 시 빈 충돌 방지

**리팩터 (REFACTOR)**
- 생략

**완료 기준**
- `OutboxImmediatePublisherTest` 전체 통과
- `./gradlew test` 회귀 없음

**완료 결과**
> `@ConditionalOnProperty(havingValue="outbox")` 포함하여 구현. `publishEvent(any())` 오버로드 모호성으로 테스트에서 `any(Object.class)` 사용.

---

### Task 3: OutboxImmediateEventHandler 구현 [tdd=true]

**선행 작업 (tdd=false, 핸들러 구현 전 추가)**
- `@EnableAsync` 설정 추가
  - `core/config/AsyncConfig.java` 신규 생성: `@Configuration + @EnableAsync`
  - `application.yml`에 `spring.threads.virtual.enabled: true` 추가 (Spring Boot 3.2+ 기본 async executor가 가상 스레드 사용)
- `PaymentOutboxUseCase`에 `findByOrderId(String orderId): Optional<PaymentOutbox>` 추가
  - `paymentOutboxRepository.findByOrderId(orderId)` 그대로 위임

**테스트 (RED)**
- `OutboxImmediateEventHandlerTest`
  - `handle_성공_claimToInFlight_후_confirmPaymentWithGateway_호출한다`
  - `handle_성공_executePaymentSuccessCompletion_및_markDone_호출한다`
  - `handle_retryable_실패_시_incrementRetryOrFail_호출한다`
  - `handle_nonRetryable_실패_시_executePaymentFailureCompensation_및_markFailed_호출한다`
  - `handle_validation_실패_시_executePaymentFailureCompensation_및_markFailed_호출한다`
  - `handle_outbox_미존재_시_아무것도_처리하지_않는다`
  - `handle_claimToInFlight_실패_시_이후_로직을_건너뛴다`

**구현 (GREEN)**
- `payment/listener/OutboxImmediateEventHandler.java`
  - `@Component`, `@RequiredArgsConstructor`
  - `handlePaymentConfirmEvent(PaymentConfirmEvent event)` 메서드
    - `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)`
    - `@Async` (가상 스레드 pool 사용)
  - 처리 흐름 (OutboxWorker `processRecord`와 동일한 패턴):
    1. `paymentOutboxUseCase.findByOrderId(orderId)` — 미존재 시 early return
    2. `paymentOutboxUseCase.claimToInFlight(outbox)` — false 시 early return
    3. `paymentLoadUseCase.getPaymentEventByOrderId(orderId)` — 예외 시 `incrementRetryOrFail`
    4. `paymentCommandUseCase.validateCompletionStatus(paymentEvent, command)` — 실패 시 failure compensation
    5. `paymentCommandUseCase.confirmPaymentWithGateway(command)`
    6. 성공: `transactionCoordinator.executePaymentSuccessCompletion()` + `markDone()`
    7. `PaymentTossRetryableException`: `incrementRetryOrFail()`
    8. `PaymentTossNonRetryableException` / `PaymentValidException` / `PaymentStatusException`: failure compensation + `markFailed()`

**리팩터 (REFACTOR)**
- 공통 처리 흐름을 private 메서드로 추출 (필요 시)

**완료 기준**
- `OutboxImmediateEventHandlerTest` 전체 통과
- `./gradlew test` 회귀 없음

**완료 결과**
> OutboxWorker.processRecord() 패턴을 그대로 이식. `@Async + @TransactionalEventListener(AFTER_COMMIT)` 조합으로 TX 커밋 후 가상 스레드에서 즉시 처리.

---

### Task 4: OutboxAsyncConfirmService에 publish 호출 추가 [tdd=true]

**테스트 (RED)**
- `OutboxAsyncConfirmServiceTest` 기존 테스트 수정 + 추가
  - 기존 테스트: `PaymentConfirmPublisherPort` mock 파라미터 추가
  - `confirm_성공_시_publish_1회_호출한다` — `confirmPublisher.publish(orderId)` 1회 호출 검증
  - `confirm_재고부족_시_publish_호출하지_않는다` — 예외 시 `publish()` 미호출 검증

**구현 (GREEN)**
- `OutboxAsyncConfirmService`
  - `@Transactional(rollbackFor = PaymentOrderedProductStockException.class)` 추가
    - `executePaymentAndStockDecreaseWithOutbox()`가 동일 TX에 참여(REQUIRED)
    - `publish()` 호출이 TX 내부에서 발생 → `@TransactionalEventListener(AFTER_COMMIT)` 정상 발화
    - 재고 실패 시 예외가 `confirm()` 밖으로 전파되어 TX 롤백
  - `PaymentConfirmPublisherPort confirmPublisher` 필드 추가
  - `confirm()` 재고 실패 catch 블록 이후에 `confirmPublisher.publish(command.getOrderId())` 추가
    - TX가 아직 활성 상태이므로 이벤트가 TX에 바인딩됨

**리팩터 (REFACTOR)**
- 생략

**완료 기준**
- `OutboxAsyncConfirmServiceTest` 전체 통과
- `./gradlew test` 회귀 없음

**완료 결과**
> `@Transactional(rollbackFor = ...)` + `confirmPublisher.publish()` 추가. publish가 TX 내부에서 발행되어 AFTER_COMMIT 리스너 정상 발화.

---

### Task 5: OutboxWorker fixed-delay 기본값 및 application.yml 수정 [tdd=false]

**구현**
- `OutboxWorker.java`
  - `@Scheduled(fixedDelayString = "${scheduler.outbox-worker.fixed-delay-ms:1000}")` → `:5000`
- `src/main/resources/application.yml`
  - `outbox-worker.fixed-delay-ms: 1000` → `5000`

**완료 기준**
- 컴파일 오류 없음
- `./gradlew test` 회귀 없음

**완료 결과**
> `OutboxWorker` 기본값 1000→5000, `application.yml` 동일하게 변경. recovery 전용 성격에 맞게 주기 완화.
