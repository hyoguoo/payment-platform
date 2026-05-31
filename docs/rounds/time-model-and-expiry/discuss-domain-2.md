# discuss-domain-2

**Topic**: TIME-MODEL-AND-EXPIRY
**Round**: 2
**Persona**: Domain Expert

## Reasoning
Round 1에서 낸 major 2건(raw-JDBC dedupe TTL TZ 누수, approvedAt 돈 앵커 offset-drop)이 Round 2의 D7/D8 + AC8/AC9로 실제 소스 근거와 함께 해소됐다. D7은 코드베이스에 존재하는 raw-JDBC 시각-쓰기 경로 2개(payment/product dedupe store) 전부를 정확히 지목하고 connection TZ=UTC + 명시 UTC Calendar 2중 방어 + product NOW()/Instant split-brain 수렴 + 비-UTC JVM round-trip AC를 명문화했다 — 누락 경로 없음을 grep으로 확인. D8은 raw 문자열이 offset을 보존(`parsedPaidAt.toString()`)함을 전제로 payment 측 `.toInstant()` 정규화를 단일 앵커로 확정해 메시지 contract 무변경으로 9시간 오차를 막는다. D6 연쇄·F6 자기치유도 도메인상 타당해 잔여 차단 사유 없음.

## Domain risk checklist
- [yes] 멱등성 전략이 결정됨 — Round 1 [no]에서 해소. D7이 dedupe `received_at`/`expires_at`의 시각 I/O 경계를 connection TZ=UTC(1차) + 명시 UTC Calendar(2차)로 강제하고, product의 DB `NOW()` vs 앱 `Instant` split-brain까지 동일 규약으로 수렴. AC8(비-UTC JVM round-trip 동일성 + NOW()/Instant 동일 만료 경계)이 Testcontainers 통합으로 회귀 가드. TTL 값/의미(P8D)는 NG3로 보존.
- [yes] 장애 시나리오 최소 3개 — F1~F5 + 신규 F6(혼재 배포 이중 해석). F2b가 raw-JDBC TZ 누수를 멱등 사고 경로로 명시. 요구 충족.
- [yes] 재시도 정책 — 신규 경로 없음. DedupeCleanupWorker 기존 패턴 + 스케줄러 다음 주기 자동 재시도(idempotent 조회) 유지(§5).
- [n/a] PII — 새 민감정보 없음(시각 데이터만). §5 명시.

추가 점검:
- [yes] 돈 앵커(approvedAt) 타입/의미 보존 — Round 1 [no]에서 해소. D8이 `.toLocalDateTime()`(offset 폐기) → `.toInstant()`(offset 보존) 정규화를 결정·금지패턴 명문화. AC9가 KST `+09:00` → UTC 절대시점 동치 단정.
- [yes] 만료/복원 cutoff TZ 결정성 — D3에서 ORM 경로 = `hibernate.jdbc.time_zone=UTC` 프로퍼티 의존, raw-JDBC = D7 명시 변환으로 일관 분리 확정(Round 1 partial 해소).

## 도메인 관점 추가 검토

1. **major #1 해소 확인 — D7이 raw-JDBC TTL 윈도우 오차를 실제로 막고, 누락 경로 없음** (`JdbcPaymentEventDedupeStore.java:58-59,73`, `JdbcEventDedupeStore.java:39,45,84,106`). 코드베이스 전체에서 raw-JDBC로 시각 컬럼을 쓰는 클래스는 이 2개뿐임을 grep으로 확인 — D7이 둘 다 지목해 빠진 경로 없음. D7의 1차 방어(`connectionTimeZone=UTC` + `forceConnectionTimeZoneToSession=true`)는 `Timestamp.from(instant)` 바인딩과 DB 세션 `NOW()`를 동일 UTC로 묶어, product가 한 테이블에서 `existsValid`/`SQL_DELETE_EXPIRED_BY_UUID`의 `NOW()`(세션 TZ)와 `deleteExpired`의 앱 `Instant`를 다르게 보던 split-brain을 수렴시킨다(코드 L39/L45 vs L106에서 실재 확인). 2차 방어(명시 UTC `Calendar`)가 connection 설정 누락 환경(test embedded 등)의 안전망. AC8이 비-UTC JVM(`Asia/Seoul`)에서 round-trip 절대시점 동일성 + NOW()/Instant 동일 만료 경계를 Testcontainers로 단정하므로 메커니즘 부재가 아니라 회귀 가드까지 갖췄다. NG3(TTL 의미 보존) 위반 해소.

