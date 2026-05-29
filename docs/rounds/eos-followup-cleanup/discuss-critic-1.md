# discuss-critic-1

**Topic**: EOS-FOLLOWUP-CLEANUP
**Round**: 1
**Persona**: Critic

## Reasoning

Gate checklist 5개 섹션(scope / design decisions / acceptance criteria / verification plan / artifact) 전 항목이 yes로 충족된다. 산출물이 주장하는 코드/스키마 사실 — `isCompensatableByFailureHandler` 호출처 2곳(`PaymentConfirmResultUseCase.java:94`, `PaymentTransactionCoordinator.java:141`), `setTransactionManager`(`KafkaConsumerConfig.java:62`), PaymentEventStatus 9개 상태, pg_inbox 상태값/인덱스/`updated_at`, payment·product dedupe의 `expires_at`+`idx_expires_at`, 포트/어댑터 위치, Flyway V4 비충돌 — 을 전부 실제 코드와 대조해 일치 확인했다. §9의 미해결 적신호 5개는 모두 "plan/구현 단계에서 확정" 성격이며 설계 완결성에 구멍을 내는 blocker가 아니다(분류 근거 아래). 사용자 확정 결정(D-TM-QUALIFIER=handle만, D-PGINBOX-CLEANUP=상태+시간, 검증=단위+Testcontainers) 위반 없음. critical/major finding 없음 → pass.

## Checklist judgement

### scope
- TOPIC UPPER-KEBAB-CASE: **yes** (line 1 `# EOS-FOLLOWUP-CLEANUP`)
- 모듈/패키지 경계 명시: **yes** (§2-3, payment/product/pg 패키지까지 열거)
- non-goals ≥1: **yes** (§2-2 7개 항목)
- 범위 밖 이슈 위임/포함: **yes** (§2-2가 TC-13-FOLLOW-1/-3/-4, TC-7, STOCK-COMPENSATION-OTHER-PATHS를 별도 토픽/non-goal로 명시 위임)

### design decisions
- hexagonal layer 배치: **yes** (D-CLEAN-1 포트=application/port/out, D-CLEAN-2 워커=infrastructure/scheduler, D-TRACE-2 OTel 추출=infrastructure, D-SPLIT-1 상태판별=domain)
- 포트 인터페이스 위치 결정: **yes** (D-CLEAN-1 — 기존 application/port/out 포트에 메서드 추가, 신규 포트 미생성. 실제 위치와 일치 검증)
- 새 상태 추가 시 전이 다이어그램: **n/a→yes** (§4 — 새 비즈니스 상태 없음 명시. 단 pg_inbox 생명주기+청소/추적 시점 stateDiagram 제공)
- 전체 결제 흐름 호환성 검토: **yes** (§5 — FOLLOW-6/5 동작보존, cleanup 시간분리, traceparent 관측성전용 컬럼 비참여)

### acceptance criteria
- 성공 조건 관찰가능: **yes** (§7 — grep 0건/compile 경고 0/통합 테스트 만료행만 삭제/trace-id 연속성 등 구체)
- 실패 관찰 수단: **yes** (§7 실패 관찰 수단 — ERROR/WARN 로그 + Micrometer 카운터 신규/기존 명시)

### verification plan
- 테스트 계층 결정: **yes** (§8 표 — 작업군별 단위/Testcontainers/k6 명시, 사용자 확정 "단위+Testcontainers"와 정합)
- 벤치마크 지표: **n/a** (§8 — 전 작업군 k6 불요, 측정 의존 non-goal. 정당)

### artifact
- 결정 사항 섹션 존재: **yes** (§3 "결정 사항" — D-TM-1~4, D-SPLIT-1~2, D-CLEAN-1~4, D-PGINBOX-1~2, D-TRACE-1~3)

### domain risk (Domain Expert 전담 — 메모만)
- 명백한 공백 없음. §6에 멱등성/장애시나리오 4개(L-1~L-4)/재시도/PII 모두 기술. 최종 판정은 Domain Expert.

## Findings

minor 2건 + n/a 처리 항목만 존재. critical/major 없음.

