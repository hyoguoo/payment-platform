# Phase 8: Refactor Confirm Adapters — Research

**Researched:** 2026-03-16
**Domain:** Hexagonal Architecture refactoring — infrastructure adapter → application service, port abstraction
**Confidence:** HIGH

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**Phase Boundary**
infrastructure 레이어에 있는 KafkaConfirmAdapter/OutboxConfirmAdapter/SyncConfirmAdapter를 제거하고,
오케스트레이션 로직을 application 레이어의 전략별 서비스 클래스로 이동한다.
KafkaTemplate 직접 의존은 PaymentConfirmPublisherPort로 추상화하고, KafkaConfirmPublisher 구현체를 infrastructure/kafka/에 배치한다.

기존 KafkaConfirmListener, OutboxWorker, PaymentTransactionCoordinator는 이 Phase의 범위가 아니다.

**Kafka 포트 추상화**
- 포트 인터페이스: `PaymentConfirmPublisherPort` — `application/port/out/` 패키지에 위치
- 메서드 시그니처: `void publish(String orderId)` — 발행 실패 시 KafkaException 전파
- 구현체: `KafkaConfirmPublisher` — `infrastructure/kafka/` 패키지에 위치 (KafkaConfirmListener와 동일 패키지)

**오케스트레이션 이동 방식**
- 전략별 서비스 클래스 신규 생성: `KafkaAsyncConfirmService`, `OutboxAsyncConfirmService`
- 각 서비스가 `PaymentConfirmService` 인터페이스를 직접 implements
- `@ConditionalOnProperty`를 서비스 클래스에 직접 적용 — `KafkaAsyncConfirmService(havingValue="kafka")`, `OutboxAsyncConfirmService(havingValue="outbox")`
- 기존 infrastructure adapter 클래스 3종 모두 제거: `KafkaConfirmAdapter`, `OutboxConfirmAdapter`, `SyncConfirmAdapter`

**SyncConfirmAdapter 정리**
- `SyncConfirmAdapter` 제거
- `PaymentConfirmServiceImpl`이 `PaymentConfirmService`를 직접 implements
- `@ConditionalOnProperty(name="spring.payment.async-strategy", havingValue="sync", matchIfMissing=true)` 추가
- 반환 타입을 `PaymentConfirmAsyncResult`로 변경 (ResponseType.SYNC_200)

### Claude's Discretion
- 새 서비스 클래스의 정확한 패키지 경로 (application/ 하위 구조)
- `PaymentConfirmPublisherPort` 관련 테스트에서 Fake 구현 방식
- KafkaAsyncConfirmService 내 `executePayment` 호출 순서 세부 구현

### Deferred Ideas (OUT OF SCOPE)
없음 — 논의가 Phase 8 범위 내에서 유지됨
</user_constraints>

---

## Summary

Phase 8는 순수한 헥사고날 아키텍처 정리 작업이다. 핵심 동기는 `KafkaConfirmAdapter`가 `PaymentConfirmService` 인터페이스(presentation/port)의 구현체임에도 `KafkaTemplate`을 직접 import하는 레이어 역전이 발생하고 있다는 것이다. infrastructure 레이어가 presentation 포트를 구현하면서 application 레이어의 use case들을 직접 orchestrate하는 구조 자체가 잘못된 배치다.

변경 범위는 세 가지다. 첫째, `KafkaConfirmAdapter`/`OutboxConfirmAdapter`의 오케스트레이션 로직을 `application/` 하위의 전략별 서비스 클래스(`KafkaAsyncConfirmService`, `OutboxAsyncConfirmService`)로 이동한다. 둘째, `KafkaTemplate` 직접 의존을 `PaymentConfirmPublisherPort`(application/port/out)로 추상화하고 `KafkaConfirmPublisher`(infrastructure/kafka)가 구현한다. 셋째, `SyncConfirmAdapter`를 제거하고 `PaymentConfirmServiceImpl`이 `PaymentConfirmService`를 직접 implements하도록 변경한다.

이 리팩터 후에는 infrastructure/adapter/ 패키지에 아무것도 남지 않아야 하며, 모든 `@ConditionalOnProperty` Bean 등록은 application 레이어 서비스 클래스에서 직접 담당한다.

**Primary recommendation:** 오케스트레이션 로직은 코드 복사가 아닌 이동이므로 기존 테스트를 새 클래스 위치에 맞게 재배치하고, `KafkaTemplate` 의존을 Fake 구현으로 대체하는 단일 포트 테스트를 추가한다.

