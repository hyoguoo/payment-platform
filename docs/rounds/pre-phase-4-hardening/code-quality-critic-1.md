# code-code-quality-4 (critic)

**Topic**: PRE-PHASE-4-HARDENING
**Round**: 4 (code-quality 단독)
**Persona**: Critic

## Reasoning
하드닝 4 라운드 산출물의 craftsmanship을 5 모듈 main source 전수 점검했다.
**구조적 결함 1건 — application 계층이 infrastructure 계층을 광범위하게 import** (payment-service 8개 클래스, pg-service 6개 클래스) — 이 한 건만으로도 ARCHITECTURE.md 의 hexagonal layer 규약이 깨져 있고, 향후 인프라 교체·모듈 분리·테스트 격리에 모두 누적 부담을 만든다 → critical.
그 외에 **컨벤션 위반 4건**(LogFmt 5중 복제·`@Value` 필드 주입과 생성자 주입 혼용·HTTP 어댑터의 `IllegalStateException` 변환 누락 카테고리·도메인 객체 Lombok 미적용), **유지보수성 4건**(시간 소스 비일관·`PaymentConfirmResultUseCase` 331라인 책임 비대·`@Deprecated` 잔재·중복 `offer/offerNow` 오버로드), **초보 코드 3건**(static `ObjectMapper` 5중 복제·FQN 주소 사용·서비스 내 `enum + static final class` 인라인 캡슐화), **디자인 결함 2건**(`AsyncConfig` 와 `OutboxWorker.processParallel` 이중 래핑 boilerplate 중복·`buildConfirmedPayload`의 dead 분기 throw)을 식별했다 → 합산 14건. 최소 3건이 craftsmanship 만성 부담(major), 1건이 설계 결함(critical), 나머지는 minor.

## Checklist judgement

| section | item | status | evidence |
|---|---|---|---|
| convention | LogFmt 사용 / 평문 log 미사용 | yes | 평문 `log.info/warn/error` 호출 grep 결과 0건 |
| convention | Lombok `@RequiredArgsConstructor` + `@Getter` 권장 / `@Data` 금지 | no (major) | `StockOutbox` 도메인 POJO가 Lombok 없이 hand-rolled getter |
| convention | null 반환 금지, Optional 사용 | no (minor) | `TossPaymentGatewayStrategy.parseApprovedAt` 등 null 반환 잔존 (하지만 fallback 의미가 명확하므로 minor) |
| convention | `@Value` 필드 주입 금지 (`@RequiredArgsConstructor` 통일) | no (major) | `PaymentConfirmResultUseCase`, `ProductHttpAdapter`, `UserHttpAdapter`, `*KafkaPublisher`에서 `@Value` 필드 주입 + 명시 생성자 혼용 |
| convention | `catch (Exception)` 없음 | yes | T-F2에서 모두 `RuntimeException` / `Throwable` 로 축소 — 잔여 `catch (Exception)` 0건 |
| convention | 패키지 레이어 위반 (`application → infrastructure` 등) | no (critical) | application 계층 14개 클래스가 infrastructure 계층 직접 import — ARCHITECTURE.md 위반 |
| design | 도메인 누수 (도메인이 인프라 import) | yes | domain 패키지 grep 결과 0건 — clean |
| design | 어댑터 안 비즈니스 로직 | yes | HTTP/Kafka 어댑터는 thin (LogFmt + 위임만) |
| maintainability | 메서드 길이 ≥ 60줄 | yes | 60줄 초과 메서드 없음 — 이미 분해되어 있다 |
| maintainability | 파라미터 ≥ 5개 | no (minor) | `StockOutbox.create(...)` / `PgOutbox.create(...)` 5인자 — primitive obsession 후보지만 acceptable |
| maintainability | 중복 코드 (3+ 사용처에서 비슷한 로직) | no (major) | LogFmt 복제 5회·이중 래핑 boilerplate 3회·시간 소스 직접 호출 다수 |
| maintainability | 시간 소스 일관성 (Clock/Provider 주입) | no (major) | `StockOutboxRelayService` / `FailureCompensationService` / `PaymentConfirmResultUseCase.buildStockCommitOutbox` 가 `LocalDateTime.now()` / `Instant.now()` 직접 호출 — 같은 클래스에 `LocalDateTimeProvider` 주입 받고도 안 씀 |
| beginner | 매직 넘버 | yes | 상수화 잘 되어 있음 (`STOP_AWAIT_TIMEOUT_SECONDS`, `NEAR_FULL_DIVISOR` 등) |
| beginner | static mutable state | no (minor) | LogFmt의 `private static final ObjectMapper objectMapper = new ObjectMapper();` 5중 복제 — Jackson 모듈 등록 누락 시 일제 수정 필요 |
| beginner | 임시 디자인·잔재 | no (minor) | `@Deprecated PgConfirmResult(6-arg) constructor`, `@Deprecated EventDedupeStore.markSeen` — 정상 cleanup 시점 미정 |
| design | 구조적 dead code | no (minor) | `DuplicateApprovalHandler.buildConfirmedPayload`의 `case "APPROVED"` 분기가 `IllegalArgumentException` throw — 진입 불가 분기를 코드로 남김 |

총 16개 평가 항목 중 1 critical / 4 major / 4 minor / 7 yes.

## Findings

### CRITICAL

