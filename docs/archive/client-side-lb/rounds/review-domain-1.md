# review-domain-1

**Topic**: CLIENT-SIDE-LB
**Round**: 1
**Persona**: Domain Expert

## Reasoning
4xx (404/429/503/그 외 5xx) HTTP 응답 분기는 새 ErrorDecoder 가 이전 어댑터의 분기를 동일하게 재현한다. 그러나 **네트워크 IO 실패** (connect refused / read timeout / DNS 실패 등) 의 도메인 매핑이 누락되었고, **Feign 기본 timeout (10s connect / 60s read)** 이 이전 명시값(3s connect)보다 훨씬 길어진 채 `@Transactional` 체크아웃 경로에서 사용된다. 결과로 (a) 도메인 회복성 신호 손실 (`ProductServiceRetryableException` / `LogFmt PRODUCT_SERVICE_RETRYABLE` 미발생), (b) Hikari 커넥션 점유 시간 최대 ~70초까지 확장 가능, (c) Phase 4 CircuitBreaker 가 IO 실패를 typed catch 로 분류 못 함. 4xx/5xx 응답 분기는 OK.

## Domain risk checklist

- [x] **상태 전이 무영향** — 본 변경은 cross-service 조회(GET) 만 다루며 PaymentEventStatus 머신·outbox 머신은 변경 없음. checkout 단계 실패는 기존과 동일하게 4xx/5xx 응답으로 끝나고 PaymentEvent 가 생성되기 *전* 단계라 고아 row 위험 없음.
- [x] **멱등성 무영향** — Feign 호출은 모두 GET. spring-retry 미설치 + Spring Cloud LoadBalancer retry 비활성 + Feign 기본 `Retryer.NEVER_RETRY` 확인. POST 자동 재시도로 인한 중복 부과 위험 없음.
- [x] **race window 무영향** — product-service 다중 인스턴스 시 동일 productId 조회는 read-only 이며 stock 차감은 payment 가 자기 redis-stock 으로 처리(SoT 모델). LB scale-up 자체가 race 를 새로 만들지 않음.
- [!] **외부 시스템 실패 모드 회귀 — 네트워크 IO 실패의 도메인 매핑 누락**. 이전 어댑터는 `WebClientRequestException` 을 잡아 `ProductServiceRetryableException(PRODUCT_SERVICE_UNAVAILABLE)` 로 매핑 + LogFmt 이벤트 발행. Feign 전환 후 `feign.RetryableException` 이 그대로 propagate 되어 도메인 코드/이벤트 손실.
- [!] **timeout 정책 — 비명시로 인한 critical-path 점유 확장**. 이전 명시값 (3s connect, ~5–30s read) 이 application-*.yml 에서 제거됐으나 Feign 측 대응 설정 없음. Spring Cloud OpenFeign 4.x 기본은 connect 10s + read 60s → `@Transactional` checkout 경로에서 product/user-service 가 행(hang) 시 70초까지 Hikari 점유. PITFALLS #3 (TX 안 동기 외부 호출 점유) 와 정확히 같은 패턴.
- [x] **PII / amount 무관** — 본 변경 경로(getProductById/getUserById)는 결제 금액·승인 시각 처리 흐름 아님. `paymentEvent.totalAmount` vs `message.amount` 양방향 방어는 별도 경로(Kafka confirm 응답)에서 동작.
- [x] **traceparent 전파** — B7 검증에서 5-service chain 완주 확인. Spring Cloud OpenFeign 4.x + spring-cloud-starter-loadbalancer 4.x 조합은 micrometer ObservationRegistry 자동 wiring (Spring Boot 3.2+ auto-config). 회귀 위험 없음.
- [x] **smoke override 무영향** — payment→product/user 경로는 fake PG 모드와 무관. ErrorDecoder 는 응답 바디 유무 상관없이 `Response.body() == null` 가드 포함 (ProductFeignConfig L65–69).

## 도메인 관점 추가 검토

