# plan-critic-1

**Topic**: MSA-TRANSITION
**Round**: 1
**Persona**: Critic
**Decision**: revise

## Reasoning

태스크 총 26개가 29개 ADR 전부에 매핑되고 tdd=true 태스크의 테스트 스펙도 구체적이어서 traceability / task quality / TDD specification 대부분은 통과한다. 그러나 **architecture fit**과 **dependency ordering** 섹션에서 구조적 공백이 다수 관측된다: AOP 축 복제 이관 부재(Phase-1.4, ADR-13 핵심 불변 — 감사 원자성), 모듈 경계 끊기 태스크 부재(Phase-1.3 이후 cross-module import 잔류), orphan port 2개(`MessageConsumerPort`·`ReconciliationPort`), Metric 클래스 배치 경로가 ARCHITECTURE.md:221 관례와 충돌(Phase-1.11·5.1), Phase-2.1/3.1의 도메인·벤더 전략·런타임 스택 산출물 누락, Phase-2.3 `PgEventPublisher` 포트 부재, Phase-3.3 `StockRestoreConsumer` → application usecase 공백 등. critical은 없으나 major가 다수로 **revise** 판정.

## Checklist judgement

### traceability
- [x] PLAN.md가 `docs/topics/MSA-TRANSITION.md` 결정 사항 참조 — yes (PLAN.md:3 링크, 각 태스크 ADR-XX 참조, ADR→태스크 커버리지 테이블 650-680행)
- [x] 모든 태스크가 설계 결정 매핑 — yes (PLAN.md:646 "orphan 없음" 명시, 커버리지 테이블에서 29개 ADR 전수 매핑 확인)

### task quality
- [~] 객관적 완료 기준 — partial (대부분 yes, Phase-0.1 `placeholder`·Phase-0.3 "공통 모듈 이동 계획 수립"은 완료 판정 기준 모호 — minor)
- [~] 태스크 크기 ≤ 2시간 — partial (Phase-1.4가 TransactionCoordinator+PaymentHistoryEventListener+AOP축 이관까지 암묵적으로 포함 시 2h 초과 가능 — minor)
- [x] 관련 소스 파일/패턴 언급 — yes

### TDD specification
- [x] tdd=true 태스크의 테스트 클래스+메서드 스펙 명시 — yes (Phase-1.3~1.11, 2.3, 3.3, 3.4 전부 `@ParameterizedTest @EnumSource` 포함 메서드명 구체)
- [~] tdd=false 태스크의 산출물 명시 — partial (Phase-0.3 산출물 느슨, Phase-0.1 `KafkaTopicConfig` 배치 위치 미결 — minor)
- [x] TDD 분류 합리성 — yes (도메인·상태 머신·dedupe 모두 tdd=true)

### dependency ordering
- [~] layer 의존 순서 준수 — partial (Phase 내부 port→domain→application→infrastructure 대체로 지켜지나, Phase-1.3 직전에 모듈 경계 단절 태스크 없음 — major, F-03)
- [x] Fake가 소비자 앞에 옴 — yes (Phase-1.2 Fake → Phase-1.5~1.7 소비자, Phase-3.2 → 3.3)
- [ ] orphan port 없음 — **no** (`MessageConsumerPort`·`ReconciliationPort` 구현/Fake/사용처 공백 — major, F-02)

### architecture fit
- [ ] `docs/context/ARCHITECTURE.md` layer 규칙과 충돌 없음 — **no** (Metric 배치 불일치, Publisher 포트 부재, Consumer → usecase 누락 — major, F-04/F-08/F-09)
- [ ] 모듈 간 호출이 port / InternalReceiver 통함 — **no** (Phase-1.3 이후 결제 서비스가 모놀리스 paymentgateway/product/user 패키지 import 지속 경로 잔존 — major, F-03)
- [~] CONVENTIONS.md Lombok/예외/로깅 패턴 따르도록 계획됨 — partial (명시적 언급 없음, 관례 승계로 암묵 — minor)

### artifact
- [x] `docs/<TOPIC>-PLAN.md` 존재 — yes (680행 분량 존재)

## Findings