2. **major #2 해소 확인 — D8 정규화가 정산/감사 시각 정합을 보장하고 메시지 contract 무변경** (`PaymentConfirmResultUseCase.java:230` `.toLocalDateTime()`, `NicepayPaymentGatewayStrategy.java:243,249,251`). 핵심 검증: payment로 넘어가는 raw 값은 `parsedPaidAt.toString()`(L251) = ISO_OFFSET 문자열로 **offset(+09:00)이 보존**된다. 병렬 `LocalDateTime` 필드(L249)만 offset을 버리는데 그것은 정산 앵커가 아니다. 따라서 D8이 payment `parseApprovedAt`을 `OffsetDateTime.parse(raw).toInstant()`로 바꾸면 raw에 살아 있는 offset으로 정확한 절대시점을 복원 — KST 9시간 오차 차단. raw 문자열 contract를 유지하므로 pg→payment 직렬화 무변경(D8 명시와 일치). AC9가 `2026-01-01T09:00:00+09:00` → `...T00:00:00Z` 동치를 단정. PITFALLS §13의 `.toLocalDateTime()` 처방을 `.toInstant()`로 갱신 권고도 D8 참조에 포함됨. 단 §13 본문은 아직 옛 처방이라 verify 동기화 필요(아래 minor #3).

3. **D6 두 스케줄러 연쇄 — D7/D8 추가로 변동 없음(silent loss/race 부재 재확인)** (`PaymentEvent` expire READY 가드 / resetToReady IN_PROGRESS 가드, §4 다이어그램). IN_PROGRESS 직접 만료 금지 + 정합 스캐너 READY 복원 후 만료 2단 연쇄는 "실제 승인됐는데 만료" 위험을 회피하는 의도된 설계. 만료(`scheduler.payment-expiration.*`)와 복원(`reconciler.*`)이 별개 프로퍼티로 독립 운영돼 새 race 없음. D7(저장 TZ)·D8(앵커 정규화)은 시각 비교의 *기준 시점*만 안정화할 뿐 전이 가드를 건드리지 않아 연쇄 정책 불변. Round 1 확인 유지.

4. **F6 혼재 배포 이중 해석 — 자기치유 현실성 검토(수용 가능)** (§5 F6). 1차 방어로 컨테이너 TZ=UTC 고정 시 구·신 인스턴스가 모두 UTC 절대시점이 되어 이중 해석이 무해해진다는 논리는 타당하다. 자기치유 근거(dedupe는 다음 주기 재스캔으로 수렴, 만료는 종결 전이 1회라 1주기 어긋나도 영구 손상 없음)도 도메인상 성립. 다만 approvedAt은 종결 시 1회 기록되는 값이라 "혼재 배포 중 구버전이 offset-drop LocalDateTime으로 박은 행"은 자기치유 대상이 아니라 영구 잔존(자기치유 논리는 dedupe/만료 한정). F6은 "TZ=UTC 고정 시 무해 + 학습용 단일/소수 인스턴스라 동시 가동 윈도우 짧음"으로 수용했고, plan 게이트(비-UTC 운영이면 배포 전 TZ=UTC 선반영)로 위임했으므로 discuss 단계 차단 사유는 아니다. approvedAt 과도기 행 한정 잔존을 plan에서 한 줄 명시 권고(경미).

5. **R1 운영 데이터 정합 plan 게이트 위임 — 도메인상 적절**. connection TZ=UTC 규약 변경이 비-UTC로 저장된 기존 행 해석을 바꿀 수 있으나, 컬럼 타입 무변경(D3)이라 보정은 데이터 레벨 작업으로 분리 가능. 학습용 영속 데이터 부재 개연성 + plan 명시 확인 게이트로 둔 것은 타당.

## Findings
- **minor #3** — PITFALLS §13 본문이 아직 옛 처방(`.toLocalDateTime()`)을 담고 있어 D8 결정과 어긋난다. D8 참조에 "verify 단계 §13 동기화 갱신 권고"가 있으나 discuss 산출물 시점엔 함정 문서가 새 결정과 모순 상태. 위 검토 2.
- **minor #4** — F6 자기치유 근거가 dedupe/만료에는 성립하나 approvedAt(종결 시 1회 기록)은 혼재 배포 중 구버전 offset-drop 저장분이 자기치유 대상이 아님. F6 대응이 "TZ=UTC 고정 시 무해"로 일반 차단하므로 경미하나, approvedAt 과도기 행 한정 잔존을 plan에서 명시 권고. 위 검토 4.

