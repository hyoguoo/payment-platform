# discuss-domain-2

**Topic**: MSA-TRANSITION
**Round**: 2
**Persona**: Domain Expert

## Reasoning

Round 1 도메인 지적 7건은 **모두 반영**되었다. 특히 두 `critical`(ADR-13 전제 오류 · 보상 이벤트 consumer dedupe 공백)이 § 4-10의 수락 기준 및 § 6 Phase 1 필수 산출물로 승격되면서 근거 사실이 소스 코드(`PaymentHistoryEventListener.java:20` BEFORE_COMMIT)와 정렬됐고, ADR-15 × ADR-21 상호 참조가 § 5 의존 그래프에 점선 edge로 추가됐다. `ALREADY_PROCESSED_PAYMENT`/`2201` 가면 방어가 § 6 Phase 1 필수 산출물로 승격된 것도 확인됐다. 다만 **신규 도메인 리스크 3건**(대부분 `minor`)이 남아 있다: (i) ADR-05 수락 기준이 "금액 일치 검증"을 NicePay `2201` 경로에만 명시하고 Toss `ALREADY_PROCESSED_PAYMENT` 경로의 금액 검증 대칭이 불명확, (ii) ADR-16 수락 기준의 "dedupe 레코드 보존 기간"이 정량 기준 없이 서술적, (iii) § 7 행 #9의 "D12 가드(결제 서비스 로컬만 유효)" 문구가 Phase 1(상품 서비스 분리 전)과 Phase 3(분리 후)의 **이행 구간**에서 어느 쪽 가드가 먼저 작동하는지 모호. 이들은 phase gating만 명확히 하면 되는 수준이며 **critical 수준은 아니다**. 따라서 **pass**(minor 이월).

## Domain risk checklist