- **F-01 (major)** — Phase-1.4 AOP 축 복제 이관 태스크 누락
- **F-02 (major)** — orphan port 2개 (`MessageConsumerPort`, `ReconciliationPort`)
- **F-03 (major)** — 모듈 경계 단절 태스크 부재 (Phase-1.3 이후 cross-module import 잔류)
- **F-04 (major)** — Metric 클래스 배치가 ARCHITECTURE.md 관례와 충돌 (Phase-1.11, Phase-5.1)
- **F-05 (major)** — Phase-1.4 Flyway V1 스키마 산출물 누락 (ADR-13 `payment_history` 결제 서비스 DB 잔류의 실체화 부재)
- **F-06 (major)** — Phase-2.1 Toss/NicePay 벤더 전략 어댑터 이관 산출물 누락
- **F-07 (major)** — Phase-2.1 `@TossApiMetric` AOP 복제 배치 누락 (§ 2-6 AOP 복제 원칙 위반)
- **F-08 (major)** — Phase-2.3 `PgEventPublisher` 대응 application port 산출물 없음
- **F-09 (major)** — Phase-3.3 `StockRestoreConsumer`가 호출할 application usecase 부재 + `DedupeRepository` port/구현체 경계 모호
- **F-10 (major)** — Phase-3.1 상품 도메인 엔티티 이관 산출물 누락 (Phase-1.3 대칭 공백)
- **F-11 (major)** — Phase-3.1 `build.gradle`에 MVC + Virtual Threads 런타임 스택 명시 누락 (§ 2-8 위반)
- **F-12 (minor)** — Phase-0.3 산출물 "이동 계획 수립"으로 느슨, `KafkaTopicConfig` 배치 위치 미결
- **F-13 (minor)** — Phase-1.4 범위가 TransactionCoordinator + EventListener + (암묵) AOP 이관까지 넓어 2h 초과 리스크
- **F-14 (minor)** — 포트 경로 일관성: 기존 포트는 `application/port/` 플랫, 신규 포트는 `application/port/out/` 서브디렉토리 혼재 (§ 2-6 위반 여지)

## JSON

