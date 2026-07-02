# 문서 전수 정합 개선 — 진단 리포트

> 최종 갱신: 2026-07-02 (Task 4 — README + PAYMENT-FLOW-GUIDE 진단: outbox 발행 실패 stale 클러스터가 GUIDE 에도 5곳 확장 잔존 확인 + README "주요 해결 과제" 표 "장애 내성 복구 체계" 행 전체가 폐기된 3개념(`RecoveryDecision` 완전 삭제·`canCompensateStock` 가드 완전 삭제·FCG 프로덕션 호출처 0)을 현재형으로 서술 중임을 신규 발견(S1 critical) + README "결제 상태 관리" 섹션 "보상 안전 가드 자체는 유지" 서술이 코드와 정반대임을 신규 발견 + Outbox 모델 표 FAILED dead-terminal 미표기 확장 + Phase 축 3종(README 개발순서/결제단계/MSA로드맵) 전수 채록 + 위키 링크 25건 슬러그 전건 유효 확인 + README 도메인 사실(S1) 항목 별도 표기(ship 대조 입력용)). 이전: 2026-07-02 (Task 3 — 잔여 에이전트 문서 12파일 + smoke 5파일 진단: 대상 17파일에는 S1 클러스터 3종(outbox REQUIRES_NEW/IN_FLIGHT·FAILED dead-terminal·parallel-enabled 층위) 잔존 0건 확인 + 17파일 자체 교차 대조로 신규 S1 4건 발견(STRUCTURE.md 빌드/JaCoCo 서술 2건이 STACK.md/TESTING.md 와 정면 모순, STACK.md 스케줄러 매트릭스 user-service 누락, conventions/transactions.md 예시 qualifier 누락) + S4 중복 4건 SSOT 지정). 이전: 2026-07-02 (Task 2 — 플로우·대장·함정 5파일 진단: CONFIRM-FLOW/PAYMENT-FLOW 의 outbox REQUIRES_NEW/IN_FLIGHT stale 클러스터 확장 확정 + PaymentOutboxStatus.FAILED dead-terminal 신규 발견 + TODOS/CONCERNS 3분류 예비 판정 + PITFALLS ID 참조 오류 2건 발견)
> 이 문서는 `docs/DOCS-CONSISTENCY-OVERHAUL-PLAN.md` Task 2~19 가 채워 넣는 **근거 대장**이다. 모든 수정(Task 7~17)은 이 문서의 항목을 근거로만 수행한다.
> ship 시 `docs/archive/docs-consistency-overhaul/`로 이동한다.

## 0. 형식 정의

### 0.1 항목 형식 (Task 2~6 이 채우는 표의 컬럼)

| 컬럼 | 의미 |
|---|---|
| **문서 위치** | 파일 경로 + 섹션/줄 (예: `docs/context/CONFIRM-FLOW.md §12`, `outbox-pattern.md L186-194`) |
| **문제** | 무엇이 어떻게 틀렸거나 낡았는지 한 문장 |
| **소스 근거** | **소스 코드 파일:라인만.** 다른 문서(`docs/context/` 상호 인용, archive briefing, 위키 상호 인용)는 근거로 불인정 — 코드가 없으면 "근거 없음"으로 표기하고 심각도를 낮춘다 |
| **수정 방향** | 삭제 / 문장 교체 / 신규 서술 / 보존(변경 없음이 결정인 경우도 명시) |
| **심각도** | S1~S5 (아래 0.2) |

### 0.2 심각도 분류

| 등급 | 정의 |
|---|---|
| **S1** | 코드-문서 불일치 — 문서가 현재 코드와 반대이거나 존재하지 않는 걸 있다고 서술 |
| **S2** | 문서 간 · 문서 내 모순 — 같은 사실을 다르게 서술 |
| **S3** | 완료 잔존 · 노후 — 이미 끝난 일을 "예정"으로, 또는 스냅샷 시점이 오래됨 |
| **S4** | 중복 · 비대 — 같은 내용을 여러 문서가 반복 서술 (SSOT 미지정) |
| **S5** | 문체 (AI체) — 평가 형용사·번역투·과도한 단정문 |

### 0.3 기본값 인용 규칙 (게이트 2R minor 반영)

문서가 설정값의 "기본값"을 인용할 때는 **반드시 층위를 명시**한다 — 같은 키가 계층마다 다른 기본값을 가질 수 있다.

- **코드 fallback**: `@Value("${key:default}")` 또는 `@DefaultValue("...")` 애노테이션의 값 — 어떤 profile yml 도 없을 때 최후 적용
- **default profile yml**: `application.yml` 의 명시값 — 로컬/테스트 기본 구동 시 적용, 코드 fallback 을 덮어씀
- **profile별 yml**: `application-docker.yml` / `application-benchmark.yml` 등 — 운영/벤치마크 시 추가로 덮어씀

**실증 사례**: `scheduler.outbox-worker.parallel-enabled` — 코드 fallback `false`(`OutboxWorker.java:26` `@Value("${scheduler.outbox-worker.parallel-enabled:false}")`) vs default profile yml `true`(`application.yml:149`) vs benchmark profile `${SCHEDULER_PARALLEL_ENABLED:true}`(`application-benchmark.yml:25`). "기본값은 false다"라고만 쓰면 default profile 로 도는 로컬/docker 구동 실측과 어긋난다 — 인용 시 "코드 fallback: false / default 프로파일: true" 두 값을 함께 적어야 한다.

---

## 1. 사실 목록 (Fact Ledger)

EOS 전환(2026-05-17 봉인, PAYMENT-EOS-TRANSITION) 이후 ~ TC-3 재고 수동 resync(2026-07-01) 사이 archive 봉인 토픽 + 봉인 이후 standalone 커밋에서 "코드에 실제로 일어난 변경"을 추출해 **소스에서 재확인**한 목록이다. archive briefing 은 후보 목록 출처일 뿐이며, 아래 각 행은 briefing 인용이 아니라 소스 파일:라인 확인 결과다. Task 2~6 이 문서 대조 시 1차 입력으로 쓴다.

| # | 사실 | 소스 근거 (파일:라인) | 최초 도입/변경 토픽 |
|---|---|---|---|
| F1 | `PaymentConfirmResultUseCase.handle` 은 `@Transactional(transactionManager = "transactionManager", timeout = 5)` 로 JPA TM 을 **명시** qualifier 고정 (qualifier 미명시 아님) | `PaymentConfirmResultUseCase.java:116` | EOS-FOLLOWUP-CLEANUP 2026-05-29 |
| F2 | `OutboxRelayService.relay` 는 단일 `@Transactional` 안에서 선점(`claimToInFlight`)·발행·`toDone()` 을 모두 수행 — 발행 실패 시 TX 롤백으로 **PENDING 복귀**(IN_FLIGHT 잔류 아님), REQUIRES_NEW 선점 분리 없음 | `OutboxRelayService.java:49-59` (Javadoc 46-47 "실패 시 rollback으로 PENDING 유지가 올바른 동작") | PAYMENT-EOS-TRANSITION 이전부터 현재까지 이 구조 |
| F3 | `PaymentOutboxUseCase.claimToInFlight`(REQUIRES_NEW 선점)·`incrementRetryOrFail` 은 프로덕션 호출처 0 — `OutboxWorker` 는 `recoverTimedOutInFlightRecords`/`findPendingBatch` 만 호출 | `PaymentOutboxUseCase.java:36-55` 정의, 호출부 `OutboxWorker.java:38,41` (해당 두 메서드 호출 없음, grep 확인) | 구조상 상시 — 코드 확인 필요 항목 (topic 결정) |
| F4 | `OutboxWorker` 폴링 주기 5초, 발행 실패는 retryCount 증가 없이(F3) 무백오프 재시도 | `application.yml:147` (`fixed-delay-ms: 5000`) | 상시 |
| F5 | `DedupeCleanupWorker`(`@Scheduled`) 가 `payment_event_dedupe` 만료행을 `deleteExpired` 로 청소 — "후속 항목" 아니라 구현 완료 | `payment-service/.../infrastructure/scheduler/DedupeCleanupWorker.java` (파일 존재) | EOS-FOLLOWUP-CLEANUP 2026-05-29 |
| F6 | `PaymentEventStatus` enum 은 8개 값(READY/IN_PROGRESS/DONE/FAILED/CANCELED/PARTIAL_CANCELED/EXPIRED/QUARANTINED) — **RETRYING 없음** | `PaymentEventStatus.java:3-12` | CLEANUP-BATCH-E 2026-06-21 |
| F7 | `PaymentEventStatus.canCompensateStock()` 메서드 자체가 코드베이스에서 완전 삭제(grep 0) — `handleFailed`/`handleQuarantined` 는 가드 없이 `stockCachePort.compensateAtomic` 직접 호출 | `PaymentConfirmResultUseCase.java:280-303`, 전체 grep 0 | STOCK-COMPENSATION-OTHER-PATHS 2026-06-21 |
| F8 | `StockCachePort` 포트는 `decrementAtomic`/`compensateAtomic`/`set` 3메서드만 존재 — 단건 API(`decrement`/`rollback`/`findCurrent`/`current`) 5종 삭제 | `StockCachePort.java:23,35,48` | CLEANUP-BATCH-E 2026-06-21 |
| F9 | `payment_event.retry_count` 컬럼 DROP + 도메인 필드 제거(`payment_outbox.retry_count` 는 별개 컬럼으로 존치) | `V5__drop_payment_event_retry_count.sql:13` | RETRY-METRIC-CLEANUP 2026-06-22 |
| F10 | DONE + APPROVED 재배달 시 종결 가드가 noop 하지 않고 `sendStockCommittedEvents` 재발행(`terminalResendMetrics` 계측) | `PaymentConfirmResultUseCase.java:124-138` | CONFIRM-APPROVED-RESEND-GAP 2026-06-22 |
| F11 | `pg_inbox.attempt` 컬럼(Flyway V5) 이 self-loop 시도횟수 SoT | `pg-service/.../V5__add_pg_inbox_attempt.sql` | DLQ-REACHABILITY 2026-06-25 |
| F12 | `KafkaConsumerConfig` 가 `factory.setAfterRollbackProcessor(...)` 명시 연결 — EOS `commitTransaction` 반복 실패 시 `events.confirmed.dlq` 로 도달(과거엔 컨테이너 디폴트 9회 후 단순 스킵) | `payment-service/.../KafkaConsumerConfig.java:92` | DLQ-REACHABILITY 2026-06-25 |
| F13 | `prometheus.yml` 가 `rule_files` 로 4그룹(coordinator/guard-skip/dlq/availability) 규칙 로드 — Alertmanager 통지는 미도입, 평가/조회까지만 | `observability/prometheus/prometheus.yml:10`, `observability/prometheus/rules/{coordinator,guard-skip,dlq,availability}.yml` | ALERTING-RULES-AND-FAULT-DRILL 2026-06-27 + FAULT-INJECTION-RESILIENCE 2026-06-30(availability 그룹) |
| F14 | 4서비스 `DependencyHealthMetrics` 가 `dependency_up{component}` 폴링 게이지 노출 | `{payment,pg,product,user}-service/.../infrastructure/metrics/DependencyHealthMetrics.java` (4파일 존재) | FAULT-INJECTION-RESILIENCE 2026-06-30 |
| F15 | 만료 배치가 건별 독립 트랜잭션으로 분리돼 stranded 1건이 다른 정상 READY 만료를 막지 않음(poison-pill 격리) — `PaymentExpirationServiceImpl` 은 `@Transactional` 없이 `PaymentCommandUseCase.expirePayment`(별도 빈, 자체 TX)를 건별 try/catch 로 호출 | `PaymentExpirationServiceImpl.java:41-50` | L-14 부분 해소, 2026-07-01 (커밋 c0d1b90c) |
| F16 | stranded 결제(DB 다운 등으로 READY 잔류) 는 여전히 자동 복구되지 않음(비종결 READY 영구 잔류) — poison-pill 해소와 별개 한계 | `docs/context/CONCERNS.md:159-161`(코드 근거는 F15 와 동일 파일 — `expire()` 는 order NOT_STARTED 만 대상이라 order EXECUTING 잔류분은 여전히 미해결, `PaymentOrder` 상태 전이 로직 grep 필요 시 Task 7/9 에서 재확인) | FAULT-INJECTION-RESILIENCE 2026-06-30 |
| F17 | payment `POST /admin/stock/resync/{productId}` 가 product RDB 재고를 조회해 redis-stock 을 단건 SET 으로 덮어씀(수동, 단건 한정) — 전체 일괄/자동 발산 감지는 미구현 | `StockAdminController.java`, `StockResyncUseCase.java` (파일 존재) | TC-3 부분 완료, 2026-07-01 (커밋 fa160b34/b39b510e) |
| F18 | `PITFALLS.md` 는 헤더 최종 갱신 2026-05-17 로 표기하지만 본문 `## 24` 항목은 2026-06-27 산출물(broker 완전 정지 absent 분기) | `docs/context/PITFALLS.md:3`(헤더) vs `:248-254`(§24 본문, "absent(kafka_brokers)" 최근 도입 서술) — 헤더-본문 시점 자체가 문서 내부 근거이며 §24 도입 사실은 alerting rule 소스(F13)로 뒷받침 | 헤더 갱신 누락 |
| F19 | 로그·트레이스 관측성은 Promtail → Loki 경유(Elasticsearch/Logstash 아님) — `LogFmt` 로그가 Loki 에 적재, orderId 검색 → derivedField 로 Tempo 점프 | `docs/context/STACK.md:67,70,80` (Loki/Promtail 정의, 로그 기반 추적 진입 서술) | OBSERVABILITY-COMPLETION 2026-06-10~11 |
| F20 | `business-dashboard.json` / `system-dashboard.json` 2분할 — 옛 `payment-dashboard.json` 폐기 | `observability/grafana/dashboards/{business-dashboard,system-dashboard}.json` (파일 존재, `payment-dashboard.json` 부재) | OBSERVABILITY-COMPLETION 2026-06-10~11 |
| F21 | `LocalDateTimeProvider`/`SystemLocalDateTimeProvider` 포트는 코드베이스에서 완전 제거(grep 0, 테스트 파일 주석의 역사적 언급만 잔존) — 4서비스 `Clock` 빈 + `Instant` 로 시간 표준 통일 | 전체 grep 0 (`payment-service/src/test/.../JdbcPaymentEventDedupeStoreRoundTripTest.java:46,135` 만 주석으로 과거 비교 언급) | TIME-MODEL-AND-EXPIRY 2026-06-03 |
| F22 | `payment.expiration.ready-timeout-minutes` 기본값 30(분) — default profile yml 명시값 | `application.yml:131` | TIME-MODEL-AND-EXPIRY 2026-06-03 |
| F23 | payment `Dockerfile` 에 `ENV TZ=UTC` — TZ backstop 3겹(Dockerfile/JVM/compose) 중 하나 | `payment-service/Dockerfile:2` | TIME-MODEL-FOLLOWUP 2026-06-07 |
| F24 | CI 는 서비스별 재사용 워크플로우(`_service-ci.yml`, `workflow_call`) 로 6서비스 fan-out — 단일 2-job 구조 아님 | `.github/workflows/_service-ci.yml` (파일 존재) | CI-PIPELINE-REDESIGN 2026-06-08 |
| F25 | JaCoCo 라인 커버리지 게이트 서비스별 상이(payment 0.86 / pg 0.93 / product 0.97 / user 0.97 / gateway·eureka 0.0), 단위 `test` exec 기준(통합 미합산) | `docs/context/TESTING.md:137` 서술 자체는 최신(직접 code 확인은 Task 3/9 범위 — 루트 `build.gradle` `jacoco.lineCoverageMinimum` 확인 필요) | CLEANUP-BATCH-B 2026-05-31 → CI-PIPELINE-REDESIGN 2026-06-08 재정의 |
| F26 | `docs/context/TESTING.md` "현재 테스트 카운트" 표는 2026-06-14 스냅샷(873단위/48통합) — 이후 CI-PIPELINE-REDESIGN·OBSERVABILITY-COMPLETION·CLEANUP-BATCH-C/D·K6-ASYNC·CAPACITY·STOCK-COMPENSATION·CLEANUP-BATCH-E·RETRY-METRIC-CLEANUP·CONFIRM-APPROVED-RESEND-GAP·DLQ-REACHABILITY·ALERTING-RULES·FAULT-INJECTION·L-14·TC-3 등 다수 토픽에서 테스트 추가/삭제 발생 — 스냅샷이 현재 값을 대표하지 못함(근사치: payment 단위 342 `@Test` 애노테이션 grep, pg 240, product 50, user 9 — 정확한 실행 카운트는 Task 9 수정 시 `./gradlew test` 재실행으로 확정) | `docs/context/TESTING.md:166-178`(스냅샷 표) vs `grep -rc "@Test" {payment,pg,product,user}-service/src/test` = 342/240/50/9(애노테이션 수, parameterized 확장 전) | 지속 누적 |
| F27 | README 배너의 "589 PASS" 는 스냅샷보다도 더 이전 값 — F26 과 같은 근본 원인(수동 갱신 문서) | `README.md:8` | 지속 누적 |
| F28 | 위키 저장소 마지막 실질 커밋은 2026-06-12("수정") — 이후 6/13(cleanup-batch-c)부터 7/1(TC-3)까지 13개 이상 토픽 미반영 | `payment-platform.wiki/` git log: `554e120 2026-06-12`, `51db9c4 2026-06-01`, 그 이전 `ff2735c 2026-05-17`(PAYMENT-EOS-TRANSITION 봉인) | 위키 갱신 프로세스 부재 |

