# discuss-domain-1

**Topic**: CLEANUP-BATCH-B
**Round**: 1
**Persona**: Domain Expert

## Reasoning
NET-RETRY 의 502/504 retryable 승격이 닿는 cross-service 호출은 실제 소스상 `ProductFeignClient`/`UserFeignClient` 의 GET 단건 조회 전용이며(`@GetMapping` 만 존재, 비멱등 POST 0건), 호출 시점도 결제 레코드 생성(`createNewPaymentEvent`) 이전인 checkout 조회 단계라 설계 §7 의 "GET 멱등 → 중복 부작용 없음" 전제가 코드와 정확히 일치한다. 핸들러는 retryable → 503 + Retry-After:5 로 매핑돼 설계 표와 정합한다. 다만 설계가 다루지 않은 도메인 상호작용 1건(checkout 멱등성 IN_PROGRESS 마커 잔존 vs Retry-After 재시도 타이밍)이 있어 major 1건으로 revise 판단한다.

## Domain risk checklist (discuss-ready.md domain risk 섹션)

- [x] **멱등성 전략이 결정됨** — 본 토픽은 새 멱등성 메커니즘을 도입하지 않는다. 502/504 승격이 기존 멱등성(checkout `IdempotencyStore`, confirm `payment_event_dedupe`, Lua dedup token)에 미치는 영향만 검토 대상. §7 이 checkout `Idempotency-Key` store 가 503 후 재시도 중복을 흡수한다고 주장 → 부분 검증됨(아래 F1 단서 첨부).
- [x] **장애 시나리오 최소 3개** — §7 에 3종 식별됨(502 인스턴스 교체 / 504 과부하 / 500 영구 오류). 각 시나리오의 결제 정합성 영향이 기술됨(F2 참조: thundering herd 완화가 Retry-After 고정값 신호뿐임은 명시됨, 비대상 처리 합당).
- [x] **재시도 정책이 정의됨** — 본 토픽은 클라이언트 자동 재시도 wiring 을 도입하지 않음(non-goal, Phase 4 T4-D). ErrorDecoder 는 "재시도 가능 여부 신호(503+Retry-After)" 정확화까지만. 신호 레벨 정책은 정의됨. 500 비-retryable 유지(D-NR1b)도 도메인적으로 타당(아래 추가검토 3).
- [x] **PII/민감정보** — §7 명시대로 신규 로깅·저장 경로 없음. `ErrorDecoder.readBodyQuietly` 의 body 로깅은 기존과 동일(`ProductFeignConfig:60` 의 `body=` warn 은 이미 존재하던 코드, 본 토픽 신규 아님). 신규 PII 유입 0. n/a 근접.

## 도메인 관점 추가 검토 (파일:라인 근거)

1. **502/504 승격의 비멱등 위험 — 코드 교차검증 결과 "없음" 확정.**
   `ProductFeignClient.java:20-21` / `UserFeignClient.java:20-21` 모두 `@GetMapping("/api/v1/{products|users}/{id}")` 단건 조회만 노출. 비멱등 POST/PUT/PATCH 0건. 이 ErrorDecoder 는 두 GET 클라이언트(`@FeignClient(configuration=...)`)에만 한정 등록되므로(`ProductFeignConfig:31-33` NOTE), 502/504 retryable 승격이 상태변경 호출에 새는 경로가 구조적으로 없다. 설계 §7 의 핵심 안전 전제가 소스로 확인됨.

2. **승격된 GET 의 호출 시점 — 결제 레코드 생성 이전임을 확정.**
   `PaymentCheckoutServiceImpl.java:53-63`: `createCheckoutResult` 안에서 user 조회(L54) → product 조회(L55)가 `paymentCreateUseCase.createNewPaymentEvent`(L58)보다 **먼저** 실행된다. 즉 product/user 가 502/504→503 으로 거절되면 supplier 가 조회 단계에서 throw 하여 `createNewPaymentEvent` 미도달 → 결제 레코드 자체가 생성되지 않는다. confirm 경로(`OutboxAsyncConfirmService`)는 product/user Feign 을 직접 호출하지 않음(grep 결과 productPort/userPort 사용 0). 따라서 503 승격은 진행 중 confirm 사이클의 상태 전이·멱등성에 닿지 않는다 — §7 주장 정합.

3. **500 비-retryable 유지(D-NR1b) — 도메인적으로 타당.**
   조회는 멱등이라 500 재시도가 "중복 부작용" 위험은 없으나, 500 은 회복 신호가 아니므로 무의미한 재시도가 같은 결함을 반복 때린다. checkout 진입 거절이지 결제 정합성에 직접 닿지 않으므로 보수적 비승격이 옳다. 기각 근거가 도메인 관점에서 합당.

