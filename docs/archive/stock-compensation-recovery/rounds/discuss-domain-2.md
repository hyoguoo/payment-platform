# discuss-domain-2

**Topic**: STOCK-COMPENSATION-RECOVERY
**Round**: 2
**Persona**: Domain Expert

## Reasoning

Round 1 의 4건 finding (D1 워커 크래시 race / D2 insert_fail 메트릭 / D3 다른 보상 경로 분리 / D4 attempt boundary) 이 Round 2 patch 에서 모두 도메인적으로 충분히 해소됐다. 특히 D1 의 silent over-restore 위험은 Lua 스크립트 (SETNX + INCRBY atomic 묶기) + worker_reentry 가시화 메트릭 (a)+(b) 동시 적용으로 차단·가시화가 함께 갖춰졌고, D2 의 마지막 silent loss 경로도 `stock_compensation_outbox_insert_fail_total` Counter (알람 임계 0) 로 봉인됐다. 신규 도입된 Lua 처리 토큰의 도메인 안전성을 코드 사실(`redis-stock` standalone, `DefaultRedisScript` 기존 사용 패턴, 다른 두 보상 경로의 line 99-119 / 168-180 일치) 과 교차 검증한 결과 신규 critical 도메인 리스크는 발견되지 않았다. 잔여 minor 2건 (events.confirmed 첫 보상 토큰 미사용 / Lua 응답 손실 시 워커 동작 미명시) 은 plan 단계 단위 테스트와 후속 토픽으로 흡수 가능하다. 판정 pass.

## Domain risk checklist

### 멱등성 전략 (yes — Round 1 대비 강화)
- outbox 적재 멱등성: UNIQUE `(order_id, product_id)` + `INSERT ... ON DUPLICATE KEY UPDATE` no-op (§4.3 D3). **OK**.
- 워커 동시성 멱등성: `SELECT ... FOR UPDATE SKIP LOCKED` (§4.4 D4). **OK**.
- 워커 처리 결과 멱등성: `processedAt IS NOT NULL` 가드 (§5.1). **OK**.
- **워커 크래시 재진입 멱등성 (Round 2 신규)**: outboxId 기반 Redis Lua 처리 토큰 (`SETNX compensation:token:{outboxId} ... EX P8D` + INCRBY atomic). 같은 outboxId 두 번째 진입 시 `ALREADY_PROCESSED` no-op (§4.4 D4 채택안). **OK — Round 1 D1 finding 해소**.
- consumer 멱등성: 기존 two-phase lease 그대로 (§5.3). **OK**.
- isTerminal 이중 가드: `PaymentConfirmResultUseCase.java:259-264, 282-287` 코드 사실 일치. **OK**.

### 장애 시나리오 (yes — 6개 식별)
- Redis 일시 장애 (INCR RuntimeException) — §2.2, §6.2 (a)/(b)/(c)
- **워커 자체 크래시 (Redis INCR 성공 직후 processedAt 기록 전) — §4.4 D4 race window 명시 + Lua 토큰 차단 + worker_reentry 가시화. §6.2 (d) 통합 테스트 명시. Round 1 보강 완료**
- RDB 다운 (outbox INSERT 실패) — §7.1.3 + §3.5 `insert_fail_total` 알람 임계 0
- 한 결제 N항목 중 일부 실패 (부분 실패) — §5.2
- 메시지 자체 손실 (broker retention 초과) — §7.1.4 dedupe TTL P8D 정렬
- 다중 워커 인스턴스 도입 시 멱등성 — §7.1.1 처리 토큰 그대로 보장 (§4.4 마지막 줄)

### 재시도 정책 (yes — boundary 명시)
- maxAttempts=5, FIXED 5s, `RetryPolicyProperties` 재사용 (§4.5 D5).
- attempt boundary 표: 0(적재) → 1(1회 실패) → 4(4회 실패) → **5(5회 실패=FAILED, available_at 갱신 안 함)** (§4.5 D5 표). **Round 1 D4 finding 해소**.
- §8.5 plan task 분해에 attempt=4→5 전환 + attempt=5→FAILED boundary 단위 테스트 명시.

