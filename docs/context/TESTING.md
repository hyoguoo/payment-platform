# Testing Patterns

## 로컬 서비스 품질 검증

`scripts/k6/quality-payment.js`와 `scripts/quality/`는 정상 부하·payment 프로세스 중단을 검증한다.
Java 단위/통합 테스트와 별개이며 [운영 기준](../quality/RUNBOOK.md)에 실행 조건을 둔다.
판정기와 장애 제어의 회귀는 `python3 -m unittest discover -s scripts/quality -p 'test_*.py'`로 확인한다.
실제 부하는 별도로 실행하며 `--output-root`로 지정한 디렉토리에 실행별 원자료와 판정을 보존한다.
실측에서는 실패·시간 초과·접수 불명을 분모에서 빼지 않으며 오래된 벤치마크 수치와 합산하지 않는다.

## 테스트 프레임워크

- JUnit 5 (Jupiter) — `spring-boot-starter-test`
- Mockito — Spring Boot Test 번들 포함
- AssertJ — 가독성 우선
- Testcontainers (`org.testcontainers:mysql` + Redis는 `GenericContainer`) — 통합 테스트용 MySQL·Redis 컨테이너
- Spring Kafka Test (`spring-kafka-test`, payment·product) — 통합 테스트의 `@EmbeddedKafka`
- ArchUnit (`archunit-junit5`) — 레이어 규칙, 루트 `build.gradle`이 4서비스에 공통 적용
- MockWebServer (`com.squareup.okhttp3:mockwebserver`) — pg-service만. vendor HTTP 어댑터(`HttpOperatorImpl`) traceparent 전파 contract test 한정

## 테스트 카테고리

분류 기준은 **외부 프로세스(컨테이너)를 띄우는가**다. 띄우면 `@Tag("integration")`을 붙여 `integrationTest`에서 돌리고,
단위 테스트(`test`)는 Docker 없이 돈다. `test`는 `integration` 태그를 제외하고(루트 `build.gradle`의 `excludeTags`),
`integrationTest`는 그 태그만 포함한다(각 서비스 `build.gradle`의 `includeTags`). 실행 명령은
[검증 순서](../../.agents/profiles/stack.md)를 따른다.

| 카테고리 | 도구 | 특징 | 위치 |
|---|---|---|---|
| **도메인 단위** | JUnit + AssertJ | Spring 의존 0. `@ParameterizedTest @EnumSource` 로 상태 전이 유효/무효 모두 | `<service>/src/test/java/.../domain/` |
| **Use case 단위** | Mockito + Fake | port 는 Fake, 외부 의존(Repository 등)은 Mock 가능 | `.../application/` |
| **Adapter 단위** | Mockito | 출력 포트 어댑터의 변환·예외 분기 | `.../infrastructure/` |
| **Kafka producer/consumer 단위** | Mockito | 컨테이너·브로커 없이 발행·직렬화 분기 검증 | `.../infrastructure/messaging/` |
| **HTTP 어댑터 contract (cross-service)** | Mockito FeignClient mock | FeignClient 가 throw 한 도메인 예외 / `feign.RetryableException` 의 어댑터 propagation·변환 | `payment-service/.../infrastructure/adapter/http/*ContractTest` |
| **Feign ErrorDecoder** | Mockito + `feign.Response` mock | 404 → NotFoundException, 429/503 → RetryableException, 그 외 5xx → IllegalStateException | `payment-service/.../infrastructure/adapter/http/feign/*FeignConfigTest` |
| **HTTP 어댑터 contract (vendor)** | MockWebServer | pg-service `HttpOperatorImpl` traceparent 전파 | `pg-service/.../infrastructure/http/HttpOperatorTraceparentPropagationTest` |
| **Web layer** | `@WebMvcTest` + `MockMvc` | controller 입력 매핑 + 예외 → HTTP 상태 | `.../presentation/` |
| **아키텍처 규칙** | ArchUnit | hexagonal 레이어 의존 방향·트랜잭션 경계·어댑터 격리를 바이트코드 의존 그래프로 판정. 규칙 정의는 `config/archunit/` 한 곳, 4서비스가 공유 | `<service>/src/test/java/.../ArchitectureTest` |
| **JPA / Repository (통합)** | Testcontainers MySQL + `@DataJpaTest` | 실제 SQL 검증 | `.../infrastructure/repository/` |
| **Redis 어댑터·Lua (통합)** | Testcontainers Redis | 재고 캐시 어댑터와 Lua 스크립트의 실제 응답 분기 검증 | `payment-service/.../infrastructure/cache/` |
| **통합 (부팅)** | `@SpringBootTest` + Testcontainers + (필요 시) `@EmbeddedKafka` | 부팅 + 실 DB·Redis·Kafka | `.../integration/` |