**F-01 [critical]** — Hexagonal layer 위반: application → infrastructure 광범위 import
- **location**: 
  - payment-service: `payment/application/usecase/PaymentConfirmResultUseCase.java:20-22`, `payment/application/service/FailureCompensationService.java:8-9`, `payment/application/service/OutboxRelayService.java:12-13`, `payment/application/service/StockCacheWarmupService.java:7`, `payment/application/service/PaymentReconciler.java:14`, `payment/application/usecase/PaymentCommandUseCase.java:3,6`, `payment/application/usecase/PaymentCreateUseCase.java:5`
  - pg-service: `pg/application/service/PgVendorCallService.java:20-22`, `pg/application/service/DuplicateApprovalHandler.java:16-19`, `pg/application/service/PgConfirmService.java:15`, `pg/application/service/PgDlqService.java:12-14`, `pg/application/service/PgFinalConfirmationGate.java:15-17`, `pg/application/service/PgInboxAmountService.java:8`
- **problem**: ARCHITECTURE.md의 layer 규약은 application → port(out) → infrastructure 단방향이어야 한다. 지금은 application 계층이 `infrastructure.messaging.PaymentTopics`, `infrastructure.messaging.event.StockCommittedEvent/StockRestoreEvent`, `infrastructure.messaging.event.ConfirmedEventPayload(Serializer)`, `infrastructure.aspect.annotation.*`, `infrastructure.converter.AmountConverter`, `infrastructure.metrics.StockCacheDivergenceMetrics` 를 직접 참조한다.
- **evidence**: `grep -rn "import com.hyoguoo.paymentplatform.payment.infrastructure." payment-service/src/main/java/.../payment/application` 14건, 동일 패턴 pg-service 14건. 한 예: `PaymentConfirmResultUseCase.java:20 import ...payment.infrastructure.messaging.PaymentTopics; line 22 import ...infrastructure.messaging.event.StockCommittedEvent;` 이후 `buildStockCommitOutbox`(L232~)에서 직접 사용.
- **suggestion**: 
  1. 토픽 상수(`PaymentTopics`, `PgTopics`)는 `application/port/out` 또는 `domain/topic` 으로 이동 — application 이 알아야 할 식별자이므로 application 측 소유.
  2. `StockCommittedEvent` / `StockRestoreEvent` / `ConfirmedEventPayload(Serializer)` 같은 wire-format DTO 는 `application/dto/event` 로 이동 — outbox 도메인 의미는 application 이 결정한다 (현재 stock_outbox row 의 payload semantic 도 application 이 빌드).
  3. `AmountConverter` 는 `application/util` 또는 domain VO 로 승격 — 도메인 정합성 검증이므로 인프라 레이어가 아니다.
  4. `aspect.annotation` 은 application 계층에서 사용하는 contract 이므로 `application/aspect/annotation` 으로 이동.
  5. `StockCacheDivergenceMetrics` 같은 메트릭은 port 인터페이스 도입 (`StockCacheDivergenceRecorder` 등) 후 infra 가 구현.

이 결함은 누적성이며 일정 안에 별도 리팩터 토픽으로 처리해야 한다. 장기적으로 모듈 단위 분리·테스트 컨텍스트 격리 비용을 누적시킨다.

### MAJOR

**F-02 [major]** — 시간 소스 비일관: 같은 클래스 내 `LocalDateTimeProvider` 주입 + `LocalDateTime.now()` / `Instant.now()` 직접 호출 혼용
- **location**:
  - `payment-service/.../payment/application/usecase/PaymentConfirmResultUseCase.java:216-217, 237`
  - `payment-service/.../payment/application/service/FailureCompensationService.java:72, 75`
  - `payment-service/.../payment/application/service/StockOutboxRelayService.java:47`
- **problem**: `PaymentConfirmResultUseCase`는 `LocalDateTimeProvider localDateTimeProvider`를 주입받고 한곳에선 `localDateTimeProvider.now()`(L216) 다른 곳(L237 `Instant.now()`)에선 정적 호출. `FailureCompensationService`는 `LocalDateTimeProvider` 자체를 주입받지 않고 `LocalDateTime.now()` + `Instant.now()` 직접 호출. `StockOutboxRelayService.relay`도 마찬가지. 테스트에서 시간 결정성 깨지고, 같은 도메인 이벤트 한 건의 timestamp 두 개가 서로 다른 clock 에서 산출되어 정합성 위험.
- **evidence**: `grep -n "Instant.now\|LocalDateTime.now"` 결과 위 3 파일 4 라인.
- **suggestion**: `LocalDateTimeProvider` 가 `Instant now()` 도 노출하거나, `Clock` 주입 패턴(pg-service 처럼)으로 통일. `FailureCompensationService` / `StockOutboxRelayService` 도 같은 추상화로 주입.

**F-03 [major]** — `@Value` 필드 주입과 명시 생성자 주입 혼용
- **location**:
  - `payment-service/.../payment/application/usecase/PaymentConfirmResultUseCase.java:78-82`
  - `payment-service/.../payment/infrastructure/adapter/http/ProductHttpAdapter.java:41-42`
  - `payment-service/.../payment/infrastructure/adapter/http/UserHttpAdapter.java:37-38`
  - `payment-service/.../payment/infrastructure/messaging/publisher/StockOutboxKafkaPublisher.java:35-36`
  - `payment-service/.../payment/infrastructure/messaging/publisher/KafkaMessagePublisher.java:42-43`
  - `payment-service/.../payment/infrastructure/messaging/publisher/PaymentConfirmDlqKafkaPublisher.java:35-36`
  - `payment-service/.../payment/scheduler/OutboxWorker.java:24-31`
