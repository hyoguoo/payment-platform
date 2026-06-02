# Coding Conventions — Code Style

> Lombok/Builder, Record DTO, Naming, 주석/문서화, 안티패턴 회피.

## Lombok 사용 패턴

**Spring Bean 클래스** — 일반적인 use case / service / adapter:
```java
@Service
@RequiredArgsConstructor
public class PaymentConfirmResultUseCase {
    private final StockCachePort stockCachePort;
    private final QuarantineCompensationHandler quarantineCompensationHandler;
    // ...
}
```
- `@RequiredArgsConstructor` + `private final` 필드 → 생성자 주입 (Spring 4.x 부터 단일 생성자 자동 주입)
- `@Autowired` 명시 금지

**도메인 Entity / Value Object**:
```java
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
public class PaymentEvent {
    private Long id;
    private PaymentEventStatus status;
    // 변경 메서드는 도메인 행위로만 — setter 금지
    // 시각은 Instant 인자 주입 (도메인은 Clock/now() 직접 호출 금지 — PITFALLS §6)
    public void done(Instant approvedAt, Instant lastStatusChangedAt) { ... }
}
```
- `@Setter` 금지. 상태 변경은 도메인 메서드로
- JPA Entity 는 `@NoArgsConstructor(access = PROTECTED)` 로 외부 생성 차단

### Builder 룰 — payment-service + pg-service 정합

**도메인 POJO 표준 패턴**:
```java
@Getter
@Builder(builderMethodName = "allArgsBuilder", buildMethodName = "allArgsBuild")
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class PgInbox {
    private Long id;
    private PgInboxStatus status;
    // ...

    /**
     * 외부 호출자는 factory method(create*, of, ofWithId)만 사용.
     * allArgsBuilder() 직접 호출 금지 — factory 내부 캡슐화 전용.
     */
    public static PgInbox createPending(String orderId, BigDecimal amount) {
        return allArgsBuilder()
                .orderId(orderId)
                .status(PgInboxStatus.PENDING)
                .amount(amount)
                // ...
                .allArgsBuild();
    }
}
```

**factory only 노출 룰**:
- 외부 호출자는 정적 factory 메서드만 호출 (예: `PgInbox.createPending(...)` / `PgInbox.createDirectInProgress(...)` / `PaymentOutbox.createFromCommand(...)`)
- `allArgsBuilder()` 는 factory 내부 캡슐화 전용 — 외부 직접 호출 금지
- 컴파일러 강제 불가 (Lombok 제약) — JavaDoc + code review 로 강제

**시나리오 의도 보존**:
- factory 시그니처가 도메인 시나리오를 명시 (예: 정상 PENDING 진입 / 보정 직접 진입 / DB 복원 / test 픽스처)
- builder 외부 노출 시 시나리오 우회 가능 — 금지 이유

**적용 위치**:
- payment-service: `PaymentOutbox`, `Payment`, ... (`StockOutbox` 는 폐기됨)
- pg-service: `PgInbox`, `PgOutbox`

**mutable 필드 변경 룰**:
- 상태 전이 대상 필드는 도메인 메서드로만 변경 (예: `PgInbox.markInProgress()`, `PgOutbox.markDone()`)
- builder 는 build 시점만 — setter 노출 없음

**Record DTO**:
```java
public record ConfirmedEventMessage(
    String orderId,
    String eventUuid,
    String status,
    Long amount,
    String approvedAt
) { ... }
```
- Kafka payload, response DTO 는 record 우선

## Naming

| 카테고리 | 규칙 | 예 |
|---|---|---|
| Use case | `<Action><Subject>UseCase` | `PaymentConfirmResultUseCase`, `StockCommitUseCase` |
| Service (보조) | `<Subject>Service` | `OutboxRelayService`, `PgInboxPendingService` |
| Use case 입력 포트 | `<Verb>UseCase` 인터페이스 | `PaymentCommandUseCase` |
| 출력 포트 | `<Subject>Port` | `StockCachePort`, `PaymentConfirmPublisherPort` |
| 출력 포트 어댑터 | `<Subject><Tech>Adapter` | `StockCacheRedisAdapter` |
| Kafka 메시지 record | `<Subject>EventMessage` (수신) / `<Subject>EventPayload` (발행) | `ConfirmedEventMessage`, `ConfirmedEventPayload` |
| 이벤트 (Spring ApplicationEvent) | `<Subject>RequestedEvent` | `StockCommitRequestedEvent` |
| Listener | `<Subject>Listener` 또는 `<Subject>EventHandler` | `OutboxImmediateEventHandler`, `InboxReadyEventHandler` |
| Scheduler | `<Subject>Worker` | `OutboxWorker`, `PgOutboxImmediateWorker` |
| Fake (테스트 전용) | `Fake<Subject><Type>` | `FakeStockCachePort` |
| Test class | `<Subject>Test` (단위) / `<Subject>ContractTest` / `<Subject>MdcPropagationTest` | `PaymentConfirmResultUseCaseTest` |

