# discuss-domain-4

**Topic**: MSA-TRANSITION
**Round**: 4
**Persona**: Domain Expert

## Reasoning

Round 4.1 + 4.2 수정이 반영된 `docs/topics/MSA-TRANSITION.md`(1226 lines)를 `.claude/skills/_shared/checklists/discuss-ready.md`의 **Gate checklist** 중 domain risk 관할 항목과 Round 3 findings(중복 승인 2자 대조 · pg DB 부재 경로 · inbox 5상태 · QUARANTINED 2단계 복구 · outbox retry 파이프라인 · DLQ 전용 consumer) 전건에 대조했다. Round 3의 3 critical(C1~C3 추정: 단조 전이 역행 · QUARANTINED TX 원자성 · 재시도 토픽 폭증) + 3 major(M4~M6 추정: attempt 헤더 위치 · 재시도 상한 소유권 · 보상 이벤트 dedupe 위치) + 3 minor가 **모두 설계 반영**됐음을 확인했다. 특히 (a) inbox terminal 집합을 `{APPROVED,FAILED,QUARANTINED}` 3상태로 확장(§4-10 ADR-21 수락 기준 vi~ix), (b) 2단계 복구 + `quarantine_compensation_pending` 플래그(§2-2b-3 + 불변식 7/7b), (c) outbox+ApplicationEvent+Channel+Worker 4단 파이프라인이 initial/retry/DLQ 공용으로 단일화(§4-10 ADR-30), (d) `PaymentConfirmDlqConsumer` 물리적 분리 + pg_inbox UNIQUE + terminal 체크 이중 방어(§2-2b-4 불변식 6c), (e) Toss `ALREADY_PROCESSED_PAYMENT`/NicePay `2201` 수신 시 pg DB 존재/부재 분기 + 2자 금액 대조 절차(§4-10 ADR-05 보강 6단계)까지 절차 순서가 명시되어 Round 3 이전에서 지적된 돈 사고 경로는 서류상 모두 막혔다. 그러나 **Round 3에서 명시적으로 요청되지 않았던 신규 돈 사고 경로 2건**이 Round 4 재설계 과정에서 생겼다: (1) §4-10 ADR-05 보강 6번 "pg DB 레코드 부재 경로"에서 **벤더 재조회 금액과 command payload amount의 비교가 누락** — status=paid 만 확인하고 APPROVED 처리, (2) ADR-21의 business inbox schema 기술에서 **inbox에 amount 컬럼이 포함되는지 명시 없음** — "pg DB amount vs 벤더 2자 대조"(§4-10 ADR-05 보강 4번 · 불변식 4c)의 전제인 "pg DB amount"가 어디에서 오는지 스키마 불확정. 둘 다 금액 위변조가 pg 경계에서 통과되는 구체적 경로이므로 **major**. 그 외 중복 DLQ 수신 흡수(불변식 6c), race 봉쇄, 보상 이벤트 UUID dedupe(ADR-16)는 Round 3에서 정돈된 수준을 유지한다. **판정: revise (major × 2)**.

## Domain risk checklist

