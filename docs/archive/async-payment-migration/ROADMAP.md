# Roadmap: Payment Platform — 비동기 결제 처리 마이그레이션

## Overview

기존 동기 confirm 플로우에 `PaymentConfirmAsyncPort` 추상화 계층을 추가하고, Sync / DB Outbox / Kafka 세 가지 전략을 Spring Bean 설정으로 교체 가능하게 구현한다. 포트 계약과 상태 조회 엔드포인트를 먼저 정의해 컴파일 의존성을 확정하고, 가장 단순한 Sync 어댑터로 컨트롤러 배선을 검증한 뒤, Outbox → Kafka 순으로 비동기 복잡도를 높여간다. 마지막으로 k6 부하 테스트로 세 전략의 성능을 정량 비교하는 것으로 포트폴리오 목표를 달성한다.

## Phases

**Phase Numbering:**
- Integer phases (1, 2, 3): Planned milestone work
- Decimal phases (2.1, 2.2): Urgent insertions (marked with INSERTED)

Decimal phases appear between their surrounding integers in numeric order.

- [x] **Phase 1: Port Contract + Status Endpoint** - 모든 어댑터가 의존하는 포트 인터페이스·DTO·상태 조회 API를 정의한다 (completed 2026-03-14)
- [x] **Phase 2: Sync Adapter** - 기존 동기 처리를 포트 구현체로 래핑하고 컨트롤러 배선을 검증한다 (completed 2026-03-15)
- [x] **Phase 3: DB Outbox Adapter** - 재고 감소 후 PENDING 레코드 저장 → 워커 스레드 비동기 처리를 구현한다 (completed 2026-03-15)
- [x] **Phase 4: Kafka Adapter** - Kafka 발행 → 컨슈머 처리 흐름과 DLT를 구현한다 (completed 2026-03-15)
- [x] **Phase 5: k6 Benchmark** - 세 전략을 동일 부하 조건에서 측정하고 성능 비교 문서를 작성한다 (completed 2026-03-16)
- [ ] **Phase 6: k6 JSON Path Fix + Benchmark Execution** - helpers.js JSON 경로 수정 완료(c3149a2); 벤치마크 실제 실행 및 BENCHMARK.md 수치 기입 미완료
- [x] **Phase 7: Orphaned Port Method Cleanup** - PaymentOutboxRepository 포트에서 미사용 existsByOrderId() 메서드를 제거해 포트-구현 계약을 정리한다 (completed 2026-03-17 — a3db8b7)
- [x] **Phase 8: Refactor Confirm Adapters** - KafkaConfirmAdapter/OutboxConfirmAdapter 오케스트레이션 로직을 application 레이어로 이동, KafkaTemplate 포트 추상화 (completed 2026-03-17)

## Phase Details

### Phase 1: Port Contract + Status Endpoint
**Goal**: 세 어댑터 모두의 컴파일 의존성이 되는 포트 인터페이스와 응답 DTO가 확정되고, 비동기 클라이언트가 완료 여부를 확인할 수 있는 상태 조회 엔드포인트가 동작한다
**Depends on**: Nothing (first phase)
**Requirements**: PORT-01, PORT-02, PORT-03, PORT-04, STATUS-01, STATUS-02, STATUS-03
**Success Criteria** (what must be TRUE):
  1. `PaymentConfirmAsyncPort` 인터페이스가 컴파일되고, 세 어댑터 모두 이 인터페이스를 구현하는 형태로 선언될 수 있다
  2. `PaymentConfirmAsyncResult`의 status 필드만으로 컨트롤러가 200/202를 결정할 수 있다 — config 값을 직접 읽지 않는다
  3. `GET /api/v1/payments/{orderId}/status` 호출 시 orderId·status·approvedAt 포함 응답이 반환된다
  4. `spring.payment.async-strategy` 설정값이 없을 때 애플리케이션이 정상 기동되고 sync가 기본으로 동작한다
**Plans**: 3 plans

Plans:
- [x] 01-01-PLAN.md — PaymentConfirmAsyncResult DTO 신규 생성 + PaymentConfirmService 반환 타입 변경 (포트 계약 정의)
- [x] 01-02-PLAN.md — SyncConfirmAdapter 생성 + PaymentConfirmServiceImpl implements 제거 + 컨트롤러 ResponseEntity 분기 (앱 부트 가능)
- [x] 01-03-PLAN.md — Status 엔드포인트 추가 (GET /api/v1/payments/{orderId}/status) + 통합 테스트

### Phase 2: Sync Adapter
**Goal**: 기존 confirm 플로우의 동작이 포트 래핑 후에도 100% 동일하게 유지되어 비동기 어댑터 도입의 회귀 기준선이 확립된다
**Depends on**: Phase 1
**Requirements**: SYNC-01, SYNC-02, SYNC-03
**Success Criteria** (what must be TRUE):
  1. `spring.payment.async-strategy=sync` 설정 시 confirm 요청이 200 OK와 결제 결과를 반환한다
  2. `spring.payment.async-strategy` 미설정 시에도 동일하게 200 OK가 반환된다 (matchIfMissing=true)
  3. 기존 `PaymentConfirmServiceImpl` 내부 코드가 변경되지 않은 상태로 Sync 어댑터가 동작한다