`@Tag("integration")`이 붙는 컨테이너 테스트는 `BaseIntegrationTest`를 상속하거나 자체 컨테이너를 띄운다.
JPA/Repository 통합 테스트에는 payment `JpaPaymentOutboxRepositoryTest`·`StockHoldRecordRepositoryImplTest`,
pg `PgOutboxRepositoryImplTest`·`PgInboxRepositoryImplTest`, product `ProductRepositoryImplPageTest`가 든다.

## Fake vs Mock 룰

| 종류 | 언제 |
|---|---|
| **Fake (정식 구현체)** | port 의 정상 행동을 재현해야 하는 경우. ConcurrentHashMap 기반 in-memory 또는 Clock 주입 TTL 시뮬레이션. **여러 테스트가 같은 행동 기대** |
| **Mock (Mockito)** | 호출 사실 검증(`verify(...)`) 또는 특정 시나리오 stub 만 필요할 때. 단일 테스트 의존 |

**예**:
- `FakeStockCachePort` — ConcurrentHashMap 기반 in-memory 시뮬레이션. `decrementAtomic` / `compensateAtomic` 결과 enum (OK / ALREADY_DONE / INSUFFICIENT) + dedup token 시뮬 포함
- `Mockito.when(repo.findById(...)).thenReturn(Optional.of(...))` — 단일 테스트 시나리오

원칙: **외부 의존은 가능한 Fake. 내부 협력자는 Mock**.

**Fake 저장소는 저장과 조회 양쪽에서 방어적 복사한다** — 자식 컬렉션의 원소까지 복사한다.
도메인 전이 메서드는 대상 객체를 in-place 로 바꾸고, 조건부 갱신(CAS) 계열 위임 메서드는 그
전이를 먼저 적용한 뒤 저장소를 부른다. 조회가 저장소 참조를 그대로 돌려주면 CAS 선행조건 검사가
이미 바뀐 값을 보고 정상 케이스를 충돌로 오판한다. 컨테이너만 새로 만들고 원소를 공유하면 더
나쁘다 — 자식 주문에 전이가 두 번 걸려 상태 가드에 막힌 예외가 난다(`FakePaymentEventRepository`). 참조 동일성에 기대던 Mockito 검증이 깨지면 도메인 값 객체에
`equals` 를 넣지 말고 테스트 쪽 매처를 값 비교로 바꾼다.

## Testcontainers MySQL 패턴

`BaseIntegrationTest`와 payment `integration/` 아래 테스트는 `withReuse(true)`를 선언하고 static 블록에서 수동 `start()` 한다. JUnit5 `@Container`/`@Testcontainers` 는 클래스 종료 후 `stop()` 을 호출해 `withReuse(true)` 를 무력화하기 때문이다(`BaseIntegrationTest.java`의 static 블록 주석). 실제 재사용은 Testcontainers 의 reuse 설정을 켠 로컬에서만 일어난다. 저장소에는 reuse 설정 파일이 없고 CI 는 reuse 를 끈다(`.github/workflows/_service-ci.yml`의 `integration-test` job 주석). Redis 단일 컨테이너 테스트(`StockCacheRedisAdapterTest` 등)는 `@Container` 로 클래스마다 띄운다.

```java
@SpringBootTest
@Tag("integration")
class SomeIntegrationTest {
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("...")   // 스키마 관리 방식별로 분리 (아래)
            .withReuse(true);
    static { MYSQL.start(); }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl);
        // ...
    }
}
```

### 스키마 관리 방식별 DB명 분리 (필수)

payment-service 통합테스트는 스키마 생성 방식이 둘로 갈린다:
- **create-drop 그룹** — `@ActiveProfiles("test")`(`application-test.yml`: `flyway.enabled=false` + `ddl-auto: create-drop`). JPA 가 스키마 생성. `BaseIntegrationTest`가 DB명 `payment-test` 를 점유한다.
- **flyway-on 그룹** — `@DynamicPropertySource` 로 `spring.flyway.enabled=true` override. Flyway 마이그레이션이 스키마 생성.

재사용 컨테이너에서 **두 방식이 같은 DB명을 공유하면**, create-drop 이 만든 history 테이블 없는 스키마에 Flyway 가 진입해 `Found non-empty schema but no schema history table` 로 ApplicationContext 로드가 깨진다(여러 통합테스트를 함께 기동할 때 flaky — 단일 클래스 격리 실행은 우연히 통과). 따라서 **flyway-on 통합테스트는 각자 전용 DB명을 갖는다**(`payment-eos-test` / `payment-scr-test` / `payment-dedupe-test` 등 클래스별로 다름). create-drop 그룹만 `payment-test` 를 쓴다.

DB명 분리는 스키마 관리 방식 경합을 차단한다. flyway-on 테스트가 마이그레이션으로 부팅되는 사실 자체가 마이그레이션 회귀 게이트 역할도 겸한다.

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

