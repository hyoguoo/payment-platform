# Planned Cleanup / Future Work

> 최종 갱신: 2026-07-31 (AGENT-CONTEXT-OVERHAUL Task 17 — 섹션 F 에 1건 추가 등재: `[CONFIRM-PAYMENT-FLOW-MERMAID-ARROW-CLEANUP]`(검사 스크립트 Mermaid 금지 문자 판정이 찾은 `CONFIRM-FLOW.md`/`PAYMENT-FLOW.md` 기존 다이어그램의 유니코드 화살표 라벨 40건 정리)). 이전: 2026-07-31 (AGENT-CONTEXT-OVERHAUL Task 14 — 섹션 F 신설 후 4건 등재: `[REVIEWER-EFFORT-DOWNGRADE-RECHECK]`(reviewer effort 하향 `xhigh`→`high` 원복 조건 재확인, `CONCERNS.md` C-11 연결), `[AGENT-DOCS-STATIC-ANALYSIS]`(`var`·`@Data`·null 반환·`catch (Exception)`·try 블록 외부 변수 재할당을 checkstyle·ArchUnit으로 강제), `[AGENT-DOCS-CHECK-SCRIPT-CI]`(지침 문서 검사 스크립트의 CI 편입), `[CODE-READY-HANDLEUNKNOWNFAILURE-STALE]`(`code-ready.md` convention 섹션이 코드베이스에 더 이상 없는 `handleUnknownFailure` 메서드를 참조)). 이전: 2026-07-29 (LIVE-DRILL-FORMALIZATION Task 10 — 섹션 E 에 1건 추가 등재: `[CHECKOUT-DUPLICATE-FLAG-DROPPED]`(체크아웃 응답 DTO 매퍼가 `isDuplicate` 를 누락 — 실제 중복 신호는 HTTP 상태 코드뿐, 라이브 실측 장면 7 판정 기준을 이에 맞춰 정정)). 이전: 2026-07-29 (LIVE-DRILL-FORMALIZATION Task 6 — 섹션 E 신설 후 2건 등재: `[FAKE-PG-BOOT-ENV-GUARD]`(모의 벤더 로드 시 환경 조합 검사 가드 부재 — 부팅 차단은 어떤 환경을 정상으로 볼지 정하는 배포 환경 논의가 선행돼야 해 이번 범위 밖), `[STOCK-RETENTION-DUPLICATE-CONFIRM-FALSE-SIGNAL]`(같은 주문 confirm 동시 재호출이 유니크 제약 위반으로 남기는 가짜 재고 미회수 신호 — 정상 중복 차단인데도 확정 실패와 같은 신호로 묶여 관측 정확성 저하)). 이전: 2026-07-27 (ADMIN-VISIBILITY discuss — 섹션 D 신설 후 2건 등재: `[PG-WORKER-SPAN-ORDER-ID]`(재시도 워커가 추적 문맥을 복원만 해 원격 구간에 속성이 조용히 버려짐 — 주문 단위 추적 검색 불가), `[PG-ZOMBIE-TIMEOUT-BACKOFF-OVERLAP]`(좀비 타임아웃 60s < 최대 재시도 백오프 67.5s 겹침 — 벤더 호출 없는 발행 행 발생, `[PG-RETRY-BACKOFF-OFF-BY-ONE]` 와 함께 판단 필요)). 이전: 2026-07-11 (DLQ-QUARANTINE-RECOVERY ship — TQ-1 혼합 축소: `events.confirmed.dlq` 관리자 수동 재주입 완료분 제거, 조건부 자동 재시도 잔여 보존 / TQ-2 혼합 축소: QUARANTINED 관리자 안전 실패 종결(FAILED 전이·토큰 조건부 보상·CAS·audit) 완료분 제거, 격리 DONE 복구 잔여 보존). 이전: 2026-07-03 (DOCS-CONSISTENCY-OVERHAUL doc-review 라운드 1 수정 1차 — 코드 확인 필요 항목 신규 등재: `[PG-RETRY-BACKOFF-OFF-BY-ONE]`(`RetryPolicy` javadoc 의도(2s/6s/18s/54s)와 호출부 `computeBackoff(nextAttempt)`(`PgVendorCallService.java:190-192`) 어긋남 — 런타임 첫 재시도 대기 ~6s, 위키는 런타임 기준으로 정정). 이전: 2026-07-02 (DOCS-CONSISTENCY-OVERHAUL Task 8 — 대장 정정: ✅ 완료+archive 경로 확인된 항목 24건 전체 삭제(a) — DIAGNOSIS §4.1.3 예비 판정 22건 그대로 적용 + 판정표 누락분 2건(TC-13-FOLLOW-7/TC-9, 동일 패턴 ✅완료+archive 경로 확인으로 동일 판정 적용, 사유는 PLAN.md Task 8 완료 결과에 기록) + 혼합 항목(b) 3건(TC-13-FOLLOW-6/[CLEANUP-BATCH-B 후속]/TC-3) 해소분 문장만 제거·잔여 한계 보존 + "토픽 묶음 계획"·"## 완료" 섹션 전체 삭제(`docs/archive/README.md` 완전 중복) + TC-7 "한도 초과 시 종결" stale 서술 정정(`incrementRetryOrFail` 프로덕션 호출처 0 반영) + 코드 확인 필요 항목 3건 신규 등재(코드 수정 없음, 등재만)). 이전: 2026-07-01 (context-update — TC-13-FOLLOW-3/4 알람 rule 해소 반영: ALERTING-RULES 6/27 coordinator·guard-skip 그룹 + FAULT-INJECTION 6/30 availability 그룹).
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

> 아래 3건은 `DOCS-CONSISTENCY-OVERHAUL` 진단(§4.5) 중 문서 정정 범위를 벗어난 코드측 발견이다. 데드 코드/회귀 여부 판정은 사용자 확인이 필요하며, 이 항목들은 확인 필요성만 등재한다.

#### [PAYMENT-OUTBOX-INFLIGHT-UNUSED] — REQUIRES_NEW 선점 경로 프로덕션 미사용

- **현황**: `PaymentOutboxUseCase.claimToInFlight`(REQUIRES_NEW 선점)·`incrementRetryOrFail` 프로덕션 호출처 0 — `OutboxWorker` 는 `recoverTimedOutInFlightRecords`/`findPendingBatch` 만 호출한다. 실제 발행 실패 경로(`OutboxRelayService.relay` 단일 TX)는 롤백으로 PENDING 복귀 후 `OutboxWorker` 5초 주기 배치가 재픽업 — retryCount 증가·backoff 없이 무백오프로 재시도된다.
- **영향**: `nextRetryAt` 기반 backoff 설계가 이 경로에서는 실효되지 않는다 — 벤더/브로커 부하 시 재시도 폭주 가능성. IN_FLIGHT 타임아웃 회수(`recoverTimedOutInFlightRecords`)는 워커 비정상 종료 등 드문 경로의 보조 안전장치로만 유효.
- **처방**: 단일 TX 즉시 재시도가 충분하다는 의도된 단순화인지, REQUIRES_NEW 선점을 실제로 연결했어야 하는 미완성 회귀인지 코드/설계 이력 확인 필요. 데드 코드 판정(제거 여부)은 사용자 확인 필요.

#### [STRUCTURED-LOGGING-MASKING-GAP] — 민감정보 마스킹 메커니즘 대체 없이 소실 추정

- **현황**: `MaskingPatternLayout`(로그 라인 민감정보 마스킹) 클래스가 코드베이스 전건 grep 0 — `logback-spring.xml` 에도 커스텀 `PatternLayout` 서브클래스나 마스킹 설정이 없다. 위키 `structured-logging.md` 는 이 메커니즘을 여전히 현재형으로 서술하나(진단 §4.5.5), 실제로는 대체 구현 없이 사라진 것으로 보인다.
- **영향**: 로그에 민감 필드가 마스킹 없이 그대로 남는 잠재 회귀 — 도메인 리스크 우선순위 높음.
- **처방**: 의도적 제거(다른 계층에서 마스킹을 대체 수행 등)인지 회귀인지 코드 확인 필요. 대체 메커니즘 부재가 확정되면 별도 토픽으로 마스킹 재도입 검토.

#### [PAYMENT-STATUS-TRIGGER-DETECT-DEAD-BRANCH] — 콜스택 기반 trigger 자동 감지가 존재하지 않는 클래스명 참조

- **현황**: `PaymentStatusMetricsAspect.detectTriggerFromCallStack()` 이 `className.contains("PaymentConfirmService")`/`"PaymentRecoverService"`/`"PaymentExpirationService"` 3개 분기로 trigger 를 판정한다. confirm 분기는 스택의 실제 구현 클래스명이 `OutboxAsyncConfirmService`(인터페이스명 `PaymentConfirmService` 매칭과 불일치)라 매칭 불발, recovery 분기는 대상 클래스(`PaymentRecoverService`)가 전체 삭제돼 매칭 불발 — 두 분기 모두 항상 미스매치. expiration 분기만 `PaymentExpirationServiceImpl` 이 `"PaymentExpirationService"` 부분 문자열을 포함해 정상 매치된다. confirm/recovery 미매치 시 최종 폴백은 `unknown`(`PaymentStatusMetricsAspect.java:93`).
- **영향**: confirm/recovery 트리거로 발생하는 상태 전이 메트릭은 라벨이 전부 `unknown` 으로 뭉개져 trigger 구분이 무의미해진다. expiration 전이만 라벨이 정확하다.
- **처방**: confirm/recovery 매칭 대상 클래스명을 현재 클래스명(`OutboxAsyncConfirmService` 등)으로 갱신할지, 이 자동 감지 로직 자체를 폐기할지 코드 확인 필요.

#### [PG-RETRY-BACKOFF-OFF-BY-ONE] — pg-service 재시도 백오프 off-by-one 의심

- **현황**: `RetryPolicy` javadoc 의 의도한 값(attempt=1 → 기준 2s, attempt=2 → 6s, attempt=3 → 18s, attempt=4 → 54s)과 실제 호출부가 어긋난다 — `PgVendorCallService.insertRetryOutbox`(`PgVendorCallService.java:190-192`)가 `computeBackoff(attempt)` 가 아니라 `computeBackoff(nextAttempt)`(= 실패한 attempt + 1)를 호출해, 런타임 첫 재시도 대기가 의도된 ~2s 가 아니라 ~6s 부터 시작한다(이후 18s, 54s 로 한 단계씩 밀림).
- **영향**: 재시도 정책의 실제 대기 시간이 설계 문서화된 의도보다 한 단계 길다 — 위키 `pg-confirm-flow.md` 는 이번 수정에서 런타임 실측값(6s/18s/54s) 기준으로 정정했으나, 애초 설계 의도(2s/6s/18s/54s 4단)가 맞다면 호출부(`computeBackoff(nextAttempt)` → `computeBackoff(attempt)`)를 정정해야 한다.
- **처방**: 의도된 정책이 무엇인지(런타임 값 유지 vs javadoc 값 복원) 확인 필요 — 코드 수정은 이 항목 등재 범위 밖.

### D. ADMIN-VISIBILITY discuss 발견 (관리자 화면 가시성 확충, 2026-07-27)

> 아래 2건은 `ADMIN-VISIBILITY` discuss 단계에서 발견됐으나 해당 토픽(관측 전용 화면 추가) 범위를 벗어난다. 등재만 하고 코드는 건드리지 않는다.

#### [PG-WORKER-SPAN-ORDER-ID] — 재시도 워커에 자체 추적 구간이 없어 주문 단위 추적 검색 불가

- **현황**: 추적 구간 속성에 주문 식별자가 없어 특정 주문의 재시도를 검색으로 모을 수 없다. 재시도 워커는 저장된 추적 문맥을 **복원만** 하며(`PgInboxPollingWorker.java:182` — `restoreContext(...).makeCurrent()`), 복원된 구간은 propagator 가 만든 원격 구간이라 기록 대상이 아니다(`TraceparentExtractor.restoreContext` → `PROPAGATOR.extract`). 여기에 `Span.current().setAttribute(...)` 를 호출하면 예외 없이 조용히 버려진다. 저장소 전체에 자체 추적 구간을 생성하는 코드(`spanBuilder` / `@NewSpan` / `setAttribute`)가 한 곳도 없다.
- **영향**: 재시도 원인 추적 시 시간순으로 로그를 뒤져 수동 식별해야 한다(라이브 실측에서 실제로 그렇게 했다). 관리자 화면에 시도 이력이 노출되면 급한 필요는 해소되지만, 추적 백엔드에서 주문 단위로 모아 보는 수단은 여전히 없다.
- **처방**: 워커가 자체 추적 구간을 만들고 주문 식별자를 속성으로 부여하는 방식으로 별 토픽에서 다룬다. 순진하게 속성만 추가하면 테스트도 통과하는데 백엔드엔 아무것도 남지 않으므로, 구간 생성이 함께 가야 한다.

#### [PG-ZOMBIE-TIMEOUT-BACKOFF-OVERLAP] — 좀비 회수 타임아웃이 최대 재시도 백오프보다 짧아 겹친다

- **현황**: `in-progress-timeout-ms: 60000`(`pg-service/src/main/resources/application.yml:96`)인데 마지막 재시도의 백오프는 기준 54초에 지터 ±25% 로 40.5~67.5초 범위다(`RetryPolicy.java:29-33,63-68`). 백오프가 60초를 넘는 경우 좀비 폴러가 예약된 재시도보다 먼저 깨어나 벤더를 다시 호출한다. `resolveAttempt` 는 항상 `inbox.getAttempt()` 를 읽으므로(`PgInboxProcessor.java:95,135`) 같은 회차로 호출되고, 재시도 한도가 소진된 상태면 그 자리에서 격리로 전이된다. 이후 원래 예약돼 있던 재시도 행은 relay 가 inbox 상태를 보지 않고 `available_at <= now` 만으로 발행해 `processed_at` 을 채우며(`PgOutboxRelayService.java:59-79`), 소비 측은 종결 상태를 발견하고 흔적 없이 건너뛴다(`PgInboxImmediateWorker.java:159-163` TERMINAL_SKIP).
- **영향**: 실제 벤더 호출이 없는 발행 행이 남아, outbox 를 이력으로 읽는 화면이 "종결 이후에 시도가 하나 더 있었다"는 유령 항목을 시간 역전된 순서로 보여줄 수 있다. `ADMIN-VISIBILITY` 는 이력 조립 단계에서 종결 시각 기준 라벨링으로 **표시를 교정**하지만 겹침 자체는 남는다. 관련: `[PG-RETRY-BACKOFF-OFF-BY-ONE]` 의 off-by-one 을 정정하면 최대 백오프가 18초로 내려가 겹침이 사라진다.
- **처방**: 좀비 타임아웃을 최대 백오프보다 여유 있게 올리는 방향과 백오프 off-by-one 정정으로 최대값을 낮추는 방향 중 어느 쪽이 의도인지 확인 필요. 두 항목을 함께 판단해야 한다.

### E. LIVE-DRILL-FORMALIZATION 후속 (라이브 실측 체계 정식화, 2026-07-29)

> 아래 3건은 `LIVE-DRILL-FORMALIZATION` 에서 의도적으로 범위 밖으로 뺀 후속이다. 앞 2건 근거: CONCERNS.md L-18. 마지막 1건은 Task 10 스크립트 작성 중 발견.

#### [FAKE-PG-BOOT-ENV-GUARD] — 모의 벤더 로드 시 환경 조합 검사 가드 부재

- **현황**: 모의 벤더가 `pg.gateway.type=fake` 로 로드될 때 그 환경이 허용된 조합인지 검사하는 코드가 없다. `warnActivation()`(`FakePgGatewayStrategy.java:132-142`)은 경고 배너 로그만 남기고 기동을 막지 않는다.
- **영향**: 스모크 구동용 환경변수(`pg-service/src/main/resources/application-docker.yml:21` `${PG_GATEWAY_TYPE:toss}`)가 배포 파이프라인에 남으면 실 승인 없이 결제가 완료된다(CONCERNS.md L-18).
- **처방**: 부팅 시 허용된 환경 조합(프로파일/환경변수 조합)인지 검사해 아니면 기동을 멈추는 가드 도입. 이번 작업에서 하지 않은 이유 — 어떤 환경을 정상으로 볼지 먼저 정해야 하고, 그것은 배포 환경 논의라 범위를 넘는다.

#### [STOCK-RETENTION-DUPLICATE-CONFIRM-FALSE-SIGNAL] — 승인 동시 재호출이 남기는 가짜 재고 미회수 신호

- **현황**: 같은 주문으로 confirm 을 **동시에** 두 번 보내면, 둘 다 `PaymentTransactionCoordinator.decrementStock`(`PaymentTransactionCoordinator.java:42-55`)의 Redis atomic DECR 에서 이미 처리됨(`ALREADY_DONE`) 판정을 통과해 SUCCESS 로 흐른다. 이후 각자 `executeConfirmTx`(`PaymentTransactionCoordinator.java:74-85`) 안에서 `PaymentOutboxUseCase.createPendingRecord`(`PaymentOutboxUseCase.java:30-34`)로 발행 행을 만들려 하고, `payment_outbox.order_id` 유니크 제약(`uk_payment_outbox_order_id`, `V1__payment_schema.sql:70`)에 진 쪽이 예외를 맞는다. 그 예외는 `RuntimeException` 이라 `OutboxAsyncConfirmService.executeConfirmTxWithStockRetention` 의 `catch (RuntimeException txException)`(`OutboxAsyncConfirmService.java:97`)이 원인을 구분하지 않고 받아, `stockRetentionMetrics.record()` + `STOCK_RETENTION_UNRECOVERED` 로그(`OutboxAsyncConfirmService.java:98-102`)로 재고 미회수 신호를 남긴다.
- **영향**: 이긴 쪽 요청은 정상 처리돼 실제 재고 누수는 없는데도, 진 쪽의 유니크 제약 위반이 같은 신호로 묶여 재고 미회수 경보가 남는다. 그 catch 는 원래 "확정 실패 시 재고를 되돌리지 않는다"는 정책을 가시화하려는 것인데, 중복 차단으로 진 요청까지 같은 신호로 묶어 관측 정확성을 떨어뜨린다. 순차 재호출은 첫 호출에서 이미 outbox 행이 만들어져 있어 예외 없이 흡수되므로 해당하지 않는다 — 동시 요청일 때만 발생한다.
- **처방**: 유니크 제약 위반(예: `DataIntegrityViolationException`)과 그 외 원인을 구분해, 전자는 재고 미회수 신호에서 제외하는 방향. 별도 토픽 여지.

#### [CHECKOUT-DUPLICATE-FLAG-DROPPED] — 체크아웃 응답 DTO 에서 중복 여부 필드가 빠짐

- **현황**: `CheckoutResult`(application 계층)는 `isDuplicate` 를 정확히 채운다(`PaymentCheckoutServiceImpl.checkout`). 하지만 `PaymentPresentationMapper.toCheckoutResponse`(`PaymentPresentationMapper.java:30-35`)가 이를 `CheckoutResponse`(presentation DTO)로 옮기지 않아, 응답 JSON에는 `orderId`/`totalAmount`만 남고 중복 여부가 사라진다. 유일하게 밖으로 드러나는 신호는 `PaymentController.checkout`(`PaymentController.java:45-49`)이 `isDuplicate()` 로 분기하는 HTTP 상태 코드(신규 201 / 중복 200)뿐이다.
- **영향**: 클라이언트가 응답 바디만 보고 중복 여부를 판단할 방법이 없다 — 상태 코드까지 봐야 한다. 라이브 실측 장면 7(중복 결제 차단, `references/scenarios.md`)의 판정 기준도 이 상태 코드로 정정했다.
- **처방**: `CheckoutResponse` 에 `duplicate` 필드를 추가하고 매퍼에서 채울지, 상태 코드만으로 충분하다고 볼지 결정 필요. 별도 토픽 여지.

### F. AGENT-CONTEXT-OVERHAUL 후속 (에이전트 지침 컨텍스트 정비, 2026-07-31)

> 아래 4건은 `AGENT-CONTEXT-OVERHAUL` 에서 의도적으로 범위 밖으로 뺀 후속(앞 3건)과 Task 7 실행 중 발견된 낡은 참조(마지막 1건)다.

#### [REVIEWER-EFFORT-DOWNGRADE-RECHECK] — reviewer effort 하향 원복 조건 재확인

- **현황**: `AGENT-CONTEXT-OVERHAUL` Task 3 에서 `.claude/agents/reviewer.md` frontmatter `effort` 를 `xhigh` 에서 `high` 로 낮췄다 — 근거와 사각·원복 조건은 `CONCERNS.md` C-11 에 등재됨.
- **처방**: 적용 후 첫 도메인 인접 토픽(domain-expert 배차 대상 토픽)에서, Reviewer 가 놓쳤던 critical·major 도메인 finding 이 Domain Expert 사후 배석에서 새로 발견되는지 대조 확인한다. 발견되면 C-11 원복 조건에 따라 `effort: high` 를 `xhigh` 로 즉시 복귀.

#### [AGENT-DOCS-STATIC-ANALYSIS] — `var`·`@Data`·null 반환·`catch (Exception)`·try 재할당 정적 강제 도입

- **현황**: 다섯 규칙(`var` 키워드 금지, `@Data` 금지, 공개 유스케이스·포트의 null 반환 금지, `catch (Exception)` swallow 금지, try 블록 내 외부 변수 재할당 금지) 모두 `docs/context/conventions/code-style.md` 에 명문화돼 있으나, 이를 자동 강제하는 checkstyle 규칙이나 ArchUnit 테스트가 없다 — `code-ready.md` convention 섹션의 리뷰어 수동 판정이 현재 유일한 검증 수단이다.
- **영향**: 리뷰 라운드에서 놓치면 위반이 그대로 병합될 수 있다.
- **처방**: checkstyle 커스텀 규칙 또는 ArchUnit 룰로 다섯 항목을 빌드 단계에서 자동 검출하도록 도입. 별도 토픽 여지.

#### [AGENT-DOCS-CHECK-SCRIPT-CI] — 지침 문서 검사 스크립트의 CI 편입

- **현황**: `AGENT-CONTEXT-OVERHAUL` Task 16·17 에서 신설하는 `scripts/check-agent-docs.py`(참조 무결성·frontmatter·체크리스트 참조·중복 규칙·Mermaid 금지 문자·고아 문서 판정)는 종료 코드를 판정 결과와 무관하게 0으로 고정한 정보 제공용 스크립트다.
- **처방**: 도입 후 몇 차례 실행에서 오탐(false positive)이 잦아든 뒤, CI 게이트로 편입해 종료 코드로 판정을 강제할지 결정한다.

#### [CODE-READY-HANDLEUNKNOWNFAILURE-STALE] — convention 섹션의 낡은 메서드 참조

- **현황**: `.claude/skills/_shared/checklists/code-ready.md` convention 섹션의 "`catch (Exception e)` 없음 (있다면 `handleUnknownFailure` 경유)" 항목이 가리키는 `handleUnknownFailure` 메서드는 코드베이스 전체에 존재하지 않는다 — `outbox-only-refactor` 리팩터로 `PaymentFailureUseCase.handleUnknownFailure()` 가 삭제됐고(grep 결과 프로덕션 코드 0건, archive 문서에만 잔존), `AGENT-CONTEXT-OVERHAUL` Task 7 실행 중 발견됐다.
- **영향**: 리뷰어가 이 항목을 문자 그대로 적용하면 존재하지 않는 경유 메서드를 찾게 된다.
- **처방**: 항목을 현재 `error-logging.md` 규칙("잡으면 LogFmt.error + 재throw 또는 명시적 fallback")으로 교체. 별도 토픽 여지 없이 다음 체크리스트 편집 시 바로 반영 가능한 소규모 정정.

#### [CONFIRM-PAYMENT-FLOW-MERMAID-ARROW-CLEANUP] — 기존 다이어그램의 Mermaid 금지 문자

- **현황**: `AGENT-CONTEXT-OVERHAUL` Task 17 에서 신설한 `scripts/check-agent-docs.py` Mermaid 금지 문자 판정을 돌린 결과, `docs/context/CONFIRM-FLOW.md`(10건) · `docs/context/PAYMENT-FLOW.md`(30건) 의 기존 다이어그램이 노드/엣지 라벨 내부에 유니코드 화살표(`→`, 예: `READY → IN_PROGRESS`)를 상태 전이 서술에 쓰고 있다 — `writing-visuals.md` "Mermaid 노드 라벨 금지 문자" 절 위반.
- **영향**: 일부 렌더러에서 라벨 내부 화살표가 깨질 수 있다. 정보 제공용 판정이라 스크립트가 작업을 막지는 않는다.
- **처방**: 두 문서의 해당 라벨을 `->` ASCII 화살표로 교체. 다이어그램 수가 많아(40건) 별도 소규모 정리 작업으로 처리.

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