### PII (n/a)
- 새 컬럼 9종 모두 비PII (Round 1 동일).
- Lua 처리 토큰 키 `compensation:token:{outboxId}` — outboxId 는 내부 식별자, PII 아님. **n/a**.

## 도메인 관점 추가 검토

### 1. Round 1 D1 워커 크래시 race — 해소 검증 (n/a, 만족)

§4.4 D4 가 race window timeline (t0~t3) 을 명시했고, 채택안 (a) Lua SETNX+INCRBY atomic 토큰 + (b) `stock_compensation_worker_reentry_total` 가시화 메트릭 + WORKER_REENTRY EventType 을 §3.5 / §6.1 / §6.2 (d) / §8.6 / §8.9 / §8.10 전 layer 에 일관 반영했다. TTL P8D 가 dedupe lease P8D 와 정렬되어 토큰 수명 안에서는 모든 재진입이 차단된다. 도메인 안전성 비대칭 (silent under-restore 막으면서 silent over-restore 도입) 이 해소됐다. **만족**.

`redis-stock` 컨테이너 토폴로지 코드 사실 확인: `docker/docker-compose.infra.yml:91-109` 단일 컨테이너 standalone (`redis:7.2-alpine`, `redis-server` 단일 명령). cluster 모드 아니므로 `KEYS[1]=stock:product:{productId}` 와 `KEYS[2]=compensation:token:{outboxId}` 의 hash slot 분산으로 인한 CROSSSLOT 에러 우려 없음. 향후 cluster 전환 시 hash tag 추가 필요한 점은 §7.1 또는 §7.2 에 한 줄 추가가 좋지만 현재 운영에서는 영향 없으므로 **n/a**.

### 2. Round 1 D2 outbox INSERT 실패 메트릭 — 해소 검증 (n/a, 만족)

§3.5 에 `stock_compensation_outbox_insert_fail_total` Counter + `EventType.STOCK_COMPENSATION_OUTBOX_INSERT_FAILED` 추가 + "알람 임계 0 (단일 발생도 즉시 알람)" 명시. §3.2 appender 책임 줄도 `LogFmt.error + insert_fail_total++ + EventType 발행 + 재throw 안 함` 으로 보강됨. §8.9 plan task 가 `EventType` 5종 + Counter 5종 으로 갱신되어 일관성 유지. 마지막 silent loss 경로가 운영 가시화 됐다. **만족**.

### 3. Round 1 D3 다른 보상 경로 분리 — 해소 검증 (n/a, 만족)

§0 Non-goals 표에 "`OutboxAsyncConfirmService.compensateStock` / `PaymentTransactionCoordinator.compensateStockCacheGuarded` 회복" 명시 + 사유 ("진입 시점·도메인 의미 다름. 후속 별도 토픽"). §7.2 후속 연계 표에도 line 99-119 / 168-180 정확히 인용. 코드 사실 교차 검증 완료:
- `OutboxAsyncConfirmService.java:99-119` — confirm TX 실패 시 compensateStock catch swallow + LogFmt.error 패턴 코드 사실 일치
- `PaymentTransactionCoordinator.java:168-180` — compensateStockCacheGuarded 동일 패턴 코드 사실 일치

후속 작업자가 "보상 회복 완료" 로 오해할 위험 차단됐다. **만족**.

### 4. Round 1 D4 attempt boundary — 해소 검증 (n/a, 만족)

§4.5 D5 에 attempt 0/1/4/5 boundary 표 + "총 5회 시도 후 6번째 진입 직전 종결" 명시. §8.5 plan task 에 boundary 테스트 명시. §6.1 `StockCompensationRetryService` 단위 테스트도 (a) 성공, (b) 실패+attempt 미도달 (attempt=4→5 전환은 PENDING + available_at 갱신), (c) 실패+도달 (attempt=5 도달은 FAILED + available_at 갱신 안 함) 3분기 + boundary 케이스로 명시. **만족**.

