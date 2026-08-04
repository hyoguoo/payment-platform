# Codebase Concerns

> 최종 갱신: 2026-08-04 (SIGNAL-AND-GUARDRAIL-SWEEP ship — C-11 1차 대조 결과 등재: 코드 리뷰에서는 원복 조건 미해당(Domain Expert findings 0건)이나 설계 게이트에서는 Reviewer 가 놓친 중대 지적이 3라운드 연속 나옴 — 하향 유지하되 판단 기준에 설계 게이트 포함. L-18 잔여 유지, 재시도 창 축소로 커진 격리 복구 압력은 `TODOS.md` 섹션 E 로 등재. 이전 갱신 이력은 `docs/archive/README.md` 와 각 토픽 COMPLETION-BRIEFING 참고)
> 운영 / 아키텍처 / 신뢰성 우려 인덱스. 새 항목은 우선순위와 함께 추가, 해소된 항목은 `TODOS.md` 또는 archive briefing 으로 이동.

## High — Phase 4 진입 차단 가능성

### C-1. Toxiproxy 장애 주입 검증 부재

- **현황**: 단위 테스트는 회복성을 검증하나 통합 환경 8가지 시나리오 전수는 미검증. **부분 진척**: Toxiproxy latency 드릴 프로파일(`docker/docker-compose.drill.yml` + `toxiproxy.json`, ALERTING-RULES 6/27) 구축 + 서비스/DB/Redis 다운 라이브 firing→해소 실측(FAULT-INJECTION 6/30 — `ServiceDown`·`DependencyDown{db,redis-dedupe}`·`KafkaBrokerUnavailable`·`DlqTopicOffsetRising`).
- **잔여 시나리오**: Kafka 지연 EOS abort, 프로세스 kill+재시작, 보상 중복 방지, FCG PG timeout, 재고 캐시 발산, DLQ 소진 + k6 부하 결합 전수
- **영향**: 운영 환경 실 장애 시 잔여 시나리오는 추측에 의존
- **처방**: Phase 4 — 잔여 시나리오 Toxiproxy + k6 부하 + 메트릭 검증

### C-2. CircuitBreaker 미적용 — cross-service HTTP

- **현황**: `ProductHttpAdapter` / `UserHttpAdapter` 가 `feign.RetryableException` 을 catch 해 `*ServiceRetryableException` 으로 변환하는 transport 분기 한 줄만 가짐. 4xx/5xx 매핑은 `ErrorDecoder` 가 담당. 재시도 / CircuitBreaker / fallback 은 미적용
- **영향**: product/user 서비스 장애 시 Feign 호출이 timeout 까지 spawn 누적 → payment-service 가 같이 끌려갈 위험
- **처방**: Phase 4 (T4-D) — Resilience4j CircuitBreaker 적용 + fallbackFactory 마이그레이션 (어댑터 try/catch 제거) + p95 latency 메트릭

### C-3. 로컬 오토스케일러 부재

- **현황**: 부하 시 수동 docker compose scale 만 가능. payment-service 의 큐 길이 / CPU 임계로 자동 scale 하는 메커니즘 없음
- **영향**: 부하 spike 시 응답 시간 발산
- **처방**: Phase 4 — Prometheus 메트릭 기반 로컬 scaler

## Medium — 운영 부담

### C-4. flyway_schema_history 운영 적용 가이드 부재

- **현황**: `baseline-on-migrate` 옵션을 default(false) 로 두고 있어 기존 DB 에 Flyway 도입할 때 수동 baseline 작업 필요
- **영향**: 운영 도입 시 시행착오 가능
- **처방**: STACK.md 운영 가이드 절 + `baseline-on-migrate: true + baseline-version: 0` 옵션 가이드 명시 (이미 본 문서 갱신에 포함)

### C-5. DLQ 자동 소비 부재 (수동 재주입 도구는 도입 — 부분 해소)

