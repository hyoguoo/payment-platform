# Round 0 — Interviewer 질의응답 요약

> topic: STOCK-COMPENSATION-RECOVERY
> date: 2026-05-01
> mode: AskUserQuestion 4문항 + 코드 사실 확인

## Ambiguity Ledger 4트랙

### scope (payment-service 한정)
- 변경 범위: payment-service 내부만. pg-service / product-service 코드 변경 없음.
- 보상 대상 메시지: events.confirmed FAILED / QUARANTINED 분기.
- 변경 코드 위치: `PaymentConfirmResultUseCase.compensateStockCache` + 신규 보상 outbox 인프라.
- (코드 확인 — Path 1) `StockCachePort.increment(productId, qty)` 자체 멱등 보장 없음 — 보상 멱등성은 outbox row 단위로 보장한다.

### constraints (사용자 답변 + 코드 확인)
- **회복 메커니즘**: RDB 보상 outbox 신규 (`stock_compensation_outbox`). happy path 영향 0 — 보상 실패 시에만 row 적재.
- **적재 단위**: 주문 항목 단위 N행. 부분 실패 격리 + 멱등성 키 (orderId+productId) 자연스러움.
- **재시도 정책**: 기존 `payment_outbox` 정렬 — FIXED 5s × max-attempts 5 (`RetryPolicyProperties` 재사용 가능).
  - (코드 확인) `application.yml` — `payment.retry.max-attempts: 5`, `scheduler.outbox-worker.fixed-delay-ms: 5000`.
- **DLQ 경로**: RDB FAILED 상태 마킹만. Kafka DLQ 토픽 신설하지 않음. 운영 admin 도구로 조회.
- **dedupe 와 무충돌**: events.confirmed two-phase lease 는 그대로 둔다 — 보상 실패가 lease extend 를 막지 않도록 outbox INSERT 는 "성공" 으로 간주되어 lease 가 P8D 로 연장된다 (메시지는 ack). 회복은 별도 outbox 워커가 책임.

### outputs (Architect 가 채울 산출물 목록)
- 신규 도메인: `StockCompensationOutbox` POJO + `StockCompensationOutboxStatus` enum (PENDING / FAILED).
  - 기존 `StockOutbox` 와 동일 패턴 — `processedAt IS NULL` = PENDING, status enum 으로 FAILED 마킹.
- 신규 테이블: `stock_compensation_outbox` (Flyway V).
  - 컬럼 안: id / order_id / product_id / quantity / reason_code / status / available_at / processed_at / attempt / created_at.
  - UNIQUE 제약: `(order_id, product_id)` 후보 — 중복 적재 방지 + 멱등성 키.
- 신규 어댑터: `StockCompensationOutboxRepository` (JPA / JdbcTemplate, 기존 outbox 패턴 따름).
- 신규 use case: `StockCompensationRetryService` (워커가 호출).
- 신규 워커: `StockCompensationWorker` (`@Scheduled` 폴링, 기존 `OutboxWorker` 패턴).
- 변경 use case: `PaymentConfirmResultUseCase.compensateStockCache` — 실패 시 outbox INSERT.

### verification (Architect 가 채울 영역, 기본 가정 명시)
- 단위 테스트: outbox 적재 / 워커 retry / status 전이 / UNIQUE 충돌 시나리오.
- 통합 테스트: Testcontainers (MySQL + Redis) — `stockCachePort.increment` Mockito 로 RuntimeException 주입 → outbox 적재 검증 → 워커 trigger → 정상화 검증.
- Toxiproxy 시나리오 추가는 본 토픽 범위 외 (Phase 4 에서 다룸 — CONCERNS C-1).
- (가정) 테스트 수 변경: 기존 `compensateStockCache` 단위 테스트 보강 + 신규 워커 통합 테스트.

## 확정된 가정

- A1. **happy path 영향 0** — `compensateStockCache` 정상 경로에는 outbox INSERT 없음. 실패 catch 분기에서만 INSERT.
- A2. **격리 마킹은 보상과 분리** — `QuarantineCompensationHandler.handle` 의 `markPaymentAsQuarantined` 는 보상 실패와 무관하게 정상 호출. 격리 마킹 자체가 실패하면 별개 트랙 (현재 dedupe two-phase lease catch 경로 재사용).
- A3. **outbox INSERT 자체는 성공한다는 전제** — RDB 가 살아있으면 outbox INSERT 는 성공. RDB 자체 장애는 더 큰 outage 로 별도 처리 (현 토픽 범위 외).
- A4. **멱등성 키**: UNIQUE `(order_id, product_id)` — 같은 (주문, 상품) 에 보상이 두 번 트리거되어도 row 는 1건만. 단 INSERT 충돌 시 status 가 이미 PENDING 이면 no-op, FAILED 면 PENDING 으로 reset 할지 별도 결정 (Architect Round 1).
- A5. **재시도 종결 정책**: attempt ≥ 5 시 status=FAILED 마킹. 재시도 영구 중단. 운영자 admin 도구로 status=FAILED 조회 후 수동 처리.
- A6. **워커 스케줄**: 기존 `OutboxWorker` / `StockOutboxWorker` 와 동일 패턴 — `@Scheduled fixedDelayMs=5000`, `parallel-enabled=true`, VT executor 재사용.

## 3-Path Routing 분포

| Path | 사용 횟수 | 비고 |
|---|---|---|
| Path 1 (code) | 3 | RetryPolicy 설정 / StockCachePort 시그니처 / 기존 outbox 패턴 |
| Path 2 (user) | 4 (AskUserQuestion 4문항) | 회복 메커니즘 / 적재 단위 / 재시도 정책 / DLQ 경로 |
| Path 3 (hybrid) | 0 | — |
| Path 4 (research) | 0 | 기존 패턴 재사용으로 외부 조사 불필요 |

Dialectic Rhythm Guard 통과 — Path 1 3연속 후 Path 2 4문항으로 사용자 결정 회수.

## 종료 조건 충족 확인

- [x] scope / constraints / outputs / verification 4트랙 모두 1회 이상 커버
- [x] 핵심 가정 6건 사용자 또는 코드 확인을 거침
- [x] Architect Round 1 진입 가능 — 6개 가정 + 4가지 사용자 결정 → 설계 작성 가능

→ Round 1 (Architect) 진입.
