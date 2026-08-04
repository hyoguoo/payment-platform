# Domain Pitfalls

> 최종 갱신: 2026-07-11 (DLQ-QUARANTINE-RECOVERY — §20 잔여 한계 서술을 "DLQ 적체분 관리자 수동 재주입(`DlqReprocessUseCase`) 복구, 자동 재시도는 후속"으로 정정). 이전: 2026-06-27 (ALERTING-RULES-AND-FAULT-DRILL — §24 `kafka_brokers` dead branch 함정 등재). DOCS-CONSISTENCY-OVERHAUL Task 9(2026-07-03)에서 §17/§18 CONCERNS.md 참조 오류(ID dangling/오기) 정정
> 비동기 confirm + 다중 서비스 분산 트랜잭션 환경에서 학습된 함정 목록.

## 1. AOP 우회 → audit trail 누락

**증상**: `paymentEvent.done(approvedAt)` 직접 호출 후 `saveOrUpdate(paymentEvent)` 하면 `payment_history` row 가 생기지 않는다. 추후 사고 재구성 시 상태 전이 흔적이 사라짐.

**원인**: `@PublishDomainEvent` + `@PaymentStatusChange` AOP 가 `markPaymentAsDone` / `markPaymentAsFail` / `markPaymentAsQuarantined` 메서드에만 부착돼 있다. 직접 도메인 메서드 + save 호출은 AOP 우회.

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

## 6. 시각 직접 호출(`now()`) 과 시간대 모호성

**증상**: ① 테스트에서 시간 위조 불가 → 시간 의존 분기 단정 어려움. ② `LocalDateTime`(시간대 없음)을 쓰면 컨테이너 TZ 에 따라 의미가 달라져, raw-JDBC 저장값과 ORM 조회 기준이 어긋난다(예: 만료 cutoff vs created_at 9시간 어긋남, dedupe TTL 윈도우 오염).

**처방** (TIME-MODEL-AND-EXPIRY 표준 + TIME-MODEL-FOLLOWUP 잔여 수렴):
- **시간 소스 = JDK `Clock` 빈 주입**(4서비스 공통, `Clock.systemUTC()`). 도메인은 `Clock` 을 주입받지 않고 호출자가 `clock.instant()` 로 얻은 `Instant` 를 **인자로 주입**한다(도메인 순수성). 어댑터도 self `clock.instant()` 호출 금지 — 진입점(consumer/스케줄러)이 `now` 를 1회 산출해 전 경로에 같은 `Instant` 를 전달(시계 split 방지). 예: product `StockCommitConsumer` 가 `now` 를 commit 인자와 `resolveExpiresAt` 양쪽에 동일 전달.
- **시각 타입 = `Instant`**(절대 시점). `LocalDateTime` 은 표현/비즈니스 경계 컬럼(예: `payment_outbox.next_retry_at/in_flight_at`)에서만 제한적으로 — 이 경우 `toInstant/toLocalDateTime(UTC)` 헬퍼로 명시 변환. **BaseEntity audit 컬럼(`created_at/updated_at/deleted_at`)은 `Instant`** (TIME-MODEL-FOLLOWUP 에서 `LocalDateTime` → `Instant` 전환, 매핑 경계 수동 `.toInstant(UTC)` 변환 제거).
- **UTC 저장 일관**: ORM 경로 `hibernate.jdbc.time_zone=UTC`, raw-JDBC 경로 datasource URL `connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true` + 바인딩에 명시 UTC `Calendar`. 도메인·audit 시각 컬럼은 `DATETIME(6)`(마이크로초). payment audit 컬럼은 V4(`V4__audit_datetime6_upgrade.sql`)로 `DATETIME` → `DATETIME(6)` 승급.
- **JPA auditing**: `@EnableJpaAuditing(dateTimeProviderRef="clockDateTimeProvider")` + `Clock` 기반 `DateTimeProvider` 가 `Instant` 를 반환해 `createdAt/updatedAt`(BaseEntity `Instant`)을 UTC 기준으로 채운다. created_at 기반 만료 cutoff 비교가 비-UTC JVM 에서 어긋나지 않게 하는 핵심. 회귀 가드: `JpaAuditingProviderWiringTest`(provider 반환 타입 + `dateTimeProviderRef` 빈 연결).
- **raw-JDBC 만료 판정도 앱 `Instant` 기준**: product 재고 멱등(`JdbcEventDedupeStore`)의 만료행 삭제·정리가 DB `NOW()` 가 아닌 호출자 주입 `now` 로 비교(TIME-MODEL-FOLLOWUP, DB 시계 의존 제거). `connectionTimeZone=UTC` 는 raw-JDBC `Timestamp` 바인딩 backstop 으로 존치.
- 테스트는 `Clock.fixed(...)` / 가변 `TestClock` 빈 또는 명시 `Instant` 리터럴로 시각 고정.
- **비-UTC JVM 1차 방어 (F6 backstop, 적용 완료)**: 6개 서비스 컨테이너/JVM `TZ=UTC` 를 **3겹**으로 명시 — Dockerfile `ENV TZ=UTC` + `ENTRYPOINT` JVM `-Duser.timezone=UTC` + compose `environment.TZ=UTC`(eureka 는 `docker-compose.infra.yml`). 동일값 멱등이라 충돌 없음. auditing UTC化와 별개로 깔아두는 안전망(TIME-MODEL-FOLLOWUP).

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

