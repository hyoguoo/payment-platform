# discuss-domain-2

**Topic**: CLEANUP-BATCH-B
**Round**: 2
**Persona**: Domain Expert

## Reasoning
Round 1 major F1(502/504→503 승격이 새로 켜는 Retry-After:5 신호가 checkout IN_PROGRESS 마커 TTL 10s 와 어긋나 첫 권고 재시도가 stale 윈도우에 걸려 재실패)이 Round 2 에서 §7 의 명시적 윈도우 기술 + D-NR1d("윈도우 수용 + 비대상 사유")로 정면 반영됐다. D-NR1d 가 의존하는 세 핵심 주장(마커 TTL 자연 만료, Retry-After:5 < 10s, 금전 무해=레코드 미생성)을 실제 소스로 재교차검증한 결과 전부 코드와 정확히 일치한다 — 따라서 F1 은 도메인 관점에서 충분히 해소됐고, 잔여 critical/major 도메인 리스크 없음.

## Domain risk checklist
- [x] **멱등성 전략** — 새 멱등성 메커니즘 도입 없음. 502/504 승격이 기존 checkout `IdempotencyStore` 와 만드는 상호작용(stale 마커 윈도우)을 §7 + D-NR1d 가 정면으로 다룸. winner 예외 경로의 마커 잔존이 의도된 동작임을 어댑터 Javadoc 이 명시(`IdempotencyStoreRedisAdapter.java:26-27`)하므로 D-NR1d 의 "10s 자연 만료 회복" 근거가 코드 사실과 일치. 해소됨.
- [x] **장애 시나리오 ≥3** — Round 1 에서 검증한 3종(502 인스턴스 교체 / 504 과부하 / 500 영구) 그대로 유지. Round 2 변경은 stale 윈도우 처리 추가뿐이라 시나리오 회귀 없음.
- [x] **재시도 정책** — 클라이언트 자동 재시도 wiring 비도입(non-goal). 신호(503+Retry-After) 정확화까지. D-NR1d 가 Retry-After/TTL 정렬을 명시적 비대상으로 두고 TODOS/Phase 4 위임 — 정직한 deferred 처리.
- [x] **PII/민감정보** — 신규 로깅·저장 경로 없음. `ErrorDecoder` body 로깅 기존 동일. n/a.

## 도메인 관점 추가 검토 (파일:라인 근거)

1. **D-NR1d 의존 주장 1 — winner 예외 시 마커 10s 잔존, TTL 자연 만료 회복 (코드 확정).**
   `IdempotencyStoreRedisAdapter.java:58-67`: winner 가 SET NX 로 `IN_PROGRESS_MARKER`(L59, TTL=`IN_PROGRESS_TTL_SECONDS`=10, L38)를 박은 뒤 `creator.get()`(L63, = `createCheckoutResult`)이 예외를 던지면, 예외 경로에 마커 삭제 로직이 **없어** TTL 만료까지 마커가 잔존한다. L26-27 Javadoc 이 "creator 가 예외를 던지면 lock 이 TTL 만료까지 유지되어 loser 들이 polling timeout 후 예외를 전파한다"로 이 동작을 의도된 설계로 명시. → D-NR1d "10s 자연 만료 회복 + 별도 보상 불요" 근거가 소스와 정확히 일치.

2. **D-NR1d 의존 주장 2 — Retry-After:5 < IN_PROGRESS_TTL 10s 어긋남 실재 (코드 확정).**
   `PaymentExceptionHandler.java:101-116`: `ProductServiceRetryableException`/`UserServiceRetryableException` → `retryableServiceUnavailable` → 503 + `RETRY_AFTER` 헤더 `"5"` 하드코딩(L114-115). 502/504→retryable 승격(D-NR1a)이 이 경로를 새로 타므로 권고 백오프 5s < 마커 TTL 10s 어긋남 윈도우가 실재. §7·D-NR1d 의 윈도우 진단 정확.

