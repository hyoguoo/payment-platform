# 운영 신호 정합과 규칙 자동 검출 정비 구현 플랜

> 작성일: 2026-08-03

## 요약 브리핑

### Task 목록

| # | 내용 | 성격 |
|---|---|---|
| 1 | 전이 주체를 호출 스택 짐작에서 전이 지점 선언으로 전환 | 코드, 도메인 |
| 2 | 발행 행 삽입을 충돌 없는 방식으로 바꾸고 확인 조회에 블로킹 잠금을 건다 | 코드, 도메인 |
| 3 | 중복 재진입 예외를 만들어 재고 미회수 경보에서 분리 | 코드, 도메인 |
| 4 | 실제 데이터베이스로 동시 승인 경합을 50회 반복 검증 | 테스트, 도메인 |
| 5 | 체크아웃 응답 본문에 중복 여부 복원 | 코드 |
| 6 | 재시도 백오프 회차 정정, 좀비 회수와의 관계 설정에 명시 | 코드, 도메인 |
| 7 | 재시도 워커에 자체 추적 구간과 주문 번호 부여 | 코드 |
| 8 | 로그 마스킹 계층 도입 (payment 먼저) | 코드, 도메인 |
| 9 | 마스킹 계층 나머지 네 서비스 확산 | 코드, 도메인 |
| 10 | 벤더 응답 원문 로깅 길이 제한 | 코드, 도메인 |
| 11 | 문자열로 판정 가능한 스타일 3규칙 검출과 기준선 억제 | 빌드 |
| 12 | 구조 판정이 필요한 스타일 2규칙 검출 | 빌드 |
| 13 | 지침 문서 검사 스크립트 CI 편입 | CI |
| 14 | 리뷰 체크리스트 낡은 참조 정정 | 문서 |
| 15 | 결제 흐름 문서 다이어그램 표기 정리 | 문서 |

### 변경 후 전체 플로우

```mermaid
flowchart TD
    A[결제 승인 요청] --> B[재고 선차감]
    B --> C[확정 트랜잭션 시작, 결제 상태 전이]
    C --> D[발행 행 삽입, 이미 있으면 넘어감]
    D --> E{반영된 행이 있는가}
    E -->|있음| F[정상 확정]
    E -->|없음| G[주문 번호로 블로킹 잠금 읽기]
    G -->|기존 행 있음| H[중복 재진입 예외, 경보 제외]
    G -->|기존 행 없음| I[저장 실패, 종전대로 경보]
    H --> J[트랜잭션 롤백, 상태 전이도 되돌아감]
    I --> J
    F --> K[상태 전이 지표에 실제 주체 기록]
    F --> L[승인 명령을 pg 로 전달]
    L --> M{벤더 호출 결과}
    M -->|성공| N[승인 결과 회신]
    M -->|실패| O[다음 재시도 예약, 대기 2초 6초 18초]
    O --> P{재시도 한도}
    P -->|남음| M
    P -->|소진| Q[격리 전이, 안전 종결 전 벤더 상태 확인 필요]
    O -.최대 22.5초.-> R[좀비 회수 타임아웃 60초와 겹치지 않음]
    M --> S[워커 자체 추적 구간에 주문 번호 기록]
    N --> T[모든 로그는 출력 직전 마스킹 통과]
```

### 핵심 결정 → Task 매핑

- 전이 주체 선언 전환 → Task 1
- 중복 승인 경보 분리, 중복 판정 근거, 확인 조회 잠금 방식 → Task 2, 3, 4
- 체크아웃 응답 → Task 5
- 재시도 백오프, 좀비 회수 타임아웃 유지 → Task 6
- 재시도 워커 추적 → Task 7
- 로그 마스킹 → Task 8, 9
- 벤더 응답 원문 로깅 → Task 10
- 코드 스타일 강제, 기존 위반 기준선 → Task 11, 12
- 지침 문서 검사 → Task 13
- 체크리스트 정정 → Task 14
- 다이어그램 표기 → Task 15
- 리뷰 강도 대조 → 리뷰 처리 절 (ship 단계)
- 커밋 단위 → 컨텍스트 절

### 트레이드오프 / 후속 작업

- 재시도 창이 78초에서 26초로 줄어, 그 사이 길이의 벤더 장애에서는 전에 회복했을 결제가 격리로 남는다. 격리 이탈 경로는 벤더 상태를 다시 묻지 않는 편도 종결뿐이고 환불 경로가 없다 — 이 토픽에서 닫지 않으며, 격리 복구와 환불은 이미 후속 항목으로 올라 있다.
- 마스킹은 모든 로그 줄에 정규식이 도는 비용을 새로 만든다. 지금 새는 경로가 없는 상태에서 까는 안전망이다.
- 스타일 검출의 기존 위반은 억제 목록으로 덮고 규모만 숫자로 남긴다. 실제 수정은 다음으로 미룬다.
- Task 12는 기존 위반 규모가 미지수라, 규모 파악 결과에 따라 구현 수단(구조 검사 라이브러리 도입 여부)이 갈린다.

---

## 목표

지표·로그·추적이 실제 동작과 어긋나던 자리를 맞추고, 재시도 대기를 설계 값으로 되돌려 좀비 회수와의 겹침을 없애며, 사람 눈으로만 지키던 규칙에 검출 장치를 깐다. 15개 태스크가 모두 끝나고 전체 회귀가 통과하면 완료다.

## 컨텍스트

- 설계 문서: `docs/topics/SIGNAL-AND-GUARDRAIL-SWEEP.md`
- 이슈/브랜치: #132
- 커밋은 태스크 단위로 분리하고 PR은 하나로 낸다 (설계 "커밋 단위" 결정)
- 주요 변경 파일
  - payment: `PaymentStatusMetricsAspect`, `PaymentStatusChange`, `PaymentCommandUseCase`, `PaymentOutboxRepository`(포트), `JpaPaymentOutboxRepository`, `PaymentOutboxRepositoryImpl`, `PaymentOutboxUseCase`, `OutboxAsyncConfirmService`, `CheckoutResponse`, `PaymentPresentationMapper`
  - pg: `PgVendorCallService`, `RetryPolicy`, `PgInboxPollingWorker`, `TossPaymentGatewayStrategy`, `application.yml`
  - 공통: 5개 서비스 `core/common/log/`, `logback-spring.xml`
  - 빌드/문서: `config/checkstyle/`, `.github/workflows/`, `.claude/skills/_shared/checklists/code-ready.md`, `docs/context/{CONFIRM,PAYMENT}-FLOW.md`

## 진행 상황

- [x] Task 1: 전이 주체를 전이 지점이 선언하게 전환
- [x] Task 2: 발행 행 삽입을 충돌 없는 방식 + 잠금 읽기 확인으로 교체
- [x] Task 3: 중복 재진입 예외 도입과 재고 미회수 경보 분리
- [x] Task 4: 동시 승인 경합 통합 검증
- [x] Task 5: 체크아웃 응답에 중복 여부 복원
- [x] Task 6: 재시도 백오프 회차 정정과 좀비 회수 관계 명시
- [x] Task 7: 재시도 워커 자체 추적 구간 부여
- [x] Task 8: 로그 마스킹 계층 도입 (payment)
- [x] Task 9: 마스킹 계층 나머지 4서비스 확산
- [x] Task 10: 벤더 응답 원문 로깅 길이 제한
- [x] Task 11: 문자열로 판정 가능한 스타일 3규칙 검출과 기준선 억제
- [x] Task 12: 구조 판정이 필요한 스타일 2규칙 검출
- [x] Task 13: 지침 문서 검사 스크립트 CI 편입
- [x] Task 14: 리뷰 체크리스트 낡은 참조 정정
- [ ] Task 15: 결제 흐름 문서 다이어그램 표기 정리

---

## 태스크

### Task 1: 전이 주체를 전이 지점이 선언하게 전환 [tdd=true] [domain_risk=true]

호출 스택을 문자열로 뒤져 전이 주체를 짐작하던 방식을 없애고, 전이를 일으킨 쪽이 값을 넘기게 한다.

**테스트 (RED)**
- `PaymentStatusMetricsAspectTest`
  - `승인_결과_수신_전이는_confirm_라벨로_기록된다`
  - `만료_전이는_expiration_라벨로_기록된다`
  - `관리자_수동_종결은_manual_라벨로_기록된다`
  - `라벨에_unknown_이_기록되는_경로가_없다` — 전이 지점 전수에 대해 라벨이 비지 않음을 검증
- `PaymentCommandUseCaseTest` 보강
  - `markPaymentAsFail_승인_실패_경로와_재고_실패_경로가_서로_다른_주체로_기록된다`
  - `markPaymentAsQuarantined_재고_캐시_장애와_금액_불일치가_서로_다른_주체로_기록된다`
- 패턴: Mockito BDD + AssertJ. 지표는 `SimpleMeterRegistry`로 실제 라벨을 읽어 단정한다 — 호출 사실이 아니라 기록된 라벨 값을 본다.

**구현 (GREEN)**
- `PaymentStatusMetricsAspect.detectTriggerFromCallStack()` 삭제, `"auto"` 분기 제거
- `PaymentStatusChange` 애노테이션의 주체 값을 고정 선언으로 채우되, 한 메서드가 여러 흐름에서 불리는 두 곳(`markPaymentAsFail`, `markPaymentAsQuarantined`)은 호출자가 주체를 인자로 넘기도록 시그니처를 바꾼다
- 호출부 수정: `PaymentConfirmResultUseCase`(승인 실패), `PaymentFailureUseCase`(재고 실패), `PaymentTransactionCoordinator`(재고 캐시 장애), `QuarantineCompensationHandler`(금액 불일치)
- 애노테이션에 넘길 주체 값은 상수로 모아 오타를 막는다

