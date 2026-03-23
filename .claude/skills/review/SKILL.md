---
name: review
description: >
  payment-platform 프로젝트의 변경 사항을 구조적으로 코드 리뷰한다.
  "리뷰", "코드 리뷰", "review", "check my changes", "뭐 문제 없어?", "looks good?" 등
  변경 사항 검토를 요청할 때 반드시 이 스킬을 사용한다.
  캐주얼한 요청이더라도 아키텍처, 컨벤션, 테스트, 결제 도메인 리스크를 망라한 구조적 리뷰를 실행한다.
---

# 코드 리뷰

payment-platform 프로젝트의 변경 사항을 아키텍처 규칙, 컨벤션, 테스트 전략, 결제 도메인 리스크 관점에서 구조적으로 검토한다.

## 리뷰 준비

`git diff HEAD`와 `git status`로 변경 파일을 파악한 뒤 각 파일을 읽어 내용을 확인한다. 리뷰 대상을 이해하지 않은 채 체크리스트만 적용하지 않는다.

---

## 아키텍처

이 프로젝트는 **포트-어댑터 아키텍처**를 사용한다. 허용되는 의존 방향:

```
Presentation → Application → Domain ← Infrastructure
```

**확인 항목:**
- **레이어 침범** — Infrastructure 클래스가 Domain에 임포트되거나, Domain이 Application을 임포트하거나, Presentation이 포트 인터페이스를 거치지 않고 유스케이스를 직접 호출하는 경우
- **포트 위치** — 포트(인터페이스)는 `application/port/` 또는 `presentation/port/`에 위치해야 하며, 구현체는 `infrastructure/` 또는 `application/`에 위치
- **모듈 간 직접 임포트** — `payment`, `paymentgateway`, `product`, `user` 모듈은 포트 인터페이스 또는 `*InternalReceiver`를 통해서만 통신해야 하며, 다른 모듈의 구현 클래스를 직접 임포트하면 안 됨

---

## 컨벤션

### Lombok
- 서비스/유스케이스 클래스의 생성자 주입에는 `@RequiredArgsConstructor` 사용
- 도메인 엔티티에 `@Data` 사용 금지 — `@Getter`만 사용하고, 상태 변경은 도메인 메서드로
- 도메인 엔티티는 `@AllArgsConstructor(access = AccessLevel.PRIVATE)` + `@Builder(builderMethodName = "allArgsBuilder", buildMethodName = "allArgsBuild")`로 `create()` 정적 팩토리를 통해서만 생성
- 상태 없는 유틸리티/헬퍼 클래스는 `@NoArgsConstructor(access = AccessLevel.PRIVATE)`

### 네이밍
- 정적 팩토리 메서드: `create()` 또는 `of()` — 생성자 직접 호출 금지
- 예외 생성: `PaymentStatusException.of(PaymentErrorCode.XYZ)` — `new` 사용 금지
- 테스트 모의 변수명: `mock` 접두사 (예: `mockPaymentEventRepository`)
- 상수: `UPPER_SNAKE_CASE`

### 예외 처리
- `catch (Exception e)` 나체 사용 금지 — confirm/compensation 흐름의 최종 catch-all에서 명시적으로 `handleUnknownFailure`로 라우팅하는 경우만 허용
- 도메인 객체는 상태 검증 후 타입화된 도메인 예외를 던지고, 서비스 레이어는 그것을 삼키지 않음

### 반환값
- `null` 반환 금지 — `Optional`을 사용하거나 타입화된 예외를 던짐 (`orElseThrow()` 패턴)

### 로깅
- `LogFmt` 헬퍼를 항상 사용 — 원시 `log.info("...")` 문자열 보간 금지
- 패턴: `LogFmt.warn(log, LogDomain.PAYMENT, EventType.EXCEPTION, e::getMessage);`
- 불필요한 문자열 생성을 막기 위해 레벨 체크로 래핑: `if (logger.isInfoEnabled()) { ... }`

---

## 테스트

### 레이어별 테스트 스타일

| 레이어 | 스타일 | 규칙 |
| --- | --- | --- |
| Domain | 순수 Java, 모의 없음 | `@ParameterizedTest @EnumSource`로 모든 상태 전환 커버 |
| Application / UseCase | `@BeforeEach`에 Mockito 모의 (`@MockBean` 사용 금지) | 가능하면 실제 협력자 사용, 포트만 모의 |
| Integration | Testcontainers MySQL + MockMvc | 외부 포트(Toss, product, user)는 `FakeX` 구현체 사용 |

