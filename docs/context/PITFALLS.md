# Domain Pitfalls

> 최종 갱신: 2026-04-27
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

## 10. Single-phase mark with long TTL

**증상**: 처리 도중 워커 크래시 → markSeen 만 박아둔 상태에서 8일 동안 다른 워커가 재처리 못 함.

**처방** (two-phase lease):
- `markWithLease(eventUuid, leaseTtl=PT5M)` — 짧은 lease 로 처리 권한
- 처리 완료 시 `extendLease(eventUuid, longTtl=P8D)` 로 dedupe 윈도우 확장
- 처리 실패 시 `remove(eventUuid)` → false 면 DLQ publish

## 11. 보상 트랜잭션 중복 진입

**증상**: 다중 워커 동시 진입 또는 retry 후 응답 처리 직전 크래시 → 같은 결제에 재고 INCR 두 번 → 재고 발산.

**처방**:
- `executePaymentFailureCompensationWithOutbox` 진입 시 TX 내 outbox + event 재조회
- outbox 가 IN_FLIGHT AND event 가 비종결일 때만 재고 INCR
- 한쪽이라도 종결된 흔적 있으면 재고 복구 skip + warn 로그

## 12. Virtual Thread / Async 경계 MDC 손실

**증상**: HTTP → @Async → Kafka 경계에서 traceparent 가 끊김 → 사고 시 trace 추적 불가.

**처방**:
- Spring `@Async` executor 가 OTel context 자동 전파하도록 wiring
- Kafka producer ProducerFactory 자체 생성 시에도 `ObservationRegistry` 를 명시적으로 wiring
- `OtelMdcMessageInterceptor` 로 consumer 측 traceparent → MDC 복원

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

## 17. QUARANTINED 결제는 status 폴링이 영원히 PROCESSING

**증상**: 클라이언트가 `GET /api/v1/payments/{orderId}/status` 폴링하는데 결제가 격리됐는데도 응답이 영영 `PROCESSING` 으로만 옴. 폴링 무한 루프.

**원인**: `PaymentStatusServiceImpl.mapEventStatus` 의 switch 가 DONE / FAILED 만 명시적 매핑하고 나머지는 default = `PROCESSING`. QUARANTINED 는 도메인상 종결(`isTerminal()` true) 이지만 폴링 결과에선 종결 표현이 없다.

**처방** (단기):
- 클라이언트가 무한 폴링하지 않도록 timeout 정책을 client 측에 둠
- admin 도구로 격리 결제를 검토 후 DONE / FAILED 강제 전이 → 폴링 자연 종료

**처방** (장기):
- `PaymentStatusResult.StatusType` 에 `QUARANTINED` 추가 + `mapEventStatus` 명시 매핑 → 클라이언트가 격리 상태를 인지하고 polling 종료
- TODOS.md 의 admin 복구 도구(TQ-2 QUARANTINED-ADMIN-RECOVERY) 와 함께 진행

## 관련 자료

- 도메인 학습 자료: archive 안 토픽별 `COMPLETION-BRIEFING.md`
- 자주 겹치는 우려: `CONCERNS.md`
- 향후 처리 항목: `TODOS.md`
