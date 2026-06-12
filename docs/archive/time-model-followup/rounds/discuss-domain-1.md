# discuss-domain-1

**Topic**: TIME-MODEL-FOLLOWUP
**Round**: 1
**Persona**: Domain Expert

## Reasoning
세 항목(NOW 통일 / TZ backstop / BaseEntity Instant) 모두 만료 앵커(`created_at` cutoff)와 재고 멱등(INSERT IGNORE UNIQUE)에 돈 사고 경로를 새로 만들지 않으며, D1은 오히려 DELETE/INSERT 시각 소스 이원화를 제거해 만료 정합을 강화한다. 직전 토픽의 major(raw-JDBC UTC 누락 / approvedAt offset 폐기)에 해당하는 돈-앵커 리스크는 실제 소스 교차검증 결과 이 설계에 남아있지 않다. 발견 항목은 전부 문서/주석 정합 수준의 minor.

## Domain risk checklist
- **멱등성 전략 결정**: yes — 재고 dedupe 멱등의 SSOT는 `stock_commit_dedupe.event_uuid` UNIQUE + INSERT IGNORE(`JdbcEventDedupeStore:50,103`). DELETE(`SQL_DELETE_EXPIRED_BY_UUID`)는 만료행 청소 보조 단계일 뿐 멱등 권한이 아님. D1의 NOW()→앱 now 전환은 멱등 판정(INSERT IGNORE affected rows)을 건드리지 않음. idempotency key 소스=`StockEventUuidDeriver.derive` 결정적 UUID, 수명=P8D, 충돌처리=INSERT IGNORE 0 row→중복 skip. 정합.
- **장애 시나리오 ≥3**: yes — (1) 정상 재배달(미만료 행 존재): DELETE 0 row + INSERT IGNORE 0 row → false, NOW/앱now 무관 동일. (2) 만료 후(>8일, Kafka retention 7일 초과) 재배달: 새 이벤트로 간주, 재기록 안전. (3) connectionTimeZone 설정 누락: D1 후 DELETE도 앱 now라 split-brain 1차 원인 해소, D5가 Timestamp 바인딩 backstop 존치. (4) 비-UTC JVM TZ: D6 재배치 AC8이 회귀 가드.
- **재시도 정책**: n/a 변경 — 본 토픽은 시간 모델 수렴이라 retry/backoff 정책 무변경. Kafka DefaultErrorHandler/DLQ 경로 불변.
- **PII**: n/a — 새 PII 도입 없음. audit 컬럼(created_at 등)은 시각값으로 민감정보 아님.

## 도메인 관점 추가 검토

1. **D1 멱등성 — race window 신규 생성 없음 (검증 완료).**
   `StockCommitUseCase.commit`(`:62-72`)은 `@Transactional` 단일 TX 안에서 `recordIfAbsent` → `commitToRdb` 순서로 실행되고, dedupe INSERT와 재고 차감이 같은 TX에 묶임(`JdbcEventDedupeStore` 클래스 javadoc `:36` 확인). 멱등 보장은 INSERT IGNORE의 UNIQUE 제약이지 DELETE의 시각 기준이 아니다. DELETE의 `NOW()`→앱 `now` 전환은 "만료행만 비워 재기록 허용"하는 보조 경로의 시각 소스만 바꾼다. TTL이 P8D(8일)이고 Kafka retention 7일이라, 정상 재배달 윈도우 안에서는 `expires_at < now`가 항상 false → DELETE 0 row → INSERT IGNORE가 중복을 정확히 차단. **NOW()든 앱 now든 정상 경로 결과 동일하므로 새 race window 없음.** 오히려 현재 DELETE(DB NOW)/INSERT(앱 expiresAt, UTC Calendar `:107`)의 시각 소스 이원화가 D1로 한 호출 안에서 통일되어 정합 강화.

