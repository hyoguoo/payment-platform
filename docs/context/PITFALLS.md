# Domain Pitfalls

> 최종 갱신: 2026-05-17 (PAYMENT-EOS-TRANSITION 봉인 — EOS 도입 학습 함정 4건 등재)
> 비동기 confirm + 다중 서비스 분산 트랜잭션 환경에서 학습된 함정 목록.

## 1. AOP 우회 → audit trail 누락

**증상**: `paymentEvent.done(approvedAt)` 직접 호출 후 `saveOrUpdate(paymentEvent)` 하면 `payment_history` row 가 생기지 않는다. 추후 사고 재구성 시 상태 전이 흔적이 사라짐.

**원인**: `@PublishDomainEvent` + `@PaymentStatusChange` AOP 가 `markPaymentAsDone` / `markPaymentAsFail` / `markPaymentAsRetrying` / `markPaymentAsQuarantined` 메서드에만 부착돼 있다. 직접 도메인 메서드 + save 호출은 AOP 우회.

**처방**:
- 모든 상태 전이를 `PaymentCommandUseCase` 위임 메서드로 일원화
- 직접 `done() + save()` 패턴 코드 리뷰에서 차단

## 2. Try 블록에서 외부 변수 재할당

**증상**: catch 분기에서 외부 변수 null/sentinel 처리 코드가 늘어나고, race condition / 부분 초기화 디버깅이 어려움.

**원인**:
```java
ResultType result = null;
try { result = service.call(); } catch (Exception e) { /* */ }
process(result);  // result 가 null 일 수 있음
```

**처방**: private 메서드 추출 + 반환값으로 의도 표현. 변수 재할당 패턴 자체를 금지.

## 3. `@Transactional` 안에서 동기 Kafka publish

**증상**: Kafka broker 가 느려지면 `KafkaTemplate.send().get()` 가 트랜잭션 안에서 대기 → Hikari 커넥션 점유 → 풀 고갈 → cascade 장애.

**처방**:
- TX 안에서는 `ApplicationEventPublisher.publishEvent()` 만
- 실제 Kafka publish 는 `@TransactionalEventListener(AFTER_COMMIT)` 리스너에서
- `@Transactional(timeout=5)` 명시로 외부 호출 끼어 있는 경로의 점유 한계 시각화

## 4. fire-and-forget Kafka publisher

**증상**: `whenComplete((res, ex) -> ...)` 로 콜백 등록만 하고 main thread 가 outbox.done() 처리 → broker 미도달 시 메시지 유실.

**처방**:
- `KafkaTemplate.send().get(timeout)` 동기 호출
- broker 도달 보장 후 outbox 상태 변경
- timeout 명시로 무한 대기 방지

## 5. `catch (Exception)` swallow

**증상**: 워커/aspect 에서 모든 Exception 잡고 로그 한 줄 + return → 실제 장애 신호가 묻힘.

**처방**:
- 가능하면 catch 자체를 좁히거나(특정 RuntimeException) 제거
- 워커 등 절대 죽으면 안 되는 경로만 catch + ERROR 승격 + 메트릭(`*_fail_total` 카운터)
- 단순 swallow + INFO/WARN 로그는 사고 가시화 실패

**적용 사례 (STOCK-COMPENSATION-RECOVERY 봉인)**:
- `PaymentConfirmResultUseCase.compensateStockCache` 의 try/catch swallow + WARN 한 줄 + 진행 패턴이 본 함정의 전형 — 보상 호출이 RuntimeException 으로 끝나도 후속 `markPaymentAsFail` 이 진행되어 재고 silent loss 발생
- 처방: catch 제거 + Spring Kafka `DefaultErrorHandler` (`KafkaErrorHandlerConfig`) 가 retry / DLQ 책임. `handleFailed` 호출 순서를 보상 → `markPaymentAsFail` 로 뒤집어 보상 먼저 끝내도록 강제. 보상 자체는 Lua atomic + dedup token 으로 멱등 보장

## 6. `LocalDateTime.now()` 직접 호출

**증상**: 테스트에서 시간 위조 불가 → 시간 의존 분기를 단정하기 어려움.

**처방**:
- `LocalDateTimeProvider` 주입
- 테스트는 위조된 Provider 로 시각 고정

