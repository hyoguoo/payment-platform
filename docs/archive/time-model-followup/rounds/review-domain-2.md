# review-domain-2

**Topic**: time-model-followup
**Round**: 2
**Persona**: Domain Expert

## Reasoning
ce296873 은 product 재고 멱등 어댑터의 미사용 `clock` 필드/생성자 파라미터 제거 + RoundTripTest 의 `@Primary Clock` 오버라이드(`FixedClockConfig`) → 단순 `FIXED_INSTANT` 상수 대체로, 둘 다 만료 경계 판정 로직(`expires_at < now` strict, == now 잔존)과 무관한 잔재 제거다. 만료 경계는 전적으로 호출자(StockCommitConsumer)가 `clock.instant()` 로 단일 산출해 useCase→어댑터로 주입하는 `Instant now` 기반이며, 어댑터 내부 `clock` 호출은 변경 전에도 0건이라 멱등/만료 경계가 약화되지 않았다. D6 경계(만료행 삭제 + == now 동치 잔존 + 중복 차감 방지) 검증력은 상수 대체 후에도 통합테스트 2건에 그대로 고정돼 있다.

## Domain risk checklist
- 상태 전이 정합: 본 커밋은 결제 상태 전이 코드 무변경 — n/a
- 멱등성 보장: recordIfAbsent DELETE(strict <) → INSERT IGNORE 로직 무변경. 중복 재고 차감 방지 경로 보존 (PITFALLS #20/#22) — 유지
- 만료 경계: `expires_at < now` strict, `== now` 잔존 규약(D6) 코드/테스트 모두 보존 — 유지
- race window 신규 유입: 없음. 필드 제거는 동작 무영향, 생성자 시그니처만 축소 — n/a
- PII: 해당 없음 — n/a
- 금전 정확성: 재고(=금전 인접 자산) 멱등 보장 회귀 없음 — 유지

## 도메인 관점 추가 검토
1. **clock 필드 제거의 만료 경계 무영향 — 교차 검증 통과.**
   `JdbcEventDedupeStore.recordIfAbsent`(JdbcEventDedupeStore.java:79-106) / `deleteExpired`(:120-127) 는 만료 경계를 전부 인자 `Instant now` 로만 판정한다. 변경 전 `clock` 필드는 어디서도 `clock.instant()` 로 호출되지 않던 순수 미사용 필드였고(grep 결과 main 소스 내 잔여 호출 0건), 제거 후에도 `Timestamp.from(now)` 바인딩 경로(:82, :124)는 동일. 만료 cutoff 의 진짜 시각 권한은 `StockCommitConsumer.java:65` 의 `Instant now = clock.instant()` 단일 산출점이며, useCase(StockCommitUseCase.java:64-65)를 거쳐 어댑터로 주입된다 — 어댑터 clock 과 무관함이 재확인됨. 돈/재고 새는 경로 신규 유입 0.

2. **FixedClockConfig → FIXED_INSTANT 상수 대체가 D6 검증력 보존.**
   RoundTripTest 의 핵심 회귀 가드는 ① 비-UTC JVM TZ(Asia/Seoul) round-trip 절대시점 동치(JdbcEventDedupeStoreRoundTripTest.java:102-124), ② D6 만료행 삭제 경계(:147-198)다. 두 테스트 모두 `now`/`expiresAt` 를 **테스트 본문에서 명시 `Instant` 로 주입**(:106 `now=fixedInstant`, :149 `now=Instant.parse(...)`)하지, Spring 의 `Clock` 빈에서 끌어오지 않는다. 즉 기존 `@Primary Clock` 오버라이드는 어댑터가 clock 을 안 쓰므로 검증에 기여한 바가 없는 죽은 설정이었다. 상수 대체 후에도 `== now` 경계 행 잔존(:177-180), 만료행 삭제 후 재기록 true(:165-168), 최종 행 수 4 단정(:195-197)이 그대로 유지 — 중복 재고 차감 방지(멱등) 보장이 테스트로 여전히 고정된다.

3. **부수 효과 점검: allow-bean-definition-overriding=true 제거.**
   `FixedClockConfig` 의 `@Primary Clock` 빈이 사라지면서 `spring.main.allow-bean-definition-overriding=true`(이전 :32)도 제거됐다. 이는 ClockConfig.java 의 `Clock.systemUTC()` 빈과의 오버라이드 충돌 회피용이었고, 이제 오버라이드가 없으므로 제거가 정합. RoundTripTest 가 실제 `systemUTC` 빈으로 뜨더라도 테스트는 clock 빈을 주입받지 않으므로(어댑터·테스트 본문 모두 명시 Instant) 결정성 영향 없음.

## Findings
- (none) — 도메인 리스크 관점 신규 finding 없음. 멱등성/만료 경계/금전 정확성/race window 모두 회귀 없이 보존됨.

## JSON
```json
{
  "stage": "review",
  "topic": "time-model-followup",
  "round": 2,
  "persona": "domain-expert",
  "decision": "pass",
  "findings": [],
  "summary": "clock 필드 제거·FixedClockConfig→FIXED_INSTANT 상수 대체 모두 잔재 정리로, 만료 경계(expires_at < now strict, == now 잔존)와 재고 멱등 보장은 호출자 주입 Instant 기반으로 불변. D6 통합테스트 검증력 보존, 돈/재고 새는 경로·race window 신규 유입 0."
}
```