**처방** (현행 — STOCK-COMPENSATION-RECOVERY):
- `handleFailed` / `handleQuarantined` 의 보상은 `compensateAtomic(orderId, orders)` Lua 1회 호출 — `compensation:done:{orderId}` SETNX P8D dedup token 이 결제 단위 멱등 보장
- 동일 orderId 재진입 시 Lua 가 `ALREADY_DONE` early return → 재고 INCR 0회. 다중 워커 race 시에도 token 1회만 박힘
- (구) `executePaymentFailureCompensationWithOutbox` 의 TX 내 재조회 + outbox isInFlight / event canCompensateStock 이중 가드는 ADR-04 + STOCK-COMPENSATION-OTHER-PATHS 로 死 코드 제거됨 (회신 실패 보상은 위 `compensateAtomic` 경로가 전담)

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

**처방** (TIME-MODEL-AND-EXPIRY 갱신):
- pg-service 측에서 raw 문자열 보존 → `approvedAtRaw(String)` 으로 ConfirmedEventPayload 에 전달(offset 보존, Kafka contract 무변경)
- payment 측에서 `OffsetDateTime.parse(approvedAtRaw).toInstant()` 변환 — **`.toLocalDateTime()` 금지**. offset 을 버리면 KST(+09:00) 응답이 UTC 로 오인돼 정산·감사 앵커(`approvedAt`)가 최대 9시간 틀어진다. `.toInstant()` 는 offset 을 보존한 절대 시점이라 정산 시각 정합.
- `parseApprovedAt` 경로에 `.toLocalDateTime()` 잔존 0건(AC9 grep 가드).

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
- `PgConfirmPort` / `PgStatusLookupPort` 추상화 + Strategy 패턴 (Toss / NicePay / Fake)

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
- 이론적으로 디스크 latency 수준의 race window 는 잔존 — 수용된 한계(CONCERNS.md 에 별도 항목으로 등재돼 있지 않음, 이 처방 문단 자체가 SSOT)

## 18. 보상 끝난 결제의 재confirm cascade (CONCERNS.md L-12 보상 끝난 결제의 새 confirm 사이클 cascade / L-7 markPaymentAsFail 영구실패 cascade)

**증상**: P8D 안에서 동일 orderId 가 `decrement:done` + `compensation:done` 둘 다 박힌 상태로 새 confirm 사이클로 재진입. `decrementAtomic` 이 ALREADY_DONE → SUCCESS 매핑되어 재고는 추가 차감 안 되지만, 벤더가 APPROVED 회신하면 product RDB 만 차감 + redis 보상 +1 잔존 → 발산.

**원인**:
- (CONCERNS.md L-12) 외부 force resetToReady 등이 동일 orderId 재confirm 을 띄울 때 발생 가능. STOCK-COMPENSATION-OTHER-PATHS 가 `OutboxAsyncConfirmService.compensateStock`(확정 진입 보상)을 폐기하면서 이 트리거 한 경로가 소멸했고, 보상을 안 해 `compensation:done` 토큰을 박지 않으므로 재confirm 도 `decrement:done` ALREADY_DONE 으로 흡수된다 (정합 강화 방향)
- (CONCERNS.md L-7) `markPaymentAsFail` 영구 실패 → DLQ → Reconciler `resetToReady` → 새 confirm. PG 멱등성으로 보통 차단되나 이론적 가능성은 인정