---

## Standard Stack

### Core (기존 프로젝트 스택 — 변경 없음)

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Spring Boot | 3.3.3 | `@ConditionalOnProperty`, DI | 프로젝트 기반 프레임워크 |
| Spring Kafka | Spring Boot managed | `KafkaTemplate` → `KafkaConfirmPublisher` 구현에서 사용 | Phase 4에서 도입 |
| Lombok | Boot managed | `@RequiredArgsConstructor`, `@Slf4j` | 프로젝트 전역 컨벤션 |
| JUnit 5 + Mockito | Boot managed | 서비스 단위 테스트 | 프로젝트 전역 테스트 스택 |

### No New Dependencies

Phase 8은 새 라이브러리를 추가하지 않는다. 기존 `spring-kafka`와 `lombok`만으로 충분하다.

---

## Architecture Patterns

### 리팩터 후 디렉토리 구조

```
payment/
├── application/
│   ├── KafkaAsyncConfirmService.java       (신규 — kafka 전략)
│   ├── OutboxAsyncConfirmService.java      (신규 — outbox 전략)
│   ├── PaymentConfirmServiceImpl.java      (변경 — PaymentConfirmService implements 추가)
│   └── port/
│       └── out/                            (신규 디렉토리 또는 기존 port/ 하위)
│           └── PaymentConfirmPublisherPort.java  (신규)
├── infrastructure/
│   ├── adapter/                            (리팩터 완료 후 빈 패키지 → 제거)
│   └── kafka/
│       ├── KafkaConfirmListener.java       (기존 — 변경 없음)
│       └── KafkaConfirmPublisher.java      (신규)
└── presentation/
    └── port/
        └── PaymentConfirmService.java      (기존 인터페이스 — 변경 없음)
```

### Pattern 1: Port 추상화 (PaymentConfirmPublisherPort)

**What:** `KafkaTemplate<String, String>` 직접 의존을 포트 인터페이스 뒤로 숨긴다.

**When to use:** infrastructure 기술(Kafka)에 대한 의존이 application/domain 레이어에 누수되는 경우.

**기존 코드 (KafkaConfirmAdapter — 제거 대상):**
```java
// infrastructure/adapter/KafkaConfirmAdapter.java
private final KafkaTemplate<String, String> kafkaTemplate;
// ...
kafkaTemplate.send(TOPIC, command.getOrderId(), command.getOrderId());
```

**변경 후 (KafkaAsyncConfirmService — application 레이어):**
```java
// application/KafkaAsyncConfirmService.java
private final PaymentConfirmPublisherPort confirmPublisher;
// ...
confirmPublisher.publish(command.getOrderId());
```

**포트 인터페이스:**
```java
// application/port/out/PaymentConfirmPublisherPort.java
public interface PaymentConfirmPublisherPort {
    void publish(String orderId);  // 실패 시 KafkaException 전파
}
```

**구현체:**
```java
// infrastructure/kafka/KafkaConfirmPublisher.java
@Component
@RequiredArgsConstructor
public class KafkaConfirmPublisher implements PaymentConfirmPublisherPort {

    private static final String TOPIC = "payment-confirm";
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Override
    public void publish(String orderId) {
        kafkaTemplate.send(TOPIC, orderId, orderId);
    }
}
```

### Pattern 2: @ConditionalOnProperty를 application 서비스로 이동

**What:** 전략 선택 조건을 infrastructure adapter에서 application service로 옮긴다.

**기존 구조 (잘못된 위치):**
```java
// infrastructure/adapter/KafkaConfirmAdapter.java
@Service
@ConditionalOnProperty(name = "spring.payment.async-strategy", havingValue = "kafka")
public class KafkaConfirmAdapter implements PaymentConfirmService { ... }
```

**변경 후 구조 (올바른 위치):**
```java
// application/KafkaAsyncConfirmService.java
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "spring.payment.async-strategy", havingValue = "kafka")
public class KafkaAsyncConfirmService implements PaymentConfirmService { ... }
```

```java
// application/OutboxAsyncConfirmService.java
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "spring.payment.async-strategy", havingValue = "outbox")
public class OutboxAsyncConfirmService implements PaymentConfirmService { ... }
```

### Pattern 3: SyncConfirmAdapter 제거 — PaymentConfirmServiceImpl 직접 구현

