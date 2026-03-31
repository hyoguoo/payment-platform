# Outbox Immediate Dispatch 설계

> 최종 수정: 2026-03-28

---

## 문제 정의

현재 `outbox` 전략은 OutboxWorker 스케줄러가 primary path다.
`fixedDelay=1000ms` 주기 + Toss API 딜레이가 그대로 e2e latency에 쌓이고,
sequential 모드에서는 worker가 한 번에 1건씩 처리하므로 고지연 환경에서 처리 속도가 심각하게 저하된다.

**핵심 문제**: outbox가 "안전장치"가 아니라 "주 처리 경로"로 동작하고 있어,
아키텍처 의도(비동기 + 내구성 보장)와 달리 latency가 scheduler 주기에 종속된다.

---

## 영향 범위

- **변경**:
  - `OutboxAsyncConfirmService` — confirm 후 `PaymentConfirmPublisherPort.publish()` 호출 추가
  - `OutboxWorker` — primary 처리 역할 제거, recovery 전용으로 축소. `fixed-delay-ms` 기본값 1000 → 5000
- **신규**:
  - `OutboxImmediatePublisher` — `PaymentConfirmPublisherPort` 구현체. Spring ApplicationEvent 발행
  - `OutboxImmediateEventHandler` — `@TransactionalEventListener(AFTER_COMMIT)` + `@Async` (virtual thread). Toss API 즉시 호출
- **무관**:
  - `SyncConfirmService`, `KafkaAsyncConfirmService`, `KafkaConfirmListener`
  - 도메인 엔티티, 보상 트랜잭션 로직
  - `PaymentConfirmPublisherPort` 인터페이스 (변경 없음, 기존 포트 재활용)

---

## 결정 사항

| 항목 | 결정 | 이유 |
|------|------|------|
| 기존 outbox 전략 | 교체 (전략값 `outbox` 유지) | 새 전략값 추가 없이 동작 변경 |
| dispatch 포트 | 기존 `PaymentConfirmPublisherPort` 재활용 | Kafka와 동일 인터페이스 → 미래 교체 용이 |
| 즉시 처리 트리거 | `@TransactionalEventListener(AFTER_COMMIT)` | TX 커밋 전 이벤트 발행 시 컨슈머가 IN_PROGRESS 미조회 가능 — Kafka와 동일한 타이밍 문제 방지 |
| virtual thread 활용 | `@Async` (Spring Boot 3 + virtual threads enabled) | 플랫폼 스레드 점유 없이 다수 동시 Toss API 호출 |
| OutboxWorker 역할 | recovery 전용 (크래시/재시도 대상 PENDING 처리) | 즉시 처리 성공 시 worker는 처리할 레코드 없음 |
| OutboxWorker 주기 | `fixed-delay-ms` 기본값 5000ms | recovery 전용이므로 1s 주기 불필요, 5s면 충분 |
| HTTP 응답 | 202 Accepted 유지 | 즉시 처리지만 Toss 호출은 비동기 — 클라이언트는 기존과 동일하게 폴링 |

---

## 처리 흐름

```
confirm():
  TX { executePayment(), decreaseStock(), createPendingRecord(PENDING) }
  → confirmPublisher.publish(orderId)   ← PaymentConfirmPublisherPort
  → 202 Accepted

OutboxImmediatePublisher.publish():
  → ApplicationEventPublisher.publishEvent(PaymentConfirmEvent(orderId))

@TransactionalEventListener(AFTER_COMMIT) @Async (virtual thread):
  → claimToInFlight(orderId)
  → validateCompletionStatus()
  → confirmPaymentWithGateway() — Toss API 호출
  → 성공: executePaymentSuccessCompletion(), markDone()
  → 실패(retryable): outbox PENDING 복귀 → worker가 5s 내 재처리
  → 실패(non-retryable): executePaymentFailureCompensation(), markFailed()

OutboxWorker (fixedDelay=5000ms, recovery 전용):
  → PENDING 레코드 조회 (즉시 처리 실패 or 크래시 복구 대상)
  → 기존 처리 로직 동일
```

---

## 제외 범위

- **Kafka 전략 실제 구현**: 추후 별도 작업. 이번은 포트 호환성만 확인
- **OutboxWorker parallel 옵션 제거**: worker가 recovery 전용이 되더라도 병렬 설정은 그대로 유지 (설정 변경 최소화)
- **벤치마크 스크립트 수정**: 전략 교체 후 별도 작업
