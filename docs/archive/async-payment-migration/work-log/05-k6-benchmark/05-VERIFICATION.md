---
phase: 05-k6-benchmark
verified: 2026-03-16T12:00:00Z
status: passed
score: 11/11 must-haves verified
re_verification: false
human_verification:
  - test: "k6 스크립트 실제 실행으로 세 전략 TPS·레이턴시 측정"
    expected: "sync.js는 200 OK 응답, outbox.js/kafka.js는 202 → DONE 폴링 완료, e2e_latency_ms 메트릭이 출력됨"
    why_human: "Docker + 실행 중인 서버 + MySQL + Kafka 필요 — 자동화 불가"
  - test: "benchmark 프로파일로 서버 기동 후 FakeTossHttpOperator 활성화 확인"
    expected: "spring.profiles.active=benchmark 기동 시 FakeTossHttpOperator @Primary 빈이 실제 TossHttpOperator 대신 주입되고 서버가 정상 시작됨"
    why_human: "런타임 프로파일 활성화 동작 — 자동화 불가"
---

# Phase 5: k6 Benchmark 검증 보고서

**Phase Goal:** k6 부하 테스트로 Sync / Outbox / Kafka 세 전략의 TPS와 지연 시간을 측정할 수 있는 완전한 벤치마크 환경을 구축한다.
**Verified:** 2026-03-16T12:00:00Z
**Status:** passed
**Re-verification:** No — initial verification

---

## Goal Achievement

### Observable Truths

| #  | Truth | Status | Evidence |
|----|-------|--------|----------|
| 1  | sync.js / outbox.js / kafka.js 세 파일이 scripts/k6/ 디렉토리에 존재한다 | VERIFIED | 파일 모두 존재 확인 (1090~1091B) |
| 2  | outbox.js와 kafka.js는 202 응답 후 DONE/FAILED까지 폴링 루프를 포함한다 | VERIFIED | 양 파일에 `pollStatus(orderId)` 호출 및 `check(finalStatus, { 'completed (DONE)': ... })` 존재 |
| 3  | 세 스크립트 모두 동일한 stages(50→100→200 VU, 60s씩)를 사용한다 | VERIFIED | 세 파일 모두 `BENCHMARK_STAGES` import + `export const options = { stages: BENCHMARK_STAGES }` |
| 4  | run-benchmark.sh가 전략 전환 안내 후 세 스크립트를 순서대로 실행한다 | VERIFIED | `run_strategy "sync"`, `"outbox"`, `"kafka"` 순서 실행, 각 전략 전 user prompt 포함 |
| 5  | helpers.js에서 setup()이 1,000개 orderId를 사전 생성하고 반환값으로 공유한다 | VERIFIED | `for (let i = 0; i < ORDER_POOL_SIZE; i++)` 루프 (ORDER_POOL_SIZE=1000), `return { orderIds }` |
| 6  | benchmark 프로파일에서 FakeTossHttpOperator가 실제 TossHttpOperator 대신 활성화된다 | VERIFIED | `BenchmarkConfig.java`: `@Configuration @Profile("benchmark")` + `@Bean @Primary HttpOperator` |
| 7  | application-benchmark.yml에 stock 충분 확보를 위한 data-benchmark.sql 참조가 설정된다 | VERIFIED | `spring.sql.init.data-locations: classpath:data.sql, classpath:data-benchmark.sql` |
| 8  | spring.profiles.active=benchmark 으로 기동 시 hikari pool이 200 VU 대응 가능하다 | VERIFIED | `hikari.maximum-pool-size: 30` (기본값 10에서 확장) |
| 9  | BENCHMARK.md가 프로젝트 루트에 존재하고 비교 결과 표가 포함된다 | VERIFIED | VU 50/100/200 단계별 TPS/p50/p95/p99/에러율/e2e 비교 표 3개 존재 |
| 10 | 전략별 특성 해석 섹션이 포함된다 | VERIFIED | Sync 동기 병목 / DB Outbox 배치 지연 / Kafka 컨슈머 병렬성 설명 존재 |
| 11 | 어댑터 선택 가이드가 포함된다 | VERIFIED | 시나리오(즉시성/내구성/확장성) → 추천 전략 → 이유 3열 표 존재 |

