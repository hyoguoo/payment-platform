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