4. **(MAJOR) checkout 멱등성 IN_PROGRESS 마커 잔존 ×  Retry-After 타이밍 — 설계 미검토 상호작용.**
   `IdempotencyStoreRedisAdapter.java:46-71`: winner 가 `creator.get()`(=`createCheckoutResult`, product/user 조회 포함)에서 예외를 던지면, L59 의 `IN_PROGRESS_MARKER` 가 `IN_PROGRESS_TTL_SECONDS=10`(L38)초 동안 잔존한다(L26-27 Javadoc 명시). 같은 Idempotency-Key 로 들어온 loser 는 polling 최대 2초(`MAX_POLL_COUNT=40 × 50ms`, L39-40) 후 `IllegalStateException`(L86)을 던진다. 그런데 핸들러가 클라이언트에 돌려주는 신호는 `Retry-After: 5`(`PaymentExceptionHandler.java:113-116`)다. **Retry-After(5s) < IN_PROGRESS_TTL(10s)** 이므로, 502/504→503 거절을 받은 클라이언트가 권고된 5초 뒤 같은 Idempotency-Key 로 재시도하면, winner 의 stale 마커가 아직 남아 있어(최대 10초) GET 1단계(L52-53) miss + SET NX 2단계(L58) 실패 → 또 loser polling → 2초 후 다시 503/500 으로 거절될 수 있다. 즉 502/504 승격이 만드는 "재시도 유도"가 checkout 멱등성 마커 TTL 과 어긋나 **첫 권고 재시도가 거의 확정적으로 한 번 더 실패**하는 윈도우가 생긴다. 중복 결제나 silent loss 같은 금전 사고는 아니지만(레코드 미생성 단계), 결제 진입 가용성/사용자 재시도 경험에 직접 닿는 도메인 상호작용이고, 본 토픽이 502/504 를 "재시도하라"는 신호로 새로 승격하는 변경이므로 설계가 이 타이밍 관계를 최소한 명시(혹은 비대상 사유)해야 한다. 현재 §7 은 "같은 Idempotency-Key 면 멱등 store 가 중복 생성을 막는다"까지만 말하고, supplier 실패 시 마커 잔존 동작과 Retry-After/TTL 정렬은 다루지 않았다.
   - 단, `loser` 의 `IllegalStateException` 이 핸들러에서 어떤 상태코드로 환원되는지(500 vs 503)는 본 토픽 변경과 무관한 기존 동작이므로 그 매핑 자체는 지적 대상 아님. 지적은 "502/504 승격으로 재시도 신호를 새로 켜는 결정이 checkout 진입 멱등 마커 TTL 과 정렬됐는지 설계가 짚지 않음".

5. **(MINOR) 통합테스트 합산이 결제 시나리오 커버리지를 반영하는지.**
   D-COV2 의 integrationTest exec data 합산은 `PaymentEosIntegrationTest` 5종(CONFIRM-FLOW §16: EOS commit/abort/중복/multi-product/D7 가드)을 application/usecase LINE 커버리지에 반영하는 효과가 있어, 결제 정합성 핵심 경로(EOS·dedupe·D7)가 baseline 에 들어오는 방향은 옳다. 다만 baseline `minimum` 을 "실측 - 마진"으로 잡으면(§3-3 절차 2) 결제 정합성 분기가 실제로 커버되는지를 보장하는 게 아니라 현재 수치를 동결할 뿐이다 — 게이트 실효화 목적상 충분하나, "커버리지 게이트가 결제 시나리오 누락을 잡는다"는 의미는 아님을 인지(도메인 리스크 낮음, 정보성).

## Findings

- **F1 (major)** — §7 가 502/504→503 승격 후 클라이언트 재시도를 checkout `Idempotency-Key` store 가 흡수한다고만 기술하고, supplier(product/user 조회) 실패 시 `IdempotencyStoreRedisAdapter` 의 IN_PROGRESS 마커가 10초 잔존(`:38,:59,:26-27`)하는 동작과, 핸들러 `Retry-After:5`(`PaymentExceptionHandler:113-116`)가 그 TTL(10s)보다 짧아 첫 권고 재시도가 stale 마커 윈도우에 걸려 재실패할 수 있는 타이밍 관계를 다루지 않음. 중복 결제/금전 사고는 아니나(레코드 생성 이전 단계 — F-검증 2), 502/504 를 "재시도하라"로 새로 승격하는 본 토픽의 결정이 결제 진입 가용성·사용자 재시도 경험에 닿으므로 설계에 명시(또는 비대상 사유 + Retry-After/TTL 정렬 의도)가 필요.
- **F2 (minor)** — §7 장애 시나리오 2(504 thundering herd)에서 완화책이 `Retry-After:5` 고정 신호뿐이고 지수 백오프/CircuitBreaker 는 Phase 4 비대상으로 명시됨. 결정 자체는 타당하나, 다수 클라이언트가 동일 5초 후 동시 재시도 시 동기화된 재시도 파동이 생길 수 있음(jitter 부재). 비대상 범위 합당하므로 정보성 — TODOS/Phase 4 연결만 확인.
- **F3 (n/a)** — PII/민감정보: 신규 로깅·저장 경로 없음. ErrorDecoder body 로깅은 기존 코드. 결제 상태 전이 변경 없음. spotbugs/JaCoCo 항목은 도메인 상태에 무영향.

