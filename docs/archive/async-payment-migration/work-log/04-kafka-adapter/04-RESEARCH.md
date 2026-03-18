# Phase 4: Kafka Adapter - Research

**Researched:** 2026-03-15
**Domain:** Spring Kafka, @RetryableTopic, Testcontainers Kafka, Docker Compose KRaft
**Confidence:** HIGH

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**발행 원자성**
- 재고 감소(DB)와 Kafka 발행을 **직접 발행 + 실패 시 롤백** 방식으로 처리
- `KafkaConfirmAdapter.confirm()` 내에서 재고 감소 트랜잭션과 Kafka 발행을 순서대로 실행
- Kafka 발행 실패(`KafkaException`) 시 예외를 전파하여 DB 트랜잭션이 롤백되도록 처리
- 서버 다운 엣지 케이스(재고 커밋 후 다운)는 포트폴리오 범위에서 허용 가능한 단순화로 결정

**재시도 & DLT**
- `@RetryableTopic` (non-blocking) 방식 — 실패 메시지는 retry 토픽으로 이동, 컨슈머가 다른 메시지 계속 처리 가능
- 최대 재시도 횟수: **5회** (Outbox `RETRYABLE_LIMIT=5`와 통일 — k6 비교 시 공정한 조건)
- DLT(`payment-confirm-dlq`) 도달 시: 로그 기록 + `executePaymentFailureCompensation()` 호출
  - 재고 복원 + PaymentEvent FAILED 전환 — Outbox FAILED 처리와 동일한 보상 트랜잭션 재사용

**메시지 포맷**
- 발행 내용: **orderId만** 발행 (Outbox 패턴과 동일하게 페이로드 최소화)
- 컨슈머가 orderId로 PaymentEvent를 DB에서 재조회 후 처리
- 직렬화: JSON + StringSerializer/JsonDeserializer (kafbat/kafka-ui에서 메시지 내용 직접 확인 가능)

**멱등성 전략**
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

### Deferred Ideas (OUT OF SCOPE)

없음 — 논의가 Phase 4 범위 내에서 유지됨

</user_constraints>

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| KAFKA-01 | Docker Compose에 Kafka (KRaft, 3.9), kafbat/kafka-ui를 추가한다 | Docker Compose KRaft 패턴 + kafbat/kafka-ui 환경변수 조사 완료 |
| KAFKA-02 | `KafkaConfirmAdapter`가 confirm 요청을 `payment-confirm-requests` 토픽에 발행하고 202를 반환한다 | `KafkaTemplate.send()` + `@ConditionalOnProperty(havingValue="kafka")` 패턴 |
| KAFKA-03 | 재고 감소는 발행 전 동기적으로 완료된다 | `PaymentTransactionCoordinator` 재사용 패턴 조사 완료 |
| KAFKA-04 | `KafkaConfirmListener`가 토픽을 컨슘해 Toss API 호출 및 상태 업데이트를 처리한다 | `@KafkaListener` + `@RetryableTopic` 패턴 조사 완료 |
| KAFKA-05 | `existsByOrderId` 가드로 중복 컨슘에 대한 멱등성을 보장한다 | CONTEXT.md에서 Toss 멱등키 위임으로 결정 — 별도 가드 불필요 |
| KAFKA-06 | 최대 재시도 후 처리 실패한 메시지는 `payment-confirm-dlq` 토픽으로 라우팅된다 | `@RetryableTopic(dltTopicSuffix)` + `@DltHandler` 패턴 조사 완료 |
| KAFKA-07 | Testcontainers Kafka로 통합 테스트를 작성한다 | `org.testcontainers.kafka.KafkaContainer` + `@DynamicPropertySource` 패턴 조사 완료 |

</phase_requirements>

---

## Summary

Phase 4는 Spring Kafka(`spring-boot-starter` 포함 시 `spring-kafka` 3.2.x 자동 관리)를 사용해 Kafka Adapter를 구현한다. 핵심 구성요소는 세 가지다: 재고 감소 + 발행을 순서 보장으로 실행하는 `KafkaConfirmAdapter`, `@RetryableTopic`(non-blocking) + `@DltHandler`로 재시도·DLT를 처리하는 `KafkaConfirmListener`, 그리고 단일 노드 KRaft 모드 Docker Compose 구성이다.