---

## 2. 표본 12건 재검증 판정

topic 문서 "사전 진단 표본" 12건을 위 형식으로 재검증한 결과. **#1·#12는 소스 검증 완료 상태로 인계**받아 근거를 보강, 나머지 10건은 이번 태스크에서 재검증했다.

### #1 — `CONCERNS.md` L-1 qualifier 서술 모순

| 컬럼 | 내용 |
|---|---|
| 문서 위치 | `docs/context/CONCERNS.md` L-1 절, 특히 L92 |
| 문제 | L92 "`@Transactional(timeout=5)` 는 qualifier 미명시로 `@Primary JpaTransactionManager` 를 선택한다" — 현재 코드와 반대. 같은 절 L97 은 "qualifier 명시는 EOS-FOLLOWUP-CLEANUP 에서 완료"라고 써서 **같은 항목 안에서 자기모순** |
| 소스 근거 | `PaymentConfirmResultUseCase.java:116` — `@Transactional(transactionManager = "transactionManager", timeout = 5)` qualifier 명시 확정. `docs/context/CONFIRM-FLOW.md:162` 는 이미 정정된 서술("qualifier 로 명시 고정") — CONCERNS.md 만 뒤처짐 |
| 수정 방향 | CONCERNS.md L-1 L92 문장을 "qualifier 명시 완료(EOS-FOLLOWUP-CLEANUP)" 기준으로 정정. L97 과 중복되는 서술은 하나로 정리 |
| 심각도 | **S1 + S2** (코드 불일치 + 문서 내 모순) |

### #2 — `CONFIRM-FLOW.md` §12 TTL 정리 스케줄러 "후속 항목" 서술

| 컬럼 | 내용 |
|---|---|
| 문서 위치 | `docs/context/CONFIRM-FLOW.md:437` |
| 문제 | "TTL 정리 스케줄러는 TC-13-FOLLOW-2 후속 항목" — 이미 구현 완료된 사실을 미착수로 서술 |
| 소스 근거 | `DedupeCleanupWorker.java` 파일 존재 (F5) |
| 수정 방향 | "후속 항목" → 완료 서술로 교체(스케줄 주기·배치 크기 등 실제 동작 반영) |
| 심각도 | **S1** (완료 반영 누락) |

### #3 — `PITFALLS.md` 헤더-본문 시점 불일치

| 컬럼 | 내용 |
|---|---|
| 문서 위치 | `docs/context/PITFALLS.md:3` |
| 문제 | 헤더 "최종 갱신: 2026-05-17" 이지만 본문 `## 24` 는 그보다 훨씬 뒤 산출물 |
| 소스 근거 | F13(alerting rule 4그룹, 2026-06-27/06-30 도입)이 §24 서술(`absent(kafka_brokers)` 분기)의 소스 근거 |
| 수정 방향 | 헤더 최종 갱신 날짜를 §24 도입 시점으로 갱신 |
| 심각도 | **S3** (헤더-본문 불일치, 경미) |

### #4 — `TODOS.md` 완료 항목 잔존 비대

| 컬럼 | 내용 |
|---|---|
| 문서 위치 | `docs/context/TODOS.md` 전체 (377줄) |
| 문제 | ✅ 완료 마킹 항목이 다수(수십 건) — "토픽 종결 시 항목 삭제" 자체 규칙(`TODOS.md:371`)과 모순, discuss 진입마다 탐색 비용 |
| 소스 근거 | 문서 구조 문제라 소스 코드 근거 대상 아님 — 삭제 판정 근거는 각 항목이 인용한 archive 경로(`docs/archive/<topic>/COMPLETION-BRIEFING.md`)의 실재 여부로 대체 확인(F1~F26 각 사실이 이미 소스로 검증된 완료 항목들과 대응) |
| 수정 방향 | Task 8 에서 3분류 적용 — (a) 완전 삭제 (b) 혼합 항목 문장 단위 제거 (c) 보존. 예: L-14 텍스트(TODOS.md 에는 없고 CONCERNS.md 에 있음, 혼합 항목 사례) |
| 심각도 | **S3** (완료 잔존 비대) |

### #5 — `README.md` 배너 노후

| 컬럼 | 내용 |
|---|---|
| 문서 위치 | `README.md:7-12` |
| 문제 | "진행 중 Phase 6 · 589 PASS · 정합이 안 맞을 수 있음" 배너가 현재 상태와 괴리 — 테스트 수는 F26/F27 근거로 훨씬 큼, "재고 복원 가드"(`README.md:24`)는 CLEANUP-BATCH-E 에서 제거된 단건 API 기반 개념(F8) |
| 소스 근거 | F8(StockCachePort 단건 API 삭제), F26/F27(테스트 카운트 스냅샷 노후) |
| 수정 방향 | Task 11 에서 배너 재작성 — 정확한 테스트 카운트는 그 시점 `./gradlew test` 재실행 값 사용, "재고 복원 가드" 문구 삭제/교체 |
| 심각도 | **S1(재고 복원 가드 서술)** + **S3(배너 지표 노후)** — README 도메인 사실 항목이라 ship domain-expert 대조 입력 대상 |

### #6 — README ↔ 내부 문서 페이즈 번호 이원화

| 컬럼 | 내용 |
|---|---|
| 문서 위치 | `README.md:34-43`(개발 과정 Phase 1~6+ETC) vs `docs/context/PAYMENT-FLOW.md:23-138`(흐름 단계 Phase 1~5) vs `docs/context/PAYMENT-FLOW-GUIDE.md:70-141`(흐름 단계 Phase 1~6) |
| 문제 | 같은 "Phase" 단어가 두 축을 가리킨다 — README 는 "개발 진행 순서"(위키 페이지별 이정표), PAYMENT-FLOW*.md 는 "결제 요청 하나가 통과하는 처리 단계"(order 생성→confirm→outbox→pg→결과 수신→폴링). PAYMENT-FLOW.md:6 은 심지어 세 번째 축("MSA 전환 Phase 0~3.5")까지 남아 있어 최소 3축이 "Phase" 로 혼용 |
| 소스 근거 | 문서 자체가 근거(용어 사용 실태 채록) — 코드에는 "Phase" 개념이 없음(순수 문서 조직 개념) |
| 수정 방향 | Task 4 에서 전수 채록 후 Task 11 에서 확정안 결정 — 최소한 서로 다른 축임을 각 문서 도입부에 1줄 명시(topic 결정: "내부 Phase 번호가 README 개발 과정 Phase 와 별개임을 TODOS 분류 룰에 1줄 명시") |
| 심각도 | **S2** (용어 충돌로 인한 혼동, 사실관계 오류는 아님) |

### #7 — `TESTING.md` 테스트 카운트 스냅샷 노후

| 컬럼 | 내용 |
|---|---|
| 문서 위치 | `docs/context/TESTING.md:166-178` |
| 문제 | "2026-06-14 기준" 명시 스냅샷(873/48)이나 갱신 시점 규칙이 없어 스냅샷이 영구히 낡아감 |
| 소스 근거 | F26 — `@Test` 애노테이션 grep 342(payment)/240(pg)/50(product)/9(user), 2026-06-14 이후 최소 13개 토픽에서 테스트 추가/삭제 확인 |
| 수정 방향 | Task 9 수정 시점에 `./gradlew test`(+`integrationTest`) 재실행으로 정확한 카운트 갱신, 표 상단에 "스냅샷일 뿐 회귀 가드는 pass/fail" 문구는 유지(이미 있음, TESTING.md:178) |
| 심각도 | **S3** (노후, 경미 — 문서 자체가 스냅샷임을 인지하고 있음) |

### #8 — 위키 전체 갱신 격차 미검증