**완료 기준**
- 위 테스트 전부 pass, 코드베이스에 `detectTriggerFromCallStack` 잔존 0건
- `./gradlew :payment-service:test` 회귀 없음

**완료 결과**
> `detectTriggerFromCallStack`/`"auto"` 분기를 제거했다. `PaymentStatusChange.trigger()`에 `default ""`를 주고,
> 새 파라미터 애노테이션 `@Trigger`(`core.common.aspect.annotation`)를 도입해 한 메서드가 여러 흐름에서 불리는
> 두 곳(`markPaymentAsFail`, `markPaymentAsQuarantined`)은 호출자가 `@Trigger String trigger` 인자로 주체를
> 넘기도록 시그니처를 바꿨다. 아스펙트는 `@Trigger` 파라미터 값을 우선 쓰고 없으면 애노테이션 고정값으로 fallback한다.
> 주체 상수는 `PaymentStatusChangeTrigger`(CONFIRM/EXPIRATION/MANUAL/STOCK_FAILURE/STOCK_CACHE_DOWN)로 모았다.
> 호출부 4곳(`PaymentConfirmResultUseCase.handleFailed`→CONFIRM, `PaymentFailureUseCase.handleStockFailure`→STOCK_FAILURE,
> `PaymentTransactionCoordinator.markStockCacheDownQuarantine`→STOCK_CACHE_DOWN, `QuarantineCompensationHandler.handle`→CONFIRM)를
> 갱신했다. `@Reason`을 찾는 `DomainEventLoggingAspect.findReasonParameter`가 새 `@Trigger` 파라미터와 섞이지 않는지
> 실행 기반 테스트(`DomainEventLoggingAspectReasonParameterTest`)로 확인했다 — 안전함을 확인.
> 지표 검증은 `SimpleMeterRegistry`에 실제 등록된 카운터 태그 값을 읽어 단정했고(`PaymentStatusMetricsAspectTest`,
> `PaymentCommandUseCaseTest.TriggerLabelRecordingTest` — `AspectJProxyFactory`로 실제 AOP 프록시를 조립해 검증),
> 전이 지점 전수 스캔으로 라벨이 비는 경로가 없음을 구조적으로 고정했다(`라벨에_unknown_이_기록되는_경로가_없다`).
> 시그니처 변경에 딸린 기존 호출부 목(mock) 단정 8개 테스트 파일도 3-인자 형태로 함께 갱신했다.
> `./gradlew :payment-service:test` 548개 전부 pass, checkstyle 통과.

---

### Task 2: 발행 행 삽입을 충돌 없는 방식 + 잠금 읽기 확인으로 교체 [tdd=true] [domain_risk=true]

주문 단위 발행 행을 만들 때 제약 충돌 예외가 아예 발생하지 않게 하고, 반영 행이 없으면 잠금 읽기로 기존 행 존재를 확인한다.

**테스트 (RED)**
- `JpaPaymentOutboxRepositoryTest` (Testcontainers MySQL + `@DataJpaTest`)
  - `이미_있는_주문으로_삽입하면_반영_행이_0이고_예외가_없다`
  - `새_주문_삽입은_반영_행이_1이다`
  - `잠금_읽기_조회가_기존_행을_반환한다`
- `PaymentOutboxRepositoryImplTest`
  - `반영_행_0이고_기존_행_존재면_이미_있음을_반환한다`
  - `반영_행_0이고_기존_행_없으면_저장_실패를_반환한다`
  - `반영_행_1이면_생성됨을_반환한다`
- `JpaPaymentOutboxRepositoryLockContractTest` — 잠금 방식이 조용히 바뀌는 회귀를 막는 구조 검증
  - `확인_조회는_쓰기_잠금_읽기로_선언된다` — 리플렉션으로 락 선언 존재를 단정
  - `확인_조회에_건너뛰기_힌트가_없다` — pg 워커 선점용 `SKIP LOCKED`를 베껴오는 실수를 막는다. 쿼리 문자열에 해당 힌트 부재를 단정
- 패턴: 실제 SQL 검증이 필요하므로 저장소 계층은 Testcontainers. 어댑터 분기는 Mockito. 락 선언 검증은 기존 애노테이션 리플렉션 단정 테스트(`PaymentCommandUseCaseTest`의 audit 애노테이션 검증)와 같은 방식.

**구현 (GREEN)**
- `JpaPaymentOutboxRepository`에 이미 있으면 넘어가는 삽입 네이티브 쿼리 추가 — pg 수신 기록 테이블의 `insertIgnorePending`과 같은 형태로 작성하되, 반영 행 수를 반환받는다
- 같은 인터페이스에 주문 번호 기준 확인 조회 추가. 잠금은 **블로킹 쓰기 잠금 읽기**로 건다 — 워커 선점용 건너뛰기 잠금도, 평범한 조회도 아니다. 앞선 요청이 커밋할 때까지 기다렸다가 최신 값을 읽어야 한다
- `PaymentOutboxRepository`(application 포트)에 생성 전용 메서드를 **새로 추가**하고, 결과를 생성됨 / 이미 있음 / 저장 실패 세 갈래로 구분해 돌려준다. 기존 `save`는 다른 호출부가 쓰므로 시그니처를 건드리지 않는다
- `PaymentOutboxUseCase.createPendingRecord`가 `save` 대신 새 메서드를 호출하도록 바꾼다
- `PaymentOutboxRepositoryImpl`이 반영 행 수와 확인 조회로 세 결과를 판정

**완료 기준**
- 위 테스트 전부 pass
- 삽입 경로에서 제약 위반 예외가 발생하지 않음을 저장소 테스트가 보임
- 락 계약 테스트가 존재하고, 잠금 선언을 제거하거나 건너뛰기 힌트로 바꾸면 실패함

**완료 결과**
> `JpaPaymentOutboxRepository`에 `insertIgnorePending`(네이티브 `INSERT IGNORE`, 반영 행 수 반환)과
> `findByOrderIdForUpdate`(`@Lock(PESSIMISTIC_WRITE)` 블로킹 쓰기 잠금 읽기, 건너뛰기 힌트 없음)를
> 추가했다. pg 수신 기록 테이블의 `insertIgnorePending` + `findIdByOrderId` 조합을 참고하되, 확인
> 조회는 pg 워커 선점용 `SKIP LOCKED`를 베끼지 않고 블로킹 잠금으로 못박았다 — 확정 트랜잭션이
> 결제 상태 전이로 이미 읽기 스냅샷을 잡은 뒤이므로, 잠금 없는 조회로는 자신을 막은 앞선 행을
> 보지 못해 중복을 저장 실패로 오분류하기 때문이다.
>
> `PaymentOutboxRepository`(포트)에 `createPendingIfAbsent(orderId)`를 새로 추가했다(기존 `save`는
> 그대로 유지). 결과는 `PaymentOutboxCreationResult`(CREATED/ALREADY_EXISTS/SAVE_FAILED) 세 갈래이며,
> `PaymentOutboxRepositoryImpl`이 반영 행 수 1을 CREATED로, 0이면 확인 조회 결과로 ALREADY_EXISTS와
> SAVE_FAILED를 가른다. `PaymentOutboxUseCase.createPendingRecord`가 `save` 대신 이 메서드를 호출하도록
> 바꿨다 — CREATED가 아니면 예외로 막되, 어떤 예외(중복 재진입 vs 그 밖의 실패)를 던질지 구분하는
> 것은 다음 태스크의 몫이라 지금은 하나의 예외로 막는다.
>
> 락 계약 테스트(`JpaPaymentOutboxRepositoryLockContractTest`)를 실제로 무력화해 검증했다 —
> `@Lock` 애노테이션을 제거하면 "쓰기 잠금 읽기로 선언된다" 케이스가 `Expecting actual not to be null`로
> 실패했고, 쿼리에 `FOR UPDATE SKIP LOCKED`를 붙이면 "건너뛰기 힌트가 없다" 케이스가
> `not to contain "SKIP LOCKED"`로 실패함을 확인한 뒤 원상복구했다.
>
> `PaymentOutboxRepository`에 메서드가 추가되면서 `OutboxPendingAgeMetricsTest`/`PaymentOutboxMetricsTest`의
> Fake 구현체 2곳이 컴파일 깨짐 — CREATED 고정 반환으로 보강했다(Rule 1, 인터페이스 확장에 따른
> 기계적 보완).
>
> `./gradlew :payment-service:test` 556개 전부 pass, checkstyle 통과. 참고로 이 태스크가 건드리는
> 중복 삽입 경로를 실제로 태우는 `StockRetentionIntegrationTest`(`@Tag("integration")`, 기본 test에서 제외)도
> `integrationTest`로 별도 실행해 3개 전부 pass 확인 — 이전에 UNIQUE 위반으로 얻던 RuntimeException이
> 지금은 `createPendingRecord`가 막는 예외로 바뀌었을 뿐 상위 계약(재확정 시 재고 이중 차감 0)은
> 그대로 유지된다.

---

### Task 3: 중복 재진입 예외 도입과 재고 미회수 경보 분리 [tdd=true] [domain_risk=true]

응용 계층이 Task 2의 결과를 받아 중복 재진입을 뜻하는 예외를 던지고, 승인 서비스가 그 예외만 경보에서 뺀다.

**테스트 (RED)**
- `PaymentOutboxUseCaseTest`
  - `이미_있음이면_중복_재진입_예외를_던진다`
  - `저장_실패면_중복이_아닌_예외를_던진다`
  - `생성됨이면_예외_없이_반환한다`
