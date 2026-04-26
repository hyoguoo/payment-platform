# code-critic-2

**Topic**: PRE-PHASE-4-HARDENING
**Round**: 2
**Persona**: Critic
**Stage**: code (pre-Phase-4 하드닝 Round 1 대비 재검증)

## Reasoning

Round 1 critical 2건·major 5건·minor 3건이 T-A1~T-G3 19 태스크로 전부 또는 대부분 해소됐다. critical-1(approvedAt/amount 비대칭)은 `ConfirmedEventPayload`/`ConfirmedEventMessage` 양쪽에 `amount(Long)`+`approvedAt(String)` 추가 + `handleApproved` 역방향 AMOUNT_MISMATCH 방어선으로 완결, critical-2(FakePgGatewayStrategy null)는 `UnsupportedOperationException` 전환으로 해소됐다. major-1(VT MDC)은 Micrometer `ContextExecutorService.wrap` + `Slf4jMdcThreadLocalAccessor` 등록 + `MdcTaskDecorator`로 3경로 전부 조치, major-2(ARCHITECTURE drift)는 문서가 실구조(`OutboxImmediateEventHandler`/`StockEventPublishingListener`)로 재작성, major-3(TX 내 동기 Kafka publish)는 `StockCommitRequestedEvent`/`StockRestoreRequestedEvent` ApplicationEvent + `@TransactionalEventListener(AFTER_COMMIT)` 리스너 분리 + `@Transactional(timeout=5)` 명시로 근본 제거, major-5(`catch (Exception)`)는 `grep -rn 'catch (Exception' */src/main/java` 0건으로 완전 축출됐다. minor 3건도 모두 정정됐다. 남은 우려 1건(Round 1 major-4 `PaymentConfirmPublisherPort` Javadoc 계약 미강화)은 PLAN에 명시 scope 없이 T-D2의 간접 결과(Spring ApplicationEventPublisher 확정)로 실효 위험이 제거돼 major→minor 수준으로 격하 가능하나, 인터페이스 Javadoc에 "non-blocking in-memory only" 명시가 아직 없어 still_failing로 남긴다. 새로 발견된 잠재 이슈(`StockEventPublishingListener` AFTER_COMMIT 스왈로우로 stock 이벤트 유실 가능)는 T-D2가 의도적으로 승인한 trade-off(TX 이미 commit)로 최소 minor. critical 0 · major 0 · minor 2로 **pass** — 하드닝 루프 종료 조건 충족.

## Checklist judgement

### task execution
- 19 태스크 전부 RED(test:) → GREEN(feat:/fix:/refactor:/docs:) 커밋 쌍 존재 — `git log --oneline` 확인: T-A1(3f98e6df/0c210b2d), T-A2(7ad15ca5/2604509c), T-B1(08b9c6fc), T-B2(f1e0822e), T-C1(eab35e06/dc9c0829), T-C2(c6ab4b89/93c30dbf), T-C3(d40f4463/a443b065), T-D1(f8d30da9/4a5afed8), T-D2(b06c75ac/6b76629e), T-E1(2444a0be/ab4eb3a8), T-E2(4cd4f261/7bfddc9b), T-E3(efd0f91f), T-E4(24351bbe), T-F1(439f6c5e/2772d8f5), T-F2(20de6e15/1cf396db), T-F3(5945153b/0a95769a), T-F4(b15d61b5), T-G1(0bf9cb69), T-G2(a3ad8da4), T-G3(0ed3b977): **yes**.
- 커밋 메시지 포맷 `test:`/`feat:`/`fix:`/`refactor:`/`docs:` 일관: **yes**.
- STATE.md active task T-Gate 갱신, T-G3 완료 명시: **yes** (`docs/STATE.md:10`).

### test gate
- 각 태스크 완료 시 `./gradlew test` 전수 PASS 선언 — T-G2 시점 512 PASS가 최신 스냅샷: **yes** (라운드 재실행 불요 지시).
- 신규/수정 로직 테스트 커버리지 — 각 GREEN 커밋에 동반 RED 테스트 19건: **yes**.
- state machine 전이 `@ParameterizedTest`: T-C2 `PaymentEvent.quarantine` `IllegalStateException` 가드 전이, T-C3 two-phase lease 전이 — 기존 EnumSource 테스트 유지: **yes**.

