# 현재 작업 상태

> 최종 수정: 2026-06-30 (Task 6 완료 → Task 7 진입)

## 활성 작업

- **주제**: FAULT-INJECTION-RESILIENCE (서비스·DB·Redis 가용성 알람 추가 + docker stop 완전 다운 시 정합 거동 실증)
- **단계**: execute
- **이슈/브랜치**: #118
- **활성 태스크**: Task 7 (redis-stock 보상실패 정합 거동 통합테스트 — compensateAtomic 실패 → EOS abort → DLQ 유실0 + 선차감 stranded 보수적)

## 재개 메모

plan 완료. PLAN `docs/FAULT-INJECTION-RESILIENCE-PLAN.md` 7태스크(① payment ② pg ③ product+user 의존성 가용성 게이지 → ④ availability.yml 알람+promtool → ⑤ 다운 주입 드릴+런북 → ⑥ DB다운 통합테스트 → ⑦ redis-stock 보상실패 통합테스트). 게이트 2R 종결: domain-expert pass, reviewer 조건부 pass(요약 브리핑 3곳 reconcile 충족).

Task 1 완료: `payment/infrastructure/metrics/DependencyHealthMetrics.java` — `dependency_up{component}` + `dependency_health_last_poll_timestamp_seconds` 게이지, VirtualThread ExecutorService 타임아웃 가드(2s), redis-dedupe/redis-stock 분리. 단위 테스트 7건 PASS, 전체 465건 PASS, spotbugs PASS.
Task 2 완료: `pg/infrastructure/metrics/DependencyHealthMetrics.java` — 컴포넌트 db+redis(단일 factory). 단위 테스트 6건 PASS, 전체 330건 PASS, spotbugs PASS.
Task 3 완료: `product/infrastructure/metrics/DependencyHealthMetrics.java`, `user/infrastructure/metrics/DependencyHealthMetrics.java` — db 단일 컴포넌트. user ClockConfig 신규 [Rule 1]. product 50건/user 9건 PASS, spotbugs PASS.
Task 4 완료: `observability/prometheus/rules/availability.yml` (ServiceDown for:1m / DependencyDown absent 백스톱 / DependencyHealthStale staleness 60s + absent 백스톱) + `rules/tests/availability_test.yml` (9케이스 — 발화/미발화/staleness/absent dead-branch 전 케이스 PASS). 기존 16케이스 회귀 없음.
Task 5 완료: `scripts/smoke/alert-firing-availability.sh` 신규(4시나리오: 서비스 프로세스/DB/redis-dedupe/redis-stock 다운 주입 → 발화+해소 폴링, promtool 격하 폴백). `alert-rules-promtool.sh` 4그룹 25케이스로 확장. `docs/smoke/alert-firing-check.md` availability 그룹 절 추가. bash -n 문법 통과. 라이브 환경 미기동 → 절차·기대치 문서화 격하.
Task 6 완료: `ConfirmedDbDownIntegrationTest` — `@EmbeddedKafka`+전용 MySQL+`@MockitoSpyBean doThrow(CannotAcquireLockException)`. markPaymentAsDone spy → DefaultErrorHandler 200ms×5 retry 소진 → events.confirmed.dlq 유실0(시나리오1). TestClock+reconciler.scan(IN_PROGRESS→READY)+expireOldReadyPayments(READY→EXPIRED) 명시 호출 → DLQ 유실0(시나리오2). [Rule 1] `PaymentEvent.resetToReady()` PaymentOrder 상태 미복원(EXECUTING→NOT_STARTED) 도메인 버그 수정 — `PaymentOrder.resetToNotStarted()` 신규 + 도메인 단위 테스트 갱신. 단위 471건/통합 2건 PASS, spotbugs PASS.

핵심 설계: `dependency_up{component}` 폴링 게이지(payment redis dedupe/stock 2분리, 타임아웃 2s 가드) + `dependency_health_last_poll_timestamp_seconds` staleness, availability.yml에 `==0 or absent` dead-branch 방지(PITFALLS §24). 통합테스트는 `@EmbeddedKafka`+전용 MySQL+`@MockitoSpyBean doThrow`(BaseIntegrationTest 미확장 — Kafka 없음), load-bearing = `events.confirmed.dlq` 유실0(시간 무관). EXPIRED는 expected, 단정은 "마스킹 가로질러 DLQ 증거 생존=silent 아님"(status!=EXPIRED 단정 아님). **no-divergence(over-sell 0)는 공허 단정이라 제외**(별 토픽 위임, SoT 번복 기록). redis-dedupe 다운=checkout fail-closed(중복 과금 없음). **신규 복구 로직 없음**(TQ-1/TC-3 위임).