| 항목 | 판정 | 근거 |
|---|---|---|
| Round 3 C1 (inbox 단조 전이 역행) 해소 | **pass** | §4-10 ADR-21 보강 "inbox 상태 모델 5상태" + 불변식 4/4b/4c/6c에서 terminal 집합 `{APPROVED,FAILED,QUARANTINED}` 세 상태 모두 "저장된 status 재발행 + 벤더 재호출 금지" 명시(`MSA-TRANSITION.md:897-901, 442-448`). QUARANTINED 재수신 시 단조 전이 역전 경로 봉쇄. |
| Round 3 C2 (QUARANTINED TX 원자성) 해소 | **pass** | §2-2b-3 2단계 분할 + `quarantine_compensation_pending` 플래그(`MSA-TRANSITION.md:401-419`, 425-431) + 불변식 7/7b(`:449-450`) + Phase-1 schema 산출물(`:1072`). 2단계 사이 수 ms 윈도우 리스크는 §2-2b-5 E에서 수용적으로 명시. |
| Round 3 C3 (재시도 토픽 폭증 + 지연 파티션 drift) 해소 | **pass** | ADR-30에서 Kafka retry 토픽 체인 폐기 + `pg_outbox.available_at` DB 컬럼으로 지연 표현(`MSA-TRANSITION.md:914-917`, 360-366). 토픽 3개로 축소 + 동일 파티션 수 + 동일 파티션 키(orderId) 강제(불변식 6b · `:447`). Round 3에서 파괴적이었던 "retry 토픽별 파티션 drift"는 구조적으로 불가능. |
| Round 3 M4 (attempt 헤더 초기값) 해소 | **pass** | payment-service는 attempt 헤더를 읽거나 쓰지 않는다 — initial 발행 시 헤더 부재(`MSA-TRANSITION.md:948`). pg-service consumer가 `attempt = headers.attempt ?: 0`으로 흡수(`:927`). 계약 공백 없음. |
| Round 3 M5 (재시도 상한 판정 소유권) 해소 | **pass** | §4-10 ADR-30 "재시도 상한 판정 위치: pg-service consumer 내부가 소유"(`MSA-TRANSITION.md:919-932`) + 수락 기준 "재시도 상한 판정 코드는 pg-service 안에만 존재"(`:948`). payment-service ↔ pg-service 경계 청결. |
| Round 3 M6 (보상 이벤트 dedupe 소유/키) 해소 | **pass** | §4-10 ADR-16 보강 — 키는 eventUUID, 소유는 consumer 서비스(상품 서비스)(`MSA-TRANSITION.md:969-977`). 불변식 14(`:457`) + §7 행 #9(`:1153`) 재확인. |
| Round 3 minor (PENDING age · dedupe TTL · phase 이행 보상) 해소 | **pass** | ADR-20 pending_age_seconds histogram 유지(`:751`), ADR-16 보강 3번 retention+margin(`:976`), Phase 1 보상 경로는 모놀리스 상품 컨텍스트 동기 호출 유지 방침(§6 Phase 1 `:1068-1072`, §8-4 `:1184`). |
| 중복 승인 응답 2자 대조 충분성 | **major(신규)** | §4-10 ADR-05 보강 6번 "pg DB 레코드 부재 경로"(`MSA-TRANSITION.md:792`): "벤더 측 status=paid 이면 pg-service DB에 승인 결과를 신규 insert … APPROVED 발행". **벤더 재조회 amount와 command payload amount의 비교가 절차에 누락**. status=paid + 엉뚱한 금액 조합이 운영 알림만 띄우고 APPROVED로 흘러 들어간다. 부재 경로는 "정상이나 비정상" 희소 케이스이지만 금액 위변조가 뚫리는 구체적 돈 사고 경로. |
| inbox amount 컬럼 존재성 | **major(신규)** | §4-10 ADR-21 보강 "business inbox (orderId 기준)"(`MSA-TRANSITION.md:892`) 및 2-2b-4 불변식 4c(`:444`)는 "pg DB amount vs 벤더 재조회 amount 2자 대조"를 명시하나, **pg-service inbox 스키마에 amount 컬럼이 포함되는지 본문에 서술 없음**. "payload amount가 inbox 적재 시점에 DB amount로 고정"(`:790`)이 전제인데, 5상태 모델의 필드 정의나 Phase-2.x 산출물에 amount 저장이 명시되지 않는다. 스키마 불확정 상태에서 plan 단계가 amount 누락으로 구현하면 2자 대조의 좌변이 부재해 중복 승인 방어선이 런타임에 관철되지 않는다. |
| attempt·backoff 2중 워커 ImmediateWorker + PollingWorker 경쟁 방어 | **pass** | §4-10 ADR-04 보강 "중복 발행 방어"(`:837`): `UPDATE ... WHERE processed_at IS NULL` 원자 조건 + `FOR UPDATE SKIP LOCKED`. 불변식 11(`:454`) + §7 행 #12(`:1156`)로 교차 확인. |
| DLQ consumer 크래시 시 중복 QUARANTINED 전이 방어 | **pass** | §4-10 ADR-30 "DLQ consumer 자체 실패"(`:938`) + §7 행 #14(`:1158`) + 불변식 6c(`:448`). pg_inbox UNIQUE(order_id) + terminal 체크 + offset 미커밋 재처리. payment-service consumer의 eventUUID dedupe가 재발행 흡수. |
| FCG timeout → QUARANTINED 무조건 원칙 | **pass** | §4-10 ADR-15 보강 "FCG 불변: timeout · 네트워크 에러 · 5xx로 최종 확인 실패 시 무조건 QUARANTINED. 재시도·폴백·추측성 APPROVED 모두 금지"(`:869`). 불변 명시. |
| pg-service 2회 벤더 호출(중복 승인 경로 getStatus 재조회) rate limit 부담 | **minor** | §4-10 ADR-05 보강 3번 "벤더 getStatus 재조회"(`:789`). 중복 승인 응답은 희소(Pitfall 11) + `pg.duplicate_approval_hit` 카운터로 빈도 관측(`:791`). 일반 트래픽 영향 미미. |
| 재시도 총 시간 p99 ≤ 150s 합리성 | **pass** | 2+6+18+54=80s expected, jitter ±25% 적용 상한 약 100s, 여유 마진 포함 150s. 벤더 일시 장애 대응으로 적절. 수학적 검증 가능(`:372`). |
| 발행·종결 invariant archiving 부재 | **minor** | ADR-30 본문(`:942`)이 "주기적 archiving 필요(후속 개선)"으로 명시. 불변식 21(`:465`)은 "장기 구간 성립, 누적 drift 0"인데 archiving이 없으면 장기 검증 윈도우가 무제한 늘어나 실제 drift 감지 신뢰도↓. §9에 분리 근거 있음 — 단기 SLO에는 무해. |
| QuarantineCompensationScheduler Redis INCR 재시도 멱등성 | **pass** | `quarantine_compensation_pending` 플래그가 복구 완료 마커 역할 — INCR 성공 후 플래그 해제 → 다음 스캔에서 제외(`:429-430`, §2-2b-5 E). 2단계 사이 "수 ms 크래시 윈도우"는 §2-2b-5 E에서 수용적 명시. |
| 금전 정확성 — 금액 위변조 선검증(LVAL) | **pass** | §2-2b-1 LVAL 박스(`:218-220`): 금액 불일치 시 4xx 거부. TX 진입 전 검증 유지. |
| PII 노출·저장 | **n/a** | §1-3 비목표(`:520`). 불변식 14b에서 paymentKey/buyerId Kafka retention 동기화 + 브로커 암호화/토큰화만 언급(`:458`). |

