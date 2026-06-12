# Round 6 — 3 시나리오 audit + 후보 정리

> 사용자가 5라운드 산출물을 검토 후 "현재 후보들이 정확한 해결 방법을 명시하지 않는다" 고 지적. Kafka consumer 신뢰성 시나리오 3가지에 대해 각 후보를 코드 path 단위로 audit 한 결과.

---

## 사용자 제기 시나리오

| # | 시나리오 (한글) | Kafka consumer 의미 | 의심 |
|---|---|---|---|
| 1 | 메시지 수신 실패 | broker outage / network partition / consumer down — 메시지가 consumer 까지 도달 못 함 | 메시지 자체가 사라지면 회복 불가 |
| 2 | 메시지 처리 못 했는데 커밋해버림 | handler 가 INCR 실패 했는데도 ack — silent loss (현 버그) | 보상 실패가 영구 발산 |
| 3 | 메시지는 처리했는데 커밋 직전에 죽음 | INCR 성공 후 extendLease(P8D) 또는 ack 직전 process crash — 5분 후 lease 만료 → Kafka redeliver → INCR 두 번 = silent over-restore | dedupe lease P5M race window |

---

## 현행 코드 동작 — race window 분석

### 핵심 호출 순서 (코드 사실)

`PaymentConfirmResultUseCase.handle(ConfirmedEventMessage)` 의 정확한 호출 순서 (`payment-service/.../application/usecase/PaymentConfirmResultUseCase.java`):

```java
// line 120-131
@Transactional(timeout = 5)
public void handle(ConfirmedEventMessage message) {
    if (!eventDedupeStore.markWithLease(message.eventUuid(), leaseTtl)) {  // line 122 — P5M lease
        return;  // 이미 처리 중 (다른 consumer)
    }
    processMessageWithLeaseGuard(message);  // line 130
}

// line 136-144
private void processMessageWithLeaseGuard(ConfirmedEventMessage message) {
    try {
        processMessage(message);  // line 138 — handleApproved/Failed/Quarantined 분기
        eventDedupeStore.extendLease(message.eventUuid(), longTtl);  // line 139 — P8D 연장
    } catch (RuntimeException e) {
        handleRemoveOnFailure(message.eventUuid(), e);  // dedupe key DELETE 시도
        throw e;  // Kafka ack 거부 → redeliver
    }
}

// line 258-272 (FAILED 분기)
private void handleFailed(PaymentEvent paymentEvent, String reasonCode) {
    if (paymentEvent.getStatus().isTerminal()) { return; }  // line 259
    paymentCommandUseCase.markPaymentAsFail(paymentEvent, reasonCode);  // line 266 — RDB UPDATE
    compensateStockCache(paymentEvent, reasonCode);  // line 268 — Redis INCR 반복
}

// line 304-317 — silent loss 본체
private void compensateStockCache(PaymentEvent paymentEvent, String reasonCode) {
    for (PaymentOrder order : paymentEvent.getPaymentOrderList()) {
        try {
            stockCachePort.increment(order.getProductId(), order.getQuantity());
        } catch (RuntimeException e) {
            LogFmt.error(...);  // swallow — 다음 order 로 진행
        }
    }
}
```

### TX / Kafka ack / Redis 호출 시점 매핑

| t | 사건 | 영향 |
|---|---|---|
| t0 | Kafka consumer poll → `handle(message)` 진입 | TX 시작 (timeout=5s) |
| t1 | `markWithLease(eventUuid, P5M)` Redis SETNX EX 300s | dedupe 키 생성 |
| t2 | `markPaymentAsFail` JPA UPDATE | TX 안 RDB 변경 (아직 커밋 0) |
| t3 | for-loop: `stockCachePort.increment(productId_i, qty_i)` Redis INCR (TX 외부 호출) | Redis 측 즉시 반영. RDB TX 와 무관 |
| t4 | `extendLease(eventUuid, P8D)` Redis SET XX EX 691200s | dedupe 키 TTL P8D 연장 |
| t5 | `handle` 메서드 정상 종료 → TX commit | RDB markPaymentAsFail 영구 반영 |
| t6 | Spring Kafka listener: ack offset commit | 메시지 영구 사라짐 |