### 5. Round 2 신규 — events.confirmed 첫 보상 호출이 토큰 미사용 (minor)

**문제**: §4.4 D4 처리 토큰은 **워커 측만** 적용한다. `PaymentConfirmResultUseCase.compensateStockCache` (line 304-317) 의 첫 보상 호출 (events.confirmed FAILED/QUARANTINED 직후) 은 여전히 토큰 없이 일반 `stockCachePort.increment` 를 호출한다.

**도메인 임팩트**: events.confirmed 메시지가 두 번 도달하는 경우 (예: dedupe lease PT5M 만료 + Kafka redeliver) 첫 보상이 두 번 호출되어 INCR 두 번 — silent over-restore 가능. 다만:
- 1차 처리 성공 후 `extendLease(P8D)` 가 즉시 호출되므로 5분 lease 만료 전 처리가 완료될 가능성이 절대다수다.
- two-phase lease 의 `markWithLease(PT5M)` 가 첫 5분 안에 두 번째 진입을 막는다.
- 5분 안에 메시지가 두 번 도달하는 시나리오 (consumer 쪽 timeout 등) 는 일반적이지 않다.

따라서 도메인 위험은 작으나 **0 은 아니다**. 본 토픽이 워커 측 over-restore 를 봉인하면서 첫 보상 호출은 그대로 두는 것이 약간의 비대칭이지만, 본 토픽 책임 (보상 outbox + 워커 회복) 을 넘어가는 영역.

**제안**: 본 토픽 범위 외로 인정하되 §7.2 후속 연계 표에 한 줄 추가 — "events.confirmed 첫 보상 호출 (`compensateStockCache`) 의 토큰화는 dedupe lease 5분 안의 race 가 매우 드물어 본 토픽 범위 외. 후속 별도 토픽 또는 다른 두 보상 경로 토픽과 함께 처리." 또는 plan 단계 단위 테스트에서 명시적으로 "첫 호출은 비-토큰 + 워커는 토큰" 분리를 fix 하면 충분.

위치: `docs/topics/STOCK-COMPENSATION-RECOVERY.md` §7.2 후속 표.

### 6. Round 2 신규 — Lua 응답 손실 시 워커 동작 미명시 (minor)

**문제**: §4.4 D4 채택안 Lua 스크립트가 atomic SETNX+INCRBY 후 응답을 워커에 반환한다. 그러나 **워커가 Lua 응답을 받기 전 네트워크 단절 / 워커 크래시** 시 워커는 다음 두 상황을 구분 못 한다:
- (i) Lua 가 아직 실행 안 됨 → 토큰 미생성, INCR 안 일어남 → 재시도 안전
- (ii) Lua 가 실행됨 → 토큰 생성, INCR 일어남 → 재시도 시 토큰이 차단

산출물 §4.4 가 "(ii) 라도 토큰이 막아 안전" 라고 명시하긴 했지만, 워커가 row 를 어떻게 처리할지 (markProcessed 즉시 / next-attempt 갱신 / FAILED) 가 명시되지 않았다. 도메인적으로는 (ii) 인 경우 INCR 은 이미 일어났으므로 markProcessed 가 정확하지만, 워커가 그 사실을 모르고 attempt++ + next-attempt 갱신으로 진행하면 다음 폴링에서 토큰만 차단되고 attempt 만 1 늘어나 max 도달 → 결국 FAILED 마킹. 도메인 사고는 아니지만 **운영 false-positive FAILED** 가 발생 가능.

**도메인 임팩트**: low — INCR 자체는 안전하게 한 번만 (토큰이 보장), 결제 종결도 잘못된 게 아니지만 운영자가 FAILED 행을 보고 "재고 미회복" 으로 오해해 수동 INCR 을 또 하면 그때 silent over-restore. 이 second-order risk 는 admin 도구 설계에서 봉인 가능.

