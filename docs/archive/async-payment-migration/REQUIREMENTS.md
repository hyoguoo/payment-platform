# Requirements: Payment Platform — 비동기 결제 처리 마이그레이션

**Defined:** 2026-03-14
**Core Value:** 어떤 비동기 전략을 쓰든 Spring Bean 설정만으로 교체 가능하고, k6로 성능 차이를 즉시 측정할 수 있어야 한다.

## v1 Requirements

### PORT — 포트 추상화

- [x] **PORT-01**: `PaymentConfirmAsyncPort` 인터페이스를 정의한다 — `process(PaymentConfirmCommand): PaymentConfirmAsyncResult`
- [x] **PORT-02**: `PaymentConfirmAsyncResult`는 처리 방식(sync/async)과 결과를 담아 컨트롤러가 200/202를 자동 결정한다
- [x] **PORT-03**: `spring.payment.async-strategy=sync|outbox|kafka` 설정값 하나로 활성 어댑터를 교체할 수 있다 (`@ConditionalOnProperty`)
- [x] **PORT-04**: 설정값이 없을 경우 기존 동기 처리(sync)가 기본값으로 동작한다 (`matchIfMissing=true`)

### STATUS — 상태 조회 엔드포인트

- [x] **STATUS-01**: `GET /api/v1/payments/{orderId}/status`로 결제 처리 상태를 조회할 수 있다
- [x] **STATUS-02**: 응답은 orderId, status(PENDING/PROCESSING/DONE/FAILED), approvedAt을 포함한다
- [x] **STATUS-03**: 비동기 어댑터 사용 시 confirm은 즉시 202 Accepted + orderId를 반환하고, 클라이언트는 이 엔드포인트로 완료를 확인한다

### SYNC — 동기 어댑터

- [x] **SYNC-01**: 기존 동기 confirm 처리 로직을 `PaymentConfirmAsyncPort` 구현체(`SyncConfirmAdapter`)로 래핑한다
- [x] **SYNC-02**: Sync 어댑터 사용 시 기존 동작(200 OK + 결제 결과)이 그대로 유지된다
- [x] **SYNC-03**: 기존 `PaymentConfirmServiceImpl` 내부 로직은 변경하지 않는다 — 어댑터가 위임만 한다

### OUTBOX — DB Outbox 어댑터

- [x] **OUTBOX-01**: confirm 요청 수신 시 `payment_outbox` 테이블에 PENDING 레코드를 저장하고 즉시 202를 반환한다 (전용 테이블 사용 — PaymentProcess 재사용 없음)
- [x] **OUTBOX-02**: 재고 감소(`executeStockDecreaseWithOutboxCreation`)는 202 반환 전 동기적으로 완료된다
- [x] **OUTBOX-03**: `@Scheduled` 워커가 PENDING 레코드를 조회해 Toss API 호출 및 상태 업데이트를 처리한다
- [x] **OUTBOX-04**: 워커는 처리 시작 시 레코드를 IN_FLIGHT로 전환해 중복 실행을 방지한다 (`fixedDelay` 방식)
- [x] **OUTBOX-05**: 기존 `RETRYABLE_LIMIT = 5` 제한을 그대로 적용해 최대 재시도 후 FAILED 처리한다
- [x] **OUTBOX-06**: 보상 트랜잭션(`executePaymentFailureCompensation`)은 멱등하게 동작한다 (중복 호출 시 안전)

### KAFKA — Kafka 어댑터

- [x] **KAFKA-01**: Docker Compose에 Kafka (KRaft, 3.9), kafbat/kafka-ui를 추가한다
- [x] **KAFKA-02**: `KafkaConfirmAdapter`가 confirm 요청을 `payment-confirm` 토픽에 발행하고 202를 반환한다 (DLT 토픽 `payment-confirm-dlq` 충족을 위해 Plan 03에서 변경)
- [x] **KAFKA-03**: 재고 감소는 발행 전 동기적으로 완료된다
- [x] **KAFKA-04**: `KafkaConfirmListener`가 토픽을 컨슘해 Toss API 호출 및 상태 업데이트를 처리한다
- [x] **KAFKA-05**: Toss 결제 API의 멱등키(orderId 기반)에 위임해 중복 컨슘에 대한 멱등성을 보장한다 — 별도 `existsByOrderId` 가드 없음 (CONTEXT.md 결정)
- [x] **KAFKA-06**: 최대 재시도 후 처리 실패한 메시지는 `payment-confirm-dlq` 토픽으로 라우팅된다
- [x] **KAFKA-07**: Testcontainers Kafka로 통합 테스트를 작성한다

