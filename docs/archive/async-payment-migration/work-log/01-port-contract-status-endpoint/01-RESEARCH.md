# Phase 1: Port Contract + Status Endpoint - Research

**Researched:** 2026-03-15
**Domain:** Spring Boot 헥사고날 아키텍처 — 포트 추상화 · 조건부 Bean 등록 · Status REST 엔드포인트
**Confidence:** HIGH

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**PaymentConfirmAsyncResult 설계**
- `ResponseType enum { SYNC_200, ASYNC_202 }` 필드를 포함 — 컨트롤러가 이 값만 보고 HTTP 상태코드를 결정하며, Spring 설정을 직접 읽지 않는다
- payload: `orderId` + `amount` — Sync 어댑터는 두 필드 모두 반환, 비동기 어댑터는 `orderId`만 의미있고 `amount`는 null 가능
- 포트 메서드 시그니처: `confirm(PaymentConfirmCommand): PaymentConfirmAsyncResult` (PORT-01 요구사항 명시)

**컨트롤러 연결 방식**
- 기존 `PaymentConfirmService` 인터페이스 이름을 **유지**한다 (presentation/port/ 위치 유지)
- 인터페이스 반환 타입만 `PaymentConfirmResult` → `PaymentConfirmAsyncResult`로 변경 — 컨트롤러 코드는 변경 없음
- 구현체 이름에 전략을 표현한다: `SyncConfirmAdapter`, `OutboxConfirmAdapter`, `KafkaConfirmAdapter`
- `@ConditionalOnProperty(name="spring.payment.async-strategy", havingValue="sync", matchIfMissing=true)`로 기본 어댑터 지정

**PaymentConfirmServiceImpl 처리**
- `PaymentConfirmServiceImpl`은 **삭제하지 않는다** — `@Service` Bean으로 유지하되 `implements PaymentConfirmService`만 제거
- `SyncConfirmAdapter`가 `PaymentConfirmServiceImpl`을 DI로 주입받아 위임한다 (SYNC-03 선행 준수)
- 기존 `PaymentConfirmServiceImpl` 내부 로직은 수정하지 않는다

**Status 응답 스펙**
- 신규 단순화 enum `PaymentStatusResponse { PENDING, PROCESSING, DONE, FAILED }` 을 정의한다
- `PaymentEventStatus` → 상태조회 enum 매핑 규칙:
  - `DONE` → `DONE`
  - `FAILED` → `FAILED`
  - `READY`, `IN_PROGRESS`, `UNKNOWN`, `EXPIRED` 등 나머지 → `PROCESSING`
  - Outbox PENDING 상태(Phase 3 이후) → `PENDING` (Phase 1에서는 해당 경로 없음)
- 데이터 소스: `PaymentEvent` 테이블 (`orderId`로 조회)
- 응답 필드: `orderId`, `status`, `approvedAt` (STATUS-02)
- 엔드포인트: `GET /api/v1/payments/{orderId}/status` — 기존 `PaymentController`에 추가

**Phase 1 완료 기준 (부트 가능성)**
- Phase 1 완료 시 앱이 부트되어야 한다
- `SyncConfirmAdapter`를 Phase 1에 포함시켜 `PaymentConfirmService` 구현체를 Spring이 찾을 수 있게 한다
- `spring.payment.async-strategy` 미설정 시 `SyncConfirmAdapter`가 자동 활성화된다 (`matchIfMissing=true`)

### Claude's Discretion
- `PaymentConfirmAsyncResult`의 정확한 필드 접근자 방식 (record vs class + Lombok)
- `PaymentStatusResponse` enum의 패키지 위치 (domain/enums/ vs presentation/dto/)
- Status 엔드포인트의 예외 처리 (orderId 없을 때 404 vs 커스텀 에러코드)
- `SyncConfirmAdapter` 내 `PaymentConfirmResult` → `PaymentConfirmAsyncResult` 변환 로직 세부 구현