**제안**: 산출물 §4.4 또는 §6.2 (d) 통합 테스트에 "Lua 응답 직전 워커 크래시 시 재진입 시 status 처리 흐름" boundary 케이스 한 줄 명시. 또는 plan 단계 단위 테스트에서 "Lua 가 ALREADY_PROCESSED 반환 시 워커는 markProcessed (성공으로 간주) 처리한다" 분기 고정. 운영 admin 도구 설계 시 "FAILED row 처리 전 토큰 존재 여부 확인" 가이드 후속 토픽에 인계.

위치: `docs/topics/STOCK-COMPENSATION-RECOVERY.md` §4.4 채택안 본문 또는 §6.2 (d).

### 7. 격리 마킹과 보상 적재 순서 — 안전 재확인 (n/a)

**확인**: `PaymentConfirmResultUseCase.java:289-294` 에서 `handleQuarantined` 가 `compensateStockCache` 먼저 → `quarantineCompensationHandler.handle` 순서. §4.7 D7 이 이 순서 보존. outbox 적재 실패 시 재throw 없으므로 격리 마킹은 항상 진행. **OK — Round 1 동일 결론**.

### 8. Lua 스크립트 등록 패턴 — 코드 사실 정합 (n/a)

**확인**: `StockCacheRedisAdapter.java:10, 28-31` 에 이미 `DefaultRedisScript<Long> DECREMENT_SCRIPT` 패턴이 있음. 신규 보상 토큰 Lua 스크립트도 같은 어댑터 안 또는 신규 `StockCompensationCachePort` 어댑터에 동일 패턴으로 추가하면 layer 룰 (port = application, 어댑터 = infrastructure) 위배 없음. **OK**.

### 9. 결제 종결과 재고 회복 사이의 시간 window — 도메인 acceptable (n/a)

**확인**: handleFailed → markPaymentAsFail (terminal) → compensateStockCache 실패 시 outbox 적재 → 워커 5초~25초 + Lua 토큰 + 부분 실패 격리. 결제 종결은 정확하고 재고만 max 25초 발산 후 회복. 운영자 admin 개입까지의 시간 window 도 §3.5 메트릭으로 가시화. 결제 도메인 허용 가능. **OK — Round 1 동일 결론**.

## Findings

1. **(minor) events.confirmed 첫 보상 호출 (`PaymentConfirmResultUseCase.compensateStockCache`, line 304-317) 은 처리 토큰 미사용** — 워커는 §4.4 D4 토큰으로 보호되나 첫 호출은 일반 `stockCachePort.increment`. dedupe lease PT5M 만료 후 같은 메시지 재배달 시 첫 보상 두 번 INCR 가능. 발생 확률 작지만 0 은 아니다.
   - 위치: `docs/topics/STOCK-COMPENSATION-RECOVERY.md` §7.2
   - 제안: §7.2 후속 연계 표에 "events.confirmed 첫 보상 호출 토큰화는 후속 토픽 (또는 다른 두 보상 경로 토픽과 함께)" 한 줄 추가. 본 토픽 범위 외 인정.

2. **(minor) Lua 응답 손실 시 워커 동작 미명시** — 워커가 Lua 응답 받기 전 크래시 후 재진입 시, 워커는 (i) Lua 미실행 vs (ii) Lua 실행됨 + INCR 완료 를 구분 못 함. 도메인 INCR 자체는 토큰이 보장하지만 운영 false-positive FAILED 마킹 가능 (재고는 안전하나 운영자가 수동 INCR 추가 시 second-order silent over-restore 위험).
   - 위치: `docs/topics/STOCK-COMPENSATION-RECOVERY.md` §4.4 채택안 본문 또는 §6.2 (d)
   - 제안: plan 단계 단위 테스트에 "Lua 가 `ALREADY_PROCESSED` 반환 시 워커는 markProcessed 로 처리 (성공 간주)" 분기 고정 + admin 도구 후속 토픽에 "FAILED row 처리 전 Redis 토큰 존재 확인" 가이드 인계.

