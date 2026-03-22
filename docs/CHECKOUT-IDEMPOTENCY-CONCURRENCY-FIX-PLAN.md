# Checkout 멱등성 동시성 수정 구현 플랜

> 작성일: 2026-03-22

## 목표

`IdempotencyStore` 포트를 `getOrCreate(key, supplier)` 단일 원자적 메서드로 재설계하여
TOCTOU 경쟁 조건을 제거한다.

## 컨텍스트

- 설계 문서: [docs/topics/CHECKOUT-IDEMPOTENCY-CONCURRENCY-FIX.md](../topics/CHECKOUT-IDEMPOTENCY-CONCURRENCY-FIX.md)
- 주요 변경 파일:
  - `payment/application/port/IdempotencyStore.java`
  - `payment/infrastructure/idempotency/IdempotencyStoreImpl.java`
  - `payment/application/PaymentCheckoutServiceImpl.java`
  - `test/mock/FakeIdempotencyStore.java`

---

## 진행 상황

- [x] Task 1: `IdempotencyResult<T>` 값 객체 추가
- [x] Task 2: `IdempotencyStore` 포트 재설계
- [ ] Task 3: `FakeIdempotencyStore` 업데이트
- [ ] Task 4: `IdempotencyStoreImpl` Caffeine 재구현
- [ ] Task 5: `PaymentCheckoutServiceImpl` 수정
- [ ] Task 6: Checkout 멱등성 E2E 통합 테스트

---

## 태스크

### Task 1: `IdempotencyResult<T>` 값 객체 추가 [tdd=false]

**구현**
- 위치: `payment/application/dto/IdempotencyResult.java`
- `@Getter @RequiredArgsConstructor(access = PRIVATE)` 적용
- 필드: `T value`, `boolean duplicate`
- 정적 팩토리: `IdempotencyResult.hit(T value)`, `IdempotencyResult.miss(T value)`

```java
public class IdempotencyResult<T> {
    private final T value;
    private final boolean duplicate;

    public static <T> IdempotencyResult<T> hit(T value) { ... }
    public static <T> IdempotencyResult<T> miss(T value) { ... }
}
```

**완료 기준**
- 컴파일 오류 없음

**완료 결과**
> `payment/application/dto/IdempotencyResult.java` 생성. `hit()`/`miss()` 정적 팩토리로 캐시 히트 여부를 표현.

---

### Task 2: `IdempotencyStore` 포트 재설계 [tdd=false]

**구현**
- 위치: `payment/application/port/IdempotencyStore.java`
- 기존 `getIfPresent`, `put` 제거
- 신규: `IdempotencyResult<CheckoutResult> getOrCreate(String key, Supplier<CheckoutResult> creator)`

```java
public interface IdempotencyStore {
    IdempotencyResult<CheckoutResult> getOrCreate(String key, Supplier<CheckoutResult> creator);
}
```

**완료 기준**
- 컴파일 오류 없음 (구현체들이 아직 컴파일 오류 상태여도 무방 — 다음 태스크에서 해결)

**완료 결과**
> `getIfPresent + put` 제거, `getOrCreate(key, supplier)` 단일 메서드로 교체. 반환 타입 `IdempotencyResult<CheckoutResult>`.

---

### Task 3: `FakeIdempotencyStore` 업데이트 [tdd=false]

**구현**
- 위치: `test/mock/FakeIdempotencyStore.java`
- `HashMap` → `ConcurrentHashMap`으로 교체
- `getOrCreate` 구현: `ConcurrentHashMap.computeIfAbsent`로 원자성 확보
  - 키가 없으면 `creator.get()` 호출 후 저장 → `miss` 반환
  - 키가 있으면 기존 값 반환 → `hit` 반환

```java
public IdempotencyResult<CheckoutResult> getOrCreate(String key, Supplier<CheckoutResult> creator) {
    boolean[] created = {false};
    CheckoutResult result = store.computeIfAbsent(key, k -> {
        created[0] = true;
        return creator.get();
    });
    return created[0] ? IdempotencyResult.miss(result) : IdempotencyResult.hit(result);
}
```

**완료 기준**
- 컴파일 오류 없음
- 기존 테스트 통과

**완료 결과**
> (완료 후 작성)

---

### Task 4: `IdempotencyStoreImpl` Caffeine 재구현 [tdd=false]

**구현**
- 위치: `payment/infrastructure/idempotency/IdempotencyStoreImpl.java`
- `getOrCreate` 구현: `Cache.get(key, loader)`로 원자성 확보
  - Caffeine은 동일 키에 대해 loader를 한 번만 실행 보장, 다른 스레드는 대기
  - `loaderCalled` 플래그로 hit/miss 구분

