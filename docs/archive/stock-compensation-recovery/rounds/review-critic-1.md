# review-critic-1

**Topic**: STOCK-COMPENSATION-RECOVERY
**Round**: 1
**Persona**: Critic
**Stage**: review

## Reasoning

execute 단계 SCR-1~10 산출물 전수 점검 결과, 코드 결함·동시성·트랜잭션 경계·테스트 정합·관례 준수가 PLAN 의도와 일치한다. PLAN 산출물 목록과 diff 의 변경 파일이 모두 매핑되며, 누락 / scope creep 없음 (`spring-kafka-test` testImplementation 추가는 PLAN.md SCR-10 본문에 사전 고지된 Rule 2 의 test-only 의존). `EventDedupeStore` / `PaymentConfirmDlqPublisher` 잔여 사용처 grep 결과 0 — orphan port 정리 완전. critical / major finding 없음. 다만 후속 토픽 (PHASE2) 에서 점검할 minor 두 건 (SCR-7 미회수 PHASE2 의존 표기 누락, 통합 테스트 retry 경로 단정 자료 약함) 만 기록.

## Checklist judgement

### task execution (taskt 실행)
- RED 커밋: yes (SCR-1/2/3/4/5/6/8/10 각 RED 커밋 식별, `git log main..HEAD` 검증)
- GREEN 커밋: yes (모든 SCR 의 GREEN + PLAN.md 체크박스 + "완료 결과")
- REFACTOR 커밋: SCR-7 refactor 1건 — 합리
- 커밋 메시지 포맷: yes
- STATE.md active: yes (마지막 커밋이 `execute 완료 → review 전이`)

### test gate
- `./gradlew test`: PLAN 본문 SCR-10 "전체 회귀 단위 377 PASS" 보고. 본 환경 로컬 gradle 이 Java major 68 unsupported 로 실행 불가 (실행 환경 문제, 코드 결함 아님)
- 신규/수정 business logic 커버리지: yes (Lua 단위 5+5, Adapter 신규 5, FakeStockCachePort atomic 5, HandleFailed 4, HandleQuarantined 3, IdempotencyGuard 4, KafkaErrorHandler 3, IntegrationTest 5)
- state machine 전이 EnumSource 커버: n/a (본 토픽은 enum 추가만, PaymentEventStatus 기존 룰 재사용)

### convention
- Lombok: yes (`@RequiredArgsConstructor`, `@Slf4j` — `@Data` 부재)
- AllArgsConstructor + Builder: n/a (해당 패턴 적용 클래스 추가 없음)
- LogFmt: yes (`PaymentConfirmResultUseCase.handleFailed` line 198 / 210 등)
- null 반환 금지: yes
- `catch (Exception e)` 없음: yes — `PaymentTransactionCoordinator.decrementStock` line 54 의 `catch (RuntimeException e)` 는 specific exception 으로 합리적

### execution discipline
- 범위 밖 코드 수정 없음: yes — `OutboxAsyncConfirmService.compensateStock` / `PaymentTransactionCoordinator.compensateStockCacheGuarded` 는 PHASE2 로 인지된 채 그대로 유지
- 분석 마비 없음: yes

### final task only
- STATE.md → review: yes (commit 36c24c84 메시지에 명시)
- `.continue-here.md` 제거됨: n/a (생성 흔적 없음)

### domain risk
- 민감정보 plaintext 로그: yes — diff 안 paymentKey 노출 0
- 보상 멱등성: yes (Lua dedup token + Fake dedup set + 어댑터 enum 변환)
- "이미 처리됨" 정당성 검증: yes — `ALREADY_DONE` 분기에서도 RDB 진행 (silent loss 방어, PLAN D6) — 단순 noop 이 아니라 **반환값 검증 후** 진행
- 상태 전이 불변식: yes (`isTerminal` 가드로 SUCCESS → FAIL 등 역행 차단)
- race window 락: yes (Lua 서버 단일 스레드 atomic + dedup token SETNX + RDB `@Transactional`)