**Plans**: 1 plan

Plans:
- [x] 02-01-PLAN.md — SYNC-01/02/03 공식화: REQUIREMENTS.md 체크마크 업데이트 + 회귀 기준선 테스트 확인

### Phase 3: DB Outbox Adapter
**Goal**: confirm 요청이 재고 감소와 PENDING 레코드 저장을 원자적으로 완료한 뒤 즉시 202를 반환하고, 워커 스레드가 Toss API 호출을 비동기로 완료한다
**Depends on**: Phase 2
**Requirements**: OUTBOX-01, OUTBOX-02, OUTBOX-03, OUTBOX-04, OUTBOX-05, OUTBOX-06
**Success Criteria** (what must be TRUE):
  1. `spring.payment.async-strategy=outbox` 설정 시 confirm 요청이 즉시 202 Accepted + orderId를 반환한다
  2. 202 반환 후 `GET /status` 폴링으로 최종적으로 DONE 상태가 확인된다 (end-to-end 완료)
  3. 워커가 PENDING 레코드를 IN_FLIGHT로 전환한 뒤 처리하므로 동일 레코드가 중복 처리되지 않는다
  4. Toss API 호출이 5회 실패하면 레코드가 FAILED로 전환되고 보상 트랜잭션이 멱등하게 실행된다
**Plans**: 3 plans

Plans:
- [x] 03-01-PLAN.md — PaymentOutbox 도메인 엔티티 + 포트 + JPA 인프라 + OutboxConfirmAdapter (도메인/어댑터 계층)
- [x] 03-02-PLAN.md — PaymentOutboxUseCase + OutboxWorker (3단계 트랜잭션 + 재시도 + 가상 스레드)
- [x] 03-03-PLAN.md — Status 엔드포인트 outbox-first 조회 + PaymentPresentationMapper 확장

### Phase 4: Kafka Adapter
**Goal**: confirm 요청이 재고 감소와 Kafka 발행을 원자적으로 완료하고, 컨슈머가 Toss API 호출을 처리하며, 처리 불가 메시지는 DLT로 라우팅된다
**Depends on**: Phase 3
**Requirements**: KAFKA-01, KAFKA-02, KAFKA-03, KAFKA-04, KAFKA-05, KAFKA-06, KAFKA-07
**Success Criteria** (what must be TRUE):
  1. Docker Compose에서 Kafka(KRaft)가 기동되고 `spring.payment.async-strategy=kafka` 설정 시 confirm 요청이 202를 반환한다
  2. `GET /status` 폴링으로 컨슈머가 처리를 완료한 뒤 DONE 상태가 확인된다
  3. 동일 orderId의 메시지가 재전달되어도 결제가 중복 처리되지 않는다 (idempotency guard)
  4. 최대 재시도 후 처리 실패한 메시지가 `payment-confirm-dlq` 토픽에서 확인된다
  5. Testcontainers Kafka를 이용한 통합 테스트가 통과한다
**Plans**: 4 plans

Plans:
- [x] 04-01-PLAN.md — Docker Compose Kafka(KRaft) + build.gradle 의존성 + application.yml 설정 + Wave 0 테스트 스텁
- [x] 04-02-PLAN.md — KafkaConfirmAdapter + PaymentTransactionCoordinator.executeStockDecreaseOnly()
- [x] 04-03-PLAN.md — KafkaConfirmListener (@RetryableTopic + @DltHandler) + 단위 테스트
- [x] 04-04-PLAN.md — Testcontainers Kafka 통합 테스트 (KafkaConfirmListenerIntegrationTest)

### Phase 5: k6 Benchmark
**Goal**: 세 전략이 동일한 부하 조건에서 측정되고, 각 전략의 TPS·레이턴시·에러율 비교 결과가 BENCHMARK.md에 기록된다
**Depends on**: Phase 4
**Requirements**: BENCH-01, BENCH-02, BENCH-03, BENCH-04, BENCH-05
**Success Criteria** (what must be TRUE):
  1. sync.js / outbox.js / kafka.js 세 k6 스크립트가 각 전략 서버를 대상으로 실행된다
  2. 비동기 스크립트(outbox.js, kafka.js)는 status 폴링 루프를 포함해 결제 완전 완료까지 측정한다
  3. 세 스크립트가 동일한 VU 수와 테스트 데이터 조건으로 실행된다
  4. BENCHMARK.md에 TPS / p50 / p95 / p99 / 에러율 비교 표와 해석이 포함된다
**Plans**: 3 plans

Plans:
- [x] 05-01-PLAN.md — k6 스크립트 3종 (helpers.js + sync.js + outbox.js + kafka.js) + run-benchmark.sh + README.md
- [x] 05-02-PLAN.md — Spring benchmark 프로파일 (FakeTossHttpOperator src/main 이동 + BenchmarkConfig + application-benchmark.yml)
- [x] 05-03-PLAN.md — BENCHMARK.md 템플릿 (비교 표 + 전략별 해석 + 어댑터 선택 가이드)

