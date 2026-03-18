---
phase: 05-k6-benchmark
plan: "02"
subsystem: infra
tags: [k6, benchmark, spring-profile, fake, toss-payments]

# Dependency graph
requires:
  - phase: 05-k6-benchmark
    provides: "05-01 k6 스크립트 3종 (sync/outbox/kafka) + helpers.js + run-benchmark.sh"
provides:
  - "benchmark 프로파일용 FakeTossHttpOperator (src/main으로 이동)"
  - "@Profile(benchmark) + @Primary BenchmarkConfig로 Fake 빈 등록"
  - "application-benchmark.yml (hikari pool 30, async-strategy, data-benchmark.sql 참조)"
  - "data-benchmark.sql (product stock=5000, 1,000개 setup() checkout 보장)"
affects: [05-k6-benchmark-plan-03]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "@Profile + @Primary 조합으로 benchmark 환경에서만 Fake 빈 우선 활성화"
    - "data-locations에 추가 SQL 파일 체이닝으로 test fixture stock 재설정"

key-files:
  created:
    - src/main/java/com/hyoguoo/paymentplatform/mock/FakeTossHttpOperator.java
    - src/main/java/com/hyoguoo/paymentplatform/mock/BenchmarkConfig.java
    - src/main/resources/application-benchmark.yml
    - src/main/resources/data-benchmark.sql
  modified: []

key-decisions:
  - "FakeTossHttpOperator를 src/test에서 src/main으로 이동: benchmark 프로파일이 프로덕션 컨텍스트에서 Fake 빈을 활성화하려면 src/main에 있어야 함"
  - "application-benchmark.yml에 spring.myapp.toss-payments.http.read-timeout-millis 포함: FakeTossHttpOperator의 @Value 바인딩 필요 (docker 프로파일에만 정의되어 있었음)"
  - "data-benchmark.sql로 stock=5000 설정: data.sql stock=59로는 60번째 checkout부터 재고 부족 실패"

patterns-established:
  - "BenchmarkConfig: @Configuration @Profile(benchmark) — benchmark 환경 전용 빈 등록 패턴"

requirements-completed: [BENCH-01, BENCH-03, BENCH-04]

# Metrics
duration: 2min
completed: 2026-03-16
---

# Phase 5 Plan 02: Benchmark 프로파일 설정 Summary

**FakeTossHttpOperator를 src/main으로 이동하고 @Profile("benchmark") BenchmarkConfig + application-benchmark.yml로 부하 테스트 전용 Fake Toss 환경 구성**

## Performance

- **Duration:** 2 min
- **Started:** 2026-03-16T11:29:29Z
- **Completed:** 2026-03-16T11:31:15Z
- **Tasks:** 2
- **Files modified:** 4 (2 created in src/main/java, 2 created in src/main/resources)

## Accomplishments

- FakeTossHttpOperator를 src/test에서 src/main으로 이동 — benchmark 프로파일이 프로덕션 컨텍스트에서 Fake 빈 활성화 가능
- BenchmarkConfig (@Profile("benchmark") + @Primary) 생성 — benchmark 환경에서만 실제 TossHttpOperator 대신 Fake 빈 우선 등록
- application-benchmark.yml 생성 — async-strategy 기본값, hikari pool 30, data-benchmark.sql 참조
- data-benchmark.sql 생성 — product stock 5000으로 재설정하여 1,000개 setup() checkout 보장

## Task Commits

각 태스크를 원자적으로 커밋:

1. **Task 1: FakeTossHttpOperator 이동 + BenchmarkConfig 생성** - `7de77f3` (feat)
2. **Task 2: application-benchmark.yml + data-benchmark.sql 생성** - `c5ac8dc` (feat)

## Files Created/Modified

- `src/main/java/com/hyoguoo/paymentplatform/mock/FakeTossHttpOperator.java` - src/test에서 이동한 Fake Toss HTTP 오퍼레이터 (내용 변경 없음)
- `src/main/java/com/hyoguoo/paymentplatform/mock/BenchmarkConfig.java` - @Profile("benchmark") + @Primary @Bean HttpOperator 등록
- `src/main/resources/application-benchmark.yml` - benchmark 프로파일 설정 (async-strategy, hikari pool, data-benchmark.sql 참조, read-timeout-millis)
- `src/main/resources/data-benchmark.sql` - product stock=5000 UPDATE 구문

## Decisions Made

- **FakeTossHttpOperator src/main 이동**: benchmark 프로파일이 프로덕션 컨텍스트에서 활성화되므로 src/main에 있어야 Spring이 빈으로 등록 가능. 테스트는 classpath에서 src/main 클래스를 참조하므로 회귀 없음 (265 tests PASS).
- **spring.myapp.toss-payments.http.read-timeout-millis 추가**: 기존 FakeTossHttpOperator의 @Value 필드가 이 키를 참조하는데 docker 프로파일에만 정의되어 있었음 → benchmark yml에 포함하지 않으면 빈 생성 시 바인딩 오류 발생 (Rule 2 - 누락된 필수 설정 자동 추가).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing Critical] application-benchmark.yml에 read-timeout-millis 추가**
- **Found during:** Task 2 (application-benchmark.yml 생성)
- **Issue:** FakeTossHttpOperator가 `@Value("${spring.myapp.toss-payments.http.read-timeout-millis}")`를 참조하는데 benchmark 프로파일에 해당 설정이 없으면 빈 생성 실패
- **Fix:** application-benchmark.yml에 `spring.myapp.toss-payments.http.read-timeout-millis: 30000` 추가
- **Files modified:** src/main/resources/application-benchmark.yml
- **Verification:** ./gradlew test 265 tests PASS
- **Committed in:** c5ac8dc (Task 2 commit)

---

**Total deviations:** 1 auto-fixed (missing critical config)
**Impact on plan:** benchmark 프로파일 기동을 위한 필수 설정. 스코프 creep 없음.

## Issues Encountered

None — 계획대로 실행. 테스트 회귀 없음 (265 tests PASS).

## User Setup Required

None - 외부 서비스 설정 불필요.

## Next Phase Readiness

- benchmark 프로파일 환경 구성 완료 (FakeToss, hikari pool, stock 재설정)
- 05-03-PLAN.md (Docker Compose benchmark 환경 구성) 실행 준비 완료
- `spring.profiles.active=benchmark` 기동 시 FakeTossHttpOperator 활성화됨을 확인

---
*Phase: 05-k6-benchmark*
*Completed: 2026-03-16*