## 도메인 관점 추가 검토

### 1. Round 3 findings 전건 해소 재검증

Round 3의 도메인 critical 3건 · major 3건 · minor 3건이 각기 ADR/불변식/Phase 산출물에 어떻게 내려왔는지 위 체크리스트에 기록. 서류상 모두 반영. 특히 ADR-30의 outbox+available_at 설계는 Round 3에서 지적된 "retry 토픽별 파티션 drift"를 구조적으로 제거한 굵직한 진전이다. payment-service 기존 자산(`OutboxImmediateEventHandler` + `PaymentConfirmChannel` + `OutboxImmediateWorker` + `OutboxWorker`)을 그대로 재사용하면서 pg-service가 같은 패턴을 복제하는 구조도 검증된 코드를 재사용한다는 점에서 리스크 축소.

### 2. 신규 리스크 — §4-10 ADR-05 보강 6번 "pg DB 부재 경로" 금액 비교 누락 (major)

§4-10 ADR-05 보강 6번(`MSA-TRANSITION.md:792`)의 원문:

> **pg DB 레코드 부재 경로**: 벤더 재조회로 현 PG 측 상태 · amount · 승인 시각을 확인한다. 벤더 측 status=paid 이면 pg-service DB에 승인 결과를 신규 insert(inbox NONE → APPROVED 원자 전이) 후 `payment.events.confirmed(status=APPROVED)` 발행.

여기서 벤더 재조회로 amount를 확인한다고만 할 뿐, 그 amount를 **command payload amount (LVAL로 검증된 값)와 비교**하는 절차가 없다. 실제 중복 승인 응답이 오는 경로는:
- (a) 정상 경로: pg-service가 벤더를 이미 호출 → pg DB에 승인 레코드 존재 → 2자 대조(pg DB vs 벤더) → pg DB amount=payload amount이 보장(LVAL)되므로 벤더 amount만 일치하면 OK. **방어 유효**.
- (b) 부재 경로 (6번): pg DB에 승인 레코드가 **없다**. 이 경우 "pg DB amount"라는 좌변 자체가 없으므로 2자 대조가 작동하지 않는다. 오직 벤더 amount만 확인 가능.
  - 만약 벤더 측이 어떤 이유로 잘못된 amount로 승인을 기록했다면(예: 벤더 쪽 중복 요청 처리에서 과거 요청 amount를 보유), status=paid 확인만으로 APPROVED 발행 시 **결제 금액이 틀어진 채 DONE으로 흐른다**.
  - 절차에 "벤더 amount ?= command payload amount" 비교 단계가 없다.

