# discuss-domain-1

**Topic**: PAYMENT-EOS-TRANSITION
**Round**: 1
**Persona**: Domain Expert (격리)

## Reasoning

산출물은 위키 EOS 안과 실제 코드 사이 잔여 갭을 메우는 빅뱅 PR을 정의한다. §4(결정)/§5(layer)/§9(도메인 리스크)/§11(한계) 의 추상 수준은 적절하고 멱등성 골격(`event_uuid` PK INSERT IGNORE + 발행 항상 진행)도 위키 line 141 보장과 정합하지만, **(a) 발행 보장 모델 자체가 outbox → EOS 로 전환되는 빅뱅 PR 임에도 multi-product `idempotencyKey` 유지 의무가 산출물 어디에도 명시되지 않아 downstream silent 회귀 경로가 열렸고**, **(b) D4 transactional.id 정책이 docker-compose 의 현재 `hostname: payment-service` 고정 설정과 정면 충돌해 다중 인스턴스 확장 시 fencing 의도 정반대로 동작할 수 있으며**, **(c) §3 To-Be 분기 flowchart 가 `markPaymentAsDone`/`markPaymentAsFail` 의 도메인 사전조건(QUARANTINED 같은 non-terminal 비정상 상태에서의 거부) 과 정합하지 않아 IllegalStateException 즉시 DLQ 분기를 silent 로 흡수**한다. (a)/(b)/(c) 중 (a)가 본 토픽 빅뱅 PR의 가장 큰 도메인 회귀 위험이라 critical, (b)는 high (배포 직후 시점에서는 단일 인스턴스라 즉시 사고는 없지만 토픽 결정 자체가 결함이라 plan 단계 진입 전 수정 필요), (c)는 high. fail 판정.

## Domain risk checklist

체크리스트 `domain risk` 섹션 (discuss-ready.md line 40~45):

- [yes] **멱등성 전략 결정**: §9 멱등성 전략 표 — `event_uuid` 소스 / 수명 (8일) / 충돌 처리 (INSERT IGNORE → 0 row 면 비즈니스 skip, 발행은 항상 진행) 모두 명시. cleanup 정책은 본 토픽 범위 밖이지만 명시적으로 TC-11 이관.
- [yes] **장애 시나리오 ≥ 3 식별**: §9 a~e 5개 (RDB commit 직후 producer commit 직전 crash / producer commit 직후 offset commit 직전 crash / Kafka tx coordinator 응답 불능 / abort → product-service read_committed 가시화 / markIfAbsent 후 GC pause + Kafka tx timeout).
- [yes] **재시도 정책 정의**: §9 재시도 정책 — 기존 `DefaultErrorHandler` (FixedBackOff 1s × 5 → DLQ) 유지 + EOS와 직교임 명시.
- [yes] **PII 검토**: §9 PII — 새로 도입되는 PII 없음 (event_uuid 비식별 / order_id 내부 식별자 / status enum / timestamp).

체크리스트 항목 4개 모두 yes — 그러나 yes 판정 자체가 도메인 리스크 부재를 보장하지 않는다. 아래 추가 검토에서 가드 누락된 영역을 등재.

## 도메인 관점 추가 검토 (7가지 관점 + 발견된 추가 항목)

### 1. 멱등성 충분성 (event_uuid INSERT IGNORE + isTerminal 가드 + APPROVED markPaymentAsDone race)

산출물 §3 mermaid (line 206~226) 의 분기는 다음 순서다:
1. `BeginTx` (producer+RDB)
2. `TerminalGuard` (isTerminal? — 산출물이 'handle 진입에서' 가드 적용 명시)
3. `Markup` (INSERT IGNORE)
4. `AffectedCheck` (0 = 중복 → SkipBiz; 1 = 신규 → Branch)
5. Branch (APPROVED/FAILED/QUARANTINED)
6. APPROVED → `markPaymentAsDone` + StockSend
7. SkipBiz → StockSend
8. RDB commit + offset sendOffsetsToTransaction + producer commit