**What:** `SyncConfirmAdapter`가 `PaymentConfirmServiceImpl`에 단순 위임하는 래퍼 역할만 했다. 래퍼를 제거하고 `PaymentConfirmServiceImpl` 자체가 `PaymentConfirmService`를 구현하도록 변경한다.

**변경 사항:**
1. `PaymentConfirmServiceImpl`에 `implements PaymentConfirmService` 추가
2. `@ConditionalOnProperty(name="spring.payment.async-strategy", havingValue="sync", matchIfMissing=true)` 추가
3. 반환 타입 시그니처 변경: `confirm()` 메서드가 `PaymentConfirmAsyncResult`를 반환 (ResponseType.SYNC_200)
4. `SyncConfirmAdapter` 클래스 삭제

**주의사항:** 기존 `PaymentConfirmServiceImpl.confirm()`은 `PaymentConfirmResult`를 반환하는 내부 메서드다. `PaymentConfirmService` 인터페이스 구현 메서드는 `PaymentConfirmAsyncResult`를 반환해야 하므로, 내부 로직을 private 메서드로 추출하거나 반환 타입을 조정해야 한다. 가장 깔끔한 방법은:
- 기존 `confirm(PaymentConfirmCommand)` 로직을 `private PaymentConfirmResult doConfirm(PaymentConfirmCommand)` 등으로 리네임
- public `confirm()` 메서드가 `PaymentConfirmAsyncResult`를 반환하도록 래핑

### 오케스트레이션 순서 (KafkaAsyncConfirmService)

기존 `KafkaConfirmAdapter`의 순서를 그대로 유지한다:

```
1. paymentLoadUseCase.getPaymentEventByOrderId(orderId)
2. paymentCommandUseCase.executePayment(paymentEvent, paymentKey)   // READY → IN_PROGRESS
3. transactionCoordinator.executeStockDecreaseOnly(orderId, orders) // 트랜잭션 커밋
4. confirmPublisher.publish(orderId)                                  // KafkaException 가능
5. return PaymentConfirmAsyncResult(ASYNC_202)
```

### 오케스트레이션 순서 (OutboxAsyncConfirmService)

기존 `OutboxConfirmAdapter`의 순서를 그대로 유지한다:

```
1. paymentLoadUseCase.getPaymentEventByOrderId(orderId)
2. paymentCommandUseCase.executePayment(paymentEvent, paymentKey)    // READY → IN_PROGRESS
3. transactionCoordinator.executeStockDecreaseWithOutboxCreation(orderId, orders)
4. return PaymentConfirmAsyncResult(ASYNC_202)
```

### Anti-Patterns to Avoid

- **오케스트레이션 로직 변경:** 이 Phase는 위치 이동이지 로직 변경이 아니다. 기존 adapter의 메서드 호출 순서, 파라미터를 그대로 유지한다.
- **TOPIC 상수를 KafkaAsyncConfirmService에 두기:** TOPIC 문자열은 infrastructure 관심사다. `KafkaConfirmPublisher`에서만 알면 된다.
- **PaymentConfirmPublisherPort를 `port/` 바로 아래 배치:** 기존 포트들(`PaymentEventRepository`, `PaymentGatewayPort` 등)은 모두 `application/port/` 바로 아래에 있다. 일관성을 위해 동일 위치 또는 `out/` 서브패키지 중 하나를 선택하되 기존 패턴을 따른다.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Kafka 발행 | 커스텀 발행 로직 | 기존 `KafkaTemplate.send()` + `KafkaConfirmPublisher` 래핑 | Phase 4에서 검증된 패턴 재사용 |
| 조건부 Bean 등록 | XML/Java Config 수동 Bean | `@ConditionalOnProperty` | Phase 1-4에서 확립된 패턴 |
| 테스트용 Kafka 대역 | `KafkaTemplate` 직접 Mock | `FakePaymentConfirmPublisher` Fake 구현 | Fake > Mock 원칙 (TESTING.md) |

**Key insight:** 이 Phase는 새 기능 추가가 아닌 구조 정리다. 새로 구현해야 할 복잡한 로직이 없으므로 모든 "새 코드"는 기존 adapter 코드의 이동이거나 단순 위임이다.

---

## Common Pitfalls

### Pitfall 1: PaymentConfirmServiceImpl의 기존 테스트 깨짐