Spring Boot 3.3.3이 관리하는 `spring-kafka` 3.2.x는 `@RetryableTopic`의 `sameIntervalTopicReuseStrategy` 기본값이 `SINGLE_TOPIC`(3.2+ 변경)이며, 이 설정에서 같은 backoff 간격의 재시도는 단일 토픽을 재사용한다. DLT 토픽명은 `@RetryableTopic(dltTopicSuffix="-dlq")`로 지정하면 `payment-confirm-requests-dlq`가 생성되므로 요구사항 KAFKA-06의 `payment-confirm-dlq`와 일치시키려면 토픽명 자체를 `payment-confirm-dlq`로 선언하거나 dltTopicSuffix를 사용한다. CONTEXT.md 결정에 따라 토픽명 `payment-confirm-requests`와 DLT명 `payment-confirm-dlq`를 dltTopicSuffix="-dlq"를 통해 일치시키면 된다.

Testcontainers 1.19.8(현재 프로젝트 버전)에서는 `org.testcontainers.containers.KafkaContainer`가 아직 사용 가능하다(1.20.x에서 새 API로 교체). `confluentinc/cp-kafka:7.4.0` 이미지 + `.withKraft()`로 KRaft 모드를 활성화하는 패턴이 표준이며, 프로젝트의 `BaseIntegrationTest` 패턴(정적 컨테이너 + `@DynamicPropertySource`)을 그대로 확장한다.

**Primary recommendation:** `OutboxConfirmAdapter` 구조를 `KafkaConfirmAdapter`로 1:1 복제하되 `executeStockDecreaseWithOutboxCreation` 대신 `executeStockDecreaseOnly`(신규) 또는 `OrderedProductUseCase.decreaseStockForOrders()` 직접 호출로 교체하고, `KafkaTemplate.send()` 완료 후 202를 반환한다.

---

## Standard Stack

### Core

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `spring-kafka` | 3.2.x (Boot 3.3.3 관리) | Kafka Producer/Consumer, @RetryableTopic, DLT | Spring Boot 자동 설정, `@RetryableTopic` 내장 |
| `org.testcontainers:kafka` | 1.19.8 (기존) | 통합 테스트용 Kafka 컨테이너 | 프로젝트에 이미 Testcontainers BOM 선언됨 |
| `confluentinc/cp-kafka:7.4.0` | 7.4.x | KRaft 모드 단일 노드 Kafka | `.withKraft()` API 지원 안정 버전 |
| `ghcr.io/kafbat/kafka-ui:latest` | latest | Kafka 토픽/메시지 시각화 | CONTEXT.md 결정 |

### Supporting

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `org.springframework.boot:spring-boot-testcontainers` | Boot 관리 | `@ServiceConnection` 패턴 지원 | Kafka 통합 테스트 Spring context 주입 |
| `awaitility` (Boot 관리) | Boot 관리 | 비동기 처리 완료 대기 | Kafka 컨슈머가 메시지를 처리할 때까지 polling |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| `confluentinc/cp-kafka` (Testcontainers) | `apache/kafka-native` (1.20.x 신규 API) | Testcontainers 1.19.8에서는 `apache/kafka-native` 지원이 `org.testcontainers.kafka` 모듈에 있어 클래스명이 다름; 1.19.8에서는 기존 `containers.KafkaContainer` + Confluent 이미지가 안전 |
| `@RetryableTopic` (non-blocking) | `DefaultErrorHandler` + BlockingRetryTopic | Non-blocking이 컨슈머 지연 없이 다른 메시지 계속 처리 가능 — CONTEXT.md 결정 |

**Installation:**
```bash
# build.gradle에 추가
implementation 'org.springframework.boot:spring-boot-starter'  # kafka auto-config 포함
# spring-kafka는 spring-boot-starter에서 자동 관리되지 않으므로:
implementation 'org.springframework.kafka:spring-kafka'
testImplementation "org.testcontainers:kafka:${testcontainersVersion}"
```

> 참고: `spring-kafka`는 `spring-boot-starter-web` 등에 포함되지 않는다. `spring-kafka` 의존성 명시 추가 필요.

---

## Architecture Patterns

### Recommended Project Structure