- **C-MIN-1** (minor / artifact 부수): §2-3 product 경계가 Flyway 미언급인데, 실제 product는 `db/schema` 디렉토리를 쓰고 dedupe 인덱스가 이미 존재해 마이그레이션 불요(interview-0 §outputs와 정합). 직접 모순 아님 — plan에서 product가 마이그레이션 대상 아님을 한 줄 못박으면 충분.
- **C-MIN-2** (minor / design decisions 명확성): pg-service에 dedupe와 무관한 동명 `EventDedupeStore`(Redis adapter) 포트가 별도 존재한다. D-CLEAN-1 표의 product `EventDedupeStore`와 이름이 겹쳐 plan 디스패치 시 혼동 소지. plan에서 FQCN 또는 서비스 prefix로 구분 명시 권장.
- 표 linter 경고 5건: 내용 판정과 무관 — 경미 메모.

## §9 적신호 5개 blocker 여부 분류

전부 **plan 이월 가능**, discuss 종료 blocker 아님:
1. `setKafkaAwareTransactionManager` 버전 존재 — Spring Boot 3.4.4 → Spring Kafka 3.3.x. 해당 API는 3.2+ 존재, `KafkaTransactionManager`가 `KafkaAwareTransactionManager` 구현. plan 전 1줄 확인이면 족함. 부재 risk 사실상 0.
2. pg_inbox 8일 보존 적정성 — Domain Expert 정량 판정 대상. 8일 정렬 근거(Kafka retention 7일+버퍼 1일, dedupe TTL과 일치)가 §3-C D-PGINBOX-2에 제시됨. 설계 공백 아님.
3. 회수 추적 parent vs span link — Domain Expert 관측성 정확성 판정. 두 옵션 모두 회수 동작 자체엔 무영향(§5 관측성 전용), best-effort 폴백 보장. blocker 아님.
4. traceparent 추출 소스(Context.current vs @Header) — D-TRACE-2가 plan 택일로 명시 위임. layer 배치(infrastructure)는 이미 확정.
5. 메서드 명명 — 가칭 + 정답표 확정됨. 이름만 plan/구현 확정. 의미 분리 결정(D-SPLIT-1)은 완결.