여기서 **APPROVED markPaymentAsDone race 가 열린다**:
- 시나리오: 같은 `event_uuid` 의 메시지가 두 번째 partition rebalance 직후 잠깐 두 consumer 에 동시 도달한다고 가정하자. Kafka는 partition 당 한 consumer 만 보장하지만 rebalance 짧은 윈도우에는 양쪽이 처리 시도 가능. 양쪽 모두 INSERT IGNORE 시도 → 한쪽은 0 row (skip 비즈니스 + 발행) / 다른 쪽은 1 row (markPaymentAsDone + 발행). row 0 측은 도메인 transition 안 함, row 1 측은 transition + AOP audit + stock-committed 발행. 결과적으로 발행은 2회 (양쪽 모두 항상 발행). `idempotencyKey` 는 결정론적이라 product-service 측 dedupe 가 흡수하지만, **`PaymentEventStatus.done()` 의 자기전이 가드 (`status == DONE → return`) 가 무력화되는 race 는 없는가**?
- 실제 코드 확인 (PaymentEvent.java line 97~116): `done()` 은 DONE 자기전이는 no-op, IN_PROGRESS/RETRYING 만 진행, 그 외 `PaymentStatusException` throw. 따라서 race 두 측 모두 IN_PROGRESS 였다고 가정하면 한쪽 성공 + 다른쪽 done(approvedAt) 호출 시점에 이미 DONE → no-op return. 도메인 invariant는 보장. **그러나 AOP audit (`@PaymentStatusChange` / `@PublishDomainEvent`) 이 no-op 경로에도 적용되는지는 산출물에 명시 없음** — payment_history 중복 row 가능성. 결제 도메인은 audit가 사고 재구성의 단일 소스이므로 중복 row가 들어가면 그 자체로 분석 혼란.
- 더 큰 race: 산출물 §3 mermaid가 `TerminalGuard` 를 `BeginTx` 직후 (= INSERT IGNORE 전) 에 배치. 그러나 `paymentEventRepository.findByOrderId` 로딩과 `INSERT IGNORE` 사이에 다른 consumer 가 같은 메시지를 처리해 DONE 으로 전이시키는 race window 가 존재. row 0 → SkipBiz 흐름이지만, 만약 첫 번째 처리 측의 RDB commit 이 두 번째 측의 findByOrderId 보다 늦으면 두 번째는 isTerminal=false (아직 IN_PROGRESS) 로 보고 INSERT IGNORE 시도 → 1 row (신규) → markPaymentAsDone 호출 시점에는 이미 DONE → done() 의 자기전이 가드 no-op. **상태 정합은 OK 지만 INSERT IGNORE 의 row 1 신호와 실제 비즈니스 진행 사이 의미 불일치**가 발생한다 (dedupe 테이블의 신규 entry 인데도 실제로는 자기전이 no-op). 운영 가시성/사후 분석에서는 dedupe row 가 박혔으니 비즈니스 진행됐다고 가정할 수 있음. **§9 멱등성 전략 표에 이 race window 의 의미를 명시할 필요**.

### 2. 장애 시나리오 ≥ 4 식별 (가용성/회복 경로 분석)

산출물 §9 5개 시나리오는 적절. 다만 추가로 식별해야 할 경로:

- **(f) `transactional.id` fencing 실수**: §11 L3 가 다중 인스턴스 검증 부재를 인정하지만, **현재 docker-compose `hostname: payment-service` 고정** (docker-compose.apps.yml line 30) 과 D4 `${HOSTNAME:local}` 표현식이 정면 충돌. `docker compose --scale payment-service=2` 실행 시 두 컨테이너 모두 HOSTNAME 환경변수 = `payment-service` (compose `hostname:` 가 컨테이너 hostname 을 같은 값으로 설정) → `transactional.id = payment-service-payment-service` 두 인스턴스 동일 → 새 인스턴스 startup 시 직전 인스턴스 producer fence → 진행 중인 트랜잭션이 producer-fenced 로 abort. 이건 단일 인스턴스 환경에서는 영향 0 이지만 **다중 인스턴스 확장 시 fencing 메커니즘이 정반대로 동작** (의도: 인스턴스별 유일 / 실제: 인스턴스별 공유). §11 L3 가 "설계는 보장" 이라 주장하지만 docker-compose 설정상 설계가 안 맞는다. plan 단계 진입 전 (a) compose `hostname:` 제거 + 컨테이너 자동 hostname 사용 또는 (b) `transactional.id = ${spring.application.name}-${INSTANCE_ID:HOSTNAME}` 등 인스턴스 고유 환경변수 명시 도입이 필요.

- **(g) Kafka tx coordinator 의 partition rebalance race**: producer transactional.id 가 fence 된 상태에서 `sendOffsetsToTransaction` 호출이 어떻게 동작하는지 산출물 명시 없음. 같은 group.id 의 다른 consumer 가 partition 잡고 있는 동안 producer 가 offset commit 시도하면 InvalidProducerEpochException. §11 L4 가 `transaction.timeout.ms` 비대칭만 언급하지만, **partition rebalance 와 EOS 의 상호작용**도 plan 단계에서 다뤄야 함.

- **(h) Redis 보상 + RDB rollback 시점 silent 진행**: handleFailed / handleQuarantined 의 `compensateAtomic` 은 RDB tx 내에서 호출되지만 **Redis Lua 부수효과는 RDB rollback 과 무관**. 산출물 §10 직전 SCR 보상 순서 가드 유지 확인이 이를 인지하지만, **EOS 도입 시 Kafka tx + RDB tx 양쪽 abort 의 경우 Redis 보상만 실행된 상태 잔존**. 재배달 시 ALREADY_DONE → markPaymentAsFail 재진행으로 정합 회복은 보장되나, **5회 retry 후 DLQ 영구 격리 시 Redis 보상은 이미 영구 박힘 + RDB는 IN_PROGRESS 영구 체류** 가능. CONFIRM-FLOW.md line 189 의 "L7 cascade 인지" 는 markPaymentAsFail 실패 의 cascade 만 인지하지만, EOS 도입 후 L7 의 트리거 빈도가 변하는지 (Kafka tx coordinator 장애가 retry 5회 안에서 회복 안 될 가능성) 산출물에 평가 없음.

