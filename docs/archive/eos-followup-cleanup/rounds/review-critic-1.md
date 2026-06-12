# review-critic-1

**Topic**: EOS-FOLLOWUP-CLEANUP
**Round**: 1
**Persona**: Critic

## Reasoning
결정론적 백본(`./gradlew test`)이 BUILD SUCCESSFUL 로 전 모듈 통과하고, 14 태스크 A-1~E-5 의 핵심 변경(enum 가드 분리·교차 불변식 테스트, TM qualifier 명시, deprecated API 교체, 양 서비스 청소 스케줄러, pg traceparent 복원)이 모두 체크리스트 게이트를 만족한다. 호출자가 의심한 두 항목(payment/product `deleteExpired` 바인딩 차이, product worker 의 `Instant.now()` 직접 호출)은 회귀가 아닌 기존 서비스별 아키텍처 차이로 확인됐고, LIMIT 은 양쪽 모두 named-param 으로 안전 바인딩되어 SQL 인젝션·정수 바인딩 결함이 없다. critical/major finding 없음 — **pass**.

## Checklist judgement
- task execution: RED→GREEN 커밋 페어 14 태스크 전반 존재, 메시지 `test:`/`feat:` 포맷 준수, STATE.md stage=review 전환됨 → **yes**
- test gate: 전체 `./gradlew test` BUILD SUCCESSFUL, 신규 로직(enum 분리·deleteExpired·worker·extractor)에 단위/통합 테스트 + `@ParameterizedTest @EnumSource` 교차 불변식 → **yes**
- convention: `var` 미사용, `catch(Exception)` 없음(RuntimeException 한정 포획), LogFmt 사용, Optional 반환, OTel import 가 infra/trace 에만 격리 → **yes**
- execution discipline: 범위 밖 수정 없음, 분석 마비 없음 → **yes**
- final task only: STATE.md stage=review, `.continue-here.md` 흔적 없음 → **yes**

## Findings
- (minor) PRODUCT-TIME-ABSTRACTION: product `DedupeCleanupWorker.cleanup()` 가 `Instant.now()` 직접 호출(DedupeCleanupWorker.java:64). payment 측은 `LocalDateTimeProvider.nowInstant()` 주입. evidence: product-service 전체에 `LocalDateTimeProvider`/Clock 추상화가 부재하고 기존 `StockCommitConsumer.java:83` 도 동일하게 `Instant.now()` 직접 호출 — 서비스 내부 패턴과는 정합. 시간 추상화 도입은 별도 후속(서비스 전역) 사안.
- (minor) SCHEDULER-ENABLED-GATE: 양 서비스 application.yml 에 `scheduler.enabled=true` 미설정. SchedulerConfig 가 `@ConditionalOnProperty(havingValue="true")` 이므로 기본 프로파일에서 cleanup worker 미기동. evidence: pg 의 기존 PgInboxPollingWorker 와 동일 게이트 패턴 — 환경/프로파일 주입 전제의 by-design.

## JSON
```json
{
  "stage": "code",
  "persona": "critic",
  "round": 1,
  "task_id": null,
  "decision": "pass",
  "reason_summary": "전체 gradlew test 통과, 14 태스크 게이트 충족. deleteExpired LIMIT 은 양 서비스 named-param 안전 바인딩, 레이어 격리·컨벤션 준수. 잔여는 minor 2건(서비스별 기존 패턴 차이)뿐.",
  "checklist": {
    "source": "_shared/checklists/code-ready.md",
    "items": [
      {"section": "test gate", "item": "전체 ./gradlew test 통과", "status": "yes", "evidence": "gradlew test → BUILD SUCCESSFUL (전 모듈 UP-TO-DATE/pass)"},
      {"section": "test gate", "item": "새 state machine 전이가 @ParameterizedTest @EnumSource로 커버", "status": "yes", "evidence": "PaymentEventStatusCrossInvariantTest.java:14-22 @EnumSource 두 가드 동조 단언"},
      {"section": "test gate", "item": "신규 business logic 테스트 커버리지", "status": "yes", "evidence": "JdbcPaymentEventDedupeStoreCleanupTest / JdbcEventDedupeStoreCleanupTest / DedupeCleanupWorkerTest x2 / TraceparentExtractorTest / PgInboxPollingWorkerTraceparentTest / PgInboxTraceparentIntegrationTest 신설"},
      {"section": "task execution", "item": "test/feat 커밋 포맷 + STATE.md stage 전환", "status": "yes", "evidence": "git log RED/GREEN 페어 14 태스크, STATE.md L3 stage=review"},
      {"section": "convention", "item": "catch(Exception e) 없음", "status": "yes", "evidence": "변경 diff grep: catch(Exception 0건, worker 들은 catch(RuntimeException e) 한정"},
      {"section": "convention", "item": "var 키워드 미사용", "status": "yes", "evidence": "변경 *.java diff grep '\\bvar\\b' 0건"},
      {"section": "convention", "item": "레이어 경계(application→infra 역의존 회피)", "status": "yes", "evidence": "io.opentelemetry import 가 pg infrastructure/trace 와 기존 core/config 에만 존재; PgInboxRepository 포트는 불투명 String(findStoredTraceparent) 만 노출 (PgInboxRepository.java:52)"},
      {"section": "convention", "item": "deleteExpired LIMIT 안전 바인딩", "status": "yes", "evidence": "payment JdbcPaymentEventDedupeStore.java:71-75 / product JdbcEventDedupeStore.java:104-108 모두 NamedParameterJdbcTemplate :batchSize 바인딩"},
      {"section": "task execution", "item": "구 enum 메서드 잔여 참조 없음", "status": "yes", "evidence": "grep isCompensatableByFailureHandler/isEosConsumerEntryAllowed in main → 0건; 두 호출처 canApplyConfirmResult(UseCase:113)/canCompensateStock(Coordinator:141) 로 교체"}
    ],
    "total": 18,
    "passed": 18,
    "failed": 0,
    "not_applicable": 0
  },
  "scores": {
    "correctness": 0.93,
    "conventions": 0.95,
    "discipline": 0.94,
    "test-coverage": 0.92,
    "domain": 0.90,
    "mean": 0.928
  },
  "findings": [
    {
      "severity": "minor",
      "checklist_item": "신규 로깅/시간 소스 컨벤션 일관성",
      "location": "product-service/.../infrastructure/scheduler/DedupeCleanupWorker.java:64",
      "problem": "product worker 가 Instant.now() 를 직접 호출 — payment worker 는 LocalDateTimeProvider 주입.",
      "evidence": "product-service main 전체에 LocalDateTimeProvider/Clock 추상화 부재, 기존 StockCommitConsumer.java:83 도 Instant.now() 직접 호출 — 서비스 내부 패턴과 정합.",
      "suggestion": "서비스 전역 시간 추상화 도입 시 함께 정렬. 현 라운드 차단 사유 아님."
    },
    {
      "severity": "minor",
      "checklist_item": "신규 컴포넌트 활성화 조건 명시성",
      "location": "payment-service/.../application.yml:131, product-service/.../application.yml:70",
      "problem": "scheduler.enabled=true 미설정으로 기본 프로파일에서 cleanup worker 미기동.",
      "evidence": "SchedulerConfig @ConditionalOnProperty(havingValue=\"true\"); 기존 PgInboxPollingWorker 와 동일 게이트 패턴.",
      "suggestion": "운영 프로파일에서 scheduler.enabled 주입 전제임을 PLAN/배포 노트에 명기."
    }
  ],
  "previous_round_ref": null,
  "delta": null,
  "unstuck_suggestion": null
}
```
