# Phase 6: k6 JSON Path Fix + Benchmark Execution - Research

**Researched:** 2026-03-17
**Domain:** k6 벤치마크 실행 + Spring Boot YAML 설정 패치
**Confidence:** HIGH

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

#### scheduler.enabled 픽스
- `src/main/resources/application-benchmark.yml`에 `scheduler:\n  enabled: true` 1줄만 추가
- `application.yml`(기본 프로파일)에는 추가하지 않음 — dev/test 환경에서 OutboxWorker 미실행은 의도적 설계
- Phase 6 코드 커밋에 함께 포함 (독립 커밋 아님)
- SchedulerConfig 유닛 테스트 신규 추가 불필요 — 실제 벤치마크 실행 시 동작 확인

#### 벤치마크 실행 방식
- Plan에 **CHECKPOINT: Action Required** 추가 — 사용자가 서버 기동 + k6 실행 후 Enter
- 전략 전환: `application.yml`의 `spring.payment.async-strategy` 직접 수정 후 재기동
- 실행 순서: `run-benchmark.sh` 그대로 사용 (sync → outbox → kafka)
- 서버 기동 명령: `./gradlew bootRun --args='--spring.profiles.active=benchmark'`

#### k6 결과 저장 방식
- **terminal summary + JSON export 모두 저장**
- `--summary-export=scripts/k6/results/{strategy}-{vu}.json` 플래그 추가
- BENCHMARK.md 수치는 JSON/terminal에서 수동 전사
- e2e_latency_ms Trend 메트릭 그대로 유지 (스크립트 변경 없음)

#### BENCHMARK.md 기록 범위
- 50 VU / 100 VU / 200 VU 3단계 모두 기록
- 각 단계: TPS, p50, p95, p99, 에러율, e2e p50, e2e p95
- 기록 완료 후 REQUIREMENTS.md BENCH-02/03 체크박스 `[x]` 업데이트

### Claude's Discretion
- JSON export 저장 디렉토리 구조 (`scripts/k6/results/` 여부)
- run-benchmark.sh에 --summary-export 플래그 추가 방식
- BENCHMARK.md 수치 기입 형식 (소수점 자리수 등)

### Deferred Ideas (OUT OF SCOPE)
없음 — 논의가 Phase 6 범위 내에서 유지됨
</user_constraints>

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| BENCH-02 | 비동기 전략 스크립트는 status 폴링 루프를 포함해 end-to-end 완료까지 측정한다 (공정한 비교) | outbox.js/kafka.js의 pollStatus() 구현 이미 완료; e2e_latency_ms Trend 메트릭으로 캡처됨 |
| BENCH-03 | 측정 지표는 TPS(requests/sec), p50/p95/p99 레이턴시, 에러율을 포함한다 | k6 내장 http_reqs rate + http_req_duration + checks 메트릭으로 자동 집계; BENCHMARK.md 표 구조 기존 정의됨 |
</phase_requirements>

---

## Summary

Phase 6은 코드 변경이 최소인 실행 완료 단계다. 핵심 미결 사항은 두 가지다: (1) `application-benchmark.yml`에 `scheduler.enabled: true` 1줄 추가 — 이것 없이는 Outbox 전략에서 `OutboxWorker` `@Scheduled` 빈이 활성화되지 않아 PENDING 레코드가 영원히 처리되지 않는다. (2) `run-benchmark.sh`에 `--summary-export` 플래그 추가로 k6 결과를 JSON 파일로 보존.

`helpers.js`의 JSON 경로 수정(커밋 c3149a2)은 이미 완료됐다. `setup()`은 `body.data.orderId`를 읽고 `pollStatus()`는 `body.data.status`를 읽는다 — ResponseAdvice 래핑 구조(`{ data: {...} }`)와 일치한다. 스크립트 자체는 변경 불필요.

벤치마크 실행은 사람이 직접 수행해야 한다 (서버 기동, 전략 전환, k6 실행). Plan에 CHECKPOINT 단계를 포함해 사용자 액션 후 BENCHMARK.md에 수치를 전사하는 흐름을 안내한다.

**Primary recommendation:** Wave 0에서 YAML 수정 + sh 수정 + results/ 디렉토리 생성만 처리하고, Wave 1은 CHECKPOINT로 사용자 벤치마크 실행을 대기한 뒤 BENCHMARK.md 수치 기입 + REQUIREMENTS.md 업데이트로 마무리한다.

---

## Standard Stack

### Core (이미 확립됨)