## JSON
```json
{
  "stage": "discuss",
  "topic": "CLEANUP-BATCH-B",
  "round": 1,
  "persona": "domain-expert",
  "decision": "revise",
  "findings": [
    {
      "id": "F1",
      "severity": "major",
      "area": "idempotency / checkout 진입 가용성",
      "summary": "502/504->503 승격이 새로 켜는 재시도 신호(Retry-After:5)가 checkout IdempotencyStore IN_PROGRESS 마커 TTL(10s)과 어긋나 첫 권고 재시도가 stale 마커 윈도우에 걸려 재실패할 수 있음. 설계 §7 미검토 상호작용.",
      "evidence": [
        "IdempotencyStoreRedisAdapter.java:38,:59,:26-27 (creator 예외 시 IN_PROGRESS 마커 10s 잔존)",
        "IdempotencyStoreRedisAdapter.java:73-87 (loser polling 최대 2s 후 예외)",
        "PaymentExceptionHandler.java:113-116 (retryable -> 503 + Retry-After:5)",
        "PaymentCheckoutServiceImpl.java:53-58 (product/user 조회가 createNewPaymentEvent 보다 먼저 = supplier 실패 시 레코드 미생성)"
      ],
      "recommendation": "§7 에 supplier 실패 시 마커 잔존 동작 + Retry-After(5s) vs IN_PROGRESS_TTL(10s) 정렬 의도를 명시하거나, 정렬 불요 사유(레코드 미생성이라 금전 무해 + 10s 후 자연 만료로 재시도 회복)를 설계에 못박을 것."
    },
    {
      "id": "F2",
      "severity": "minor",
      "area": "failure mode / retry storm",
      "summary": "504 thundering herd 완화가 고정 Retry-After:5 신호뿐(jitter 부재). 동기화된 재시도 파동 가능성. Phase 4 비대상 범위는 합당.",
      "evidence": ["CLEANUP-BATCH-B.md §7 장애 시나리오 2", "INTEGRATIONS.md L91 CircuitBreaker Phase 4"],
      "recommendation": "정보성. 비대상 범위 유지하되 Phase 4(T4-D)에서 백오프 jitter 고려를 TODOS 연결 확인."
    },
    {
      "id": "F3",
      "severity": "n/a",
      "area": "PII / 상태전이 / 빌드위생",
      "summary": "신규 PII/로깅/저장 경로 없음, 결제 상태 전이 변경 없음. spotbugs(D-SB1)·JaCoCo(D-COV1~3)는 도메인 상태 무영향. FakeMessagePublisher 시그니처 변경은 test fixture 한정으로 main 계약(MessagePublisherPort) 무영향.",
      "evidence": ["FakeMessagePublisher.java:15,:81 (test mock, MessagePublisherPort 구현)", "ProductFeignConfig.java:59-63 (기존 body 로깅, 본 토픽 신규 아님)"],
      "recommendation": "조치 불요."
    }
  ],
  "crosscheck": {
    "verified_claims": [
      "ProductFeignClient/UserFeignClient 가 GET 단건 조회 전용 (비멱등 POST 0건) — §7 GET 멱등 전제 확정 (ProductFeignClient.java:20, UserFeignClient.java:20)",
      "retryable -> 503 + Retry-After:5 매핑 — 설계 §3-2 표와 핸들러 정합 (PaymentExceptionHandler.java:113-116)",
      "product/user 조회가 결제 레코드 생성 이전 단계 — §7 '레코드 생성 전 단계' 정합 (PaymentCheckoutServiceImpl.java:53-58)",
      "confirm 경로(OutboxAsyncConfirmService)는 product/user Feign 직접 호출 안 함 — §7 'confirm 상태 전이 무관' 정합",
      "ErrorDecoder 가 두 GET FeignClient 에 한정 등록 — 승격이 다른 호출로 새지 않음 (ProductFeignConfig.java:31-33)"
    ]
  }
}
```
