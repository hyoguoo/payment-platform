# discuss-interview-0 — CLEANUP-BATCH-A Round 0 Interviewer

> stage: discuss
> round: 0
> persona: Interviewer (Main thread)
> 작성일: 2026-05-11

---

## 사용자 사전 컨텍스트

직전 토픽 PG-CONFIRM-LISTENER-SPLIT (PR #74) 봉인 직후, TODOS.md `[PR A]` 태그 4건 (TC-16 / TC-10 / TC-2 / TC-5) 을 단일 PR 묶음으로 묶어 진행하는 것이 사용자 사전 결정이다. 4 항목 모두 **도메인 결정 동반 없는 코드 청소** 라 이 토픽 자체는 가정 비중이 낮고, 핵심 ambiguity 는 (a) 묶음 구조(단일 PR 안 4 sub-section vs 더 쪼개기), (b) 4 항목 각각의 변경 경계 / 정책 선택 1~2개로 좁혀진다.

본 ledger 는 Path 1 (코드) 으로 사실 검증된 항목을 가정으로 굳히고, Path 2 (사용자) 가 필요한 분기를 1~2건으로 추려 Architect Round 1 진입 가능성을 판정한다.

---

## Ambiguity Ledger (4트랙)

### scope (범위)

| 트랙 항목 | 상태 | 근거 / 검증 |
|---|---|---|
| **묶음 단위** — 4 항목 단일 토픽 vs 더 쪼개기 | **Path 2 필요** | 4 영역이 서로 cross 의존 없음 (pg-service / payment-service / product+user). plan / review 단계에서 finding 분산 위험 vs 직전 토픽 맥락 살리는 묶음 이점. 사용자 판단 필요 |
| TC-16 영역 | 확정 (Path 1) | main 호출처 0건 (grep 확인). 단 test 1건 `PgInboxAmountStorageTest.java` 가 의존 — 함께 삭제 대상. `PgInboxAmountService.java` + test 파일 2건 |
| TC-10 영역 | 확정 (Path 1) | main 호출처 — `PgOutbox.create` 7건 (PgFinalConfirmationGate × 3, PgTerminalReemitService, PgDlqService, DuplicateApprovalHandler, PgVendorCallService × 2) + `PgOutbox.createWithAvailableAt` 1건 (PgVendorCallService) + `PgOutbox.of` 1건 (PgOutboxEntity#toDomain). `PgInbox` factory 6종 (`create` × 4 오버로드, `createDirectInProgress`, `createDirectTerminal`, `of` × 2, `ofWithId`). main 호출처는 RepositoryImpl + Entity#toDomain 만. **test 호출처는 12+개 파일** — review 부담 |
| TC-2 영역 | 확정 (Path 1) | product `V2__seed_product_stock.sql` + user `V2__seed_user.sql` 2건. payment / pg 서비스에는 V2 seed 없음 — 본 토픽 범위 밖. 4 서비스 모두 `spring.flyway.locations: classpath:db/migration` 동일 — 환경 분리 미적용 확인 |
| TC-5 영역 | 확정 (Path 1) | 매핑 대상 2 예외 (`ProductServiceRetryableException` / `UserServiceRetryableException`). 둘 다 `RuntimeException` 상속 → 현재 `GlobalExceptionHandler.catchRuntimeException` 이 잡아 500 반환. `PaymentExceptionHandler` (`@Order(HIGHEST_PRECEDENCE)`) 와의 충돌 없음 (다른 예외 타입) |
| 외부 서비스 변경 | 확정 | gateway / eureka / pg-service 변경 0 (TC-16 / TC-10 빼고). payment-service 변경은 TC-5 의 ControllerAdvice 1개 |
| DB 스키마 | 확정 | 0 — V1/V2 SQL 자체 변경 없고 `spring.flyway.locations` 설정 + profile 분리만 |

### constraints (제약)

| 트랙 항목 | 상태 | 근거 / 검증 |
|---|---|---|
| Hexagonal layer 룰 | 확정 (Path 1) | 4 항목 모두 layer cross 0. TC-16 = application 1 클래스 삭제. TC-10 = domain 2 클래스 패턴 통일 (다른 layer 영향 없음). TC-2 = infrastructure 측 yml + sql 분리. TC-5 = `core/common/exception` 패키지의 ControllerAdvice 1 클래스에 핸들러 추가. presentation 도 안 건드림 |
| TC-10 — PG-CONFIRM-LISTENER-SPLIT 봉인 패턴과 정합 | **Path 2 필요 (or Path 1 결론)** | 직전 토픽이 `PgInbox` 에 추가한 4종 (`create`(4 오버로드), `createDirectInProgress`, `createDirectTerminal`) factory 안에 **시나리오 의도** (정상 PENDING / 보정 IN_PROGRESS 우회 / 보정 terminal 우회) 명시. `@Builder` 로 바꾸면 factory 가 builder 호출을 캡슐화하므로 의도 보존은 가능 — 단 호출처(외부) 가 builder 직접 호출 시 `IllegalArgumentException` (terminal status 가드 등) 우회 가능성. **factory only 노출 + builder `private` 캡슐화 룰** 을 plan 에서 명시할지 사용자 확인 권고 |
| TC-10 — id 매개변수 처리 | 확정 (Path 1) | 현재 `PgOutbox.create(Long id, ...)` 의 id 가 호출처 10건 모두 null. `@Builder` 전환 시 builder 안에서 id 생략 가능 — id 컬럼은 RDB AUTO_INCREMENT 가 어차피 채움. `PgOutboxEntity#toDomain` 은 `of(...)` 풀 컨스트럭터 사용 (id 포함) — 그대로 유지 |
| TC-2 — Flyway 환경 분리 방식 | **Path 2 필요** | 3 후보: (a) `spring.flyway.locations` 에 profile placeholder, (b) `application-{profile}.yml` 에서 `spring.flyway.locations` override, (c) `V2__seed_*.sql` 을 별도 `db/seed/` 디렉토리로 이동 + profile 별 location 합치기. 운영 docker-compose 가 `docker` profile 활성 — 운영 = `docker` 면 (b) 가 가장 단순. 그러나 `INSERT IGNORE` 가 멱등이라 운영 적용돼도 row 충돌 0 = **위험 가시화 vs 안전 중복** 트레이드오프. 사용자 판단 권고 |
| TC-2 — docker-compose / 운영 호환성 | 확정 (Path 1) | `application-docker.yml` (product / user 모두 존재) 에서 `spring.flyway.locations` override 가능. 별도 운영 / dev 분리 안 — 단일 docker profile 만 존재. `prod` profile 추가는 본 토픽 범위 밖. **현재 환경 = docker (운영 가정) + 기본 profile (테스트 / 로컬)** 의 2층 구조로 충분 |
| TC-5 — 응답 코드 정책 | **Path 2 필요** | 2 후보: (a) `RetryableException` 일괄 503 + `Retry-After: <default>` 헤더, (b) 예외 origin (Feign ErrorDecoder 매핑) 별 분기 — 503 → 503 그대로, 429 → 429 그대로. 현재 `ProductFeignConfig.ErrorDecoder` 가 429 / 503 둘 다 `ProductServiceRetryableException` 단일 타입으로 매핑 — 원본 status 정보 손실. (a) 가 코드 변경 적고 사용자 시그널 명확 (재시도 가능). (b) 는 status 보존 필요해서 예외 타입에 origin status 추가 필요 — 변경 범위 큼. 사용자 판단 권고 |
| TC-5 — `Retry-After` 기본값 | **Path 2 필요 (작음)** | `Retry-After: <seconds>` 의 기본값 — 1 / 5 / 10 / 30 중 무엇? Feign `connectTimeout=2s readTimeout=5s` baseline 고려 시 5~10s 가 자연스러움. 정책 결정 필요 |
| 처리 순서 | 확정 (Path 1 — 자유) | 4 항목 의존 없음. 영향 작은 것 먼저 권고 — TC-16 (삭제 1건) → TC-2 (yml) → TC-5 (ControllerAdvice 1 클래스) → TC-10 (호출처 다수 + 테스트 다수) |
| 호환성 | 확정 | 운영 인프라 (Kafka / MySQL / Redis) 변경 0. 외부 API 응답 contract — TC-5 만 status 코드 500 → 503/429 변경, 클라이언트 핸들링 차이 있음. 단 본 프로젝트는 학습용이라 운영 클라이언트 영향 0 |

### outputs (산출물)

| 트랙 항목 | 상태 |
|---|---|
| TC-16 산출물 | `PgInboxAmountService.java` 삭제 + 의존 test `PgInboxAmountStorageTest.java` 삭제. 2 파일 (main 1 + test 1) 변경. 코드 라인 0 추가 / 200+ 라인 제거 |
| TC-10 산출물 | `PgInbox` / `PgOutbox` 2 클래스 — `@Builder(builderMethodName="allArgsBuilder", buildMethodName="allArgsBuild")` + `@AllArgsConstructor(PRIVATE)` 추가, 명시 `private` 생성자 제거. factory method 내부 builder 호출 + id 생략. 호출처 10건 (main) 은 시그니처 변경 없음 (id 인자 제거). test 호출처 12+개 파일 회귀 검증 필요 |
| TC-2 산출물 | `product-service` + `user-service` 의 `application-docker.yml` (또는 `application-prod.yml` 신설) 에 `spring.flyway.locations` override. 또는 `V2__seed_*.sql` 을 별도 디렉토리로 이동. 1~2 yml 변경 + 0~2 sql 이동 |
| TC-5 산출물 | `GlobalExceptionHandler` (or 별 `RetryableExceptionHandler`) 에 2 `@ExceptionHandler` 추가 (`ProductServiceRetryableException` / `UserServiceRetryableException`). 503 + `Retry-After` 헤더 응답. `ErrorResponse` 본문 + `PaymentErrorCode` 매핑 |
| 테스트 | TC-16 — test 삭제로 cover. TC-10 — 기존 12+ 테스트 파일이 회귀 cover, 신규 builder 패턴 단위 테스트 1~2개 (선택). TC-2 — 검증 어려움 (verification 트랙 참조). TC-5 — `GlobalExceptionHandlerTest` (없으면 신규) 또는 MockMvc 통합 테스트 1~2건 |
| context 문서 | verify 단계에서 TODOS.md 4 항목 제거 + CONVENTIONS.md 의 Lombok / Builder 룰에 pg-service 정합 표기 + STACK.md 의 Flyway 운영 가이드에 환경 분리 추가 |
| 위키 동기화 | 본 토픽 범위 밖 (TC-13 이 위키 정합 본진) |

### verification (검증)

| 트랙 항목 | 상태 |
|---|---|
| 단위 테스트 (TC-16) | test 파일 자체 삭제 — 회귀 검증 = 다른 테스트 PASS 유지 |
| 단위 + 통합 테스트 (TC-10) | 기존 12+ 테스트 파일이 `PgInbox.of` / `PgOutbox.of` / `PgOutbox.create` 호출. 시그니처 호환 유지 시 코드 변경 0. 단 `@Builder` 추가 후 `lombok` annotation processor 가 builder 메서드 노출 — `allArgsBuilder()` / `allArgsBuild()` 명시 (payment-service 패턴 그대로 적용 권고) |
| 단위 / 통합 테스트 (TC-2) | **검증 갭** — Flyway location override 가 dev profile 만 적용된다는 것 어떻게 확인? 후보: (a) `@SpringBootTest(activeProfiles="docker")` 통합 테스트로 V2 not-applied 확인, (b) Testcontainers + profile 가변 통합 테스트, (c) 검증 안 함 (수동 스모크). 본 토픽 학습 가치 = (a) 또는 (b). 사용자 판단 권고 |
| 단위 / 통합 테스트 (TC-5) | MockMvc + `@WebMvcTest` 로 `RestController` 가 `RetryableException` throw 시 503 + `Retry-After` 헤더 응답 검증. controller 한 개 mocking 으로 충분 |
| 회귀 | 4 서비스 전체 `./gradlew test` PASS 유지. 직전 PR #74 기준 pg-service 207 PASS, payment-service 다수 PASS |
| 정적 분석 | `./gradlew check` PASS (jacoco / spotless / checkstyle 등) — 직전 토픽 baseline 유지 |

---

## 3-Path Routing

| # | 질문 / 가정 | Path | 결과 / 권고 |
|---|---|---|---|
| 1 | 4 항목을 단일 토픽 안 4 sub-section 으로 두는가, 더 쪼개는가? | **Path 2 (사용자)** | sub-section 4 묶음 권고 — 영역 cross 0, 변경 폭 작음, plan 분기 단순. 단 사용자 결정 |
| 2 | TC-16 dead service 함께 삭제할 test 1건 (`PgInboxAmountStorageTest`) 처리 | Path 1 (코드) | 함께 삭제. 다른 의존 0 확인됨 |
| 3 | TC-10 `@Builder` 전환 시 시나리오 의도 (createDirectInProgress / createDirectTerminal 등) 보존 방법 | Path 1 (코드 패턴) | payment-service `PaymentOutbox` 미러링 — `allArgsBuilder/allArgsBuild` 명명 + factory method 안에서 builder 호출. terminal 가드 (`createDirectTerminal` 의 `isTerminal()` 체크) 는 factory 안에 그대로 남김. 호출처는 factory only — builder 직접 노출 안 함 |
| 4 | TC-10 호출처 12+ 테스트 파일 회귀 — 시그니처 호환 가능? | Path 1 (코드) | 호환 가능 — 현재 main 호출 시그니처가 `PgOutbox.create(null, topic, key, payload, headersJson)` 인데 id 인자 제거 후 `PgOutbox.create(topic, key, payload, headersJson)` 로 단순화 가능. 호출처 10건 모두 동일 변경 (null 인자 제거). test 호출처는 `PgOutbox.of(...)` / `PgInbox.of(...)` 형 — 그대로 유지 |
| 5 | TC-2 Flyway 환경 분리 — 3 후보 중 선택 | **Path 2 (사용자)** | 권고: `application-docker.yml` 에 `spring.flyway.locations: classpath:db/schema` override + `V2__seed_*.sql` 을 `classpath:db/seed/` 로 분리. 기본(local/test) profile 은 `classpath:db/schema,classpath:db/seed` 둘 다 적용. 단 사용자 판단 |
| 6 | TC-5 응답 코드 정책 — 503 일괄 vs 429/503 분기 | **Path 2 (사용자)** | 권고: 503 일괄 + `Retry-After` 헤더. 변경 작고 학습 가치 (Retryable 의도 = "재시도 가능, 일시 장애" 한 가지 시그널이면 충분). 429/503 분기는 ErrorDecoder 매핑 변경까지 동반 — 본 토픽 범위 초과 |
| 7 | TC-5 `Retry-After` 기본값 | Path 2 (사용자, 작음) | 권고: 5초 (Feign readTimeout=5s 와 정합) |
| 8 | TC-2 검증 방식 | **Path 2 (사용자)** | 권고: `@ActiveProfiles("docker")` + Testcontainers 통합 테스트 1건 — V2 SQL not-applied 확인. 또는 수동 스모크만. 사용자 판단 |
| 9 | 처리 순서 | Path 1 (자유) | TC-16 → TC-2 → TC-5 → TC-10. 영향 작은 것부터 |

**Dialectic Rhythm Guard**: Path 1 = 4건 (#2, #3, #4, #9), Path 2 = 5건 (#1, #5, #6, #7, #8). 3연속 Path 1 한도 미초과. 사용자 에스컬레이션 분기 5건 누적 — **적정 수준** (다음 라운드 진입 전 일괄 답변 받으면 OK).

---

## 확정 가능한 가정 (Path 1 결론)

1. **TC-16 범위 확정** — `PgInboxAmountService.java` (main 1) + `PgInboxAmountStorageTest.java` (test 1) 동시 삭제. 다른 의존 0
2. **TC-10 builder 패턴** — payment-service `PaymentOutbox` 패턴 미러링 (`@Builder(builderMethodName="allArgsBuilder", buildMethodName="allArgsBuild")` + `@AllArgsConstructor(PRIVATE)`). factory method 내부 builder 호출 + id 인자 제거. PG-CONFIRM-LISTENER-SPLIT 봉인 factory 4종 (`create`(4 오버로드), `createDirectInProgress`, `createDirectTerminal`) 시나리오 의도 그대로 보존
3. **TC-10 호출처 회귀** — main 10건 시그니처 단순화 (null id 인자 제거) + test 12+ 파일은 `PgInbox.of(...)` / `PgOutbox.of(...)` 형 — 그대로 유지
4. **TC-2 영역** — product / user-service 의 V2 seed 만. payment / pg 는 V2 seed 없음 — 범위 밖
5. **TC-5 매핑 대상** — `ProductServiceRetryableException` + `UserServiceRetryableException` 2 예외. 둘 다 `GlobalExceptionHandler.catchRuntimeException` 의 fallback 으로 빠짐 — 신규 핸들러 추가로 정확 매핑
6. **Layer 룰 위반 0** — 4 항목 모두 단일 layer 안 변경
7. **처리 순서** — TC-16 → TC-2 → TC-5 → TC-10 (영향 작은 것부터). plan 의 태스크 정렬 권고

---

## 사용자 에스컬레이션 필요 (5건)

다음 Architect Round 1 진입 전 사용자 답변 권고:

1. **묶음 구조** — 4 항목 단일 토픽 안 4 sub-section 으로 진행 OK? (또는 pg-service 묶음 vs payment+product+user 묶음 2 토픽 분리?)
2. **TC-2 Flyway 환경 분리 방식** — 권고안 (`application-docker.yml` override + `db/seed/` 분리) 채택? 또는 다른 안?
3. **TC-2 검증 방식** — Testcontainers 통합 테스트 1건 추가? 또는 수동 스모크만?
4. **TC-5 응답 코드 정책** — 503 일괄 + `Retry-After` 권고안 채택? 또는 429/503 분기?
5. **TC-5 `Retry-After` 기본값** — 5초 권고안 채택? 또는 다른 값?

---

## 종료 조건 충족

- [x] 4트랙 (scope/constraints/outputs/verification) 모두 최소 1회 커버
- [x] Path 1 결론 7건 확정 + Path 2 에스컬레이션 5건 정리
- [x] Dialectic Rhythm Guard — Path 1 / Path 2 균형 (3연속 Path 1 위반 없음)
- [x] 핵심 가정이 사용자 확인 거치도록 5건 명시 (Path 2 분기)

---

## 다음 단계 판정

**ambiguity 수준**: 적정 — 사용자 에스컬레이션 5건 모두 단답형 (3 후보 중 1 택 또는 yes/no 또는 숫자 1개). 도메인 결정 동반 없음. **사용자 답변 받은 뒤 Architect Round 1 진입 OK**.

Architect Round 1 — `docs/topics/CLEANUP-BATCH-A.md` 본문 (§ 결정 + § to-be 다이어그램 4건 + § 컴포넌트 인벤토리 + § 산출물 명세 + § 검증 매트릭스) 작성. 본 ledger 의 가정 7건 + 사용자 답변 5건 합쳐 12개 결정 사항 기반.

---

## 참고 (사실 검증 출처)

- `pg-service/src/main/java/com/hyoguoo/paymentplatform/pg/domain/PgInbox.java` — factory 6종 + private 생성자 (line 37~58)
- `pg-service/src/main/java/com/hyoguoo/paymentplatform/pg/domain/PgOutbox.java` — factory 3종 (`create` / `createWithAvailableAt` / `of`) + private 생성자 (line 23~65)
- `pg-service/src/main/java/com/hyoguoo/paymentplatform/pg/application/service/PgInboxAmountService.java` — dead service 본체 + WARNING JavaDoc (line 18~21)
- `pg-service/src/test/java/com/hyoguoo/paymentplatform/pg/application/service/PgInboxAmountStorageTest.java` — TC-16 함께 삭제 대상
- `payment-service/src/main/java/com/hyoguoo/paymentplatform/payment/domain/PaymentOutbox.java` — TC-10 참조 패턴 (line 12~14)
- `payment-service/src/main/java/com/hyoguoo/paymentplatform/payment/core/common/exception/GlobalExceptionHandler.java` — TC-5 매핑 추가 대상
- `payment-service/src/main/java/com/hyoguoo/paymentplatform/payment/exception/ProductServiceRetryableException.java` / `UserServiceRetryableException.java` — TC-5 매핑 입력
- `payment-service/src/main/java/com/hyoguoo/paymentplatform/payment/infrastructure/adapter/http/feign/ProductFeignConfig.java` — 429/503 → Retryable 통합 매핑 출처 (line 53~58)
- `product-service/src/main/resources/db/migration/V2__seed_product_stock.sql` + `user-service/src/main/resources/db/migration/V2__seed_user.sql` — TC-2 대상 SQL
- `*-service/src/main/resources/application.yml` 4건 — 현재 `spring.flyway.locations: classpath:db/migration` 동일
- `product-service/src/main/resources/application-docker.yml` + `user-service/src/main/resources/application-docker.yml` — TC-2 override 대상 후보
