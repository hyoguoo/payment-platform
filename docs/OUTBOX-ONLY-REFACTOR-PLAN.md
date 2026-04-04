# Outbox 전용 아키텍처 정리 및 현대화 구현 플랜

> 작성일: 2026-04-04

## 목표

Sync 전략과 관련 코드를 완전히 제거하고, HTTP 클라이언트 현대화·관측성 강화·사용자 시그널 추가까지 완료해 Outbox 전용 시스템으로 정리한다.

## 컨텍스트

- 설계 문서: [docs/topics/OUTBOX-ONLY-REFACTOR.md](../topics/OUTBOX-ONLY-REFACTOR.md)
- 주요 변경 파일:
  - `payment/application/PaymentConfirmServiceImpl.java` (삭제)
  - `payment/application/OutboxAsyncConfirmService.java`
  - `payment/application/usecase/PaymentTransactionCoordinator.java`
  - `payment/application/usecase/PaymentFailureUseCase.java`
  - `payment/application/usecase/PaymentProcessUseCase.java` (삭제)
  - `payment/domain/PaymentProcess.java` (삭제)
  - `payment/domain/PaymentEvent.java`
  - `payment/domain/enums/PaymentEventStatus.java`
  - `core/common/infrastructure/http/HttpOperatorImpl.java`
  - `core/channel/PaymentConfirmChannel.java`
  - `payment/application/dto/response/PaymentConfirmAsyncResult.java`

---

## 진행 상황

<!-- execute 단계에서 각 태스크 완료 시 체크 -->
- [x] Task 1: PaymentConfirmServiceImpl 제거 + Outbox 단일화
- [x] Task 2: PaymentTransactionCoordinator Sync 전용 메서드 제거
- [ ] Task 3: PaymentProcess 도메인 + 인프라 전체 제거
- [ ] Task 4: UNKNOWN 상태 + PaymentFailureUseCase Sync 메서드 제거
- [ ] Task 5: 설정 프로퍼티 및 문서 정리
- [ ] Task 6: RestTemplate → WebClient
- [ ] Task 7: offer 실패 시 사용자 시그널
- [ ] Task 8: Grafana 관측 지표 추가
- [ ] Task 9: 코드 클렌징

---

## 태스크

### Task 1: PaymentConfirmServiceImpl 제거 + Outbox 단일화 [tdd=false]

**구현**
- `payment/application/PaymentConfirmServiceImpl.java` 삭제
- `payment/application/OutboxAsyncConfirmService.java` — `@ConditionalOnProperty` 어노테이션 제거
- `payment/application/dto/response/PaymentConfirmAsyncResult.java` — `ResponseType.SYNC_200` 제거, `ASYNC_202`만 유지
- `payment/presentation/PaymentController.java` — `SYNC_200` 분기 제거, 항상 202 반환으로 단순화
- `src/test/.../PaymentConfirmServiceImplTest.java` 삭제
- `src/test/.../PaymentConfirmAsyncResultTest.java` — SYNC_200 관련 테스트 제거
- `src/test/.../OutboxAsyncConfirmServiceTest.java` — `@ConditionalOnProperty` 어노테이션 검증 테스트 제거
- `src/test/.../PaymentControllerMvcTest.java` — `confirmPayment_SyncAdapter_Returns200()` 제거, 202 단일 흐름으로 갱신

**완료 기준**
- 컴파일 오류 없음
- `./gradlew test` 회귀 없음

**완료 결과**
> PaymentConfirmServiceImpl 삭제, OutboxAsyncConfirmService의 @ConditionalOnProperty 제거, ResponseType.SYNC_200 제거, PaymentController를 항상 202 반환으로 단순화. HttpStatus import를 checkout() 메서드에서 여전히 사용 중이라 유지.

---

### Task 2: PaymentTransactionCoordinator Sync 전용 메서드 제거 [tdd=false]

**구현**
- `PaymentTransactionCoordinator`에서 아래 메서드 제거:
  - `executeStockDecreaseWithJobCreation()` — Sync 전용 (재고 감소 + PaymentProcess 생성)
  - `executePaymentSuccessCompletion()` — Sync 전용 (PaymentProcess complete)
  - `executePaymentFailureCompensation()` — Sync 전용 (PaymentProcess fail + 재고 복원)
  - `executePaymentAndStockDecrease()` — 호출처 없는 사문 메서드
- `PaymentTransactionCoordinatorTest` — 위 메서드에 대한 테스트 제거

