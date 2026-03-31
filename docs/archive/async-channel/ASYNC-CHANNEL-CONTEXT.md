# 비동기 결제 처리 개선 논의

> 작성: 2026-03-30 / 상태: 논의 완료

---

## 1. 프로젝트 컨텍스트

### 목적

Toss Payments 결제 확인 플로우를 세 가지 전략으로 구현하고 k6 벤치마크로 TPS·레이턴시·안정성을 정량 비교한다.

- **Sync**: HTTP 스레드가 Toss API를 직접 동기 호출 → 200 OK
- **Outbox**: DB에 PENDING 기록 후 202 즉시 반환 → 백그라운드 처리
- **Outbox-Parallel**: Outbox + 가상 스레드 활성화

### 현재 구현 — Outbox 즉시 처리 경로

```
HTTP 스레드
  └─ confirm() @Transactional
       └─ PaymentOutbox(PENDING) DB 저장
       └─ TX 커밋
            └─ @TransactionalEventListener(AFTER_COMMIT)
                 └─ @Async("immediateHandlerExecutor")
                      └─ OutboxImmediateEventHandler.handle()
                           └─ claimToInFlight() → Toss API → markDone()
```

`AsyncConfig.java`의 `immediateHandlerExecutor`:
```java
SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("outbox-immediate-");
executor.setVirtualThreads(virtualThreadsEnabled);   // VIRTUAL_THREADS_ENABLED 환경변수
executor.setConcurrencyLimit(concurrencyLimit);       // IMMEDIATE_CONCURRENCY_LIMIT 환경변수
```

---

## 2. 벤치마크 Round 2 결과 (2026-03-30)

### 환경

| 항목 | 값 |
|------|----|
| k6 executor | ramping-arrival-rate |
| MAX_VUS | 1,500 |
| Ramp stages | 100→300→600 req/s (각 20s) |
| HikariCP pool | 300 |
| Toss API 저지연 | 100~300ms |
| Toss API 고지연 | 800~1,500ms |

### 전체 결과

| 케이스 | TPS | HTTP med | HTTP p95 | E2E med | E2E p95 | 에러율 | Dropped |
|--------|----:|---------:|---------:|--------:|--------:|-------:|--------:|
| sync-low | 118.2 | 3,176ms | 5,550ms | 251ms | 2,133ms | 0% | 6,390 |
| sync-high | 102.7 | 1,873ms | 6,904ms | 1,396ms | 5,464ms | 0.08% | 6,580 |
| outbox-low | 101.5 | 2,386ms | 5,803ms | 323ms | 1,767ms | 0.01% | 6,318 |
| outbox-high | 114.1 | 821ms | 7,058ms | 1,471ms | 7,690ms | 0% | 6,032 |
| outbox-parallel-c10-low | 8.1 | 21,685ms | 30,009ms | 338ms | 1,279ms | 35.3% | 11,022 |
| outbox-parallel-c10-high | 2.8 | 27,756ms | 30,099ms | 1,811ms | 5,109ms | 51.6% | 11,265 |
| outbox-parallel-c50-low | 15.5 | 180ms | 30,057ms | 324ms | 477ms | 30.5% | 10,757 |
| outbox-parallel-c50-high | 20.1 | 22,386ms | 32,069ms | 1,188ms | 4,149ms | 35.7% | 11,914 |
| outbox-parallel-c100-low | 23.8 | 14ms | 30,009ms | 319ms | 387ms | 16.0% | 10,472 |
| outbox-parallel-c100-high | 22.4 | 1,636ms | 31,256ms | 1,356ms | 3,424ms | 22.0% | 11,152 |
| outbox-parallel-c200-low | 26.7 | 10ms | 712ms | 322ms | 449ms | 0.27% | 10,096 |
| outbox-parallel-c200-high | 24.0 | 11ms | 29,719ms | 1,257ms | 2,191ms | 3.18% | 10,434 |
| outbox-parallel-c250-low | 38.8 | 16ms | 31,254ms | 318ms | 510ms | 3.9% | 9,352 |
| outbox-parallel-c250-high | 34.2 | 12ms | 30,044ms | 1,291ms | 1,668ms | 4.6% | 9,619 |

