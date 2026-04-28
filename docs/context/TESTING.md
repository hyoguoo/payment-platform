# Testing Patterns

> 최종 갱신: 2026-04-27

## 테스트 프레임워크

- JUnit 5 (Jupiter) — `spring-boot-starter-test`
- Mockito — Spring Boot Test 번들 포함
- AssertJ — 가독성 우선
- Testcontainers (`org.testcontainers:mysql`) — 통합 테스트 시 새 MySQL 컨테이너
- MockWebServer (`com.squareup.okhttp3:mockwebserver`) — pg-service vendor HTTP 어댑터(`HttpOperatorImpl`) traceparent 전파 contract test 한정

## 테스트 카테고리

| 카테고리 | 도구 | 특징 | 위치 |
|---|---|---|---|
| **도메인 단위** | JUnit + AssertJ | Spring 의존 0. `@ParameterizedTest @EnumSource` 로 상태 전이 유효/무효 모두 | `<service>/src/test/java/.../domain/` |
| **Use case 단위** | Mockito + Fake | port 는 Fake, 외부 의존(Repository 등)은 Mock 가능 | `.../application/` |
| **Adapter 단위** | Mockito | 출력 포트 어댑터의 변환·예외 분기 | `.../infrastructure/` |
| **JPA / Repository** | Testcontainers MySQL + `@DataJpaTest` | 실제 SQL 검증 | `.../infrastructure/persistence/` |
| **Kafka producer/consumer** | Spring Kafka EmbeddedKafka 또는 Mock + 자체 어댑터 | 실 broker 없이도 직렬화·observation 검증 | `.../infrastructure/messaging/` |
| **HTTP 어댑터 contract (cross-service)** | Mockito FeignClient mock | FeignClient 가 throw 한 도메인 예외 / `feign.RetryableException` 의 어댑터 propagation·변환 | `.../infrastructure/adapter/http/*ContractTest` |
| **Feign ErrorDecoder** | Mockito + `feign.Response` mock | 404 → NotFoundException, 429/503 → RetryableException, 그 외 5xx → IllegalStateException | `.../infrastructure/adapter/http/feign/*FeignConfigTest` |
| **HTTP 어댑터 contract (vendor)** | MockWebServer | pg-service `HttpOperatorImpl` traceparent 전파 | `pg-service/.../infrastructure/http/HttpOperatorTraceparentPropagationTest` |
| **Web layer** | `@WebMvcTest` + `MockMvc` | controller 입력 매핑 + 예외 → HTTP 상태 | `.../presentation/` |
| **통합** | `@SpringBootTest` + Testcontainers + `@Tag("integration")` | 부팅 + 실 DB | 별도 `integrationTest` task |

## Fake vs Mock 룰

| 종류 | 언제 |
|---|---|
| **Fake (정식 구현체)** | port 의 정상 행동을 재현해야 하는 경우. ConcurrentHashMap 기반 in-memory 또는 Clock 주입 TTL 시뮬레이션. **여러 테스트가 같은 행동 기대** |
| **Mock (Mockito)** | 호출 사실 검증(`verify(...)`) 또는 특정 시나리오 stub 만 필요할 때. 단일 테스트 의존 |

**예**:
- `FakeEventDedupeStore` — markWithLease/extendLease/remove 의 TTL 시뮬레이션을 정식 구현체로 (여러 테스트가 의존)
- `FakeStockCachePort` — ConcurrentHashMap merge 로 INCR/DECR 멱등 시뮬레이션
- `FakePaymentConfirmDlqPublisher` — DLQ 발행 누적 검증
- `Mockito.when(repo.findById(...)).thenReturn(Optional.of(...))` — 단일 테스트 시나리오

원칙: **외부 의존은 가능한 Fake. 내부 협력자는 Mock**.

## Testcontainers MySQL 패턴

```java
@SpringBootTest
@Testcontainers
@Tag("integration")
class SomeIntegrationTest {
    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", mysql::getJdbcUrl);
        // ...
    }
}
```

부팅 시 자동:
1. Testcontainers 가 새 MySQL 컨테이너 기동
2. Flyway `migrate()` 가 V1 부터 적용
3. JPA `ddl-auto: validate` 가 schema 검증
4. 테스트 실행

매 테스트 클래스마다 새 컨테이너 — schema 가 통합 V1 으로 잘 부팅되는 사실 자체가 회귀 게이트 역할.

## Contract test 패턴

cross-service HTTP 의 4xx / 5xx → 도메인 예외 매핑은 **2-layer** 로 검증한다 — Feign `ErrorDecoder` 가 응답 → 예외 변환을 책임지고, `*HttpAdapter` 는 transport-level 변환만 책임진다.