**중요**: `extendLease` 가 **TX 커밋 전** (line 139, TX 안). Kafka ack 는 **TX 커밋 후** Spring Kafka 가 자동 처리.

### 시나리오 1 — 메시지 수신 실패

**의미**: Kafka broker outage / network partition / consumer process down — t0 진입 자체가 안 되는 상태.

**현행 동작**:
- 메시지가 consumer 에 도달 못 함 → `handle` 진입 0 → markWithLease 호출 0 → Redis dedupe 키 생성 0
- Kafka retention (7d) 동안 broker 재기동 / network 복구 후 consumer 재시작 시 같은 메시지 자연 재배달
- 단 retention (7d) 초과 시 메시지 영구 손실 — 본 토픽 범위 외 (Non-goal: "메시지 broker 자체 손실")

**race window 위치**: 본 시나리오는 application 코드 진입 전이라 race 0. 회복 trigger 는 Kafka 자체 retention + consumer 재시작.

**모든 후보 동등**: Baseline 0 / A / B / D 모두 Kafka 인프라에 동등 의존 — 후보별 차이 없음. 단 후보의 회복 layer 가 시나리오 1 후 정상 회복 시 제대로 거동하는지는 별개 점검 필요 (= 시나리오 2/3 의 회복 사이클이 작동해야 함).

### 시나리오 2 — 메시지 처리 못 했는데 커밋

**의미**: t3 의 일부 INCR 이 RuntimeException 던졌는데 catch swallow → t4 `extendLease(P8D)` 호출 → t5 TX commit → t6 ack.

**현행 동작 (silent loss 본체)**:
1. t3 의 INCR 실패 → `compensateStockCache` line 308-315 catch 진입 → `LogFmt.error` 만 + `for-loop` 다음 항목 진행
2. for-loop 모두 끝 → `compensateStockCache` 정상 리턴 → `processMessage` 정상 리턴 → `processMessageWithLeaseGuard` line 139 `extendLease(P8D)` 호출
3. t5 TX commit → t6 ack → 메시지 영구 사라짐 → dedupe 키 P8D 잠금 → **재배달 시도해도 markWithLease line 122 false → skip** → 영구 발산

**race window 위치**: 없음 — race 가 아닌 deterministic silent loss. 모든 INCR 실패 시 항상 silent loss 발생.

**sub-case 분기**:
| sub | 시나리오 | 현행 동작 |
|---|---|---|
| 2a | 일부 INCR 실패 (productA 성공 + productB 실패) | productB 만 swallow + productA 는 정상 → 운영자에 productB 만 발산 |
| 2b | 모든 INCR 실패 (Redis 다운) | for-loop 모든 항목 swallow → 결제 N항목 모두 발산 |
| 2c | 첫 시도 실패 후 재시도도 실패 (반복 실패) | 현행은 재시도 layer 0 — 1회 실패가 곧 영구 발산 |
| 2d | outbox INSERT / RDB UPDATE 자체 실패 (RDB 일시 다운) | RDB 다운 시 markPaymentAsFail JPA UPDATE 자체가 RuntimeException → processMessageWithLeaseGuard catch → `eventDedupeStore.remove` → Kafka redeliver. 하지만 이 case 는 보상 실패가 아닌 RDB 다운이라 본 토픽 범위 모호 |

### 시나리오 3 — 메시지는 처리했는데 커밋 직전 죽음

**의미**: t3 INCR 성공한 항목이 있는 상태에서 t4 `extendLease` / t5 TX commit / t6 ack 중 어느 시점에 process crash. P5M lease 만료 후 redeliver → markWithLease 통과 → INCR 두 번.

**현행 동작 race window**:

#### 3a — INCR 성공 후 extendLease 직전 crash

| t | 사건 | 상태 |
|---|---|---|
| t3 | for-loop 모든 INCR 성공 (Redis 측 +N 영구 반영) | Redis 정상 |
| t3.5 | process crash (OOM / kubectl kill / JVM crash) | TX rollback → markPaymentAsFail 무효화 |
| — | 5분 후 P5M lease 만료 | dedupe 키 자연 삭제 |
| — | Kafka 재배달 (같은 eventUuid) | markWithLease line 122 true 통과 → handleFailed 재진입 |
| — | isTerminal 가드 line 259 — TX rollback 으로 status FAILED 가 아님 → 진입 통과 → markPaymentAsFail + **compensateStockCache 재호출** | **모든 INCR 두 번 → silent over-restore 항목 N개 발산** |