### Deferred Ideas (OUT OF SCOPE)
- Outbox PENDING 상태의 정확한 Status 매핑 — Phase 3에서 `PaymentProcess` 상태와 연계하여 결정
- `PaymentEvent` vs `PaymentProcess` 통합 Status 쿼리 — Phase 3 Outbox 구현 이후 검토
- Phase 3 Outbox storage 결정 (전용 테이블 vs PaymentProcess 재활용) — Phase 3 진입 시 결정
</user_constraints>

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| PORT-01 | `PaymentConfirmAsyncPort` 인터페이스 정의 — `process(PaymentConfirmCommand): PaymentConfirmAsyncResult` | `PaymentConfirmService` 인터페이스 반환 타입 변경으로 구현. 인터페이스 이름은 유지하며 시그니처만 수정 |
| PORT-02 | `PaymentConfirmAsyncResult`는 처리 방식(sync/async)과 결과를 담아 컨트롤러가 200/202를 자동 결정 | `ResponseType` enum 필드 + 컨트롤러 분기 로직으로 구현. 현재 컨트롤러는 `ResponseEntity` 미사용 — 변경 필요 |
| PORT-03 | `spring.payment.async-strategy=sync\|outbox\|kafka` 설정값 하나로 활성 어댑터 교체 (`@ConditionalOnProperty`) | Spring Boot 3.3.3의 `@ConditionalOnProperty` 지원 확인. 코드베이스 내 첫 도입 |
| PORT-04 | 설정값이 없을 경우 기존 동기 처리(sync)가 기본값으로 동작 (`matchIfMissing=true`) | `@ConditionalOnProperty(matchIfMissing=true)` 속성으로 구현 |
| STATUS-01 | `GET /api/v1/payments/{orderId}/status`로 결제 처리 상태 조회 | `PaymentController`에 `@GetMapping` 추가. `PaymentLoadUseCase.getPaymentEventByOrderId()` 재사용 |
| STATUS-02 | 응답은 orderId, status(PENDING/PROCESSING/DONE/FAILED), approvedAt 포함 | `PaymentStatusResponse` enum + `PaymentStatusApiResponse` DTO 신규 정의 |
| STATUS-03 | 비동기 어댑터 사용 시 confirm은 즉시 202 Accepted + orderId 반환, 클라이언트는 이 엔드포인트로 완료 확인 | Phase 1에서는 SyncConfirmAdapter만 구현(200 OK). 202 경로는 Phase 3/4에서 실제 활성화 |
</phase_requirements>

---

## Summary

Phase 1은 세 가지 비동기 전략 어댑터(Sync/Outbox/Kafka) 모두의 컴파일 의존점이 되는 포트 인터페이스와 DTO를 확정하고, 상태 조회 엔드포인트를 추가하며, SyncConfirmAdapter를 포함시켜 앱이 정상 부트 가능한 상태를 만드는 작업이다.

핵심 변경은 두 가지다. (1) 기존 `PaymentConfirmService` 인터페이스의 반환 타입을 `PaymentConfirmResult` → `PaymentConfirmAsyncResult`로 교체하고, 기존 `PaymentConfirmServiceImpl`에서 `implements PaymentConfirmService`를 제거한 뒤 `SyncConfirmAdapter`가 위임 방식으로 대체한다. (2) `PaymentController`에 `@GetMapping` 엔드포인트를 추가하되, confirm 메서드도 `ResponseEntity<?>` 반환으로 변경해 `ResponseType.SYNC_200` / `ResponseType.ASYNC_202` 분기를 처리한다.

기존 코드베이스에서 재사용 가능한 자산이 풍부하다: `PaymentLoadUseCase.getPaymentEventByOrderId()`, `PaymentEvent.status/approvedAt` 필드, `PaymentFoundException`(→ 404 매핑 이미 존재), `PaymentPresentationMapper`(→ Status 변환 메서드 추가 위치). `@ConditionalOnProperty`는 코드베이스 첫 도입이지만 Spring Boot 3.3.3이 완전 지원한다.

**Primary recommendation:** `PaymentConfirmService` 인터페이스 반환 타입 변경 → `SyncConfirmAdapter` 생성 → `PaymentConfirmServiceImpl` implements 제거 순서로 진행하되, 컴파일 오류 전파 범위(컨트롤러·매퍼)를 한 번에 해결한다.