| Component | Version/Value | Purpose | Status |
|-----------|--------------|---------|--------|
| k6 | `grafana/k6` Docker 이미지 | 부하 테스트 실행기 | 기존 사용 중 |
| Spring Boot benchmark 프로파일 | `application-benchmark.yml` | 벤치마크 전용 설정 | 기존 존재, 패치 필요 |
| `SchedulerConfig` | `@ConditionalOnProperty(name="scheduler.enabled", havingValue="true")` | OutboxWorker 스케줄링 게이트 | 패치로 활성화 필요 |
| `run-benchmark.sh` | bash, docker run | sync/outbox/kafka 순서 실행 | 기존 존재, --summary-export 추가 필요 |

### SchedulerConfig 동작 확인

`SchedulerConfig.java`는 `@ConditionalOnProperty(name="scheduler.enabled", havingValue="true")`로 선언되어 있다. `matchIfMissing`이 없으므로 기본값은 `false` — 즉, 이 프로퍼티가 없으면 `@EnableScheduling`이 비활성된다. `application-benchmark.yml`에 이 값이 없으면 Outbox 전략에서 워커가 실행되지 않는다.

`application-docker.yml`에 이미 `scheduler.enabled: true`가 선언되어 있다. 동일 패턴을 `application-benchmark.yml`에 추가한다.

### k6 --summary-export 플래그

`docker run grafana/k6 run --summary-export=/results/{strategy}.json /scripts/{script}.js` 형태로 사용한다. 결과 JSON 파일이 컨테이너 내부에 쓰이므로 호스트 디렉토리를 마운트해야 한다. 현재 `run-benchmark.sh`는 `-v "${SCRIPT_DIR}:/scripts"`만 마운트하고 있다. results/ 디렉토리를 별도로 마운트하거나, 같은 볼륨 경로 내에 `results/` 서브디렉토리를 사용한다.

가장 단순한 방법: `scripts/k6/results/` 디렉토리를 생성하고 동일 볼륨(`-v "${SCRIPT_DIR}:/scripts"`) 안에서 `/scripts/results/{strategy}.json`에 저장한다. 추가 마운트 없이 기존 볼륨 경로 재사용 가능.

---

## Architecture Patterns

### YAML 프로파일 패치 패턴

`application-docker.yml`의 scheduler 블록을 그대로 참조한다:

```yaml
# application-docker.yml에서 확인된 패턴
scheduler:
  enabled: true
```

`application-benchmark.yml`에는 현재 `scheduler.enabled` 키가 없다. 최상위 레벨에 추가하면 된다. 기존 파일 구조:

```yaml
spring:
  config:
    activate:
      on-profile: benchmark
  payment:
    async-strategy: sync
  datasource:
    hikari:
      maximum-pool-size: 30
  sql:
    init:
      data-locations: ...
  myapp:
    toss-payments:
      http:
        read-timeout-millis: 30000

# 추가할 위치 (spring: 블록 밖)
scheduler:
  enabled: true
```

### run-benchmark.sh --summary-export 추가 패턴

현재 `docker run` 명령:
```bash
docker run --rm \
  -v "${SCRIPT_DIR}:/scripts" \
  -e BASE_URL="${BASE_URL}" \
  grafana/k6 run "/scripts/${script}"
```

수정 패턴 (`results/` 서브디렉토리는 `/scripts/results/`로 접근 가능):
```bash
mkdir -p "${SCRIPT_DIR}/results"

docker run --rm \
  -v "${SCRIPT_DIR}:/scripts" \
  -e BASE_URL="${BASE_URL}" \
  grafana/k6 run \
    --summary-export="/scripts/results/${strategy}.json" \
    "/scripts/${script}"
```

전략명(`$strategy`)을 파일명으로 사용하면 `sync.json`, `outbox.json`, `kafka.json` 3개가 생성된다.

### BENCHMARK.md 수치 기입 형식

BENCHMARK.md 표 구조는 이미 올바르게 정의되어 있다. VU 단계별 수치를 k6 terminal summary에서 읽어 채운다:

| 메트릭 | k6 출력 항목 |
|--------|------------|
| TPS | `http_reqs` rate (req/s) |
| p50 | `http_req_duration` p(50) |
| p95 | `http_req_duration` p(95) |
| p99 | `http_req_duration` p(99) |
| 에러율 | 100% - `checks` pass% |
| e2e p50 | `e2e_latency_ms` p(50) (비동기 전략만) |
| e2e p95 | `e2e_latency_ms` p(95) (비동기 전략만) |

VU 단계별 수치는 k6가 전체 테스트 종료 후 집계 summary로 출력한다. 단계 구분 없이 전체 실행 평균/백분위가 제공된다. 각 VU 단계(50/100/200)를 독립적으로 실행해 단계별 수치를 얻으려면 각 VU를 별도 실행해야 한다.

