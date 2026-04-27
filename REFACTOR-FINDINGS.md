# Refactor Findings — Round 1 정적 사실 수집

> 작성: 2026-04-27 — Phase 4 진입 전 최종 코드 검수.
> 임시 파일 — Round 4 종료 시 archive 또는 삭제.
>
> 우선순위: **Critical** = 동작/보안 결함 · **Major** = 도메인 일관성/회복성 약점 · **Minor** = 가독성/모던 자바/메서드 분리 · **Defer** = 비용>이득.

---

## 0. 모듈 라인 매트릭스

| 모듈 | 파일 | 총 라인 | 비고 |
|---|---:|---:|---|
| payment-service | 195 | 8,529 | core 모듈 |
| pg-service | 80 | 5,442 | 벤더 strategy 2종 + outbox/inbox |
| product-service | 34 | 1,142 | stock-committed consumer |
| user-service | 18 | 470 | 단순 조회 |
| gateway | 6 | 302 | 라우팅 + traceparent |
| eureka-server | 1 | 14 | Spring Cloud autoconfig 만 |

가장 긴 java 파일 2개 (>=300 라인): `DuplicateApprovalHandler.java` (320), `NicepayPaymentGatewayStrategy.java` (316). 두 파일 모두 본질적 책임 단일 — god class 아님.

---

## 1. 메서드 분리 (Extract Method)

### Critical / Major
- 0건.

### Minor
| 위치 | 발견 | 처방 |
|---|---|---|
| `AdminPaymentQueryRepositoryImpl.searchPaymentEvents` (40 라인) | 보더라인 길이. 동적 조건 → JPQL 빌드 + 페이지네이션 + 결과 매핑이 한 메서드 | Extract: `buildPredicates(...)` + `executePagedQuery(...)` |
| `PaymentTransactionCoordinator.executePaymentFailureCompensationWithOutbox` (max nesting=6) | if(outboxInFlight && eventCompensatable) 안에 for+try/catch 중첩 | Extract: `compensateStockCacheGuarded(orderList)` private 메서드 |

---

## 2. 모던 자바 21 활용도

### 양호 (보존)
- `record` 27곳 (DTO / value object / message)
- `sealed interface` 3곳 (`PgVendorCallService.GatewayOutcome` / `DuplicateApprovalHandler.HandlerOutcome` / `PgFinalConfirmationGate.FcgOutcome`)
- `switch expression` 광범위 사용 (`PaymentEventStatus.isTerminal`, `PaymentPresentationMapper`, `RetryPolicy.nextDelay`)

### Minor (검토 후보)
| 항목 | 사용 빈도 | 처방 |
|---|---:|---|
| `var` (지역 변수) | 0건 | 명시 타입 보존이 일관 — 도입 시 코드 일관성 깨짐. **Defer** |
| `text block` (`"""`) | 미사용 | log message 다중 라인 후보 없음 — 도입 의미 약함. **Defer** |
| `pattern matching` (instanceof) | `if (payload instanceof String s)` 1곳만 사용 | 추가 도입 후보 적음 |

---

## 3. 안티 패턴

### Critical / Major
- 0건.

### Minor
| # | 위치 | 패턴 | 처방 |
|---|---|---|---|
| A1 | `DomainEventLoggingAspect.java:77`, `PaymentStatusMetricsAspect.java:68` | `return null;` (AOP `@AfterReturning` advice — 의도된 sentinel) | 보존 |
| A2 | `TossPaymentGatewayStrategy.java:236`, `NicepayPaymentGatewayStrategy.java:289` | `return null;` (parseApprovedAt fallback — null 의미 명시) | 보존 |
| A3 | `Optional.orElse(null)` 3건 | 호출처 직후 `if (x == null) ...` 분기 — 의미상 nullable optional | `.map(...).orElseGet(...)` 또는 `.ifPresentOrElse(...)` 변환 검토 |
| A4 | `DuplicateApprovalHandler.java:155`, `TraceContextPropagationFilter.java:65` | `if (optional.isPresent()) { optional.get() ... }` | `.ifPresent(value -> ...)` 또는 `.map(...).orElse(...)` |
| A5 | `TossApiMetrics.recordTossApiCall(..., boolean success, ...)` 2개 오버로드 | boolean parameter — 호출처에서 의미 불명확 | `enum CallOutcome { SUCCESS, FAILURE }` 도입 |

### `IllegalStateException` 사용 27건 — 일괄 검토
- 대부분 의도된 wrapper:
  - `JsonProcessingException` → `IllegalStateException(context, e)` (Kafka 직렬화)
  - `ExecutionException` → `IllegalStateException(topic + key, cause)` (Kafka send)
  - `NoSuchAlgorithmException` → `IllegalStateException("SHA-256...", e)` (불변)
