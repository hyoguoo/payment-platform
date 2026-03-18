# Phase 5: k6 Benchmark - Research

**Researched:** 2026-03-15
**Domain:** k6 load testing, Spring profile configuration, shell scripting
**Confidence:** HIGH

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

#### 부하 설정
- **VU 단계**: 50 → 100 → 200 VU (3단계 순차 측정)
- **각 단계 지속 시간**: 60초 (전체 3분)
- **패턴**: Constant load (ramping 없음)
- **Thresholds**: 없음 — 순수 측정만, pass/fail 기준 없음

#### 테스트 데이터 전략
- **orderId 준비**: k6 `setup()` 단계에서 checkout API를 호출해 orderId 생성 — 실제 측정 트래픽에 checkout 요청 포함되지 않아 공정한 비교
- **풀 크기**: 1,000개 orderId 사전 생성
- **할당 방식**: `SharedArray` + 순번 할당으로 VU별 전담 orderId 사용 — 동일 orderId 중복 서브밋 방지

#### 폴링 파라미터 (비동기 스크립트)
- **Interval**: 500ms
- **Timeout**: 30초
- **Timeout 초과 처리**: `check()` 실패 태그 기록 후 이터레이션 계속 — 에러율에 반영됨

#### Toss API 대체 (부하 테스트 환경)
- **방법**: `application-benchmark.yml` 프로파일로 `FakeTossHttpOperator` 빈 활성화
- **활성화**: `spring.profiles.active=benchmark` 설정으로 서버 기동 — 코드 변경 없음

#### 전략 전환 방법
- `application.yml`의 `spring.payment.async-strategy` 값을 직접 수정 후 애플리케이션 재기동
- `run-benchmark.sh`에 각 전략 전환 안내 텍스트 포함

#### k6 실행 방법
- **실행**: Docker로 k6 실행 — `docker run --rm grafana/k6 run script.js`
- 로컬 k6 설치 불필요

#### 스크립트 구조
- **위치**: `scripts/k6/` 디렉토리
  - `helpers.js` — setup(), pollStatus(), 공통 상수 (BASE_URL, POLL_INTERVAL 등)
  - `sync.js` — Sync 전략 측정
  - `outbox.js` — Outbox 전략 측정 (폴링 루프 포함)
  - `kafka.js` — Kafka 전략 측정 (폴링 루프 포함)
  - `README.md` — Docker 실행 명령어, 설정 변경법, 결과 해석 방법
- **자동화**: `scripts/k6/run-benchmark.sh` — 세 전략 순서대로 실행하는 쉘 스크립트

#### BENCHMARK.md 범위
- **위치**: 프로젝트 루트 (`BENCHMARK.md`)
- **내용**: 측정 환경, TPS/p50/p95/p99/에러율 비교 표, 전략별 해석, 어댑터 선택 가이드
- **실제 수치**: 템플릿으로 작성, 직접 k6 실행 후 채워 넣는 구조

### Claude's Discretion
- `helpers.js` 내 `pollStatus()` 함수의 정확한 구현 방식
- `SharedArray` orderId 순번 할당 시 원자성 처리 방법 (k6 VU 컨텍스트 내 처리)
- `run-benchmark.sh` 전략 전환 안내 메시지 형식
- `FakeTossHttpOperator`의 `application-benchmark.yml` 등록 방식 세부사항

### Deferred Ideas (OUT OF SCOPE)
없음 — 논의가 Phase 5 범위 내에서 유지됨

모니터링 대시보드(Grafana), 분산 k6 실행, 실시간 결과 스트리밍은 이 Phase의 범위가 아니다.
</user_constraints>

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| BENCH-01 | k6 스크립트 3종(sync / outbox / kafka)을 작성한다 | k6 script structure, SharedArray, setup(), Docker run 방법 확인 |
| BENCH-02 | 비동기 전략 스크립트는 status 폴링 루프를 포함해 end-to-end 완료까지 측정한다 | GET /api/v1/payments/{orderId}/status 엔드포인트 확인, PaymentStatusResponse enum 확인 |
| BENCH-03 | 측정 지표는 TPS(requests/sec), p50/p95/p99 레이턴시, 에러율을 포함한다 | k6 내장 http_req_duration 트렌드 메트릭, http_reqs 카운터로 TPS 계산 |
| BENCH-04 | 동일한 부하 조건(VU 수, 테스트 데이터)으로 3가지 전략을 비교한다 | SharedArray 1,000개 orderId, 50→100→200 VU stages |
| BENCH-05 | 측정 결과를 BENCHMARK.md에 표와 해석으로 정리한다 | BENCHMARK.md 구조 설계, 수치 자리표시자 포함 템플릿 패턴 |
</phase_requirements>