**현재 설계:** `BENCHMARK_STAGES`는 ramping 스테이지 형식 (`{ duration: '60s', target: 50 }` → 100 → 200)으로 정의되어 있다. k6은 전체 실행의 단일 summary만 출력한다. 결과적으로 50/100/200 VU 수치를 분리하려면 세 번의 독립 실행이 필요하다.

**CONTEXT.md 결정은 3단계 모두 기록을 요구한다.** 이는 현재 `run-benchmark.sh`가 단일 ramping 실행만 하므로, run-benchmark.sh를 3회 분리 실행하거나 stage별 결과를 추론하는 방식이 필요하다.

현실적인 접근: CONTEXT.md의 결정에 따르면 사용자가 직접 수동 전사한다. k6의 `--stage` 옵션이나 단계별 개별 실행으로 얻은 수치를 BENCHMARK.md에 기입하는 것은 사용자 재량이다. PLAN에서는 "3단계 수치 기록" 지침을 CHECKPOINT 단계에 명시한다.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| k6 결과 저장 | 커스텀 파싱 스크립트 | `--summary-export` 플래그 | k6 내장 기능, JSON 구조 보장됨 |
| 스케줄러 활성화 조건 | 새 `@ConditionalOnProperty` 빈 | 기존 `SchedulerConfig` + YAML 1줄 | 이미 구현됨, YAML 패치만 필요 |

---

## Common Pitfalls

### Pitfall 1: scheduler.enabled 누락 시 Outbox 전략 PENDING 무한 대기
**What goes wrong:** `application-benchmark.yml`에 `scheduler.enabled: true`가 없으면 `SchedulerConfig` 빈이 생성되지 않고 `@EnableScheduling`이 비활성된다. Outbox 전략에서 confirm 요청이 PENDING 상태로 저장되지만 OutboxWorker가 실행되지 않아 k6 pollStatus()가 30초 타임아웃 후 'TIMEOUT'을 반환한다.
**Why it happens:** `SchedulerConfig`에 `matchIfMissing`이 없어 프로퍼티 부재 = false로 동작한다.
**How to avoid:** `scheduler.enabled: true`를 `application-benchmark.yml`에 추가한다.
**Warning signs:** Outbox 전략 k6 실행 시 모든 VU가 'completed (DONE)' check 실패 + e2e 타임아웃.

### Pitfall 2: --summary-export 결과 파일이 컨테이너 내부에만 저장됨
**What goes wrong:** `--rm` 플래그로 컨테이너가 종료되면 컨테이너 내부 경로에 저장된 파일이 삭제된다.
**Why it happens:** 마운트된 볼륨 경로 밖에 파일을 저장하면 호스트에서 접근 불가.
**How to avoid:** `/scripts/results/` 경로(기존 `-v "${SCRIPT_DIR}:/scripts"` 볼륨 내부)에 저장한다. `mkdir -p "${SCRIPT_DIR}/results"`로 디렉토리 선행 생성 필요.
**Warning signs:** k6 실행 완료 후 `scripts/k6/results/` 디렉토리에 JSON 파일이 없음.

### Pitfall 3: orderId 재사용 충돌로 confirm 실패
**What goes wrong:** 동일 orderId로 confirm을 두 번 시도하면 중복 처리 오류가 발생할 수 있다.
**Why it happens:** `getOrderIndex()` 소수 97 곱수 패턴이 1000개 풀로 200 VU × 다수 ITER 조합을 커버하지만 완전한 충돌 방지는 아니다.
**How to avoid:** 이미 확립된 패턴 유지. 에러율이 일정 수준 이상이면 orderId 풀 크기 증가 고려 (Phase 6 범위 밖).
**Warning signs:** k6 `checks` 실패율이 높고 서버 로그에 "이미 처리된 orderId" 오류가 다수 발생.

---

## Code Examples

### application-benchmark.yml scheduler 추가 (확인된 패턴)

```yaml
# Source: application-docker.yml 패턴 (scheduler.enabled: true 이미 사용 중)
scheduler:
  enabled: true
```

`application-benchmark.yml` 최하단에 추가. `spring:` 블록 밖에 위치해야 한다 (application-docker.yml과 동일한 들여쓰기 레벨).

### run-benchmark.sh --summary-export 추가

```bash
# Source: k6 공식 CLI 플래그 (--summary-export)
run_strategy() {
  local strategy=$1
  local script=$2
  mkdir -p "${SCRIPT_DIR}/results"
  # ... (대기 및 준비 메시지)

  docker run --rm \
    -v "${SCRIPT_DIR}:/scripts" \
    -e BASE_URL="${BASE_URL}" \
    grafana/k6 run \
      --summary-export="/scripts/results/${strategy}.json" \
      "/scripts/${script}"
}
```

---

## State of the Art

