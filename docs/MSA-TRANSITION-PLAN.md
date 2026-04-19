# MSA-TRANSITION-PLAN

**토픽**: [MSA-TRANSITION](topics/MSA-TRANSITION.md)
**날짜**: 2026-04-18
**라운드**: 5 (plan-round 5 Planner 수정 — Redis 캐시 차감 + IdempotencyStore Redis 이관)

---

<!-- ARCH tag 범례: ARCH R<N>: = 해당 라운드 신규 지적 | ARCH R<N> RESOLVED: = 해당 라운드에서 해소 확인 -->

<!-- ARCH R2 RESOLVED: Round 1에서 달린 `<!-- ARCH R1: -->` 주석이 Round 2 재작성에서 삭제됐다. 프로세스 원칙(해소된 주석도 `<!-- ARCH R2 RESOLVED: -->` 접두 마킹으로 유지)을 위반. Round 3에서 범례 규칙을 본 파일 상단에 명시하여 이후 라운드 준수를 확보한다 (F-18 minor 대응). -->

## 요약 브리핑

### 1. Task 목록 (40개, 6 Phase)

**Phase 0 — 인프라 준비 (5)**
1. Phase-0.1: docker-compose 기반 인프라 정의 (Kafka / Redis 공유 + payment 전용 Redis / Eureka / Config Server + 공유 네트워크)
2. Phase-0.1a: IdempotencyStore Caffeine → Redis 이관 (payment-service 전용 Redis, SETNX 동시성 방어) — domain_risk
3. Phase-0.2: Spring Cloud Gateway 서비스 모듈 신설 (WebFlux/Netty, 모놀리스 전체 fallback 라우트)
4. Phase-0.3: W3C Trace Context + LogFmt 공통 기반 (traceparent 헤더 MDC 주입 — 리액티브 엣지 한정)
5. Phase-0.4: Toxiproxy 장애 주입 도구 구성 (Kafka / MySQL proxy 엔드포인트 선언)

**Phase 1 — 결제 코어 분리 (17)**
6. Phase-1.0: 결제 서비스 모듈 경계 정리 (cross-context port 복제 + InternalPaymentGatewayAdapter 이관 + paymentgateway compile 의존 제거 + StockCachePort 선언)
7. Phase-1.1: 결제 서비스 모듈 신설 + port 계층 구성 (모든 포트를 application/port/{in,out}로 일괄 정리 + StockCommitEventPublisher 포트 선언)
8. Phase-1.2: Fake 구현체 신설 (테스트용 PaymentGatewayPort · MessagePublisherPort · IdempotencyStorePort · StockCachePort Fake)
9. Phase-1.3: 도메인 이관 — PaymentEvent · PaymentOutbox · RetryPolicy 상태 전이 테스트 보존
10. Phase-1.4: 트랜잭션 경계 + 감사 원자성 — payment_history BEFORE_COMMIT 리스너 (결제 서비스 내부 TX 원자성에 한정, stock 캐시 차감은 외부 호출 분리)
11. Phase-1.4b: AOP 축 결제 서비스 복제 이관 (@PublishDomainEvent · @PaymentStatusChange · 로깅/메트릭 aspect)
12. Phase-1.4c: 결제 서비스 Flyway V1 스키마 (빈 DB 시작 + 모놀리스 PENDING 미종결 레코드 모놀리스 잔류 처리)
13. Phase-1.4d: StockCachePort + Redis 어댑터 (Lua atomic DECR, DECR 음수→INCR 복구 + FAILED 전이, AOF 지속성) — domain_risk
14. Phase-1.5: PG 가면 응답 방어선 구현 + Toss 전략 wiring 완결 (ALREADY_PROCESSED_PAYMENT 포착 + isSuccess() 수정) — domain_risk
15. Phase-1.5b: StockCommitEventPublisher — 결제 확정 시 payment.events.stock-committed 발행 — domain_risk
16. Phase-1.6: 결제 릴레이 → Kafka publisher 구현 (at-least-once + relay 멱등성)
17. Phase-1.7: FCG 격리 불변 + RecoveryDecision 이관 (timeout → QUARANTINED 무조건, QUARANTINED 결제 Redis DECR 상태 유지)
18. Phase-1.8: Graceful Shutdown + Virtual Threads 재검토
19. Phase-1.9: Reconciliation 루프 + FCG/Reconciler 역할 분리 + Redis ↔ RDB 재고 대조 — domain_risk
20. Phase-1.10: Gateway 결제 엔드포인트 교체 + 모놀리스 결제 경로 비활성화 (@ConditionalOnProperty 기본 false + migrate-pending-outbox.sh)
21. Phase-1.11: payment.outbox.pending_age_seconds 히스토그램 + payment.stock_cache.divergence_count 메트릭 (stock lock-in·캐시 발산 감지)
22. Phase-1.12: 재고 warmup — product.events.stock-snapshot 토픽 재생 (기동 시 Redis 초기화) — domain_risk

**Phase 2 — PG 서비스 분리 (6)**
23. Phase-2.1: PG 서비스 모듈 신설 + port 계층 + 벤더 전략(Toss · NicePay) 이관
24. Phase-2.1b: PG 서비스 AOP 축 복제 이관 (@TossApiMetric · TossApiMetricsAspect)
25. Phase-2.2: Fake PG 서비스 구현
26. Phase-2.3: PgStatusPort Kafka 이벤트 경로 + 이벤트 토픽 명명 + 전 서비스 공통 토픽 네이밍 규약 확정 (PgEventPublisherPort + PgConfirmUseCase)
27. Phase-2.3b: 결제 서비스 측 PgStatusPort·PaymentGatewayPort 구현체 교체 (Local/Internal → HTTP/Kafka) — domain_risk
28. Phase-2.4: Gateway 라우팅 — PG 내부 API 격리

**Phase 3 — 주변 도메인 분리 + 보상 이벤트화 (7)**
29. Phase-3.1: 상품 서비스 모듈 신설 + 도메인 이관 (StockRestoreUseCase implements StockRestoreCommandService 겸임 + stock-snapshot 발행 훅)
30. Phase-3.1b: 사용자 서비스 모듈 신설 + 도메인 이관 + port 계층 + Flyway V1
31. Phase-3.2: Fake 상품 서비스 구현 (FakeStockRepository + FakeEventDedupeStore + FakePaymentRedisStockPort — StockCommit·StockRestore 소비자 공용)
32. Phase-3.1c: StockCommitConsumer + payment-service 전용 Redis 직접 쓰기 (product → payment Redis SET, product RDB UPDATE) — domain_risk
33. Phase-3.3: 보상 이벤트 consumer dedupe — stock.restore UUID 키, 상품 서비스 소유, EventDedupeStore port/JdbcStore 분리 — domain_risk
34. Phase-3.4: 결제 서비스 ProductPort/UserPort → HTTP 어댑터 교체 (InternalAdapter 퇴역)
35. Phase-3.5: Gateway 라우팅 — 상품·사용자 엔드포인트 교체

**Phase 4 — 장애 주입 + 오토스케일러 (3)**
36. Phase-4.1: Toxiproxy 장애 시나리오 스위트 (8종: 브로커 파티션 · DB 지연 · Kafka 지연 · PG timeout · 보상 중복 주입 · FCG PG timeout · Redis down · 재고 캐시 발산)
37. Phase-4.2: k6 시나리오 재설계 (분산 토폴로지 경로별 TPS/레이턴시)
38. Phase-4.3: 로컬 오토스케일러 (Prometheus 지표 감시 + docker-compose scale 스크립트)

**Phase 5 — 잔재 정리 (2)**
39. Phase-5.1: 메트릭 네이밍 규약 공통화 + Admin UI 처리 결정
40. Phase-5.2: LogFmt 공통화 완결 + 최종 문서화 (archive 이동)

### 2. Phase 의존 흐름 + 최종 토폴로지

```mermaid
flowchart TB
    subgraph P0["Phase 0 — 인프라"]
        P01[Phase-0.1<br/>Kafka / Redis 공유 + payment 전용 Redis<br/>keyspace: stock:{id} · idem:{key}]
        P01a[Phase-0.1a<br/>멱등성 Redis 이관<br/>Caffeine → RedisIdempotencyAdapter<br/>SETNX 동시성 방어]
        P02[Phase-0.2<br/>Gateway WebFlux/Netty]
        P03[Phase-0.3<br/>Trace Context / LogFmt]
        P04[Phase-0.4<br/>Toxiproxy]
    end
    subgraph P1["Phase 1 — 결제 코어 분리"]
        P10[Phase-1.0<br/>cross-context port 복제<br/>paymentgateway 경계 단절<br/>StockCachePort 선언]
        P11[Phase-1.1<br/>결제 모듈 + port 일괄<br/>StockCommitEventPublisher 포트]
        P12[Phase-1.2<br/>Fake 신설<br/>FakeStockCachePort 포함]
        P13[Phase-1.3<br/>도메인 이관]
        P14[Phase-1.4 / 1.4b / 1.4c<br/>감사 원자성 + AOP + 빈 DB]
        P14d[Phase-1.4d<br/>재고 캐시 차감<br/>Lua atomic DECR<br/>음수→INCR 복구+FAILED]
        P15[Phase-1.5<br/>PG 가면 방어 / Toss wiring]
        P15b[Phase-1.5b<br/>StockCommitEventPublisher<br/>payment.events.stock-committed 발행]
        P16[Phase-1.6<br/>결제 릴레이 Kafka]
        P17[Phase-1.7<br/>FCG 불변<br/>QUARANTINED DECR 상태 유지]
        P19[Phase-1.9<br/>Reconciler<br/>Redis↔RDB 재고 대조<br/>QUARANTINED INCR 복원]
        P110[Phase-1.10<br/>Gateway 전환 + 모놀리스 결제 비활성화]
        P111[Phase-1.11<br/>pending_age_seconds<br/>stock_cache.divergence_count]
        P112[Phase-1.12<br/>stock-snapshot warmup<br/>기동 시 Redis 초기화]
    end
    subgraph P2["Phase 2 — PG 서비스 분리"]
        P21[Phase-2.1 / 2.1b<br/>PG 모듈 + AOP]
        P23[Phase-2.3<br/>PgStatus Kafka]
        P24[Phase-2.4<br/>Gateway 재라우팅]
    end
    subgraph P3["Phase 3 — 주변 도메인 + 보상 이벤트화"]
        P31[Phase-3.1<br/>상품 모듈<br/>stock-snapshot 발행 훅]
        P31b[Phase-3.1b<br/>사용자 서비스 모듈]
        P32[Phase-3.2<br/>Fake 신설<br/>FakeStockRepository<br/>FakeEventDedupeStore<br/>FakePaymentRedisStockPort]
        P31c[Phase-3.1c<br/>StockCommitConsumer<br/>product RDB UPDATE<br/>payment Redis 직접 SET]
        P33[Phase-3.3<br/>보상 dedupe]
        P34[Phase-3.4<br/>ProductPort HTTP 교체]
        P35[Phase-3.5<br/>Gateway 재라우팅]
    end
    subgraph P4["Phase 4 — 장애 주입 + 오토스케일러"]
        P41[Phase-4.1<br/>8종 chaos 시나리오<br/>Redis down · 재고 캐시 발산 추가]
        P42[Phase-4.2<br/>k6 재설계]
        P43[Phase-4.3<br/>로컬 오토스케일러]
    end
    subgraph P5["Phase 5 — 잔재 정리"]
        P51[Phase-5.1<br/>메트릭 공통화]
        P52[Phase-5.2<br/>LogFmt + 아카이브]
    end

    P0 --> P1 --> P2 --> P3 --> P4 --> P5
    P01 --> P01a
    P10 --> P11 --> P12 --> P13 --> P14 --> P14d --> P15 --> P15b --> P16 --> P17 --> P19 --> P110
    P01a -.멱등성 Redis.-> P11
    P14d -.재고 캐시 차감 포트.-> P15b
    P21 --> P23 --> P24
    P31 --> P31b --> P32 --> P31c --> P33 --> P34 --> P35
    P112 -.warmup.-> P31c
```

```mermaid
flowchart LR
    Client[클라이언트] --> GW[API Gateway<br/>WebFlux/Netty<br/>traceparent 주입]
    GW -->|결제 confirm/cancel| PAY[결제 서비스<br/>MVC+VT<br/>FCG · 릴레이 · 감사]
    GW -->|PG getStatus| PG[PG 서비스<br/>MVC+VT<br/>Toss/NicePay 전략]
    GW -->|상품 조회/차감| PROD[상품 서비스<br/>MVC+VT<br/>보상 consumer dedupe]
    GW -->|사용자 조회| USR[사용자 서비스<br/>MVC+VT]
    GW -->|관리자 페이지| ADMIN[관리자 모놀리스<br/>Thymeleaf 잔존]

    PAY -.PaymentGatewayPort.-> PG
    PAY -->|payment.outbox 릴레이| KAFKA[Kafka 브로커]
    KAFKA -->|stock.restore UUID| PROD
    KAFKA -->|pg.status.changed| PAY
    PAY -->|payment.events.stock-committed| KAFKA
    KAFKA -->|stock-committed consume| PROD

    PAY --> PAYDB[(결제 DB<br/>payment_event<br/>payment_outbox<br/>payment_history)]
    PAY -->|재고 캐시 차감 DECR| PREDIS[(payment 전용 Redis<br/>stock:{productId}<br/>idem:{key}<br/>appendonly yes)]
    PG --> PGDB[(PG DB)]
    PROD --> PRODDB[(상품 DB<br/>event_dedupe)]
    PROD -->|직접 SET stock:{id}| PREDIS
    USR --> USRDB[(사용자 DB)]
    ADMIN --> ADMINDB[(모놀리스 DB<br/>Phase 5 잔재)]

    PAY -.Reconciliation 루프<br/>Redis↔RDB 대조.-> PAYDB
    PAY -.Reconciliation 루프<br/>Redis↔RDB 대조.-> PREDIS
    PAY -.FCG PG 재조회.-> PG
```

### 3. 핵심 결정 → Task 매핑

| 결정 축 | 주 Task |
|---|---|
| 분해 기준(3/4/5서비스 택일) · 런타임 스택(Gateway만 WebFlux) | Phase-0.2, 1.1, 2.1, 3.1 |
| PG 가면 응답 방어선(Toss `ALREADY_PROCESSED_PAYMENT` + NicePay `2201` 금액 대칭) | Phase-1.5 |
| 감사 원자성(payment_history BEFORE_COMMIT + AOP + 결제 서비스 DB 잔류) | Phase-1.4 / 1.4b / 1.4c |
| FCG timeout → QUARANTINED 불변 (QUARANTINED 결제 Redis DECR 상태 유지) | Phase-1.7, Phase-4.1 fcg-pg-timeout.sh |
| 보상 이벤트 consumer dedupe (상품 서비스 소유, UUID 키, TTL 정량화) | Phase-3.3, Phase-4.1 stock-restore-duplicate.sh |
| Transactional Outbox 릴레이 → Kafka at-least-once | Phase-1.6 |
| Strangler Fig 이중 발행 방지 | Phase-1.10 (@ConditionalOnProperty + migrate-pending-outbox.sh) |
| 장애 주입 8종 + 로컬 오토스케일러 | Phase-4.1 / 4.3 |
| 이벤트 스키마 + 토픽 명명 (payment.events.stock-committed 포함) | Phase-2.3, Phase-1.5b |
| 관측성 공통화(W3C Trace · LogFmt) | Phase-0.3, Phase-5.2 |
| **재고 캐시 차감 — ADR-05/ADR-15 연계** (payment 전용 Redis, Lua DECR, Overselling 0 보장, Redis down→QUARANTINED) | Phase-0.1, Phase-1.4d, Phase-1.5b, Phase-1.7, Phase-1.9, Phase-1.12 |
| **멱등성 저장소 Redis 이관 — ADR-16** (Caffeine→Redis, MSA 수평 확장 대응, SETNX 동시성 방어) | Phase-0.1a |
| **상품 서비스 Redis 직접 쓰기 — product→payment Redis 동기화 경로** (Kafka 경유 아님, product 생성·수정·admin 시 직접 SET) | Phase-3.1c |
| **재고 Reconciler 확장 — Redis ↔ RDB 대조** (QUARANTINED INCR 복원, TTL 자동 복원, RDB 진실) | Phase-1.9, Phase-1.12 |