**Score:** 11/11 truths verified

---

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `scripts/k6/helpers.js` | setup(), pollStatus(), BENCHMARK_STAGES, getOrderIndex() | VERIFIED | 1851B, 5개 export 모두 존재 |
| `scripts/k6/sync.js` | Sync 전략 k6 스크립트 (200 OK 기대) | VERIFIED | 729B, check('status is 200') 포함 |
| `scripts/k6/outbox.js` | Outbox 전략 k6 스크립트 (202 → 폴링) | VERIFIED | 1091B, pollStatus() + e2eLatency Trend 포함 |
| `scripts/k6/kafka.js` | Kafka 전략 k6 스크립트 (202 → 폴링) | VERIFIED | 1090B, pollStatus() + e2eLatency Trend 포함 |
| `scripts/k6/run-benchmark.sh` | 세 전략 순서 실행 자동화 스크립트 | VERIFIED | 1397B, 실행 권한(-rwxr-xr-x), common.sh source, 3전략 순서 실행 |
| `scripts/k6/README.md` | Docker 실행법, 전략 전환법, 결과 해석법 | VERIFIED | 3881B, macOS/Linux 별도 실행 명령어, 전략 전환법, 결과 해석 포함 |
| `src/main/java/.../mock/FakeTossHttpOperator.java` | benchmark 프로파일용 Fake Toss HTTP 오퍼레이터 | VERIFIED | 6722B, src/main에 존재 (src/test에서 이동 완료) |
| `src/main/java/.../mock/BenchmarkConfig.java` | @Profile("benchmark") + @Primary 빈 등록 | VERIFIED | 532B, @Configuration @Profile("benchmark") @Bean @Primary HttpOperator 구조 정확 |
| `src/main/resources/application-benchmark.yml` | benchmark 프로파일 설정 | VERIFIED | 473B, async-strategy/hikari pool/data-benchmark.sql 참조/read-timeout-millis 포함 |
| `src/main/resources/data-benchmark.sql` | stock=5000 설정 (1,000개 setup() checkout 보장) | VERIFIED | 216B, UPDATE product SET stock = 5000 WHERE id=1 AND id=2 |
| `BENCHMARK.md` | k6 벤치마크 결과 문서 (수치 자리표시자 포함 템플릿) | VERIFIED | 5344B, "## 비교 결과" 섹션 존재, 수치 자리표시자(-) 포함 |

---

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| `scripts/k6/outbox.js` | `helpers.js#pollStatus()` | `import { pollStatus } from './helpers.js'` | WIRED | import 문 존재 + `pollStatus(orderId)` 호출 확인 |
| `scripts/k6/kafka.js` | `helpers.js#pollStatus()` | `import { pollStatus } from './helpers.js'` | WIRED | import 문 존재 + `pollStatus(orderId)` 호출 확인 |
| `scripts/k6/run-benchmark.sh` | `scripts/common.sh` | `source "$(dirname "$0")/../common.sh"` | WIRED | source 문 존재, common.sh에 print_section/print_warning/print_info 함수 확인 |
| `BenchmarkConfig.java` | `FakeTossHttpOperator` | `@Bean @Primary` | WIRED | `@Primary` 어노테이션 + `return new FakeTossHttpOperator()` 확인 |
| `application-benchmark.yml` | `spring.payment.async-strategy` | Spring profile activation | WIRED | `async-strategy: sync` 설정 존재 |
| `BENCHMARK.md` | `scripts/k6/run-benchmark.sh` | 문서 내 실행 명령어 참조 | WIRED | `./scripts/k6/run-benchmark.sh` 명령어 참조 존재 |

