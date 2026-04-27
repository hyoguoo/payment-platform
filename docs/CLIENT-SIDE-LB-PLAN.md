# Plan — Client-side Load Balancing 도입 (A → B)

> 작성: 2026-04-27 · 활성 토픽 — `docs/topics/CLIENT-SIDE-LB.md` 참고.
>
> 진행 순서: **Phase A** (LoadBalanced WebClient) 완료 → 검증 → **Phase B** (OpenFeign) 진행.
> 각 태스크는 독립 commit 단위. 매 태스크 후 `./gradlew test` 회귀 0 확인.

---

## Phase A — Spring Cloud LoadBalancer + WebClient

### A1. 의존성 명시 추가
- [x] `payment-service/build.gradle` 에 `spring-cloud-starter-loadbalancer` 명시
  - 이유: 현재 `eureka-client` 가 transitively 가져오지만 명시가 hexagonal/dep 가시성에 좋음
  - 검증: `./gradlew :payment-service:dependencies | grep loadbalancer`
- 의존: 없음 (Phase A 진입점)
- TDD: 불필요 (의존성 변경)
- 단일 commit: `chore(payment-service): spring-cloud-starter-loadbalancer 명시 추가`
- 완료 결과: `spring-cloud-starter-loadbalancer:4.2.0` 직접 의존성 추가 확인, `compileJava` PASS

### A2. `@LoadBalanced WebClient.Builder` Bean 등록
- [x] `payment-service/.../core/config/HttpClientConfig.java` 신규 (또는 기존 config 에 추가)
- [x] `@Bean @LoadBalanced public WebClient.Builder loadBalancedWebClientBuilder() { ... }` 정의
  - **주의**: 기본 `WebClient.Builder` (autoconfig) 와 별개로, `@LoadBalanced` 는 한정자(qualifier) 역할도 한다
- [x] `HttpOperatorImpl` 의 builder 주입을 `@LoadBalanced` 로 한정 — Toss/NicePay 외부 호출은 logical name 미사용이므로 이 builder 가 외부 host 그대로 호출하는지 검증 필요
  - **결정**: 외부 host (e.g. `https://api.tosspayments.com`) 는 LoadBalancer 가 통과시킴 (logical name 형식 아니면 패스스루)
  - 단 Phase A 에선 영향 최소화를 위해 새 LoadBalanced builder 를 별도로 만들고, internal cross-service 호출 어댑터(ProductHttpAdapter/UserHttpAdapter) 만 사용
- 의존: A1
- TDD: A4 의 단위 테스트가 검증 — 따로 RED 필요 없음
- 단일 commit: `feat(payment-service): @LoadBalanced WebClient.Builder Bean 추가`
- 완료 결과: `HttpClientConfig` 신규 등록, `compileJava` PASS, 전체 578/578 tests PASS

### A3. base-url 을 logical service name 으로 변경
- [x] `application.yml`:
  ```yaml
  product-service.base-url: ${PRODUCT_SERVICE_BASE_URL:http://product-service}
  user-service.base-url:    ${USER_SERVICE_BASE_URL:http://user-service}
  ```
- [x] `application-docker.yml` 에 별도 override 가 있으면 제거 또는 동일하게 유지 (docker network 안에서도 logical name 동일)
- [x] `application-test.yml` 은 통합 테스트가 cross-service HTTP 안 타므로 영향 없음 (확인)
- 의존: A2 (Bean 등록되어야 logical name resolve 동작)
- TDD: A4 가 검증
- 단일 commit: `refactor(payment-service): cross-service base-url 을 logical service name 으로 변경`
- 완료 결과: application.yml default 값만 변경 (localhost:8083→product-service, localhost:8084→user-service). docker/benchmark/test yml 에는 해당 prop override 없음 확인.

### A4. HttpOperatorImpl 의 WebClient.Builder 주입에 @LoadBalanced 적용
- [x] **정정 사항** (A4 dispatch 시 발견): 어댑터 (ProductHttpAdapter / UserHttpAdapter) 는
      `WebClient.Builder` 를 직접 받지 않고 `HttpOperator` 추상화를 통해 간접 사용한다.
      실제 builder 주입처는 `payment-service/.../core/common/infrastructure/http/HttpOperatorImpl`.
      따라서 `@LoadBalanced` 부착 위치는 어댑터가 아니라 `HttpOperatorImpl` 한 곳.
