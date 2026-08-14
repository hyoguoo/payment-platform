# Planned Cleanup / Future Work

> 최종 갱신: 2026-08-14 (PG-VENDOR-SIGNAL-CONSOLIDATION ship — 현재 과업 3건 삭제(중복 승인 신호 이중 / 접수대장 동시 경합 검증 / 소진 시점 벤더 확인 배선 판단 — 모두 이번 토픽에서 해소, 이력은 아카이브 브리핑). 후속 3건 신설: 재고 재동기화의 진행 중 선차감 가드 / 선차감 흔적 만료 임박 알람 / 관리자 조회 서비스의 부분 취소 분류. 재시도 창 축소 항목에 관문 배선에 따른 부분 해소 추가). 이전: 2026-08-13 (PG-DUPLICATE-APPROVAL-SETTLEMENT ship — 처리 중 재전송 겹침 차단 항목 삭제(중복 승인 응답 종결로 해소, 이력은 아카이브 브리핑), 후속 1건 신설: 중복 승인 신호 이중으로 인한 발행 2건. 소진 시점 자동 벤더 확인 배선 항목에 원문 승인 시각 반영 메모 추가). 이전: 2026-08-11 (PG-MESSAGE-DEDUPE-LAYER-REMOVAL ship — pg dedupe 층 재검토 항목 삭제(제거안 실행 완료, 이력은 아카이브 브리핑), 후속 2건 신설: 처리 중 재전송 벤더 호출 겹침 차단 / 접수대장 UNIQUE 동시 경합 검증). 이전: 2026-08-04 (BACKLOG-RESIDUE-CLEANUP ship — 판단만으로 닫히는 현재 과업 7건 삭제: 트랜잭션 매니저 체인 검토(우려 대장 L-1 중복) / 커버리지 집계 범위 잔여 / 선점 경로 프로덕션 미사용(코드 제거) / 모의 벤더 부팅 가드 부재(코드로 해소) / 정적 검출 게이트 승격 판단 / 기준선 억제 정리(코드로 해소) / 종결 이후 발행 행 이력 표시. 섹션 라벨(A~F) 폐지, 남는 재시도 창 축소 항목은 현재 과업 아래 라벨 없이 배치. TC-7 에 타임아웃 회수 경로 미도달 확인 결과와 그로 인해 값이 고정된 컬럼 4개·지표 2개 등재, 스키마 정리를 별도 토픽 조건부 후속으로 추가. 이전 갱신 이력은 `docs/archive/README.md` 와 각 토픽 COMPLETION-BRIEFING 참고)
> 분류 룰: **현재 과업** = 측정 / Toxiproxy / 멀티 인스턴스 환경 의존 없는 작업. **Phase 5** = 부하 측정 결과 또는 인프라 환경 필요. 내부 "Phase 5" 번호는 README 의 독자용 개발 과정 Phase 1~7 체계와 별개다(서로 다른 축 — 혼용 금지).
> discuss 단계 시작 시 다음 작업을 고를 때 이 파일을 참고한다.

---

## 현재 과업 (작업 가능 — 측정 / 인프라 무관)

### [RETRY-WINDOW-NARROWED-QUARANTINE-PRESSURE] — 재시도 창 축소로 커진 격리 복구 후속의 무게