---

## Summary

Phase 5는 새로운 라이브러리 도입이 없는 **스크립트 작성 + 문서화 Phase**다. k6는 JavaScript로 작성하는 load testing 도구이며, 이미 결정된 Docker 실행 방식(`grafana/k6` 이미지)으로 로컬 설치 없이 사용한다. 프로젝트에 이미 구현된 세 어댑터(Sync/Outbox/Kafka)와 `GET /api/v1/payments/{orderId}/status` 폴링 엔드포인트를 그대로 활용한다.

핵심 구현 과제는 두 가지다. 첫째, `application-benchmark.yml` 프로파일을 새로 만들어 `FakeTossHttpOperator`를 메인 컨텍스트에서 활성화해야 한다 — 현재 `FakeTossHttpOperator`는 `src/test/java`에 `@TestConfiguration` 없이 plain class로 존재하므로, 메인 소스로 이동하거나 `@Configuration`으로 등록하는 방법을 선택해야 한다. 둘째, k6의 `SharedArray`는 VU별로 독립 인덱스를 사용해야 하며, orderId 순번 할당 시 `__VU`와 `__ITER`를 조합해 중복 방지를 구현한다.

**Primary recommendation:** `FakeTossHttpOperator`를 `src/main/java`로 이동하고 `@Configuration` + `@Profile("benchmark")`으로 등록한다. k6 스크립트는 `helpers.js`에서 공통 로직을 분리하고, 세 전략 스크립트가 동일한 stages 설정을 import해 비교 공정성을 확보한다.

---

## Standard Stack

### Core
| Library/Tool | Version | Purpose | Why Standard |
|---|---|---|---|
| k6 (Docker image) | `grafana/k6:latest` | Load testing 실행 | 결정된 실행 방식 |
| JavaScript (ES6+) | k6 내장 runtime | k6 스크립트 언어 | k6 전용 JS 런타임 (Node.js 아님) |
| Spring Boot profile | `benchmark` | FakeToss 활성화 | 기존 ConditionalOnProperty 패턴 |

### Supporting
| Library/Tool | Version | Purpose | When to Use |
|---|---|---|---|
| k6 `SharedArray` | k6 내장 | 공유 orderId 풀 | VU 간 데이터 공유 시 필수 |
| k6 `http` module | k6 내장 | HTTP 요청 | 모든 API 호출 |
| k6 `check()` | k6 내장 | 검증 + 에러율 측정 | 응답 검증마다 |
| k6 `sleep()` | k6 내장 | 폴링 인터벌 | 500ms 폴링 딜레이 |
| k6 `Trend`, `Counter` | k6 내장 커스텀 메트릭 | end-to-end 레이턴시 측정 | 비동기 스크립트의 완료까지 시간 |

**Installation:**
```bash
# 로컬 설치 없음 — Docker로 실행
docker run --rm -v $(pwd)/scripts/k6:/scripts grafana/k6 run /scripts/sync.js
```

---

## Architecture Patterns

### Recommended Project Structure
```
scripts/k6/
├── helpers.js          # setup(), pollStatus(), BASE_URL, POLL_INTERVAL, ORDER_POOL_SIZE
├── sync.js             # Sync 전략 (200 OK 기대)
├── outbox.js           # Outbox 전략 (202 → 폴링)
├── kafka.js            # Kafka 전략 (202 → 폴링)
├── run-benchmark.sh    # 세 전략 순서 실행 + 전략 전환 안내
└── README.md           # 실행 방법, 전략 전환법, 결과 해석법

src/main/
├── java/com/hyoguoo/paymentplatform/
│   └── mock/
│       └── FakeTossHttpOperator.java    # test→main 이동 (benchmark 프로파일용)
└── resources/
    └── application-benchmark.yml       # benchmark 프로파일 설정 (신규)
```

