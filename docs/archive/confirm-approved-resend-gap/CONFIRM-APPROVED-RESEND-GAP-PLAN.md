# 비동기 confirm APPROVED 재고 확정 재발행 갭 구현 플랜

> 작성일: 2026-06-22

## 요약 브리핑

### Task 목록

1. **재발행 관측 메트릭 컴포넌트** — 종결 가드 재발행 분기가 dedupe를 안 거쳐 silent 반복 발행 위험이 있으므로, "종결 후 재발행" 횟수를 세는 카운터를 먼저 만든다.
2. **종결 가드 재발행 + dead branch 제거 + 회귀 테스트 교체** — 결제가 완료(DONE)된 뒤 같은 승인 결과가 다시 도착하면 재고 확정을 재발행하도록 진입 가드를 바꾸고, 도달 불가능했던 중복 발행 분기를 단순 skip으로 정리한다. 단위·통합 회귀 가드를 실효 시나리오로 교체해 트리를 녹색으로 유지한다.
3. **결정적 EOS 커밋 실패 주입 실증** — 실제 임베디드 Kafka에서 결제 완료 커밋 직후 재고 확정 발행만 실패시켜, 재배달이 재발행으로 재고를 복구하는지 end-to-end로 증명한다.

### 변경 후 전체 플로우차트

```mermaid
flowchart TD
    MSG([승인 결과 메시지 수신<br/>confirmed: APPROVED]) --> GUARD{진입 가드<br/>결제가 아직 비종결인가?}

    GUARD -->|종결 = DONE| RESEND_T["★ 재고 확정 재발행<br/>(DONE+APPROVED = 재배달 신호)<br/>+ 재발행 카운터(Task1) + 로그"]
    GUARD -->|종결 = QUARANTINED/FAILED 등| NOOP["가드 skip 카운터 + noop<br/>(재발행 안 함 — DR-3 보호)"]
    GUARD -->|비종결 READY/IN_PROGRESS| DEDUPE["멱등 마킹"]

    DEDUPE -->|이미 마킹됨 = 중복| SKIP["단순 skip<br/>(도달 불가 — 방어적 로그만, Task2)"]
    DEDUPE -->|신규| DONE["결제 완료 전이 (RDB DONE)"]
    DONE --> SEND["재고 확정 발행 buffer"]

    SEND --> COMMIT{2단계 커밋}
    RESEND_T --> COMMIT
    COMMIT -->|RDB + 브로커 모두 성공| OK([재고 확정 가시화])
    COMMIT -.->|브로커 커밋 실패<br/>(Task3 결정적 주입)| LOSS["오프셋 미커밋 → 재배달"]
    LOSS -.재배달.-> GUARD

    RESEND_T -.->|수신측 product| ABSORB["결정적 키로 멱등 흡수<br/>차감 정확히 1회"]
    SEND -.->|수신측 product| ABSORB
```

### 핵심 결정 → Task 매핑

| 설계 결정 (topic.md) | Task |
|---|---|
| D7 재발행 관측 메트릭/로그 | Task 1 |
| 재발행 위치 = 종결 가드, 조건 = `status==DONE && message APPROVED` | Task 2 |
| 도달 불가 affected==0 발행 분기 제거 → 단순 skip | Task 2 |
| 단위 2종 + `PaymentEosIntegrationTest` #3·#4 회귀 가드 교체 (#5 green 유지) | Task 2 |
| 결정적 EOS 커밋 실패 주입 실증 (Toxiproxy 대체 — 임베디드 Kafka 하니스) | Task 3 |
| 순서 뒤집기(Option B) 미채택 | (구현 없음 — 코드로 미반영, 문서 근거만) |

### 트레이드오프 / 후속 작업

