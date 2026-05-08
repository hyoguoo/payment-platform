# discuss-domain-1

**Topic**: STOCK-COMPENSATION-RECOVERY
**Round**: 1
**Persona**: Domain Expert

## Reasoning

산출물은 보상 회복 layer 의 **돈이 새는 1차 경로 — events.confirmed 보상 INCR silent loss** 를 outbox + 워커로 봉인한다. 멱등성 layer (UNIQUE `(order_id, product_id)` + ON DUPLICATE KEY no-op + SKIP LOCKED + processedAt 가드) 와 isTerminal 이중 가드가 합리적으로 결합되어 정상 동작 시나리오 대부분을 차단한다. 다만 **단일 워커 인스턴스에서도 "워커 크래시 후 재시작" 시 INCR 이중 호출이 가능한 구간** (Redis INCR 성공 후 processedAt 기록 전 크래시) 이 명시적으로 다뤄지지 않았고, **outbox INSERT 자체 실패 시의 메트릭 가시화 (stock_compensation_outbox_insert_fail_total)** 가 빠져 있다. 두 항목 모두 본 토픽의 범위 안에서 보강 가능한 도메인 리스크이므로 revise 로 판정한다.

## Domain risk checklist

### 멱등성 전략 (yes — 다만 보강 권고)
- outbox 적재 멱등성: UNIQUE `(order_id, product_id)` + `INSERT ... ON DUPLICATE KEY UPDATE` no-op upsert (§4.3 D3). **OK**.
- 워커 동시성 멱등성: `SELECT ... FOR UPDATE SKIP LOCKED` (§4.4 D4). **OK** (다중 인스턴스 시점은 §7.1.1 후속).
- 워커 처리 결과 멱등성: `processedAt IS NOT NULL` 가드 (§5.1). **OK** — 같은 row 재폴링 시 no-op.
- consumer 멱등성: 기존 two-phase lease 그대로 (§5.3). **OK**.
- isTerminal 이중 가드: `handleFailed` / `handleQuarantined` 진입 시 `paymentEvent.getStatus().isTerminal()` 체크 — `PaymentConfirmResultUseCase.java:259-264, 282-287`. **OK**.

### 장애 시나리오 (yes — 4개 이상 명시)
- Redis 일시 장애 (INCR RuntimeException) — §2.2, §6.2 통합 테스트 (a)/(b)/(c)
- 워커 자체 크래시 (Redis INCR 성공 직후 processedAt 기록 전) — **§4.4 가 SKIP LOCKED 만으로 면책하지만 재시작 시 재진입은 별 위험. 설계 문서에 명시 부재**.
- RDB 다운 (outbox INSERT 실패) — §7.1.3 "범위 외" 로 명시
- 한 결제 N항목 중 일부 실패 (부분 실패) — §5.2
- 메시지 자체 손실 (broker retention 초과) — §7.1.4 "범위 외"

### 재시도 정책 (yes)
- maxAttempts=5, FIXED 5s, `RetryPolicyProperties` 재사용 (§4.5 D5).
- attempt < max → incrementAttempt + scheduleNextAttempt
- attempt == max → status=FAILED + 운영자 admin 처리 대기

### PII (n/a)
- 새로 도입되는 컬럼: `order_id / product_id / quantity / reason_code / status / available_at / processed_at / attempt / created_at`. 카드번호·생년월일·CI 같은 PII 없음. orderId 도 내부 식별자. **n/a**.

## 도메인 관점 추가 검토

### 1. 워커 크래시 후 재시작 시 INCR 이중 호출 가능성 (major)

**문제**: 산출물 §4.4 D4 는 "단일 인스턴스 단일 스레드 폴링 + SKIP LOCKED 로 충분"이라 결정했는데, SKIP LOCKED 는 **동시성 race** 만 막는다. 다음 시나리오는 막지 못한다:
- t0: 워커가 PENDING row 픽업, `SELECT ... FOR UPDATE` 로 row lock 획득
- t1: `stockCachePort.increment(productId, qty)` 호출 — Redis 측에서 INCR 성공 (재고 +qty)
- t2: 워커 프로세스 크래시 (OOM, kubectl kill, JVM crash 등) → row lock 자동 해제 (TX rollback) → `processedAt IS NULL` 그대로
- t3: 워커 재시작 후 같은 row 재픽업 → 또 INCR (재고 +qty 두 번째) → **재고 발산**