- **현황**: `payment.events.confirmed.dlq` 는 이제 **관리자 수동 재주입**(원 토픽 republish → EOS 컨슈머 재처리, DLQ-QUARANTINE-RECOVERY)으로 복구 가능하다(`DlqReprocessUseCase`/`KafkaDlqReprocessAdapter`, 관리자 API/버튼, 나이 게이트). `payment.commands.confirm.dlq` 는 pg-service 가 소비. **상시 자동 소비 컨슈머는 여전히 미도입** — 재주입은 관리자 트리거 on-demand.
- **영향**: 조건부 자동 재시도(벤더 5xx 등 일시 실패)는 미구현 — 관리자 개입 전까지 적재 잔류
- **처방**: 조건부 자동 재시도 정책은 후속 토픽 (TQ-1 잔여)

### C-6. 단일 Kafka broker

- **현황**: `kafka:9092` 1대. replication-factor=1
- **영향**: broker 장애 시 메시지 처리 중단
- **처방**: 운영 환경 / Phase 4 부하 테스트 시 multi-broker 검토

### C-11. Reviewer effort 하향(`xhigh` → `high`) — 도메인 인접 diff 오분류 사각

- **현황**: `AGENT-CONTEXT-OVERHAUL` Task 3 에서 `.claude/agents/reviewer.md` frontmatter `effort` 를 `xhigh` 에서 `high` 로 낮췄다. 근거는 검토 방법 2항이 이미 명시하는 역할 분리 — 결제 도메인 리스크(상태 전이·멱등성·race 등)의 깊은 판정은 Domain Expert 몫이고, Reviewer는 명백한 것만 짚고 미배석 시 domain-expert 호출을 권고하는 얕은 역할이라 xhigh 수준의 추론 예산이 상시 필요하지 않다는 판단.
- **영향(사각)**: effort 하향으로 Reviewer가 도메인 인접 diff에서 "명백한 것"의 판단선이 낮아져, 실제로는 도메인 리스크인데 명백하지 않아 놓치는 diff(오분류)를 더 자주 통과시킬 위험이 있다. 이 사각은 Domain Expert가 병행 배석하지 않는 라운드(단독 리뷰, 배차 조건 미충족 토픽)에서 가장 크다.
- **원복 조건**: effort 하향 적용 이후 Domain Expert 가 사후 배석한 라운드에서 Reviewer 가 놓쳤던 critical 또는 major 급 도메인 finding 이 새로 발견되면, `effort: high` 를 `xhigh` 로 즉시 복귀한다.
- **1차 대조 결과 (SIGNAL-AND-GUARDRAIL-SWEEP, 2026-08-04)**: 원복 조건에 **해당하지 않아 하향을 유지**한다. ship 코드 리뷰에서 Domain Expert 는 findings 0건으로 통과했고, Reviewer 가 놓친 도메인 결함을 Domain Expert 가 뒤늦게 잡아낸 사례가 없었다. Reviewer 의 findings 3건은 모두 커밋 이력·규약 문제였다.
- **다만 설계 단계는 양상이 달랐다**: discuss 게이트 3라운드에서 Domain Expert 가 Reviewer 가 짚지 못한 중대 지적을 연달아 냈다 — 격리 대응 근거로 다른 토픽의 도구를 잘못 인용한 점, 충돌 예외를 잡고 같은 영속성 세션을 이어 쓰는 패턴의 위험, 읽기 스냅샷 때문에 확인 조회가 앞선 행을 놓치는 경로(→ `PITFALLS.md` 25). plan 게이트에서도 잠금 읽기가 실행 가능한 형태로 내려오지 않은 것을 잡았다. 이 지적들이 없었다면 구현은 세션 오염과 스냅샷 격리라는 두 결함을 안고 진행됐을 것이다.
- **시사점**: 하향의 사각은 완성된 코드 리뷰보다 **설계 단계**에서 드러났다. 원복 판단 기준을 코드 리뷰 대조에만 두면 이 양상을 놓친다. 도메인 인접 토픽에서는 effort 설정보다 **설계 게이트에 Domain Expert 를 반드시 배석시키는 것**이 실효가 컸다.
- **처방**: 하향 유지. 다음 도메인 인접 토픽에서 한 번 더 대조하되, 코드 리뷰뿐 아니라 설계 게이트 지적도 함께 본다.