```
payment/
├── infrastructure/
│   └── adapter/
│       ├── SyncConfirmAdapter.java          # 기존
│       ├── OutboxConfirmAdapter.java         # 기존
│       └── KafkaConfirmAdapter.java          # 신규 (KAFKA-02, KAFKA-03)
├── listener/
│   └── KafkaConfirmListener.java             # 신규 (KAFKA-04, KAFKA-06)
│       └── (기존 PaymentHistoryEventListener와 동일 패키지)
```

### Pattern 1: KafkaConfirmAdapter — 재고 감소 + Kafka 발행

**What:** `PaymentConfirmService` 구현체. 재고 감소(트랜잭션 내)와 `KafkaTemplate.send()` 호출 순서 보장. Kafka 발행 실패 시 예외 전파로 DB 롤백.

**When to use:** `spring.payment.async-strategy=kafka` 시 Bean 활성화

```java
// Source: OutboxConfirmAdapter 패턴 적용 + KafkaTemplate 추가
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "spring.payment.async-strategy",
        havingValue = "kafka"
)
public class KafkaConfirmAdapter implements PaymentConfirmService {

    private static final String TOPIC = "payment-confirm-requests";

    private final PaymentTransactionCoordinator transactionCoordinator;
    private final PaymentLoadUseCase paymentLoadUseCase;
    private final PaymentCommandUseCase paymentCommandUseCase;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Override
    public PaymentConfirmAsyncResult confirm(PaymentConfirmCommand command)
            throws PaymentOrderedProductStockException {
        PaymentEvent paymentEvent =
                paymentLoadUseCase.getPaymentEventByOrderId(command.getOrderId());

        // PaymentEvent READY → IN_PROGRESS + paymentKey 기록 (컨슈머가 조회 시 필요)
        paymentCommandUseCase.executePayment(paymentEvent, command.getPaymentKey());

        // 재고 감소 (트랜잭션 내) — Outbox 레코드 생성 없이 재고 감소만
        transactionCoordinator.executeStockDecreaseOnly(
                command.getOrderId(), paymentEvent.getPaymentOrderList()
        );

        // Kafka 발행 — 실패 시 KafkaException 전파 → DB 트랜잭션 롤백
        kafkaTemplate.send(TOPIC, command.getOrderId(), command.getOrderId());

        return PaymentConfirmAsyncResult.builder()
                .responseType(ResponseType.ASYNC_202)
                .orderId(command.getOrderId())
                .amount(command.getAmount())
                .build();
    }
}
```

> **주의:** `executeStockDecreaseOnly()`는 `PaymentTransactionCoordinator`에 신규 메서드로 추가 필요. Outbox 생성 없이 재고 감소만 수행하는 `@Transactional` 메서드.

### Pattern 2: KafkaConfirmListener — @RetryableTopic + @DltHandler

**What:** `@KafkaListener`에 `@RetryableTopic`을 결합해 non-blocking retry 및 DLT 라우팅을 선언적으로 구성. 컨슈머 로직은 `OutboxWorker.processRecord()`와 동일 흐름.

**When to use:** `payment-confirm-requests` 토픽 메시지 수신 시 자동 실행

```java
// Source: Spring Kafka docs - @RetryableTopic
@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaConfirmListener {

    private final PaymentLoadUseCase paymentLoadUseCase;
    private final PaymentCommandUseCase paymentCommandUseCase;
    private final PaymentTransactionCoordinator transactionCoordinator;

    @RetryableTopic(
            attempts = "6",   // 1회 최초 시도 + 5회 재시도 = RETRYABLE_LIMIT=5와 동일
            backoff = @Backoff(delay = 1000, multiplier = 2.0, maxDelay = 30000),
            dltTopicSuffix = "-dlq",
            include = {PaymentTossRetryableException.class},
            exclude = {PaymentTossNonRetryableException.class},
            autoCreateTopics = "true"
    )
    @KafkaListener(
            topics = "payment-confirm-requests",
            groupId = "${spring.kafka.consumer.group-id:payment-confirm-group}"
    )
    public void consume(String orderId) {
        PaymentEvent paymentEvent =
                paymentLoadUseCase.getPaymentEventByOrderId(orderId);

        PaymentConfirmCommand command = PaymentConfirmCommand.builder()
                .userId(paymentEvent.getBuyerId())
                .orderId(orderId)
                .paymentKey(paymentEvent.getPaymentKey())
                .amount(paymentEvent.getTotalAmount())
                .build();

        PaymentGatewayInfo gatewayInfo =
                paymentCommandUseCase.confirmPaymentWithGateway(command);

        transactionCoordinator.executePaymentSuccessCompletion(
                orderId, paymentEvent,
                gatewayInfo.getPaymentDetails().getApprovedAt()
        );
    }

    @DltHandler
    public void handleDlt(String orderId) {
        LogFmt.error(log, LogDomain.PAYMENT, EventType.EXCEPTION,
                () -> "DLT reached for orderId=" + orderId);
        PaymentEvent paymentEvent =
                paymentLoadUseCase.getPaymentEventByOrderId(orderId);
        transactionCoordinator.executePaymentFailureCompensation(
                orderId, paymentEvent, paymentEvent.getPaymentOrderList(),
                "kafka-dlt-exhausted"
        );
    }
}
```