#### 3b — extendLease 후 ack 직전 crash

| t | 사건 | 상태 |
|---|---|---|
| t3 | for-loop 모든 INCR 성공 | Redis +N |
| t4 | `extendLease(eventUuid, P8D)` 호출 — Redis SET XX EX 691200s | dedupe 키 P8D |
| t4.5 | TX commit 직전 crash | TX rollback → markPaymentAsFail 무효화 |
| — | 8일 후까지 dedupe 키 살아있음 → 재배달 시도 시 markWithLease false → skip | redeliver 차단됨 |
| — | 하지만 markPaymentAsFail rollback 으로 PaymentEvent.status = IN_PROGRESS 잔존 | Reconciler 가 IN_PROGRESS 픽업 → resetToReady → pg-service 재confirm → 새 eventUuid 의 events.confirmed 수신 → markWithLease 새 키 → handleFailed 재진입 → **compensateStockCache 재호출** → **모든 INCR 두 번** |

#### 3c — TX commit 후 ack 직전 crash

| t | 사건 | 상태 |
|---|---|---|
| t5 | TX commit 완료 (markPaymentAsFail 영구 반영, status=FAILED) | RDB FAILED |
| t5.5 | ack 호출 직전 crash | Kafka offset 미커밋 |
| — | consumer 재시작 → Kafka 재배달 (같은 메시지) → handle 진입 → markWithLease P5M 키 살아있음 → **false → skip** | redeliver 차단됨. 단 markPaymentAsFail 은 이미 commit 됐고 INCR 도 이미 일어났으니 정상 사이클 종결 |

3c 는 silent loss 0. 3a/3b 가 본질 위험.

**핵심 사실**: 현행 코드는 시나리오 3a/3b 에 대한 항목 단위 멱등성 layer 가 0 — INCR 자체가 멱등하지 않고 (`Redis INCRBY` 는 매번 누적), `compensateStockCache` 내부에 dedupe key 가 없다. 보호 layer 는 (i) markWithLease P5M (3c 만 차단) (ii) PaymentEvent.isTerminal 가드 (3a 부분 차단 — TX commit 통과한 case 에 한정) 두 개뿐.

---

## 후보별 시나리오 매핑

### Baseline 0 — RDB 보상 outbox + Lua 처리 토큰 + 5초 워커

**기본 거동 (현재 sketch 그대로)**:
- 시나리오 1: Kafka 인프라 동등 의존. 회복 trigger 는 Kafka retention.
- 시나리오 2: `compensateStockCache` catch 분기에서 outbox INSERT → 워커 5초마다 PENDING 행 픽업 → INCR 재시도. **Lua 토큰은 워커 path 에만 적용 — `outboxId` 기반 SETNX P8D + INCRBY atomic**. 회복 ✓
- 시나리오 3a/3b: **consumer happy path 의 INCR (line 307) 은 plain `stockCachePort.increment` — Lua 토큰 미적용**. process crash 후 P5M 만료 + Kafka redeliver 시 같은 INCR 두 번 가능. **❌ NOT COVERED**.

**enhancement 가능성 — Lua 토큰을 consumer happy path 에도 적용**:
- consumer happy path 의 INCR 호출도 `incrementWithToken(productId, qty, tokenKey="compensation:event:{eventUuid}:{productId}", ttl=P8D)` 로 변경
- 시나리오 3a/3b 모두 ✓ 커버 — 같은 eventUuid 재진입 시 SETNX 실패 → `ALREADY_PROCESSED` no-op
- **사용자 신호 위반**:
  - (b) Lua 사용처 확장 — 1건 (선차감) → 2건 (선차감 + 보상 happy path) **가속**
  - (c) port API 시그니처 변경 — `StockCachePort.increment(productId, qty)` → `incrementWithToken(...)` 가 happy path 에도 도입
- **본 후보 본질**: 이미 Lua + 새 port + 새 워커 받아들인 baseline → enhancement 가 본질 깨지 않음 (단지 Lua 적용 범위 확장)

