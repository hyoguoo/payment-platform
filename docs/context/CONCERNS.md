# Codebase Concerns

> 최종 갱신: 2026-04-27
> 운영 / 아키텍처 / 신뢰성 우려 인덱스. 새 항목은 우선순위와 함께 추가, 해소된 항목은 `TODOS.md` 또는 archive briefing 으로 이동.

## High — Phase 4 진입 차단 가능성

### C-1. Toxiproxy 장애 주입 검증 부재

- **현황**: 실제 broker / DB / vendor 장애 시 회복성을 단위 테스트는 검증하지만 통합 환경에서 8가지 시나리오(Kafka 지연, DB 지연, 프로세스 kill+재시작, 보상 중복 방지, FCG PG timeout, Redis 다운, 재고 캐시 발산, DLQ 소진) 전수 미검증
- **영향**: 운영 환경 실 장애 시 추측에 의존
- **처방**: Phase 4 — Toxiproxy + k6 부하 + 메트릭 검증

### C-2. CircuitBreaker 미적용 — cross-service HTTP

- **현황**: `ProductHttpAdapter` / `UserHttpAdapter` 가 `try/catch` + 재시도만 적용 — Resilience4j CircuitBreaker 는 Phase 4 에서 도입 예정
- **영향**: product/user 서비스 장애 시 payment-service 가 같이 끌려갈 위험
- **처방**: Phase 4 — CircuitBreaker 적용 + p95 latency 메트릭

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

### L-1. 단일 리전 / 단일 AZ

본 프로젝트는 학습용 — multi-region, geo-redundancy 미구현.

### L-2. 결제 cancel / refund 미구현

`PgGatewayPort.cancel(...)` 인터페이스만 존재. 운영 활용 별도 토픽.

### L-3. EXPIRED 상태의 만료 스케줄러 정책

`PaymentEventStatus.EXPIRED` 가 정의되어 있지만 만료 스케줄러는 PRE-PHASE-4 시점에 도메인 매핑이 일부 제거됨 (`quarantine_compensation_pending` 컬럼은 호환용 유지). 명확한 만료 정책 별도 정리 필요.

### L-4. Two-strategy PG 라우팅 — 결제 건별 `gatewayType` 결정 정책

현재 결제 건별 `gatewayType` 은 client 측에서 결정해 전송. 동적 routing (예: 벤더 장애 시 자동 fallback) 미구현.

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

## 관련

- 학습된 함정: `PITFALLS.md`
- 향후 처리: `TODOS.md`