---

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Spring Boot | 3.3.3 | 전체 프레임워크 | 프로젝트 기존 선택 |
| Spring Web MVC | (Boot 관리) | REST 컨트롤러, `ResponseEntity` | 표준 HTTP 응답 제어 |
| Spring Context | (Boot 관리) | `@ConditionalOnProperty`, `@Bean` 조건부 등록 | 어댑터 전략 교체 핵심 |
| Lombok | (Boot 관리) | `@Getter`, `@Builder`, `@RequiredArgsConstructor` | 코드베이스 전체 패턴 |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| Spring Data JPA | (Boot 관리) | `PaymentEventRepository` 조회 | Status 엔드포인트의 데이터 소스 |
| AssertJ + JUnit 5 | (Boot 관리) | 단위/통합 테스트 | 모든 신규 클래스 테스트 |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| `@Getter @Builder` (Lombok) | Java `record` | `PaymentConfirmAsyncResult`는 record도 가능(불변 DTO). 그러나 코드베이스 전체가 Lombok 패턴 사용 — Lombok 유지 권장 |
| `PaymentStatusResponse` enum in `presentation/dto/` | `domain/enums/` | presentation 계층 DTO이므로 `presentation/dto/response/` 또는 `presentation/dto/enums/` 패키지가 적합 |

---

## Architecture Patterns

### Recommended Project Structure (신규 파일 위치)

```
payment/
├── presentation/
│   ├── port/
│   │   └── PaymentConfirmService.java          # 반환 타입만 변경 (기존 파일 수정)
│   ├── dto/
│   │   └── response/
│   │       └── PaymentStatusApiResponse.java   # 신규: Status 엔드포인트 응답 DTO
│   ├── PaymentController.java                  # confirm() + status() 메서드 수정/추가
│   └── PaymentPresentationMapper.java          # toPaymentStatusApiResponse() 추가
├── application/
│   └── dto/
│       └── response/
│           ├── PaymentConfirmResult.java       # 기존 유지 (내부 DTO)
│           └── PaymentConfirmAsyncResult.java  # 신규: 포트 반환 DTO
└── infrastructure/
    └── adapter/
        └── SyncConfirmAdapter.java             # 신규: PaymentConfirmService 구현체
```

**패키지 선택 근거:**
- `SyncConfirmAdapter`는 infrastructure 계층에 위치 — 외부 전략(어댑터)이므로 application이 아닌 infrastructure가 적합
- `PaymentConfirmAsyncResult`는 `application/dto/response/` — 포트 메서드 반환 타입이며 application 계층 DTO
- `PaymentStatusApiResponse`는 `presentation/dto/response/` — 컨트롤러 응답 DTO 패턴 일치

### Pattern 1: @ConditionalOnProperty를 이용한 어댑터 교체

**What:** 동일 인터페이스의 구현체를 Spring 설정값 하나로 교체
**When to use:** 런타임 전략 교체가 필요한 모든 어댑터
**Example:**
```java
// SyncConfirmAdapter.java
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = "spring.payment.async-strategy",
    havingValue = "sync",
    matchIfMissing = true  // 설정 없을 때 기본 활성화
)
public class SyncConfirmAdapter implements PaymentConfirmService {

    private final PaymentConfirmServiceImpl paymentConfirmServiceImpl;

    @Override
    public PaymentConfirmAsyncResult confirm(PaymentConfirmCommand command) {
        PaymentConfirmResult result = paymentConfirmServiceImpl.confirm(command);
        return PaymentConfirmAsyncResult.builder()
                .responseType(ResponseType.SYNC_200)
                .orderId(result.getOrderId())
                .amount(result.getAmount())
                .build();
    }
}
```

**중요:** `name` 속성에 `spring.` prefix가 있는 커스텀 프로퍼티는 Spring Boot에서 정상 작동한다. 단, application.yml에서 `spring.payment.async-strategy`를 별도 섹션으로 관리해야 한다.

### Pattern 2: ResponseType enum을 통한 HTTP 상태코드 결정

**What:** 컨트롤러가 Spring config를 직접 읽지 않고, DTO의 enum 값으로 HTTP 상태코드 분기
**When to use:** 어댑터 전략마다 HTTP 응답 코드가 다를 때
**Example:**
```java
// PaymentController.java - confirm() 수정
@PostMapping("/api/v1/payments/confirm")
public ResponseEntity<?> confirm(@RequestBody PaymentConfirmRequest request) {
    PaymentConfirmCommand command = PaymentPresentationMapper.toPaymentConfirmCommand(request);
    PaymentConfirmAsyncResult result = paymentConfirmService.confirm(command);

    if (result.getResponseType() == ResponseType.ASYNC_202) {
        return ResponseEntity.accepted()
                .body(PaymentPresentationMapper.toPaymentConfirmResponse(result));
    }
    return ResponseEntity.ok(PaymentPresentationMapper.toPaymentConfirmResponse(result));
}
```