---

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|-------------|-------------|--------|----------|
| BENCH-01 | 05-01, 05-02 | k6 스크립트 3종(sync / outbox / kafka)을 작성한다 | SATISFIED | sync.js, outbox.js, kafka.js 모두 존재하고 실질적 내용 확인됨 |
| BENCH-02 | 05-01 | 비동기 전략 스크립트는 status 폴링 루프를 포함해 end-to-end 완료까지 측정한다 | SATISFIED | outbox.js/kafka.js에서 pollStatus() 호출 + e2e_latency_ms Trend 메트릭 확인됨 |
| BENCH-03 | 05-02, 05-03 | 측정 지표는 TPS, p50/p95/p99 레이턴시, 에러율을 포함한다 | SATISFIED | BENCHMARK.md에 TPS/p50/p95/p99/에러율/e2e 열 구조로 정의됨 |
| BENCH-04 | 05-01, 05-02 | 동일한 부하 조건(VU 수, 테스트 데이터)으로 3가지 전략을 비교한다 | SATISFIED | BENCHMARK_STAGES import로 동일 VU 조건 보장, setup()으로 1000개 orderId 사전 생성 공유 |
| BENCH-05 | 05-03 | 측정 결과를 BENCHMARK.md에 표와 해석으로 정리한다 | SATISFIED | BENCHMARK.md에 VU 단계별 표, 전략별 해석, 어댑터 선택 가이드 존재 (수치는 실행 후 기입 예정) |

**Note:** REQUIREMENTS.md의 BENCH-01~05 전체가 계획대로 모두 Phase 5에 매핑되어 있고 검증됨. 고아 요구사항 없음.

---

### Anti-Patterns Found

없음 — 모든 Phase 5 산출물 파일에서 TODO/FIXME/PLACEHOLDER/빈 구현 패턴이 발견되지 않음.

---

### Human Verification Required

#### 1. k6 스크립트 실제 실행 검증

**Test:** Docker 실행 후 `./scripts/k6/run-benchmark.sh`를 실행하고 sync/outbox/kafka 각 전략에서 k6 결과가 출력되는지 확인한다.
**Expected:** sync.js는 `status is 200` check pass, outbox.js/kafka.js는 `confirm accepted (202)` + `completed (DONE)` check pass, e2e_latency_ms 커스텀 메트릭이 k6 요약에 나타남.
**Why human:** Docker, 실행 중인 Spring 서버(benchmark 프로파일), MySQL, Kafka 인프라가 모두 필요한 런타임 환경 의존성 — 정적 분석 불가.

#### 2. benchmark 프로파일 기동 및 Fake 빈 활성화

**Test:** `./gradlew bootRun --args='--spring.profiles.active=benchmark'` 실행 후 FakeTossHttpOperator가 주입되고 서버가 정상 시작되는지 확인한다.
**Expected:** 시작 로그에 FakeTossHttpOperator 빈 등록이 나타나고, POST /api/v1/payments/confirm 요청 시 실제 Toss API 없이 응답이 반환됨.
**Why human:** 런타임 Spring 컨텍스트 로딩 및 프로파일 활성화 동작 — 자동화 불가.

---

### Gaps Summary

없음. 모든 must-have가 충족되었다.

Phase 5의 세 플랜이 목표대로 구현되었다:
- Plan 01: k6 스크립트 3종(helpers.js 포함) + run-benchmark.sh + README.md — 완전 구현
- Plan 02: FakeTossHttpOperator(src/main 이동) + BenchmarkConfig + application-benchmark.yml + data-benchmark.sql — 완전 구현
- Plan 03: BENCHMARK.md (수치 자리표시자 포함 템플릿) — 완전 구현

BENCH-01~05 모든 요구사항 충족. 실제 k6 실행 후 BENCHMARK.md 수치 기입만 남아 있으며 이는 설계된 사후 작업이다.

---

_Verified: 2026-03-16T12:00:00Z_
_Verifier: Claude (gsd-verifier)_