**완료 기준**
- 컴파일 오류 없음
- `./gradlew test` 회귀 없음

**완료 결과**
> PaymentTransactionCoordinator에서 Sync 전용 메서드 4개 제거. PaymentConfirmServiceImpl 삭제(Task 1)로 호출처 없어진 handleNonRetryableFailure, handleUnknownFailure도 함께 제거(컴파일 오류 자동 수정). PaymentFailureUseCase에서 transactionCoordinator 의존성 제거. OutboxProcessingServiceTest의 executePaymentSuccessCompletion 검증 라인도 제거.

---

### Task 3: PaymentProcess 도메인 + 인프라 전체 제거 [tdd=false]

**구현**
아래 파일 전체 삭제:
- `payment/domain/PaymentProcess.java`
- `payment/application/port/PaymentProcessRepository.java`
- `payment/application/usecase/PaymentProcessUseCase.java`
- `payment/infrastructure/repository/PaymentProcessRepositoryImpl.java`
- `payment/infrastructure/repository/JpaPaymentProcessRepository.java`
- `payment/infrastructure/entity/PaymentProcessEntity.java`
- `src/test/.../PaymentProcessTest.java`
- `src/test/.../PaymentProcessUseCaseTest.java`

`PaymentTransactionCoordinator`에서 `PaymentProcessUseCase` 필드 및 생성자 파라미터 제거.
`PaymentTransactionCoordinatorTest` — `@Mock PaymentProcessUseCase` 필드 및 관련 선언 제거.

**완료 기준**
- 컴파일 오류 없음
- `./gradlew test` 회귀 없음

**완료 결과**
> (완료 후 작성)

---

### Task 4: UNKNOWN 상태 + PaymentFailureUseCase Sync 메서드 제거 [tdd=true]

**테스트 (RED)**
`PaymentEventTest`:
- `execute_UNKNOWN_상태에서_호출_시_InvalidStatus_예외` — `execute()`의 UNKNOWN 허용 제거 검증
- `done_UNKNOWN_상태에서_호출_시_InvalidStatus_예외` — `done()`의 UNKNOWN 허용 제거 검증
- `fail_UNKNOWN_상태에서_호출_시_InvalidStatus_예외` — `fail()`의 UNKNOWN 허용 제거 검증

**구현 (GREEN)**
- `PaymentEvent.execute()` — source state에서 `UNKNOWN` 제거
- `PaymentEvent.done()` — source state에서 `UNKNOWN` 제거
- `PaymentEvent.fail()` — source state에서 `UNKNOWN` 제거
- `PaymentEvent.unknown()` 메서드 삭제
- `PaymentEventStatus.UNKNOWN` 열거값 삭제
- `PaymentFailureUseCase.handleRetryableFailure()` 삭제
- `PaymentFailureUseCase.handleUnknownFailure()` 삭제
- `PaymentCommandUseCase.markPaymentAsUnknown()` 삭제
- `PaymentEventTest` — `unknown()` 관련 기존 테스트 제거, 추가된 예외 테스트 포함
- `PaymentEventTest` — `execute_Success`, `done_Success`, `fail_Success` 등의 `@EnumSource` 목록에서 `UNKNOWN` 제거

**완료 기준**
- 추가한 예외 테스트 3개 통과
- `./gradlew test` 회귀 없음

**완료 결과**
> (완료 후 작성)

---

### Task 5: 설정 프로퍼티 및 문서 정리 [tdd=false]

**구현**
- `src/main/resources/application.yml` — `spring.payment.async-strategy` 프로퍼티 제거
- `src/main/resources/application-benchmark.yml` — 동일
- `src/main/resources/application-docker.yml` — 해당 프로퍼티 있으면 제거
- `docs/context/ARCHITECTURE.md` — Sync 전략 설명, `@ConditionalOnProperty` 분기, `ResponseType.SYNC_200` 내용 제거 및 Outbox 단일 전략으로 갱신
- `docs/context/CONFIRM-FLOW-ANALYSIS.md` — Sync 플로우 섹션 제거, Outbox 플로우만 유지
- `docs/context/STACK.md` — `spring.payment.async-strategy` 설정 항목 제거

**완료 기준**
- `./gradlew test` 회귀 없음
- 문서에서 Sync 전략 잔재 제거 확인

**완료 결과**
> (완료 후 작성)

---

### Task 6: RestTemplate → WebClient [tdd=false]