## JSON

```json
{
  "stage": "discuss",
  "persona": "domain-expert",
  "round": 2,
  "task_id": null,

  "decision": "pass",
  "reason_summary": "Round 1 의 4건 finding (워커 크래시 race / insert_fail 메트릭 / 다른 보상 경로 분리 / attempt boundary) 가 Round 2 patch 의 §4.4 D4 Lua 처리 토큰 + worker_reentry 가시화 + §3.5 insert_fail Counter (알람 임계 0) + §0 Non-goals + §7.2 후속 표 + §4.5 D5 boundary 표에서 모두 도메인적으로 충분히 해소됐다. 코드 사실 (redis-stock standalone, DefaultRedisScript 기존 패턴, line 99-119/168-180 일치) 과 교차 검증 완료. 잔여 minor 2건 (events.confirmed 첫 보상 토큰 미사용 / Lua 응답 손실 워커 동작) 은 plan 단계와 후속 토픽으로 흡수 가능.",

  "checklist": {
    "source": "_shared/checklists/discuss-ready.md",
    "items": [
      {
        "section": "domain risk",
        "item": "멱등성 전략이 결정됨 (idempotency key 소스/수명/충돌 처리)",
        "status": "yes",
        "evidence": "docs/topics/STOCK-COMPENSATION-RECOVERY.md §4.3 D3 + §4.4 D4 (Round 2 신규: outboxId 기반 Lua 처리 토큰 SETNX EX P8D + INCRBY atomic) + §5.1 (UNIQUE + ON DUPLICATE KEY no-op + SKIP LOCKED + processedAt 가드 + 토큰)"
      },
      {
        "section": "domain risk",
        "item": "장애 시나리오 최소 3개 식별됨",
        "status": "yes",
        "evidence": "docs/topics/STOCK-COMPENSATION-RECOVERY.md §2.2, §5.2, §7.1 — Redis 일시 장애 / 워커 크래시 (Round 2 §4.4 D4 race window timeline + Lua 토큰 차단) / RDB 다운 (Round 2 §3.5 insert_fail Counter 알람 임계 0) / 부분 실패 / 메시지 손실 / 다중 워커 인스턴스 6종"
      },
      {
        "section": "domain risk",
        "item": "재시도 정책이 정의됨",
        "status": "yes",
        "evidence": "docs/topics/STOCK-COMPENSATION-RECOVERY.md §4.5 D5 — maxAttempts=5, FIXED 5s, RetryPolicyProperties 재사용 + Round 2 신규: attempt 0/1/4/5 boundary 표 (총 5회 시도 후 6번째 진입 직전 FAILED 마킹, available_at 갱신 안 함). §8.5 plan task 에 boundary 단위 테스트 명시"
      },
      {
        "section": "domain risk",
        "item": "PII/민감정보 검토",
        "status": "n/a",
        "evidence": "docs/topics/STOCK-COMPENSATION-RECOVERY.md §4.1 DDL — order_id/product_id/quantity/reason_code/status/available_at/processed_at/attempt/created_at + Lua 토큰 키 compensation:token:{outboxId} 모두 비PII"
      }
    ],
    "total": 4,
    "passed": 3,
    "failed": 0,
    "not_applicable": 1
  },

  "scores": {
    "clarity": 0.92,
    "completeness": 0.90,
    "risk": 0.88,
    "testability": 0.90,
    "fit": 0.92,
    "mean": 0.904
  },

  "findings": [
    {
      "severity": "minor",
      "checklist_item": "멱등성 전략이 결정됨 (idempotency key 소스/수명/충돌 처리)",
      "location": "docs/topics/STOCK-COMPENSATION-RECOVERY.md §7.2",
      "problem": "§4.4 D4 의 Lua 처리 토큰은 워커 측만 적용한다. PaymentConfirmResultUseCase.compensateStockCache (line 304-317) 의 events.confirmed 첫 보상 호출은 여전히 토큰 없이 일반 stockCachePort.increment 호출. dedupe lease PT5M 만료 후 같은 메시지가 재배달되는 case 에서 첫 보상이 두 번 호출되어 INCR 두 번 가능. 발생 확률은 매우 작으나 (5분 안에 메시지 두 번 도달이 일반적이지 않음) 도메인 비대칭이 약간 남는다.",
      "evidence": "산출물 §4.4 D4 'outboxId 기반 Redis 처리 토큰' (워커 측 한정) + 코드 사실: PaymentConfirmResultUseCase.java:304-317 compensateStockCache 의 stockCachePort.increment 호출은 토큰 없이 직접. §5.3 'consumer 측 two-phase lease 만 보호' 명시.",
      "suggestion": "본 토픽 범위 외로 인정. §7.2 후속 연계 표에 'events.confirmed 첫 보상 호출 (compensateStockCache) 의 토큰화는 후속 별도 토픽 — 다른 두 보상 경로 (OutboxAsyncConfirmService.compensateStock / PaymentTransactionCoordinator.compensateStockCacheGuarded) 토픽과 함께 처리' 한 줄 추가."
    },
    {
      "severity": "minor",
      "checklist_item": "장애 시나리오 최소 3개 식별됨",
      "location": "docs/topics/STOCK-COMPENSATION-RECOVERY.md §4.4 채택안 본문 또는 §6.2 (d)",
      "problem": "§4.4 D4 채택안 Lua 스크립트가 atomic SETNX+INCRBY 후 워커에 응답 반환 직전 네트워크 단절 / 워커 크래시 시, 워커는 (i) Lua 미실행 vs (ii) Lua 실행됨+INCR 완료 를 구분 못 한다. INCR 자체는 토큰이 보호하므로 도메인 silent over-restore 는 차단되지만, 워커가 (ii) 인 줄 모르고 attempt++ + next-attempt 갱신 진행 시 다음 폴링에서 토큰만 차단되고 attempt 만 1 늘어 max 도달 → false-positive FAILED 마킹 가능. 운영자가 FAILED row 보고 수동 INCR 추가하면 second-order silent over-restore 위험.",
      "evidence": "산출물 §4.4 D4 채택안 본문이 'Lua 가 ALREADY_PROCESSED 반환 시 no-op' 까지만 명시. 응답 손실 case 의 워커 분기 (markProcessed vs incrementAttempt vs markFailed) 가 명시되지 않음. §3.2 StockCompensationRetryService '@Transactional 로 row update 만 묶음' 으로 Redis 호출과 row update 분리.",
      "suggestion": "plan 단계 단위 테스트에 'Lua 가 ALREADY_PROCESSED 반환 시 워커는 markProcessed 로 처리 (성공 간주)' 분기 고정 + 산출물 §4.4 채택안 또는 §6.2 (d) 통합 테스트에 'Lua 응답 직전 워커 크래시 시 재진입 status 처리 흐름' 한 줄 명시. admin 도구 후속 토픽 인수인계 항목에 'FAILED row 처리 전 Redis 토큰 존재 확인' 가이드 추가."
    }
  ],

  "previous_round_ref": "docs/rounds/stock-compensation-recovery/discuss-domain-1.md",
  "delta": {
    "from_round": 1,
    "previous_decision": "revise",
    "previous_findings_count": {"critical": 0, "major": 2, "minor": 2, "n/a": 0},
    "resolved": [
      {
        "previous_severity": "major",
        "summary": "워커 크래시 후 재시작 시 INCR 이중 호출 race",
        "resolution": "§4.4 D4 race window timeline (t0~t3) 명시 + 채택안 (a) Lua SETNX+INCRBY atomic 처리 토큰 (compensation:token:{outboxId} EX P8D) + (b) stock_compensation_worker_reentry_total Counter + WORKER_REENTRY EventType 동시 적용. §3.5 / §6.1 / §6.2 (d) / §8.6 / §8.9 / §8.10 전 layer 일관 반영. silent over-restore 차단됨."
      },
      {
        "previous_severity": "major",
        "summary": "outbox INSERT 자체 실패 시 메트릭 가시화 누락",
        "resolution": "§3.5 메트릭 표에 stock_compensation_outbox_insert_fail_total Counter + EventType.STOCK_COMPENSATION_OUTBOX_INSERT_FAILED 추가 + '알람 임계 0 (단일 발생도 즉시 알람)' 명시. §3.2 appender 책임 줄도 'LogFmt.error + insert_fail_total++ + EventType 발행 + 재throw 안 함' 으로 보강. §8.9 plan task 가 5종 EventType + 5종 Counter 로 갱신."
      },
      {
        "previous_severity": "minor",
        "summary": "다른 두 보상 경로 (OutboxAsyncConfirmService.compensateStock / PaymentTransactionCoordinator.compensateStockCacheGuarded) 와의 분리 미선언",
        "resolution": "§0 Non-goals 표에 두 경로 회복이 본 토픽 범위 외임을 명시 (사유: 진입 시점·도메인 의미 다름. 후속 별도 토픽). §7.2 후속 연계 표에도 line 99-119 / 168-180 인용 + '같은 outbox 재사용 vs 별도 outbox 결정은 후속에서'. 코드 사실 교차 검증 완료."
      },
      {
        "previous_severity": "minor",
        "summary": "attempt boundary 모호 (== vs >= fence-post)",
        "resolution": "§4.5 D5 본문에 attempt 0(적재) → 1(1회 실패) → 4(4회 실패) → 5(5회 실패=FAILED, available_at 갱신 안 함) boundary 표 명시 + '총 5회 시도 후 6번째 진입 직전 종결'. §8.5 plan task 에 attempt=4→5 PENDING 전환 + attempt=5→FAILED boundary 단위 테스트 명시. §6.1 단위 테스트도 3분기 + boundary 케이스 명시."
      }
    ],
    "introduced": [
      {
        "severity": "minor",
        "summary": "events.confirmed 첫 보상 호출 (compensateStockCache, line 304-317) 토큰 미사용 — 워커만 보호되고 첫 호출은 비-토큰. dedupe lease 5분 안 race 시 silent over-restore 가능 (확률 작음). §7.2 후속 표에 분리 명시 권고.",
        "rationale": "Lua 처리 토큰 도입의 적용 범위가 워커로 한정되어 첫 보상 호출과 워커 호출 간 비대칭이 발생. 본 토픽 책임 외이지만 명시 필요."
      },
      {
        "severity": "minor",
        "summary": "Lua 응답 손실 시 워커 동작 미명시 — 워커가 (i) Lua 미실행 vs (ii) Lua 실행 후 응답 손실 구분 못 함. INCR 자체는 토큰이 보호하지만 false-positive FAILED 마킹 + admin 수동 INCR 추가 시 second-order silent over-restore 위험. plan 단계 단위 테스트와 admin 도구 후속 토픽으로 흡수 가능.",
        "rationale": "Lua 처리 토큰 도입이 새로 만든 boundary 케이스. 산출물 §4.4 채택안이 응답 성공 시나리오만 다루고 응답 손실 분기는 명시 안 함."
      }
    ],
    "decision_change": "revise -> pass",
    "decision_change_rationale": "Round 1 의 major 2건이 모두 도메인적으로 충분히 해소되었고 (race 차단 + 메트릭 알람 임계 0), minor 2건도 명시·boundary 표로 해결됐다. Round 2 신규 2건은 모두 minor 이며 본 토픽 범위 또는 plan 단계 단위 테스트로 흡수 가능. critical 없음, major 없음 → 기계적 판정 pass."
  },

  "unstuck_suggestion": null
}
```