**What goes wrong:** `PaymentConfirmServiceImpl`에 `implements PaymentConfirmService`를 추가하면서 메서드 시그니처가 변경되면, `PaymentConfirmServiceImplTest`와 `SyncConfirmAdapterTest`가 함께 실패한다.

**Why it happens:** 기존 `confirm()`은 `PaymentConfirmResult`를 반환하는데, `PaymentConfirmService` 인터페이스는 `PaymentConfirmAsyncResult`를 요구한다. 직접 반환 타입 변경 시 기존 테스트가 모두 깨진다.

**How to avoid:** 내부 로직을 private 메서드(`doConfirm` 또는 동일한 이름의 private 오버로드)로 분리하고, public `confirm(PaymentConfirmCommand)` 메서드를 새로 정의하여 `PaymentConfirmAsyncResult`를 반환하게 한다. 기존 테스트는 public 메서드만 테스트하므로 시그니처 변경이 필수다.

**Warning signs:** 컴파일 오류 — `PaymentConfirmServiceImplTest`에서 `PaymentConfirmResult` 타입 참조 실패.

### Pitfall 2: infrastructure/adapter/ 패키지 제거 시 테스트 참조 잔존

**What goes wrong:** `KafkaConfirmAdapterTest`, `OutboxConfirmAdapterTest`, `SyncConfirmAdapterTest`가 기존 adapter 클래스를 참조하고 있어서, adapter 클래스 삭제 후 컴파일 에러가 발생한다.

**Why it happens:** 테스트 파일이 production code보다 먼저 삭제되거나 이동이 누락된 경우.

**How to avoid:** production 클래스 이동/삭제와 테스트 파일 이동/재작성을 같은 커밋에서 처리한다. adapter 테스트의 검증 내용을 새 서비스 테스트로 흡수시킨다.

**Warning signs:** `./gradlew test` 시 `package com.hyoguoo.paymentplatform.payment.infrastructure.adapter does not exist` 컴파일 에러.

### Pitfall 3: @ConditionalOnProperty 어노테이션 중복 등록

**What goes wrong:** 리팩터 과정에서 기존 adapter 클래스를 삭제하지 않고 신규 서비스 클래스를 추가하면, 동일 `havingValue`에 두 개의 Bean이 등록되어 `NoUniqueBeanDefinitionException` 발생.

**Why it happens:** 점진적 이동 중 중간 상태에서 두 파일이 동시에 존재.

**How to avoid:** 신규 서비스 추가와 기존 adapter 제거를 동일 작업 단위에서 처리한다. 중간 상태를 커밋하지 않는다.

**Warning signs:** Spring 컨텍스트 로딩 실패 — `No qualifying bean of type 'PaymentConfirmService' available: expected single matching bean but found 2`.

### Pitfall 4: application/port/ vs application/port/out/ 패키지 불일치

**What goes wrong:** 기존 포트들(`PaymentEventRepository`, `PaymentGatewayPort` 등)은 `application/port/` 바로 아래에 있는데, `PaymentConfirmPublisherPort`를 `application/port/out/`에 배치하면 일관성이 깨진다.

**Why it happens:** Hexagonal Architecture의 "driven port"를 out/ 서브패키지로 분리하는 관례를 적용하려 할 때.

**How to avoid:** CONTEXT.md에서 `application/port/out/`으로 명시했으므로 해당 경로를 사용한다. 단, 기존 포트들과의 불일치는 Claude's Discretion 범위이므로 일관성 있는 선택을 한다.

---

## Code Examples

### KafkaAsyncConfirmService (이동 후)

```java
// Source: 기존 KafkaConfirmAdapter 로직 이동
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "spring.payment.async-strategy",
        havingValue = "kafka"
)
public class KafkaAsyncConfirmService implements PaymentConfirmService {

    private final PaymentTransactionCoordinator transactionCoordinator;
    private final PaymentLoadUseCase paymentLoadUseCase;
    private final PaymentCommandUseCase paymentCommandUseCase;
    private final PaymentConfirmPublisherPort confirmPublisher;

    @Override
    public PaymentConfirmAsyncResult confirm(PaymentConfirmCommand command)
            throws PaymentOrderedProductStockException {
        PaymentEvent paymentEvent =
                paymentLoadUseCase.getPaymentEventByOrderId(command.getOrderId());

        paymentCommandUseCase.executePayment(paymentEvent, command.getPaymentKey());

        transactionCoordinator.executeStockDecreaseOnly(
                command.getOrderId(), paymentEvent.getPaymentOrderList()
        );

        confirmPublisher.publish(command.getOrderId());

        return PaymentConfirmAsyncResult.builder()
                .responseType(ResponseType.ASYNC_202)
                .orderId(command.getOrderId())
                .amount(command.getAmount())
                .build();
    }
}
```

