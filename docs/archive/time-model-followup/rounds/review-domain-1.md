# review-domain-1

**Topic**: TIME-MODEL-FOLLOWUP
**Round**: 1
**Persona**: Domain Expert

## Reasoning
D1 멱등 경계(앱 단일 시계 `now` 통일 + strict `<` 만료 DELETE + 경계 동치 행 잔존으로 재차감 skip 멱등 보존)와 D4 정산 앵커(created_at cutoff·auditing Instant 전환)는 소스/통합테스트 교차 검증에서 돈 새는 경로·만료 경계 회귀가 없음을 확인했다. native cutoff 비교와 round-trip은 Testcontainers MySQL에서 실제 5/5 PASS(product 멱등 경계 5/5 PASS)했고, approvedAt 9시간 오차 회귀(`.toLocalDateTime()`)·NOW() 잔재는 0건이다. 유일한 흠은 만료 cutoff 경로 native query 주석(`JpaPaymentEventRepository:16,19`)이 P14 이후 created_at을 여전히 `LocalDateTime(DATETIME)`로 서술하는 stale로, 동작 영향 없는 minor다.

## Domain risk checklist
- [n/a] paymentKey/orderId/카드번호 plaintext 로그 노출 — 본 diff는 시간 모델/타입 전환만, 신규 PII 로깅 경로 없음.
- [yes] 보상/취소 멱등성 가드 — `recordIfAbsent` 만료 경계 동치(`expires_at == now`) 행 잔존 → 동일 uuid 재진입 false 반환으로 중복 재고 차감 skip 멱등 유지(JdbcEventDedupeStore:84, RoundTripTest D6 경계 테스트 PASS).
- [n/a] PG "이미 처리됨" 특수 응답 검증 — 본 diff 범위 외(PG 연동 무변경).
- [yes] 상태 전이 불변식 — 상태 머신 enum 무변경, audit 시각 타입만 전환. SUCCESS→FAIL 등 불변식 영향 없음.
- [yes] race window 락/격리 — `recordIfAbsent` DELETE→INSERT IGNORE가 `StockCommitUseCase.commit` `@Transactional` 안에서 동일 TX 참여(JdbcEventDedupeStore:35 주석). now가 consumer 진입점 단일 `clock.instant()`라 시계 split 위험 제거.
- [추가] 시각 소스 단일성 — consumer `now = clock.instant()` 1회 산출 후 commit(now) + resolveExpiresAt(now) 양쪽 동일 Instant 공유(StockCommitConsumer:64-66). 어댑터 내부 self-clock 호출 0건(헥사고날 D2 정합).
- [추가] 정산 앵커 무변형 — approvedAt/executedAt은 이미 Instant(무변경). `.toLocalDateTime()` 잔존 0건(PITFALLS §13 9시간 오차 회귀 없음).
- [추가] 만료 cutoff UTC 일관 — created_at Instant 전환 후 native query `WHERE created_at < :before`가 hibernate.jdbc.time_zone=UTC 바인딩으로 전환 전후 동일 UTC 비교(round-trip 통합 PASS).
- [추가] auditing UTC 일관 — clockDateTimeProvider Instant 반환, dateTimeProviderRef 빈 연결 가드 + 실제 채움 가드 GREEN(직전 DM1 회귀 재발 없음).