- **실증 방식 변경**: discuss의 "Toxiproxy 실증"은 통합 하니스가 임베디드 Kafka(외부 TCP 엔드포인트 없음)라 부적합 → **결정적 주입 시드**로 plan 확정(사용자 승인). 실제 브로커·EOS·재배달은 그대로 쓰되 커밋 실패만 테스트 시드로 주입해 윈도우를 결정적으로 재현. topic.md 검증 절도 동일하게 정정.
- **종결 가드 의미 확장**: 순수 noop → 조건부 재발행. DONE+APPROVED 재배달마다 무해한 재발행 1회(product 흡수). Task 1 카운터로 빈도 가시화.
- **재발행 분기 dedupe 미경유**: 재발행 커밋 반복 실패 시 발행량 상한 = N상품 × 최대 6회, 전량 흡수돼 차감 추가 0. Task 3에서 DLQ-bound 단정.
- **문서 정정(CONFIRM-FLOW §5·§16, CONCERNS L-1)**: ship 단계 context-update에서 처리 (execute 태스크 아님).

## 목표

비동기 confirm APPROVED 경로에서 RDB DONE 커밋 후 stock-committed 발행이 유실돼도 재배달이 종결 가드에서 재발행해 재고 확정을 복구하고, 도달 불가 dead branch를 정리하며, 결정적 주입으로 복구를 실증하면 완료.

## 컨텍스트