## Low — 코드 청결도

### C-8. archive 안의 historical 잔재 참조

- **현황**: archive 안의 여러 plan / context 문서가 옛 클래스 이름 (`OutboxImmediateWorker`, `executePaymentAndStockDecreaseWithOutbox` 등)을 참조
- **영향**: AI 에이전트가 archive 를 읽지 말라는 룰을 어기면 혼동
- **처방**: archive `README.md` 가 명시적으로 "AI 에이전트 미참조" 선언 — 이미 적용. 추가 조치 불필요

### ~~C-9. observability 대시보드 현행화~~ ✅ 해소 (OBSERVABILITY-COMPLETION, 2026-06-11)

- **해소**: 옛 `payment-dashboard.json` 폐기 + `business-dashboard.json`(funnel·전이·상태분포·격리·벤더latency·DLQ·outbox·cleanup·코디네이터·guard_skip) / `system-dashboard.json`(6서비스 JVM/GC/HTTP/Hikari/lag) 2분할 신설. 메트릭 이름 현행 코드 기준 정합.
- **후속 해소**: Prometheus alerting rule 인프라는 ALERTING-RULES-AND-FAULT-DRILL(6/27)에서 구축 — `prometheus.yml rule_files` → 4그룹(coordinator/guard-skip/dlq/availability, availability 는 FAULT-INJECTION 6/30) 평가 + `promtool test rules` 25케이스 회귀(`observability/prometheus/rules/`).
- **잔여**: Alertmanager 통지 채널 미도입(rule 평가/조회까지만).

### C-10. seed 데이터의 운영 안전성

- **현황**: `product/V2__seed_product_stock.sql` 와 `user/V2__seed_user.sql` 가 `INSERT IGNORE` 로 멱등이지만 운영 배포에 같이 적용됨
- **영향**: 운영 환경에 dummy seed 가 들어갈 가능성
- **처방**: 운영 배포 시 `spring.flyway.locations` 에서 seed 디렉토리 분리 또는 `placeholder` 활용. **현재는 데모/스모크 환경 한정으로 OK**

## 알려진 한계 (수용 — 별도 토픽 필요 시 plan)

### L-1. Kafka tx coordinator 의존 — 가용성 약화 (EOS 전환 수용)

- **현황**: EOS (Kafka 트랜잭션) 전환 이후 payment-service 결제 결과 처리가 Kafka tx coordinator 에 의존. broker 가 죽거나 tx coordinator 가 응답 못 하면 `ConfirmedEventConsumer` 처리 자체가 멈춤.
- **이전 모델 대비**: `StockOutbox` 모델에서는 RDB 만 살아있으면 outbox 행이 쌓이고 broker 복구 후 OutboxWorker 가 자동 회수 — 더 높은 가용성.
- **수용 근거**: 학습용 프로젝트 EOS 정합 목표 (D3). coordinator 가용성은 대시보드(OBSERVABILITY-COMPLETION) + 알람 3규칙(ALERTING-RULES-AND-FAULT-DRILL)으로 가시화 완료 — 의존 자체는 구조적으로 수용된 한계.
- **잔여**: 멀티 broker 환경에서의 lag 임계 재교정 — TODOS.md T4-B 정밀화 묶음(`[DE2]`) 참고.

