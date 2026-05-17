# Codebase Concerns

> 최종 갱신: 2026-05-17 (PAYMENT-EOS-TRANSITION 봉인 — EOS 도입 수용 한계 L1/L2/L3/L5/L6 등재)
> 운영 / 아키텍처 / 신뢰성 우려 인덱스. 새 항목은 우선순위와 함께 추가, 해소된 항목은 `TODOS.md` 또는 archive briefing 으로 이동.

## High — Phase 4 진입 차단 가능성

### C-1. Toxiproxy 장애 주입 검증 부재

- **현황**: 실제 broker / DB / vendor 장애 시 회복성을 단위 테스트는 검증하지만 통합 환경에서 8가지 시나리오(Kafka 지연, DB 지연, 프로세스 kill+재시작, 보상 중복 방지, FCG PG timeout, Redis 다운, 재고 캐시 발산, DLQ 소진) 전수 미검증
- **영향**: 운영 환경 실 장애 시 추측에 의존
- **처방**: Phase 4 — Toxiproxy + k6 부하 + 메트릭 검증

### C-2. CircuitBreaker 미적용 — cross-service HTTP

- **현황**: `ProductHttpAdapter` / `UserHttpAdapter` 가 `feign.RetryableException` 을 catch 해 `*ServiceRetryableException` 으로 변환하는 transport 분기 한 줄만 가짐. 4xx/5xx 매핑은 `ErrorDecoder` 가 담당. 재시도 / CircuitBreaker / fallback 은 미적용
- **영향**: product/user 서비스 장애 시 Feign 호출이 timeout 까지 spawn 누적 → payment-service 가 같이 끌려갈 위험
- **처방**: Phase 4 (T4-D) — Resilience4j CircuitBreaker 적용 + fallbackFactory 마이그레이션 (어댑터 try/catch 제거) + p95 latency 메트릭

### C-3. 로컬 오토스케일러 부재

- **현황**: 부하 시 수동 docker compose scale 만 가능. payment-service 의 큐 길이 / CPU 임계로 자동 scale 하는 메커니즘 없음
- **영향**: 부하 spike 시 응답 시간 발산
- **처방**: Phase 4 — Prometheus 메트릭 기반 로컬 scaler

## Medium — 운영 부담

### C-4. flyway_schema_history 운영 적용 가이드 부재

- **현황**: `baseline-on-migrate` 옵션을 default(false) 로 두고 있어 기존 DB 에 Flyway 도입할 때 수동 baseline 작업 필요
- **영향**: 운영 도입 시 시행착오 가능
- **처방**: STACK.md 운영 가이드 절 + `baseline-on-migrate: true + baseline-version: 0` 옵션 가이드 명시 (이미 본 문서 갱신에 포함)

### C-5. DLQ 소비 자동화 부재

- **현황**: `payment.commands.confirm.dlq`, `payment.events.confirmed.dlq` 가 발행되지만 자동 처리 컨슈머 없음. 수동 검증 후 처리
- **영향**: DLQ 적재가 누적되면 트리아지 부담
- **처방**: 별도 토픽 — DLQ 처리 정책 (수동 admin tool 또는 자동 재시도 정책)

### C-6. 단일 Kafka broker

- **현황**: `kafka:9092` 1대. replication-factor=1
- **영향**: broker 장애 시 메시지 처리 중단
- **처방**: 운영 환경 / Phase 4 부하 테스트 시 multi-broker 검토

### C-7. payment-service 측 application.yml 의 ddl-auto 비명시 시기 (해소됨)

- ~~기존: default profile 에서 `spring.jpa.hibernate.ddl-auto` 미명시 → IDE 로컬 실행 시 빈 DB 부팅 실패 가능~~
- **해소**: 본 봉인 작업의 Flyway 통일 커밋에서 `ddl-auto: validate` 명시. Flyway 가 baseline 자동 적용

## Low — 코드 청결도

