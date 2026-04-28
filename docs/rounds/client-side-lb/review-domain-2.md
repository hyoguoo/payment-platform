# review-domain-2

**Topic**: CLIENT-SIDE-LB
**Round**: 2
**Persona**: Domain Expert
**Previous round ref**: docs/rounds/client-side-lb/review-domain-1.md

## Reasoning

Round 1 의 두 major (M1 transport 매핑 회귀 / M2 timeout 비명시) 가 commit `799e1f64` 로 모두 도메인 의미 동치로 복원됐다. `feign.RetryableException` 은 Feign 13.5 의 `FeignException.errorExecuting(Request, IOException)` 가 모든 transport-level IOException(SocketTimeoutException / ConnectException / UnknownHostException 등) 을 단일 wrapper 로 감싸는 정확한 boundary 라, 어댑터의 `catch (feign.RetryableException)` 한 번이 이전 baseline(2c1e2dd4) 의 `WebClientRequestException` catch 와 동일 범위를 커버한다. timeout baseline(2s/5s) 은 PITFALLS #3 의 cascade 패턴 위험을 60s → 14s(최악 두 호출 합산) 로 감축하고, T4-D 에 정밀 튜닝을 명시 deferred. minor(TC-5 ControllerAdvice)는 별도 토픽으로 명시 deferred. 새 도메인 리스크 없음 → **approve**.

## Domain risk checklist

- [x] **R1-M1 transport 매핑 회귀 — RESOLVED** — `ProductHttpAdapter.java:38-42` / `UserHttpAdapter.java:38-42` 가 try/catch 로 `feign.RetryableException` 잡고 `ProductServiceRetryableException.of(PRODUCT_SERVICE_UNAVAILABLE)` / `UserServiceRetryableException.of(USER_SERVICE_UNAVAILABLE)` 으로 변환 + `LogFmt.warn(... PRODUCT_SERVICE_RETRYABLE / USER_SERVICE_RETRYABLE ...)` emit. 도메인 코드(PaymentErrorCode.PRODUCT_SERVICE_UNAVAILABLE/E03031, USER_SERVICE_UNAVAILABLE/E03032) + LogFmt EventType 둘 다 baseline 과 정확히 동일. 컨트랙트 테스트로 변환 검증 (`ProductHttpAdapterContractTest.java:62-68`, `UserHttpAdapterContractTest.java:62-68`).
- [x] **R1-M2 timeout 명시 — RESOLVED** — `application.yml:17-24` 에 `spring.cloud.openfeign.client.config.default.{connectTimeout: 2000, readTimeout: 5000}` 추가. 프로파일 overlays(`application-docker.yml`, `application-benchmark.yml`, `application-test.yml`) 모두 feign.* 미오버라이드 확인 → 전 환경에 동일 baseline 적용. T4-D 에 Phase 4 SLO 정밀 튜닝 deferred 명시.
- [x] **R1-minor TC-5 ControllerAdvice — DEFERRED (acceptable)** — `TODOS.md:109-114` 의 TC-5 항목으로 분리. CLIENT-SIDE-LB 회귀가 아니라 pre-existing 임이 명시되어 있고, ErrorDecoder/어댑터 가 throw 시작한 시점이라 정렬이 가시화됐다는 맥락도 보존.
- [x] **transport boundary 완전성 검증** — Feign 13.5 sources 확인: `SynchronousMethodHandler.executeAndDecode` (L95-L105) 가 모든 IOException 을 `FeignException.errorExecuting(request, e)` 로 wrap, 이 메서드는 RetryableException 을 반환(L295-L302). 즉 connect refused / read timeout(SocketTimeoutException) / DNS(UnknownHostException) / socket reset 모두 단일 `feign.RetryableException` 으로 진입 → 어댑터 catch 한 번이 transport 전체를 커버. 누락 케이스 없음.
- [x] **응답 도착 후 4xx/5xx 분기 도메인 동치성** — 408/504/502/500 → ErrorDecoder 의 "그 외" 분기 → `IllegalStateException`. baseline(2c1e2dd4 시점 ProductHttpAdapter L70-86) 도 동일하게 NOT_FOUND/SERVICE_UNAVAILABLE/TOO_MANY_REQUESTS 외 status 는 IllegalStateException 으로 매핑했고, GlobalExceptionHandler.catchRuntimeException 이 500 으로 떨어뜨리는 결말도 동일. **회귀 없음**. (408/504 가 RFC 의미상 retryable 이라는 도메인 약점이 있지만 본 PR 의 회귀가 아니라 baseline 부터 그러했음 — 별도 토픽으로 분리해야 할 사안이며 round 2 finding 아님.)
- [x] **상태 전이 / 멱등성 / race window 무영향** — Round 1 에서 이미 검증. 본 변경(799e1f64) 은 어댑터 레이어 try/catch + yml 추가만으로 use case / TX 경계 / outbox 머신 / dedupe 모두 변경 없음.
- [x] **PII / amount 무관** — 호출 경로 동일.
- [x] **traceparent 전파 무영향** — micrometer auto-wire 는 Feign + LB 조합에 그대로 동작. 어댑터 try/catch 추가는 trace context 에 영향 없음.