### Pattern 3: Status 엔드포인트 — PaymentLoadUseCase 재사용

**What:** 기존 `getPaymentEventByOrderId()`로 `PaymentEvent`를 조회하고, `PaymentEventStatus` → `PaymentStatusResponse` 매핑
**When to use:** 새 쿼리 없이 기존 도메인 모델 재사용
**Example:**
```java
// PaymentController.java - 신규 메서드
@GetMapping("/api/v1/payments/{orderId}/status")
public ResponseEntity<PaymentStatusApiResponse> getPaymentStatus(
        @PathVariable String orderId) {
    PaymentEvent paymentEvent = paymentLoadUseCase.getPaymentEventByOrderId(orderId);
    return ResponseEntity.ok(PaymentPresentationMapper.toPaymentStatusApiResponse(paymentEvent));
}
```

```java
// PaymentPresentationMapper.java - 신규 메서드 추가
public static PaymentStatusApiResponse toPaymentStatusApiResponse(PaymentEvent event) {
    return PaymentStatusApiResponse.builder()
            .orderId(event.getOrderId())
            .status(mapToPaymentStatusResponse(event.getStatus()))
            .approvedAt(event.getApprovedAt())
            .build();
}

private static PaymentStatusResponse mapToPaymentStatusResponse(PaymentEventStatus status) {
    return switch (status) {
        case DONE -> PaymentStatusResponse.DONE;
        case FAILED -> PaymentStatusResponse.FAILED;
        default -> PaymentStatusResponse.PROCESSING;
    };
}
```

### Anti-Patterns to Avoid

- **Config 직접 읽기:** 컨트롤러에서 `@Value("${spring.payment.async-strategy}")` 읽어 분기 — `ResponseType` enum으로 대체
- **PaymentConfirmServiceImpl 삭제:** 기존 로직 삭제 시 SYNC-03 위반 및 Phase 2 이후 테스트 깨짐
- **implements 유지 문제:** `PaymentConfirmServiceImpl`이 `PaymentConfirmService`를 계속 implements하면 `SyncConfirmAdapter`와 Bean 충돌 → `NoUniqueBeanDefinitionException`
- **SyncConfirmAdapter를 application 계층에 배치:** 어댑터는 infrastructure/adapter 위치가 헥사고날 원칙에 부합

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| 조건부 Bean 등록 | 수동 if/else Bean 팩토리 | `@ConditionalOnProperty` | Spring Boot 자동 조건 평가, 프로파일 안전 |
| orderId 미존재 예외 | 커스텀 상태 조회 예외 | 기존 `PaymentFoundException` | 이미 `PaymentExceptionHandler`가 404로 매핑 처리 중 |
| HTTP 상태코드 설정 | 직접 `HttpServletResponse.setStatus()` | `ResponseEntity.ok()` / `ResponseEntity.accepted()` | Spring MVC 표준 패턴 |
| PaymentEvent 조회 | 별도 Status 조회 쿼리/리포지토리 | `PaymentLoadUseCase.getPaymentEventByOrderId()` | 동일 로직 중복 방지 |

**Key insight:** `PaymentFoundException`은 이미 `PaymentExceptionHandler`에서 HTTP 404로 처리된다. Status 엔드포인트에서 orderId가 없을 때 추가 예외 클래스 없이 동일 동작을 얻을 수 있다.

---

## Common Pitfalls

### Pitfall 1: Bean 중복 등록 — NoUniqueBeanDefinitionException
**What goes wrong:** `PaymentConfirmServiceImpl`이 `implements PaymentConfirmService`를 유지하면서 `SyncConfirmAdapter`도 동일 인터페이스를 구현하면 Spring이 `PaymentConfirmService` Bean을 유일하게 결정하지 못한다
**Why it happens:** `@ConditionalOnProperty`는 `SyncConfirmAdapter`에만 적용되므로 `PaymentConfirmServiceImpl`이 무조건 등록되면 둘 다 활성화됨
**How to avoid:** `PaymentConfirmServiceImpl`에서 `implements PaymentConfirmService`를 **반드시** 제거한다
**Warning signs:** 앱 시작 시 `NoUniqueBeanDefinitionException: expected single matching bean but found 2` 에러