상세 추적 테이블은 아래 "추적 테이블: discuss 리스크 → 태스크 매핑" 참조.

### 4. 트레이드오프 / 후속 작업

- **Strangler Fig 공존 기간**: Phase 1~3 사이 모놀리스와 신규 서비스가 같은 도메인 테이블을 읽/쓰는 구간이 존재. `Phase-1.4c` 빈 DB 시작 + `Phase-1.10` 모놀리스 결제 경로 비활성화(@ConditionalOnProperty 기본 false + `migrate-pending-outbox.sh`)로 이중 발행을 닫는다.
- **AOP 축 복제 선택**: 공통 jar 추출 대신 결제/PG 서비스별 복제. `@PaymentStatusChange`·`@TossApiMetric` 소유권을 각 서비스에 귀속시키되 Phase-5.1에서 메트릭 네이밍만 공통화.
- **보상 이벤트화 시점**: Phase 1 기간 `stock.restore` 보상은 결제 서비스 내부 동기 호출 유지(`InternalProductAdapter` 승계), 이벤트화는 Phase 3과 동시. 이행 구간 이중 복원 방어선 공백 방지.
- **리액티브 경계**: Gateway만 WebFlux/Netty. 내부 서비스는 MVC + Virtual Threads 유지. Reactor 타입은 Gateway 필터 안에서만 허용, `core/common`으로 누출 금지(`TraceIdExtractor`는 순수 Java).
- **DB 분리 수준**: container-per-service — 분산 트랜잭션 원천 배제. 정합성은 Saga + Outbox 릴레이 + consumer dedupe + Reconciliation 4층으로 방어.
- **보안 비목표**: mTLS / PCI / 인증은 본 토픽 scope 아님. 별도 토픽으로 분리.
- **실배포(k8s) 비목표**: docker-compose + 로컬 오토스케일러 수준까지만. k8s 전환은 후속 토픽.

---

## Round 2 변경 요약

1. **orphan port 제거**: `MessageConsumerPort`(방향 부적절 — Kafka listener는 inbound 흐름)와 `ReconciliationPort`(내부 @Scheduled 서비스로 충분)를 Phase-1.1 산출물에서 제거. `FakeReconciliationAdapter`도 Phase-1.2에서 삭제.
2. **AOP 축 복제 태스크 신설**: Phase-1.4b(결제 서비스용 `@PublishDomainEvent`·`@PaymentStatusChange`·`DomainEventLoggingAspect`·`PaymentStatusMetricsAspect` 복제)와 Phase-2.1b(PG 서비스용 `@TossApiMetric`·`TossApiMetricsAspect` 복제)를 별도 태스크로 분리.
3. **레이어 경계 보강**: Phase-1.0(cross-context port 복제 + InternalAdapter 승계 경계 정리), Phase-1.4c(Flyway V1 스키마), Phase-2.3b(`PgEventPublisherPort` + `PgConfirmUseCase`), Phase-3.3 재구성(`StockRestoreUseCase` + `EventDedupeStore` port 분리) 신설.
4. **domain_risk 태스크 보강**: Phase-1.5에 Toss 전략 수정·에러 코드 enum 수정·wiring 검증 테스트 추가. Phase-4.1에 `stock-restore-duplicate.sh`·`fcg-pg-timeout.sh` 시나리오 추가.
5. **배치 경로 일관성**: 메트릭 클래스를 `application/usecase/` 대신 `infrastructure/metrics/`로 재배치(Phase-1.11, Phase-5.1). 포트 위치 일관성(모든 포트를 `application/port/{in,out}`)은 Phase-1.1에서 일괄 정리.

---

## Round 5 변경 요약

**반영 공백**: S-1(재고 캐시 차감 전략) / S-2(StockCommitEvent 발행) / S-3(Reconciler 재고 대조) / S-4(멱등성 저장소 MSA 스케일링)

1. **payment 전용 Redis 인프라 추가** (Phase-0.1 스펙 수정): 공유 Redis와 별개 컨테이너 `redis-payment`. `appendonly yes + appendfsync everysec`. keyspace `stock:{productId}` / `idem:{key}` 분리 설계 명시.

2. **멱등성 저장소 Redis 이관** (Phase-0.1a 신설): `IdempotencyStoreImpl`(Caffeine) → `IdempotencyStoreRedisAdapter`(Redis). SETNX 동시 진입 방어. Phase-4.3 오토스케일러 다중 인스턴스 확장 대응. tdd=true / domain_risk=true.

3. **StockCachePort 레이어 선언** (Phase-1.0 스펙 패치): `decrement / rollback / current` 메서드 포트 선언. "예약/reservation" 용어 배제, "재고 캐시 차감" 기준.

4. **StockCommitEventPublisherPort 선언** (Phase-1.1 스펙 패치): `payment.events.stock-committed` 발행 포트 선언. spring-data-redis 의존성 추가.

5. **FakeStockCachePort + FakeStockCommitEventPublisher 추가** (Phase-1.2 스펙 패치): Redis 없이 application 계층 테스트 가능.

6. **재고 차감 실패 분기 테스트 분할** (Phase-1.4 스펙 패치): 기존 `WhenStockDecreaseFails_ShouldTransitionToQuarantineWithoutOutbox` → (a) `WhenStockCacheDecrementRejected_ShouldTransitionToFailed`(재고 부족→FAILED) + (b) `WhenPgTimeout_ShouldTransitionToQuarantineWithoutOutbox`(PG 장애→QUARANTINED) 분할.

7. **StockCacheRedisAdapter 신설** (Phase-1.4d 신설): Lua atomic DECR, DECR 음수→INCR 복구+false, Redis down 예외 전파. tdd=true / domain_risk=true.

8. **StockCommitEventPublisher 구현** (Phase-1.5b 신설): `payment.events.stock-committed` Kafka 발행 어댑터. tdd=true / domain_risk=true.

9. **FCG QUARANTINED 불변에 Redis DECR 상태 유지 명시** (Phase-1.7 스펙 패치): QUARANTINED 전이 시 즉시 INCR 금지. Reconciler 위임. `WhenQuarantined_ShouldNotRollbackStockCache` 테스트 추가.

10. **Reconciler Redis ↔ RDB 대조 확장** (Phase-1.9 스펙 확장): 대조 알고리즘, QUARANTINED DECR 복원, TTL 자동 복원, divergence_count 메트릭 연계. 3개 테스트 메서드 추가. tdd=true / domain_risk=true.

11. **`payment.stock_cache.divergence_count` 메트릭 추가** (Phase-1.11 스펙 패치): `StockCacheDivergenceMetrics` 클래스 + `StockCacheDivergenceMetricsTest` 추가.

12. **stock-snapshot warmup 신설** (Phase-1.12 신설): `product.events.stock-snapshot` 토픽 replay → Redis 초기화. `StockCacheWarmupService`. tdd=true / domain_risk=true.

13. **Phase-3.1 stock-snapshot 발행 훅 추가** (Phase-3.1 스펙 패치): `StockSnapshotPublisher` (ApplicationReadyEvent 시 전 상품 재고 일괄 발행). Phase-1.12 warmup의 pair.

14. **StockCommitConsumer + Redis 직접 쓰기 신설** (Phase-3.1c 신설): product-service가 `payment.events.stock-committed` consume → RDB UPDATE + payment Redis 직접 SET. `PaymentRedisStockPort`. tdd=true / domain_risk=true.

15. **Phase-4.1 chaos 시나리오 확장** (Phase-4.1 스펙 패치): `redis-down.sh`(Redis 중단→QUARANTINED→복원), `stock-cache-divergence.sh`(캐시 발산→Reconciler 재설정) 추가. 총 8종.

16. **태스크 번호 체계**: 기존 35개 + 신규 5개(Phase-0.1a, Phase-1.4d, Phase-1.5b, Phase-1.12, Phase-3.1c 신설) = **40개 총 태스크**. domain_risk=true **19개**.

---

## 실행 순서 및 PR 전략

각 Phase는 독립적인 PR 단위로 분리된다. 모놀리스는 Phase 5 완료 전까지 공존한다(Strangler Fig).

| Phase | 내용 요약 | 예상 PR 수 | 의존 Phase |
|---|---|---|---|
| Phase 0 | 인프라 준비 (Kafka/Redis/payment 전용 Redis/Gateway + 멱등성 Redis 이관) | 5 PR | — |
| Phase 1 | 결제 코어 분리 + PG 가면 방어 + 감사 원자성 + 재고 캐시 차감 + StockCommit 발행 + Reconciler 확장 | 17 PR | Phase 0 |
| Phase 2 | PG 서비스 분리 (ADR-21 결정 이후) | 6 PR | Phase 1 |
| Phase 3 | 상품·사용자 서비스 분리 + 보상 dedupe + StockCommitConsumer + Redis 직접 쓰기 | 8 PR | Phase 2 |
| Phase 4 | 장애 주입 검증 (8종) + 로컬 오토스케일러 | 3 PR | Phase 3 |
| Phase 5 | Admin UI 처리 + 잔재 정리 + 최종 문서화 | 2 PR | Phase 4 |

**Strangler Fig 원칙**: 각 Phase 사이에 모놀리스가 살아 있다. Gateway가 분리된 서비스와 모놀리스 사이를 라우팅한다. `Phase 1~3` 진행 중 모놀리스의 해당 컨텍스트 엔드포인트는 Gateway 라우팅으로 점진 교체된다.

---

## Phase 0 — 인프라 준비

**목적**: 모놀리스가 그대로 떠 있어도 동작하는 런타임 기반 확보. Kafka/Redis/Gateway/Observability docker-compose 기동.

관련 ADR: ADR-04, ADR-05(방향), ADR-08, ADR-09, ADR-10, ADR-11, ADR-12(방향), ADR-16(방향), ADR-18, ADR-29(도구 결정)

---

### Phase-0.1 — docker-compose 기반 인프라 정의

- **제목**: Kafka + Redis 공유 + payment 전용 Redis + Config Server + Discovery 컨테이너 구성
- **목적**: ADR-10(compose 토폴로지), ADR-11(Spring Cloud 매트릭스) — Kafka 브로커, 공유 Redis(Gateway/Admin 용), **payment-service 전용 Redis**(재고 캐시 차감 + 멱등성 저장소 전용), Config Server, Eureka(잠정) 컨테이너를 단일 `docker-compose.infra.yml`에 정의. 모놀리스는 기존 `docker-compose.yml`에서 기동 유지. Toxiproxy·Kafka·Redis가 모놀리스와 동일 Docker network에 합류하도록 `networks:` 블록 명시.
  - **payment 전용 Redis 설계**: 공유 Redis와 별개 컨테이너. `appendonly yes` + `appendfsync everysec` (AOF 지속성). keyspace: `stock:{productId}` (재고 캐시 차감) / `idem:{key}` (멱등성 저장소). 이 인스턴스는 product-service가 직접 SET하는 경로(Phase-3.1c)와 payment-service warmup 경로(Phase-1.12)의 공통 진입점.
- **tdd**: false
- **domain_risk**: false
- **크기**: ≤ 2h
- **산출물**:
  - `docker-compose.infra.yml` — Kafka(+Zookeeper), 공유 Redis, **payment 전용 Redis**(컨테이너명: `redis-payment`, AOF 설정 포함), Eureka 서버 컨테이너, `networks:` 블록(모놀리스 공유 네트워크 정의 포함)
  - `docker-compose.observability.yml` — Prometheus, Grafana, Tempo, Loki 컨테이너
  - 토픽 이름 상수는 각 서비스의 `domain/messaging/` 값 객체로 관리(Spring 의존 없음). `@Configuration`(NewTopic 빈)은 Phase-1.1/2.1/3.1에서 각 서비스 `infrastructure/config/KafkaTopicConfig.java`로 복제 배치.
  - **DB 경계 방침** (M-4): PG 서비스는 **무상태(DB 없음)** — Toss/NicePay HTTP 호출과 Kafka consume/publish만 수행. `docker-compose.infra.yml`에 PG 전용 MySQL 미포함. 모놀리스 잔류 관리자는 Phase 5 종료 시점까지 **모놀리스 DB**에 대해 **읽기 전용 뷰**만 유지 (결제/상품/사용자 소유권 테이블은 각 서비스 DB로 이동). 관리자 쓰기 경로는 Gateway 경유 HTTP 호출로 각 서비스에 위임 — 방침은 Phase-5.1에서 확정.

<!-- ARCH R5: Phase-0.1에 payment 전용 Redis 컨테이너(공유 Redis와 별개) 추가. S-1(재고 캐시 차감 인프라)·S-4(멱등성 MSA 스케일링) 공백 해소를 위한 전제 인프라. -->

---

### Phase-0.1a — IdempotencyStore Caffeine → Redis 이관 (ADR-16)

- **제목**: payment-service 멱등성 저장소 Redis 어댑터 교체 (Caffeine 인메모리 제거)
- **목적**: ADR-16(Idempotency 분산화) — 현 `IdempotencyStoreImpl`(Caffeine 로컬 캐시)은 Phase-4.3 오토스케일러로 payment-service가 다중 인스턴스 확장될 때 stateful하여 중복 checkout을 허용. MSA 수평 확장 전제(horizontal stateless) 위반. Redis로 교체하여 인스턴스 간 공유 멱등성 보장. keyspace `idem:{key}`. 기존 `IdempotencyProperties`(maximumSize, expireAfterWriteSeconds) 유지 — 저장소만 교체. 동시 miss 진입 방어: Redis `SETNX`(또는 Lua SET NX PX) 사용.
- **tdd**: true
- **domain_risk**: true
- **크기**: ≤ 2h
- **테스트 클래스**: `IdempotencyStoreRedisAdapterTest`
- **테스트 메서드**:
  - `IdempotencyStoreRedisAdapterTest#getOrCreate_WhenKeyAbsent_ShouldInvokeCreatorAndStoreResult` — 첫 요청: creator 1회 호출, Redis에 결과 저장, `IdempotencyResult.miss()` 반환
  - `IdempotencyStoreRedisAdapterTest#getOrCreate_WhenKeyPresent_ShouldReturnCachedResultWithoutCreator` — 동일 key 2회 요청: creator 0회, `IdempotencyResult.hit()` 반환
  - `IdempotencyStoreRedisAdapterTest#getOrCreate_ConcurrentMiss_ShouldInvokeCreatorOnce` — 동일 key 동시 진입(SETNX 경합): creator 1회만 호출 (중복 checkout 방지)
  - `IdempotencyStoreRedisAdapterTest#getOrCreate_ShouldRespectExpireAfterWriteSeconds` — TTL expireAfterWriteSeconds 경과 후 key 재조회 시 miss 처리
- **산출물**:
  - `payment-service/src/main/java/.../payment/infrastructure/idempotency/IdempotencyStoreRedisAdapter.java` — `IdempotencyStore` Redis 구현 (SETNX Lua 스크립트 원자성 보장, keyspace `idem:{key}`)
  - `payment-service/src/main/java/.../payment/infrastructure/idempotency/IdempotencyStoreImpl.java` — **Caffeine 구현 제거** (클래스 삭제 또는 `@Deprecated` + Spring 빈 제거)
  - `payment-service/src/main/resources/application.yml` — payment 전용 Redis 연결 설정 (`spring.data.redis.host: redis-payment`)

<!-- ARCH R5: S-4(멱등성 저장소 MSA 스케일링 공백) 반영. 기존 Caffeine 구현을 교체하여 horizontal stateless 확장 가능. -->

---

### Phase-0.2 — Spring Cloud Gateway 서비스 모듈 생성