- `OutboxAsyncConfirmServiceTest`
  - `중복_재진입_예외는_재고_미회수_경보를_남기지_않는다` — 지표 카운터와 로그 이벤트 모두 미발생 확인
  - `그_밖의_실패는_종전대로_재고_미회수_경보를_남긴다`
  - `중복_재진입_예외도_호출자에게_그대로_전파된다` — 트랜잭션 롤백 경계 유지 확인
- 패턴: Mockito BDD + AssertJ, 지표는 `SimpleMeterRegistry`로 카운터 증감을 직접 읽는다.

**구현 (GREEN)**
- 중복 재진입을 뜻하는 예외를 기존 결제 예외 10종과 같은 자리(`payment-service/.../payment/exception/`)에 추가한다 — 새 하위 경로를 만들지 않는다. 예외 계층 규칙은 `docs/context/conventions/error-logging.md` 준수
- `PaymentOutboxUseCase.createPendingRecord`가 Task 2의 결과를 해석해 예외를 던지도록 수정
- `OutboxAsyncConfirmService.executeConfirmTxWithStockRetention`의 `catch (RuntimeException)`이 중복 재진입 예외를 먼저 걸러 경보 없이 재throw
- 재고를 되돌리지 않는 기존 정책은 그대로 둔다

**완료 기준**
- 위 테스트 전부 pass, `./gradlew :payment-service:test` 회귀 없음
- 예외 클래스가 기존 관례 위치(`payment/exception/`)에 있고, 어댑터에서 throw하지 않음

**완료 결과**
> 기존 관례 위치(`payment-service/.../payment/exception/`)에 `PaymentOutboxDuplicateException`을
> 추가했다(`RuntimeException` 상속, `StockCacheUnavailableException`과 같은 형태 — `code` 필드 +
> private 생성자 + `of(PaymentErrorCode)` 정적 factory). 에러코드 `PAYMENT_OUTBOX_DUPLICATE_INSERT`
> (`E03042`)를 `PaymentErrorCode`에 추가했다. 어댑터에서는 던지지 않는다 — 여전히 응용 계층
> (`PaymentOutboxUseCase`)만 `PaymentOutboxCreationResult`를 해석해 예외를 던진다.
>
> `PaymentOutboxUseCase.createPendingRecord`가 `IllegalStateException` 하나로 뭉뚱그리던 것을
> switch 표현식으로 갈랐다 — `CREATED`는 정상 반환, `ALREADY_EXISTS`는 `PaymentOutboxDuplicateException`,
> `SAVE_FAILED`는 그대로 `IllegalStateException`(재고 미회수 경보 대상, 이번 태스크에서 새 클래스를
> 만들지 않음 — 기존 "두 번째 가드" 관례 유지).
>
> `OutboxAsyncConfirmService.executeConfirmTxWithStockRetention`의 `catch (RuntimeException)` 앞에
> `catch (PaymentOutboxDuplicateException)`을 추가해 먼저 걸러 메트릭·로그 없이 그대로 재throw한다 —
> 예외를 삼키지 않으므로 `@Transactional` 롤백 경계가 호출자까지 유지된다. 재고를 되돌리지 않는
> 기존 정책은 손대지 않았다.
>
> 지표 검증은 `SimpleMeterRegistry`를 실제로 생성해 `stock_retention_unrecovered_total` 카운터
> 값을 직접 읽어 단정했다(Mockito 상호작용 검증이 아니라 실제 등록된 카운터). 로그 미발생은
> `ListAppender`를 서비스 로거에 붙여 ERROR 레벨 로그 이벤트 목록이 비어 있음을 확인했다
> (`OutboxAsyncConfirmServiceTest.DuplicateReentryAlertExclusionTest`).
>
> `./gradlew :payment-service:test` 560개 전부 pass(Task 2 종료 시점 556개 + 이번 태스크 4개),
> checkstyle 통과.

---

### Task 4: 동시 승인 경합 통합 검증 [tdd=true] [domain_risk=true]

실제 데이터베이스로 같은 주문의 승인 두 건을 동시에 태워, 진 쪽이 중복으로 분류되고 경보가 남지 않는지 확인한다.

**테스트 (RED)**
- `PaymentDuplicateConfirmConcurrencyIntegrationTest` (`@SpringBootTest` + Testcontainers MySQL + `@Tag("integration")`)
  - `같은_주문_동시_승인에서_진_쪽은_중복_재진입으로_분류된다`
  - `진_쪽에는_재고_미회수_경보가_남지_않는다`
  - `이긴_쪽은_정상_확정된다`
  - `진_쪽의_상태_전이는_롤백된다` — 결제가 진행 중 상태로 반쯤 남지 않음
- 두 스레드를 `CountDownLatch`로 같은 시점에 풀어 경합을 만든다. 스케줄링 편차로 한쪽이 앞서 나가면 경합 없이 통과할 수 있으므로 `@RepeatedTest(50)`으로 반복한다 — `TESTING.md`가 동시성·정확히 한 번 보장 검증에 요구하는 최소 횟수이자, 발행 워커 경합 테스트가 이미 쓰는 값이다. Testcontainers는 `TESTING.md`의 static 수동 start + reuse 패턴을 따른다.
- 반복마다 새 주문 번호를 만든다. 같은 번호를 재사용하면 두 번째 반복부터는 앞선 반복이 남긴 행 때문에 동시 삽입 경합 자체가 재현되지 않는다.
- 이 통합 테스트는 Task 2의 락 계약 테스트 위에 얹는 확인이지 유일한 방어선이 아니다.

**완료 기준**
- 위 테스트 pass, 반복 50회 전부 통과
- `./gradlew :payment-service:integrationTest` 통과 (캐시된 UP-TO-DATE가 아니라 실제 실행 확인)

**완료 결과**
> `PaymentDuplicateConfirmConcurrencyIntegrationTest`(`@SpringBootTest` + Testcontainers MySQL 수동 start,
> `@Tag("integration")`)를 추가했다. 재고 차감(Redis)은 이미 별도로 정확히-한-번을 보장하는 경로라
> 이 테스트의 관심사가 아니므로, 실 Redis 컨테이너 대신 Task 2/3이 다루는 outbox 삽입 경합에
> 집중하기 위해 실 Redis + Kafka(`@EmbeddedKafka`) 컨테이너 조합은 `StockRetentionIntegrationTest`와
> 동일하게 그대로 두되(재고 차감·발행 파이프라인 자체는 여기서도 실제 인프라를 태운다),
> `payment.monolith.confirm.enabled=false`로 AFTER_COMMIT 즉시 relay만 꺼서 outbox 행이
> 검증 시점 이전에 비동기로 DONE 전환되는 타이밍 흔들림을 없앴다(최초 시행에서 50회 중 18회가
> 이 타이밍 경합으로 실패해, 관련 없는 흔들림임을 확인하고 원인을 제거했다).
>
> 반복마다 `UUID`로 새 주문 번호를 발급해 앞선 반복의 outbox 잔여 행이 경합을 무력화하지
> 않게 했다. 두 스레드를 `CountDownLatch`(ready 2 + start 1)로 같은 시점에 풀어 outbox
> INSERT IGNORE 경합을 유도했다. `@RepeatedTest(50)` 안에서 네 가지를 한 번에 단정한다 —
> 정확히 한 건만 실패(진 쪽) / 진 쪽 예외가 `PaymentOutboxDuplicateException` / 진 쪽에서
> `stock_retention_unrecovered_total` 카운터가 증가하지 않음(반복 시작 전 값과 비교) / 이긴
> 쪽만 outbox 행을 남기고 `payment_event.status`가 `IN_PROGRESS` 하나로만 귀결(진 쪽 트랜잭션이
> 상태 전이까지 통째로 롤백됨).
>
> 경합이 실제로 재현되는지 확인 없이 통과만 보고하지 않기 위해, 커밋 전 임시로 두 스레드의
> `confirm()` 호출 구간 시각을 나노초로 재 겹침 여부를 로그로 남겨 봤다 — 50회 반복 전부에서
> 두 스레드의 실행 구간이 겹쳤다(`overlap=true` 50/50, 겹치지 않은 경우 0). 즉 스케줄링이 완전히
> 갈려 순차 실행으로 경합 자체가 빠지는 반복은 없었다. 확인 후 계측 코드는 제거했다.
>
> `./gradlew :payment-service:integrationTest --tests "*PaymentDuplicateConfirmConcurrencyIntegrationTest*" --rerun-tasks`
> 로 캐시 없이 재실행해 50/50 pass 확인. `./gradlew :payment-service:test` 560개 전부 pass(신규
> 단위 테스트 없음 — 이 태스크는 통합 테스트만 추가).

---

### Task 5: 체크아웃 응답에 중복 여부 복원 [tdd=true] [domain_risk=false]

응용 계층이 이미 채우고 있는 중복 여부가 응답 본문까지 전달되게 한다.

**테스트 (RED)**
- `PaymentPresentationMapperTest`
  - `중복_결제_결과는_응답에_중복_여부가_참으로_담긴다`
  - `신규_결제_결과는_거짓으로_담긴다`
- `PaymentControllerTest` (`@WebMvcTest` + MockMvc)
  - `중복_요청_응답_본문에_중복_여부_필드가_존재한다`
  - `기존_필드_주문번호와_총액은_그대로_유지된다`