3. **D-NR1d 의존 주장 3 — 금전 무해(레코드 미생성) (코드 확정).**
   `PaymentCheckoutServiceImpl.java:53-58`: `createCheckoutResult` 에서 user 조회(L54) → product 조회(L55)가 `paymentCreateUseCase.createNewPaymentEvent`(L58)보다 **먼저** 실행된다. supplier(product/user)가 502/504→503 으로 throw 하면 L58 미도달 → 결제 레코드 자체가 생성되지 않는다. stale 윈도우에 걸린 재거절은 레코드 생성 이전 단계라 중복 결제·silent loss 와 무관. → D-NR1d "금전 무해(레코드 미생성)" 근거가 소스와 정확히 일치.

4. **stale 윈도우가 재고/dedup token 에도 무영향 (확장 확인).**
   checkout 진입 단계는 redis 선차감 DECR·`decrement:done:{orderId}` token(CONFIRM-FLOW §12)이 박히기 전이다(이들은 confirm 단계 `decrementAtomic` 에서만 발생). 따라서 stale 마커 재거절은 재고 캐시·보상 token·product RDB 어디에도 닿지 않는다. D-NR1d 의 "금전·정합성 무해" 범위 판단이 checkout/confirm 경계 관점에서도 정확.

5. **D-NR1d 기각 대안 (c)(마커 즉시 삭제) 비채택의 도메인 타당성.**
   대안 (c)는 `IdempotencyStoreRedisAdapter` 의 winner 예외 경로 동작 변경인데, 이 어댑터의 마커 TTL 만료 정책은 Javadoc(L26-27)에 의도로 못박힌 멱등성 store 동작이고 NET-RETRY(Feign ErrorDecoder) 거주지 밖이다. 이번 토픽(매핑 정확화)에서 멱등성 store 동작을 건드리는 것은 범위 확대 + 회귀 표면 증가라, 비채택이 도메인적으로 합당. 정렬을 Phase 4 로 미룬 것도 결제 진입 가용성(금전 무관)에만 닿는 항목이라 우선순위 판단 정직.

6. **(정보성) Round 1 F2(504 thundering herd jitter 부재) 잔존 — 비대상 유지 적정.**
   D-NR1d 가 Retry-After 를 5s 고정으로 유지하므로 다수 클라이언트 동기 재시도 파동 가능성은 Round 1 과 동일하게 잔존. 단 자동 재시도 wiring 비도입(non-goal) + Phase 4(T4-D) 위임 범위라 신규 리스크 아님. 정보성.

## Findings

- **F1 (n/a, Round 1 major 해소 확인)** — Round 1 F1(502/504→503 승격 ↔ checkout IN_PROGRESS 마커 TTL 어긋남 윈도우 미검토)이 §7 의 명시적 윈도우 기술 + D-NR1d("윈도우 수용 + 비대상 사유 + Phase 4 위임")로 정면 반영됨. D-NR1d 가 의존하는 세 주장(마커 10s 자연 만료, Retry-After:5 < TTL 10s, 금전 무해=레코드 미생성)을 소스 재교차검증 결과 전부 일치(`IdempotencyStoreRedisAdapter.java:26-27,38,58-67`, `PaymentExceptionHandler.java:113-116`, `PaymentCheckoutServiceImpl.java:53-58`). 금전 무해 근거 타당, 비대상 사유(범위 확대 회피) 정직. 해소 확인.
- **F2 (minor, Round 1 유지)** — 504 thundering herd 완화가 고정 Retry-After:5(jitter 부재). D-NR1d 가 5s 고정 유지를 확정했으므로 동기 재시도 파동 가능성 잔존하나, 자동 재시도 비도입 + Phase 4 위임 범위라 신규 리스크 아님. 정보성, 조치 불요.
- **F3 (n/a)** — PII/로깅/저장 경로 변경 없음. 결제 상태 전이 변경 없음. spotbugs(D-SB1)·JaCoCo(D-COV1~3)는 도메인 상태 무영향. FakeMessagePublisher 시그니처 변경은 test fixture 한정으로 main 계약 무영향.