---

## 3. 근본 원인 분석

### 현상

Virtual Thread 환경에서 `SimpleAsyncTaskExecutor`에 `setConcurrencyLimit(N)`을 적용했을 때, **N이 충분히 크지 않으면 TPS가 급감하고 에러율이 상승**한다. (Round 2: c10 에러율 35~51%, c100도 16~22%)

### Root Cause 1 — Spring Throttling 메커니즘

`SimpleAsyncTaskExecutor`는 내부적으로 `ConcurrencyThrottleSupport`를 상속한다. `execute(task)` 호출 시 `beforeAccess()`가 실행되며, 동시 실행 수가 `concurrencyLimit`에 도달하면 **호출 스레드를 `throttleLock.wait()`으로 무기한 블로킹**한다.

```java
// ConcurrencyThrottleSupport.beforeAccess() 내부 (Spring 소스)
synchronized (this.throttleLock) {
    while (this.concurrencyCount >= this.concurrencyLimit) {
        this.throttleLock.wait();   // ← 호출 스레드 여기서 멈춤
    }
    this.concurrencyCount++;
}
```

이는 `Backpressure`(부하 분산)가 아닌 **호출자를 직접 잠그는 방식**이다. `BlockingQueue`처럼 별도 큐에 적재하고 반환하지 않고, 호출 스레드 자체가 슬롯이 빌 때까지 기다린다.

### Root Cause 2 — 트랜잭션 훅의 함정

```
HTTP 스레드 → confirm() @Transactional
              └─ TX 커밋 완료
                   └─ @TransactionalEventListener(AFTER_COMMIT) 실행  ← TX lifecycle 안
                        └─ executor.execute(task)  ← throttleLock.wait() 가능성
                             └─ [블로킹 발생 시] HTTP 응답 전송 지연
```

`@TransactionalEventListener(AFTER_COMMIT)`은 TX lifecycle이 완전히 닫히기 전에 실행된다. 이 안에서 `@Async`를 호출하면 **HTTP 응답을 클라이언트에 보내기 전 단계에서 호출 스레드가 블로킹**될 수 있다. k6 VU 입장에서는 Latency 폭증 → VU 적체 → `dropped_iterations` 폭발로 이어진다.

```
HTTP 스레드 → confirm TX 커밋 → AFTER_COMMIT 훅 실행
  → executor.execute(task) 호출
      → 슬롯 N개 모두 점유 중
          → throttleLock.wait() 호출 ← HTTP 스레드 여기서 블로킹
              → 슬롯이 빌 때까지 HTTP 응답 지연
                  → k6 VU가 응답 대기 → VU 적체 → Dropped 폭발
```

### Root Cause 3 — 가상 스레드의 한계

가상 스레드는 **I/O 블로킹**(소켓 대기, DB 쿼리 등)에서는 캐리어 스레드를 반납하고 unmount되어 메모리 효율을 발휘한다. 그러나 `throttleLock.wait()`과 같은 **논리적 블로킹(Java Object monitor wait)** 상황에서는 OS 스레드를 반납하지 못하고 플랫폼 스레드와 동일하게 '정체' 현상을 유발한다.

| 블로킹 유형 | 가상 스레드 동작 |
|-------------|-----------------|
| I/O 블로킹 (소켓, DB) | 캐리어 반납 → unmount → OS 스레드 재사용 가능 |
| `Object.wait()` / `throttleLock.wait()` | 캐리어 고정 → OS 스레드 점유 지속 (플랫폼 스레드와 동일) |
| `synchronized` 내 블로킹 | Pinning → 캐리어 고정 (JEP 491 이전) |

결국 `setConcurrencyLimit`은 가상 스레드의 이점을 살리지 못하고, 오히려 고부하 상황에서 Tomcat worker pool을 고갈시킨다.

