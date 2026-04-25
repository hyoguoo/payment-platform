# Planned Cleanup / Future Work

> 이 파일은 현재 작업 범위 밖이지만 향후 처리가 필요한 항목을 추적한다.
> discuss 단계 시작 시 다음 작업을 고를 때 이 파일을 참고한다.

---

## Phase 4 후속: stock commit/restore payment_outbox 이관

**배경:**
T-D2(2026-04-24)에서 `PaymentConfirmResultUseCase`의 stock commit/restore Kafka 발행을
`StockEventPublishingListener` AFTER_COMMIT 리스너로 이관했다.
TX 이미 commit 이후 발행이므로 Kafka broker 장시간 중단 시 이벤트가 영구 유실될 수 있다.
현재는 `stock.kafka.publish.fail.total` counter + ERROR 로그로 감시 유지 중이다 (T-H2).

**제안:**
pg-service의 `pg_outbox` 패턴을 payment-service에 동일 적용한다.

- 방안 A: payment-service에 `stock_outbox` 테이블 신설 후 relay 패턴.
  `StockEventPublishingListener` AFTER_COMMIT 대신 TX 내부에서 `stock_outbox` INSERT → relay 워커 발행.
- 방안 B: `StockCommitRequestedEvent` / `StockRestoreRequestedEvent` 자체를
  기존 `payment_outbox` 에 INSERT 후 relay. 테이블 재사용으로 인프라 추가 없음.

**Grafana 알림:**
`stock.kafka.publish.fail.total` rate 기반 패널 + 임계 알림(1회/분 초과 시 경고) 추가 필요.
현재 `chaos/grafana/` 디렉토리 미존재 — 관측성 스택 재도입 시 함께 추가.

**제안 시점:**
- Phase 4 Toxiproxy Kafka 중단 시나리오 검증 후 이관 여부 결정.
- Phase 4 완료 전에는 counter + ERROR 로그 감시로 운영.

**관련 파일:**
- `payment-service/src/main/java/.../payment/listener/StockEventPublishingListener.java`
- `payment-service/src/main/java/.../payment/infrastructure/adapter/messaging/PaymentOutboxRepository.java` (방안 B 경유)
- (신설) `stock_outbox` 테이블 (방안 A 경유)

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

---

## PG 호출 실종 — T1-18 기본값 + 로컬 프로파일 누락 조합 버그 (Phase 1 완료 직후 발견)

**증상 (2026-04-21, Phase 1 완료 후 로컬 수동 스모크):**
체크아웃·컨펌 요청까지는 정상 진입하지만 PG(Toss) API로 요청이 나가지 않음. `payment_event`는 `IN_PROGRESS`에서 멈추고, `payment_outbox`에는 `PENDING` 행이 생성되지만 Kafka `payment.commands.confirm` 토픽은 전 파티션 offset=0(메시지 0건).

**원인 (중첩된 2개 요인으로 양 경로 모두 dead):**
1. **T1-18에서 `OutboxImmediateEventHandler`에 `@ConditionalOnProperty(name="payment.monolith.confirm.enabled", havingValue="true", matchIfMissing=false)` 추가** — pg-service가 Kafka를 소비한다는 전제로 모놀리스 confirm 경로를 **기본 비활성화**. 그러나 pg-service는 Phase 2.a 이후 신설 예정이라 **현 시점에는 유일한 Kafka 발행 경로가 모놀리스 경로**인데 그게 꺼져 있음.
2. **로컬 bootRun에서 `SPRING_PROFILES_ACTIVE=docker` 미주입 시 `application-docker.yml`의 `scheduler.enabled: true`가 적용 안 됨** → `SchedulerConfig`가 bean 등록 안 됨 → `OutboxWorker.@Scheduled`가 아예 안 도는 상태 → polling fallback도 dead.

**임시 우회책 (로컬 스모크용):**
```
SPRING_PROFILES_ACTIVE=docker
PAYMENT_MONOLITH_CONFIRM_ENABLED=true
SPRING_DATASOURCE_URL='jdbc:mysql://localhost:3306/...'
TOSS_SECRET_KEY=...
```
위 3종 환경변수 주입 시 정상 경로 복구 확인.

**근본 해결 후보 (review 단계에서 택1 결정):**
- (a) 현 설계 유지 + README/로컬 스모크 가이드 문서화 + T1-Gate 문서에 "bootRun 시 `SPRING_PROFILES_ACTIVE=docker` 필수" 경고 추가
- (b) `@ConditionalOnProperty`의 `matchIfMissing`을 **`true`**로 전환해 기본 on, Phase 2.b 스위치오버 태스크(T2b-03 전후)에서 **명시적으로 false flip**. pg-service 도입 전까지 혼란 방지.
- (c) T1-18의 조건부 bean 등록을 제거하고, Phase 2.b에서 모놀리스 경로 코드 자체를 삭제하는 방식으로 재설계 (Strangler Fig 완전 절단).