### KafkaConfirmPublisher (신규 infrastructure 구현체)

```java
// Source: KafkaConfirmAdapter의 kafkaTemplate.send() 호출 추출
@Component
@RequiredArgsConstructor
public class KafkaConfirmPublisher implements PaymentConfirmPublisherPort {

    private static final String TOPIC = "payment-confirm";
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Override
    public void publish(String orderId) {
        kafkaTemplate.send(TOPIC, orderId, orderId);
    }
}
```

### FakePaymentConfirmPublisher (테스트용 Fake)

```java
// 테스트에서 KafkaTemplate 없이 PaymentConfirmPublisherPort 검증
public class FakePaymentConfirmPublisher implements PaymentConfirmPublisherPort {

    private final List<String> publishedOrderIds = new ArrayList<>();

    @Override
    public void publish(String orderId) {
        publishedOrderIds.add(orderId);
    }

    public List<String> getPublishedOrderIds() {
        return Collections.unmodifiableList(publishedOrderIds);
    }
}
```

### PaymentConfirmServiceImpl 변경 핵심 (반환 타입 조정)

```java
// @ConditionalOnProperty 추가 + PaymentConfirmService implements + confirm() 시그니처 변경
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "spring.payment.async-strategy",
        havingValue = "sync",
        matchIfMissing = true
)
public class PaymentConfirmServiceImpl implements PaymentConfirmService {

    // 기존 필드 유지

    @Override
    public PaymentConfirmAsyncResult confirm(PaymentConfirmCommand command) {
        // 기존 로직을 private doConfirm()으로 분리하고 결과를 래핑
        PaymentConfirmResult result = doConfirm(command);
        return PaymentConfirmAsyncResult.builder()
                .responseType(ResponseType.SYNC_200)
                .orderId(result.getOrderId())
                .amount(result.getAmount())
                .build();
    }

    private PaymentConfirmResult doConfirm(PaymentConfirmCommand command) {
        // 기존 confirm() 로직 전체 이동
        // ...
    }
}
```

---

## Existing Test Impact Analysis

### 삭제 대상 테스트

| Test File | 이유 | 처리 방법 |
|-----------|------|-----------|
| `infrastructure/adapter/KafkaConfirmAdapterTest.java` | 클래스 삭제 따라 제거 | 내용을 `KafkaAsyncConfirmServiceTest`로 이동 |
| `infrastructure/adapter/OutboxConfirmAdapterTest.java` | 클래스 삭제 따라 제거 | 내용을 `OutboxAsyncConfirmServiceTest`로 이동 |
| `infrastructure/adapter/SyncConfirmAdapterTest.java` | 클래스 삭제 따라 제거 | `PaymentConfirmServiceImplTest`에 `@ConditionalOnProperty` 어노테이션 검증 추가 |

### 변경 대상 테스트

| Test File | 변경 사항 |
|-----------|-----------|
| `application/PaymentConfirmServiceImplTest.java` | `confirm()` 반환 타입이 `PaymentConfirmAsyncResult`로 변경되므로 assert 타입 변경. `@ConditionalOnProperty` 어노테이션 검증 테스트 추가. |

### 신규 테스트

| Test File | 검증 내용 |
|-----------|-----------|
| `application/KafkaAsyncConfirmServiceTest.java` | 오케스트레이션 순서, ASYNC_202 반환, `@ConditionalOnProperty` 어노테이션 |
| `application/OutboxAsyncConfirmServiceTest.java` | 오케스트레이션 순서, ASYNC_202 반환, `@ConditionalOnProperty` 어노테이션 |
| `infrastructure/kafka/KafkaConfirmPublisherTest.java` | `publish()` 호출 시 `KafkaTemplate.send()`에 올바른 topic/key/value 전달 |

---

## Validation Architecture

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit 5 (Spring Boot 3.3.3 managed) |
| Config file | `build.gradle` (jacoco 0.8.11) |
| Quick run command | `./gradlew test --tests "*.payment.application.*"` |
| Full suite command | `./gradlew test` |

### Phase Requirements → Test Map

