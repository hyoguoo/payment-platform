---
milestone: v1.0
audited: 2026-03-17T00:20:00Z
status: gaps_found
scores:
  requirements: 24/26
  phases: 6/8
  integration: 25/28
  flows: 2/3
gaps:
  requirements:
    - id: "BENCH-02"
      status: "partial"
      phase: "Phase 6 (assigned gap-closure owner)"
      claimed_by_plans: ["05-01-PLAN.md (Phase 5 VERIFICATION claims SATISFIED)"]
      completed_by_plans: []
      verification_status: "missing"
      evidence: "Phase 5 VERIFICATION claims scripts have polling loops — structurally correct. JSON path fix applied in c3149a2. However Phase 6 (assigned owner) has NO VERIFICATION.md, NO SUMMARY.md. REQUIREMENTS.md checkbox unchecked [ ]. Actual benchmark execution with real measurements not documented. Outbox strategy benchmark additionally blocked by scheduler.enabled gap (see integration)."
    - id: "BENCH-03"
      status: "unsatisfied"
      phase: "Phase 6 (assigned gap-closure owner)"
      claimed_by_plans: ["05-03-PLAN.md (BENCHMARK.md template created)"]
      completed_by_plans: []
      verification_status: "missing"
      evidence: "BENCHMARK.md still contains all '-' placeholder values. No actual TPS/p50/p95/p99/error-rate measurements recorded. Phase 6 success criteria '측정값이 BENCHMARK.md에 기록된다' not achieved. REQUIREMENTS.md checkbox unchecked [ ]."
  integration:
    - id: "scheduler-enabled-missing-benchmark-profile"
      severity: high
      files: ["src/main/resources/application-benchmark.yml"]
      root_cause: "SchedulerConfig has @ConditionalOnProperty(name='scheduler.enabled', havingValue='true') with no matchIfMissing. application.yml and application-benchmark.yml both lack scheduler.enabled=true. Only application-docker.yml sets it."
      fix: "Add 'scheduler:\\n  enabled: true' to src/main/resources/application-benchmark.yml"
      affected_requirements: ["OUTBOX-03", "OUTBOX-04", "BENCH-02 (outbox strategy)"]
  flows:
    - flow: "Outbox E2E benchmark flow"
      breaks_at: "OutboxWorker.process() invocation — @EnableScheduling never activated in benchmark profile"
      root_cause: "scheduler.enabled=true missing from application-benchmark.yml"
      steps_complete: ["POST /confirm → 202 Accepted, PENDING record saved atomically"]
      steps_missing: ["OutboxWorker.process() never invoked", "PENDING never transitions to IN_FLIGHT", "GET /status returns PENDING indefinitely", "k6 pollStatus() times out after 30s"]
tech_debt:
  - phase: "06-k6-json-fix"
    items:
      - "Phase 6 code done (c3149a2 — helpers.js JSON path fix) but bypassed GSD workflow. No PLAN.md, SUMMARY.md, VERIFICATION.md created. REQUIREMENTS.md BENCH-02/03 checkboxes not updated."
  - phase: "07-outbox-cleanup"
    items:
      - "Phase 7 code done (a3db8b7 — existsByOrderId removed from PaymentOutboxRepository, PaymentOutboxRepositoryImpl, JpaPaymentOutboxRepository) but bypassed GSD workflow. No PLAN.md, SUMMARY.md, VERIFICATION.md created."
  - phase: "04-kafka-adapter"
    items:
      - "KafkaConfirmListenerIntegrationTest excluded from ./gradlew test (@Tag('integration')) due to Docker Desktop / docker-java API version incompatibility. 263 unit tests pass; Kafka E2E integration test requires CI with compatible Docker."
      - "LOW: KafkaConfirmPublisher lacks @ConditionalOnProperty — Kafka auto-config loads in all strategies; no correctness failure but logs connection errors when Kafka unavailable."
  - phase: "03-db-outbox-adapter"
    items:
      - "REQUIREMENTS.md stale text: OUTBOX-01 references 'PaymentProcess 테이블' and OUTBOX-02 references 'executeStockDecreaseWithJobCreation' — pre-architectural-decision artifacts. Functionality correct."
  - phase: "all-phases"
    items:
      - "Nyquist validation: Phases 01-05 and 08 have VALIDATION.md stubs, all status: draft, nyquist_compliant: false, wave_0_complete: false. No phase has completed Nyquist validation."
