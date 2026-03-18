# Phase 5: k6 Benchmark - Context

**Gathered:** 2026-03-15
**Status:** Ready for planning

<domain>
## Phase Boundary

세 전략(Sync/Outbox/Kafka)을 동일한 부하 조건에서 측정하고, 각 전략의 TPS·레이턴시·에러율 비교 결과를 BENCHMARK.md에 기록한다.
k6 스크립트 3종(sync.js / outbox.js / kafka.js) 작성과 BENCHMARK.md 문서화가 대상이다.
모니터링 대시보드(Grafana), 분산 k6 실행, 실시간 결과 스트리밍은 이 Phase의 범위가 아니다.

</domain>

<decisions>
## Implementation Decisions

### 부하 설정
- **VU 단계**: 50 → 100 → 200 VU (3단계 순차 측정)
- **각 단계 지속 시간**: 60초 (전체 3분)
- **패턴**: Constant load (ramping 없음)
- **Thresholds**: 없음 — 순수 측정만, pass/fail 기준 없음

### 테스트 데이터 전략
- **orderId 준비**: k6 `setup()` 단계에서 checkout API를 호출해 orderId 생성 — 실제 측정 트래픽에 checkout 요청 포함되지 않아 공정한 비교
- **풀 크기**: 1,000개 orderId 사전 생성
- **할당 방식**: `SharedArray` + 순번 할당으로 VU별 전담 orderId 사용 — 동일 orderId 중복 서브밋 방지

### 폴링 파라미터 (비동기 스크립트)
- **Interval**: 500ms — Kafka 컨슈머 처리속도(통상 100ms 미만) 대비 충분한 간격
- **Timeout**: 30초 — Outbox 최대 재시도(5회) 라인에서 충분한 대기
- **Timeout 초과 처리**: `check()` 실패 태그 기록 후 이터레이션 계속 — 에러율에 반영됨

### Toss API 대체 (부하 테스트 환경)
- **방법**: `application-benchmark.yml` 프로파일로 `FakeTossHttpOperator` 빈 활성화
- **이유**: 실제 Toss Sandbox 호출 시 외부 API 응답속도가 병목이 되어 어댑터 간 성능 차이 측정 불가
- **활성화**: `spring.profiles.active=benchmark` 설정으로 서버 기동 — 코드 변경 없음

### 전략 전환 방법
- `application.yml`의 `spring.payment.async-strategy` 값을 직접 수정 후 애플리케이션 재기동
- `run-benchmark.sh`에 각 전략 전환 안내 텍스트 포함

### k6 실행 방법
- **실행**: Docker로 k6 실행 — `docker run --rm grafana/k6 run script.js`
- **로컬 k6 설치 불필요**, 포트폴리오 환경에서 취약점 없음

### 스크립트 구조
- **위치**: `scripts/k6/` 디렉토리
  - `helpers.js` — setup(), pollStatus(), 공통 상수 (BASE_URL, POLL_INTERVAL 등)
  - `sync.js` — Sync 전략 측정
  - `outbox.js` — Outbox 전략 측정 (폴링 루프 포함)
  - `kafka.js` — Kafka 전략 측정 (폴링 루프 포함)
  - `README.md` — Docker 실행 명령어, 설정 변경법, 결과 해석 방법
- **자동화**: `scripts/k6/run-benchmark.sh` — 세 전략 순서대로 실행하는 쉘 스크립트

### BENCHMARK.md 범위
- **위치**: 프로젝트 루트 (`BENCHMARK.md`)
- **내용 구성**:
  1. 측정 환경 및 조건 (VU 단계, 기간, Fake Toss 사용 명시)
  2. TPS / p50 / p95 / p99 / 에러율 비교 표 (수치 자리표시자 포함)
  3. 전략별 특성 해석 (Sync 동기 병목 / Outbox 배치 지연 / Kafka 컨슈머 병렬성)
  4. 시나리오별 어댑터 선택 가이드 (고TPS 우선 / 안정성 우선 / 단순성 우선)
- **실제 수치**: 템플릿으로 작성, 직접 k6 실행 후 채워 넣는 구조

### Claude's Discretion
- `helpers.js` 내 `pollStatus()` 함수의 정확한 구현 방식
- `SharedArray` orderId 순번 할당 시 원자성 처리 방법 (k6 VU 컨텍스트 내 처리)
- `run-benchmark.sh` 전략 전환 안내 메시지 형식
- `FakeTossHttpOperator`의 `application-benchmark.yml` 등록 방식 세부사항

</decisions>

<code_context>
## Existing Code Insights

### Reusable Assets
- `FakeTossHttpOperator` (@TestConfiguration): `application-benchmark.yml` 프로파일로 메인 컨텍스트에서도 활성화 가능 — 부하 테스트 시 외부 API 의존 제거
- `scripts/common.sh`, `scripts/run.sh`: 기존 쉘 스크립트 패턴 참고 — `scripts/k6/` 하위에 동일 컨벤션 적용
- `scripts/demo/`: 데모 데이터 seed 패턴 참고 — k6 `setup()`에서 checkout API 호출 패턴 설계 시 참고
- Docker Compose (`docker/compose/docker-compose.yml`): MySQL + Kafka + kafbat/kafka-ui 이미 정의됨 — k6 테스트 전 compose up으로 인프라 준비

### Established Patterns
- `@ConditionalOnProperty`: Sync/Outbox/Kafka 어댑터 전환 패턴 — `application-benchmark.yml` 프로파일도 동일 패턴 활용
- `GET /api/v1/payments/{orderId}/status`: Phase 1에서 구현 완료 — 비동기 스크립트 폴링 타겟

### Integration Points
- `POST /api/v1/payments/confirm` — k6 부하 요청 타겟
- `GET /api/v1/payments/{orderId}/status` — 비동기 스크립트 폴링 타겟
- `spring.payment.async-strategy` 설정 — 전략 전환 제어 포인트
- `spring.profiles.active=benchmark` — FakeTossHttpOperator 활성화 포인트

</code_context>

<specifics>
## Specific Ideas

- Kafka 어댑터의 핵심 차별점은 컨슈머 병렬 처리 — k6 비교에서 Kafka가 Outbox보다 높은 TPS를 보여야 포트폴리오 목적 달성
- 비동기 스크립트는 `202 Accepted` → status 폴링 → `DONE` 확인까지 end-to-end 완료 시간을 측정 (단순 202 응답 시간이 아닌 실제 처리 완료까지)
- BENCHMARK.md의 어댑터 선택 가이드는 포트폴리오 리뷰어가 "어느 전략을 언제 쓰면 되는가"를 바로 이해할 수 있도록 작성

</specifics>

<deferred>
## Deferred Ideas

없음 — 논의가 Phase 5 범위 내에서 유지됨

</deferred>

---

*Phase: 05-k6-benchmark*
*Context gathered: 2026-03-15*