```java
public IdempotencyResult<CheckoutResult> getOrCreate(String key, Supplier<CheckoutResult> creator) {
    boolean[] loaderCalled = {false};
    CheckoutResult result = cache.get(key, k -> {
        loaderCalled[0] = true;
        return creator.get();
    });
    return loaderCalled[0] ? IdempotencyResult.miss(result) : IdempotencyResult.hit(result);
}
```

**완료 기준**
- 컴파일 오류 없음
- 기존 테스트 통과

**완료 결과**
> (완료 후 작성)

---

### Task 5: `PaymentCheckoutServiceImpl` 수정 [tdd=true]

**테스트 (RED)**
- 파일: `PaymentCheckoutServiceImplTest.java` 기존 테스트 수정 + 신규 추가
- 기존 테스트: 새 포트 계약에 맞게 수정 (FakeIdempotencyStore의 `getOrCreate` 사용)
- 신규 테스트:
  - `checkout_신규_요청_isDuplicate_false_반환` — supplier 호출, miss 반환
  - `checkout_중복_요청_isDuplicate_true_반환` — supplier 1회만 호출, hit 반환
  - `checkout_헤더_없으면_hasher_호출하여_키_파생` — idempotencyKey null 시 hasher 호출

**구현 (GREEN)**
- 위치: `payment/application/PaymentCheckoutServiceImpl.java`
- `getIfPresent + put` 분리 호출 → `getOrCreate` 단일 호출로 교체
- supplier 내에서 유저 조회 + 상품 조회 + 결제 이벤트 생성 수행
- `IdempotencyResult.isDuplicate()`로 `CheckoutResult.isDuplicate` 설정

```java
@Transactional
public CheckoutResult checkout(CheckoutCommand checkoutCommand) {
    String idempotencyKey = resolveIdempotencyKey(checkoutCommand);

    IdempotencyResult<CheckoutResult> idempotencyResult = idempotencyStore.getOrCreate(
        idempotencyKey,
        () -> createCheckoutResult(checkoutCommand)
    );

    return CheckoutResult.builder()
            .orderId(idempotencyResult.getValue().getOrderId())
            .totalAmount(idempotencyResult.getValue().getTotalAmount())
            .isDuplicate(idempotencyResult.isDuplicate())
            .build();
}
```

**완료 기준**
- `PaymentCheckoutServiceImplTest` 전체 통과
- `./gradlew test` 회귀 없음

**완료 결과**
> (완료 후 작성)

---

### Task 6: Checkout 멱등성 E2E 통합 테스트 [tdd=true]

**테스트 (RED)**
- 파일: `payment/presentation/PaymentCheckoutConcurrencyIntegrationTest.java`
- 베이스: `IntegrationTest` 확장 (Testcontainers MySQL + MockMvc + `@Sql("/data-test.sql")`)
- 외부 포트(`TossOperator`, product/user 조회)는 기존 `Fake` 구현체 사용
- 검증 시나리오:
  - `checkout_동일_키_동시_요청_결제이벤트_1개만_생성` — `ExecutorService`로 N개 스레드 동시 요청,
    DB의 `payment_event` 레코드가 정확히 1개인지 확인
  - `checkout_동일_키_순차_요청_두번째는_200_반환` — 첫 요청 201, 두 번째 요청 200 확인
  - `checkout_다른_키_동시_요청_각각_독립_생성` — 서로 다른 키의 요청은 서로 간섭 없이 각자 생성

**구현 (GREEN)**
- 테스트가 통과하도록 이미 완성된 구현 (Task 1~5) 그대로 사용
- 추가 구현 없음 — 테스트 코드만 작성

**완료 기준**
- `PaymentCheckoutConcurrencyIntegrationTest` 전체 통과
- `./gradlew test` 회귀 없음

**완료 결과**
> (완료 후 작성)

---

## 알려진 한계

- **서버 재시작 시나리오**: Caffeine TTL 내 재시작 후 같은 키로 요청 시 새 요청으로 처리.
  단일 인스턴스 벤치마크 환경에서 실질적 발생 없음.
- **T1 롤백 시나리오**: T1의 loader가 결과를 캐시에 올린 후 T1 트랜잭션이 롤백되면,
  T2가 이미 T1의 orderId를 반환한 상태일 수 있음. Caffeine 단일 JVM 환경의 알려진 한계.