## 도메인 관점 추가 검토

1. **R1-M1 → 회복성 신호 chain 완전 복원** — `ProductHttpAdapter.java:35-42` 와 `UserHttpAdapter.java:35-42` 의 try/catch 가 (a) 도메인 예외 클래스, (b) PaymentErrorCode, (c) LogFmt EventType 세 신호 모두 baseline 과 동일하게 emit. 운영 대시보드/알람 룰이 `PRODUCT_SERVICE_RETRYABLE` / `USER_SERVICE_RETRYABLE` LogFmt 이벤트에 의존했을 경우의 silent 회귀 가능성 제거. 단위 테스트 (`*ContractTest.java:62-68`) 는 mock `feign.RetryableException` 을 throw 하고 `ProductServiceRetryableException` / `UserServiceRetryableException` 으로 변환되는지를 단정 — Round 1 의 'getProduct_WhenFeignThrowsRetryableException_ShouldMapToProductServiceUnavailable' 권장과 동등한 검증.

2. **R1-M2 → checkout TX 점유 한계 재차 가시화** — `application.yml:23-24` 의 `connectTimeout: 2000` + `readTimeout: 5000` 로 외부 호출 한 건당 worst-case 7s, `PaymentCheckoutServiceImpl.checkout` 가 user + product 두 호출을 순차 호출하므로 TX 안 외부 호출 상한 ~14s. 이전 60s 까지 확장됐던 회귀가 baseline 수준으로 회복. 다만 `@Transactional(timeout=N)` 자체는 명시 안 됐는데 — Round 1 에서 권장 suggestion 으로 추가했으나 failing checklist 항목은 아니었다(Feign timeout 명시 자체가 핵심). pre-existing 동일 (baseline 2c1e2dd4 시점 PaymentCheckoutServiceImpl 도 timeout 미명시) → 회귀 아님. 본 토픽 finding 으로 들지 않는다.

3. **transport boundary 완전성 (Feign 13.5 source 확인)** — `feign.SynchronousMethodHandler.executeAndDecode` 의 `try { client.execute(...) } catch (IOException e) { throw errorExecuting(request, e); }` 구조가 (a) connect-time IOException, (b) read-time SocketTimeoutException(IOException 서브), (c) DNS(UnknownHostException) 모두 `feign.RetryableException` 단일 wrapper 로 깔때기. 어댑터의 `catch (feign.RetryableException)` 가 잡지 못하는 transport 케이스 없음. (예외: Feign 가 응답을 받았지만 본문 decode 단계에서 IOException — 이건 `errorReading` 으로 별도 wrapping 되며 FeignException 서브이지만 Retryable 아님. 그러나 GET + record decode 가 단순한 본 경로에서 read-during-decode 실패가 도메인 retryable 인지 transient 인지 모호하므로 IllegalStateException catch-all 로 떨어지는 현재 동작이 baseline 과 동치. 별도 finding 아님.)