### 이상 현상 설명

**N이 작을수록 더 나빠지는 이유**

N=10: 슬롯 거의 상시 점유 → HTTP 스레드 대부분 블로킹 → Tomcat 포화 → 연쇄 실패 (에러율 35~51%)

**c200-low HTTP med=10ms인데 TPS=26.7밖에 안 되는 이유**

- Fast path (슬롯 여유): 10ms 응답 → k6 VU 빠르게 해방
- Slow path (슬롯 포화 순간): HTTP 스레드 블로킹 → 응답 지연 → VU 적체
- p95=712ms가 Slow path 흔적
- 결과적으로 dropped_iterations=10,096 발생 → 실제 TPS 억제

**Little's Law로 본 필요 슬롯 수**

```
L = λ(TPS) × W(평균 latency)

저지연: 600 × 0.2s  = 120개 → c200이 여유 있어서 안정 (에러율 0.27%)
고지연: 600 × 1.15s = 690개 → c200으로 구조적으로 부족 (에러율 3.18%)
```

---

## 4. 개선 방향

### 핵심 원칙

근본 원인을 해결하려면 **Spring Executor의 Throttling 기능(ConcurrencyThrottleSupport)을 완전히 제거**하고, 대신 `LinkedBlockingQueue`를 도입하여 **Producer(HTTP 스레드)와 Consumer(Worker 가상 스레드)를 완전히 격리**해야 한다.

HTTP 스레드는 큐에 `offer()`로 이벤트를 넣는 것만 하고 즉시 반환한다. 큐가 가득 찼을 때는 **즉시 거절(offer() → false)**하고 폴백(OutboxWorker 폴링)으로 위임함으로써, HTTP 스레드의 **비차단(Non-blocking)을 보장**한다.

```
❌ 현재: HTTP 스레드 → execute(task) → throttleLock.wait() → [블로킹] → 응답 지연
✅ 목표: HTTP 스레드 → queue.offer(event) → [즉시 반환] → 202 응답
                              ↓
                       Worker VT → queue.take() → 처리
```

### 방향 A — ThreadPoolTaskExecutor + CallerRunsPolicy (소폭 개선)

```java
ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
executor.setCorePoolSize(300);
executor.setMaxPoolSize(300);
executor.setQueueCapacity(600);
executor.setRejectedExecutionHandler(new CallerRunsPolicy());
```

- 큐 꽉 참 → HTTP 스레드가 직접 처리 후 복귀 (무기한 wait 대신 빠른 처리)
- 기존 `@Async` 구조 유지, 변경 범위 좁음
- **한계**: 여전히 AFTER_COMMIT 훅 안에서 실행 → HTTP 응답이 완전히 자유롭지 않음

### 방향 B — Resilience4j Semaphore Bulkhead (부분 개선)

- Circuit Breaker + Bulkhead 조합으로 Toss API 장애 격리
- `@Async` 구조 유지, 별도 라이브러리 의존성 추가
- 근본 원인(AFTER_COMMIT 훅에서의 블로킹) 미해결

### 방향 C — InMemory BlockingQueue Channel (근본 해결) ★ 검토 중

---

## 5. 방향 C 상세 설계

### 핵심 아이디어

HTTP 스레드와 처리 스레드를 완전히 분리한다. HTTP 스레드는 큐에 이벤트를 넣는 것만 하고 즉시 반환한다.

### 구조

```
[HTTP VT]       confirm TX 커밋 → queue.offer(event) → 202 즉시 반환  [~0ms]
                                          ↓
[Worker VT-1]   queue.take() → claimToInFlight() → Toss API → markDone()
[Worker VT-2]   queue.take() → claimToInFlight() → Toss API → markDone()
...
[Worker VT-N]   queue.take() → (큐 비어있음 → unmount 상태로 대기)
```

고정된 N개의 Worker VT가 각자 `while(true) { take(); process(); }` 루프를 돌린다.
Worker 수 N = 동시 처리 수 상한. 별도 제어 메커니즘 불필요.

