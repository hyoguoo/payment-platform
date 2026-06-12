# review-domain-1 — STOCK-COMPENSATION-RECOVERY review 1라운드 Domain Expert

> stage: review
> round: 1
> persona: Domain Expert

---

## 판정

**pass** — critical 0, major 0, minor 1.

---

## Reasoning

호출 순서 뒤집기 (보상 → RDB) 가 코드에 정확히 반영되어 있고 (`PaymentConfirmResultUseCase.java:205, 208` / `:234, 236`), Lua dedup token namespace 가 `decrement:done:` 와 `compensation:done:` 으로 분리되어 보상 / 선차감이 충돌 없이 작동한다. 통합 테스트가 InOrder 로 호출 순서를 직접 검증하고 retry 5회 + DLQ 분기까지 cover. PLAN §V2 race window 분석이 코드 사실과 정합. PLAN 에 명시된 L1~L7 trade-off 외에 본 라운드에서 **새 silent loss 경로** 또는 **돈 사고 경로** 는 발견되지 않았다.

---

## Domain risk checklist

- **상태 전이 정합** — pass
  - `PaymentConfirmResultUseCase.handleFailed:197` `isTerminal()` 가드 → `markPaymentAsFail` 진입 → 도메인 `fail()` 의 `isTerminalStatus()` 이중 가드 (`PaymentEvent.java:119`) 도 살아있음. 새 호출 순서에서 보상 후 `markPaymentAsFail` 사이 crash 시 재배달 → `compensateAtomic` ALREADY_DONE → `markPaymentAsFail` 진행 → 정합. PLAN §V2 분석 코드 일치.
  - `handleQuarantined` 의 `isTerminal()` 가드 (`:226`) 는 PLAN 본문 §V2 가 명시한 race window 외 잔여는 L3 으로 인정.
- **멱등성** — pass
  - Lua dedup token namespace 분리 (`stock_compensation_atomic.lua` `compensation:done:{orderId}` vs `stock_decrement_atomic.lua` `decrement:done:{orderId}`) → 차감 / 보상 dedup 충돌 0.
  - 통합 테스트 `보상_ALREADY_DONE_재배달_멱등` (`StockCompensationRecoveryIntegrationTest`) 가 같은 orderId 두 번 발행 후 재고 한 번만 복원 검증.
- **호출 순서 cascade (L6 / L7)** — PLAN 결정 인정. 본 라운드 추가 finding 없음.
- **PG 실패 모드** — pass
  - FAILED 분기 (`:196-212`) / QUARANTINED 분기 (`:225-243`) 모두 보상 호출됨. AMOUNT_MISMATCH (`handleApproved:128-138`) 는 `quarantineCompensationHandler.handle` 위임 — 이 경로는 본 토픽 코드 변경 외.
- **race window** — pass (PLAN §V2 분석 코드 사실 일치)
- **PII** — n/a (본 토픽 변경 영역에 PII 없음)

---

## 도메인 관점 추가 검토

### 1. AMOUNT_MISMATCH 분기는 보상을 수행하지 않는다 — 정책 보존 확인

`PaymentConfirmResultUseCase.handleApproved:128-138` — AMOUNT_MISMATCH 시 `quarantineCompensationHandler.handle` 만 호출하고 `compensateAtomic` 은 부르지 않는다. `CONFIRM-FLOW.md:166-179` 와 `PITFALLS.md` #16 의 정책과 정합. 본 토픽이 이 분기를 변경하지 않은 것은 의도된 결정으로 정합.

### 2. `handleQuarantined` 의 QUARANTINED → QUARANTINED 자기 전이 허용 — 본 토픽 신규 cascade 아님

`PaymentEventStatus.isTerminal()` 에서 QUARANTINED=false 이고, `PaymentEvent.quarantine()` 의 가드는 isTerminal 만 검사 → QUARANTINED 가 다시 QUARANTINED 로 자기 전이 허용. 본 토픽 이전부터 있던 거동이며 본 라운드 변경의 결과가 아니다 (호출 순서 뒤집기는 FAILED 분기에만 적용). 본 라운드 finding 으로 들지 않음.