### BENCH — k6 성능 측정

- [x] **BENCH-01**: k6 스크립트 3종(sync / outbox / kafka)을 작성한다
- [ ] **BENCH-02**: 비동기 전략 스크립트는 status 폴링 루프를 포함해 end-to-end 완료까지 측정한다 (공정한 비교)
- [ ] **BENCH-03**: 측정 지표는 TPS(requests/sec), p50/p95/p99 레이턴시, 에러율을 포함한다
- [x] **BENCH-04**: 동일한 부하 조건(VU 수, 테스트 데이터)으로 3가지 전략을 비교한다
- [x] **BENCH-05**: 측정 결과를 BENCHMARK.md에 표와 해석으로 정리한다

## v2 Requirements

### 모니터링

- **MON-01**: Prometheus + Grafana 대시보드 — 전략별 처리량 실시간 시각화
- **MON-02**: 전략별 처리 지연 메트릭 (`payment.async.processing.duration`)

### 다중 인스턴스 대응

- **DIST-01**: Outbox 워커 분산 락 — 멀티 인스턴스 환경에서 중복 실행 방지

## Out of Scope

| Feature | Reason |
|---------|--------|
| WebSocket / SSE 실시간 푸시 | 폴링으로 충분, 복잡도 대비 효용 낮음 |
| 분산 환경 (멀티 인스턴스) | 싱글 인스턴스 가정 |
| Debezium / CDC | 인프라 복잡도 과다, 단일 인스턴스에 불필요 |
| Spring @Async | 요청 유실 위험, 의도적 제외 |
| 프론트엔드 UI | API + 문서 결과물로 충분 |
| 실결제 연동 | Toss 테스트 키 사용 |
| 인증/인가 | 기존 코드 없음 |

## Traceability

| Requirement | Phase | Status |
|-------------|-------|--------|
| PORT-01 | Phase 1 | Complete |
| PORT-02 | Phase 1 | Complete |
| PORT-03 | Phase 1 | Complete |
| PORT-04 | Phase 1 | Complete |
| STATUS-01 | Phase 1 | Complete |
| STATUS-02 | Phase 1 | Complete |
| STATUS-03 | Phase 1 | Complete |
| SYNC-01 | Phase 2 | Complete |
| SYNC-02 | Phase 2 | Complete |
| SYNC-03 | Phase 2 | Complete |
| OUTBOX-01 | Phase 3 | Complete |
| OUTBOX-02 | Phase 3 | Complete |
| OUTBOX-03 | Phase 3 | Complete |
| OUTBOX-04 | Phase 3 | Complete |
| OUTBOX-05 | Phase 3 | Complete |
| OUTBOX-06 | Phase 3 | Complete |
| KAFKA-01 | Phase 4 | Complete |
| KAFKA-02 | Phase 4 | Complete |
| KAFKA-03 | Phase 4 | Complete |
| KAFKA-04 | Phase 4 | Complete |
| KAFKA-05 | Phase 4 | Complete |
| KAFKA-06 | Phase 4 | Complete |
| KAFKA-07 | Phase 4 | Complete |
| BENCH-01 | Phase 5 | Complete |
| BENCH-02 | Phase 6 | Pending |
| BENCH-03 | Phase 6 | Pending |
| BENCH-04 | Phase 5 | Complete |
| BENCH-05 | Phase 5 | Complete |

**Coverage:**
- v1 requirements: 26 total
- Mapped to phases: 26
- Unmapped: 0 ✓

---
*Requirements defined: 2026-03-14*
*Last updated: 2026-03-18 — OUTBOX-01/02 stale text 수정 (payment_outbox 테이블, executeStockDecreaseWithOutboxCreation 반영)*
