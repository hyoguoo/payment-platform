# Checkout 멱등성 구현 플랜

> 작성일: 2026-03-22

## 목표

`POST /checkout` 중복 요청(동일 사용자/상품 조합)을 Caffeine 인메모리 캐시로 감지하여,
신규 생성(201)과 중복 반환(200)을 구분해 응답한다.

## 컨텍스트

- 설계 문서: [docs/topics/CHECKOUT-IDEMPOTENCY.md](../topics/CHECKOUT-IDEMPOTENCY.md)
- 주요 변경 파일:
  - `build.gradle`
  - `payment/application/port/IdempotencyStore.java` (신규)
  - `core/common/util/IdempotencyKeyHasher.java` (신규)
  - `payment/infrastructure/idempotency/IdempotencyStoreImpl.java` (신규)
  - `payment/application/dto/request/CheckoutCommand.java`
  - `payment/application/dto/response/CheckoutResult.java`
  - `payment/presentation/PaymentController.java`
  - `payment/presentation/PaymentPresentationMapper.java`
  - `payment/application/PaymentCheckoutServiceImpl.java`

---

## 진행 상황

<!-- execute 단계에서 각 태스크 완료 시 체크 -->
- [x] Task 1: Caffeine 의존성 추가
- [ ] Task 2: `IdempotencyStore` outbound port 정의
- [ ] Task 3: `IdempotencyKeyHasher` body hash 유틸 구현
- [ ] Task 4: `FakeIdempotencyStore` 테스트 더블 구현
- [ ] Task 5: `IdempotencyStoreImpl` Caffeine 구현체
- [ ] Task 6: `CheckoutCommand` / `CheckoutResult` / 프레젠테이션 레이어 변경
- [ ] Task 7: `PaymentCheckoutServiceImpl` 중복 판정 로직 추가

---

## 태스크

### Task 1: Caffeine 의존성 추가 [tdd=false]

**구현**
- `build.gradle` dependencies 블록에 추가:
  ```
  implementation 'com.github.ben-manes.caffeine:caffeine'
  ```
  (Spring Boot dependency management가 버전 관리)

**완료 기준**
- `./gradlew compileJava` 성공
- 기존 테스트 통과

**완료 결과**
> build.gradle dependencies 블록에 `com.github.ben-manes.caffeine:caffeine` 추가. Spring Boot dependency management가 버전 자동 관리.

---

### Task 2: `IdempotencyStore` outbound port 정의 [tdd=false]

**구현**
- `payment/application/port/IdempotencyStore.java` 인터페이스 생성
  ```java
  Optional<CheckoutResult> getIfPresent(String key);
  void put(String key, CheckoutResult result);
  ```
- `CheckoutResult`를 저장 타입으로 사용 (orderId + totalAmount 포함)

**완료 기준**
- 컴파일 오류 없음
- 기존 테스트 통과

**완료 결과**
> (완료 후 작성) 실제로 어떻게 구현했는지, 계획과 달라진 점, 주요 결정 사항

---

### Task 3: `IdempotencyKeyHasher` body hash 유틸 구현 [tdd=true]

**테스트 (RED)**
- `core/common/util/IdempotencyKeyHasherTest`
  - `hash_동일한_userId와_상품목록_동일한_해시_반환`
  - `hash_상품_순서가_달라도_동일한_해시_반환` — 정렬 후 해싱 검증
  - `hash_다른_userId_다른_해시_반환`
  - `hash_다른_상품목록_다른_해시_반환`
- 사용 패턴: AssertJ `isEqualTo` / `isNotEqualTo`

**구현 (GREEN)**
- `core/common/util/IdempotencyKeyHasher.java` 클래스 (Spring `@Component`)
- `String hash(Long userId, List<OrderedProduct> products)` 메서드
  - 파생식: `userId + ":" + products.stream().sorted(by productId).map(p -> p.getId() + "x" + p.getQuantity()).collect(joining(","))`
  - SHA-256 → hex 문자열 반환
  - `MessageDigest` 사용, checked exception은 `IllegalStateException`으로 래핑

**완료 기준**
- `IdempotencyKeyHasherTest` 전체 통과
- `./gradlew test` 회귀 없음

**완료 결과**
> (완료 후 작성) 실제로 어떻게 구현했는지, 계획과 달라진 점, 주요 결정 사항

