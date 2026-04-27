# Testing Patterns

> 최종 갱신: 2026-04-27

## 테스트 프레임워크

- JUnit 5 (Jupiter) — `spring-boot-starter-test`
- Mockito — Spring Boot Test 번들 포함
- AssertJ — 가독성 우선
- Testcontainers (`org.testcontainers:mysql`) — 통합 테스트 시 새 MySQL 컨테이너
- MockWebServer (`com.squareup.okhttp3:mockwebserver`) — HTTP 어댑터 contract test

## 테스트 카테고리

| 카테고리 | 도구 | 특징 | 위치 |
|---|---|---|---|
| **도메인 단위** | JUnit + AssertJ | Spring 의존 0. `@ParameterizedTest @EnumSource` 로 상태 전이 유효/무효 모두 | `<service>/src/test/java/.../domain/` |
| **Use case 단위** | Mockito + Fake | port 는 Fake, 외부 의존(Repository 등)은 Mock 가능 | `.../application/` |
| **Adapter 단위** | Mockito | 출력 포트 어댑터의 변환·예외 분기 | `.../infrastructure/` |
| **JPA / Repository** | Testcontainers MySQL + `@DataJpaTest` | 실제 SQL 검증 | `.../infrastructure/persistence/` |
| **Kafka producer/consumer** | Spring Kafka EmbeddedKafka 또는 Mock + 자체 어댑터 | 실 broker 없이도 직렬화·observation 검증 | `.../infrastructure/messaging/` |
| **HTTP 어댑터 contract** | MockWebServer | 404/503/429/500 분기별 어댑터 행동 고정 | `.../infrastructure/http/` |
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

`ProductHttpAdapterContractTest`, `UserHttpAdapterContractTest` — MockWebServer 로 4개 분기 고정:

| 시나리오 | MockResponse | 어댑터 행동 |
|---|---|---|
| 404 NOT_FOUND | `setResponseCode(404)` | `Optional.empty()` 또는 `NotFoundException` |
| 503 SERVICE_UNAVAILABLE | `setResponseCode(503)` | retryable 예외 throw |
| 429 TOO_MANY_REQUESTS | `setResponseCode(429)` | retryable 예외 throw |
| 500 INTERNAL_SERVER_ERROR | `setResponseCode(500)` | retryable 예외 throw |

목적: HTTP 상태 → 도메인 의미 매핑 계약 동결. 어댑터 변경 시 회귀 즉시 감지.

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
| payment-service | 348 |
| pg-service | 206 |
| product-service | 19 |
| user-service | 1 |
| **합계** | **578 PASS** (회귀 0) |

`./gradlew test --rerun-tasks` 로 전체 검증.