**판정**: 시나리오 3 커버 가능하지만 사용자 거부 사유 그대로. enhancement 후에도 사용자 신호 4 위반 그대로 + (b)(c) 가속.

### Candidate A — terminal+compensation_status 분리 모델

**기본 거동 (Round 5 흡수안 (b) — handleFailed 진입 즉시 markStockCompensationPending)**:
- 시나리오 1: Kafka 인프라 동등.
- 시나리오 2: handleFailed 진입 즉시 PaymentEvent.compensation_status=PENDING 마킹 (markPaymentAsFail 과 같은 TX) → compensateStockCache 호출 → 일부 실패 → catch 외부 markStockCompensationDone 호출 0 → compensation_status PENDING 잔존 → Reconciler 5초 polling 픽업 → INCR 재시도. ✓
- 시나리오 3a (INCR 성공 후 extendLease 직전 crash):
  - TX rollback → markPaymentAsFail + markStockCompensationPending + Redis INCR 일부 살아있음
  - P5M 만료 → Kafka redeliver → handleFailed 재진입 → isTerminal 가드 false (TX rollback 이라 status≠FAILED) → 진입 통과
  - markStockCompensationPending 재호출 (TX 안) → markPaymentAsFail 재호출 → **compensateStockCache 재호출 → 같은 productId INCR 두 번** (compensated_at 항목 단위 격리 부재)
  - **❌ NOT COVERED** — 본 토픽 §Round 5 비교표의 잔여 위험 DA4-1 그대로
- 시나리오 3b (extendLease 후 TX commit 전 crash):
  - dedupe 키 P8D 살아있음 → redeliver 차단
  - 하지만 markPaymentAsFail rollback 으로 status=IN_PROGRESS 잔존 → Reconciler 가 IN_PROGRESS 픽업 → resetToReady → 새 confirm 사이클 → 새 eventUuid 의 events.confirmed 수신 → 같은 productId INCR 두 번
  - **❌ NOT COVERED**

**enhancement 가능성 — PaymentOrder.compensated_at 컬럼 도입 (PHASE2 deferred 였던 것)**:
- 항목 단위 격리: `compensateStockCache` 가 `WHERE compensated_at IS NULL` 가드 → 이미 INCR 된 항목 skip
- 시나리오 3a/3b 모두 ✓ 커버
- **사용자 신호 위반**:
  - (d) PaymentEvent 라이프사이클 침투 가속 — PaymentOrder schema 까지 도입
- **본 토픽 작업량 비대화**: 현재 Round 5 비교표가 본 토픽 단독 작업량 = 중간 (Flyway 1 + 도메인 3 + wrapper 3 + Reconciler 메서드 1 + 단위 6 + 통합 4). PaymentOrder.compensated_at 추가는 별도 Flyway + PaymentOrder 도메인 변경 + 항목 단위 가드 도입 = 작업량 큼으로 전환
- **본 토픽 non-goal 위반**: §Round 5 §사용자 결정 입력 표 line 209 — "DA4-1 over-restore 잔여 + PHASE2 일반화 break 인정" 이 본 후보 채택 조건. enhancement 채택은 이 조건 자체를 폐기

**판정**: 시나리오 3 커버 가능하지만 본 후보 채택 조건 (DA4-1 잔여 인정) 깨짐. PaymentOrder.compensated_at 은 본 토픽 non-goal 명시였다.

### Candidate B — payment_outbox message_type 확장

**기본 거동 (Round 4/5 흡수안 (c) — PaymentConfirmEvent payload 보존)**:
- 시나리오 1: Kafka 인프라 동등.
- 시나리오 2: `compensateStockCache` catch 분기에서 payment_outbox INSERT (`message_type='STOCK_COMPENSATE'`, `dedup_key=productId`) → AFTER_COMMIT relay 또는 OutboxWorker 폴링 → StockCompensationHandler.handle → SETNX `compensation:{orderId}:{productId}` + INCR. ✓ (단 SETNX-INCR race window 인정)
- 시나리오 3a (INCR 성공 후 extendLease 직전 crash):
  - **consumer happy path 의 INCR (line 307) 은 plain — SETNX 미적용**
  - TX rollback → outbox INSERT 도 rollback → catch 진입 0 → outbox row 0
  - P5M 만료 → redeliver → handleFailed 재진입 → compensateStockCache 재호출 → 같은 productId INCR 두 번
  - **❌ NOT COVERED**