**구현**
- `build.gradle` — `implementation 'org.springframework.boot:spring-boot-starter-webflux'` 추가
- `HttpOperatorImpl.java`:
  - `RestTemplate` import 제거
  - 클래스 레벨에서 `WebClient` 싱글톤 필드 선언 및 초기화
  - `requestGet()` — `webClient.get()...retrieve()...bodyToMono().block()`으로 교체
  - `requestPost()` — `webClient.post()...bodyValue()...retrieve()...bodyToMono().block()`으로 교체
  - `getClientHttpRequestFactory()`, `createHttpEntity()` 헬퍼 메서드 제거
  - `readTimeoutMillis` → `WebClient` 빌더의 `responseTimeout`으로 적용

**완료 기준**
- 컴파일 오류 없음
- `./gradlew test` 회귀 없음

**완료 결과**
> (완료 후 작성)

---

### Task 7: offer 실패 시 사용자 시그널 [tdd=true]

**테스트 (RED)**
`OutboxAsyncConfirmServiceTest`:
- `confirm_채널_여유_있을_때_queueNearFull_false` — `remainingCapacity()` 충분 → result.isQueueNearFull() == false
- `confirm_채널_임계값_이하일_때_queueNearFull_true` — `remainingCapacity()` 10% 이하 → result.isQueueNearFull() == true
- 기존 테스트의 `setUp()`에 `PaymentConfirmChannel` mock 추가 (`Mockito.mock(PaymentConfirmChannel.class)`)

`PaymentControllerMvcTest`:
- `confirm_queueNearFull_false_시_202_정상_응답`
- `confirm_queueNearFull_true_시_202_지연_메시지_포함`

**구현 (GREEN)**
- `PaymentConfirmChannel.java` — `remainingCapacity()` 메서드 추가 (`queue.remainingCapacity()` 위임)
- `PaymentConfirmAsyncResult.java` — `queueNearFull: boolean` 필드 추가
- `OutboxAsyncConfirmService.confirm()`:
  - `channel.remainingCapacity()` 조회
  - `capacity * 0.1` 이하이면 `queueNearFull=true`
  - capacity는 `@Value("${outbox.channel.capacity:2000}")` 주입
- `PaymentController.confirm()`:
  - `result.isQueueNearFull()`이 true이면 응답 body의 `message` 필드에 안내 문구 포함
- `payment/presentation/dto/response/PaymentConfirmResponse.java` — `message: String` 필드 추가 (nullable)
- `PaymentPresentationMapper` — `toPaymentConfirmResponse()` 매핑 갱신

**완료 기준**
- 추가한 테스트 모두 통과
- `./gradlew test` 회귀 없음

**완료 결과**
> (완료 후 작성)

---

### Task 8: Grafana 관측 지표 추가 [tdd=false]

**구현**

*PaymentConfirmChannel Gauge 등록:*
- `PaymentConfirmChannel.java` — `MeterRegistry` 주입 추가
- `@PostConstruct`에서 Gauge 등록:
  - `payment_confirm_channel_queue_size` — `queue.size()`
  - `payment_confirm_channel_remaining_capacity` — `queue.remainingCapacity()`

*Grafana 대시보드 패널 추가:*
- `docker/compose/grafana/dashboards/1-system-infrastructure.json`:
  - Tomcat 활성 스레드 수 패널 (`tomcat_threads_current_threads{state="active"}`)
  - Tomcat 전체 스레드 설정 패널 (`tomcat_threads_config_max_threads`)
- `docker/compose/grafana/dashboards/3-payment-operations.json`:
  - 채널 큐 현재 크기 패널 (`payment_confirm_channel_queue_size`)
  - 채널 잔여 용량 패널 (`payment_confirm_channel_remaining_capacity`)

**완료 기준**
- 앱 기동 후 Prometheus 메트릭 엔드포인트에서 Gauge 확인 가능
- `./gradlew test` 회귀 없음

**완료 결과**
> (완료 후 작성)

---

### Task 9: 코드 클렌징 [tdd=false]

**구현**
- `superpowers:code-reviewer` 스킬로 Task 1~8 완료 후 전체 변경 사항 검토
- dead code, 불필요한 주석, 복잡도 높은 패턴 정리
- 변경 사항 있을 경우 `refactor:` 커밋

**완료 기준**
- code-reviewer 피드백 반영 완료
- `./gradlew test` 회귀 없음

**완료 결과**
> (완료 후 작성)
