# Topic — Client-side Load Balancing 도입

> 작성: 2026-04-27 — Phase 4 본 작업 직전 architectural fix.

## 1. 현재 상태 (사실)

### 1.1 cross-service HTTP 호출 경로
- `payment-service` 가 `product-service` / `user-service` 를 HTTP 로 호출
- 호출자: `ProductHttpAdapter` / `UserHttpAdapter` (`payment-service/.../infrastructure/adapter/http/`)
- 호출 클라이언트: Spring `WebClient` (auto-config builder 주입)
- base-url: `application.yml` 에 고정 host:port
  ```yaml
  product-service.base-url: ${PRODUCT_SERVICE_BASE_URL:http://localhost:8083}
  user-service.base-url:    ${USER_SERVICE_BASE_URL:http://localhost:8084}
  ```

### 1.2 Eureka 등록 상태
- 4 비즈니스 서비스 + Gateway 가 모두 `spring.application.name` 으로 Eureka 등록
- `infra-healthcheck.sh` 의 5건 등록 확인 PASS
- 단, **payment-service 가 Eureka 인스턴스 목록을 조회·라우팅하는 경로 없음**
- `spring-cloud-starter-loadbalancer` 의존성도 추가되어 있지 않음 (eureka-client 가 transitively 가져오긴 하지만 `@LoadBalanced` 활용 0건)

### 1.3 결과 (오토스케일링 부적합)
- `docker compose up --scale product-service=3` 같은 인스턴스 다중화 시:
  - product-service 컨테이너는 3개 떠도, payment-service 는 항상 `localhost:8083` (단일 호스트 포트) 한 곳으로만 호출
  - 게다가 host port 8083 매핑은 1대까지만 가능 — 추가 인스턴스는 host port 충돌 또는 random
- 즉 인프라(스케일링) 관점에서 cross-service HTTP 가 **단일 인스턴스 의존**
- Phase 4 의 로컬 오토스케일러 (Toxiproxy / k6 부하 / scale up·down) 작동 무용

## 2. 결정

**A → B 두 Phase 순차 도입.**

### Phase A — Spring Cloud LoadBalancer + WebClient (최소 변경)
- 의존성: 명시 추가 `spring-cloud-starter-loadbalancer` (또는 transitive 확정)
- 변경 범위: WebClient.Builder 1개 + base-url 2건 + 어댑터 0건 (필드 그대로 유지 가능)
- 효과: Eureka 인스턴스 list resolve + round-robin LB
- 영향: 거의 없음, 기존 contract test (MockWebServer) 그대로

### Phase B — OpenFeign 도입 (선언적 호출)
- 의존성 추가: `spring-cloud-starter-openfeign`
- 변경 범위: `ProductHttpAdapter` / `UserHttpAdapter` 의 WebClient 호출 → Feign 인터페이스 위임
- 효과: 선언적 인터페이스 + Feign Decoder 로 4xx/5xx → 도메인 예외 매핑 일관
- Resilience4j CircuitBreaker (Phase 4 T4-D) 와 통합 용이

### A → B 순서 이유
- A 만으로도 오토스케일링 차단 해소 (가장 큰 가치를 먼저)
- A 적용 후 1주일 이상 운영 검증 가능 (k6 부하 시나리오 측정)
- B 는 어댑터 재구성·contract test 재작성 비용이 있어 별도 토픽으로 분리

## 3. 비범위

- pg-service ↔ payment-service 통신 (Kafka 양방향만, HTTP 없음 — 변경 없음)
- gateway → 4서비스 (이미 `lb://service-name` 사용 중 — 변경 없음)
- Resilience4j CircuitBreaker (Phase 4 T4-D 별도 토픽)
- service mesh (Istio / Linkerd) — overkill

## 4. 다음 단계

→ `docs/CLIENT-SIDE-LB-PLAN.md` 의 태스크 분해를 따른다.