## Findings

### Minor

**M1. SCR-7 폐기 정리 후, `OutboxAsyncConfirmService.compensateStock` (line 100-120) / `PaymentTransactionCoordinator.compensateStockCacheGuarded` (line 164-176) 가 여전히 단일 상품 단위 `stockCachePort.increment(...)` for-loop 을 사용**

- severity: minor
- location: `payment-service/src/main/java/com/hyoguoo/paymentplatform/payment/application/OutboxAsyncConfirmService.java:107`, `payment-service/src/main/java/com/hyoguoo/paymentplatform/payment/application/usecase/PaymentTransactionCoordinator.java:167`
- problem: PLAN.md line 230, line 81 (PHASE2), line 652-653 에서 명시적으로 PHASE2 로 위임됐다. 따라서 **본 토픽 범위 외**이며 결함이 아니다. 다만 review 단계에서 한 번 더 verifier 가 grep 했을 때 **"왜 SCR-2 보상 Lua 가 있는데 같은 메서드명 보상 경로가 또 있나"** 의문이 발생할 수 있어 PHASE2 토픽 명시 주석을 두 위치에 한 줄씩 추가하는 것을 권장.
- evidence: `PaymentTransactionCoordinator.java:160` 주석 "재고 캐시 보상 — 각 PaymentOrder 별 increment" 만 있고 "PHASE2 / STOCK-COMPENSATION-OTHER-PATHS 토픽 위임" 표시 없음. `OutboxAsyncConfirmService.java:85-89` 의 javadoc 도 마찬가지.
- suggestion: 두 곳에 `// PHASE2 (STOCK-COMPENSATION-OTHER-PATHS): SCR-2 atomic 보상 Lua 로 통합 예정. PLAN.md PHASE2 섹션 참조.` 같은 1줄 주석을 추가. **본 PR 범위 외이므로 후속 토픽에서 수행해도 충분**.

**M2. SCR-10 통합 테스트 `RuntimeException_시_retry_5회_후_DLQ` 가 retry **횟수**(times(6))만 검증하고 **DLQ 토픽 도착**은 검증하지 않음**

- severity: minor
- location: `payment-service/src/test/java/com/hyoguoo/paymentplatform/payment/integration/StockCompensationRecoveryIntegrationTest.java:233-257`
- problem: PLAN.md line 572 의 테스트 스펙은 "FixedBackOff 5회 → DLQ 토픽 발행 확인" 으로 명시. 현재 테스트는 `verify(stockCachePort, times(6)).compensateAtomic(...)` 만 검증 — DLQ 토픽 (`payment.events.confirmed.dlq`) 에 메시지가 실제로 도착했는지를 KafkaConsumer 또는 EmbeddedKafkaBroker 의 records 로 확인하지 않음.
- evidence: 해당 테스트 메서드 내 `events.confirmed.dlq` 토픽을 폴링/구독하는 로직 없음. `EmbeddedKafka.topics` 에는 `EVENTS_CONFIRMED_DLQ` 가 등록되어 있지만 검증 측에서 사용 안 함.
- suggestion: `KafkaTestUtils.consumerProps(...)` 로 별도 컨슈머 구성 후 `events.confirmed.dlq` 를 polling 해 6회 retry 후 1건 도착을 추가 검증. **본 PR 범위 외 — 통합 테스트 보강 후속 토픽** 으로 처리해도 충분.

**M3. `KafkaErrorHandlerConfig.kafkaErrorHandler(KafkaTemplate<String, String> confirmedDlqKafkaTemplate)` 의 의존 주입이 **파라미터 이름 매칭** 에 의존 — `-parameters` 컴파일 플래그 분실 시 NoUniqueBeanDefinitionException 위험**

