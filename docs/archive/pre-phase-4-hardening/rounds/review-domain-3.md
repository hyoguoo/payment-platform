# code-domain-3

**Topic**: pre-phase-4-hardening
**Round**: 3
**Persona**: Domain Expert

## Reasoning

Round 2 잔여 minor 1건(AFTER_COMMIT stock Kafka publish swallow)은 T-H2에서 `stock.kafka.publish.fail.total` counter(tag event=commit/restore) 직접 호출 + `TODOS.md` "Phase 4 후속: stock commit/restore payment_outbox 이관" 항목(배경·방안 A/B·Grafana 알림·제안 시점·관련 파일 5섹션)으로 관측성과 추후 이관 경로가 확보되어 "문서·metric 감시로 관리되는 상태"로 전이했다. T-H1은 `PaymentConfirmPublisherPort` Javadoc(in-memory 즉시 완주·원격 I/O 차단 금지·AFTER_COMMIT 리스너 위임)에 더해 `OutboxImmediatePublisherTest.publish_shouldCompleteSynchronouslyUnder50ms` non-blocking assertion이 박혀 향후 Kafka 동기 발행 회귀를 실제 테스트가 막는다 — 계약이 글이 아닌 가드로 성립. Round 1/2 통과 항목(FAILED qty=실수량 복원, dedupe TTL P8D, QUARANTINED 역전이 가드, Redis DECR 보상, lease 2단계+DLQ, 다중 홉 traceparent, APPROVED amount/approvedAt non-null) 모두 회귀 없음 — 돈이 새는 경로는 현 시점 차단됐고, 남은 잔여 리스크는 Phase 4 Toxiproxy에서 추가 검증될 scope 로 이월됐다. **완전 종료 판정 가능.**

## Domain risk checklist (Round 3 재판정)

- [x] FAILED 경로 실제 재고 복원 동작 (R1 critical-1 CLOSED Round 2 → Round 3 회귀 없음)
- [x] 멱등성 TTL ↔ 메시지 보관 윈도우 정렬 (R1 critical-2 CLOSED 유지)
- [x] 다중 홉 traceId 연속성 (R1 major-3 CLOSED 유지)
- [x] Redis DECR ↔ confirm TX 원자성 (R1 major-4 CLOSED 유지)
- [x] 상태 전이 불변식 QUARANTINED 역전이 차단 (R1 major-5 CLOSED 유지)
- [x] 멱등성 Redis flap 영구 누락 차단 (R1 major-6 CLOSED 유지)
- [x] APPROVED 계약 amount/approvedAt non-null (신규 축 PASS 유지)
- [x] DuplicateApprovalHandler eventUuid 오배치 제거 (R1 minor-7 CLOSED 유지)
- [x] outbox relay headers_json 잔재 제거 (R1 minor-8 CLOSED 유지)
- [x] QUARANTINED 운영자 복구 문서 (R1 minor-9 CLOSED 유지)
- [x] **신규(R3): `PaymentConfirmPublisherPort` non-blocking 계약 가드** — Javadoc 명시 + Test에서 50ms assertion (R2 minor-R2-1-b CLOSED)
- [x] **신규(R3): AFTER_COMMIT stock publish swallow metric 감시** — `stock.kafka.publish.fail.total{event=commit|restore}` counter 증가 + TODOS.md Phase 4 outbox 이관 항목 등록 (R2 minor-R2-1 문서·관측성 이관으로 CLOSED)

## 도메인 관점 추가 검토 (Round 3 신규)

1. **T-H1 계약의 실효성 검증 — 테스트가 계약을 강제하는가**
   - `PaymentConfirmPublisherPort` Javadoc: "구현체는 in-memory 로 즉시 완주해야 한다 — 원격 I/O(Kafka 등)로 블로킹 금지" 명시.
   - 현재 유일 구현체 `OutboxImmediatePublisher.publish` (`payment-service/.../infrastructure/publisher/OutboxImmediatePublisher.java:17-19`) 는 `applicationEventPublisher.publishEvent(PaymentConfirmEvent.of(...))` 단일 호출 — TX 동기화 활성 스레드에서 in-memory 즉시 완주.
   - `OutboxImmediatePublisherTest.publish_shouldCompleteSynchronouslyUnder50ms` (`payment-service/src/test/.../OutboxImmediatePublisherTest.java:63-78`) 는 `Duration.between(before, after) < 50ms` assertion. 향후 이 포트에 Kafka 동기 send 를 박는 구현체가 들어오면 바로 실패.
   - **실효성 판정**: TX 내부 `PaymentTransactionCoordinator.executeConfirmTx:88-93` 의 `confirmPublisher.publish` 호출 경로가 여전히 TX 경계 안이므로 포트 계약 위반은 즉시 Hikari 풀 고갈로 이어진다. Javadoc + 50ms assertion 은 Phase 4 에서 다른 개발자가 "편의상" Kafka 직접 발행 구현을 추가하는 것을 막는 실질 가드. PASS.

