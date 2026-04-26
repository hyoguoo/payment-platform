# plan-critic-4

**Topic**: MSA-TRANSITION
**Round**: 4
**Persona**: Critic
**Decision**: pass

## Reasoning

Round 3 pass 이후 메인 스레드 MSA 자기 점검에서 발견된 5건(C-1 critical / C-2 critical / M-3 major / M-4 major / M-5 major) + Architect R4 1건(adapter→adapter 위임 리스크)이 Round 4 재작성에 모두 반영됐다. C-1은 Phase-3.1b(656-672행) 신설로 user-service 모듈(build.gradle · User.java · UserRepository · UserQueryService inbound port · UserQueryUseCase 겸임 · UserController · Flyway V1 · 전용 MySQL 방침 · KafkaTopicConfig)이 확보됐고, C-2는 Phase-2.3b(588-608행) 신설로 `PgStatusHttpAdapter` / `PaymentGatewayKafkaCommandAdapter` / `PgEventConsumer` 3개 어댑터 + `LocalPgStatusAdapter` / `InternalPaymentGatewayAdapter` 퇴역 명시 + 테스트 5건이 확보됐다. M-3은 Phase-1.4(345행) 목적 문단에 "stock-- 는 외부 호출 분리 · 단일 TX 가정은 결제 서비스 DB 내부(payment_event + payment_outbox)에만 적용" 경계가 명문화되고 테스트가 `executePaymentConfirm_CommitsPaymentStateAndOutboxInSingleTransaction` / `executePaymentConfirm_WhenStockDecreaseFails_ShouldTransitionToQuarantineWithoutOutbox`로 재정의됐다. M-4는 Phase-0.1 산출물(211행) 마지막 블록에 "PG 무상태(DB 없음)" + "관리자 모놀리스 DB 읽기 전용 뷰" 방침이, Phase-5.1 목적(802행)에 "Admin은 모놀리스 DB 직접 SELECT 경로 폐기 + Gateway 경유 HTTP 위임" 한 문장이 박혔다. M-5는 Phase-2.3 목적(569행)에 `<source-service>.<type>.<action>` 규약 + ADR-12 결론란 기재 의무 + 산출물(581-584행) `PaymentTopics.java` / `PgTopics.java` / `ProductTopics.java` 3개 상수 중앙화 + `MSA-TRANSITION.md` ADR-12 갱신이 포함됐다. ARCH R4는 Phase-1.0(286-287행) `PaymentGatewayPort`를 "confirm/cancel 경로 전담"으로 재정의하고 `PgStatusPort`를 "getStatus 단일 경로 전담"으로 분리 + Phase-2.3b(604-605행) `PaymentGatewayKafkaCommandAdapter`에 "getStatus 메서드 비보유" + "adapter→adapter 위임 금지" 문구 + RESOLVED 주석으로 해소됐다. 의존 순서(port → domain → application → infrastructure → controller)도 신규 두 태스크 모두 정합, 추적 테이블(840-842행) 및 ADR 커버리지 테이블(870·879·880·881행)도 ADR-12/21/22/23 갱신을 반영했다. 반환 지표는 "태스크 총 개수 35"가 Phase 합산(4+14+6+6+3+2=35)과 일치하나, "domain_risk=true 15개" 집계는 실제 true=14개 + Phase-3.4(false 유지) 섞여 나열된 표기 불일치가 있다 — 판정 항목에 직접 걸리지 않는 minor. → **pass**.

## Round 3 대비 해소 현황