- 모두 메시지에 context (orderId / topic / key) 포함. cause 보존. **보존**.

---

## 4. 의존성 / DI

### 위반
- 0건. `@Autowired` 0, `infrastructure → application/domain import` 0, `domain → Spring/JPA import` 0.

### Minor (검토 후보)
| 위치 | 패턴 | 처방 |
|---|---|---|
| `StartupConfigLogger`, `PaymentHealthMetrics`, `StockRedisConfig`, `RedisConfig`, `ResponseAdvice` 등 7곳 | `@Value("${...}")` 필드 주입 (Lombok `@RequiredArgsConstructor` 와 혼용) | constructor injection 으로 통일 가능. 단 Spring SpEL 한계 / @ConfigurationProperties 도입이 더 큰 변경. **카테고리별 일괄 검토** |
| `TossPaymentGatewayStrategy`, `NicepayPaymentGatewayStrategy` | `@Value` 필드 주입 + 생성자 의존 혼합 | 명시 docstring 있음 (`@Value 필드는 생성자 방식으로 주입할 수 없어 필드 방식 유지`). **보존** |

---

## 5. 헥사고날 / 도메인 경계

- domain 모듈에 외부 framework import 0건 (정상)
- application 모듈에 infrastructure 직접 import 0건 (port 통해서만 — 정상)
- presentation → application port 만 의존 (정상)

→ **이상 0**. 보존.

---

## 6. 동시성 / 시간

| 항목 | 결과 | 비고 |
|---|---|---|
| `LocalDateTime.now()` 직접 호출 | 다수 잔존 (`PaymentReconciler` 등) | `LocalDateTimeProvider` / `Clock` 주입 권장 — 일부 잔존. **Minor** |
| `Instant.now()` 직접 호출 | 일부 잔존 | 동일 |
| `Thread.sleep(...)` in production | 0건 | (테스트는 의도) |
| `ConcurrentHashMap` / `AtomicLong` 사용 | 적절 | Fake 어댑터 / metrics |

### Minor 후보
- production 코드의 `LocalDateTime.now()` 직접 호출 → `LocalDateTimeProvider.now()` 로 일관 정리 가능. 단 caller chain 변경 비용 큼. **Defer**

---

## 7. Spring 사용 적정성

| 항목 | 결과 |
|---|---|
| Constructor injection (Lombok `@RequiredArgsConstructor`) | 일관 |
| `@Transactional` 위치 | application 계층 (정상) |
| `@Async` + executor wiring | 적절 |
| `@Scheduled` 룰 | `scheduler.enabled=false` 테스트 분리 |
| Bean cycle | 0건 (DuplicateApprovalDetectedEvent 패턴으로 단절) |

→ **이상 0**.

---

## 8. 노이즈

| 항목 | 결과 |
|---|---|
| `System.out.println` / `System.err.println` | 0건 |
| `printStackTrace()` | 0건 |
| 빈 `catch {}` 블록 | 0건 |
| `@Autowired` 필드 주입 | 0건 |
| TODO / FIXME / HACK | 0건 (앞선 sweep 에서 정리) |

→ **이상 0**.

---

## 9. Round 별 우선순위 매트릭스

| Round | 카테고리 | 항목 수 | 추정 |
|---|---|---:|---|
| 2 | Critical / Major | **0** | skip |
| 3a | 메서드 분리 | 2 | 30분 |
| 3b | Optional 패턴 정리 (A3, A4) | 5 | 30분 |
| 3c | boolean parameter → enum (A5) | 2 호출처 | 20분 |
| 3d | `LocalDateTime.now()` Provider 주입 일관 (Minor + Defer 혼합) | ~10 호출처 | 60분 (Defer 권장) |
| 4 | TODOS 등록 + REFACTOR-FINDINGS archive 이동 | — | 5분 |

**총 자율 가능 변경 ~9건 / 80분**. Critical/Major 0 — 코드 품질 베이스라인 매우 양호.

---

## 10. 결론

이 코드베이스는 다음 기준에서 합격선:
- 헥사고날 의존성 방향 (위반 0)
- DI 일관성 (Lombok constructor injection)
- 도메인 분리 (domain ↔ infrastructure 격리)
- 모던 자바 활용 (record / sealed / switch expression 적극)
- 노이즈 (println / 빈 catch / TODO 0건)

남은 정리는 **Minor** 카테고리만으로 한정 (메서드 분리 2건, Optional/boolean param 정리 7건). Round 3a/3b/3c 진행 후 종결 권장.