**구현 (GREEN)**
- `CheckoutResponse`에 중복 여부 필드 추가
- `PaymentPresentationMapper.toCheckoutResponse`가 `CheckoutResult`의 값을 옮김
- 상태 코드 분기(신규 201 / 중복 200)는 그대로 둔다

**완료 기준**
- 위 테스트 pass, 기존 응답 필드 제거 없음

**완료 결과**
> `CheckoutResponse`에 `isDuplicate` 필드를 추가했다 — `CheckoutResult`와 같은 이유로
> `@JsonProperty("duplicate")`를 붙여 Jackson 기본 직렬화(`isXxx()` getter의 "is" prefix 제거)와
> key를 맞췄다. `PaymentPresentationMapper.toCheckoutResponse`가 `CheckoutResult.isDuplicate()`를
> 옮기도록 한 줄 추가했다. 상태 코드 분기(신규 201 / 중복 200)는 손대지 않았다.
>
> 기존 응답 필드(orderId, totalAmount)를 검증하는 전체 통합 테스트(`PaymentControllerTest`,
> `@Tag("integration")`)가 JSON 역직렬화에 쓰는 `CheckoutResponseMixin`이 새 필드를 모르면
> `FAIL_ON_UNKNOWN_PROPERTIES` 기본값 때문에 깨지므로, mixin 생성자에 `duplicate` 파라미터를
> 추가했다(Rule 1, 필드 추가에 따른 기계적 보완 — 새 테스트 자체가 아니라 기존 테스트를
> 계속 통과시키기 위한 기존 fixture 수정).
>
> `./gradlew :payment-service:test` 564개 전부 pass(Task 4 종료 시점 560개 + 이번 태스크 4개:
> `PaymentPresentationMapperTest` 2개, `PaymentControllerMvcTest`에 추가한 체크아웃 중복 응답
> 테스트 2개). `./gradlew :payment-service:integrationTest --tests "*PaymentControllerTest*"
> --rerun-tasks`로 캐시 없이 재실행해 기존 통합 테스트 4개(체크아웃 성공 케이스 포함)도
> 회귀 없음을 확인했다.

---

### Task 6: 재시도 백오프 회차 정정과 좀비 회수 관계 명시 [tdd=true] [domain_risk=true]

백오프 계산에 실패한 회차를 넘기도록 고쳐 대기를 설계 값으로 되돌리고, 좀비 회수 타임아웃과의 관계를 설정에 남긴다.

**테스트 (RED)**
- `PgVendorCallServiceTest`
  - `첫_재시도_예약은_기준_2초_구간에_들어간다`
  - `두번째_재시도_예약은_기준_6초_구간에_들어간다`
  - `마지막_재시도_예약은_기준_18초_구간에_들어가_좀비_회수_타임아웃보다_짧다`
- 지터 범위를 감안해 구간으로 단정한다. 고정 시드 난수를 주입해 흔들림을 없앤다.

**구현 (GREEN)**
- `PgVendorCallService.insertRetryOutbox`가 `computeBackoff(nextAttempt)` 대신 실패한 회차를 넘기도록 수정
- `RetryPolicy` javadoc의 회차별 기준값 서술을 실제 동작과 맞춤
- `pg-service/src/main/resources/application.yml`의 좀비 회수 타임아웃 설정에 최대 백오프와의 관계를 주석으로 남김
- `PgSelfLoopRetryExhaustionIntegrationTest`의 대기 시간 주석과 단정 구간을 새 값으로 조정

**완료 기준**
- 위 테스트 pass, 재시도 소진 통합 테스트가 단축된 대기로 통과
- 최대 백오프 상한이 좀비 회수 타임아웃보다 작음이 테스트로 고정됨

**완료 결과**
> `PgVendorCallService.insertRetryOutbox`가 `RetryPolicy.computeBackoff`에 `nextAttempt`(증가 후 값)
> 대신 실패한 `attempt`를 그대로 넘기도록 고쳤다. 이제 attempt=1 실패 후 재시도는 2s, attempt=2
> 실패 후는 6s, attempt=3 실패 후는 18s(각각 jitter ±25%) 기준으로 예약된다 — 전에는 한 회차씩
> 밀려 6s/18s/54s부터 시작했다.
>
> 지터 흔들림 없이 회차별 대기 구간을 결정적으로 검증하기 위해 `PgVendorCallService`의 재시도용
> 난수원을 `private static final SecureRandom RNG`에서 생성자 주입 `SecureRandom secureRandom`
> 필드로 바꿨다(Clock이 이미 이 클래스에서 쓰는 생성자 주입 패턴과 동일). `PgServiceConfig`에
> `SecureRandom` Bean을 추가했고, 직접 인스턴스화하는 테스트 4곳(`PgVendorCallServiceTest`,
> `PgVendorCallServiceVendorTypeTest`, `PaymentConfirmConsumerTest`,
> `PgSelfLoopDuplicateAbsorptionIntegrationTest`)의 생성자 호출에 `SecureRandom` 인자를 추가했다
> (Rule 1, 생성자 시그니처 변경에 따른 기계적 보완). `PgVendorCallServiceTest` 전용으로는
> `nextDouble()`이 항상 0.5(= jitter 0, backoff가 base 값 그대로)를 반환하는
> `FakeSecureRandom`(`pg.mock` 패키지)을 만들어 주입했다.
>
> `PgVendorCallServiceTest`에 `RetryBackoffScheduling` 중첩 클래스로 3개 테스트를 추가했다 —
> 첫/두번째/마지막(attempt=MAX_ATTEMPTS-1=3) 재시도 예약이 각각 [1.5s,2.5s] / [4.5s,7.5s] /
> [13.5s,22.5s] 구간에 들어가는지, 마지막 회차는 좀비 회수 타임아웃(60s)보다 짧은지 단정한다.
> RED 확인 절차: `computeBackoff` 인자를 일시적으로 `nextAttempt`로 되돌려 3개 테스트가 각각
> 6s/18s/54s로 예상 구간을 벗어나 실패하는 것을 확인한 뒤 원복했다.
>
> `RetryPolicyTest`에는 `computeBackoff(MAX_ATTEMPTS-1)`이 좀비 회수 타임아웃(60초)보다 항상
> 짧음을 고정하는 `@RepeatedTest(20)`를 추가했다 — attempt=MAX_ATTEMPTS(4)는 `shouldRetry`가
> false라 DLQ 경로로 빠져 실제로는 backoff를 계산하지 않으므로, 실제 재시도에 쓰이는 마지막
> 회차(attempt=3)를 좀비 타임아웃과 비교 대상으로 삼았다.
>
> `RetryPolicy`의 attempt=4 항목 javadoc에 "shouldRetry(4)가 false라 DLQ 경로로 빠지므로 실제
> 재시도 예약에는 쓰이지 않는다"는 설명을 보강했다. `pg-service/application.yml`의
> `in-progress-timeout-ms: 60000` 옆에, 실제로 예약되는 마지막 재시도 백오프 상한(22.5초)보다
> 충분히 크게 유지해야 한다는 관계를 주석으로 남겼다(숫자는 변경하지 않음).
>
> `PgSelfLoopRetryExhaustionIntegrationTest`의 클래스 javadoc과 본문 주석의 회차별 대기 구간·
> 누적 소요(구 78s 평균/97.5s 최악 → 신 26s 평균/32.5s 최악)를 새 값으로 갱신하고, Awaitility
> 타임아웃을 100s에서 60s로 낮췄다(최악 32.5s + 폴링 워커 안전망 지연 여유). 실제 재실행
> (`--rerun-tasks`, UP-TO-DATE 캐시 배제)으로 통과를 확인했다 — 대기가 짧아진 만큼 이전보다
> 빠르게 종결됐다.
>
> `./gradlew :pg-service:test` 379개 전부 pass, checkstyle 통과. `./gradlew :pg-service:integrationTest
> --rerun-tasks`로 16개 통합 테스트 전부 재실행해 pass 확인(`PgSelfLoopRetryExhaustionIntegrationTest`
> 포함, 캐시된 UP-TO-DATE 아님).

---

### Task 7: 재시도 워커 자체 추적 구간 부여 [tdd=true] [domain_risk=false]

워커가 복원한 문맥을 부모로 삼아 자기 구간을 열고 주문 번호를 속성으로 단다.

**테스트 (RED)**
- `PgInboxPollingWorkerSpanTest`
  - `좀비_회수_처리마다_자체_구간이_생성된다` — 구간 생성 자체를 단정 대상에 포함
  - `생성된_구간에_주문_번호_속성이_붙는다`
  - `복원된_문맥이_생성_구간의_부모가_된다`
- OpenTelemetry 테스트용 인메모리 익스포터로 실제 기록된 구간을 읽어 검증한다. 속성만 넣고 구간을 만들지 않으면 실패해야 한다.

**구현 (GREEN)**
- `PgInboxPollingWorker.processWithRestoredContext`가 복원 문맥을 부모로 구간을 시작하고, 주문 번호를 속성으로 설정한 뒤 종료
- 주문 번호는 처리 대상 수신 기록에서 읽는다

**완료 기준**
- 위 테스트 pass, 기존 추적 연속성 테스트 회귀 없음