**제안 시점:**
- review 단계(Phase 1 review)에서 (a)/(b)/(c) 결정
- 결정 후 T2b-03 직전에 구현 반영

**관련 파일:**
- `payment-service/src/main/java/.../payment/listener/OutboxImmediateEventHandler.java` (L18-22 `@ConditionalOnProperty`)
- `payment-service/src/main/java/.../core/config/SchedulerConfig.java` (L12 `@ConditionalOnProperty("scheduler.enabled", "true")`)
- `payment-service/src/main/resources/application-docker.yml` (L44 `scheduler.enabled: true`)
- `docs/phase-gate/phase-1-gate.md` (가이드 추가 필요)
- `docs/MSA-TRANSITION-PLAN.md` T1-18 / T2b-03

---

## QUARANTINED 홀딩 자산 운영자 복구 (QUARANTINED-ADMIN-RECOVERY 토픽)

**배경:**
FCG(Final Confirmation Gate)가 INDETERMINATE 판정을 내리면 `payment_event.status`가 `QUARANTINED`로 전이된다.
이 상태에서는 PG 실제 결제 결과(승인/실패)를 시스템이 자동 판단할 수 없어 재고·돈이 동결된다.
T3.5-07(2026-04-24)에서 QUARANTINED 재고 자동 복구 경로를 철거했다 — PG 실제 승인 건에 이중 복구가 일어날 위험 때문.
현재 운영자 개입 없이는 `QUARANTINED` 상태에서 `DONE`/`FAILED`로 최종 전이할 경로가 없다.

**필요 기능:**
- **(a) Admin API `/admin/payments/{orderId}/reconcile`**: PG 수동 조회 결과를 입력받아 `payment_event` 상태를 `DONE` 또는 `FAILED`로 강제 전이. 재고 정합 재조정(DONE이면 재고 차감 유지, FAILED이면 `StockRestoreEventPublisherPort.publishPayload` 경유 복원) 포함.
- **(b) 운영자 대시보드**: Grafana `payment_outbox` 패널 확장 — `QUARANTINED` 잔여 건수 Gauge + TTR(Time-To-Resolve) 분포 패널 추가.
- **(c) SLA 정의**: TTR 목표(예: 4시간 이내), 알림 임계(예: 1건 이상 30분 지속 시 PagerDuty). `QUARANTINED-ADMIN-RECOVERY` 토픽 설계 시 확정.
- **(d) PG 수동 조회 + 정합 재조정 절차**: 운영자 런북 — 조회 → 판단 → API 호출 → 모니터링 확인.

**제안 시점:**
- Phase 4 이후 별도 토픽 `QUARANTINED-ADMIN-RECOVERY` 에서 설계·구현.
- Phase 4 완료 전에는 위 §운영자 복구 절차(수동 SQL + Grafana 확인)로 운영.

**관련 파일:**
- `payment-service/src/main/java/.../payment/application/usecase/QuarantineCompensationHandler.java`
- `pg-service/src/main/java/.../pg/application/service/PgDlqService.java`
- `payment-service/src/main/java/.../payment/domain/PaymentEvent.java` — `quarantine()` 메서드
- `docs/context/ARCHITECTURE.md` — Quarantine Recovery 섹션 (Error Handling 하위)

---

## @Value 필드 주입 잔존 (K6 범위 밖 — Phase 4 후속 정리)

**배경:**
K6(2026-04-24)에서 7개 파일의 `@Value` 필드 주입을 생성자 파라미터로 이전했다.
아래 4개 파일은 K6 범위 밖이라 미처리 상태다.

**대상 파일 및 필드:**
- `payment-service/.../core/config/StartupConfigLogger.java` — `virtualThreadsEnabled`/`outboxWorkerParallelEnabled`/`outboxWorkerBatchSize`/`outboxWorkerFixedDelayMs` 4개 `@Value` 필드 주입 (로깅 전용 클래스, 생성자 파라미터 전환 또는 `@ConfigurationProperties` 바인딩)
- `payment-service/.../core/common/metrics/PaymentHealthMetrics.java` — `stuckInProgressMinutes`/`maxRetryCount` 2개 `@Value` 필드 주입 (Micrometer Gauge 설정, `KafkaPublisherProperties` 또는 `PaymentMetricsProperties` 그룹화 검토)
- `payment-service/.../payment/infrastructure/config/KafkaProducerConfig.java` — `bootstrapServers`/`commandsConfirmTopic` 2개 `@Value` 필드 주입 (Config 클래스 패턴이 일반적이나, `@ConfigurationProperties`로 묶기 가능)
- `payment-service/.../payment/infrastructure/dedupe/EventDedupeStoreRedisAdapter.java` — `ttl` 1개 `@Value` 필드 주입

**제안 시점:**
- Phase 4 하드닝 후 별도 리팩토링 태스크.
