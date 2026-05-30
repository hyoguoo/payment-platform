# review-domain-1 — Domain Expert, CLEANUP-BATCH-B

## 판정: pass — critical/major 결제 정합성 위험 없음
Severity: critical 0, major 0, minor 2

## 핵심 교차검증 (파일:라인 근거)

1. **502/504 retryable 승격이 GET 조회 경로에만 적용 (n/a, 위험 없음)** — `ProductFeignClient.java:20`, `UserFeignClient.java:20` 모두 `@GetMapping` 단건 조회만. 비멱등 POST 없음. ErrorDecoder(`ProductFeignConfig.java:51-60`/`UserFeignConfig.java:51-60`)가 429/502/503/504 → `*ServiceRetryableException`, 500 → `IllegalStateException`(비-retryable) 유지. 테스트에 502/504 retryable + 500 비-retryable 고정.
2. **Feign 자동 재시도(Retryer) 부재 → 비멱등 재시도 0 (n/a)** — `Retryer` 빈/`spring.cloud.openfeign` 재시도 설정 없음. retryable 예외는 호출자 전파만, 자동 재호출 안 함.
3. **502/504 승격이 confirm 사이클·결제 레코드 생성에 닿지 않음 (n/a)** — user/product 조회는 `PaymentCheckoutServiceImpl.createCheckoutResult:53-57`에서만, 실패 시 `createNewPaymentEvent`(L58) 전 throw → 레코드 미생성, Redis 차감/outbox/Kafka confirm 격리. checkout 진입 거절(503)로 종결. D-NR1d 코드 변경 없음(설계 확정 유지).
4. **FakeMessagePublisher Supplier 전환이 발행 실패 회귀 가드 보존 (n/a)** — `send()`가 `sent.add()` 전 throw. `OutboxRelayServiceTest.java:120,126` failNext 후 `outbox != DONE` 검증 유지. setFailure/setPermanentFailure 현재 호출처 없음.
5. **FakePgEventPublisher defensive copy가 발행 캡처 검증 의미 보존 (n/a)** — topic/key/payload/headers 내용 조회 가능, byte[] 가변 노출만 차단.

## Findings
- **D1 (minor)** — user-service `jacoco.lineCoverageMinimum` 미설정 → 0.0 fallback 게이트 무력. user 조회는 checkout 진입에만 영향, user-service는 결제 상태 전이/멱등성/금전 로직 부재 → 도메인 위험 아님(품질 이슈). 후속 처리 권고. 근거: `user-service/build.gradle`, `PaymentCheckoutServiceImpl.java:53-58`.
- **D2 (minor)** — jacoco 제외 `**/infrastructure/**`가 EOS `ConfirmedEventConsumer`/`JdbcPaymentEventDedupeStore`를 커버리지 집계에서 제외 → 실측 minimum의 EOS 정합성 표현력 약화. 단 `PaymentEosIntegrationTest`(시나리오 1~5)가 실행되어 회귀 가드는 유효(집계 범위 ≠ 테스트 실행). 도메인 위험 아님.

## domain_risk_summary
- state_transition: no change
- idempotency: confirm 사이클 미접촉, Feign Retryer 부재로 비멱등 재시도 0
- pg_failure_mode: 502/504 retryable 승격은 checkout 진입 거절 단계 격리, GET 조회 전용
- money_accuracy: no impact

```json
{"stage":"review","topic":"CLEANUP-BATCH-B","round":1,"persona":"domain-expert","decision":"pass","findings":[{"id":"D1","severity":"minor","area":"coverage-gate","summary":"user-service jacoco minimum 미설정 0.0 fallback, 도메인 위험 아님(품질 이슈)"},{"id":"D2","severity":"minor","area":"coverage-scope","summary":"infrastructure 제외로 EOS 어댑터 집계 제외, 단 PaymentEosIntegrationTest 실행으로 회귀 가드 유효"}]}
```