- **제목**: API Gateway 모듈 신설 (Spring Cloud Gateway + WebFlux/Netty)
- **목적**: ADR-11 런타임 스택 원칙 — Gateway는 WebFlux(Netty), 내부 서비스는 MVC + Virtual Threads. 리액티브 확산 금지. 모놀리스 전체를 fallback으로 라우팅하는 Gateway를 가장 먼저 기동.
- **tdd**: false
- **domain_risk**: false
- **크기**: ≤ 2h
- **산출물**:
  - `gateway/` 신규 모듈 디렉터리
  - `gateway/src/main/java/.../gateway/GatewayApplication.java`
  - `gateway/src/main/resources/application.yml` — 모놀리스 전체 proxy route 정의 (`lb://monolith` 또는 직접 URL)
  - `gateway/build.gradle` — `spring-cloud-starter-gateway` 의존성, MVC 미포함 확인

---

### Phase-0.3 — W3C Trace Context + 관측성 공통 기반

- **제목**: Micrometer Tracing(OTel bridge) + LogFmt 공통 모듈 추출
- **목적**: ADR-18(W3C Trace Context), ADR-19(LogFmt 공통화) — Gateway 필터에서 `traceparent` 헤더를 MDC에 주입. `LogFmt`·`MaskingPatternLayout` 공통화 방침 확정: ADR-19 대안 **(b) 복제** 채택 — 공통 jar 추출 대신 각 서비스 패키지에 복제 배치(Phase-5.2에서 최종 확인). 이 결정을 `docs/topics/MSA-TRANSITION.md` ADR-19 결론란에 기록.
- **tdd**: false
- **domain_risk**: false
- **크기**: ≤ 2h
- **산출물**:
  - `core/common/log/LogFmt.java` — 현 위치 확인(이미 존재하는 경우 복제 방침 주석 추가)
  - `gateway/src/main/java/.../gateway/filter/TraceContextPropagationFilter.java` — `traceparent` 헤더 → MDC 주입 WebFlux filter(Reactor 타입은 이 파일 안에서만 사용, `core/common`으로 누출 금지)
  - `core/common/tracing/TraceIdExtractor.java` — W3C traceparent 파싱 순수 Java 유틸(Reactor 타입 비포함)
  - `docs/topics/MSA-TRANSITION.md` ADR-19 항목에 "복제(b) 확정" 기록

---

### Phase-0.4 — Toxiproxy 장애 주입 도구 구성

- **제목**: Toxiproxy docker-compose 통합 + 기본 proxy 정의
- **목적**: ADR-29(장애 주입 도구 스택) — Toxiproxy를 docker-compose에 추가하고, Kafka 브로커 및 MySQL(모놀리스 DB)에 대한 proxy 엔드포인트를 미리 정의. 실제 장애 시나리오 실행은 Phase 4에서 수행.
- **tdd**: false
- **domain_risk**: false
- **크기**: ≤ 2h
- **산출물**:
  - `docker-compose.infra.yml`에 `toxiproxy` 서비스 추가
  - `chaos/toxiproxy-config.json` — proxy 정의(kafka-proxy, mysql-proxy 엔드포인트)
  - `chaos/README.md` — 장애 주입 커맨드 메모

---

## Phase 1 — 결제 코어 분리

**목적**: 결제 컨텍스트를 독립 서비스로 뽑고, Gateway가 결제 엔드포인트를 신규 서비스로 라우팅. 모놀리스는 PG·상품·사용자·Admin UI만 보유.

**필수 산출물(discuss 승격)**:
- ADR-05 PG 가면 응답 방어 (Toss `ALREADY_PROCESSED_PAYMENT` / NicePay `2201`)
- ADR-13 감사 원자성 (`payment_history` 결제 서비스 DB 잔류)

관련 ADR: ADR-01, ADR-02, ADR-03, ADR-04, ADR-05, ADR-06, ADR-07, ADR-13, ADR-15, ADR-17, ADR-23, ADR-25, ADR-26

**Phase 1 보상 경로 원칙**: Phase 1에서 상품 서비스는 아직 모놀리스 안에 있다. `stock.restore` 보상은 **결제 서비스 내부 동기 호출 유지**(`InternalProductAdapter` 방식 승계). 이벤트화는 Phase 3(상품 분리)과 동시에 진행한다. 이행 구간 이중 복원 방어선 공백 방지(discuss-domain-2 minor finding 대응).

---

### Phase-1.0 — 결제 서비스 모듈 경계 정리 (cross-context port 복제 + InternalAdapter 승계 + StockCachePort 선언)

- **제목**: 결제 서비스가 사용할 cross-context port 복제본 선언 + InternalXxxAdapter 승계 + StockCachePort 선언
- **목적**: ADR-01, ADR-02 — Phase-1.1 포트 계층 구성 전에 결제 서비스가 모놀리스 `paymentgateway/`·`product/`·`user/` 패키지를 직접 import하는 경계를 차단. 결제 서비스 관점에서 필요한 메서드만 슬라이스한 독립 port 인터페이스를 `application/port/out/`에 선언하고, Phase 1 기간에는 `InternalXxxAdapter`(모놀리스 내부 Java 호출 래핑)로 구현. Phase 3에서 HTTP 어댑터로 교체. **재고 캐시 차감 포트(`StockCachePort`)를 이 단계에서 layer 경계로 선언** — Phase-1.4d Redis 어댑터가 구현, Phase-1.2 Fake가 테스트용 선행 구현.
- **tdd**: false
- **domain_risk**: false
- **크기**: ≤ 2h
- **산출물**:
  - `payment-service/src/main/java/.../payment/application/port/out/ProductLookupPort.java` — 재고 조회·감소 메서드 슬라이스 (현 `product/presentation/port/ProductService` 대응)
  - `payment-service/src/main/java/.../payment/application/port/out/UserLookupPort.java` — 사용자 조회 메서드 슬라이스 (현 `user/presentation/port/UserService` 대응)
  - **`payment-service/src/main/java/.../payment/application/port/out/StockCachePort.java`** — `decrement(productId, qty): boolean` / `rollback(productId, qty): void` / `current(productId): long` 메서드 선언. Redis 캐시 차감 전용 포트 (phase-1.4d 구현, phase-1.2 Fake). "예약/reservation" 용어 금지, "재고 캐시 차감" 기준.
  - `payment-service/src/main/java/.../payment/infrastructure/adapter/internal/InternalProductAdapter.java` — `ProductLookupPort` 구현, 모놀리스 `product/presentation/port` 내부 호출 래핑
  - `payment-service/src/main/java/.../payment/infrastructure/adapter/internal/InternalUserAdapter.java` — `UserLookupPort` 구현
  - `payment-service/build.gradle`에 모놀리스 결제 컨텍스트 외 패키지 의존을 compile scope에서 제거(모놀리스 전체 jar를 runtime에서만 참조하거나 internal 어댑터 직접 포함 방식 선택 명시)
  - `payment-service/src/main/java/.../payment/application/port/out/PaymentGatewayPort.java` — cross-context 복제/승계. **confirm / cancel 경로 전담** (command 경로). Phase-1.1의 `PgStatusPort`는 **getStatus 단일 경로 전담** (FCG 격리용 조회 경로). 두 포트는 역할이 분리되며 상호 포함 관계 없음 — adapter→adapter 위임 금지. application 계층은 필요에 따라 각 포트를 개별 주입한다.
  - `payment-service/src/main/java/.../payment/infrastructure/adapter/internal/InternalPaymentGatewayAdapter.java` — `PaymentGatewayPort` 구현체 이관 경로. 모놀리스 `paymentgateway/presentation/PaymentGatewayInternalReceiver`·`NicepayGatewayInternalReceiver`를 직접 import하던 compile 의존을 이 adapter 경계 안으로 국한하고, Phase-1.0 완료 시점에 `payment-service/build.gradle`에서 모놀리스 `paymentgateway` 패키지 compile 의존을 제거한다 (gradle 양방향 의존 차단)

<!-- ARCH R2 RESOLVED: Phase-1.0 scope에 `paymentgateway` 경계 단절이 빠졌다는 지적(F-15). `PaymentGatewayPort` cross-context 복제, `InternalPaymentGatewayAdapter` 이관 경로, `payment-service/build.gradle` compile 의존 제거 방침을 산출물에 명시하여 해소. -->
<!-- ARCH R5: StockCachePort 선언 추가. S-1(재고 캐시 차감 전략 공백) 반영. layer 의존 순서 준수 — port 선언이 infrastructure(Phase-1.4d) 및 Fake(Phase-1.2) 구현보다 먼저. -->

---

### Phase-1.1 — 결제 서비스 모듈 신설 + port 계층 구성 (포트 경로 일괄 정리 + StockCommitEventPublisher 포트 선언)

- **제목**: 결제 서비스 신규 모듈 + outbound port 인터페이스 선언 + port 경로 `{in,out}` 일괄 정리 + StockCommitEventPublisher 포트
- **목적**: ADR-01(분해 기준), ADR-02(통신 패턴), ADR-11 — 결제 서비스를 독립 Spring Boot 모듈로 생성. port 계층이 먼저 존재해야 domain/application 태스크가 의존할 수 있음. 기존 모놀리스의 `application/port/` 플랫 구조 포트를 `application/port/out/` 하위로 이동 정리(§ 2-6 "모든 신규 포트 `{in,out}` 하위" 원칙). **`StockCommitEventPublisherPort`를 이 단계에서 선언** — 결제 확정 시 `payment.events.stock-committed` 발행 책임 추상화. Phase-1.5b 구현체가 이 포트를 구현.
- **tdd**: false
- **domain_risk**: false
- **크기**: ≤ 2h
- **산출물**:
  - `payment-service/build.gradle` — spring-boot-starter-web, virtual threads 설정, spring-kafka, spring-data-redis
  - `payment-service/src/main/java/.../payment/application/port/out/MessagePublisherPort.java` — `void publish(String topic, String eventId, Object payload)`
  - `payment-service/src/main/java/.../payment/application/port/out/PgStatusPort.java` — PG 상태 조회 포트 (ADR-21 선행). Phase 1 구현체: `infrastructure/adapter/internal/LocalPgStatusAdapter.java`(모놀리스 `paymentgateway` 내부 호출 래핑) — Phase 2에서 HTTP 어댑터로 스왑.
  - **`payment-service/src/main/java/.../payment/application/port/out/StockCommitEventPublisherPort.java`** — `void publish(String productId, int qty, String paymentEventId)` (결제 확정 시 재고 차감 확정 이벤트 발행 포트. Phase-1.5b 구현.)
  - 기존 `payment/application/port/` 하위 인터페이스 승계 (경로를 `application/port/out/`으로 이동): `PaymentEventRepository`, `PaymentOutboxRepository`, `PaymentHistoryRepository`, `PaymentOrderRepository`, `PaymentGatewayPort`, `IdempotencyStore`
  - Phase-1.0에서 선언된 `ProductLookupPort`, `UserLookupPort`, `StockCachePort`는 `out/` 하위에 배치됨(동일 원칙)
  - **제거 항목**: `MessageConsumerPort`(Kafka listener는 inbound 흐름이므로 out port 불필요 — 어댑터 내부 구조로 처리), `ReconciliationPort`(내부 @Scheduled 서비스로 충분, outbound port 불필요)
  - `payment-service/src/main/java/.../payment/infrastructure/adapter/internal/LocalPgStatusAdapter.java` — `PgStatusPort` 구현 (Phase 1 한정)
  - `payment-service/src/main/java/.../payment/infrastructure/config/KafkaTopicConfig.java` — NewTopic 빈 정의 (Phase-0.1 방침: 서비스별 복제, `payment.events.stock-committed` 토픽 포함)

<!-- ARCH R5: StockCommitEventPublisherPort 포트 선언 추가. S-2(StockCommitEvent 발행 공백) 반영. layer 의존 순서 준수 — 포트 선언(1.1) → 구현(1.5b). -->

---

### Phase-1.2 — Fake 구현체 신설 (테스트용)

- **제목**: MessagePublisherPort + PgStatusPort + StockCachePort + StockCommitEventPublisherPort Fake 구현체
- **목적**: ADR-04(메시지 유실 대응), ADR-16 — application 계층 테스트가 Kafka/HTTP/Redis 없이 동작할 수 있도록 Fake 구현체를 소비자(application 태스크) 앞에 배치. **`StockCachePort` Fake는 Phase-1.4d Redis 어댑터보다 먼저** 배치하여 Phase-1.4 트랜잭션 경계 테스트가 Redis 없이 동작 가능.
- **tdd**: false
- **domain_risk**: false
- **크기**: ≤ 2h
- **산출물**:
  - `payment-service/src/test/java/.../mock/FakeMessagePublisher.java` — `MessagePublisherPort` 인메모리 구현 (발행 이력 list 보관)
  - `payment-service/src/test/java/.../mock/FakePgStatusAdapter.java` — `PgStatusPort` 인메모리 구현 (설정 가능한 응답 반환, timeout 예외 주입 가능)
  - **`payment-service/src/test/java/.../mock/FakeStockCachePort.java`** — `StockCachePort` 인메모리 구현. `decrement()`: 현재 재고 지도에서 차감, 음수 시 false 반환. `rollback()`: INCR 복원. `current()`: 현재값 반환. 테스트용 재고 초기화 메서드 제공.
  - **`payment-service/src/test/java/.../mock/FakeStockCommitEventPublisher.java`** — `StockCommitEventPublisherPort` 인메모리 구현 (발행 이력 list 보관)

<!-- ARCH R5: FakeStockCachePort 추가. S-1 재고 캐시 차감 테스트 가능성 확보. -->

---

### Phase-1.3 — 도메인 이관: PaymentEvent·PaymentOutbox·RetryPolicy 승계

- **제목**: 결제 도메인 엔티티 + 릴레이 레코드 이관
- **목적**: ADR-03(일관성 모델), ADR-04, ADR-13 — 기존 `payment/domain/` 하위 `PaymentEvent`, `PaymentOutbox`, `PaymentOrder`, `PaymentHistory`, `RetryPolicy`, `RecoveryDecision`, `PaymentEventStatus` 등을 결제 서비스 도메인 레이어로 이관. Spring 의존 없음 유지.
- **tdd**: true
- **domain_risk**: true
- **크기**: ≤ 2h
- **테스트 클래스**: `PaymentEventTest`, `PaymentOutboxTest`
- **테스트 메서드**:
  - `PaymentEventTest#execute_Success` — `@ParameterizedTest @EnumSource(READY, IN_PROGRESS)` → IN_PROGRESS 전이 성공
  - `PaymentEventTest#execute_ThrowsException_WhenTerminalStatus` — `@ParameterizedTest @EnumSource(DONE, FAILED, CANCELED, EXPIRED)` → `PaymentStatusException`
  - `PaymentEventTest#quarantine_AlwaysSucceeds_FromAnyNonTerminal` — 비종결 상태에서 QUARANTINED 전이
  - `PaymentOutboxTest#toDone_ChangesStatusToProcessed` — PENDING → 완료 전이
  - `PaymentOutboxTest#nextRetryAt_ComputedCorrectly_ForExponentialBackoff` — RetryPolicy 기반 다음 재시도 시각 계산

---

### Phase-1.4 — 트랜잭션 경계 + 감사 원자성 유지 (ADR-13)