- **현황**: 재시도 백오프 회차 정정으로 총 재시도 창이 78초에서 26초로 줄었다(`PgVendorCallService.insertRetryOutbox`). 벤더 장애가 그 사이 길이로 지속되면 이전에는 재시도로 자연 회복했을 결제가 격리로 남는다.
- **영향**: 격리를 벗어나는 관리자 경로는 벤더 상태를 재조회하지 않는 편도 실패 종결(`QuarantineResolveUseCase.resolve`)뿐이고, 벤더 취소·환불 포트 자체가 없다. 응답만 유실된 승인이었다면 시스템은 실패로 정리되지만 벤더 쪽 과금은 남는다 — 이 조합을 만날 확률이 올라갔다.
- **처방**: 격리 복구(TQ-2 잔여)와 환불(TQ-6)의 우선순위를 이 사실에 맞춰 재평가한다.
- **부분 해소 (2026-08-06, RETRY-EXHAUSTION-DISPOSITION)**: "안전 종결 전 상태 조회를 코드로 강제하는 가드"는 도입했다 — pg 벤더 상태 조회 엔드포인트 + payment 전용 조회 통로 + 종결 직전 판정(승인이면 거부, 실패·확인 불가면 진행하되 결과를 사유에 기록)과 관리자 화면 조회 버튼. 격리 사유와 무관하게 모든 격리 결제에 적용된다.
- **부분 해소 (2026-08-14, PG-VENDOR-SIGNAL-CONSOLIDATION)**: 소진 시점 벤더 확인 관문이 배선돼 승인·확정실패로 갈리는 건은 애초에 격리에 들어오지 않는다. 격리로 남는 것은 판단이 필요한 넷(조회 실패 / 벤더 미결론 / 부분 취소 / 금액 불일치)뿐이라 압력 자체가 줄었다.
- **잔여**: 승인으로 확인된 격리를 되살릴 경로가 없어 그대로 잔류한다(TQ-2 잔여). 벤더 취소·환불도 포트 자체가 없다(TQ-6). 종결의 동시 이중 제출은 상태 조건만으로 판정해 먼저 통과한 쪽이 이긴다.

#### [OUTBOX-STALE-PUBLISH-AFTER-STRANDED-READY] — 만료 못 한 결제에 확정 명령이 뒤늦게 나가는 경로

- **현황**: 브로커 장애로 발행이 밀리면 결제는 정리 작업이 READY 로 되돌린 뒤 만료에 실패해 READY 에 영구 잔류한다(`CONCERNS.md` L-14 문제 1). 그 사이 `payment_outbox` 행은 독립적으로 재시도를 계속하고, 브로커가 복구되면 확정 명령을 발행한다. 벤더가 승인하면 되돌릴 수 없는 과금이 남고 payment 측은 종결 상태로 판정해 조용히 넘긴다.
- **이번 작업이 한 것**: `OutboxRelayService.relay` 에 발행 직전 결제 상태 가드를 넣었다. 다만 READY 는 확정 결과 적용 가능 상태라 **이 시나리오에서는 발동하지 않는다** — 가드는 결제가 이미 종결·격리일 때만 걸린다.
- **처방**: L-14 의 세 위험을 함께 저울질해야 한다 — 복구 여지(종결시키면 재주입 봉쇄), 뒤늦은 확정 명령의 벤더 과금, 재고 보상 없는 만료 시 선차감분 영구 잠김. 정리 작업이 주문까지 되돌리는 단순 해법은 2026-06-30 plan 게이트에서 이미 거부됐다(첫 번째 위험 때문). 재고 보상 설계를 포함한 별도 토픽이 필요하다.

#### [STOCK-RESYNC-INFLIGHT-GUARD] — 재고 재동기화가 진행 중 선차감을 덮어쓴다

- **현황**: `POST /admin/stock/resync/{productId}`(`StockCachePort.set`)는 redis 재고를 상품 DB 값으로 단순 덮어쓴다. `decrement:done` / `compensation:done` 흔적은 건드리지 않아, 진행 중 선차감이 있으면 그 수량이 가용으로 되돌아온다. 도구 Javadoc 이 이미 "트래픽 조용한 시점에만" 을 전제로 걸어 두었다.
- **왜 지금 무거워졌나**: PG-VENDOR-SIGNAL-CONSOLIDATION 이 부분 취소 격리의 즉시 재고 보상을 건너뛰면서, 선차감이 떠 있는 창이 초 단위에서 **관리자가 격리를 종결할 때까지(며칠)** 로 늘었다. 그 창에 재동기화가 겹치면 종결 시점 조건부 보상이 이미 되돌아온 수량을 한 번 더 얹어 재고가 부풀 수 있다.
- **처방**: 재동기화 실행 전 해당 상품에 미종결 격리가 있는지 확인하는 가드. 상품별 격리 주문 조회가 새로 필요해 범위가 작지 않다. 그때까지는 운영 절차로 막는다(설계 문서 장애 시나리오에 명시).