**영향**: 희소 경로이지만 돈이 새는 실제 경로. discuss 단계의 수락 기준으로서 phase 2 구현 태스크가 "status=paid만 확인하면 된다"로 오해될 여지. 운영 알림만으로 이를 잡으려면 알림 수신자가 실시간 금액 비교를 수동으로 해야 하는데 이는 비현실적.

**보강 제안**(major, phase 2 plan 반영 전 문서 수정 필요): §4-10 ADR-05 보강 6번에 "벤더 측 status=paid 이면 **벤더 재조회 amount와 command payload amount의 일치를 검증**한다. 일치하면 pg-service DB에 승인 결과를 신규 insert + APPROVED 발행 + 운영 알림; 불일치하면 QUARANTINED + reasonCode=AMOUNT_MISMATCH 발행"으로 명시. 불변식 4c(`:444`)에도 부재 경로 금액 비교를 추가.

### 3. 신규 리스크 — pg-service business inbox amount 컬럼 스키마 불확정 (major)

§4-10 ADR-21 보강(`MSA-TRANSITION.md:892`)은 business inbox를 다음과 같이 정의한다:

> **business inbox (orderId 기준)**: orderId 단위 멱등 경계. 5상태 모델 운영. 재명령 수신 시 상태별로 처리 분기.

5상태 집합 `{NONE, IN_PROGRESS, APPROVED, FAILED, QUARANTINED}`은 상태 · 저장된 status · 저장된 amount를 언급하나(§4-10 ADR-05 보강 1번 "상태·저장된 승인 결과·저장된 amount 확인"; `:785`), **business inbox 스키마에 amount 컬럼이 포함된다는 명시적 기술은 본문 어디에도 없다**. §2-2b-4 불변식 4c(`:444`)가 "pg DB amount vs 벤더 재조회 amount" 2자 대조를 상정하므로 amount 컬럼이 필수적으로 존재해야 하지만, ADR-21의 inbox 정의 본문과 Phase-2.x 산출물 리스트(`:1085-1087`)에 amount 컬럼 추가 산출물이 없다.

**두 가지 해석 경로**:
- (해석 A) inbox는 {orderId, status, reasonCode, updated_at} 정도 경량 테이블이고, amount는 별도 `pg_payment` 승인 결과 테이블에 있다. 이 경우 "pg DB amount"는 `pg_payment` 테이블에서 가져오며, 2자 대조의 구현은 JOIN. 합리적이지만 본문에 명시 없음.
- (해석 B) inbox 자체에 amount 컬럼이 있고 NONE→IN_PROGRESS 전이 시 payload amount를 기록. 본문 "payload amount는 이미 inbox 적재 시점에 DB amount로 고정"(`:790`)이 이 해석을 뒷받침하지만 ADR-21 본문에는 내려오지 않았다.

**영향**: plan 단계에서 inbox 스키마를 설계할 때 amount 컬럼을 누락하면 2자 대조가 런타임에 작동하지 않는다. 불변식 4c는 런타임에 관철되지 않는 죽은 조항이 된다. 중복 승인 응답 방어선의 실효성이 구현 선택에 의존하게 된다.

**보강 제안**(major): §4-10 ADR-21 보강 "business inbox (orderId 기준)" 항목 하위에 **"inbox 스키마 필드: `order_id UNIQUE`, `status ENUM(5상태)`, `amount DECIMAL NOT NULL`, `stored_result_json?`, `created_at`, `updated_at`"** 수준의 스키마 스케치를 추가. 또는 "inbox는 orderId 키 + amount 컬럼을 포함하며 NONE→IN_PROGRESS 전이 시 command payload amount를 기록한다"를 수락 기준 (x)로 승격. Phase-2.x 산출물(`:1085`)에도 "inbox amount 컬럼 포함"을 명시.

### 4. 그 외 도메인 관점 확인 — 상태 전이 · race window · 금전 정확성