| 항목 | 판정 | 근거 |
|---|---|---|
| Round 1 critical-1 (ADR-13 전제 오류) 반영 | **pass** | `MSA-TRANSITION.md:130`에서 BEFORE_COMMIT 사실 재확인, § 4-10 ADR-13 보강(`MSA-TRANSITION.md:408-418`)에서 대안 (a')가 추가되고 (c)의 tradeoff "상태 전이 TX와 감사 insert 원자성 포기"가 명시됨. 권고로 (a)/(a')를 plan 기본안으로 제시. |
| Round 1 critical-2 (보상 이벤트 consumer dedupe 공백) 반영 | **pass** | § 4-10 ADR-16 보강(`MSA-TRANSITION.md:432-440`): "이벤트 UUID 필수", "consumer 측 소유", "ADR-06 연계로 Orchestrator 도입 시에도 consumer 책임 고정"까지 수락 기준화. § 7 행 #9(`MSA-TRANSITION.md:564`)도 "consumer(상품 서비스) 측 dedupe 테이블(이벤트 UUID 키)"로 갱신. |
| Round 1 major (FCG × PG 네트워크 경계 계약) 반영 | **pass** | § 4-10 ADR-15 × ADR-21 상호 참조(`MSA-TRANSITION.md:420-430`): ADR-21 수락 기준 3조항(raw state 반환, 재시도 호출자 책임, 가면 응답 금지) + ADR-15 불변 2조항(timeout → QUARANTINED, PG 장애에 끌려가지 않음). § 5 의존 그래프(`MSA-TRANSITION.md:500-501`)에 점선 양방향 edge 추가. |
| Round 1 major (ALREADY_PROCESSED_PAYMENT phase 승격) 반영 | **pass** | § 4-10 ADR-05 보강(`MSA-TRANSITION.md:397-406`) 수락 기준 4조항 + § 6 Phase 1 "필수 산출물" 명시(`MSA-TRANSITION.md:520-522`) + § 8-4에서 "후속 토픽 분리 방침을 번복한다"고 기록(`MSA-TRANSITION.md:594`). |
| Round 1 major (PG 에러 코드 이벤트 귀속) 반영 | **pass** | ADR-12 표(`MSA-TRANSITION.md:351`)에 대안 (d)(e) 추가 — 도메인 중립 enum vs PG 원문 포함. ADR-15 × ADR-21 상호 참조 3항에서 "가면 매핑 없이 원문 또는 도메인 중립 enum으로 투명하게" 교차 참조. |
| Round 1 minor (ADR-20 PENDING age 지표) 반영 | **pass** | ADR-20 표(`MSA-TRANSITION.md:369`)에 "수락 기준(채택과 무관): `payment.outbox.pending_age_seconds`(histogram) 추가" 및 § 7 행 #4(`MSA-TRANSITION.md:559`)에서 동일 지표 재참조. |
| Round 1 minor (문서 불일치) 반영 | **partial / n/a** | § 8-4 마지막 bullet(`MSA-TRANSITION.md:595`)에서 INTEGRATIONS.md "AFTER_COMMIT, @Async"가 `PaymentConfirmEvent`를 가리킴을 명시하고, `PaymentHistoryEventListener`의 BEFORE_COMMIT 사실은 ARCHITECTURE.md와 일치 — ADR-13 전제 판단에 영향 없음으로 정리. INTEGRATIONS.md 실 수정은 후속 TODOS로 분리. 본 토픽 판단의 관점에서는 OK. |
| 신규: Toss `ALREADY_PROCESSED_PAYMENT` 경로 금액 검증 대칭 | **minor** | ADR-05 보강 수락 기준 4번은 "NicePay `2201` 경로의 현 `handleDuplicateApprovalCompensation`(tid 재조회 + 금액 일치 검증) 로직은 Toss 쪽에도 대칭으로 확보"를 명시 — 잘 들어갔다. 다만 수락 기준 1-3번의 "DB 재조회"가 **금액 일치 검증까지 포함하는지** 명시되어 있지 않아, Toss 경로에서 금액 위변조(예: PG가 DONE이지만 결제된 금액이 달라진 희소 케이스)를 잡는 책임이 어느 단계에 있는지 불명확. |
| 신규: dedupe 레코드 TTL | **minor** | ADR-16 보강 수락 기준 3번(`MSA-TRANSITION.md:439`)이 "가능한 중복 수신 윈도우 + 안전 마진 · Kafka 파티션 재할당 시나리오 커버"로 서술적. **정량 기준**(예: "consumer group offset retention + 1일" 또는 "최소 N일")이 없어, plan 단계에서 dedupe 테이블 크기·정리 전략을 판단할 근거가 불충분. phase 4 장애 주입(ADR-29) 시 의도치 않게 dedupe가 만료되어 이중 복원이 재현될 리스크가 남는다. |
| 신규: § 7 행 #9 이행 구간 모호성 | **minor** | § 7 행 #9(`MSA-TRANSITION.md:564`)는 "D12 가드(결제 서비스 로컬만 유효) + consumer(상품 서비스) 측 dedupe 테이블"이라고 병기 — 그러나 Phase 1(결제만 분리, 상품 서비스는 아직 모놀리스)과 Phase 3(상품 분리 완료) 사이의 **이행 구간**에서, `stock.restore` 이벤트가 모놀리스 상품 컨텍스트로 흘러가는 시기가 있다. 이때 consumer 측 dedupe 테이블의 소유(모놀리스 쪽에 임시 구현? 결제 서비스 쪽에 TX 내 재조회 유지?)가 § 6 phase 구성에 명시되지 않아, **이행 기간 한정 이중 복원 경로**가 서류상 공백. |
| 상태 전이 불변성 | **pass** | Round 1과 동일 — 서비스별 로컬 TX로 축소가 § 7 행 #2(`MSA-TRANSITION.md:557`)에 유지. |
| 금전 정확성 | **pass** | 금액 위변조 선검증 유지. ADR-05 수락 기준 4번이 NicePay 2201 금액 일치 검증을 Toss 쪽에도 요구 — 다만 위 신규 item 참조. |
| PII 노출·저장 | **n/a** | 본 토픽 비목표(§ 1-3). |

## 도메인 관점 추가 검토

### 1. Round 1 findings 반영 상태 — 전건 완료 확인