#### [QUARANTINE-TRACE-EXPIRY-ALERT] — 선차감 흔적 만료 임박 격리 알람

- **현황**: `decrement:done` 흔적은 P8D(8일) 수명이다. 부분 취소 격리는 이 흔적이 **유일한 복원 수단**인데(즉시 보상을 건너뛰므로), 8일 안에 관리자가 종결하지 못하면 흔적이 만료돼 재고가 복원되지 않는다. 방향은 안전한 쪽(재고가 덜 풀림)이라 과매도로 번지지는 않지만 사람이 대사해야 한다.
- **처방**: 격리 나이를 재는 지표와 만료 임박 알람. `CONCERNS.md` L-15 의 연장선이며, 이번에 그 경계가 부분 취소 격리에도 적용됐다.

#### [ADMIN-VENDOR-STATUS-PARTIAL-CANCEL] — 관리자 조회 서비스의 부분 취소 분류

- **현황**: `PgVendorStatusQueryServiceImpl.FAILED_STATUSES` 가 `PARTIAL_CANCELED` 를 확정 실패로 묶는다. FCG 는 PG-VENDOR-SIGNAL-CONSOLIDATION 에서 이 상태를 확정 실패에서 빼 전용 사유로 격리시켰지만, 관리자 화면 조회 경로는 그대로다 — 부분 취소를 "실패"로 표시하면 운영자가 안전 종결을 눌러 벤더 보유분이 남은 채 정리될 수 있다.
- **처방**: 판정값을 늘리고 화면 표시와 종결 가드까지 손봐야 한다. 자동 경로와 달리 사람이 벤더 화면을 대조할 수 있어 위험 등급은 낮다.

---

## Phase 5 — 추후 (부하 측정 / 인프라 의존)

> 모두 (a) k6 부하 측정 결과 또는 (b) Toxiproxy 8종 장애 주입 환경 또는 (c) 멀티 인스턴스 환경이 필요한 작업. Phase 4 환경이 준비된 뒤 진행.

### Phase 4 본진 (5개)

#### T4-A — Toxiproxy 8종 장애 주입 시나리오

- Kafka producer/consumer 지연
- DB 지연 / 연결 끊김
- payment-service / pg-service 프로세스 kill + 재시작
- 보상 트랜잭션 중복 진입 방지 (D12 가드 실증)
- FCG (Final Confirmation Gate) PG timeout
- Redis dedupe / stock cache 다운
- 재고 캐시 발산 시나리오
- DLQ 소진

각 시나리오: `payment_outbox_pending_age_seconds` p95≥10s, 결제·재고 정합성 교차 검증.

#### T4-B — k6 시나리오 재설계

- Gateway → payment confirm → 비동기 status 폴링 단일 시나리오
- 경로별 TPS / p95 / p99 / failure rate 메트릭
- ramping-arrival-rate 부하 곡선

**T4-B 정밀화 묶음 (멀티 broker 실측 후)**:

- **[DE1]** guard-skip 알람 `status` 라벨이 결제 현재 상태만 담아 위험(QUARANTINED + 늦은 APPROVED)과 양성(FAILED/QUARANTINED 결과 재배달)을 구분하지 못한다. 멀티 broker T4-B 정밀화 시 수신 메시지 status 를 라벨로 추가해 위험/양성 분리 구현. 현 상태는 거짓 페이징 회피를 위해 warning 유지가 합당 — domain-expert 판정.
- **[DE2]** `KafkaCoordinatorLagHigh` 임계 1000 은 단일 broker 드릴 도달 불가(라이브 실측 피크 ~150)로 미검증 baseline. 단일 broker 비대칭 구조 한계(주석 명시). 멀티 broker T4-B 실측 후 임계 재교정. 그때까지 lag 는 보조 신호, txn abort 가 1차.