### Fake vs Mock 정책
- 포트 인터페이스(레포지토리, 외부 게이트웨이)는 `src/test/.../mock/`에 **Fake 구현체** 사용 (예: `FakePaymentEventRepository`, `FakeTossOperator`)
- Fake 구성에 과도한 셋업이 필요한 서비스 레이어 단위 테스트에는 Mockito 허용
- 레포지토리 인터페이스에 `@MockBean` 사용 금지 — Fake 전략을 우회하고 더 느림

### Testcontainers 패턴
- 통합 테스트는 Singleton Container 패턴을 사용해 컨텍스트 간 컨테이너를 공유 (`withReuse(true)`)
- Spring Context를 불필요하게 분리하지 않도록 `@TestConfiguration`보다 `ReflectionTestUtils`를 우선 사용

### 테스트 네이밍
- 클래스: `{테스트대상클래스}Test`
- 메서드: `{메서드명}_{시나리오}` (예: `execute_Success`, `confirm_InvalidPaymentKey_ThrowsException`) **또는** `@DisplayName` 한국어
- 두 스타일 모두 유효하나 같은 클래스에서 혼용 금지

### 커버리지
- 도메인 엔티티의 새 public 메서드는 유효/무효 상태 전환을 모두 커버하는 테스트 필요
- 새 유스케이스 분기는 최소 하나의 단위 테스트 필요

---

## 결제 도메인 리스크

결제 코드는 일반 CRUD보다 실패 비용이 크다. 아래 항목은 특히 꼼꼼히 확인한다.

**1. 보상 트랜잭션 멱등성**
`increaseStockForOrders`와 `decreaseStockForOrders`는 이중 실행을 방어해야 한다. 보상 경로의 상태 변이 전에 존재 여부 검증이 있는지 확인한다.

**2. 레이스 컨디션**
`executeStockDecreaseWithJobCreation`은 비관적 락을 사용하지만, `executePayment` (READY → IN_PROGRESS)는 별도 트랜잭션이다. 이 두 트랜잭션 사이에 단계를 추가하는 코드는 레이스 윈도우를 넓힌다.
멱등성 체크가 필요한 곳에서는 `Cache.get(key, loader)` 패턴처럼 원자적 연산을 사용해야 한다 — `getIfPresent` + `put` 분리 패턴은 TOCTOU 취약점이 된다.

**3. PII/시크릿 로그 노출**
`paymentKey`와 `orderId`는 평문 로그 메시지에 포함되면 안 된다. 새 로그 구문이 이 필드를 노출하지 않는지 확인한다. `MaskingPatternLayout`은 안전망이지 의도적 로깅을 허용하는 수단이 아니다.

**4. 상태 머신 위반**
PaymentEvent 전환은 반드시 아래 규칙을 따라야 한다:
```
READY → IN_PROGRESS → DONE
              ↓
           FAILED
              ↓
           UNKNOWN → (재시도) → IN_PROGRESS
READY → EXPIRED
```
새 전환은 도메인 엔티티 메서드에서 검증되고 `@ParameterizedTest @EnumSource`로 커버되어야 한다.

**5. `existsByOrderId` 가드**
결제 이벤트 생성처럼 멱등성이 필요한 쓰기 작업은 INSERT 전에 기존 레코드 존재 여부를 확인해야 한다.

**6. `ALREADY_PROCESSED_PAYMENT` 처리**
이 Toss 에러 코드를 성공으로 처리하는 코드는 로컬 DB 상태가 Toss 상태와 일치하는지 검증해야 한다 — 맹목적으로 성공 처리하면 안 된다.

**7. 광범위한 `Exception` catch**
새 catch-all 블록은 반드시 `handleUnknownFailure`로 명시적 라우팅이 있어야 한다. 에러를 조용히 삼키는 broad catch는 즉시 플래그.

---

## 출력 형식

각 발견 항목은 아래 형식으로 작성한다 (설명은 한국어로):

```
[CRITICAL] path/to/File.java:42 — 한 문장 설명
  이유: 영향에 대한 간략한 설명
  수정: 구체적인 수정 방향

[WARNING] path/to/File.java:15 — 한 문장 설명
  이유: ...
  수정: ...

[INFO] path/to/File.java:8 — 관찰 사항 (조치 불필요)
```

## 심각도 기준

- **CRITICAL** — 기능을 깨뜨리거나, 아키텍처 계약을 위반하거나, 데이터 손상/보안 리스크를 만드는 문제
- **WARNING** — 품질을 저하시키거나, 컨벤션에서 벗어나거나, 기술 부채를 만드는 문제
- **INFO** — 스타일 메모 또는 인지를 위한 관찰 사항; 조치 불필요

## 마무리 요약

리뷰 끝에 반드시 아래를 추가한다:

```
---
요약: CRITICAL N건, WARNING N건, INFO N건
판정: PASS (CRITICAL 0건) | FAIL (CRITICAL N건 — 머지 전 반드시 수정)
```
