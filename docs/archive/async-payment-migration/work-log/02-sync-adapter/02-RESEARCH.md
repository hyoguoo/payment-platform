# Phase 2: Sync Adapter - Research

**Researched:** 2026-03-15
**Domain:** Spring Boot @ConditionalOnProperty adapter pattern, hexagonal port wrapping, regression baseline formalization
**Confidence:** HIGH

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

#### Phase 2 작업 범위
- **추가 구현 없음** — `SyncConfirmAdapter` 구현 + 관련 단위 테스트 + 통합 테스트가 Phase 1에서 이미 완료됨
- Phase 2 작업은 REQUIREMENTS.md SYNC-01/02/03 체크마크 업데이트와 상태 공식화에 한정됨

#### 명시적 `spring.payment.async-strategy=sync` 테스트
- **별도 테스트 추가 불필요** — `matchIfMissing=true`가 "미설정 = sync 명시 설정"을 동일하게 커버
- 현재 `PaymentControllerTest`의 전체 통합 테스트 슈트(성공/재고부족/재시도가능오류/재시도불가오류/타임아웃)가 회귀 기준선으로 충분

#### SYNC-03 불변 검증
- 별도 테스트 추가 없음 — `PaymentConfirmServiceImpl` 내부 코드는 Phase 1에서 변경되지 않았음이 코드베이스에서 직접 확인됨
- `SyncConfirmAdapter`가 위임만 수행하는 구조가 기존 코드 유지를 구조적으로 보장

### Claude's Discretion
- REQUIREMENTS.md 체크마크 업데이트 시 SYNC-01/02/03 세 항목 모두 일괄 완료 처리

### Deferred Ideas (OUT OF SCOPE)
None — discussion stayed within phase scope
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| SYNC-01 | 기존 동기 confirm 처리 로직을 `PaymentConfirmAsyncPort` 구현체(`SyncConfirmAdapter`)로 래핑한다 | `SyncConfirmAdapter` 구현이 이미 `src/main/java/.../payment/infrastructure/adapter/SyncConfirmAdapter.java`에 존재함 — Phase 1에서 완료 확인 |
| SYNC-02 | Sync 어댑터 사용 시 기존 동작(200 OK + 결제 결과)이 그대로 유지된다 | `PaymentControllerMvcTest.confirmPayment_SyncAdapter_Returns200()` + `PaymentControllerTest` 5개 시나리오가 이미 검증 중 |
| SYNC-03 | 기존 `PaymentConfirmServiceImpl` 내부 로직은 변경하지 않는다 — 어댑터가 위임만 한다 | `SyncConfirmAdapter`는 `paymentConfirmServiceImpl.confirm(command)`에만 위임; `PaymentConfirmServiceImpl`은 `@Service`만 유지하며 인터페이스 미구현 |
</phase_requirements>

## Summary

Phase 2는 구현 단계가 아닌 **공식화(formalization) 단계**다. SYNC-01/02/03을 충족하는 모든 코드와 테스트가 Phase 1에서 이미 작성되고 검증되었다. Phase 2의 유일한 실질 작업은 REQUIREMENTS.md의 세 체크마크를 `[ ]`에서 `[x]`로 업데이트하고, 이 상태를 STATE.md에 기록하는 것이다.

`SyncConfirmAdapter`는 `payment/infrastructure/adapter/` 패키지에 위치하며, `@ConditionalOnProperty(name="spring.payment.async-strategy", havingValue="sync", matchIfMissing=true)`로 활성화된다. 어댑터는 `PaymentConfirmService` 포트를 구현하며, 내부에서 `PaymentConfirmServiceImpl.confirm()`에 단순 위임한 후 `ResponseType.SYNC_200`이 담긴 `PaymentConfirmAsyncResult`를 반환한다. `PaymentConfirmServiceImpl`은 `@Service`만 보유하고 어떤 포트 인터페이스도 직접 구현하지 않아, 어댑터가 유일한 포트 구현체임을 구조적으로 강제한다.

**Primary recommendation:** REQUIREMENTS.md SYNC-01/02/03 항목을 완료(checked) 처리하고 STATE.md를 업데이트하는 단일 작업만 계획한다. 코드 변경은 없다.

## Standard Stack

### Core (이미 사용 중)
| Component | Location | Purpose | Status |
|-----------|----------|---------|--------|
| `SyncConfirmAdapter` | `payment/infrastructure/adapter/SyncConfirmAdapter.java` | `PaymentConfirmService` 포트의 동기 구현체 | 완료 |
| `PaymentConfirmAsyncResult` | `payment/application/dto/response/PaymentConfirmAsyncResult.java` | `ResponseType.SYNC_200` / `ASYNC_202` enum + 결과 필드 | 완료 |
| `PaymentConfirmServiceImpl` | `payment/application/PaymentConfirmServiceImpl.java` | 실제 confirm 비즈니스 로직 — 변경 금지 | 완료 |
| `PaymentConfirmService` (port) | `payment/presentation/port/PaymentConfirmService.java` | 컨트롤러가 의존하는 포트 인터페이스 | 완료 |