### 3. 재시도 정책 적합성 (DefaultErrorHandler × EOS 상호작용)

산출물 §9 재시도 정책 표 + §11 L4 가 시간 명시.

도메인 위험:
- `DefaultErrorHandler` 의 not-retryable 화이트리스트에 `IllegalArgumentException` / `IllegalStateException` 포함. **`PaymentStatusException` 의 부모는 무엇인가?**
- 코드 확인: `PaymentEvent.done()` 이 IN_PROGRESS/RETRYING 아닌 상태에서 `PaymentStatusException.of(INVALID_STATUS_TO_SUCCESS)` throw. `PaymentStatusException` 이 `IllegalStateException` 상속인지 확인 필요. 만약 IllegalStateException 상속이면 → **즉시 DLQ + Kafka tx abort**. 같은 메시지 재배달 안 됨 → markIfAbsent 박혔는데 비즈니스 미진행 + 발행 안 됨 → 운영자 수동 처리 외 회복 경로 없음.
- 산출물 §3 mermaid 의 `TerminalGuard` 는 isTerminal 만 가드 — QUARANTINED 는 isTerminal=false 라 가드 통과. 그러나 markPaymentAsDone(QUARANTINED 상태) → `done()` → INVALID_STATUS_TO_SUCCESS PaymentStatusException → not-retryable → DLQ. **결제가 QUARANTINED 인데 늦게 APPROVED 메시지가 도착하는 경로 (예: pg-service 측 DLQ replay 후 정상 결과 도착)** 에서 이 분기가 trigger 가능.
- 산출물의 가드 (`isTerminal` 만 체크) 는 QUARANTINED 도착 시점에 이 경로를 막지 못함. plan 단계에서 가드 의미를 명확히 정의해야 함: (a) `isTerminal` 만 — QUARANTINED 늦은 APPROVED 는 PaymentStatusException → DLQ 라는 결을 수용, (b) 또는 `isCompensatableByFailureHandler` 부재 (status가 READY/IN_PROGRESS/RETRYING 아닌 모든 경우) 를 가드로 사용. **산출물에는 둘 중 어느 의미인지 명시 없음**.

### 4. stock_outbox 삭제의 안전성 (발행 보장 모델 대체)

산출물 §6 17 단위 삭제 명시, §4 D3 가용성 트레이드오프 수용. 핵심 검토:

- **multi-product idempotencyKey 도출 로직 명시 부재 (CRITICAL)**: 현재 `StockOutboxFactory.buildStockCommitOutbox` (StockOutboxFactory.java line 41~66) 가 `StockEventUuidDeriver.derive(orderId, productId, "stock-commit")` 로 productId 별 고유 idempotencyKey 를 도출하고, 이를 `StockCommittedEvent.idempotencyKey` 필드에 박는다. product-service `StockCommitUseCase` 가 이 키를 `stock_commit_dedupe` 테이블의 PK 로 사용해 multi-product 결제의 중복 차감을 차단한다. **산출물 §6 은 `StockOutboxFactory` 를 삭제 대상으로 명시했지만 `StockEventUuidDeriver` 의 처리는 명시하지 않았고, 새 use case 의 `producer.send(stock-committed)` 직접 호출 코드가 어떻게 multi-product 별 분리 발행 + idempotencyKey 도출을 유지할지 책임 위치 결정 없음**.
- 산출물 §3 mermaid 는 `StockSend["재고 확정 발행<br/>(producer.send)"]` 1단계로 추상화 → multi-product 인 경우 N 회 발행 한 Kafka tx 안에서 묶여야 atomic 보장. for-loop 안에서 `producer.send` N회 호출 후 한 번에 commit 이 EOS 의 의도. 산출물에 이 의도 명시 없음.
- **product-service 측 silent 회귀 경로**: 빅뱅 PR 에서 idempotencyKey 도출이 깨지면 (예: 다른 derivation prefix 사용, 또는 같은 orderId 의 모든 productId 가 같은 key 박힘) product RDB 측 dedupe 가 첫 메시지만 차감 + 나머지 skip → multi-product 결제의 일부 상품만 RDB 차감되는 silent loss. 이는 plan 단계 진입 전 명확히 못박아야 할 핵심 가드.
- silent loss 가 outbox → EOS 전환 자체에서 생기는 게 아니라 **outbox 제거 시 같이 사라지는 StockOutboxFactory / StockEventUuidDeriver 의 책임 이전** 에서 생긴다. §5 hexagonal layer 배치 표에 이 책임의 새 위치 명시 부재.

### 5. 다중 발행 (loop) 의 트랜잭션 묶음

산출물 §3 변경 후 흐름이 `handleApproved` 의 multi-product for-loop 을 한 Kafka tx 에 묶는 의도는 자연스럽게 EOS 모델로 보장된다 (Spring `KafkaTransactionManager` 가 메시지 단위 tx 경계 — listener 진입 ~ 종료까지). 그러나:

- **plan 단계 명시 필요**: `KafkaTransactionManager` wire-in 위치 (산출물 §5 가 "kafkaListenerContainerFactory" 라 명시) 가 한 메시지 처리 = 한 producer transaction = 한 RDB transaction 경계로 묶이는지 architect 가 plan 에서 코드 시그니처 1:1 매핑.
- 위 (4) 의 multi-product 분리 발행이 같은 tx 안이라는 의도는 산출물에 직접 명시 없음 — §3 mermaid 가 `StockSend` 1박스로 추상화돼 plan 단계에서 architect 가 loop 의도를 못 읽을 위험.

### 6. 보상 순서 가드 (SCR 결정 유지 + EOS 와의 직교성)

산출물 §10 직전 SCR 보상 순서 가드 유지 확인 + §11 L1 가 가용성 한계 등재. 검토:

- 산출물 본문은 보상 → RDB 순서 유지를 명시하지만, **§3 분기 flowchart (line 206~226)** 의 FAILED / QUARANTINED 분기는 `Commit` 으로 직접 연결 — 보상이 RDB 와 한 tx 안에서 처리되는 의도가 시각화돼 있지 않음. handleFailed 내 `compensateAtomic` 은 Redis Lua 호출이고 RDB tx 와 무관한 외부 자원. EOS 도입 후에도 그대로 같은 위치라면 산출물의 도식이 의도를 충실히 표현하지 않음 — verify 단계 mermaid 갱신 누락 시 향후 분석 혼란.
- L7 cascade 영향: SCR 직전 토픽이 markPaymentAsFail 영구 실패 → DLQ → Reconciler resetToReady → 새 confirm 사이클 → PG 멱등성으로 일반 차단되나 이론적 가능성은 인정. **EOS 도입 후 markPaymentAsFail 영구 실패의 트리거 가능성은 변하는가**? Kafka tx coordinator 일시 장애 (L1) 가 발생하면 5회 retry 가 모두 실패 → DLQ. 이 경로가 SCR 시점 (no EOS) 대비 빈도가 어떻게 변하는지 산출물 평가 없음.

### 7. 빅뱅 PR 의 도메인 리스크 (부분 실패 / 롤백 시 깨지는 것)

D2 빅뱅 결정의 근거 "병행 운영하려면 양쪽 경로가 한동안 같이 발행 → 중복 발행 폭주 위험" 은 타당. 그러나 빅뱅 자체의 도메인 리스크:

- **PR 머지 직후 운영 사고 시 롤백 비용**: §6 17 단위 삭제 (테이블 drop 포함). PR 머지 후 30분 안에 운영 사고 발견 시 롤백하려면 Flyway V3 (stock_outbox 테이블 drop) 의 down migration 이 필요한데, Flyway 는 down migration 미지원이 기본. **롤백 절차 부재가 산출물에 명시 없음**.
- §11 L1~L4 가 한계 명시하지만 **"PR 머지 후 X 시간 안에 시스템이 회복할 수 없는 상태에 빠질 트리거"** 를 식별하지 않음. 예: Kafka tx coordinator 가 머지 직후 일시 장애 → 모든 결제 결과 처리 정지 → 운영자 인지 → outbox 모델로 회귀 (불가, 코드/테이블 둘 다 삭제됨). 학습용 프로젝트라 운영 비용이 낮다는 사용자 판단이지만 **빅뱅 트레이드오프의 비대칭 (이득은 일관성, 비용은 회복 불가) 명시는 plan 단계에서 architect 가 보강 필요**.
- product-service `application.yml` 의 `isolation.level=read_committed` 적용이 PR 의 atomic 단위에 포함됨. PR 머지 순서가 (1) product-service 먼저 (2) payment-service 나중 이어야 EOS abort 메시지 invisible 보장 — Kafka topic level 발행/소비 순서. 단일 PR 이라도 머지 후 deploy 순서가 의도대로 안 가면 짧은 윈도우 (payment 가 EOS 발행 시작 + product 가 read_uncommitted 아직 유지) 에 abort 메시지 가시화 → spurious 재고 차감. **deploy 순서 명시 필요** — 산출물 §2 / §4 D6 에 deploy 순서 시퀀스 명시 부재.

## Findings