| R4 Finding | Severity | 해소 여부 | 반영 위치 |
|---|---|---|---|
| C-1 user-service 모듈 신설 태스크 누락 | critical | **resolved** | Phase-3.1b 신설 (PLAN.md:656-672), 추적 테이블 841행, ADR-22 커버리지 880행, ADR-23 커버리지 881행 |
| C-2 결제 서비스 측 PG 어댑터 교체 태스크 누락 | critical | **resolved** | Phase-2.3b 신설 (PLAN.md:588-608): `PgStatusHttpAdapter` / `PaymentGatewayKafkaCommandAdapter` / `PgEventConsumer` + Local/Internal 퇴역 명시 + 테스트 5건, 추적 테이블 840행, ADR-21 커버리지 879행 |
| M-3 Phase-1.4 stock-- + outbox 단일 TX 가정 모순 | major | **resolved** | Phase-1.4 목적(PLAN.md:345) stock-- 외부 호출 분리 경계 + 테스트 메서드 재정의(351-352행), 추적 테이블 836행 "Phase-1.4c 이후 stock-- TX 경계 외부 — QUARANTINED 전이로 방어(M-3 반영)" |
| M-4 PG/Admin docker-compose MySQL 방침 공백 | major | **resolved** | Phase-0.1 산출물(PLAN.md:211) DB 경계 방침 블록(PG 무상태 + 관리자 읽기 전용 뷰) + Phase-5.1 목적(802행) "관리자 데이터 접근 방침(M-4)" 한 문장 |
| M-5 Kafka 토픽 네이밍 규약 태스크 부재 | major | **resolved** | Phase-2.3 목적(PLAN.md:569) `<source-service>.<type>.<action>` 규약 + ADR-12 결론란 기재 + 산출물(581-584행) Topics 상수 3종 + MSA-TRANSITION.md 갱신, ADR-12 커버리지 870행 |
| ARCH R4 adapter→adapter 위임 리스크 | major | **resolved** | Phase-1.0(PLAN.md:286-287) `PaymentGatewayPort` confirm/cancel 전담 재정의 + `PgStatusPort` getStatus 전담 분리 + Phase-2.3b(604-605) getStatus 비보유 + adapter→adapter 금지 문구 + RESOLVED 주석 |

## Checklist judgement

### traceability
- [x] PLAN.md가 `docs/topics/MSA-TRANSITION.md` 결정 사항 참조 — **yes** (PLAN.md:3 링크 유지, ADR 커버리지 테이블 857-887행 29 ADR 전수, ADR-12/21/22/23 R4 갱신 반영)
- [x] 모든 태스크가 설계 결정에 매핑됨 — **yes** (Phase-3.1b → ADR-22/23, Phase-2.3b → ADR-21, 추적 테이블 840-842행에 신규 3건 매핑 추가, 851행 "orphan 없음" 유지)

### task quality
- [x] 객관적 완료 기준 — **yes** (Phase-3.1b 산출물 9종 모두 파일 경로 확정, Phase-2.3b 산출물 4종 + 퇴역 2종 명시, Phase-1.4 345행 stock-- 경계 명문화로 완료 기준 명확)
- [x] 태스크 크기 ≤ 2시간 — **yes** (신규 Phase-3.1b·Phase-2.3b 각 "크기: ≤ 2h" 명기, 기존 태스크 분할 구조 유지, 35개 태스크 전수 유지)
- [x] 관련 소스 파일/패턴 언급 — **yes** (Phase-3.1b: `user-service/src/main/java/.../user/domain/User.java` 등 9개 경로 명시, Phase-2.3b: `payment-service/src/main/java/.../payment/infrastructure/adapter/http/PgStatusHttpAdapter.java` 등 3개 어댑터 + 퇴역 2개 명시)

### TDD specification
- [x] tdd=true 태스크의 테스트 클래스+메서드 스펙 명시 — **yes** (Phase-2.3b 595-601행 `PgStatusHttpAdapterTest` 2건 + `PaymentGatewayKafkaCommandAdapterTest` 1건 + `PgEventConsumerTest` 2건 = 5건 구체, Phase-1.4 351-354행 재정의된 3건 + BEFORE_COMMIT 1건)
- [x] tdd=false 태스크의 산출물 명시 — **yes** (Phase-3.1b 9개 산출물 경로 확정, Phase-0.1 DB 경계 방침 블록 추가, Phase-5.1 Admin 데이터 접근 방침 추가)
- [x] TDD 분류 합리성 — **yes** (Phase-2.3b는 HTTP 어댑터·Kafka publisher·consumer 상태 전이로 domain_risk=true + tdd=true 타당, Phase-3.1b는 모듈 신설/스키마로 tdd=false 타당)

### dependency ordering
- [x] layer 의존 순서 준수 — **yes** (Phase-3.1b: build.gradle → User 도메인 → UserRepository port out → UserQueryService inbound port → UserQueryUseCase 겸임 application → UserController → Flyway V1 → MySQL → KafkaTopicConfig 순서 준수. Phase-2.3b: Phase-1.1 port 선언 + Phase-2.3 PG 서비스 publisher 선행 → infrastructure 어댑터 교체로 순서 정합)
- [x] Fake가 소비자 앞에 옴 — **yes** (Phase-1.2 FakePgStatusAdapter가 Phase-2.3b PgEventConsumer/PgStatusHttpAdapter 테스트에 활용 가능, Phase-3.1b는 inbound port 구현체만이라 Fake 소비 경로 n/a)
- [x] orphan port 없음 — **yes** (Phase-1.0 PaymentGatewayPort → Phase-1.0 InternalPaymentGatewayAdapter → Phase-2.3b PaymentGatewayKafkaCommandAdapter로 계승 경로 명시, PgStatusPort → LocalPgStatusAdapter → Phase-2.3b PgStatusHttpAdapter 교체, UserRepository → Phase-3.1b 내부 구현 또는 UserQueryUseCase가 직접 사용)