### Pattern 1: k6 Stages (Constant Load)
**What:** ramping 없이 각 VU 수준에서 일정 시간 측정
**When to use:** 전략 간 공정한 비교를 위해 동일한 부하 조건 필요 시

```javascript
// Source: CONTEXT.md decisions — constant load, no ramp
export const options = {
  stages: [
    { duration: '60s', target: 50 },
    { duration: '60s', target: 100 },
    { duration: '60s', target: 200 },
  ],
  // thresholds 없음 — 순수 측정
};
```

### Pattern 2: SharedArray + 순번 할당
**What:** setup()에서 생성한 orderId 배열을 VU 간 공유하고, `__VU`와 `__ITER` 조합으로 중복 없이 할당
**When to use:** 동일 orderId 중복 서브밋 방지가 필요한 경우

```javascript
// Source: k6 docs — SharedArray pattern
import { SharedArray } from 'k6/data';

// helpers.js에서 export
export function getOrderId(orderIds) {
  // __VU는 1-based, __ITER는 0-based
  const idx = ((__VU - 1) + __ITER * __ENV.VU_COUNT) % orderIds.length;
  return orderIds[idx];
}
```

**Note:** k6의 SharedArray는 read-only이며 VU 간 메모리를 공유한다. 원자성 문제는 각 VU가 독립 인덱스를 계산하므로 발생하지 않는다. `__ENV.VU_COUNT`를 환경 변수로 전달하거나, 단순히 `(__VU * 100 + __ITER) % orderIds.length` 패턴을 사용한다.

### Pattern 3: setup()에서 orderId 사전 생성
**What:** 측정 전 단계에서 checkout API를 1,000회 호출해 orderId 풀 생성
**When to use:** 측정 트래픽에 checkout 요청을 섞지 않아야 할 때

```javascript
// Source: CONTEXT.md decisions — setup() pre-population
export function setup() {
  const orderIds = [];
  for (let i = 0; i < ORDER_POOL_SIZE; i++) {
    const res = http.post(`${BASE_URL}/api/v1/payments/checkout`, JSON.stringify({
      userId: 1,
      orderedProductList: [{ productId: 1, quantity: 1 }]
    }), { headers: { 'Content-Type': 'application/json' } });

    if (res.status === 200) {
      orderIds.push(JSON.parse(res.body).orderId);
    }
  }
  return { orderIds };
}
```

**Critical:** setup() 완료 후 DB의 stock이 1,000개 감소한다. 따라서 data.sql의 stock 값(현재 59)을 벤치마크 전 충분히 확보해야 한다 — `application-benchmark.yml`에서 초기 stock을 높이거나, benchmark 전용 data SQL을 제공해야 한다.

### Pattern 4: 비동기 폴링 루프
**What:** 202 응답 후 `/status` 엔드포인트를 DONE/FAILED까지 반복 조회
**When to use:** Outbox, Kafka 전략의 end-to-end 완료 시간 측정

```javascript
// helpers.js — pollStatus()
export function pollStatus(orderId) {
  const startTime = Date.now();
  while (Date.now() - startTime < POLL_TIMEOUT_MS) {
    sleep(POLL_INTERVAL_S);
    const res = http.get(`${BASE_URL}/api/v1/payments/${orderId}/status`);
    if (res.status === 200) {
      const body = JSON.parse(res.body);
      if (body.status === 'DONE' || body.status === 'FAILED') {
        return body.status;
      }
    }
  }
  return 'TIMEOUT';
}
```

**PaymentStatusResponse 실제 값 (확인됨):** `PENDING`, `PROCESSING`, `DONE`, `FAILED`

### Pattern 5: 커스텀 Trend 메트릭 (end-to-end 레이턴시)
**What:** 202 발송부터 DONE 확인까지 전체 시간을 별도 메트릭으로 기록
**When to use:** 비동기 스크립트에서 단순 HTTP 응답 시간이 아닌 처리 완료까지 시간 측정 시