2. **D1 호출처 사실 정정 — `StockCommitUseCase`는 `Clock` 미보유 (설계 서술 부정확, minor).**
   §2 D1은 "호출자는 이미 `Clock` 보유(TIME-MODEL-AND-EXPIRY T13)"라 적었으나, 실제로 `Clock`은 `StockCommitConsumer`(infrastructure, `:35,38`)에만 주입되어 있고 `StockCommitUseCase`(application, `:30,39-40`)에는 없다. `commit` 시그니처(`:63`)에도 `Instant now`가 없다. D1대로 `recordIfAbsent(uuid, now, expiresAt)`를 application에서 호출하려면 `now`를 consumer→useCase→port로 전파하거나 useCase에 `Clock`을 주입해야 한다. dispatch가 "정확 위치는 plan에서 확정"으로 이연했으므로 차단 사유 아님. 단 plan에서 `now` 전파 경로를 명시하지 않으면 어댑터가 자체 `clock.instant()`를 호출(기각안 A)로 회귀할 여지가 있어 plan 가드 권고.

3. **D4 만료 cutoff 안전성 — DATETIME(6) 승급은 만료를 늦추는 안전 방향 (검증 완료).**
   실제 만료 앵커는 `JpaPaymentEventRepository.findReadyPaymentsOlderThan`(`:20-22`)의 native query `created_at < :before`이며 `Instant before`(마이크로초)를 직접 바인딩한다. 현재 created_at은 `DATETIME`(V1 `:21`, 서브초 절삭)이라 저장값이 실제보다 이르거나 같고, 승급 후 `DATETIME(6)`는 서브초를 보존한다. 비교 방향상 정밀도 상승은 만료 경계를 미세하게 "늦추는"(서브초가 살아 더 늦게 cutoff 충족) 방향 → READY 결제를 조기 만료시키지 않는 안전 방향. 돈 사고(미성립 결제 조기 EXPIRED) 경로 아님. `findInProgressOlderThan`은 `executedAt`(이미 Instant/`DATETIME(6)`) 기준이라 BaseEntity 전환과 무관.

4. **D4 혼재 행 비교 — 운영 데이터 0 전제로 무해 (검증 완료).**
   기존 `DATETIME` 행과 신규 `DATETIME(6)` 행의 혼재 비교 안전성은 C2(운영 데이터 0, ledger RESOLVED) 전제하에 혼재 자체가 없어 무해. ALTER 시 기존 행은 `.000000` 확장(§5)이라 손실 없음. 전제가 깨지는 시점(운영 데이터 도입)에는 §5가 online DDL 검토를 후속 메모로 남겨 인지됨.

5. **D5 connectionTimeZone 주석 stale — 정합 무변경, 문서 갱신 누락 위험 (minor).**
   product `application.yml:13` 주석이 현재 `connectionTimeZone=UTC`의 근거를 "existsValid/SQL_DELETE_EXPIRED_BY_UUID 의 DB NOW()"로 명시한다. D1(NOW() 제거)+D2(existsValid 제거)로 이 두 근거가 모두 소멸하는데, §3 변경범위는 product yml을 "무변경(존치)"로만 적어 주석이 stale해진다. D5 근거(raw-JDBC `Timestamp.from(instant)` 바인딩 UTC 세션 backstop)로 주석을 갱신하지 않으면 미래 독자가 "NOW() 없는데 왜 이 설정?"을 재추적하게 된다 — D2가 데드 표면 제거로 막으려던 인지부담과 동형. 정합 자체는 불변이므로 minor. plan에서 주석 갱신을 변경범위에 포함 권고.