## 7. 종결 상태 재진입

**증상**: 다중 워커 / 메시지 재배달 환경에서 이미 DONE 인 결제에 또 done() 또는 quarantine() 호출 → 도메인 불변식 위반.

**처방**:
- `PaymentEventStatus.isTerminal()` 단일 진실 원천(SSOT) 사용 — exhaustive switch
- `paymentEvent.quarantine()` 등에 isTerminal 사전 가드 + `IllegalStateException` 이중 가드
- `QuarantineCompensationHandler.handle` 진입 직후 `isTerminal()` 체크 → terminal 이면 no-op + LogFmt

## 8. AMOUNT_MISMATCH 단방향 검증

**증상**: pg 측에서만 amount 검증하고 payment 측은 받은 amount 신뢰 → pg 측 버그 / 메시지 변조 시 잘못된 amount 로 done() 처리.

**처방**:
- pg 발행 시 APPROVED 라면 amount/approvedAt non-null 강제
- payment 수신 시 `paymentEvent.totalAmount` vs `message.amount` 대조 → 불일치 시 QUARANTINED

## 9. dedupe TTL ≠ Kafka retention

**증상**: Kafka retention(7d) 안에 메시지가 재배달되는데 dedupe TTL 이 1h 면 중복 처리 발생.

**처방**:
- dedupe TTL 기본 P8D (Kafka retention 7d + 복구 버퍼 1d)
- 모든 모듈의 dedupe TTL 정렬 (`StockCommitUseCase.DEDUPE_TTL = Duration.ofDays(8)`)
- 만료 행 청소는 `DedupeCleanupWorker` (`@Scheduled`, payment/product) 가 `expires_at < now` 기준으로 DELETE — TTL P8D > Kafka retention 7d 관계상 삭제 대상은 이미 재배달 가능 윈도우를 벗어난 행뿐이라 멱등에 무해 (EOS-FOLLOWUP-CLEANUP)

## 10. Single-phase mark with long TTL — payment-service 측 폐기

**증상 (이전)**: 메시지 단위 dedupe lease 가 처리 후속 RDB 작업과 같은 TX 가 아니어서 부분 실패 시 silent loss 위험.

**처방 (현재)**: payment-service `EventDedupeStore` (two-phase lease) 패턴은 STOCK-COMPENSATION-RECOVERY 봉인에서 폐기.
- 재고 멱등성은 Lua atomic dedup token (`decrement:done:{orderId}` / `compensation:done:{orderId}` SETNX P8D) 으로 같은 Lua 안에서 atomic 보장
- 메시지 retry / DLQ 는 Spring Kafka `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` (retry 5회 한도 + `payment.events.confirmed.dlq`) 가 native 책임
- pg-service `EventDedupeStore.markSeen` (Redis SET NX EX 1h) + pg_inbox UPSERT 2-layer 모델은 그대로 (RDB 같은 TX 안에서 atomicity 강제됨)

## 11. 보상 트랜잭션 중복 진입

**증상**: 다중 워커 동시 진입 또는 retry 후 응답 처리 직전 크래시 → 같은 결제에 재고 INCR 두 번 → 재고 발산.

**처방**:
- `executePaymentFailureCompensationWithOutbox` 진입 시 TX 내 outbox + event 재조회
- outbox 가 IN_FLIGHT AND event 가 비종결일 때만 재고 INCR
- 한쪽이라도 종결된 흔적 있으면 재고 복구 skip + warn 로그

**STOCK-COMPENSATION-RECOVERY 흐름의 보강**:
- `handleFailed` / `handleQuarantined` 의 보상은 `compensateAtomic(orderId, orders)` Lua 1회 호출로 통합 — `compensation:done:{orderId}` SETNX P8D dedup token 이 결제 단위 멱등 보장
- 동일 orderId 재진입 시 Lua 가 `ALREADY_DONE` early return → 재고 INCR 0회. 다중 워커 race 시에도 token 1회만 박힘

## 12. Virtual Thread / Async 경계 MDC 손실

**증상**: HTTP → @Async → Kafka 경계에서 traceparent 가 끊김 → 사고 시 trace 추적 불가.