### 소비 메커니즘 — `queue.take()`

`LinkedBlockingQueue.take()`는 큐가 비었을 때 `Condition.await()`로 스레드를 잠재운다.
가상 스레드라면 이 시점에 캐리어 스레드(OS 스레드)를 반납하고 unmount된다.

```
큐에 이벤트 없음  →  Condition.await()  →  VT unmount  →  OS 스레드 반납  →  CPU 0%
이벤트 도착       →  offer()가 signal()  →  VT 깨어남   →  즉시 처리
```

busy-wait(CPU 점유 루프)가 아니므로 유휴 시 부하가 없다.

### 현재 방식 대비 차이

| 항목 | 현재 (`setConcurrencyLimit`) | 방향 C (BlockingQueue + Worker) |
|------|------------------------------|----------------------------------|
| HTTP 스레드 차단 | 슬롯 없으면 블로킹 | 없음 (`offer`는 즉시 반환) |
| 동시 실행 수 제어 | `setConcurrencyLimit(N)` | Worker 스레드 수 N |
| Backpressure | HTTP 스레드 wait() → 역효과 | 큐 오버플로우 시 OutboxWorker 폴백 |
| Spring 의존성 | ApplicationEvent + `@Async` | 없음 (순수 Java) |

### 가상 스레드 적용

`spring.threads.virtual.enabled=true`는 Tomcat·`@Async`·`@Scheduled`에만 자동 적용된다.
Worker 스레드는 코드에서 명시적으로 지정해야 한다.

```java
// 가상 스레드 Worker
Thread.ofVirtual().name("outbox-vt-", 0).start(this::loop);

// 플랫폼 스레드 Worker
Thread.ofPlatform().name("outbox-pt-", 0).start(this::loop);

// 확인
Thread.currentThread().isVirtual()  // → true / false
```

벤치마크 변수로 VT 여부와 Worker 수를 제어:

```yaml
outbox:
  channel:
    virtual-threads: ${VIRTUAL_THREADS_ENABLED:true}
    worker-count: ${WORKER_COUNT:200}     # Little's Law: TPS × 평균latency(초)
    capacity: ${CHANNEL_CAPACITY:2000}    # 큐 상한
```

### OutboxWorker와의 역할 분담

```
즉시 처리 성공   → Worker VT가 처리 → PaymentOutbox DONE
큐 오버플로우    → offer() false → PENDING 유지 → OutboxWorker(폴링) 처리
서버 재시작      → 큐 소실 → PENDING 유지 → OutboxWorker 처리
```

큐는 성능 가속기이고, 기존 OutboxWorker 폴링이 안전망 역할을 그대로 유지한다.

### Worker VT의 블로킹 프로파일

Worker VT는 처리 중 두 종류의 블로킹을 만난다.

| 블로킹 지점 | 유형 | VT 동작 |
|-------------|------|---------|
| `claimToInFlight()` / `markDone()` — JDBC | `synchronized` 내 블로킹 | Pinning → 캐리어 고정 (현재 Connector/J 8.3.0) |
| Toss API 호출 — HTTP I/O | I/O 블로킹 | 캐리어 반납 → unmount → 정상 |

현재 `mysql-connector-j:8.3.0`은 JDBC 처리 경로에 `synchronized` 블록을 사용한다. Worker VT가 DB 작업 중 캐리어 스레드를 고정시켜 VT의 확장성 이점이 약화된다. MySQL Connector/J 9.x는 `synchronized`를 `ReentrantLock`으로 교체하여 이 문제를 해결한다.

**대응**: Spring Boot를 3.4.x로 업그레이드 → BOM에 의해 Connector/J 9.1.0 자동 포함 → Worker VT가 JDBC 처리 중에도 캐리어 반납 가능.

### 변경 파일