### 3. `decrement:done:{orderId}` 와 `compensation:done:{orderId}` 의 P8D 정합

두 dedup token 모두 P8D = 691200초 (`StockCacheRedisAdapter`, `stock_decrement_atomic.lua`, `stock_compensation_atomic.lua`). PITFALLS #9 "dedupe TTL ≠ Kafka retention" 정책 (Kafka 7d + 복구 1d = 8d) 과 정합. `STOCK_OUTBOX_TTL` (`PaymentConfirmResultUseCase.java:52`) 도 8d 로 일치 — 모든 dedupe TTL 정렬 OK.

---

## Findings

### M1 — `OutboxAsyncConfirmService.compensateStock` 가 dedup token namespace 미인지 (minor)

- **severity**: minor
- **where**: `payment-service/src/main/java/.../application/OutboxAsyncConfirmService.java:100-120`
- **what**: confirm TX 실패 시 `compensateStock` 가 `stockCachePort.increment(productId, qty)` 단순 INCR 만 호출 — `decrement:done:{orderId}` token 은 P8D 살아있는 채로 재고만 복원된다. 클라이언트가 같은 orderId 로 재confirm 진입 시 `decrementAtomic` 이 ALREADY_DONE → `StockDecrementResult.SUCCESS` 매핑 → 차감되지 않은 재고가 차감된 것처럼 처리되는 cascade.
- **why (도메인 리스크)**: 본 토픽이 도입한 dedup token 의 직접 cascade. 다만 정상 흐름에서는 confirm TX 실패 후 같은 orderId 로 재진입은 매우 드물다 (orderId 결제 단위 unique, paymentEvent.status 가 READY 가 아니면 `executePayment` 진입 자체가 차단). PLAN §알려진 한계 L6 와 같은 본질의 cascade 이며 발생 확률 매우 낮다.
- **fix_hint**: PLAN PHASE2 의 `OutboxAsyncConfirmService.compensateStock` 동일 패턴 회복 항목 본문에 "decrement dedup token 정합 (token DEL 또는 compensation token 박기)" 도 명시 추가하면 PHASE2 작업 범위가 명확해진다. 본 토픽 단독 수정 불필요 — PHASE2 인지 명시만 권고.

---

## JSON

```json
{
  "decision": "pass",
  "round": 1,
  "stage": "review",
  "persona": "domain-expert",
  "findings": [
    {
      "id": "D1",
      "severity": "minor",
      "where": "payment-service/src/main/java/.../application/OutboxAsyncConfirmService.java:100-120",
      "what": "confirm TX 실패 보상 경로(compensateStock)가 stockCachePort.increment 단순 호출이라 decrement:done:{orderId} dedup token 이 P8D 살아있는 채로 재고만 복원된다. 같은 orderId 로 재confirm 진입 시 decrementAtomic 이 ALREADY_DONE→SUCCESS 로 매핑되어 차감 없이 SUCCESS 처리되는 cascade.",
      "why": "본 토픽이 도입한 dedup token 의 직접 cascade. 정상 흐름에서는 같은 orderId 재진입 가능성 매우 낮으나(L6 와 같은 본질), PHASE2 인지에 명시되어야 PHASE2 작업 범위가 명확해진다.",
      "fix_hint": "PLAN PHASE2 의 OutboxAsyncConfirmService.compensateStock 동일 패턴 회복 항목 본문에 'decrement dedup token 정합 (token DEL 또는 compensation token 박기)' 도 명시 추가. 본 토픽 단독 수정 불필요."
    }
  ],
  "summary": "review 1라운드 도메인 판정 pass — 호출 순서 뒤집기, dedup token namespace 분리, isTerminal 가드, 통합 테스트 InOrder 검증 모두 코드 사실 정합. minor 1건은 PHASE2 인지 강화 권고만."
}
```