### Pitfall 2: 컨트롤러 반환 타입 변경 누락
**What goes wrong:** `PaymentController.confirm()`이 여전히 `PaymentConfirmResponse`를 직접 반환하면 200/202 분기 로직을 넣을 수 없다
**Why it happens:** 현재 컨트롤러는 `ResponseEntity` 없이 DTO를 직접 반환 — 반환 타입 변경 없이는 HTTP 상태코드 제어 불가
**How to avoid:** `confirm()` 반환 타입을 `ResponseEntity<PaymentConfirmResponse>`로 변경하고 분기 로직 추가
**Warning signs:** 컴파일은 되지만 Kafka 어댑터 추가 시 항상 200을 반환하게 됨

### Pitfall 3: @ConditionalOnProperty name 속성 오기재
**What goes wrong:** `name = "payment.async-strategy"` (spring. prefix 누락) vs `name = "spring.payment.async-strategy"` 불일치
**Why it happens:** Spring Boot `application.yml`의 프로퍼티 경로와 `@ConditionalOnProperty.name` 경로가 완전히 일치해야 함
**How to avoid:** `application.yml`에 `spring.payment.async-strategy: sync` 항목을 명시적으로 추가하고 `name` 값과 동일하게 작성
**Warning signs:** 설정을 추가해도 `SyncConfirmAdapter` 외 어댑터가 활성화되지 않거나, `matchIfMissing=true`인데도 Bean이 등록 안 됨

### Pitfall 4: PaymentStatusApiResponse.approvedAt null 처리
**What goes wrong:** Sync confirm이 완료되기 전(READY/IN_PROGRESS) 상태에서 조회하면 `approvedAt`이 null — 직렬화 오류 또는 NullPointerException 가능
**Why it happens:** `PaymentEvent.approvedAt`은 결제 완료 전까지 null
**How to avoid:** `approvedAt` 필드를 nullable로 선언하고 JSON 직렬화 시 null 허용. `@JsonInclude(NON_NULL)` 또는 null 그대로 직렬화(STATUS-02 필드 포함 요건이므로 null 포함 반환)

### Pitfall 5: PaymentConfirmServiceImpl 테스트 파일 참조 문제
**What goes wrong:** 기존 `PaymentConfirmServiceImplTest`가 `PaymentConfirmService` 포트를 통해 서비스를 생성하는 코드가 있으면 컴파일 오류
**Why it happens:** `implements PaymentConfirmService` 제거 후 타입 불일치
**How to avoid:** 테스트는 `PaymentConfirmServiceImpl`을 직접 구체 타입으로 참조 — 현재 테스트 코드 확인 결과 이미 `paymentConfirmService = new PaymentConfirmServiceImpl(...)` 패턴 사용 중이라 문제 없음

---

## Code Examples

Verified patterns from existing codebase:

### PaymentConfirmAsyncResult — Lombok Builder 패턴 (기존 코드베이스 스타일)
```java
// application/dto/response/PaymentConfirmAsyncResult.java
@Getter
@Builder
public class PaymentConfirmAsyncResult {

    private final ResponseType responseType;
    private final String orderId;
    private final BigDecimal amount;  // nullable (async 어댑터 시)

    public enum ResponseType {
        SYNC_200,
        ASYNC_202
    }
}
```

### PaymentStatusResponse enum 위치 권장
```java
// presentation/dto/response/enums/PaymentStatusResponse.java
// 또는 presentation/dto/response/PaymentStatusResponse.java
public enum PaymentStatusResponse {
    PENDING,
    PROCESSING,
    DONE,
    FAILED
}
```

**결정:** presentation 계층 응답 DTO용 enum이므로 `presentation/dto/response/` 또는 하위 `enums/` 서브패키지. domain/enums에 두면 presentation → domain 의존이 역방향이 되어 헥사고날 원칙 위반 없음(허용). 그러나 STATUS 응답 표현 목적이므로 presentation에 위치하는 것이 의미론적으로 정확.

### PaymentStatusApiResponse DTO
```java
// presentation/dto/response/PaymentStatusApiResponse.java
@Getter
@Builder
public class PaymentStatusApiResponse {

    private final String orderId;
    private final PaymentStatusResponse status;
    private final LocalDateTime approvedAt;  // null 가능
}
```