nyquist:
  compliant_phases: []
  partial_phases: ["01", "02", "03", "04", "05", "08"]
  missing_phases: ["06", "07"]
  overall: "PARTIAL — all completed phases have VALIDATION.md stubs but none are compliant"
---

# v1.0 Milestone Audit Report (2026-03-17 재감사)

**Milestone:** v1.0 — 비동기 결제 처리 마이그레이션
**Audited:** 2026-03-17T00:20:00Z
**Prior audit:** 2026-03-16T11:52:53Z
**Status:** ⚠ gaps_found
**Auditor:** Claude (gsd-audit-milestone)

---

## Executive Summary

v1.0 마일스톤 26개 v1 요구사항 중 24개는 충족되었다. Phase 08(어댑터 리팩터링) 완료 후 재감사.

**이전 감사 이후 해소된 갭:**
- ✅ k6 JSON 경로 오류 → `c3149a2`에서 수정 (`body.data.status`, `.data.orderId`)
- ✅ `PaymentOutboxRepository.existsByOrderId` 고아 포트 메서드 → `a3db8b7`에서 제거
- ✅ Phase 08 리팩터링 완료 → `infrastructure/adapter/` 패키지 삭제, application 레이어 정상화

**남은 갭 (2개, gaps_found 유지):**
1. **BENCH-03 미충족:** BENCHMARK.md에 실측값 없음 (모두 `-` 자리표시자)
2. **신규 통합 갭:** `application-benchmark.yml`에 `scheduler.enabled=true` 누락 → Outbox 전략 벤치마크 실행 시 OutboxWorker 미동작

**점수:** requirements 24/26, phases(GSD docs) 6/8, integration 25/28, flows 2/3

---

## 1. Phase Verification Summary

| Phase | VERIFICATION.md | Status | Score | Notes |
|-------|----------------|--------|-------|-------|
| 01 Port Contract + Status Endpoint | ✅ | passed | 7/7 | — |
| 02 Sync Adapter | ✅ | passed | 4/4 | — |
| 03 DB Outbox Adapter | ✅ | passed | 10/10 | — |
| 04 Kafka Adapter | ✅ | passed (re-verified) | 12/12 | Docker gate for Testcontainers test |
| 05 k6 Benchmark | ✅ | passed | 11/11 | Runtime JSON fix now applied |
| 06 k6 JSON Path Fix | ✗ MISSING | ad-hoc commit only | — | Code: c3149a2 ✓; GSD docs: absent |
| 07 Outbox Port Cleanup | ✗ MISSING | ad-hoc commit only | — | Code: a3db8b7 ✓; GSD docs: absent |
| 08 Refactor Confirm Adapters | ✅ | passed | 10/10 | New phase; layer violation resolved |

**Phase 06/07 note:** 두 Phase의 코드 변경은 GSD 워크플로 없이 직접 커밋으로 실행되었다. 구현은 정확하나 PLAN.md, SUMMARY.md, VERIFICATION.md가 부재하고 REQUIREMENTS.md 체크박스가 미갱신 상태다.

---

## 2. Requirements Coverage (3-Source Cross-Reference)

### PORT (Phase 1) — 4/4 satisfied

| REQ-ID | VERIFICATION | REQUIREMENTS.md | Final Status |
|--------|-------------|-----------------|--------------|
| PORT-01 | SATISFIED | [x] | **satisfied** |
| PORT-02 | SATISFIED | [x] | **satisfied** |
| PORT-03 | SATISFIED | [x] | **satisfied** |
| PORT-04 | SATISFIED | [x] | **satisfied** |