#### T4-C — 로컬 오토스케일러

- Prometheus 큐 길이 / CPU 임계 기반 payment-service 레플리카 자동 scale
- docker compose scale up/down 자동화
- scale 결정 logging + Grafana dashboard

#### T4-D — CircuitBreaker 적용

- `ProductHttpAdapter` / `UserHttpAdapter` 에 Resilience4j CircuitBreaker
- Prometheus 메트릭 (`circuit_breaker_state`, `circuit_breaker_calls_total`)
- 폐쇄/반열림/열림 상태 시각화
- **이 도입과 동시에**: 어댑터의 `try/catch (feign.RetryableException)` 매핑을 Feign **fallbackFactory** 로 마이그레이션
- **timeout 정밀 튜닝**: `application.yml` 의 `spring.cloud.openfeign.client.config.default.{connectTimeout: 2000, readTimeout: 5000}` baseline 을 Phase 4 부하 측정 기반 SLO 로 조정
- **pg-service 외부 PG timeout 정밀 튜닝**: `pg.http.{connect-timeout-millis: 3000, read-timeout-millis: 10000}` 은 현재 측정 없는 baseline. T4-B/T4-A 부하 + 장애 주입 측정 결과로 SLO 기반 값으로 교체. `max.poll.records` 기본값(500) 검증도 병행

#### T4-E — CAPACITY-AND-SCALEOUT scale-out 후속 (측정 완료 → 처방)

CAPACITY-AND-SCALEOUT 측정으로 payment 1→2 scale-out **~1.0×**(공유 DB 경합 병목, Hikari 풀·CPU 천장 아님 — CPU 5.5/10 여유) 규명. 후속 처방:

- **payment DB 스케일** — 공유 MySQL이 2 인스턴스의 진짜 천장(scale-out 차단, MySQL lock/IO + Kafka EOS commit 직렬화). 읽기 전용 복제(조회 분리) / 쓰기 샤딩 후 재측정. USL N≥3 확장 시 `scripts/usl-fit.py` 다점 회귀로 α·β·Nmax 점추정.
- **events.confirmed 파티션 수 = 인스턴스 배수** — 현재 파티션 3 vs 인스턴스 2 = 2:1 편향 → 고발행 시 consumer 백로그 비대칭(한 인스턴스만 적체).
- **payment graceful shutdown + gateway retry** — 인스턴스 restart/scale 시 가용성 갭 16%(다운 인스턴스로 라우팅된 confirm http_fail). TC-12(pg worker drain 보류)와 결 다름 — payment 는 무중단 배포 목적.
- **fencing in-flight 재고 갭 영구성 관찰** — 충돌/restart 시 redis<RDB 미세 갭(0.1%대, fencing이 stock-committed EOS abort → IN_PROGRESS in-flight 비대칭, reconciler cascade 아님). 재배달 EOS 재성공 자연 종결 vs `.dlq` 낙착 후 reconciler backstop 회수인지 장기 관찰. `decrement:done` token 정합(STOCK-COMPENSATION-OTHER-PATHS 완료분)과 연계.
- **상세 SSOT**: `docs/archive/capacity-and-scaleout/` REPORT 사이클 6/7.

### Phase 4 후속 — 자동 운영 도구 (6개)

#### TQ-1 — DLQ 조건부 자동 재시도 (수동 재주입 ✅ 완료)