**처방** (수용된 trade-off, 본 토픽 범위 외):
- 정상 흐름에서는 결제 1건 = orderId 1건 = `decrementAtomic` 1회라 발생 가능성 매우 낮음
- 본 cascade 를 차단하는 코드는 STOCK-COMPENSATION-RECOVERY 범위 밖, 알려진 한계로 인정
- STOCK-COMPENSATION-OTHER-PATHS 결정: 확정 진입 실패 시 토큰을 DEL 하지 않고 차감 유지(보상 폐기) — token DEL 이 동시 confirm 멱등을 깨므로 기각. redis<RDB 누수는 재고 reconciler(TC-3) 후속 위임

## 19. QUARANTINED 결제는 status 폴링이 영원히 PROCESSING

**증상**: 클라이언트가 `GET /api/v1/payments/{orderId}/status` 폴링하는데 결제가 격리됐는데도 응답이 영영 `PROCESSING` 으로만 옴. 폴링 무한 루프.

**원인**: `PaymentStatusServiceImpl.mapEventStatus` 의 switch 가 DONE / FAILED 만 명시적 매핑하고 나머지는 default = `PROCESSING`. QUARANTINED 는 도메인상 `isTerminal()` = false (후속 복구 워커가 보정/포기 결정하는 대기 상태) 라서 default 분기로 PROCESSING 응답이 되지만, 실제로는 자동 진행 메커니즘이 없어 admin 강제 전이 없으면 영영 PROCESSING 만 응답한다.

**처방** (단기):
- 클라이언트가 무한 폴링하지 않도록 timeout 정책을 client 측에 둠
- admin 도구로 격리 결제를 검토 후 DONE / FAILED 강제 전이 → 폴링 자연 종료

**처방** (장기):
- `PaymentStatusResult.StatusType` 에 `QUARANTINED` 추가 + `mapEventStatus` 명시 매핑 → 클라이언트가 격리 상태를 인지하고 polling 종료
- TODOS.md 의 admin 복구 도구(TQ-2 QUARANTINED-ADMIN-RECOVERY) 와 함께 진행

## 20. APPROVED RDB DONE 커밋 후 stock-committed 유실 — 종결 가드 재발행으로 복구 (CONFIRM-APPROVED-RESEND-GAP)

**증상**: APPROVED 경로에서 `markPaymentAsDone`(RDB DONE) 커밋 성공 후 EOS 커밋(stock-committed 발행 + offset)이 유실되면, 재배달이 D7 종결 가드(`canApplyConfirmResult()==false`)에 막혀 stock-committed 가 영구 유실 → product 재고 차감 누락 → redis 선차감과 RDB 발산 → 오버셀.

**원인**: 재고 확정 발행을 빠뜨리면(under-publish) 위험하지만, 같은 결제·상품의 재발행은 product `JdbcEventDedupeStore` 가 **결정적 키** `StockEventUuidDeriver.derive(orderId, productId)`(message eventUuid 와 독립) 로 흡수해 차감 1회 — over-publish 는 무해. 이 비대칭 때문에 "애매하면 재발행" 이 안전하다.