### STATUS (Phase 1) — 3/3 satisfied

| REQ-ID | VERIFICATION | REQUIREMENTS.md | Final Status |
|--------|-------------|-----------------|--------------|
| STATUS-01 | SATISFIED | [x] | **satisfied** |
| STATUS-02 | SATISFIED | [x] | **satisfied** |
| STATUS-03 | SATISFIED | [x] | **satisfied** |

### SYNC (Phase 2) — 3/3 satisfied

| REQ-ID | VERIFICATION | REQUIREMENTS.md | Final Status | Notes |
|--------|-------------|-----------------|--------------|-------|
| SYNC-01 | SATISFIED | [x] | **satisfied** | Phase 08: SyncConfirmAdapter 제거 → PaymentConfirmServiceImpl 직접 구현으로 대체 |
| SYNC-02 | SATISFIED | [x] | **satisfied** | — |
| SYNC-03 | SATISFIED | [x] | **satisfied** | Phase 08 리팩터 후 `doConfirm()` private 추출 — 내부 로직 동일, 정신 보존 |

### OUTBOX (Phase 3) — 6/6 satisfied (integration gap in benchmark context)

| REQ-ID | VERIFICATION | REQUIREMENTS.md | Final Status | Notes |
|--------|-------------|-----------------|--------------|-------|
| OUTBOX-01 | SATISFIED | [x] | **satisfied** | — |
| OUTBOX-02 | SATISFIED | [x] | **satisfied** | — |
| OUTBOX-03 | SATISFIED | [x] | **satisfied** | Production wiring OK; benchmark profile has scheduler gap |
| OUTBOX-04 | SATISFIED | [x] | **satisfied** | Same caveat as OUTBOX-03 |
| OUTBOX-05 | SATISFIED | [x] | **satisfied** | — |
| OUTBOX-06 | SATISFIED | [x] | **satisfied** | existsByOrderId orphan removed in a3db8b7 |

### KAFKA (Phase 4) — 7/7 satisfied

| REQ-ID | VERIFICATION | REQUIREMENTS.md | Final Status |
|--------|-------------|-----------------|--------------|
| KAFKA-01 | SATISFIED | [x] | **satisfied** |
| KAFKA-02 | SATISFIED | [x] | **satisfied** |
| KAFKA-03 | SATISFIED | [x] | **satisfied** |
| KAFKA-04 | SATISFIED | [x] | **satisfied** |
| KAFKA-05 | SATISFIED | [x] | **satisfied** |
| KAFKA-06 | SATISFIED | [x] | **satisfied** |
| KAFKA-07 | SATISFIED (code) | [x] | **satisfied** — Docker gate for runtime |

### BENCH (Phases 5/6) — 3/5 satisfied

| REQ-ID | Phase | VERIFICATION | REQUIREMENTS.md | Final Status |
|--------|-------|-------------|-----------------|--------------|
| BENCH-01 | 5 | SATISFIED | [x] | **satisfied** |
| BENCH-02 | 6 | MISSING | **[ ]** | ⚠ **partial** — scripts structurally correct, JSON fix applied; no runtime execution evidence |
| BENCH-03 | 6 | MISSING | **[ ]** | ✗ **unsatisfied** — BENCHMARK.md has all `-` placeholders, no real measurements |
| BENCH-04 | 5 | SATISFIED | [x] | **satisfied** |
| BENCH-05 | 5 | SATISFIED | [x] | **satisfied** (template with correct structure) |

**Orphan detection:** 고아 요구사항 없음. 26개 모두 최소 1개 VERIFICATION.md에서 검증됨.

---

## 3. FAIL Gate: Unsatisfied Requirements

### BENCH-03 — Unsatisfied (주 블로커)

