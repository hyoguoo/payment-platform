# Planned Cleanup / Future Work

> 최종 갱신: 2026-08-04 (SIGNAL-AND-GUARDRAIL-SWEEP ship — 해소 항목 8건 삭제: 마스킹 소실 / trigger 자동감지 dead branch / 백오프 off-by-one / 좀비 타임아웃 겹침 / 워커 span 부재 / 중복 confirm 가짜 재고 신호 / 체크아웃 중복 필드 누락 / 지침 문서 후속 4건. 섹션 E 신설 후 잔여 3건 등재, 섹션 F 를 겹침 해소 반영해 축소. 이전 갱신 이력은 `docs/archive/README.md` 와 각 토픽 COMPLETION-BRIEFING 참고)
> 분류 룰: **현재 과업** = 측정 / Toxiproxy / 멀티 인스턴스 환경 의존 없는 작업. **Phase 5** = 부하 측정 결과 또는 인프라 환경 필요. 내부 "Phase 5" 번호는 README 의 독자용 개발 과정 Phase 1~7 체계와 별개다(서로 다른 축 — 혼용 금지).
> discuss 단계 시작 시 다음 작업을 고를 때 이 파일을 참고한다.

---

## 현재 과업 (작업 가능 — 측정 / 인프라 무관)

### A. 위키 정합 (큰 토픽 1)

#### TC-13-FOLLOW-6 — ChainedKafkaTransactionManager 검토 (미채택) (RD1-2)

