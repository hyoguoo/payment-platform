# plan-domain-1

**Topic**: TIME-MODEL-FOLLOWUP
**Round**: 1
**Persona**: Domain Expert

## Reasoning
재고 멱등 저장소의 `NOW()`→앱 주입 `Instant` 전환(D1)은 만료 비교 split-brain을 제거하는 안전 방향이고, DELETE 경계·UTC Calendar 바인딩·단일 진입점 동일 시각 불변이 P2/P5 AC에 명시돼 멱등성(중복 재고 차감 방지)을 깨지 않는다. BaseEntity Instant 전환(D4) 연쇄는 직전 토픽 auditing 회귀 major 전례를 P11→P12→P13 순서와 `updatable=false` 보존 AC로 정면 방어한다. 다만 P13(엔티티 datetime(6)+Spring 부팅 확인)이 P14(DDL ALTER) 앞에 와 `ddl-auto: validate` 부팅 시점 스키마 불일치 가능성이 AC에서 모호하다.

## Domain risk checklist
- [x] discuss domain risk(DM-F1 now 전파 경로 / DM-F2 stale 주석)가 각각 대응 태스크 보유 — DM-F1 → P4(useCase now 인자)+P5(consumer clock.instant() 산출 후 전달), DM-F2 → P10. 매핑 테이블(PLAN §discuss findings)에 명시.
- [x] 중복 방지 체크가 필요한 경로에 계획됨 — `recordIfAbsent` INSERT IGNORE(event_uuid PK) 멱등 SSOT는 D1 전환 후에도 유지. P2 새 테스트 `recordIfAbsent_nonUtcJvm_expiredRow삭제경계_만료행만삭제`가 "미만료 동일 uuid는 false 반환"을 단정해 이중 차감 방지 경계를 회귀 가드.
- [x] 재시도 안전성 검증 태스크 존재 — Kafka 재배달 → `StockCommitUseCase.commit` → `recordIfAbsent` 멱등이 핵심 재시도 경로. P4 `commit_중복이벤트_...false반환시스킵`(commitToRdb 미호출)이 재배달 시 재고 미차감 검증. PITFALLS §9/§20(0 row=중복이어도 발행 진행, product dedupe가 흡수) 모델과 정합.
- [추가] DELETE 경계의 `now` vs `expiresAt` 동일 호출 일관성 — P5 AC가 `now` 산출을 `resolveExpiresAt` 호출보다 앞에 배치해 commit now 인자와 fallback base가 동일 Instant 공유(D1 단일 진입점 불변). 검증됨.
- [추가] raw-JDBC UTC 규약 정합 — P2 AC가 DELETE `Timestamp.from(now)`+UTC_CALENDAR 명시 바인딩 요구(INSERT 경로 D7 규약과 동형). D5(connectionTimeZone=UTC 존치)가 backstop으로 잔존.
- [추가] auditing 채움 회귀(직전 M3 전례) — P11이 `clockDateTimeProvider_반환타입이Instant`(RED 게이트)+빈 연결(`dateTimeProviderRef`)+실제 채움(`auditing_createdAt_isFilledByClockDateTimeProvider`) 3겹 유지. P13 AC가 `@Column(updatable=false)` createdAt 불변 보존 명시.
- [추가] TZ backstop 3겹 이중 변환 부작용 — D3는 동일값(UTC) 멱등 중복이라 만료/정산 시각에 이중 해석을 만들지 않음. 만료 정합은 이미 UTC 기준(앱 Instant)이라 backstop은 정합 무변경.