## JSON
```json
{
  "stage": "discuss",
  "persona": "domain-expert",
  "round": 2,
  "task_id": null,

  "decision": "pass",
  "reason_summary": "Round 1 major 2건이 D7(raw-JDBC connection TZ=UTC + 명시 Calendar + product NOW()/Instant split-brain 수렴 + AC8)과 D8(approvedAt offset 보존 .toInstant() 정규화 + raw 문자열 contract 무변경 + AC9)으로 실제 소스 근거와 함께 해소. 남은 항목은 PITFALLS §13 동기화·F6 approvedAt 과도기 행 명시 권고로 minor만.",

  "checklist": {
    "source": "_shared/checklists/discuss-ready.md",
    "items": [
      {
        "section": "domain risk",
        "item": "멱등성 전략이 결정됨 (idempotency key 소스/수명/충돌 처리)",
        "status": "yes",
        "evidence": "D7 — JdbcPaymentEventDedupeStore.java:58-59,73 + JdbcEventDedupeStore.java:39,45,84,106 (코드베이스 raw-JDBC 시각-쓰기 경로 전부)에 connectionTimeZone=UTC 1차 + 명시 UTC Calendar 2차 + product NOW()/Instant split-brain 수렴 규약 명문화. AC8 비-UTC JVM round-trip 동일성 회귀 가드."
      },
      {
        "section": "domain risk",
        "item": "장애 시나리오 최소 3개 식별됨",
        "status": "yes",
        "evidence": "§5 F1~F6 (F2b raw-JDBC TZ 누수 멱등 사고, F6 혼재 배포 이중 해석 추가)"
      },
      {
        "section": "domain risk",
        "item": "재시도 정책이 정의됨",
        "status": "yes",
        "evidence": "§5 — 신규 경로 없음, DedupeCleanupWorker 기존 패턴 + 스케줄러 다음 주기 idempotent 재시도 유지"
      },
      {
        "section": "domain risk",
        "item": "PII/민감정보 새로 도입 시 로깅·저장·전송 경로 검토",
        "status": "n/a",
        "evidence": "§5 'PII: 새로 도입되는 민감정보 없음 — 시각 데이터만'"
      },
      {
        "section": "domain risk (추가)",
        "item": "돈 기록 앵커(approvedAt) 타입·의미 보존",
        "status": "yes",
        "evidence": "D8 — PaymentConfirmResultUseCase.java:230 .toLocalDateTime() → .toInstant() 정규화. NicepayPaymentGatewayStrategy.java:251 parsedPaidAt.toString() 가 +09:00 offset 보존하므로 payment .toInstant() 가 정확한 절대시점 복원. AC9 KST→UTC 동치 단정, 메시지 contract 무변경."
      },
      {
        "section": "design decisions (UTC 변환 경계)",
        "item": "엔티티 UTC 변환 메커니즘 통일 방향 결정",
        "status": "yes",
        "evidence": "D3 — ORM 경로=hibernate.jdbc.time_zone=UTC 프로퍼티 의존, raw-JDBC=D7 명시 변환으로 일관 분리 확정 (Round 1 minor #3 해소)"
      }
    ],
    "total": 6,
    "passed": 5,
    "failed": 0,
    "not_applicable": 1
  },

  "scores": {
    "clarity": 0.88,
    "completeness": 0.90,
    "risk": 0.88,
    "testability": 0.86,
    "fit": 0.86,
    "mean": 0.876
  },

  "findings": [
    {
      "severity": "minor",
      "checklist_item": "돈 기록 앵커(approvedAt) 타입·의미 보존",
      "location": "docs/context/PITFALLS.md#13 / docs/topics/TIME-MODEL-AND-EXPIRY.md#D8",
      "problem": "PITFALLS §13 본문이 아직 옛 처방(OffsetDateTime.parse(raw).toLocalDateTime())을 담고 있어 D8의 .toInstant() 결정과 모순. D8 참조가 verify 동기화를 권고하나 discuss 시점엔 함정 문서가 새 결정과 어긋난 상태로 남는다.",
      "evidence": "PITFALLS.md:133 '.toLocalDateTime() 변환' 처방 vs D8 '.toLocalDateTime() 금지 패턴'. 동일 경계를 반대로 기술.",
      "suggestion": "plan/verify 단계에서 PITFALLS §13 처방 문구를 .toInstant() 정규화로 동기화 갱신하도록 게이트에 명시."
    },
    {
      "severity": "minor",
      "checklist_item": "장애 시나리오 (혼재 배포)",
      "location": "docs/topics/TIME-MODEL-AND-EXPIRY.md#F6",
      "problem": "F6 자기치유 근거(다음 주기 재스캔 수렴, 만료 1회 전이)는 dedupe/만료에는 성립하나, approvedAt 은 종결 시 1회 기록되는 정산 앵커라 혼재 배포 중 구버전 offset-drop 저장분은 자기치유 대상이 아니라 영구 잔존한다. TZ=UTC 고정 1차 방어가 일반 차단하므로 경미.",
      "evidence": "§5 F6 자기치유 (b) 항은 dedupe/만료만 예시. approvedAt 종결 1회 기록 특성상 과도기 잘못 저장분은 후속 재스캔으로 교정 안 됨.",
      "suggestion": "plan에서 'F6 자기치유는 dedupe/만료 한정, approvedAt 과도기 행은 TZ=UTC 선반영으로만 예방'을 한 줄 명시. 배포 전 컨테이너 TZ=UTC 게이트와 정합 확인."
    }
  ],

  "previous_round_ref": "discuss-domain-1.md",
  "delta": {
    "newly_passed": [
      "멱등성 전략이 결정됨 (idempotency key 소스/수명/충돌 처리)",
      "돈 기록 앵커(approvedAt) 타입·의미 보존",
      "엔티티 UTC 변환 메커니즘 통일 방향 결정"
    ],
    "newly_failed": [],
    "still_failing": []
  },

  "unstuck_suggestion": null
}
```