2. **T-H2 counter 태그 분리의 운영 효용**
   - `StockEventPublishingListener.onStockCommitRequested:80-88` / `onStockRestoreRequested:104-112` 각각 `commitFailCounter.increment()` / `restoreFailCounter.increment()` — 동일 metric 이름 `stock.kafka.publish.fail.total` + tag `event=commit|restore` 분리. Prometheus 쿼리에서 `rate(stock_kafka_publish_fail_total{event="commit"}[5m])` vs `restore` 로 양방 독립 알림 가능.
   - ERROR 로그 메시지에 `metric=stock.kafka.publish.fail.total[event=commit]++` 문자열이 박혀 로그 드릴다운 시 metric 이름 즉시 식별 가능 — 운영자 MTTR 관점에서 유효.
   - **판정**: counter 가 실제 분기별로 증가하고 TC-H2-1/TC-H2-2 로 가드됨. PASS.

3. **TODOS.md "Phase 4 후속: stock commit/restore payment_outbox 이관" 항목의 맥락 충실성**
   - 배경: T-D2 AFTER_COMMIT swallow 결정 + T-H2 counter 추가의 연속성 명시.
   - 방안: A(`stock_outbox` 신설 + relay) / B(`payment_outbox` 재사용) 2안 제시 — Phase 4 결정권자에게 충분한 진입점.
   - Grafana 알림: `rate` 기반 + 임계(1회/분) 명시.
   - 제안 시점: "Phase 4 Toxiproxy Kafka 중단 시나리오 검증 후" 명확.
   - 관련 파일: 3건 경로 명시.
   - **판정**: 이관 시 재진입할 사람이 10분 내에 맥락 복원 가능한 수준. 문서 관리 상태로 전이 충분.

4. **Phase 4 진입 차단 가능성 재확인 (Round 2 결론 재검토)**
   - (a) FAILED 경로 qty=실수량 전달 — CLOSED, 회귀 없음.
   - (b) Redis DECR ↔ TX 보상 — `OutboxAsyncConfirmService.executeConfirmTxWithStockCompensation` TX catch 에서 `stockCachePort.increment` 보상, CLOSED.
   - (c) dedupe lease 2단계 — `markWithLease(PT5M)` + `extendLease(P8D)` + remove 실패 DLQ 경로, CLOSED.
   - (d) QUARANTINED 역전이 — `isTerminal` 가드 + `PaymentEvent.quarantine` 도메인 이중 가드, CLOSED.
   - (e) traceparent 다중 홉 — Boot auto-config Builder 주입 + VT `ContextExecutorService.wrap` + `@Async` `MdcTaskDecorator`, CLOSED.
   - (f) AFTER_COMMIT stock publish 유실 — counter + Phase 4 outbox 이관 TODO, 문서 이관으로 CLOSED.
   - (g) HTTP/Kafka/Redis 홉별 traceparent 전파는 T-E1/E2/E3 테스트 경로로 검증됐으며 compose-up 기반 실 스모크는 사용자 로컬 실행 범위 — 이 리뷰 scope 밖(명시된 바).
   - **판정**: 현재 상태로 Phase 4 Toxiproxy/k6 진입 시 "추가로 돈 문제가 터질 경로" 미식별. PASS.

5. **회귀 검증 — Round 1/2 통과 항목의 안정성**
   - 최근 5개 커밋(`ddcbc6f6`, `9410c667`, `cdbf1c1f`, `37df7f8b`, `0ed3b977`) 중 T-H1/T-H2 만이 행위 변경 — 각각 `PaymentConfirmPublisherPort.java` Javadoc 추가(계약 문서화만) + `OutboxImmediatePublisherTest` 테스트 추가 / `StockEventPublishingListener.java` counter 필드 주입 + catch 블록 counter.increment() + TODOS.md 추가. 이외 소스 변경 없음.
   - T-H1 은 Javadoc + 테스트 only — 기존 경로 회귀 가능성 0.
   - T-H2 는 catch 블록 내부 confirm metric 호출만 추가 — swallow 시맨틱 유지, TX 경계 영향 없음.
   - **판정**: Round 2 CLOSED 항목 회귀 없음. PASS.

## Findings

Round 3 에서 identify 된 critical/major/minor finding **없음**. 완전 종료 판정.

## JSON