- 설계 문서: `docs/topics/CONFIRM-APPROVED-RESEND-GAP.md`
- 이슈/브랜치: #112 / `#112`
- 주요 변경 파일:
  - `payment-service/.../core/common/metrics/PaymentConfirmTerminalResendMetrics.java` (신규)
  - `payment-service/.../application/usecase/PaymentConfirmResultUseCase.java` (handle 분기)
  - `payment-service/.../application/usecase/PaymentConfirmResultUseCaseTest.java` (단위 교체)
  - `payment-service/.../integration/PaymentEosIntegrationTest.java` (#3 실효 교체 + 실증 시나리오 추가)

## 진행 상황

- [x] Task 1: 재발행 관측 메트릭 컴포넌트
- [x] Task 2: 종결 가드 재발행 + dead branch 제거 + 단위/통합 회귀 교체
- [x] Task 3: 결정적 EOS 커밋 실패 주입 실증

## 태스크

### Task 1: 재발행 관측 메트릭 컴포넌트 [tdd=true] [domain_risk=false]

**테스트 (RED)**
- `PaymentConfirmTerminalResendMetricsTest` (신규, `PaymentConfirmGuardSkipMetricsTest` 패턴 따름)
  - `record_DONE_호출시_카운터_1증가` — `record(DONE)` 후 `payment_confirm_terminal_resend_total{status=DONE}` 카운터 == 1.0
  - `record_null_입력_throwFree_noop` — `record(null)` 예외 없이 noop (가드 noop 분기 예외 전파 → DLQ 변환 방지 계약).
  - `생성자_eager등록_DONE_0시리즈` — 기동 직후 DONE 라벨 카운터 0 시리즈 사전 등록 확인.

**구현 (GREEN)**
- `payment-service/.../core/common/metrics/PaymentConfirmTerminalResendMetrics.java` 신규.
  - 카운터명 `payment_confirm_terminal_resend_total`, 라벨 `status` 1개만(고카디널리티 금지).
  - `record(PaymentEventStatus)` throw-free(null noop). `PaymentConfirmGuardSkipMetrics` 구조 그대로 차용 — eager 등록 대상은 재발행 트리거 상태 DONE 1종.

**완료 기준**
- 신규 테스트 3 pass. `./gradlew :payment-service:test` 회귀 없음.

**완료 결과**
- `PaymentConfirmTerminalResendMetrics` 신규 — `PaymentConfirmGuardSkipMetrics` 구조 그대로 차용. 카운터 `payment_confirm_terminal_resend_total`, 라벨 `status` 1개. eager 등록 대상은 재발행 트리거 상태 DONE 1종(가드 스킵 6종 필터링 방식 대신 단일 라벨 직접 등록).
- 신규 테스트 3 pass(`record_DONE_호출시_카운터_1증가`, `record_null_입력_throwFree_noop`, `생성자_eager등록_DONE_0시리즈`). `./gradlew :payment-service:test` 전체 453 pass(기존 450 + 신규 3), 회귀 없음.

---

### Task 2: 종결 가드 재발행 + dead branch 제거 + 단위/통합 회귀 교체 [tdd=true] [domain_risk=true]

**테스트 (RED)** — 단위 (`PaymentConfirmResultUseCaseTest`)
- **교체/제거**: 도달 불가 상태(IN_PROGRESS + dedupe 인위 선마킹)에 의존하던 `shouldSkipBusinessWhenMarkIfAbsentReturnsZero`, `shouldSkipBusinessButAlwaysSendWhenMarkIfAbsentReturnsZero` 제거.
- **신규** (6건):
  - `shouldResendStockCommittedWhenDoneApprovedRedelivered` — 상태 DONE + APPROVED 메시지 → `sendStockCommittedEvents` 상품 N개당 1회(`stockCommittedKafkaTemplate.send` times(N)) + `markPaymentAsDone` **never** + `terminalResendMetrics.record(DONE)` 1회.
  - `shouldResendWhenDoneApprovedWithFreshEventUuid` — 상태 DONE + **처음 보는 eventUuid** + APPROVED → send N회 + `terminalResendMetrics.record(DONE)`. 종결 가드 재발행이 eventUuid 신/구에 **비의존**임을 고정(S3 새 eventUuid 정상 도착 경로 회귀 가드 — eventUuid 기반 가드 추가 시 under-publish 회귀 차단).
  - `shouldNotResendWhenQuarantinedLateApproved` — 상태 QUARANTINED + APPROVED → send **never** + `guardSkipMetrics.record(QUARANTINED)` (DR-3 보호 불변, 재발행 카운터 미증가).
  - `shouldNotResendWhenFailedTerminalApproved` — 상태 FAILED + APPROVED → send **never**.
  - `shouldSimpleSkipWhenMarkIfAbsentReturnsZero` — 비종결 + affected==0 → send **never** + `markPaymentAsDone` never (단순 skip).
  - `shouldResendDistinctKeyPerProductWhenDoneApprovedRedelivered` — 멀티상품 재발행 결정성: DONE + 상품 2건 재배달 → send 2회 + productId별 distinct idempotencyKey.

**테스트 (RED→GREEN 동반)** — 통합 (`PaymentEosIntegrationTest`)
- 시나리오 #3 `shouldSkipBusinessButResendOnDuplicateInsert`(dead-state) → **실효 교체**: 결제 DONE 저장 + dedupe row 선존재(첫 처리 모사) → 동일 eventUuid 배달 → 종결 가드 재발행 → `pollStockCommitted(orderId, 1)` 가시화 + payment 상태 DONE 불변 + dedupe row 불변. **발행 책임 이전 실증**: 재발행이 종결 가드 경로로만 일어나고 affected==0 분기는 미진입(`markIfAbsent` 재호출 0 / guardSkip·terminalResend 카운터 증가)을 함께 단정.
- 시나리오 #4: 재배달 절(IN_PROGRESS + dedupe 인위 선삽입 = dead-state 의존)을 **제거** → 재배달 흡수는 #3 실효 시나리오가 커버. #4는 **정상 경로 멀티상품 distinct idempotencyKey 결정성만 유지**(DR-1 가드 보존).
- 시나리오 #5(QUARANTINED → stock-committed 0건 + DLQ 0건)는 **수정 없이 green 유지 검증**(재발행 조건 status==DONE이라 미트리거).

**구현 (GREEN)** — `PaymentConfirmResultUseCase.handle`
- D7 종결 가드 분기: `guardSkipMetrics.record(status)` 후, `paymentEvent.getStatus()==DONE && ConfirmStatus.from(message.status())==APPROVED`이면 `sendStockCommittedEvents(paymentEvent)` + `terminalResendMetrics.record(DONE)` + 재발행 로그, 그 외엔 기존 noop 로그. 그 후 return.
- affected==0 분기: APPROVED 시 `sendStockCommittedEvents` 호출 제거 → 중복 skip 로그 + return만(주석으로 "단일 컨슈머 EOS서 도달 불가, 방어적 처리" 명시).
- 생성자에 `PaymentConfirmTerminalResendMetrics` 주입.

**완료 기준**
- 단위 신규 6 pass + 기존 2 제거. 통합 #3 실효 통과(발행 책임 이전 단정 포함) + #4 정상 경로만 통과 + #5 green. `./gradlew :payment-service:test` 회귀 없음(트리 녹색 유지).

**완료 결과**
- `PaymentConfirmResultUseCase.handle` 종결 가드 분기: `guardSkipMetrics.record(status)` 후 `status==DONE && ConfirmStatus.from(message.status())==APPROVED`면 `sendStockCommittedEvents` 재발행 + `terminalResendMetrics.record(DONE)` + 재발행 로그 후 return, 그 외 종결은 기존 noop 로그. affected==0 분기에서 APPROVED `sendStockCommittedEvents` 호출 제거(단일 컨슈민 EOS서 도달 불가, 방어적 처리 주석 명시) → 중복 skip 로그 + return만. 생성자에 `PaymentConfirmTerminalResendMetrics` 주입.
- 단위(`PaymentConfirmResultUseCaseTest`): 도달 불가 dead branch 의존 기존 2종(`shouldSkipBusinessWhenMarkIfAbsentReturnsZero`, `shouldSkipBusinessButAlwaysSendWhenMarkIfAbsentReturnsZero`) 제거, 신규 6종(`shouldResendStockCommittedWhenDoneApprovedRedelivered`, `shouldResendWhenDoneApprovedWithFreshEventUuid`, `shouldNotResendWhenQuarantinedLateApproved`, `shouldNotResendWhenFailedTerminalApproved`, `shouldSimpleSkipWhenMarkIfAbsentReturnsZero`, `shouldResendDistinctKeyPerProductWhenDoneApprovedRedelivered`) 추가.
- 통합(`PaymentEosIntegrationTest`): #3 `shouldSkipBusinessButResendOnDuplicateInsert`(dead-state) → `shouldResendStockCommittedViaTerminalGuardOnRedelivery`로 실효 교체 — DONE 저장 + dedupe row 선존재 + 동일 eventUuid 재배달 → stock-committed 1건 가시화 + payment DONE/dedupe row 불변 + `markIfAbsent` 미재호출(spy) + guardSkip/terminalResend 카운터 증가(증가분 단정, MeterRegistry 클래스 공유 고려) 검증. #4 재배달 절(dead-state 의존) 제거 → 정상 경로 멀티상품 distinct idempotencyKey 결정성만 유지. #5 무수정 green 유지.
- Rule 1: Task 1에서 생성자에 `PaymentConfirmTerminalResendMetrics` 인자가 추가됐을 때 갱신되지 않았던 기존 테스트 7개(`ConfirmedEventConsumerTest`, `PaymentConfirmResultUseCaseClockTest`, `PaymentConfirmResultUseCaseHandleApprovedTest`, `PaymentConfirmResultUseCaseHandleQuarantinedTest`, `PaymentConfirmResultUseCaseHandleFailedTest`, `PaymentConfirmResultUseCaseIdempotencyGuardTest`, `PaymentConfirmResultUseCaseGuardSkipTest`)의 생성자 호출에 동일 인자를 추가해 컴파일 오류 해소.
- 단위 457 pass(기존 453 + 신규 6 - 제거 2 = 457). 통합 37 pass(기존 동일 수, #3 실효 교체 + #4 재배달 절 제거 반영). `./gradlew :payment-service:test` + `:payment-service:integrationTest` 전체 green, 회귀 없음.

---

### Task 3: 결정적 EOS 커밋 실패 주입 실증 [tdd=false] [domain_risk=true]

**구현 (실증 시나리오 — 신규 통합 테스트)**
- 테스트 시드: 테스트 프로파일에서 `stockCommittedProducerFactory`가 만드는 Producer를 데코레이트해 `commitTransaction()`을 **최초 1회만** throw(이후 위임). JPA(inner) 커밋이 Kafka(outer) 커밋보다 먼저라, 이 throw는 "RDB DONE 커밋됨 + 재고 확정 발행/오프셋 커밋 실패"를 결정적으로 재현.
- 시나리오 A (복구): READY 결제 + APPROVED 1차 배달 → 1차 EOS 커밋 실패(주입) → stock-committed 0건(유실) + payment DONE + **`payment_event_dedupe` row 1건**(dedupe와 DONE이 같은 JPA inner tx로 동반 커밋된 증거) 확인 → 오프셋 미커밋 재배달 → **재배달이 종결 가드 분기로 진입**(`markIfAbsent` 미재호출 — terminalResend 카운터 증가로 확인, affected==0 분기 우회) → 재발행 → `pollStockCommitted(orderId, 1)` 복구 + product 흡수로 차감 정확히 1회. (dedupe row 동반 커밋 단정은 추후 D7 가드 앞으로 dedupe 이동(Option C류) 변경의 회귀 가드.)
- 시나리오 B (bound): 주입을 N회 연속 실패로 확장 → FixedBackOff(test 200ms×5) 소진 → `payment.events.confirmed.dlq` 진입 확인 + product 중복 차감 0건(흡수).

**완료 기준**
- 시나리오 A·B pass. 차감 1회/0 중복 단정 통과. `./gradlew :payment-service:test` 회귀 없음.
- 시드는 테스트 스코프 한정(운영 코드 무변경).

**완료 결과**
- 시드 `CommitFailureInjectingProducerPostProcessor`(신규, 테스트 스코프 한정) — `stockCommittedProducerFactory`가 생성하는 `Producer`를 동적 프록시로 감싸 `commitTransaction()` 최초 N회만 결정적으로 throw(이후 위임). `ProducerFactory.addPostProcessor`(운영 코드 공식 확장 포인트) 경유로 운영 코드 무변경. `PaymentEosIntegrationTest` tearDown에서 `removePostProcessor` + `reset()`으로 다음 테스트 격리.
- 시나리오 #6(복구, `shouldRecoverStockCommittedAfterSingleEosCommitFailure`) — **설계대로 증명됨**: 1차 EOS 커밋 실패 주입 → JPA inner 커밋(DONE + dedupe row 1건)은 그대로 반영되고 Kafka outer 커밋만 실패 → 오프셋 미커밋 재배달 → 재배달이 종결 가드로 진입(`markIfAbsent`/`markPaymentAsDone` 재호출 0 — affected==0 분기 우회) → 재발행 → `terminalResendMetrics(DONE)` +1 → stock-committed 1건 복구(차감 정확히 1회). PLAN 가설 A 그대로 성립.
- 시나리오 #7(반복실패 bound) — **PLAN 가설 B는 2가지 지점에서 반증됨**(실증 가치 있는 실패):
  1. "FixedBackOff(200ms×5) 소진 → DLQ" 가정이 틀림. `kafkaErrorHandler`(`DefaultErrorHandler`, interval 200ms×5 + DLQ recoverer)는 리스너가 던진 도메인 `RuntimeException`에만 적용되고, `commitTransaction()` 실패는 `TransactionTemplate.execute()` 내부(컨테이너의 `invokeInTransaction`)에서 발생해 별도 경로인 `DefaultAfterRollbackProcessor`(컨테이너가 명시 설정하지 않으면 `SeekUtils.DEFAULT_BACK_OFF` = interval 0, maxAttempts 9, recoverer는 단순 로그)로 처리됨 — DLQ recoverer를 거치지 않음. 9회 소진 후 메시지는 DLQ가 아니라 단순 스킵(오프셋 전진, 로그만).
  2. "발행 횟수가 N이어도 차감 1회(중복 0건)" 가정도 틀림. 종결 가드 재발행은 `sendStockCommittedEvents`까지 같은 EOS 프로듀서 트랜잭션 안에서 수행되므로, 그 트랜잭션의 `commitTransaction()`이 매번 실패하면 발행 자체가 매번 abort된다(read_committed 컨슈머에 노출 안 됨). N회 전부 실패를 주입하면 9회의 재배달·재발행 시도 전부가 abort돼 stock-committed가 단 1건도 가시화되지 않음 — "중복 차감 0건"이 아니라 "발행 자체가 0건(완전 유실)". payment는 DONE(재고 확정 완료로 보임)인데 재고 확정 이벤트는 영구 소실되는 심각한 불일치.
  - 실제 동작에 맞춰 테스트를 재작성(`shouldExhaustAfterRollbackBackoffWithoutDlqAndNoDuplicateStock`) — DLQ 미진입 확인(`pollConfirmedDlq` 빈 리스트) + stock-committed 0건(완전 유실) 확인 + `markPaymentAsDone`/dedupe row는 1차 배달에서만 1회(종결 가드 흡수 자체는 #6과 동일하게 성립) 단정으로 교체. 클래스 상단 Javadoc에 "범위 밖 알려진 한계" 항목으로 운영 보강 필요성(`setAfterRollbackProcessor`로 동일 DLQ recoverer + backoff 명시 연결) 기록, 후속 토픽 분리 — Task 3 범위 밖(운영 코드 무변경 제약).
- Rule 1: (a) WIP에 정의 없이 호출만 있던 `pollConfirmedDlq` 헬퍼 신규 작성(`pollStockCommitted` 패턴 따라 DLQ 토픽을 orderId 키로 폴링) — 컴파일 오류 해소. (b) #6의 "차감 정확히 1회" 검증이 별도 consumer group으로 추가 폴링해 동일 메시지를 earliest로 재조회하는 거짓 중복 버그 — 직전 폴링이 정확히 1건 반환한 것 자체가 중복 없음의 증거이므로 추가 폴링 제거. (c) #7을 실제 Spring Kafka 동작(AfterRollbackProcessor 경로)에 맞춰 시나리오·DisplayName·클래스 Javadoc 전면 재작성.
- 통합 39 pass(기존 37 + 신규 #6·#7 = 39). `./gradlew :payment-service:test`(457 pass) + `:payment-service:integrationTest`(39 pass) 전체 green, 회귀 없음.

## 리뷰 처리

ship Phase A 리뷰 — reviewer·domain-expert 모두 **verdict=pass** (critical·major 코드 결함 0). findings는 doc-sync + 주석 보강.

| # | finding (출처) | severity | 처리 | 사유 |
|---|---|---|---|---|
| R1 | CONFIRM-FLOW §5(L129,162-163,173)·§16 시나리오#3 + CONCERNS L-1(L94): 제거된 dead branch("affected==0 발행 항상 진행")가 crash 내성 SSOT로 잔존 (reviewer major / domain minor, 동일 항목) | major | **채택 — B2 context-update** | 코드 결함 아님(양 리뷰 합의: Phase B2 책임). crash 내성 = 종결 가드 DONE+APPROVED 재발행 + product 결정적 키 흡수로 정정 |
| R2 | CONFIRM-FLOW §5 mermaid 에러핸들링(L149-151): commitTransaction 실패를 DefaultErrorHandler→DLQ로 표기하나 실제는 AfterRollbackProcessor(9회, DLQ 미진입) (reviewer minor) | minor | **채택 — B2 context-update** | Task 3 실증 결과 반영. 리스너 도메인 예외 vs EOS 커밋 실패 경로 분기 명시 |
| R3 | topic.md S2 반증 가설(5회 DLQ+중복0)이 거짓으로 잔존 (domain minor) | minor | **채택 — B3 COMPLETION-BRIEFING** | topic.md는 archive 이동분 — 브리핑에 "S2 가설 반증→실제 동작"을 SSOT로 기록해 archive가 거짓 가설 박제 방지 |
| R4 | `PaymentConfirmResultUseCase` 종결 가드 재발행이 amount/approvedAt 재검증 우회 — 무해하나 의도 주석 부재 (domain minor) | minor | **채택 — 주석 1줄(코드) — 처리 완료** | 후속 수정자가 우회를 결함으로 오인해 종결 상태에 검증 끼워넣어 격리 전이 유발하는 것 차단. 재발행은 이미 검증 통과한 DONE의 재고 확정 복구라 재검증 불요·금지 |