**EOS atomicity 정합 SSOT (RD1-2 명시):**
- `PaymentConfirmResultUseCase.handle` 은 `@Transactional(transactionManager = "transactionManager", timeout = 5)` 로 qualifier 를 명시해 `JpaTransactionManager` 를 고정한다 — `KafkaTransactionManager(EOS)` 와는 여전히 별개 TM.
- 이 구조에서 RDB commit(JPA inner) 성공 + Kafka EOS commit(outer) 실패 시 at-least-once 재배달이 발생한다 (best-effort 1PC).
- **crash 내성 SSOT 는 종결 가드 재발행 (CONFIRM-APPROVED-RESEND-GAP, #112)**: APPROVED 경로에서 RDB DONE 커밋 후 EOS 발행이 유실되면 재배달이 D7 종결 가드에 도달하는데 `status==DONE && message==APPROVED` 면 stock-committed 를 재발행한다(`terminalResendMetrics` 계측). product-service 가 결정적 키로 멱등 흡수 → 차감 1회.
- **폐기**: 과거 SSOT "중복 시 발행 항상 진행(위키 line 141)" 분기는 dedupe 마킹과 종결 전이가 같은 JPA tx 원자 커밋이라 도달 불가 dead branch 였고, CONFIRM-APPROVED-RESEND-GAP 에서 제거됨.
- **잔여 한계 (over-sell, DLQ-REACHABILITY)**: 종결 가드 재발행도 같은 EOS tx 라 `commitTransaction` 지속 실패 시 stock-committed 자체는 완전 유실(payment DONE + 재고 확정 영구 소실 → over-sell). 입력 `events.confirmed` 메시지는 `KafkaConsumerConfig` 에 명시 연결된 `AfterRollbackProcessor`(공유 DLQ recoverer + `payment.kafka.after-rollback.backoff`)가 소진 후 `events.confirmed.dlq` 로 발행해 가시화한다(+`payment_eos_commit_failure_dlq_total`). 재고 확정 복구는 이제 **관리자 수동 DLQ 재주입**(DLQ-QUARANTINE-RECOVERY — 원 토픽 republish → EOS 컨슈머 재처리, 결정적 키가 중복 흡수)으로 가능. **상시 자동 복구는 여전 미수행** — 수용된 한계.
- **후속 과제**: TC-13-FOLLOW-6 — `ChainedKafkaTransactionManager` 도입 검토(qualifier 명시는 EOS-FOLLOWUP-CLEANUP 에서 이미 완료). over-sell 의 조건부 자동 복구(자동 재시도)는 TQ-1 잔여(수동 재주입은 DLQ-QUARANTINE-RECOVERY 에서 완료).

### L-4. Two-strategy PG 라우팅 — 결제 건별 `gatewayType` 결정 정책

현재 결제 건별 `gatewayType` 은 client 측에서 결정해 전송. 동적 routing (예: 벤더 장애 시 자동 fallback) 미구현.

### L-5. 회복 비대칭 — EOS abort 시 Redis 보상 lease 미회복

- **현황**: EOS abort 발생 시 RDB rollback + producer tx abort 는 자동 원복. 그러나 `compensateAtomic` (Redis 보상 Lua) 은 EOS tx 밖에서 실행 (Redis 는 XA 참여 불가) — abort 시 Redis 보상이 완료됐지만 RDB rollback 으로 결제 상태는 복귀 → 재배달 시 보상 dedup token `compensation:done:{orderId}` 이 이미 박혀 있어 보상 재실행이 `ALREADY_DONE` 으로 막힘.
- **빈도**: FAILED/QUARANTINED 경로 + EOS abort 가 동시에 발생하는 case 에만 해당. 빈도 낮음.
- **수용 근거**: SCR L7 cascade 평가 결과 수용. Redis 보상 dedup token 은 P8D TTL 로 자연 만료.
- **참고**: 보상 끝난 결제의 새 confirm 사이클 cascade(L-12) 와 관련.

### L-7. `markPaymentAsFail` 영구 실패 → Reconciler resetToReady cascade (인지)

`handleFailed` 호출 순서 (보상 → `markPaymentAsFail`) 에서 보상 OK + `markPaymentAsFail` 영구 실패 → DefaultErrorHandler retry 5회 후 DLQ → Reconciler 가 IN_PROGRESS 결제를 resetToReady → 새 confirm 사이클 → 벤더가 재confirm 시 APPROVED 회신 가능 → product RDB 차감 + redis 보상 +1 잔존 → 발산. PG 멱등성 (idempotency-key=orderId) 으로 일반적으로 차단. PHASE2 admin 도구 또는 자동 QUARANTINED fallback 별 토픽 결정.

### L-8. 단일 리전 / 단일 AZ

본 프로젝트는 학습용 — multi-region, geo-redundancy 미구현.

### L-9. 결제 cancel / refund 미구현

cancel / refund 경로 미구현 — pg 포트(`PgConfirmPort`/`PgStatusLookupPort`)에 cancel 메서드 자체가 없다. 운영 활용 별도 토픽.

### L-11. Redis cluster 환경에서 multi-key Lua 사용 불가

`stock_decrement_atomic.lua` / `stock_compensation_atomic.lua` 가 결제 단위 N개 상품 KEYS 를 한 번에 받는다. Redis cluster 에서는 same hash slot 이어야 하는데 글로벌 상품 키(`stock:{productId}`) 는 결제 단위로 hash tag 묶을 수 없음. **단일 노드 Redis 가정 위에서 성립**, cluster 도입 시 별 토픽.

### L-12. 보상 끝난 결제의 새 confirm 사이클 cascade (인지)

P8D 안에서 동일 orderId 의 `decrement:done` + `compensation:done` 두 dedup token 이 살아있는 상태에서 force resetToReady 등으로 새 confirm 사이클이 진입하면, `decrementAtomic` 이 `ALREADY_DONE → SUCCESS` 매핑되어 redis 재고는 +1 잔존 + 벤더가 APPROVED 회신 시 product RDB 차감 → 발산 가능. 정상 흐름에서는 결제 1건 = orderId 1건이라 발생 가능성 매우 낮음. PHASE2 token DEL 정책 정밀화 또는 admin 도구 (TODOS `STOCK-COMPENSATION-OTHER-PATHS`).

### L-14. confirm 결과수신 DB 다운 → reconciler 복원 후 order EXECUTING 잔류로 만료 차단 (READY 잔류 잔여 — poison-pill 격리 해소) (부분 해소)

confirm 결과수신 중 payment DB write 실패 → `events.confirmed`(APPROVED)가 1s×5 retry 후 `events.confirmed.dlq` stranded(벤더 과금됨, 자동소비 없음 C-5). `PaymentReconciler`가 IN_PROGRESS→READY 복원하지만 `PaymentEvent.resetToReady`는 event 상태만 바꾸고 `PaymentOrder`는 EXECUTING 잔류 → `PaymentExpirationServiceImpl.expireOldReadyPayments`의 `order.expire()`(NOT_STARTED 전용)가 INVALID_STATUS_TO_EXPIRE 전파. 두 문제: (1) **READY 영구 잔류** — EXPIRED 도달 불가, 벤더 과금+미이행 stranded가 비종결로 고착. (2) 만료 batch가 단일 `@Transactional` forEach라 stranded event 1건이 **무관한 정상 READY 만료까지 롤백**(poison-pill) — stranded 1건이 존재하는 한 만료가 영구 wedge되어 정상 READY 누적 → 각 redis 선차감 미해제 누적(보수적 under-sell 방향). 만료 정책(READY 만 직접 만료, IN_PROGRESS 정체분은 정합 스캐너 복원 후 만료의 2단 연쇄 — TIME-MODEL-AND-EXPIRY 에서 명문화)이 order 상태 미복원으로 **실제 차단**됨이 이번에 실측. 자동 복구(DLQ 재주입 + order/event 정합 복원)는 TQ-1/TC-3 및 별 토픽 위임.

**부분 해소 (2026-07-01)**: 문제 (2) **만료 batch poison-pill** 은 만료 배치를 건별 독립 트랜잭션 + 실패 격리로 해소했다 — `PaymentExpirationServiceImpl` 에서 `@Transactional` 을 제거해 `PaymentCommandUseCase.expirePayment`(별도 빈, 자체 `@Transactional`, self-invocation 아님)가 건별로 커밋/롤백하게 하고, 호출부 try/catch 로 단건 실패를 격리(`payment_expiration_skipped_total` 카운터 + WARN, never-silent)한다. stranded 1건이 무관한 정상 READY 만료를 더는 막지 않는다(만료 영구 wedge → 정상 READY 누적 → redis 선차감 미해제 누적 차단). 문제 (1) **READY 잔류**(stranded 자체) 는 여전 한계 — 비종결 READY 가 복구 여지상 안전 방향이라 자동 복구는 TQ-1/TC-3 위임 유지. 회귀: `PaymentExpirationServiceImplTest#expireOldReadyPayments_oneStranded_doesNotBlockOthers` + `ConfirmedDbDownIntegrationTest`(stranded 만료 실패 격리).

> 검토 기록: `resetToReady`가 order를 NOT_STARTED로 복원하게 바꾸면 expire 통과 → EXPIRED 종결 도달하나, EXPIRED는 terminal이고 D7 가드(`canApplyConfirmResult` EXPIRED=false)가 TQ-1 재주입을 noop으로 막아 **복구 영구 봉쇄**(더 나쁨). 따라서 그 변경은 plan 게이트에서 거부·롤백 — 비종결 READY 잔류가 복구 여지 면에서 안전 방향(domain-expert critical, 2026-06-30).

### L-15. 격리 복구 보상의 `decrement:done` P8D 만료 후 미복원 (보수적 언더셀)

격리 안전 종결(DLQ-QUARANTINE-RECOVERY)의 재고 보상은 `decrement:done:{orderId}` 토큰 존재에 조건화된다(`compensateIfDecremented` + `stock_compensation_if_decremented.lua` — 실제 차감이 없던 건에 보상해 재고를 부풀리는 유령 재고를 막기 위함, `EXISTS decrement:done` 을 `SETNX compensation:done` 보다 먼저 판정). 토큰 TTL(P8D=8일) 만료 후 복구하는 **실차감 건**은 토큰 소멸로 보상이 skip 돼 redis 선차감이 미복원 — 재고 과소(보수적 언더셀) 방향이라 안전 측 누수. P8D 초과 격리는 수동 대사로 우회, 자동 reconciler 는 TC-3 위임.

### L-16. 복구로 종결된 결제에 늦은 confirm 재요청 시 재차감·보상 불가 (보수적 언더셀)

`PaymentEvent.validateConfirmRequest` 가 종결·격리 상태를 검사하지 않아, 복구로 FAILED 종결된 결제에 늦은 confirm 재요청이 도착하면 redis 재차감(`decrementAtomic`) 후 종결 가드로 `execute` 가 차단되나 차감은 retention 유지되고, 복구가 이미 심은 `compensation:done` 토큰으로 보상이 `ALREADY_DONE` no-op → redis 선차감 잔존(보수적 언더셀). confirm 진입에 종결·격리 상태 조기 거부 가드를 두는 것은 후속.

### L-17. DLQ 재주입 전량 스캔 성능·관측성 한계

`KafkaDlqReprocessAdapter` 는 재주입 시 `events.confirmed.dlq` 전 파티션을 `seekToBeginning` 으로 스캔한다. 대량 적체 시 `read-timeout` 내 `endOffsets` 미도달 가능 — 스캔 미완료(재시도 안내 예외)와 "완주 후 없음"은 `DlqScanResult(payload, completed)` 로 구분하나, 최근 구간부터 역방향 탐색(`offsetsForTimes`)은 미구현이라 최근 메시지가 가장 나중에 스캔된다(사용 패턴과 역방향). 또 스캔 미완료 + 매치 존재 조합은 warn 로그 없이 발행(미스캔 구간에 더 최신 레코드 존재 가능 — 동일 orderId 는 동일 `eventUuid` 재발행이라 멱등 체인이 흡수). 관리 도구 사용 빈도 대비 수용, 역방향 탐색·`dlq_scan_incomplete` warn 은 후속.

### L-18. 모의 벤더(`FakePgGatewayStrategy`) 오배포 위험 — 실제 승인 없이 결제 완료 가능

- **현황**: 모의 벤더 전략은 `pg.gateway.type=fake` 일 때만 스프링 빈으로 로드된다(`@ConditionalOnProperty`, `FakePgGatewayStrategy.java:68`). 이 값은 `pg-service/src/main/resources/application-docker.yml:21` 에서 `${PG_GATEWAY_TYPE:toss}` 로 환경변수 오버라이드가 가능한 구조라, 스모크 구동용 값이 배포 파이프라인 환경변수에 남으면 그대로 적용된다. 로드되면 `supports()`(`FakePgGatewayStrategy.java:144-148`)가 벤더 종류를 가리지 않고 `TOSS`/`NICEPAY` 요청을 모두 받아들인다 — 사용자가 어느 벤더를 선택했든 모의 벤더가 처리한다.
- **영향**: 실제 벤더 승인 없이 결제가 완료되고 재고가 확정 차감된다. 탐지 수단은 기동 시 `warnActivation()`(`FakePgGatewayStrategy.java:132-142`)이 남기는 경고 배너 로그 하나뿐 — 기동을 막는 코드는 없다.
- **수용 근거**: 이 조건부 로드 구조와 무차별 `supports()` 자체는 `LIVE-DRILL-FORMALIZATION` 에서 새로 만든 것이 아니라 이미 운영 중이던 구조다. 이번 작업이 더한 것은 라이브 실측용 시나리오 접두어(`fake-fail-`/`fake-retry-`/`fake-flaky-`) 분기뿐이고, 오배포 위험 구조 자체는 이전부터 있었다 — 이 점을 빼면 우선순위 판단을 흐린다.
- **후속**: 부팅 시 환경 조합 검사 가드 — `TODOS.md` [FAKE-PG-BOOT-ENV-GUARD] 참고.

## 회피된 우려 (해소 완료, 기록 보존용)

| 우려 | 해소 위치 |
|---|---|
| ~~Sync/Outbox/Kafka 3전략 분리의 복잡도~~ | `outbox-only-refactor` archive — 단일 비동기 경로 |
| ~~UNKNOWN 상태의 조용한 흡수~~ | `payment-double-fault-recovery` archive — `PaymentGatewayStatusUnmappedException` |
| ~~payment-service Flyway 비대칭~~ | 이번 봉인 — Flyway 통일 |
| ~~`resetToReady`의 order NOT_STARTED 복원 = EXPIRED 종결화로 D7 복구 봉쇄~~ | FAULT-INJECTION-RESILIENCE plan 게이트 2026-06-30 — 롤백, 비종결 READY 유지 (L-14) |
| ~~AMOUNT_MISMATCH 단방향~~ | PRE-PHASE-4 — pg → payment 양방향 amount 대조 |
| ~~stock publish 가 TX 안에서 Hikari 점유~~ | PRE-PHASE-4 — AFTER_COMMIT 분리 |
| ~~Redis DECR 보상 부재~~ | PRE-PHASE-4 — caller 측 try/catch 보상 |
| ~~payment-history audit 누락 (직접 done() 호출)~~ | PRE-PHASE-4 — `@PublishDomainEvent` AOP 강제 |
| ~~consumer groupId 공유로 토픽 간 백압~~ | PRE-PHASE-4 — groupId 토픽별 분리 |
| ~~outbox immediate worker 의 race~~ | PRE-PHASE-4 — `@RepeatedTest(50)` 검증 |
| ~~문서/스킬에 옛 3전략 어조 잔재~~ | 이번 봉인 + context 갈아엎기 |
| ~~보상 silent loss (compensateStockCache try/catch swallow)~~ | STOCK-COMPENSATION-RECOVERY — Lua atomic + 호출 순서 뒤집기 + DefaultErrorHandler |
| ~~dedupe lease 8일 잠금 + 처리 권한 모호~~ | STOCK-COMPENSATION-RECOVERY — `EventDedupeStore` 폐기, Lua dedup token (orderId 단위) 으로 일원화 |
| ~~PaymentConfirmDlqPublisher 직접 호출~~ | STOCK-COMPENSATION-RECOVERY — Spring Kafka native `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` 위임 |

## 관련

- 학습된 함정: `PITFALLS.md`
- 향후 처리: `TODOS.md`