- severity: minor
- location: `payment-service/src/main/java/com/hyoguoo/paymentplatform/payment/infrastructure/config/KafkaErrorHandlerConfig.java:48-50`
- problem: `KafkaTemplate<String, String>` 타입 빈이 2개(`stockOutboxKafkaTemplate`, `confirmedDlqKafkaTemplate`) 존재 → Spring 은 파라미터 이름으로 disambiguate. Spring Boot 3.x 의 bootJar 컨벤션이 `-parameters` 를 자동 추가하므로 현재 GREEN 이지만, 누군가 `compileJava { options.compilerArgs.remove('-parameters') }` 같은 변경을 하면 즉시 컨텍스트 시작 실패. `@Qualifier("confirmedDlqKafkaTemplate")` 명시가 더 견고.
- evidence: `KafkaProducerConfig.java:59` `stockOutboxKafkaTemplate` + `KafkaProducerConfig.java:80` `confirmedDlqKafkaTemplate` 두 빈 모두 `KafkaTemplate<String, String>` 타입. `KafkaErrorHandlerConfig.java` 에 `@Qualifier` 없음. payment-service `build.gradle` 에 `-parameters` 명시 없음 (Spring Boot 의 implicit 동작에 의존).
- suggestion: 파라미터에 `@Qualifier("confirmedDlqKafkaTemplate")` 추가. 1줄 변경. **선택사항 — 현재 GREEN 이므로 본 PR 머지 차단 사유 아님**. PHASE2 처리 OK.

## Scores (참고용 — 판정 기준 아님)

```
correctness: 0.95
conventions: 0.95
discipline:  0.95
test-coverage: 0.88  (M2 DLQ 도착 검증 부재)
domain:      0.95
mean: 0.936
```

## JSON