**근거**:
- 산출물 §3.2 `StockCompensationRetryService` 가 `@Transactional` 로 row update 만 묶고 Redis 호출은 TX 외부 — 즉 Redis INCR 후 row update 사이에 크래시 window 가 존재.
- 산출물 §5.1 의 "워커 처리 결과 기록 — `processedAt IS NOT NULL` 가드" 는 정상 처리 종료 후 재폴링만 막을 뿐, 처리 도중 크래시는 막지 못한다.

**도메인 임팩트**: 한 결제의 재고가 두 번 복원되는 것은 결제 도메인적으로 "재고 발산 (silent over-restore)" 이며, 본 토픽이 막으려는 것 (재고 캐시 발산) 과 정확히 같은 부류의 사고다. 본 토픽이 events.confirmed 경로의 silent under-restore 를 막으면서 자기 회복 layer 가 silent over-restore 를 도입하는 비대칭이 발생한다.

**제안**:
- (a) `stockCachePort.increment` 자체에 처리 토큰을 추가해 멱등화 — Lua 스크립트로 `SETNX compensation:{outboxId} 1 EX 8d` 후 INCR 묶기. 같은 outboxId 두 번이면 두 번째는 no-op.
- (b) 또는 산출물 §3.5 의 메트릭 / EventType 에 "워커 재진입 의심" 카운터 (`stock_compensation_worker_reentry_total`) 추가 + processedAt IS NULL 인 row 의 attempt > 0 진입 시 WARN 로그 + 운영자 알림.
- (c) 최소한 **본 토픽 §4.4 또는 §7.1 에 이 race window 를 명시 + 후속 처리 명시**. 현재는 "다중 인스턴스 시 검토" 만 적혀 있어 단일 인스턴스에서도 발생 가능하다는 점이 가려진다.

위치: `docs/topics/STOCK-COMPENSATION-RECOVERY.md` §4.4, §7.1.1.

### 2. outbox INSERT 자체 실패 시 메트릭 가시화 누락 (major)

**문제**: 산출물 §3.2 `StockCompensationOutboxAppender` 는 "RDB INSERT 자체 실패 시 LogFmt.error + 메트릭 + 재throw 안 함" 이라 명시했지만, §3.5 메트릭 표에는 다음 3종만 있다:
- `stock_compensation_outbox_inserted_total` (성공 적재)
- `stock_compensation_outbox_retry_total`
- `stock_compensation_outbox_failed_total` (FAILED 마킹)

**없는 것**: `stock_compensation_outbox_insert_fail_total` — appender 자체가 RDB 실패로 swallow 한 횟수.

**도메인 임팩트**: 이 경로가 본 작업의 **마지막 silent loss 경로**다. Redis 보상 실패 + outbox INSERT 도 실패 → swallow → `extendLease(P8D)` 호출 → 메시지 ack → **재고 영구 발산**. 산출물 §5.3 "outbox INSERT 자체가 실패하면 현재 코드와 동일하게 try/catch 가 흡수 → extendLease 호출 → dedupe 잠금" 이 이 경로를 인정한다. §7.1.3 "RDB 다운은 더 큰 outage 로 별도 처리" 로 책임 분리는 합리적이지만, **이 사고를 운영자가 즉시 보려면 카운터가 필요**하다. 로그만으로는 "재고 발산이 일어났다" 를 시각화하기 어렵다 (LogFmt.error 는 다른 사유 에러와 섞임).

**제안**:
- §3.5 메트릭 표에 `stock_compensation_outbox_insert_fail_total` (Counter) + `EventType.STOCK_COMPENSATION_OUTBOX_INSERT_FAILED` 추가.
- 설명: "appender 가 swallow 한 RDB 실패 — 결제 종결되었으나 재고 캐시 미회복. 알람 임계 0 (단일 발생도 즉시 알람)."
- 산출물 §3.2 의 appender 책임 한 줄을 "LogFmt.error + 메트릭 + 재throw 안 함" 에서 "LogFmt.error + `insert_fail_total++` + 재throw 안 함" 로 명시.

위치: `docs/topics/STOCK-COMPENSATION-RECOVERY.md` §3.2, §3.5.

### 3. 다른 보상 경로 (`OutboxAsyncConfirmService.compensateStock`, `PaymentTransactionCoordinator.compensateStockCacheGuarded`) 와의 일관성 미선언 (minor)

**문제**: 본 토픽은 events.confirmed FAILED/QUARANTINED 보상만 회복 layer 를 도입한다. 그러나 같은 silent loss 패턴이 다음 두 경로에도 있다 (PITFALLS 5번 catch swallow 와 동일 구조):
- `OutboxAsyncConfirmService.compensateStock` (line 99-119) — confirm TX 실패 시 보상. catch swallow + LogFmt.error 만.
- `PaymentTransactionCoordinator.compensateStockCacheGuarded` (line 168-180) — 동일.

