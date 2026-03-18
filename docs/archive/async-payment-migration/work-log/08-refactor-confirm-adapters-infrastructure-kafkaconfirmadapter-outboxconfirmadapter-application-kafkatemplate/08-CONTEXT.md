# Phase 8: Refactor Confirm Adapters - Context

**Gathered:** 2026-03-16
**Status:** Ready for planning

<domain>
## Phase Boundary

infrastructure 레이어에 있는 KafkaConfirmAdapter/OutboxConfirmAdapter/SyncConfirmAdapter를 제거하고,
오케스트레이션 로직을 application 레이어의 전략별 서비스 클래스로 이동한다.
KafkaTemplate 직접 의존은 PaymentConfirmPublisherPort로 추상화하고, KafkaConfirmPublisher 구현체를 infrastructure/kafka/에 배치한다.

기존 KafkaConfirmListener, OutboxWorker, PaymentTransactionCoordinator는 이 Phase의 범위가 아니다.

</domain>

<decisions>
## Implementation Decisions

### Kafka 포트 추상화
- 포트 인터페이스: `PaymentConfirmPublisherPort` — `application/port/out/` 패키지에 위치
- 메서드 시그니처: `void publish(String orderId)` — 발행 실패 시 KafkaException 전파
- 구현체: `KafkaConfirmPublisher` — `infrastructure/kafka/` 패키지에 위치 (KafkaConfirmListener와 동일 패키지)

### 오케스트레이션 이동 방식
- 전략별 서비스 클래스 신규 생성: `KafkaAsyncConfirmService`, `OutboxAsyncConfirmService`
- 각 서비스가 `PaymentConfirmService` 인터페이스를 직접 implements
- `@ConditionalOnProperty`를 서비스 클래스에 직접 적용 — `KafkaAsyncConfirmService(havingValue="kafka")`, `OutboxAsyncConfirmService(havingValue="outbox")`
- 기존 infrastructure adapter 클래스 3종 모두 제거: `KafkaConfirmAdapter`, `OutboxConfirmAdapter`, `SyncConfirmAdapter`

### SyncConfirmAdapter 정리
- `SyncConfirmAdapter` 제거
- `PaymentConfirmServiceImpl`이 `PaymentConfirmService`를 직접 implements
- `@ConditionalOnProperty(name="spring.payment.async-strategy", havingValue="sync", matchIfMissing=true)` 추가
- 반환 타입을 `PaymentConfirmAsyncResult`로 변경 (ResponseType.SYNC_200)

### Claude's Discretion
- 새 서비스 클래스의 정확한 패키지 경로 (application/ 하위 구조)
- `PaymentConfirmPublisherPort` 관련 테스트에서 Fake 구현 방식
- KafkaAsyncConfirmService 내 `executePayment` 호출 순서 세부 구현

</decisions>

<code_context>
## Existing Code Insights

### Reusable Assets
- `PaymentTransactionCoordinator.executeStockDecreaseOnly()`: KafkaAsyncConfirmService에서 재사용 — Outbox 생성 없이 재고 감소만
- `PaymentTransactionCoordinator.executeStockDecreaseWithOutboxCreation()`: OutboxAsyncConfirmService에서 재사용
- `PaymentLoadUseCase.getPaymentEventByOrderId()`: 두 서비스 모두 재사용
- `PaymentCommandUseCase.executePayment()`: 두 서비스 모두 재사용 (READY → IN_PROGRESS + paymentKey 기록)
- `KafkaConfirmAdapter`의 기존 오케스트레이션 순서: load → executePayment → decreaseStock → publish — 그대로 KafkaAsyncConfirmService로 이동
- `OutboxConfirmAdapter`의 기존 오케스트레이션 순서: load → executePayment → decreaseStockWithOutbox — 그대로 OutboxAsyncConfirmService로 이동

### Established Patterns
- `@ConditionalOnProperty(name="spring.payment.async-strategy", havingValue=...)`: Phase 1~4에서 확립 — 동일 패턴 유지
- `@RequiredArgsConstructor` + `private final` 생성자 주입: 신규 서비스 클래스에 동일 적용
- `LogFmt.info/error()`: 구조화 로깅 — 기존 어댑터 로그 이벤트 유지
- `ResponseType.ASYNC_202` / `ResponseType.SYNC_200`: 기존 값 그대로 재사용

### Integration Points
- `PaymentConfirmService` 인터페이스: `KafkaAsyncConfirmService`, `OutboxAsyncConfirmService`, `PaymentConfirmServiceImpl` 세 구현체가 각각 @Conditional로 등록
- `infrastructure/adapter/` 패키지: 리팩터 완료 후 빈 패키지 — 제거 대상
- `infrastructure/kafka/` 패키지: `KafkaConfirmPublisher` 추가, `KafkaConfirmListener`와 공존
- `application/port/out/` 패키지: `PaymentConfirmPublisherPort` 추가

</code_context>

<specifics>
## Specific Ideas

- "PaymentConfirmService 구현체인데 KafkaTemplate 직접 호출 중이라 레이어 역전 발생" — 이것이 리팩터의 핵심 동기
- 리팩터 완료 후 infrastructure/adapter/ 패키지에는 아무것도 남지 않아야 함 (또는 패키지 자체 제거)
- 3개 전략 모두 infrastructure 어댑터 없이 application 서비스로만 동작하는 것이 최종 목표

</specifics>

<deferred>
## Deferred Ideas

없음 — 논의가 Phase 8 범위 내에서 유지됨

</deferred>

---

*Phase: 08-refactor-confirm-adapters*
*Context gathered: 2026-03-16*