**완료 결과**
> `PgInboxPollingWorker`에 `io.opentelemetry.api.trace.Tracer` 생성자 주입을 추가했다 — Spring Boot
> actuator의 `OpenTelemetryTracingAutoConfiguration`이 `management.tracing`/OTel 의존이 이미 걸린
> 조건에서 `Tracer` 빈을 자동 등록하므로(기존 `MeterRegistry` 주입과 동일 방식), 별도 `@Bean` 설정 없이
> 프로덕션에서 그대로 주입된다. `processWithRestoredContext`가 복원 문맥(`restoredContext`)을
> `setParent`로 넘겨 `pg_inbox.zombie_recovery` span을 새로 열고, `inboxRepository.findById(inboxId)`로
> 처리 대상 수신 기록을 읽어 주문 번호를 `order_id` 속성으로 붙인 뒤, 처리 구간 전체를 이 span의
> Scope 안에서 실행하고 종료 시 `span.end()`한다. 복원된 원격 span 자체는 기록 대상이 아니라 속성을
> 붙여도 조용히 버려지므로, 부모로만 삼고 실제 기록은 새로 연 자체 span이 맡는다.
>
> 검증은 공식 테스트 아티팩트(`opentelemetry-sdk-testing`) 없이 진행했다 — 프로덕션 의존
> (`opentelemetry-exporter-otlp`)이 이미 `opentelemetry-sdk-trace`/`opentelemetry-sdk`를 전이
> 의존으로 가져오고 있어(`implementation`은 테스트 클래스패스에도 노출), `SpanExporter` 계약을
> 직접 구현한 `FakeSpanExporter`(`pg.mock`)를 `SimpleSpanProcessor` + `SdkTracerProvider`에
> 꽂아 실제 export된 span을 읽었다. 새 빌드 의존 추가 없음.
>
> 속성만 붙고 span 자체를 열지 않는 회귀를 실제로 잡는지 확인했다 — 구현을 일시적으로
> `Span.current().setAttribute(...)` + 기존 `restoredContext.makeCurrent()` 방식으로 되돌리자
> 3개 테스트(생성 자체·속성·부모 관계) 전부 `IndexOutOfBoundsException`(export된 span 0개)으로
> 실패하는 것을 확인한 뒤 원복했다.
>
> 기존 두 테스트(`PgInboxPollingWorkerTest`, `PgInboxPollingWorkerTraceparentTest`)는 생성자
> 시그니처 변경에 따라 `OpenTelemetry.noop().getTracer("test")`를 추가 인자로 넘기도록
> 기계적으로 보강했다(Rule 1) — 두 테스트 모두 추적 자체를 검증 대상으로 삼지 않으므로 no-op
> tracer로 충분하다.
>
> `./gradlew :pg-service:test` 382개 전부 pass(Task 6 종료 시점 379개 + 이번 태스크 3개),
> checkstyle/spotbugs 통과. 기존 추적 연속성 통합 테스트
> (`PgInboxTraceparentIntegrationTest` 3개 + `PgSelfLoopRetryExhaustionIntegrationTest` 1개)를
> `--rerun-tasks`로 캐시 없이 재실행해 회귀 없음을 확인했다 — `@SpringBootTest` 전체 컨텍스트
> 로드로 `Tracer` 빈이 실제로 해석됨도 함께 검증됐다.

---

### Task 8: 로그 마스킹 계층 도입 (payment) [tdd=true] [domain_risk=true]

로그가 출력되기 직전에 민감 값을 가리는 계층을 payment 서비스에 먼저 만든다.

**테스트 (RED)**
- `MaskingPatternLayoutTest`
  - `결제_키가_앞자리만_남고_가려진다`
  - `인증_헤더_값이_가려진다`
  - `이메일_주소가_가려진다`
  - `카드번호_형태_문자열이_가려진다`
  - `민감하지_않은_문자열은_그대로_통과한다`
  - `패턴이_없으면_원문이_그대로_나온다`
- 레이아웃에 패턴을 등록하고 로그 이벤트를 직접 넣어 결과 문자열을 단정한다.

**구현 (GREEN)**
- `payment-service/.../core/common/log/MaskingPatternLayout` — Logback 패턴 레이아웃을 상속해 출력 직전 정규식 치환
- 패턴은 설정에서 등록하며, 캡처 그룹으로 지정한 부분만 가려 앞자리는 남긴다
- `logback-spring.xml`의 콘솔 출력이 이 레이아웃을 쓰도록 연결

**완료 기준**
- 위 테스트 pass, 기존 로그 포맷(시각·스레드·추적 번호·레벨)이 유지됨
- 패턴 목록이 코드가 아닌 설정에 있음

**완료 결과**
> `payment-service/.../core/common/log/MaskingPatternLayout`을 추가했다 — `PatternLayout`을
> 상속해 `doLayout(ILoggingEvent)`에서 `super.doLayout(event)`로 조립된 최종 문자열에 등록된
> 정규식을 순서대로 적용한다. 각 정규식은 캡처 그룹을 정확히 하나 가져야 한다는 규약으로
> 통일했다 — 그룹 밖 문자(접두사·접미사)는 원문 그대로 두고, `matcher.start(1)`~`end(1)`
> 구간만 고정 마스크 문자열(`***`)로 치환한다. 그룹 경계 바깥은 손대지 않으므로 앞자리
> 보존과 로그 포맷 유지가 구현 방식 자체로 보장된다.
>
> `logback-spring.xml`의 CONSOLE 어펜더가 `<encoder class="LayoutWrappingEncoder">`로
> 이 레이아웃을 감싸도록 바꿨다(logback이 커스텀 Layout을 encoder 안에 꽂는 표준 방식).
> 패턴 4개(결제 키/인증 헤더 값/이메일/카드번호 형태)는 `<maskPattern>` 요소로 설정에만
> 등록했다 — 코드를 고치지 않고 추가·제거할 수 있다. 세 프로필(test/docker/default)이
> 이 어펜더 하나를 공유하므로 별도 프로필별 중복 설정은 없다.
>
> 테스트는 `ListAppender`로 실제 로그 이벤트를 캡처해 레이아웃에 직접 태워 검증했다 —
> 4가지 대상 각각 앞자리 보존 + 가려짐, 비민감 문자열 그대로 통과, 패턴 미등록 시 원문
> 그대로 통과, 그리고 실제 `logback-spring.xml`의 전체 패턴(`%d/%thread/%X{traceId}/%level`)을
> 그대로 사용해 시각·스레드·추적 번호·레벨이 유지됨을 확인하는 케이스까지 7개.
>
> `./gradlew :payment-service:test` 571개 전부 pass(Task 7 종료 시점 564개 + 이번 태스크 7개).

---

### Task 9: 마스킹 계층 나머지 4서비스 확산 [tdd=false] [domain_risk=true]

Task 8에서 만든 형태를 pg / product / user / gateway에 같은 모양으로 넣는다.

**산출물**
- 각 서비스 `core/common/log/MaskingPatternLayout` (payment와 동일 구조)
- 각 서비스 `logback-spring.xml` 연결
- 각 서비스에 최소 1개 확인 테스트 — 결제 키 형태 문자열이 가려지는지

**완료 기준**
- 5개 서비스 모두 마스킹 적용, 각 서비스 테스트 pass
- `./gradlew test` 전체 회귀 없음

**완료 결과**
> payment-service의 `MaskingPatternLayout`(패키지·클래스 동일 구조, 서비스별 패키지 경로만
> 교체)를 pg / product / user / gateway 네 서비스의 `core/common/log/`에 그대로 복제했다 —
> 이 프로젝트가 `LogFmt` 등 로깅 유틸을 서비스마다 두는 방식을 그대로 따랐고, 공용 모듈은
> 새로 만들지 않았다.
>
> 각 서비스 `logback-spring.xml`의 CONSOLE 어펜더를 `LayoutWrappingEncoder` +
> `MaskingPatternLayout`으로 바꾸고, payment와 동일한 4개 `<maskPattern>`(결제 키/인증 헤더
> 값/이메일/카드번호 형태)을 등록했다. `LOG_PATTERN`/`LOG_PATTERN_COLOR`와 프로필별(test/docker/
> default) 로거 레벨은 그대로 두고 인코더만 교체했다 — gateway는 애초에 `org.hibernate.SQL`
> 로거 자체가 없어 다른 3서비스와 프로필 구성이 달랐는데, 이 차이도 그대로 유지했다.
>
> gateway는 `TraceContextPropagationFilter`가 WebFilter에서 `traceparent` 헤더를 파싱해
> MDC에 `traceId`/`spanId`를 직접 주입하는 구조라 다른 4서비스(Sleuth/Micrometer 자동 계측
> 의존)와 로깅 경로가 다르지만, `MaskingPatternLayout`은 `doLayout`이 조립된 최종 로그
> 문자열에 대해서만 동작하므로 MDC 주입 방식과 무관하게 동일하게 적용된다 — 필터 코드는
> 손대지 않았다.
>
> 각 서비스에 `MaskingPatternLayoutTest`를 1개씩 추가했다 — payment의 `결제_키가_앞자리만_
> 남고_가려진다` 테스트를 그대로 가져와 결제 키 형태 문자열이 가려짐을 확인한다(패턴별 상세
> 동작은 payment의 전체 테스트가 정본이라는 주석을 남겼다).
>
> `./gradlew test` 5개 서비스 전체 재실행, 전부 pass(신규 마스킹 테스트 4개 포함) — 회귀
> 없음. `./gradlew checkstyleMain checkstyleTest` 5개 서비스 전체 통과.

---

### Task 10: 벤더 응답 원문 로깅 길이 제한 [tdd=true] [domain_risk=true]

벤더 에러 응답 파싱이 실패했을 때 원문을 통째로 남기던 자리를 길이 제한한다.

**테스트 (RED)**
- `TossPaymentGatewayStrategyTest` 보강
  - `파싱_실패_로그의_원문은_상한_길이를_넘지_않는다`
  - `상한을_넘으면_잘렸음이_표시된다`
  - `짧은_원문은_그대로_남는다`