---

### Task 4: `FakeIdempotencyStore` 테스트 더블 구현 [tdd=false]

**구현**
- `src/test/java/com/hyoguoo/paymentplatform/mock/FakeIdempotencyStore.java`
- `IdempotencyStore` 구현체
- `HashMap<String, CheckoutResult>` 기반 인메모리 저장 (TTL 없음)
- `getIfPresent`: map에 키 존재하면 `Optional.of()`, 없으면 `Optional.empty()`
- `put`: map에 저장

**완료 기준**
- 컴파일 오류 없음
- 기존 테스트 통과

**완료 결과**
> (완료 후 작성) 실제로 어떻게 구현했는지, 계획과 달라진 점, 주요 결정 사항

---

### Task 5: `IdempotencyStoreImpl` Caffeine 구현체 [tdd=false]

**구현**
- `payment/infrastructure/idempotency/IdempotencyStoreImpl.java`
- `IdempotencyStore` 구현, `@Component`
- 생성자에서 `Caffeine.newBuilder().expireAfterWrite(10, TimeUnit.SECONDS).build()` 로 Cache 초기화
- `getIfPresent`: `cache.getIfPresent(key)` → `Optional.ofNullable()`
- `put`: `cache.put(key, result)`

**완료 기준**
- 컴파일 오류 없음
- 기존 테스트 통과

**완료 결과**
> (완료 후 작성) 실제로 어떻게 구현했는지, 계획과 달라진 점, 주요 결정 사항

---

### Task 6: `CheckoutCommand` / `CheckoutResult` / 프레젠테이션 레이어 변경 [tdd=false]

**구현**
- `CheckoutCommand`: `idempotencyKey` 필드(`String`) 추가
- `CheckoutResult`: `isDuplicate` 필드(`boolean`) 추가
- `PaymentController.checkout()`:
  - `@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey` 파라미터 추가
  - `CheckoutResult.isDuplicate() == false` → `ResponseEntity.status(201).body(...)` 반환
  - `CheckoutResult.isDuplicate() == true` → `ResponseEntity.ok(...)` 반환
- `PaymentPresentationMapper.toCheckoutCommand()`: `idempotencyKey` 포함

**완료 기준**
- 컴파일 오류 없음
- 기존 테스트 통과

**완료 결과**
> (완료 후 작성) 실제로 어떻게 구현했는지, 계획과 달라진 점, 주요 결정 사항

---

### Task 7: `PaymentCheckoutServiceImpl` 중복 판정 로직 추가 [tdd=true]

**테스트 (RED)**
- `PaymentCheckoutServiceImplTest` — 기존 클래스에 케이스 추가, `FakeIdempotencyStore` 사용
  - `testCheckout_신규_요청_201_신규_결과_반환` — isDuplicate=false, IdempotencyStore에 저장됨 검증
  - `testCheckout_중복_요청_200_기존_결과_반환` — 동일 키로 두 번 호출, 두 번째는 isDuplicate=true 반환, `paymentCreateUseCase`는 1번만 호출됨 검증
  - `testCheckout_헤더_없으면_body_hash_파생` — idempotencyKey=null 시 `IdempotencyKeyHasher` 호출됨 검증

**구현 (GREEN)**
- `PaymentCheckoutServiceImpl`에 `IdempotencyStore`, `IdempotencyKeyHasher` 의존성 추가
- 로직:
  1. `idempotencyKey == null` 이면 `IdempotencyKeyHasher.hash(userId, orderedProductList)` 파생
  2. `idempotencyStore.getIfPresent(key)` 존재하면 → `isDuplicate=true`인 `CheckoutResult` 반환
  3. 없으면 → 기존 결제 이벤트 생성 후 → `idempotencyStore.put(key, result)` → `isDuplicate=false`인 `CheckoutResult` 반환

**리팩터 (REFACTOR)**
- 멱등성 키 해석 로직을 private 메서드 `resolveIdempotencyKey(CheckoutCommand)` 로 추출

**완료 기준**
- `PaymentCheckoutServiceImplTest` 전체 통과
- `./gradlew test` 회귀 없음

**완료 결과**
> (완료 후 작성) 실제로 어떻게 구현했는지, 계획과 달라진 점, 주요 결정 사항