## Lua 스크립트 테스트 패턴

`payment-service/src/main/resources/lua/`의 스크립트(`stock_decrement_atomic.lua`, `stock_compensation_if_decremented.lua`, `stock_reject_compensation.lua`, `stock_order_lock_acquire.lua`, `stock_order_lock_release.lua`)는 상품·주문 단위 재고 차감·보상과 dedup token(SETNX + TTL P8D)을 한 호출 안에서 처리한다.
Lua 는 실제 Redis 가 있어야 검증되므로 이 테스트들은 모두 컨테이너를 띄우는 통합 테스트다.

| 카테고리 | 도구 | 검증 대상 |
|---|---|---|
| Lua 스크립트 | Testcontainers Redis + `StockCachePort` 어댑터 (`StockDecrementAtomicLuaTest`, `StockRejectCompensationLuaTest`, `StockCacheRedisAdapterTest`, `StockCacheRedisAdapterOrderLockTest`) | 정상 / `INSUFFICIENT` / `ALREADY_DONE` 분기, 상품별 독립, dedup token 박힘 / 만료 |
| End-to-end 보상 | Testcontainers Redis + `@EmbeddedKafka` + `@SpringBootTest` (예: `StockCompensationRecoveryIntegrationTest`) | events.confirmed FAILED 메시지 → `compensateAtomic` 호출 → 재고 복원 + dedup token + Kafka retry / DLQ 동작 |

룰:
- Lua 분기 검증은 Mockito 로 `RedisTemplate` 을 mock 하지 않고 실제 Redis 응답으로 한다
- DLQ / retry 검증은 `@EmbeddedKafka` 기반 `@KafkaListener` 통합

## `@RepeatedTest` 결정 케이스

동시성 경합 케이스는 `@RepeatedTest(50)` 등으로 반복해 race window 를 검증한다(`PaymentDuplicateConfirmConcurrencyIntegrationTest`,
`OutboxPublishFailureConcurrentClaimIntegrationTest`, `StockHoldDoubleRevertConcurrencyIntegrationTest`는 50회, `AwaitingResultReconcileRaceIntegrationTest`는 30회).
단발 PASS 로는 lock-free 코드의 동시성 결함을 못 잡는다.

룰:
- 동시성·exactly-once·atomic 보장 검증 테스트 → `@RepeatedTest` 로 수십 회 반복
- 단순 분기 테스트 → 일반 `@Test`
- 반복마다 **키를 새로 만든다**. 같은 주문 번호를 재사용하면 두 번째 반복부터 앞선 반복이 남긴 행 때문에 경합 자체가 재현되지 않는다 (`PaymentDuplicateConfirmConcurrencyIntegrationTest`)

## 구조 계약 테스트 — 설정 한 줄이 방어선인 경우

동작이 아니라 **선언 자체가 안전성을 좌우하는 자리**는 기능 테스트로 회귀를 못 잡는다. 잠금 방식이 대표적이다 — 단일 스레드에서는 어떤 잠금을 걸어도 똑같이 통과하기 때문에, 락이 빠지거나 다른 종류로 바뀌어도 테스트가 조용히 통과한다.

- `JpaPaymentOutboxRepositoryLockContractTest` — 확인 조회에 쓰기 잠금이 선언돼 있는지(리플렉션), 쿼리에 건너뛰기 힌트(`SKIP LOCKED`)가 없는지(문자열) 단정. 잠금을 제거하거나 워커 선점용 관용구로 바꾸면 실패한다
- `PgInboxPollingWorkerSpanTest` — 추적 구간이 **실제로 생성되는지**를 인메모리 exporter 로 확인. 속성 단정만 두면 구간 없이 속성만 붙는 회귀를 놓친다(→ `PITFALLS.md` 26)
- `PaymentStatusMetricsAspectTest` — 상태 전이 지표의 주체 라벨이 비는 경로가 없음을 구조적으로 고정(→ `PITFALLS.md` 27)

작성 기준: "이 한 줄이 빠져도 기존 테스트가 통과하는가?" 가 예이면 계약 테스트를 함께 둔다.

레이어 규칙도 같은 성격이다 — 도메인이 `@Entity` 를 하나 달아도 기존 테스트는 전부 통과한다. 다만 판정 대상이 한 파일 안의 선언이 아니라 클래스 간 의존 그래프라 도구가 다르다(ArchUnit). 적용 범위와 규칙 목록은 [`STACK.md`](STACK.md) 가드레일 절, 규칙 정의는 `config/archunit/`의 `HexagonalRules` 참고.

## JaCoCo 커버리지 정책

**측정 대상**: application / use case / domain layer 만.
**제외**: `Q*`, `dto`, `entity`, `enums`, `event`, `exception`, `infrastructure`, `presentation`, `publisher`, `mock`, `aspect`, `metrics`, `log`, `filter`, `util`, `config`, `response`, `**/*Application.class`

