# Confirm Flow — Mermaid Flowchart

> 최종 갱신: 2026-04-27 (post-MSA + PRE-PHASE-4-HARDENING 봉인)
> 짝 문서: [`CONFIRM-FLOW-ANALYSIS.md`](CONFIRM-FLOW-ANALYSIS.md), 전체 end-to-end 는 [`PAYMENT-FLOW.md`](PAYMENT-FLOW.md)

본 문서는 payment-service 측 비동기 confirm 사이클의 시각화. PG 측 흐름은 `PAYMENT-FLOW.md` Phase 4 절.

## 1. 상태 머신

### PaymentEventStatus

```mermaid
stateDiagram-v2
    [*] --> READY : checkout 완료

    READY --> IN_PROGRESS : confirm TX 커밋
    READY --> EXPIRED : 만료 스케줄러
    READY --> RETRYING : 복구 사이클

    IN_PROGRESS --> DONE : APPROVED 수신
    IN_PROGRESS --> FAILED : FAILED 수신
    IN_PROGRESS --> RETRYING : 재시도 결정
    IN_PROGRESS --> QUARANTINED : 판단 불가 / AMOUNT_MISMATCH

    RETRYING --> DONE : APPROVED 수신
    RETRYING --> FAILED : FAILED 수신
    RETRYING --> RETRYING : 한도 미소진
    RETRYING --> QUARANTINED : 한도 소진 + FCG 판단 불가

    DONE --> [*]
    FAILED --> [*]
    EXPIRED --> [*]
    CANCELED --> [*]
    PARTIAL_CANCELED --> [*]
    QUARANTINED --> [*]
```

### PaymentOutboxStatus

```mermaid
stateDiagram-v2
    [*] --> PENDING : confirm TX 내 INSERT

    PENDING --> IN_FLIGHT : claimToInFlight (atomic CAS)
    IN_FLIGHT --> DONE : Kafka 발행 성공
    IN_FLIGHT --> FAILED : 재시도 한도 초과 또는 영구 실패
    IN_FLIGHT --> PENDING : inFlightAt 타임아웃 회수 (워커 크래시 회복)
    PENDING --> PENDING : 재시도 (nextRetryAt 갱신)

    DONE --> [*]
    FAILED --> [*]
```

## 2. confirm 진입 — `OutboxAsyncConfirmService.confirm()`

```mermaid
flowchart TD
    A([Controller: POST /confirm]) --> B[getPaymentEventByOrderId]
    B --> LVAL[validateConfirmRequest<br/>userId/amount/orderId/paymentKey]
    LVAL -->|PaymentValidException| FAIL_4XX([4xx throw])
    LVAL --> DECR[decrementStock<br/>Redis 원자 DECR — TX 외부]

    DECR -->|REJECTED| RJ[handleStockFailure<br/>event=FAILED]
    DECR -->|CACHE_DOWN| CD[markStockCacheDownQuarantine<br/>event=QUARANTINED<br/>quarantine_compensation_pending=true]
    DECR -->|SUCCESS| TX[executeConfirmTxWithStockCompensation]

    RJ --> FAIL_409([409 throw])
    CD --> FAIL_409

    TX --> TX_INNER[ @Transactional:<br/>event READY → IN_PROGRESS<br/>paymentKey 기록<br/>payment_outbox PENDING INSERT ]
    TX_INNER -->|예외| COMP[Redis INCR 보상<br/>compensateStock]
    COMP --> RETHROW([txException re-throw])
    TX_INNER -->|성공| PUB[ApplicationEventPublisher<br/>publishEvent PaymentConfirmEvent]
    PUB --> RESP([202 Accepted])
```

## 3. AFTER_COMMIT 즉시 발행 — `OutboxImmediateEventHandler` + `OutboxRelayService`

```mermaid
flowchart TD
    EV([@TransactionalEventListener AFTER_COMMIT<br/>+ @Async outboxRelayExecutor VT]) --> RELAY[OutboxRelayService.relay orderId]

    RELAY --> CL[Step 1: claimToInFlight<br/>atomic UPDATE PENDING → IN_FLIGHT<br/>REQUIRES_NEW]
    CL -->|선점 실패| SKIP([no-op return])
    CL -->|선점 성공| LOAD[Step 2: outbox + paymentEvent 조회]

    LOAD --> SEND[Step 3: KafkaMessagePublisher.send<br/>topic=payment.commands.confirm<br/>payload=PaymentConfirmCommandMessage]

    SEND -->|발행 실패| HOLD[IN_FLIGHT 유지<br/>OutboxWorker 폴백 재시도]
    SEND -->|성공| DONE[Step 4: outbox.toDone save<br/>IN_FLIGHT → DONE]

    DONE --> END([완료])
    HOLD --> END
```

## 4. 폴링 폴백 — `OutboxWorker`

```mermaid
flowchart TD
    S([@Scheduled fixedDelay]) --> R0[Step 0: recoverTimedOutInFlightRecords<br/>inFlightAt 기준 N분 초과 → PENDING 복귀]
    R0 --> R1[Step 1: findPendingBatch batchSize<br/>기본 50건]
    R1 -->|배치 없음| END([no-op])
    R1 --> LOOP[배치 순회]
    LOOP --> RELAY[OutboxRelayService.relay<br/>위 3번 다이어그램과 동일]
    RELAY --> END
```

## 5. 결과 수신 — `ConfirmedEventConsumer` + `PaymentConfirmResultUseCase`

