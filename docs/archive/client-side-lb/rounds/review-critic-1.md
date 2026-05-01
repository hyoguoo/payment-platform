# review-critic-1

**Topic**: CLIENT-SIDE-LB
**Round**: 1
**Persona**: Critic
**Stage**: review
**Range**: `2c1e2dd4..HEAD` (Phase B + 후속 scale-aware 처리)

## 판정

`revise` — critical 0건 / major 2건 / minor 4건.

Phase B 코드 변경은 전반적으로 구조적으로 단정하다 (Feign 도입, ErrorDecoder 4분기 매핑, dead code 제거가 일관되게 수행, `@Configuration` 미부착 정책 준수). 다만 **이전 구현(WebClient/HttpOperator)이 처리하던 두 가지 동작이 Feign 전환에서 누락**됐다 — silent behavior change 두 건이 major.

## Findings

### M1 (major) — Transport-level 예외가 ProductServiceRetryableException / UserServiceRetryableException 으로 더 이상 매핑되지 않음 (회귀)

- **위치**:
  - `payment-service/src/main/java/.../infrastructure/adapter/http/ProductHttpAdapter.java:27-30`
  - `payment-service/src/main/java/.../infrastructure/adapter/http/UserHttpAdapter.java:27-30`
  - `payment-service/src/main/java/.../infrastructure/adapter/http/feign/ProductFeignConfig.java`
  - `payment-service/src/main/java/.../infrastructure/adapter/http/feign/UserFeignConfig.java`
- **문제**: 이전 `ProductHttpAdapter` (commit `2c1e2dd4` 기준) 는 `WebClientRequestException` (connect 실패 / 호스트 unreachable / IO 에러) 을 catch 해서 `ProductServiceRetryableException.of(PRODUCT_SERVICE_UNAVAILABLE)` 으로 매핑했다. Feign 전환 후엔 transport-level 실패가 `feign.RetryableException` 으로 그대로 propagate 된다. ErrorDecoder 는 HTTP 응답이 도착해야 호출되므로 transport 단을 다루지 못한다. 호출자 (UseCase) 가 retryable 신호를 받지 못함.
- **증거**: `git show 2c1e2dd4:.../ProductHttpAdapter.java` 의 `callGet()` 메서드에 `catch (WebClientRequestException e)` 분기. 현재는 try/catch 없이 직접 위임. ProductFeignConfig.mapToException 은 Response 인자만 받음.
- **제안**:
  1. (선호) 어댑터의 위임 메서드에 `try { ... } catch (feign.RetryableException e) { throw ProductServiceRetryableException.of(...); }` 추가 + contract test 보강
  2. 의도적 deferral 이라면 PLAN.md / topic.md 에 "Phase 4 `@CircuitBreaker` 에서 통합" 으로 명시

### M2 (major) — Feign connect/read timeout 명시 설정 부재 (silent default 변경)

- **위치**: `payment-service/src/main/resources/application*.yml` 전체
- **문제**: 이전 `application-docker.yml` 의 `myapp.toss-payments.http.read-timeout-millis: 30000` 이 B5 에서 제거됐는데 그 결과 cross-service HTTP timeout 이 **명시 30s → 암묵 default (Spring Cloud OpenFeign default: connectTimeout=10s, readTimeout=60s)** 로 바뀌었다. payment-service 의 결제 critical path 가 동기 + `@Transactional` 이라 timeout 길어짐 = DB connection 점유 시간 확장 = cascade 장애 위험 회귀 (PITFALLS #3 패턴).
- **증거**: `grep "feign:" payment-service/src/main/resources/application*.yml` 0건. `git show 2c1e2dd4:.../application-docker.yml` 에 30000 명시 존재.
- **제안**: `application.yml` 에 `spring.cloud.openfeign.client.config.default.{connectTimeout, readTimeout}` 명시. 값을 Phase 4 에서 결정하더라도 PLAN/STATE 에 deferred 로 기록.

### m1 (minor) — `PaymentPlatformApplication.java:7` 의 stale 주석

- 주석 `// basePackages 명시 생략 — main 클래스 하위 자동 scan, B2 에서 ProductFeignClient 등록 예정` 의 "B2 에서 등록 예정" 은 B7 완료 후 stale.
- 제안: `// basePackages 생략 — main 클래스 하위 자동 scan 으로 ProductFeignClient / UserFeignClient 등록.`

### m2 (minor) — Feign 산출물의 javadoc 에 phase ID 잔존

- `ProductFeignClient.java:12` "ErrorDecoder (B3) 가 담당", `UserFeignClient.java:12` 동일
- `ProductHttpAdapterContractTest.java:21` "B6 에서 별도 검증", `UserHttpAdapterContractTest.java:21` 동일
- 제안: phase ID 빼고 사실 기술 — "4xx/5xx → 도메인 예외 매핑은 ProductFeignConfig 의 ErrorDecoder 가 담당."

### m3 (minor) — Smoke script 의 `docker-` project name prefix hardcoding

- `scripts/smoke/infra-healthcheck.sh:96` + `scripts/smoke/trace-continuity-check.sh:270`
- `docker ps --filter "name=docker-product-service-"` 가 compose default project name 이 `docker` 인 가정에 의존. `-p other` / `COMPOSE_PROJECT_NAME=other` 시 0건 매칭.
- 제안: `${COMPOSE_PROJECT_NAME:-docker}` 변수화 또는 `com.hyoguoo.application=product-service` label 사용 (`apps.yml:127`).

### m4 (minor) — Adapter contract test 의 mock-the-mock 우려

- `ProductHttpAdapterContractTest.java:38-56` + `UserHttpAdapterContractTest.java:38-56`
- B4 후 어댑터가 단순 위임이라 "mock throw → adapter throw" 검증은 try/catch 부재 확인일 뿐 trivial. M1 해결 후 transport 매핑 케이스 추가되면 의미 회복.

## scores

- correctness 0.72 (M1 transport 회귀, M2 timeout silent default)
- conventions 0.92
- discipline 0.95
- test-coverage 0.78
- domain 0.85
- mean 0.844

## decision

`revise`