### convention
- Lombok 패턴 준수: **yes**.
- null 반환 금지 — `FakePgGatewayStrategy.getStatusByOrderId` throw로 전환: **yes** (Round 1 critical-2 해소).
- `catch (Exception e)` — `grep -rn 'catch (Exception' */src/main/java` 0건: **yes** (Round 1 major-5 해소).
- 신규 로깅 LogFmt — `grep -rnE '^\s*log\.(warn|info|error|debug|trace)' */src/main/java` 0건, `FakePgGatewayStrategy` 배너는 `LogFmt.banner` 경유: **yes** (Round 1 minor-1 해소).

### execution discipline
- 범위 밖 코드 수정 없음 — 태스크별 PLAN.md "완료 결과" 해당 범위 준수: **yes**.
- 분석 마비: **n/a** (execute 단계 라운드 아님).
- 문서-코드 정합성 — ARCHITECTURE.md `OutboxImmediateEventHandler`/`StockEventPublishingListener` 실구조 반영, `PaymentConfirmChannel` 부재 명시, `@CircuitBreaker` "Phase 4 설치 예정" 정정: **yes** (Round 1 major-2 / minor-3 해소).

### domain risk
- `paymentKey` 평문 로그: **yes** — `FakePgGatewayStrategy.maskKey` 유지.
- 보상/취소 멱등성 — `QuarantineCompensationHandler.handle` 진입 시 `isTerminal` 가드 + `PaymentEvent.quarantine` `IllegalStateException` 이중 가드: **yes** (T-C2로 강화).
- PG "이미 처리됨" 정당성 검증 — `DuplicateApprovalHandler` 2경로 + `AmountConverter` strict: **yes**.
- 상태 전이 불변식 — `handleApproved` 수신 `approvedAt` 주입(`LocalDateTime.now()` 위조 제거) + amount 역방향 대조 → AMOUNT_MISMATCH QUARANTINED: **yes** (Round 1 critical-1 해소).
- race window — VT executor 3경로 MDC 전파(T-E1) + TX timeout 5s(T-D2) + two-phase lease(T-C3) + Redis DECR 보상(T-D1): **yes** (Round 1 major-1 해소).
- TX 외 Kafka 발행 — `PaymentConfirmResultUseCase.handleApproved/handleFailed`는 ApplicationEvent 발행만, 실 publish는 `StockEventPublishingListener(AFTER_COMMIT)` 이관: **yes** (Round 1 major-3 해소).

## Findings

### [minor-1] `PaymentConfirmPublisherPort` Javadoc 에 "non-blocking in-memory only" 계약 미명시 (Round 1 major-4 잔존·격하)

- **severity**: minor
- **checklist_item**: domain risk / race window
- **location**:
  - `payment-service/src/main/java/com/hyoguoo/paymentplatform/payment/application/port/out/PaymentConfirmPublisherPort.java:1-8` (Javadoc 전무)
  - `payment-service/src/main/java/com/hyoguoo/paymentplatform/payment/application/usecase/PaymentTransactionCoordinator.java:84-95` (`@Transactional` 내부 `confirmPublisher.publish(...)`)
- **problem**: 포트 Javadoc 이 비어 있어 "구현체는 TX 동기화가 활성화된 스레드에서 non-blocking으로 완주해야 한다"는 계약이 드러나 있지 않다. 현재 구현 `OutboxImmediatePublisher`는 `ApplicationEventPublisher.publishEvent` 만 호출하므로 실효 위험은 제로지만, 미래에 Kafka 구현체가 들어오면 `executeConfirmTx` TX 내부에서 블로킹 Kafka publish 로 귀결될 수 있다. Round 1 major-4 원안은 이 계약 문서화를 지적했고, T-D2는 stock commit/restore 만 이관했기에 이 finding 은 아직 해소되지 않았다. 다만 실효 위험 0 이므로 major→minor 로 격하.
- **evidence**:
  - `PaymentConfirmPublisherPort.java` 전 8행에 Javadoc 0건.
  - `PaymentTransactionCoordinator.java:81-82` 주석이 "AFTER_COMMIT 리스너가 드롭되지 않도록 TX 동기화가 활성 상태일 때 publish" 라고 정당화하지만, 포트 계약 자체가 이 제약을 강제하지 않음.