**구현 (GREEN)**
- `TossPaymentGatewayStrategy`의 파싱 실패 경로에서 원문을 상한 길이로 자르고 잘림 표시를 붙임
- 상한은 상수로 선언

**완료 기준**
- 위 테스트 pass, 파싱 실패 시 반환값(알 수 없음 처리)은 종전과 동일

**완료 결과**
> `TossPaymentGatewayStrategy.parseErrorResponse`의 JSON 파싱 실패 로그(`"에러 응답 파싱 실패 —
> UNKNOWN 처리 raw=" + errorResponse`)가 원문을 통째로 남기던 자리를 상수
> `MAX_PARSE_FAILURE_LOG_LENGTH`(500자)로 자르는 `truncateForLog` private 메서드를 추가해 적용했다.
> 상한을 넘으면 `"...(truncated, originalLength=<원래 길이>)"`를 붙여 잘렸다는 사실과 원래 길이를
> 로그에서 그대로 확인할 수 있게 했다 — 잘린 것과 원래 짧은 것을 구분 못 하는 문제를 없앤다.
> 반환하는 `TossPaymentApiFailResponse("UNKNOWN", errorResponse)`의 원문 필드는 이 자르기와
> 무관하게 그대로 유지된다 — 로그만 줄이고 판정(알 수 없음 처리) 자체는 손대지 않았다.
>
> 확인 결과 NicePay(`NicepayPaymentGatewayStrategy.parseErrorResponse`)에도 같은 형태(원문 통째
> 로그)가 있어 함께 처리했다 — 같은 상수명·같은 `truncateForLog` 헬퍼를 그대로 복제했다(이
> 프로젝트가 벤더 전략 간 공통 로직을 서비스별·전략별로 각각 복제해 온 기존 관례를 그대로 따름 —
> 두 클래스 사이에 공유 상위 타입이 없다).
>
> RED 확인: 구현 전 상태(main 변경분만 stash)에서 신규 테스트 6개 중 4개가 실패함을 확인했다
> (`MAX_PARSE_FAILURE_LOG_LENGTH` 필드 없음 / truncated 마커 없음 — `짧은_원문은_그대로_남는다`
> 2개는 원래도 자르기가 필요 없어 자연히 통과). 구현 복원 후 6개 전부 pass.
>
> 테스트는 `TossPaymentGatewayStrategyParseFailureLogTest`/
> `NicepayPaymentGatewayStrategyParseFailureLogTest` — `ListAppender`로 실제 WARN 로그 이벤트를
> 캡처해 `getStatusByOrderId`가 파싱 불가 JSON(`RestClientResponseException`)을 받았을 때 남기는
> 메시지를 직접 읽어 단정한다(5,000자 malformed body는 잘림+마커 확인, 8자 malformed body는
> 원문 그대로 확인).
>
> `./gradlew :pg-service:test` 389개 전부 pass(Task 9 종료 시점 383개 + 이번 태스크 신규 6개 —
> Toss 3 + Nicepay 3). `checkstyleMain`/`checkstyleTest` 통과.

---

### Task 11: 문자열로 판정 가능한 스타일 3규칙 검출과 기준선 억제 [tdd=false] [domain_risk=false]

패턴 매칭으로 잡히는 세 규칙을 빌드 단계에서 검출하되 빌드를 막지는 않는다.

**산출물**
- `config/checkstyle/checkstyle.xml`에 세 규칙 검출 추가 — 타입 추론 키워드 금지, 통짜 데이터 애노테이션 금지, 공개 유스케이스·포트의 빈 값 반환 금지
- 위반 심각도를 경고로 두어 빌드가 실패하지 않게 설정
- 기존 위반 전량을 `config/checkstyle/checkstyle-suppressions.xml`에 등재해 기준선을 0으로 만듦
- 억제 목록 규모를 규칙별 건수로 `docs/context/TODOS.md`에 기록

**완료 기준**
- `./gradlew checkstyleMain checkstyleTest` 실행 시 신규 위반 0으로 통과
- 검출이 실제로 동작함을 다음 절차로 확인하고 결과를 완료 결과에 붙여넣는다: 억제 목록에서 항목 하나를 임시 삭제 → `./gradlew checkstyleMain` 재실행 → 출력된 위반 로그를 그대로 기록 → 억제 항목 복원
- 규칙별 억제 건수가 대장에 숫자로 남음

**완료 결과**
> `config/checkstyle/checkstyle.xml`의 `TreeWalker`에 `RegexpSinglelineJava` 세 개를 `severity="warning"`으로
> 추가했다(id 로 `VarKeywordUsage`/`DataAnnotationUsage`/`PublicUseCasePortNullReturn` 부여) — Checker 레벨
> 기본 severity(`error`)를 개별 module 에서 override 했고, Gradle checkstyle 플러그인의 `maxWarnings` 기본값이
> 무제한이라 warning 은 `ignoreFailures=false` 상태에서도 빌드를 막지 않는다(직접 확인).
> - `VarKeywordUsage`: `\bvar\s+[A-Za-z_$][A-Za-z0-9_$]*\s*[=:]` — `var x =` / `for (var x : ...)` 형태만 겨냥.
> - `DataAnnotationUsage`: `@Data\b` — `@DataJpaTest` 등은 단어 경계로 오탐하지 않음.
> - `PublicUseCasePortNullReturn`: `return\s+null\s*;` — 리터럴 `return null;`만 겨냥, 삼항 `? x : null` 형태는
>   문자열 판정 범위 밖(Task 12 구조 검사 영역)이라 의도적으로 잡지 않는다.
>
> 공개 유스케이스·포트 한정 스코프는 checkstyle 자체엔 파일 경로 필터가 없어(`RegexpSinglelineJava`엔 files
> 속성이 없음), `checkstyle-suppressions.xml`에 `application/usecase`·`application/port` 밖 전 파일을
> `PublicUseCasePortNullReturn` id 하나만 억제하는 음의 전방탐색(negative lookahead) 항목
> (`^(?!.*[\\/]application[\\/](usecase|port)[\\/]).*$`)으로 좁혔다 — 이 항목은 "위반 억제"가 아니라
> "검사 대상 범위 한정"이다.
>
> 실제 위반 규모 확인 결과: 전체 저장소 grep 으로 `var x =` 형태 2건(둘 다 테스트 코드,
> `AsyncConfigContextPropagationTest.java:60`/`PaymentTransactionCoordinatorTest.java:191`), `@Data` 0건,
> `application/usecase`·`application/port` 안의 리터럴 `return null;` 0건(기존 `return null;` 5곳은 전부
> `infrastructure`/`aspect` 디렉토리라 이미 있던 블랑켓 억제 대상)을 확인하고 그대로 등재했다 — var 2건만
> 파일+라인 지정 억제, 나머지 두 규칙은 억제 항목 없이 기준선 0.
>
> **검출 동작 확인** — suppression DTD 를 1.0(`checks` 만 지원)에서 1.2(`id` 지원)로 올려야 했다: 처음
> `checks="VarKeywordUsage"` 로 억제를 시도했더니 무시됐는데, 원인은 checkstyle `SuppressFilterElement`가
> `checks` 속성을 module 의 `id` 프로퍼티가 아니라 `AuditEvent.getSourceName()`(Check 구현 클래스 풀네임)
> 에 매칭하기 때문이었다(바이트코드 역어셈블로 직접 확인) — 커스텀 id 로 세 `RegexpSinglelineJava` 인스턴스를
> 구분하려면 suppress 요소의 `id` 속성(DTD 1.1+ 필요)을 써야 했다. DTD 를 1.2 로 올리고 `id=` 로 바꾼 뒤
> 정상 동작을 확인했다.
>
> `PaymentTransactionCoordinatorTest.java:191` 억제를 임시 삭제하고 재실행한 로그(`--rerun-tasks`로 캐시 배제):
> ```
> > Task :payment-service:checkstyleMain
>
> > Task :payment-service:checkstyleTest
> [ant:checkstyle] [WARN] .../payment/application/usecase/PaymentTransactionCoordinatorTest.java:191:
> 타입 추론 키워드(var) 금지 — 명시적 타입을 선언한다 (code-style.md 안티패턴 회피). [VarKeywordUsage]
> Checkstyle rule violations were found. ... Checkstyle violations by severity: [warning:1]
> BUILD SUCCESSFUL
> ```
> `checkstyleMain`에는 나타나지 않았다(이 위반이 테스트 소스에만 있어서다) — 확인 후 억제 항목을 그대로 복원했다.
> `PublicUseCasePortNullReturn`은 실제 위반이 0건이라 지울 억제가 없어, 대신 실제 `.java` 파일에
> `return null;`을 반환하는 private 메서드를 임시로 추가해(`PaymentLoadUseCase.java`) `checkstyleMain`이
> 잡아내는지 확인했다:
> ```
> > Task :payment-service:checkstyleMain
> [ant:checkstyle] [WARN] .../payment/application/usecase/PaymentLoadUseCase.java:47: 공개 유스케이스·포트는
> null 대신 Optional 을 반환한다 (code-style.md 안티패턴 회피). [PublicUseCasePortNullReturn]
> BUILD SUCCESSFUL
> ```
> 확인 후 즉시 원복(git status 로 잔여 diff 없음 확인). `@Data` 도 같은 방식으로 스크래치 클래스에
> `@Data`를 임시로 붙여 `checkstyleMain`이 `DataAnnotationUsage`로 잡는 것을 확인한 뒤 스크래치 파일을
> 삭제했다.
>
> `./gradlew checkstyleMain checkstyleTest`(`--rerun-tasks`) 전체 6모듈 재실행 — 위반 0건(모든
> `build/reports/checkstyle/{main,test}.xml`의 `<error>` 카운트 0). `docs/context/TODOS.md`
> `[AGENT-DOCS-STATIC-ANALYSIS]`에 규칙별 억제 건수(var 2 / `@Data` 0 / null 반환 0)를 기록하고, 5규칙 중
> 3규칙 완료·잔여 2규칙(광범위 예외 삼킴, try 블록 재할당)은 Task 12로 이관됨을 남겼다.
>
> 코드(소스)는 건드리지 않고 빌드 설정 파일 2개만 변경했다 — `./gradlew test`는 UP-TO-DATE(캐시 유효,
> 컴파일 대상 변경 없음)로 회귀 확인 대상이 아니다.