```json
{
  "stage": "review",
  "persona": "critic",
  "round": 1,
  "task_id": null,
  "decision": "pass",
  "reason_summary": "SCR-1~10 코드 결함·트랜잭션 경계·관례 준수·테스트 커버리지 모두 정합. PLAN 산출물과 diff 1:1 매핑, 잔여 사용처 0, scope creep 없음. minor 3건은 후속 토픽 보강 사항으로 본 머지 차단 아님.",
  "checklist": {
    "source": "_shared/checklists/code-ready.md",
    "items": [
      {"section": "test gate", "item": "신규/수정 business logic 테스트 커버리지", "status": "yes", "evidence": "Lua 10 + Adapter 5 + Fake 5 + ConfirmResult 11 + KafkaErrorHandler 3 + Integration 5 = 39건 신규/갱신"},
      {"section": "convention", "item": "catch (Exception e) 없음", "status": "yes", "evidence": "PaymentTransactionCoordinator.java:54 catch (RuntimeException e) — 합리적 specific catch"},
      {"section": "execution discipline", "item": "범위 밖 코드 수정 없음", "status": "yes", "evidence": "OutboxAsyncConfirmService.compensateStock / PaymentTransactionCoordinator.compensateStockCacheGuarded PHASE2 의도대로 미수정"},
      {"section": "domain risk", "item": "보상 / 취소 멱등성 가드 존재", "status": "yes", "evidence": "stock_compensation_atomic.lua line 13 SETNX dedup token + StockCacheRedisAdapter.compensateAtomic line 80 enum 매핑"},
      {"section": "domain risk", "item": "ALREADY_DONE 정당성 검증", "status": "yes", "evidence": "PaymentConfirmResultUseCase.handleFailed line 205-208 — ALREADY_DONE 이어도 markPaymentAsFail 진행 (silent loss 차단)"},
      {"section": "domain risk", "item": "race window lock 고려", "status": "yes", "evidence": "Lua atomic + dedup SETNX + isTerminal 가드 + @Transactional"}
    ],
    "total": 17,
    "passed": 14,
    "failed": 0,
    "not_applicable": 3
  },
  "scores": {
    "correctness": 0.95,
    "conventions": 0.95,
    "discipline": 0.95,
    "test_coverage": 0.88,
    "domain": 0.95,
    "mean": 0.936
  },
  "findings": [
    {
      "severity": "minor",
      "checklist_item": "execution discipline — 범위 밖 코드 수정 없음 (PHASE2 표시)",
      "location": "payment-service/src/main/java/com/hyoguoo/paymentplatform/payment/application/usecase/PaymentTransactionCoordinator.java:160-176, payment-service/src/main/java/com/hyoguoo/paymentplatform/payment/application/OutboxAsyncConfirmService.java:85-120",
      "problem": "compensateStockCacheGuarded / compensateStock 두 메서드가 PHASE2 로 미루어져 있으나, 코드 안에 PHASE2 토픽 위임 주석이 없어 향후 reviewer 가 'SCR-2 atomic 보상 Lua 가 있는데 왜 단일 increment for-loop 가 또 있나' 의문에 빠질 가능성.",
      "evidence": "PLAN.md:81, line 230, line 652-653 에 PHASE2 명시되어 있으나 코드 주석에는 '재고 캐시 보상 — 각 PaymentOrder 별 increment' (PaymentTransactionCoordinator.java:160) 만 존재.",
      "suggestion": "두 메서드 javadoc 에 'PHASE2 (STOCK-COMPENSATION-OTHER-PATHS): SCR-2 atomic 보상 Lua 로 통합 예정. PLAN.md PHASE2 섹션 참조' 1줄 주석 추가. 본 PR 범위 외 — 후속 토픽 처리 가능."
    },
    {
      "severity": "minor",
      "checklist_item": "test gate — 신규 logic 커버리지 (DLQ 토픽 도착 검증)",
      "location": "payment-service/src/test/java/com/hyoguoo/paymentplatform/payment/integration/StockCompensationRecoveryIntegrationTest.java:233-257",
      "problem": "RuntimeException_시_retry_5회_후_DLQ 가 retry 호출 횟수(times(6))만 검증, DLQ 토픽(payment.events.confirmed.dlq) 도달 여부 미검증. PLAN.md line 572 스펙 '5회 → DLQ 토픽 발행 확인' 일부 누락.",
      "evidence": "해당 테스트 메서드 내 EmbeddedKafkaBroker 의 ConsumerRecords 또는 KafkaTestUtils.getRecords 호출 없음. EmbeddedKafka.topics 에 EVENTS_CONFIRMED_DLQ 가 등록되어 있으나 verify 측에서 미사용.",
      "suggestion": "KafkaTestUtils.consumerProps + ConsumerFactory 로 별도 컨슈머 구성 후 events.confirmed.dlq 폴링하여 1건 도착 추가 검증. 본 PR 범위 외 — verifier / verify 단계에서 별 토픽으로 보강 권장."
    },
    {
      "severity": "minor",
      "checklist_item": "convention — DI 견고성",
      "location": "payment-service/src/main/java/com/hyoguoo/paymentplatform/payment/infrastructure/config/KafkaErrorHandlerConfig.java:48-50",
      "problem": "KafkaTemplate<String, String> 타입의 빈이 2개(stockOutboxKafkaTemplate / confirmedDlqKafkaTemplate) 존재. kafkaErrorHandler 메서드의 파라미터 이름(confirmedDlqKafkaTemplate) 매칭으로 disambiguate — Spring Boot 3.x 의 implicit -parameters 컴파일에 의존.",
      "evidence": "KafkaProducerConfig.java:59, 80 두 빈 모두 KafkaTemplate<String, String>. payment-service/build.gradle 에 -parameters 명시 없음.",
      "suggestion": "kafkaErrorHandler 파라미터에 @Qualifier(\"confirmedDlqKafkaTemplate\") 명시 추가. 1줄 변경으로 빌드 도구 / IDE / Java 버전 변동에서도 견고. 현재 GREEN 이므로 본 PR 차단 사유 아님."
    }
  ],
  "previous_round_ref": null,
  "delta": {
    "newly_passed": [],
    "newly_failed": [],
    "still_failing": []
  },
  "unstuck_suggestion": null
}
```
