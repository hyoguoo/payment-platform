# Phase 4: Kafka Adapter - Context

**Gathered:** 2026-03-15
**Status:** Ready for planning

<domain>
## Phase Boundary

confirm 요청 수신 시 재고 감소(동기)와 Kafka 발행을 완료하고 즉시 202를 반환한다.
`KafkaConfirmListener` 컨슈머가 토픽을 소비해 Toss API 호출 및 상태 업데이트를 처리한다.
처리 불가 메시지는 `@RetryableTopic`을 통해 최대 재시도 후 `payment-confirm-dlq` DLT로 라우팅된다.

KAFKA-01~07 요구사항이 대상이다. k6 성능 측정, Kafka UI 이외의 모니터링 확장은 이 Phase의 범위가 아니다.

</domain>

<decisions>
## Implementation Decisions

### 발행 원자성
- 재고 감소(DB)와 Kafka 발행을 **직접 발행 + 실패 시 롤백** 방식으로 처리
- `KafkaConfirmAdapter.confirm()` 내에서 재고 감소 트랜잭션과 Kafka 발행을 순서대로 실행
- Kafka 발행 실패(`KafkaException`) 시 예외를 전파하여 DB 트랜잭션이 롤백되도록 처리
- 서버 다운 엣지 케이스(재고 커밋 후 다운)는 포트폴리오 범위에서 허용 가능한 단순화로 결정

### 재시도 & DLT
- `@RetryableTopic` (non-blocking) 방식 — 실패 메시지는 retry 토픽으로 이동, 컨슈머가 다른 메시지 계속 처리 가능
- 최대 재시도 횟수: **5회** (Outbox `RETRYABLE_LIMIT=5`와 통일 — k6 비교 시 공정한 조건)
- DLT(`payment-confirm-dlq`) 도달 시: 로그 기록 + `executePaymentFailureCompensation()` 호출
  - 재고 복원 + PaymentEvent FAILED 전환 — Outbox FAILED 처리와 동일한 보상 트랜잭션 재사용

### 메시지 포맷
- 발행 내용: **orderId만** 발행 (Outbox 패턴과 동일하게 페이로드 최소화)
- 컨슈머가 orderId로 PaymentEvent를 DB에서 재조회 후 처리
- 직렬화: JSON + StringSerializer/JsonDeserializer (kafbat/kafka-ui에서 메시지 내용 직접 확인 가능)

### 멱등성 전략
- **별도 existsByOrderId 가드 없음** — Toss 멱등키에 위임
- 컨슈머는 orderId로 PaymentEvent 조회 후 Toss API 호출, 이미 DONE 상태라면 Toss가 동일 응답 반환
- PaymentEvent DONE 재기록은 멱등하게 동작하도록 처리

### Claude's Discretion
- `KafkaConfirmAdapter` 패키지 위치 (`infrastructure/adapter/` 기존 패턴 적용)
- Docker Compose Kafka KRaft 설정 세부 옵션 (파티션 수, 복제 계수 — 로컬 단일 노드 기준)
- kafbat/kafka-ui 포트 및 Docker Compose 연결 설정
- `@RetryableTopic` backoff 간격 설정
- 컨슈머 그룹 ID, Ack 모드 설정 (`spring.kafka.listener.ack-mode` — Boot 3.3.3 자동설정 키 확인 필요)
- Testcontainers Kafka 버전 및 설정 세부사항

</decisions>

<code_context>
## Existing Code Insights

### Reusable Assets
- `OutboxConfirmAdapter`: `@ConditionalOnProperty(havingValue="outbox")` 패턴 — `KafkaConfirmAdapter`는 `havingValue="kafka"`로 동일 패턴 적용
- `PaymentTransactionCoordinator.executeStockDecreaseWithOutboxCreation()`: Outbox 어댑터용이지만 재고 감소 분리 로직 참고 — Kafka 어댑터는 Outbox 생성 없이 재고 감소만 필요
- `PaymentTransactionCoordinator.executePaymentSuccessCompletion()` / `executePaymentFailureCompensation()`: 컨슈머 성공/실패 처리에 재사용 가능
- `TossApiCallUseCase`, `PaymentCommandUseCase.confirmPaymentWithGateway()`: 컨슈머의 Toss API 호출에 재사용
- `PaymentLoadUseCase.getPaymentEventByOrderId()`: 컨슈머가 orderId로 PaymentEvent 조회 시 재사용
- `PaymentOutboxStatus` enum 패턴: 참고용 — Kafka 어댑터는 별도 상태 테이블 없음
- Testcontainers MySQL (`MySQLContainer`): Testcontainers 이미 의존성 존재, Kafka 컨테이너 추가만 필요

### Established Patterns
- `@ConditionalOnProperty(name="spring.payment.async-strategy", havingValue=...)`: Phase 1~3에서 확립
- `@RequiredArgsConstructor` + `private final` 생성자 주입: 어댑터/리스너 동일 적용
- `LogFmt.info/error()`: 구조화 로깅 — 프로듀서 발행 성공/실패, 컨슈머 처리 이벤트 로깅
- `@Scheduled(fixedDelay)`: Outbox 워커에서 사용 — Kafka는 리스너 방식으로 대체
- `ResponseType.ASYNC_202`: KafkaConfirmAdapter 반환값으로 동일하게 사용

### Integration Points
- `PaymentConfirmService` 인터페이스: `KafkaConfirmAdapter`가 구현 (`@ConditionalOnProperty(havingValue="kafka")`)
- Docker Compose (`docker/compose/docker-compose.yml`): Kafka (KRaft 모드, 3.9) + kafbat/kafka-ui 서비스 추가
- `application.yml`: `spring.kafka.*` 설정 추가, `spring.payment.async-strategy=kafka` 전환 시 활성화
- `build.gradle`: `spring-kafka` + `testcontainers kafka` 의존성 추가 필요 (현재 없음)
- STATUS 엔드포인트: Kafka 어댑터는 별도 Outbox 레코드가 없으므로 Phase 3의 Outbox 우선 조회 분기는 통과되고 PaymentEvent 직접 조회로 자연스럽게 처리됨 — 코드 변경 불필요

</code_context>

<specifics>
## Specific Ideas

- Kafka 어댑터의 핵심 차별점은 **컨슈머 병렬 처리**와 **DLT 메커니즘** — k6 성능 비교에서 이 차이가 드러나야 함
- Outbox 어댑터(`parallel-enabled` 설정)와 Kafka 어댑터를 k6로 비교할 때, Kafka가 더 높은 TPS를 보여야 포트폴리오 목적 달성
- STATE.md 기록된 blocker: `spring.kafka.listener.ack-mode=RECORD` 설정 키 이름을 Boot 3.3.3 자동설정과 대조 필요 — 연구 단계에서 확인

</specifics>

<deferred>
## Deferred Ideas

없음 — 논의가 Phase 4 범위 내에서 유지됨

</deferred>

---

*Phase: 04-kafka-adapter*
*Context gathered: 2026-03-15*
