# review-domain-1

**Topic**: EOS-FOLLOWUP-CLEANUP
**Round**: 1
**Persona**: Domain Expert

## Reasoning
돈이 새는 1차 경로(중복 차감/이중 보상)는 dedupe 만료 청소가 재배달 가능 윈도우를 침해하느냐에 달려 있는데, TTL(8일) > Kafka retention(7일) 불변식이 양쪽 서비스에 코드로 박혀 있고 `deleteExpired`는 `expires_at < now`만 지우므로 삭제 시점에는 메시지가 이미 retention 만료라 재배달 불가 — 멱등 윈도우 비침해를 확인했다. 가드 분리(A)도 두 메서드가 9상태에서 동일 분기라 동작 불변이며 교차 불변식 테스트가 드리프트를 막는다. critical/major 도메인 리스크는 발견되지 않았고, 관측성·운영 측면 minor 2건만 남는다.

## Domain risk checklist
- 상태 전이 올바름: PASS — `canApplyConfirmResult`/`canCompensateStock` 모두 READY/IN_PROGRESS/RETRYING=true, 나머지(DONE/FAILED/CANCELED/PARTIAL_CANCELED/EXPIRED/QUARANTINED)=false. 기존 `isCompensatableByFailureHandler`와 동일 분기 → 동작 0 변경. D7 침묵 DLQ 방어선(QUARANTINED→false) 보존 확인. grep `isCompensatableByFailureHandler` 0건.
- 멱등성/정합성: PASS — payment `markIfAbsent` INSERT IGNORE(PK event_uuid) 시맨틱 불변. product `recordIfAbsent`는 만료행 DELETE→INSERT IGNORE로 자체 정리(cleanup과 독립). cleanup은 `expires_at < now`만 삭제.
- dedupe 만료 청소 vs 재배달 윈도우: PASS (핵심) — payment `STOCK_COMMITTED_TTL=ofDays(8)`, product `DEDUPE_TTL=ofDays(8)`, Kafka retention 7일. 행 삭제 시점(expires_at 경과 = receivedAt+8일)에는 메시지가 retention(7일) 만료로 재배달 불가 → 이중 차감/이중 보상 유발 불가. PITFALLS §9(dedupe TTL ≠ retention) 정렬 유지.
- race window: PASS — `deleteExpired`는 멱등 batch DELETE(이미 삭제된 행 0 row). 단일 인스턴스 가정 주석화. 인덱스(`idx_expires_at`) 양 테이블 존재로 레인지 스캔.
- PG 실패 모드: N/A — 본 묶음은 PG 호출 경로 미변경. E는 관측성(traceparent) 전용, 비즈니스 비참여.
- PII: N/A — traceparent(W3C 추적 식별자)는 PII 아님. 금액·카드정보 신규 저장 없음.
- 금전 정확성: PASS — amount/approvedAt 검증 경로(handleApproved AMOUNT_MISMATCH 양방향 방어) 무변경.

## 도메인 관점 추가 검토

1. **[멱등 윈도우 — 핵심 안전 확인] C/D `deleteExpired`가 재배달 가능 행을 조기 삭제하지 않음.**
   `JdbcPaymentEventDedupeStore.java:31-34` / `JdbcEventDedupeStore.java:47-50` 모두 `WHERE expires_at < :now`. expires_at은 `PaymentConfirmResultUseCase.java:126,192`에서 `nowInstant().plus(STOCK_COMMITTED_TTL=ofDays(8))`, product는 `StockCommitUseCase.java:37 DEDUPE_TTL=ofDays(8)`. Kafka retention 7일이므로 행이 삭제 대상이 되는 시점(생성 후 8일 경과)엔 원본 메시지가 이미 retention 만료 → 재배달 물리적 불가. 따라서 "삭제된 dedupe 행 + 동일 event_uuid 재도착 → affected=1 신규 재처리 → 이중 차감" 시나리오는 성립하지 않는다. 멱등 SoT 비파괴.

2. **[가드 분리 — 동작 불변 + 회귀 방어] A-1~A-3.**
   `PaymentEventStatus.java:42-68` 두 메서드의 switch arm이 완전 동일(READY/IN_PROGRESS/RETRYING→true). 호출처는 `PaymentConfirmResultUseCase.java:113`(canApplyConfirmResult), `PaymentTransactionCoordinator.java:141`(canCompensateStock)로 의미적 분리 정확. QUARANTINED→false 유지로 D7 침묵 DLQ 1차 방어선 보존(PITFALLS §21). `PaymentEventStatusCrossInvariantTest`가 두 가드 답 동조를 명시 단언 → 한쪽만 드리프트 시 RED. 도메인 리스크 측면 개선(겸용 메서드 오용 차단).

3. **[product `existsValid` vs cleanup 상호작용 — 무해 확인]**
   `JdbcEventDedupeStore.java:38-39` `existsValid`는 `expires_at >= NOW()`로 SELECT 시점 만료 판단. cleanup이 만료 행을 먼저 지워도 만료 행은 어차피 not-valid 취급이라 멱등 가드 결과 불변. `recordIfAbsent`도 만료행 자체 DELETE→재삽입(line 80-84)이라 cleanup과 독립 — 이중 안전망.