## JSON
```json
{
  "stage": "discuss",
  "topic": "CLEANUP-BATCH-B",
  "round": 2,
  "persona": "domain-expert",
  "decision": "pass",
  "findings": [
    {
      "id": "F1",
      "severity": "n/a",
      "area": "idempotency / checkout 진입 가용성",
      "summary": "Round 1 major F1(502/504->503 승격 ↔ checkout IN_PROGRESS 마커 TTL 10s 어긋남 윈도우 미검토)이 §7 명시 기술 + D-NR1d(윈도우 수용 + 비대상 사유 + Phase 4 위임)로 정면 해소됨. 의존 주장 3종 소스 재교차검증 전부 일치 — 금전 무해 근거 타당.",
      "evidence": [
        "IdempotencyStoreRedisAdapter.java:58-67 (winner creator.get() 예외 시 마커 삭제 없음 → TTL 만료까지 잔존)",
        "IdempotencyStoreRedisAdapter.java:26-27 (Javadoc: creator 예외 시 lock TTL 만료까지 유지 — 의도된 동작)",
        "IdempotencyStoreRedisAdapter.java:38 (IN_PROGRESS_TTL_SECONDS=10)",
        "PaymentExceptionHandler.java:113-116 (retryable -> 503 + Retry-After:5 하드코딩)",
        "PaymentCheckoutServiceImpl.java:53-58 (user/product 조회가 createNewPaymentEvent 보다 먼저 = supplier 실패 시 레코드 미생성 → 금전 무해)"
      ],
      "recommendation": "조치 불요. D-NR1d 의 Phase 4/TODOS 위임(Retry-After/TTL 정렬)이 후속에서 추적되는지만 확인."
    },
    {
      "id": "F2",
      "severity": "minor",
      "area": "failure mode / retry storm",
      "summary": "504 thundering herd 완화가 고정 Retry-After:5(jitter 부재). D-NR1d 가 5s 고정 유지 확정 — 동기 재시도 파동 가능성 잔존하나 자동 재시도 비도입 + Phase 4 위임 범위라 신규 리스크 아님.",
      "evidence": [
        "PaymentExceptionHandler.java:113-116 (Retry-After 고정 5)",
        "CLEANUP-BATCH-B.md §7 장애 시나리오 2",
        "INTEGRATIONS.md L91 CircuitBreaker Phase 4"
      ],
      "recommendation": "정보성. 비대상 범위 유지. Phase 4(T4-D)에서 백오프 jitter 고려를 TODOS 연결만 확인."
    },
    {
      "id": "F3",
      "severity": "n/a",
      "area": "PII / 상태전이 / 빌드위생",
      "summary": "신규 PII/로깅/저장 경로 없음, 결제 상태 전이 변경 없음. spotbugs(D-SB1)·JaCoCo(D-COV1~3)는 도메인 상태 무영향. FakeMessagePublisher 시그니처 변경은 test fixture 한정으로 main 계약(MessagePublisherPort) 무영향.",
      "evidence": [
        "PaymentCheckoutServiceImpl.java:34-51 (checkout 경로 변경 없음)"
      ],
      "recommendation": "조치 불요."
    }
  ],
  "crosscheck": {
    "verified_claims": [
      "winner creator.get() 예외 시 IN_PROGRESS 마커가 TTL(10s) 만료까지 잔존 — 예외 경로 마커 삭제 로직 부재 + Javadoc 의도 명시 (IdempotencyStoreRedisAdapter.java:26-27,58-67)",
      "Retry-After:5 < IN_PROGRESS_TTL 10s 어긋남 실재 — retryable -> 503+Retry-After 5 하드코딩 (PaymentExceptionHandler.java:113-116)",
      "supplier(product/user) 조회가 createNewPaymentEvent 이전 단계 — 502/504 실패 시 결제 레코드 미생성 → 금전 무해 (PaymentCheckoutServiceImpl.java:53-58)",
      "stale 윈도우가 redis 선차감/decrement:done token/product RDB 에 무영향 — 이들은 confirm 단계에서만 발생 (CONFIRM-FLOW §12)",
      "D-NR1d 기각 대안(c) 마커 즉시 삭제는 멱등성 store 동작 변경 + NET-RETRY 거주지 밖 — 비채택이 minimal-change 와 정합"
    ]
  }
}
```
