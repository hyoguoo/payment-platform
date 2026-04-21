# Planned Cleanup / Future Work

> 이 파일은 현재 작업 범위 밖이지만 향후 처리가 필요한 항목을 추적한다.
> discuss 단계 시작 시 다음 작업을 고를 때 이 파일을 참고한다.

---

## loadPaymentEvent catch(Exception e) 범위 축소 검토

**배경:**
`OutboxProcessingService.loadPaymentEvent()`의 `catch (Exception e)` 범위가 넓다.
현재는 어떤 예외든 outbox retry 메커니즘으로 처리하는 것이 의도(주석: `intentionally broad`)이므로 기능 문제는 없다.
그러나 예상치 못한 예외(프로그래밍 오류 등)도 삼켜서 silent retry로 이어질 수 있다.

**제안 개선 방향:**
- DataAccessException 등 특정 예외 타입만 catch하고, 그 외는 재throw
- 또는 이 broad catch를 유지하되 로그 수준을 명확히 하고 알람 체계와 연동

**관련 파일:**
- `payment/scheduler/OutboxProcessingService.java` — `loadPaymentEvent` 메서드

---

## IN_FLIGHT 고아 레코드 즉시 복구

**배경:**
서버 장애(Crash) 또는 Graceful Shutdown 시 `IN_FLIGHT` 상태에서 멈춘 Outbox 레코드가
현재 `in-flight-timeout-minutes: 5`(5분) 이후에야 복구된다.
Toss API 최대 소요 시간이 ~13초(read 10s + connect 3s)임을 감안하면 과도하게 보수적인 값이다.

**제안 개선 방향:**
- **Graceful Shutdown**: `OutboxImmediateWorker.stop()`에서 `inProcessing` Set으로 처리 중 orderId를 추적,
  종료 시 즉시 `resetToPending(orderId)` 호출 (retryCount 증가 없이 IN_FLIGHT → PENDING 복원)
- **Crash**: `in-flight-timeout-seconds: 30`으로 단축 (현재 5분 → 30초)

**관련 파일:**
- `payment/scheduler/OutboxImmediateWorker.java`
- `application.yml`: `scheduler.outbox-worker.in-flight-timeout-minutes`

---

## Redis 캐시 장애 즉시 격리 경로 → FAILED 전환 검토 (별도 discuss)

**배경:**
`PaymentTransactionCoordinator.quarantineForCacheFailure()` (line 123-129) — Redis 재고 캐시 차감 중 `RuntimeException` 발생 시 재시도 없이 곧바로 `QUARANTINED + quarantine_compensation_pending=true`로 격리한다.
이 경로는 현재 설계에서 **유일하게 "재시도 없이 바로 격리"되는 경로**다(다른 격리 경로는 retry 소진 후 FCG 또는 DLQ consumer를 거친다).

**문제 제기:**
Redis 캐시 장애는 **PG 호출 이전**의 인프라 실패다 — 돈의 이동 0, PG 상태 없음, 외부 부수효과 0.
QUARANTINED 시맨틱은 "결제 결과 판단 불가, 수동·자동 개입 대기"인데, 이 경로는 "결제를 시도조차 못 함"에 가까워 FAILED(결제 자체 명확 실패, 재고 rollback, 종결) 시맨틱이 더 적합할 수 있다.

**제안 방향 (discuss 주제):**
- FAILED 전환 시: 사용자에게 즉시 실패 응답·재주문 유도, 보상 큐 적재 없음, `quarantine_compensation_pending` 플래그 미사용 → 운영 단순화
- QUARANTINED 유지 시: Redis 복구 후 재처리 여지 있으나, 동일 요청은 이미 응답이 나간 상태라 실효 재시도 의미 약함
- ADR-13(격리 트리거) / §2-2b-3(2단계 복구) 재평가 + 테스트 재정의 필요

**제안 시점:**
- Phase 1 완료 전후 (별도 소주제 discuss 진입)
- TOPIC 후보: `REDIS-CACHE-FAILURE-POLICY`

**관련 파일:**
- `payment-service/src/main/java/.../payment/application/service/PaymentTransactionCoordinator.java` — `quarantineForCacheFailure()`
- `docs/topics/MSA-TRANSITION.md` §2-2b-3, ADR-13

---

## PaymentConfirmChannel (LinkedBlockingQueue) 단순화 검토

**배경:**
MSA 전환 후 `OutboxImmediateWorker`가 하는 일은 "큐에서 orderId 꺼내 → Kafka send" 단 두 단계로 줄어든다.
모놀리스 시절 Worker가 수행하던 PG 호출·상태 전이·retry 판정 로직은 pg-service로 이관되므로
in-memory 채널 + SmartLifecycle 워커 풀 구조의 존재 이유가 상대적으로 얇아진다.

**대안:**
- AFTER_COMMIT 리스너가 `Executors.newVirtualThreadPerTaskExecutor().submit()`으로 직접 Kafka send를 위임
- 오버플로우 방어는 `ThreadPoolExecutor` `RejectedExecutionHandler` + fallback to Polling으로 동일 효과
- 채널 타입(`PaymentConfirmChannel`) / `OutboxImmediateEventHandler` / `OutboxImmediateWorker` 3개 제거 가능

**유지를 선택한 이유 (현재):**
- ADR-04 보강: pg-service가 동일 4구성 패턴을 독립 복제 구현 예정 (구조 대칭성)
- 오버플로우 메트릭(`isNearFull()`, size gauge) 노출이 명시적
- SmartLifecycle graceful drain 제어가 단순

**제안 시점:**
- Phase 2 완료 후 (양 서비스 공통 리팩토링 대상)
- ADR-04 수정 + discuss 재진입 선행 필요

**관련 파일:**
- `core/channel/PaymentConfirmChannel.java`
- `payment/listener/OutboxImmediateEventHandler.java`
- `payment/scheduler/OutboxImmediateWorker.java`
- `docs/topics/MSA-TRANSITION.md` §4-10 ADR-04 보강