- 시나리오 3b/3c: 3a 와 동일 — happy path 에 멱등성 layer 없음 → INCR 두 번

**enhancement 가능성 — SETNX 토큰을 consumer happy path 에도 적용**:
- consumer happy path 의 INCR 호출도 `SETNX compensation:{eventUuid}:{productId}` + INCR 묶음 (Lua 아닌 application layer)
- 시나리오 3a/3b ✓ 커버 (race window 인정 — SETNX 후 INCR 직전 crash 시 토큰만 살고 INCR 미발생, 토큰 TTL 만료 후 회복)
- **사용자 신호 위반**:
  - (a) outbox 일반화 가속 — 작업 큐 의미 확장 (현행 partial 위반) + happy path 에도 SETNX 가드
  - **happy path 영향 0 가드 위협 가속** — Round 5 §최종 추천 거부 사유 본질 그대로 (relay 내부 분기 + Strategy dispatch + claimToInFlight 4 인자) 에 happy path SETNX 호출 1개 더 추가
- **본 후보 본질**: outbox 일반화 + happy path 영향 0 가드 정면 충돌 본질 그대로. enhancement 가 가드 위협 누적

**판정**: 시나리오 3 커버 가능하지만 본 후보의 핵심 위험 (happy path 가드 위협) 가속. SETNX 가 application layer 라 Lua 미도입 강점은 보존되지만 happy path 에 새 layer 진입 누적이 본질 위협.

### Candidate D — payment_history audit-driven

**기본 거동 (Round 4/5 흡수안 (a) — application 빈 wrapper)**:
- 시나리오 1: Kafka 인프라 동등.
- 시나리오 2: `compensateStockCache` catch 분기에서 PaymentCommandUseCase.markStockCompensateFailed wrapper 호출 → 별 Aspect 가 PaymentHistoryEvent INSERT (action='STOCK_COMPENSATE_FAILED', dedup_key=productId). 별 Reconciler 가 5초 polling NOT EXISTS 서브쿼리로 미회복 audit 픽업 → INCR 재시도 → markStockCompensateRecovered (action='STOCK_COMPENSATE_RECOVERED' append). ✓
- 시나리오 3a/3b/3c: A 와 동일 본질 — **consumer happy path 의 INCR 성공 시 audit row 0 → 항목 단위 격리 0 → INCR 두 번**. **❌ NOT COVERED**

**enhancement 가능성 — payment_history audit 을 consumer happy path INCR 성공에도 추가**:
- consumer happy path 의 INCR 성공 직후 markStockCompensateSucceeded (action='STOCK_COMPENSATE_SUCCEEDED' append)
- 다음 진입 시 `WHERE NOT EXISTS (SELECT 1 FROM payment_history WHERE order_id=? AND action='STOCK_COMPENSATE_SUCCEEDED' AND dedup_key=?)` 가드로 항목 skip
- 시나리오 3a/3b ✓ 커버 (단 happy path INCR 성공 후 audit INSERT 직전 crash 시 INCR 한 번 + audit 0 → 다음 시도 시 INCR 두 번 race window 인정)
- **사용자 신호 위반**:
  - (d) payment_history 의미 변질 가속 — STATUS_CHANGE / STOCK_COMPENSATE_FAILED / STOCK_COMPENSATE_RECOVERED 3종 화이트리스트 → STOCK_COMPENSATE_SUCCEEDED 추가
  - **happy path 영향**: 정상 결제 (APPROVED) 는 영향 0 그대로. FAILED/QUARANTINED 의 정상 보상 경로 = 항목 N개 INCR 마다 audit INSERT N건 추가 (RDB UPDATE N건 + AOP 발화 N건). latency +N×ms
- **본 후보 본질**: audit-driven 회복 SoT — enhancement 가 audit 책임 확장이라 본질 보존

**판정**: 시나리오 3 커버 가능 + audit 의미 확장이 본 후보 본질과 정합. 단 happy path latency +N×ms 와 action 화이트리스트 4종으로 확장 부담.

---

## 후보 정리 결정

### 판정 기준