```json
{
  "stage": "code",
  "persona": "domain-expert",
  "round": 3,
  "task_id": null,

  "decision": "pass",
  "reason_summary": "Round 2 잔여 minor 1건(AFTER_COMMIT stock publish swallow)은 T-H2에서 stock.kafka.publish.fail.total counter(tag 분리) + TODOS.md Phase 4 outbox 이관 항목 등록으로 '문서·metric 감시' 상태로 전이. T-H1은 PaymentConfirmPublisherPort Javadoc 계약 + OutboxImmediatePublisherTest 50ms non-blocking assertion 으로 향후 Kafka 동기 구현 회귀를 테스트가 강제. Round 1/2 통과 항목 회귀 없음. Phase 4 Toxiproxy/k6 진입 차단 요인 미식별. 완전 종료 판정.",

  "checklist": {
    "source": "_shared/checklists/code-ready.md#domain-risk",
    "items": [
      {
        "section": "domain risk",
        "item": "보상/취소 로직에 멱등성 가드 존재 (FAILED 재고 실 복원)",
        "status": "yes",
        "evidence": "PaymentConfirmResultUseCase.handleFailed가 FailureCompensationService.compensate(orderId, productId, qty) 순회 — Round 2 CLOSED 유지, 커밋 회귀 없음."
      },
      {
        "section": "domain risk",
        "item": "race window에 락/트랜잭션 격리 고려됨 (Redis DECR ↔ TX 원자성)",
        "status": "yes",
        "evidence": "OutboxAsyncConfirmService.executeConfirmTxWithStockCompensation TX catch에서 stockCachePort.increment 보상 — Round 2 CLOSED 유지."
      },
      {
        "section": "domain risk",
        "item": "상태 전이 불변식 위반 없음 (QUARANTINED 역전이 차단)",
        "status": "yes",
        "evidence": "QuarantineCompensationHandler.handle isTerminal no-op + PaymentEvent.quarantine 도메인 IllegalStateException — Round 2 CLOSED 유지."
      },
      {
        "section": "domain risk",
        "item": "PG ALREADY_PROCESSED 계열 특수 응답이 정당성 검증을 거침",
        "status": "yes",
        "evidence": "DuplicateApprovalHandler.handleDuplicateApproval(orderId, amount) 2-arg 유지 — Round 2 CLOSED 유지."
      },
      {
        "section": "domain risk",
        "item": "PII/paymentKey plaintext 로그 노출 없음",
        "status": "yes",
        "evidence": "LogFmt 경로 점검 — 벤더 PII 로깅 0건. Round 2 대비 변경 없음."
      },
      {
        "section": "domain risk (추가)",
        "item": "사고 재구성을 위한 traceId가 다중 홉에 연속 전파됨",
        "status": "yes",
        "evidence": "HttpOperatorImpl Builder 주입 + VT ContextExecutorService.wrap + @Async MdcTaskDecorator — Round 2 CLOSED 유지."
      },
      {
        "section": "domain risk (추가)",
        "item": "멱등성 키 TTL이 Kafka retention·DLQ 재처리 주기와 정렬됨",
        "status": "yes",
        "evidence": "EventDedupeStoreRedisAdapter 기본 P8D + application.yml ttl: P8D 명시 — Round 2 CLOSED 유지."
      },
      {
        "section": "domain risk (추가)",
        "item": "APPROVED 이벤트 계약 amount/approvedAt non-null 보장",
        "status": "yes",
        "evidence": "ConfirmedEventPayload.approved 팩토리 Objects.requireNonNull + 4개 발행 경로 Clock fallback — Round 2 CLOSED 유지."
      },
      {
        "section": "domain risk (추가)",
        "item": "QUARANTINED 홀딩 자산 복구 경로 문서화",
        "status": "yes",
        "evidence": "ARCHITECTURE.md Quarantine Recovery 섹션 + TODOS.md QUARANTINED-ADMIN-RECOVERY 항목 — Round 2 CLOSED 유지."
      },
      {
        "section": "domain risk (신규 R3)",
        "item": "TX 내부 PaymentConfirmPublisherPort non-blocking 계약 가드",
        "status": "yes",
        "evidence": "PaymentConfirmPublisherPort.java:8-18 Javadoc(in-memory 즉시 완주·원격 I/O 차단 금지·AFTER_COMMIT 리스너 위임) + OutboxImmediatePublisherTest.publish_shouldCompleteSynchronouslyUnder50ms 50ms assertion — 향후 Kafka 동기 구현 회귀 강제 차단."
      },
      {
        "section": "domain risk (신규 R3)",
        "item": "AFTER_COMMIT stock Kafka publish swallow 관측성 + 이관 문서",
        "status": "yes",
        "evidence": "StockEventPublishingListener:45-61, 80-88, 104-112 commitFailCounter/restoreFailCounter 태그 분리 + catch 블록 increment + TODOS.md:8-36 'Phase 4 후속: stock commit/restore payment_outbox 이관' 항목(배경·방안 A/B·Grafana 알림·제안 시점·관련 파일 5섹션)."
      }
    ],
    "total": 11,
    "passed": 11,
    "failed": 0,
    "not_applicable": 0
  },

  "scores": {
    "correctness": 0.94,
    "conventions": 0.92,
    "discipline": 0.92,
    "test-coverage": 0.90,
    "domain": 0.95,
    "mean": 0.926
  },

  "findings": [],

  "previous_round_ref": "review-domain-2.md",
  "delta": {
    "newly_passed": [
      "TX 내부 PaymentConfirmPublisherPort non-blocking 계약 가드 (T-H1)",
      "AFTER_COMMIT stock Kafka publish swallow 관측성 + 이관 문서 (T-H2)"
    ],
    "newly_failed": [],
    "still_failing": []
  },

  "unstuck_suggestion": null
}
```