> **중요 결정:** `@RetryableTopic`의 `attempts` 기본값은 `"3"` (1회 시도 + 2회 재시도). RETRYABLE_LIMIT=5에 맞추려면 `attempts = "6"` (1+5)으로 설정해야 한다.

> **토픽명 vs DLT명:** 기본 DLT 토픽명 = `{topicName}{dltTopicSuffix}` = `payment-confirm-requests-dlq`. KAFKA-06 요구사항은 `payment-confirm-dlq`. 두 가지 해결 방법:
> 1. `dltTopicSuffix`를 비우고 `dltTopicSuffix = ""`로 두면 적용 불가
> 2. **권장**: `@KafkaListener(topics = "payment-confirm")` + `dltTopicSuffix = "-dlq"` → DLT = `payment-confirm-dlq` (토픽 기본명 변경)
> 3. **대안**: 토픽명을 그대로 유지하고 DLT 토픽명을 `payment-confirm-requests-dlq`로 수정해 요구사항 명세 자체를 갱신

### Pattern 3: PaymentTransactionCoordinator 신규 메서드

**What:** Kafka 어댑터가 Outbox 생성 없이 재고 감소만 수행하는 `executeStockDecreaseOnly()` 메서드 추가

```java
// PaymentTransactionCoordinator에 추가
@Transactional(rollbackFor = PaymentOrderedProductStockException.class)
public void executeStockDecreaseOnly(
        String orderId,
        List<PaymentOrder> paymentOrderList
) throws PaymentOrderedProductStockException {
    orderedProductUseCase.decreaseStockForOrders(paymentOrderList);
    // Kafka 어댑터: Outbox 레코드 생성 없이 재고 감소만
}
```

### Pattern 4: Testcontainers Kafka 통합 테스트

**What:** 기존 `BaseIntegrationTest`(MySQL 컨테이너) 확장. `KafkaContainer` 정적 필드 추가 + `@DynamicPropertySource`로 bootstrap-servers 오버라이드.

```java
// Source: Testcontainers Java Kafka module docs
// KafkaConfirmListenerIntegrationTest 또는 BaseKafkaIntegrationTest
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
public abstract class BaseKafkaIntegrationTest {

    @Container
    protected static final MySQLContainer<?> MYSQL_CONTAINER =
            new MySQLContainer<>("mysql:8.0")
                    .withDatabaseName("payment-test")
                    .withUsername("test")
                    .withPassword("test");

    @Container
    protected static final KafkaContainer KAFKA_CONTAINER =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.4.0"))
                    .withKraft();

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL_CONTAINER::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL_CONTAINER::getUsername);
        registry.add("spring.datasource.password", MYSQL_CONTAINER::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.kafka.bootstrap-servers", KAFKA_CONTAINER::getBootstrapServers);
        registry.add("spring.payment.async-strategy", () -> "kafka");
    }
}
```

> **주의:** `org.testcontainers.containers.KafkaContainer` (Testcontainers 1.19.8). 1.20.x에서는 `org.testcontainers.kafka.KafkaContainer`로 패키지가 이동하나 1.19.8에서는 기존 패키지 사용.

### Anti-Patterns to Avoid

