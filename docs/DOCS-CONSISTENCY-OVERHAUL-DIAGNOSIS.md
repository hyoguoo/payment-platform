# 문서 전수 정합 개선 — 진단 리포트

> 최종 갱신: 2026-07-02 (Task 1 — 사실 목록 + 리포트 뼈대)
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

> (Task 2 에서 채움 — #2/#3/#4/#12 는 위 §2 판정을 인계받아 확장)

### 4.2 Task 3 — 잔여 에이전트 문서 12파일 + smoke 5파일

> (Task 3 에서 채움 — #6/#7 은 위 §2 판정을 인계받아 확장)

### 4.3 Task 4 — README + PAYMENT-FLOW-GUIDE

> (Task 4 에서 채움 — #5/#6 은 위 §2 판정을 인계받아 확장)

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