- **problem**: CONVENTIONS.md L62 — "constructor injection — never use @Autowired on fields". `@Value` 도 사실상 같은 안티패턴(필드 주입 + 가변). 위 모든 클래스가 명시 생성자(또는 `@RequiredArgsConstructor`)를 두면서 `@Value` 만 필드에 남겨 두어 일관성이 깨졌다. `final` 도 부여 못함.
- **evidence**: `grep -rn "@Value" /payment/infrastructure/adapter/http /payment/infrastructure/messaging/publisher` 7개 파일 인용.
- **suggestion**: 1) `@ConfigurationProperties` 객체로 묶거나, 2) 생성자에 `@Value` 파라미터로 주입해서 `final` 부여. 토픽 / base-url 등은 후자가 더 가볍다.

**F-04 [major]** — `StockOutbox` 도메인 POJO 가 Lombok 컨벤션 미적용 + 가변 setter 노출
- **location**: `payment-service/.../payment/domain/StockOutbox.java:14-119`
- **problem**: CONVENTIONS.md "Domain entity (custom builder names)" 패턴(`@Getter` + `@Builder(builderMethodName = "allArgsBuilder", buildMethodName = "allArgsBuild")` + `@AllArgsConstructor(access = AccessLevel.PRIVATE)`)을 모두 위반. hand-rolled 생성자 + getter 9개가 도메인 패키지에 들어왔다. 또한 `processedAt`, `attempt` 가 `final` 미부여 + `markProcessed`/`incrementAttempt` setter 노출 — `PaymentEvent` / `PgOutbox` 의 일관 패턴(상태 전이 메서드만 노출, 단발 setter 없음)과 어긋난다.
- **evidence**: `StockOutbox.java:22-23` `private LocalDateTime processedAt; private int attempt;` final 미부여 + L112-118 setter 메서드.
- **suggestion**: `@Getter` + `@Builder(builderMethodName = "allArgsBuilder", buildMethodName = "allArgsBuild")` + `@AllArgsConstructor(access = AccessLevel.PRIVATE)` 패턴으로 재구성. setter 는 도메인 의미를 가진 메서드명(예: `markProcessed`) 만 유지.

**F-05 [major]** — 중복 코드: VT executor 이중 래핑(OTel + Micrometer) boilerplate 가 3 위치에 복제
- **location**:
  - `payment-service/.../core/config/AsyncConfig.java:39-48`
  - `payment-service/.../payment/scheduler/OutboxWorker.java:51-61` (`processParallel`)
  - `pg-service/.../pg/scheduler/PgOutboxImmediateWorker.java:71-91` (`start`)
- **problem**: 동일한 `Context.taskWrapping(Executors.newVirtualThreadPerTaskExecutor())` → `ContextExecutorService.wrap(otelWrapped, ContextSnapshotFactory.builder().build())` 패턴이 3곳에 복제. 향후 wrap 순서 변경·context 전파 정책 변경 시 3곳을 동시에 수정해야 한다. 한 곳만 누락하면 traceparent 회귀 (이미 T-I~T-J 시리즈에서 발생).
- **evidence**: 위 라인 인용.
- **suggestion**: `ContextPropagationExecutors` 헬퍼(또는 `ContextAwareVirtualThreadExecutor` 팩토리) 1개로 추출하여 3곳이 위임. payment-service 와 pg-service 가 별도 module 이므로 각 모듈 안에 `core/common/concurrent/` 같은 위치에 동일한 헬퍼를 두는 ADR-19 복제 방식을 채택해도 좋다.

### MINOR

**F-06 [minor]** — `private static final ObjectMapper` 5 모듈 복제 (LogFmt)
- **location**: `payment-service/.../core/common/log/LogFmt.java:19`, `pg-service/.../pg/core/common/log/LogFmt.java:18`, `product-service/.../product/core/common/log/LogFmt.java:18`, `user-service/.../user/core/common/log/LogFmt.java:18`, `gateway/.../gateway/core/common/log/LogFmt.java:18`
- **problem**: ADR-19 복제 정책상 모듈 간 공유 jar 금지지만, `LogFmt.toJson` 만 사용하는 ObjectMapper 가 모듈마다 별도 인스턴스로 생성됨. Jackson 모듈 등록(JavaTimeModule 등) 정책 변경 시 5곳 일괄 수정 + 누락 위험.
- **evidence**: grep `private static final ObjectMapper`.
- **suggestion**: 각 모듈 LogFmt 가 Spring `ObjectMapper` 빈을 주입받도록 Component 화하거나, `LogFmt.toJson` 자체를 별도 util 로 분리. 현 시점에는 `JavaTimeModule` 미등록 시 `LocalDateTime` 직렬화에 실패하는 점이 잠복 리스크.