6. **D4 BaseEntity 외 LocalDateTime 잔재 — 범위 외, 만료/정산 앵커 아님 (참고).**
   `PaymentOutboxEntity.nextRetryAt/inFlightAt`(`:53,56`)은 BaseEntity가 아닌 자체 필드로 여전히 `LocalDateTime`(DDL `DATETIME(6)`)이며 `toInstant`/`toLocalDateTime` 헬퍼로 변환된다. D4가 BaseEntity getter의 `toInstant` 호출(`:77-78`)만 제거하므로 헬퍼는 `nextRetryAt/inFlightAt`(`:75-76`)용으로 잔존해야 한다. §3이 "사용처 사라진 헬퍼 있으면 동반 제거"로 조건부 서술해 이 잔존을 인지함 — 헬퍼 무조건 삭제 시 컴파일 깨짐 방지됨. 이 두 필드의 LocalDateTime 잔재는 도메인 표준 미수렴이나 retry 스케줄 시각이라 만료/정산 돈-앵커 아님. 범위 외 정상.

## Findings
- **[minor] D1 설계 서술 부정확**: `StockCommitUseCase`가 `Clock`을 보유한다는 §2 D1 서술이 실제 소스와 불일치(Clock은 consumer 레이어). plan에서 `now` 전파 경로 명시 + 어댑터 자체 clock 호출(기각안 A) 회귀 방지 가드 권고.
- **[minor] D5 주석 stale**: product `application.yml:13` connectionTimeZone 근거 주석이 D1/D2로 소멸하는 NOW()/existsValid를 가리킴. plan 변경범위에 주석 갱신 포함 권고.
- **[n/a] D1 멱등성/race**: 신규 race window 없음, 오히려 시각 소스 이원화 제거로 정합 강화 (검증 완료).
- **[n/a] D4 만료 cutoff**: DATETIME(6) 승급은 만료를 늦추는 안전 방향, 조기 만료 돈 사고 없음 (검증 완료).
- **[n/a] D4 혼재 행**: 운영 데이터 0 전제로 무해 (검증 완료).

## JSON
```json
{
  "stage": "discuss",
  "topic": "TIME-MODEL-FOLLOWUP",
  "round": 1,
  "persona": "domain-expert",
  "decision": "pass",
  "findings": [
    {
      "id": "DM-F1",
      "severity": "minor",
      "area": "D1 / StockCommitUseCase Clock wiring",
      "summary": "§2 D1이 StockCommitUseCase가 Clock 보유라 서술하나 실제 Clock은 StockCommitConsumer(infrastructure)에만 주입됨. commit 시그니처에도 now 인자 없음.",
      "evidence": "StockCommitUseCase.java:30,39-40,63 (Clock 미주입) vs StockCommitConsumer.java:35,38 (Clock 주입)",
      "recommendation": "plan에서 now 전파 경로(consumer→useCase→port) 명시 + 어댑터 자체 clock.instant() 호출(기각안 A) 회귀 방지 가드."
    },
    {
      "id": "DM-F2",
      "severity": "minor",
      "area": "D5 / connectionTimeZone 주석 정합",
      "summary": "product application.yml:13 주석이 connectionTimeZone=UTC 근거를 existsValid/SQL_DELETE_EXPIRED_BY_UUID NOW()로 명시하나 D1/D2로 두 근거 모두 소멸 → 주석 stale.",
      "evidence": "product-service/src/main/resources/application.yml:13-16 주석 vs D1(NOW 제거)/D2(existsValid 제거)",
      "recommendation": "plan 변경범위에 yml 주석 갱신(raw-JDBC Timestamp 바인딩 UTC backstop 근거로 재서술) 포함."
    }
  ],
  "domain_risk_summary": {
    "idempotency": "ok — INSERT IGNORE UNIQUE가 멱등 SSOT, D1 NOW()→앱now 전환은 멱등 판정 불변, 신규 race window 없음",
    "state_transition": "n/a — 상태 머신 무변경",
    "money_anchor": "ok — 만료 cutoff(created_at) DATETIME(6) 승급은 조기 만료 방지 안전 방향, 정산 앵커(approvedAt/executedAt) 무변경",
    "race_window": "ok — recordIfAbsent DELETE/INSERT 시각 소스 이원화 제거로 정합 강화",
    "pii": "n/a — 신규 PII 없음"
  }
}
```
