# Phase 6: k6 JSON Path Fix + Benchmark Execution - Context

**Gathered:** 2026-03-17
**Status:** Ready for planning

<domain>
## Phase Boundary

`application-benchmark.yml`에 `scheduler.enabled: true` 1줄을 추가해 Outbox E2E 흐름을 복구하고, 세 전략(sync/outbox/kafka) k6 벤치마크를 실행해 실제 측정값을 BENCHMARK.md에 기록한다.

- `helpers.js` JSON 경로 수정은 이미 완료(c3149a2) — 추가 코드 변경 없음
- REQUIREMENTS.md BENCH-02, BENCH-03 체크박스 `[x]` 업데이트 포함

</domain>

<decisions>
## Implementation Decisions

### scheduler.enabled 픽스
- `src/main/resources/application-benchmark.yml`에 `scheduler:\n  enabled: true` 1줄만 추가
- `application.yml`(기본 프로파일)에는 추가하지 않음 — dev/test 환경에서 OutboxWorker 미실행은 의도적 설계
- Phase 6 코드 커밋에 함께 포함 (독립 커밋 아님)
- SchedulerConfig 유닛 테스트 신규 추가 불필요 — 실제 벤치마크 실행 시 동작 확인

### 벤치마크 실행 방식
- Plan에 **CHECKPOINT: Action Required** 추가 — 사용자가 서버 기동 + k6 실행 후 Enter
- 전략 전환: `application.yml`의 `spring.payment.async-strategy` 직접 수정 후 재기동
- 실행 순서: `run-benchmark.sh` 그대로 사용 (sync → outbox → kafka)
- 서버 기동 명령: `./gradlew bootRun --args='--spring.profiles.active=benchmark'`

### k6 결과 저장 방식
- **terminal summary + JSON export 모두 저장**
- `--summary-export=scripts/k6/results/{strategy}-{vu}.json` 플래그 추가
- BENCHMARK.md 수치는 JSON/terminal에서 수동 전사
- e2e_latency_ms Trend 메트릭 그대로 유지 (스크립트 변경 없음)

### BENCHMARK.md 기록 범위
- 50 VU / 100 VU / 200 VU 3단계 모두 기록
- 각 단계: TPS, p50, p95, p99, 에러율, e2e p50, e2e p95
- 기록 완료 후 REQUIREMENTS.md BENCH-02/03 체크박스 `[x]` 업데이트

### Claude's Discretion
- JSON export 저장 디렉토리 구조 (`scripts/k6/results/` 여부)
- run-benchmark.sh에 --summary-export 플래그 추가 방식
- BENCHMARK.md 수치 기입 형식 (소수점 자리수 등)

</decisions>

<code_context>
## Existing Code Insights

### Reusable Assets
- `scripts/k6/run-benchmark.sh`: sync → outbox → kafka 순서로 대기 후 실행. `--summary-export` 플래그만 추가하면 JSON 저장 가능
- `scripts/k6/helpers.js`: JSON 경로 이미 수정됨 (`body.data.status`, `.data.orderId`) — 수정 불필요
- `scripts/k6/outbox.js`, `kafka.js`: `e2e_latency_ms` Trend 메트릭 이미 구현됨

### Established Patterns
- `application-docker.yml`에 `scheduler.enabled: true` 이미 존재 — 동일 패턴 benchmark.yml에 적용
- Phase 5 CONTEXT.md에서 결정된 VU/duration/Fake Toss 설정 그대로 유지

### Integration Points
- `src/main/resources/application-benchmark.yml` — scheduler.enabled 추가 대상
- `scripts/k6/run-benchmark.sh` — --summary-export 플래그 추가 대상
- `BENCHMARK.md` (프로젝트 루트) — 실측값 기입 대상
- `REQUIREMENTS.md` — BENCH-02/03 체크박스 업데이트 대상

</code_context>

<specifics>
## Specific Ideas

- run-benchmark.sh 실행 시 각 전략 측정 결과를 `scripts/k6/results/` 디렉토리에 JSON으로 저장 → 나중에 재참조 가능
- BENCHMARK.md는 이미 올바른 표 구조로 작성되어 있음 — 수치만 채우면 완성

</specifics>

<deferred>
## Deferred Ideas

없음 — 논의가 Phase 6 범위 내에서 유지됨

</deferred>

---

*Phase: 06-k6-json-fix*
*Context gathered: 2026-03-17*
