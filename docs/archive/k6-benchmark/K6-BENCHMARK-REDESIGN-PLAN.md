# k6 벤치마크 스크립트 재설계 구현 플랜

> 작성일: 2026-03-28

## 목표

idle 테스트 제거 및 메트릭·부하 단계 통일로 sync vs outbox-parallel 결과를 동일 지표로 비교 가능하게 만든다.

## 컨텍스트

- 설계 문서: [docs/topics/K6-BENCHMARK-REDESIGN.md](../topics/K6-BENCHMARK-REDESIGN.md)
- 주요 변경 파일:
  - `scripts/k6/helpers.js`
  - `scripts/k6/sync.js`
  - `scripts/k6/outbox.js`
  - `scripts/k6/run-benchmark.sh`
  - 삭제: `scripts/k6/outbox-idle.js`

---

## 진행 상황

- [x] Task 1: helpers.js 상수 정리
- [x] Task 2: sync.js 부하 단계 통일 및 메트릭 이름 변경
- [x] Task 3: outbox.js idle 시나리오 제거 및 메트릭 이름 변경
- [x] Task 4: outbox-idle.js 삭제 및 run-benchmark.sh 정리

---

## 태스크

### Task 1: helpers.js 상수 정리 [tdd=false]

**구현**
- `scripts/k6/helpers.js`
  - `TREND_E2E_LATENCY`, `TREND_E2E_UNDER_LOAD`, `TREND_E2E_IDLE` 제거
  - `TREND_E2E_COMPLETION = 'e2e_completion_ms'` 추가
  - `RAMPING_ARRIVAL_RATE_STAGES`: `100→200→400` 으로 수정 (현재 `50→100→200`)
  - sync 전용이었던 별도 stages 상수 불필요 → `RAMPING_ARRIVAL_RATE_STAGES` 단일 공유

**완료 기준**
- 기존 `TREND_E2E_LATENCY`, `TREND_E2E_UNDER_LOAD`, `TREND_E2E_IDLE` import 참조 없음
- `TREND_E2E_COMPLETION` export 확인

**완료 결과**
> `TREND_E2E_COMPLETION = 'e2e_completion_ms'` 단일 상수로 통일. `RAMPING_ARRIVAL_RATE_STAGES`를 `100→200→400`으로 변경.

---

### Task 2: sync.js 부하 단계 통일 및 메트릭 이름 변경 [tdd=false]

**구현**
- `scripts/k6/sync.js`
  - `SYNC_THROUGHPUT_STAGES` (100→300→500) 제거 → `RAMPING_ARRIVAL_RATE_STAGES` import로 대체
  - `e2eLatency` Trend 메트릭: `TREND_E2E_LATENCY` → `TREND_E2E_COMPLETION` import
  - `e2eLatency` 변수명 → `e2eCompletion`으로 변경

**완료 기준**
- `SYNC_THROUGHPUT_STAGES` 상수 제거됨
- throughput stages가 `100→200→400`으로 동작
- `e2e_completion_ms` 메트릭으로 기록됨

**완료 결과**
> `SYNC_THROUGHPUT_STAGES` 제거, `RAMPING_ARRIVAL_RATE_STAGES` 공유. `e2eLatency` → `e2eCompletion`, 메트릭명 `e2e_completion_ms`로 통일.

---

### Task 3: outbox.js idle 시나리오 제거 및 메트릭 이름 변경 [tdd=false]

**구현**
- `scripts/k6/outbox.js`
  - `OUTBOX_THROUGHPUT_STAGES` (100→200→400) 제거 → `RAMPING_ARRIVAL_RATE_STAGES` import로 대체
  - `e2eUnderLoad` Trend 메트릭: `TREND_E2E_UNDER_LOAD` → `TREND_E2E_COMPLETION` import
  - `e2eUnderLoad` 변수명 → `e2eCompletion`으로 변경
  - `TREND_E2E_IDLE`, `COUNTER_E2E_TIMEOUT` import 정리 (COUNTER_E2E_TIMEOUT은 유지)

**완료 기준**
- `OUTBOX_THROUGHPUT_STAGES` 상수 제거됨
- throughput stages가 `100→200→400`으로 동작
- `e2e_completion_ms` 메트릭으로 기록됨

**완료 결과**
> `OUTBOX_THROUGHPUT_STAGES` 제거, `RAMPING_ARRIVAL_RATE_STAGES` 공유. `e2eUnderLoad` → `e2eCompletion`. `measureE2e` 파라미터에서 미사용 `trendMetric` 제거.

---

### Task 4: outbox-idle.js 삭제 및 run-benchmark.sh 정리 [tdd=false]

**구현**
- `scripts/k6/outbox-idle.js` 삭제
- `scripts/k6/run-benchmark.sh`
  - `run_outbox()` 함수에서 idle run 제거:
    - `run_k6 "outbox-idle.js" "${testid}-idle"` 라인 제거
    - idle 관련 주석 제거
    - idle reset_data 호출 제거 (throughput 전 reset_data 하나만 남김)
  - 스크립트 마지막 안내 메시지에서 idle 결과 경로 제거

**완료 기준**
- `outbox-idle.js` 파일 없음
- `run-benchmark.sh`에 idle 관련 코드 없음
- `run_outbox()`가 `switch_strategy → reset_data → run_k6(outbox.js)` 순서로 동작

**완료 결과**
> `outbox-idle.js` 삭제. `run_outbox()`에서 idle run 및 관련 주석 제거. 결과 경로 안내 메시지 단순화.