- **suggestion**: `PaymentConfirmPublisherPort` 에 Javadoc 추가 — "Implementations must complete in memory without blocking on remote I/O. TX 동기화가 활성화된 상태에서 호출되므로, `ApplicationEventPublisher.publishEvent` 계열만 허용되며 Kafka/HTTP 등 외부 호출 금지. Phase 4 도입 시 별도 outbox 경로로 위임." `OutboxImmediatePublisherTest` 에 non-blocking assertion(예: `Duration.ofMillis(50)` 이내 완주) 추가 권고. Phase 4 진입 후 후속 이슈로 처리 가능.

### [minor-2] `StockEventPublishingListener` AFTER_COMMIT 스왈로우로 stock 이벤트 유실 가능 (신규 — T-D2 도입 trade-off)

- **severity**: minor
- **checklist_item**: domain risk / 보상 멱등성
- **location**: `payment-service/src/main/java/com/hyoguoo/paymentplatform/payment/listener/StockEventPublishingListener.java:55-61, 77-83`
- **problem**: T-D2 가 stock commit/restore Kafka 발행을 AFTER_COMMIT 리스너로 이관하면서, Kafka 발행 실패 시 `catch (RuntimeException) { LogFmt.error + SWALLOW }` 패턴을 채택했다. 주석이 "TX 이미 commit 이므로 rollback 불가" 로 정당화하나, 이 경로에 outbox/재시도 안전망이 없어 Kafka broker 장애 중 완료된 결제의 stock commit 이벤트가 영구 유실될 수 있다. product-service 는 stock_snapshot warmup 으로 부분 복구하지만, 단일 commit 이벤트 유실 자체는 회복되지 않는다.
- **evidence**:
  - `StockEventPublishingListener.java:55-60`: `catch (RuntimeException e) { LogFmt.error(..., "action=SWALLOW(TX already committed)"); }` — re-throw 없음.
  - `PaymentConfirmResultUseCase.java:30` 주석 "Kafka 지연이 DB TX 블로킹으로 이어지지 않음" — Kafka 유실 시 복구 정책 언급 없음.
  - `T-D2 완료 결과` (PLAN.md:50): "Kafka 발행 실패 시 LogFmt.error 후 예외 삼킴(TX 이미 commit)" — 명시적 의도.
- **suggestion**: Phase 4 진입 후 후속 이슈로 처리 — stock commit/restore 도 payment_outbox 패턴으로 wrap(TX 안 INSERT만, 발행은 기존 `OutboxRelayService` 가 담당)하거나, 최소한 발행 실패 카운터(`stock_kafka_publish_fail_total`) + Grafana 알림 임계 정의 필요. 현 시점 하드닝 루프에서는 Phase 4 이전 차단사안은 아님.

## Scores (code stage)

- correctness: 0.94 (critical 2건 해소, minor 2건 잔존)
- conventions: 0.98 (null/catch Exception/평문 log 전수 축출)
- discipline: 0.95 (ARCHITECTURE drift 0, Javadoc 정정, PLAN scope 준수)
- test-coverage: 0.95 (19 태스크 전수 RED/GREEN 쌍, MdcPropagation/TwoPhaseLease/D2 테스트 신설)
- domain: 0.92 (AMOUNT_MISMATCH 양방향 방어선, TX-publish 분리, 보상 멱등 강화; stock AFTER_COMMIT 스왈로우만 minor)
- **mean: 0.948** (Round 1 0.776 → Round 2 0.948, +0.172 개선)

## Decision

**pass** — critical 0 / major 0 / minor 2. 판정 규칙: `minor 또는 n/a만` → **pass**. 하드닝 루프 종료 조건(critical 0 · major 0) 충족. 두 minor 는 Phase 4 진입 후 후속 이슈로 처리 가능.