### Test Coverage (이미 존재)
| Test Class | Type | Covers |
|------------|------|--------|
| `SyncConfirmAdapterTest` | Unit (Mockito) | 위임 검증 + `@ConditionalOnProperty` 어노테이션 검증 |
| `PaymentControllerMvcTest` | `@WebMvcTest` | `SYNC_200` → HTTP 200 분기 검증 (PORT-02 / SYNC-02) |
| `PaymentControllerTest` | Integration (Testcontainers MySQL) | 5개 e2e 시나리오 — 회귀 기준선 |

## Architecture Patterns

### Established Pattern: Conditional Adapter Registration
```java
// Source: src/main/java/.../payment/infrastructure/adapter/SyncConfirmAdapter.java
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
    public PaymentConfirmAsyncResult confirm(PaymentConfirmCommand paymentConfirmCommand) {
        PaymentConfirmResult result = paymentConfirmServiceImpl.confirm(paymentConfirmCommand);

        return PaymentConfirmAsyncResult.builder()
                .responseType(ResponseType.SYNC_200)
                .orderId(result.getOrderId())
                .amount(result.getAmount())
                .build();
    }
}
```

`matchIfMissing=true`가 SYNC-02("미설정 시에도 200 OK") 요건을 구조적으로 충족한다. `OutboxConfirmAdapter`와 `KafkaConfirmAdapter`(Phase 3/4)는 동일 패턴을 `matchIfMissing=false`로 사용하게 된다.

### Established Pattern: ResponseType-driven HTTP Status
```java
// Source: PaymentConfirmAsyncResult.java
public enum ResponseType {
    SYNC_200,
    ASYNC_202
}
```
컨트롤러는 이 enum 값을 읽어 `HttpStatus.OK` 또는 `HttpStatus.ACCEPTED`를 결정한다. Spring Bean 설정을 직접 읽지 않으므로 Spring 컨텍스트 결합이 없다.

### Established Pattern: Delegation-only Adapter (SYNC-03 구조적 보장)
```java
// PaymentConfirmServiceImpl은 인터페이스를 직접 구현하지 않음
@Service
@RequiredArgsConstructor
public class PaymentConfirmServiceImpl { // implements 없음
    public PaymentConfirmResult confirm(PaymentConfirmCommand cmd) { ... }
}
```
`PaymentConfirmServiceImpl`이 포트를 직접 구현하지 않으므로, 어댑터를 우회하는 컨트롤러 직접 주입이 불가능하다. 이로써 SYNC-03 "내부 로직 변경 없음"이 구조적으로 강제된다.

### Anti-Patterns to Avoid
- **`PaymentConfirmServiceImpl`에 `implements PaymentConfirmService` 추가**: Phase 1에서 의도적으로 제거됨 — 절대 복원하지 않는다.
- **Spring config 직접 주입으로 HTTP 상태 결정**: `ResponseType` enum이 이 역할을 담당한다. 컨트롤러에서 `@Value("${spring.payment.async-strategy}")` 를 읽으면 안 된다.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Bean 조건부 활성화 | 커스텀 BeanFactory 조건 로직 | `@ConditionalOnProperty` | Spring Boot 내장, `matchIfMissing` 지원 |
| HTTP 상태 코드 분기 | 어댑터/컨트롤러에서 config 직접 읽기 | `PaymentConfirmAsyncResult.ResponseType` | 이미 Phase 1에서 설계되고 테스트됨 |

## Common Pitfalls

### Pitfall 1: 비-작업을 작업으로 오해
**What goes wrong:** Phase 2에 새 코드를 작성하려 시도한다.
**Why it happens:** REQUIREMENTS.md에 SYNC-01/02/03이 여전히 `[ ]`(미체크)로 표시되어 미완료처럼 보인다.
**How to avoid:** CONTEXT.md의 명시적 결정을 확인한다 — "추가 구현 없음". 작업은 체크마크 업데이트뿐이다.
**Warning signs:** 플랜에 `.java` 파일 생성/수정 작업이 포함되면 범위 위반이다.

### Pitfall 2: `matchIfMissing=true` 동작 오해
**What goes wrong:** `spring.payment.async-strategy` 설정 없이 실행할 때 어댑터가 활성화되지 않는다고 가정한다.
**Why it happens:** `@ConditionalOnProperty`의 기본 동작이 `matchIfMissing=false`임을 인지하지 못한다.
**How to avoid:** 코드에서 직접 확인됨: `matchIfMissing = true`로 설정되어 있어 미설정 = sync 활성화이다.