---

### Task 12: 구조 판정이 필요한 스타일 2규칙 검출 [tdd=false] [domain_risk=false]

패턴 매칭으로는 잡히지 않는 두 규칙을 구조 검사로 잡는다. 기존 위반 규모가 미지수이므로 규모 파악을 먼저 하고, 그 결과를 기준선 처리에 반영한다.

**산출물**
- 광범위 예외를 잡고 삼키는 처리 금지, 예외 처리 블록 안에서 바깥 변수 재할당 금지 — 두 규칙을 구조 검사로 구현
- 구현 수단은 ArchUnit 도입 또는 checkstyle 트리 검사 중 규모 파악 결과를 보고 고른다. 고른 이유를 완료 결과에 기록
- 기존 위반은 Task 11과 같은 방식으로 기준선을 0으로 만든다
- 위반 건수를 `docs/context/TODOS.md`에 기록

**완료 기준**
- 검사가 실행되고 신규 위반 0으로 통과
- 검출 동작 확인 절차는 Task 11과 동일 — 억제 하나를 임시 제거해 실제로 잡히는지 보고 로그를 완료 결과에 기록
- 새 의존을 추가한 경우 그 사실과 버전이 완료 결과에 남음

**완료 결과**
> **1) 규모부터 셌다.** 두 규칙의 catch/try 후보를 전수 확인한 결과 — 광범위 예외(Exception/
> RuntimeException/Throwable/Error) catch 는 main 19곳 + test 11곳 = 30곳이었으나, 전부 하나하나
> 열어 실제 동작을 확인한 결과 main 19곳은 전부 이미 로그+재throw 또는 로그+명시적 fallback(early
> return, 카운터 increment)으로 끝나 있었다 — STOCK-COMPENSATION-RECOVERY 토픽에서 이미 이 함정을
> 걷어낸 결과다(`PITFALLS.md` §5). 진짜 "삼킴"(빈 catch)은 test 코드 3곳뿐이었다:
> `PaymentCheckoutConcurrencyIntegrationTest.java:76,141`(`catch (Exception ignored) {}`),
> `PgConfirmListenerSplitIntegrationTest.java:275`(`catch (RuntimeException ignored) { // 주석만 }`).
> try 블록 외부 변수 재할당은 선언(초기화 유무 무관) 이후 같은 블록의 try 안에서 재할당되는 패턴을
> 스크립트로 전수 스캔해 4곳을 찾았다 — `StockCatalogViewServiceImpl.java`/`PgAttemptHistoryViewServiceImpl.java`
> (둘 다 application 계층, 실사용 코드), `DuplicateApprovalHandlerListenerTest.java`/
> `TossPaymentGatewayStrategyDuplicateEventTest.java`(리플렉션으로 메서드/클래스 존재를 확인하는
> 테스트 픽스처). 규모가 두 규칙 다 한 자릿수라 넓게 잡히는 검사를 새로 들이는 부담과 균형이 맞았다.
>
> **2) 구현 수단 — ArchUnit 대신 checkstyle 커스텀 TreeWalker Check.** ArchUnit 은 바이트코드 기반
> 클래스/메서드 의존 그래프(호출 대상, 필드 접근, 애노테이션, 패키지 계층)를 다루도록 설계된
> 도구다 — "이 catch 블록 안에 throw 문이 있는가", "이 변수가 try 문보다 앞서 선언되고 try 블록
> 안에서 재할당됐는가" 같은 **메서드 본문 내부의 statement 구조·제어 흐름**은 ArchUnit 의 API
> (`JavaMethod.getMethodCallsFromSelf()` 등)로 표현할 수 있는 범위 밖이다. 반대로 checkstyle 은
> 이 프로젝트가 이미 쓰는 도구이고, `TreeWalker` 가 완전한 문법 AST(`DetailAST`)를 제공해 두
> 규칙 모두 자연스럽게 구현된다. 그래서 `config/checkstyle/custom-checks/`(root project 전용
> Gradle sourceSet, 별도 subproject 아님)에 `AbstractCheck` 를 상속한 커스텀 Check 2개를 추가했다.
> - `SwallowedBroadExceptionCheck` — catch 타입이 Exception/RuntimeException/Throwable/Error 중
>   하나이고 catch 블록 body 가 완전히 비어 있으면(주석만 있어도 AST 상 빈 블록) 위반. 로그만
>   남기고 재throw 없이 이어가는 더 약한 삼킴은 "재throw/return 여부"까지 정확히 구분하려면 더
>   깊은 분석이 필요해 이번 범위 밖으로 뒀다 — 실제로 그런 사례가 현재 0건임을 위 전수 확인으로
>   이미 검증했다.
> - `TryBlockExternalReassignmentCheck` — try 문과 같은 블록에서 앞서 선언된 변수 이름을 모아,
>   try 블록 body 안에서 그 이름에 대한 대입(`IDENT = expr;`)을 찾으면 위반. 중첩 람다·익명
>   클래스·메서드 정의는 별도 스코프라 탐색하지 않는다(섀도잉 오탐 방지). catch/finally 안
>   재할당은 규칙 문구가 "try 블록 안"만 금지해 대상이 아니다. 검사 범위는 try 문과 **같은
>   블록**에서 선언된 변수로 한정했다 — 중첩 if 안의 try처럼 바깥 블록의 변수까지는 추적하지
>   않는다(실제 위반 4건 전부 같은 블록 레벨이라 이 범위로 충분, 넓히면 섀도잉 오탐 위험 증가).
>
> **새 의존**: `com.puppycrawl.tools:checkstyle:10.17.0` — 이미 프로젝트의 checkstyle Gradle
> 플러그인이 쓰는 것과 **동일 버전**을 커스텀 Check 컴파일용 API 로 root project 전용
> `checkstyleCustomChecks` sourceSet 에 추가했을 뿐, 새 외부 라이브러리 생태계를 들인 것은
> 아니다. `build.gradle` 에 root 전용 sourceSet + 이 sourceSet 산출물을 각 서비스 `checkstyle`
> classpath 에 추가하는 배선을 넣었고, root project 자체의 `checkstyleMain`/`checkstyleTest`
> 및 새 sourceSet 자신의 checkstyle task 는 비활성화했다(자기 자신의 커스텀 규칙을 아직
> classpath 에 없는 상태로 요구하는 순환을 피하기 위함). [Rule 1] 구현 중 `checkstyleCustomChecksImplementation`
> 의존 좌표를 `com.puppycrawl.tools.checkstyle:checkstyle:10.17.0`(오타 — group/artifact 분리
> 잘못)로 썼다가 컴파일 단계에서 `Could not find` 에러로 즉시 드러나 `com.puppycrawl.tools:checkstyle:10.17.0`
> 로 고쳤다. 이어서 `checkstyle` Gradle 설정에 커스텀 classpath 를 직접 추가하면 Gradle 이 tool
> 본체 기본 dependency 를 더 이상 자동으로 넣어주지 않아 `ClassNotFoundException: ...CheckstyleAntTask`
> 로 실패했는데, 같은 버전의 tool 본체(`com.puppycrawl.tools:checkstyle:10.17.0`)를 나란히 명시해
> 해결했다(둘 다 build.gradle 자체의 즉시 드러난 배선 오류 수정 — 범위 내 자기 수정).
>
> **3) 기존 위반 기준선 억제** — `checkstyle-suppressions.xml` 에 4건 등재: `StockCatalogViewServiceImpl.java:36`/
> `PgAttemptHistoryViewServiceImpl.java:36`(`TryBlockExternalReassignment`), `DuplicateApprovalHandlerListenerTest.java:73`
> (`TryBlockExternalReassignment`), `PgConfirmListenerSplitIntegrationTest.java:275`(`SwallowedBroadException`).
> 나머지 2건(`PaymentCheckoutConcurrencyIntegrationTest.java:76,141` — `presentation/` 디렉토리,
> `TossPaymentGatewayStrategyDuplicateEventTest.java` — `infrastructure/` 디렉토리)은 기존 블랑켓
> 억제(`checks=".*"`)에 이미 포함돼 있어 새 항목이 필요 없었다(Task 11 이 `return null;` 잔여
> 5곳에 대해 확인한 것과 같은 구조).
>
> **4) 검출 동작 확인** — `payment-service:checkstyleMain` 에서 `StockCatalogViewServiceImpl.java`
> 억제 항목을 임시 삭제하고 `--rerun-tasks` 로 재실행한 로그:
> ```
> > Task :payment-service:checkstyleMain
> [ant:checkstyle] [WARN] .../payment/application/StockCatalogViewServiceImpl.java:36:22:
> try 블록 밖에서 선언한 변수 "pageInfo"를 try 블록 안에서 재할당한다 — private 메서드로 추출해
> try 안에서 바로 반환하거나 던진다 (code-style.md try 블록 패턴). [TryBlockExternalReassignment]
> Checkstyle rule violations were found. ... Checkstyle violations by severity: [warning:1]
> BUILD SUCCESSFUL
> ```
> 확인 후 억제 항목을 원상복구하고 `git diff` 로 잔여 변경 없음을 확인했다. `SwallowedBroadExceptionCheck`
> 도 같은 방식으로 `payment-service`/`pg-service` 각 checkstyleMain/checkstyleTest 를 개별 실행해
> 두 신규 Check id 가 정확한 파일·라인·메시지로 잡히는 것을 확인했다(위 로그와 동일 형식, 지면상
> 생략).
>
> **5) 최종 검증** — `./gradlew checkstyleMain checkstyleTest --rerun-tasks` 6개 서비스 전체 실행,
> `SwallowedBroadException`/`TryBlockExternalReassignment` 위반 0건(`build/reports/checkstyle/{main,test}.xml`
> grep 확인). `./gradlew test --rerun-tasks` 전체 재실행 — payment 571 / pg 389 / product 58 /
> user 10 전부 pass, 회귀 없음(build.gradle·checkstyle 설정·신규 Java 클래스만 변경, 서비스
> 소스는 미변경이라 UP-TO-DATE 캐시가 아닌 실제 재실행으로 확인).
>
> `docs/context/TODOS.md` `[AGENT-DOCS-STATIC-ANALYSIS]`를 5/5 완료로 갱신하고 규칙별 억제 건수
> (빈 catch 1건, try 재할당 3건)와 ArchUnit 기각 사유를 기록했다.