### C-8. archive 안의 historical 잔재 참조

- **현황**: archive 안의 여러 plan / context 문서가 옛 클래스 이름 (`OutboxImmediateWorker`, `executePaymentAndStockDecreaseWithOutbox` 등)을 참조
- **영향**: AI 에이전트가 archive 를 읽지 말라는 룰을 어기면 혼동
- **처방**: archive `README.md` 가 명시적으로 "AI 에이전트 미참조" 선언 — 이미 적용. 추가 조치 불필요

### C-9. observability 대시보드 현행화

- **현황**: Grafana 대시보드 정의가 옛 메트릭 이름 일부 사용 가능
- **영향**: Phase 4 진입 시 대시보드 표시 누락
- **처방**: Phase 4 시작 시 대시보드 inventory + 갱신

### C-10. seed 데이터의 운영 안전성

- **현황**: `product/V2__seed_product_stock.sql` 와 `user/V2__seed_user.sql` 가 `INSERT IGNORE` 로 멱등이지만 운영 배포에 같이 적용됨
- **영향**: 운영 환경에 dummy seed 가 들어갈 가능성
- **처방**: 운영 배포 시 `spring.flyway.locations` 에서 seed 디렉토리 분리 또는 `placeholder` 활용. **현재는 데모/스모크 환경 한정으로 OK**

## 알려진 한계 (수용 — 별도 토픽 필요 시 plan)

### L-1. Kafka tx coordinator 의존 — 가용성 약화 (EOS 전환 수용)

- **현황**: EOS (Kafka 트랜잭션) 전환 이후 payment-service 결제 결과 처리가 Kafka tx coordinator 에 의존. broker 가 죽거나 tx coordinator 가 응답 못 하면 `ConfirmedEventConsumer` 처리 자체가 멈춤.
- **이전 모델 대비**: `StockOutbox` 모델에서는 RDB 만 살아있으면 outbox 행이 쌓이고 broker 복구 후 OutboxWorker 가 자동 회수 — 더 높은 가용성.
- **수용 근거**: 학습용 프로젝트 EOS 정합 목표 (D3). 운영 환경에서는 모니터링 대시보드로 coordinator 가용성 가시화 필요 (TC-13-FOLLOW-3).
- **처방 후속**: TC-13-FOLLOW-3 (Kafka tx coordinator 가용성 모니터링 대시보드).

**EOS atomicity 정합 SSOT (RD1-2 명시):**
- `PaymentConfirmResultUseCase.handle` 의 `@Transactional(timeout=5)` 는 qualifier 미명시로 `@Primary JpaTransactionManager` 를 선택한다 — `KafkaTransactionManager(EOS)` 와 별개 TM.
- 이 구조에서 RDB commit 성공 + Kafka producer commit 실패(또는 그 역) 시 at-least-once 재배달이 발생한다.
- **정합성 SSOT 는 EOS atomicity 자체가 아니라 "중복 시 발행 항상 진행 (위키 line 141)" 룰**: 0 row(중복) 시에도 stock-committed 발행을 진행하고, product-service `stock_commit_dedupe` 가 재배달을 흡수한다.
- 즉 EOS 는 "정상 경로 중복 발행 최소화" 최적화이며, crash 내성은 위키 line 141 + product-service dedupe 조합이 담당한다.
- **후속 과제**: TC-13-FOLLOW-1 — `@Transactional` qualifier 명시 또는 `ChainedKafkaTransactionManager` 도입 검토 (CONFIRM-FLOW.md §5 EOS atomicity SSOT 절 참조).

### L-2. `payment_event_dedupe` TTL 정리 스케줄러 부재

- **현황**: `payment_event_dedupe` 테이블에 `expires_at = receivedAt + P8D` 컬럼이 있지만 자동 cleanup 스케줄러 없음. 장기 운영 시 만료 row 누적 → 인덱스 비대 → 쿼리 성능 저하 가능.
- **비교**: product-service `stock_commit_dedupe` / pg-service `pg_inbox` 도 동일 문제 (TC-11).
- **처방 후속**: TC-13-FOLLOW-2 — `payment_event_dedupe` TTL 정리를 TC-11 cleanup 스케줄러 통합 토픽에 묶어 처리.