### SyncConfirmAdapter — 위임 패턴
```java
// infrastructure/adapter/SyncConfirmAdapter.java
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = "spring.payment.async-strategy",
    havingValue = "sync",
    matchIfMissing = true
)
public class SyncConfirmAdapter implements PaymentConfirmService {

    private final PaymentConfirmServiceImpl paymentConfirmServiceImpl;

    @Override
    public PaymentConfirmAsyncResult confirm(PaymentConfirmCommand command) {
        PaymentConfirmResult result = paymentConfirmServiceImpl.confirm(command);
        return PaymentConfirmAsyncResult.builder()
                .responseType(PaymentConfirmAsyncResult.ResponseType.SYNC_200)
                .orderId(result.getOrderId())
                .amount(result.getAmount())
                .build();
    }
}
```

### PaymentController — 수정된 confirm + 신규 status
```java
// presentation/PaymentController.java (수정)
@PostMapping("/api/v1/payments/confirm")
public ResponseEntity<PaymentConfirmResponse> confirm(
        @RequestBody PaymentConfirmRequest request
) {
    PaymentConfirmCommand command = PaymentPresentationMapper.toPaymentConfirmCommand(request);
    PaymentConfirmAsyncResult result = paymentConfirmService.confirm(command);
    PaymentConfirmResponse response = PaymentPresentationMapper.toPaymentConfirmResponse(result);

    if (result.getResponseType() == PaymentConfirmAsyncResult.ResponseType.ASYNC_202) {
        return ResponseEntity.accepted().body(response);
    }
    return ResponseEntity.ok(response);
}

@GetMapping("/api/v1/payments/{orderId}/status")
public ResponseEntity<PaymentStatusApiResponse> getPaymentStatus(
        @PathVariable String orderId
) {
    PaymentEvent paymentEvent = paymentLoadUseCase.getPaymentEventByOrderId(orderId);
    return ResponseEntity.ok(PaymentPresentationMapper.toPaymentStatusApiResponse(paymentEvent));
}
```

**주의:** `PaymentController`가 현재 `PaymentLoadUseCase`를 직접 주입받지 않으므로, DI 필드 추가가 필요하다. `PaymentCheckoutService`는 이미 주입 중 — `paymentLoadUseCase`도 추가한다.

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `PaymentConfirmService.confirm()` → `PaymentConfirmResult` | `PaymentConfirmService.confirm()` → `PaymentConfirmAsyncResult` | Phase 1 | 컴파일 의존 전파: PaymentController, PaymentPresentationMapper, PaymentConfirmServiceImplTest 영향권 |
| `PaymentConfirmServiceImpl implements PaymentConfirmService` | `PaymentConfirmServiceImpl` 독립 `@Service` + `SyncConfirmAdapter` 위임 | Phase 1 | `implements` 제거로 Bean 중복 제거, 기존 테스트 영향 없음 |
| Controller가 DTO 직접 반환 | Controller가 `ResponseEntity<?>` 반환 | Phase 1 | Jackson MixIn 테스트 코드 수정 가능성 검토 필요 |

**기존 `PaymentConfirmResponseMixin`:** 현재 테스트에서 `PaymentConfirmResponse`의 Jackson 역직렬화를 위해 mixin 사용 중. `PaymentConfirmResponse` 구조가 변경되지 않으면 mixin도 그대로 유지.

---

## Open Questions

1. **PaymentController에 paymentLoadUseCase DI 추가 방식**
   - What we know: 현재 `PaymentController`는 `PaymentCheckoutService`와 `PaymentConfirmService`만 주입받음
   - What's unclear: `PaymentLoadUseCase`를 컨트롤러에 직접 주입하면 application 계층 use case가 presentation 계층 컨트롤러에 노출됨 — 헥사고날 원칙상 presentation → application port(interface)를 통해 접근해야 함
   - Recommendation: `PaymentStatusService` 인터페이스를 `presentation/port/`에 추가하고, 그 구현이 `PaymentLoadUseCase`를 감싸는 방식이 헥사고날 원칙 순수 적용. 그러나 코드베이스 기존 패턴 확인: `PaymentCheckoutService`가 presentation/port/에 있고 `PaymentCheckoutServiceImpl`이 구현. 동일 패턴으로 `PaymentStatusService` + `PaymentStatusServiceImpl` 추가. **단, 복잡도 대비 효용이 낮으므로 플래너가 최종 결정.**