**처방** (종결 가드 재발행):
- D7 종결 가드 분기에서 `status==DONE && message==APPROVED`(= 정상 첫 도착엔 없는 = 재배달 신호) 면 `sendStockCommittedEvents` **재발행** + `terminalResendMetrics.record(DONE)` 계측.
- **함정**: "0 row(중복) 시 발행 항상 진행(과거 위키 line 141)" 분기로 해결하려 하면 안 된다 — dedupe 마킹과 종결 전이가 같은 JPA tx 원자 커밋이라 "dedupe됨+비종결" 조합이 단일 컨슈머 EOS 흐름에서 발생 불가 → **도달 불가 dead branch**. 재배달은 항상 종결 가드로 온다.
- **함정**: 순서 뒤집기(발행 먼저)로 해결되지 않는다 — 발행은 producer tx buffer 라 원자성 경계(JPA 커밋 ↔ Kafka 커밋)가 그대로다(FAILED·QUARANTINED 의 즉시 Redis 보상과 성격이 다름).
- **잔여 한계 (over-sell)**: 재발행도 같은 EOS tx 라 `commitTransaction` **지속** 실패 시 stock-committed 자체는 완전 유실(over-sell). 단 입력 `events.confirmed` 메시지는 명시 연결된 `AfterRollbackProcessor`(공유 DLQ recoverer + backoff)가 소진 후 DLQ 로 발행해 가시화한다(DLQ-REACHABILITY). DLQ 적체분은 관리자 수동 재주입(`DlqReprocessUseCase` → 원 토픽 `events.confirmed` 재발행, 나이 게이트)으로 복구하며, 조건부 자동 재시도는 후속(TQ-1). 검증: `PaymentEosIntegrationTest` #6(복구)·#7(지속실패 → DLQ 도달).

## 21. QUARANTINED 결제에 늦은 APPROVED 메시지 — D7 가드 없으면 DLQ silent 분기

**증상**: 결제가 QUARANTINED 된 이후 뒤늦게 APPROVED 결제 결과 메시지가 도착 (pg-service retry 지연 등). 가드 없으면 `handleApproved` 가 실행 → `markPaymentAsDone` 에서 `IllegalStateException` (QUARANTINED → DONE 비허용 전이) → not-retryable 즉시 DLQ → 재고 발행 0 + 상태 불일치 로그 없음.

**처방** (D7 가드 — PET-3 / PET-8):
- `handle()` 진입 직후 `paymentEvent.getStatus().canApplyConfirmResult()` 체크.
- false (QUARANTINED 포함 종결 상태) → `LogFmt.warn` + noop return. DLQ 전혀 건드리지 않음.
- D7 가드 변경 시 `PaymentEventStatusSplitMethodTest` (분리 메서드 검증) 가 회귀 탐지 (DR-3). (구) `PaymentEventStatusCrossInvariantTest` 의 `canApplyConfirmResult` ↔ `canCompensateStock` 교차 동조 불변식은 `canCompensateStock` 死 코드 제거(STOCK-COMPENSATION-OTHER-PATHS)와 함께 폐기.

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

## 24. kafka_brokers < 1 단독 알람식의 dead branch — 완전 정지 시 시리즈 소멸(absent)

Kafka broker 완전 정지 시 kafka-exporter 의 `kafka_brokers` 메트릭은 0 이 아니라 시리즈 자체가 소멸(absent)한다. 따라서 `kafka_brokers < 1` 단독 알람식은 단일 broker 완전 다운 상황에서 절대 발화하지 않는 dead branch다. 라이브 실측 근거: ALERTING-RULES-AND-FAULT-DRILL 드릴에서 단일 broker 완전 정지 시 kafka_brokers 시리즈 소멸 관측.

**처방**:
- 완전다운 backstop 은 `absent(kafka_brokers)`(또는 `up{job="kafka-exporter"}==0`)로 잡는다.
- `kafka_brokers < 1` 은 exporter 가 살아있고 broker 수만 0/부족 보고하는 경우(멀티 broker 부분 다운) 대비로 남겨 3분기 OR 구성: `up==0 or kafka_brokers<1 or absent(kafka_brokers)`.
- promtool 픽스처에서 `kafka_brokers` 시리즈를 선언하지 않으면 absent 를 정확히 재현할 수 있다(`_` gap 은 5분 lookback staleness 로 부정확하게 시뮬).

## 25. 읽기 스냅샷 때문에 자신을 막은 행이 안 보인다 — 확인 조회는 잠금 읽기여야

같은 주문의 확정이 동시에 들어오면 진 쪽은 발행 행 삽입에서 앞선 요청의 잠금에 막혔다가, 그쪽이 커밋한 뒤 풀려나 반영 행 0 을 받는다. 그런데 확정 트랜잭션은 발행 행 삽입 **전에** 결제 상태를 먼저 바꾸므로 그 시점에 이미 읽기 스냅샷이 확정된다. MySQL 기본 격리 수준(REPEATABLE READ)에서 평범한 조회는 그 스냅샷 기준이라, **방금 자신을 막았던 바로 그 행을 보지 못한다**. 그러면 중복이 아니라 저장 실패로 오분류된다.