이유: infrastructure / presentation 은 Spring wiring + Testcontainers 통합 테스트로 검증하며 라인 커버리지 의미가 약하다. 도메인 + use case 가 본질.

**설정 위치**: 루트 `build.gradle` `subprojects` 블록(`jacocoTestReport`의 `excludePatterns`, `afterEvaluate`의 게이트 설정). 서비스별로는 각 `build.gradle`의 ext `jacoco.lineCoverageMinimum`만 둔다.

**게이트는 단위 `test` exec 기준** (통합 exec 미합산): CI `build-test-lint` job 이 `./gradlew :<svc>:build -x integrationTest` 로 통합 없이 돌아 게이트가 단위만으로 평가되기 때문. `jacocoTestReport`(코멘트 수치)·`jacocoTestCoverageVerification`(게이트) 둘 다 `test.exec` 만 사용해 코멘트-게이트 수치를 일치시킨다.

**통합테스트 정합성 보호**: 통합 경로로만 커버되는 결제 정합성(EOS commit/abort, dedupe, 보상 등)은 커버리지 게이트가 아니라 **CI `integration-test`의 모든 shard 통과(pass/fail)** 로 보호한다. payment는 3개, pg는 2개 shard이며 product/user는 1개다(`.github/workflows/ci.yml`). 통합테스트가 깨지면 해당 shard job이 실패한다(branch protection required checks 전제).

**커버리지 게이트**: `jacocoTestCoverageVerification` 에 LINE `minimum` 을 둔다(element=`BUNDLE` — 테스트 없는 관리 클래스가 전체를 차단하지 않도록 번들 합산). 값은 단위 실측에서 안전마진(~3%p)을 뺀 값이다(ext `jacoco.lineCoverageMinimum`: payment 0.86 / pg 0.93 / product 0.97 / user 0.97). gateway·eureka 는 값을 두지 않아 0(기본값)이다 — 라우팅/디스커버리 전용이라 측정 대상 클래스가 없다.

## TDD 흐름

비즈니스 로직·상태 전이·버그 수정에 적용한다. 문서·문구·단순 선언에는 산출물에 맞는 검사를 한다.

1. **RED**: 실패하는 테스트를 먼저 작성하고 실패를 확인한다.
   - 도메인 entity: `@ParameterizedTest @EnumSource` 로 유효/무효 상태 전환 모두 커버.
   - Use case: Mockito 단위 테스트 먼저 작성.
   - 테스트 목록에는 정상 경로와 함께 [테스트 관점](../../.agents/conventions/testing.md)의 예외 케이스를 넣는다.
2. **GREEN**: 테스트를 통과하는 최소 구현.
3. **REFACTOR** (선택): 개선. 변경이 없으면 생략한다.

검증 범위와 정적 분석은 [AGENTS.md](../../AGENTS.md)의 구현과 검증 절, 명령은 [검증 순서](../../.agents/profiles/stack.md)를 따른다.
커밋 단위는 [Git 규칙](../../.agents/conventions/git.md)의 커밋 절을 따른다.

## 도메인 enum + 상태 전이 테스트

```java
@ParameterizedTest
@EnumSource(value = PaymentEventStatus.class, names = {"READY", "IN_PROGRESS"})
void quarantine_whenNonTerminal_shouldTransition(PaymentEventStatus from) { ... }

@ParameterizedTest
@EnumSource(value = PaymentEventStatus.class, names = {"DONE", "FAILED", "CANCELED", "EXPIRED", "QUARANTINED", "PARTIAL_CANCELED"})
void quarantine_whenTerminal_shouldThrow(PaymentEventStatus from) { ... }
```

- 유효 source / 무효 source 양쪽을 `@EnumSource(names=...)` 로 명시
- 새 상태 추가 시 빠진 case 가 컴파일러는 못 잡지만 테스트가 잡는다 (exhaustive switch + isTerminal SSOT 와 같이)

## 시각 추상화 — Clock 주입

`LocalDateTime.now()` / `Instant.now()` 직접 호출 금지 → JDK `Clock` 빈(payment·product·user 의 `ClockConfig`) 주입 + 도메인은 `Instant` 를 인자로 전달받는다. 테스트는 고정 `Clock` 으로 시각을 위조한다(payment 통합 테스트는 `BaseIntegrationTest.TestClock` 의 `setFixedInstant(...)`).

## 테스트 개수

개수는 매번 달라지므로 문서에 적지 않는다. 필요하면 `./gradlew test --rerun-tasks`(단위) /
`./gradlew :<svc>:integrationTest --rerun-tasks`(통합)로 재실행해 확인한다. 회귀 가드는 개수가 아니라 pass/fail 이다.