**처방**:
- 가상 스레드 executor 는 `ContextAwareVirtualThreadExecutors.newWrappedVirtualThreadExecutor()` 로 생성 — OTel Context + MDC 둘 다 호출자 스레드에서 자동 캡처해 새 VT 에 set
- Kafka producer ProducerFactory 자체 생성 시에도 `ObservationRegistry` 를 명시적으로 wiring (자동 wiring 이 닿지 않는 비표준 경로)
- `@Async` 메서드는 위 executor 를 사용하는 빈(`outboxRelayExecutor` 등) 을 `@Async("...")` 로 지정
- pg-service 의 in-memory channel 처럼 호출자/소비자 사이 시간차가 있어 Executor 자동 캡처가 안 통하는 경계는 `OutboxJob` 같은 작업 객체에 두 컨텍스트를 동봉해 워커가 직접 set/원복
- consumer 측 traceparent → MDC 복원은 `spring.kafka.listener.observation-enabled=true` + `MdcContextPropagationConfig` 의 `Slf4jMdcThreadLocalAccessor` 등록이 자동 처리

## 13. NicePay paidAt offset 정규화

**증상**: NicePay 응답의 `paidAt` 이 `+09:00` offset 으로 오는데 ConfirmedEventPayload 직렬화 시 제대로 안 들어가면 payment 측 역직렬화에서 `OffsetDateTime.parse` 실패.

**처방** (직전 fix):
- pg-service 측에서 raw 문자열 보존 → `approvedAtRaw(String)` 으로 ConfirmedEventPayload 에 전달
- payment 측에서 `OffsetDateTime.parse(approvedAtRaw).toLocalDateTime()` 변환

## 14. ddl-auto: update 와 Flyway 혼용

**증상**: 한 서비스에 Flyway 도입하면서 다른 서비스만 `ddl-auto: update` 로 두면, 운영 환경에 컬럼 추가 같은 변경을 ad-hoc SQL 메모로 따로 관리해야 함.

**처방** (이번 봉인 작업):
- 4서비스 모두 Flyway + `ddl-auto: validate` 통일
- schema 변경은 V 파일로만, JPA Entity 변경 시 V N+1 추가
- `flyway_schema_history` 테이블이 단일 진실 원천

## 15. PG 호출 직접 호출 — 아키텍처 경계 위반

**증상**: payment-service 안에서 직접 Toss/NicePay HTTP 호출하면 PG 벤더 회복성/멱등성/dedupe 가 도메인 코드와 섞여 망가지기 쉬움.

**처방** (MSA-TRANSITION):
- payment-service 는 PG 호출 안 함
- pg-service 만 벤더 호출. payment 와는 Kafka 양방향 메시지로만 통신
- `PgGatewayPort` 추상화 + Strategy 패턴 (Toss / NicePay / Fake)

## 16. 재고 SoT 모델 — RDB 가 SoT, redis-stock 은 선차감 캐시

**증상**: payment 가 Redis 만 차감했는데 product RDB 와 발산.

**원인**: 두 저장소의 역할이 분리되어 있다.
- product-service mysql `stock` 테이블 = **진짜 잔고 (SoT)**. APPROVED 결제만 누적 차감 (`payment.events.stock-committed`)
- redis-stock = payment-service 의 **선차감 게이트 캐시**. confirm 진입 시 Lua 원자 DECR 로 빠른 reject

**처방** (이번 stock 모델 정리):
- payment 가 Redis 자기 책임으로 관리: confirm 진입 시 DECR, FAILED/QUARANTINED 회신 시 INCR 보상
- product DB 차감은 APPROVED 시만 — 복원(restore) 메시지 자체가 폐기됨 (애초에 차감 안 됐으므로 복원 불필요)
- 부팅 직후 1회 `scripts/seed-stock.sh` 가 mysql-product → redis-stock 으로 동일 수치 시드. 이후 동기화 메커니즘은 의도적 부재
- AMOUNT_MISMATCH 격리 시에도 Redis INCR 보상 — 결제 미성립이라 일관