**도메인 임팩트**: 두 경로는 confirm 진입 직후 보상이라 "선차감 후 곧바로 실패" 시나리오에 한정되고, 클라이언트가 동기 응답을 받기 전이라 결제 종결 표시도 안 됨 → 재고 발산이 events.confirmed 경로보다 작은 window. 그러나 silent loss 자체는 동일하다. 본 토픽이 events.confirmed 경로만 처리하면서 **두 경로를 명시적으로 "다른 토픽" 으로 분리하지 않으면**, 후속 작업자가 "보상 회복은 끝났다" 고 오해할 수 있다.

**제안**:
- §7.2 후속 연계 표에 "OutboxAsyncConfirmService / PaymentTransactionCoordinator 의 compensateStock(Guarded) 동일 패턴 보상 회복은 별도 토픽" 한 줄 추가.
- 또는 §1 또는 사전 브리핑 §1 "현재 이해한 문제" 에 "events.confirmed 경로 한정" 을 명시.

위치: `docs/topics/STOCK-COMPENSATION-RECOVERY.md` §7.2 후속 표, 또는 사전 브리핑.

### 4. attempt 카운터 의미와 boundary 검증 (minor)

**문제**: 산출물 §4.5 D5 는 "워커 호출당 attempt++ → attempt 가 max 와 같아지는 순간 status=FAILED 마킹" 인데, 산출물 §4.1 DDL 의 `attempt` 기본값은 0. 따라서:
- 적재 시점: attempt=0 (실제 시도 0회).
- 1회 시도 실패 후: attempt=1.
- 5회 시도 실패 후: attempt=5 → status=FAILED.

총 5회 retry 후 종결 — 합리적. 다만 §2.3 워커 회복 사이클 다이어그램 N 분기 표현 ("attempt 가 max 도달?") 이 boundary 모호 (`==` 인지 `>=` 인지). plan 단계에서 명확화 필요.

**도메인 임팩트**: low — 1회 차이는 운영 알람 시점에 5초~25초 차이만 발생.

**제안**: §4.5 D5 본문에 "`attempt` 가 `max-attempts` 와 같아지는 시점 (즉 5회 시도 후 6번째 진입 직전)" 처럼 명시 — 또는 plan 단계 task 분해에서 단위 테스트로 boundary 고정.

위치: `docs/topics/STOCK-COMPENSATION-RECOVERY.md` §4.5, §2.3.

### 5. 격리 마킹과 보상 적재 순서 — 안전 확인 (n/a — 이미 안전)

**확인**: `handleQuarantined` 는 `compensateStockCache` (보상 시도 + 실패 시 outbox 적재) → `quarantineCompensationHandler.handle` (markPaymentAsQuarantined) 순서다 (`PaymentConfirmResultUseCase.java:289-294`). 산출물 §3.2 가 "RDB INSERT 자체 실패 시 재throw 안 함" 으로 명시했기에 outbox 적재 실패가 격리 마킹을 막지 않는다. 격리 마킹이 보상 회복과 분리된다는 D7 결정 (§4.7) 이 코드 사실과 일치. **OK**.

### 6. 결제 종결과 재고 회복 사이의 시간 window — 도메인 acceptable (n/a)

**확인**: handleFailed → markPaymentAsFail (terminal) → compensateStockCache 실패 시 outbox 적재 → 워커가 5초~25초 안에 회복. 사용자 측에는 "결제 실패" 가 즉시 응답되고 재고만 max 25초 발산 — 결제 도메인적으로 결제 자체 정합성은 유지되며 재고 발산은 워커가 회복. 운영자 admin 개입 (FAILED 마킹된 outbox 행) 까지 더 길어질 수 있으나, 결제 종결이 잘못된 게 아니므로 도메인 허용 가능. **OK**.

## Findings

1. **(major) 워커 크래시 후 재시작 시 INCR 이중 호출 가능성** — `StockCompensationRetryService` 가 Redis INCR 성공 후 processedAt update 사이 크래시 시 row lock 해제 → 재폴링 → 재 INCR. 단일 인스턴스 가정이 이 race 를 막지 못함.
   - 위치: §4.4, §7.1.1
   - 제안: outboxId 기반 처리 토큰 (Redis SETNX 8d) + INCR 묶기, 또는 최소 메트릭 + 명시.

2. **(major) outbox INSERT 자체 실패 시 메트릭 가시화 누락** — appender swallow 경로의 silent loss 가 운영 카운터에 안 잡힘.
   - 위치: §3.2, §3.5
   - 제안: `stock_compensation_outbox_insert_fail_total` Counter + `EventType.STOCK_COMPENSATION_OUTBOX_INSERT_FAILED` 추가. 알람 임계 0.