**요구사항:** 측정 지표는 TPS(requests/sec), p50/p95/p99 레이턴시, 에러율을 포함한다

**미충족 이유:**
- BENCHMARK.md 템플릿 존재 (열 구조 정의): ✓
- 실제 측정값 기록: ✗ — 모든 셀에 `-` 자리표시자
- Phase 6 성공 기준 "측정값이 BENCHMARK.md에 기록된다": ✗ 미달성
- REQUIREMENTS.md 체크박스: `[ ]` 미체크

**해결 방법:** Outbox 스케줄러 갭 수정 후 k6 벤치마크 3종 실행 → BENCHMARK.md 수치 기입

### BENCH-02 — Partial

**요구사항:** 비동기 전략 스크립트는 status 폴링 루프를 포함해 end-to-end 완료까지 측정한다

**미충족 이유:**
- 폴링 루프 구조: ✓ (Phase 5 검증)
- helpers.js JSON 경로 수정: ✓ (c3149a2 적용)
- 실제 end-to-end 측정 실행 및 문서화: ✗
- REQUIREMENTS.md 체크박스: `[ ]` 미체크
- Phase 6 GSD 문서(VERIFICATION.md 등): 부재
- **추가 블로커:** Outbox 전략 벤치마크 시 OutboxWorker 미동작 (scheduler.enabled 누락)

---

## 4. Cross-Phase Integration

### 신규 발견: scheduler.enabled 누락 (HIGH)

**파일:** `src/main/resources/application-benchmark.yml`

**원인:** `SchedulerConfig.java`가 `@ConditionalOnProperty(name="scheduler.enabled", havingValue="true")`로 `@EnableScheduling`을 조건부 활성화한다. `application.yml`과 `application-benchmark.yml` 모두 `scheduler.enabled=true`를 미설정. `application-docker.yml`에만 설정됨.

**결과:** benchmark Spring 프로파일(`FakeTossHttpOperator` 활성화에 필요)로 기동 시:
- `@EnableScheduling` 미활성 → `OutboxWorker.@Scheduled` 미호출
- PENDING 레코드가 IN_FLIGHT로 전환되지 않음
- `GET /status`가 항상 PENDING 반환
- k6 `pollStatus()` 30초 후 TIMEOUT

**수정:** `application-benchmark.yml`에 1줄 추가:
```yaml
scheduler:
  enabled: true
```

**영향 요구사항:** OUTBOX-03, OUTBOX-04, BENCH-02 (outbox 전략)

### E2E 흐름 검증

#### Sync Confirm — COMPLETE ✓
```
POST /confirm → PaymentConfirmServiceImpl (@ConditionalOnProperty sync, matchIfMissing=true)
  → doConfirm(): executePayment + confirmPaymentWithGateway + executePaymentSuccessCompletion
  ← SYNC_200 → HTTP 200
```

#### Kafka Async Confirm — COMPLETE ✓
```
POST /confirm → KafkaAsyncConfirmService (@ConditionalOnProperty kafka)
  → executePayment + executeStockDecreaseOnly
  → PaymentConfirmPublisherPort → KafkaConfirmPublisher → kafkaTemplate.send("payment-confirm")
  ← ASYNC_202 → HTTP 202

KafkaConfirmListener.consume() [@KafkaListener, @RetryableTopic(attempts=6)]
  → confirmPaymentWithGateway → executePaymentSuccessCompletion
  실패 시: DLT("payment-confirm-dlq") + @DltHandler → executePaymentFailureCompensation

GET /status → body.data.status (k6 JSON 경로 수정 완료) ✓
```

#### Outbox Async Confirm — BROKEN in benchmark profile ✗
```
POST /confirm → OutboxAsyncConfirmService (@ConditionalOnProperty outbox)
  → executePayment + executeStockDecreaseWithOutboxCreation [원자적]
  ← ASYNC_202 → HTTP 202 ✓

OutboxWorker.process() ← ✗ 미호출 (scheduler.enabled 누락)
  PENDING → IN_FLIGHT 전환 없음
  GET /status 항상 PENDING 반환
  k6 pollStatus() TIMEOUT
```

