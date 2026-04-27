# Codebase Risk / Drift / Dead Code Findings Report

> 작성: 2026-04-27 (CONFIRM-FLOW 통합 후 systematic sweep)
> 임시 파일 — 사용자가 검토 후 처리되면 삭제 가능
>
> 이전 리포트 (CONFIRM-FLOW 통합 시점 8건 drift + 1 의문점) 는 모두 해결됨 (`commit 8ca22e1e` + `commit e0a8d3e6` stock_outbox 폴링 폴백 추가). 본 리포트는 그 이후 추가 sweep 결과.

---

## A. 즉시 검토 가치 — 결제 핵심 로직 영향

### Finding #핵심1: 보상 중복 진입 방어 약점 (handleFailed / handleQuarantined)

- **카테고리**: A (Risk paths) — 결제 정합성
- **severity**: **major** — 재고 발산 가능
- **위치**:
  - `payment-service/.../application/usecase/PaymentConfirmResultUseCase.java:254-261` (handleFailed)
  - `payment-service/.../application/usecase/PaymentConfirmResultUseCase.java:267-277` (handleQuarantined)
  - `payment-service/.../domain/PaymentEvent.java:118-131` (fail() 가드)
  - `payment-service/.../domain/PaymentEvent.java:162-171` (quarantine() 가드)
- **문제**: 두 핸들러가 도메인 가드 (`paymentEvent.fail()` idempotent / `paymentEvent.quarantine()` throw) 와 보상 (`compensateStockCache`) 을 분리해서 호출. 도메인 메서드는 idempotent 보장하지만, 보상은 호출자가 항상 실행 → 두 번 진입 시 redis-stock 두 번 INCR.
- **발산 시나리오**:
  - IN_PROGRESS retry 활성화 후 (commit `e524b514`) 동시 race 시 두 consumer 가 vendor 호출
  - 한 쪽 → events.confirmed_1 발행 (FAILED/QUARANTINED, eventUuid_1)
  - 다른 쪽 → DuplicateApprovalHandler → getStatus → events.confirmed_2 발행 (다른 eventUuid_2)
  - payment-service 둘 다 받음 → markWithLease 둘 다 통과 (다른 키) → handleFailed/Quarantined 두 번 → **보상 두 번 → 재고 발산**
- **markWithLease 보호 한계**: 같은 eventUuid 두 번 처리는 막지만, 다른 eventUuid 로 같은 orderId 의 결과 두 번 발행은 못 막음
- **현 상태**: 새로 발굴 (markWithLease 가 보호한다고 단정한 PITFALLS 와 코드 동작 어긋남)
- **fix 방안**:
  - (a) 도메인 fail/quarantine 이 boolean 반환 — 호출자가 first-transition 만 보상
  - (b) 호출자에 `if (paymentEvent.getStatus().isTerminal()) return;` 가드 추가 — 단순
  - (c) 현행 보존 + 운영 측정 후 결정
- **즉시 처리 권장** — (b) 가 가장 단순한 fix

---

## B. major 운영 영향 — 별도 토픽 격상 가치

### Finding #B1: EXPIRED 상태 만료 정책 미정의

- **위치**: `PaymentEventStatus.EXPIRED`, `PaymentExpirationServiceImpl`, CONCERNS L-3 / TODOS TC-4
- **문제**: 도메인 enum 정의는 있으나 만료 워커 / 타임아웃 / 폴링 응답 정책 부재. 실제 만료 워커가 동작하는지 / 타임아웃이 몇 시간인지 명문화 안 됨
- **영향**: 클라이언트가 PROCESSING 상태에 무한 머물 수 있음, 운영 시 만료 처리 일관성 없음
- **현 상태**: deferred (TODOS TC-4)
- **제안**: 별도 토픽 "Payment expiration policy" 격상

### Finding #B2: DLQ 자동 처리 부재

- **위치**: `payment.commands.confirm.dlq`, `payment.events.confirmed.dlq`, CONCERNS C-5 / TODOS TQ-1
- **문제**: DLQ 두 토픽이 발행되지만 자동 컨슈머 부재 (PaymentConfirmDlqConsumer 는 있으나 PgDlqService 가 QUARANTINED 만 전이). 무한 누적 + 수동 트리아지 부담
- **영향**: 결제 영구 누락 가능, 모니터링 / 재발행 admin 도구 부재
- **현 상태**: deferred (TODOS TQ-1)
- **제안**: TQ-1 의 보강 — DLQ admin endpoint + 자동 재시도 정책 + 모니터링 알림

### Finding #B3: VT explicit bulkhead 부재 — 외부 PG 호출 rate limit 위험

- **위치**: 외부 호출 어댑터 전반, TODOS TC-6
- **문제**: 가상 스레드 무제한 spawn → 벤더 rate limit (예: Toss 초당 100 req) 초과 가능, 다운스트림 다운 시 메모리 압박
- **영향**: 부하 spike 시 OOM 또는 OS 리소스 고갈
- **현 상태**: deferred (TODOS TC-6) — "측정 없이 마법 숫자 박지 않는다"
- **제안**: Phase 4 T4-A (Toxiproxy) + T4-B (k6) 측정 후 Resilience4j Bulkhead 도입

### Finding #B4: Retryable 도메인 예외 ControllerAdvice 미등록

- **위치**: `ProductServiceRetryableException` / `UserServiceRetryableException`, TODOS TC-5
- **문제**: 어댑터에서 throw 하지만 ControllerAdvice 매처 미등록 → 클라이언트엔 500 으로 노출. 재시도 신호 / Retry-After 헤더 손실
- **영향**: 클라이언트 재시도 정책 정합성 깨짐, 모니터링 5xx 잘못 분류
- **현 상태**: deferred (TODOS TC-5)
- **제안**: Phase 4 T4-D 묶음 또는 별도 작업

