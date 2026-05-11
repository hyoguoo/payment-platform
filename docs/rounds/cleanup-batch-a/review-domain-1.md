# review-domain-1

**Topic**: CLEANUP-BATCH-A
**Round**: 1
**Persona**: Domain Expert

## Reasoning

본 토픽은 결제 도메인 결정을 동반하지 않는 청소 4건 묶음(§1.1 dead service / §1.2 builder 통일 / §1.3 Flyway 분리 / §1.4 Retryable 매핑)이며, 가장 위험한 §1.2 (PG-CONFIRM-LISTENER-SPLIT 봉인 직후의 PgInbox/PgOutbox 도메인 POJO 표면 교체) 가 도메인 메서드 / 사전 가드 / 어댑터 가드를 모두 보존하고 호출처 컴파일도 단순화한 것을 코드 교차 검증으로 확인했다. 도메인 측면의 critical / major 리스크 0건 — pass.

## Domain risk checklist

- [yes] **paymentKey / orderId / 카드번호 등 plaintext 로그 노출 없음**
  - `PaymentExceptionHandler.handleProductServiceRetryable` / `handleUserServiceRetryable` 가 `LogFmt.warn(log, LogDomain.PAYMENT, EventType.EXCEPTION, e::getMessage)` 만 사용, message 는 `PaymentErrorCode` 의 정적 문구 (E03031/E03032) 로 PII 0. `PgInbox` JavaDoc 변경분 / 호출처 변경분 모두 신규 로그 추가 없음.
- [yes] **보상 / 취소 로직 멱등성 가드 존재**
  - 본 토픽은 보상/취소 경로 변경 0. `PgOutbox.create` 의 `Long id` 제거가 `@GeneratedValue(IDENTITY)` 채움 정책과 정합 — INSERT 후 `saved.getId()` 가 AUTO_INCREMENT 값을 받음 (DuplicateApprovalHandler.enqueueOutbox / PgVendorCallService.handleSuccess 등 모두 동일 패턴 유지). claimToInFlight / processedAt 등 outbox 멱등성 로직 미변경.
- [yes] **PG "이미 처리됨" 응답 맹목 수용 없음**
  - 본 토픽 비범위. `DuplicateApprovalHandler` 의 `handleDbAbsent*` 경로 — `PgInbox.createDirectInProgress` / `PgInbox.of` 7-arg 만 시그니처 유지하면서 builder 내부만 교체됨 (호출 인자 의미 변경 0). `PgFinalConfirmationGate` / `PgTerminalReemitService` 도 동일.
- [yes] **상태 전이 불변식 위반 없음** (SUCCESS → FAIL 금지 등)
  - `PgInbox.markInProgress/markApproved/markFailed/markQuarantined` 4종 모두 사전 가드 보존 (`if (this.status != PgInboxStatus.IN_PROGRESS) throw IllegalStateException` 등). `markQuarantined` 의 `isTerminal()` 사전 가드도 유지 (PgInbox.java:410, 429). `createDirectTerminal` 의 `!terminalStatus.isTerminal()` 가드 (PgInbox.java:196) 보존 + 어댑터 가드 (PgInboxRepositoryImpl.java:150) 그대로.
- [yes] **race window 경로 락 / TX 격리 고려**
  - 본 토픽은 락 / TX 정책 변경 0. PG-CONFIRM-LISTENER-SPLIT 봉인의 `FOR UPDATE SKIP LOCKED` (PENDING + IN_PROGRESS) / `claimToInFlight CAS` 모두 미접촉. AFTER_COMMIT 발행 등록 시점에 `publishEvent` 호출 contract 변경 0.

## 도메인 관점 추가 검토

1. **PgInbox.createDirectTerminal 도메인 가드 vs 어댑터 가드 이중화**
   - 위치: `pg-service/src/main/java/com/hyoguoo/paymentplatform/pg/domain/PgInbox.java:194-213` + `pg-service/src/main/java/com/hyoguoo/paymentplatform/pg/infrastructure/repository/PgInboxRepositoryImpl.java:148-157`
   - 검증: 어댑터 `transitDirectToTerminal` 가 reasonCode 파라미터 부재로 도메인 factory(`createDirectTerminal`) 우회, `PgInbox.of` 7-arg 직접 호출 — 이 경로의 도메인 측 isTerminal 가드는 어댑터가 직접 `if (!terminalStatus.isTerminal())` 로 박아 둠. CBA-8 PgInbox.java:185-186 JavaDoc 에 "도메인 가드 isTerminal() 은 test 픽스처 이중화 목적 — main 보호는 어댑터 가드(PgInboxRepositoryImpl.java:150) 가 담당" 명시. 본 라운드 변경분에서 이 분리 보존됨.