## 도메인 관점 추가 검토
1. **[minor] `recordIfAbsent` DELETE의 `expires_at < now` 경계가 정확히 만료행만 지우는지 — boundary equality(`=now`) 미만료 처리 회귀 가드 부재.** `JdbcEventDedupeStore.java:53`의 새 SQL은 `expires_at < ?`(strict less-than). P2 새 테스트는 만료 행(now 이전)·미만료 행(now 이후)만 다루고 `expires_at == now`인 경계 동치 행을 다루지 않는다. 동치 행을 "미만료"로 보존해야 INSERT IGNORE가 0(false) → 차감 skip 멱등이 유지된다. TTL=P8D >> Kafka retention 7d(PITFALLS §9)라 실무상 경계 동시각 충돌 확률은 0에 수렴하므로 minor. P2 테스트에 `==now` 케이스 1줄 추가 권고.
2. **[major] P13(BaseEntity `columnDefinition="datetime(6)"` + "Spring 부팅 확인")이 P14(DDL `DATETIME`→`DATETIME(6)` ALTER) 앞에 배치 — `ddl-auto: validate` 부팅 시점 스키마 불일치 가능성이 AC에서 미봉.** `BaseEntity.java:18-27`은 현재 `columnDefinition="datetime"`. P13(:244-259)이 이를 `datetime(6)`로 바꾸고 AC 라인 258이 "Spring 부팅 확인"을 요구하나, 실제 DDL ALTER는 P14(:268-281)에서 비로소 적용된다. payment-service가 `ddl-auto: validate`(PITFALLS §14)라면 P13 시점에 부팅하는 통합 컨텍스트는 V1~V3(여전히 `DATETIME`) 스키마와 엔티티 `datetime(6)` 매핑이 어긋나 validate 실패하거나, 통과하더라도 마이크로초 절삭으로 round-trip 단언이 흔들린다. P13 테스트 스펙(`BaseEntityAuditingTest`)은 리플렉션 단위 테스트라 DB 부팅 불요인데 AC 본문은 "Spring 부팅 확인"을 함께 요구해 **테스트 스펙과 AC가 불일치**한다. 돈이 새는 경로는 아니나(만료 cutoff는 DATETIME(6) 승급=서브초만큼 늦춤=조기 만료 없음, topic §5 안전 방향), 회귀 가드가 실제로 무엇을 부팅·검증하는지 모호해 직전 토픽 "회귀 가드 무력" major 전례를 답습할 위험. 처방: P13 AC에서 "Spring 부팅 확인"을 리플렉션 단위 단정으로 한정하고, validate 부팅을 동반하는 round-trip 검증은 P14(DDL) 완료 이후인 P17/P18로 명시 이관. 또는 P13↔P14 순서를 DDL 먼저로 뒤집을지 plan에서 확정.
3. **[minor] P10 connectionTimeZone 존치 주석 갱신과 D1 `NOW()` 제거의 시점 의존.** P10(:189-202) AC는 "D1/D2로 NOW()·existsValid 제거 완료" 근거로 주석을 재서술하는데 의존(P2 완료 후)이 명시돼 있어 정합. 다만 주석이 "raw-JDBC Timestamp.from(instant) 바인딩의 DB 세션 UTC backstop"으로 바뀌는데, `recordIfAbsent`의 DELETE도 이제 `Timestamp.from(now)` 바인딩 경로가 되므로(P2) 주석의 바인딩 열거에 INSERT/deleteExpired만이 아니라 DELETE-by-uuid도 포함돼야 backstop 근거가 완전하다. 정합성 영향은 없어 minor.
4. **[n/a — 확인 완료] 정산/금전 앵커 영향 없음.** approvedAt(정산 앵커, PITFALLS §13)·amount 대조(§8)·EOS 발행(CONFIRM-FLOW §5)은 본 PLAN 범위 밖. D4는 payment audit 컬럼(created_at/updated_at/deleted_at)만 다루고 `approvedAt`/`totalAmount` 무관. product `stock_commit_dedupe.expires_at`은 `TIMESTAMP`(V1:45)로 D4 DATETIME(6) 승급 대상 아님 — 별도 정밀도 회귀 없음. 재고 SoT(§16)·보상 멱등(§11) 경로 무변경.