```json
{
  "stage": "plan",
  "persona": "critic",
  "round": 1,
  "task_id": null,

  "decision": "revise",
  "reason_summary": "critical 없음, major 11건(F-01~F-11) + minor 3건(F-12~F-14). architecture fit·dependency ordering 섹션에서 구조적 공백 다수.",

  "checklist": {
    "source": "_shared/checklists/plan-ready.md",
    "items": [
      {
        "section": "traceability",
        "item": "PLAN.md가 topics/<TOPIC>.md 결정 사항 참조",
        "status": "yes",
        "evidence": "docs/MSA-TRANSITION-PLAN.md:3 링크, ADR→태스크 커버리지 테이블(650-680행)"
      },
      {
        "section": "traceability",
        "item": "모든 태스크가 설계 결정 중 하나 이상에 매핑됨",
        "status": "yes",
        "evidence": "docs/MSA-TRANSITION-PLAN.md:646 'orphan 없음' + 커버리지 테이블 29개 ADR 전수 매핑"
      },
      {
        "section": "task quality",
        "item": "모든 태스크가 객관적 완료 기준을 가짐",
        "status": "no",
        "evidence": "docs/MSA-TRANSITION-PLAN.md:58 'placeholder', 89행 '공통 모듈 이동 계획 수립' 등 완료 판정 기준 모호"
      },
      {
        "section": "task quality",
        "item": "태스크 크기 ≤ 2시간 (한 커밋 단위 분해)",
        "status": "no",
        "evidence": "Phase-1.4(PLAN.md:186-207)는 TransactionCoordinator+EventListener 이관 + 암묵 AOP 축 + Flyway까지 범위 확대 시 2h 초과 리스크"
      },
      {
        "section": "task quality",
        "item": "각 태스크에 관련 소스 파일/패턴 언급됨",
        "status": "yes",
        "evidence": "모든 태스크에 '산출물' 섹션으로 파일 경로 명시"
      },
      {
        "section": "TDD specification",
        "item": "tdd=true 태스크는 테스트 클래스+메서드 스펙 명시",
        "status": "yes",
        "evidence": "Phase-1.3~1.11, 2.3, 3.3, 3.4 모두 테스트 클래스+메서드 구체 명시"
      },
      {
        "section": "TDD specification",
        "item": "tdd=false 태스크는 산출물(파일/위치) 명시",
        "status": "no",
        "evidence": "Phase-0.3(PLAN.md:89) '공통 모듈 이동 계획 수립'만 제시되어 산출물 위치 미확정"
      },
      {
        "section": "TDD specification",
        "item": "TDD 분류가 합리적",
        "status": "yes",
        "evidence": "도메인 엔티티/상태 머신/dedupe/FCG/가면 방어선 모두 tdd=true"
      },
      {
        "section": "dependency ordering",
        "item": "layer 의존 순서 준수 (port → domain → application → infrastructure → controller)",
        "status": "no",
        "evidence": "Phase-1.3에서 도메인 이관 시 모놀리스 cross-module import 끊기 태스크 부재 (PLAN.md:184 ARCH 주석)"
      },
      {
        "section": "dependency ordering",
        "item": "Fake 구현이 소비자 태스크보다 먼저 옴",
        "status": "yes",
        "evidence": "Phase-1.2 Fake → Phase-1.5~1.7 소비자, Phase-3.2 → Phase-3.3"
      },
      {
        "section": "dependency ordering",
        "item": "orphan port 없음 (port만 있고 구현/Fake 없는 경우)",
        "status": "no",
        "evidence": "Phase-1.1(PLAN.md:139-142)의 `MessageConsumerPort`, `ReconciliationPort`가 실 구현/Fake/사용 태스크 부재 (ARCH 주석 148, 302행)"
      },
      {
        "section": "architecture fit",
        "item": "ARCHITECTURE.md의 layer 규칙과 충돌 없음",
        "status": "no",
        "evidence": "Phase-1.11 `OutboxPendingAgeMetrics`·Phase-5.1 `PaymentStateMetrics`를 `application/usecase`에 배치(PLAN.md:332, 599) vs ARCHITECTURE.md:221 Metrics=`core/common/metrics/` 관례"
      },
      {
        "section": "architecture fit",
        "item": "모듈 간 호출이 port / InternalReceiver를 통함",
        "status": "no",
        "evidence": "Phase-1.3 이후 결제 서비스가 모놀리스 paymentgateway/product/user 패키지 import 지속 경로 해소 태스크 부재 (PLAN.md:184 ARCH 주석)"
      },
      {
        "section": "architecture fit",
        "item": "CONVENTIONS.md Lombok/예외/로깅 패턴 따르도록 계획됨",
        "status": "n/a",
        "evidence": "계획 문서 수준에서 명시적 준수 언급은 없으나 관례 승계로 간주"
      },
      {
        "section": "artifact",
        "item": "docs/<TOPIC>-PLAN.md 존재",
        "status": "yes",
        "evidence": "docs/MSA-TRANSITION-PLAN.md 680행 존재"
      }
    ],
    "total": 15,
    "passed": 8,
    "failed": 6,
    "not_applicable": 1
  },

  "scores": {
    "traceability": 0.92,
    "decomposition": 0.70,
    "ordering": 0.62,
    "specificity": 0.68,
    "risk_coverage": 0.80,
    "mean": 0.744
  },

  "findings": [
    {
      "severity": "major",
      "checklist_item": "ARCHITECTURE.md의 layer 규칙과 충돌 없음",
      "location": "docs/MSA-TRANSITION-PLAN.md#phase-1-4 (186-207행)",
      "problem": "Phase-1.4가 ADR-13 감사 원자성의 핵심 축인 `@PublishDomainEvent`·`@PaymentStatusChange` 어노테이션과 `DomainEventLoggingAspect`·`PaymentStatusMetricsAspect`의 결제 서비스 복제 이관을 산출물에 포함하지 않는다. 이행 후 결제 서비스의 `PaymentCommandUseCase` 어노테이션이 aspect 없이 no-op 상태가 되어 `PaymentHistoryEventListener(BEFORE_COMMIT)` 경로가 트리거되지 않을 수 있다.",
      "evidence": "PLAN.md:186-207 Phase-1.4 산출물에 aspect 클래스·어노테이션 이관 항목 없음. topics/MSA-TRANSITION.md § 2-6 'AOP 축 복제 원칙', § 4-10 ADR-13 (a/a') 권고 참조.",
      "suggestion": "Phase-1.4 산출물에 `payment-service/.../infrastructure/aspect/DomainEventLoggingAspect.java`, `PaymentStatusMetricsAspect.java` 및 `@PublishDomainEvent`·`@PaymentStatusChange` 어노테이션 이관을 추가하거나 Phase-1.4b 태스크로 분리."
    },
    {
      "severity": "major",
      "checklist_item": "orphan port 없음",
      "location": "docs/MSA-TRANSITION-PLAN.md#phase-1-1 (139-142행)",
      "problem": "Phase-1.1에서 선언된 `MessageConsumerPort`(outbound)·`ReconciliationPort`가 실 구현체·Fake·소비자 태스크 어디에서도 참조되지 않는 orphan. `MessageConsumerPort`는 방향(out)이 Spring Kafka `@KafkaListener`가 호출하는 일반 흐름과도 배치되어 inbound여야 할 여지.",
      "evidence": "PLAN.md:139-142 선언 이후 Phase-1.2 Fake 목록(161-163)에 `FakeMessageConsumerAdapter` 부재, Phase-1.9 `PaymentReconciler`(300행)도 `ReconciliationPort`를 구현·주입하지 않음(ARCH 주석 148, 302행 지적).",
      "suggestion": "두 포트의 실 호출 경로 재검토 — 불필요하면 Phase-1.1 산출물에서 제거, 필요하면 구현/Fake 태스크 추가 + `MessageConsumerPort`는 inbound로 재분류."
    },
    {
      "severity": "major",
      "checklist_item": "모듈 간 호출이 port / InternalReceiver를 통함",
      "location": "docs/MSA-TRANSITION-PLAN.md#phase-1-3 (167-183행)",
      "problem": "Phase-1.3 도메인 이관 시 결제 서비스가 모놀리스 `paymentgateway/presentation/port/*`, `product/presentation/port/*`, `user/presentation/port/*`를 계속 import하는 상태가 해소되지 않는다. gradle 모듈 의존 그래프가 `모놀리스 → payment-service ↔ payment-service → 모놀리스` 양방향이 되어 Phase 3까지 circular 유지.",
      "evidence": "PLAN.md:184 ARCH 주석에서 명시. Phase-1.1 산출물(139-142)에 cross-context inbound port 복제본(예: `payment-service/application/port/out/ProductLookupPort`) 정의 없음.",
      "suggestion": "Phase-1.1 또는 Phase-1.3 직전에 '결제 서비스용 cross-context port 복제 + `InternalXxxAdapter` 승계' 경계 정리 태스크 신설."
    },
    {
      "severity": "major",
      "checklist_item": "ARCHITECTURE.md의 layer 규칙과 충돌 없음",
      "location": "docs/MSA-TRANSITION-PLAN.md:332,599",
      "problem": "`OutboxPendingAgeMetrics`(Phase-1.11)와 `PaymentStateMetrics`(Phase-5.1)를 `payment-service/.../application/usecase/`에 배치. 현 프로젝트 관례(ARCHITECTURE.md:221)는 메트릭 클래스를 `core/common/metrics/`에 두며, `application/usecase`는 도메인 orchestration 계층이라 Micrometer `MeterRegistry` 직접 의존이 부적절.",
      "evidence": "PLAN.md:332 `application/usecase/OutboxPendingAgeMetrics.java`, 599행 `application/usecase/PaymentStateMetrics.java`. ARCHITECTURE.md:221 'Metrics: ... in core/common/metrics/'.",
      "suggestion": "`payment-service/.../infrastructure/metrics/` 또는 서비스 내부 `core/common/metrics/` 경로로 재배치하고 5종 메트릭 모두 일관된 위치로 정리."
    },
    {
      "severity": "major",
      "checklist_item": "모든 태스크가 설계 결정 중 하나 이상에 매핑됨",
      "location": "docs/MSA-TRANSITION-PLAN.md#phase-1-4",
      "problem": "ADR-13 수락 기준인 'payment_history 결제 서비스 DB 잔류'의 실체화를 위한 Flyway V1 스키마 산출물(`payment_event`, `payment_order`, `payment_outbox`, `payment_history` 포함)이 Phase-1.x 어디에도 없다. Phase-3.1에서는 product 서비스 Flyway V1 명시됨(PLAN.md:441) — 비대칭.",
      "evidence": "PLAN.md:186-207 Phase-1.4 산출물에 Flyway 파일 없음. ADR-13 § 4-10 대안 (a) 권고 + topics/MSA-TRANSITION.md § 4-10 ADR-13.",
      "suggestion": "Phase-1.4 산출물에 `payment-service/src/main/resources/db/migration/V1__payment_schema.sql` 추가하거나 별도 태스크 Phase-1.4b 신설."
    },
    {
      "severity": "major",
      "checklist_item": "각 태스크에 관련 소스 파일/패턴이 언급됨",
      "location": "docs/MSA-TRANSITION-PLAN.md#phase-2-1 (347-358행)",
      "problem": "Phase-2.1 PG 서비스 신설에서 기존 모놀리스 `paymentgateway/infrastructure/gateway/toss/TossPaymentGatewayStrategy`·`nicepay/NicepayPaymentGatewayStrategy` 이관이 산출물에 없다. ADR-21 대안 a(물리 분리) 선택에서 이 벤더 전략 구현이 빠지면 PG 서비스가 껍데기만 생성됨.",
      "evidence": "PLAN.md:354-358 산출물 목록에 `TossPaymentGatewayStrategy`·`NicepayPaymentGatewayStrategy` 이관 부재. ARCHITECTURE.md:63 현 전략 패턴 명시.",
      "suggestion": "Phase-2.1 산출물에 `pg-service/.../infrastructure/gateway/{toss,nicepay}/*Strategy.java` 이관 추가."
    },
    {
      "severity": "major",
      "checklist_item": "ARCHITECTURE.md의 layer 규칙과 충돌 없음",
      "location": "docs/MSA-TRANSITION-PLAN.md#phase-2-1",
      "problem": "`@TossApiMetric` AOP의 PG 서비스 복제 배치가 산출물에 없음. § 2-6 AOP 복제 원칙 위반이며 PG 서비스가 Toss API 호출 메트릭을 기록하지 못하는 공백 발생.",
      "evidence": "PLAN.md:354-358 산출물에 `TossApiMetricsAspect.java` 이관/복제 부재. topics/MSA-TRANSITION.md § 2-6 AOP 복제 원칙.",
      "suggestion": "Phase-2.1 산출물에 `pg-service/.../infrastructure/aspect/TossApiMetricsAspect.java`(+ NicePay 대응) 및 `@TossApiMetric` 어노테이션 복제 추가, 또는 별도 Phase-2.x 태스크."
    },
    {
      "severity": "major",
      "checklist_item": "ARCHITECTURE.md의 layer 규칙과 충돌 없음",
      "location": "docs/MSA-TRANSITION-PLAN.md#phase-2-3 (388-399행)",
      "problem": "Phase-2.3 산출물 `PgEventPublisher`(infrastructure) 대응 application outbound port가 선언되지 않음. 레이어 원칙상 infra는 application/port 인터페이스를 구현해야 하나 포트 없이 떠 있어 application 서비스가 infra를 직접 import할 경로가 생긴다.",
      "evidence": "PLAN.md:399 `pg-service/.../infrastructure/messaging/PgEventPublisher.java` 선언, 같은 Phase 산출물 내 `PgEventPublisherPort`(또는 `MessagePublisherPort` PG 복제) 부재.",
      "suggestion": "Phase-2.3 또는 Phase-2.1 산출물에 `pg-service/.../application/port/out/PgEventPublisherPort.java` 추가 + `PgEventPublisher`가 구현하도록 명시."
    },
    {
      "severity": "major",
      "checklist_item": "ARCHITECTURE.md의 layer 규칙과 충돌 없음",
      "location": "docs/MSA-TRANSITION-PLAN.md#phase-3-3 (466-488행)",
      "problem": "Phase-3.3 `StockRestoreConsumer`(infrastructure)가 호출할 application usecase 계층이 산출물에 없고, `DedupeRepository.java`가 infrastructure 하위에 있어 port/구현체 구분이 모호. consumer가 `StockRepository`·`DedupeRepository`를 직접 inject 받아 '중복 체크 + 재고 증가'를 자기 안에 구현하면 application 책임이 infrastructure로 샌다.",
      "evidence": "PLAN.md:480-482 산출물에 application usecase 부재, `DedupeRepository.java`가 `infrastructure/idempotency/`에만 존재 (port 선언 없음).",
      "suggestion": "`product-service/.../application/usecase/StockRestoreUseCase.java` + `application/port/out/EventDedupeStore.java`(port) + `infrastructure/idempotency/JdbcEventDedupeStore.java`(구현체) 3분할로 재배치."
    },
    {
      "severity": "major",
      "checklist_item": "ARCHITECTURE.md의 layer 규칙과 충돌 없음",
      "location": "docs/MSA-TRANSITION-PLAN.md#phase-3-1 (431-443행)",
      "problem": "Phase-3.1에 상품 도메인 엔티티(Product/Stock aggregate) 이관 산출물이 없음. Phase-1.3(결제 도메인 이관) 대비 대칭 공백. Spring Data repository 인터페이스(`StockRepository`)만 다뤄 레이어 피라미드의 바닥이 비어 있다.",
      "evidence": "PLAN.md:438-443 산출물에 `product-service/.../product/domain/*` 엔티티 이관 부재.",
      "suggestion": "Phase-3.1 산출물에 도메인 엔티티 이관(`Product`, `Stock` 등) + 상품 서비스 application usecase 선언 추가."
    },
    {
      "severity": "major",
      "checklist_item": "ARCHITECTURE.md의 layer 규칙과 충돌 없음",
      "location": "docs/MSA-TRANSITION-PLAN.md#phase-3-1 (438-443행)",
      "problem": "Phase-3.1 `product-service/build.gradle` 산출물에 런타임 스택 명시 없음. Phase-0.2·1.1·2.1은 MVC+VT+spring-kafka 명시하나 Phase-3.1만 비일관. § 2-8 '내부 서비스는 MVC + Virtual Threads' 원칙이 강제되지 않으면 Phase-3.3 Kafka consumer가 어떤 런타임에 돌지 공백.",
      "evidence": "PLAN.md:438-443 산출물에 `spring-boot-starter-web`, virtual threads, `spring-kafka` 명시 없음.",
      "suggestion": "Phase-3.1 산출물에 `product-service/build.gradle`의 의존성·VT 설정을 Phase-1.1 수준으로 명시."
    },
    {
      "severity": "minor",
      "checklist_item": "tdd=false 태스크는 산출물(파일/위치) 명시",
      "location": "docs/MSA-TRANSITION-PLAN.md#phase-0-3 (84-93행)",
      "problem": "Phase-0.3 `core/common/log/LogFmt.java` 산출물이 '기존 파일 위치 확인 후 공통 모듈 이동 계획 수립'으로 완료 판정 기준이 모호. 공통 jar 추출인지 복제인지 방침 미결 시 후속 Phase 1/2/3가 동시에 두 방향으로 갈라질 수 있다.",
      "evidence": "PLAN.md:90 '기존 파일 위치 확인 후 공통 모듈 이동 계획 수립'. ADR-19 대안 (a) 공통 jar vs (b) 복제 중 결정 없음.",
      "suggestion": "이 태스크에 '공통 jar 추출(a) vs 복제(b) 결정 + 결정을 topic.md에 기록' 산출물 추가."
    },
    {
      "severity": "minor",
      "checklist_item": "태스크 크기 ≤ 2시간",
      "location": "docs/MSA-TRANSITION-PLAN.md#phase-1-4 (186-207행)",
      "problem": "Phase-1.4 범위가 TransactionCoordinator + PaymentHistoryEventListener 이관 + (ARCH 권고대로 추가 시) AOP 축 + Flyway V1까지 확대되면 2h 초과 리스크. F-01·F-05 대응 시 반드시 분할 필요.",
      "evidence": "PLAN.md:186-207 산출물 범위 + ARCH 주석 199-205 추가 요청.",
      "suggestion": "Phase-1.4를 (a) TransactionCoordinator/EventListener 이관 (b) AOP 축 이관 (c) Flyway V1 스키마 3개 태스크로 분할 검토."
    },
    {
      "severity": "minor",
      "checklist_item": "layer 의존 순서 준수",
      "location": "docs/MSA-TRANSITION-PLAN.md#phase-1-1 (142행)",
      "problem": "기존 포트(예: `IdempotencyStore`)가 모놀리스에선 `application/port/` 플랫 구조인데 신규 포트만 `application/port/out/` 서브디렉토리로 선언. § 2-6 '모든 신규 포트 {in,out} 하위' 원칙과 혼재 레이아웃.",
      "evidence": "PLAN.md:138-142 신규 포트 `application/port/out/`, 승계 포트는 기존 `application/port/` 플랫 유지.",
      "suggestion": "Phase-1.1에 '기존 포트 전수 `out/`으로 이동 + inbound는 `presentation/port` 관례 유지' 명시."
    }
  ],

  "previous_round_ref": null,
  "delta": null,

  "unstuck_suggestion": null
}
```
