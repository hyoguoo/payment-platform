# discuss-domain-5

**Topic**: MSA-TRANSITION
**Round**: 5
**Persona**: Domain Expert

## Reasoning

Round 5 판정 범위는 Round 4 두 major(M1: pg DB 부재 경로 금액 비교 누락, M2: inbox amount 컬럼 스키마 불확정)의 해소 여부 하나다. Architect 4.3이 수정한 두 지점(§4-10 ADR-05 보강 6번 `MSA-TRANSITION.md:792` + ADR-21 보강 business inbox 스키마 `:893` + 불변식 4c `:444` + ADR-21 수락 기준 (ix) `:911`)과 보호 체인(§7 행 #8·#14, §8-4, §2-2b-2 mermaid의 REFETCH → ABSENT_OK/QMISMATCH 분기)을 실제 문서 라인으로 교차 검증했다. 두 지점 모두 돈이 새는 구체 경로가 스키마·절차·불변식 삼층에서 닫혔다. 단위/타입 표기(M2 수정에서 `amount BIGINT NOT NULL`로 확정)만 기존 도메인의 `BigDecimal` 표현과 표면 충돌처럼 읽히나 KRW 정수 범위에서는 값 비교 정확성에 영향이 없고, 이는 plan 단계 스키마 확정 태스크에서 해소 가능한 표기 문제이므로 돈 경로 리스크로는 minor. Round 4에서 이미 minor로 수용됐던 항목(archiving 분리, Scheduler 복구 마커)은 재판정 금지 지침에 따라 re-raise 하지 않는다. **판정: pass**.

## Domain risk checklist

| 항목 | 판정 | 근거 |
|---|---|---|
| M1 해소 — 벤더 재조회 amount vs command payload amount 비교가 6번 절차에 명시됐는가 | **pass** | `MSA-TRANSITION.md:792` "벤더 재조회 amount와 command payload(Kafka 메시지) 요청 amount의 일치를 검증한다(Domain Expert Round 4 major M1 적용)" — 절차 문장 수준에서 검증 단계가 삽입됨. 기존 4번의 2자 대조 좌변 `inbox.amount(=payload)` 규약과 일관. |
| M1 해소 — 불일치 시 QUARANTINED 경로 명확성 | **pass** | `:792` "불일치 시에는 inbox를 QUARANTINED로 전이(§2-2b-3 재사용) + `payment.events.confirmed(status=QUARANTINED, reasonCode=AMOUNT_MISMATCH)` 발행, 그 외 재발행 금지". 재사용 지점(§2-2b-3)이 기존 2단계 복구 + `quarantine_compensation_pending` 플래그로 수렴되어 새 경로 추가 비용 없음. |
| M1 해소 — 불변식 4c 보호 범위 확대 | **pass** | `:444` 불변식 4c에 "pg DB 레코드 부재 → 벤더 재조회로 금액 확인 + APPROVED 처리 + 운영 알림" 항이 포함됨. 검증 테스트로 `pg_duplicate_approval_WhenPgDbAbsent_ShouldAlertAndApprove` 지정 — runtime 관철 설계. 절차 본문(`:792`)에서 "이 경로도 불변식 4c 보호 범위에 포함된다"는 명시가 삽입됨. |
| M1 해소 — status=paid + amount 불일치에서 `payment.events.confirmed(APPROVED)` 발행 차단 | **pass** | `:792` 본문이 "일치 시에만 ... APPROVED 발행"을 어휘적으로 고정. 불일치 시에만 QUARANTINED 발행 경로로 분기, 그 외 APPROVED 재발행은 문장 수준에서 금지. 돈 사고 직결 경로 봉쇄. |
| M2 해소 — inbox 스키마에 amount 컬럼 명시 | **pass** | `:893` "business inbox 스키마 필드: `order_id`(UNIQUE), `status`(5상태 ENUM), `amount BIGINT NOT NULL`, `stored_status_result`, `reason_code`, `created_at`, `updated_at`" — 스키마 수준에서 amount 컬럼 확정. ADR-21 수락 기준 (ix)(`:911`)에도 2자 대조 전제가 수락 조건으로 승격. |
| M2 해소 — amount 저장 시점 규약(IN_PROGRESS payload / APPROVED 검증 후) | **pass** | `:893` "(a) NONE→IN_PROGRESS 전이 시 command payload amount 기록, (b) IN_PROGRESS→APPROVED 전이 시 벤더 2자 재조회 amount == inbox.amount 검증 통과 값만, (c) pg DB 부재 경로에서도 NONE→APPROVED 직접 전이 시 벤더 재조회 amount == payload amount 검증 통과 값만". 세 경로 모두 저장 시점의 "검증 통과" 조건이 명시. |
| M2 해소 — 불변식 4c 좌변이 "pg business inbox.amount"로 확정 | **pass** | `:893` 마지막 문장 "불변식 4c의 좌변 출처가 스키마 수준에서 확정된다". §2-2b-2 mermaid(`:259`)의 AMOUNT 노드 "pg DB amount vs 벤더 재조회 amount"와 일관 — 다이어그램-본문-불변식 삼층 수렴. |
| Q7/Q8/Q9 기존 결정과 충돌 | **pass** | Q7/Q8/Q9는 토픽 구성/발행 파이프라인/재시도 상한 소유권 계열 — M1/M2 수정은 pg-service inbox 내부·ADR-05 6번 세부에 국한되어 토픽 수(3개)·파이프라인 4단·attempt 소유권(pg-service consumer)에 변경 없음. ADR-14·ADR-30 본문 건드리지 않음. |
| §2-2b-2/ADR-30 아웃박스+이벤트 패턴 충돌 | **pass** | M1 경로에서 발행되는 `QUARANTINED + AMOUNT_MISMATCH`는 기존 `pg_outbox` row 경로(PG_OUT4 → PGRELAY → EVTPUB; `:281-289`)를 그대로 사용. 신규 토픽·워커·consumer 필요 없음. |
| §7 크래시 매트릭스 충돌 | **pass** | 행 #8(pg-service 크래시 · FCG 최종 확인)과 행 #14(DLQ consumer 크래시 · terminal 체크)는 M1/M2 수정 전후 동일. 부재 경로 진입 시에도 pg_inbox UNIQUE + 원자 전이가 중복 APPROVED 삽입을 차단. |
| §8 관측성 충돌 | **pass** | `:792` "기존 QUARANTINED 알럿 흐름(§8)을 그대로 재사용" 명시. AMOUNT_MISMATCH reasonCode는 `payment.quarantine_reason` 태그 기존 카테고리(§2-2b-3 METRIC `:417`)로 분기 — 신규 대시보드 불필요. |
| amount 단위·타입 일관성(BIGINT vs BigDecimal) | **minor** | Architect 4.3이 `amount BIGINT NOT NULL`(원화 최소 단위 정수 표기, `PaymentEvent.totalAmount`와 동일 단위)로 확정했는데, 기존 `PaymentEvent`·`PaymentConfirmEvent`·`PaymentOrder.totalAmount`는 전부 `BigDecimal` 타입. KRW 정수 범위에서는 값 비교 정확성에 영향 없음(소수부 부재). 그러나 pg-service inbox가 BIGINT로 저장되고 Kafka payload는 (현 구현 기준) BigDecimal 직렬화이므로 교차 경계에서 변환 스케일 명시가 필요. **돈이 새는 경로는 아니며 plan 단계 inbox 스키마 태스크에서 "BIGINT 수신 시 BigDecimal 표현 변환 규약(scale=0)" 한 줄로 흡수 가능**. Round 5 pass 판정에는 영향 없음. |
| 재판정 금지 대상(Round 4 minor) | **skip** | archiving 분리(§9-6)·Scheduler 복구 마커는 Round 4에서 이미 minor로 수용된 항목. 지침에 따라 re-raise 하지 않는다. |

## 도메인 관점 추가 검토

### 1. M1 해소 — 실제 라인 검증

§4-10 ADR-05 보강 6번(`MSA-TRANSITION.md:792`)의 수정된 원문 핵심:
> 벤더 측 status=paid 이면 **벤더 재조회 amount와 command payload(Kafka 메시지) 요청 amount의 일치를 검증한다**(Domain Expert Round 4 major M1 적용 — 이 경로도 불변식 4c "벤더 2자 amount 검증" 보호 범위에 포함된다). 일치 시에만 pg-service DB에 승인 결과를 신규 insert하며 이때 inbox `amount` 컬럼에 command payload amount를 함께 기록한다(inbox NONE → APPROVED 원자 전이) → `payment.events.confirmed(status=APPROVED)` 발행 + 운영 알림 발생. ... 불일치 시에는 inbox를 QUARANTINED로 전이 + `payment.events.confirmed(status=QUARANTINED, reasonCode=AMOUNT_MISMATCH)` 발행, 그 외 `payment.events.confirmed` 재발행은 금지

M1에서 요청했던 네 지점이 모두 충족:
- (a) 벤더 재조회 amount와 command payload amount의 일치 검증 — 문장 핵심에 삽입
- (b) 불일치 시 QUARANTINED 전이 경로 — "§2-2b-3 재사용" 명시
- (c) 불변식 4c 보호 범위 — 본문 괄호 안 "보호 범위에 포함된다" + `:444` 4c에 부재 경로 조항 추가
- (d) 돈 사고 방지 — 불일치 시 APPROVED 발행 금지 + QUARANTINED + AMOUNT_MISMATCH로만 발행

불변식 4c(`:444`)가 검증 테스트 `pg_duplicate_approval_WhenPgDbAbsent_ShouldAlertAndApprove`를 지정하므로 plan 단계 구현 태스크에 테스트 keyword가 걸려 있다 — runtime 관철 강제 장치 존재.

### 2. M2 해소 — 실제 라인 검증

§4-10 ADR-21 보강(`MSA-TRANSITION.md:893`)의 수정된 원문 핵심:
> business inbox 스키마 필드(Domain Expert Round 4 major M2 적용 — 불변식 4c 좌변 출처 확정): `order_id`(UNIQUE), `status`(5상태 ENUM), `amount BIGINT NOT NULL`(원화 최소 단위 정수 표기, `PaymentEvent.totalAmount`와 동일 단위), ... `amount` 저장 규약: (a) NONE → IN_PROGRESS 전이 시 command payload(Kafka 메시지)의 요청 amount를 그대로 기록, (b) IN_PROGRESS → APPROVED 전이 시 벤더 2자 재조회 amount와 inbox.amount 일치 검증을 통과한 값만 저장(불일치 시 QUARANTINED 전이), (c) "pg DB 부재 경로"(§4-10 ADR-05 보강 6번)에서도 NONE → APPROVED 직접 전이 시 벤더 재조회 amount == command payload amount 검증을 통과한 값만 기록. 이로써 불변식 4c "pg business inbox.amount vs 벤더 재조회 amount 2자 대조"의 좌변 출처가 스키마 수준에서 확정된다.

M2에서 요청했던 세 지점이 모두 충족:
- (a) inbox 스키마에 amount 컬럼 명시 — `amount BIGINT NOT NULL` 필드 명시
- (b) 저장 시점 규약(payload amount · 검증 후 amount) — (a)(b)(c) 세 경로 모두 전이별 저장 규약 명시
- (c) 불변식 4c 좌변 스키마 확정 — 마지막 문장이 명시적으로 선언

ADR-21 수락 기준 (ix)(`:911`)에도 2자 대조 + 부재 경로 분기가 수락 기준으로 승격되어 Phase-2.x 산출물 gate로 묶였다.

### 3. 기존 결정과의 충돌 여부

- **Q7/Q8/Q9(토픽·파이프라인·attempt 소유)**: ADR-14(`:844-854`)·ADR-30(`:913-951`)·수락 기준 `:945-950` 모두 변경 없음. M1/M2 수정은 pg-service 내부 inbox 스키마 + ADR-05 6번 절차 세부 수정에 국한.
- **§2-2b-2 mermaid**: AMOUNT 노드(`:259`)의 "pg DB amount vs 벤더 재조회 amount"는 M2 수정으로 "pg DB amount = inbox.amount" 출처가 확정되어 오히려 강화. REFETCH → ABSENT_OK 경로(`:267-268`)는 "금액 확인" 추상 표기이나 ADR-05 6번 본문에서 구체화되어 다이어그램-본문 불일치 없음.
- **§7 크래시 매트릭스**(`:1144-1159`): M1/M2 수정 지점이 행 #8(pg-service 내부)·#13(Consumer 크래시)·#14(DLQ consumer 크래시)에 영향을 주지 않는다. pg_inbox UNIQUE(order_id) + NONE→APPROVED 원자 전이가 부재 경로의 중복 insert를 차단.
- **§8 관측성**: `payment.quarantine_reason` 태그(`:417`)가 이미 `amount_mismatch` 분기를 갖고 있고 본 수정에서 "기존 QUARANTINED 알럿 흐름 재사용" 명시.

### 4. 타입/단위 일관성 minor

Architect 4.3 문구 "`amount BIGINT NOT NULL`(원화 최소 단위 정수 표기, `PaymentEvent.totalAmount`와 동일 단위)"의 교차 검증:
- 실제 소스: `src/main/java/com/hyoguoo/paymentplatform/payment/domain/event/PaymentConfirmEvent.java:12`의 `amount`는 `BigDecimal`. `PaymentOrder.totalAmount`(`PaymentOrder.java:24`)도 `BigDecimal`.
- `docs/context/INTEGRATIONS.md:44` Toss 응답 `totalAmount`는 `double`, `:132` `payment_order.amount`는 `DECIMAL`.
- 따라서 Architect 4.3 표기는 **DB 타입만 BIGINT로 결정**하고 "동일 단위"는 scale=0 정수 값이라는 의미로 해석해야 하나, 문장이 "PaymentEvent.totalAmount와 동일 단위"라고만 서술해 **타입(BIGINT vs BigDecimal) 변환 규약**은 명시되지 않음.
- **돈 경로 영향**: KRW는 소수점 없는 정수이므로 값 비교의 정확성은 훼손되지 않는다. 과오기 가능성은 두 가지 — (1) plan 단계에서 `BigDecimal.longValueExact()` 변환 시 scale>0인 예외(현재 구현상 불가능)로 ArithmeticException, (2) Kafka payload 직렬화 포맷(현재 JSON + BigDecimal)과 DB BIGINT 사이 명시적 매핑 누락. 둘 다 plan 단계 태스크 "inbox 스키마 마이그레이션 + payload ↔ DB 변환 규약 한 줄 추가"로 흡수 가능.
- **결론**: 돈 사고 경로가 아니므로 **minor**. Round 5 pass 판정 유지.

### 5. Round 4 minor re-raise 금지

Round 4 findings 중 minor 2건(archiving 분리·Scheduler 복구 마커)은 지침대로 재판정하지 않는다. Round 5 findings에는 기재하지 않는다.

## Findings

- **[minor]** §4-10 ADR-21 보강(`MSA-TRANSITION.md:893`)의 `amount BIGINT NOT NULL`(원화 최소 단위 정수, `PaymentEvent.totalAmount`와 동일 단위) 표기가 기존 도메인 타입(`BigDecimal`)과 표면 불일치. 값 비교 정확성(KRW 정수 범위)에는 영향 없음. plan 단계 inbox 스키마 태스크에서 "payload `BigDecimal` ↔ DB `BIGINT` 변환 규약(scale=0 정수 변환, 음수·소수 거부)" 한 줄 삽입으로 해소 가능한 표기 문제. 돈 경로 리스크는 아니며 Round 5 판정에 영향 없음.
- **[n/a]** PII·보안은 본 토픽 비목표(§1-3). M1/M2 수정이 PII 처리 경계를 건드리지 않음.

## JSON

```json
{
  "topic": "MSA-TRANSITION",
  "stage": "discuss",
  "round": 5,
  "persona": "domain-expert",
  "artifact_ref": "docs/topics/MSA-TRANSITION.md",
  "previous_round_ref": "docs/rounds/msa-transition/discuss-domain-4.md",
  "decision": "pass",
  "round_4_major_followup": {
    "M1_pg_db_absent_amount_comparison": "resolved",
    "M1_mismatch_quarantine_path": "resolved",
    "M1_invariant_4c_coverage": "resolved",
    "M1_approved_emission_blocked_on_mismatch": "resolved",
    "M2_inbox_schema_amount_column": "resolved",
    "M2_amount_storage_timing_rules": "resolved",
    "M2_invariant_4c_left_side_schema_pin": "resolved"
  },
  "findings": [
    {
      "severity": "minor",
      "domain_risk": "금전 정확성(표기)",
      "section": "§4-10 ADR-21 보강 business inbox 스키마",
      "line": 893,
      "description": "amount BIGINT NOT NULL 표기가 기존 PaymentEvent.totalAmount(BigDecimal) 타입과 표면 불일치. KRW 정수 범위에서 값 비교 정확성 영향 없음 — plan 단계 inbox 스키마 태스크에서 'payload BigDecimal ↔ DB BIGINT scale=0 변환 규약' 한 줄 추가로 해소. 돈 경로 리스크 아님."
    },
    {
      "severity": "n/a",
      "domain_risk": "PII",
      "section": "§1-3",
      "line": 520,
      "description": "PII·보안은 본 토픽 비목표. M1/M2 수정이 PII 처리 경계를 건드리지 않음."
    }
  ],
  "counts": {
    "critical": 0,
    "major": 0,
    "minor": 1,
    "n/a": 1
  },
  "notes": "M1(pg DB 부재 경로 금액 비교)·M2(inbox amount 컬럼 스키마) 모두 ADR-05 보강 6번·ADR-21 보강 스키마·불변식 4c 삼층에서 해소 확인. 신규 minor는 타입 표기 불일치(돈 경로 아님). Q7/Q8/Q9·ADR-30·§7·§8과 신규 충돌 없음.",
  "summary": "M1/M2 두 major 모두 해소. 돈이 새는 경로 잔존 없음. pass."
}
```
