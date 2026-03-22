# TDD 구현 패턴

## RED — 테스트 파일 위치 및 명명

테스트 파일 위치: `src/test/java/com/hyoguoo/paymentplatform/<module>/<layer>/`
테스트 클래스 이름: `{ClassUnderTest}Test`

---

## Domain entity 테스트 패턴

유효/유효하지 않은 상태를 `@ParameterizedTest @EnumSource`로 분리한다.

```java
@ParameterizedTest
@EnumSource(value = PaymentStatus.class, names = {"READY", "IN_PROGRESS"})
void execute_유효한_상태에서_성공(PaymentStatus status) { ... }

@ParameterizedTest
@EnumSource(value = PaymentStatus.class, names = {"DONE", "FAILED", "EXPIRED"})
void execute_유효하지_않은_상태에서_예외(PaymentStatus status) { ... }
```

---

## Use case 테스트 패턴 (Mockito BDD style)

```java
@BeforeEach
void setUp() {
    mockIdempotencyStore = mock(IdempotencyStore.class);
    sut = new CheckoutIdempotencyService(mockIdempotencyStore);
}

@Test
void checkout_중복_요청_기존_orderId_반환() {
    given(mockIdempotencyStore.get("key-123")).willReturn(Optional.of("order-abc"));
    // when / then
}
```

---

## GREEN — Lombok 패턴

| 클래스 유형 | 애너테이션 |
|------------|-----------|
| Service | `@Slf4j @Service @RequiredArgsConstructor` |
| Domain entity | `@Getter @AllArgsConstructor(access = AccessLevel.PRIVATE)` + static factory `create()` |
| Exception | `ErrorCode.of(PaymentErrorCode.XYZ)` 패턴 |

---

## 커밋 메시지

| 단계 | 커밋 |
|------|------|
| RED | `git commit -m "test: <태스크 이름> 실패 테스트 작성"` |
| GREEN | `git commit -m "feat: <태스크 이름> 구현"` |
| REFACTOR | `git commit -m "refactor: <태스크 이름> 정리"` (변경이 있을 때만) |