**F-07 [minor]** — `PaymentConfirmResultUseCase` 책임 비대 (331 라인, 한 클래스에 dedupe·status 분기·outbox INSERT·payload 직렬화)
- **location**: `payment-service/.../payment/application/usecase/PaymentConfirmResultUseCase.java`
- **problem**: 클래스 한 개가 (1) two-phase lease guard, (2) status 분기, (3) APPROVED 경로의 amount mismatch 검증 + stock_outbox 빌드, (4) FAILED 경로 위임, (5) payload JSON 직렬화 까지 모두 담고 있다. `buildStockCommitOutbox` / `serializeToJson` 은 `FailureCompensationService` 와 동일한 책임이므로 공통 헬퍼로 분리 가능.
- **evidence**: 파일 길이 331 라인, `serializeToJson` 메서드가 `FailureCompensationService.serializeToJson` 과 코드 라인 단위로 동일.
- **suggestion**: `StockOutboxFactory` 같은 application 헬퍼로 빌더 + 직렬화 추출. usecase 는 status 분기에만 집중.

**F-08 [minor]** — Fully Qualified Name 인라인 사용 (코드 정리 미흡)
- **location**: `pg-service/.../pg/application/service/PgVendorCallService.java:225-226`
- **problem**: 클래스 이름 충돌이 없는데도 `com.hyoguoo.paymentplatform.pg.infrastructure.converter.AmountConverter.fromBigDecimalStrict(...)` FQN 으로 호출. 이 줄만 보면 패키지 의존성 검증 회피처럼 보이지만, 같은 파일 다른 곳은 import 가 깔려 있다 — 미정리 잔재.
- **evidence**: 위 라인 인용. 같은 클래스 다른 메서드는 정상 import.
- **suggestion**: 정상 import 로 정리. (덧붙여 F-01의 application→infrastructure 위반과 묶어서 처리.)

**F-09 [minor]** — `DuplicateApprovalHandler.buildConfirmedPayload` dead 분기를 throw 로 표현
- **location**: `pg-service/.../pg/application/service/DuplicateApprovalHandler.java:303-308`
- **problem**: `case "APPROVED" -> { throw new IllegalArgumentException(...) }` — switch 분기를 dead 로 만들어 두고 호출자 책임으로 위반. 코멘트로 "이 분기는 사용 안 됨" 이라 명시. dead branch 는 코드에서 제거하는 편이 정직하다.
- **evidence**: 위 라인. 동일 클래스 L293 `buildApprovedPayload` 가 별도 존재.
- **suggestion**: `case "APPROVED"` 분기 제거 후 `default` 에서 통합 throw, 또는 enum 으로 status 타입 도입.

**F-10 [minor]** — `@Deprecated` 잔재 — 청소 시점 미정
- **location**: 
  - `payment-service/.../payment/application/port/out/EventDedupeStore.java:70-73 markSeen()`
  - `pg-service/.../pg/application/dto/PgConfirmResult.java:28-37 6-arg constructor`
- **problem**: `@Deprecated` 부여 후 청소 일정이 명시적이지 않다. `@Deprecated(since = "...", forRemoval = true)` 로 강도 올리거나 즉시 제거. 현재는 새로 합류하는 코드가 무엇을 써야 할지 표지가 약하다.
- **evidence**: 위 라인 인용.
- **suggestion**: `@Deprecated(forRemoval = true)` 부여 + 다음 정리 토픽에 흡수.

**F-11 [minor]** — `PgOutboxChannel.offer` 가 `offerNow` 단순 위임 — 하위 호환 API 가 영구화
- **location**: `pg-service/.../pg/infrastructure/channel/PgOutboxChannel.java:71-87`
- **problem**: T-J4 에서 `offerNow(Long)` 도입 시 기존 호출처 호환을 위해 `offer(Long)` 을 단순 위임 오버로드로 남겼지만, 호출자(`OutboxReadyEventHandler`) 는 실제로 `offerNow` 를 직접 호출 중이다. 즉 `offer(Long)` 은 사용처가 없다.
- **evidence**: `OutboxReadyEventHandler.java:39 channel.offerNow(...)`. 본 채널의 `offer(Long)` 외부 호출처 grep 결과 0건(테스트 외).
- **suggestion**: `offer(Long)` 오버로드 제거 또는 deprecate.

**F-12 [minor]** — `enum + static final class` 인라인 캡슐화 패턴 3 클래스에 복제 (`GatewayOutcome` / `FcgOutcome` / `VendorQueryOutcome`)
- **location**:
  - `pg-service/.../pg/application/service/PgVendorCallService.java:70-99` (`OutcomeKind` + `GatewayOutcome`)
  - `pg-service/.../pg/application/service/PgFinalConfirmationGate.java:79-102` (`FcgOutcomeKind` + `FcgOutcome`)
  - `pg-service/.../pg/application/service/DuplicateApprovalHandler.java:99-118` (`VendorQueryKind` + `VendorQueryOutcome`)
- **problem**: "try 블록 외부 변수 재할당 금지 규약"을 우회하는 동일 패턴이 3 클래스에서 거의 같은 형식으로 반복. 매번 enum + 정적 팩토리 4개 + 필드 3개를 hand-roll. Java 21 `sealed interface + record` 한 줄이면 표현 가능.
- **evidence**: 위 라인 인용.
- **suggestion**: `sealed interface Outcome<T> permits Success, Failure, ...` 형 record 패턴으로 통일. 또는 `Either<L,R>` 라이트 도입. 적어도 PgVendorCallService 의 4-state outcome 은 sealed record 가 자연스럽다.