Phase 8은 v1 Requirements의 새 요구사항이 아닌 내부 품질 개선이다. 기존 요구사항에 대한 회귀 방지가 목적이다.

| Behavior | Test Type | Automated Command | File Exists? |
|----------|-----------|-------------------|-------------|
| KafkaAsyncConfirmService가 ASYNC_202 반환 | unit | `./gradlew test --tests "*.KafkaAsyncConfirmServiceTest"` | ❌ Wave 0 |
| OutboxAsyncConfirmService가 ASYNC_202 반환 | unit | `./gradlew test --tests "*.OutboxAsyncConfirmServiceTest"` | ❌ Wave 0 |
| PaymentConfirmServiceImpl이 SYNC_200 반환 | unit | `./gradlew test --tests "*.PaymentConfirmServiceImplTest"` | ✅ (수정 필요) |
| KafkaConfirmPublisher가 올바른 토픽에 발행 | unit | `./gradlew test --tests "*.KafkaConfirmPublisherTest"` | ❌ Wave 0 |
| @ConditionalOnProperty 어노테이션 검증 (kafka) | unit | `./gradlew test --tests "*.KafkaAsyncConfirmServiceTest"` | ❌ Wave 0 |
| @ConditionalOnProperty 어노테이션 검증 (outbox) | unit | `./gradlew test --tests "*.OutboxAsyncConfirmServiceTest"` | ❌ Wave 0 |
| @ConditionalOnProperty 어노테이션 검증 (sync) | unit | `./gradlew test --tests "*.PaymentConfirmServiceImplTest"` | ✅ (추가 필요) |
| Kafka 통합 회귀 (KafkaConfirmListenerIntegrationTest) | integration | `./gradlew test --tests "*.KafkaConfirmListenerIntegrationTest"` | ✅ |

### Sampling Rate

- **Per task commit:** `./gradlew test --tests "*.payment.application.*" --tests "*.payment.infrastructure.kafka.*"`
- **Per wave merge:** `./gradlew test`
- **Phase gate:** Full suite green before `/gsd:verify-work`

### Wave 0 Gaps

- [ ] `src/test/.../payment/application/KafkaAsyncConfirmServiceTest.java` — 신규 서비스 단위 테스트
- [ ] `src/test/.../payment/application/OutboxAsyncConfirmServiceTest.java` — 신규 서비스 단위 테스트
- [ ] `src/test/.../payment/infrastructure/kafka/KafkaConfirmPublisherTest.java` — 포트 구현체 단위 테스트

---

## Sources

### Primary (HIGH confidence)

- 직접 소스 코드 분석:
  - `payment/infrastructure/adapter/KafkaConfirmAdapter.java` — 현재 오케스트레이션 로직, KafkaTemplate 의존
  - `payment/infrastructure/adapter/OutboxConfirmAdapter.java` — 현재 오케스트레이션 로직
  - `payment/infrastructure/adapter/SyncConfirmAdapter.java` — 현재 위임 패턴
  - `payment/application/PaymentConfirmServiceImpl.java` — 변경 대상 클래스 현재 구조
  - `payment/application/usecase/PaymentTransactionCoordinator.java` — 재사용 메서드 목록
  - `payment/presentation/port/PaymentConfirmService.java` — 인터페이스 시그니처
  - `payment/application/port/*.java` — 기존 포트 패턴
- 테스트 파일 분석:
  - `infrastructure/adapter/KafkaConfirmAdapterTest.java` — 검증 패턴, @ConditionalOnProperty 검증 방식
  - `infrastructure/adapter/OutboxConfirmAdapterTest.java` — 검증 패턴
  - `application/PaymentConfirmServiceImplTest.java` — 서비스 테스트 패턴

### Secondary (MEDIUM confidence)

- `.planning/codebase/ARCHITECTURE.md` — 헥사고날 아키텍처 레이어 규칙
- `.planning/codebase/TESTING.md` — Fake vs Mock 전략, 테스트 패턴
- `.planning/codebase/CONVENTIONS.md` — Lombok, 로깅 컨벤션

---

## Metadata

**Confidence breakdown:**

- Standard Stack: HIGH — 신규 의존성 없음, 기존 스택만 사용
- Architecture: HIGH — 소스 코드 직접 분석, 이동 로직이 명확
- Pitfalls: HIGH — 기존 테스트 파일과 클래스 구조를 모두 확인

**Research date:** 2026-03-16
**Valid until:** Phase 8 구현 완료까지 (30일)