### Phase 6: k6 JSON Path Fix + Benchmark Execution
**Goal**: helpers.js JSON 경로 3줄 수정으로 벤치마크 스크립트 런타임 오류를 해소하고, 세 전략 실제 측정값을 BENCHMARK.md에 기록한다
**Depends on**: Phase 5
**Requirements**: BENCH-02, BENCH-03
**Gap Closure**: Closes gaps from v1.0 audit
**Success Criteria** (what must be TRUE):
  1. helpers.js의 setup()과 pollStatus()가 ResponseAdvice 래핑 구조(`data.*`)를 올바르게 읽는다
  2. 세 전략 벤치마크가 오류 없이 실행되고 측정값이 BENCHMARK.md에 기록된다
**Plans**: 1 plan

Plans:
- [ ] 06-01-PLAN.md — scheduler.enabled 픽스 + run-benchmark.sh JSON export + 세 전략 벤치마크 실행 + BENCHMARK.md 수치 기입

> **Note:** helpers.js JSON 경로 수정(c3149a2)은 완료. 벤치마크 실제 실행 및 BENCHMARK.md 수치 기입 미완료.

### Phase 7: Orphaned Port Method Cleanup
**Goal**: PaymentOutboxRepository 포트에서 미사용 existsByOrderId() 메서드를 제거해 포트-구현 계약을 정리한다
**Depends on**: Phase 6
**Requirements**: OUTBOX-06 (integration gap 해소)
**Gap Closure**: Closes outbox-existsByOrderId-orphan gap from v1.0 audit
**Success Criteria** (what must be TRUE):
  1. `PaymentOutboxRepository` 포트에 `existsByOrderId` 선언이 없다
  2. `PaymentOutboxRepositoryImpl`과 `JpaPaymentOutboxRepository`에서 관련 코드가 모두 제거된다
  3. `./gradlew test`가 컴파일 오류 없이 통과한다

> **Note:** GSD 워크플로 없이 a3db8b7 커밋으로 직접 완료. PLAN.md/SUMMARY.md/VERIFICATION.md 부재.

### Phase 8: Refactor Confirm Adapters
**Goal**: infrastructure 레이어에서 PaymentConfirmService 구현체가 사라지고, application 레이어의 전략별 서비스 클래스(KafkaAsyncConfirmService, OutboxAsyncConfirmService, PaymentConfirmServiceImpl)가 @ConditionalOnProperty로 직접 Bean 등록된다. KafkaTemplate 의존은 PaymentConfirmPublisherPort 뒤로 추상화된다.
**Depends on**: Phase 7
**Requirements**: 내부 품질 개선 (기존 v1 요구사항에 대한 회귀 방지)
**Success Criteria** (what must be TRUE):
  1. `infrastructure/adapter/` 패키지에 PaymentConfirmService 구현체가 없다
  2. `KafkaAsyncConfirmService`, `OutboxAsyncConfirmService`, `PaymentConfirmServiceImpl`이 application 레이어에 있고 @ConditionalOnProperty로 직접 등록된다
  3. `KafkaTemplate` 직접 의존이 `PaymentConfirmPublisherPort`로 추상화된다
  4. 전체 테스트 통과
**Plans**: 3 plans

Plans:
- [x] 08-01-PLAN.md — Wave 0 테스트 스텁 3종 + PaymentConfirmPublisherPort 인터페이스 + KafkaConfirmPublisher 구현체
- [x] 08-02-PLAN.md — KafkaAsyncConfirmService + OutboxAsyncConfirmService (application 레이어로 오케스트레이션 이동)
- [x] 08-03-PLAN.md — PaymentConfirmServiceImpl 리팩터 (implements PaymentConfirmService) + 어댑터 3종 삭제

## Progress

**Execution Order:**
Phases execute in numeric order: 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. Port Contract + Status Endpoint | 3/3 | Complete | 2026-03-14 |
| 2. Sync Adapter | 1/1 | Complete | 2026-03-15 |
| 3. DB Outbox Adapter | 3/3 | Complete | 2026-03-15 |
| 4. Kafka Adapter | 5/5 | Complete | 2026-03-15 |
| 5. k6 Benchmark | 3/3 | Complete | 2026-03-16 |
| 6. k6 JSON Path Fix + Benchmark | 0/1 | Partial¹ | — |
| 7. Orphaned Port Method Cleanup | 0/1 | Complete² | 2026-03-17 |
| 8. Refactor Confirm Adapters | 3/3 | Complete | 2026-03-17 |

¹ helpers.js JSON 경로 수정(c3149a2) 완료. 벤치마크 실제 실행 및 BENCHMARK.md 수치 기입 미완료. GSD 문서 부재.
² existsByOrderId 제거(a3db8b7) 완료. GSD 문서(PLAN.md 등) 부재 — ad-hoc 커밋으로 처리됨.