**F-13 [minor]** — `record ProductHttpAdapter.StockCommandItem` package-private 노출 + adapter 내 record 다중 정의
- **location**: `payment-service/.../payment/infrastructure/adapter/http/ProductHttpAdapter.java:122-133`
- **problem**: `ProductResponse` 와 `StockCommandItem` 두 record 가 어댑터 내부에 인라인 정의되어 있고 `StockCommandItem` 은 package-private 이라 외부에서 접근 가능. 어댑터의 wire DTO 는 `infrastructure/adapter/http/dto/` 같은 하위 패키지로 분리하는 패턴(다른 모듈에서 채택)이 일관성 있다.
- **evidence**: 위 라인 인용. UserHttpAdapter 도 동일 패턴.
- **suggestion**: 어댑터 내 dto 를 별도 패키지로 분리.

**F-14 [minor]** — `PgConfirmResult` 7-arg record + 6-arg `@Deprecated` 보조 생성자 → record canonical constructor 의 의미가 흐려짐
- **location**: `pg-service/.../pg/application/dto/PgConfirmResult.java:14-37`
- **problem**: record 가 7개 컴포넌트를 노출하면서 6-arg 보조 생성자도 함께 둠. record 의 immutable 의미는 명확하지만, 보조 생성자 + Deprecated 조합이 호출자에게 "어느 것이 정식인가" 라는 인지 부담을 준다. T-A1 도입 시 한꺼번에 7-arg 전환했어야 한다.
- **evidence**: 위 라인. 추가로 `result.approvedAtRaw()` null 체크가 호출처(`PgVendorCallService.buildApprovedPayload`)에 있어 record 자체 invariant 가 약함.
- **suggestion**: 보조 생성자 즉시 제거 + 모든 호출처 7-arg 로 통일. record 가 invariant(null disallow) 를 강제하도록 canonical constructor 추가 검증.

## JSON