```javascript
import { Trend } from 'k6/metrics';

const e2eLatency = new Trend('e2e_latency');

export default function(data) {
  const start = Date.now();
  // confirm 요청 (202)
  const confirmRes = http.post(/* ... */);

  // 폴링
  const status = pollStatus(orderId);

  const elapsed = Date.now() - start;
  e2eLatency.add(elapsed);
  check(status, { 'completed': s => s === 'DONE' });
}
```

### Pattern 6: FakeTossHttpOperator 메인 소스 등록
**What:** 현재 test 소스의 `FakeTossHttpOperator`를 benchmark 프로파일에서 활성화
**When to use:** 외부 Toss API 없이 부하 테스트 환경에서 일관된 응답 필요 시

```yaml
# application-benchmark.yml
spring:
  config:
    activate:
      on-profile: benchmark
  payment:
    async-strategy: sync  # 기본값 — 각 전략 측정 시 변경

  # benchmark 프로파일에서 FakeToss 빈 활성화
  fake-toss:
    enabled: true
```

```java
// FakeTossHttpOperator를 src/main/java로 이동하고 @Configuration으로 등록
@Configuration
@Profile("benchmark")
public class BenchmarkConfig {
    @Bean
    @Primary
    public HttpOperator httpOperator() {
        return new FakeTossHttpOperator();
    }
}
```

**Alternative (simpler):** `FakeTossHttpOperator`에 `@ConditionalOnProperty(name="spring.fake-toss.enabled", havingValue="true")` 추가하고 `src/main/java`로 이동.

### Anti-Patterns to Avoid
- **setup()에서 생성한 orderId를 실제 측정에서 그대로 재사용**: 비동기 전략에서 이미 IN_PROGRESS 상태인 orderId를 다시 confirm 요청하면 에러 발생 — 1,000개 풀에서 VU 수 × 이터레이션으로 인덱스 계산
- **테스트별 stock 상태 미확인**: 1,000개 orderId 생성 = 1,000회 stock 감소. stock 59개(현재 data.sql)로는 60번째 checkout부터 실패 — benchmark용 stock 초기값 확보 필수
- **k6를 Node.js처럼 사용**: k6의 JS 런타임은 Node.js가 아님. `require()`, `fs`, `process` 미지원. ES6 모듈 import/export 사용
- **단순 202 응답 시간만 측정**: CONTEXT.md에서 명시적으로 end-to-end 완료까지 측정 요구. 폴링 없이 202 시간만 측정하면 BENCH-02 미충족

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| TPS 계산 | 직접 카운터 구현 | k6 내장 `http_reqs` counter | k6가 자동으로 req/s 계산 |
| p50/p95/p99 | 수동 분위수 계산 | k6 내장 `http_req_duration` trend | k6 summary에 자동 출력 |
| 에러율 | 실패 카운트 직접 집계 | k6 `checks` 내장 메트릭 | check() 실패율 자동 계산 |
| 폴링 딜레이 | busy-wait loop | `sleep(0.5)` | k6 내장 sleep (초 단위) |
| VU 간 데이터 공유 | 전역 변수 | `SharedArray` | 전역 변수는 VU별 독립 복사본 생성으로 메모리 낭비 |

**Key insight:** k6는 end-of-test summary에서 모든 HTTP 메트릭(p50/p95/p99/req/s/에러율)을 자동 출력한다. 커스텀 메트릭은 `e2e_latency` (end-to-end) 같이 k6가 직접 측정하지 못하는 구간에만 사용한다.

---

## Common Pitfalls

### Pitfall 1: stock 부족으로 setup() 중단
**What goes wrong:** checkout API가 stock 부족으로 에러 반환, orderId 풀이 1,000개 미만으로 생성됨
**Why it happens:** data.sql의 product stock이 59개로 초기화되어 있음 (확인됨)
**How to avoid:** `application-benchmark.yml`에서 또는 별도 `data-benchmark.sql`로 stock을 2,000+으로 초기화
**Warning signs:** setup() 중 HTTP 4xx 에러 발생