```mermaid
flowchart TD
    KC([@KafkaListener payment.events.confirmed<br/>groupId=payment-service]) --> UC[PaymentConfirmResultUseCase.handle]

    UC --> MARK[markWithLease<br/>eventUuid, leaseTtl=PT5M<br/>SET NX EX]
    MARK -->|false 이미 처리 중| SKIP([no-op return])
    MARK -->|true 권한 획득| LOAD[paymentEvent 조회]

    LOAD --> SW{message.status}

    SW -->|APPROVED| AMT[parseApprovedAt<br/>+ isAmountMismatch 검사]
    AMT -->|불일치| QU_AM[stockCachePort.increment 보상<br/>+ QuarantineCompensationHandler<br/>reason=AMOUNT_MISMATCH]
    AMT -->|일치| DONE_OK[markPaymentAsDone approvedAt<br/>각 PaymentOrder 별<br/>stock_outbox INSERT + StockOutboxReadyEvent publish]

    SW -->|FAILED| FAIL_OK[markPaymentAsFail reason<br/>각 PaymentOrder 별<br/>stockCachePort.increment 보상]

    SW -->|QUARANTINED| QU_PG[stockCachePort.increment 보상<br/>+ QuarantineCompensationHandler<br/>reason=PG_QUARANTINED]

    DONE_OK --> EXT[extendLease<br/>longTtl=P8D<br/>SET XX EX]
    FAIL_OK --> EXT
    QU_AM --> EXT
    QU_PG --> EXT
    EXT --> END([완료])

    DONE_OK -.실패시.-> RM[remove eventUuid<br/>false → DLQ publish]
    FAIL_OK -.실패시.-> RM
```

## 6. AFTER_COMMIT stock 발행 — `StockOutboxImmediateEventHandler`

APPROVED 결과에서만 발행됨 — FAILED/QUARANTINED 시 stock 발행 X (Redis 보상만).

```mermaid
flowchart TD
    AE([StockOutboxReadyEvent — TX 커밋 직후<br/>@TransactionalEventListener AFTER_COMMIT + @Async outboxRelayExecutor]) --> RELAY[StockOutboxRelayService.relay outboxId]

    RELAY --> CL[claimToInFlight CAS]
    CL -->|선점 실패| SKIP([no-op])
    CL -->|선점 성공| SEND[StockOutboxKafkaPublisher.send<br/>topic=payment.events.stock-committed]

    SEND -->|성공| DONE[stock_outbox.toDone save]
    SEND -->|실패| LOG_FAIL[stock.kafka.publish.fail.total counter +1<br/>processedAt 미기록 → Polling 재시도]
    DONE --> END([완료])
    LOG_FAIL --> END
```

## 7. AMOUNT_MISMATCH 양방향 방어

```mermaid
sequenceDiagram
    participant Vendor as PG Vendor
    participant Pg as pg-service
    participant K as Kafka
    participant Pay as payment-service
    participant Quar as QuarantineCompensationHandler

    Vendor->>Pg: confirm response (amount, approvedAt)
    Pg->>Pg: AmountConverter.fromBigDecimalStrict<br/>scale·음수 검증
    Pg->>K: ConfirmedEventPayload<br/>{amount: Long, approvedAt: ISO-8601}<br/>(APPROVED 시 non-null 강제)
    K->>Pay: ConfirmedEventMessage 수신
    Pay->>Pay: paymentEvent.totalAmount<br/>vs message.amount 대조

    alt 일치
        Pay->>Pay: paymentEvent.done(approvedAt)
        Pay->>K: payment.events.stock-committed publish
    else 불일치 또는 amount=null
        Pay->>Quar: handle(orderId, AMOUNT_MISMATCH)
        Quar->>Pay: paymentEvent.quarantine(reason)
    end
```

## 8. D12 재고 복구 가드 (`executePaymentFailureCompensationWithOutbox`)

```mermaid
flowchart TD
    START[executePaymentFailureCompensationWithOutbox<br/>orderId, orderList, reason] --> RELOAD[① TX 내 DB 재조회<br/>findByOrderId<br/>getPaymentEventByOrderId]

    RELOAD --> CHK_OB{outbox.status<br/>== IN_FLIGHT?}
    CHK_OB -->|아니오 DONE/FAILED| SKIP[재고 복구 skip<br/>warn 로그]
    CHK_OB -->|예| CHK_EV{event.status<br/>비종결?}

    CHK_EV -->|아니오 DONE/FAILED/QUARANTINED| SKIP
    CHK_EV -->|예 READY/IN_PROGRESS/RETRYING| RESTORE[각 PaymentOrder 별<br/>stockCachePort.increment<br/>Redis 보상 ✅]

    RESTORE --> FAIL_OB[outbox.toFailed save]
    SKIP --> CHK_OB2{outbox 가<br/>IN_FLIGHT 였나?}
    CHK_OB2 -->|예| FAIL_OB
    CHK_OB2 -->|아니오| MARK
    FAIL_OB --> MARK[markPaymentAsFail event, reason<br/>이미 종결이면 no-op]
    MARK --> FIN([종료])
```

## 관련 문서

- 진입점·use case 분석: `CONFIRM-FLOW-ANALYSIS.md`
- 전체 end-to-end (브라우저 → 폴링): `PAYMENT-FLOW.md`
- RecoveryDecision / FCG / D12 의 도입 배경: `docs/archive/payment-double-fault-recovery/COMPLETION-BRIEFING.md`
- two-phase lease, AFTER_COMMIT stock 분리, 보강 결정 시리즈: `docs/archive/pre-phase-4-hardening/COMPLETION-BRIEFING.md`