### architecture fit
- [x] ARCHITECTURE.md layer 규칙 충돌 없음 — **yes** (Phase-2.3b adapter→adapter 위임 금지를 ARCH R4 RESOLVED 주석(605행)으로 명문화, PaymentGatewayPort scope를 confirm/cancel 전담으로 재정의(286-287행)해 헥사고날 경계 복원. `@CircuitBreaker`는 adapter 내부 메서드에만 부여(591, 603행))
- [x] 모듈 간 호출이 port / InternalReceiver를 통함 — **yes** (Phase-3.1b UserController는 Gateway 경유 HTTP 접근만, Phase-3.4 UserHttpAdapter가 호출. Phase-2.3b PaymentGatewayKafkaCommandAdapter는 Kafka topic 경유(payment.commands.confirm), PG 서비스는 PgEventConsumer → application → PgGatewayPort 경로로 직접 import 차단)
- [x] CONVENTIONS.md Lombok/예외/로깅 — **n/a** (계획 수준 관례 승계)

### artifact
- [x] `docs/<TOPIC>-PLAN.md` 존재 — **yes** (docs/MSA-TRANSITION-PLAN.md 887행, 반환 지표 848행 "태스크 총 개수 35" 명기)

## Findings

(critical/major 없음)

**참고 minor** (판정 영향 없음):

- **severity**: minor
  **checklist_item**: task quality (집계 정확도)
  **location**: docs/MSA-TRANSITION-PLAN.md:849-850
  **problem**: 반환 지표 "domain_risk=true 태스크 개수: 15"로 선언했으나 실제 `domain_risk: true` 필드 수는 14개. 850행 리스트에 "Phase-3.4(false 유지)"가 섞여 나열되어 15개로 카운트됨.
  **evidence**: grep `domain_risk.*:.*true` 결과 14건(330/347/363/379/395/417/435/467/484/500/571/593/694/753). Phase-3.4(line 716)는 `domain_risk: false`로 주석에도 "(false 유지)" 명기됨.
  **suggestion**: 849행을 "domain_risk=true 태스크 개수: 14"로 수정 + 850행 리스트에서 `Phase-3.4(false 유지)` 항목 제거 또는 별도 각주 처리. Gate checklist 판정 항목 아님 — 다음 커밋 또는 execute 진입 전 보정 가능.

## previous_round_ref

`docs/rounds/msa-transition/plan-critic-3.md`

## Delta

- **newly_passed**:
  - "orphan port 없음" — Phase-2.3b에서 PaymentGatewayPort/PgStatusPort 두 포트 구현체 교체 경로(HTTP + Kafka) 확정되어 Phase 2 이후 구현 공백 해소
  - "모듈 간 호출이 port / InternalReceiver를 통함" — Phase-3.1b UserController Gateway 경유 HTTP 경계 + Phase-2.3b Kafka 토픽 경유 command 경계 추가로 강화
  - "모든 태스크가 설계 결정에 매핑됨" — ADR-21/22/23 갱신 + 추적 테이블 C-1/C-2/M-5 3행 추가로 신규 태스크 orphan 방지
- **newly_failed**: 없음
- **still_failing**: 없음

## JSON