| 항목 | 현재 상태 | Phase 6 완료 후 |
|------|-----------|-----------------|
| helpers.js JSON 경로 | 수정 완료 (c3149a2) — `body.data.orderId`, `body.data.status` | 변경 없음 |
| application-benchmark.yml | scheduler.enabled 없음 → Outbox 전략 동작 안 함 | `scheduler.enabled: true` 추가 |
| run-benchmark.sh | --summary-export 없음 → 결과 파일 미저장 | --summary-export 추가 |
| BENCHMARK.md | 수치 모두 `-` 로 비어 있음 | 실측값으로 채워짐 |
| REQUIREMENTS.md BENCH-02/03 | `[ ]` 미완료 | `[x]` 완료 |

---

## Open Questions

1. **50/100/200 VU 단계별 수치 분리 방법**
   - What we know: 현재 `BENCHMARK_STAGES`는 50→100→200 ramping 단계를 단일 실행으로 묶는다. k6는 전체 실행의 단일 summary만 출력한다.
   - What's unclear: CONTEXT.md 결정은 "3단계 모두 기록"을 요구하나, 현재 스크립트 설계로는 단계별 독립 수치를 자동 분리하기 어렵다.
   - Recommendation: PLAN CHECKPOINT에서 "각 VU 단계를 독립적으로 실행하거나 ramping 결과를 단계 구간별로 추정해 기록"을 사용자에게 안내한다. 스크립트 수정은 CONTEXT.md 결정(스크립트 변경 없음)에 따라 하지 않는다.

---

## Validation Architecture

nyquist_validation이 config.json에서 `true`로 설정되어 있다.

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit 5 + Gradle (`./gradlew test`) |
| Config file | `build.gradle` (JaCoCo 포함) |
| Quick run command | `./gradlew test` |
| Full suite command | `./gradlew test jacocoTestReport` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| BENCH-02 | outbox.js/kafka.js가 pollStatus()로 end-to-end 완료까지 측정 | manual-only | N/A — k6 실행은 서버 기동 필요, CI 자동화 불가 | N/A |
| BENCH-03 | http_reqs rate + http_req_duration + checks 메트릭이 BENCHMARK.md에 기록됨 | manual-only | N/A — 실측값 전사는 사람이 수행 | N/A |

> **manual-only 사유:** BENCH-02/03은 k6 부하 테스트 실행 + 결과 전사로 구성된다. 서버를 benchmark 프로파일로 기동하고 Docker로 k6를 실행하는 것은 단위/통합 테스트 프레임워크로 자동화할 수 없다. 실행 결과가 BENCHMARK.md에 기입됐는지 여부가 완료 기준이다.

### Sampling Rate

- **Per task commit:** `./gradlew test` (YAML 변경이 기존 테스트를 깨지 않는지 확인)
- **Per wave merge:** `./gradlew test`
- **Phase gate:** `./gradlew test` green + BENCHMARK.md 수치 기입 완료

### Wave 0 Gaps

None — 기존 테스트 인프라가 Phase 6 범위를 커버한다. YAML 패치와 sh 수정은 신규 테스트 파일을 요구하지 않는다.

---

## Sources

### Primary (HIGH confidence)
- `src/main/java/com/hyoguoo/paymentplatform/core/config/SchedulerConfig.java` — `@ConditionalOnProperty` 조건 직접 확인
- `src/main/resources/application-docker.yml` — `scheduler.enabled: true` 패턴 확인
- `src/main/resources/application-benchmark.yml` — scheduler 키 부재 확인
- `scripts/k6/helpers.js` — JSON 경로 (`body.data.orderId`, `body.data.status`) 현재 상태 확인
- `scripts/k6/run-benchmark.sh` — `docker run` 명령 구조 확인
- `scripts/k6/outbox.js`, `kafka.js` — `e2e_latency_ms` Trend 메트릭 구현 확인
- `BENCHMARK.md` — 표 구조 및 빈 수치 확인

### Secondary (MEDIUM confidence)
- k6 `--summary-export` 플래그: 공식 k6 CLI 기능으로 알려진 동작 (training data + docker 볼륨 마운트 패턴으로 추론)

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — 모든 파일 직접 확인, 변경 범위가 YAML 1줄 + sh 플래그 추가로 명확
- Architecture: HIGH — 기존 패턴(application-docker.yml) 그대로 적용, SchedulerConfig 코드 직접 확인
- Pitfalls: HIGH — scheduler.enabled 누락은 코드 직접 확인으로 검증됨; --summary-export 볼륨 이슈는 docker 마운트 원리로 HIGH

**Research date:** 2026-03-17
**Valid until:** 안정적 — 2026-04-17 (설정 파일 기반, 라이브러리 변경 없음)