- **@RetryableTopic과 @Transactional 동시 사용:** `@KafkaListener` 메서드에 `@Transactional`을 추가하면 retry 동작과 충돌 가능. 컨슈머 메서드 자체에는 `@Transactional` 금지 — 대신 내부 호출 메서드(`transactionCoordinator`)가 트랜잭션을 관리.
- **KafkaTemplate.send() 트랜잭션 내 포함:** `executeStockDecreaseOnly()` 내부에서 `kafkaTemplate.send()`를 호출하면 Kafka 발행이 DB 트랜잭션과 2PC 없이 결합되어 원자성 보장 불가. Kafka 발행은 트랜잭션 커밋 **이후** 별도 호출해야 함.
- **attempts 기본값 사용:** `@RetryableTopic` 기본 attempts는 3(= 1+2회 재시도). RETRYABLE_LIMIT=5와 공정한 비교를 위해 반드시 `attempts = "6"` 명시.
- **`@KafkaListener` 없이 `@RetryableTopic` 단독 사용:** `@RetryableTopic`은 반드시 `@KafkaListener`와 함께 선언해야 한다.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Non-blocking retry 로직 | 직접 retry 카운터 + 조건문 | `@RetryableTopic` | retry/DLT 토픽 자동 생성, backoff 설정, DLT 라우팅 내장 |
| DLT 토픽 라우팅 | 예외 타입별 수동 분기 + 토픽 발행 | `@DltHandler` + `@RetryableTopic(exclude=...)` | Spring Kafka가 exhausted 메시지를 DLT로 자동 라우팅 |
| Testcontainers Kafka 설정 | EmbeddedKafka 수동 구성 | `KafkaContainer.withKraft()` | 실제 Kafka 브로커 동작 재현, KRaft 모드 지원 |
| Kafka producer 설정 | `KafkaProducer` 직접 생성 | `KafkaTemplate<String, String>` | Spring auto-config가 프로듀서 팩토리 자동 생성 |

**Key insight:** `@RetryableTopic`은 retry 토픽 생성, 메시지 라우팅, backoff 지연, exhausted 후 DLT 전달까지 전부 처리한다. 직접 구현 시 race condition, offset 관리, 컨슈머 블로킹 등 복잡한 문제를 직접 해결해야 한다.

---

## Common Pitfalls

### Pitfall 1: @RetryableTopic attempts 의미 혼동

**What goes wrong:** `attempts = "5"`가 "5회 재시도"라고 오해해 설정. 실제로는 "총 시도 횟수"이므로 5를 설정하면 최초 1회 + 재시도 4회 = 4회만 재시도.
**Why it happens:** Spring Kafka 문서에서 attempts는 "total attempts" 의미.
**How to avoid:** RETRYABLE_LIMIT=5(재시도 횟수)와 맞추려면 `attempts = "6"` 설정.
**Warning signs:** DLT에 예상보다 빠르게 메시지 도달 시 attempts 값 확인.

### Pitfall 2: ack-mode 설정 키 이름

**What goes wrong:** `spring.kafka.listener.ack-mode=RECORD` — 이 키가 Spring Boot 3.3.x 자동 설정에서 올바르게 바인딩되지 않을 수 있다는 CONTEXT.md 우려.
**Investigation result:** 키 이름 `spring.kafka.listener.ack-mode`는 **올바른** Spring Boot autoconfigure 프로퍼티다. `ContainerProperties.AckMode` 열거값과 바인딩됨. Spring Boot 3.3.3에서 유효.
**Confidence:** MEDIUM (공식 Spring Boot docs에서 간접 확인, Boot 3.3.3 직접 검증은 미완료)
**Recommendation:** `RECORD` 모드는 메시지별 오프셋 커밋으로 중복 처리 최소화. Kafka 어댑터에 적합. `enable-auto-commit: false`와 함께 사용.

### Pitfall 3: Testcontainers KafkaContainer 임포트 경로

**What goes wrong:** Testcontainers 1.19.x에서 잘못된 클래스 임포트.
**How to avoid:**
- 1.19.8: `org.testcontainers.containers.KafkaContainer` (사용)
- 1.20.x 이상: `org.testcontainers.kafka.KafkaContainer` (새 모듈)
현재 프로젝트 버전 1.19.8에서는 기존 `containers.KafkaContainer` 사용.

### Pitfall 4: Spring Boot 3.2+ @RetryableTopic 기본 전략 변경