- **critical-1 (ADR-13 전제)**: `MSA-TRANSITION.md:130`에서 현 코드 사실(`DomainEventLoggingAspect`는 AOP로 `ApplicationEvent` 발행 + `PaymentHistoryEventListener` BEFORE_COMMIT으로 같은 TX에서 `payment_history` insert)이 적시됐고, § 4-10 ADR-13 보강(`MSA-TRANSITION.md:408-418`)에서 대안 (a')(결제 서비스 내부 `payment_history` 유지 + cross-service 이벤트 이원화)가 추가돼 권고 방향으로 명시. (c)의 tradeoff 명시 요구도 반영. **수락**.
- **critical-2 (보상 이벤트 consumer dedupe)**: § 4-10 ADR-16 보강(`MSA-TRANSITION.md:432-440`)에서 (i) 이벤트 UUID 필수, (ii) consumer 측 소유, (iii) ADR-06 연계로 Orchestrator 도입 시에도 consumer 책임 고정까지 반영. § 7 행 #9(`MSA-TRANSITION.md:564`)와 § 5 의존 그래프에도 교차 참조 확보. **수락**.
- **major-1 (FCG × PG 계약)**: § 5 의존 그래프(`MSA-TRANSITION.md:500-501`)에 `A21 -.->|FCG 네트워크 경계| A15` 및 `A15 -.->|getStatus 계약 요구| A21` 양방향 점선 edge 추가 + § 4-10 상호 참조 섹션(`MSA-TRANSITION.md:420-430`)에서 ADR-21 수락 기준 3조항 · ADR-15 불변 2조항 본문화. **수락**.
- **major-2 (가면 방어 phase 승격)**: § 6 Phase 1 "필수 산출물(본 라운드 승격)"(`MSA-TRANSITION.md:520-522`) + § 8-4에서 방침 번복 명시(`MSA-TRANSITION.md:594`). **수락**.
- **major-3 (PG 에러 코드 이벤트 귀속)**: ADR-12 표(`MSA-TRANSITION.md:351`)에 (d)(e) 대안 추가, (d)는 도메인 중립 enum(`RETRYABLE`/`NON_RETRYABLE`/`AMBIGUOUS`/`DUPLICATE_APPROVAL`), (e)는 PG 원문 포함. **수락**.
- **minor (PENDING age 지표)**: ADR-20 표(`MSA-TRANSITION.md:369`) 수락 기준 + § 7 행 #4(`MSA-TRANSITION.md:559`) 교차 참조. **수락**.
- **minor (문서 불일치)**: § 8-4 마지막 bullet(`MSA-TRANSITION.md:595`)에서 INTEGRATIONS.md 문구가 `PaymentConfirmEvent`를 가리키는 것이라고 구분 명시 + ADR-13 전제는 `PaymentHistoryEventListener` BEFORE_COMMIT 사실과 ARCHITECTURE.md 일치 확인. INTEGRATIONS.md 실 수정은 후속 TODOS. ADR-13 판단에 영향 없다는 결론이 서류상 명확해졌다. **수락**.

### 2. 신규 리스크 — Toss `ALREADY_PROCESSED_PAYMENT` 금액 검증 대칭의 세부 공백

ADR-05 보강 수락 기준 1-3번(`MSA-TRANSITION.md:402-404`)은 "`PaymentEvent.status` DB 재조회 → DONE이면 no-op, DONE 아니면 QUARANTINED"를 명시한다. 4번(`MSA-TRANSITION.md:405`)은 "NicePay `2201` 경로의 `handleDuplicateApprovalCompensation`(tid 재조회 + **금액 일치 검증**) 로직은 Toss 쪽에도 대칭으로 확보"를 별도로 명시한다.

도메인 관점에서 볼 때 이 4번 조항이 1-3번과 **직교 방어선**인지 **1-3번의 세부 단계인지**가 불명확하다. 구체적으로:

- 1번의 "DB 재조회"는 결제 서비스의 `PaymentEvent` 상태만 본다. PG 쪽에서 "다른 금액으로 처리된 duplicate"라는 희소 시나리오(실제 PG 리턴 금액 ≠ 요청 금액)를 잡으려면 **PG 재조회**도 수반되어야 한다. 2번의 "`DONE`이면 no-op"만으로는 금액 위변조가 이미 confirmed된 상태로 남는다.
- 4번이 "Toss 쪽에도 대칭으로 확보"라고만 했을 뿐, Toss의 `ALREADY_PROCESSED_PAYMENT` 응답 처리 시 **PG 재조회 + 금액 비교**를 수락 기준 1-3번의 어느 단계에 꽂을지가 서술되어 있지 않다.

**영향**: Phase 1 결제 코어 분리 시 Toss 경로에서 금액 위변조 방어 구현 누락 리스크. plan 단계의 구현 태스크가 "DB만 보면 된다"로 오해될 수 있다.

**보강 제안**(minor): ADR-05 보강 수락 기준 1번을 "DB에서 `PaymentEvent.status` + **PG API `getStatus`로 실 금액 재조회**를 수행한다"로 명시하거나, 4번을 "`ALREADY_PROCESSED_PAYMENT`/`2201` 수신 시 수락 기준 1-3번 **전에** PG 재조회 + 금액 일치 검증을 수행하며, 실패 시 QUARANTINED"로 순서 명시.

### 3. 신규 리스크 — ADR-16 보강의 dedupe TTL 정량 기준 공백

ADR-16 보강 수락 기준 3번(`MSA-TRANSITION.md:439`)은 "dedupe 레코드의 보존 기간은 '가능한 중복 수신 윈도우 + 안전 마진'이어야 하며, Kafka 파티션 재할당 시나리오 커버"라고 서술적이다. 도메인 관점에서:

- Kafka consumer group offset retention은 기본 7일(`offsets.retention.minutes=10080`)이고, `__consumer_offsets` 토픽의 `log.retention.hours`로도 영향을 받는다. 파티션 재할당 시 과거 offset 재처리가 일어나는 가장 긴 윈도우는 이 설정에 연동된다.
- 보상 이벤트의 성격(`stock.restore`, `payment.refund`)은 **희소**하므로 dedupe 테이블 크기가 문제가 되진 않는다 — 그럼에도 정량 TTL이 없으면 phase 4 장애 주입(ADR-29) 실험 중 **"dedupe 만료 후 과거 offset 재주입"** 시나리오가 의도치 않게 이중 복원을 발생시킬 수 있다.

**영향**: phase 4 장애 주입 시 false-positive 정합성 깨짐이 기록될 수 있고, 원인이 "MSA 전환 악화"로 오인될 수 있다(실제로는 TTL 설정 결함).

**보강 제안**(minor): ADR-16 보강 수락 기준 3번을 "consumer group offset retention과 연동하여 **retention + 안전 마진(예: +1일) 이상**으로 보존"으로 정량화. 또는 ADR-29 phase 4 장애 주입 수락 기준에 "dedupe TTL 미스매치에 의한 false positive 배제"를 명시.

### 4. 신규 리스크 — § 7 행 #9의 phase 이행 구간 공백

§ 7 행 #9(`MSA-TRANSITION.md:564`)는 "D12 가드(결제 서비스 로컬만 유효) + consumer(상품 서비스) 측 dedupe 테이블(이벤트 UUID 키)"로 기술한다. 그러나:

- Phase 1(결제 코어 분리)에서는 상품 서비스가 **아직 모놀리스 안**에 있다. 이 때 `stock.restore` 이벤트는 Kafka를 거쳐 **모놀리스의 상품 컨텍스트**로 소비되거나, 아니면 결제 서비스 내부에서 여전히 `orderedProductUseCase.increaseStockForOrders`를 직접 호출한다. § 6 Phase 1 설명(`MSA-TRANSITION.md:517-523`)은 "결제 서비스 + 결제 DB 컨테이너 + Kafka 기반 릴레이"만 명시하고, 보상 경로가 이벤트화되는지 직접 호출로 남는지 **명시되지 않는다**.
- Phase 3(상품 분리 완료)에서는 소유가 상품 서비스로 이동한다.

이 이행 구간의 **이중 복원 방어 소유자**가 § 6·§ 7에 phase별로 명시되어 있지 않다. 만약 Phase 1에서 보상이 이벤트화되는데 상품 쪽 dedupe는 Phase 3에서야 신설된다면, **Phase 2 동안 모놀리스 상품 컨텍스트가 이중 복원을 흡수할 방어선이 없다**.

**영향**: Phase 2 운영 구간에서 CONCERNS "executePaymentFailureCompensation 이중 복원" 결함이 **일시적으로 악화**될 가능성. 본 토픽 목표인 "악화·유지·개선 분류"와 정면 배치.

**보강 제안**(minor): § 6 Phase 1 또는 Phase 2에 "보상 경로는 **결제 서비스 내부 동기 호출 유지**(이벤트화는 Phase 3과 동시 진행)" 또는 "Phase 1 동시에 모놀리스 상품 컨텍스트에 dedupe 테이블 신설"을 phase gating 조항으로 추가. 또는 § 7 행 #9에 "이행 구간(Phase 1-2) 방어선"을 별행으로 분리.

### 5. 그 외 도메인 관점 확인 — 상태 전이·race window·금전 정확성

- **상태 전이**: `PaymentEventStatus` enum 소유(container-per-service에서 결제 서비스 단일 소유)가 § 2-2(`MSA-TRANSITION.md:167`)와 § 7 행 #2(`MSA-TRANSITION.md:557`)에서 유지. **OK**.
- **claim race**: `claimToInFlight` REQUIRES_NEW가 결제 서비스 내부에 유지(§ 7 행 #7, `MSA-TRANSITION.md:562`). **OK**.
- **금전 정확성**: 금액 위변조 선검증은 요청 수신 직후 결제 서비스 로컬 단계에서 유지(그림 § 2-2). **OK**. 다만 항목 2의 Toss 금액 검증 대칭은 별도 리스크.
- **FCG 무한 재귀 방지**: ADR-15 불변 2조항 "timeout · 네트워크 에러 · 5xx → 무조건 QUARANTINED, 재시도·폴백 금지"가 § 4-10 상호 참조 섹션(`MSA-TRANSITION.md:429`)에 박혔다. **OK**.

## Findings

- **[minor]** ADR-05 보강 수락 기준에서 Toss `ALREADY_PROCESSED_PAYMENT` 경로의 **PG 재조회 + 금액 일치 검증** 순서가 1-3번과 4번 사이에 명시되지 않음. Phase 1 구현 시 "DB만 재조회하면 된다"로 오해될 수 있음. 수락 기준 1번에 "PG `getStatus`로 실 금액 재조회"를 명시하거나, 4번을 1-3번 이전 선행 단계로 순서 고정 필요.
- **[minor]** ADR-16 보강 수락 기준 3번(dedupe 레코드 보존 기간)이 "가능한 중복 수신 윈도우 + 안전 마진"으로 서술적 — 정량 기준(예: Kafka consumer group offset retention + 1일) 미명시. Phase 4 장애 주입(ADR-29)에서 false positive 이중 복원 경로 리스크.
- **[minor]** § 7 행 #9 · § 6 Phase 1-3 이행 구간에서 **보상(`stock.restore`) 경로의 소유자**(Phase 1: 결제 서비스 내부 동기 호출? 모놀리스 상품 컨텍스트 이벤트 수신?)가 phase별로 명시되지 않아, 이행 중 이중 복원 방어선 공백 가능성.
- **[n/a]** PII·보안 경로는 본 토픽 비목표.

## JSON
```json
{
  "topic": "MSA-TRANSITION",
  "stage": "discuss",
  "round": 2,
  "persona": "domain-expert",
  "artifact_ref": "docs/topics/MSA-TRANSITION.md",
  "previous_round_ref": "docs/rounds/msa-transition/discuss-domain-1.md",
  "decision": "pass",
  "round_1_followup": {
    "critical_1_adr13_premise": "reflected",
    "critical_2_compensation_dedupe": "reflected",
    "major_1_fcg_pg_network_contract": "reflected",
    "major_2_already_processed_phase_promotion": "reflected",
    "major_3_pg_error_code_event_schema": "reflected",
    "minor_1_pending_age_metric": "reflected",
    "minor_2_doc_consistency": "reflected_with_deferred_integrations_md_edit"
  },
  "findings": [
    {
      "severity": "minor",
      "area": "already-processed-amount-check-ordering",
      "summary": "ADR-05 보강 수락 기준에서 Toss ALREADY_PROCESSED_PAYMENT 경로의 PG 재조회 + 금액 일치 검증이 1-3번(DB 재조회)과 4번(대칭 요구) 사이에 순서 명시 없음",
      "evidence": [
        "docs/topics/MSA-TRANSITION.md:402-405 (ADR-05 보강 수락 기준 1-4)",
        "docs/context/INTEGRATIONS.md:86 (NicePay 2201 금액 일치 검증)",
        "src/main/java/com/hyoguoo/paymentplatform/paymentgateway/infrastructure/gateway/nicepay/NicepayPaymentGatewayStrategy.java"
      ],
      "fix": "ADR-05 보강 1번을 'DB에서 PaymentEvent.status 재조회 + PG getStatus로 실 금액 재조회' 로 수정하거나, 4번을 1-3번보다 선행 단계로 순서 고정(ALREADY_PROCESSED_PAYMENT/2201 수신 시 PG 재조회 + 금액 일치 검증 → 실패 시 QUARANTINED)"
    },
    {
      "severity": "minor",
      "area": "dedupe-ttl-quantification",
      "summary": "ADR-16 보강 수락 기준 3번의 dedupe 레코드 보존 기간이 '가능한 중복 수신 윈도우 + 안전 마진'으로 서술적 — Kafka consumer group offset retention과의 정량 연동 기준 부재",
      "evidence": [
        "docs/topics/MSA-TRANSITION.md:439 (ADR-16 보강 수락 기준 3번)",
        "docs/topics/MSA-TRANSITION.md:393 (ADR-29 장애 주입)"
      ],
      "fix": "ADR-16 보강 3번을 'consumer group offset retention + 안전 마진(예: +1일) 이상으로 보존' 으로 정량화 또는 ADR-29 phase 4 수락 기준에 'dedupe TTL 미스매치에 의한 false positive 배제' 명시"
    },
    {
      "severity": "minor",
      "area": "phase-transition-compensation-owner",
      "summary": "§ 7 행 #9 · § 6 Phase 1-3 이행 구간에서 stock.restore 보상 경로의 소유자(결제 서비스 내부 동기 호출 유지 vs 모놀리스 상품 컨텍스트 이벤트 수신)가 phase별로 명시되지 않아 이중 복원 방어선 공백 가능",
      "evidence": [
        "docs/topics/MSA-TRANSITION.md:517-523 (§ 6 Phase 1)",
        "docs/topics/MSA-TRANSITION.md:525-528 (§ 6 Phase 2)",
        "docs/topics/MSA-TRANSITION.md:564 (§ 7 행 #9)",
        "docs/context/CONCERNS.md:51-56 (executePaymentFailureCompensation 이중 복원)"
      ],
      "fix": "§ 6 Phase 1 또는 Phase 2에 '보상 경로는 결제 서비스 내부 동기 호출 유지(이벤트화는 Phase 3과 동시)' 또는 'Phase 1과 동시에 모놀리스 상품 컨텍스트 dedupe 테이블 신설'을 phase gating 조항으로 추가. 또는 § 7 행 #9에 '이행 구간(Phase 1-2) 방어선' 별행 추가"
    },
    {
      "severity": "n/a",
      "area": "security-pii",
      "summary": "PII·보안 경로는 본 토픽 비목표",
      "evidence": ["docs/topics/MSA-TRANSITION.md:141 (§ 1-3)"],
      "fix": "해당 없음"
    }
  ],
  "counts": {
    "critical": 0,
    "major": 0,
    "minor": 3,
    "n/a": 1
  }
}
```
