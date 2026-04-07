# Planned Cleanup / Future Work

> 이 파일은 현재 작업 범위 밖이지만 향후 처리가 필요한 항목을 추적한다.
> discuss 단계 시작 시 다음 작업을 고를 때 이 파일을 참고한다.

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
