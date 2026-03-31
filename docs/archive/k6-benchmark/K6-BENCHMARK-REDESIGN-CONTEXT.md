# k6 벤치마크 스크립트 재설계

> 최종 수정: 2026-03-28

---

## 문제 정의

현재 벤치마크 스크립트 구조에 다음 문제가 있다.

1. **idle 테스트 잡음**: `outbox-idle.js`가 매 outbox 케이스마다 실행되어 결과가 복잡해짐. idle e2e는 OutboxWorker `fixedDelay`에 의해 결정론적으로 수렴하므로 추가 측정 가치가 낮음.
2. **e2e 메트릭 이름 불일치**: sync는 `e2e_latency_ms`, outbox는 `e2e_under_load_ms`로 달라 Grafana에서 직접 비교 불가.
3. **부하 단계 불일치**: sync는 100→300→500 req/s, outbox는 100→200→400 req/s로 달라 동등 비교 어려움.

**목표**: 같은 부하 조건에서 sync vs outbox-parallel 간 TPS·e2e 완료 시간 차이를 고지연 환경에서 명확하게 보여준다.

---

## 영향 범위

- 변경: `scripts/k6/sync.js`, `scripts/k6/outbox.js`, `scripts/k6/helpers.js`, `scripts/k6/run-benchmark.sh`
- 삭제: `scripts/k6/outbox-idle.js`
- 무관: 애플리케이션 코드, `docs/context/`, `BENCHMARK.md`(결과 채울 때 별도 반영)

---

## 결정 사항

| 항목 | 결정 | 이유 |
|------|------|------|
| idle 테스트 | 제거 | 결정론적 측정값 + 결과 단순화 |
| e2e 메트릭 이름 | `e2e_completion_ms` 통일 | sync/outbox 동일 기준으로 Grafana 직접 비교 |
| 부하 단계 | sync/outbox 동일 `100→200→400 req/s` | 동등 비교 전제 조건 |
| e2e VU 수 | 5 VU 유지 | 부하 시나리오와 간섭 최소화 |
| confirm 응답 시간 | `http_req_duration` (k6 기본) 그대로 사용 | 별도 Trend 불필요 |

### 측정 지표 최종 정의

| 지표 | 메트릭 | sync | outbox-parallel |
|------|-------|------|----------------|
| confirm 응답 시간 | `http_req_duration` | 800~1500ms (Toss API 블로킹) | ~수 ms (202 즉시 반환) |
| e2e 완료 시간 | `e2e_completion_ms` | HTTP 응답 = 결제 완료 | 202 수신 ~ DONE 폴링 완료 |
| TPS | `confirm_requests` rate | 스레드 포화로 제한 | 가상스레드 병렬 처리로 높음 |
| 성공율 | `checks` rate | - | - |

### helpers.js 변경 사항

- `TREND_E2E_LATENCY`, `TREND_E2E_UNDER_LOAD`, `TREND_E2E_IDLE` → `TREND_E2E_COMPLETION = 'e2e_completion_ms'`로 통일
- `RAMPING_ARRIVAL_RATE_STAGES`: `100→200→400` 단일 정의로 sync/outbox 공유
- `COUNTER_E2E_TIMEOUT` 유지

### 기대 결과 패턴 (고지연 800~1500ms)

- **sync-high**: TPS 낮음, `e2e_completion_ms` p95 높음 (스레드 포화 + 큐잉)
- **outbox-parallel-high**: TPS 높음, `e2e_completion_ms` p95 낮음 (가상스레드 병렬 처리)

---

## 제외 범위

- `BENCHMARK.md` 결과 테이블 업데이트: 실제 측정 후 별도 작업
- Kafka 전략 스크립트: 현재 구현 범위 외
- `outbox-idle.js` 관련 `roop.md` 참조 정정: 별도 docs 업데이트

---

## 참고

- 현재 OutboxWorker `fixedDelay`: 기본 5000ms
- `OUTBOX_PARALLEL=true`일 때 가상스레드로 병렬 Toss API 호출
- k6 `ramping-arrival-rate`: executor가 목표 RPS를 유지하도록 VU를 동적 할당