## 최근 완료

- **ALERTING-RULES-AND-FAULT-DRILL** (Prometheus 알람 규칙 인프라 + Toxiproxy 장애 주입 발화 실증 — TC-13-FOLLOW-3·4 + Phase 5 진입. rule 평가만 도입(Alertmanager 미도입): `prometheus.yml` rule_files → `observability/prometheus/rules/*.yml` 로드, `/api/v1/{rules,alerts}` 평가까지. 3그룹 7규칙 — coordinator(EOS txn abort / events.confirmed lag / broker 가용성 backstop), guard-skip(위험 status skip 비율, 분모 `payment_transition_total{from_status=IN_PROGRESS}` resetToReady AOP 우회 비오염), dlq(앱 카운터 / `.dlq` offset 델타 / `commands.confirm.dlq` lag 독립 cross-check, 합산 금지). `promtool test rules` 16케이스 회귀 고정. Toxiproxy latency 전용 드릴 프로파일(`docker-compose.drill.yml`, 평상시 미기동). **라이브 실증이 promtool 사각 dead branch 발견** — broker 완전 정지 시 kafka_brokers 는 0 아닌 absent → `kafka_brokers<1` 단독 미발화 → `absent(kafka_brokers)` 3분기 보강 + PITFALLS #24. KafkaBrokerUnavailable·DlqTopicOffsetRising 라이브 발화 실측. 단일 broker 구조 한계(payment 가 commands.confirm producer 겸 consumer → lag 비대칭 미실현 피크 ~150·EOS abort 미발화)로 코디네이터/EOS 라이브 발화는 promtool+통합테스트 격하. 애플리케이션 코드 무변경. 7태스크, promtool 16케이스+셸 5종+compose 양 스택 검증, discuss R3·plan R2·ship 리뷰 R2 pass critical0/major1 doc-sync/minor6(해소4·후속2), 18커밋, 2026-06-27, 이슈/브랜치 #116) — `docs/archive/alerting-rules-and-fault-drill/COMPLETION-BRIEFING.md`
- **DLQ-REACHABILITY** (장애 지속 시 DLQ 도달 보장 — [PG-SELFLOOP-ATTEMPT-GAP]+TC-13-FOLLOW-7 둘 다 해소. Track P: pg self-loop 시도횟수가 런타임 1 고정(relay 헤더 미발행+attempt 컬럼 부재)이라 한도 dead branch·무한 반복하던 것을 `pg_inbox.attempt`(Flyway V5) SoT로 영속(Option B), 워커 resolveAttempt 읽기+retry 분기 incrementAttempt(TX_B) 누적→4 소진 시 기존 DLQ→QUARANTINED 자동 격리. 격리 metric은 QUARANTINED 전이 성공 지점(멱등). Track E: payment EOS 커밋 반복 실패가 컨테이너 디폴트 AfterRollbackProcessor(9회·DLQ 미진입)로 빠지던 것을 `setAfterRollbackProcessor` 명시 연결(공유 recoverer 빈 추출+신규 `payment.kafka.after-rollback.backoff.*` 기본 1000ms×5)로 confirmed.dlq 도달+metric. 비트랜잭션 DLQ 템플릿이라 실패 EOS tx와 분리. #7 갭-문서화→갭-수정-검증 전환. 수용 한계: over-sell 자동 복구는 TQ-1 후속, attempt over-count(동시 진입 조기 격리)는 안전 방향 수용. 4태스크, pg 단위 324+통합 9/payment 단위 458+통합 39 PASS+린트, discuss R2·plan R2·ship pass critical0/major1 doc-sync/minor2, 2026-06-25, 이슈/브랜치 #114) — `docs/archive/dlq-reachability/COMPLETION-BRIEFING.md`

전체 이력: `docs/archive/README.md` / 구 STATE 이력: `docs/archive/state-history-2026H1.md`