### Pitfall 3: REQUIREMENTS.md와 Traceability 테이블 불일치
**What goes wrong:** `## v1 Requirements` 섹션만 업데이트하고 `## Traceability` 테이블의 Status를 "Complete"로 갱신하지 않는다.
**How to avoid:** 두 섹션을 동시에 업데이트한다 — 체크마크와 Traceability Status 모두.

## Code Examples

### 어노테이션 검증 패턴 (이미 존재하는 테스트)
```java
// Source: SyncConfirmAdapterTest.java
@Test
void conditional_property() {
    ConditionalOnProperty annotation = SyncConfirmAdapter.class
            .getAnnotation(ConditionalOnProperty.class);

    assertThat(annotation).isNotNull();
    assertThat(annotation.name()).containsExactly("spring.payment.async-strategy");
    assertThat(annotation.havingValue()).isEqualTo("sync");
    assertThat(annotation.matchIfMissing()).isTrue();
}
```
이 패턴은 Phase 3/4의 `OutboxConfirmAdapter`, `KafkaConfirmAdapter` 테스트에도 재사용 가능하다.

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `PaymentController` → `PaymentConfirmServiceImpl` 직접 의존 | `PaymentController` → `PaymentConfirmService`(port) → `SyncConfirmAdapter` → `PaymentConfirmServiceImpl` | Phase 1 | 비동기 어댑터 교체 가능 |
| `PaymentConfirmServiceImpl implements PaymentConfirmService` | `PaymentConfirmServiceImpl` 독립 `@Service`, 어댑터가 유일한 포트 구현 | Phase 1 | 어댑터 우회 불가, 경계 명확 |

## Open Questions

없음 — Phase 2 범위는 CONTEXT.md에서 완전히 확정되었다.

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 + Mockito + Spring Boot Test (Spring Boot managed versions) |
| Config file | `build.gradle` (Spring Boot test dependencies); Testcontainers for integration |
| Quick run command | `./gradlew test --tests "*.SyncConfirmAdapterTest"` |
| Full suite command | `./gradlew test` |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| SYNC-01 | `SyncConfirmAdapter`가 `PaymentConfirmAsyncPort` 구현체로 동작하고 `PaymentConfirmServiceImpl`에 위임한다 | unit | `./gradlew test --tests "*.SyncConfirmAdapterTest.confirm_success"` | ✅ |
| SYNC-02 | Sync 어댑터 시 HTTP 200 + 결제 결과 반환 | unit (WebMvcTest) + integration | `./gradlew test --tests "*.PaymentControllerMvcTest.confirmPayment_SyncAdapter_Returns200"` | ✅ |
| SYNC-03 | `SyncConfirmAdapter`에 `@ConditionalOnProperty(matchIfMissing=true)` 선언; `PaymentConfirmServiceImpl` 내부 코드 변경 없음 | unit (annotation inspection) | `./gradlew test --tests "*.SyncConfirmAdapterTest.conditional_property"` | ✅ |

### Sampling Rate
- **Per task commit:** `./gradlew test --tests "*.SyncConfirmAdapterTest" --tests "*.PaymentControllerMvcTest"`
- **Per wave merge:** `./gradlew test`
- **Phase gate:** Full suite green before `/gsd:verify-work`

### Wave 0 Gaps
None — existing test infrastructure covers all phase requirements. 모든 테스트 파일이 Phase 1에서 작성 완료되었다.

## Sources

### Primary (HIGH confidence)
- Direct code inspection — `src/main/java/.../payment/infrastructure/adapter/SyncConfirmAdapter.java`
- Direct code inspection — `src/test/java/.../payment/infrastructure/adapter/SyncConfirmAdapterTest.java`
- Direct code inspection — `src/main/java/.../payment/application/PaymentConfirmServiceImpl.java`
- Direct code inspection — `src/main/java/.../payment/application/dto/response/PaymentConfirmAsyncResult.java`
- Direct code inspection — `src/test/java/.../payment/presentation/PaymentControllerMvcTest.java`
- `.planning/phases/02-sync-adapter/02-CONTEXT.md` — locked decisions

### Secondary (MEDIUM confidence)
- `.planning/codebase/ARCHITECTURE.md` — hexagonal pattern confirmation
- `.planning/codebase/TESTING.md` — test infrastructure verification

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — code directly inspected, all components verified present
- Architecture: HIGH — hexagonal pattern with `@ConditionalOnProperty` directly observed in codebase
- Pitfalls: HIGH — based on direct code observation + CONTEXT.md explicit decisions

**Research date:** 2026-03-15
**Valid until:** 2026-04-14 (stable — no external dependencies, all findings from local code)