pg 수신 기록 테이블의 `INSERT IGNORE` 선례를 그대로 베끼면 이 함정에 빠진다 — 거기서는 그 삽입이 트랜잭션의 첫 문장이라 안전했던 것이고, 패턴 자체의 성질이 아니다.

**처방**:
- 반영 행 0 이후의 확인 조회는 **블로킹 쓰기 잠금 읽기**(`@Lock(PESSIMISTIC_WRITE)`)로 건다 — 스냅샷이 아니라 마지막 커밋 값을 읽는다.
- 워커 선점용 `FOR UPDATE SKIP LOCKED` 를 쓰면 안 된다. 앞선 요청의 커밋을 기다리지 않고 지나가버려 목적이 정반대로 깨진다.
- 이 조건은 단일 스레드 기능 테스트로 잡히지 않는다(어떤 잠금 방식이든 똑같이 통과). 잠금 선언 존재와 건너뛰기 힌트 부재를 리플렉션·쿼리 문자열로 단정하는 계약 테스트를 따로 둔다 — `JpaPaymentOutboxRepositoryLockContractTest`.
- 반영 행 0 을 곧바로 중복으로 읽지 않는다. 조회로 기존 행 존재를 확인해야 진짜 저장 실패를 중복으로 오판하지 않는다.

## 26. 복원한 추적 문맥에 속성만 붙이면 조용히 버려진다

워커가 저장된 traceparent 로 복원한 문맥의 span 은 propagator 가 만든 **원격 span** 이라 기록 대상이 아니다. 여기에 `Span.current().setAttribute(...)` 를 호출하면 예외도 없고 로그도 없이 그냥 버려진다. 순진하게 "주문 번호를 속성으로 추가"만 하면 코드는 그럴듯하고 속성 단정 테스트도 통과하는데 추적 백엔드에는 아무것도 안 남는다.

**처방**:
- 복원한 문맥을 **부모로 삼아 자체 span 을 만든 뒤** 속성을 붙인다. 구간 생성이 함께 가야 한다.
- 검증 테스트는 **span 생성 자체를 단정 대상에 넣는다**. 속성만 검사하면 이 실패 양상을 못 잡는다. 인메모리 exporter 로 실제 내보내진 span 을 읽어 확인한다(`PgInboxPollingWorkerSpanTest` — 새 의존 없이 `SpanExporter` 를 직접 구현).

## 27. 호출 스택 문자열 매칭으로 컨텍스트를 짐작하면 조용히 깨진다

상태 전이 지표의 주체 라벨을 호출 스택에서 클래스 이름을 문자열로 맞춰 판정하던 코드가 있었다. 승인 경로는 스택의 실제 구현 클래스 이름이 매칭 문자열과 달랐고(인터페이스명으로 매칭 시도), 복구 경로는 대상 클래스가 리팩터로 삭제돼 — 두 분기 모두 영구 미스매치였다. 만료 전이만 라벨이 정확하고 나머지는 전부 `unknown` 으로 뭉개졌다.

이 방식은 클래스 이름이 바뀌거나 계층이 하나 끼어들면 그 순간부터 깨지는데 **컴파일도 되고 테스트도 통과한다**. 실제로 두 번 깨졌고 아무도 몰랐다.

**처방**:
- 컨텍스트는 짐작하지 말고 **그것을 아는 쪽이 선언**한다. 애노테이션 고정값으로 충분하면 그것을, 한 메서드가 여러 흐름에서 불리면 호출자가 파라미터로 넘긴다(`@Trigger`).
- 전이 지점 전수를 스캔해 라벨이 비는 경로가 없음을 구조적으로 고정하는 테스트를 둔다 — 새 전이 지점을 추가하며 값을 빠뜨리면 잡힌다.

## 관련 자료

- 도메인 학습 자료: archive 안 토픽별 `COMPLETION-BRIEFING.md`
- 자주 겹치는 우려: `CONCERNS.md`
- 향후 처리 항목: `TODOS.md`