### Layer 1 — `*FeignConfigTest` (ErrorDecoder 매핑)

Mockito 로 `feign.Response` 를 mock 하고 `ErrorDecoder.decode()` 결과를 검증.

| 시나리오 | Response 상태 | ErrorDecoder 결과 |
|---|---|---|
| 404 NOT_FOUND | 404 | `ProductNotFoundException` / `UserNotFoundException` |
| 503 SERVICE_UNAVAILABLE | 503 | `*ServiceRetryableException` (`PRODUCT_SERVICE_UNAVAILABLE` / `USER_SERVICE_UNAVAILABLE`) |
| 429 TOO_MANY_REQUESTS | 429 | `*ServiceRetryableException` |
| 500 INTERNAL_SERVER_ERROR | 500 | `IllegalStateException` |

### Layer 2 — `*HttpAdapterContractTest` (어댑터 propagation)

Mockito 로 FeignClient 를 mock 하고 throw 시나리오별 어댑터 동작을 검증.

| 시나리오 | FeignClient 행동 | 어댑터 결과 |
|---|---|---|
| 도메인 예외 throw | `*NotFoundException` / `*ServiceRetryableException` 그대로 throw | 어댑터가 그대로 propagate |
| transport 예외 | `feign.RetryableException` (connect/read timeout 등) | 어댑터가 `*ServiceRetryableException` 로 변환 |

목적: HTTP 상태 → 도메인 의미 매핑 계약 동결 + transport 분기 동작 동결. ErrorDecoder 또는 어댑터 변경 시 회귀 즉시 감지.

### vendor 측 contract — pg-service `HttpOperatorImpl`

`HttpOperatorTraceparentPropagationTest` 가 OkHttp `MockWebServer` 로 임의 응답을 띄우고 `RestClient.Builder` 주입 구조의 `HttpOperatorImpl` 이 traceparent 헤더를 vendor 호출에 전파하는지 검증.

## `@RepeatedTest` 결정 케이스

`PgOutboxImmediateWorkerTest` 의 exactly-once 케이스는 `@RepeatedTest(50)` 으로 확장 — race window 검증. 단발 PASS 로는 lock-free 코드의 동시성 결함을 못 잡는다.

룰:
- 동시성·exactly-once·atomic 보장 검증 테스트 → `@RepeatedTest(50)` 이상
- 단순 분기 테스트 → 일반 `@Test`

## JaCoCo 커버리지 정책

**측정 대상**: application / use case / domain layer 만.
**제외**: `dto`, `entity`, `enums`, `event`, `exception`, `infrastructure`, `presentation`, `publisher`, `mock`, `aspect`, `metrics`, `log`, `filter`, `util`, `config`, `response`, `PaymentPlatformApplication`

이유: infrastructure / presentation 은 Spring wiring + Testcontainers 통합 테스트로 검증, JaCoCo 라인 커버리지 의미 약함. 도메인 + use case 가 본질.

## TDD 흐름 (CLAUDE.md 룰)

1. **RED**: 실패하는 테스트 작성 → `test:` 커밋
2. **GREEN**: 최소 구현 → `feat:` 커밋 (PLAN.md / STATE.md 함께)
3. **REFACTOR** (선택): `refactor:` 커밋

매 태스크 완료 후 `./gradlew test` 회귀 0 확인.

## 도메인 enum + 상태 전이 테스트

```java
@ParameterizedTest
@EnumSource(value = PaymentEventStatus.class, names = {"READY", "IN_PROGRESS", "RETRYING"})
void quarantine_whenNonTerminal_shouldTransition(PaymentEventStatus from) { ... }

@ParameterizedTest
@EnumSource(value = PaymentEventStatus.class, names = {"DONE", "FAILED", "CANCELED", "EXPIRED", "QUARANTINED", "PARTIAL_CANCELED"})
void quarantine_whenTerminal_shouldThrow(PaymentEventStatus from) { ... }
```

- 유효 source / 무효 source 양쪽을 `@EnumSource(names=...)` 로 명시
- 새 상태 추가 시 빠진 case 가 컴파일러는 못 잡지만 테스트가 잡는다 (exhaustive switch + isTerminal SSOT 와 같이)

## LocalDateTimeProvider 주입

`LocalDateTime.now()` 직접 호출 금지 → `LocalDateTimeProvider` 주입 (테스트에서 fixed clock 위조 가능).

## 현재 테스트 카운트 (2026-04-27 기준)

| 모듈 | 테스트 수 |
|---|---|
| eureka-server | 1 |
| gateway | 3 |
| payment-service | 358 |
| pg-service | 207 |
| product-service | 19 |
| user-service | 1 |
| **합계** | **589 PASS** (회귀 0) |

`./gradlew test --rerun-tasks` 로 전체 검증.