- **제목**: PaymentTransactionCoordinator 이관 + payment_history BEFORE_COMMIT 원자성 보존
- **목적**: ADR-13(감사 원자성, 대안 a/a') — `PaymentTransactionCoordinator`, `PaymentHistoryEventListener`(BEFORE_COMMIT)를 결제 서비스 내부로 이관. `payment_history` 테이블이 결제 서비스 DB에 잔류해 상태 전이 TX와 같은 TX 안에서 insert됨을 보장. **Phase-1.4c 분리 이후 재고 캐시 차감은 외부 호출이므로 '단일 TX' 가정은 결제 서비스 DB 내부(payment_event + payment_outbox)에만 적용. 재고 캐시 차감 실패는 TX 경계 바깥이며, 실패 유형에 따라 FAILED(재고 부족) 또는 QUARANTINED(시스템 장애)로 분기. 분리 전 모놀리스의 `stock-- + outbox 단일 TX` 개념은 Phase-1.4c 이후 Saga 보상 경로(Phase-3.3 `stock.restore`)로 대체된다.**
- **tdd**: true
- **domain_risk**: true
- **크기**: ≤ 2h
- **테스트 클래스**: `PaymentTransactionCoordinatorTest`, `PaymentHistoryEventListenerTest`
- **테스트 메서드**:
  - `PaymentTransactionCoordinatorTest#executePaymentConfirm_CommitsPaymentStateAndOutboxInSingleTransaction` — **결제 서비스 내부** TX 원자성 검증: payment_event 상태 전이 + payment_outbox 생성이 단일 TX, 실패 시 롤백 (재고 캐시 차감은 외부 호출로 분리, 단일 TX 가정 대상 아님)
  - `PaymentTransactionCoordinatorTest#executePaymentConfirm_WhenStockCacheDecrementRejected_ShouldTransitionToFailed` — **재고 부족** (DECR 결과 음수 → `StockCachePort.decrement()` false 반환) 시 PaymentEvent FAILED 전이 + outbox 미생성 검증 (사용자 에러, 복원 불필요)
  - `PaymentTransactionCoordinatorTest#executePaymentConfirm_WhenPgTimeout_ShouldTransitionToQuarantineWithoutOutbox` — **PG timeout·Redis down 등 시스템 장애** 시 QUARANTINED 전이 + outbox 미생성 검증 (시스템 장애, Reconciler 복원 대상)
  - `PaymentTransactionCoordinatorTest#executePaymentQuarantine_TransitionsToQuarantined` — quarantine 전이 + PaymentQuarantineMetrics 카운터 +1
  - `PaymentHistoryEventListenerTest#onPaymentStatusChange_InsertsHistoryBeforeCommit` — BEFORE_COMMIT 단계에서 payment_history insert 호출 1회 검증 (`@ExtendWith(MockitoExtension.class)`)

<!-- ARCH R5: S-1 반영 — 기존 `WhenStockDecreaseFails_ShouldTransitionToQuarantineWithoutOutbox` 테스트를 재고 부족(FAILED) vs PG 장애(QUARANTINED) 두 케이스로 분할. "예약/reservation" 용어 배제, "재고 캐시 차감" 사용. -->

---

### Phase-1.4d — StockCachePort Redis 어댑터 (Lua atomic DECR + 실패 분기)

- **제목**: payment-service 재고 캐시 차감 Redis 어댑터 구현 (Lua script atomic DECR)
- **목적**: S-1(재고 캐시 차감 전략) — `StockCachePort` 구현체. payment-service 전용 Redis에서 Lua 스크립트로 `stock:{productId}` 키를 atomic DECR. DECR 결과 음수 → INCR 복구 후 false 반환 (Overselling 엄격 0). Redis down 시 예외 전파 → 상위에서 QUARANTINED 처리. AOF 지속성(Phase-0.1 설정 전제). keyspace `stock:{productId}`.
  - **실패 분기 정책** (layer 외부에서 처리):
    - `decrement()` = false → 호출 측이 PaymentEvent FAILED 전이 (재고 부족, 사용자 에러)
    - `decrement()` 예외(Redis down) → 호출 측이 PaymentEvent QUARANTINED 전이 (시스템 장애, Reconciler 복원)
- **tdd**: true
- **domain_risk**: true
- **크기**: ≤ 2h
- **테스트 클래스**: `StockCacheRedisAdapterTest`
- **테스트 메서드**:
  - `StockCacheRedisAdapterTest#decrement_WhenSufficientStock_ShouldDecrementAndReturnTrue` — 재고 충분: DECR 후 양수 → true 반환, `current()` 검증
  - `StockCacheRedisAdapterTest#decrement_WhenStockWouldGoNegative_ShouldRollbackAndReturnFalse` — DECR 음수 → INCR 복구 + false 반환 (Overselling 방지)
  - `StockCacheRedisAdapterTest#decrement_Concurrent_ShouldBeAtomicAndNeverGoNegative` — 동시 다중 DECR: 재고 0 이하 불가 확인 (Lua atomic 검증)
  - `StockCacheRedisAdapterTest#rollback_ShouldIncrementStock` — `rollback()` 호출 시 `stock:{id}` INCR 검증
  - `StockCacheRedisAdapterTest#decrement_WhenRedisDown_ShouldPropagateException` — Redis 연결 실패 시 예외 전파 (QUARANTINED 처리는 상위 계층 책임)
- **산출물**:
  - `payment-service/src/main/java/.../payment/infrastructure/cache/StockCacheRedisAdapter.java` — `StockCachePort` 구현. Lua 스크립트 원자성 DECR 로직 포함. `infrastructure/cache/` 경로.
  - `payment-service/src/main/resources/lua/stock_decrement.lua` — Lua 스크립트 (DECR → 음수 검사 → INCR 복구 → 결과 반환)

<!-- ARCH R5: S-1 재고 캐시 차감 공백 반영. layer 의존 순서: port(1.0) → Fake(1.2) → infrastructure(1.4d). -->
<!-- ARCH R5: 참고(minor) — `StockCacheRedisAdapterTest`의 `decrement_Concurrent_ShouldBeAtomicAndNeverGoNegative` 테스트는 실제 Lua 원자성 검증을 위해 Testcontainers Redis 또는 embedded Redis가 필요함. infrastructure 테스트 슬라이스(@DataRedisTest 또는 @SpringBootTest 부분 컨텍스트)로 명시적으로 두도록 execute 단계에 주의. 순수 Mockito로는 Lua 원자성을 검증할 수 없음. -->

---

### Phase-1.4b — AOP 축 결제 서비스 복제 이관 (ADR-13 § 2-6)

- **제목**: `@PublishDomainEvent`·`@PaymentStatusChange` + Aspect 결제 서비스 복제
- **목적**: ADR-13(감사 원자성), § 2-6(AOP 복제 원칙) — `PaymentCommandUseCase`의 `@PublishDomainEvent`·`@PaymentStatusChange` 어노테이션이 no-op이 되지 않도록, 현 `core/common/aspect/` 하위 `DomainEventLoggingAspect`·`PaymentStatusMetricsAspect`와 어노테이션 자체를 결제 서비스 패키지로 복제. cross-service 공유 금지 — 각 서비스가 자기 패키지에 소유.
- **tdd**: false
- **domain_risk**: true
- **크기**: ≤ 2h
- **산출물**:
  - `payment-service/src/main/java/.../payment/infrastructure/aspect/DomainEventLoggingAspect.java` — 현 `core/common/aspect/DomainEventLoggingAspect` 복제
  - `payment-service/src/main/java/.../payment/infrastructure/aspect/PaymentStatusMetricsAspect.java` — 현 `core/common/metrics/aspect/PaymentStatusMetricsAspect` 복제
  - `payment-service/src/main/java/.../payment/infrastructure/aspect/annotation/PublishDomainEvent.java` — 어노테이션 복제
  - `payment-service/src/main/java/.../payment/infrastructure/aspect/annotation/PaymentStatusChange.java` — 어노테이션 복제
  - `payment-service/src/main/java/.../payment/application/usecase/PaymentCommandUseCase.java`에 복제된 어노테이션 참조로 교체 확인

---

### Phase-1.4c — 결제 서비스 Flyway V1 스키마 (ADR-13 DB 분리 실체화)

- **제목**: 결제 서비스 DB Flyway V1 마이그레이션 스크립트 작성
- **목적**: ADR-13(payment_history 결제 서비스 DB 잔류), ADR-23(DB 분리) — 결제 서비스가 독립 DB를 소유하는 순간 스키마 소유권이 모놀리스 → 결제 서비스로 이전됨. `payment_event`, `payment_order`, `payment_outbox`, `payment_history` 테이블을 결제 서비스 Flyway V1으로 실체화.
- **tdd**: false
- **domain_risk**: true
- **크기**: ≤ 2h
- **산출물**:
  - `payment-service/src/main/resources/db/migration/V1__payment_schema.sql` — `payment_event`, `payment_order`, `payment_outbox`, `payment_history` 테이블 DDL
  - `payment-service/docker-compose` 항목 — 결제 전용 MySQL 컨테이너 추가
  - **DB 병존 기간 데이터 방침**: 결제 전용 DB는 **빈 상태로 시작**. 모놀리스 DB의 미종결 `payment_outbox`·`payment_event` 레코드는 Phase-1.10 전환 전까지 모놀리스 컨테이너에서만 처리한다. 전환 시점에 모놀리스 DB `payment_outbox` PENDING 레코드 수동 이행 스크립트 `chaos/scripts/migrate-pending-outbox.sh`를 별도 산출물로 제공 (Phase-1.10에 포함) — 이 방침이 "Phase-1.4c 완료"의 객관적 판정 기준이 된다.

<!-- ARCH R2 RESOLVED: Phase-1.4c DB 병존 기간 데이터 소스 공백(F-16). 결제 전용 DB 빈 상태 시작, 모놀리스 미종결 레코드 처리 주체, 수동 이행 스크립트 경로를 산출물에 명시하여 완료 기준 공백 해소. -->

---

### Phase-1.5 — PG 가면 응답 방어선 구현 + Toss 전략 wiring (ADR-05) — domain_risk

- **제목**: Toss `ALREADY_PROCESSED_PAYMENT` / NicePay `2201` 가면 응답 방어 + Toss 전략 wiring 완결
- **목적**: ADR-05(멱등성·중복 처리 + PG 가면 방어) — 이 태스크는 discuss Round 2에서 Phase 1 필수 산출물로 승격됨. PG가 "성공"으로 분류하는 코드 수신 시 DB 재조회 후 DONE이 아니면 QUARANTINED. 금액 일치 검증 순서 포함. **핵심**: 현 `TossPaymentErrorCode.ALREADY_PROCESSED_PAYMENT.isSuccess() == true`가 `ALREADY_PROCESSED_PAYMENT`를 성공으로 조기 매핑하는 경로를 차단하고, Toss 전략에 NicePay `handleDuplicateApprovalCompensation` 대칭 분기를 심어 `PgMaskedSuccessHandler`가 실제로 호출됨을 보장(plan-domain-1 major finding #1 대응).
- **tdd**: true
- **domain_risk**: true
- **크기**: ≤ 2h
- **테스트 클래스**: `PgMaskedSuccessHandlerTest`, `TossPaymentGatewayStrategyWiringTest`
- **테스트 메서드**:
  - `PgMaskedSuccessHandlerTest#handle_Toss_AlreadyProcessed_WhenStatusIsDone_ShouldNoOp` — DB 재조회 후 DONE이면 no-op, `payment.pg.masked_success` 카운터 +1
  - `PgMaskedSuccessHandlerTest#handle_Toss_AlreadyProcessed_WhenStatusIsNotDone_ShouldQuarantine` — DB 재조회 후 DONE 아니면 QUARANTINED
  - `PgMaskedSuccessHandlerTest#handle_Nicepay_2201_VerifiesAmountBeforeDecision` — PG `getStatus` 재조회 + 금액 일치 검증 후 분기 (금액 불일치 → QUARANTINED, 일치+DONE → no-op)
  - `PgMaskedSuccessHandlerTest#handle_Toss_AlreadyProcessed_VerifiesAmountSymmetry` — Toss 경로에서도 PG `getStatus` + 금액 일치 검증 수행 (ADR-05 수락 기준 4번 대칭 보장)
  - `PgMaskedSuccessHandlerTest#handle_WhenPgStatusCallFails_ShouldQuarantine` — PG 재조회 자체 실패 → QUARANTINED
  - `TossPaymentGatewayStrategyWiringTest#confirm_WhenAlreadyProcessedPayment_ShouldInvokePgMaskedSuccessHandler` — Toss confirm 경로가 `ALREADY_PROCESSED_PAYMENT` 수신 시 `PgMaskedSuccessHandler.handle()` 1회 호출 검증 (wiring 검증)
- **산출물**:
  - `payment-service/src/main/java/.../payment/application/usecase/PgMaskedSuccessHandler.java`
  - `payment-service/src/main/java/.../payment/infrastructure/gateway/toss/TossPaymentGatewayStrategy.java` — `ALREADY_PROCESSED_PAYMENT` 포착 분기 추가 (NicePay `handleDuplicateApprovalCompensation` 대칭)
  - `payment-service/src/main/java/.../payment/infrastructure/gateway/toss/TossPaymentErrorCode.java` — `ALREADY_PROCESSED_PAYMENT.isSuccess()` 반환값 수정(가면 응답을 success로 취급하지 않음)

---

### Phase-1.5b — StockCommitEventPublisher 구현 (재고 확정 이벤트 발행)

- **제목**: payment.events.stock-committed Kafka 발행 어댑터 구현 (결제 확정 시 재고 차감 확정 이벤트)
- **목적**: S-2(StockCommitEvent 발행 공백) — 결제가 DONE 상태로 확정될 때 `payment.events.stock-committed` 이벤트를 Kafka로 발행. product-service의 Phase-3.1c `StockCommitConsumer`가 이를 수신하여 product RDB UPDATE. `StockCommitEventPublisherPort`(Phase-1.1 선언) 구현체. 기존 outbox relay 흐름(Phase-1.6)과 연계 — DONE 전이 outbox 엔트리에 stock-committed 이벤트 포함 또는 별도 outbox 엔트리 방식. 토픽 명명 규약(Phase-2.3): `payment.events.stock-committed`.
- **tdd**: true
- **domain_risk**: true
- **크기**: ≤ 2h
- **테스트 클래스**: `StockCommitEventPublisherTest`
- **테스트 메서드**:
  - `StockCommitEventPublisherTest#publish_WhenPaymentConfirmed_ShouldEmitStockCommittedEvent` — 결제 DONE 확정 시 `payment.events.stock-committed` 토픽으로 1회 발행 검증 (FakeMessagePublisher + FakeStockCommitEventPublisher)
  - `StockCommitEventPublisherTest#publish_ShouldIncludeProductIdQtyAndPaymentEventId` — 발행 이벤트에 productId, qty, paymentEventId 필드 포함 검증
  - `StockCommitEventPublisherTest#publish_IsIdempotent_WhenCalledTwice` — 동일 paymentEventId 2회 발행 시 outbox 멱등성 검증 (at-least-once 전제)
- **산출물**:
  - `payment-service/src/main/java/.../payment/infrastructure/messaging/publisher/StockCommitEventKafkaPublisher.java` — `StockCommitEventPublisherPort` Kafka 구현 (`infrastructure/messaging/publisher/` 경로)
  - `payment-service/src/main/java/.../payment/domain/messaging/PaymentTopics.java`에 `STOCK_COMMITTED = "payment.events.stock-committed"` 상수 추가

<!-- ARCH R5: S-2(StockCommitEvent 발행 공백) 반영. ADR-12 토픽 네이밍 규약 준수. -->

---

### Phase-1.6 — Transactional Outbox relay → Kafka publisher 구현 (ADR-04)

- **제목**: MessagePublisherPort Kafka 구현 어댑터 + OutboxRelayService
- **목적**: ADR-04(Transactional Outbox DB-first) — PENDING outbox 레코드를 Kafka로 발행하는 infrastructure 어댑터. `MessagePublisherPort`를 구현하는 `KafkaMessagePublisher`와 릴레이 루프 서비스. 어댑터 경로는 `infrastructure/messaging/publisher/`(publisher 서브디렉토리)로 배치 — consumer(`infrastructure/messaging/consumer/`)와 대칭.
- **tdd**: true
- **domain_risk**: true
- **크기**: ≤ 2h
- **테스트 클래스**: `OutboxRelayServiceTest`
- **테스트 메서드**:
  - `OutboxRelayServiceTest#relay_PublishesAllPendingOutbox_ThenMarksDone` — FakeMessagePublisher + FakeOutboxRepository로 PENDING → PROCESSED 전이 검증
  - `OutboxRelayServiceTest#relay_WhenPublishFails_DoesNotMarkDone_LeavesForRetry` — 발행 실패 시 레코드 상태 변경 없음(at-least-once 보장)
  - `OutboxRelayServiceTest#relay_IsIdempotent_WhenCalledTwice` — 동일 outbox 2회 처리 → 발행 1회만
- **산출물**:
  - `payment-service/src/main/java/.../payment/infrastructure/messaging/publisher/KafkaMessagePublisher.java` — `MessagePublisherPort` Kafka 구현
  - `payment-service/src/main/java/.../payment/application/service/OutboxRelayService.java`

---

### Phase-1.7 — FCG 격리 불변 + RecoveryDecision 이관 (ADR-15)

- **제목**: FCG timeout → 무조건 QUARANTINED 불변 보장 + QUARANTINED 결제 Redis 재고 차감 상태 유지 명시
- **목적**: ADR-15(FCG 격리 불변) — `OutboxProcessingService`의 FCG 경로에서 PG 서비스 호출 timeout·네트워크 에러·5xx 발생 시 재시도 래핑 없이 무조건 QUARANTINED 전이. `RecoveryDecision` 이관 포함. **QUARANTINED 전이 시 Redis stock cache DECR 상태를 즉시 INCR 복구하지 않는다** — Reconciler(Phase-1.9)가 QUARANTINED 결제를 감지하여 RDB 기준으로 Redis를 재설정하거나 INCR 복원하는 책임을 가진다. QUARANTINED 결제의 DECR은 Reconciler 처리 전까지 Redis에 유지됨을 불변으로 명시.
- **tdd**: true
- **domain_risk**: true
- **크기**: ≤ 2h
- **테스트 클래스**: `OutboxProcessingServiceTest`
- **테스트 메서드**:
  - `OutboxProcessingServiceTest#process_WhenFcgPgCallTimesOut_ShouldQuarantine` — FakePgStatusAdapter가 timeout 예외 → QUARANTINED 전이, 재시도 없음
  - `OutboxProcessingServiceTest#process_WhenFcgPgReturns5xx_ShouldQuarantine` — 5xx 응답 → QUARANTINED
  - `OutboxProcessingServiceTest#process_WhenFcgSucceeds_ShouldTransitionToDone` — PG DONE 반환 → executePaymentSuccessCompletion 호출
  - `OutboxProcessingServiceTest#process_RetryExhausted_CallsFcgOnce` — retryCount=maxRetries 소진 시 FCG 1회만 호출 (재귀 금지)
  - `OutboxProcessingServiceTest#process_WhenQuarantined_ShouldNotRollbackStockCache` — QUARANTINED 전이 시 `FakeStockCachePort.rollback()` 호출 없음 검증 (DECR 상태 Reconciler 위임)
- **관련 파일**: 기존 `src/main/java/com/hyoguoo/paymentplatform/payment/scheduler/OutboxProcessingService.java` 이관

<!-- ARCH R5: S-1 반영 — QUARANTINED 결제의 Redis DECR 상태 유지 불변 명시. Phase-1.9 Reconciler가 INCR 복원 책임. -->

---

### Phase-1.8 — Graceful Shutdown + Virtual Threads 재검토 (ADR-25, ADR-26)

- **제목**: SmartLifecycle drain + VT 설정 결제 서비스 이관
- **목적**: ADR-25(Graceful Shutdown), ADR-26(VT vs PT) — SIGTERM 시 in-flight outbox 처리 중인 워커를 안전하게 drain. 기존 `OutboxImmediateWorker`의 `SmartLifecycle` 패턴 결제 서비스로 이관. VT/PT 설정(`outbox.channel.virtual-threads`) 유지.
- **tdd**: true
- **domain_risk**: false
- **크기**: ≤ 2h
- **테스트 클래스**: `OutboxImmediateWorkerTest`
- **테스트 메서드**:
  - `OutboxImmediateWorkerTest#stop_DrainsInFlightBeforeShutdown` — `SmartLifecycle.stop()` 호출 시 진행 중 태스크 완료 후 종료
  - `OutboxImmediateWorkerTest#start_SpawnsConfiguredNumberOfWorkers` — `outbox.channel.virtual-threads` 설정값에 따른 워커 수 생성
- **관련 파일**: 기존 `src/main/java/com/hyoguoo/paymentplatform/payment/scheduler/OutboxImmediateWorker.java` 이관

---

### Phase-1.9 — Reconciliation 루프 + FCG vs Reconciler 역할 + Redis ↔ RDB 재고 대조 (ADR-07, ADR-17)

- **제목**: 결제 서비스 로컬 Reconciler — 미종결 레코드 주기 스캔 + Redis 재고 캐시 vs RDB 대조 + QUARANTINED DECR 복원
- **목적**: ADR-07(Reconciliation), ADR-17(FCG vs Reconciler 역할 재정의) — FCG=즉시 경로, Reconciler=지연 경로(분/시간 단위 배치 스캔). 기존 `OutboxWorker`의 폴백 스캔을 Reconciler로 역할 명확화. `PaymentReconciler`는 application/service에 위치하는 내부 서비스(@Scheduled 직접 보유) — `ReconciliationPort` outbound 불필요(Phase-1.1에서 제거됨).
  **Redis ↔ RDB 재고 대조 알고리즘** (S-3 Reconciler 확장):
  - 주기 스캔 시 `StockCachePort.current(productId)` vs (product-service RDB 재고 − PENDING/QUARANTINED PaymentEvent 합계) 대조.
  - 발산 감지 시 **RDB를 진실**로 Redis `stock:{productId}` 재설정(SET). `payment.stock_cache.divergence_count` 카운터 +1.
  - QUARANTINED 결제 감지 시 해당 결제의 DECR 수량만큼 INCR 복원 (Reconciler 단독 책임, FCG 경로에서 즉시 복원 금지 — Phase-1.7 불변 준수).
  - TTL 기반 DECR 자동 복원: Redis key TTL 만료 후 재조회 시 miss → RDB 기준 재설정.
- **tdd**: true
- **domain_risk**: true
- **크기**: ≤ 2h
- **테스트 클래스**: `PaymentReconcilerTest`
- **테스트 메서드**:
  - `PaymentReconcilerTest#scan_FindsStaleInFlightRecords_AndResetsToRetry` — IN_FLIGHT + timeout 초과 레코드 → PENDING 복원
  - `PaymentReconcilerTest#scan_FindsPendingRecords_BypassedChannel_AndProcesses` — 채널 오버플로우로 누락된 PENDING 레코드 배치 처리
  - `PaymentReconcilerTest#scan_DoesNotTouchTerminalRecords` — DONE/FAILED/QUARANTINED 레코드 불간섭
  - `PaymentReconcilerTest#scan_WhenStockCacheDivergesFromRdb_ShouldResetCacheToRdbValue` — Redis 재고값이 RDB 기준(재고 − PENDING/QUARANTINED 합계)과 다를 때 Redis 재설정 + divergence_count +1 검증 (FakeStockCachePort 사용)
  - `PaymentReconcilerTest#scan_WhenQuarantinedPaymentExists_ShouldRollbackDecrForEach` — QUARANTINED PaymentEvent 감지 → 해당 수량 `StockCachePort.rollback()` 호출 검증 (Phase-1.7 불변: FCG에서 즉시 복원 금지 확인)
  - `PaymentReconcilerTest#scan_WhenStockCacheKeyMissing_ShouldRestoreFromRdb` — Redis key TTL 만료 miss → `StockCachePort`에 RDB 기준값 SET 검증
- **산출물**:
  - `payment-service/src/main/java/.../payment/application/service/PaymentReconciler.java` — Redis ↔ RDB 대조 로직 + QUARANTINED INCR 복원 포함

<!-- ARCH R5: S-3(Reconciler 확장 공백) 반영. 사용자 명시 설계 결정 반영: RDB를 진실로 Redis 재설정, QUARANTINED DECR은 Reconciler 단독 복원, TTL 기반 자동 복원. -->

---

### Phase-1.10 — Gateway 라우팅: 결제 엔드포인트 교체 + 모놀리스 결제 경로 비활성화

- **제목**: Gateway route — 결제 서비스로 라우팅 + 모놀리스 결제 confirm 경로 비활성화
- **목적**: ADR-02(통신 패턴), ADR-01 — 결제 서비스 분리 완료 후 Gateway가 `/api/v1/payments/**`를 신규 결제 서비스로, 나머지를 모놀리스로 라우팅. 모놀리스 내부의 결제 confirm 경로(`OutboxImmediateEventHandler`·`PaymentCommandUseCase` 직접 호출)를 `@ConditionalOnProperty` 또는 Spring profile 분기로 비활성화하여 Strangler Fig 기간 이중 발행 경로 차단(plan-domain-1 minor finding #4 대응).
- **tdd**: false
- **domain_risk**: true
- **크기**: ≤ 2h
- **산출물**:
  - `gateway/src/main/resources/application.yml` 라우트 추가 — `payment-service` route (`/api/v1/payments/**`)
  - `gateway/src/main/resources/application.yml` 라우트 — monolith fallback route
  - 모놀리스 `payment/listener/OutboxImmediateEventHandler.java` — `@ConditionalOnProperty("payment.monolith.confirm.enabled", havingValue="true", matchIfMissing=false)` 추가 (Gateway 라우팅 전환 후 기본값=비활성화)
  - 모놀리스 `payment/presentation/PaymentController.java` confirm 엔드포인트 — `@ConditionalOnProperty` 동일 처리 또는 HTTP 501 응답 라우팅
  - `chaos/scripts/migrate-pending-outbox.sh` — 모놀리스 DB `payment_outbox` PENDING 레코드를 결제 서비스 DB로 수동 이행하는 스크립트 (Phase-1.4c 방침 대응 산출물)

---

### Phase-1.11 — `payment.outbox.pending_age_seconds` 히스토그램 + `payment.stock_cache.divergence_count` (ADR-20)

- **제목**: PENDING 레코드 체류 시간 histogram + 재고 캐시 발산 카운터 메트릭 추가
- **목적**: ADR-20(메트릭 네이밍 + stock lock-in 감지) — 수락 기준(채택과 무관): PENDING outbox 레코드의 생성 시각 대비 체류 시간을 `payment.outbox.pending_age_seconds` histogram으로 기록. Phase-4.1 `kafka-latency.sh` 수락 기준: histogram p95가 임계값(10s) 이상 Prometheus 쿼리로 관측됨(plan-domain-1 minor finding #3 반영). **`payment.stock_cache.divergence_count` 카운터 추가**: Reconciler(Phase-1.9)가 Redis ↔ RDB 재고 발산 감지 시 이 카운터를 증가. Prometheus alert 연계.
- **tdd**: true
- **domain_risk**: true
- **크기**: ≤ 2h
- **테스트 클래스**: `OutboxPendingAgeMetricsTest`, `StockCacheDivergenceMetricsTest`
- **테스트 메서드**:
  - `OutboxPendingAgeMetricsTest#record_ShouldEmitHistogramForEachPendingRecord` — PENDING 레코드 체류 시간이 histogram에 기록됨 (MeterRegistry 직접 검증)
  - `OutboxPendingAgeMetricsTest#record_ZeroPendingRecords_ShouldNotRecord` — PENDING 레코드 없으면 histogram 기록 없음
  - `StockCacheDivergenceMetricsTest#increment_ShouldIncreaseDivergenceCounter` — Reconciler 호출 시 divergence_count 카운터 +1 검증
  - `StockCacheDivergenceMetricsTest#noDivergence_ShouldNotIncrementCounter` — 발산 없을 때 카운터 변화 없음 검증
- **산출물**:
  - `payment-service/src/main/java/.../payment/infrastructure/metrics/OutboxPendingAgeMetrics.java` — `infrastructure/metrics/` 배치(application/usecase 아님, ARCHITECTURE.md 관례 준수)
  - `payment-service/src/main/java/.../payment/infrastructure/metrics/StockCacheDivergenceMetrics.java` — `payment.stock_cache.divergence_count` counter (`infrastructure/metrics/` 배치)

<!-- ARCH R5: S-1 반영 — 재고 캐시 발산 감지 메트릭 추가. Phase-1.9 Reconciler 발산 감지와 연계. -->

---

### Phase-1.12 — 재고 캐시 warmup (product.events.stock-snapshot 토픽 재생)

- **제목**: payment-service 기동 시 Redis stock cache 초기화 — stock-snapshot 토픽 재생
- **목적**: S-3(Reconciler 확장) + Phase-1.9 Reconciler 전제 — payment-service 기동(또는 Redis 재시작) 후 `stock:{productId}` 캐시가 비어 있을 때 product-service가 발행하는 `product.events.stock-snapshot` 토픽을 replay하여 Redis를 초기화. warmup 완료 전까지 결제 차감 요청은 차단(또는 RDB fallback). product-service의 snapshot 발행 훅은 Phase-3.1 산출물.
- **tdd**: true
- **domain_risk**: true
- **크기**: ≤ 2h
- **테스트 클래스**: `StockCacheWarmupServiceTest`
- **테스트 메서드**:
  - `StockCacheWarmupServiceTest#onApplicationReady_ShouldPopulateCacheFromSnapshotTopic` — ApplicationReadyEvent 수신 시 snapshot 토픽의 productId·qty 항목을 `StockCachePort`에 SET 검증 (FakeStockCachePort 사용)
  - `StockCacheWarmupServiceTest#warmup_WhenTopicEmpty_ShouldLeaveEmptyCacheAndLog` — snapshot 토픽 비어 있으면 캐시 미설정 + 경고 로그 검증
  - `StockCacheWarmupServiceTest#warmup_DuplicateSnapshot_ShouldUseLatestValue` — 동일 productId 스냅샷 복수 → 최신값으로 덮어쓰기 검증
- **산출물**:
  - `payment-service/src/main/java/.../payment/infrastructure/cache/StockCacheWarmupService.java` — `ApplicationListener<ApplicationReadyEvent>` 구현. `StockCachePort.set(productId, qty)` 호출로 초기 재고값 주입. `infrastructure/cache/` 경로.
  - `payment-service/src/main/java/.../payment/application/port/out/StockCachePort.java`에 `set(productId, qty): void` 메서드 추가 (warmup 전용, 덮어쓰기)

<!-- ARCH R5: S-3 반영 — Reconciler + warmup 연계로 Redis 재시작 후 정합성 복원 경로 확보. Phase-3.1 snapshot 발행 훅과 pair. -->
<!-- ARCH R5: 참고(minor) — `StockCacheWarmupService`를 `infrastructure/cache/`에 배치했으나 Kafka 토픽 소비 책임도 겸함. 엄밀히 분리하면 (a) `infrastructure/messaging/consumer/StockSnapshotReplayConsumer`(Kafka 소비) + (b) `application/service/StockCacheWarmupService`(포트 호출 orchestration) 두 컴포넌트로 쪼개는 것이 layer 책임 분리에 더 부합. 다만 lifecycle bootstrap 성격상 infrastructure 단일 묶음이 실용적이므로 단일 컴포넌트 유지도 수용 — execute 단계 구현자가 두 책임을 private 메서드 경계로라도 분리하도록 권고. `StockCachePort.set()` 추가(warmup 전용)는 decrement/rollback/current와 대칭 세트로 수용 가능. -->

---

## Phase 2 — PG 서비스 분리

**목적**: `paymentgateway` 컨텍스트를 물리 분리(ADR-21 선택 시). PG 서비스 `getStatus`가 raw state만 반환하고 재시도 래핑을 내장하지 않음.

관련 ADR: ADR-21, ADR-04(재확정), ADR-14, ADR-20

---

### Phase-2.1 — PG 서비스 모듈 신설 + port 계층 + 벤더 전략 이관 (ADR-21)

- **제목**: PG 서비스 신규 모듈 + `getStatus` API port 선언 + Toss/NicePay 전략 이관
- **목적**: ADR-21(PG 물리 분리, 대안 a) — PG 서비스를 독립 Spring Boot 모듈로 생성. `getStatus` API는 raw state만 반환 (`DONE`/`IN_PROGRESS`/`FAILED`/`NOT_FOUND`/`DUPLICATE_ATTEMPT`). 재시도 래핑 미포함. Toss/NicePay 벤더 전략 어댑터를 모놀리스에서 PG 서비스로 이관. 가면 응답(`ALREADY_PROCESSED_PAYMENT`/`2201`) → `DUPLICATE_ATTEMPT` 매핑 책임은 application 서비스(`PgStatusServiceImpl`)에 귀속 — Controller/Adapter는 DTO 운반만.
- **tdd**: false
- **domain_risk**: false
- **크기**: ≤ 2h
- **산출물**:
  - `pg-service/build.gradle` — spring-boot-starter-web, virtual threads, spring-kafka
  - `pg-service/src/main/java/.../pg/application/port/out/PgGatewayPort.java` — Toss/NicePay 원문 상태 조회 포트
  - `pg-service/src/main/java/.../pg/application/port/out/PgEventPublisherPort.java` — PG 결과 이벤트 발행 추상화 (Phase-2.3의 `PgEventPublisher`가 구현)
  - `pg-service/src/main/java/.../pg/presentation/port/PgConfirmCommandService.java` — inbound port (`PgConfirmConsumer`가 호출)
  - `pg-service/src/main/java/.../pg/presentation/port/PgStatusService.java` — inbound port
  - `pg-service/src/main/java/.../pg/presentation/PgStatusController.java` — `GET /internal/pg/status/{orderId}` (raw state 반환, MVC)
  - `pg-service/src/main/java/.../pg/infrastructure/gateway/toss/TossPaymentGatewayStrategy.java` — 모놀리스에서 이관
  - `pg-service/src/main/java/.../pg/infrastructure/gateway/nicepay/NicepayPaymentGatewayStrategy.java` — 모놀리스에서 이관
  - `pg-service/src/main/java/.../pg/infrastructure/config/KafkaTopicConfig.java` — NewTopic 빈 복제 배치

---

### Phase-2.1b — AOP 축 PG 서비스 복제 이관 (§ 2-6)

- **제목**: `@TossApiMetric` + `TossApiMetricsAspect` PG 서비스 복제
- **목적**: § 2-6(AOP 복제 원칙) — PG 서비스가 Toss/NicePay API 호출 메트릭(`toss.api.*`)을 기록하려면 `TossApiMetricsAspect`와 `@TossApiMetric` 어노테이션이 PG 서비스 패키지 안에 있어야 함. 공통 jar 공유 금지 — 서비스 소유 복제.
- **tdd**: false
- **domain_risk**: false
- **크기**: ≤ 2h
- **산출물**:
  - `pg-service/src/main/java/.../pg/infrastructure/aspect/TossApiMetricsAspect.java` — 현 `core/common/metrics/aspect/TossApiMetricsAspect` 복제
  - `pg-service/src/main/java/.../pg/infrastructure/aspect/annotation/TossApiMetric.java` — 어노테이션 복제
  - (NicePay 대응 AOP가 현 코드베이스에 존재하는 경우 동일 패턴으로 추가)

---

### Phase-2.2 — Fake PG 서비스 구현 (테스트용)

- **제목**: FakePgGatewayAdapter + FakePgStatusService
- **목적**: ADR-21 수락 기준 — application 계층이 실제 Toss/NicePay 없이 테스트 가능하도록 Fake 배치.
- **tdd**: false
- **domain_risk**: false
- **크기**: ≤ 2h
- **산출물**:
  - `pg-service/src/test/java/.../mock/FakePgGatewayAdapter.java` — `PgGatewayPort` 인메모리 구현
  - `pg-service/src/test/java/.../mock/FakePgStatusService.java` — 설정 가능한 응답 반환

---

### Phase-2.3 — PgStatusPort Kafka 이벤트 경로 + 이벤트 토픽 명명 + 전 서비스 공통 토픽 네이밍 규약 확정 (ADR-14, ADR-12)

- **제목**: confirm 요청 → Kafka 이벤트화 + 토픽 네임스페이스 정의 + 전 서비스 공통 토픽 네이밍 규약 확정
- **목적**: ADR-14(이벤트 vs 커맨드 구분), ADR-12(이벤트 스키마 + 토픽 네이밍) — `payment.commands.confirm` / `payment.events.confirmed` / `payment.events.failed` 토픽 정의. 결제 서비스 → PG 서비스 방향 Kafka command, 역방향 event. **본 태스크에서 전 서비스 공통 토픽 네이밍 규약 `<source-service>.<type>.<action>` (예: `payment.commands.confirm`, `payment.events.confirmed`, `payment.events.failed`, `product.events.stock-restored`, `pg.events.status-changed`)을 확정하고, 신규 토픽 추가 시 이 규약을 따르는 것을 의무화한다. 규약은 `docs/topics/MSA-TRANSITION.md` ADR-12 결론란에 기재한다.**
- **tdd**: true
- **domain_risk**: true
- **크기**: ≤ 2h
- **테스트 클래스**: `PgConfirmConsumerTest`
- **테스트 메서드**:
  - `PgConfirmConsumerTest#consume_PaymentConfirmCommand_ShouldCallPgConfirmCommandService` — `payment.commands.confirm` 수신 → `PgConfirmCommandService.confirm()` 1회 호출 (어댑터는 port 경유)
  - `PgConfirmConsumerTest#consume_DuplicateCommand_ShouldDedupeByEventUuid` — 동일 eventUUID 2회 수신 → PG 호출 1회 (멱등성)
  - `PgConfirmConsumerTest#consume_WhenPgReturnsAlreadyProcessed_ShouldMapToDuplicateAttempt` — 가면 응답 → `DUPLICATE_ATTEMPT` 이벤트 발행 (도메인 중립 enum, 벤더 코드 노출 금지)
- **산출물**:
  - `pg-service/src/main/java/.../pg/infrastructure/messaging/consumer/PgConfirmConsumer.java` — `PgConfirmCommandService`(inbound port) 호출, 직접 PgGatewayPort 주입 금지
  - `pg-service/src/main/java/.../pg/infrastructure/messaging/publisher/PgEventPublisher.java` — `PgEventPublisherPort` 구현
  - `docs/topics/MSA-TRANSITION.md` ADR-12 결론란 — 토픽 네이밍 규약 `<source-service>.<type>.<action>` + 현재 토픽 목록 표 기재 (M-5)
  - `payment-service/src/main/java/.../payment/domain/messaging/PaymentTopics.java` — 결제 서비스 토픽 이름 상수 중앙화 (M-5, Phase-0.1 방침: Spring 의존 없는 값 객체)
  - `pg-service/src/main/java/.../pg/domain/messaging/PgTopics.java` — PG 서비스 토픽 이름 상수 중앙화 (M-5)
  - `product-service/src/main/java/.../product/domain/messaging/ProductTopics.java` — 상품 서비스 토픽 이름 상수 중앙화 (M-5)

---

### Phase-2.3b — 결제 서비스 측 PgStatusPort·PaymentGatewayPort 구현체 교체 (Local/Internal → HTTP/Kafka) (C-2)

- **제목**: 결제 서비스 PgStatusPort·PaymentGatewayPort 구현체 교체 — LocalPgStatusAdapter/InternalPaymentGatewayAdapter 퇴역
- **목적**: ADR-21(결제 서비스 측 어댑터 교체) — Phase-1.1에서 `LocalPgStatusAdapter`(Phase 1 한정 선언)와 `InternalPaymentGatewayAdapter`(Phase 1 한정)가 배치됨. Phase 2 PG 서비스 분리 완료 이후 결제 서비스 측 구현체를 HTTP/Kafka 기반으로 교체. Phase-3.4(ProductHttpAdapter 교체)와 동형 태스크. `@CircuitBreaker`는 adapter 구현 내부 메서드에만 부여(port 인터페이스 오염 방지).
- **tdd**: true
- **domain_risk**: true
- **크기**: ≤ 2h
- **테스트 클래스**: `PgStatusHttpAdapterTest`, `PaymentGatewayKafkaCommandAdapterTest`, `PgEventConsumerTest`
- **테스트 메서드**:
  - `PgStatusHttpAdapterTest#getStatus_WhenServiceReturnsDone_ShouldReturnDomainDone` — PG 서비스 HTTP 응답 DONE → 도메인 DONE 변환 검증
  - `PgStatusHttpAdapterTest#getStatus_WhenServiceUnavailable_ShouldThrowRetryableException` — HTTP 503/timeout → RetryableException 전파, `@CircuitBreaker` 내부 메서드 적용 검증
  - `PaymentGatewayKafkaCommandAdapterTest#confirm_ShouldPublishPaymentCommandsConfirmTopic` — confirm 호출 시 `payment.commands.confirm` 토픽으로 Kafka 커맨드 발행 1회 검증
  - `PgEventConsumerTest#consume_PaymentEventsConfirmed_ShouldMarkPaymentDone` — `payment.events.confirmed` 수신 후 outbox/PaymentEvent 상태 DONE 전이 검증
  - `PgEventConsumerTest#consume_DuplicateEvent_ShouldDedupeByEventUuid` — 동일 eventUUID 2회 수신 → 상태 전이 1회만 (멱등성)
- **산출물**:
  - `payment-service/src/main/java/.../payment/infrastructure/adapter/http/PgStatusHttpAdapter.java` — `PgStatusPort` HTTP 구현 (`GET /internal/pg/status/{orderId}` 호출). `@CircuitBreaker`는 adapter 내부 메서드에만.
  - `payment-service/src/main/java/.../payment/infrastructure/messaging/publisher/PaymentGatewayKafkaCommandAdapter.java` — `PaymentGatewayPort` confirm/cancel 경로 Kafka 커맨드 발행 구현 (`payment.commands.confirm` 토픽 등). 이 어댑터는 **getStatus 메서드 비보유** (Phase-1.0에서 `PaymentGatewayPort`는 confirm/cancel 전담으로 재정의됨). getStatus 경로는 application 계층이 `PgStatusPort`를 직접 주입해 사용 — adapter→adapter 위임 금지.
<!-- ARCH R4 RESOLVED: PaymentGatewayKafkaCommandAdapter가 PgStatusHttpAdapter에 getStatus를 위임한다는 초기 문구가 헥사고날 경계를 흐린다는 지적. Architect 권고 대안 (b) 채택 — Phase-1.0에서 `PaymentGatewayPort`의 scope를 confirm/cancel 전담으로 재정의하고 getStatus는 `PgStatusPort`가 단독 담당하도록 역할 중복 자체를 제거. 이 어댑터에서는 getStatus 메서드가 존재하지 않으므로 위임 경로도 원천 차단. -->

  - `payment-service/src/main/java/.../payment/infrastructure/messaging/consumer/PgEventConsumer.java` — `payment.events.confirmed` / `payment.events.failed` 수신 후 outbox/PaymentEvent 상태 전이 (PG 서비스 → 결제 서비스 역방향 이벤트 소비)
  - **제거 산출물**: 기존 `LocalPgStatusAdapter.java` 퇴역, `InternalPaymentGatewayAdapter.java` 퇴역 (Phase 2 완료 후 불필요)

---

### Phase-2.4 — Gateway 라우팅: PG 내부 API 격리

- **제목**: Gateway route — PG `getStatus` 내부 API는 외부 노출 차단
- **목적**: ADR-21, ADR-02 — PG 서비스의 `getStatus` API는 결제 서비스만 호출. Gateway는 외부(클라이언트) → PG 서비스 직접 라우팅을 차단.
- **tdd**: false
- **domain_risk**: false
- **크기**: ≤ 2h
- **산출물**:
  - `gateway/src/main/resources/application.yml` — 내부 서비스 route 격리 설정 (path 접두사 `/internal/**` deny 또는 serviceId 기반 필터)
  - Gateway filter: `InternalOnlyGatewayFilter.java` (외부 요청 차단)

---

## Phase 3 — 상품·사용자 서비스 분리

**목적**: 주변 도메인 분리. 결제 서비스의 `InternalProductAdapter`, `InternalUserAdapter`가 HTTP/이벤트 기반 어댑터로 교체. `stock.restore` 보상 이벤트화 + consumer dedupe 신설.

관련 ADR: ADR-22, ADR-23, ADR-02(재확정), ADR-14, ADR-16

---

### Phase-3.1 — 상품 서비스 모듈 신설 + 도메인 이관 + port 계층 + stock-snapshot 발행 훅 (ADR-22, ADR-23)

- **제목**: 상품 서비스 신규 모듈 + 도메인 엔티티 이관 + 재고 port 선언 + 런타임 스택 명시 + stock-snapshot 발행 훅
- **목적**: ADR-22(주변 도메인 분리 순서, product → user), ADR-23(DB 분리 세부) — 상품 서비스 독립 모듈 생성. Phase-1.3(결제 도메인 이관)과 대칭으로 상품 도메인 엔티티(`Product`, `Stock` aggregate)를 이관. MVC + Virtual Threads 런타임 스택 명시(§ 2-8 원칙). Flyway 마이그레이션 디렉터리 분리. **`product.events.stock-snapshot` 토픽 발행 훅 추가**: 상품 서비스 기동(ApplicationReadyEvent) 시 현재 재고 스냅샷을 토픽으로 발행. payment-service Phase-1.12 warmup이 이 토픽을 replay.
- **tdd**: false
- **domain_risk**: false
- **크기**: ≤ 2h
- **산출물**:
  - `product-service/build.gradle` — spring-boot-starter-web, virtual threads 설정, spring-kafka, spring-data-redis (§ 2-8 준수)
  - `product-service/src/main/java/.../product/domain/Product.java` — 상품 도메인 엔티티 이관
  - `product-service/src/main/java/.../product/domain/Stock.java` — 재고 도메인 엔티티 이관
  - `product-service/src/main/java/.../product/application/port/out/StockRepository.java` — 재고 조회·증감 포트
  - `product-service/src/main/java/.../product/application/port/out/EventDedupeStore.java` — `boolean recordIfAbsent(String eventUuid, Instant expiresAt)` (Phase-3.3 소비자 앞에 port 선언)
  - `product-service/src/main/java/.../product/presentation/port/StockRestoreCommandService.java` — inbound port
  - `product-service/src/main/java/.../product/application/usecase/StockRestoreUseCase.java` — `StockRestoreCommandService` 구현 겸임 (`StockRestoreUseCase implements StockRestoreCommandService`). ARCHITECTURE.md 관례(`PaymentConfirmService ← OutboxAsyncConfirmService`) 준수. Phase-3.3 `StockRestoreConsumer`는 `StockRestoreCommandService` 인터페이스 타입으로 주입받는다.
  - `product-service/src/main/resources/db/migration/V1__product_schema.sql` — 상품·재고 테이블
  - `product-service/docker-compose` 항목 — 상품 전용 MySQL 컨테이너
  - `product-service/src/main/java/.../product/infrastructure/config/KafkaTopicConfig.java` — NewTopic 빈 복제 배치 (`product.events.stock-snapshot` 포함)
  - **`product-service/src/main/java/.../product/infrastructure/event/StockSnapshotPublisher.java`** — `ApplicationListener<ApplicationReadyEvent>` 구현. 전 상품 재고를 `product.events.stock-snapshot` 토픽으로 일괄 발행. `infrastructure/event/` 경로. (Phase-1.12 warmup의 pair 산출물)

<!-- ARCH R2 RESOLVED: Phase-3.1 `StockRestoreCommandService` 구현체 주체 공백(F-17). `StockRestoreUseCase implements StockRestoreCommandService` 겸임 채택(택일 a)을 산출물에 명시하고, Phase-3.3 consumer가 인터페이스 타입으로 주입받음을 기술하여 해소. -->
<!-- ARCH R5: stock-snapshot 발행 훅 추가. Phase-1.12 warmup과 pair. S-3 Reconciler 확장의 사전 조건. -->

---

### Phase-3.1b — 사용자 서비스 모듈 신설 + 도메인 이관 + port 계층 + Flyway V1 (C-1)

- **제목**: 사용자 서비스 신규 모듈 + 도메인 엔티티 이관 + 사용자 조회 port 선언 + Flyway V1
- **목적**: ADR-22(product → user 순서) — Phase-3.1(상품 서비스 신설) 직후 사용자 서비스 모듈을 신설하여 ADR-22의 분리 순서를 완성. Phase-3.4의 `UserHttpAdapter`가 호출할 사용자 서비스 엔드포인트(`GET /api/v1/users/{id}`)를 이 태스크에서 확보. Phase-3.1의 StockRestoreUseCase 패턴과 대칭으로 구성.
- **tdd**: false
- **domain_risk**: false
- **크기**: ≤ 2h
- **산출물**:
  - `user-service/build.gradle` — spring-boot-starter-web, virtual threads(§ 2-8), spring-kafka
  - `user-service/src/main/java/.../user/domain/User.java` — 사용자 도메인 엔티티 이관
  - `user-service/src/main/java/.../user/application/port/out/UserRepository.java` — 사용자 조회 포트
  - `user-service/src/main/java/.../user/presentation/port/UserQueryService.java` — inbound port
  - `user-service/src/main/java/.../user/application/usecase/UserQueryUseCase.java` — `UserQueryService` 구현 겸임 (`UserQueryUseCase implements UserQueryService`, Phase-3.1 StockRestoreUseCase 패턴과 대칭)
  - `user-service/src/main/java/.../user/presentation/UserController.java` — `GET /api/v1/users/{id}` (MVC)
  - `user-service/src/main/resources/db/migration/V1__user_schema.sql` — `user` 테이블 DDL
  - `docker-compose.infra.yml`에 사용자 전용 MySQL 컨테이너 추가 방침 (Phase-0.1 DB 경계 방침 준수)
  - `user-service/src/main/java/.../user/infrastructure/config/KafkaTopicConfig.java` — NewTopic 빈 (현재는 비워두되 후속 확장 여지 표시, Phase-0.1 복제 방침 준수)

---

### Phase-3.2 — Fake 상품 서비스 구현 (테스트용 — StockCommit·StockRestore 공용)

- **제목**: FakeStockRepository + FakeEventDedupeStore + FakePaymentRedisStockPort
- **목적**: ADR-16 보상 dedupe 테스트를 실제 DB 없이 수행하기 위한 Fake. Phase-3.1c(StockCommitConsumer)와 Phase-3.3(StockRestoreConsumer) 두 소비자가 공유하는 Fake 묶음. "Fake가 소비자 앞에" 원칙(Phase-1.2 패턴 준수) — Phase-3.1b 직후·Phase-3.1c 전에 배치.
- **tdd**: false
- **domain_risk**: false
- **크기**: ≤ 2h
- **산출물**:
  - `product-service/src/test/java/.../mock/FakeStockRepository.java` — `StockRepository` 인메모리 구현
  - `product-service/src/test/java/.../mock/FakeEventDedupeStore.java` — `EventDedupeStore` 인메모리 구현 (TTL 만료 시뮬레이션 가능)
  - `product-service/src/test/java/.../mock/FakePaymentRedisStockPort.java` — `PaymentRedisStockPort` 인메모리 구현 (`Map<Long, Integer>`로 SET 호출 기록, 테스트 assertion용)

<!-- ARCH R5 RESOLVED: Phase-3.2를 Phase-3.1b 직후·Phase-3.1c 전으로 재배치하고 Phase-3.2 산출물에 `FakePaymentRedisStockPort`를 추가하여 "Fake가 소비자 앞에" 원칙을 복원. Phase-3.1c 테스트 메서드 재선언 불필요 (Fake 위치만 이동). -->

---

### Phase-3.1c — StockCommitConsumer + payment-service 전용 Redis 직접 쓰기 (S-2/S-3)

- **제목**: product-service가 payment.events.stock-committed 소비 → RDB UPDATE + payment 전용 Redis 직접 SET
- **목적**: S-2(StockCommitEvent 발행 공백) + S-3(Reconciler 확장) — product-service가 payment-service 발행 `payment.events.stock-committed`를 consume하여 (1) 자기 RDB stock 컬럼 UPDATE, (2) payment-service 전용 Redis에 `stock:{productId}` 직접 SET. **동기화 경로**: product→payment Redis는 **Kafka 경유 아님** — product-service가 RDB UPDATE 완료 직후 payment Redis에 직접 SET (상품 생성·수정·admin 조정 포함). `payment.events.stock-committed` consumer dedupe: Phase-3.3 `EventDedupeStore` 패턴 동일하게 이벤트 UUID 키로 dedupe. keyspace `stock:{productId}`.
- **tdd**: true
- **domain_risk**: true
- **크기**: ≤ 2h
- **테스트 클래스**: `StockCommitConsumerTest`, `StockCommitUseCaseTest`
- **테스트 메서드**:
  - `StockCommitUseCaseTest#commit_ShouldUpdateRdbAndSetPaymentRedis` — `payment.events.stock-committed` 소비 시 (1) StockRepository RDB UPDATE + (2) PaymentRedisStockPort SET 원자적 호출 검증 (FakeStockRepository + FakePaymentRedisStockPort 사용)
  - `StockCommitUseCaseTest#commit_DuplicateEventUuid_ShouldNoOp` — 동일 eventUUID 2회 → 1회만 처리 (dedupe)
  - `StockCommitUseCaseTest#commit_WhenRdbUpdateFails_ShouldNotSetRedis` — RDB UPDATE 실패 시 Redis SET 미호출 검증 (정합성 보호)
  - `StockCommitConsumerTest#consume_ShouldDelegateToStockCommitUseCase` — Kafka 메시지 수신 시 use case 1회만 호출 (consumer 얇음)
- **산출물**:
  - `product-service/src/main/java/.../product/application/port/out/PaymentRedisStockPort.java` — `set(productId, qty): void` (payment 전용 Redis 쓰기 포트. `stock:{productId}` keyspace.)
  - `product-service/src/main/java/.../product/infrastructure/cache/PaymentRedisStockAdapter.java` — `PaymentRedisStockPort` 구현. payment-service 전용 Redis 연결(`redis-payment` 컨테이너). Spring Data Redis `RedisTemplate` 사용. `infrastructure/cache/` 경로.
  - `product-service/src/main/java/.../product/infrastructure/messaging/consumer/StockCommitConsumer.java` — `payment.events.stock-committed` 토픽 consumer. use case 호출 경유.
  - `product-service/src/main/java/.../product/application/usecase/StockCommitUseCase.java` — RDB UPDATE + Redis SET 조합. EventDedupeStore dedupe 적용 (Phase-3.3 패턴).
  - `product-service/src/main/resources/db/migration/V3__add_stock_commit_dedupe.sql` — stock-committed dedupe 테이블 (또는 Phase-3.3의 event_dedupe 테이블 재사용 방침 명시)
  - `product-service/src/main/resources/application.yml` — payment 전용 Redis 연결 설정 (`redis-payment` 엔드포인트)

<!-- ARCH R5: S-2(StockCommitEvent 발행·소비 경로 공백) + S-3(Redis 직접 쓰기 경로) 반영. 동기화 경로 분기: product→payment Redis 직접 SET(Kafka 경유 아님), payment→product RDB Kafka 이벤트 경유. "예약/reservation" 용어 배제. -->
<!-- ARCH R5 RESOLVED: product-service에서 payment 전용 Redis에 쓰는 경계가 `PaymentRedisStockPort`(application/port/out) → `PaymentRedisStockAdapter`(infrastructure/cache)로 port/adapter 층을 통해 분리되고, 엔드포인트는 application.yml의 `redis-payment` 설정으로 외부화됨 — product-service가 payment 인프라 엔드포인트를 코드에 하드코딩하지 않음. 헥사고날 경계 준수. -->
<!-- ARCH R5: 참고(minor, 판정 영향 없음) — (1) 포트 이름 `PaymentRedisStockPort`가 대상 인프라("PaymentRedis")를 이름에 박아 도메인 중립 대신 infra-aware 포트가 됨. 교체 용이성 관점에서 `ExternalStockCacheWriterPort` 류가 더 이상적이나, 현 이름이 경로의 의도를 선명히 드러내는 장점도 있어 트레이드오프. (2) `stock:{productId}` keyspace 상수가 payment-service(Phase-1.4d/1.9/1.12)와 product-service(Phase-3.1c) 양쪽에 중복 하드코딩 — drift 리스크. 공용 `common` 모듈 도입은 본 토픽 scope를 벗어나므로 각 서비스 `domain/messaging/` 또는 `infrastructure/cache/` 내 상수 클래스로 분리 + 두 서비스 코드 리뷰로 동기화 유지하는 수준이 현실적. execute 단계에서 구현자에게 인지시킬 사항. -->


---

### Phase-3.3 — 보상 이벤트 consumer dedupe 구현 (ADR-16) — domain_risk

- **제목**: `stock.restore` 이벤트 consumer + dedupe port/구현체 분리 (UUID 키, 상품 서비스 소유)
- **목적**: ADR-16(Idempotency 분산화 + 보상 dedupe 소유 결정) — at-least-once 전제에서 `stock.restore` 중복 수신 시 이중 복원 방지. consumer 측(상품 서비스)이 이벤트 UUID를 자기 DB dedupe 테이블에 기록. TTL = Kafka consumer group offset retention + 1일. `StockRestoreConsumer`(infrastructure)는 `StockRestoreUseCase`(application)만 호출 — consumer 안에서 직접 dedupe·stock 로직 금지.
- **tdd**: true
- **domain_risk**: true
- **크기**: ≤ 2h
- **테스트 클래스**: `StockRestoreConsumerTest`, `StockRestoreUseCaseTest`
- **테스트 메서드**:
  - `StockRestoreUseCaseTest#restore_StockRestoreEvent_ShouldIncreaseStock` — FakeStockRepository + FakeEventDedupeStore로 재고 복원 1회 검증
  - `StockRestoreUseCaseTest#restore_DuplicateEventUuid_ShouldNoOp` — 동일 이벤트 UUID 2회 → 재고 복원 1회만 (이중 복원 방지)
  - `StockRestoreUseCaseTest#restore_AfterDedupeTtlExpiry_ShouldReprocessOnce` — TTL 만료 시뮬레이션 → 첫 처리만 성공
  - `StockRestoreUseCaseTest#restore_WhenStockIncreaseFailsMidway_ShouldNotRecordDedupe` — DB 재고 증가 실패 시 dedupe 레코드 미기록 (재시도 안전성)
  - `StockRestoreConsumerTest#consume_ShouldDelegateToStockRestoreUseCase` — Kafka 메시지 수신 시 `StockRestoreUseCase.restore()` 1회만 호출 (consumer 얇음 검증)
- **산출물**:
  - `product-service/src/main/java/.../product/infrastructure/messaging/consumer/StockRestoreConsumer.java` — `StockRestoreCommandService`(inbound port) 경유 usecase 호출
  - `product-service/src/main/java/.../product/infrastructure/idempotency/JdbcEventDedupeStore.java` — `EventDedupeStore` 구현체 (JdbcTemplate 또는 JPA)
  - `product-service/src/main/java/.../product/infrastructure/idempotency/JpaEventDedupeRepository.java` — Spring Data 인터페이스 (infra 내부)
  - `product-service/src/main/resources/db/migration/V2__add_event_dedupe_table.sql` — `event_uuid VARCHAR`, `expires_at TIMESTAMP NOT NULL` 컬럼 포함

---

### Phase-3.4 — 결제 서비스 ProductPort/UserPort → HTTP 어댑터 교체

- **제목**: InternalProductAdapter → HTTP 기반 ProductHttpAdapter 교체
- **목적**: ADR-02, ADR-22 — 결제 서비스의 `ProductLookupPort`, `UserLookupPort` 구현체를 직접 Java 호출(`InternalProductAdapter`)에서 HTTP REST 기반 어댑터로 교체. Resilience4j circuit breaker는 **adapter 구현 내부 메서드에만** `@CircuitBreaker` 어노테이션 부여(port 인터페이스 오염 방지 — `infrastructure/adapter/http/` 경로 단일화).
- **tdd**: true
- **domain_risk**: false
- **크기**: ≤ 2h
- **테스트 클래스**: `ProductHttpAdapterTest`
- **테스트 메서드**:
  - `ProductHttpAdapterTest#getProduct_ShouldCallProductServiceAndReturnDomain` — HTTP 호출 응답 → 도메인 DTO 변환 검증 (Mockito stub RestTemplate/WebClient)
  - `ProductHttpAdapterTest#decreaseStock_WhenServiceUnavailable_ShouldThrowRetryableException` — HTTP 503 → `PaymentGatewayRetryableException` 전파
- **산출물**:
  - `payment-service/src/main/java/.../payment/infrastructure/adapter/http/ProductHttpAdapter.java` — `ProductLookupPort` 구현, `@CircuitBreaker` 내부 메서드에만
  - `payment-service/src/main/java/.../payment/infrastructure/adapter/http/UserHttpAdapter.java` — `UserLookupPort` 구현

---

### Phase-3.5 — Gateway 라우팅: 상품·사용자 엔드포인트 교체

- **제목**: Gateway route — 상품·사용자 엔드포인트 신규 서비스로 라우팅
- **목적**: ADR-01, ADR-02 — Gateway가 `/api/v1/products/**`, `/api/v1/users/**`를 신규 서비스로 라우팅. 모놀리스에서 해당 컨텍스트 비활성화.
- **tdd**: false
- **domain_risk**: false
- **크기**: ≤ 2h
- **산출물**:
  - `gateway/src/main/resources/application.yml` route 추가 — `product-service`, `user-service`

---

## Phase 4 — 장애 주입 검증 · 로컬 오토스케일러

**목적**: 전 ADR 교차 검증. 이 Phase 통과가 본 토픽 최종 성공 조건. Toxiproxy 기반 장애 시나리오, k6 재설계, 로컬 오토스케일러 코드.

관련 ADR: ADR-29, ADR-09, ADR-28, ADR-17

---

### Phase-4.1 — Toxiproxy 장애 시나리오 스위트 (ADR-29)

- **제목**: Kafka 지연 / DB 지연 / 프로세스 kill / 보상 이벤트 중복 / FCG timeout / Redis down / 재고 캐시 발산 8종 장애 주입 시나리오
- **목적**: ADR-29(알려진 결함 MSA 악화 검증 + 장애 주입 도구) — Toxiproxy proxy를 통해 장애를 주입하고 최종 정합성(재고 일치 + 결제 상태 종결) 복원을 검증. FCG 불변(`timeout → QUARANTINED`)과 consumer dedupe(이중 복원 방지), Redis 재고 캐시 차감 장애 분기를 통합 수준에서 교차 검증.
- **tdd**: false
- **domain_risk**: true
- **크기**: ≤ 2h
- **산출물**:
  - `chaos/scenarios/kafka-latency.sh` — Toxiproxy latency toxic 주입 + k6 실행 + 정합성 확인. **수락 기준**: `payment.outbox.pending_age_seconds` histogram p95 ≥ 10s가 Prometheus 쿼리로 관측됨 (plan-domain-1 minor finding #3 반영)
  - `chaos/scenarios/db-latency.sh` — MySQL proxy latency toxic
  - `chaos/scenarios/process-kill.sh` — 결제 서비스 컨테이너 kill + 재시작 + reconciler 복원 대기
  - `chaos/scenarios/verify-consistency.sh` — 결제 DB 재고 DB 크로스 검증 스크립트 (재고 RDB와 Redis stock cache 동시 대조 포함)
  - `chaos/scenarios/stock-restore-duplicate.sh` — `stock.restore` 이벤트를 동일 UUID로 2회 발행 + 상품 서비스 DB 재고 증가량이 1회만 반영됐는지 검증 (plan-domain-1 major finding #2 반영)
  - `chaos/scenarios/fcg-pg-timeout.sh` — Toxiproxy로 PG `getStatus` 엔드포인트 timeout 주입 + PaymentEvent가 QUARANTINED로 전이됐는지 확인 (FAILED/DONE 아님, ADR-15 불변 통합 검증)
  - **`chaos/scenarios/redis-down.sh`** — payment 전용 Redis 컨테이너 중단 + 결제 confirm 시도 → QUARANTINED 전이 확인 + Reconciler Redis 복원 후 정합성 확인. **수락 기준**: Redis down 기간 중 Overselling 0 보장, Reconciler 재시작 후 RDB 기준 Redis 재설정.
  - **`chaos/scenarios/stock-cache-divergence.sh`** — Redis stock cache 값을 수동으로 잘못 설정 → Reconciler 주기 스캔 후 RDB 기준 재설정 + `payment.stock_cache.divergence_count` 카운터 증가 Prometheus 쿼리 확인. **수락 기준**: Reconciler가 발산 감지 후 Redis를 RDB 기준으로 재설정.

<!-- ARCH R5: Redis down 시나리오(S-1), 재고 캐시 발산 시나리오(S-3) 추가. 총 8종 chaos 시나리오. -->

---

### Phase-4.2 — k6 시나리오 재설계 (ADR-28)

- **제목**: Gateway 경유 k6 단일 시나리오 + 비동기 결과 폴링
- **목적**: ADR-28(k6 재설계, 대안 a 기반) — Gateway를 통해 결제 confirm → 상태 폴링 → 최종 상태 확인 흐름을 k6로 재구성. 현 `k6/` 디렉터리 스크립트 기반 확장.
- **tdd**: false
- **domain_risk**: false
- **크기**: ≤ 2h
- **산출물**:
  - `k6/msa-payment-scenario.js` — Gateway 경유 confirm + poll 시나리오
  - `k6/msa-config.json` — 대상 URL Gateway 엔드포인트, VU 설정

---

### Phase-4.3 — 로컬 오토스케일러 (ADR-09)

- **제목**: Docker SDK 기반 CPU·큐 길이 기반 결제 서비스 레플리카 조정
- **목적**: ADR-09(로컬 오토스케일링, 대안 a) — Prometheus 메트릭(큐 길이 또는 CPU)을 주기적으로 조회해 결제 서비스 레플리카를 docker-compose scale로 조정하는 Python/Go 스크립트.
- **tdd**: false
- **domain_risk**: false
- **크기**: ≤ 2h
- **산출물**:
  - `autoscaler/autoscaler.py` (또는 `.go`) — Prometheus 조회 → docker-compose 레플리카 조정 루프
  - `autoscaler/README.md` — 실행 방법 및 스케일링 임계값 설명

---

## Phase 5 — 잔재 정리

**목적**: Admin UI 처리, 공통 문서 최종화, 관측성 메트릭 네이밍 정비.

관련 ADR: ADR-24, ADR-19, ADR-20

---

### Phase-5.1 — 메트릭 네이밍 규약 공통화 (횡단 작업) + Admin UI 처리 결정 (ADR-20, ADR-24)

- **제목**: `<service>.<domain>.<event>` 메트릭 컨벤션 전 서비스 적용 + Admin UI 잔재 처리
- **목적**: ADR-20(메트릭 네이밍 규약), ADR-24(Admin UI 서비스화 여부) — 결제·PG·상품·사용자 서비스 전체의 메트릭 이름·태그를 `payment.event.status_change`, `payment.quarantine.count`, `pg.api.toss.duration_seconds` 등 규약으로 일괄 정렬. 메트릭 클래스는 `infrastructure/metrics/`에 배치(application/usecase 아님). Admin UI는 ADR-24 결론(모놀리스 잔재 기본값)에 따라 처리. **관리자 데이터 접근 방침(M-4)**: Admin은 모놀리스 DB 직접 SELECT 경로를 폐기하고 Gateway 경유 HTTP 호출로 각 서비스 API 사용. 이행은 기본 모놀리스 잔류(ADR-24), 쓰기 경로는 각 서비스 API 위임.
- **tdd**: false
- **domain_risk**: false
- **크기**: ≤ 2h
- **산출물**:
  - `payment-service/src/main/java/.../payment/infrastructure/metrics/PaymentStateMetrics.java` — 이름·태그 규약 정렬 (`infrastructure/metrics/` 경로)
  - `payment-service/src/main/java/.../payment/infrastructure/metrics/PaymentTransitionMetrics.java` — 동일
  - `payment-service/src/main/java/.../payment/infrastructure/metrics/PaymentQuarantineMetrics.java` — 동일
  - `pg-service/src/main/java/.../pg/infrastructure/metrics/PgApiMetrics.java` — PG 서비스 메트릭 이름·태그 정렬
  - `product-service/src/main/java/.../product/infrastructure/metrics/StockMetrics.java` — 상품 서비스 메트릭 이름·태그 정렬
  - Grafana 대시보드 쿼리 예시 업데이트 (인라인 주석 또는 `docs/` 경로 파일)

---

### Phase-5.2 — LogFmt 공통화 완결 + 최종 문서화

- **제목**: LogFmt/MaskingPatternLayout 복제 방침(Phase-0.3) 전 서비스 적용 확인 + 아카이브
- **목적**: ADR-19(LogFmt 공통화) — Phase 0.3에서 확정된 복제(b) 방침대로 각 서비스 `logback-spring.xml`에 `MaskingPatternLayout` 적용 확인. 본 토픽 `docs/topics/MSA-TRANSITION.md` → `docs/archive/` 이동.
- **tdd**: false
- **domain_risk**: false
- **크기**: ≤ 2h
- **산출물**:
  - 각 서비스 `src/main/resources/logback-spring.xml` — `MaskingPatternLayout` 적용 확인
  - `docs/archive/MSA-TRANSITION.md` — 아카이브 이동

---

## 추적 테이블: discuss 리스크 → 태스크 매핑

| 리스크 출처 | 리스크 내용 | 대응 태스크 | domain_risk |
|---|---|---|---|
| ADR-05 (discuss-domain-2 Phase 1 승격) | Toss `ALREADY_PROCESSED_PAYMENT` / NicePay `2201` 가면 응답 방어, 금액 일치 검증 대칭 + Toss 전략 wiring 완결(TossPaymentErrorCode.isSuccess() 수정) | Phase-1.5 | true |
| ADR-16 (discuss-domain-2 보상 dedupe) | 보상 이벤트 `stock.restore` consumer dedupe (이벤트 UUID 키, 상품 서비스 소유, TTL 정량화, port/구현체 분리) | Phase-3.3 | true |
| ADR-15 (discuss-domain-2 FCG 불변) | FCG timeout 시 무조건 QUARANTINED 전이 (재시도 래핑 금지) + QUARANTINED 결제 Redis DECR 상태 유지 불변 + 통합 chaos 검증 (Phase-4.1 fcg-pg-timeout.sh) | Phase-1.7, Phase-4.1 | true |
| ADR-13 (discuss-domain-2 감사 원자성) | `payment_history` 결제 서비스 내부 잔류, BEFORE_COMMIT TX 리스너 원자성 보존 + AOP 축 복제(Phase-1.4b) + Flyway V1(Phase-1.4c). Phase-1.4c 이후 재고 캐시 차감 TX 경계 외부 — 재고 부족→FAILED, 시스템 장애→QUARANTINED 분기로 방어(S-1 반영) | Phase-1.4, Phase-1.4b, Phase-1.4c | true |
| ADR-20 (discuss-domain-1 minor) | `payment.outbox.pending_age_seconds` histogram (stock lock-in 감지) + `payment.stock_cache.divergence_count` 카운터 + Phase-4.1 chaos 수락 기준 연결 | Phase-1.11, Phase-4.1 | true |
| ADR-04 + RetryPolicy (기존 자산) | RetryPolicy 재시도 안전성 승계, outbox relay at-least-once 보장 + 통합 chaos 검증(stock-restore-duplicate.sh) | Phase-1.6, Phase-4.1 | true |
| Strangler Fig 이중 발행 방지 (discuss-domain-2 minor) | 모놀리스 결제 confirm 경로 비활성화 → Gateway 전환 후 단일 발행자 보장 | Phase-1.10 | true |
| ADR-21 결제 서비스 측 어댑터 교체 (C-2 반영) | 결제 서비스 `PgStatusPort`·`PaymentGatewayPort` 구현체를 LocalPgStatusAdapter/InternalPaymentGatewayAdapter에서 HTTP/Kafka 기반으로 교체. PG 이벤트 역방향 소비(PgEventConsumer). 기존 Phase 1 한정 구현체 퇴역 | Phase-2.3b | true |
| ADR-22 사용자 서비스 모듈 신설 (C-1 반영) | user-service 모듈, 도메인 이관, port 계층, Flyway V1, 사용자 전용 MySQL 컨테이너. ADR-22 product → user 순서 완성. Phase-3.4 UserHttpAdapter 호출 대상 엔드포인트 확보 | Phase-3.1b | false |
| ADR-12 이벤트 스키마 + 토픽 네이밍 규약 (M-5 반영) | 토픽 이름 drift 방지, `<source-service>.<type>.<action>` 규약 확정 및 문서화, 서비스별 토픽 이름 상수 중앙화(PaymentTopics/PgTopics/ProductTopics), `payment.events.stock-committed` 토픽 추가 | Phase-2.3, Phase-1.5b | true |
| **S-1 재고 캐시 차감 전략 공백 (Round 5 신규)** | payment 전용 Redis DECR atomic 차감, Overselling 0 보장, Redis down→QUARANTINED, FAILED vs QUARANTINED 분기 명시 | Phase-0.1, Phase-1.0, Phase-1.2, Phase-1.4, Phase-1.4d, Phase-1.7 | true |
| **S-2 StockCommitEvent 발행 공백 (Round 5 신규)** | 결제 확정 시 `payment.events.stock-committed` 발행 → product-service RDB UPDATE. 포트 선언 + Kafka 구현체 + consumer dedupe | Phase-1.1, Phase-1.5b, Phase-3.1c | true |
| **S-3 Reconciler 재고 대조 공백 (Round 5 신규)** | Redis ↔ RDB 대조 알고리즘, QUARANTINED DECR 복원, TTL 기반 자동 복원, warmup 경로 | Phase-1.9, Phase-1.12, Phase-3.1, Phase-4.1 | true |
| **S-4 멱등성 저장소 MSA 스케일링 공백 (Round 5 신규)** | Caffeine 로컬 캐시 → Redis 이관, SETNX 동시성 방어, horizontal stateless 보장 | Phase-0.1a | true |

---

## 반환 지표

- **태스크 총 개수**: 40
- **domain_risk=true 태스크 개수**: 19
  - Phase-0.1a, Phase-1.3, Phase-1.4, Phase-1.4d, Phase-1.4b, Phase-1.4c, Phase-1.5, Phase-1.5b, Phase-1.6, Phase-1.7, Phase-1.9, Phase-1.10, Phase-1.11, Phase-1.12, Phase-2.3, Phase-2.3b, Phase-3.1c, Phase-3.3, Phase-4.1
  - (Phase-3.4, Phase-3.1b 등은 `domain_risk=false` — 본 집계 제외)
- **topic.md 결정 중 태스크로 매핑하지 못한 항목**: 없음 (orphan 없음)
- **Round 5 신규 공백 해소 현황**:
  - S-1 재고 캐시 차감: Phase-0.1(인프라) + Phase-1.0(port) + Phase-1.2(Fake) + Phase-1.4(테스트 분할) + Phase-1.4d(Redis 어댑터) + Phase-1.7(QUARANTINED 불변) → 완전 매핑
  - S-2 StockCommitEvent 발행: Phase-1.1(port) + Phase-1.5b(발행자) + Phase-3.1c(consumer + Redis 직접 쓰기) → 완전 매핑
  - S-3 Reconciler 재고 대조: Phase-1.9(대조 알고리즘) + Phase-1.12(warmup) + Phase-3.1(snapshot 훅) + Phase-4.1(chaos 검증) → 완전 매핑
  - S-4 멱등성 MSA 스케일링: Phase-0.1a(Redis 이관) → 완전 매핑

---

## ADR → 태스크 커버리지 확인

| ADR | 태스크 |
|---|---|
| ADR-01 (분해 기준) | Phase-1.0, Phase-1.1, Phase-1.10, Phase-2.1, Phase-3.1, Phase-3.5 |
| ADR-02 (통신 패턴) | Phase-1.0, Phase-1.10, Phase-2.4, Phase-3.4, Phase-3.5 |
| ADR-03 (일관성 모델) | Phase-1.3 |
| ADR-04 (메시지 유실) | Phase-1.3, Phase-1.6, Phase-1.9, Phase-4.1 |
| ADR-05 (멱등성 + 가면 방어) | Phase-1.5 |
| ADR-06 (Saga 보상) | Phase-1.7, Phase-1.9 |
| ADR-07 (Reconciliation) | Phase-1.9, Phase-1.12 |
| ADR-08 (관측성 통합) | Phase-0.3, Phase-5.2 |
| ADR-09 (로컬 오토스케일링) | Phase-4.3 |
| ADR-10 (compose 토폴로지) | Phase-0.1 |
| ADR-11 (Spring Cloud 매트릭스 + 런타임 스택) | Phase-0.2, Phase-0.1, Phase-1.1, Phase-2.1, Phase-3.1 |
| ADR-12 (이벤트 스키마 + 토픽 네이밍 규약) | Phase-2.3, Phase-1.5b (payment.events.stock-committed 추가) |
| ADR-13 (AOP 운명 + 감사 원자성) | Phase-1.4, Phase-1.4b, Phase-1.4c |
| ADR-14 (이벤트 vs 커맨드) | Phase-2.3, Phase-3.5 |
| ADR-15 (FCG 격리 불변) | Phase-1.7, Phase-4.1 |
| ADR-16 (Idempotency 분산 + 보상 dedupe) | Phase-0.1a(Redis 이관), Phase-1.2, Phase-3.2, Phase-3.3, Phase-3.1c |
| ADR-17 (Reconciler vs FCG 역할) | Phase-1.9 |
| ADR-18 (W3C Trace Context) | Phase-0.3 |
| ADR-19 (LogFmt 공통화) | Phase-0.3, Phase-5.2 |
| ADR-20 (메트릭 네이밍 + stock lock-in) | Phase-1.11, Phase-4.1, Phase-5.1 |
| ADR-21 (PG 물리 분리) | Phase-2.1, Phase-2.1b, Phase-2.2, Phase-2.3b(결제 서비스 측 구현체 교체), Phase-2.4 |
| ADR-22 (주변 도메인 순서) | Phase-3.1, Phase-3.1b(사용자 서비스 신설), Phase-3.4 |
| ADR-23 (DB 분리 세부) | Phase-1.4c, Phase-3.1, Phase-3.1b(사용자 DB) |
| ADR-24 (Admin UI) | Phase-5.1 |
| ADR-25 (Graceful Shutdown) | Phase-1.8 |
| ADR-26 (VT vs PT) | Phase-1.8 |
| ADR-27 (로컬 DX 프로필) | Phase-0.1 |
| ADR-28 (k6 재설계) | Phase-4.2 |
| ADR-29 (결함 악화 검증 + 장애 주입) | Phase-0.4, Phase-4.1 |
| **S-1 재고 캐시 차감 (Round 5 신규 — ADR 부재, topic.md 설계 결정으로 추적)** | Phase-0.1, Phase-1.0, Phase-1.2, Phase-1.4, Phase-1.4d, Phase-1.7, Phase-1.9 |
| **S-2 StockCommitEvent 발행 (Round 5 신규 — ADR 부재)** | Phase-1.1, Phase-1.5b, Phase-3.1c |
| **S-3 Reconciler 재고 대조 (Round 5 신규 — ADR 부재)** | Phase-1.9, Phase-1.12, Phase-3.1, Phase-4.1 |
| **S-4 멱등성 MSA 스케일링 (Round 5 신규 — ADR-16 연계)** | Phase-0.1a |
