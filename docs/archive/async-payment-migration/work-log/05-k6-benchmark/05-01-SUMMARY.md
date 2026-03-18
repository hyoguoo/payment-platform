---
phase: 05-k6-benchmark
plan: "01"
subsystem: testing
tags: [k6, benchmark, load-test, performance, docker]

requires:
  - phase: 04-kafka-adapter
    provides: "Kafka 전략 어댑터 완성 — kafka.js 가 측정 대상"
  - phase: 03-db-outbox-adapter
    provides: "Outbox 전략 어댑터 완성 — outbox.js 가 측정 대상"
  - phase: 01-port-contract-status-endpoint
    provides: "GET /payments/{orderId}/status 엔드포인트 — pollStatus() 가 호출"
provides:
  - "k6 부하 테스트 스크립트 3종 (sync.js / outbox.js / kafka.js)"
  - "공통 헬퍼 모듈 (helpers.js) — setup(), pollStatus(), BENCHMARK_STAGES, getOrderIndex()"
  - "세 전략 순서 실행 자동화 스크립트 (run-benchmark.sh)"
  - "벤치마크 실행 가이드 (README.md)"
affects:
  - 05-02
  - 05-03

tech-stack:
  added:
    - "k6 (grafana/k6 Docker 이미지)"
  patterns:
    - "SharedArray 대신 setup() 반환값으로 orderId 풀 공유 — k6 ES6 모듈 제약 대응"
    - "getOrderIndex(): (VU-1)*97 + ITER 소수 패턴으로 VU간 충돌 최소화"
    - "e2e_latency_ms Trend 메트릭: 202 수신~DONE 폴링 완료까지 측정"
    - "BENCHMARK_STAGES import로 세 전략 동일 부하 조건 보장"

key-files:
  created:
    - scripts/k6/helpers.js
    - scripts/k6/sync.js
    - scripts/k6/outbox.js
    - scripts/k6/kafka.js
    - scripts/k6/run-benchmark.sh
    - scripts/k6/README.md
  modified: []

key-decisions:
  - "setup() 반환값으로 orderId 풀 전달 — k6 ES6 모듈 환경에서 SharedArray import는 사용 불가"
  - "POLL_INTERVAL_S=0.5초, POLL_TIMEOUT_MS=30000ms — 비동기 전략 완료 대기 파라미터"
  - "getOrderIndex에 소수 97 사용 — 200 VU * 다수 ITER 조합에서 충돌 최소화"

patterns-established:
  - "k6 전략 분리: 각 전략마다 독립 파일, helpers.js에서 공통 setup/pollStatus 재사용"
  - "e2eLatency Trend: outbox/kafka에서 202 수신~최종 상태까지 end-to-end 시간 측정"

requirements-completed:
  - BENCH-01
  - BENCH-02
  - BENCH-04

duration: 3min
completed: 2026-03-16
---

# Phase 05 Plan 01: k6 Benchmark Scripts Summary

**grafana/k6 Docker 기반 부하 테스트 스크립트 3종 — 50→100→200 VU 동일 조건에서 sync/outbox/kafka 세 전략 비교 인프라 구축**

## Performance

- **Duration:** 3 min
- **Started:** 2026-03-16T11:23:32Z
- **Completed:** 2026-03-16T11:26:42Z
- **Tasks:** 3
- **Files modified:** 6 (모두 신규 생성)

## Accomplishments

- `helpers.js`: `setup()` 으로 1,000개 orderId 사전 생성, `pollStatus()` 로 202 비동기 완료 대기, `BENCHMARK_STAGES` export로 동일 부하 조건 보장
- `sync.js / outbox.js / kafka.js`: 세 전략 각각 독립 측정 스크립트, outbox/kafka는 e2e_latency_ms Trend 메트릭 포함
- `run-benchmark.sh`: common.sh 색상 함수 활용, 전략별 user prompt로 서버 재기동 안내 후 순서 실행
- `README.md`: macOS/Linux 개별 실행 명령어 분리, k6 결과 해석법(p50/p95/p99, TPS, 에러율) 포함

## Task Commits

1. **Task 1: helpers.js 공통 헬퍼 모듈** - `918cf68` (feat)
2. **Task 2: 세 전략 측정 스크립트** - `e8ea83f` (feat)
3. **Task 3: run-benchmark.sh + README.md** - `0222240` (feat)

## Files Created/Modified

- `scripts/k6/helpers.js` - 공통 상수, setup(), getOrderIndex(), pollStatus(), BENCHMARK_STAGES
- `scripts/k6/sync.js` - Sync 전략 k6 스크립트 (200 OK 검증)
- `scripts/k6/outbox.js` - Outbox 전략 k6 스크립트 (202 → 폴링 → DONE 검증)
- `scripts/k6/kafka.js` - Kafka 전략 k6 스크립트 (202 → 폴링 → DONE 검증)
- `scripts/k6/run-benchmark.sh` - 세 전략 순서 실행 자동화 (실행 권한 포함)
- `scripts/k6/README.md` - Docker 실행법, 전략 전환법, 결과 해석법

## Decisions Made

- `setup()` 반환값으로 orderId 풀 전달: k6 ES6 모듈 환경에서 SharedArray는 별도 import 필요하나, setup() 반환값 패턴이 더 단순하고 안전
- `getOrderIndex()` 에서 소수 97 곱수 사용: 200 VU × 다수 ITER 조합에서 `% poolSize` 충돌을 최소화하는 분산 패턴
- Thresholds 없음: 순수 측정이 목적이므로 임계값 설정 없이 raw 수치 수집

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None. Java 코드 무변경으로 `./gradlew test` 회귀 없음 (BUILD SUCCESSFUL, 7 tasks up-to-date).

## User Setup Required

벤치마크 실행 전 사용자가 직접 준비해야 하는 항목:
1. Docker 실행
2. MySQL 실행 (`docker/compose`)
3. 서버를 `benchmark` 프로파일로 기동 (`./gradlew bootRun --args='--spring.profiles.active=benchmark'`)
4. Kafka 전략 측정 시: Kafka 서비스 추가 기동

상세 안내: `scripts/k6/README.md` 참조

## Next Phase Readiness

- **05-02**: sync 전략 벤치마크 실행 준비 완료 — `scripts/k6/sync.js` 실행 가능
- **05-03**: 세 전략 비교 분석 준비 완료 — 측정 후 BENCHMARK.md 기입 필요
- 차단 사항 없음

---
*Phase: 05-k6-benchmark*
*Completed: 2026-03-16*