### L-3. 다중 인스턴스 동시 운영 검증 부재

- **현황**: EOS 전환 후 `transactional.id = ${spring.application.name}-${HOSTNAME:local}` 단일 인스턴스 가정 (D4). 다중 인스턴스 동시 운영 시 transactional.id 충돌 → Kafka producer fencing 동작 불확실.
- **영향**: Phase 5 부하 테스트 시 멀티 인스턴스 확장 전제 시나리오에서 EOS fencing 검증 필요.
- **처방 후속**: TC-13-FOLLOW-1 (multi-instance 확장 시 docker-compose hostname 라인 제거 또는 INSTANCE_ID 환경변수 도입).

### L-4. Two-strategy PG 라우팅 — 결제 건별 `gatewayType` 결정 정책

현재 결제 건별 `gatewayType` 은 client 측에서 결정해 전송. 동적 routing (예: 벤더 장애 시 자동 fallback) 미구현.

### L-5. 회복 비대칭 — EOS abort 시 Redis 보상 lease 미회복

- **현황**: EOS abort 발생 시 RDB rollback + producer tx abort 는 자동 원복. 그러나 `compensateAtomic` (Redis 보상 Lua) 은 EOS tx 밖에서 실행 (Redis 는 XA 참여 불가) — abort 시 Redis 보상이 완료됐지만 RDB rollback 으로 결제 상태는 복귀 → 재배달 시 보상 dedup token `compensation:done:{orderId}` 이 이미 박혀 있어 보상 재실행이 `ALREADY_DONE` 으로 막힘.
- **빈도**: FAILED/QUARANTINED 경로 + EOS abort 가 동시에 발생하는 case 에만 해당. 빈도 낮음.
- **수용 근거**: SCR L7 cascade 평가 결과 수용. Redis 보상 dedup token 은 P8D TTL 로 자연 만료.
- **참고**: 이전 L-6 (보상 끝난 결제 재confirm cascade) 과 관련.

### L-6. EOS multi-instance 확장 시 docker-compose hostname 충돌

- **현황**: `docker/docker-compose.apps.yml` 의 payment-service 컨테이너에 `hostname: payment-service` 라인 존재 시, 다중 인스턴스 배포에서 두 컨테이너가 동일 hostname → transactional.id 충돌 → Kafka producer epoch fencing 불확실.
- **현재**: 단일 인스턴스 운영이라 문제 없음 (D4 단일 인스턴스 가정).
- **트리거 조건**: payment-service 를 2개 이상 컨테이너로 scale-out 할 때.
- **처방 후속**: TC-13-FOLLOW-1 — `hostname:` 라인 제거 또는 `INSTANCE_ID` 환경변수 도입.

### L-7. `markPaymentAsFail` 영구 실패 → Reconciler resetToReady cascade (인지)

`handleFailed` 호출 순서 (보상 → `markPaymentAsFail`) 에서 보상 OK + `markPaymentAsFail` 영구 실패 → DefaultErrorHandler retry 5회 후 DLQ → Reconciler 가 IN_PROGRESS 결제를 resetToReady → 새 confirm 사이클 → 벤더가 재confirm 시 APPROVED 회신 가능 → product RDB 차감 + redis 보상 +1 잔존 → 발산. PG 멱등성 (idempotency-key=orderId) 으로 일반적으로 차단. PHASE2 admin 도구 또는 자동 QUARANTINED fallback 별 토픽 결정.

### L-8. 단일 리전 / 단일 AZ

본 프로젝트는 학습용 — multi-region, geo-redundancy 미구현.

### L-9. 결제 cancel / refund 미구현