2. **PgOutbox.create dead parameter 제거 후 self-loop retry / DLQ 헤더 의미 보존**
   - 위치: `pg-service/src/main/java/com/hyoguoo/paymentplatform/pg/application/service/PgVendorCallService.java:209-240`
   - 검증: `insertRetryOutbox` / `insertDlqOutbox` 모두 `buildAttemptHeader(nextAttempt|attempt)` 가 headersJson 으로 진입 → `PgOutbox.createWithAvailableAt(topic, key, payload, headersJson, availableAt)` / `PgOutbox.create(topic, key, payload, headersJson)` 4~5 인자 시그니처가 이전의 `(null, topic, key, payload, headersJson[, availableAt])` 와 동일 의미. attempt 헤더 / availableAt backoff / DLQ 토픽 분기(`PgTopics.COMMANDS_CONFIRM_DLQ`) 모두 변경 0. `RetryPolicy.shouldRetry(attempt)` 분기점도 그대로.

3. **PgInbox.create 4 오버로드 — main 호출 0건의 의미 정합**
   - 위치: `pg-service/src/main/java/com/hyoguoo/paymentplatform/pg/domain/PgInbox.java:63-153`
   - 검증: `grep -rn "PgInbox.create\b" pg-service/src/main` 결과 0 (factory `PgInbox.createDirectInProgress` / `PgInbox.of` 만 main 활성). main 정상 PENDING 신설 경로는 `PgInboxRepositoryImpl.insertPending` 의 native INSERT (어댑터가 ORM Entity 우회) — 따라서 `PgInbox.create*` 의 builder 본문 변경은 main 동작에 영향 0이며 test 픽스처 회귀만 cover. JavaDoc 에 "main 호출처 0건 (test 픽스처 전용) — insertPending native INSERT 가 정상 경로" 명시. 도메인 리스크 0.

4. **Retryable 매핑 — 클라이언트 backoff 시그널의 결제 의미**
   - 위치: `payment-service/src/main/java/com/hyoguoo/paymentplatform/payment/exception/common/PaymentExceptionHandler.java:101-117`
   - 검증: 503 + `Retry-After: 5` 가 단일 retryable 시그널로 통합되며, 429 (rate-limit) vs 503 (서비스 다운) 의 구분이 ErrorDecoder 단에서 손실되는 trade-off 는 TODOS `[NET-RETRY]` 로 등재 완료. 결제 흐름 영향: cross-service product/user 조회 일시 장애 시 클라이언트 (브라우저 fetch) 가 무한 자동 재시도 없이 응답 코드 + 5초 명시 backoff 만 받음. payment 도메인 자체의 confirm TX / 보상 멱등성 영향 0 (이 두 예외는 controller advice 단에서 잡혀 trans 외부 처리, paymentKey / orderId 등은 응답 body 에 노출되지 않음).

5. **AMOUNT_MISMATCH 양방향 방어 미접촉**
   - 위치: `docs/context/CONFIRM-FLOW.md` §7 + §13
   - 검증: CONFIRM-FLOW.md §7 line 254 의 "pg 측 방어 (1단)" 서술이 `PgInboxAmountService` 잔재 참조에서 `AmountConverter.fromBigDecimalStrict — PgInboxRepositoryImpl.insertPending 경로 + DuplicateApprovalHandler.amountMismatch 경로` 로 정정됨. TC-16 dead service 제거가 양방향 방어의 실제 1단 주체 (`AmountConverter.fromBigDecimalStrict`) 에 영향 0. payment-service 측 `isAmountMismatch` 도 변경 0.

