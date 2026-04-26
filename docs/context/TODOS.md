# Planned Cleanup / Future Work

> 최종 갱신: 2026-04-27 (MSA + PRE-PHASE-4-HARDENING 봉인 후 전면 재작성).
> 이 파일은 현재 활성 작업 범위 밖이지만 향후 처리가 필요한 항목을 추적한다.
> discuss 단계 시작 시 다음 작업을 고를 때 이 파일을 참고한다.

---

## Phase 4 — 다음 토픽 (계획됨, 활성 X)

### T4-A — Toxiproxy 8종 장애 주입 시나리오

- Kafka producer/consumer 지연
- DB 지연 / 연결 끊김
- payment-service / pg-service 프로세스 kill + 재시작
- 보상 트랜잭션 중복 진입 방지 (D12 가드 실증)
- FCG (Final Confirmation Gate) PG timeout
- Redis dedupe / stock cache 다운
- 재고 캐시 발산 시나리오
- DLQ 소진

각 시나리오: `payment_outbox_pending_age_seconds` p95≥10s, 결제·재고 정합성 교차 검증.

### T4-B — k6 시나리오 재설계

- Gateway → payment confirm → 비동기 status 폴링 단일 시나리오
- 경로별 TPS / p95 / p99 / failure rate 메트릭
- ramping-arrival-rate 부하 곡선

### T4-C — 로컬 오토스케일러

- Prometheus 큐 길이 / CPU 임계 기반 payment-service 레플리카 자동 scale
- docker compose scale up/down 자동화
- scale 결정 logging + Grafana dashboard

### T4-D — CircuitBreaker 적용 (ADR-22)

- `ProductHttpAdapter` / `UserHttpAdapter` 에 Resilience4j CircuitBreaker
- Prometheus 메트릭 (`circuit_breaker_state`, `circuit_breaker_calls_total`)
- 폐쇄/반열림/열림 상태 시각화

---

## Phase 4 후속 — 자동 운영 도구 (계획됨)

### TQ-1 — DLQ 처리 정책 + admin tool

- `payment.commands.confirm.dlq`, `payment.events.confirmed.dlq` 가 자동 처리되지 않음
- 별도 admin endpoint 또는 CLI 로 트리아지 + 재발행 가능하도록
- 조건부 자동 재시도 (벤더 5xx 같은 일시적 실패)

### TQ-2 — QUARANTINED-ADMIN-RECOVERY

- `PaymentEventStatus.QUARANTINED` 결제의 수동 복구 인터페이스
- 관리자가 검토 후 DONE / FAILED 로 강제 전이 + audit
- 격리 사유별 (AMOUNT_MISMATCH, CACHE_DOWN, 판단 불가) UI

### TQ-3 — REDIS-CACHE-FAILURE-POLICY

- `redis-stock` 다운 시 어떤 정책으로 가야 하는가? — 현재는 CACHE_DOWN → QUARANTINED + 보상 펜딩
- 운영 시 Redis HA / fallback 정책 결정 필요

### TQ-4 — Vendor 동적 라우팅

- 현재 `gatewayType` 은 client 결정. 벤더 장애 시 자동 fallback 미구현
- 헬스 체크 기반 동적 라우팅 정책

### TQ-5 — multi-broker Kafka

- 현재 broker 1대 + replication-factor=1
- HA 환경 검증 필요

### TQ-6 — Cancel / Refund 워크플로우

- `PgGatewayPort.cancel(...)` 인터페이스만 존재
- 운영 cancel 정책 + 부분 환불 + audit trail

---

## Low priority — 코드 청결도

### TC-1 — observability 대시보드 현행화

- Grafana dashboard JSON 들이 옛 메트릭 이름 일부 사용 가능
- Phase 4 부하 테스트 시작 전 inventory + 갱신

### TC-2 — Seed 데이터 분리 (운영 안전성)

- `product/V2__seed_product_stock.sql`, `user/V2__seed_user.sql` 가 운영 배포에도 같이 적용됨
- 옵션: `spring.flyway.locations` 환경별 분리 또는 placeholder 활용
- 현재는 데모 / 스모크 환경에서 동작하므로 우선순위 낮음

### TC-3 — EXPIRED 만료 스케줄러 정책 명확화

- `PaymentEventStatus.EXPIRED` 정의는 있으나 도메인 매핑이 PRE-PHASE-4 시점에 일부 제거됨
- `quarantine_compensation_pending` 컬럼은 호환용 유지
- 만료 스케줄러 정책 (몇 시간 후 EXPIRED 전이?) 별도 토픽 정리 필요

---

## Plan 작성 시 사용 가이드

- 각 T 항목을 새 토픽으로 승격할 때 `docs/topics/<TOPIC>.md` + `docs/<TOPIC>-PLAN.md` 신규
- 본 TODOS 의 항목은 plan 의 "근거" 절에서 인용 가능
- 토픽 종결 시 본 파일에서 해당 항목 삭제 (또는 archive briefing 으로 이전)

## 관련

- 학습된 함정: `PITFALLS.md`
- 알려진 우려: `CONCERNS.md`
- 직전 봉인 토픽 회고: `docs/archive/{msa-transition,pre-phase-4-hardening}/COMPLETION-BRIEFING.md`