**알려진 한계**:
- 부팅 외 시점에서 product RDB 가 외부(관리자/입고) 변경되면 Redis 와 발산. 추후 시점·정책 별도 정리 필요 (TODOS)
- 운영 환경에서 redis-stock 데이터 lost 시 정합성 회복 메커니즘은 부팅 재시드뿐 — payment 가 진행 중이면 redis 키 부재로 confirm DECR 결과가 음수일 수 있음

## 17. Redis crash + AOF fsync race window

**증상**: Redis 가 `decrementAtomic` Lua 응답을 클라이언트에 돌려준 직후 crash. AOF 가 `appendfsync=everysec` 면 최대 1초치 명령이 디스크에 안 박혀 있을 수 있음 → 재기동 시 token / 재고 상태가 “명령 직전”으로 복원 → 같은 orderId 재진입이 ALREADY_DONE 이 아닌 OK 로 잡혀 재고 발산.

**처방** (수용된 trade-off):
- redis-stock 의 AOF 를 `appendfsync=always` 로 운영 (`docker/docker-compose.infra.yml`) — 매 명령 fsync
- throughput 감소 trade-off 인정. cluster 환경 / 더 강한 보장은 별 토픽
- 이론적으로 디스크 latency 수준의 race window 는 잔존 (L2 알려진 한계)

## 18. 보상 끝난 결제의 재confirm cascade (L6 / L7)

**증상**: P8D 안에서 동일 orderId 가 `decrement:done` + `compensation:done` 둘 다 박힌 상태로 새 confirm 사이클로 재진입. `decrementAtomic` 이 ALREADY_DONE → SUCCESS 매핑되어 재고는 추가 차감 안 되지만, 벤더가 APPROVED 회신하면 product RDB 만 차감 + redis 보상 +1 잔존 → 발산.

**원인**:
- L6: `OutboxAsyncConfirmService.compensateStock` 같은 폐기 경로 또는 외부 force resetToReady 가 동일 orderId 재confirm 을 띄울 때 발생 가능
- L7: `markPaymentAsFail` 영구 실패 → DLQ → Reconciler `resetToReady` → 새 confirm. PG 멱등성으로 보통 차단되나 이론적 가능성은 인정

**처방** (수용된 trade-off, 본 토픽 범위 외):
- 정상 흐름에서는 결제 1건 = orderId 1건 = `decrementAtomic` 1회라 발생 가능성 매우 낮음
- 본 cascade 를 차단하는 코드는 STOCK-COMPENSATION-RECOVERY 범위 밖, 알려진 한계로 인정
- PHASE2 에서 token DEL 정책 정밀화 또는 admin QUARANTINED fallback 별 토픽 결정 (TODOS `STOCK-COMPENSATION-OTHER-PATHS` 참고)

## 19. QUARANTINED 결제는 status 폴링이 영원히 PROCESSING

**증상**: 클라이언트가 `GET /api/v1/payments/{orderId}/status` 폴링하는데 결제가 격리됐는데도 응답이 영영 `PROCESSING` 으로만 옴. 폴링 무한 루프.

**원인**: `PaymentStatusServiceImpl.mapEventStatus` 의 switch 가 DONE / FAILED 만 명시적 매핑하고 나머지는 default = `PROCESSING`. QUARANTINED 는 도메인상 `isTerminal()` = false (후속 복구 워커가 보정/포기 결정하는 대기 상태) 라서 default 분기로 PROCESSING 응답이 되지만, 실제로는 자동 진행 메커니즘이 없어 admin 강제 전이 없으면 영영 PROCESSING 만 응답한다.

**처방** (단기):
- 클라이언트가 무한 폴링하지 않도록 timeout 정책을 client 측에 둠
- admin 도구로 격리 결제를 검토 후 DONE / FAILED 강제 전이 → 폴링 자연 종료

**처방** (장기):
- `PaymentStatusResult.StatusType` 에 `QUARANTINED` 추가 + `mapEventStatus` 명시 매핑 → 클라이언트가 격리 상태를 인지하고 polling 종료
- TODOS.md 의 admin 복구 도구(TQ-2 QUARANTINED-ADMIN-RECOVERY) 와 함께 진행

## 20. INSERT IGNORE 0 row 시 비즈니스 skip 해도 발행은 항상 진행 (EOS 도입)