- [x] `HttpOperatorImpl` 생성자의 `WebClient.Builder webClientBuilder` 매개변수에
      `@LoadBalanced` 어노테이션 추가 + import (`org.springframework.cloud.client.loadbalancer.LoadBalanced`)
- [x] payment-service 의 `HttpOperator` 사용처 검증: ProductHttpAdapter / UserHttpAdapter
      두 군데뿐 — 외부 PG (Toss/NicePay) 호출은 pg-service 의 별개 `HttpOperatorImpl` 이
      전담하므로 본 변경의 부수효과 없음
- [x] 어댑터 두 클래스는 변경 없음 — `@Mock HttpOperator` 기반 contract test 영향 0
- 의존: A2, A3
- TDD: 불필요 — 기존 contract test 가 `@Mock HttpOperator` 기반이라 시그니처 변경 없음.
       회귀 게이트는 `./gradlew :payment-service:test` 348 tests 회귀 0 으로 갈음.
- 단일 commit: `refactor(payment-service): HttpOperatorImpl WebClient.Builder 에 @LoadBalanced 적용`
- 완료 결과: `HttpOperatorImpl` 생성자 `@LoadBalanced` 적용 + javadoc 보강. `compileJava` PASS, 348/348 tests PASS.

### A5. docker-compose 의 cross-service env 정리
- [ ] `docker/docker-compose.apps.yml` 의 `payment-service` env 에서 `PRODUCT_SERVICE_BASE_URL` / `USER_SERVICE_BASE_URL` 명시 제거 (default = logical name 사용)
- 의존: A3
- TDD: 불필요 (config)
- 단일 commit: `chore(docker): payment-service env 의 cross-service URL override 제거`

### A6. 검증 — 스케일 업 시나리오
- [ ] stack 기동: `bash scripts/compose-up.sh --mode fake --reset-db`
- [ ] product-service 인스턴스 2개로 scale: `docker compose -f docker/docker-compose.apps.yml up --scale product-service=2 -d`
- [ ] Eureka 대시보드에서 PRODUCT-SERVICE 가 인스턴스 2건 등록 확인
- [ ] curl 결제 요청 5건 → product-service 두 인스턴스 로그에 traceId 분산되어 도착 확인 (round-robin)
- [ ] `bash scripts/smoke-all.sh` PASS
- TDD: 통합 시나리오 — 검증 단계
- 단일 commit (선택): `test(scale): product-service 다중 인스턴스 round-robin 시나리오 검증 결과 기록`

### Phase A 종결 기준
- 의존성 / Bean / config / 어댑터 / 검증 모두 PASS
- `./gradlew test` 578+ tests 회귀 0
- `scripts/smoke-all.sh --with-trace` 결제 1건 후 PASS

---

## Phase B — OpenFeign 도입

> Phase A 종결 후 진행. A 가 끝났다는 사실은 STATE.md 직전 봉인 항목으로 기록.

### B1. 의존성 추가
- [ ] `payment-service/build.gradle` 에 `spring-cloud-starter-openfeign` 추가
- [ ] `@EnableFeignClients(basePackages = "com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http.feign")` 활성화
- 의존: Phase A 종결
- TDD: 불필요 (의존성 / 어노테이션 활성화)
- 검증: `./gradlew :payment-service:dependencies | grep openfeign`
- 단일 commit: `chore(payment-service): spring-cloud-starter-openfeign 추가`

### B2. Feign 인터페이스 정의
- [ ] `infrastructure/adapter/http/feign/ProductFeignClient.java` 신규
  ```java
  @FeignClient(name = "product-service", configuration = ProductFeignConfig.class)
  public interface ProductFeignClient {
      @GetMapping("/api/v1/products/{id}")
      ProductInfoDto getProduct(@PathVariable("id") Long id);
      // ... 다른 endpoint
  }
  ```
- [ ] `UserFeignClient.java` 동일
- [ ] DTO 는 기존 `ProductHttpAdapter` 가 사용하던 record 재사용
- 의존: B1
- TDD: 불필요 (선언적 인터페이스 정의 — 동작 검증은 B4/B6 의 contract test 가 담당)
- 단일 commit: `feat(payment-service): ProductFeignClient / UserFeignClient 인터페이스 정의`