`PgGatewayPort.cancel(...)` 인터페이스만 존재. 운영 활용 별도 토픽.

### L-10. EXPIRED 상태의 만료 스케줄러 정책

`PaymentEventStatus.EXPIRED` 가 정의되어 있지만 만료 스케줄러는 PRE-PHASE-4 시점에 도메인 매핑이 일부 제거됨 (`quarantine_compensation_pending` 컬럼은 호환용 유지). 명확한 만료 정책 별도 정리 필요.

### L-11. Redis cluster 환경에서 multi-key Lua 사용 불가

`stock_decrement_atomic.lua` / `stock_compensation_atomic.lua` 가 결제 단위 N개 상품 KEYS 를 한 번에 받는다. Redis cluster 에서는 same hash slot 이어야 하는데 글로벌 상품 키(`stock:{productId}`) 는 결제 단위로 hash tag 묶을 수 없음. **단일 노드 Redis 가정 위에서 성립**, cluster 도입 시 별 토픽.

### L-12. 보상 끝난 결제의 새 confirm 사이클 cascade (인지)

P8D 안에서 동일 orderId 의 `decrement:done` + `compensation:done` 두 dedup token 이 살아있는 상태에서 force resetToReady 등으로 새 confirm 사이클이 진입하면, `decrementAtomic` 이 `ALREADY_DONE → SUCCESS` 매핑되어 redis 재고는 +1 잔존 + 벤더가 APPROVED 회신 시 product RDB 차감 → 발산 가능. 정상 흐름에서는 결제 1건 = orderId 1건이라 발생 가능성 매우 낮음. PHASE2 token DEL 정책 정밀화 또는 admin 도구 (TODOS `STOCK-COMPENSATION-OTHER-PATHS`).

## 회피된 우려 (해소 완료, 기록 보존용)

| 우려 | 해소 위치 |
|---|---|
| ~~Sync/Outbox/Kafka 3전략 분리의 복잡도~~ | `outbox-only-refactor` archive — 단일 비동기 경로 |
| ~~UNKNOWN 상태의 조용한 흡수~~ | `payment-double-fault-recovery` archive — `PaymentGatewayStatusUnmappedException` |
| ~~payment-service Flyway 비대칭~~ | 이번 봉인 — Flyway 통일 |
| ~~AMOUNT_MISMATCH 단방향~~ | PRE-PHASE-4 — pg → payment 양방향 amount 대조 |
| ~~stock publish 가 TX 안에서 Hikari 점유~~ | PRE-PHASE-4 — AFTER_COMMIT 분리 |
| ~~Redis DECR 보상 부재~~ | PRE-PHASE-4 — caller 측 try/catch 보상 |
| ~~payment-history audit 누락 (직접 done() 호출)~~ | PRE-PHASE-4 — `@PublishDomainEvent` AOP 강제 |
| ~~consumer groupId 공유로 토픽 간 백압~~ | PRE-PHASE-4 — groupId 토픽별 분리 |
| ~~outbox immediate worker 의 race~~ | PRE-PHASE-4 — `@RepeatedTest(50)` 검증 |
| ~~문서/스킬에 옛 3전략 어조 잔재~~ | 이번 봉인 + context 갈아엎기 |
| ~~보상 silent loss (compensateStockCache try/catch swallow)~~ | STOCK-COMPENSATION-RECOVERY — Lua atomic + 호출 순서 뒤집기 + DefaultErrorHandler |
| ~~dedupe lease 8일 잠금 + 처리 권한 모호~~ | STOCK-COMPENSATION-RECOVERY — `EventDedupeStore` 폐기, Lua dedup token (orderId 단위) 으로 일원화 |
| ~~PaymentConfirmDlqPublisher 직접 호출~~ | STOCK-COMPENSATION-RECOVERY — Spring Kafka native `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` 위임 |

## 관련

- 학습된 함정: `PITFALLS.md`
- 향후 처리: `TODOS.md`
