# Phase 3: DB Outbox Adapter - Context

**Gathered:** 2026-03-15
**Status:** Ready for planning

<domain>
## Phase Boundary

confirm 요청 수신 시 재고 감소를 동기로 완료하고 Outbox 레코드를 저장한 뒤 즉시 202를 반환한다. 별도 워커가 Outbox 레코드를 조회해 Toss API를 호출하고 결제를 완료한다.

OUTBOX-01~06 요구사항이 대상이다. Kafka 어댑터, k6 성능 측정, Status 엔드포인트의 신규 추가는 이 Phase의 범위가 아니다.

</domain>

<decisions>
## Implementation Decisions

### Outbox 스토리지
- **전용 `payment_outbox` 테이블** 신규 생성 — `PaymentProcess` 테이블 재활용 안 함
- 이유: `PaymentRecoverServiceImpl.recoverStuckPayments()`가 `PaymentProcess.PROCESSING`을 조회하므로 재활용 시 race condition 위험
- 상태: `PENDING` / `IN_FLIGHT` / `DONE` / `FAILED`
  - PENDING: confirm 요청 접수, 워커 처리 대기
  - IN_FLIGHT: 워커가 처리 시작 (중복 실행 방지용)
  - DONE: Toss API 호출 성공, PaymentEvent DONE으로 전환 완료
  - FAILED: 최대 재시도 초과, 보상 트랜잭션 완료
- 페이로드: `order_id`만 저장 — 나머지 정보는 워커가 PaymentEvent에서 재조회
- 재시도: `retry_count` 컬럼 포함 — RETRYABLE_LIMIT=5 적용
- IN_FLIGHT 타임아웃 컬럼: `in_flight_at` — 앱 재시작 등 비정상 케이스 복구에 사용

### 워커 동작
- `@Scheduled(fixedDelay)` 방식, 기본값 `scheduler.outbox-worker.fixed-delay-ms=1000`
- 배치 조회: 1회 실행 시 PENDING 레코드를 최대 N건 조회 (FIFO, createdAt 오름차순), 기본값 `scheduler.outbox-worker.batch-size=10`
- **처리 모드 전환 가능**: `scheduler.outbox-worker.parallel-enabled` 설정으로 두 모드 비교 가능
  - `false` (기본): 배치 N건 순차 처리
  - `true`: Java 21 가상 스레드(`Executors.newVirtualThreadPerTaskExecutor()`)로 배치 N건 병렬 처리 — IO 블로킹(Toss HTTP) 시 플랫폼 스레드 미점유
- IN_FLIGHT timeout: `scheduler.outbox-worker.in-flight-timeout-minutes=5` — 초과 시 PENDING으로 자동 복구
- FAILED 최종 처리 시 워커가 직접 `executePaymentFailureCompensation` 호출 (재고 복원 + PaymentProcess 실패 + PaymentEvent FAILED)
- 보상 트랜잭션은 멱등하게 동작해야 함 (OUTBOX-06)

### Status 엔드포인트 PENDING 매핑
- `GET /api/v1/payments/{orderId}/status`는 **payment_outbox 먼저 조회**
  - PENDING 레코드 존재 → `PENDING` 반환
  - IN_FLIGHT 레코드 존재 → `PROCESSING` 반환
  - DONE/FAILED → PaymentEvent fallback으로 최종 상태 반환
  - payment_outbox에 없음 → PaymentEvent.status 기반 기존 매핑 로직 유지
- Phase 1에서 정의한 Status enum (`PENDING / PROCESSING / DONE / FAILED`) 변경 없음

### 트랜잭션 경계
- confirm 수신 시: 재고 감소 + `payment_outbox` PENDING 생성 → **같은 트랜잭션** (원자성 보장)
- 워커 처리 시:
  1. PENDING → IN_FLIGHT 전환: **별도 트랜잭션** (즉시 flush/commit)
  2. Toss API 호출: **트랜잭션 밖** (HTTP 호출이 롤백 대상에 포함되지 않도록)
  3. 결과 업데이트 (DONE/FAILED + PaymentEvent 전환): **별도 트랜잭션**

### Claude's Discretion
- `payment_outbox` 테이블 DDL 정확한 컬럼 타입/인덱스 설계
- `OutboxConfirmAdapter` 패키지 위치 (infrastructure/adapter/ 기존 패턴 따름)
- 워커 클래스명 (예: `OutboxWorker`, `OutboxProcessorService`)
- IN_FLIGHT 타임아웃 복구 로직을 메인 워커에 포함할지 별도 스케줄러로 분리할지

</decisions>

<code_context>
## Existing Code Insights

### Reusable Assets
- `PaymentTransactionCoordinator.executeStockDecreaseWithJobCreation()`: confirm 수신 시 재고 감소 + `PaymentProcess` Job 생성 트랜잭션 — Outbox 어댑터는 이 중 `PaymentProcess` Job 생성 없이 stock 감소만 필요하므로, 내부 분리 또는 별도 호출 검토 필요
- `PaymentTransactionCoordinator.executePaymentSuccessCompletion()` / `executePaymentFailureCompensation()`: 워커 성공/실패 시 재사용 가능
- `TossApiCallUseCase`, `PaymentCommandUseCase.confirmPaymentWithGateway()`: 워커의 Toss API 호출에 재사용
- `PaymentLoadUseCase.getPaymentEventByOrderId()`: 워커가 orderId로 PaymentEvent 조회 시 재사용
- `PaymentProcessUseCase.createProcessingJob()`: Sync 어댑터에서 사용 중 — Outbox 어댑터에서는 Outbox 레코드가 Job 역할을 대체하므로 사용하지 않음
- `SyncConfirmAdapter`: `@ConditionalOnProperty(havingValue="sync", matchIfMissing=true)` 패턴 — OutboxConfirmAdapter는 `havingValue="outbox"`로 동일 패턴 적용

### Established Patterns
- `@RequiredArgsConstructor` + `private final` 생성자 주입 — 어댑터/워커 동일 적용
- `@Scheduled(fixedDelay)`: `PaymentScheduler`에서 이미 사용 중 — 동일 방식
- `LogFmt.info/error()`: 구조화 로깅 패턴 — 워커 이벤트 로깅에 적용
- `@Transactional` 세분화: `PaymentProcessUseCase`의 메서드별 트랜잭션 패턴 — 워커 단계별 트랜잭션 분리에 참고

### Integration Points
- `PaymentConfirmService` 인터페이스: `OutboxConfirmAdapter`가 구현 (`@ConditionalOnProperty(havingValue="outbox")`)
- `PaymentLoadUseCase` (Status 엔드포인트): Outbox 상태 조회 로직 추가 필요 — 기존 `getPaymentEventByOrderId()`와 별개로 outbox 조회 포트/유즈케이스 추가
- `PaymentController.status()`: outbox PENDING 우선 조회 로직 추가
- Docker Compose: Kafka 추가는 Phase 4 범위, Phase 3에서는 변경 없음

</code_context>

<specifics>
## Specific Ideas

- 워커 병렬 모드 전환 (`parallel-enabled`)은 k6 성능 비교에도 활용 가능 — `outbox-sequential` vs `outbox-virtual-thread` 시나리오
- Java 21 가상 스레드 사용은 포트폴리오 관점에서 현대적 패턴을 보여주는 포인트

</specifics>

<deferred>
## Deferred Ideas

없음 — 논의가 Phase 3 범위 내에서 유지됨

</deferred>

---

*Phase: 03-db-outbox-adapter*
*Context gathered: 2026-03-15*