| ID | Severity | 위치 | 카테고리 | Finding |
|---|---|---|---|---|
| DR-1 | critical | PAYMENT-EOS-TRANSITION.md:§3 mermaid (line 206~226), §5 hexagonal layer 배치, §6 삭제 대상 | 멱등성·발행 보장 | multi-product 결제의 stock-committed 메시지별 `idempotencyKey` 도출 (현재 `StockEventUuidDeriver.derive(orderId, productId, "stock-commit")`) 의 새 책임 위치가 산출물에 명시되지 않음. §6 가 `StockOutboxFactory` 를 삭제 대상으로 명시하지만 `StockEventUuidDeriver` 는 언급 없음. EOS 직접 `producer.send` 로 교체 시 이 derivation 이 깨지거나 누락되면 product-service `stock_commit_dedupe` 가 multi-product 결제의 첫 메시지만 차감 + 나머지 skip → silent 재고 부족 사고. 빅뱅 PR 의 가장 큰 도메인 회귀 위험. |
| DR-2 | high | PAYMENT-EOS-TRANSITION.md:§4 D4 (line 267~280), §11 L3 (line 573~577) | 가용성·일관성 | D4 `transactional.id = ${spring.application.name}-${HOSTNAME:local}` 결정이 현재 `docker/docker-compose.apps.yml` line 30 의 `hostname: payment-service` 고정 설정과 정면 충돌. `--scale payment-service=N` 시 모든 컨테이너의 HOSTNAME 환경변수가 동일 → transactional.id 인스턴스별 공유 → fencing 메커니즘이 의도 정반대로 동작 (인스턴스 새로 뜰 때마다 직전 인스턴스 producer fence). §11 L3 가 "설계는 보장" 이라 주장하지만 compose 설정이 설계와 불일치. plan 단계 진입 전 compose hostname 정책 수정 또는 INSTANCE_ID 환경변수 명시 도입 필요. |
| DR-3 | high | PAYMENT-EOS-TRANSITION.md:§3 mermaid TerminalGuard (line 210~212), §4 D2 항목 5 (line 248) | 상태 전이 정합 | "handle 진입에 isTerminal 가드 추가" 의 의미가 모호. QUARANTINED 는 `isTerminal()=false` (PaymentEventStatus.java line 24) 이므로 가드 통과 → markIfAbsent 신규 entry → APPROVED 분기에서 `markPaymentAsDone` 호출 → 도메인 `done()` (PaymentEvent.java line 107~110) 가 status≠IN_PROGRESS/RETRYING/DONE 이면 `PaymentStatusException.of(INVALID_STATUS_TO_SUCCESS)` throw → not-retryable (산출물 §9 재시도 정책) → 즉시 DLQ + Kafka tx abort + RDB rollback. 결제가 QUARANTINED 상태에서 늦은 APPROVED 도착하는 경로 (pg-service DLQ replay 후 정상 결과 도착 등) 가 silent DLQ 분기로 빠진다. plan 단계에서 가드 의미를 (a) `isTerminal` 만 / (b) `isCompensatableByFailureHandler` 부재 (READY/IN_PROGRESS/RETRYING 만 진행) / (c) `isTerminal` + QUARANTINED 명시 처리 중 결정 명확화 필요. |
| DR-4 | high | PAYMENT-EOS-TRANSITION.md:§4 D6 (line 319~328), §2 영향 모듈 (line 124) | 다운스트림 일관성·deploy 순서 | EOS 발행 (payment) 과 `isolation.level=read_committed` (product) 적용이 단일 PR 의 atomic 단위로 결정됐지만 deploy 순서가 명시 없음. PR 머지 후 product-service 가 먼저 재시작되어 read_committed 적용 + payment-service 가 늦게 재시작되는 순서가 보장돼야 abort 메시지 spurious 가시화 (= 재고 부정확 차감) 방지. 반대 순서로 deploy 되면 짧은 윈도우에 정확히 EOS abort 시나리오의 spurious 효과 발생. 빅뱅 PR 결정과 deploy 순서 의무는 분리된 결정 — plan 단계 acceptance criteria 에 명시 필요. |
| DR-5 | medium | PAYMENT-EOS-TRANSITION.md:§3 변경 후 흐름 (line 138~226), §9 멱등성 전략 (line 467~472) | 멱등성 의미·운영 가시성 | INSERT IGNORE 1 row (= 신규) 신호와 실제 비즈니스 진행 사이 의미 불일치 race window 존재. partition rebalance 짧은 윈도우에 두 consumer 가 같은 메시지 동시 처리 시 한쪽이 RDB commit (status → DONE) 완료한 후 다른 쪽이 findByOrderId (아직 IN_PROGRESS 로 봄) + INSERT IGNORE (신규 1 row, 다른 PK) + markPaymentAsDone (`done()` 자기전이 가드 no-op) + stock-committed 발행. dedupe row 박혔는데 비즈니스는 noop. 운영자가 dedupe row count 로 처리량 추적할 때 의미 혼선. §9 표에 이 race window 의미 명시 + cleanup 스케줄러 (TC-11) 의 SLO 계산 시 이 잡음 고려 명시 필요. |
| DR-6 | medium | PAYMENT-EOS-TRANSITION.md:§11 L1 (line 557~565), §4 D3 (line 259~265) | 운영 회복 가능성 | 빅뱅 PR 의 회복 비대칭 명시 부재. 머지 후 운영 사고 발견 시 Flyway V3 (stock_outbox drop) 의 down migration 없음 → 코드 revert 만으로는 outbox 모델 회귀 불가. PR 머지 후 X 시간 안에 회복 불가능한 상태에 빠질 트리거 (Kafka tx coordinator 일시 장애 + 5회 retry 한도 내 회복 안 됨 등) 미식별. 학습용 프로젝트라 운영 비용 낮다는 사용자 판단이라도 비대칭 트레이드오프 명시 + 머지 직후 운영 모니터링 SLO (메트릭 기준 무엇을 기준으로 회귀 판정할지) plan 단계에서 명시 필요. |
| DR-7 | medium | PAYMENT-EOS-TRANSITION.md:§3 변경 후 흐름 + §10 보상 순서 (line 530~537) | 보상 silent 잔존 | EOS 도입 후 handleFailed/handleQuarantined 의 `compensateAtomic` (Redis Lua) 가 RDB tx + Kafka tx 양쪽 abort 시점에도 Redis 효과 잔존. 재배달 시 ALREADY_DONE → markPaymentAsFail 재진행으로 정합 회복은 §9 가 보장하지만, **5회 retry 후 DLQ 영구 격리 시 Redis 보상 영구 박힘 + RDB IN_PROGRESS 영구 체류 + DLQ 격리** 의 cascade 가능. PITFALLS #18 (L7) 가 이미 이 cascade 의 trigger 인지하지만, EOS 도입으로 markPaymentAsFail 영구 실패의 빈도가 변하는지 평가 없음. plan 단계에서 SCR L7 한계가 EOS 도입으로 어떻게 변하는지 (감소 / 무변 / 증가) 평가 명시 필요. |
| DR-8 | minor | PAYMENT-EOS-TRANSITION.md:§5 hexagonal layer 배치 표 (line 336) | 명명 충돌 가능성 | `EventDedupeStore` port 이름 재사용 결정. SCR 직전 토픽에서 동명 port (lease 기반 two-phase) 폐기 → 본 토픽에서 동명 port (INSERT IGNORE one-phase) 신설. 시그니처 다르다 명시했지만 동명 재사용은 archive 토픽 추적성 저하 + git blame 분석 시 헷갈림. plan 단계에서 (a) 동명 재사용 (현재 안) / (b) `PaymentEventDedupeStore` 등 다른 이름 중 결정 명시 — pg-service `EventDedupeStore` 도 동명이라 4서비스 통합 시 혼란 가능. |