| 파일 | 변경 내용 |
|------|-----------|
| `build.gradle` | Spring Boot 3.3.3 → 3.4.x 업그레이드 (Connector/J 9.1.0 자동 포함) |
| `AsyncConfig.java` | `immediateHandlerExecutor` 빈 삭제 |
| `OutboxImmediateEventHandler.java` | `@Async` 제거, `channel.offer(orderId)` 한 줄로 단순화 |
| `core/config/PaymentConfirmChannel.java` | 신규 — `LinkedBlockingQueue` 래퍼 |
| `scheduler/OutboxImmediateWorker.java` | 신규 — `SmartLifecycle`, Worker VT N개, `take() → process()` 루프 |
| `scheduler/OutboxProcessingService.java` | 신규 — `OutboxWorker`·`OutboxImmediateWorker` 공통 처리 로직 |
| `scheduler/OutboxWorker.java` | `processRecord()` → `OutboxProcessingService` 위임으로 리팩터링 |
| `application-benchmark.yml` | `IMMEDIATE_CONCURRENCY_LIMIT` → `WORKER_COUNT`, `CHANNEL_CAPACITY` |
| `run-benchmark.sh` | 환경변수 교체 |

---

## 6. 검증 계획

방향 C 전체 구현 전, 최소 변경으로 핵심 가설 검증 예정.

> **"HTTP 스레드가 블로킹에서 벗어나면 dropped_iterations가 줄고 TPS가 올라간다"**

### 검증 기준 (Round 2 c200-low 대비)

| 지표 | Round 2 c200-low | 성공 기준 |
|------|-----------------|----------|
| TPS | 26.7 | 100 이상 |
| HTTP p95 | 712ms | 1,000ms 이하 |
| dropped_iterations | 10,096 | 3,000 이하 |
| 에러율 | 0.27% | 1% 이하 |

---

## 7. 2차 논의 요약 — 이중 트랙 구조 확정

### 설계 요약

| 항목 | 주요 내용                                       | 목적 |
|------|---------------------------------------------|------|
| **이중 트랙 구조** | Fast Track(메모리 큐) + Safety Track(DB 폴링)     | 저지연 처리와 데이터 정합성 동시 확보 |
| **자원 배분** | Worker 수를 DB 커넥션 풀보다 적게 설정     | Tomcat 스레드의 DB 접근 자원 확보 (Starvation 방지) |
| **폴링 주기** | UX를 고려하여 10초 미만 (권장 1~3초)                   | 처리 누락 시 사용자의 최대 대기 시간 단축 |
| **예외 대응** | 큐 오버플로우 시 즉시 처리 포기, DB(Outbox) 기록 유지        | 시스템 과부하 시에도 결제 데이터 유실 방지 |

### 핵심 병목 분석 요약

- **원인**: 가상 스레드를 사용하더라도 Spring Executor의 `concurrencyLimit`이 **호출 스레드(HTTP 요청 처리 중인 가상 스레드)를 `throttleLock.wait()`으로 직접 블로킹**하여 전체 시스템 정체 발생
- **해결**: `LinkedBlockingQueue` 도입으로 HTTP 스레드(Producer)와 Worker(Consumer)를 완전히 격리하고, Worker 수를 DB 커넥션 풀 규모에 맞춰 최적화

```
Before: HTTP 스레드 → execute(task) → throttleLock.wait() → [블로킹] → 응답 지연 → VU 적체
After:  HTTP 스레드 → queue.offer(event) → [즉시 반환] → 202 응답
                               ↓
                        Worker VT-N → queue.take() → Toss API → markDone()
```

### 다음 설계 결정 항목

방향 C 구현을 위해 아래 세 가지 영역의 구체적인 설계가 필요하다.

**1. DB 조회 최적화**
- `PENDING` 상태 결제 건만 빠르게 조회하기 위한 인덱스 및 배치 쿼리 설계
- 폴링 주기 1~3초에서 불필요한 Full Scan을 방지하는 것이 핵심