---

## C. 새로 발굴 (TODO 미등재) — 운영 영향 minor / 청결도

### Finding #C1: pg-service HTTP readTimeout 10s 근거 부재

- **위치**: `pg-service/src/main/resources/application.yml:96-98`
- **문제**: `read-timeout-millis: 10000` 설정 근거 미문서화. payment Feign 5s 와 비대칭
- **영향**: PG 벤더 장애 시 timeout 동작 예측 불가, CircuitBreaker 와의 상호작용 복잡도
- **제안**: T4-D timeout 정밀 튜닝 시 함께 처리 (또는 새 TODO TC-? 등재)

### Finding #C2: Kafka consumer groupId 네이밍 룰 미문서화

- **위치**: ARCHITECTURE 토픽 표
- **문제**: 현재 `pg-service` / `payment-service` / `pg-service-dlq` 명명 사용 중이나 일관 룰 부재
- **영향**: 새 토픽 / consumer 추가 시 네이밍 결정 부담
- **제안**: CONVENTIONS.md 에 한 줄 룰 추가 (e.g. `{service-name}` 또는 `{service-name}-{purpose}`)

### Finding #C3: OutboxWorker batch-size default 비대칭

- **위치**: `OutboxWorker.java:25` (default 10) vs `StockOutboxWorker.java` (default 50). application.yml 양쪽 50 명시
- **문제**: yml 명시 시 동일하지만 default 값 자체가 5배 차이
- **영향**: yml 미명시 시 동작 차이 (테스트 환경 등)
- **제안**: default 통일 — 즉시 fix 가능 (한 줄 변경)

### Finding #C4: Kafka consumer timeout vs @Transactional timeout 정렬 부재

- **위치**: CONVENTIONS L145 (timeout 정책 명시되었으나)
- **문제**: spring-kafka `fetch.poll.timeout` (default 3s) / `@Transactional(timeout=5)` / Kafka broker `max.poll.interval.ms` (5분) 의 관계 미문서화
- **영향**: 처리 시간 3~5s 변동 시 timeout 동작 불예측, consumer rebalance 유발 가능
- **제안**: STACK.md 또는 CONVENTIONS.md 에 "Kafka consumer timeout 정렬" 섹션 추가

### Finding #C5: product/pg dedupe 테이블 cleanup 스케줄러 부재

- **위치**: `stock_commit_dedupe`, `pg_inbox` 테이블
- **문제**: 만료 row 자동 cleanup 스케줄러 없음 → 시간이 지날수록 테이블 누적
- **영향**: 장기 운영 시 쿼리 성능 저하
- **제안**: 별도 cleanup 토픽 또는 Phase 4 측정 후 도입 결정. ARCHITECTURE 의 dedupe 결정 사유 섹션에 이미 명시됨 (재기록 불필요)

---

## D. deferred 재확인 (이미 TODOS 등재됨)

| ID | 주제 | TODO |
|---|---|---|
| Finding #1 (이전 sweep) | HttpAdapter 임시 try/catch | T4-D |
| Finding #2 | Feign timeout baseline | T4-D |
| Finding #4 | 시간 추상화 혼용 | TC-8 |
| Finding #5 | PgInbox/PgOutbox 생성자 패턴 | TC-10 |
| Finding #15 | pg/payment OutboxWorker 복제 | 아키텍처 제약 — necessary |

새 보고는 안 함 (이미 등재).

---

## E. 알려진 보존 결정 (재확인 — fix 예정 없음)

| 항목 | 결정 |
|---|---|
| `PgFinalConfirmationGate` 호출처 0 | 후속 Phase (T4-D 또는 별도) 에서 연결 예정. javadoc 에 명시 |
| `PG_CONFIRM_IN_PROGRESS_NOOP` EventType 미사용 | IN_PROGRESS retry 활성화 후 dead 됐으나 보존 (race no-op 분기 분리 시 재사용 가능) |
| `StockOutbox.incrementAttempt()` 호출 0 | 미러링 패턴 + 미래 retry 정책 자리 — TC-7 deferred |

---

## 처리 우선순위

### 즉시 처리 (단순 fix + 결제 핵심)
- **#핵심1 보상 중복 진입 가드** — 즉시 fix 권장 (방법 B: handleFailed/handleQuarantined 에 isTerminal 가드)
- **#C3 OutboxWorker batch-size default 통일** — 한 줄 변경

### 새 TODO 등재 후 deferred
- **#C1** pg-service timeout SLO 근거
- **#C2** Kafka groupId 네이밍 룰
- **#C4** Kafka consumer timeout 정렬

### 별도 토픽 격상 가치
- **#B1** EXPIRED 만료 정책
- **#B2** DLQ 자동 처리 (TQ-1 보강)
- **#B3** VT bulkhead (Phase 4 측정 후)
- **#B4** Retryable ControllerAdvice (TC-5)

### 검증 결과 (이미 분석 완료)
- 보상 중복 진입 — markWithLease 가 같은 eventUuid 보호하지만 다른 eventUuid (race 후 두 발행) 시 발산 가능. handleFailed / handleQuarantined 가드 필요

---

## 통합 체크리스트

- [ ] #핵심1 보상 중복 진입 fix (즉시)
- [ ] #C3 OutboxWorker default 통일 (즉시)
- [ ] #C1 / #C2 / #C4 새 TODO 등재
- [ ] #B1 / #B2 / #B3 / #B4 별도 토픽 격상 (Phase 4 진입 시 결정)
- [ ] 본 보고서 정리 후 삭제