2. **PaymentConfirmResponse 구조 변경 여부**
   - What we know: 현재 `PaymentConfirmResponse`는 `orderId + amount`. 비동기 어댑터 시 `amount`가 null
   - What's unclear: 클라이언트가 `amount: null`을 받았을 때 처리 가능한지
   - Recommendation: `amount` 필드를 nullable로 유지하고 JSON에 `null`로 직렬화. 또는 `@JsonInclude(NON_NULL)`로 amount 생략. Phase 1 Sync 어댑터에서는 amount가 항상 존재하므로 실제 문제 발생 시점은 Phase 3/4.

---

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 + Mockito + AssertJ (Spring Boot 관리) |
| Config file | `src/test/resources/application-test.yml` |
| Quick run command | `./gradlew test --tests "*.SyncConfirmAdapterTest" --tests "*.PaymentConfirmServiceTest"` |
| Full suite command | `./gradlew test` |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| PORT-01 | `PaymentConfirmService.confirm()` 반환 타입이 `PaymentConfirmAsyncResult`임을 컴파일 확인 | compile | `./gradlew compileJava` | ❌ Wave 0 (인터페이스 변경) |
| PORT-02 | `ResponseType.SYNC_200` 시 컨트롤러가 200 반환 | unit | `./gradlew test --tests "*.PaymentControllerStatusCodeTest"` | ❌ Wave 0 |
| PORT-03 | `spring.payment.async-strategy=sync` 설정 시 `SyncConfirmAdapter` Bean 활성화 | unit | `./gradlew test --tests "*.SyncConfirmAdapterTest"` | ❌ Wave 0 |
| PORT-04 | 설정값 없을 때 `SyncConfirmAdapter`가 기본 Bean으로 등록됨 | unit | `./gradlew test --tests "*.SyncConfirmAdapterTest"` | ❌ Wave 0 |
| STATUS-01 | `GET /api/v1/payments/{orderId}/status` 200 응답 | integration | `./gradlew test --tests "*.PaymentControllerTest"` | ✅ 기존 파일에 추가 |
| STATUS-02 | 응답 body에 orderId, status, approvedAt 포함 | integration | `./gradlew test --tests "*.PaymentControllerTest"` | ✅ 기존 파일에 추가 |
| STATUS-03 | 존재하지 않는 orderId 조회 시 404 반환 | integration | `./gradlew test --tests "*.PaymentControllerTest"` | ✅ 기존 파일에 추가 |

### Sampling Rate
- **Per task commit:** `./gradlew test --tests "*.SyncConfirmAdapterTest" --tests "*.PaymentControllerTest"`
- **Per wave merge:** `./gradlew test`
- **Phase gate:** Full suite green before `/gsd:verify-work`

### Wave 0 Gaps
- [ ] `src/test/java/.../payment/infrastructure/adapter/SyncConfirmAdapterTest.java` — PORT-03, PORT-04 커버
- [ ] `src/test/java/.../payment/presentation/PaymentControllerStatusCodeTest.java` — PORT-02 커버 (또는 기존 `PaymentControllerTest`에 통합)
- 기존 `PaymentControllerTest.java`에 STATUS-01/02/03 시나리오 테스트 메서드 추가

---

## Sources

### Primary (HIGH confidence)
- 직접 코드 분석 — `PaymentConfirmService`, `PaymentConfirmServiceImpl`, `PaymentController`, `PaymentLoadUseCase`, `PaymentEvent`, `PaymentExceptionHandler`, `PaymentPresentationMapper`
- `.planning/codebase/ARCHITECTURE.md` — 헥사고날 레이어 규칙, 모듈 경계
- `.planning/codebase/CONVENTIONS.md` — Lombok 패턴, 예외 처리, 네이밍
- `.planning/codebase/TESTING.md` — 테스트 전략, Fake vs Mock, 패턴
- `01-CONTEXT.md` — 잠금된 구현 결정 사항

### Secondary (MEDIUM confidence)
- `.planning/codebase/STACK.md` — Spring Boot 3.3.3, `@ConditionalOnProperty` 지원 확인
- `application.yml` — 기존 설정 구조 (`spring.payment.*` 네임스페이스 미사용 확인)

### Tertiary (LOW confidence)
- 없음

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — Spring Boot 3.3.3 코드베이스 직접 확인
- Architecture: HIGH — 기존 코드의 헥사고날 패턴 직접 분석
- Pitfalls: HIGH — 실제 코드 파일(컨트롤러 반환 타입, Bean 중복 가능성)에서 도출

**Research date:** 2026-03-15
**Valid until:** 2026-04-15 (stable — Spring Boot 3.3.3 LTS 범위 내)