## 주석 / 문서화

코드 주석은 **"왜"만 남기고 "무엇/어떻게"는 코드로 말한다.** 다음은 금지:

- **내부 작업·결정 ID 금지**: `D7`, `PET-8`, `SCR-8`, `DR-3`, `PCS-9`, `CBA-9`, `§6` 같은 토픽/태스크/결정 식별자를 주석·Javadoc·로그 문자열·`@DisplayName` 에 박지 않는다. 작업이 끝나면 의미를 잃고 노이즈만 남는다. 코드만 읽는 사람이 docs 를 열어야 이해되면 실패다.
- **변경 이력 서사 금지**: "~ 재작성", "EOS 전환 후", "구 순서 → 새 순서로 교체", "통합 후 제거됨" 같은 과거 경위. 이력은 git 이 담당한다.
- **깨지기 쉬운 외부 참조 금지**: "위키 line 141", "CONCERNS.md L6", "DECISION §6" 같은 줄/번호 참조. 대상이 바뀌면 썩는다 — 그 내용의 **의미**를 한 줄로 풀어쓴다.
- **메타 지시 / 자명한 주석 금지**: "implementer 주의", "변경 시 영향 경고", `// 보상 먼저` 처럼 코드가 이미 말하는 것.

남기는 것:
- 코드만으로 알 수 없는 **불변식 / race window / 호출 순서 이유 / AOP self-invocation 함정** 등. 단 1~2줄로 압축하고 인공 ID 없이 자연어로.
- 테스트의 `@DisplayName` / 주석도 동일 — `TC1:` 같은 케이스 번호 라벨은 떼고 설명만 남긴다(테스트 메서드명 식별자는 참조 안전을 위해 보존).

## Try 블록 패턴

**`try` 블록 안에서 외부 변수 재할당 금지**:
```java
// ❌ 금지
ResultType result = null;
try {
    result = service.call();
} catch (Exception e) { /* ... */ }
process(result);

// ✅ 권장 — private 메서드 추출
private ResultType doSafely() {
    try {
        return service.call();
    } catch (Exception e) {
        // ...
        throw new ...;
    }
}
```

이유: 외부 변수 재할당은 catch 분기에서 null/sentinel 처리가 필요하고, 코드 readability 가 떨어진다. private 메서드 추출로 반환값에 의도 표현.

## 안티패턴 회피

이미 정착한 룰: `@Autowired` 필드 주입 금지(생성자 주입), `catch (Exception)` swallow 금지(→ [error-logging.md](error-logging.md)), `try` 블록 외부 변수 재할당 금지(위 섹션), `var` 키워드 금지.

추가 룰:

- **도메인 상태값은 magic string 대신 enum**: `"APPROVED".equals(...)` / `switch ("APPROVED")` 금지.
  단 **Kafka 메시지 record 의 status 필드는 String 으로 유지**해 와이어 포맷·역직렬화 계약을 보존하고, 내부 분기에서만 enum 으로 변환한다 (`ConfirmStatus.from(raw)`). 알 수 없는 값은 `UNKNOWN` 으로 흡수해 default 분기 동작을 보존하고, 발행 측은 `enum.name()` 으로 채운다.
- **`new ObjectMapper()` 직접 생성 지양 — DI 주입**: 단 직렬화 포맷이 cross-service wire 계약에 영향하면 신중히 판단한다. 예: `PaymentConfirmResultUseCase` 의 발행 매퍼는 `Instant` 를 epoch 숫자로 직렬화하므로(Jackson 기본 + JavaTimeModule), Spring 기본 빈(ISO 문자열)으로 바꾸면 발행 포맷이 달라져 소비 측 계약이 흔들린다 → 의도적으로 독립 매퍼 유지. self round-trip(자기가 쓰고 읽음)이거나 직렬화 대상에 시간 필드가 없으면 안전하게 주입한다. `LogFmt` 의 static `ObjectMapper` 는 정적 유틸이라 DI 대상이 아니다.
- **불필요한 한 줄 위임 메서드 금지**: 다른 메서드를 한 줄 호출만 하고 끝나는 래퍼는 합친다.