## JSON

```json
{
  "stage": "code",
  "persona": "critic",
  "round": 2,
  "task_id": null,
  "decision": "pass",
  "reason_summary": "Round 1 critical 2건·major 5건·minor 3건이 T-A1~T-G3 19 태스크로 전수 해소. AMOUNT_MISMATCH 양방향 방어선(T-A1/A2), VT MDC 전파 3경로(T-E1), stock AFTER_COMMIT 분리(T-D2), catch (Exception)/null/평문 log 전수 0건(T-F1/F2/F3), ARCHITECTURE drift 0(T-F4). 잔존 minor 2건(PaymentConfirmPublisherPort Javadoc 계약 + StockEventPublishingListener AFTER_COMMIT 스왈로우)은 Phase 4 진입 후 후속 이슈.",
  "checklist": {
    "source": "_shared/checklists/code-ready.md",
    "items": [
      {"section": "task execution", "item": "19 태스크 RED/GREEN 커밋 쌍 존재", "status": "yes", "evidence": "git log T-A1(3f98e6df/0c210b2d) ~ T-G3(0ed3b977) 전수 확인"},
      {"section": "test gate", "item": "전체 ./gradlew test 통과", "status": "yes", "evidence": "T-G2 시점 512 PASS(각 태스크 완료 결과에서 회귀 없음 선언)"},
      {"section": "convention", "item": "null 반환 금지", "status": "yes", "evidence": "FakePgGatewayStrategy.java:102-105 UnsupportedOperationException throw — T-F1 해소"},
      {"section": "convention", "item": "catch (Exception e) 없음", "status": "yes", "evidence": "grep -rn 'catch (Exception' */src/main/java 결과 0건 — T-F2 해소"},
      {"section": "convention", "item": "신규 로깅 LogFmt 사용", "status": "yes", "evidence": "grep -rnE '^\\s*log\\.(warn|info|error|debug|trace)' */src/main/java 결과 0건, FakePgGatewayStrategy 배너는 LogFmt.banner 경유 — T-F3 해소"},
      {"section": "domain risk", "item": "상태 전이가 불변식을 위반하지 않음", "status": "yes", "evidence": "PaymentConfirmResultUseCase.java:169-202 handleApproved에서 수신 approvedAt/amount 사용 — T-A1/A2 해소"},
      {"section": "domain risk", "item": "race window 락/트랜잭션 격리", "status": "yes", "evidence": "PgOutboxImmediateWorker.java:70-74 + OutboxWorker.java:53-55 ContextExecutorService.wrap + AsyncConfig.java:27 MdcTaskDecorator — T-E1 해소"},
      {"section": "domain risk", "item": "보상/취소 멱등성", "status": "yes", "evidence": "QuarantineCompensationHandler isTerminal 가드 + PaymentEvent.quarantine IllegalStateException 이중 가드 — T-C2; EventDedupeStore two-phase lease P5M→P8D + DLQ 전송 — T-C3"},
      {"section": "domain risk", "item": "TX 외부 Kafka 발행", "status": "yes", "evidence": "PaymentConfirmResultUseCase.java:30 주석 + StockEventPublishingListener AFTER_COMMIT + @Transactional(timeout=5) — T-D2 해소"},
      {"section": "execution discipline", "item": "문서-코드 정합성", "status": "yes", "evidence": "ARCHITECTURE.md:220 'OutboxImmediateWorker/PaymentConfirmChannel 존재하지 않음' 명시, @CircuitBreaker Phase 4 설치 예정 정정 — T-F4/G1 해소"}
    ],
    "total": 10,
    "passed": 10,
    "failed": 0,
    "not_applicable": 0
  },
  "scores": {
    "correctness": 0.94,
    "conventions": 0.98,
    "discipline": 0.95,
    "test-coverage": 0.95,
    "domain": 0.92,
    "mean": 0.948
  },
  "findings": [
    {
      "severity": "minor",
      "checklist_item": "domain risk / race window 고려",
      "location": "payment-service/src/main/java/com/hyoguoo/paymentplatform/payment/application/port/out/PaymentConfirmPublisherPort.java:1-8; payment-service/.../PaymentTransactionCoordinator.java:84-95",
      "problem": "PaymentConfirmPublisherPort Javadoc이 전무해 '구현체는 TX 동기화 활성 스레드에서 non-blocking으로 완주해야 한다' 계약이 코드/문서로 강제되지 않는다. 현재 구현(OutboxImmediatePublisher = ApplicationEventPublisher)은 실효 위험 0이지만, Kafka 구현체 도입 시 executeConfirmTx TX 내부에서 블로킹 publish로 귀결 가능. Round 1 major-4 원안 잔존 — T-D2는 stock commit/restore만 이관했음.",
      "evidence": "PaymentConfirmPublisherPort.java 전 8행 Javadoc 0건 / PaymentTransactionCoordinator.java:81-82 TX 내부 publish 정당화 주석만 존재",
      "suggestion": "PaymentConfirmPublisherPort에 'Implementations must complete in memory without blocking on remote I/O' Javadoc 계약 명시. OutboxImmediatePublisherTest에 non-blocking assertion 추가(Duration 기반). Phase 4 진입 후 후속 이슈로 처리 가능."
    },
    {
      "severity": "minor",
      "checklist_item": "domain risk / 보상 멱등성",
      "location": "payment-service/.../listener/StockEventPublishingListener.java:55-61,77-83",
      "problem": "T-D2가 stock commit/restore Kafka 발행을 AFTER_COMMIT 리스너로 이관하면서 발행 실패 시 catch (RuntimeException) + SWALLOW 패턴 채택. 주석은 'TX already committed' 정당화하나 outbox/재시도 안전망 부재로 Kafka broker 장애 중 완료된 결제의 stock 이벤트가 영구 유실 가능. product-service stock_snapshot warmup이 부분 복구하나 단일 commit 이벤트 유실 자체는 회복 안 됨.",
      "evidence": "StockEventPublishingListener.java:55-60 catch (RuntimeException e) { LogFmt.error(..., 'action=SWALLOW(TX already committed)'); } 재throw 없음; PLAN.md:50 T-D2 완료 결과에 명시된 의도",
      "suggestion": "Phase 4 진입 후 후속 이슈 — stock commit/restore도 payment_outbox 패턴 wrap 또는 stock_kafka_publish_fail_total 카운터 + Grafana 알림. 현 하드닝 루프에서는 Phase 4 차단사안 아님."
    }
  ],
  "previous_round_ref": "review-critic-1.md",
  "delta": {
    "newly_passed": [
      "convention / null 반환 금지 (Round 1 critical-2 → pass)",
      "convention / catch (Exception e) 없음 (Round 1 major-5 → pass, 6건 → 0건)",
      "convention / 신규 로깅 LogFmt 사용 (Round 1 minor-1 → pass, FakePgGatewayStrategy 배너 LogFmt.banner)",
      "domain risk / 상태 전이 불변식 (Round 1 critical-1 → pass, approvedAt/amount 양방향)",
      "domain risk / race window 락/트랜잭션 (Round 1 major-1 → pass, VT MDC 3경로)",
      "domain risk / TX 외부 Kafka 발행 (Round 1 major-3 → pass, AFTER_COMMIT 이관)",
      "execution discipline / 문서-코드 정합성 (Round 1 major-2 + minor-3 → pass, ARCHITECTURE 재작성 + @CircuitBreaker 정정)",
      "domain risk / traceId 정확 추적 (Round 1 minor-2 → pass, parseHeaders 제거 + observation 주석)"
    ],
    "newly_failed": [
      "domain risk / 보상 멱등성 (Round 2 신규 minor: StockEventPublishingListener AFTER_COMMIT 스왈로우)"
    ],
    "still_failing": [
      "domain risk / PaymentConfirmPublisherPort Javadoc 계약 미명시 (Round 1 major-4 잔존, minor로 격하)"
    ]
  },
  "unstuck_suggestion": null
}
```