## JSON

```json
{
  "round": 1,
  "persona": "domain-expert",
  "topic": "PAYMENT-EOS-TRANSITION",
  "decision": "fail",
  "gate_results": [
    {"item": "scope - TOPIC UPPER-KEBAB-CASE", "status": "yes", "evidence": "PAYMENT-EOS-TRANSITION 형식 충족"},
    {"item": "scope - 모듈/패키지 경계 명시", "status": "yes", "evidence": "§2 영향 모듈/패키지 (line 109~125)"},
    {"item": "scope - non-goals ≥ 1", "status": "yes", "evidence": "§2 Non-goals (line 128~134) 5항목"},
    {"item": "scope - 범위 밖 이슈 TODOS 위임", "status": "yes", "evidence": "§11 후속 작업 목록 (line 587~596) TC-11 / FLYWAY-USER-SEED-GAP / T4-D"},
    {"item": "design - hexagonal layer 배치 명시", "status": "yes", "evidence": "§5 layer 배치 표 (line 334~342)"},
    {"item": "design - port 인터페이스 위치", "status": "yes", "evidence": "§5 EventDedupeStore=application/port/out + JdbcEventDedupeStore=infrastructure/dedupe"},
    {"item": "design - 상태 전이 다이어그램", "status": "n/a", "evidence": "새 상태 없음. interview line 99 에서 확인"},
    {"item": "design - 전체 결제 흐름 호환성 검토", "status": "partial", "evidence": "§10 CONFIRM-FLOW.md 정합 (line 499~552) 작성됐지만 boundary case (multi-product idempotencyKey 책임 이전, QUARANTINED 늦은 APPROVED) 미커버"},
    {"item": "acceptance - 관찰 가능 성공 조건", "status": "yes", "evidence": "§7 line 402~412 6항목"},
    {"item": "acceptance - 실패 관찰 방식", "status": "yes", "evidence": "§7 line 414~418 테스트/로그/지표"},
    {"item": "verification - 테스트 계층 결정", "status": "yes", "evidence": "§8 line 422~459 단위 + 통합 (Testcontainers Kafka + MySQL), k6 제외"},
    {"item": "verification - 벤치마크 지표", "status": "n/a", "evidence": "k6 본 토픽 제외 (Phase 5 T4-D)"},
    {"item": "artifact - 결정 사항 섹션", "status": "yes", "evidence": "§4 D1~D6"},
    {"item": "domain risk - 멱등성 전략 결정", "status": "yes", "evidence": "§9 멱등성 전략 표 line 467~472"},
    {"item": "domain risk - 장애 시나리오 ≥ 3", "status": "yes", "evidence": "§9 5개 (a~e). 다만 (f) docker-compose hostname race 미식별 — DR-2"},
    {"item": "domain risk - 재시도 정책", "status": "yes", "evidence": "§9 line 485~491. 다만 PaymentStatusException → IllegalStateException 매핑 / 즉시 DLQ 결 분석 부족 — DR-3"},
    {"item": "domain risk - PII", "status": "yes", "evidence": "§9 line 494~495 — 새 PII 없음"}
  ],
  "fail_items": [
    {"item": "multi-product idempotencyKey 도출 책임 이전 미명시", "location": "PAYMENT-EOS-TRANSITION.md:§3 mermaid line 219, §5 layer 배치, §6 line 354~362", "issue": "DR-1 — StockOutboxFactory 삭제 대상이지만 StockEventUuidDeriver 도출 로직의 새 책임 위치 명시 없음. 빅뱅 PR 머지 시 product-service stock_commit_dedupe 회귀로 multi-product 결제의 일부 상품만 차감되는 silent 사고 위험.", "suggestion": "§5 layer 배치 표에 StockEventUuidDeriver 유지 명시 + 새 use case 코드의 producer.send(stock-committed) 호출 시 multi-product for-loop 안에서 idempotencyKey 도출 + 같은 Kafka tx 안에서 N회 send 의도 명시. §3 mermaid 의 StockSend 박스를 multi-product loop 으로 풀어내고, §6 삭제 대상 표에 StockEventUuidDeriver 는 유지 (또는 신규 위치) 라고 explicit 명시. Acceptance criteria 에 multi-product 결제 통합 테스트 추가 (productId 2개 결제의 stock_commit_dedupe 두 row 모두 박힘 + product RDB 두 상품 모두 차감)."},
    {"item": "transactional.id 정책 ↔ docker-compose hostname 충돌", "location": "PAYMENT-EOS-TRANSITION.md:§4 D4 line 267~280, §11 L3 line 573~577, docker/docker-compose.apps.yml:30", "issue": "DR-2 — D4 의 HOSTNAME 기반 fencing 의도가 현재 compose hostname:payment-service 고정 설정과 충돌. 다중 인스턴스 확장 시 fencing 메커니즘이 의도 정반대 (인스턴스 추가될수록 직전 producer fence). §11 L3 가 인지하지만 설계 자체의 결함 명시 부재.", "suggestion": "본 토픽 plan 단계에서 (a) compose hostname 제거 + docker 자동 hostname 또는 (b) INSTANCE_ID 환경변수 (compose 의 `{{.Task.Slot}}` 또는 init script 의 pod uid) 도입 결정. D4 본문에 docker-compose 설정 수정 또는 별 토픽 deferred 결정 명시. §2 영향 모듈 표에 docker/docker-compose.apps.yml 포함 (현재 누락)."},
    {"item": "handle 진입 isTerminal 가드 의미 모호 + QUARANTINED 늦은 APPROVED silent DLQ", "location": "PAYMENT-EOS-TRANSITION.md:§3 mermaid TerminalGuard line 210, §4 D2 line 248", "issue": "DR-3 — isTerminal 가드는 QUARANTINED 통과. 결제 QUARANTINED 상태에서 늦은 APPROVED 도착 시 markPaymentAsDone → PaymentStatusException (IllegalStateException 상속 확인 필요) → not-retryable → 즉시 DLQ + Kafka tx abort. 운영자 수동 처리 외 회복 경로 없음.", "suggestion": "§4 D2 항목 5 의 가드 의미를 명확화: (a) isTerminal 만 — QUARANTINED 늦은 APPROVED 는 DLQ 라는 결을 명시적 수용 (DLQ replay 정책 명시) / (b) isCompensatableByFailureHandler 부재 (READY/IN_PROGRESS/RETRYING 만 진행) / (c) isTerminal + QUARANTINED 명시 분기. PaymentStatusException 의 부모 클래스 확인 + DefaultErrorHandler not-retryable 결과 분석 추가. §9 장애 시나리오 (f) 로 등재."},
    {"item": "EOS 발행 ↔ read_committed 적용의 deploy 순서 명시 부재", "location": "PAYMENT-EOS-TRANSITION.md:§4 D6 line 319~328, §2 line 124", "issue": "DR-4 — payment-service EOS 발행 시작과 product-service read_committed 적용의 deploy 순서가 명시 없음. 반대 순서 deploy 시 짧은 윈도우에 spurious 재고 차감.", "suggestion": "§7 acceptance criteria 에 deploy 순서 의무 명시: (1) product-service deploy 후 isolation.level=read_committed 적용 확인 → (2) payment-service deploy. 또는 (1) 컨슈머 측 yaml 변경만 먼저 별 PR + (2) producer EOS 변경을 본 PR 로 분리. 빅뱅 결정 D2 의 trade-off 재검토 (deploy 순서 보장 책임 운영자에게 위임 vs PR 분리)."}
  ],
  "domain_risks": [
    {"id": "DR-1", "title": "multi-product idempotencyKey 도출 책임 이전 미명시 — silent 재고 사고 위험", "severity": "high", "evidence": "PAYMENT-EOS-TRANSITION.md:§6 line 354~362 (StockOutboxFactory 삭제), §3 mermaid StockSend (line 219) 1박스 추상화. 실제 코드: payment-service/.../application/util/StockOutboxFactory.java line 41~66 (idempotencyKey = StockEventUuidDeriver.derive(orderId, productId, 'stock-commit')) + StockEventUuidDeriver.java line 14~17 (multi-product 보장 javadoc)", "mitigation": "§5 layer 배치 표에 StockEventUuidDeriver 유지 명시 + §3 mermaid 의 StockSend 박스를 multi-product loop 으로 풀어 설명 + §6 삭제 대상 표 explicit 등재 (StockEventUuidDeriver 유지) + §7 acceptance criteria 에 multi-product 결제 통합 테스트 추가 (productId 2개의 stock_commit_dedupe 두 row 박힘 검증)"},
    {"id": "DR-2", "title": "transactional.id fencing 의도 ↔ docker-compose hostname 정면 충돌", "severity": "high", "evidence": "PAYMENT-EOS-TRANSITION.md:§4 D4 line 267~280 (HOSTNAME 환경변수 사용), §11 L3 line 573~577 (다중 인스턴스 검증 부재). docker/docker-compose.apps.yml:30 (hostname: payment-service 고정)", "mitigation": "plan 단계에서 (a) compose hostname 제거 + docker 자동 hostname 사용, 또는 (b) INSTANCE_ID 환경변수 (compose `{{.Task.Slot}}` / pod uid) 명시 도입. D4 본문에 compose 수정 또는 deferred 결정 명시. §2 영향 모듈 표에 docker-compose.apps.yml 포함"},
    {"id": "DR-3", "title": "isTerminal 가드 모호 — QUARANTINED 늦은 APPROVED 가 silent DLQ 분기로 빠짐", "severity": "high", "evidence": "PAYMENT-EOS-TRANSITION.md:§3 mermaid TerminalGuard line 210, §4 D2 line 248. PaymentEventStatus.java line 24 (QUARANTINED isTerminal=false). PaymentEvent.java line 107~110 (done() 의 INVALID_STATUS_TO_SUCCESS PaymentStatusException)", "mitigation": "§4 D2 가드 의미 명확화 (isTerminal only / isCompensatableByFailureHandler 부재 / QUARANTINED 명시 분기 중 결정). §9 장애 시나리오 (f) 등재. plan 단계에서 PaymentStatusException → IllegalStateException 매핑 확인 후 DLQ 분기 의도 또는 회복 경로 결정"},
    {"id": "DR-4", "title": "EOS 발행 ↔ read_committed 적용의 deploy 순서 명시 부재", "severity": "high", "evidence": "PAYMENT-EOS-TRANSITION.md:§4 D6 line 319~328 (단일 PR 결정), §2 line 124 (product-service application.yml 영향). 빅뱅 PR 결정 D2 line 240~257", "mitigation": "§7 acceptance criteria 에 deploy 순서 의무 명시 또는 D6 를 별 선행 PR (consumer 측 yaml 만) + 본 PR (producer 측 EOS) 분리 결정. plan 단계에서 결정 명시"},
    {"id": "DR-5", "title": "INSERT IGNORE row 신호와 비즈니스 진행 의미 불일치 race", "severity": "medium", "evidence": "PAYMENT-EOS-TRANSITION.md:§3 mermaid line 206~226. PaymentEvent.java line 104~106 (done() DONE 자기전이 no-op)", "mitigation": "§9 멱등성 전략 표에 race window 의미 명시 (dedupe row 박혔어도 자기전이 no-op 가능). TC-11 cleanup 스케줄러 SLO 계산 시 잡음 고려 명시"},
    {"id": "DR-6", "title": "빅뱅 PR 의 회복 비대칭 명시 부재", "severity": "medium", "evidence": "PAYMENT-EOS-TRANSITION.md:§11 L1 line 557~565, §4 D3 line 259~265. Flyway V3 down migration 부재 인정 없음", "mitigation": "§11 에 회복 비대칭 명시 (코드 revert 만으로는 outbox 모델 회귀 불가 + Flyway down migration 부재). 머지 직후 운영 모니터링 SLO 명시 (회귀 판정 메트릭)"},
    {"id": "DR-7", "title": "EOS 도입이 SCR L7 markPaymentAsFail 영구 실패 cascade 에 미치는 영향 평가 없음", "severity": "medium", "evidence": "PAYMENT-EOS-TRANSITION.md:§10 line 530~537 (SCR 결정 유지), §11 L1 line 557~565 (가용성 한계). PITFALLS.md #18 L7", "mitigation": "§10 또는 §11 에 EOS 도입 후 markPaymentAsFail 영구 실패 빈도 평가 (Kafka tx coordinator 일시 장애가 retry 5회 안에 회복 안 될 시나리오) 명시. cascade 트리거 빈도 감소/무변/증가 중 결정"},
    {"id": "DR-8", "title": "EventDedupeStore 동명 재사용으로 archive 추적성 저하", "severity": "low", "evidence": "PAYMENT-EOS-TRANSITION.md:§5 line 336 (SCR 폐기한 동명 port 재사용). pg-service 측에도 동명 port 존재", "mitigation": "plan 단계에서 동명 재사용 / PaymentEventDedupeStore 등 분리 명명 중 결정. 4서비스 통합 시 혼란 가능성 고려"}
  ],
  "auxiliary_scores": {
    "structure_clarity": 0.85,
    "wiki_code_alignment_explicit": 0.7,
    "rollback_path_visibility": 0.3,
    "downstream_impact_coverage": 0.6,
    "risk_enumeration_completeness": 0.55
  }
}
```