### Pitfall 2: 비동기 스크립트에서 이미 처리된 orderId 재사용
**What goes wrong:** 동일 orderId로 두 번 confirm 요청 시 ALREADY_IN_PROGRESS 에러 → 에러율 오염
**Why it happens:** VU 인덱스 계산 오류 또는 orderId 풀 소진
**How to avoid:** `(__VU - 1 + __ITER * MAX_VU) % orderIds.length` 패턴으로 인덱스 중복 방지. orderId 풀 크기(1,000)가 총 이터레이션 수를 충분히 커버하는지 확인
**Warning signs:** confirm 응답에서 4xx 에러 다수 발생

### Pitfall 3: FakeTossHttpOperator 빈 충돌
**What goes wrong:** benchmark 프로파일에서 실제 TossHttpOperator와 FakeTossHttpOperator 둘 다 빈으로 등록되어 `NoUniqueBeanDefinitionException`
**Why it happens:** `@Primary` 없이 두 HttpOperator 구현체가 동시 등록됨
**How to avoid:** `BenchmarkConfig`에서 `@Primary` 또는 `@ConditionalOnProperty`로 실제 빈 비활성화
**Warning signs:** 애플리케이션 기동 실패, Bean definition conflict 로그

### Pitfall 4: k6 Docker 실행 시 host 네트워크 접근
**What goes wrong:** k6 컨테이너에서 `localhost:8080` 접근 불가 (컨테이너 내부 localhost는 컨테이너 자신)
**Why it happens:** Docker 컨테이너 격리
**How to avoid:** `--network host` (Linux) 또는 `host.docker.internal` (macOS/Windows) 사용
```bash
# macOS
docker run --rm -v $(pwd)/scripts/k6:/scripts \
  -e BASE_URL=http://host.docker.internal:8080 \
  grafana/k6 run /scripts/sync.js
```
**Warning signs:** k6 connection refused 에러

### Pitfall 5: sleep 단위 혼동
**What goes wrong:** 폴링 간격이 500초로 설정됨 (500ms 의도했으나 500초 지정)
**Why it happens:** k6 `sleep()`은 초 단위, JavaScript의 `setTimeout`과 다름
**How to avoid:** `sleep(0.5)` — 500ms = 0.5초
**Warning signs:** 테스트가 비정상적으로 오래 걸림

### Pitfall 6: PENDING 상태를 DONE으로 오인
**What goes wrong:** 폴링 루프가 PENDING 상태에서 조기 종료
**Why it happens:** 상태 체크 조건 잘못 작성
**How to avoid:** `status === 'DONE' || status === 'FAILED'`만 종료 조건으로 사용. 실제 PaymentStatusResponse enum: PENDING, PROCESSING, DONE, FAILED (확인됨)

---

## Code Examples

Verified patterns from project source code inspection:

### API Endpoints (확인됨)
```
POST /api/v1/payments/checkout
  Body: { userId: Long, orderedProductList: [{ productId: Long, quantity: Int }] }
  Response 200: { orderId: String, totalAmount: BigDecimal }

POST /api/v1/payments/confirm
  Body: { userId: Long, orderId: String, amount: BigDecimal, paymentKey: String }
  Response 200 (sync): { orderId, amount }
  Response 202 (async): { orderId, amount }

GET /api/v1/payments/{orderId}/status
  Response 200: { orderId: String, status: PENDING|PROCESSING|DONE|FAILED, approvedAt: LocalDateTime|null }
```

### data.sql 기반 테스트 데이터 (확인됨)
```javascript
// setup()에서 사용할 유효한 userId, productId
const VALID_USER_ID = 1;
const VALID_PRODUCT_IDS = [1, 2]; // product 1: price 50000, product 2: price 30000

// confirm 요청에 필요한 amount는 checkout 응답의 totalAmount와 일치해야 함
// paymentKey는 Fake 환경에서 임의 문자열 허용
const FAKE_PAYMENT_KEY = 'test-payment-key';
```

### application-benchmark.yml 구조
```yaml
spring:
  config:
    activate:
      on-profile: benchmark

  # benchmark 기본 전략 (측정 시 변경)
  payment:
    async-strategy: sync

  datasource:
    # docker profile에서 상속 또는 로컬 설정
    hikari:
      maximum-pool-size: 30

# FakeToss 활성화 — 실제 Toss API 호출 없음
fake-toss:
  enabled: true

# stock 충분히 확보
```