4. **응답 도착 후 분기 — 408/504 ⊃ "그 외"** — ErrorDecoder 가 408 / 504 를 별도 retryable 로 분류하지 않고 IllegalStateException 으로 보내는 점은 RFC 의미상 retryable 누락이지만 baseline(2c1e2dd4) 도 동일했음. 본 토픽이 만든 회귀가 아니므로 finding 으로 들지 않는다 — 향후 TC-5 와 함께 정렬할 후보 항목으로 메모해두는 정도가 적절(이미 TC-5 가 Retryable 매핑 정렬을 다룸).

5. **timeout baseline 의 도메인 약점 — Phase 4 deferred 가 충분히 명시됨** — 2s connect 는 docker compose 같은 빠른 네트워크에 적당하나 운영 환경 / cross-region 에서는 짧을 수 있음. 5s read 는 product/user-service 의 GET 경로(단순 엔티티 조회)에 적당. 도메인 관점에서 "빨리 끊고 retry-after" 가 "오래 기다려 응답 받기" 보다 안전(checkout 은 사용자가 기다리는 동기 경로). 따라서 baseline 자체로 도메인 회복성에 +. T4-D (TODOS.md:36-42) 에 SLO 측정 기반 정밀 튜닝 명시 → round 2 도메인 finding 아님.

6. **fallbackFactory 마이그레이션 deferred 의 도메인 의미** — 현재 어댑터 try/catch + ErrorDecoder 이중 구조는 도메인적으로 회복성 신호를 정확히 전달하므로 동작 측면에선 문제 없음. T4-D 시점에 fallbackFactory 단일 경로로 정리 예정이라는 점이 PLAN(L227) + TODOS(L41) 양쪽에 명시. 도메인 finding 아님.

7. **smoke script 변경(3c75b384, 32c30ccc) 도메인 영향** — `infra-healthcheck.sh` / `trace-continuity-check.sh` 의 product-service 다중 인스턴스 동적 검사 + COMPOSE_PROJECT_NAME 변수화는 운영 도구 변경. 결제 비즈니스 로직 / 상태 머신 / 멱등성 모두 무관. finding 아님.

## Findings

(Round 1 finding 별 status delta)

- [resolved] **R1-major-1 / 외부 시스템 실패 모드 — 네트워크 IO 실패의 도메인 매핑 누락** — `ProductHttpAdapter.java:38-42` + `UserHttpAdapter.java:38-42` try/catch 추가 + 컨트랙트 테스트 (`*ContractTest.java:62-68`) 검증. PRODUCT_SERVICE_UNAVAILABLE / USER_SERVICE_UNAVAILABLE 도메인 코드 + PRODUCT_SERVICE_RETRYABLE / USER_SERVICE_RETRYABLE LogFmt 이벤트 모두 baseline 과 동일하게 emit.
- [resolved] **R1-major-2 / timeout — Feign client timeout 비명시** — `application.yml:17-24` baseline (connectTimeout 2000ms / readTimeout 5000ms) 추가. T4-D (TODOS.md:36-42) 에 Phase 4 SLO 정밀 튜닝 deferred 명시.
- [deferred] **R1-minor / 429/503 → 클라이언트 500 응답 (pre-existing)** — TC-5 (TODOS.md:109-114) 별도 토픽으로 분리. CLIENT-SIDE-LB 회귀 아님 + ErrorDecoder/어댑터 가 throw 시작한 가시성 맥락 보존.

새 finding: 0건 (critical 0 / major 0 / minor 0).

판정: 새 critical/major 0건 + Round 1 도메인 리스크 모두 resolved 또는 명시 deferred → **approve**.

## JSON