```json
{
  "stage": "plan",
  "persona": "critic",
  "round": 4,
  "task_id": null,

  "decision": "pass",
  "reason_summary": "R3 pass 이후 메인 스레드 공백 5건(C-1 critical / C-2 critical / M-3/M-4/M-5 major) + ARCH R4 adapter→adapter 위임 리스크가 Round 4 재작성에 전부 반영됨. Phase-3.1b user-service 신설, Phase-2.3b 결제 서비스 측 HTTP/Kafka 어댑터 교체, Phase-1.4 TX 경계 재정의, Phase-0.1/5.1 DB·Admin 방침 추가, Phase-2.3 토픽 네이밍 규약 + Topics 상수 중앙화, Phase-1.0 PaymentGatewayPort confirm/cancel 전담 재정의가 모두 완료. Gate checklist 전 항목 yes 또는 n/a. domain_risk 집계 오차(15 vs 14) minor 1건 — 판정 영향 없음.",

  "checklist": {
    "source": "_shared/checklists/plan-ready.md",
    "items": [
      {
        "section": "traceability",
        "item": "PLAN.md가 topics/<TOPIC>.md 결정 사항 참조",
        "status": "yes",
        "evidence": "docs/MSA-TRANSITION-PLAN.md:3 링크, ADR 커버리지 테이블 857-887행 29 ADR 전수. R4에 ADR-12/21/22/23 행 갱신(870/879/880/881)"
      },
      {
        "section": "traceability",
        "item": "모든 태스크가 설계 결정에 매핑됨",
        "status": "yes",
        "evidence": "추적 테이블 840(ADR-21 Phase-2.3b C-2)·841(ADR-22 Phase-3.1b C-1)·842(ADR-12 Phase-2.3 M-5)행 신설, 851행 'orphan 없음' 유지"
      },
      {
        "section": "task quality",
        "item": "모든 태스크가 객관적 완료 기준을 가짐",
        "status": "yes",
        "evidence": "Phase-3.1b 656-672행 9개 산출물 파일 경로 확정, Phase-2.3b 588-608행 4개 산출물 + 2개 퇴역 명시, Phase-1.4 345행 stock-- 외부 호출 경계 명문화로 완료 기준 명확"
      },
      {
        "section": "task quality",
        "item": "태스크 크기 ≤ 2시간",
        "status": "yes",
        "evidence": "Phase-3.1b(662행)·Phase-2.3b(594행) 각 '크기: ≤ 2h' 명기, 35개 태스크 분할 구조 유지"
      },
      {
        "section": "task quality",
        "item": "각 태스크에 관련 소스 파일/패턴 언급됨",
        "status": "yes",
        "evidence": "Phase-3.1b `user-service/src/main/java/.../user/domain/User.java` 외 8개 경로, Phase-2.3b `payment-service/src/main/java/.../payment/infrastructure/adapter/http/PgStatusHttpAdapter.java` 외 2개 어댑터 + 퇴역 2개 경로 명시"
      },
      {
        "section": "TDD specification",
        "item": "tdd=true 태스크는 테스트 클래스+메서드 스펙 명시",
        "status": "yes",
        "evidence": "Phase-2.3b 595-601행 PgStatusHttpAdapterTest 2 + PaymentGatewayKafkaCommandAdapterTest 1 + PgEventConsumerTest 2 = 5건, Phase-1.4 351-354행 재정의된 PaymentTransactionCoordinatorTest 3건 + PaymentHistoryEventListenerTest 1건"
      },
      {
        "section": "TDD specification",
        "item": "tdd=false 태스크는 산출물(파일/위치) 명시",
        "status": "yes",
        "evidence": "Phase-3.1b 9개 산출물 경로 확정, Phase-0.1 211행 DB 경계 방침 블록, Phase-5.1 802행 Admin 데이터 접근 방침 추가"
      },
      {
        "section": "TDD specification",
        "item": "TDD 분류가 합리적",
        "status": "yes",
        "evidence": "Phase-2.3b HTTP 어댑터·Kafka publisher·consumer 상태 전이 → tdd=true + domain_risk=true 타당(593행), Phase-3.1b 모듈 신설/스키마 → tdd=false 타당(661-662행)"
      },
      {
        "section": "dependency ordering",
        "item": "layer 의존 순서 준수 (port → domain → application → infrastructure → controller)",
        "status": "yes",
        "evidence": "Phase-3.1b: build.gradle → User 도메인 → UserRepository out port → UserQueryService inbound port → UserQueryUseCase 겸임 → UserController → Flyway V1 → MySQL → KafkaTopicConfig 순서(664-672). Phase-2.3b: Phase-1.1 port + Phase-2.3 PG publisher 선행 → 결제 서비스 infrastructure 어댑터 교체"
      },
      {
        "section": "dependency ordering",
        "item": "Fake 구현이 소비자 태스크보다 먼저 옴",
        "status": "yes",
        "evidence": "Phase-1.2 FakePgStatusAdapter가 Phase-2.3b 테스트 선행 가능(Phase-2.3b가 tdd=true인 만큼 Fake 기반 테스트 가능), Phase-3.1b는 자기 완결형 모듈이라 Fake 소비 관계 n/a"
      },
      {
        "section": "dependency ordering",
        "item": "orphan port 없음 (port만 있고 구현/Fake 없는 경우)",
        "status": "yes",
        "evidence": "PaymentGatewayPort → Phase-1.0 InternalPaymentGatewayAdapter → Phase-2.3b PaymentGatewayKafkaCommandAdapter(604행) 계승. PgStatusPort → Phase-1.1 LocalPgStatusAdapter → Phase-2.3b PgStatusHttpAdapter(603행) 교체. UserRepository → Phase-3.1b 내부 UserQueryUseCase가 사용(668행)"
      },
      {
        "section": "architecture fit",
        "item": "ARCHITECTURE.md의 layer 규칙과 충돌 없음",
        "status": "yes",
        "evidence": "Phase-1.0 286-287행 PaymentGatewayPort confirm/cancel 전담 재정의 + PgStatusPort getStatus 전담 분리로 역할 중복 제거. Phase-2.3b 604-605행 PaymentGatewayKafkaCommandAdapter getStatus 비보유 + adapter→adapter 위임 금지 문구 + ARCH R4 RESOLVED 주석으로 헥사고날 경계 복원. `@CircuitBreaker`는 adapter 내부 메서드에만(591, 603행)"
      },
      {
        "section": "architecture fit",
        "item": "모듈 간 호출이 port / InternalReceiver를 통함",
        "status": "yes",
        "evidence": "Phase-3.1b UserController는 Gateway 경유 HTTP 접근만(669행), Phase-3.4 UserHttpAdapter가 `UserLookupPort` 경로로 호출. Phase-2.3b PaymentGatewayKafkaCommandAdapter는 payment.commands.confirm Kafka topic 경유 command 발행(599행), PG 서비스는 PgEventConsumer → application → PgGatewayPort 경로 준수"
      },
      {
        "section": "architecture fit",
        "item": "CONVENTIONS.md Lombok/예외/로깅 패턴 따르도록 계획됨",
        "status": "n/a",
        "evidence": "계획 수준 관례 승계 간주, execute 단계 코드 리뷰로 검증"
      },
      {
        "section": "artifact",
        "item": "docs/<TOPIC>-PLAN.md 존재",
        "status": "yes",
        "evidence": "docs/MSA-TRANSITION-PLAN.md 887행, 반환 지표 848행 '태스크 총 개수: 35' Phase 합산과 일치(4+14+6+6+3+2=35)"
      }
    ],
    "total": 15,
    "passed": 14,
    "failed": 0,
    "not_applicable": 1
  },

  "scores": {
    "traceability": 0.96,
    "decomposition": 0.93,
    "ordering": 0.94,
    "specificity": 0.94,
    "risk_coverage": 0.95,
    "mean": 0.944
  },

  "findings": [
    {
      "severity": "minor",
      "checklist_item": "task quality (반환 지표 집계 정확도 — Gate checklist 항목 아님, 참고)",
      "location": "docs/MSA-TRANSITION-PLAN.md:849-850",
      "problem": "반환 지표 'domain_risk=true 태스크 개수: 15'와 실제 `domain_risk: true` 필드 수(14) 불일치. 850행 리스트에 'Phase-3.4(false 유지)'가 섞여 나열되어 15로 카운트됨.",
      "evidence": "grep `domain_risk.*:.*true` 결과 14건(Phase-1.3/1.4/1.4b/1.4c/1.5/1.6/1.7/1.9/1.10/1.11/2.3/2.3b/3.3/4.1). Phase-3.4(line 716)는 `domain_risk: false`.",
      "suggestion": "849행을 'domain_risk=true 태스크 개수: 14'로 수정 + 850행 리스트에서 'Phase-3.4(false 유지)' 항목 제거 또는 별도 각주. 판정 영향 없음 — 다음 커밋 또는 execute 진입 전 보정."
    }
  ],

  "previous_round_ref": "plan-critic-3.md",
  "delta": {
    "newly_passed": [
      "orphan port 없음 (Phase-2.3b PaymentGatewayPort/PgStatusPort 구현체 교체 경로 확정)",
      "모듈 간 호출이 port / InternalReceiver를 통함 (Phase-3.1b Gateway HTTP + Phase-2.3b Kafka 경유 강화)",
      "모든 태스크가 설계 결정에 매핑됨 (ADR-21/22/12 갱신 + 추적 테이블 C-1/C-2/M-5 3행 추가)"
    ],
    "newly_failed": [],
    "still_failing": []
  },

  "unstuck_suggestion": null
}
```