### run-benchmark.sh 패턴 (기존 scripts/common.sh 참고)
```bash
#!/bin/bash
source "$(dirname "$0")/../common.sh"

SCRIPT_DIR="$(dirname "$0")"

print_section "=== Phase 5 k6 Benchmark ==="
print_warning "전제 조건:"
echo "  1. 서버가 benchmark 프로파일로 실행 중 (spring.profiles.active=benchmark)"
echo "  2. application.yml의 spring.payment.async-strategy 설정 확인"
echo ""

run_strategy() {
  local strategy=$1
  local script=$2
  print_section "--- $strategy 전략 측정 시작 ---"
  print_warning "application.yml에서 spring.payment.async-strategy=$strategy 로 설정 후 서버 재기동하세요"
  read -p "준비됐으면 Enter 키를 누르세요..."

  docker run --rm \
    -v "$SCRIPT_DIR:/scripts" \
    -e BASE_URL="${BASE_URL:-http://host.docker.internal:8080}" \
    grafana/k6 run "/scripts/$script"
}

run_strategy "sync" "sync.js"
run_strategy "outbox" "outbox.js"
run_strategy "kafka" "kafka.js"

print_info "모든 전략 측정 완료. BENCHMARK.md에 수치를 기록하세요."
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|---|---|---|---|
| k6 로컬 설치 | Docker 실행 (`grafana/k6`) | 결정 사항 | 환경 의존성 없음 |
| Grafana Cloud k6 | Docker 로컬 실행 | 결정 사항 | 포트폴리오 환경에 적합 |
| 단순 HTTP 응답시간 측정 | end-to-end 완료 시간 측정 | CONTEXT.md | 비동기 전략 공정 비교 |

**현재 프로젝트 상태:**
- Phases 1-4 모두 완료 — 세 어댑터(Sync/Outbox/Kafka) 구현 완료
- `GET /api/v1/payments/{orderId}/status` 폴링 엔드포인트 Phase 1에서 구현 완료
- `FakeTossHttpOperator`는 `src/test/java`에 plain class로 존재 — benchmark 프로파일에서 사용하려면 `src/main/java`로 이동 필요
- `application-benchmark.yml`은 아직 존재하지 않음 — 신규 생성 필요

---

## Open Questions

1. **FakeTossHttpOperator 이동 방식**
   - What we know: 현재 `src/test/java`에 `@TestConfiguration` 없이 plain class
   - What's unclear: `src/main/java`로 이동 시 프로덕션 빌드에 포함되는 것이 허용되는지
   - Recommendation: `src/main/java/com/hyoguoo/paymentplatform/mock/`으로 이동하고 `@Profile("benchmark")`으로 격리. 포트폴리오 프로젝트이므로 허용 범위 내

2. **DB stock 초기화 전략**
   - What we know: data.sql에 stock=59 (product 1, 2 각각)
   - What's unclear: `application-benchmark.yml`에서 별도 data SQL을 지정할 수 있는지, 또는 benchmark 전용 stock 리셋이 필요한지
   - Recommendation: `data-benchmark.sql`을 별도로 만들거나, stock=10000으로 data.sql을 업데이트. 가장 단순한 방법은 run-benchmark.sh 실행 전 DB 리셋 안내 문구 포함

3. **Docker 네트워크 설정 문서화**
   - What we know: macOS에서 `host.docker.internal`, Linux에서 `--network host`
   - What's unclear: 사용자 환경이 macOS인지 Linux인지
   - Recommendation: README.md에 OS별 실행 명령어 두 가지 제공, BASE_URL 환경 변수로 추상화

---

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 (Jupiter) + Spring Boot Test |
| Config file | `src/test/resources/application-test.yml` |
| Quick run command | `./gradlew test --tests "*.k6*" -x` (k6 Phase는 JUnit 테스트 없음) |
| Full suite command | `./gradlew test` |

### Phase Requirements → Test Map

Phase 5는 k6 스크립트와 문서 작성이 주 산출물이다. JUnit 자동화 테스트로 직접 검증 가능한 요구사항이 없다. 검증은 수동 실행(k6 스크립트 실제 구동)으로 이루어진다.

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| BENCH-01 | k6 스크립트 3종 존재 | manual | `ls scripts/k6/{sync,outbox,kafka}.js` | ❌ Wave 0 |
| BENCH-02 | 비동기 스크립트 폴링 루프 포함 | manual-only | 실제 k6 실행 후 결과 확인 | N/A |
| BENCH-03 | TPS/p50/p95/p99/에러율 출력 | manual-only | k6 end-of-test summary 확인 | N/A |
| BENCH-04 | 동일 VU 조건 3전략 실행 | manual-only | `scripts/k6/run-benchmark.sh` 실행 | ❌ Wave 0 |
| BENCH-05 | BENCHMARK.md 존재 + 내용 | manual | `ls BENCHMARK.md` | ❌ Wave 0 |

**Manual-only 정당성:** k6 부하 테스트는 실행 시간(3분/전략)과 외부 인프라(DB, Kafka, 애플리케이션 서버)가 필요해 JUnit 자동화 범위가 아니다.

### Sampling Rate
- **Per task commit:** 스크립트 파일 구문 확인 (`docker run --rm grafana/k6 inspect /scripts/sync.js`)
- **Per wave merge:** 단일 VU로 smoke test (`docker run --rm grafana/k6 run --vus 1 --duration 10s /scripts/sync.js`)
- **Phase gate:** 세 전략 모두 `run-benchmark.sh`로 실행 완료 + BENCHMARK.md 수치 기재 완료

### Wave 0 Gaps
- [ ] `scripts/k6/helpers.js` — 공통 상수, setup(), pollStatus()
- [ ] `scripts/k6/sync.js` — Sync 전략 스크립트
- [ ] `scripts/k6/outbox.js` — Outbox 전략 스크립트
- [ ] `scripts/k6/kafka.js` — Kafka 전략 스크립트
- [ ] `scripts/k6/run-benchmark.sh` — 자동화 실행 스크립트
- [ ] `scripts/k6/README.md` — 실행 가이드
- [ ] `src/main/resources/application-benchmark.yml` — benchmark 프로파일
- [ ] `BENCHMARK.md` — 수치 자리표시자 포함 결과 문서

---

## Sources

### Primary (HIGH confidence)
- 프로젝트 소스 직접 분석
  - `src/main/java/.../PaymentController.java` — API 엔드포인트 확인
  - `src/main/java/.../PaymentConfirmAsyncResult.java` — ResponseType enum 확인
  - `src/main/java/.../PaymentStatusResponse.java` — 상태값 PENDING/PROCESSING/DONE/FAILED 확인
  - `src/main/java/.../KafkaConfirmAdapter.java` — TOPIC = "payment-confirm" 확인
  - `src/main/java/.../SyncConfirmAdapter.java` — ConditionalOnProperty 패턴 확인
  - `src/main/resources/data.sql` — userId=1,2 / productId=1(50000원),2(30000원) / stock=59 확인
  - `src/main/resources/application.yml` — spring.payment.async-strategy 설정키 확인
  - `src/main/resources/application-docker.yml` — profile 구조 패턴 확인
  - `src/test/java/.../FakeTossHttpOperator.java` — plain class, test 소스에 위치 확인
  - `scripts/common.sh` — 기존 스크립트 컨벤션 확인
  - `docker/compose/docker-compose.yml` — Kafka PLAINTEXT://kafka:9092 확인
- `.planning/phases/05-k6-benchmark/05-CONTEXT.md` — 모든 결정 사항

### Secondary (MEDIUM confidence)
- k6 공식 문서 (훈련 데이터 기반 — SharedArray, sleep(), http 모듈, Trend 메트릭)
- k6 Docker 실행 방식 (`grafana/k6` 이미지, volume mount 패턴)

### Tertiary (LOW confidence)
- 없음

---

## Metadata

**Confidence breakdown:**
- Standard Stack: HIGH — 프로젝트 소스에서 직접 확인
- Architecture: HIGH — 기존 코드 패턴과 CONTEXT.md 결정 기반
- Pitfalls: MEDIUM — stock 부족, Docker 네트워크는 프로젝트 분석으로 확인. k6 sleep 단위는 훈련 데이터

**Research date:** 2026-03-15
**Valid until:** 2026-04-15 (k6 API는 안정적, Spring Boot 프로파일 변경 없음)