## 도메인 관점 추가 검토
1. **[검증완료] D1 만료 경계 멱등 보존** — `JdbcEventDedupeStore.recordIfAbsent`(라인 83-110): DELETE 조건이 `expires_at < ?`(strict)로 바뀌고 `now`를 호출자 주입 받음. 경계 동치(`expires_at == now`) 행은 DELETE되지 않아 잔존 → 동일 uuid 재기록 시 INSERT IGNORE 0 row → false 반환 → `StockCommitUseCase.commit`에서 `commitToRdb` skip. 중복 재고 차감 0 멱등 유지. `RoundTripTest.recordIfAbsent_nonUtcJvm_expiredRow삭제경계_만료행만삭제`가 expired/active/boundary 3종으로 경계를 직접 단정, Testcontainers 5/5 PASS.
2. **[검증완료] now/expiresAt 단일 시계 소스** — `StockCommitConsumer`(라인 64-66, 90-92): `Instant now = clock.instant()` 1회 산출 후 `resolveExpiresAt(message, now)` fallback base와 `commit(..., now, expiresAt)` 양쪽에 동일 Instant 전달. 어댑터(`JdbcEventDedupeStore`)는 `clock` 필드를 보유하나 `recordIfAbsent` 경로에서 self `clock.instant()` 미호출 — split-brain 시계 재발 차단(D1 헥사고날 원칙).
3. **[검증완료] D4 정산/만료 앵커 무변형** — `BaseEntity`(라인 18-26) 3필드 Instant + DATETIME(6) 전환, `createdAt @Column(updatable=false)` 보존. `PaymentEventEntity.toDomain()`이 `getCreatedAt().toInstant(UTC)` → `getCreatedAt()` 직접 대입으로 단순화됐고 도메인 `PaymentEvent.createdAt`(이미 Instant)와 타입 정합. `PaymentOutboxEntity`의 `toInstant`/`toLocalDateTime` 헬퍼는 nextRetryAt/inFlightAt(LocalDateTime 비즈니스 컬럼)에 잔존(P16 AC 준수, 라인 75-76/82-94). approvedAt/executedAt 무변경.
4. **[검증완료] created_at 만료 cutoff UTC 정합** — `JpaPaymentEventRepository.findReadyPaymentsOlderThan`(라인 20-22) native query가 `Instant before`를 바인딩. created_at이 Instant 매핑으로 전환된 후에도 hibernate.jdbc.time_zone=UTC 기준 UTC Calendar 바인딩이라 cutoff 비교 일관. `PaymentEventRepositoryImplTest.findReadyPaymentsOlderThan_withInstantCutoff` + `save_createdAt_Instant_roundTrip` 통합 5/5 PASS로 native 비교·round-trip 정밀도(DATETIME(6) 서브초 보존) 확인.
5. **[검증완료] V4 DDL 안전성** — `V4__audit_datetime6_upgrade.sql`: 4테이블×3컬럼 MODIFY DATETIME(6). nullable+DEFAULT 없음 V1 정의 보존. `payment_outbox.created_at`(복합 인덱스 키)·`payment_history.created_at`(단일 인덱스 키) MODIFY 시 MySQL 자동 인덱스 재구성, V1→V4 순차 부팅 통합 에러 없음.
6. **[검증완료] TZ backstop·connectionTimeZone 중첩 무해** — D3 3겹(Dockerfile ENV/JVM -Duser.timezone/compose) 동일값 UTC 멱등 중복. product `connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true` URL 값 무변경 존치(주석만 D5 갱신). raw-JDBC Timestamp 바인딩 backstop과 이중 변환 충돌 없음(UTC Calendar 명시 바인딩이 1차 보장). product main `NOW()` 잔재 0건.
7. **[minor — stale 주석] `JpaPaymentEventRepository.java:16,19`** — 만료 cutoff native query 주석이 P14 이후에도 created_at을 "`LocalDateTime(DATETIME)` 컬럼"으로 서술. 실제는 Instant + DATETIME(6). 동작은 통합테스트로 정합 확인됐으나, 만료 경계 경로의 핵심 주석이 틀린 타입/정밀도를 말해 미래 독자가 cutoff 정합 근거를 재추적하게 만든다. DM-F2(stale 주석 minor) 전례와 동급. 동작 무영향이라 minor.

## Findings
- minor: `JpaPaymentEventRepository.java:16-19` 만료 cutoff native query 주석 stale (created_at을 LocalDateTime(DATETIME)로 서술, 실제 Instant+DATETIME(6)).