```json
{
  "stage": "code",
  "persona": "critic",
  "round": 4,
  "task_id": "code-quality",

  "decision": "fail",
  "reason_summary": "Application 계층이 Infrastructure 계층을 광범위하게 import 하는 hexagonal layer critical 위반 1건. 별도로 시간 소스 비일관·@Value 필드 주입 혼용·도메인 객체 Lombok 미적용·VT 이중 래핑 boilerplate 복제의 major 4건.",

  "checklist": {
    "source": "_shared/checklists/code-ready.md",
    "items": [
      {
        "section": "convention",
        "item": "패키지 레이어 위반 (application → infrastructure 등)",
        "status": "no",
        "evidence": "payment-service application 7 파일·pg-service application 6 파일이 infrastructure.* import — PaymentConfirmResultUseCase.java:20-22 외 13 파일"
      },
      {
        "section": "convention",
        "item": "Lombok 패턴 (@RequiredArgsConstructor, @Getter / @Data 금지)",
        "status": "no",
        "evidence": "StockOutbox.java 도메인 POJO 가 Lombok annotation 없이 hand-rolled — 동일 컨텍스트 PaymentEvent / PgOutbox 와 패턴 불일치"
      },
      {
        "section": "convention",
        "item": "@Value 필드 주입 금지 / 생성자 주입 통일",
        "status": "no",
        "evidence": "PaymentConfirmResultUseCase.java:78-82, ProductHttpAdapter.java:41, UserHttpAdapter.java:37, *KafkaPublisher.java sendTimeoutMillis 등 7 파일이 명시 생성자 + @Value 필드 혼용"
      },
      {
        "section": "convention",
        "item": "신규 로깅이 LogFmt 사용",
        "status": "yes",
        "evidence": "평문 log.info/warn/error grep 0건 (LogFmt.java 본체 제외)"
      },
      {
        "section": "convention",
        "item": "catch (Exception e) 없음",
        "status": "yes",
        "evidence": "T-F2 결과 catch (Exception) grep 0건 — RuntimeException/Throwable 로 축소"
      },
      {
        "section": "maintainability",
        "item": "시간 소스 일관성 (Clock/Provider 주입)",
        "status": "no",
        "evidence": "FailureCompensationService.java:72,75 / StockOutboxRelayService.java:47 / PaymentConfirmResultUseCase.java:237 가 LocalDateTime.now()/Instant.now() 직접 호출 — 같은 클래스에 LocalDateTimeProvider 주입받고도 미사용"
      },
      {
        "section": "maintainability",
        "item": "중복 코드 (3+ 사용처 동일 로직)",
        "status": "no",
        "evidence": "VT 이중 래핑 boilerplate 3곳 복제 (AsyncConfig, OutboxWorker, PgOutboxImmediateWorker), enum + static final class outcome 패턴 3곳 복제 (PgVendorCallService, PgFinalConfirmationGate, DuplicateApprovalHandler), serializeToJson 메서드 동일 본문 2곳"
      },
      {
        "section": "design",
        "item": "도메인 누수 (도메인이 인프라 import)",
        "status": "yes",
        "evidence": "domain 패키지 grep 결과 infrastructure import 0건"
      },
      {
        "section": "design",
        "item": "어댑터 안 비즈니스 로직",
        "status": "yes",
        "evidence": "HTTP/Kafka 어댑터는 LogFmt + 위임만 — 비즈니스 로직 부재"
      },
      {
        "section": "convention",
        "item": "null 반환 금지",
        "status": "no",
        "evidence": "TossPaymentGatewayStrategy.java:210, NicepayPaymentGatewayStrategy.java:252, DomainEventLoggingAspect.java:77, PaymentStatusMetricsAspect.java:68 — minor (fallback 의미 명확)"
      },
      {
        "section": "beginner",
        "item": "static mutable / 중복 인스턴스",
        "status": "no",
        "evidence": "LogFmt 의 static ObjectMapper 5 모듈 복제 (private static final ObjectMapper objectMapper = new ObjectMapper(); 5건)"
      }
    ],
    "total": 16,
    "passed": 7,
    "failed": 9,
    "not_applicable": 0
  },

  "scores": {
    "correctness": 0.86,
    "conventions": 0.62,
    "discipline": 0.74,
    "test_coverage": 0.85,
    "domain": 0.82,
    "mean": 0.778
  },

  "findings": [
    {
      "severity": "critical",
      "checklist_item": "패키지 레이어 위반 (application → infrastructure)",
      "location": "payment-service application/usecase/PaymentConfirmResultUseCase.java:20-22, application/service/FailureCompensationService.java:8-9, application/service/OutboxRelayService.java:12-13, application/service/StockCacheWarmupService.java:7, application/service/PaymentReconciler.java:14, application/usecase/PaymentCommandUseCase.java:3,6, application/usecase/PaymentCreateUseCase.java:5; pg-service application/service/PgVendorCallService.java:20-22, application/service/DuplicateApprovalHandler.java:16-19, application/service/PgConfirmService.java:15, application/service/PgDlqService.java:12-14, application/service/PgFinalConfirmationGate.java:15-17, application/service/PgInboxAmountService.java:8",
      "problem": "ARCHITECTURE.md hexagonal layer 규약상 application → port → infrastructure 단방향이어야 하나, payment-service 7개·pg-service 6개 application 클래스가 infrastructure.messaging.PaymentTopics, infrastructure.messaging.event.*, infrastructure.aspect.annotation.*, infrastructure.converter.AmountConverter, infrastructure.metrics.* 를 직접 import 한다. 토픽 상수·wire DTO·금액 검증 util 이 application 의 의사결정에 필요함에도 인프라 패키지에 위치해 의존 방향이 역전됨.",
      "evidence": "grep -rn 'import com.hyoguoo.paymentplatform.payment.infrastructure.' payment-service/.../payment/application 14건, 동일 pattern pg-service 14건. PaymentConfirmResultUseCase.buildStockCommitOutbox(L232) 가 PaymentTopics.EVENTS_STOCK_COMMITTED + StockCommittedEvent record 를 직접 사용.",
      "suggestion": "(1) PaymentTopics/PgTopics 를 application/port/out 또는 domain/topic 으로 이동. (2) StockCommittedEvent / StockRestoreEvent / ConfirmedEventPayload(Serializer) 같은 wire DTO 를 application/dto/event 로 이동. (3) AmountConverter 를 application/util 또는 domain VO 로 승격. (4) aspect annotation 을 application/aspect/annotation 로 이동. (5) 인프라 의존이 진짜 필요한 경우 port 인터페이스 도입(StockCacheDivergenceRecorder 등). 별도 리팩터 토픽으로 분리 권장."
    },
    {
      "severity": "major",
      "checklist_item": "시간 소스 일관성 (Clock/Provider 주입)",
      "location": "payment-service/.../payment/application/usecase/PaymentConfirmResultUseCase.java:216-217,237; payment-service/.../payment/application/service/FailureCompensationService.java:72,75; payment-service/.../payment/application/service/StockOutboxRelayService.java:47",
      "problem": "PaymentConfirmResultUseCase 는 LocalDateTimeProvider 를 주입받고 L216 에서는 localDateTimeProvider.now() 호출하지만 L237 buildStockCommitOutbox 의 StockCommittedEvent 생성 시 Instant.now() 정적 호출. FailureCompensationService 와 StockOutboxRelayService 는 LocalDateTimeProvider 주입 자체가 없고 LocalDateTime.now() / Instant.now() 직접 호출. 같은 도메인 이벤트 한 건이 서로 다른 clock 에서 산출된 timestamp 를 갖게 됨 + 테스트 결정성 깨짐.",
      "evidence": "grep -n 'Instant.now\\|LocalDateTime.now' 결과 위 3 파일 4 라인. PaymentConfirmResultUseCase 는 같은 클래스 L209 paymentEvent.done(receivedApprovedAt, localDateTimeProvider.now()) 와 L237 Instant.now() 가 공존.",
      "suggestion": "LocalDateTimeProvider 가 Instant now() 도 노출하거나 Clock 주입 패턴(pg-service 처럼)으로 통일. FailureCompensationService / StockOutboxRelayService 도 같은 추상화 주입."
    },
    {
      "severity": "major",
      "checklist_item": "@Value 필드 주입 금지 / 생성자 주입 통일",
      "location": "payment-service/.../payment/application/usecase/PaymentConfirmResultUseCase.java:78-82, infrastructure/adapter/http/ProductHttpAdapter.java:41, infrastructure/adapter/http/UserHttpAdapter.java:37, infrastructure/messaging/publisher/StockOutboxKafkaPublisher.java:35-36, infrastructure/messaging/publisher/KafkaMessagePublisher.java:42-43, infrastructure/messaging/publisher/PaymentConfirmDlqKafkaPublisher.java:35-36, scheduler/OutboxWorker.java:24-31",
      "problem": "CONVENTIONS.md L62 'constructor injection — never use @Autowired on fields' 규약. @Value 필드 주입도 같은 안티패턴(가변·final 미부여·테스트 reflection 의존). 위 7 파일이 모두 명시 생성자(또는 @RequiredArgsConstructor)를 두면서 @Value 만 필드에 남겨 일관성 결여.",
      "evidence": "grep -rn '@Value' /payment/infrastructure/adapter/http /payment/infrastructure/messaging/publisher /payment/scheduler 결과 7 파일.",
      "suggestion": "(1) @ConfigurationProperties 객체로 묶거나, (2) 생성자 파라미터에 @Value 부여하여 final 으로 받기. base-url·timeout·batch-size 정도는 후자가 가볍다."
    },
    {
      "severity": "major",
      "checklist_item": "Lombok 도메인 패턴 / 가변 필드",
      "location": "payment-service/.../payment/domain/StockOutbox.java:14-119",
      "problem": "CONVENTIONS.md 'Domain entity (custom builder names)' 패턴(@Getter + @Builder(builderMethodName='allArgsBuilder', buildMethodName='allArgsBuild') + @AllArgsConstructor(access=PRIVATE)) 미적용. hand-rolled constructor + 9개 getter. 또한 processedAt / attempt 가 final 미부여 + markProcessed/incrementAttempt setter 노출 — PaymentEvent / PgOutbox 의 일관 패턴(상태 전이 메서드 노출, 단발 setter 없음) 위반.",
      "evidence": "StockOutbox.java:22-23 'private LocalDateTime processedAt; private int attempt;' final 미부여 + L112-118 setter 메서드.",
      "suggestion": "@Getter + @Builder(builderMethodName='allArgsBuilder', buildMethodName='allArgsBuild') + @AllArgsConstructor(access=AccessLevel.PRIVATE) 패턴 적용. 가변 setter 는 PaymentOutbox.toDone() 같은 도메인 의미를 가진 메서드명만 유지."
    },
    {
      "severity": "major",
      "checklist_item": "중복 코드 (3+ 사용처 동일 로직)",
      "location": "payment-service/.../core/config/AsyncConfig.java:39-48, payment-service/.../payment/scheduler/OutboxWorker.java:51-61, pg-service/.../pg/scheduler/PgOutboxImmediateWorker.java:71-91",
      "problem": "VT executor 이중 래핑 (Context.taskWrapping(Executors.newVirtualThreadPerTaskExecutor()) → ContextExecutorService.wrap(otelWrapped, ContextSnapshotFactory.builder().build())) 가 3곳에 동일 라인으로 복제. T-I~T-J 시리즈의 누적 문제(traceparent 회귀)도 한곳만 누락하여 발생. 향후 wrap 순서·factory 옵션 변경 시 3곳 동시 수정 필요.",
      "evidence": "AsyncConfig.java:42-46, OutboxWorker.java:55-58, PgOutboxImmediateWorker.java:76-81 라인 단위 동일.",
      "suggestion": "ContextPropagationExecutors / ContextAwareVirtualThreadFactory 1개 헬퍼로 추출. 모듈 분리상 공유 jar 가 어렵다면 ADR-19 복제 방식으로 모듈 내 core/common/concurrent/ 위치에 동일 헬퍼 두기."
    },
    {
      "severity": "minor",
      "checklist_item": "static mutable / 중복 인스턴스",
      "location": "payment-service/.../core/common/log/LogFmt.java:19, pg-service/.../pg/core/common/log/LogFmt.java:18, product-service/.../product/core/common/log/LogFmt.java:18, user-service/.../user/core/common/log/LogFmt.java:18, gateway/.../gateway/core/common/log/LogFmt.java:18",
      "problem": "LogFmt.toJson 에서 사용하는 ObjectMapper 가 모듈마다 별도 static instance. JavaTimeModule 등 모듈 등록 정책 변경 시 5곳 일괄 수정 + 누락 리스크. 현 시점에는 LocalDateTime 직렬화 시 InvalidDefinitionException 잠복 가능.",
      "evidence": "grep -rn 'private static final ObjectMapper' 결과 5건.",
      "suggestion": "LogFmt 를 Component 화하여 Spring ObjectMapper 빈 주입, 또는 toJson util 자체를 별도 분리. 최소한 JavaTimeModule 등록 + writeDatesAsTimestamps 비활성 default 부여."
    },
    {
      "severity": "minor",
      "checklist_item": "메서드 책임 분리 / 클래스 길이",
      "location": "payment-service/.../payment/application/usecase/PaymentConfirmResultUseCase.java (전체 331 라인)",
      "problem": "한 클래스가 dedupe lease guard, status 분기, AMOUNT_MISMATCH 검증, stock_outbox 빌드, payload JSON 직렬화 까지 5개 책임 보유. serializeToJson 메서드는 FailureCompensationService.serializeToJson 와 본문 동일 — 중복 코드 + 책임 비대.",
      "evidence": "wc -l 결과 331 라인, PaymentConfirmResultUseCase.java:324-330 / FailureCompensationService.java:100-106 본문 동일.",
      "suggestion": "StockOutboxFactory 같은 application 헬퍼 도입하여 buildStockCommitOutbox + serializeToJson 추출. usecase 는 dedupe·status 분기에만 집중."
    },
    {
      "severity": "minor",
      "checklist_item": "코드 정리 미흡",
      "location": "pg-service/.../pg/application/service/PgVendorCallService.java:225-226",
      "problem": "패키지 import 가능한데도 com.hyoguoo.paymentplatform.pg.infrastructure.converter.AmountConverter.fromBigDecimalStrict(...) FQN 사용. 같은 클래스 다른 곳은 정상 import 처리됨 — 미정리 잔재.",
      "evidence": "위 라인 인용. 동일 클래스에 다른 메서드는 normal import 사용.",
      "suggestion": "정상 import 로 정리. F-01의 application→infrastructure 위반과 묶어서 처리(이동 시 자연 정리)."
    },
    {
      "severity": "minor",
      "checklist_item": "design / 디자인 결함",
      "location": "pg-service/.../pg/application/service/DuplicateApprovalHandler.java:303-308",
      "problem": "buildConfirmedPayload 의 case 'APPROVED' 분기가 IllegalArgumentException throw — '이 분기는 사용 안 됨' 코멘트 + dead code. 호출자가 정확히 'APPROVED' 를 안 넘긴다는 invariant 가 코드로 표현되지 않음.",
      "evidence": "위 라인 인용 + L304 inline 주석.",
      "suggestion": "case 'APPROVED' 분기 제거 후 default 에서 통합 throw, 또는 enum status 로 컴파일 타임 분기 강제."
    },
    {
      "severity": "minor",
      "checklist_item": "Deprecated 잔재",
      "location": "payment-service/.../payment/application/port/out/EventDedupeStore.java:70-73, pg-service/.../pg/application/dto/PgConfirmResult.java:28-37",
      "problem": "@Deprecated 부여 후 청소 일정이 명시되지 않음. forRemoval=true 부여 또는 즉시 제거 필요. 현재는 새 코드가 어느 API 를 써야 할지 표지가 약하다.",
      "evidence": "위 라인 인용.",
      "suggestion": "@Deprecated(forRemoval=true) 부여 후 다음 정리 토픽에서 제거."
    },
    {
      "severity": "minor",
      "checklist_item": "사용처 없는 API",
      "location": "pg-service/.../pg/infrastructure/channel/PgOutboxChannel.java:71-87",
      "problem": "T-J4 도입 후 호출자(OutboxReadyEventHandler.java:39)가 모두 offerNow(Long) 사용. offer(Long) 오버로드는 단순 위임 + 사용처 0건.",
      "evidence": "OutboxReadyEventHandler.java:39 channel.offerNow(...). channel.offer(Long) main grep 결과 0건.",
      "suggestion": "offer(Long) 오버로드 제거 또는 @Deprecated 부여."
    },
    {
      "severity": "minor",
      "checklist_item": "중복 패턴 (sealed type 부재)",
      "location": "pg-service/.../pg/application/service/PgVendorCallService.java:70-99, pg/application/service/PgFinalConfirmationGate.java:79-102, pg/application/service/DuplicateApprovalHandler.java:99-118",
      "problem": "'try 블록 외부 변수 재할당 금지' 규약 우회 패턴(enum + static final class)이 3 클래스에서 거의 동일한 형식으로 반복. Java 21 sealed interface + record 한 줄로 표현 가능한데 매번 hand-roll.",
      "evidence": "PgVendorCallService.GatewayOutcome 4-state, PgFinalConfirmationGate.FcgOutcome 3-state, DuplicateApprovalHandler.VendorQueryOutcome 2-state — 모두 동일 골격.",
      "suggestion": "sealed interface Outcome permits ... + record 패턴으로 통일. 적어도 GatewayOutcome 의 4-state 는 sealed record 가 자연스럽다."
    },
    {
      "severity": "minor",
      "checklist_item": "어댑터 wire DTO 위치",
      "location": "payment-service/.../payment/infrastructure/adapter/http/ProductHttpAdapter.java:122-133, UserHttpAdapter.java:92",
      "problem": "ProductResponse / StockCommandItem / UserResponse record 가 어댑터 클래스 내부에 인라인 정의. StockCommandItem 은 package-private — 외부 접근 가능. 다른 모듈은 dto 하위 패키지 분리 패턴 사용.",
      "evidence": "위 라인 인용.",
      "suggestion": "infrastructure/adapter/http/dto/ 패키지로 분리."
    },
    {
      "severity": "minor",
      "checklist_item": "record canonical invariant 약화",
      "location": "pg-service/.../pg/application/dto/PgConfirmResult.java:14-37",
      "problem": "7-arg record 에 6-arg @Deprecated 보조 생성자 공존. 호출자가 정식 7-arg 와 보조 6-arg 사이 인지 부담. T-A1 도입 시 한꺼번에 7-arg 전환했어야. record 자체 invariant(approvedAtRaw null disallow)도 호출처 책임으로 떠넘겨짐.",
      "evidence": "PgConfirmResult.java:28-37 6-arg 생성자 @Deprecated. PgVendorCallService.buildApprovedPayload(L227-229) 가 호출처에서 null fallback 처리.",
      "suggestion": "보조 생성자 즉시 제거 + 모든 호출처 7-arg 통일. record canonical constructor 에 invariant 검증 추가."
    }
  ],

  "previous_round_ref": null,
  "delta": null,
  "unstuck_suggestion": null
}
```