*주의: Java 코드 자체(OUTBOX-03/04)는 정상 구현됨. benchmark 프로파일 설정 누락이 원인.*

### 통합 이슈 요약

| 이슈 | 심각도 | 영향 | 수정 |
|------|-------|------|------|
| `scheduler.enabled` 누락 (application-benchmark.yml) | HIGH | OUTBOX-03/04, BENCH-02 | 1줄 추가 |
| `KafkaConfirmPublisher` 무조건 로드 | LOW | — | @ConditionalOnProperty 추가 또는 허용 |
| Phase 06/07 GSD 문서 부재 | MEDIUM | BENCH-02/03 체크박스 미갱신 | GSD 문서 소급 생성 또는 기술 부채로 기록 |

---

## 5. Nyquist Compliance

| Phase | VALIDATION.md | `nyquist_compliant` | `wave_0_complete` | Action |
|-------|-------------|---------------------|-------------------|--------|
| 01 | exists (draft) | false | false | `/gsd:validate-phase 1` |
| 02 | exists (draft) | false | false | `/gsd:validate-phase 2` |
| 03 | exists (draft) | false | false | `/gsd:validate-phase 3` |
| 04 | exists (draft) | false | false | `/gsd:validate-phase 4` |
| 05 | exists (draft) | false | false | `/gsd:validate-phase 5` |
| 06 | MISSING | — | — | GSD 실행 먼저 |
| 07 | MISSING | — | — | GSD 실행 먼저 |
| 08 | exists (draft) | false | false | `/gsd:validate-phase 8` |

모든 VALIDATION.md가 초안 상태. Nyquist 검증 미완료.

---

## 6. Tech Debt by Phase

### Phase 06 — GSD 문서 부재 (2 items)
- Phase 6 코드(c3149a2: helpers.js JSON 경로 수정) GSD 워크플로 없이 직접 커밋
- REQUIREMENTS.md BENCH-02/03 체크박스 미갱신 (여전히 `[ ]`)

### Phase 07 — GSD 문서 부재 (2 items)
- Phase 7 코드(a3db8b7: existsByOrderId 제거) GSD 워크플로 없이 직접 커밋
- PLAN.md, SUMMARY.md, VERIFICATION.md 부재

### Phase 04 — 실행 환경 제약 (2 items)
- `KafkaConfirmListenerIntegrationTest` `@Tag("integration")` 제외 (Docker Desktop 비호환)
- `KafkaConfirmPublisher` 무조건 로드 — sync/outbox 전략에서 Kafka 연결 오류 로그 발생

### Phase 03 — 문서 스테일 (1 item)
- REQUIREMENTS.md: OUTBOX-01 "PaymentProcess 테이블", OUTBOX-02 "executeStockDecreaseWithJobCreation" — 아키텍처 결정 이전 문서 아티팩트

### 전체 — Nyquist 미완료 (1 item)
- 모든 VALIDATION.md가 `status: draft` — Nyquist 검증 한 Phase도 완료 없음

### **Total: 8 items across phases**

---

## 7. Gap Closure Checklist

gaps_found 해소를 위한 최소 작업:

- [ ] `src/main/resources/application-benchmark.yml`에 `scheduler.enabled: true` 추가 (1줄 수정)
- [ ] k6 벤치마크 3종 실행 (sync / outbox / kafka)
- [ ] 실측값 BENCHMARK.md 기입 (TPS, p50/p95/p99, 에러율, e2e 레이턴시)
- [ ] REQUIREMENTS.md BENCH-02, BENCH-03 체크박스 `[x]`로 업데이트

---

_Prior audit: 2026-03-16T11:52:53Z_
_This audit: 2026-03-17T00:20:00Z_
_Auditor: Claude (gsd-audit-milestone)_