**2. Worker 로직 구현**
- `OutboxImmediateWorker` (Fast Track): 가상 스레드 N개가 `queue.take() → process()` 루프
- `OutboxPollingWorker` (Safety Track): 일정 주기마다 DB에서 PENDING 건 일괄 조회 및 처리
- Worker 수, 큐 용량, 폴링 주기를 환경변수로 파라미터화

---

## 8. 결정 사항

| 항목 | 결정 | 이유 |
|------|------|------|
| 아키텍처 방향 | 방향 C — `LinkedBlockingQueue` + Worker VT | HTTP 스레드 블로킹 완전 제거 (방향 A·B는 근본 원인 미해결) |
| Worker 스레드 유형 | 가상 스레드 (`Thread.ofVirtual()`) | 고수 연결 수에서 메모리 효율 + 대기 중 캐리어 반납 |
| Worker 수 상한 | DB 커넥션 풀(300)보다 적게 설정 (100~200) | Tomcat 스레드 DB 자원 Starvation 방지 |
| 큐 오버플로우 처리 | `offer()` false → PENDING 유지 → OutboxWorker 폴백 | HTTP 스레드 비차단 보장, 안전망은 기존 폴링이 담당 |
| OutboxWorker 폴링 주기 | 10초 미만 (권장 1~3초) | 처리 누락 시 사용자 최대 대기 시간 단축 |
| JDBC 드라이버 | Spring Boot 3.3.3 → 3.4.x 업그레이드 (Connector/J 9.1.0 자동 포함) | Connector/J 8.x `synchronized` 블록 → Worker VT pinning 발생, 9.x에서 `ReentrantLock`으로 교체됨. Boot BOM 관리로 수동 override 불필요 |
| Spring `@Async` 제거 | `OutboxImmediateEventHandler`에서 `@Async` 제거, `channel.offer()` 만 호출 | AFTER_COMMIT 훅 내 블로킹 완전 차단 |
| 환경변수 파라미터화 | `WORKER_COUNT`, `CHANNEL_CAPACITY` | 벤치마크 케이스별 조정 가능하도록 |
| `OutboxImmediateEventHandler` 존치 | `@TransactionalEventListener(AFTER_COMMIT)` 유지, `@Async` 제거 후 `channel.offer(orderId)` 한 줄로 단순화 | TX 커밋 전 Worker가 깨어나 미커밋 PENDING 레코드를 조회하는 경쟁 조건 방지 (AFTER_COMMIT 시점 필수) |
| 패키지 배치 | `PaymentConfirmChannel` → `core/config/`, `OutboxImmediateWorker` → `payment/scheduler/` | Channel은 순수 Java 구조체로 core가 적합, Worker는 기존 OutboxWorker와 동일 레이어 |
| Worker 생명주기 | `SmartLifecycle` 구현 | Spring 컨테이너 start/stop 훅 연동, graceful shutdown 시 interrupt 후 폴백에 위임 |
| 처리 로직 공유 | `OutboxProcessingService` (신규) 추출, `OutboxWorker`와 `OutboxImmediateWorker` 모두 위임 | 중복 제거, 로직 변경 시 한 곳만 수정 |
| `OutboxImmediateWorker` 테스트 | `SmartLifecycle` 유닛 테스트 — 채널에 이벤트 offer 후 `OutboxProcessingService.process()` 호출 검증, `Awaitility` 사용 | 비동기 Worker 흐름을 결정론적으로 검증 |

---

## 10. 참고 — JDK 21 Virtual Thread Pinning

`synchronized` 내 블로킹 시 VT가 캐리어 스레드를 고정(pinning)시켜 OS 스레드를 반납하지 못한다.
MySQL Connector/J 8.x 등 JDBC 드라이버에 `synchronized` 구간이 존재한다.

| 방법 | 설명 |
|------|------|
| 진단 | JVM 옵션 `-Djdk.tracePinnedThreads=full` |
| 부분 해결 | MySQL Connector/J 9.x 또는 MariaDB Connector 3.3.0+ |
| 완전 해결 | JDK 24 (JEP 491) |