---

### Task 13: 지침 문서 검사 스크립트 CI 편입 [tdd=false] [domain_risk=false]

이미 있는 검사 스크립트를 CI에서 돌려 결과를 남기되 게이트로 쓰지 않는다.

**산출물**
- `.github/workflows/ci.yml`에 검사 실행 job 추가 — `scripts/check-agent-docs.py` 실행
- 결과를 워크플로우 요약에 남김
- 종료 코드는 판정과 무관하게 0을 유지 (스크립트 현행 동작 그대로)

**완료 기준**
- 워크플로우 문법 검증 통과 (`actionlint` 또는 GitHub 파싱 확인)
- 로컬에서 스크립트가 정상 실행되고 결과가 출력됨

**완료 결과**
> `.github/workflows/ci.yml`에 `agent-docs-check` job을 6서비스 fan-out과 나란한 top-level
> job으로 추가했다 — `needs`로 다른 job에 걸지 않고 완전히 독립 실행되며, 취합(`report`)
> job의 `needs`에도 넣지 않았다(게이트가 아니므로 PR 코멘트 조립 대기 이유가 없음).
>
> 스텝 구성: `actions/checkout@v6` → `python3 scripts/check-agent-docs.py`를 파일로 받아 job
> 로그(`cat`)와 `$GITHUB_STEP_SUMMARY`(코드 펜스로 감싼 원문) 양쪽에 남긴다. 스크립트
> 자체가 이미 항상 exit 0이라 별도로 종료 코드를 무시하는 처리를 추가하지 않았다 — 스크립트
> 현행 동작 그대로.
>
> `brew install actionlint`(+ 의존 `shellcheck`)로 로컬에 설치해 `actionlint .github/workflows/ci.yml`
> 실행 — 문제 없이 종료(exit 0). 워크플로우 저장소 전체(`actionlint`, 인자 없이)도 함께 재검증해
> 기존 5개 워크플로우 파일까지 회귀 없음을 확인했다.
>
> 로컬에서 GitHub Actions 스텝을 그대로 재현(`$GITHUB_STEP_SUMMARY`를 임시 파일로 지정해 동일
> 커맨드 실행) — 스크립트 정상 실행 확인, 현재 판정 결과는 참조 무결성/frontmatter/체크리스트
> 참조/고아 문서 4종 0건, 중복 규칙 1건(`docs/context/TODOS.md:103` — `var` 키워드 금지 문구가
> Task 11 완료 결과 서술에 그대로 등장), Mermaid 금지 문자 40건(`CONFIRM-FLOW.md`/`PAYMENT-FLOW.md`
> 다이어그램의 유니코드 화살표·가운뎃점 — Task 15가 정리할 대상 그 자체). 요약 파일에 `##` 제목 +
> 코드 펜스로 감싼 원문이 그대로 기록됨을 확인했다.
>
> 코드(소스)는 건드리지 않고 워크플로우 설정 파일 1개만 변경했다 — `./gradlew test`는 검증
> 대상이 아니다(코드 비접촉).

---

### Task 14: 리뷰 체크리스트 낡은 참조 정정 [tdd=false] [domain_risk=false]

**산출물**
- `.claude/skills/_shared/checklists/code-ready.md`의 사라진 메서드 경유 항목을 현행 오류 처리 규칙(`docs/context/conventions/error-logging.md`)으로 교체

**완료 기준**
- 코드베이스에 없는 메서드 이름이 체크리스트에서 사라짐
- `scripts/check-agent-docs.py` 실행 시 해당 항목 관련 판정이 깨끗함

**완료 결과**
> `code-ready.md` convention 섹션의 "`catch (Exception e)` 없음 (있다면 `handleUnknownFailure` 경유)"
> 항목을 `error-logging.md` 정본 규칙 그대로 "`catch (Exception e)` swallow 금지 — 잡으면 LogFmt로 기록 후
> 재throw 또는 명시적 fallback (error-logging.md)"으로 교체했다. 존재하지 않는 메서드명은 사라지고,
> 참조 대상도 정본 문서 경로로 명시했다.
>
> Task 12에서 광범위 예외 삼킴 중 "완전히 빈 catch 블록"만 checkstyle 커스텀 Check
> (`SwallowedBroadException`, severity=warning)이 이미 자동 검출한다는 점을 확인했다 — 이 체크리스트
> 항목은 손대지 않고 그대로 남겼다. 자동 검출이 빈 catch라는 좁은 부분집합만 잡고(로그만 남기고
> 재throw·명시적 fallback 없이 흐름을 이어가는 더 약한 삼킴은 잡지 못함), severity도 warning이라
> 빌드를 막지 않아 놓칠 수 있다 — 사람이 "잡으면 기록 후 재throw 또는 명시적 fallback"이라는 전체
> 규칙을 리뷰에서 계속 확인할 필요가 있다고 판단했다. `var`/`@Data`/null 반환 항목(Task 11에서 같은
> 방식으로 이미 자동 검출 대상이 됨)도 이 체크리스트에 그대로 남아 있는 것과 같은 결도 이 판단을
> 뒷받침한다.
>
> `error-logging.md`의 "`catch (Exception e) swallow 금지`" 문구를 code-ready.md convention 섹션에
> 그대로 옮겨 적었으나, `check-agent-docs.py`의 중복 규칙 판정(판정 4)은 code-ready.md convention/domain
> risk 섹션을 Task 7 존치 결정에 따라 매칭 대상에서 이미 제외하고 있어 새 중복 경고는 뜨지 않는다.
>
> 함께 `docs/context/TODOS.md` 섹션 F의 `[CODE-READY-HANDLEUNKNOWNFAILURE-STALE]` 항목을 완료로
> 갱신했다(이 문서가 등재해 둔 후속 항목을 이 태스크가 실제로 해소하므로) — 대장에 미해결로 남겨두면
> 실제 상태와 어긋난다.
>
> `python3 scripts/check-agent-docs.py` 재실행 — 참조 무결성(1) 0건, frontmatter(2) 0건, 체크리스트
> 참조(3) 0건, 중복 규칙(4) 1건(`docs/context/TODOS.md:103`의 `var` 키워드 금지 문구 — Task 11 완료
> 결과 서술에 있던 기존 항목, 이 태스크 범위 밖), Mermaid 금지 문자(5) 40건(Task 15 대상 그 자체),
> 고아 문서(6) 0건. 이 태스크가 다룬 항목(catch/handleUnknownFailure 관련)은 어떤 판정에도 걸리지
> 않아 깨끗함을 확인했다.
>
> 코드(소스)는 건드리지 않고 문서 2개(`code-ready.md`, `TODOS.md`)만 변경했다 — `./gradlew test`는
> 검증 대상이 아니다(코드 비접촉).

---

### Task 15: 결제 흐름 문서 다이어그램 표기 정리 [tdd=false] [domain_risk=false]

**산출물**
- `docs/context/CONFIRM-FLOW.md`와 `docs/context/PAYMENT-FLOW.md`의 다이어그램 라벨 안 유니코드 화살표를 아스키로 교체
- 다이어그램 블록 밖 본문 서술의 화살표는 건드리지 않는다

**완료 기준**
- `scripts/check-agent-docs.py`의 다이어그램 금지 문자 판정에서 두 파일 건수 0
- 다이어그램이 정상 렌더링됨

**완료 결과**
>

---

## 리뷰 처리

> (ship 단계에서 채움 — finding별 채택/스킵 + 사유)

### 리뷰 강도 대조 (설계 "리뷰 강도 대조" 결정 이행)

ship 단계 코드 리뷰가 끝나면 아래를 확인하고 결과를 기록한다.

- [ ] 이 토픽의 리뷰 라운드에서 도메인 검토자가 새로 잡아낸 중대 발견이 있었는지, 그것을 일반 검토자가 놓쳤는지 대조
- [ ] 대조 결과를 `docs/context/CONCERNS.md` C-11(리뷰 판정 강도 하향)에 기록
- [ ] 놓친 중대 발견이 있었다면 C-11의 원복 조건에 따라 검토자 판정 강도를 즉시 되돌림

> 대조 결과:
