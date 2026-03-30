# ASYNC-CHANNEL 구현 플랜

> 작성일: 2026-03-31

## 목표

`LinkedBlockingQueue` + Worker 가상 스레드로 Outbox 즉시 처리 경로를 재구성하여,
HTTP 스레드 블로킹을 완전히 제거하고 Spring Boot 3.4.x로 업그레이드한다.

## 컨텍스트

- 설계 문서: [docs/topics/ASYNC-CHANNEL-DISCUSS.md](../topics/ASYNC-CHANNEL-DISCUSS.md)
- 주요 변경 파일:
  - `build.gradle`
  - `core/config/AsyncConfig.java`, `core/channel/PaymentConfirmChannel.java` (신규)
  - `payment/scheduler/OutboxWorker.java`, `OutboxProcessingService.java` (신규), `OutboxImmediateWorker.java` (신규)
  - `payment/listener/OutboxImmediateEventHandler.java`
  - `application-benchmark.yml`, `run-benchmark.sh`

---

## 진행 상황

<!-- execute 단계에서 각 태스크 완료 시 체크 -->
- [ ] Task 1: Spring Boot 3.4.x 업그레이드
- [ ] Task 2: PaymentConfirmChannel 구현
- [ ] Task 3: OutboxProcessingService 추출 (OutboxWorker 공통 로직)
- [ ] Task 4: OutboxWorker 리팩터링 — OutboxProcessingService 위임
- [ ] Task 5: OutboxImmediateWorker 구현 (SmartLifecycle + Worker VT)
- [ ] Task 6: OutboxImmediateEventHandler 단순화 (@Async 제거, channel.offer)
- [ ] Task 7: AsyncConfig 정리 + 환경변수 교체

---

## 태스크

### Task 1: Spring Boot 3.4.x 업그레이드 [tdd=false]

**구현**
- `build.gradle`: `id 'org.springframework.boot' version '3.3.3'` → `'3.4.4'`
- BOM에 의해 MySQL Connector/J 9.1.0 자동 포함 (수동 override 불필요)
- 컴파일 및 기존 테스트로 breaking change 확인

**완료 기준**
- `./gradlew test` 전체 통과

**완료 결과**
> (완료 후 작성)

---

### Task 2: PaymentConfirmChannel 구현 [tdd=false]

**구현**
- `src/main/java/com/hyoguoo/paymentplatform/core/channel/PaymentConfirmChannel.java` (신규)
  - `LinkedBlockingQueue<String>` 래퍼 (orderId를 큐 요소로 사용)
  - `offer(String orderId): boolean` — 논블로킹, 큐 가득 차면 false 즉시 반환
  - `take(): String` — Worker가 호출, 큐 비면 VT unmount 대기
  - `@Value("${outbox.channel.capacity:2000}")` 로 큐 용량 주입
  - `@Component`로 등록

**완료 기준**
- 컴파일 오류 없음
- `./gradlew test` 회귀 없음

**완료 결과**
> (완료 후 작성)

---

### Task 3: OutboxProcessingService 추출 [tdd=true]

**테스트 (RED)**
- `src/test/java/.../payment/scheduler/OutboxProcessingServiceTest.java` (신규)
- 현재 `OutboxWorkerTest`의 처리 로직 시나리오를 그대로 이전:
  - `process_정상흐름_성공_완료까지_호출`: claimToInFlight → confirmPaymentWithGateway → executePaymentSuccessCompletionWithOutbox
  - `process_claimToInFlight_empty_Toss미호출`
  - `process_retryable예외_incrementRetryOrFail_호출`
  - `process_retryable예외_소진_executePaymentFailureCompensationWithOutbox_호출`
  - `process_nonRetryable예외_executePaymentFailureCompensationWithOutbox_호출`
  - `process_paymentEvent_로드실패_incrementRetryOrFail_호출`
- 패턴: Mockito BDD (`given` / `then`), 수동 mock 인스턴스화

**구현 (GREEN)**
- `src/main/java/.../payment/scheduler/OutboxProcessingService.java` (신규, `@Service`)
  - `process(String orderId)` — `OutboxWorker.processRecord()` 로직 그대로 이전
  - 의존성: `PaymentOutboxUseCase`, `PaymentLoadUseCase`, `PaymentCommandUseCase`, `PaymentTransactionCoordinator`

**완료 기준**
- `OutboxProcessingServiceTest` 전체 통과
- `./gradlew test` 회귀 없음 (OutboxWorker는 아직 수정 전이므로 OutboxWorkerTest도 통과)

**완료 결과**
> (완료 후 작성)

---

### Task 4: OutboxWorker 리팩터링 — OutboxProcessingService 위임 [tdd=false]

**구현**
- `OutboxWorker.java`:
  - `OutboxProcessingService` 의존성 추가
  - `processRecord(PaymentOutbox outbox)` 내부를 `outboxProcessingService.process(outbox.getOrderId())`로 교체
  - 기존 `PaymentCommandUseCase`, `PaymentLoadUseCase`, `PaymentTransactionCoordinator` 직접 의존 제거
- `OutboxWorkerTest.java` 대폭 단순화:
  - 처리 로직 시나리오 제거 (OutboxProcessingServiceTest로 이전됨)
  - `process()` 배치 흐름만 검증: `findPendingBatch` → `outboxProcessingService.process()` 위임 횟수 확인
  - `recoverTimedOutInFlightRecords()` 호출 검증 유지

**완료 기준**
- `OutboxWorkerTest` 전체 통과
- `./gradlew test` 회귀 없음

**완료 결과**
> (완료 후 작성)

---

### Task 5: OutboxImmediateWorker 구현 [tdd=true]

**테스트 (RED)**
- `src/test/java/.../payment/scheduler/OutboxImmediateWorkerTest.java` (신규)
  - `이벤트를채널에제출하면_OutboxProcessingService가호출된다`:
    - `PaymentConfirmChannel(capacity=1)`, `OutboxProcessingService` mock, Worker count=1
    - `worker.start()` → `channel.offer("order-1")` → Awaitility 1초 내 `mockService.process("order-1")` 호출 검증
  - `stop_호출후_Worker가중단된다`:
    - `worker.start()` → `worker.stop(callback)` → 모든 Worker 스레드 join 완료 후 callback 호출 검증
      (`CountDownLatch` 또는 `AtomicBoolean`으로 callback 호출 여부 확인)
  - `process_RuntimeException_Worker스레드가_종료되지_않는다`:
    - `mockService.process()` → RuntimeException throw 설정
    - `worker.start()` → `channel.offer("order-1")` → 예외 발생 후에도 `channel.offer("order-2")` → Awaitility 내 `mockService.process("order-2")` 호출 검증 (Worker 생존 확인)
- 패턴: Awaitility (`await().atMost(1, SECONDS).untilAsserted(...)`)
- Awaitility: `spring-boot-starter-test`에 포함 여부 먼저 확인 (`./gradlew dependencies --configuration testRuntimeClasspath | grep awaitility`). 미포함 시 `testImplementation 'org.awaitility:awaitility'` 추가 (버전은 Boot BOM 위임)

**구현 (GREEN)**
- `src/main/java/.../payment/scheduler/OutboxImmediateWorker.java` (신규, `@Component`, `SmartLifecycle`)
  - `@Value("${outbox.channel.worker-count:200}")` Worker 수 주입
  - `@Value("${outbox.channel.virtual-threads:true}")` 가상 스레드 여부 주입
  - `start()`: Worker 스레드 N개 생성 (`Thread.ofVirtual()` 또는 `Thread.ofPlatform()`)
    - 각 Worker 루프:
      ```
      while (!Thread.currentThread().isInterrupted()) {
          try {
              orderId = channel.take();
              outboxProcessingService.process(orderId);
          } catch (InterruptedException e) {
              Thread.currentThread().interrupt(); // 루프 종료
          } catch (Exception e) {
              log.warn(...); // 루프 유지 — Worker 스레드 생존 보장
          }
      }
      ```
  - `stop(Runnable callback)`: 모든 Worker `interrupt()` → 각 `thread.join(timeout)` → `callback.run()`
    - join timeout: 예) 5초 (graceful shutdown 시 DataSource destroy 전에 Worker 종료 보장)
  - `isRunning()`: 상태 플래그 반환
  - 의존성: `PaymentConfirmChannel`, `OutboxProcessingService`