- **배경**: `PaymentConfirmResultUseCase.handle` 은 `@Transactional(transactionManager = "transactionManager", timeout = 5)` 로 JPA TM 을 명시 고정한다(qualifier 명시 완료, EOS-FOLLOWUP-CLEANUP). `KafkaTransactionManager(EOS)` 와는 여전히 별개 TM 이라 crash 시 at-least-once 재배달이 발생 가능.
- **정합 SSOT**: crash 내성 = 종결 가드 DONE+APPROVED 재발행 + product-service 결정적 키 dedupe 흡수 (CONFIRM-APPROVED-RESEND-GAP, #112 — 과거 "중복 시 발행 항상 진행(위키 line 141)" 은 dead branch 라 제거됨. CONCERNS.md L-1, CONFIRM-FLOW.md §5).
- **미채택 (잔여)**: `ChainedKafkaTransactionManager` 도입 — JPA TM 과 Kafka TM 체인으로 원자성 강화. 운영 환경에서 at-least-once 허용 불가 수준의 중복 발생 시 재검토.

### B. EOS-FOLLOWUP-CLEANUP 후속 등재

#### [CLEANUP-BATCH-B 후속] — 커버리지 게이트 / 빌드 스크립트 잔여 (CLEANUP-BATCH-B, 2026-05-31)

- **infra 커버리지 집계 제외** — `**/infrastructure/**` 제외로 EOS `ConfirmedEventConsumer`/dedupe 어댑터가 커버리지 집계에서 빠짐(측정 대상 정책 유지, G1). `PaymentEosIntegrationTest` 가 실행되어 회귀 가드는 유효하므로 도메인 위험 아님. 측정 대상 확대는 별도 토픽 여지.

### C. 코드 확인 필요 항목 (진단 단계 발견 — 코드 수정 없음, 등재만)

> `DOCS-CONSISTENCY-OVERHAUL` 진단(§4.5) 중 문서 정정 범위를 벗어난 코드측 발견이다. 데드 코드/회귀 여부 판정은 사용자 확인이 필요하며, 이 항목은 확인 필요성만 등재한다.

#### [PAYMENT-OUTBOX-INFLIGHT-UNUSED] — REQUIRES_NEW 선점 경로 프로덕션 미사용

- **현황**: `PaymentOutboxUseCase.claimToInFlight`(REQUIRES_NEW 선점)·`incrementRetryOrFail` 프로덕션 호출처 0 — `OutboxWorker` 는 `recoverTimedOutInFlightRecords`/`findPendingBatch` 만 호출한다. 실제 발행 실패 경로(`OutboxRelayService.relay` 단일 TX)는 롤백으로 PENDING 복귀 후 `OutboxWorker` 5초 주기 배치가 재픽업 — retryCount 증가·backoff 없이 무백오프로 재시도된다.
- **영향**: `nextRetryAt` 기반 backoff 설계가 이 경로에서는 실효되지 않는다 — 벤더/브로커 부하 시 재시도 폭주 가능성. IN_FLIGHT 타임아웃 회수(`recoverTimedOutInFlightRecords`)는 워커 비정상 종료 등 드문 경로의 보조 안전장치로만 유효.
- **처방**: 단일 TX 즉시 재시도가 충분하다는 의도된 단순화인지, REQUIRES_NEW 선점을 실제로 연결했어야 하는 미완성 회귀인지 코드/설계 이력 확인 필요. 데드 코드 판정(제거 여부)은 사용자 확인 필요.

### D. LIVE-DRILL-FORMALIZATION 후속 (라이브 실측 체계 정식화, 2026-07-29)

#### [FAKE-PG-BOOT-ENV-GUARD] — 모의 벤더 로드 시 환경 조합 검사 가드 부재

- **현황**: 모의 벤더가 `pg.gateway.type=fake` 로 로드될 때 그 환경이 허용된 조합인지 검사하는 코드가 없다. `warnActivation()`(`FakePgGatewayStrategy.java:132-142`)은 경고 배너 로그만 남기고 기동을 막지 않는다.
- **영향**: 스모크 구동용 환경변수(`pg-service/src/main/resources/application-docker.yml:21` `${PG_GATEWAY_TYPE:toss}`)가 배포 파이프라인에 남으면 실 승인 없이 결제가 완료된다(CONCERNS.md L-18).
- **처방**: 부팅 시 허용된 환경 조합(프로파일/환경변수 조합)인지 검사해 아니면 기동을 멈추는 가드 도입. 어떤 환경을 정상으로 볼지 정하는 배포 환경 논의가 선행돼야 한다.

### E. SIGNAL-AND-GUARDRAIL-SWEEP 후속 (신호 정합과 가드레일 정비, 2026-08-04)

> 아래 3건은 `SIGNAL-AND-GUARDRAIL-SWEEP` 이 의도적으로 범위 밖으로 뺀 잔여다. 상세: `docs/archive/signal-and-guardrail-sweep/COMPLETION-BRIEFING.md`.

#### [STATIC-CHECK-GATE-PROMOTION] — 정적 검출·지침 검사의 게이트 승격 판단

- **현황**: 코드 스타일 5규칙(`config/checkstyle/checkstyle.xml`)과 지침 문서 검사(`.github/workflows/ci.yml` 의 `agent-docs-check` job)는 모두 결과만 보고하고 빌드를 막지 않는다 — 전자는 `severity=warning`, 후자는 종료 코드 0 고정이다.
- **처방**: 몇 차례 운용해 오탐이 잦아드는지 본 뒤 게이트로 승격할지 결정한다. 승격 시 억제 목록에 남은 기존 위반이 먼저 정리돼야 한다(아래 항목).

#### [STYLE-BASELINE-SUPPRESSION-CLEANUP] — 기준선 억제로 덮어둔 기존 스타일 위반 정리

- **현황**: 정적 검출 도입 시 기준선을 0 으로 만들기 위해 기존 위반을 `config/checkstyle/checkstyle-suppressions.xml` 에 전량 등재했다 — `var` 키워드 2건(테스트), 빈 catch 블록 1건(테스트), try 블록 외부 변수 재할당 3건(프로덕션 2 / 테스트 1). `@Data` 와 공개 유스케이스·포트 null 반환은 기존 위반 0건이었다.
- **처방**: 억제 항목을 하나씩 걷어내며 실제로 고친다. 프로덕션 코드 2건(`StockCatalogViewServiceImpl.java:36`, `PgAttemptHistoryViewServiceImpl.java:36`)이 우선순위가 높다.

#### [RETRY-WINDOW-NARROWED-QUARANTINE-PRESSURE] — 재시도 창 축소로 커진 격리 복구 후속의 무게

- **현황**: 재시도 백오프 회차 정정으로 총 재시도 창이 78초에서 26초로 줄었다(`PgVendorCallService.insertRetryOutbox`). 벤더 장애가 그 사이 길이로 지속되면 이전에는 재시도로 자연 회복했을 결제가 격리로 남는다.
- **영향**: 격리를 벗어나는 관리자 경로는 벤더 상태를 재조회하지 않는 편도 실패 종결(`QuarantineResolveUseCase.resolve`)뿐이고, 벤더 취소·환불 포트 자체가 없다. 응답만 유실된 승인이었다면 시스템은 실패로 정리되지만 벤더 쪽 과금은 남는다 — 이 조합을 만날 확률이 올라갔다.
- **처방**: 격리 복구(TQ-2 잔여)와 환불(TQ-6)의 우선순위를 이 사실에 맞춰 재평가한다. 그 전까지는 격리 사유가 재시도 소진인 건을 안전 종결하기 전에 벤더 상태를 사람이 확인한다. 안전 종결 전 상태 조회를 코드로 강제하는 가드는 상태 조회 포트와 관리자 화면 흐름을 함께 손봐야 해 별도 토픽 여지.

### F. ADMIN-VISIBILITY discuss 발견 (관리자 화면 가시성 확충, 2026-07-27)

> `ADMIN-VISIBILITY` discuss 단계에서 발견됐으나 해당 토픽(관측 전용 화면 추가) 범위를 벗어난다.

#### [PG-ZOMBIE-OUTBOX-PHANTOM-ROW-HISTORY] — 종결 이후 발행 행이 이력 화면에 남는 문제

- **현황**: 좀비 회수와 재시도 예약이 겹치던 원인(백오프 off-by-one)은 `SIGNAL-AND-GUARDRAIL-SWEEP` 에서 해소됐다(최대 대기 22.5s < 좀비 타임아웃 60s). 다만 relay 가 inbox 상태를 보지 않고 `available_at <= now` 만으로 발행하는 구조(`PgOutboxRelayService.java:59-79`)와, 소비 측이 종결 상태를 발견하고 흔적 없이 건너뛰는 처리(`PgInboxImmediateWorker.java:159-163` TERMINAL_SKIP)는 그대로다.
- **영향**: 겹침 자체가 사라져 발생 경로는 크게 줄었지만, 다른 이유로 종결 이후 발행 행이 생기면 outbox 를 이력으로 읽는 화면이 시간 역전된 항목을 보여줄 수 있다. `ADMIN-VISIBILITY` 는 이력 조립 단계에서 종결 시각 기준 라벨링으로 표시를 교정한다.
- **처방**: relay 가 발행 직전 inbox 종결 여부를 확인하도록 할지, 표시 교정으로 충분하다고 볼지 판단 필요.

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
- `payment_outbox`: `RetryPolicy` 존재 — `RetryPolicyProperties` (env 주입) + maxAttempts=5 + FIXED 5s default. 단, 이 정책을 적용하는 `incrementRetryOrFail`(REQUIRES_NEW 선점 경로 전용)이 프로덕션 호출처 0([PAYMENT-OUTBOX-INFLIGHT-UNUSED] 참조) — 실제 발행 실패 경로(`OutboxRelayService.relay` 단일 TX 롤백)는 retryCount 증가·`FAILED` 종결 없이 5초 주기 무백오프로 재시도된다. `PaymentOutboxStatus.FAILED` 도달 코드 경로도 현재 0건.

**조정 필요 사항**:
1. **payment_outbox 정책 재검토** — [PAYMENT-OUTBOX-INFLIGHT-UNUSED] 확인 결과에 따라 REQUIRES_NEW 선점 경로를 실제로 연결할지, 현재 단일 TX 무백오프 재시도를 유지하고 backoff 를 그 경로에 이식할지 결정 필요. maxAttempts=5 + FIXED 5s 가 SLO 기준 적절한지 측정 검증도 병행 (Phase 5 자물쇠 — k6 측정 후)

**관련 코드**:
- `payment-service/.../domain/PaymentOutbox.java` — retryCount + incrementRetryCount
- `payment-service/.../application/config/RetryPolicyProperties.java`
- `payment-service/.../domain/RetryPolicy.java`

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