6. **Flyway profile 분리 — 신규 DB 가정 외 회귀 가능성**
   - 위치: `product-service/src/main/resources/application-docker.yml:11-12` + `user-service/src/main/resources/application-docker.yml:11-12` + 통합 테스트 `product-service/src/test/java/.../FlywayDockerProfileTest.java`
   - 검증: docker profile 에서 `spring.flyway.locations: classpath:db/schema` override 적용. Testcontainers 통합 테스트가 `flyway_schema_history row count == 1` + `product row count == 0` 으로 seed 차단 회귀 자동 보호. 도메인 리스크: 학습용 docker named volume 재사용 시 missing migration fail 가능성은 STACK.md 의 3-step 운영 가이드로 흡수 완료. 결제 도메인 정합성 자체에는 영향 0.

## Findings

없음 (도메인 critical / major).

(참고용 minor — 판정에 영향 없음)
- `PgInbox.create` 4 오버로드는 main 호출 0건으로 test 픽스처 전용. 토픽 §1.2 표 + JavaDoc 명시 + PgInboxTest 회귀 cover 됨. 후속 토픽에서 test 도메인 객체 헬퍼 통합 시 정리 후보로만 인지하면 충분.
- 429/503 정보 손실 trade-off 는 `[NET-RETRY]` 후속 토픽으로 등재 완료. 본 토픽 범위 내 도메인 리스크 0.

## JSON

```json
{
  "stage": "code",
  "persona": "domain-expert",
  "round": 1,
  "task_id": null,

  "decision": "pass",
  "reason_summary": "도메인 critical/major 0. PG-CONFIRM-LISTENER-SPLIT 봉인 상태 머신 + 어댑터 가드 + 사전 가드 모두 보존. PgOutbox.create dead-param 제거가 self-loop retry attempt 헤더 / DLQ 분기 / availableAt backoff 의미와 정합. Retryable 503+Retry-After:5 매핑은 PII 노출 0 + 429 손실 trade-off는 TODOS 등재 완료.",

  "checklist": {
    "source": "_shared/checklists/code-ready.md",
    "items": [
      {
        "section": "domain risk",
        "item": "paymentKey / orderId / 카드번호 등이 plaintext 로그에 노출되지 않음",
        "status": "yes",
        "evidence": "PaymentExceptionHandler.java:103,109 LogFmt.warn(e::getMessage) — message는 PaymentErrorCode 정적 문구만"
      },
      {
        "section": "domain risk",
        "item": "보상 / 취소 로직에 멱등성 가드 존재",
        "status": "n/a",
        "evidence": "본 토픽 보상/취소 경로 변경 0. PgOutbox.create의 Long id 제거가 @GeneratedValue(IDENTITY) 채움 정책과 정합"
      },
      {
        "section": "domain risk",
        "item": "PG가 반환하는 '이미 처리됨' 계열 특수 응답이 맹목 수용되지 않음",
        "status": "n/a",
        "evidence": "본 토픽 PG 응답 처리 변경 0. DuplicateApprovalHandler 의 createDirectInProgress/of 호출 시그니처/의미 유지"
      },
      {
        "section": "domain risk",
        "item": "상태 전이가 불변식을 위반하지 않음 (예: SUCCESS → FAIL 금지)",
        "status": "yes",
        "evidence": "PgInbox.java:305-437 markInProgress/markApproved/markFailed/markQuarantined 4종 모두 사전 가드 보존. markQuarantined의 isTerminal() 가드 + createDirectTerminal의 !isTerminal() 가드 (line 196) 그대로"
      },
      {
        "section": "domain risk",
        "item": "race window가 있는 경로에 락 / 트랜잭션 격리 고려됨",
        "status": "n/a",
        "evidence": "본 토픽 락/TX 정책 변경 0. claimToInFlight CAS + FOR UPDATE SKIP LOCKED + AFTER_COMMIT publishEvent contract 미접촉"
      }
    ],
    "total": 5,
    "passed": 2,
    "failed": 0,
    "not_applicable": 3
  },

  "scores": {
    "correctness": 0.95,
    "conventions": 0.92,
    "discipline": 0.95,
    "test-coverage": 0.90,
    "domain": 0.95,
    "mean": 0.934
  },

  "findings": [],

  "previous_round_ref": null,
  "delta": {
    "newly_passed": [],
    "newly_failed": [],
    "still_failing": []
  },

  "unstuck_suggestion": null
}
```