1. **`feign.RetryableException` 형 leak** — `ProductHttpAdapter` (L24, L28) 는 try/catch 없이 `productFeignClient.getProductById(productId)` 를 그대로 호출. 컴파일 타임에는 `feign.*` 이 직접 import 되지 않지만 런타임에는 Feign 프록시가 IO 실패 시 `feign.RetryableException` 을 던지고 어댑터가 그대로 propagate → use case → controller advice 까지 전파. `PaymentExceptionHandler` 에 매처 없음 → `GlobalExceptionHandler.catchRuntimeException` (L26) 의 `RuntimeException` 매처가 잡아서 500. **결과 HTTP 상태(500)는 동일하나** 도메인 LogFmt 이벤트 `PRODUCT_SERVICE_RETRYABLE` 가 더 이상 발생하지 않음 → 운영 대시보드 / 알람 룰이 이 이벤트에 의존했다면 silent 회귀.

2. **timeout 비명시 — TX-점유 회귀** — 이전 `HttpOperatorImpl` (삭제됨, 2c1e2dd4 시점 L29–35) 은 `connect 3s` 와 `myapp.toss-payments.http.read-timeout-millis` 환경변수로 명시. B5 에서 4개 application*.yml 에서 해당 설정 일괄 제거. application.yml 에 `feign.client.config.default.{connect,read}-timeout` 블록 추가 없음. Spring Cloud OpenFeign 4.2.0 기본값은 `feign.Request.Options.DEFAULT` (connect 10s, read 60s). `PaymentCheckoutServiceImpl.checkout` (L35 `@Transactional`) 가 `OrderedUserUseCase` + `OrderedProductUseCase` 를 동기 호출하므로, product-service 한 인스턴스가 GC pause 등으로 행(hang) 시 최악 60s 까지 Hikari 커넥션 + Tomcat 스레드 점유. 이전 환경에서는 ~3-30s 안에 끊겼던 경로가 60s 까지 확장된다. PITFALLS #3 의 cascade 장애 패턴.

3. **429/503 → 500 응답 (pre-existing, 본 변경에서 그대로 유지)** — `ProductServiceRetryableException` / `UserServiceRetryableException` 둘 다 `PaymentExceptionHandler` 에 핸들러 없음. 이전부터 GlobalExceptionHandler 의 `RuntimeException` catch-all 로 떨어져 500 매핑. 본 변경이 만든 회귀는 아니지만, ErrorDecoder 가 이 예외를 명시적으로 throw 하므로 의미가 강조됨에도 불구하고 controller advice 가 미정렬 상태. retry-after 시그널이 호출자(브라우저/Gateway)에 전달되지 않고 `ProductNotFoundException` / `UserNotFoundException` 만 4xx 로 떨어진다. 본 PR 의 비범위지만 ErrorDecoder 도입으로 가시성이 높아져 함께 정렬할 필요 있음.

4. **404 의미 동일성 OK** — `ProductFeignConfig.mapToException` (L47–51) / `UserFeignConfig.mapToException` (L47–51) 가 404 → `ProductNotFoundException(PRODUCT_NOT_FOUND)` / `UserNotFoundException(USER_NOT_FOUND)` 매핑. PaymentExceptionHandler L74–96 가 둘 다 404 응답. 이전 어댑터의 매핑 (2c1e2dd4 시점 ProductHttpAdapter L70–74) 과 정확히 동일. checkout 도중 product 가 삭제된 경우 동작 일관.

5. **B5 (WebClient/HttpOperator 일체 제거) 도메인 영향** — 외부 PG (Toss/NicePay) 호출은 pg-service 가 별도 `HttpOperatorImpl` 로 전담하므로 본 제거가 PG 직호출 경로를 끊지 않음 (`docs/CLIENT-SIDE-LB-PLAN.md` L157 의 결정 일치). 향후 payment-service 가 외부 비-LB host 를 호출할 일이 생기면 webflux 의존성을 다시 추가해야 하지만, 결제 도메인 관점에서는 "현재 책임 = cross-service 호출만, 외부 PG = pg-service 전담" 이 명확해진 것이 안정성에 +. 도메인 finding 아님.

