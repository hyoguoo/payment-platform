# plan-domain-1 — Domain Expert, CLEANUP-BATCH-B Round 1

## 한 줄 판정
**pass** — discuss에서 식별된 결제 도메인 리스크(F1 stale 마커 윈도우, D-NR1d 윈도우 수용, F2 thundering herd)가 PLAN에 각각 정확히 매핑됐고, domain_risk=true 태스크(B-1/B-2)의 GET 멱등 전제·EOS 커버리지 반영·FakeMessagePublisher 의미 보존을 실제 소스로 교차검증한 결과 전부 일치. critical/major 없음.

## severity별 finding 개수
- critical: 0
- major: 0
- minor: 1 (DF-2)
- n/a: 3 (DF-1, DF-3, DF-4)

---

## Domain risk checklist (plan-ready.md domain risk 섹션)

- [x] **discuss domain risk가 각각 대응 태스크를 가짐** — F1(502/504 승격 ↔ IN_PROGRESS 마커 TTL 윈도우, major)은 PLAN 추적테이블에서 B-1/B-2 + D-NR1d(윈도우 수용 설계 확정)로 매핑. F2(thundering herd, minor)는 "비대상 Phase 4" 정보성 매핑. D-NR1d는 "없음(비대상)"으로 명시 매핑. 미매핑 risk 없음.
- [x] **중복 방지 체크가 필요한 경로에 계획됨** — 본 토픽은 새 멱등성 경로 도입 없음. 502/504 승격이 닿는 경로가 GET 단건 조회 전용(상태변경 POST 0건)이라 중복 부수효과 자체가 구조적으로 부재. 별도 중복방지 태스크 불요 — 도메인적으로 타당.
- [x] **재시도 안전성 검증 태스크 존재** — B-1/B-2가 502/504 → `*ServiceRetryableException` 매핑을 검증하고, 기존 404/429/503/500 4분기 회귀를 유지(B-1 회귀 케이스 8건). 재시도 신호 정확화의 안전성 검증이 태스크에 포함됨.

---

## 도메인 관점 추가 검토 (파일:라인 근거)

1. **B-1/B-2가 "GET 조회 전용" 멱등 전제를 회귀로 못박는가 — 부분적이나 충분.**
   `ProductFeignClient.java:20` / 대응 UserFeignClient은 `@GetMapping` 단건 조회만 노출(비멱등 POST 0건). `ProductFeignConfig.java:53-58`은 현재 429/503만 retryable, 502/504는 fall-through하여 `IllegalStateException`(L61). ErrorDecoder는 HTTP 상태 → 예외 순수 변환이라 "조회 전용"은 ErrorDecoder 책임이 아니라 FeignClient 인터페이스 책임. FeignClient에 POST가 추가되는 변경은 본 토픽 범위 밖이고 `@GetMapping` 인터페이스 변경이 PR diff에 드러나 별도 검토 트리거가 된다. 매핑 테스트(B-1)가 멱등 회귀를 직접 안 걸어도 도메인 리스크 낮음 — 정보성으로 충분.

2. **D-NR1d 윈도우 수용을 "코드 태스크 불요 비대상"으로 처리 — 도메인 관점에서 정확.**
   discuss-domain-2에서 D-NR1d 의존 주장 3종(마커 10s 자연만료 / Retry-After:5 < TTL 10s / 금전 무해=레코드 미생성)을 소스 재검증해 전부 일치 확인. 윈도우 수용은 설계 결정이지 미구현 부채가 아니므로 코드 태스크를 만들지 않은 것이 정답.

3. **C-1 integrationTest 합산이 결제 정합성 시나리오를 baseline에 반영 — 옳음 (소스 확정).**
   `PaymentEosIntegrationTest.java:82` `@Tag("integration")` → `build.gradle:99-100` integrationTest 태스크 포함. 루트 단위 `test`는 `excludeTags 'integration'`로 EOS 제외 → C-1 합산이 EOS commit/abort/중복/multi-product/D7 가드 5종을 application/usecase LINE 커버리지에 신규 반영. C-1 완료기준("합산 전 대비 상승") 검증 가능.

4. **A-2 FakeMessagePublisher Supplier 전환이 send 실패 주입 의미 보존 — 확정.**
   유일 실외부 호출부 `OutboxRelayServiceTest.java:120` `failNext()` 1건, 단언은 `:123-124` `isInstanceOf(RuntimeException.class)` 타입만 검증(인스턴스 동일성 미의존). `setFailure`/`setPermanentFailure` 직접 호출부 grep 0건. Supplier.get() 매번 새 인스턴스 생성이 시나리오 의미 보존, main 계약 `MessagePublisherPort.send` 불변.

---

## Findings

- **DF-1 (n/a)** — F1(major) 해소가 PLAN에 정확히 매핑됨. 코드 태스크 불요가 도메인적으로 옳음(금전 무해, 멱등성 store 거주지 밖).
- **DF-2 (minor)** — F2(504 thundering herd, jitter 부재)가 "비대상 Phase 4(T4-D) 정보성"으로 매핑. 자동 재시도 비도입 + Phase 4 위임 범위라 신규 리스크 아님. 정보성, 조치 불요.
- **DF-3 (n/a)** — A-2 Supplier 전환이 send 실패 주입 의미 보존. main 계약 무영향.
- **DF-4 (n/a)** — A-1(spotbugs)·C-1/C-2(JaCoCo)는 도메인 상태·멱등성·상태전이 무영향. PII/신규 로깅 경로 없음.

---

## JSON
```json
{
  "stage": "plan",
  "topic": "CLEANUP-BATCH-B",
  "round": 1,
  "persona": "domain-expert",
  "decision": "pass",
  "findings": [
    {"id": "DF-1", "severity": "n/a", "area": "idempotency / checkout 진입 가용성 risk 매핑", "summary": "discuss major F1(502/504->503 승격 ↔ IN_PROGRESS 마커 TTL 10s 윈도우)이 PLAN에서 B-1/B-2 + D-NR1d(윈도우 수용, 코드 태스크 불요)로 정확히 매핑됨.", "recommendation": "조치 불요. D-NR1d Phase 4/TODOS 위임이 후속 추적되는지만 verify에서 확인."},
    {"id": "DF-2", "severity": "minor", "area": "failure mode / retry storm risk 매핑", "summary": "F2(504 thundering herd jitter 부재)가 PLAN에 비대상 Phase 4 정보성으로 매핑. 신규 리스크 아님.", "recommendation": "정보성. Phase 4(T4-D) 백오프 jitter 고려를 TODOS 연결만 확인."},
    {"id": "DF-3", "severity": "n/a", "area": "메시지 발행 테스트 의미 보존 (A-2)", "summary": "FakeMessagePublisher Supplier 전환이 send 실패 주입 시나리오 의미 보존. main 계약 무영향.", "recommendation": "조치 불요."},
    {"id": "DF-4", "severity": "n/a", "area": "PII / 상태전이 / 빌드위생 / 커버리지 매핑", "summary": "A-1·C-1/C-2는 도메인 상태 무영향. C-1 integrationTest 합산이 EOS 정합성 시나리오를 커버리지에 반영하는 방향 옳음.", "recommendation": "조치 불요."}
  ]
}
```