### B3. Feign 설정 (Encoder/Decoder/ErrorDecoder)
- [ ] `ProductFeignConfig.java`: Jackson encoder/decoder + ErrorDecoder
- [ ] ErrorDecoder 가 4xx/5xx → `ProductNotFoundException` / `ProductServiceRetryableException` / `IllegalStateException` 매핑
  - 기존 `ProductHttpAdapterContractTest` 의 4분기 정확히 동일하게 재현
- 의존: B2
- TDD: contract test 가 같은 행동을 검증 (재사용 가능하게 base 추출 또는 새 contract)
- 단일 commit: `feat(payment-service): Feign ErrorDecoder — 4xx/5xx → 도메인 예외 매핑`

### B4. 어댑터를 Feign 위임으로 재구성
- [ ] `ProductHttpAdapter` 가 `WebClient` 직접 호출 → `ProductFeignClient` 호출 위임
- [ ] `UserHttpAdapter` 동일
- [ ] 기존 `@Value("${product-service.base-url}")` 제거 — Feign 이 `name="product-service"` 로 LB resolve
- [ ] 도메인 예외는 Feign ErrorDecoder 가 throw, 어댑터는 그대로 propagate 또는 wrap
- 의존: B3
- TDD: 기존 contract test 재구성 (MockWebServer → Feign client mock 또는 동일 MockWebServer 유지하되 Feign 으로 교체)
- 단일 commit: `refactor(payment-service): HttpAdapter 가 Feign client 위임 — WebClient 제거`

### B5. WebClient 의존성 정리
- [ ] cross-service 호출이 Feign 으로 전환됐으니, `spring-boot-starter-webflux` 가 다른 데서 쓰이는지 검증
  - 외부 PG 호출 (HttpOperatorImpl) 는 여전히 WebClient 사용 — 보존
- [ ] `@LoadBalanced WebClient.Builder` Bean 도 cross-service 만 쓰던 것이라면 제거
- 의존: B4
- TDD: 회귀 테스트
- 단일 commit (선택): `chore(payment-service): cross-service 전용 LoadBalanced builder 제거 (Feign 으로 흡수)`

### B6. contract test 재작성
- [ ] `ProductHttpAdapterContractTest` / `UserHttpAdapterContractTest` 를 Feign 기반으로 재작성
- [ ] 4xx/5xx 4분기 그대로 검증 (404 → NotFound, 503 → Retryable, 429 → Retryable, 500 → IllegalState)
- 의존: B4
- TDD: 이게 RED → GREEN
- 단일 commit: `test(payment-service): contract test 재작성 — Feign 기반 4xx/5xx 매핑 검증`

### B7. 검증
- [ ] `./gradlew test` 회귀 0
- [ ] stack 기동 + scale up 시나리오 (Phase A6 와 동일) — Feign 도 round-robin 확인
- [ ] `scripts/smoke-all.sh --with-trace` PASS
- 단일 commit (선택): `test(scale): Feign client 다중 인스턴스 검증 결과 기록`

### Phase B 종결 기준
- 모든 cross-service HTTP 가 Feign 경유
- 어댑터의 `@Value("${...base-url}")` 제거됨
- contract test 재작성 + 4xx/5xx 매핑 검증
- 회귀 0

---

## 산출물 / commit 정책

- A1~A6 / B1~B7 각각 단일 commit
- TDD 적용 태스크는 `test:` (RED) → `feat:` 또는 `refactor:` (GREEN) 순서
- Phase A 종결 시 STATE.md 갱신 (직전 봉인 추가)
- Phase B 종결 시 docs/topics/CLIENT-SIDE-LB.md → docs/archive/client-side-lb/ 봉인

## 검증 매트릭스

| Phase | 단계 | 검증 |
|---|---|---|
| A | 후 | `./gradlew test` PASS + scale=2 round-robin 확인 + smoke-all PASS |
| B | 후 | `./gradlew test` PASS + scale=2 round-robin 확인 + contract test (Feign 기반) PASS |

## 비범위 (이 PLAN 에서 하지 않음)

- pg-service ↔ payment-service 통신 (Kafka 만)
- gateway → 4서비스 (이미 `lb://`)
- Resilience4j CircuitBreaker (Phase 4 T4-D 별도 토픽)
- 분산 트랜잭션 / Saga
