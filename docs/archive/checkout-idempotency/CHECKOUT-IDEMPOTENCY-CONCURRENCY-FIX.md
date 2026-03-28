# Checkout 멱등성 동시성 수정 설계

> 최종 수정: 2026-03-22

---

## 문제 정의

코드 리뷰에서 발견된 TOCTOU(Time-Of-Check-Time-Of-Use) 경쟁 조건.
현재 `IdempotencyStore` 포트가 `getIfPresent + put`을 별도 메서드로 분리하여,
두 호출 사이의 원자성이 보장되지 않는다.

```java
// 현재 구현 — TOCTOU 발생
Optional<CheckoutResult> cached = store.getIfPresent(key);  // T1: miss
                                                             // T2: miss (동시에!)
if (cached.isPresent()) { return isDuplicate; }

createPaymentEvent();  // T1: 생성
                       // T2: 생성 (중복!)
store.put(key, result);
```

`@Transactional`이 있더라도 Caffeine 캐시는 트랜잭션 밖에 존재하므로 원자성이 보장되지 않는다.

---

## 설계 옵션 비교

### Option A: `getOrCreate(key, supplier)` 원자적 단일 메서드

```java
IdempotencyResult<CheckoutResult> getOrCreate(String key, Supplier<CheckoutResult> creator);
```

Caffeine의 `Cache.get(key, mappingFunction)`은 동일 키에 대해 loader를 한 번만 실행 보장.
T2는 T1의 loader가 끝날 때까지 블록된 후 캐시된 결과를 수신한다.

- **장점:** 포트 계약이 원자적 의미를 표현, 호출부에서 원자성 신경 불필요, 구현체 교체 용이
- **단점:** 동일 키 동시 요청에서 T2가 블록됨 (단, 동일 키에만 국한)

### Option B: DB Unique Constraint + 예외 처리

`payment_event` 테이블에 `idempotency_key` 유니크 제약 추가.
중복 INSERT 시 `DataIntegrityViolationException`을 catch하여 기존 결과 반환.

- **장점:** 분산 환경 및 서버 재시작 후에도 안전, 영구 저장
- **단점:** 스키마 변경 필요, 예외 기반 흐름 제어, TTL 관리 별도 필요

### Option C: Fail-fast (선점 시 즉시 에러)

key가 이미 처리 중이면 즉시 409 반환.

- **장점:** 블로킹 없음
- **단점:** 클라이언트가 retry 타이밍을 직접 판단해야 함, UX 저하

---

## 결정 사항

| 항목 | 결정 | 이유 |
|------|------|------|
| 포트 인터페이스 | `getOrCreate(key, supplier)` | 원자적 의미를 포트 계약으로 표현, 구현체가 각자 방식으로 보장 |
| Caffeine 구현 | `Cache.get(key, loader)` | 동일 키 loader 1회 실행 보장, JVM 내 완전 안전 |
| 블로킹 허용 여부 | 허용 (동일 키 한정) | 결제 시스템에서 중복 방지 > 수십ms 블로킹 비용 |
| Fail-fast 방식 | 채택 안 함 | 클라이언트 retry 복잡도 증가, UX 저하 |
| DB Unique Constraint | 현재 추가 안 함 | 단일 JVM + Caffeine으로 충분 |
| 결과 래퍼 | `IdempotencyResult<T>` (value + isDuplicate) | 캐시 히트 여부를 호출부에 전달 |

---

## 블로킹 범위 및 Trade-off

```
key="abc" T1, T2 동시 요청 → T2만 T1 대기 (블로킹)
key="xyz" T3 요청           → T1, T2와 무관하게 즉시 실행
```

- 블로킹은 **동일 idempotency key를 가진 요청 사이에만** 발생
- Checkout 자체가 DB 조회·생성 포함 수백ms → 수십ms 블로킹은 허용 가능한 수준
- 동일 키 동시 요청 = 클라이언트 중복 클릭 or 재시도 → 비정상 패턴이므로 직렬화가 맞음

---

## 구현체별 원자성 보장 방식

| 구현체 | 원자성 방법 | 적합 환경 |
|--------|-----------|----------|
| `CaffeineIdempotencyStore` | `Cache.get(key, loader)` | 단일 JVM |
| `RedisIdempotencyStore` (미래) | `SETNX` 선예약 → 실행 → 결과 저장 | 다중 인스턴스 |

Service 코드는 포트 계약(`getOrCreate`)만 의존하므로 구현체 교체 시 변경 없음.

---

## 영향 범위

- **변경:**
  - `IdempotencyStore` 포트 — `getIfPresent + put` → `getOrCreate(key, supplier)`
  - `IdempotencyStoreImpl` — `Cache.get(key, loader)` 방식으로 재구현
  - `PaymentCheckoutServiceImpl` — `getOrCreate` 호출로 단순화
  - `CheckoutResult` — `isDuplicate` 필드를 `IdempotencyResult`로 분리 검토
- **신규:**
  - `IdempotencyResult<T>` — value + isDuplicate 래퍼
- **무관:**
  - `FakeIdempotencyStore` 내부 구현 (`ConcurrentHashMap`으로 교체 필요 — 별도 CRITICAL 항목)

---

## 제외 범위

- **Redis 구현체 추가:** 포트 인터페이스 확정 후 별도 작업
- **DB Unique Constraint:** Redis 전환 시 최후 방어선으로 추가 권장, 현재는 범위 외
- **서버 재시작 시나리오:** Caffeine TTL 내 재시작 후 중복 요청 — 단일 인스턴스 벤치마크 환경에서 실질적 발생 없음, 설계 문서에 한계로 명시