| 컬럼 | 내용 |
|---|---|
| 문서 위치 | `payment-platform.wiki/*.md` 전체 25페이지 |
| 문제 | 마지막 실질 갱신이 2026-06-12, 이후 최소 13개 토픽(F5~F17 포함) 미반영 |
| 소스 근거 | F28 — wiki git log(`554e120 2026-06-12`), F5~F17 각 사실의 소스 근거 |
| 수정 방향 | Task 5/6 에서 25페이지 전수 진단 → Task 13~17 에서 반영 |
| 심각도 | **S1 다수 예상** (Task 5/6 에서 페이지별 확정) |

### #9 — 위키 `structured-logging.md` Elasticsearch/Logstash 서술

| 컬럼 | 내용 |
|---|---|
| 문서 위치 | `structured-logging.md:16,32,48-49,282-313` |
| 문제 | Logstash 경유 Elasticsearch 인덱싱을 현재 스택처럼 서술 — 실제는 Promtail/Loki |
| 소스 근거 | `docs/context/STACK.md:67,70,80`(F19) — Loki 3100 포트, Promtail 로그 수집, 로그 기반 추적 진입 서술. 코드 레벨로는 `docker-compose.infra*.yml` 의 loki/promtail 서비스 정의가 1차 소스지만 이번 태스크에서는 STACK.md 의 코드 대조 결과(이미 코드 확인된 상태)를 인용 — Task 6 재검증 시 compose 파일 직접 확인 권고 |
| 수정 방향 | Task 6 진단 확정 → Task 16 에서 본문을 Promtail/Loki 파이프라인으로 재작성 |
| 심각도 | **S1** |

### #10 — 위키 `state-management.md` 폐기된 RETRYING 상태 서술

| 컬럼 | 내용 |
|---|---|
| 문서 위치 | `state-management.md:24,43,67,70,72-75,86,96-99,173,188,296,362` |
| 문제 | RETRYING 상태를 현재 상태 머신의 일부처럼 전면 서술(다이어그램·전이표 포함) — 실제로는 완전 제거됨 |
| 소스 근거 | `PaymentEventStatus.java:3-12`(F6) — enum 8개 값, RETRYING 없음 |
| 수정 방향 | Task 5 에서 본문 재작성 범위 확정 → Task 15 에서 상태 다이어그램·전이표 전면 갱신 |
| 심각도 | **S1** (다수 서술 지점) |

### #11 — 위키 `outbox-pattern.md` 빈 "표기 규칙" 섹션

| 컬럼 | 내용 |
|---|---|
| 문서 위치 | `outbox-pattern.md:11-13` |
| 문제 | `## 표기 규칙` 헤더 바로 다음 줄이 `## 왜 outbox 인가` — 헤더 아래 내용 없음 |
| 소스 근거 | 구조 결함이라 코드 근거 대상 아님 — 위키 파일 자체가 근거 |
| 수정 방향 | Task 13 에서 빈 헤더 제거 (본문 현행화 작업과 동시 처리) |
| 심각도 | **S4** (구조 결함, 경미) |

### #12 — CONFIRM-FLOW/PAYMENT-FLOW의 outbox 발행 실패 복구 서술 (S1 최우선)

| 컬럼 | 내용 |
|---|---|
| 문서 위치 | `docs/context/CONFIRM-FLOW.md:74,80,90,116,401,415,450` + `docs/context/PAYMENT-FLOW.md:62,68` |
| 문제 | "REQUIRES_NEW 로 선점 → 발행 실패해도 IN_FLIGHT 유지 → 일정 시간 후 타임아웃 회수(백오프 적용)"로 서술 — 실제로는 선점·발행·완료가 **단일 TX** 라 발행 실패 시 TX 롤백으로 **PENDING 복귀**, `OutboxWorker` 5초 주기 재픽업이 전부. `PaymentOutboxUseCase.claimToInFlight`(REQUIRES_NEW)·`incrementRetryOrFail` 은 프로덕션 호출처 0(dead) |
| 소스 근거 | F2(`OutboxRelayService.java:49-59` 단일 TX) + F3(REQUIRES_NEW/increment 메서드 호출처 0) + F4(`application.yml:147` fixed-delay-ms:5000) |
| 수정 방향 | CONFIRM-FLOW.md §3·§4·§9·§11, PAYMENT-FLOW.md Phase 3·장애 복원 포인트를 F2~F4 기준으로 전면 재작성. IN_FLIGHT 타임아웃 회수 서술은 dead-code 각주로 강등하거나 삭제(코드 확인 필요 항목으로 TODOS 등재는 Task 8) |
| 심각도 | **S1 (critical)** — 1라운드 게이트에서 이 stale 서술이 위키의 참인 문장을 뒤집을 뻔한 실증 사례. Task 7 최우선 처리 |

---

## 3. 게이트 2R 잔여 minor 해소

### 3.1 "기본값" 층위 명시 규칙

→ 0.3 절에 항목 형식 규칙으로 편입 완료. `parallel-enabled` 실증 사례 포함.

### 3.2 기준 예문 마지막 불릿(retry 카운트) 재검증

topic 문서 "기준 예문" 마지막 불릿:

> 결제 명령은 무조건 발행돼야 하므로 자동 FAILED 종결은 사실상 도달하지 않는다 — retry 카운트는 백오프 강도 조절과 운영 알람용으로 고려 중

**재검증 결과**: F3 확인 결과 `incrementRetryOrFail`(재시도 횟수 증가 + 한도 소진 시 FAILED 종결)은 프로덕션 호출처가 0이다. `payment_outbox.retry_count` 를 증가시키는 유일한 실사용 경로는 `recoverTimedOutInFlightRecords`(`OutboxWorker.java:38`) — 이는 IN_FLIGHT 타임아웃(선점 후 워커가 죽은 경우) 회수 전용이며, `OutboxRelayService.relay` 의 **발행 실패**(TX 롤백 → PENDING)는 retryCount 를 전혀 건드리지 않는다.

**결론**: "고려 중"(향후 계획 뉘앙스)이 아니라 **"현재 relay 실패 경로에서는 미적용"**이 정확한 현재형 서술이다. 게이트 1R 에서 이미 확정된 "후" 버전 문구(topic 문서 표 참고)는 "retry 카운트는 백오프 조절과 운영 알람용"이라고만 써서 이 미적용 사실을 담지 않는다 — Task 13(위키 outbox-pattern.md 반영) 시 다음과 같이 보강:

> retry 카운트는 원래 outbox 폴백 워커(`recoverTimedOutInFlightRecords`)의 IN_FLIGHT 타임아웃 회수 전용이고, `relay` 자체의 발행 실패는 카운트 증가 없이 5초 주기로 무한 재시도한다 — 결제 명령 발행은 포기 불가라 이 무백오프 반복이 의도된 동작이다.

이 결론은 **코드 확인 필요 항목**(topic doc "코드 확인 필요 항목" 절)과 동일 근거를 공유한다 — 회귀/의도 판정은 이 토픽 범위 밖(코드 미수정)이므로 TODOS 신규 등재는 Task 8 에서 수행.

---

## 4. Task 2~6 진단 확정 대상 (플레이스홀더)

아래 절은 Task 2~6 이 각자 담당 범위를 진단하며 표를 채운다. 형식은 0.1 절을 따른다.

### 4.1 Task 2 — 플로우·대장·함정 5파일 (`CONFIRM-FLOW.md` / `PAYMENT-FLOW.md` / `TODOS.md` / `CONCERNS.md` / `PITFALLS.md`)

전건 통독 + 사실 목록(§1) 대조 결과. #2/#3/#4/#12 는 §2 표본 판정을 인계받아 정확한 현재 줄번호로 확장했다. 소스 근거는 `grep`/`Read` 로 직접 재확인(파일:라인).

#### 4.1.1 `docs/context/CONFIRM-FLOW.md`