**리팩터**
- Worker 스레드 생성 팩토리 메서드 분리

**완료 기준**
- `OutboxImmediateWorkerTest` 전체 통과
- `./gradlew test` 회귀 없음

**완료 결과**
> (완료 후 작성)

---

### Task 6: OutboxImmediateEventHandler 단순화 [tdd=true]

**테스트 (RED)**
- `OutboxImmediateEventHandlerTest.java` 대폭 수정:
  - 기존 처리 로직 시나리오 전체 제거 (OutboxProcessingService로 이전됨)
  - 신규 테스트 2개:
    - `handle_채널offer_성공_true반환`: `channel.offer(orderId)` → true 반환 → 경고 로그 없음
    - `handle_채널오버플로우_offer_false반환`: `channel.offer()` → false 반환 → 경고 로그 1회 (로그 검증은 생략 가능, offer 호출만 검증)
  - 기존 의존성(`PaymentCommandUseCase`, `PaymentLoadUseCase`, `PaymentTransactionCoordinator`) 제거 → `PaymentConfirmChannel` mock으로 교체
  - 애노테이션 검증 테스트 추가 (리플렉션 방식):
    - `@TransactionalEventListener(phase=AFTER_COMMIT)` 존재 확인
    - `@Async` 제거 확인 (`assertThat(method.getAnnotation(Async.class)).isNull()`)

**구현 (GREEN)**
- `OutboxImmediateEventHandler.java` 대폭 단순화:
  - 의존성: `PaymentConfirmChannel`만 유지 (나머지 제거)
  - `@Async("immediateHandlerExecutor")` 제거
  - `handle()`: `boolean offered = channel.offer(event.getOrderId()); if (!offered) { log.warn(...) }`
  - `@TransactionalEventListener(phase = AFTER_COMMIT)` 유지

**완료 기준**
- `OutboxImmediateEventHandlerTest` 전체 통과
- `./gradlew test` 회귀 없음

**완료 결과**
> (완료 후 작성)

---

### Task 7: AsyncConfig 정리 + 환경변수 교체 [tdd=false]

**구현**
- `AsyncConfig.java`:
  - `immediateHandlerExecutor` 빈 삭제
  - `@Value` 필드 (`virtualThreadsEnabled`, `concurrencyLimit`) 제거
  - 빈이 없어지면 `@EnableAsync`도 더 이상 필요 없는지 확인 (`PaymentHistoryEventListener` 등 다른 `@Async` 사용 여부 확인 후 결정)
- `src/main/resources/application-benchmark.yml`:
  - `outbox.immediate-handler.concurrency-limit` → `outbox.channel.worker-count`, `outbox.channel.capacity`
- `scripts/k6/run-benchmark.sh`:
  - `IMMEDIATE_CONCURRENCY_LIMIT` 환경변수 → `WORKER_COUNT`, `CHANNEL_CAPACITY`
- `docker/compose/docker-compose.yml`:
  - `IMMEDIATE_CONCURRENCY_LIMIT=${IMMEDIATE_CONCURRENCY_LIMIT:--1}` 제거
  - `WORKER_COUNT=${WORKER_COUNT:-200}`, `CHANNEL_CAPACITY=${CHANNEL_CAPACITY:-2000}` 추가

**완료 기준**
- `./gradlew test` 전체 통과
- 벤치마크 프로파일 기동 확인 (`./gradlew bootRun --args='--spring.profiles.active=benchmark'`)

**완료 결과**
> (완료 후 작성)