- **완료**: `payment.events.confirmed.dlq` 관리자 수동 재주입(원 토픽 republish → EOS 컨슈머 재처리, 종결시각+P8D 나이 게이트) — DLQ-QUARANTINE-RECOVERY(#122). `payment.commands.confirm.dlq` 는 pg-service 소비.
- **잔여**: 조건부 자동 재시도(벤더 5xx 같은 일시적 실패의 자동 재발행) 미구현 — 상시 자동 소비 컨슈머는 별도 후속 토픽. 상세: `docs/archive/dlq-quarantine-recovery/COMPLETION-BRIEFING.md`.

#### TQ-2 — 격리 DONE 복구 (FAILED 안전 종결 ✅ 완료)

- **완료**: QUARANTINED 결제의 관리자 수동 **안전 실패 종결** — FAILED 강제 전이(`failFromQuarantine`) + `decrement:done` 토큰 조건부 재고 보상(유령 재고 방지) + event·order CAS 동조 + audit + 관리자 API/버튼. DLQ-QUARANTINE-RECOVERY(#122).
- **잔여**: 격리된 **정상** 결제를 DONE 으로 되살리는 복구 — payment→pg 상태 조회 포트 + 재고 원장 write-back(stock-committed 재발행·redis 재정렬) + 동시성이 선결이라 별도 후속 토픽. 벤더 환불 실행은 TQ-6. 상세: `docs/archive/dlq-quarantine-recovery/COMPLETION-BRIEFING.md`.

#### TQ-3 — REDIS-CACHE-FAILURE-POLICY

- `redis-stock` 다운 시 어떤 정책으로 가야 하는가? — 현재는 CACHE_DOWN → QUARANTINED + 보상 펜딩
- redis 데이터 lost 시 부팅 재시드(`scripts/seed-stock.sh`) 외 회복 경로 없음 — payment 진행 중이면 Redis 키 부재로 confirm DECR 음수 가능성
- 운영 시 Redis HA / fallback / AOF 운영 가이드 결정 필요

#### TQ-4 — Vendor 동적 라우팅

- 현재 `gatewayType` 은 client 결정. 벤더 장애 시 자동 fallback 미구현
- 헬스 체크 기반 동적 라우팅 정책

#### TQ-5 — multi-broker Kafka

- 현재 broker 1대 + replication-factor=1
- HA 환경 검증 필요

#### TQ-6 — Cancel / Refund 워크플로우

- `PgGatewayPort.cancel(...)` 인터페이스만 존재
- 운영 cancel 정책 + 부분 환불 + audit trail

### 측정 의존 코드 청결도 (6개)

#### TC-3 — 재고 동기화 정책 (부팅 외 시점) ✅ 부분 완료 (수동 단건 resync, 2026-07-01)

- **완료**: payment `POST /admin/stock/resync/{productId}`(`StockAdminController` → `StockResyncUseCase`)가 `ProductPort.getProductInfoById` 로 product RDB stock(SoT) 을 조회해 `StockCachePort.set` 으로 redis-stock(선차감 캐시) 을 단건 덮어쓴다. `STOCK_CACHE_RESYNC` 로그 가시화.
- **한계 / 잔여**: 단순 SET 이라 in-flight 선차감을 덮어써 over-sell 가능 → 운영자가 트래픽 조용한 시점/특정 productId 한정 호출이 전제(`StockCachePort#set` Javadoc). 전체 일괄 resync·자동 발산 감지(이벤트 기반 invalidation)는 미채택 — 외부 직접 RDB 변경 미탐지 한계 + cross-service 복잡도로 후속 여지. 주기 재시드는 in-flight 덮어쓰기 위험으로 기각.

#### TC-6 — 가상 스레드 명시적 throttle / bulkhead 검토

- 현재 백프레셔는 다운스트림 자원 (Hikari 30, Kafka in-flight 5, Redis Lettuce single connection, scheduler batch-size 50) 으로 자연 형성
- 명시적 `Semaphore` / `RateLimiter` / Resilience4j `Bulkhead` 코드는 0건
- 위험 시나리오: 외부 PG (Toss/NicePay) 호출 시 벤더 측 rate limit 초과 / 다운스트림 다운 시 VT 가 timeout 까지 spawn 누적 → 메모리 압박
- 도입 후보: T4-D 의 Resilience4j 묶음에 `@Bulkhead("productService")` 추가, 또는 외부 PG 호출 어댑터에 명시 Semaphore. 측정값 기반으로 결정

#### TC-7 — payment_outbox retry 정책 재검토

`stock_outbox` 는 PAYMENT-EOS-TRANSITION 봉인으로 폐기됨 (PR #77). `payment_outbox` retry 정책만 측정 검증 대상으로 남음.

**현황**:
- `payment_outbox`: `RetryPolicy` 존재 — `RetryPolicyProperties` (env 주입) + maxAttempts=5 + FIXED 5s default. 이 정책을 적용하던 REQUIRES_NEW 선점 경로의 재시도 증가 메서드는 프로덕션 호출처가 0이라 BACKLOG-RESIDUE-CLEANUP 에서 제거됐다 — `RetryPolicy.isExhausted` 소진 판정은 값 객체 메서드로 남지만 지금은 프로덕션 호출처가 없다. 실제 발행 실패 경로(`OutboxRelayService.relay` 단일 TX 롤백)는 retryCount 증가·`FAILED` 종결 없이 5초 주기 무백오프로 재시도된다. `PaymentOutboxStatus.FAILED` 도달 코드 경로도 현재 0건.

**타임아웃 회수 경로도 실행되지 않는다** (BACKLOG-RESIDUE-CLEANUP ship 중 확인):

제거 후 `incrementRetryCount` 를 부르는 프로덕션 지점은 `recoverTimedOutInFlightRecords` 하나만 남는데, 그 조회 조건(`status = 'IN_FLIGHT' AND inFlightAt < cutoff`)에 걸리는 행이 생기지 않는다. `relay` 가 단일 TX 안에서 PENDING → IN_FLIGHT → DONE 을 모두 처리해 커밋된 상태는 DONE(성공) 또는 PENDING(롤백) 둘뿐이고, 도메인 메서드 `toInFlight()` 는 프로덕션 호출처가 0이다. 이론적 예외는 선점 성공 직후 행이 사라져 `relay` 가 예외 없이 리턴하는 경우(`OutboxRelayService.java:63-67`) 하나뿐이라 실질 도달 불가.

그 결과 스키마와 지표가 실행되지 않는 정책을 가리킨다:

| 대상 | 실제 값 | 소비처 |
|:---:|:---:|:---:|
| `payment_outbox.retry_count` | 항상 0 | `attempt_count_histogram` 지표 |
| `payment_outbox.next_retry_at` | 항상 null | 선점/배치 조회 게이트 2곳, `future_pending_count` 지표 |
| `payment_outbox.in_flight_at` | DONE 행에 값 있음 | `findTimedOutInFlight`(매치 0) |
| `payment_outbox.available_at` | 삽입 기본값만 | 코드 사용처 0 |

`attempt_count_histogram` 과 `future_pending_count` 는 구조적으로 움직이지 않는 지표다.

**조정 필요 사항**:
1. **payment_outbox 정책 재검토** — REQUIRES_NEW 선점 경로를 되살려 실제로 연결할지, 현재 단일 TX 무백오프 재시도를 유지하고 backoff 를 그 경로에 이식할지 결정 필요. maxAttempts=5 + FIXED 5s 가 SLO 기준 적절한지 측정 검증도 병행 (Phase 5 자물쇠 — k6 측정 후)
2. **정책을 두지 않기로 하면 스키마·지표까지 정리** — 위 컬럼 4개와 지표 2개, 그리고 도달 불가 상태(`IN_FLIGHT` 커밋, `FAILED`)를 함께 걷는다. `next_retry_at` 은 살아있는 쿼리 3곳과 인덱스에 엮여 있어 선점·배치 조회를 같이 손봐야 하고, 결제 핵심 테이블의 파괴적 마이그레이션이라 별도 토픽이 필요하다. 1번 결정이 선행돼야 한다 — 지금 걷으면 측정을 기다리던 결정을 측정 없이 확정하는 셈이다

**관련 코드**:
- `payment-service/.../domain/PaymentOutbox.java` — retryCount + incrementRetryCount
- `payment-service/.../application/config/RetryPolicyProperties.java`
- `payment-service/.../domain/RetryPolicy.java`
- `payment-service/.../infrastructure/metrics/PaymentOutboxMetrics.java` — 움직이지 않는 지표 2종
- `payment-service/src/main/resources/db/migration/V1__payment_schema.sql:57-77` — 컬럼·인덱스 정의

#### TC-11 — product / pg dedupe 테이블 cleanup 스케줄러 (product ✅ 완료 + 운영 활성화 정상화 / pg 범위 제외)

장기 운영 시 만료 row 누적으로 쿼리 성능 저하 가능.

**현황**:
- product-service `stock_commit_dedupe` — ✅ `DedupeCleanupWorker` (`@Scheduled`) 도입 완료 (EOS-FOLLOWUP-CLEANUP, 2026-05-29). `deleteExpired` 만료 행 일괄 DELETE + `SchedulerConfig` 활성 게이트. 단, worker 와 `SchedulerConfig` 게이트는 구현됐으나 `application-docker.yml` 에 `scheduler.enabled: true` 플래그가 누락돼 운영 docker 포함 어떤 배포에서도 실제 미기동 상태였음 → CLEANUP-BATCH-D Task 3 에서 플래그 추가로 정상화.
- pg-service `pg_inbox` — **범위 제외**. 종결 행이 confirm 재배달 멱등 SoT 라 청소 대상 아님 (terminal row 보존이 멱등성 보장의 본질)
- payment-service `payment_event_dedupe` — ✅ `DedupeCleanupWorker` 도입 완료 (EOS-FOLLOWUP-CLEANUP, 2026-05-29)
- payment-service 의 Redis dedupe (재고 차감/보상 token) 는 TTL 자동 expire — 문제 없음

**관련 코드**:
- `product-service/.../infrastructure/idempotency/JdbcEventDedupeStore.java`
- `product-service/.../infrastructure/scheduler/DedupeCleanupWorker.java`

#### TC-12 — pg-service Worker.stop 채널 drain 도입 ⏸️ 보류 (2026-06-14, 실익 대비 복잡도 부적합)

**보류 결정 (PG-WORKER-GRACEFUL-DRAIN discuss 사전 브리핑 단계)**: 채널 잔여는 RDB SoT(`pg_outbox`/`pg_inbox`) + 폴링 회수로 **유실 0 이 이미 보장**된다. drain 의 실익은 "종료 시 인메모리 잔여 즉시 처리 → 재기동 후 폴링 지연 단축"이라는 graceful 품질 개선에 한정. 학습 프로젝트에서 이 한계 이득이 동반 복잡도(① 새 유입 차단을 위한 Kafka consumer→워커→채널 SmartLifecycle phase 순서 정합, ② outbox/inbox 공통 base 대칭 처리 — inbox 는 벤더 호출 in-flight, ③ drain-timeout + 폴백 + K8s grace period 정합)를 정당화하지 못한다고 판단. 운영 환경에서 종료 지연이 실제 문제로 측정되면 재검토.

**참고 — 코드 현황 (재검토 시 출발점)**:
- stop 로직은 CLEANUP-BATCH-C 에서 `AbstractImmediateWorker.stop(Runnable)` 로 공통화됨 (outbox/inbox 즉시 워커 공유). 현재 `running=false` → 워커 `interrupt` → `join(10s)` → executor `awaitTermination(10s)→shutdownNow`. 채널 잔여 drain 단계 없음.
- 이미 `executor.submit` 된 in-flight 는 executor graceful shutdown 으로 완료 대기됨. 미take 채널 잔여만 종료 시 메모리 소멸 → 폴링 회수.
- 채널(`PgOutboxChannel`/`PgInboxChannel`)은 SmartLifecycle 아님(단순 `LinkedBlockingQueue` 빈). `AbstractImmediateWorker.getPhase()` 주석의 "채널 나중 stop drain" 의도는 채널이 lifecycle 이 아니라 미실현 — 재검토 시 이 갭부터 정리.

**관련 코드**:
- `pg-service/.../infrastructure/scheduler/AbstractImmediateWorker.java` (`stop(Runnable)` 공통 base)
- `pg-service/.../infrastructure/channel/{PgOutboxChannel,PgInboxChannel}.java`
- `pg-service/.../infrastructure/scheduler/{PgOutboxPollingWorker,PgInboxPollingWorker}.java` (RDB 폴백)

#### TC-15 — PG-CONFIRM-LISTENER-SPLIT PHASE2 정밀화

PG-CONFIRM-LISTENER-SPLIT 이 의도적으로 측정 없는 baseline 으로 채택한 값들의 부하 기반 정밀화 + 알려진 한계 해소.

**항목 1 — 워커 VT 풀 / 채널 cap / 좀비 임계 측정 기반 정밀화**:
- 워커 5개 / cap=1024 / PENDING-IN_PROGRESS 좀비 임계 60s 모두 측정 없는 baseline
- T4-B (k6 부하 곡선) 측정 결과로 벤더 latency p95 확인 → 임계 정밀화 (60s ↔ 실제 벤더 timeout × 2)
- cap=1024 가 peak TPS 에서 부족한지 overflow + fallback 빈도 측정
- yml 키 (`pg.inbox.channel.capacity` / `pg.inbox.channel.worker-count` / `pg.scheduler.inbox-polling-worker.*`) 로 즉시 조정 가능 — 코드 변경 없이 운영 배포 가능

**항목 2 — 멀티 인스턴스 worker concurrency 검증 (SKIP LOCKED 멀티 인스턴스)**:
- 현재 구현은 단일 인스턴스 가정. `FOR UPDATE SKIP LOCKED` 가 멀티 인스턴스 환경에서도 중복 처리 0 을 보장하는지 검증
- 검증 환경: 동일 pg-service 2~3 인스턴스 + 같은 `mysql-pg` DB + 동일 Kafka consumer group

**항목 3 — 좀비 폴링 회수 traceparent 이어붙이기**: ✅ 완료 (EOS-FOLLOWUP-CLEANUP, 2026-05-29). 상세: `docs/archive/eos-followup-cleanup/COMPLETION-BRIEFING.md`.

**관련 코드**:
- `pg-service/.../infrastructure/scheduler/PgInboxImmediateWorker.java`
- `pg-service/.../infrastructure/scheduler/PgInboxPollingWorker.java`
- `pg-service/.../infrastructure/channel/PgInboxChannel.java`
- `pg-service/src/main/resources/application.yml` (inbox 설정 키)

---

## Plan 작성 시 사용 가이드

- 각 T 항목을 새 토픽으로 승격할 때 `docs/topics/<TOPIC>.md` + `docs/<TOPIC>-PLAN.md` 신규
- 본 TODOS 의 항목은 plan 의 "근거" 절에서 인용 가능
- 토픽 종결 시 본 파일에서 해당 항목 삭제 (또는 archive briefing 으로 이전)

## 관련

- 학습된 함정: `PITFALLS.md`
- 알려진 우려: `CONCERNS.md`
- 완료 이력: `docs/archive/README.md`
- 직전 봉인 토픽 회고: `docs/archive/{msa-transition,pre-phase-4-hardening,stock-compensation-recovery,pg-confirm-listener-split}/COMPLETION-BRIEFING.md`