**What goes wrong:** Spring Boot 3.2+ (spring-kafka 3.0+)에서 `sameIntervalTopicReuseStrategy` 기본값이 `SINGLE_TOPIC`으로 변경. 같은 backoff 간격을 가진 retry 시도들은 단일 토픽을 공유.
**Impact:** `payment-confirm-requests-retry-1`, `-retry-2`가 아닌 `payment-confirm-requests-retry`로 단일 retry 토픽만 생성될 수 있음.
**How to avoid:** 기본값 동작을 이해하고 설계. kafbat/kafka-ui에서 토픽 목록 확인으로 검증.

### Pitfall 5: 트랜잭션 내부에서 KafkaTemplate.send() 호출 타이밍

**What goes wrong:** `@Transactional` 메서드 내에서 `kafkaTemplate.send()`를 호출하면, DB 트랜잭션이 커밋되기 전에 Kafka 메시지가 발행될 수 있음. 컨슈머가 DB 커밋 전에 메시지를 소비하면 `PaymentEvent`를 찾지 못하거나 READY 상태로 조회될 수 있음.
**How to avoid:** `confirm()` 메서드는 `@Transactional` 밖에서 동작하도록 구성. `executeStockDecreaseOnly()`가 트랜잭션 내에서 재고 감소 후 커밋, 그 다음 `kafkaTemplate.send()` 호출.

### Pitfall 6: KafkaConfirmListener와 @Transactional 충돌

**What goes wrong:** `@RetryableTopic`이 적용된 `@KafkaListener` 메서드에 `@Transactional`을 직접 적용하면 retry 동작 시 트랜잭션 상태 충돌.
**How to avoid:** 컨슈머 메서드에는 `@Transactional` 미사용. `PaymentTransactionCoordinator`의 내부 `@Transactional` 메서드가 트랜잭션 경계 담당.

---

## Code Examples

### application.yml Kafka 설정

```yaml
# Source: Spring Boot official docs (spring.kafka.* properties)
spring:
  kafka:
    bootstrap-servers: localhost:9092
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer
    consumer:
      group-id: payment-confirm-group
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      enable-auto-commit: false
    listener:
      ack-mode: RECORD
```

### Docker Compose Kafka (KRaft 단일 노드) + kafbat/kafka-ui

```yaml
# Source: bitnami/kafka Docker Hub + kafbat/kafka-ui docs
# 기존 docker-compose.yml에 추가

  kafka:
    image: bitnami/kafka:3.9
    container_name: payment-kafka
    ports:
      - "9092:9092"
    environment:
      KAFKA_CFG_NODE_ID: 1
      KAFKA_CFG_PROCESS_ROLES: broker,controller
      KAFKA_CFG_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
      KAFKA_CFG_LISTENERS: PLAINTEXT://:9092,CONTROLLER://:9093
      KAFKA_CFG_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092
      KAFKA_CFG_LISTENER_SECURITY_PROTOCOL_MAP: PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT
      KAFKA_CFG_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_CFG_AUTO_CREATE_TOPICS_ENABLE: "true"
      KAFKA_CFG_NUM_PARTITIONS: 3
      KAFKA_CFG_DEFAULT_REPLICATION_FACTOR: 1
    networks:
      - payment-network
    healthcheck:
      test: ["CMD-SHELL", "kafka-topics.sh --bootstrap-server localhost:9092 --list"]
      interval: 30s
      timeout: 10s
      retries: 5

  kafka-ui:
    image: ghcr.io/kafbat/kafka-ui:latest
    container_name: payment-kafka-ui
    ports:
      - "8081:8080"
    environment:
      KAFKA_CLUSTERS_0_NAME: payment-local
      KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS: kafka:9092
    networks:
      - payment-network
    depends_on:
      kafka:
        condition: service_healthy
```

> **포트 충돌 주의:** 기존 `app` 서비스가 8080 사용. kafbat/kafka-ui는 `8081:8080`으로 매핑.
> **app의 kafka 의존성 추가:** `docker-compose.yml`의 `app.depends_on`에 `kafka: condition: service_healthy` 추가 필요.

### application-docker.yml Kafka 설정 추가

```yaml
# application-docker.yml에 추가 (docker profile 전용)
spring:
  kafka:
    bootstrap-servers: kafka:9092
    # producer/consumer/listener 설정은 application.yml 기본값 상속
```