3. **(minor) 다른 두 보상 경로 (OutboxAsyncConfirmService.compensateStock / PaymentTransactionCoordinator.compensateStockCacheGuarded) 와의 분리 미선언** — 후속 작업자가 "보상 회복 완료" 로 오해할 위험.
   - 위치: §7.2, 사전 브리핑 §1
   - 제안: 본 토픽이 events.confirmed 경로 한정임을 명시 + 다른 두 경로 후속 토픽 표기.

4. **(minor) attempt boundary 모호** — `==` / `>=` / fence-post 표현이 §2.3 다이어그램과 §4.5 본문에 분산.
   - 위치: §2.3, §4.5
   - 제안: plan 단계 task 분해에서 boundary 단위 테스트로 고정 + 본문 표현 일치.

## JSON

```json
{
  "stage": "discuss",
  "persona": "domain-expert",
  "round": 1,
  "task_id": null,

  "decision": "revise",
  "reason_summary": "보상 outbox layer 의 멱등성·재시도·격리 마킹 분리 설계는 도메인적으로 합리적이나, 워커 크래시 후 재진입 시 INCR 이중 호출 race window 와 outbox INSERT 자체 실패의 메트릭 가시화가 빠져 있어 silent over-restore / silent under-restore 위험을 완전히 봉인하지 못한다.",

  "checklist": {
    "source": "_shared/checklists/discuss-ready.md",
    "items": [
      {
        "section": "domain risk",
        "item": "멱등성 전략이 결정됨 (idempotency key 소스/수명/충돌 처리)",
        "status": "yes",
        "evidence": "docs/topics/STOCK-COMPENSATION-RECOVERY.md §4.3 D3 + §5.1 멱등성 layer 표 (UNIQUE (order_id,product_id) + ON DUPLICATE KEY no-op + SKIP LOCKED + processedAt 가드)"
      },
      {
        "section": "domain risk",
        "item": "장애 시나리오 최소 3개 식별됨",
        "status": "yes",
        "evidence": "docs/topics/STOCK-COMPENSATION-RECOVERY.md §2.2, §5.2, §7.1 — Redis 일시 장애 / 워커 크래시 / RDB 다운 / 부분 실패 / 메시지 손실 5종"
      },
      {
        "section": "domain risk",
        "item": "재시도 정책이 정의됨",
        "status": "yes",
        "evidence": "docs/topics/STOCK-COMPENSATION-RECOVERY.md §4.5 D5 — maxAttempts=5, FIXED 5s, RetryPolicyProperties 재사용, attempt 도달 시 status=FAILED"
      },
      {
        "section": "domain risk",
        "item": "PII/민감정보 검토",
        "status": "n/a",
        "evidence": "docs/topics/STOCK-COMPENSATION-RECOVERY.md §4.1 DDL — order_id/product_id/quantity/reason_code/status/available_at/processed_at/attempt/created_at. 카드번호·CI·PII 없음."
      }
    ],
    "total": 4,
    "passed": 3,
    "failed": 0,
    "not_applicable": 1
  },

  "scores": {
    "clarity": 0.86,
    "completeness": 0.78,
    "risk": 0.72,
    "testability": 0.82,
    "fit": 0.88,
    "mean": 0.812
  },

  "findings": [
    {
      "severity": "major",
      "checklist_item": "멱등성 전략이 결정됨 (idempotency key 소스/수명/충돌 처리)",
      "location": "docs/topics/STOCK-COMPENSATION-RECOVERY.md §4.4 D4 + §5.1 + §7.1.1",
      "problem": "워커 크래시 race — StockCompensationRetryService 가 Redis INCR 성공 후 processedAt update 사이에 프로세스 크래시 시 row lock 자동 해제 → 재시작 후 같은 row 재픽업 → INCR 두 번 발생 → 재고 silent over-restore (재고 발산). SKIP LOCKED 는 동시성만 막을 뿐 단일 인스턴스의 재진입은 막지 못한다. §4.4 의 '단일 인스턴스 가정' 면책이 이 race 를 가린다.",
      "evidence": "산출물 §3.2 'Redis 호출은 TX 외부 — Hikari 점유 회피' + 코드 사실: stockCachePort.increment 자체에 멱등성 보장 없음 (Round 0 인터뷰 §scope Path 1 확인) + §5.1 의 processedAt 가드는 이미 처리된 row 의 재폴링만 막음.",
      "suggestion": "(a) outboxId 기반 처리 토큰을 Redis 측에 도입 — Lua 로 SETNX compensation:{outboxId} 1 EX P8D 후 INCR 묶기. 같은 outboxId 두 번째 진입 시 no-op. (b) 또는 §3.5 메트릭에 stock_compensation_worker_reentry_total 카운터 + processedAt IS NULL && attempt > 0 진입 시 WARN. (c) 최소한 §4.4 / §7.1.1 에 이 race window 와 후속 처리를 명시."
    },
    {
      "severity": "major",
      "checklist_item": "장애 시나리오 최소 3개 식별됨",
      "location": "docs/topics/STOCK-COMPENSATION-RECOVERY.md §3.2, §3.5",
      "problem": "outbox INSERT 자체가 RDB 실패로 swallow 되는 silent loss 경로의 메트릭이 누락. §3.2 가 'LogFmt.error + 메트릭 + 재throw 안 함' 으로 명시하지만 §3.5 메트릭 3종 (inserted/retry/failed) 에는 INSERT 실패 카운터가 없다. 이 경로는 본 작업의 마지막 silent loss 경로 — Redis 보상 실패 + outbox INSERT 도 실패 시 extendLease(P8D) → 메시지 ack → 재고 영구 발산. 운영자가 LogFmt.error 만으로 즉시 인지하기 어렵다.",
      "evidence": "산출물 §3.2 책임 표 + §3.5 메트릭 표 + §5.3 '현재 코드와 동일하게 try/catch 가 흡수 → extendLease 호출 → dedupe 잠금' + §7.1.3 'RDB 다운은 더 큰 outage 로 범위 외'.",
      "suggestion": "§3.5 메트릭 표에 stock_compensation_outbox_insert_fail_total (Counter) + EventType.STOCK_COMPENSATION_OUTBOX_INSERT_FAILED 추가. 알람 임계 0 (단일 발생도 즉시 알람). §3.2 appender 책임 줄을 'LogFmt.error + insert_fail_total++ + 재throw 안 함' 으로 보강."
    },
    {
      "severity": "minor",
      "checklist_item": "TOPIC scope 외 영향 검토",
      "location": "docs/topics/STOCK-COMPENSATION-RECOVERY.md §7.2 후속 표 + 사전 브리핑 §1",
      "problem": "events.confirmed 경로 외에 OutboxAsyncConfirmService.compensateStock (line 99-119) 와 PaymentTransactionCoordinator.compensateStockCacheGuarded (line 168-180) 도 같은 catch swallow + LogFmt.error 패턴이다. 본 토픽이 events.confirmed 경로만 다룬다는 사실이 §7.2 또는 사전 브리핑에 명시되지 않으면 후속 작업자가 '보상 회복 완료' 로 오해할 수 있다.",
      "evidence": "Round 0 인터뷰 §scope '변경 코드 위치: PaymentConfirmResultUseCase.compensateStockCache + 신규 보상 outbox 인프라' — 다른 두 경로는 명시적으로 범위 외이지만 산출물 본문에 그 분리가 적히지 않음.",
      "suggestion": "§7.2 후속 연계 표에 '같은 silent loss 패턴이 OutboxAsyncConfirmService / PaymentTransactionCoordinator 에 존재하지만 본 토픽 범위 외 — 후속 토픽으로 분리' 한 줄 추가. 또는 사전 브리핑 §1 에 '본 작업은 events.confirmed FAILED/QUARANTINED 보상 경로에 한정' 명시."
    },
    {
      "severity": "minor",
      "checklist_item": "재시도 정책이 정의됨",
      "location": "docs/topics/STOCK-COMPENSATION-RECOVERY.md §2.3, §4.5",
      "problem": "attempt boundary 표현 모호. §2.3 워커 회복 사이클 다이어그램의 'attempt 가 max 도달?' 이 == 인지 >= 인지 fence-post 가 본문과 다이어그램에 분산. 도메인 임팩트는 5초~25초 알람 시점 차이로 작지만 plan 단계 task 분해 전에 명확화하는 게 안전하다.",
      "evidence": "산출물 §2.3 mermaid + §4.5 D5 본문 'attempt 가 max 와 같아지는 순간 status=FAILED'.",
      "suggestion": "§4.5 D5 본문에 'attempt 가 max-attempts (=5) 와 같아지는 시점 (즉 5회 시도 후 6번째 진입 직전 종결)' 명시 + plan 단계 단위 테스트에 attempt=4→5 전환과 attempt=5→FAILED boundary 케이스 명시."
    }
  ],

  "previous_round_ref": null,
  "delta": null,

  "unstuck_suggestion": null
}
```