6. **scale-aware smoke (후속 두 commit) 도메인 영향 없음** — `infra-healthcheck.sh` / `trace-continuity-check.sh` 의 product-service 다중 컨테이너 동적 검사는 운영 스모크 도구 변경. 결제 비즈니스 로직 무영향.

## Findings

- [major] **외부 시스템 실패 모드 / observability** — 네트워크 IO 실패의 도메인 매핑 누락 (`ProductHttpAdapter.java:24-29`, `UserHttpAdapter.java:24-29`)
- [major] **timeout / TX 점유** — Feign client timeout 비명시로 checkout `@Transactional` 점유 시간이 최대 60s 까지 확장됨 (`payment-service/src/main/resources/application.yml`)
- [minor] **외부 시스템 실패 모드 (pre-existing)** — `ProductServiceRetryableException` / `UserServiceRetryableException` 가 controller advice 에 등록 안 되어 429/503 이 클라이언트에 500 으로 보임 (`PaymentExceptionHandler.java:23-97`)

판정: `major` 2건 + `minor` 1건, `critical` 0건 → **revise**.

## JSON

```json
{
  "stage": "review",
  "persona": "domain-expert",
  "round": 1,
  "task_id": null,

  "decision": "revise",
  "reason_summary": "4xx/5xx HTTP 응답 분기는 동치. 그러나 네트워크 IO 실패의 도메인 예외 매핑 누락 + Feign timeout 비명시로 TX 점유 확장 — 두 major 회귀.",

  "checklist": {
    "source": "_shared/personas/domain-expert.md (review 도메인 리스크 체크리스트)",
    "items": [
      {
        "section": "state-transition",
        "item": "PaymentEventStatus / outbox 상태 머신 무영향",
        "status": "yes",
        "evidence": "Feign 변경은 read-only GET 경로만 영향. checkout 진입 전 단계로 PaymentEvent 미생성 상태."
      },
      {
        "section": "idempotency",
        "item": "자동 재시도로 인한 멱등성 위험 없음",
        "status": "yes",
        "evidence": "Spring Cloud OpenFeign 4.2.0 기본 Retryer.NEVER_RETRY + spring-retry 미포함 + LB retry 비활성 (build.gradle, application*.yml 검사)"
      },
      {
        "section": "external-failure",
        "item": "네트워크 IO 실패 시 도메인 예외/이벤트 매핑 유지",
        "status": "no",
        "evidence": "ProductHttpAdapter.java:24-29 — try/catch 없음. ProductFeignConfig.mapToException 은 ErrorDecoder 만이라 IOException 미커버. 이전 HttpAdapter (2c1e2dd4) 는 WebClientRequestException → ProductServiceRetryableException 매핑 보유."
      },
      {
        "section": "timeout",
        "item": "외부 호출 timeout 명시로 critical-path 점유 한계 가시화",
        "status": "no",
        "evidence": "application.yml / application-docker.yml / application-benchmark.yml / application-test.yml 에 feign.client.config.* 블록 0건. 이전 명시 (HttpOperatorImpl L20-22 connect 3s + read 환경변수) 가 B5 에서 함께 제거됨."
      },
      {
        "section": "race-window",
        "item": "다중 인스턴스 시 stock/order race 무영향",
        "status": "yes",
        "evidence": "본 변경은 read-only GET. payment 측 redis-stock 차감은 별도 경로 (PaymentTransactionCoordinator), product RDB 누적 차감은 APPROVED 시 별도 Kafka."
      },
      {
        "section": "observability",
        "item": "traceparent 5-service chain 전파",
        "status": "yes",
        "evidence": "B7 plan L186 — smoke-all Phase 2 PASS. Spring Cloud OpenFeign 4.x + LoadBalancer 4.x 는 micrometer auto-wire."
      },
      {
        "section": "external-failure",
        "item": "429/503 → 클라이언트가 transient 인지 가능한 응답 코드",
        "status": "no",
        "evidence": "PaymentExceptionHandler.java:23-97 에 ProductServiceRetryableException/UserServiceRetryableException 핸들러 부재. GlobalExceptionHandler.catchRuntimeException (L26) catch-all 로 500 응답. pre-existing 이지만 ErrorDecoder 도입으로 가시화됨."
      }
    ],
    "total": 7,
    "passed": 4,
    "failed": 3,
    "not_applicable": 0
  },

  "scores": {
    "correctness": 0.78,
    "conventions": 0.85,
    "discipline": 0.80,
    "test_coverage": 0.65,
    "domain": 0.62,
    "mean": 0.74
  },

  "findings": [
    {
      "severity": "major",
      "checklist_item": "네트워크 IO 실패 시 도메인 예외/이벤트 매핑 유지",
      "category": "external-failure / observability",
      "location": "payment-service/src/main/java/com/hyoguoo/paymentplatform/payment/infrastructure/adapter/http/ProductHttpAdapter.java:24-29 + UserHttpAdapter.java:24-29 + ProductFeignConfig.java:39-41 + UserFeignConfig.java:39-41",
      "problem": "Feign client 호출 중 connect refused / socket timeout / DNS 실패 등 네트워크 IO 에러는 ErrorDecoder 가 잡지 않는다 (ErrorDecoder 는 HTTP 응답이 도착했을 때만 발화). Feign 은 IOException 을 feign.RetryableException 으로 감싸 그대로 throw, 어댑터에 try/catch 없으므로 use case 까지 그대로 propagate. 이전 어댑터 (커밋 2c1e2dd4 의 ProductHttpAdapter L62-66) 는 WebClientRequestException 을 잡아 ProductServiceRetryableException(PRODUCT_SERVICE_UNAVAILABLE) 로 매핑하고 LogFmt EventType.PRODUCT_SERVICE_RETRYABLE 이벤트를 발행했었다. Feign 전환 후 이 도메인 신호가 사라진다.",
      "evidence": "ProductHttpAdapter.java:24-29 (try/catch 부재). ProductFeignConfig.productErrorDecoder L38-41 — ErrorDecoder 만 등록, customizer 로 IOException 변환 매핑 미설치. 회귀 결과: HTTP 응답 자체는 GlobalExceptionHandler 의 RuntimeException catch-all 이 500 으로 동일 매핑하지만 (a) PaymentErrorCode (PRODUCT_SERVICE_UNAVAILABLE / USER_SERVICE_UNAVAILABLE) 가 응답에 안 실림, (b) LogFmt 이벤트가 EventType.PRODUCT_SERVICE_RETRYABLE → EventType.EXCEPTION 으로 격이 떨어져 운영 알람 룰 회귀 가능, (c) Phase 4 CircuitBreaker 가 typed catch 로 IO 실패를 분류할 핸들이 사라짐.",
      "suggestion": "두 어댑터에 얇은 try/catch 추가하거나 (이전 패턴) 또는 ProductFeignConfig 에 feign.codec.ErrorDecoder + feign.RetryableException 변환을 모두 처리하는 어댑터 추가. 권장 패턴: ProductHttpAdapter 가 productFeignClient.getProductById(...) 호출을 try { } catch (feign.RetryableException ioFailure) { LogFmt.warn(... PRODUCT_SERVICE_RETRYABLE ...); throw ProductServiceRetryableException.of(PaymentErrorCode.PRODUCT_SERVICE_UNAVAILABLE); } 로 감싸 도메인 신호 회복. UserHttpAdapter 동일 패턴. 단위 테스트는 ProductHttpAdapterContractTest 에 'getProduct_WhenFeignThrowsRetryableException_ShouldMapToProductServiceUnavailable' 케이스 추가."
    },
    {
      "severity": "major",
      "checklist_item": "외부 호출 timeout 명시로 critical-path 점유 한계 가시화",
      "category": "timeout / TX-occupancy",
      "location": "payment-service/src/main/resources/application.yml + application-docker.yml + application-benchmark.yml + application-test.yml — feign.client.config.* 블록 부재",
      "problem": "이전 HttpOperatorImpl (커밋 2c1e2dd4 의 L18-30) 은 connect 3000ms, read=myapp.toss-payments.http.read-timeout-millis 환경변수로 명시. B5 에서 HttpOperatorImpl 과 함께 read-timeout-millis 블록을 4개 application*.yml 에서 일괄 제거. Feign 측 대응 설정 없음 → Spring Cloud OpenFeign 4.2.0 기본값 connect 10s + read 60s 적용. PaymentCheckoutServiceImpl.checkout (L35) 가 @Transactional + 동기 호출 (orderedUserUseCase.getUserInfoById, orderedProductUseCase.getProductInfoList) 이므로, product-service 한 인스턴스가 GC pause / DB lock / Eureka stale instance 로 행(hang) 시 최대 60-70초 Hikari 커넥션 + Tomcat 스레드 점유. PITFALLS #3 의 'TX 안 동기 외부 호출이 cascade 장애로 번지는' 패턴이 정확히 재현됨.",
      "evidence": "Plan B5 결과 보고 (CLIENT-SIDE-LB-PLAN.md L159) — 'application.yml product-service/user-service base-url 블록, application-docker.yml / application-benchmark.yml / application-test.yml myapp.toss-payments.http.read-timeout-millis 블록 (4개)' 삭제 명시. application.yml grep 결과 feign 키 0건. Spring Cloud 2024.0.0 → OpenFeign 4.2.x → feign-core 13.x default Request.Options(connectTimeout=10000, readTimeout=60000).",
      "suggestion": "application.yml 에 다음 블록 추가:\\n  feign:\\n    client:\\n      config:\\n        default:\\n          connect-timeout: 3000\\n          read-timeout: 5000\\n          logger-level: BASIC\\n또는 product/user 별 분리 (feign.client.config.product-service.* / user-service.*). PaymentCheckoutServiceImpl 의 @Transactional 도 명시적 timeout=5 추가 권장 (이미 다른 use case 에 통용되는 컨벤션 — CONFIRM-FLOW-ANALYSIS.md '핵심 멱등성 / 가드' 섹션의 confirm TX timeout=5 와 정렬). 단위 테스트로 timeout 자체를 검증하기는 어려우니 yml 변경 + property test 또는 docs 갱신으로 갈음."
    },
    {
      "severity": "minor",
      "checklist_item": "429/503 → 클라이언트가 transient 인지 가능한 응답 코드",
      "category": "external-failure / API-contract",
      "location": "payment-service/src/main/java/com/hyoguoo/paymentplatform/payment/exception/common/PaymentExceptionHandler.java:23-97",
      "problem": "ProductServiceRetryableException / UserServiceRetryableException 에 대한 @ExceptionHandler 미등록. GlobalExceptionHandler.catchRuntimeException (L26) 의 RuntimeException catch-all 이 500 INTERNAL_SERVER_ERROR + GlobalErrorCode.INTERNAL_SERVER_ERROR 로 매핑. 결과로 product/user-service 가 503/429 로 retry-after 시그널을 보내도 Gateway/브라우저는 일반 500 으로 인지 → 재시도 정책 / SLO 분류 불가. 본 PR 이 새로 만든 회귀는 아니지만 ErrorDecoder 가 명시적으로 throw 하기 시작하면서 가시성이 높아진 시점이라 함께 정렬할 가치 있음.",
      "evidence": "PaymentExceptionHandler.java 에 ProductServiceRetryableException / UserServiceRetryableException 매처 0건 (grep 검사). PaymentErrorCode.PRODUCT_SERVICE_UNAVAILABLE (E03031) / USER_SERVICE_UNAVAILABLE (E03032) 가 enum 에는 존재하나 응답에 실리지 않음.",
      "suggestion": "PaymentExceptionHandler 에 503 매핑 핸들러 추가:\\n  @ExceptionHandler({ProductServiceRetryableException.class, UserServiceRetryableException.class})\\n  ResponseEntity<ErrorResponse> handleServiceUnavailable(...) {\\n    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)\\n        .header(HttpHeaders.RETRY_AFTER, '5')\\n        .body(ErrorResponse.of(e.getCode(), e.getMessage()));\\n  }\\nFinding 1 의 IOException 매핑까지 같이 처리하면 retry-after 시그널이 일관되게 클라이언트에 전달됨."
    }
  ],

  "previous_round_ref": null,
  "delta": null,

  "unstuck_suggestion": null
}
```