(a) enhancement 으로 시나리오 3 커버 가능 + 사용자 신호 4가지 회피 + 본 후보 본질 보존 → **PASS**
(b) enhancement 가능하지만 사용자 신호 위반 또는 본질 손실 → **DEMOTE**
(c) enhancement 가 본질적으로 가능하지 않음 → **DELETE**

### 후보별 판정 표

| 후보 | 시나리오 1 | 시나리오 2 (기본) | 시나리오 3 (enhanced) | 사용자 신호 회피 | 본질 보존 | 판정 |
|---|---|---|---|---|---|---|
| Baseline 0 | ✓ (Kafka 동등) | ✓ (워커 + Lua) | ✓ (Lua → happy path 확장) | ✗ — 본래 4 위반. enhancement 후 (b)(c) 가속 | ✓ | **DELETE** (사용자 거부 본질 그대로) |
| Candidate A | ✓ | ✓ (Reconciler PENDING scan) | ✓ (PaymentOrder.compensated_at 도입) | ✗ — (d) 가속 + 본 토픽 채택 조건 (DA4-1 잔여 인정) 폐기 + non-goal 위반 | ✗ — enhancement 가 PHASE2 도메인 진입 | **DEMOTE** |
| Candidate B | ✓ | ✓ (outbox PENDING + StockCompensationHandler) | ✓ (SETNX → happy path 확장) | ✗ — happy path 영향 0 가드 가속 위협 | ✓ (outbox 일반화 본질 보존) | **DEMOTE** |
| Candidate D | ✓ | ✓ (audit + 별 Reconciler NOT EXISTS) | ✓ (audit SUCCEEDED action 추가) | partial — (d) audit 의미 확장 가속. 단 audit-driven 본질과 정합 | ✓ (audit-driven SoT 본질 보존) | **PASS (조건부)** |

### 판정 사유 풀이

#### Baseline 0 — DELETE

사용자가 본 후보를 거부한 사유 4 신호 그대로 — enhancement 후에도 (b) Lua 사용처 가속 + (c) port API 변경. Round 5 §사용자 결정 입력 표 자체가 baseline 을 "비교 baseline 으로 보존" 으로 둔 것 — 채택 후보 0. 시나리오 3 커버 가능성과 무관하게 사용자 4 신호 거부 본질 그대로.

#### Candidate A — DEMOTE

본 후보의 채택 조건은 §Round 5 line 209 — "DA4-1 over-restore 잔여 + PHASE2 일반화 break 인정". 시나리오 3 커버 = DA4-1 의 본질 해소 = 채택 조건 폐기. enhancement (PaymentOrder.compensated_at) 는 PHASE2 deferred 였던 것 = 본 토픽 §0 Non-goal 명시 ("PaymentOrder 단위 격리 부재" 알려진 한계로 인정).

본 토픽 단독으로 enhancement 받아들이면 Candidate A 가 아닌 별 후보 (이름은 같지만 PaymentOrder schema 변경 포함) → 본 후보 self 가 아니다. **시나리오 3 정직하게 인정 못 하면 본 후보 채택 자격 상실** — DEMOTE.

#### Candidate B — DEMOTE

본 후보의 가장 큰 위험 = happy path 영향 0 가드 정면 충돌 (Round 5 §최종 추천 거부 사유). enhancement (consumer happy path 에 SETNX 추가) 는 가드 위협 누적. SETNX 가 application layer 라 Lua 미도입 (b 회피) 강점 보존이지만 (a) outbox 일반화 가속 + happy path 에 멱등성 가드 layer 진입 = 본 후보 거부 사유 자체 가속.

단 본 후보 본질 (outbox 한 테이블 일반화 + 1 트랙 mental model + PHASE2 일반화 fit) 은 enhancement 후에도 보존 — DEMOTE (DELETE 는 아님).

#### Candidate D — PASS (조건부)

audit-driven 회복 SoT 본질이 enhancement (audit 책임 확장) 과 정합. SUCCEEDED action 추가 = action 화이트리스트 3 → 4 종 확장 = (d) 가속이지만 본 후보 본질 자체가 audit 트레일을 회복 SoT 로 두는 것 → 의미 변질이 아닌 의미 일관성 강화.