### build.gradle 의존성 추가

```groovy
// Source: Spring Kafka, Testcontainers docs
dependencies {
    // Kafka
    implementation 'org.springframework.kafka:spring-kafka'

    // Test
    testImplementation "org.testcontainers:kafka:${testcontainersVersion}"
}
```

### @RetryableTopic attempts 계산 예시

```java
// Source: Spring Kafka API docs - @RetryableTopic
// attempts = "6" → 1회 최초 실행 + 5회 재시도 = RETRYABLE_LIMIT=5 달성
@RetryableTopic(
    attempts = "6",
    backoff = @Backoff(delay = 1000, multiplier = 2.0, maxDelay = 30000),
    dltTopicSuffix = "-dlq",
    include = {PaymentTossRetryableException.class},
    autoCreateTopics = "true"
)
@KafkaListener(topics = "payment-confirm-requests")
public void consume(String orderId) { ... }
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Zookeeper 기반 Kafka | KRaft (Zookeeper 제거) | Kafka 3.3.1 GA, Kafka 4.0 완전 제거 | 단일 노드 설정 단순화, Zookeeper 컨테이너 불필요 |
| `org.testcontainers.containers.KafkaContainer` | `org.testcontainers.kafka.KafkaContainer` | Testcontainers 1.20.x | 1.19.8 프로젝트에서는 기존 패키지 사용 |
| `@RetryableTopic` 다중 retry 토픽 (3.0 이전) | `SINGLE_TOPIC` 재사용 기본값 (3.0+) | spring-kafka 3.0 / Spring Boot 3.2 | 토픽 수 감소, kafbat/kafka-ui에서 보이는 토픽명 달라짐 |
| BlockingRetryHandler | `@RetryableTopic` (non-blocking) | spring-kafka 2.7+ | 컨슈머 블로킹 없이 재시도 가능 |

**Deprecated/outdated:**
- `EmbeddedKafka`: 테스트 환경에서 실제 KRaft 브로커 동작 재현 불가, Testcontainers Kafka로 대체
- `@SendTo` + 수동 DLT 발행: `@DltHandler` + `@RetryableTopic`으로 선언적 처리 대체

---

## Open Questions

1. **DLT 토픽명: `payment-confirm-dlq` vs `payment-confirm-requests-dlq`**
   - What we know: `@RetryableTopic(dltTopicSuffix="-dlq")`는 `{listenerTopic}-dlq`를 생성 → `payment-confirm-requests-dlq`
   - What's unclear: KAFKA-06 요구사항은 `payment-confirm-dlq`로 명시
   - Recommendation: 토픽 기본명을 `payment-confirm`으로 변경(`payment-confirm-requests` → `payment-confirm`)하고 dltTopicSuffix="-dlq"를 적용하면 DLT = `payment-confirm-dlq`. 또는 요구사항 명세를 `payment-confirm-requests-dlq`로 정정.

2. **`executeStockDecreaseOnly()` vs 기존 메서드 직접 재사용**
   - What we know: `PaymentTransactionCoordinator.executeStockDecreaseWithOutboxCreation()`은 Outbox 레코드도 생성
   - What's unclear: Outbox 생성 없이 재고 감소만 하는 트랜잭션 메서드가 `PaymentTransactionCoordinator`에 없음
   - Recommendation: `executeStockDecreaseOnly()` 신규 추가. 또는 `OrderedProductUseCase.decreaseStockForOrders()`를 `KafkaConfirmAdapter`에서 직접 호출하고 해당 호출을 `@Transactional`로 래핑.

3. **`spring.kafka.listener.ack-mode=RECORD` Boot 3.3.3 바인딩 확인**
   - What we know: 키 이름 자체는 올바름(MEDIUM confidence)
   - What's unclear: Boot 3.3.3 정확한 바인딩 동작을 로컬 실행으로 확인 필요
   - Recommendation: 통합 테스트 실행 시 auto-offset-reset + RECORD 모드 동작을 Awaitility로 검증

---

## Validation Architecture

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit 5 (Spring Boot 3.3.3 관리) + Mockito |
| Config file | `src/test/resources/application-test.yml` |
| Quick run command | `./gradlew test --tests "*.KafkaConfirmAdapterTest"` |
| Full suite command | `./gradlew test` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| KAFKA-01 | Docker Compose Kafka 기동 확인 | manual | docker compose up + curl | ❌ (수동 확인) |
| KAFKA-02 | KafkaConfirmAdapter.confirm() → 토픽 발행 + ASYNC_202 반환 | unit | `./gradlew test --tests "*.KafkaConfirmAdapterTest"` | ❌ Wave 0 |
| KAFKA-03 | confirm() 시 재고 감소 동기 완료 | unit | `./gradlew test --tests "*.KafkaConfirmAdapterTest"` | ❌ Wave 0 |
| KAFKA-04 | KafkaConfirmListener consume → Toss API + 상태 업데이트 | unit + integration | `./gradlew test --tests "*.KafkaConfirmListenerTest"` | ❌ Wave 0 |
| KAFKA-05 | 중복 메시지 처리 시 Toss 멱등키로 안전 처리 | integration | `./gradlew test --tests "*.KafkaConfirmListenerIntegrationTest"` | ❌ Wave 0 |
| KAFKA-06 | 5회 재시도 후 DLT 라우팅 + 보상 트랜잭션 | unit (DltHandler) + integration | `./gradlew test --tests "*.KafkaConfirmListenerTest"` | ❌ Wave 0 |
| KAFKA-07 | Testcontainers Kafka 통합 테스트 통과 | integration | `./gradlew test --tests "*.KafkaConfirmListenerIntegrationTest"` | ❌ Wave 0 |

### Sampling Rate

- **Per task commit:** `./gradlew test --tests "*.KafkaConfirmAdapterTest" --tests "*.KafkaConfirmListenerTest"`
- **Per wave merge:** `./gradlew test`
- **Phase gate:** Full suite green before `/gsd:verify-work`

### Wave 0 Gaps

- [ ] `src/test/java/.../payment/infrastructure/adapter/KafkaConfirmAdapterTest.java` — KAFKA-02, KAFKA-03
- [ ] `src/test/java/.../payment/listener/KafkaConfirmListenerTest.java` — KAFKA-04, KAFKA-05, KAFKA-06
- [ ] `src/test/java/.../payment/listener/KafkaConfirmListenerIntegrationTest.java` — KAFKA-07 (Testcontainers)
- [ ] `src/test/java/.../core/test/BaseKafkaIntegrationTest.java` — Testcontainers KafkaContainer 기반 추상 클래스
- [ ] `testImplementation "org.testcontainers:kafka:${testcontainersVersion}"` — `build.gradle`에 추가 필요

---

## Sources

### Primary (HIGH confidence)

- Spring Kafka API docs (`@RetryableTopic`) — attempts, backoff, dltTopicSuffix, @DltHandler 파라미터 명세
- Spring Boot docs (`spring.kafka.*`) — producer/consumer/listener 프로퍼티 키 확인
- Testcontainers Kafka module docs — `KafkaContainer.withKraft()` API, 클래스 경로

### Secondary (MEDIUM confidence)

- [Spring Boot kafka.listener.ack-mode docs](https://runebook.dev/en/docs/spring_boot/application-properties/application-properties.integration.spring.kafka.listener.ack-mode) — ack-mode=RECORD 키 이름 확인
- [bitnami/kafka Docker Hub](https://hub.docker.com/r/bitnami/kafka) — KRaft 환경변수 패턴
- [kafbat/kafka-ui setup](https://ui.docs.kafbat.io/) — KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS 환경변수

### Tertiary (LOW confidence)

- Spring Boot 3.3.3 정확한 spring-kafka 버전 (3.2.x로 추정, 직접 BOM 확인 미완료)

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — Spring Kafka 공식 API docs + Testcontainers 공식 docs 확인
- Architecture: HIGH — OutboxConfirmAdapter/OutboxWorker 기존 패턴 직접 코드 분석
- Pitfalls: MEDIUM-HIGH — 공식 docs + 실제 코드 패턴에서 도출, `ack-mode` 바인딩 일부 MEDIUM
- Docker Compose: MEDIUM — bitnami/kafka KRaft 환경변수 공식 이미지 패턴으로 확인

**Research date:** 2026-03-15
**Valid until:** 2026-04-15 (spring-kafka 3.2.x 안정, 30일)