4. **[B TM qualifier — 빈 이름 일치 확인]** `PaymentConfirmResultUseCase.java:106` `@Transactional(transactionManager="transactionManager", timeout=5)`가 `JpaConfig.java:35-37` `@Primary` `transactionManager` 빈과 정확히 일치 → 런타임 빈 조회 실패 위험 없음. CONFIRM-FLOW §5 SSOT(JPA TM ≠ Kafka EOS TM, best-effort 1PC, 위키 line 141이 crash 내성 담당) 서술과 정합. 동작 변경 0.

5. **[E traceparent — 비즈니스 비참여 확인]** pg-service domain 패키지에 traceparent 누출 0건(`PgInbox` 무변경). `PgInboxEntity`에만 컬럼, INSERT IGNORE로 기존 행 보존(덮어쓰기 없음, `PgInboxRepositoryImpl.java:96`). 결제 판정·금액·상태 전이에 미참여. 관측성 전용으로 도메인 리스크 없음.

6. **[minor] product `DedupeCleanupWorker`가 `Instant.now()` 직접 호출.**
   `product/.../scheduler/DedupeCleanupWorker.java:64`. payment 측(`payment/.../DedupeCleanupWorker.java:71`)은 `localDateTimeProvider.nowInstant()` 주입을 쓰는데 product는 직접 호출 — PITFALLS §6(LocalDateTime.now 직접 호출 금지, 시간 위조 불가) 패턴 불일치. 다만 만료 청소는 시각 경계가 보수적(8일 vs 7일 여유 1일)이라 금전 영향 없고 테스트도 Mockito로 deleteExpired 반환만 검증하므로 도메인 리스크는 minor. product-service에 `LocalDateTimeProvider`가 없다는 완료 결과 주석이 근거지만, 일관성·테스트 용이성 차원의 후속 정리 여지.

7. **[minor] cleanup worker `catch (RuntimeException)` 후 0 반환 — swallow 경계 점검.**
   양 worker `executeDeleteExpired`가 예외를 ERROR 로그 + return 0으로 흡수. PITFALLS §5(catch swallow)의 "절대 죽으면 안 되는 워커 경로 + ERROR 승격 + 메트릭" 예외 조건엔 부합하나, 실패가 누적되면 만료 행이 무한 적재될 수 있다. 단 dedupe 행 적재는 정합성(돈)에 무해하고 디스크/성능 이슈일 뿐이라 도메인 리스크 minor. ERROR 로그는 있으나 `*_fail_total` 류 실패 카운터는 없음 — 관측성 후속 여지(현재 성공 삭제 카운터만 존재).

## Findings
- **[minor] product DedupeCleanupWorker가 `Instant.now()` 직접 호출** — `product-service/.../infrastructure/scheduler/DedupeCleanupWorker.java:64`. PITFALLS §6 패턴 및 payment 측 worker와 불일치. 금전 영향 없음(만료 경계 보수적). 일관성/테스트 용이성 후속 정리 권고.
- **[minor] cleanup 실패 시 실패 카운터 부재** — 양 worker `executeDeleteExpired`. 예외 흡수+ERROR 로그는 있으나 `cleanup_failed_total` 류 메트릭 없음. 반복 실패 시 만료 행 무한 적재(정합성 무해, 성능/디스크). 관측성 후속 여지.

## JSON
```json
{
  "stage": "review",
  "topic": "EOS-FOLLOWUP-CLEANUP",
  "round": 1,
  "persona": "domain-expert",
  "verdict": "pass",
  "findings": [
    {
      "id": "RD-DOM-1",
      "severity": "minor",
      "area": "observability/time-source",
      "summary": "product DedupeCleanupWorker가 Instant.now() 직접 호출 — PITFALLS §6 및 payment worker(LocalDateTimeProvider)와 불일치",
      "evidence": "product-service/.../infrastructure/scheduler/DedupeCleanupWorker.java:64",
      "domain_risk": "낮음 — 만료 경계 보수적(TTL 8일 > retention 7일), 금전 영향 없음. 시간 위조 테스트 용이성/일관성 차원"
    },
    {
      "id": "RD-DOM-2",
      "severity": "minor",
      "area": "observability/failure-metric",
      "summary": "cleanup 예외 흡수 후 실패 카운터 부재 — 반복 실패 시 만료 행 무한 적재 무가시",
      "evidence": "payment/product DedupeCleanupWorker.executeDeleteExpired (성공 삭제 카운터만 존재)",
      "domain_risk": "낮음 — 정합성(돈) 무해, 성능/디스크 누적 이슈"
    }
  ],
  "domain_checks": {
    "state_transition": "pass",
    "idempotency": "pass",
    "dedupe_ttl_vs_redelivery_window": "pass",
    "race_window": "pass",
    "pg_failure_mode": "n/a",
    "pii": "n/a",
    "monetary_accuracy": "pass"
  },
  "verdict_rule": "critical=0, major=0, minor=2 → pass"
}
```