- **상태 전이 단조성**: inbox 5상태의 terminal 집합을 `{APPROVED, FAILED, QUARANTINED}` 3상태로 확장해 QUARANTINED 재수신 시에도 "저장된 status 재발행 + 벤더 재호출 금지" 유지. Round 3에서 지적된 "단조 전이 역전" 경로 봉쇄(`:897-901`). OK.
- **inbox race 봉쇄**: UNIQUE(order_id) + INSERT ON DUPLICATE KEY UPDATE 또는 SELECT FOR UPDATE, 둘 중 plan 확정(`:902-905`). 수락 기준 (vi)에 명시(`:907`). OK.
- **Immediate + Polling 워커 경쟁**: `UPDATE ... WHERE processed_at IS NULL` + `FOR UPDATE SKIP LOCKED` 이중 방어(`:837`, 불변식 11 `:454`). OK.
- **DLQ 경로 중복 QUARANTINED 방어**: offset 미커밋 재처리 + pg_inbox UNIQUE + terminal 체크 + eventUUID dedupe 4중 방어(`:938`, 불변식 6c `:448`, §7 행 #14 `:1158`). OK.
- **2단계 QUARANTINED 복구 크래시 윈도우**: §2-2b-5 E에서 "수 ms 윈도우" 명시적 수용. `QuarantineCompensationScheduler`가 플래그 잔존 레코드를 스캔. Scheduler 구현 리스크는 plan 단계 code 라운드에서 검증 가능. OK.
- **금전 정확성 — LVAL**: TX 진입 전 payment-service 로컬 검증 유지(`:218-220`). Round 3 이전 수준 유지. OK.
- **pg_outbox archiving 부재**: 불변식 21 "장기 구간 drift 0"이 archiving 없이도 성립 가능한지는 의문(장기 누적 row 자체가 invariant 쿼리 성능을 저하). §9-6 후속 개선으로 분리된 것 확인. 단기 SLO에는 무해. **minor**.

### 5. Phase 2.c 이후 최종 상태 일관성

§3 토폴로지(`:600-687`)가 "Phase 2.c 이후 최종 상태"로 명시. payment reconciler 부재 명시(`:693`). outbox 지연 재발행 파이프라인이 reconciler의 역할을 subsume. 과도기 컴포넌트(Phase 2.a Shadow · Phase 2.b 스위치)는 §6 Phase 2 본문에 시간적 맥락으로 격리(`:1089-1115`). 시간축 정합성 OK.

## Findings

- **[major]** §4-10 ADR-05 보강 6번 "pg DB 레코드 부재 경로"(`docs/topics/MSA-TRANSITION.md:792`)에서 **벤더 재조회 amount와 command payload amount의 일치 검증이 절차에 누락**. 벤더 측 status=paid 확인만으로 APPROVED 발행되므로, 벤더 쪽이 잘못된 amount로 승인을 기록한 희소 케이스에서 금액 위변조가 pg 경계를 통과. 6번을 "벤더 amount == payload amount 검증 → 일치 시 APPROVED + 운영 알림, 불일치 시 QUARANTINED + reasonCode=AMOUNT_MISMATCH"로 수정 + 불변식 4c에도 부재 경로 금액 비교 조항 추가 필요.
- **[major]** §4-10 ADR-21 보강 business inbox(`:892`) · 불변식 4c(`:444`) · Phase-2.x 산출물(`:1085-1087`)에서 **inbox 스키마에 amount 컬럼이 포함되는지 명시 없음**. "pg DB amount vs 벤더 재조회 amount 2자 대조"의 좌변 출처가 스키마 수준에서 불확정. plan 단계가 amount 없는 inbox로 구현하면 2자 대조 불변식이 런타임에 관철되지 않아 중복 승인 방어선이 죽는 구체적 경로. ADR-21 inbox 정의에 "스키마 필드: order_id UNIQUE, status ENUM(5상태), amount DECIMAL NOT NULL, ..." 수준의 스케치 + 수락 기준 (x) "NONE→IN_PROGRESS 전이 시 command payload amount 기록" 승격 필요.
- **[minor]** pg_outbox archiving 정책(`:942`, §9-6)이 후속 개선으로 분리 — 불변식 21 "장기 구간 drift 0" 검증의 실효 윈도우 제한. 단기 SLO에는 무해하나 phase 4 장애 주입 검증 시 누적 row로 쿼리 성능 저하 가능. plan 단계에서 "archiving 미도입 상태의 invariant 검증 윈도우 상한"만이라도 명시 권고.
- **[minor]** `QuarantineCompensationScheduler`의 "복구 완료 마커"(`:429-430`)가 `quarantine_compensation_pending` 플래그 자체임을 본문에서 암시만 함. plan 단계 code 라운드가 별도 "Redis INCR 성공 저널"을 도입해 중복 방지를 두 번 쌓을지 결정해야 할 여지. 본 discuss에서는 수용 범위.
- **[n/a]** PII·보안 경로는 본 토픽 비목표(§1-3). 불변식 14b가 Kafka retention 동기화 + 브로커 암호화/토큰화 원칙만 언급.

## JSON

```json
{
  "topic": "MSA-TRANSITION",
  "stage": "discuss",
  "round": 4,
  "persona": "domain-expert",
  "artifact_ref": "docs/topics/MSA-TRANSITION.md",
  "previous_round_ref": "docs/rounds/msa-transition/discuss-domain-3.md",
  "decision": "revise",
  "round_3_followup": {
    "critical_1_inbox_monotonic_terminal": "reflected",
    "critical_2_quarantine_tx_atomicity": "reflected",
    "critical_3_retry_topic_explosion": "reflected",
    "major_1_attempt_header_initial": "reflected",
    "major_2_retry_limit_ownership": "reflected",
    "major_3_compensation_event_dedupe": "reflected",
    "minor_pending_age_dedupe_ttl_phase_transition": "reflected"
  },
  "findings": [
    {
      "severity": "major",
      "domain_risk": "중복 승인",
      "section": "§4-10 ADR-05 보강 6번",
      "line": 792,
      "description": "pg DB 레코드 부재 경로에서 벤더 재조회 amount와 command payload amount 일치 검증이 절차에 누락. status=paid만으로 APPROVED 발행되므로 벤더 쪽 amount가 잘못된 희소 케이스에서 금액 위변조가 pg 경계를 통과. 6번을 '벤더 amount == payload amount 검증 → 일치 시 APPROVED + 운영 알림, 불일치 시 QUARANTINED + reasonCode=AMOUNT_MISMATCH'로 수정 필요. 불변식 4c(§2-2b-4)에도 부재 경로 금액 비교 조항 추가 필요."
    },
    {
      "severity": "major",
      "domain_risk": "중복 승인|inbox",
      "section": "§4-10 ADR-21 보강 business inbox + 불변식 4c + Phase-2.x 산출물",
      "line": 892,
      "description": "inbox 스키마에 amount 컬럼이 포함되는지 본문에 명시 없음. '2자 금액 대조 (pg DB vs 벤더)'의 좌변 출처가 스키마 불확정. plan 단계가 amount 없는 inbox로 구현하면 불변식 4c가 런타임에 관철되지 않아 중복 승인 방어선이 죽는다. ADR-21 inbox 정의에 스키마 스케치 + 수락 기준 (x) 'NONE→IN_PROGRESS 전이 시 command payload amount 기록' 명시 필요."
    },
    {
      "severity": "minor",
      "domain_risk": "감사",
      "section": "§4-10 ADR-30 + §9-6",
      "line": 942,
      "description": "pg_outbox archiving 정책이 후속 개선으로 분리. 불변식 21 '장기 구간 drift 0' 검증의 실효 윈도우가 제한. 단기 SLO에 무해하나 phase 4 장애 주입 시 누적 row로 쿼리 성능 저하. plan 단계에서 'archiving 미도입 상태의 invariant 검증 윈도우 상한' 명시 권고."
    },
    {
      "severity": "minor",
      "domain_risk": "재시도",
      "section": "§2-2b-3 + §2-2b-5 E",
      "line": 430,
      "description": "QuarantineCompensationScheduler의 '복구 완료 마커'가 quarantine_compensation_pending 플래그 자체임을 암시만. plan 단계 code 라운드가 별도 'Redis INCR 성공 저널'을 도입해 중복 방지를 두 번 쌓을지 결정해야 할 여지. 본 discuss에서는 수용 범위."
    },
    {
      "severity": "n/a",
      "domain_risk": "기타",
      "section": "§1-3",
      "line": 520,
      "description": "PII·보안 경로는 본 토픽 비목표. 불변식 14b가 Kafka retention 동기화 + 브로커 암호화/토큰화 원칙만 언급."
    }
  ],
  "counts": {
    "critical": 0,
    "major": 2,
    "minor": 2,
    "n/a": 1
  },
  "summary": "Round 3 critical·major 전건 해소. 그러나 Round 4 재설계 과정에서 pg DB 부재 경로 금액 비교 누락 + inbox amount 컬럼 스키마 불확정 2건이 신규 major로 발견 — 둘 다 중복 승인 응답 방어선의 실효성이 구현 선택에 의존하는 돈 사고 경로. revise."
}
```