**조건**:
1. action 화이트리스트 4종 운영 가이드 (STATUS_CHANGE / STOCK_COMPENSATE_FAILED / STOCK_COMPENSATE_RECOVERED / STOCK_COMPENSATE_SUCCEEDED)
2. consumer happy path INCR 마다 audit INSERT N건 추가 — FAILED/QUARANTINED 정상 보상 경로 latency +N×ms 인정 (정상 APPROVED 경로는 영향 0 그대로)
3. SUCCEEDED audit INSERT 직전 crash race window 인정 — Reconciler 가 NOT EXISTS 서브쿼리로 회복 (기본 거동과 동일)
4. 작업량 추가 — markStockCompensateSucceeded wrapper 1 + 단위 테스트 1+ + 통합 테스트 1+

PASS 조건 충족 시 본 후보가 시나리오 3 까지 커버하는 유일한 살아남은 후보.

---

## Round 6 후 살아남은 후보

### Candidate D (enhanced) — payment_history audit-driven + happy path SUCCEEDED action

본 후보가 시나리오 1/2/3 모두 코드 path 단위로 커버하는 유일한 후보 (PASS 조건부).

**Enhanced sketch 한 줄 요약**:
- 기본 모델: payment_history.action 화이트리스트 4종 (STATUS_CHANGE / STOCK_COMPENSATE_FAILED / STOCK_COMPENSATE_RECOVERED / STOCK_COMPENSATE_SUCCEEDED) + 별 Aspect + 별 Reconciler + PaymentEvent.compensation_state_version
- enhancement: consumer happy path INCR 성공 직후 PaymentCommandUseCase.markStockCompensateSucceeded wrapper 호출 → audit row INSERT (action='STOCK_COMPENSATE_SUCCEEDED', dedup_key=productId)
- 시나리오 3 멱등성 가드: `compensateStockCache` 진입 시 `WHERE NOT EXISTS (SELECT 1 FROM payment_history WHERE order_id=? AND action='STOCK_COMPENSATE_SUCCEEDED' AND dedup_key=?)` 항목 skip 가드

### 살아남은 후보 0개 분기 (대안 시나리오)

만약 사용자가 Candidate D enhancement 의 happy path latency +N×ms / action 화이트리스트 4종 가속을 모두 거부하면 **본 토픽 살아남는 후보 0개** — 다음 중 하나 선택:

1. **시나리오 3 인정 + Candidate A 또는 D 본 토픽 단독 채택**: 본 토픽이 시나리오 1/2 만 커버. 시나리오 3 은 PHASE2 토픽으로 명시적 미루기. 사용자 신호 4 위반 0 보존 + DA4-1 잔여 알려진 한계로 인정.
2. **사용자 신호 (b) 또는 (c) 1개 양보 + Baseline 0 (Lua 토큰을 happy path 확장) 채택**: 시나리오 3 강한 차단 + 사용자 신호 1~2개 위반 인정.
3. **본 토픽 자체 폐기**: Round 6 분석으로 "본 토픽이 결정한 자동 회복 layer 가 시나리오 3 까지 커버하려면 본 토픽 단독 결정 범위 초과" 라는 결론으로 토픽 봉인 + PHASE2 와 합본.

---

## DECISION.md 갱신 사항

본 audit 결과를 §Round 6 새 섹션으로 추가. 사용자 결정 입력 체크박스 갱신:

- ~~Baseline 0~~ — DELETE 표시
- ~~Candidate A~~ — DEMOTE (시나리오 3 미커버 인정 시 채택 가능)
- ~~Candidate B~~ — DEMOTE (시나리오 3 미커버 인정 시 채택 가능)
- **Candidate D (enhanced)** — PASS 조건부 (살아남은 유일 후보)
- 시나리오 3 인정 + A/B/D 본 토픽 단독 채택 (시나리오 3 은 PHASE2 미루기)
- 본 토픽 자체 폐기 + PHASE2 합본

## ALTERNATIVES.md 갱신 사항

- Candidate 0 / A / B 섹션 헤더에 `(Round 6 audit — DELETE / DEMOTE 사유)` 표기 + 본문 시작 strikethrough 박스
- Candidate D 에 `### Round 6 enhancement` 섹션 추가 — happy path SUCCEEDED action 도입 명시
- Candidate C / E / F 는 본 audit 대상 외 (Round 2 후순위 그대로) — 변경 없음
