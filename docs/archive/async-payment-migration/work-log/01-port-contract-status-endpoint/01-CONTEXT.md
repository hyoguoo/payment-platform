# Phase 1: Port Contract + Status Endpoint - Context

**Gathered:** 2026-03-15
**Status:** Ready for planning

<domain>
## Phase Boundary

세 어댑터 모두의 컴파일 의존성이 되는 `PaymentConfirmService` 인터페이스(반환 타입 변경), `PaymentConfirmAsyncResult` DTO, 상태 조회 엔드포인트(`GET /api/v1/payments/{orderId}/status`)를 정의하고, `SyncConfirmAdapter`를 포함해 앱이 부트 가능한 상태로 만든다.

Sync / Outbox / Kafka 어댑터의 구체적 구현은 이 Phase의 범위가 아니다. Phase 1은 인터페이스·DTO·설정 구조·Status API를 확정한다.

</domain>

<decisions>
## Implementation Decisions

### PaymentConfirmAsyncResult 설계
- `ResponseType enum { SYNC_200, ASYNC_202 }` 필드를 포함 — 컨트롤러가 이 값만 보고 HTTP 상태코드를 결정하며, Spring 설정을 직접 읽지 않는다
- payload: `orderId` + `amount` — Sync 어댑터는 두 필드 모두 반환, 비동기 어댑터는 `orderId`만 의미있고 `amount`는 null 가능
- 포트 메서드 시그니처: `confirm(PaymentConfirmCommand): PaymentConfirmAsyncResult` (PORT-01 요구사항 명시)

### 컨트롤러 연결 방식
- 기존 `PaymentConfirmService` 인터페이스 이름을 **유지**한다 (presentation/port/ 위치 유지)
- 인터페이스 반환 타입만 `PaymentConfirmResult` → `PaymentConfirmAsyncResult`로 변경 — 컨트롤러 코드는 변경 없음
- 구현체 이름에 전략을 표현한다: `SyncConfirmAdapter`, `OutboxConfirmAdapter`, `KafkaConfirmAdapter`
- `@ConditionalOnProperty(name="spring.payment.async-strategy", havingValue="sync", matchIfMissing=true)`로 기본 어댑터 지정

### PaymentConfirmServiceImpl 처리
- `PaymentConfirmServiceImpl`은 **삭제하지 않는다** — `@Service` Bean으로 유지하되 `implements PaymentConfirmService`만 제거
- `SyncConfirmAdapter`가 `PaymentConfirmServiceImpl`을 DI로 주입받아 위임한다 (SYNC-03 선행 준수)
- 기존 `PaymentConfirmServiceImpl` 내부 로직은 수정하지 않는다

### Status 응답 스펙
- 신규 단순화 enum `PaymentStatusResponse { PENDING, PROCESSING, DONE, FAILED }` 을 정의한다
- `PaymentEventStatus` → 상태조회 enum 매핑 규칙:
  - `DONE` → `DONE`
  - `FAILED` → `FAILED`
  - `READY`, `IN_PROGRESS`, `UNKNOWN`, `EXPIRED` 등 나머지 → `PROCESSING`
  - Outbox PENDING 상태(Phase 3 이후) → `PENDING` (Phase 1에서는 해당 경로 없음)
- 데이터 소스: `PaymentEvent` 테이블 (`orderId`로 조회)
- 응답 필드: `orderId`, `status`, `approvedAt` (STATUS-02)
- 엔드포인트: `GET /api/v1/payments/{orderId}/status` — 기존 `PaymentController`에 추가

### Phase 1 완료 기준 (부트 가능성)
- Phase 1 완료 시 앱이 부트되어야 한다
- `SyncConfirmAdapter`를 Phase 1에 포함시켜 `PaymentConfirmService` 구현체를 Spring이 찾을 수 있게 한다
- `spring.payment.async-strategy` 미설정 시 `SyncConfirmAdapter`가 자동 활성화된다 (`matchIfMissing=true`)

### Claude's Discretion
- `PaymentConfirmAsyncResult`의 정확한 필드 접근자 방식 (record vs class + Lombok)
- `PaymentStatusResponse` enum의 패키지 위치 (domain/enums/ vs presentation/dto/)
- Status 엔드포인트의 예외 처리 (orderId 없을 때 404 vs 커스텀 에러코드)
- `SyncConfirmAdapter` 내 `PaymentConfirmResult` → `PaymentConfirmAsyncResult` 변환 로직 세부 구현

</decisions>

<code_context>
## Existing Code Insights

### Reusable Assets
- `PaymentConfirmService` (presentation/port/): 기존 인터페이스 — 반환 타입만 변경하여 재사용
- `PaymentConfirmServiceImpl`: 기존 confirm 로직 보존 — SyncConfirmAdapter의 위임 대상
- `PaymentConfirmCommand`: 기존 커맨드 DTO — 포트 메서드 파라미터로 그대로 재사용
- `PaymentLoadUseCase.getPaymentEventByOrderId()`: Status 엔드포인트가 orderId로 PaymentEvent 조회 시 재사용
- `PaymentEvent`: `status`, `orderId`, `approvedAt` 필드 보유 — Status 응답 매핑에 직접 활용

### Established Patterns
- `@ConditionalOnProperty`: 기존 코드베이스에 아직 없음 — Phase 1에서 첫 도입
- `@RequiredArgsConstructor` + `private final` 생성자 주입: 기존 전체 패턴 — SyncConfirmAdapter도 동일하게 적용
- `ResponseUtil.success()` 정적 팩토리: 컨트롤러 응답 생성 패턴 — Status 응답에도 동일 적용
- `PaymentPresentationMapper`: 컨트롤러 ↔ DTO 변환 클래스 — Status 응답 변환 로직 추가 위치

### Integration Points
- `PaymentController`: `@GetMapping("/api/v1/payments/{orderId}/status")` 메서드 추가
- `PaymentConfirmService` 인터페이스: 반환 타입 변경으로 컴파일 계층 전파 발생
- `PaymentConfirmServiceImpl`: `implements PaymentConfirmService` 제거 필요
- `PaymentController.confirm()`: 반환 타입이 `PaymentConfirmAsyncResult`로 바뀌므로 `ResponseEntity` 분기 로직 추가

</code_context>

<specifics>
## Specific Ideas

- `PaymentConfirmAsyncResult`의 `ResponseType.SYNC_200` → `ResponseEntity.ok()`, `ResponseType.ASYNC_202` → `ResponseEntity.accepted()` 분기를 컨트롤러에서 처리
- `SyncConfirmAdapter`는 기존 `PaymentConfirmServiceImpl.confirm()` 결과를 감싸 `PaymentConfirmAsyncResult.of(ResponseType.SYNC_200, result.getOrderId(), result.getAmount())` 형태로 반환
- 기존 `PaymentConfirmResult` DTO는 `PaymentConfirmServiceImpl` 내부 반환값으로 계속 사용되므로 삭제하지 않는다

</specifics>

<deferred>
## Deferred Ideas

- Outbox PENDING 상태의 정확한 Status 매핑 — Phase 3에서 `PaymentProcess` 상태와 연계하여 결정
- `PaymentEvent` vs `PaymentProcess` 통합 Status 쿼리 — Phase 3 Outbox 구현 이후 검토
- Phase 3 Outbox storage 결정 (전용 테이블 vs PaymentProcess 재활용) — CLAUDE.md에 명시된 Key Pending Decision, Phase 3 진입 시 결정

</deferred>

---

*Phase: 01-port-contract-status-endpoint*
*Context gathered: 2026-03-15*