**증상**: `payment_event_dedupe` INSERT IGNORE 가 0 row 를 반환할 때 비즈니스 처리(결제 상태 전이)도 skip 하고 재고 확정 발행도 skip 하면 재배달 시 product-service 가 stock-committed 를 수신하지 못해 재고 차감이 영영 누락된다.

**원인**: 0 row = "이미 처리됐음" 이지, "발행 안 해도 됨" 이 아니다. product-service 의 `JdbcEventDedupeStore` 가 stock-committed idempotencyKey 기반으로 중복 차감을 막으므로, 발행이 여러 번 와도 재고는 1번만 차감된다.

**처방** (위키 line 141 보장):
- `markIfAbsent()` 0 row 시 비즈니스 처리는 skip 하되 `PaymentOrder` 순회 + `stockCommittedKafkaTemplate.send` 는 **항상 진행**.
- product-service `JdbcEventDedupeStore` (INSERT IGNORE, 같은 TX) 가 이중 차감 방어를 담당.

## 21. QUARANTINED 결제에 늦은 APPROVED 메시지 — D7 가드 없으면 DLQ silent 분기

**증상**: 결제가 QUARANTINED 된 이후 뒤늦게 APPROVED 결제 결과 메시지가 도착 (pg-service retry 지연 등). 가드 없으면 `handleApproved` 가 실행 → `markPaymentAsDone` 에서 `IllegalStateException` (QUARANTINED → DONE 비허용 전이) → not-retryable 즉시 DLQ → 재고 발행 0 + 상태 불일치 로그 없음.

**처방** (D7 가드 — PET-3 / PET-8):
- `handle()` 진입 직후 `paymentEvent.getStatus().canApplyConfirmResult()` 체크.
- false (QUARANTINED 포함 종결 상태) → `LogFmt.warn` + noop return. DLQ 전혀 건드리지 않음.
- D7 가드 변경 시 `PaymentEventStatusSplitMethodTest` (분리 메서드 검증) + `PaymentEventStatusCrossInvariantTest` (`canApplyConfirmResult` ↔ `canCompensateStock` 교차 동조 불변식) 가 회귀 탐지 (DR-3).

## 22. multi-product 결제의 idempotencyKey 결정성 — StockEventUuidDeriver 보존 이유

**증상**: EOS 전환 시 `StockOutbox` 묶음 삭제하면서 `StockEventUuidDeriver` 까지 함께 삭제하면, 재고 확정 발행의 idempotencyKey 가 결정적 UUID 가 아닌 임의 UUID 로 바뀐다 → 재배달 시 product-service dedupe 가 "처음 보는 key" 로 인식 → 재고 N건 중복 차감.

**처방** (DR-1 / D8):
- `StockEventUuidDeriver.derive(orderId, productId, "stock-commit")` 는 StockOutbox 묶음 삭제와 무관하게 **반드시 보존**.
- PET-9 삭제 대상 명세에 "유지 대상 (삭제 금지)" 블록으로 명시.
- `PaymentEosIntegrationTest` 시나리오 #4 (multi-product + 재배달 dedupe) 가 회귀 가드.

## 23. rolling deploy 순서 역전 — EOS abort invisible 보장 무력화

**증상**: payment-service EOS 전환(producer tx 발행)이 먼저 배포되고 product-service `isolation.level=read_committed` 적용이 나중에 오면, abort 된 stock-committed 메시지가 product-service 에 read_uncommitted 로 보인다. abort 직후 재배달 전에 product-service 가 abort 메시지를 처리하면 dedupe 키가 박혀 재배달 메시지를 중복으로 판정 → stock-committed 0건 처리 + 재고 차감 0 → 정합 발산.

**처방** (D6 deploy 순서 — DR-4):
- product-service `read_committed` 먼저 배포 → payment-service EOS 발행 나중 배포.
- 역순(payment EOS 먼저)이면 abort 가 가시화되는 **spurious 차감 윈도우** 발생.
- PR 본문 또는 운영 배포 체크리스트에 deploy 순서를 명시한다.

## 관련 자료

- 도메인 학습 자료: archive 안 토픽별 `COMPLETION-BRIEFING.md`
- 자주 겹치는 우려: `CONCERNS.md`
- 향후 처리 항목: `TODOS.md`