## JSON
```json
{
  "stage": "code",
  "persona": "domain-expert",
  "round": 1,
  "task_id": null,

  "decision": "pass",
  "reason_summary": "D1 멱등 경계(strict < 만료 DELETE + 경계 동치 잔존 재차감 skip 멱등)·D4 정산/만료 앵커(created_at cutoff·auditing Instant) 회귀 없음, 통합테스트 PASS. 만료 cutoff native query 주석 stale 1건(minor)만 잔존.",

  "checklist": {
    "source": "_shared/checklists/code-ready.md",
    "items": [
      {
        "section": "domain risk",
        "item": "보상/취소 로직에 멱등성 가드 존재",
        "status": "yes",
        "evidence": "JdbcEventDedupeStore.java:83-110 recordIfAbsent strict < 만료 DELETE + 경계 동치 행 잔존, RoundTripTest D6 경계 테스트 5/5 PASS"
      },
      {
        "section": "domain risk",
        "item": "상태 전이가 불변식을 위반하지 않음",
        "status": "yes",
        "evidence": "상태 머신 enum 무변경, audit 시각 타입(LocalDateTime→Instant)만 전환"
      },
      {
        "section": "domain risk",
        "item": "race window가 있는 경로에 락/트랜잭션 격리 고려됨",
        "status": "yes",
        "evidence": "recordIfAbsent DELETE→INSERT IGNORE가 StockCommitUseCase.commit @Transactional 동일 TX 참여(JdbcEventDedupeStore.java:35), now consumer 단일 clock.instant() 1회 산출(StockCommitConsumer.java:64-66)로 시계 split 제거"
      },
      {
        "section": "domain risk",
        "item": "paymentKey/orderId/카드번호 plaintext 로그 노출 없음",
        "status": "n/a",
        "evidence": "시간 모델/타입 전환 diff, 신규 PII 로깅 경로 없음"
      },
      {
        "section": "domain risk(추가)",
        "item": "정산 앵커(approvedAt/executedAt) 무변형 + 9시간 오차 회귀 없음",
        "status": "yes",
        "evidence": "approvedAt/executedAt 이미 Instant 무변경, payment-service main .toLocalDateTime() approvedAt 경로 잔존 0건(grep), PITFALLS §13"
      },
      {
        "section": "domain risk(추가)",
        "item": "created_at 만료 cutoff UTC 일관 (D4 타입 전환 후)",
        "status": "yes",
        "evidence": "JpaPaymentEventRepository.findReadyPaymentsOlderThan native query Instant 바인딩, PaymentEventRepositoryImplTest cutoff+roundtrip 통합 5/5 PASS (Testcontainers MySQL)"
      },
      {
        "section": "domain risk(추가)",
        "item": "auditing UTC 일관 (clockDateTimeProvider Instant 전환, DM1 회귀 가드)",
        "status": "yes",
        "evidence": "JpaConfig.clockDateTimeProvider clock.instant() 반환, JpaAuditingProviderWiringTest dateTimeProviderRef 빈 연결+반환타입 가드 GREEN"
      },
      {
        "section": "domain risk(추가)",
        "item": "V4 DDL 인덱스 키 컬럼·NOT NULL/DEFAULT 보존",
        "status": "yes",
        "evidence": "V4__audit_datetime6_upgrade.sql nullable+DEFAULT 없음 보존, idx_payment_outbox_status_retry_created/idx_payment_history_created_at MySQL 자동 재구성, V1→V4 순차 부팅 무에러"
      },
      {
        "section": "domain risk(추가)",
        "item": "TZ backstop·connectionTimeZone 이중 변환 충돌 없음",
        "status": "yes",
        "evidence": "D3 3겹 동일값 UTC 멱등 중복, connectionTimeZone URL 무변경 존치(주석만 갱신), product main NOW() 잔재 0건(grep)"
      },
      {
        "section": "domain risk(추가)",
        "item": "만료 cutoff native query 주석이 created_at 실제 타입과 정합",
        "status": "no",
        "evidence": "JpaPaymentEventRepository.java:16,19 — created_at을 LocalDateTime(DATETIME)로 서술하나 실제 Instant+DATETIME(6) (P14 이후 stale)"
      }
    ],
    "total": 10,
    "passed": 8,
    "failed": 1,
    "not_applicable": 1
  },

  "scores": {
    "correctness": 0.92,
    "conventions": 0.90,
    "discipline": 0.93,
    "test-coverage": 0.91,
    "domain": 0.90,
    "mean": 0.912
  },

  "findings": [
    {
      "severity": "minor",
      "checklist_item": "만료 cutoff native query 주석이 created_at 실제 타입과 정합",
      "location": "payment-service/src/main/java/com/hyoguoo/paymentplatform/payment/infrastructure/repository/JpaPaymentEventRepository.java#L16-L19",
      "problem": "만료 cutoff native query(findReadyPaymentsOlderThan)의 주석이 P14 이후에도 created_at 을 'LocalDateTime(DATETIME) 컬럼'으로 서술한다. 실제는 BaseEntity 전환으로 Instant + DATETIME(6) 이다. 만료 경계 경로의 핵심 주석이 틀린 타입·정밀도를 말해 미래 독자가 cutoff UTC 정합 근거를 잘못 추적할 수 있다.",
      "evidence": "BaseEntity.java:18 created_at columnDefinition='datetime(6)' + Instant 전환 vs JpaPaymentEventRepository.java:16 'BaseEntity.createdAt 은 LocalDateTime(DATETIME) 컬럼이나'. 동작 정합은 PaymentEventRepositoryImplTest 통합 5/5 PASS로 확인됨(기능 무영향).",
      "suggestion": "주석을 'created_at 은 Instant(DATETIME(6)) 컬럼이며, Instant 파라미터를 hibernate.jdbc.time_zone=UTC 로 UTC Calendar 바인딩해 JdbcTemplate(connectionTimeZone=UTC) 경로와 동일 UTC 기준으로 비교된다'로 정정."
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