```json
{
  "stage": "review",
  "persona": "domain-expert",
  "round": 2,
  "task_id": null,

  "decision": "approve",
  "reason_summary": "Round 1 의 major 2건(M1 transport 매핑, M2 Feign timeout) 모두 도메인 의미 동치로 resolved. minor 1건은 TC-5 별도 토픽으로 명시 deferred. 새 도메인 리스크 0건.",

  "checklist": {
    "source": "_shared/personas/domain-expert.md (review 도메인 리스크 체크리스트)",
    "items": [
      {
        "section": "external-failure",
        "item": "R1-M1 — transport-level 실패의 도메인 예외/이벤트 매핑 복원",
        "status": "yes",
        "evidence": "ProductHttpAdapter.java:38-42 + UserHttpAdapter.java:38-42 try/catch (feign.RetryableException → Product/UserServiceRetryableException + LogFmt PRODUCT_SERVICE_RETRYABLE / USER_SERVICE_RETRYABLE). 컨트랙트 테스트 *ContractTest.java:62-68 로 변환 검증."
      },
      {
        "section": "timeout",
        "item": "R1-M2 — Feign timeout baseline 명시",
        "status": "yes",
        "evidence": "application.yml:17-24 spring.cloud.openfeign.client.config.default.{connectTimeout:2000, readTimeout:5000}. 프로파일 overlays 미오버라이드 확인. T4-D (TODOS.md:36-42) Phase 4 SLO 정밀 튜닝 deferred 명시."
      },
      {
        "section": "external-failure",
        "item": "R1-minor — Retryable 예외 ControllerAdvice 매핑 (pre-existing)",
        "status": "n/a",
        "evidence": "TC-5 (TODOS.md:109-114) 별도 토픽 deferred. CLIENT-SIDE-LB 회귀 아니라는 점 + 가시성 맥락 보존됨."
      },
      {
        "section": "external-failure",
        "item": "transport boundary 완전성 (RetryableException 이외 누락 케이스)",
        "status": "yes",
        "evidence": "Feign 13.5 SynchronousMethodHandler.executeAndDecode L95-L105 + FeignException.errorExecuting L295-L302 — 모든 IOException(SocketTimeoutException/ConnectException/UnknownHostException 등)을 RetryableException 단일 wrapper 로 감싸므로 어댑터 catch 한 번이 transport 전체 커버."
      },
      {
        "section": "external-failure",
        "item": "응답 도착 후 4xx/5xx 분기 도메인 동치성",
        "status": "yes",
        "evidence": "ErrorDecoder ProductFeignConfig L43-62 / UserFeignConfig L43-62 가 404/429/503 도메인 매핑 + 그 외 IllegalStateException. baseline(2c1e2dd4) ProductHttpAdapter.mapResponseException 분기와 정확히 동일."
      },
      {
        "section": "state-transition",
        "item": "PaymentEvent / PaymentOutbox 상태 머신 무영향",
        "status": "yes",
        "evidence": "어댑터 try/catch + yml 추가만 — 상태 머신 / TX 경계 / dedupe 변경 없음."
      },
      {
        "section": "idempotency",
        "item": "자동 재시도로 인한 멱등성 위험 무영향",
        "status": "yes",
        "evidence": "Feign Retryer.NEVER_RETRY 기본 + spring-retry 미포함 + LB retry 비활성 (Round 1 검증 그대로 유효)."
      },
      {
        "section": "observability",
        "item": "LogFmt 도메인 이벤트 emit 회귀 없음",
        "status": "yes",
        "evidence": "PRODUCT_SERVICE_RETRYABLE / USER_SERVICE_RETRYABLE 이벤트가 ErrorDecoder(429/503) + 어댑터(transport) 양쪽에서 emit (grep 검사 4 hits). 운영 대시보드/알람 룰에 회귀 없음."
      }
    ],
    "total": 8,
    "passed": 7,
    "failed": 0,
    "not_applicable": 1
  },

  "scores": {
    "correctness": 0.92,
    "conventions": 0.90,
    "discipline": 0.92,
    "test_coverage": 0.85,
    "domain": 0.92,
    "mean": 0.90
  },

  "findings": [],

  "previous_round_ref": "docs/rounds/client-side-lb/review-domain-1.md",
  "delta": {
    "R1-major-1": "resolved",
    "R1-major-2": "resolved",
    "R1-minor-1": "deferred",
    "new_findings_count": 0
  },

  "unstuck_suggestion": null
}
```