## JSON
```json
{
  "stage": "discuss",
  "persona": "critic",
  "round": 1,
  "task_id": null,

  "decision": "pass",
  "reason_summary": "Gate checklist 5개 섹션 전 항목 yes. 산출물의 코드/스키마 사실 주장을 실제 코드와 대조해 전부 일치 확인. §9 적신호 5개는 모두 plan 이월 가능한 항목으로 discuss 종료 blocker 아님. 사용자 확정 결정 위반 없음. critical/major finding 없음.",

  "checklist": {
    "source": "_shared/checklists/discuss-ready.md",
    "items": [
      { "section": "scope", "item": "TOPIC이 UPPER-KEBAB-CASE로 확정됨", "status": "yes", "evidence": "docs/topics/EOS-FOLLOWUP-CLEANUP.md line 1" },
      { "section": "scope", "item": "건드리는 모듈/패키지 경계 명시", "status": "yes", "evidence": "§2-3 — payment/product/pg 패키지 경로까지 열거, user/gateway/eureka 무관 명시" },
      { "section": "scope", "item": "non-goals 최소 1개", "status": "yes", "evidence": "§2-2 — 7개 non-goal (TC-13-FOLLOW-*, TC-7, ShedLock, 1PC 재설계, expires_at 신설, 즉시처리 추적변경)" },
      { "section": "scope", "item": "범위 밖 이슈 위임/포함", "status": "yes", "evidence": "§2-2 — STOCK-COMPENSATION-OTHER-PATHS/TQ-7 별도 토픽 위임, 측정 의존 항목 non-goal 위임" },
      { "section": "design decisions", "item": "hexagonal layer 배치 명시", "status": "yes", "evidence": "D-CLEAN-1(application/port/out), D-CLEAN-2(infrastructure/scheduler), D-TRACE-2(OTel 추출=infrastructure), D-SPLIT-1(상태판별=domain)" },
      { "section": "design decisions", "item": "포트 인터페이스 위치 결정", "status": "yes", "evidence": "D-CLEAN-1 표 — 기존 포트(PaymentEventDedupeStore/EventDedupeStore/PgInboxRepository)에 메서드 추가. 실제 application/port/out 위치와 일치 검증" },
      { "section": "design decisions", "item": "새 상태 추가 시 전이 다이어그램", "status": "n/a", "evidence": "§4 — 새 비즈니스 상태 없음 명시 (PaymentEventStatus 9개·PgInboxStatus 5개 불변, 코드 대조 확인). pg_inbox 청소/추적 stateDiagram은 보너스 제공" },
      { "section": "design decisions", "item": "전체 결제 흐름 호환성 검토", "status": "yes", "evidence": "§5 — FOLLOW-6/5 동작보존 리팩토링, cleanup 시간분리, traceparent 비즈니스 비참여" },
      { "section": "acceptance criteria", "item": "성공 조건 관찰가능 형태", "status": "yes", "evidence": "§7 — grep 0건/compileJava 경고 0/통합테스트 만료행만 삭제·PENDING 미삭제/trace-id 연속성" },
      { "section": "acceptance criteria", "item": "실패 관찰 수단", "status": "yes", "evidence": "§7 실패 관찰 수단 — ERROR/WARN 로그 + Micrometer cleanup_deleted_total/zombie_recovered_total" },
      { "section": "verification plan", "item": "테스트 계층 결정", "status": "yes", "evidence": "§8 표 — 단위+Testcontainers 작업군별 명시, interview-0 §verification 사용자 확정과 정합" },
      { "section": "verification plan", "item": "벤치마크 지표", "status": "n/a", "evidence": "§8 — 전 작업군 k6 불요, 측정 의존 non-goal. cleanup 튜닝은 후속" },
      { "section": "artifact", "item": "결정 사항 섹션 존재", "status": "yes", "evidence": "§3 결정 사항 — D-TM-1~4/D-SPLIT-1~2/D-CLEAN-1~4/D-PGINBOX-1~2/D-TRACE-1~3" }
    ],
    "total": 13,
    "passed": 11,
    "failed": 0,
    "not_applicable": 2
  },

  "scores": {
    "clarity": 0.92,
    "completeness": 0.88,
    "risk": 0.85,
    "testability": 0.90,
    "fit": 0.93,
    "mean": 0.896
  },

  "findings": [
    {
      "severity": "minor",
      "checklist_item": "이 변경이 건드리는 모듈/패키지 경계가 명시됨",
      "location": "docs/topics/EOS-FOLLOWUP-CLEANUP.md §2-3 (product 경계)",
      "problem": "product 경계가 Flyway 마이그레이션을 언급하지 않는다. 실제 product-service는 db/migration이 아니라 db/schema 디렉토리를 쓰고 stock_commit_dedupe의 expires_at+idx_expires_at이 이미 존재해 마이그레이션이 불요하다.",
      "evidence": "product-service/src/main/resources/db/schema/V1__product_schema.sql:45 expires_at, :48 INDEX idx_expires_at. pg-service만 db/migration 사용.",
      "suggestion": "plan에서 product가 마이그레이션 대상 아님(인덱스 기존재)을 한 줄 명시해 디렉토리 컨벤션 혼동 차단."
    },
    {
      "severity": "minor",
      "checklist_item": "포트 인터페이스 위치(application/port vs infrastructure/port)가 결정됨",
      "location": "docs/topics/EOS-FOLLOWUP-CLEANUP.md §3-C D-CLEAN-1 표",
      "problem": "D-CLEAN-1이 product 포트를 EventDedupeStore로 지정했는데, pg-service에도 dedupe와 무관한 동명 EventDedupeStore(Redis adapter) 포트가 별도로 존재한다. plan 디스패치 프롬프트에서 단순 클래스명만 쓰면 어느 포트인지 모호해질 수 있다.",
      "evidence": "product-service/.../application/port/out/EventDedupeStore.java 와 pg-service/.../application/port/out/EventDedupeStore.java 가 동명으로 공존. 후자는 pg/infrastructure/dedupe/EventDedupeStoreRedisAdapter 가 구현.",
      "suggestion": "plan에서 대상 포트를 서비스 prefix 또는 FQCN으로 구분 명시(product의 EventDedupeStore vs pg의 EventDedupeStore)."
    }
  ],

  "previous_round_ref": null,
  "delta": null,

  "unstuck_suggestion": null
}
```