## Findings
- **F1 (minor)**: P2 `recordIfAbsent` DELETE 경계 테스트에 `expires_at == now` 동치 케이스(미만료 보존 = INSERT IGNORE 0 = 차감 skip) 부재. boundary equality 멱등 회귀 가드 1줄 추가 권고.
- **F2 (major)**: P13 "Spring 부팅 확인" AC가 P14 DDL(`DATETIME(6)`) 적용 전 시점이라 `ddl-auto: validate` 스키마 불일치/마이크로초 절삭 가능성이 미봉. 테스트 스펙(리플렉션 단위)과 AC(부팅 확인)가 불일치. P13 AC를 단위 단정으로 한정하고 validate 부팅 round-trip은 P14 이후(P17/P18)로 명시 이관, 또는 P13↔P14 순서 확정.
- **F3 (minor)**: P10 갱신 주석의 raw-JDBC 바인딩 열거에 `recordIfAbsent` DELETE-by-uuid(`Timestamp.from(now)`) 경로 포함 필요.

## JSON
```json
{
  "stage": "plan",
  "topic": "TIME-MODEL-FOLLOWUP",
  "round": 1,
  "persona": "domain-expert",
  "decision": "revise",
  "findings": [
    {
      "id": "DM-1",
      "severity": "major",
      "title": "P13 'Spring 부팅 확인' AC가 P14 DDL 적용 전 시점 — validate 스키마 불일치/마이크로초 절삭 미봉",
      "evidence": "PLAN P13 AC line 258 'Spring 부팅 확인' + columnDefinition=datetime(6); P14가 DATETIME→DATETIME(6) ALTER를 P13 이후 적용. BaseEntity.java:18-27 현재 columnDefinition=datetime. payment ddl-auto: validate(PITFALLS §14). P13 테스트 스펙은 리플렉션 단위(BaseEntityAuditingTest)라 AC 본문과 불일치.",
      "recommendation": "P13 AC의 부팅 확인을 리플렉션 단위 단정으로 한정하고, validate 부팅 동반 round-trip은 P14(DDL) 완료 이후 P17/P18로 명시 이관. 또는 P13↔P14 순서를 DDL 먼저로 확정.",
      "domain_vector": "settlement/expiry-cutoff precision, auditing regression-guard integrity"
    },
    {
      "id": "DM-2",
      "severity": "minor",
      "title": "P2 DELETE 경계 테스트에 expires_at==now 동치(미만료 보존) 케이스 부재",
      "evidence": "JdbcEventDedupeStore.java:53 신 SQL expires_at < ? (strict). P2 테스트는 now 이전/이후만 단정, 경계 동치 행 미커버. 동치 행 보존=INSERT IGNORE 0=차감 skip 멱등 유지 조건.",
      "recommendation": "P2 새 테스트에 expires_at==now 행이 미만료로 보존되어 false 반환(차감 skip)되는 1줄 단정 추가.",
      "domain_vector": "idempotency boundary — duplicate stock decrement prevention"
    },
    {
      "id": "DM-3",
      "severity": "minor",
      "title": "P10 갱신 주석의 raw-JDBC 바인딩 열거에 recordIfAbsent DELETE-by-uuid 누락",
      "evidence": "PLAN P10 AC: 주석을 'INSERT/deleteExpired의 Timestamp.from(instant) 바인딩 UTC backstop'으로 재서술. D1 후 recordIfAbsent DELETE도 Timestamp.from(now) 바인딩 경로가 됨(P2).",
      "recommendation": "P10 주석 바인딩 열거에 recordIfAbsent DELETE-by-uuid 경로 포함.",
      "domain_vector": "raw-JDBC UTC convention completeness"
    }
  ],
  "checklist": {
    "domain_risk_has_task": "pass",
    "dedupe_check_planned": "pass",
    "retry_safety_task": "pass"
  }
}
```