| 문서 위치 | 문제 | 소스 근거 | 수정 방향 | 심각도 |
|---|---|---|---|---|
| L74(mermaid), L80(mermaid), L90(prose) | §3 "claimToInFlight 가 REQUIRES_NEW 로 원자 선점 → 발행 실패 시 TX rollback 이지만 outbox row 는 IN_FLIGHT 상태로 남는다" — 실제로는 `OutboxRelayService.relay` 가 claim(Step1)·발행(Step3)·toDone(Step4) 를 **단일 `@Transactional`** 안에서 수행. `PaymentOutboxRepository.claimToInFlight` 는 `@Modifying` UPDATE 로 같은 TX 소속, REQUIRES_NEW 아님. 발행 실패 시 전체 TX 롤백 → row 는 (커밋된 적 없는) PENDING 그대로 복귀 | `OutboxRelayService.java:49-78`(단일 `@Transactional`, Step1~4 순차), `PaymentOutboxRepositoryImpl.java:56-61`(`claimToInFlight` propagation 지정 없음 = REQUIRED) | 문장/다이어그램 전면 재작성: "claim+발행+완료가 한 TX, 실패 시 TX 롤백 → PENDING 즉시 복귀 → OutboxWorker 5초 주기 재픽업"으로 | **S1 critical** (표본 #12 확장) |
| L401 (§10 재시도 정책표 "코드 진입점" 행) | `PaymentOutboxUseCase.incrementRetryOrFail` 을 payment 측 retry 진입점으로 표기 — 이 메서드는 프로덕션 호출처 0(dead) | `PaymentOutboxUseCase.java:46-55` 정의, 호출처 전체 grep 0 (F3) | 진입점을 실제 재시도 경로(`OutboxWorker` 5초 주기 재픽업, `PaymentOutboxUseCase.recoverTimedOutInFlightRecords`)로 정정 | **S1** (표본 #12/§3.2 확장) |
| L415 (§11 회복 시나리오 인덱스) | "Kafka producer 실패 (payment → broker) \| IN_FLIGHT 유지 → `OutboxWorker` 타임아웃 후 PENDING 복귀 → relay 재시도" — 위와 동일 오류 재등장 | 상동 (`OutboxRelayService.java:49-78`) | "TX 롤백 → PENDING 즉시 복귀 → 5초 주기 재픽업" 으로 정정 | **S1 critical** (표본 #12 확장) |
| L450 (§13 멱등성 layer 표 "outbox claim" 행) | "`claimToInFlight` REQUIRES_NEW atomic UPDATE" — REQUIRES_NEW 아님 (위와 동일 오류) | 상동 | "REQUIRES_NEW" 삭제, 단일 TX 내 atomic UPDATE 로 정정 | **S1 critical** (표본 #12 확장) |
| L~372-380 (§9 PaymentOutboxStatus 상태표) + L399 (§10 "한도 초과 시 \| outbox FAILED") | `PaymentOutboxStatus.FAILED` 로 전이하는 코드 경로가 현재 0건 — `PaymentOutbox.toFailed()` 도메인 메서드 자체가 CLEANUP-BATCH-E 에서 삭제됐고, `PaymentOutboxStatus.FAILED` 를 세팅하는 지점이 main 코드에 없음(선언·`isTerminal()` 판별 외 참조 0) | `PaymentOutboxUseCase.java` 전체에 `toFailed` 없음(grep 0), `grep -rn "PaymentOutboxStatus.FAILED\|\.toFailed(" payment-service/src/main` = 0건. `incrementRetryOrFail`(exhaustion 판정 유일 지점)도 호출처 0(F3) | FAILED 를 "현재 도달 불가(dead terminal state, TC-7 재검토 대상)"로 각주. state diagram 의 `FAILED --> [*]` 를 dead-branch 표기로 조정 | **S1** (F3 확장, 신규 발견) |
| L113 + 헤더 L3 | "`scheduler.outbox-worker.parallel-enabled`: **false (기본)**" — 코드 fallback(false) 만 인용하고 실제 적용되는 default profile yml 값(true)을 누락. 헤더는 "2026-06-23 parallel-enabled 기본값 false 정정"이라며 이 부정확한 값을 "정정 완료"로 표기 | `OutboxWorker.java:26`(`@Value("...:false}")`) vs `application.yml:149`(`parallel-enabled: true`), `application-benchmark.yml:25`(`${SCHEDULER_PARALLEL_ENABLED:true}`) | §0.3 층위 규칙대로 "코드 fallback: false / default 프로파일(로컬·docker 실구동 값): true" 두 값 병기 | **S1+S2** (§0.3 층위 규칙 위반 실사례, 신규 발견) |
| L437 (§12 dedup TTL 표) | "TTL 정리 스케줄러는 TC-13-FOLLOW-2 후속 항목" — 이미 구현 완료(표본 #2 그대로 잔존, 현재 정확한 줄번호로 재확인) | `DedupeCleanupWorker.java` 파일 존재 (F5) | "후속 항목" → 완료 서술(스케줄 주기·`deleteExpired` 배치 삭제)로 교체 | **S1** (표본 #2 정확 위치 확정) |
| 헤더 L3 | "최종 갱신: 2026-06-23" — 그러나 본문(§5 DLQ-REACHABILITY 절, §16 EOS 시나리오 #6·#7)은 2026-06-25(DLQ-REACHABILITY) 산출물을 이미 반영 — 헤더가 본문보다 뒤처짐 | F12(`KafkaConsumerConfig.java:92`, DLQ-REACHABILITY 2026-06-25) | 헤더 날짜를 본 태스크(Task 7) 정정 완료 시점으로 갱신 | **S3** (표본 #3 과 동일 패턴) |
| §18 관련 문서 목록 | "pg-service listener 분리 안 설계 기록: `docs/archive/pg-confirm-listener-split/` (**verify 완료 후 이동 예정**)" — 이미 이동 완료(COMPLETION-BRIEFING.md 존재) | `docs/archive/pg-confirm-listener-split/COMPLETION-BRIEFING.md` 파일 존재 확인 | "(이동 예정)" 괄호 삭제 | **S3** (완료 잔존, 경미) |

#### 4.1.2 `docs/context/PAYMENT-FLOW.md`

| 문서 위치 | 문제 | 소스 근거 | 수정 방향 | 심각도 |
|---|---|---|---|---|
| L68 (Phase 3 다이어그램) | "R5a: IN_FLIGHT 유지 → OutboxWorker가 타임아웃 복구 재발행" — CONFIRM-FLOW L80/L90 과 동일한 stale 서술 (REQUIRES_NEW 분리 커밋 전제) | `OutboxRelayService.java:49-78` (F2) | "TX 롤백 → PENDING 즉시 복귀 → 5초 주기 재픽업" 으로 정정 | **S1 critical** (표본 #12, 정확 줄번호 확정) |
| L200 (장애 복원 포인트) | "리스너 스킵/크래시: payment 쪽은 `OutboxWorker` (fixedDelay 5초, batchSize 50, **IN_FLIGHT 5분 타임아웃 복귀**)" — 발행 실패 회복의 대표 서술로 IN_FLIGHT 타임아웃 경로를 앞세움. 실제 발행 실패의 1차 회복 경로는 TX 롤백 → PENDING 즉시 재픽업(5초 주기)이고, IN_FLIGHT 5분 타임아웃은 워커 크래시 등 별도(더 드문) 시나리오 | 상동 (F2/F3) | "PENDING 배치 재픽업(5초 주기)이 1차 경로, IN_FLIGHT 5분 타임아웃 복귀는 보조 경로"로 우선순위 재정렬 | **S1** (동일 클러스터 확장) |
| L6 | "현재 `main` (MSA 4서비스 분리 + Phase 0~3.5 + PRE-PHASE-4-HARDENING 봉인 시점) 코드를 기준으로" — 봉인 시점 앵커가 2026-04-24 로 매우 오래됨. 이후 EOS 전환·DLQ-REACHABILITY 등 다수 토픽 반영되었으나 도입부 프레이밍은 갱신 안 됨 | 문서 자체 근거(용어 사용 실태) — Phase 축 혼용은 표본 #6 소스 근거 재사용 | 도입부 앵커를 최신 토픽(DLQ-REACHABILITY) 기준으로 교체하거나 앵커 문구 자체를 제거 | **S3** (표본 #6 확장) |

#### 4.1.3 `docs/context/TODOS.md` — 구조 + 3분류 판정

**구조적 문제 (개별 항목과 별개)**

| 문서 위치 | 문제 | 소스 근거 | 수정 방향 | 심각도 |
|---|---|---|---|---|
| L9-27 "토픽 묶음 계획 (PR 단위)" 섹션 전체 | PR A/B/C 3묶음 모두 ✅ 완료 — 순수 과거 계획 정보이며 `docs/archive/README.md` 작업 뭉치 목록에 각 토픽(cleanup-batch-a, time-model-and-expiry, payment-eos-transition)이 이미 더 상세히 기록됨 | `docs/archive/README.md:35-36,39,46`(해당 토픽 행 존재) | 섹션 전체 삭제 (a) | **S4** (완료 잔존 + SSOT 중복) |
| L344-363 "## 완료" 섹션 전체(~20개 토픽 요약) | `docs/archive/README.md` "작업 뭉치 목록" 표가 동일 토픽을 더 정확하고 상세하게 이미 기록 — TODOS.md 는 "Planned Cleanup / Future Work"(미래 지향) 문서라는 파일 자체 성격과도 어긋남 | `docs/archive/README.md:16-53` (해당 전 토픽 행 존재, 날짜·상세 내용 일치 확인) | 섹션 전체 삭제 (a) — 필요 시 "완료 이력은 `docs/archive/README.md` 참조" 1줄로 대체 | **S4** (대장 비대, SSOT 미지정) |

**항목별 3분류 예비 판정**

| 항목 | 위치 | 분류 | 근거 |
|---|---|---|---|
| TC-13 | L35-37 | (a) 전체 삭제 | ✅ 완료 + archive 경로 존재(`docs/archive/payment-eos-transition/`) |
| TC-13-FOLLOW-1 | L49-51 | (a) 전체 삭제 | ✅ 해소 + archive 경로(`docs/archive/capacity-and-scaleout/`) |
| TC-13-FOLLOW-2 | L53-55 | (a) 전체 삭제 | ✅ 완료, "## 완료" EOS-FOLLOWUP-CLEANUP 항목(L352-356)과 중복 (그 섹션도 자체가 (a) 대상) |
| TC-13-FOLLOW-3 | L65-69 | (a) 전체 삭제 | ✅ 완료(대시보드+알람 모두). 잔여 "DE2"(lag 임계 재교정)는 이미 T4-B 정밀화 묶음(L181)에 동일 내용 존재 — 정보 손실 없음 |
| TC-13-FOLLOW-4 | L71-75 | (a) 전체 삭제 | ✅ 완료. 잔여 "DE1"(status 라벨 미분리)은 이미 T4-B 정밀화 묶음(L180)에 동일 내용 존재 |
| TC-13-FOLLOW-6 | L77-82 | (b) 혼합 | "완료 부분"(qualifier 명시, EOS-FOLLOWUP-CLEANUP) 문장 제거. "미채택 (잔여)" ChainedKafkaTransactionManager 재검토 조건은 보존 — 유일하게 이 문서에만 있는 미채택 결정 기록 |
| TC-13-FOLLOW-5 | L84-86 | (a) 전체 삭제, **S1** | canCompensateStock·RETRYING·`PaymentEventStatusCrossInvariantTest` 를 현재형으로 서술 — 셋 다 이후 토픽(STOCK-COMPENSATION-OTHER-PATHS/CLEANUP-BATCH-E)에서 완전 제거됨(F6/F7). "완료 잔존" 을 넘어 **존재하지 않는 코드를 현재처럼 서술**하는 사실 오류. Task 지시의 "canCompensateStock 잔존 언급" 대상 |
| [PG-SELFLOOP-ATTEMPT-GAP] | L61-63 | (a) 전체 삭제 | ✅ 완료 + archive 경로(`docs/archive/dlq-reachability/`). "수용 한계"(over-count) 는 CONCERNS.md L-13 에 이미 동일 내용 존재 |
| TC-4 | L92-94 | (a) 전체 삭제 | ✅ 완료, "## 완료" TIME-MODEL-AND-EXPIRY 항목(L346-351)과 중복 |
| TC-8 | L96-98 | (a) 전체 삭제 | 상동 |
| [NET-RETRY] | L102-104 | (a) 전체 삭제 | ✅ 완료 + archive(`docs/archive/cleanup-batch-b/`) |
| [FLYWAY-USER-SEED-GAP] | L106-108 | (a) 전체 삭제 | ✅ 완료 + archive(`docs/archive/ci-pipeline-redesign/`) |
| [PRODUCT-TIME-ABSTRACTION] | L112-114 | (a) 전체 삭제 | ✅ 완료, TIME-MODEL-AND-EXPIRY 중복 |
| [TIME-PRODUCT-NOW-UNIFY] | L116-118 | (a) 전체 삭제 | ✅ 완료 + archive(`docs/archive/time-model-followup/`) |
| [TZ-UTC-BACKSTOP] | L120-122 | (a) 전체 삭제 | 상동 |
| [BASEENTITY-AUDIT-SOURCE] | L124-126 | (a) 전체 삭제 | 상동 |
| [SCHEDULER-ENABLED-GATE] | L128-130 | (a) 전체 삭제 | ✅ 완료 + archive(`docs/archive/cleanup-batch-d/`) |
| [CLEANUP-FAILURE-COUNTER] | L132-134 | (a) 전체 삭제 | ✅ 완료 + archive(`docs/archive/observability-completion/`) |
| [GUARD-SKIP-EAGER-REGISTER] | L136-138 | (a) 전체 삭제 | 상동 |
| [SPOTBUGS-TEST-DEBT] | L140-142 | (a) 전체 삭제 | ✅ 완료 + archive(`docs/archive/cleanup-batch-b/`) |
| [CLEANUP-BATCH-B 후속] | L144-149 | (b) 혼합 | 3개 해소 불릿(L146,147,149) 제거, 미해소 불릿(L148 "infra 커버리지 집계 제외") 보존 — 현재도 유효한 정책 결정 |
| TQ-7 | L243-245 | (a) 전체 삭제 | ✅ 완료 + archive(`docs/archive/stock-compensation-other-paths/`) |
| TQ-8 | L247-250 | (a) 전체 삭제 | ✅ 완료 + archive(`docs/archive/cleanup-batch-e/`, `docs/archive/retry-metric-cleanup/`) |
| TC-1 | L254-256 | (a) 전체 삭제 | ✅ 완료 + archive(`docs/archive/observability-completion/`) |
| TC-3 | L258-263 | (b) 혼합 | "부분 완료" — 채택·구현 완료 프로즈는 간결화, "한계/잔여"(전체 일괄 resync·자동 발산 감지 미구현) 불릿은 **보존**(F17 실제 잔여 한계) |
| TC-6 | L265-270 | (c) 보존 | 미착수 open item, Phase 5 T4-D 연계 |
| TC-7 | L272-284 | (c) 보존, 단 **내용 정정 필요(S1)** | "현황" 절 "한도 초과 시 종결" 서술이 `incrementRetryOrFail` 미호출(F3) 및 `PaymentOutboxStatus.FAILED` 도달 불가(위 CONFIRM-FLOW L399 항목) 를 반영 못 함 — 항목 자체는 보존하되 "현황" 문장 정정 필요 |
| TC-11 | L292-304 | (c) 보존 | 이미 현황/보류 구분이 정확한 모범 사례. 변경 불요 |
| TC-12 | L306-318 | (c) 보존 | 보류 결정 기록, 재검토 조건 명시 — 변경 불요 |
| TC-15 | L320-340 | (c) 보존 | 진행 중(항목1·2 open, 항목3 만 완료 — 이미 정확히 구분됨) |
| TQ-1~TQ-6 | L210-241 | (c) 보존 | 전건 open, Phase 4 후속 |
| T4-A~T4-E | L159-206 | (c) 보존 | 전건 Phase 5 대기, 측정/인프라 의존 |

#### 4.1.4 `docs/context/CONCERNS.md`

**신규 발견 (표 형식)**

| 문서 위치 | 문제 | 소스 근거 | 수정 방향 | 심각도 |
|---|---|---|---|---|
| L92 (L-1 EOS atomicity SSOT) | "`@Transactional(timeout=5)` 는 qualifier 미명시로 `@Primary JpaTransactionManager` 를 선택한다" — 코드와 반대 | `PaymentConfirmResultUseCase.java:116` qualifier 명시 확정 (F1) | "qualifier 명시 완료(EOS-FOLLOWUP-CLEANUP)"로 정정 | **S1** (표본 #1, 정확 위치 재확인) |
| L97 (L-1 "후속 과제") | "TC-13-FOLLOW-1 — `ChainedKafkaTransactionManager` 도입 검토" — TC-13-FOLLOW-1 은 TODOS.md 상 hostname/multi-instance 항목(✅ 해소)이지 ChainedKTM 항목이 아니다. ChainedKTM 은 TODOS TC-13-FOLLOW-6 이 정확한 ID. 같은 문장의 "TC-13-FOLLOW-3·4 후속" 도 두 항목 모두 이미 ✅ 완료라 "후속" 표현이 stale | `docs/context/TODOS.md:49-51`(TC-13-FOLLOW-1 실제 내용) vs `:77-82`(TC-13-FOLLOW-6 실제 내용), `:65-75`(FOLLOW-3·4 완료 마킹) | ID 오기 정정(FOLLOW-1→FOLLOW-6) + "완료(잔여 DE1/DE2 는 T4-B 정밀화)"로 갱신 | **S2** (문서 간 ID 불일치, 신규 발견) |
| L107 (L-3 전체) | "다중 인스턴스 동시 운영 검증 부재" — CAPACITY-AND-SCALEOUT 이 2-인스턴스 fencing 을 이미 실측 완료(정상/rebalance 중복 0, 분산 편차 0.7%) | `docs/context/TODOS.md:51`("2 인스턴스 fencing 실측..."), `docker/docker-compose.apps.yml:30`(hostname 고정 제거 주석 확인) | 항목 전체 삭제 대상(아래 3분류 표) | **S1** (신규 발견, 이하 3분류 표에서 처리) |
| L120-125 (L-6 전체) | "EOS multi-instance 확장 시 docker-compose hostname 충돌" — CAPACITY-AND-SCALEOUT 처방(hostname 라인 제거)이 이미 적용됨 | `docker/docker-compose.apps.yml:30`(payment-service 블록에 `hostname:` 라인 부재, pg/product/user/gateway 는 존재 — 대조 확인) | 항목 전체 삭제 대상 | **S1** (신규 발견) |
| L67-68 (C-9 "후속 해소" 불릿) | 대시보드(완료)와 alerting rule 인프라(완료) 서술 뒤에 "**잔여**: Alertmanager 통지 채널 미도입" — 완료분과 진짜 잔여 한계가 한 불릿에 혼재 | `observability/prometheus/prometheus.yml`(Alertmanager 설정 섹션 부재 — rule_files 평가만) | 완료 서술은 간결화, "Alertmanager 미도입" 잔여는 독립 불릿으로 분리 보존 | **S3** (혼합 서술, 경미) |

**3분류 예비 판정**

| 항목 | 위치 | 분류 | 근거 |
|---|---|---|---|
| C-7 | L47-50 | (a) 전체 삭제 | ✅ 해소(PAYMENT-EOS-TRANSITION), 이미 스트라이크스루 |
| C-12 | L52-55 | (a) 전체 삭제 | ✅ 해소(CAPACITY-AND-SCALEOUT), 이미 스트라이크스루 |
| C-11 | L76-80 | (a) 전체 삭제 | ✅ 해소(CLEANUP-BATCH-D), archive 경로 존재 |
| C-9 | L65-68 | (b) 혼합 | 위 신규 발견 항목 참고 — 완료분 축약, Alertmanager 잔여 보존 |
| C-1, C-2, C-3, C-4, C-5, C-6, C-8, C-10 | High/Medium/Low 각 절 | (c) 보존 | 전건 open, 스트라이크스루 없음 |
| L-1 | L84-97 | (c) 보존, **내용 정정 필요** | Kafka tx coordinator 의존은 여전히 유효한 수용된 한계. 단 L92(qualifier)·L97(ID 오기) 두 곳 정정 필요 (위 표) |
| L-2 | L99-101 | (a) 전체 삭제 | ✅ 해소(EOS-FOLLOWUP-CLEANUP), 이미 스트라이크스루 |
| L-3 | L103-107 | (a) 전체 삭제, **S1** | 위 신규 발견 — CAPACITY-AND-SCALEOUT 이 검증 완료 |
| L-4, L-5, L-7, L-8, L-9, L-11, L-12 | 각 절 | (c) 보존 | 전건 현재도 유효한 수용된 한계, 스트라이크스루 없음 |
| L-6 | L120-125 | (a) 전체 삭제, **S1** | 위 신규 발견 — hostname 라인 이미 제거되어 처방 완료 |
| L-10 | L139-141 | (a) 전체 삭제 | ✅ 해소(TIME-MODEL-AND-EXPIRY), archive 경로 존재 |
| L-13 | L151-153 | (a) 전체 삭제 | ✅ 해소(DLQ-REACHABILITY), archive 경로 존재, [PG-SELFLOOP-ATTEMPT-GAP](TODOS) 과 중복 |
| L-14 | L155-161 | (c) 보존(모범 사례) | "부분 해소" 구조로 완료분(poison-pill)과 잔여 한계(READY 잔류)를 이미 정확히 분리 서술 — 문장 단위 편집 불요, 3분류 규칙의 참고 예시로 재발방지 문서(Task 18)에 인용 가치 |
| 회피된 우려 표 | L163-180 | (c) 보존 | topic 결정상 "기록 보존용" 명시 — 삭제 대상 아님 |

#### 4.1.5 `docs/context/PITFALLS.md`

| 문서 위치 | 문제 | 소스 근거 | 수정 방향 | 심각도 |
|---|---|---|---|---|
| 헤더 L3 | "최종 갱신: 2026-05-17" — 본문 §24(2026-06-27/06-30 산출물, absent(kafka_brokers) 분기)가 헤더보다 훨씬 최근 | F13(alerting rule 4그룹 도입 시점) | 헤더를 §24 도입 시점(또는 Task 정정 완료 시점)으로 갱신 | **S3** (표본 #3, 위치 재확인) |
| §18 "원인" 절 L187 + 제목 L182 | "L6: 외부 force resetToReady 등이 동일 orderId 재confirm 을 띄울 때 발생 가능" — 이 시나리오는 CONCERNS.md **L-12**("보상 끝난 결제의 새 confirm 사이클 cascade")의 내용과 정확히 일치. CONCERNS.md 의 실제 L-6 은 "EOS multi-instance hostname 충돌"로 전혀 다른 주제 — ID 참조가 어긋나 있음(리스트 재편 이력 추정) | `docs/context/CONCERNS.md:120-125`(L-6 실제 내용=hostname) vs `:147-149`(L-12 실제 내용=force resetToReady cascade, PITFALLS §18 과 문장 단위 일치) | "L6" → "L12" 로 정정 (제목 + 본문 2곳) | **S2** (문서 간 ID 참조 오류, 신규 발견) |
| §17 L180 | "(L2 알려진 한계)" — Redis AOF fsync race window 잔존 위험의 근거로 "L2" 를 인용하나, CONCERNS.md 의 현재 L-2 는 "`payment_event_dedupe` TTL 정리 스케줄러 부재"(✅ 이미 해소, 전혀 다른 주제)로 매칭되는 항목이 CONCERNS.md 에 없음 — 참조 자체가 dangling | `docs/context/CONCERNS.md:99-101`(L-2 실제 내용) — AOF/Redis crash 주제의 L-* 항목이 CONCERNS.md 전체에 부재 확인(grep) | 괄호 인용 삭제하거나, CONCERNS.md 에 신규 L-* 항목으로 등재 후 정확히 재연결 (Task 9 결정) | **S2** (dangling 참조, 신규 발견) |
| 본문 나머지 (§1,2,4~16,19~23) | 사실 목록(F1~F28) 및 코드 재확인 결과와 전건 일치 — 함정 서술 자체는 정합 | 각 절이 인용하는 배경 토픽(TIME-MODEL, STOCK-COMPENSATION-RECOVERY 등)과 F6/F7/F21~F23 대조 결과 불일치 0건 | 변경 불요(보존) | — |

### 4.2 Task 3 — 잔여 에이전트 문서 12파일 + smoke 5파일

대상 17파일(`ARCHITECTURE`/`STRUCTURE`/`STACK`/`stack/flyway-operations`/`CONVENTIONS`/`TESTING`/`INTEGRATIONS` + `conventions/` 5파일 + `docs/smoke/` 5파일) 전건 통독 + §1 사실 목록(F1~F28) 대조 + S1 클러스터(outbox REQUIRES_NEW/IN_FLIGHT stale, `PaymentOutboxStatus.FAILED` dead-terminal, `parallel-enabled` 층위 위반) grep 재확인. **결론: 이 17파일에는 위 S1 클러스터 3종이 나타나지 않는다** — `REQUIRES_NEW`/`IN_FLIGHT`/`toFailed`/`parallel-enabled` 전건 grep 0건(플로우 서술은 CONFIRM-FLOW/PAYMENT-FLOW 에만 있고, 이 17파일은 아키텍처/구조/컨벤션/스모크 레벨이라 outbox 재시도 디테일을 서술하지 않음). 대신 **이 17파일 자체 내부 대조에서 신규 S1 모순 2건**을 발견했다(4.2.2) — 다른 문서를 흉내 낸 게 아니라 코드 대조로 직접 확인.

#### 4.2.1 `docs/context/ARCHITECTURE.md`

전건 통독 + F1~F28 대조. `재고 복구 가드 (폐기)` 행이 이미 F7 기준으로 정합(死 코드로 정확히 표기), dedupe/AOF/Redis 설정 등 세부 수치도 소스와 일치(`docker-compose.infra.yml:98` `appendfsync always`, `PaymentEventDedupeStore` 어댑터 서술 F5 일치). "다음 토픽: PHASE-4 — Toxiproxy 8종 장애 주입" 서술은 stale 로 의심했으나 재검증 결과 **여전히 정확** — `docker/toxiproxy.json` 은 kafka-proxy 1개(latency toxic 전용)만 정의돼 있고 ALERTING-RULES-AND-FAULT-DRILL/FAULT-INJECTION-RESILIENCE 가 수행한 것은 이 중 "코디네이터 lag/DLQ/가용성" 알람 검증용 latency 드릴뿐이라, TODOS.md T4-A(`Kafka 지연/DB 지연/프로세스 kill/보상 중복 방지/FCG timeout/Redis 다운/재고 발산/DLQ 소진` 8종 전체)는 여전히 미착수(Task 2 에서 이미 (c) 보존 판정). 이 파일 범위에서 S1/S2 신규 발견 없음.

| 문서 위치 | 문제 | 소스 근거 | 수정 방향 | 심각도 |
|---|---|---|---|---|
| 전체 | 재검토 결과 코드-문서 불일치·문서 내 모순 0건 — 변경 불요(보존) | ARCHITECTURE.md 전 항목을 F1~F28 + `docker/toxiproxy.json` + `docker-compose.infra.yml:98` 로 대조, 불일치 0 | 보존 | — |

#### 4.2.2 `docs/context/STRUCTURE.md`

`STRUCTURE.md` 자체가 아니라 **`STACK.md`/`TESTING.md` 와의 대조에서 코드-문서 불일치 2건을 신규 발견**했다 — 다른 문서 인용이 아니라 각 주장을 `build.gradle` 로 독립 재확인한 결과 `STRUCTURE.md` 쪽이 코드와 어긋난다.

| 문서 위치 | 문제 | 소스 근거 | 수정 방향 | 심각도 |
|---|---|---|---|---|
| L169 (§빌드 트리거) | "`./gradlew test` \| 전 모듈 **단위 + 통합** 테스트 (Testcontainers MySQL/Redis 포함)" — 실제로는 `test` task 가 `integration` 태그를 **제외**한다(단위만). `STACK.md:117` "`./gradlew test` \| 단위 테스트만 (`integration` 태그 제외)" 가 정확 | `build.gradle:66-67` — `useJUnitPlatform { excludeTags 'integration' }` | "전 모듈 단위 + 통합 테스트" → "전 모듈 단위 테스트만(`integration` 태그 제외, 통합은 `integrationTest` 별도 task)"로 정정 | **S1** (신규 발견, `STACK.md` 와 정면 모순) |
| L177 (§정적 분석) | "JaCoCo: **모듈별** `build.gradle` 의 `jacocoTestReport` + `jacocoTestCoverageVerification`" — 실제로는 루트 `build.gradle` 의 `subprojects` 블록 안에 태스크 정의가 전부 있고, 서비스별 `build.gradle` 에는 `ext.jacoco.lineCoverageMinimum` 값만 존재(태스크 블록 없음). `TESTING.md:131` "설정 위치: 루트 `build.gradle` `subprojects` 블록 공통(4서비스 일괄). payment-service 개별 블록은 제거됨" 이 정확 | 루트 `build.gradle:20`(`subprojects {`)~`178`(`jacocoTestCoverageVerification {`) 안에 태스크 정의, `payment-service/build.gradle:15` 는 `ext` 값만 | "모듈별 `build.gradle` 의" → "루트 `build.gradle` `subprojects` 블록(4서비스 공통)의" 로 정정 | **S1** (신규 발견, `TESTING.md` 와 정면 모순) |

#### 4.2.3 `docs/context/STACK.md`

| 문서 위치 | 문제 | 소스 근거 | 수정 방향 | 심각도 |
|---|---|---|---|---|
| L98-110 (§스케줄러 활성화 정책 — 매트릭스 + 역할별 목록) | "서비스별 활성 매트릭스"가 payment/product/pg/eureka·gateway 4행만 나열하고 **user-service 가 전체 누락**됨. 그러나 user-service 도 `SchedulerConfig`(`@EnableScheduling` + `@ConditionalOnProperty(scheduler.enabled=true)`)를 보유하고 `DependencyHealthMetrics`(`@Scheduled(fixedDelayString="${metrics.user.dependency.polling-interval-seconds:10}000")`)가 실제로 그 게이트 아래 동작한다(FAULT-INJECTION-RESILIENCE 에서 신규 도입 — STATE.md 재개 메모의 "user `@EnableScheduling` 누락" 갭도 이 컴포넌트 관련). 추가로 "스케줄러 역할별 목록" 불릿도 payment/pg/product 3개뿐이고 4서비스 공통 `DependencyHealthMetrics`(availability 알람이 소비하는 폴링 게이지, ARCHITECTURE.md 는 이미 정확히 "4서비스" 로 서술)가 어느 서비스 목록에도 등재되지 않음 | `user-service/.../infrastructure/config/SchedulerConfig.java:19-24`(`@EnableScheduling` + `@ConditionalOnProperty`), `user-service/.../infrastructure/metrics/DependencyHealthMetrics.java:89`(`@Scheduled`) — payment/pg/product 동일 클래스도 각각 `@Scheduled` 확인(`payment:115`, `pg:110`, `product:89`) | 매트릭스에 user-service 행 추가("`scheduler.enabled=true` 필요, 비활성/활성" — payment/product 와 동일 패턴), 4개 역할별 목록 불릿에 각각 `DependencyHealthMetrics`(의존성 가용성 폴링 게이지, availability 알람 소비) 추가 | **S1** (신규 발견 — FAULT-INJECTION-RESILIENCE 반영 누락, 헤더는 "6/30 ship 반영됨"이라 주장하지만 이 섹션은 실제로 안 됨) |

#### 4.2.4 `docs/context/stack/flyway-operations.md`

`STACK.md` §DB 마이그레이션이 상세를 이 문서로 위임(SSOT 이미 명확)하는 패턴이 잘 지켜짐. 두 패턴(payment/pg=`db/migration` 단일 vs product/user=`db/schema`+`db/seed`) 서술을 `V*.sql` 실제 디렉토리 구조와 대조 — 일치. `MissingMigrationException` 3-step 대응 절차도 코드(`spring.flyway.ignore-migration-patterns` 기본값 `*:future` only)와 일치. S1/S2 신규 발견 없음(보존).

#### 4.2.5 `docs/context/CONVENTIONS.md` (인덱스)

9줄, 5개 하위 문서 링크만 — 대상 5파일과 제목 1:1 대응 확인(파일 경로·앵커 유효). 신규 발견 없음(보존).

#### 4.2.6 `docs/context/TESTING.md`

| 문서 위치 | 문제 | 소스 근거 | 수정 방향 | 심각도 |
|---|---|---|---|---|
| L166-178 (§현재 테스트 카운트) | "2026-06-14 기준" 스냅샷(873/48) — §2 표본 #7 인계 확장. 정확한 최신 카운트는 여전히 미확정(수정 시점 재실행 필요) | F26(`@Test` grep 342/240/50/9, 이후 13+ 토픽에서 추가/삭제) — Task 3 재확인으로 grep 재실행하니 payment 단위 파일 기준 `@Test` 합계가 F26 시점과도 또 달라져 있음(최신 파일 목록에 `DependencyHealthMetricsTest` 4서비스·`StockResyncUseCaseTest`·`PgConfirmListenerSplitIntegrationTest` 등 F26 이후 신규 파일 다수 확인) — 스냅샷이 계속 낙후되는 구조적 문제 재확인 | Task 9 수정 시점 `./gradlew test`/`integrationTest` 재실행 값으로 갱신(§2 #7 결론과 동일) | **S3** (표본 #7 확장 재확인) |

**S4 중복 발견**: `TESTING.md` §JaCoCo 커버리지 정책(L124-137, 측정대상/제외이유/게이트 산정 근거까지 상세)과 `STACK.md` §정적 분석 도구(L128, 같은 수치·같은 "단위 test exec 기준" 문장을 압축 재서술)이 사실상 동일 내용을 두 곳에서 설명 — SSOT 지정안은 4.2.7 이후 별도 절(4.2.18) 참고.

#### 4.2.7 `docs/context/INTEGRATIONS.md`

grep 상 "Elasticsearch/Logstash" 매치가 있었으나 실제로는 `net.logstash.logback:logstash-logback-encoder`(JSON 인코더 라이브러리명일 뿐, Elasticsearch 서버 언급 아님) — `관측성 통합` 표는 정확히 "Loki | Logback LogstashEncoder + LogFmt → Promtail/직접 push" 로 F19(Loki/Promtail 스택)와 일치. `PgConfirmPort`/`PgStatusLookupPort` 포트 분리, 502/504 retryable 승격(`ProductFeignConfig.java:54,56` `HttpStatus.BAD_GATEWAY`/`GATEWAY_TIMEOUT`), `pg_inbox.attempt` self-loop(F11) 등 전건 소스 대조 일치. S1/S2 신규 발견 없음(보존).

#### 4.2.8 `docs/context/conventions/code-style.md`

주석 금지 ID 예시 목록(`D7`/`PET-8`/`TC-3`/`L-14`/`TQ-1` 등)이 현재도 유효한 식별자 체계와 일치, Builder/Lombok/Try 블록 패턴 모두 실제 코드 패턴(`PgInbox.createPending`, `PaymentEvent.done(Instant, Instant)`)과 대조해 일치. S1/S2 신규 발견 없음(보존).

#### 4.2.9 `docs/context/conventions/error-logging.md`

예외 계층 트리, `LogFmt` 사용법, AOP `@PublishDomainEvent`/`@PaymentStatusChange`/`@TransactionalEventListener(AFTER_COMMIT)` 패턴 서술을 실제 코드와 대조 — 일치. S1/S2 신규 발견 없음(보존).

#### 4.2.10 `docs/context/conventions/kafka.md`

groupId 네이밍(`payment-service`/`pg-service`/`pg-service-dlq`), `DefaultErrorHandler`+`FixedBackOff(1000ms, 5)`+not-retryable 3종(`MessageConversionException`/`IllegalArgumentException`/`IllegalStateException`) 서술을 `KafkaErrorHandlerConfig.java:21,72,75-77` 로 대조 — 일치. `max.poll.records` 미설정(default 500) 서술도 `application.yml` grep 으로 확인 — 일치. S1/S2 신규 발견 없음(보존).

#### 4.2.11 `docs/context/conventions/testing.md`

17줄, Bean Validation + TDD 흐름만 — `CLAUDE.md`/`commit.md` 룰과 대조해 일치. S1/S2 신규 발견 없음(보존).

#### 4.2.12 `docs/context/conventions/transactions.md`

| 문서 위치 | 문제 | 소스 근거 | 수정 방향 | 심각도 |
|---|---|---|---|---|
| L20-23 (예시 코드) | `PaymentConfirmResultUseCase.handle` 예시로 `@Transactional(timeout = 5)` 만 표기 — 실제 코드는 `@Transactional(transactionManager = "transactionManager", timeout = 5)` 로 qualifier 를 **명시**한다(F1). 이 qualifier 는 바로 위 §0.3/CONCERNS L-1 이 다루는 EOS 트랜잭션 매니저 혼동 방지의 핵심 디테일이라, 컨벤션 문서의 "표준 예시"에서 빠지면 qualifier 없이도 되는 것처럼 오독될 위험이 있다 | `PaymentConfirmResultUseCase.java:116` — `@Transactional(transactionManager = "transactionManager", timeout = 5)`, 같은 파일 Javadoc L112-114 "qualifier `transactionManager` 는 `JpaConfig#transactionManager` 빈을 명시 지정 — Kafka `KafkaTransactionManager` 와의 혼동 방지" | 예시 코드에 `transactionManager = "transactionManager"` qualifier 추가 + 왜 명시하는지 1줄 근거(EOS 환경에서 `@Primary` 만으로는 의도가 코드에 드러나지 않음) 보강 | **S1** (신규 발견 — 표본 #1/CONCERNS L-1 과 같은 사실 축의 컨벤션 문서 반영 누락) |

#### 4.2.13 `docs/smoke/alert-firing-check.md`

25케이스(coordinator 6/guard-skip 3/dlq 7/availability 9) 표를 `observability/prometheus/rules/tests/*.yml` 실제 케이스 수·availability 드릴 4시나리오(a~d)를 `alert-firing-availability.sh` 로 대조 — 일치. "라이브 한계 명시"(consumer lag 비대칭 불가/txn abort 미발화) 서술도 F13/STACK.md §알람 규칙 서술과 일치. S1/S2 신규 발견 없음(보존).

#### 4.2.14 `docs/smoke/infra-healthcheck.md`

"13개 서비스 컨테이너"(인프라 8 + scalable 5) 를 `scripts/smoke/infra-healthcheck.sh:76-103` `EXPECTED_INFRA_SERVICES`(8)+`SCALABLE_SERVICES`(5) 로 대조 — 정확히 일치. Eureka 5개 앱 등록 서술도 ARCHITECTURE.md 와 일치. S1/S2 신규 발견 없음(보존).

#### 4.2.15 `docs/smoke/observability-load.md`

부하 생성기 옵션(`--profile`/`--fail-rate`/컨트롤 파일 축) 서술, "QUARANTINED/DLQ 패널은 단순 부하로 안 켜짐 → Phase-4 Toxiproxy 몫" 서술 — TODOS T4-A(미착수, 4.2.1 재확인) 와 일치. S1/S2 신규 발견 없음(보존).

#### 4.2.16 `docs/smoke/observability-walkthrough.md`

대시보드 바로가기 URL 의 UID(`payment-business-d001`/`payment-system-d001`)를 `observability/grafana/dashboards/{business,system}-dashboard.json` 실제 `"uid"` 필드로 대조 — 정확히 일치. "로그(orderId)→traceId→Tempo" 진입 경로 서술도 F19/STACK.md 와 일치. S1/S2 신규 발견 없음(보존).

#### 4.2.17 `docs/smoke/trace-continuity-check.md`

5개 서비스 hop(gateway→payment→pg/product→user/벤더) 서술, `ContextAwareVirtualThreadExecutors`/`PgOutboxChannel.offerNow`/`KafkaConsumerConfig` observation 참조를 ARCHITECTURE.md 횡단 관심사 표(F 대조 완료분)와 재대조 — 일치. S1/S2 신규 발견 없음(보존).

#### 4.2.18 중복 서술(S4) — SSOT 지정안

| 중복 내용 | 위치 A | 위치 B | SSOT 지정 | 근거 |
|---|---|---|---|---|
| JaCoCo 커버리지 게이트 값·정책(측정대상/제외/게이트 산정 근거/단위 test exec 기준) | `TESTING.md` §JaCoCo 커버리지 정책 (L124-137, 상세 — 제외 이유·산정 근거 포함) | `STACK.md` §정적 분석 도구 표 JaCoCo 행 (L128, 거의 동일 문장 압축 재서술) | **`TESTING.md`** (상세 근거 보유) | 두 서술이 같은 수치(payment 0.86/pg 0.93/product 0.97/user 0.97/gateway·eureka 0.0)와 같은 "게이트는 단위 test exec 기준" 근거 문장을 반복 — `STACK.md` 행은 "값·근거는 `TESTING.md`#jacoco-커버리지-정책 참고" 1줄로 축소 (Flyway 가 이미 이 패턴 사용 중, 4.2.4) |
| 빌드 트리거 명령 표(`./gradlew build`/`test`/`:<svc>:test`/`:<svc>:integrationTest`) | `STRUCTURE.md` §빌드 트리거 (L166-171, 4.2.2 에서 `test` 범위 오류 발견) | `STACK.md` §빌드/검증 (L114-120, 정확 + `compose-up.sh`/`infra-healthcheck.sh` 까지 포함해 더 완전) | **`STACK.md`** (정확 + 더 넓은 범위) | 4.2.2 의 S1(모순) 과 동일 원인 — 두 표가 같은 명령 집합을 서로 다르게 서술하다 하나가 stale 화됨. `STRUCTURE.md` 절은 삭제하고 "빌드/검증 명령은 `STACK.md` 참고" 링크로 대체 |
| Contract test 2-layer 패턴(ErrorDecoder + 어댑터 propagation) | `TESTING.md` §Contract test 패턴 (L73-101, 상세 — 표+시나리오) | `INTEGRATIONS.md` §Cross-service HTTP 내 "Contract test" 문단 (L92, 요약 1줄) | **`TESTING.md`** (상세 소유), `INTEGRATIONS.md` 는 요약 유지 | 요약과 상세 관계라 중복 자체는 경미(S4 minor) — `INTEGRATIONS.md` L92 에 `TESTING.md` 명시 링크 추가만 권고, 삭제 불요 |
| CircuitBreaker "Phase 4 예정" 서술 | `ARCHITECTURE.md` 핵심 설계 결정 인덱스 "HTTP 어댑터 회복성" 행 (L181, 근거 없이 1줄) | `INTEGRATIONS.md` §벤더/Cross-service 회복성 (L94, "Phase 4 (T4-D) 예정" + fallbackFactory 마이그레이션 근거 포함) | **`INTEGRATIONS.md`** (근거 보유) | 경미 중복(S4 minor) — `ARCHITECTURE.md` 행에 "상세: `INTEGRATIONS.md`" 링크 추가 권고 |

**완료 기준 대조**: 17파일 전부 페이지별 판정 존재(4.2.1~4.2.17, "보존" 판정도 표/문장으로 명시), S1(4건: STRUCTURE.md×2 + STACK.md×1 + conventions/transactions.md×1) 전건 소스 근거(파일:라인) 포함, S4 중복 4건 SSOT 지정 완료(4.2.18).

### 4.3 Task 4 — README + PAYMENT-FLOW-GUIDE

전건 통독 + §1 사실 목록(F1~F28) 대조 + Task 2 확정 S1 클러스터(outbox REQUIRES_NEW/IN_FLIGHT stale) grep 재확인. 표본 #5/#6 은 아래 4.3.1/4.3.4 에서 정확한 위치로 확장했다. 모든 소스 근거는 이번 태스크에서 독립적으로 재확인(코드 직접 grep/Read)했으며, 기존 판정(F1~F28, Task2 §4.1)을 인용하는 곳은 "동일 축" 표기만 하고 판정 자체는 재수행했다.

#### 4.3.1 `README.md`

| 문서 위치 | 문제 | 소스 근거 | 수정 방향 | 심각도 |
|---|---|---|---|---|
| L18-28 "🚀 주요 해결 과제" 표, "장애 내성 복구 체계" 행(L24) | "복구 판정 객체 + 스케줄링 + 재고 복원 가드 + 격리 직전 vendor 재조회" \| "6 분기 복구 결정 + 격리 전 최종 확인 + 동시성 가드" — 이 행이 서술하는 4개 개념 중 3개가 현재 코드에 없다: (1) "복구 판정 객체"(`RecoveryDecision`) 는 코드베이스에서 **완전 삭제**(파일 자체 0건) (2) "재고 복원 가드"(`canCompensateStock` 이중 조건 가드) 는 **완전 삭제**, `handleFailed`/`handleQuarantined` 는 가드 없이 `compensateAtomic` 직접 호출(F7) (3) "격리 직전 vendor 재조회"("격리 전 최종 확인")는 `PgFinalConfirmationGate` 클래스로 존재하나 **프로덕션 호출처 0건**(미연결) — "6 분기 복구 결정"도 이제 존재하지 않는 `RecoveryDecision` 의 분기 수. "스케줄링"(`PaymentReconciler`)만 현재도 유효 | `RecoveryDecision` 전체 grep 0(파일 자체 부재), `PaymentConfirmResultUseCase.java:280-303`(`handleFailed`/`handleQuarantined` 가드 없이 `compensateAtomic` 직접 호출), `PgFinalConfirmationGate.java` 존재하나 호출부 grep 결과 `PgStatusLookupPort.java`(의존 선언)뿐 — 실제 호출자 0건(`PAYMENT-FLOW.md:377` 이미 동일 결론) | 행 전면 재작성 — 현재 유효한 장애 내성 요소(`PaymentReconciler` 스케줄 복원, pg self-loop retry+DLQ 자동 격리, 종결 가드 재발행 등)로 교체. FCG 는 "존재하나 미연결(dead code, TODOS 등재 대상)"로만 언급 가능, 마치 동작 중인 것처럼 헤드라인화 금지 | **S1 critical** (표본 #5 확장 — 폐기 기능 서술, README 도메인 사실) |
| L295-328 "결제 상태 관리" 섹션, 특히 L297("보상 안전 가드 자체는 유지") + L325(mermaid GUARD 노드 "재고 복원 가드\n대기열 선점 중?\n결제 비종결?") | Phase 5→6 전환 캡션이 "PG 상태 조회 경계만 이동, 보상 안전 가드 자체는 유지"라고 명시하나, 실제로는 그 가드(`canCompensateStock`, 대기열 선점 중 + 결제 비종결 이중 조건)가 STOCK-COMPENSATION-OTHER-PATHS 에서 완전 삭제됐다 — "유지" 주장이 코드와 정반대(현재는 `QuarantineCompensationHandler.handle` 의 단일 종결 상태 체크만 남음, 이중 조건 가드 아님) | F7(`canCompensateStock` grep 0) + `PaymentConfirmResultUseCase.java:280-303`(가드 없는 직접 호출) + `QuarantineCompensationHandler.java:56-60`(남은 것은 `isTerminal()` 단일 체크뿐, "대기열 선점 중" 조건 없음) | 캡션에서 "보상 안전 가드 자체는 유지" 삭제 — STOCK-COMPENSATION-OTHER-PATHS 에서 가드가 제거되고 `QuarantineCompensationHandler` 의 단순 종결 체크로 대체됐음을 명시. mermaid GUARD 노드는 이 섹션이 "Phase 5 시점 스냅샷"(역사 기록)이라는 전제가 명확하면 다이어그램 자체는 보존 가능하나, 캡션의 "유지" 단정은 정정 필수 | **S1 critical** (신규 발견 — F7 축, 표본 #5 와 다른 위치) |
| L138-143 "Outbox 모델" 표, `payment_outbox` 행 | "4상태 머신 (PENDING / IN_FLIGHT / DONE / FAILED)" — enum 값 자체는 4개 맞지만(사실), `FAILED` 는 현재 프로덕션 코드에서 전이 경로 0건인 dead-terminal 상태(Task 2 CONFIRM-FLOW.md L~372-380 항목과 동일 축) — "4상태 머신"이라는 표현이 4개 상태가 대등하게 살아있는 것처럼 오독될 소지 | `PaymentOutboxStatus.java:9-12`(enum 4값 선언), `grep -rn "PaymentOutboxStatus.FAILED\|\.toFailed(" payment-service/src/main` = 0건(Task2 재확인 결과 재사용) | "4상태(PENDING/IN_FLIGHT/DONE/FAILED, FAILED 는 현재 도달 불가)" 로 각주 또는 3+1 표기로 조정 | **S1** (Task 2 클러스터의 README 확장 위치, minor) |
| L7-12 배너 | "🚧 진행 중 · Phase 6", "589 PASS", "⚠️ 정합이 안 맞을 수 있음" 경고 — 표본 #5 판정 그대로 잔존(F26/F27 근거 재확인: `@Test` grep 총합 이 문서 작성 시점 기준 641건(annotation 수, parameterized 확장 전) 로 589 와 이미 상이) | F26(TESTING.md 스냅샷 노후) + F27(`README.md:8` 자체가 F26 보다도 이전 값) + 본 태스크 재확인 `grep -rc "@Test" {payment,pg,product,user}-service/src/test` 합계 641(2026-07-02 시점, 이후 Task 11 수정 시점에 `./gradlew test` 재실행 값으로 최종 확정 필요 — annotation 카운트는 근사치일 뿐) | "589 PASS" 삭제하고 Task 11 실행 시점 `./gradlew test` 실측값으로 교체. "정합이 안 맞을 수 있음" 경고는 이번 토픽 완료(ship) 후 제거 여부를 Task 11에서 결정 | **S3** (표본 #5 정확 위치 재확인, 확정 수정은 Task 11 실행 시점 값 필요) |
| L9 "Phase 6 은 아직 작업/점검 중이며 후속 보강 작업이 누적되어 있음 (예: 보상 트랜잭션 자동 회복 layer, 컨텍스트 정합성 점검 등)" | 괄호 예시가 막연 — "보상 트랜잭션 자동 회복 layer" 가 가리키는 구체 항목이 문서 어디에도 명시되지 않음. 가장 근접한 실제 잔여 항목은 F16(stranded READY 자동 미복구, CONCERNS L-14/TQ-1)이나 이름이 다름 | F16(`docs/context/CONCERNS.md:159-161`) — 자동 미복구 잔여 한계가 존재하긴 하나 "보상 트랜잭션 자동 회복 layer" 라는 명칭과 직접 대응 안 됨 | 막연한 예시 문구를 TODOS 실항목(TC-6/TQ-1 등, Task 8 정정 이후 확정되는 슬림 대장 기준)으로 구체화 | **S3** (경미 — 사실 오류라기보다 모호성) |
| L292 "이상적 자원 할당(Sweet Spot)" | 평가성 표현("이상적", "최적의 수치") — 문체 기준 3항(평가·과시 형용사 제거) 대상 | 문체 기준 자체가 근거(코드 근거 대상 아님) | "이상적 자원 할당" → 사실 서술("커넥션 풀 상한을 시스템 한계에 맞춰 조정" 류)로 교정 | **S5** (경미) |
| L485 "HTTP(OpenFeign + LB) 또는 Kafka 메시지를 통해 서비스 간 통신" | "~를 통해" 번역투 — 문체 기준 3항 대상 | 문체 기준 자체가 근거 | "Kafka 메시지를 통해" → "Kafka 메시지로" 등 구체 동사/조사로 교정 | **S5** (경미) |
| L34-43 "🗺️ 개발 과정" 표 + L225-462 "이전 단계 작업" 섹션 캡션의 Phase 1~6 표기 | README 자체 축(개발 진행 순서)은 내적으로 일관되나, 같은 단어 "Phase" 가 GUIDE/PAYMENT-FLOW(결제 처리 단계)·내부 로드맵(MSA 전환/TODOS T4-* 버킷) 축과 충돌(표본 #6 축 확장) — 상세는 4.3.4 절 전수 채록 | 문서 자체 근거(용어 사용 실태 채록) | Task 11 이 실태(4.3.4) 기반으로 확정 — plan 결정상 README 축은 유지, 내부 문서와 별개임을 1줄 명시(Task 8 TODOS 분류 룰에서 수행) | **S2** (표본 #6 확장, 위치는 표 전체) |
| 위키 링크(L36-43, L52, 63, 114, 160-161, 163, 227, 293, 295, 330, 355, 404, 415, 445, 456, 480 등 25개 앵커) | 슬러그-실재 파일 대조: `cross-validation`/`tx-scope`/`retry-recovery`/`scenario-test`/`structured-logging`/`metrics`/`compensation-tx`/`idempotency`/`async-outbox`/`state-management`/`msa-transition`/`event-driven-choreography`/`stock-cache-recovery`/`outbox-pattern`/`message-delivery-and-dedupe`/`pg-confirm-flow`/`trace-propagation`/`pg-strategy`/`ai-workflow`/`architecture`/`Benchmark-Report` 전건 대응 파일 존재 확인 — **깨진 링크 0건** | `payment-platform.wiki/` 디렉토리 `ls *.md` 25개 전건 대조(README 인용 21종 전부 매치) | 변경 불요(보존). 단 `outbox-channel-dispatch.md` 는 위키에 존재하나 README 어디서도 링크 안 됨 — 누락이 아니라 README 가 모든 위키 페이지를 링크할 의무는 없으므로 보존 판정, Task 5/6 판단 대상으로만 메모 | — (보존) |
| Kafka 토픽 카탈로그 표(L104-112), Redis 2 인스턴스 서술(L55, L473), 스택 표(L471-476) | `application.yml`/`docker-compose.infra.yml` 대조 — 5개 토픽명(`PaymentTopics.java` 등)·redis-dedupe/redis-stock 분리·Java 21/Spring Boot 3.4.4 등 전건 일치 | `payment-service/.../PaymentTopics.java:17`, `pg-service/src/main/resources/application.yml:83-85`, `payment-service/src/main/resources/application.yml:114-115`, `docker/docker-compose.infra.yml:69` | 변경 불요(보존) | — |

#### 4.3.2 `docs/context/PAYMENT-FLOW-GUIDE.md`

**S1 critical 클러스터 — outbox 발행 실패 stale 서술이 GUIDE 에도 확장 잔존**(CONFIRM-FLOW.md/PAYMENT-FLOW.md 의 표본 #12 클러스터와 완전히 동일한 사실 오류가 GUIDE 에도 5곳 독립 잔존 — 짝 문서 CONFIRM-FLOW.md 를 베낀 것으로 추정되나 이번 판정은 소스로 별도 재확인):

| 문서 위치 | 문제 | 소스 근거 | 수정 방향 | 심각도 |
|---|---|---|---|---|
| L105 (§A Phase 3, 단계 14) | "발행 실패 → TX rollback 이지만 `IN_FLIGHT` 유지 → 워커 폴백" — 실제로는 claim·발행·완료가 단일 `@Transactional`(`OutboxRelayService.relay`) 이라 발행 실패 시 TX 전체 롤백으로 `IN_FLIGHT` 가 커밋된 적 없이 PENDING 그대로 복귀 | `OutboxRelayService.java:49-78`(단일 `@Transactional`, F2) | "발행 실패 → 예외가 relay TX 를 롤백해 선점까지 함께 되돌림 → PENDING 복귀" 로 정정 | **S1 critical** |
| L107 (§A Phase 3, 단계 15 각주) | "폴백: `OutboxWorker`(`@Scheduled` fixedDelay 5s) — `IN_FLIGHT` 5분 타임아웃 → PENDING 복귀 후 재픽업" 을 발행 실패의 **1차 회복 경로**처럼 서술 — 실제 1차 경로는 위 TX 롤백 → PENDING 즉시 복귀 → 5초 주기 재픽업이고, `recoverTimedOutInFlightRecords`(IN_FLIGHT 5분 타임아웃 회수)는 워커 크래시 등 별도(더 드문) 시나리오 | `OutboxWorker.java:26,38,41`(F3 — `recoverTimedOutInFlightRecords`/`findPendingBatch` 만 호출), `application.yml:147`(fixed-delay-ms 5000) | "1차 경로: TX 롤백 → PENDING 즉시 복귀 → 5초 주기 재픽업. `IN_FLIGHT` 5분 타임아웃 회수는 워커 크래시 등 보조 경로"로 우선순위 재정렬(PAYMENT-FLOW.md L200 항목과 동일 수정 방향) | **S1** |
| L214-217 (§B-2 PUBREC 서브그래프) | "Kafka 발행 실패 → `IN_FLIGHT` 유지 → `OutboxWorker` @5s `IN_FLIGHT` 5분 타임아웃 → PENDING 복귀 → relay 재시도" — 위와 동일 오류가 mermaid 다이어그램으로 재등장 | 상동 | "발행 실패 → TX 롤백 → PENDING 즉시 복귀 → 5초 주기 재픽업" 흐름으로 노드·엣지 재작성 | **S1 critical** |
| L254-255 (§C 회복 경로 색인 표) | "Kafka 발행 실패(payment→broker) \| `IN_FLIGHT` 유지 → 타임아웃 후 PENDING 복귀 → relay 재시도" — 표 형태로 재등장 | 상동 | "TX 롤백 → PENDING 즉시 복귀 → 5초 주기 재픽업" 으로 정정 | **S1 critical** |
| L294, L307-308 (§D 통합 플로우차트) | `OW["OutboxWorker @5s<br/>IN_FLIGHT 타임아웃 회수"]`, `REL -. 발행 실패·IN_FLIGHT 유지 .-> OW` — 마스터 다이어그램에도 동일 오류 | 상동 | "발행 실패" 엣지를 "TX 롤백·PENDING 복귀"로 재라벨, `OW` 노드는 "IN_FLIGHT 5분 타임아웃 회수(보조 경로)"로 역할 명확화 | **S1** |

**나머지 부분 — 전건 검증 결과 정합(보존)**: 이번 태스크에서 GUIDE 의 기술적 주장 다수를 독립 소스 대조했다.

- 단계 25 "실패(FAILED) → 재고 보상 먼저(`compensateAtomic`) → 실패 확정" — `PaymentConfirmResultUseCase.java:280-287` 와 정확히 일치(가드 없는 직접 호출, F7). README 와 달리 GUIDE 는 이미 가드 삭제 사실을 정확히 반영하고 있음 — 정정 불요
- 단계 26 EOS abort → `DefaultErrorHandler`(FixedBackOff 1s×5) → `events.confirmed.dlq` — F12(`KafkaConsumerConfig.java:92`) 와 일치
- 단계 23 "DONE+APPROVED 재배달 → 재고확정 재발행" — F10(`PaymentConfirmResultUseCase.java:124-138`) 와 일치
- 단계 17 `EventDedupeStore.markSeen`(pg-service) — `pg-service/.../application/port/out/EventDedupeStore.java` 존재 확인(payment 측만 제거됐고 pg-service 는 존치 — stale 마커 아님)
- §C "PG 재시도 한도 초과(DLQ)" 행, 단계 20 `pg_inbox.attempt` 서술 — F11/F12 일치
- `PaymentReconciler`(§B-2 STUCK/RECON, @2분 `resetToReady`) — `PaymentReconciler.java:44`(`fixed-delay-ms:120000` 기본값 2분) 일치, 최근 롤백 이력(STATE.md 재개 메모)도 이 메서드 자체의 존재·주기는 건드리지 않음
- 인용된 클래스/메서드명 17종(`markStockCacheDownQuarantine`/`executeConfirmTx`/`StockEventUuidDeriver`/`PgTerminalReemitService`/`DuplicateApprovalHandler`/`PgInboxImmediateWorker`/`processInProgressZombie`/`invokeVendor`/`applyOutcome`/`PgOutboxRelayService`/`PgEventPublisher`/`shouldRetry`/`handleActiveInbox`/`insertPendingAndPublish`/`OutboxImmediateEventHandler`/`canApplyConfirmResult`/`terminalResendMetrics`) 전건 grep 존재 확인 — 개명·삭제 0건
- 약어 범례의 `D7`/`SCR-6` 내부 ID — `CONFIRM-FLOW.md:22,124,138,164,169,224,423,513` 에 실사용 확인, dangling 아님

**S5 문체 판정**: GUIDE 는 번호 시퀀스·표·mermaid 위주 구조화 기술 문서로 위키/README 와 장르가 다르다 — 평가·과시 형용사, 번역투, 짧은 단정문 연발 패턴이 grep 상 0건(`를 통해`/`함으로써`/`방식을 사용`/`매우`/`완벽`/`탁월`/`최적` 등 전건 매치 없음). **문체 수정 대상 없음(보존)** — Task 12 는 위 outbox 클러스터 5곳의 사실 정정에만 집중.

#### 4.3.3 README 도메인 사실(S1) 항목 — ship domain-expert 대조 입력용

plan 게이트 결정(완료 기준 "README diff 중 도메인 사실(S1) 항목은 ship domain-expert 대조 입력에 포함")에 따라 Task 11 수정 후 ship 단계에서 domain-expert 가 별도 대조해야 할 항목을 표시한다.

1. "주요 해결 과제" 표 "장애 내성 복구 체계" 행 전면 재작성 (4.3.1 첫 행) — `RecoveryDecision` 삭제/`canCompensateStock` 삭제/FCG 미연결 3사실 동시 반영
2. "결제 상태 관리" 섹션 "보상 안전 가드 자체는 유지" 캡션 삭제 + mermaid GUARD 노드 처리 방식 (4.3.1 둘째 행)
3. Outbox 모델 표 `FAILED` dead-terminal 각주 반영 (4.3.1 셋째 행)

#### 4.3.4 Phase 표기 실태 전수 채록 (Task 11 결정 입력)

동일한 "Phase" 단어가 최소 3개 축으로 혼용된다 — 표본 #6 이 발견한 2축(README/PAYMENT-FLOW) 에 더해 이번 태스크에서 3번째 축(MSA 로드맵/TODOS 버킷)을 전수 채록해 확장했다.

| 축 | 의미 | 사용 문서·위치 | 번호 체계 | 비고 |
|---|---|---|---|---|
| **A. 개발 진행 순서** | 위키 페이지가 커밋된 "개발 단계" 이정표 — README 고유 축 | `README.md` 배너(L7,11), 개발 과정 표(L34-43), 이전 단계 작업 섹션 캡션(L225,229,297,332,358,406,417,447,458) | Phase 1~6 (+ETC) 완료, **Phase 7 다음 예정** | 코드 개념 아님, 순수 문서 조직. 내적으로는 일관됨(README 안에서 서로 모순 없음) |
| **B. 결제 처리 단계** | 결제 1건이 checkout→confirm→outbox→pg→결과확정→폴링까지 통과하는 처리 단계 | `PAYMENT-FLOW.md:23-138`(Phase 1~5, 폴링을 Phase5 에 포함), `CONFIRM-FLOW.md:4`("Phase 1~5 전체" 인용), `PAYMENT-FLOW-GUIDE.md:70-145`(Phase 1~6, **폴링을 Phase6 으로 별도 분리** — PAYMENT-FLOW.md 와 하위 경계가 다름) | Phase 1~5 (PAYMENT-FLOW/CONFIRM-FLOW) vs Phase 1~6 (GUIDE) | A 축과 완전 무관 + B 축 내부에서도 PAYMENT-FLOW 와 GUIDE 사이에 폴링 분리 여부가 다름(경미한 하위 불일치, 표본#6 확장 신규 발견) |
| **C. MSA/기능 로드맵 버킷** | TODOS.md 의 미래 작업 뭉치 번호(T4-A~E 등) — 프로젝트 로드맵상 "다음 큰 덩어리"를 가리키는 축 | `PAYMENT-FLOW.md:6`("MSA 4서비스 분리 + Phase 0~3.5 + PRE-PHASE-4-HARDENING 봉인 시점"), `ARCHITECTURE.md:181,228`("CircuitBreaker 는 Phase 4", "Phase 4 후속"), `TODOS.md`(T4-A~E 항목명 자체가 이 축의 번호를 그대로 사용), `docs/smoke/*.md` 일부(Phase-4 Toxiproxy 인용) | Phase 0~3.5 완료 + PRE-PHASE-4-HARDENING 봉인 + **Phase 4 = T4-A~E 버킷(Toxiproxy 8종/k6 재설계/로컬 오토스케일러/CircuitBreaker) 미착수** | PAYMENT-FLOW.md:6 앵커 자체는 2026-04-24 수준 오래된 시점 표기(표본#6 확장, Task 7 정정 대상) |

**핵심 교차 발견**: README 축(A)의 "다음 Phase 7"(L11-12, "회복성 검증 = 장애 주입 + k6 시나리오 재설계 + 로컬 오토스케일러 + 서킷브레이커")과 내부 로드맵 축(C)의 "Phase 4"(T4-A~E: Toxiproxy 8종 장애 주입/k6 시나리오 재설계/로컬 오토스케일러/CircuitBreaker)는 **내용이 완전히 동일한 작업 뭉치를 서로 다른 번호(7 vs 4)로 부르고 있다** — `docs/context/TODOS.md:159-196`(T4-A~D 항목명·내용) 대조로 확인. 세 축 모두 실제로 열려 있는(미착수) 항목이라는 점에서 완료/진행 상태 서술 자체는 정확(README "다음" 표기는 사실과 일치) — 문제는 번호 불일치뿐.

**Task 11 결정 입력**: plan 이미 "README 는 독자용 Phase 1~7 체계 유지"로 확정했으므로 축 통일은 비범위. 다만 위 교차 발견(README Phase 7 = 내부 로드맵 Phase 4, 같은 작업)은 독자 혼란 소지가 있어 Task 11 에서 README "다음 Phase 7" 절 근처에 "내부 로드맵 문서의 Phase 번호와는 무관한 별도 체계"라는 1줄 disambiguation 추가를 권고(선택, plan 승인 필요 시 반영 — 강제 완료 기준 아님).

### 4.4 Task 5 — 위키 도메인 코어 12페이지

> (Task 5 에서 채움 — #9/#10/#11 은 위 §2 판정을 인계받아 확장)

### 4.5 Task 6 — 위키 잔여 13페이지

> (Task 6 에서 채움 — #8 은 위 §2 판정을 인계받아 확장)

---

## 5. 완료 기준 대조

- [x] 사실 목록(§1) 전 항목 소스 파일:라인 채록 — 문서 인용 근거 0건 (F18/F26 은 문서 자체의 헤더-본문/스냅샷 불일치가 사실이라 문서를 1차 근거로 병기하되, 근거가 되는 "무엇이 바뀌었는가"는 각각 F13/F5~F17 소스로 뒷받침)
- [x] 표본 12건 리포트 수록·판정 완료 (§2)
- [x] 항목 형식에 "기본값 층위 명시" 규칙 포함 (§0.3)
- [x] 기준 예문 retry 카운트 불릿 재검증 완료 (§3.2)
- [x] Task 2 — 플로우·대장·함정 5파일 전부 페이지별 판정 존재, S1/S2 전건 소스 근거 포함 (§4.1)
- [x] Task 3 — 잔여 에이전트 문서 12파일 + smoke 5파일 전부 페이지별 판정 존재, S1/S2 전건 소스 근거 포함, 중복 서술(S4) SSOT 지정안 포함 (§4.2)
- [x] Task 4 — README + PAYMENT-FLOW-GUIDE 2파일 판정 완료 (§4.3.1~4.3.2), README 도메인 사실(S1) 항목 별도 표기(§4.3.3, ship domain-expert 대조 입력용), Phase 표기 실태 전수 채록(§4.3.4, Task 11 결정 입력)
