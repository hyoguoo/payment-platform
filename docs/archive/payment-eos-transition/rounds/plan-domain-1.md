# plan-domain-1

**Topic**: PAYMENT-EOS-TRANSITION
**Round**: 1
**Persona**: Domain Expert (격리)
**Stage**: plan

## Reasoning

PLAN 은 discuss 라운드 2개에서 식별된 critical 1건 (DR-1) + high 3건 (DR-2/3/4) 을 모두 strong coverage 로 회귀 보호한다. DR-1 은 PET-8 (multi-product for-loop + StockEventUuidDeriver.derive 직접 호출) + PET-9 (StockEventUuidDeriver 유지 대상 명시) + PET-12 시나리오 #4 (productId 2건 + 재배달 시 양쪽 dedupe skip) 의 3단 가드로 봉쇄. DR-3 는 PET-3 (도메인 enum 6 상태 false / 3 상태 true 검증) + PET-8 (handle 진입 D7 가드 + LogFmt.warn) + PET-12 시나리오 #5 (QUARANTINED 늦은 APPROVED 시 DLQ 0건) 로 봉쇄. DR-4 는 PET-11 (product yml read_committed 단일 변경) + PET-12 시나리오 #2 (RDB rollback 시 read_committed consumer 가시화 0건 + DLQ 재시도 후 1건) 로 검증. DR-2/6/7/8 + DM2-1/2/3 은 PET-3/PET-14 의 문서 등재 또는 acceptable deferred 로 처리. INSERT IGNORE 멱등은 PET-5 단위 (4 시나리오 — 신규/중복/메타데이터/동시 race) + PET-12 시나리오 #3 통합 양쪽으로 검증. EOS abort + read_committed invisibility 는 PET-12 시나리오 #2 에 명시. PaymentStatus enum 변경은 없고 (PET-3 는 Javadoc 보강만), 도메인 invariant 회귀 가드는 충분. 모든 critical/high strong + new critical 없음 → pass.

## Domain risk checklist

체크리스트 (plan-ready.md domain risk 섹션):

- [yes] **discuss 식별 domain risk 가 각각 대응 태스크를 가짐**: DR-1~8 + DM2-1~3 총 11건이 PLAN §"도메인 리스크 → 태스크 추적 테이블" (line 413~427) 에 1:1 매핑. orphan 없음.
- [yes] **중복 방지 체크가 필요한 경로에 계획됨**: `EventDedupeStore.markIfAbsent` INSERT IGNORE 패턴이 PET-1 (port) + PET-2 (Fake) + PET-4 (Flyway V2 테이블) + PET-5 (Jdbc 구현 + 4 시나리오 단위 테스트) + PET-8 (호출 사이트) + PET-12 시나리오 #3 (통합 회귀) 으로 6단 계획. multi-product idempotencyKey 결정성 (StockEventUuidDeriver.derive) 은 PET-9 유지 + PET-12 시나리오 #4 통합 회귀로 별도 보호.
- [yes] **재시도 안전성 검증 태스크 존재**: PET-12 시나리오 #2 (abort 시 DLQ 재시도 5회 후 메시지 1건) + PET-3/PET-8 (D7 가드로 not-retryable PaymentStatusException → DLQ 분기 사전 차단). DefaultErrorHandler 자체는 변경 없음 (§9 재시도 정책 — EOS 와 직교, FixedBackOff 1000ms × 5 → DLQ 유지) 이라 별도 회귀 테스트 불필요.

## 도메인 관점 추가 검토

### 1. DR-1 (critical) — multi-product idempotencyKey 결정성 회귀 가드

**PLAN 매핑**: PET-8 (handle 재작성) + PET-9 (StockEventUuidDeriver 유지 명시) + PET-12 시나리오 #4.

**소스 교차검증**:
- `StockEventUuidDeriver.java` line 33~36: `String seed = prefix + ":" + orderId + ":" + productId;` — productId 별 결정성 보장 코드 존재.
- `PaymentEventStatus.java` line 34~39: `isCompensatableByFailureHandler()` — D7 가드 기반.
- PLAN PET-9 line 261~264: 유지 대상 3종 명시 (`StockEventUuidDeriver` / `StockCommittedEvent` / `PaymentTopics`). 삭제 17 단위에서 분리.
- PLAN PET-8 line 207: "for-loop (`paymentEvent.getPaymentOrderList()`) 안에서 `StockEventUuidDeriver.derive(orderId, productId, 'stock-commit')` → `stockCommittedKafkaTemplate.send(topic, key, payload)`" — multi-product 분리 발행 명시.
- PLAN PET-8 테스트 `shouldDeriveDistinctIdempotencyKeyPerProduct()` line 218: 단위 테스트가 PaymentOrder 2건 시 2회 send + 서로 다른 idempotencyKey 검증.
- PLAN PET-12 시나리오 #4 line 348 + 토픽 §8 #4 line 677~681: 통합 환경에서 productId 100/200 두 메시지 가시화 + product-service `stock_commit_dedupe` 두 row 박힘 + 재배달 시 두 메시지 모두 skip 검증.

**커버리지 품질**: strong. 4단 가드 (PET-8 단위 + PET-9 유지 + PET-12 통합 + 토픽 D8 결정) 가 silent 재고 사고 회귀 경로 완전 봉쇄.

### 2. DR-2 (high) — transactional.id ↔ docker-compose hostname 충돌

**PLAN 매핑**: PET-6 (단일 인스턴스 가정으로 HOSTNAME:local 패턴 적용) + PET-14 (CONCERNS.md L6 / TODOS.md TC-13-FOLLOW-1 등재).

**소스 교차검증**:
- PLAN PET-6 line 153: `transactional.id = ${spring.application.name}-${HOSTNAME:local}` — D4 결정 그대로.
- PLAN PET-6 line 156: Eureka instance-id 도 `${spring.application.name}:${HOSTNAME:local}:${server.port}` 통일 — D4 보강.
- PLAN PET-14 line 399: CONCERNS.md 에 L6 (multi-instance hostname 충돌) 등재 명시.
- 토픽 §11 L6 + §2 line 126~127 — docker-compose.apps.yml 영향 모듈에 명시 + "수정 안 함" + multi-instance 확장 트리거 시 (a)/(b) 옵션 후속.

**커버리지 품질**: strong (acceptable deferred — 학습용 단일 인스턴스 가정 + 운영 인지 경로 확보).

### 3. DR-3 (high) — QUARANTINED 늦은 APPROVED silent DLQ 회피 D7 가드

**PLAN 매핑**: PET-3 (도메인 enum 가드 검증 TDD) + PET-8 (handle 진입 가드 호출 + LogFmt.warn) + PET-12 시나리오 #5 (통합 회귀).

**소스 교차검증**:
- `PaymentEventStatus.java` line 34~39: `isCompensatableByFailureHandler()` — READY/IN_PROGRESS/RETRYING → true, 6 상태 (DONE/FAILED/CANCELED/PARTIAL_CANCELED/EXPIRED/QUARANTINED) → false. **PLAN PET-3 의 기대 매트릭스 (line 71~72) 와 실제 enum 구현 100% 일치**.
- PLAN PET-3 line 73: Javadoc 보강 (두 사용처 명시 + D7 가드 영향 경고) — DM2-2 시맨틱 oversharing 방어.
- PLAN PET-3 테스트 3건: `shouldReturnTrueForProceedableStatuses` / `shouldReturnFalseForNonProceedableStatuses` / `shouldSkipQuarantinedExplicitly` — QUARANTINED 단독 assert 까지 확보.
- PLAN PET-8 line 203: handle 진입 시 가드 false 면 `LogFmt.warn + noop return` 명시.
- PLAN PET-12 시나리오 #5 line 349: QUARANTINED 결제 + APPROVED 메시지 → dedupe row 0건 + DLQ 0건 + warn 1건 검증.

**커버리지 품질**: strong. 도메인 가드 검증 + 호출 사이트 검증 + 통합 회귀의 3단.

### 4. DR-4 (high) — deploy 순서 (product read_committed 가 payment EOS 발행보다 선행) + EOS abort invisibility

**PLAN 매핑**: PET-11 (product-service yml 변경) + PET-12 시나리오 #2 (read_committed abort invisibility 통합).

**소스 교차검증**:
- PLAN PET-11 line 313: `spring.kafka.consumer.isolation-level: read_committed` 라인 추가 + `StockCommitConsumer` 코드 변경 없음 (read_committed 가 yml 만으로 적용되는 정합).
- PLAN PET-11 line 316: §12 deploy 순서 — "이 변경이 먼저 staging 에 배포되어야 함 (payment-service EOS 발행 시작 전)" 명시.
- PLAN ARCH-REVIEW comment line 301: PET-11 commit 순서를 PET-8 / PET-12 보다 앞에 두면 git log 상 "product 먼저" 의도가 보임 — 우선순위 권장 (강제 아님).
- PLAN PET-12 시나리오 #2 line 346~347: `shouldMakeAbortMessageInvisibleOnRollback()` — RuntimeException 주입 → dedupe row 0개 + payment 상태 불변 + stock-committed read_committed 0건 + DLQ 재시도 후 1건. **EOS abort + read_committed invisibility 통합 검증 명시**.
- 토픽 §12 mermaid (line 919~930) + Acceptance 7번 (line 514) — 운영 배포 순서 룰.

**커버리지 품질**: strong. yml 변경 + abort invisibility 통합 회귀 + 운영 배포 순서 문서화의 3단.

### 5. DR-5 (medium) — INSERT IGNORE 멱등 시맨틱 race window

**PLAN 매핑**: PET-5 (`shouldNotThrowOnConcurrentInsertSameKey`) + PET-8 (0 row 시 발행 항상 진행) + PET-12 시나리오 #3 (위키 line 141 보장).

**소스 교차검증**:
- PLAN PET-5 테스트 4건 (line 126~129) — 동시 INSERT IGNORE race 단위 + 메타데이터 정확성 검증.
- PLAN PET-8 line 217: `shouldSkipBusinessButAlwaysSendWhenMarkIfAbsentReturnsZero` — 0 row 시 비즈니스 skip + 발행 진행 단위 테스트.
- PLAN PET-12 시나리오 #3 line 347: 같은 event_uuid 재배달 → payment 상태 불변 + stock-committed 가시화 통합 검증.

**커버리지 품질**: strong.

### 6. DR-6 (medium) — 빅뱅 PR 의 회복 비대칭

**PLAN 매핑**: PET-14 (CONCERNS.md L5 등재).

**커버리지 품질**: strong (문서 등재 만으로 충분 — 회복 비대칭은 코드 변경으로 해결 불가, 운영 인지 경로 확보가 본질).

### 7. DR-7 (medium) — SCR L7 cascade 빈도 평가

**PLAN 매핑**: PET-8 (`shouldMaintainCompensationOrderForFailed` — 보상 → RDB 순서 유지 회귀 테스트) + PET-14 (CONFIRM-FLOW 갱신).

**커버리지 품질**: strong. 보상 순서 유지의 단위 회귀 가드 + 빈도 평가 표 (토픽 §10) 문서 갱신.

### 8. DR-8 (minor) — EventDedupeStore 동명 재사용

**PLAN 매핑**: PET-1 line 28 — "SCR 토픽에서 폐기한 동명 port 와 시그니처가 다름 (lease 기반 two-phase → INSERT IGNORE one-phase)" 명시. 동명 재사용 OK (§5 layer 표 결정).

**커버리지 품질**: weak (acceptable). 동명 재사용으로 archive 추적성이 일부 저하되지만 시그니처 차이 명시 + 사용 의도 다름 — minor 라 deferred 합리.

### 9. DM2-1 (minor) — 배포 순서 사람 실수 backstop

**PLAN 매핑**: PET-14 line 401 — TODOS.md TC-13-FOLLOW-1 등재 + 운영 배포 체크리스트 후속.

**커버리지 품질**: strong.

### 10. DM2-2 (minor) — D7 시맨틱 oversharing

**PLAN 매핑**: PET-3 line 73 — Javadoc 에 "두 사용처 명시 + 변경 시 D7 가드 영향 경고" 추가.

**커버리지 품질**: strong. javadoc 보강이 직접 해결책.

### 11. DM2-3 (minor) — D7 분기 알람 SLO

**PLAN 매핑**: PET-14 line 400 — TODOS.md TC-13-FOLLOW-4 등재.

**커버리지 품질**: strong (deferred 합리 — verify 단계 운영 모니터링 신설 영역).

### 12. 추가 검증 — INSERT IGNORE 멱등 회귀 보호 테스트 존재

PET-5 단위 (4 시나리오 — 신규/중복/메타데이터 정확성/동시 race) + PET-12 시나리오 #3 통합 회귀. 단위 + 통합 양쪽 모두 명시. 코드 작성만 있고 회귀 테스트 누락한 경우가 아님.

### 13. 추가 검증 — EOS abort 시 RDB rollback + product read_committed invisibility 통합 검증

PET-12 시나리오 #2 (line 346) — `shouldMakeAbortMessageInvisibleOnRollback`. RuntimeException 주입 → dedupe row 0개 + payment 상태 불변 + stock-committed read_committed 0건 + DLQ 재시도 후 1건. 통합 테스트 명시.

### 14. 추가 검증 — PaymentStatus 변경 (D7) 이 다른 상태 전이를 깨지 않는가

PaymentEventStatus enum **자체는 변경 없음** (PET-3 는 Javadoc 보강만 — line 66). 기존 상태 / 전이 룰 그대로. `isCompensatableByFailureHandler()` 메서드는 이미 SCR 에서 도입된 SSOT 의 재사용. 도메인 invariant 회귀 0.

### 15. 추가 검증 — StockOutbox 묶음 삭제 회귀 0 검증

PET-9 line 268~269 — "`./gradlew build` 컴파일 통과 + `./gradlew test` 회귀 0 (삭제된 SUT 의 테스트가 함께 삭제됨)" 완료 기준 명시. 회귀 0 확인이 완료 기준에 포함.

### 16. 약한 우려 (fail 사유 아님 — Architect 검토와 중복되는 minor)

- **PET-6 / PET-7 의 빈 wiring 단위 검증 부재**: transactional.id / transaction.timeout.ms / KafkaTransactionManager wiring / isolation.level=read_committed 가 모두 EOS 효과의 전제. PET-12 통합 테스트 #1 (정상 commit) / #2 (abort invisibility) 가 end-to-end 검증으로 흡수하므로 deferred 합리. 도메인 관점에서 이 빈 wiring 검증 부재가 즉시 silent 사고로 이어지지 않음 — PET-12 가 사후 검증으로 충분.

## Findings

| ID | Severity | 위치 | 카테고리 | Finding |
|---|---|---|---|---|
| PD1-1 | minor | PAYMENT-EOS-TRANSITION-PLAN.md:PET-1 line 28, §5 layer 표 | 명명 충돌 / archive 추적성 | DR-8 의 `EventDedupeStore` 동명 재사용이 PET-1 비고에 명시되지만 (시그니처 다름 + §5 결정 참조), git blame / archive 추적 시 SCR 폐기 port 와 본 토픽 신설 port 가 같은 이름이라 혼선 가능. plan-review 또는 execute 단계에서 (a) 동명 재사용 / (b) `PaymentEventDedupeStore` 분리 명명 중 최종 결정 — 현재 (a) default 선택지로 deferred. acceptable minor. |
| PD1-2 | minor | PAYMENT-EOS-TRANSITION-PLAN.md:PET-6 line 138~163, PET-7 line 166~187 | 단위 검증 부재 (도메인 영향 marginal) | PET-6 / PET-7 의 EOS wiring (transactional.id 패턴 / transaction.timeout.ms=10000 / KafkaTransactionManager 빈 / isolation.level=read_committed) 이 tdd=false 로 PET-12 통합 테스트에만 의존. wiring 단위 검증 부재가 PET-12 시나리오 #1/#2 의 end-to-end 검증으로 흡수되므로 도메인 silent 사고 위험은 낮으나, transaction.timeout.ms 가 RDB @Transactional(timeout=5) 와 정렬되는지 (L4 흡수) 의 정합 검증은 통합 환경에서만 사후 확인. 도메인 관점 minor — execute 단계 implementer 가 PET-6 GREEN 단계에서 `@SpringBootTest(classes = KafkaProducerConfig.class)` 빈 단위 검증 1개 추가하면 견고. |

## JSON

```json
{
  "round": 1,
  "persona": "domain-expert",
  "topic": "PAYMENT-EOS-TRANSITION",
  "stage": "plan",
  "decision": "pass",
  "gate_results": [
    {"item": "traceability - PLAN.md 가 docs/topics/<TOPIC>.md 결정 참조", "status": "yes", "evidence": "PLAN line 6 ('토픽: docs/topics/PAYMENT-EOS-TRANSITION.md') + 각 PET 의 '결정 매핑' 필드 D1~D8 참조"},
    {"item": "traceability - orphan 태스크 없음", "status": "yes", "evidence": "PLAN §'결정 → 태스크 매핑' line 433~444 — D1~D8 전부 최소 1개 이상 태스크에 매핑"},
    {"item": "task quality - 객관적 완료 기준", "status": "yes", "evidence": "14 PET 전부 '완료 기준' 섹션 명시 (테스트 클래스명 GREEN, 파일 존재, 회귀 0 등)"},
    {"item": "task quality - 태스크 크기 ≤2시간", "status": "yes", "evidence": "PLAN §요약 line 503 — 14개 모두 S(~30분)/M(~1시간)/L(~2시간)"},
    {"item": "task quality - 관련 소스 파일/패턴 언급", "status": "yes", "evidence": "각 PET 의 '건드릴 파일/패턴' 필드"},
    {"item": "TDD - tdd=true 태스크 테스트 클래스/메서드 스펙", "status": "yes", "evidence": "PET-3/5/8/12 모두 테스트 클래스명 + 테스트 메서드명 스펙 명시"},
    {"item": "TDD - tdd=false 태스크 산출물 명시", "status": "yes", "evidence": "PET-1/2/4/6/7/9/10/11/13/14 모두 신규/변경 파일 경로 명시"},
    {"item": "TDD - 분류 합리성", "status": "yes", "evidence": "business logic (PET-8) / state machine (PET-3) / edge case (PET-5/12) 모두 tdd=true. config (PET-6/7) / SQL (PET-4/10) / 삭제 (PET-9) / yml (PET-11) / docs (PET-13/14) tdd=false 합리"},
    {"item": "dependency - layer 의존 순서", "status": "yes", "evidence": "PLAN ARCH-REVIEW + §태스크 실행 순서 — PET-1 port → PET-3 domain → PET-8 application → PET-5/6/7 infrastructure. 역의존 없음"},
    {"item": "dependency - Fake 가 소비 태스크보다 선행", "status": "yes", "evidence": "PET-2 (FakeEventDedupeStore) → PET-8 RED 단계가 소비. 선행 의존 PET-1 명시"},
    {"item": "dependency - orphan port 없음", "status": "yes", "evidence": "PET-1 port → PET-2 Fake + PET-5 Jdbc 어댑터 모두 후속 흡수"},
    {"item": "architecture - layer 룰 충돌 없음", "status": "yes", "evidence": "PLAN ARCH-REVIEW '포트 위치 OK' (STRUCTURE.md line 67~68 + ARCHITECTURE.md line 82~84 룰 일치)"},
    {"item": "architecture - 모듈 간 호출 port/InternalReceiver", "status": "yes", "evidence": "PET-8 의 EventDedupeStore / StockCachePort / PaymentCommandUseCase / KafkaTemplate 모두 port/template 의존"},
    {"item": "architecture - Lombok/예외/로깅 패턴", "status": "yes", "evidence": "PET-8 LogFmt.warn 명시 + PaymentStatusException 분류 결과 §9 흡수"},
    {"item": "artifact - docs/<TOPIC>-PLAN.md 존재", "status": "yes", "evidence": "docs/PAYMENT-EOS-TRANSITION-PLAN.md 존재"},
    {"item": "domain risk - discuss 식별 risk 모두 대응 태스크 보유", "status": "yes", "evidence": "PLAN §'도메인 리스크 → 태스크 추적 테이블' line 413~427 — DR-1~8 + DM2-1~3 총 11건 1:1 매핑. orphan 없음"},
    {"item": "domain risk - 중복 방지 체크 경로 계획", "status": "yes", "evidence": "EventDedupeStore INSERT IGNORE 패턴 PET-1/2/4/5/8/12 6단 + StockEventUuidDeriver 결정성 PET-9 유지 + PET-12 #4 회귀"},
    {"item": "domain risk - 재시도 안전성 검증", "status": "yes", "evidence": "PET-12 시나리오 #2 (DLQ 재시도 5회 후 메시지 1건) + PET-3/PET-8 (D7 가드로 PaymentStatusException → DLQ silent 분기 사전 차단)"}
  ],
  "fail_items": [],
  "dr_mapping": [
    {"dr_id": "DR-1", "severity": "critical", "covered_by_tasks": ["PET-8", "PET-9", "PET-12"], "coverage_quality": "strong", "note": "PET-8 multi-product for-loop + StockEventUuidDeriver.derive 직접 호출 (단위 테스트 shouldDeriveDistinctIdempotencyKeyPerProduct) + PET-9 유지 대상 명시 + PET-12 시나리오 #4 통합 회귀 (productId 2건 + 재배달 양쪽 dedupe skip). 토픽 D8 두 UUID 역할 분리 결정과 정합. 실제 코드 StockEventUuidDeriver.java line 33~36 의 (orderId, productId, prefix) 결정성 보장과 1:1 매핑"},
    {"dr_id": "DR-2", "severity": "high", "covered_by_tasks": ["PET-6", "PET-14"], "coverage_quality": "strong", "note": "PET-6 단일 인스턴스 가정 HOSTNAME:local 패턴 + Eureka instance-id 통일 + PET-14 CONCERNS.md L6 / TODOS.md TC-13-FOLLOW-1 등재. 토픽 §11 L6 + §2 line 126~127 multi-instance 확장 트리거 시 옵션 (a)/(b) 후속 — acceptable deferred"},
    {"dr_id": "DR-3", "severity": "high", "covered_by_tasks": ["PET-3", "PET-8", "PET-12"], "coverage_quality": "strong", "note": "PET-3 도메인 enum 검증 (READY/IN_PROGRESS/RETRYING true vs 6 상태 false — 실제 코드 PaymentEventStatus.java line 34~39 와 100% 일치) + Javadoc 보강 (DM2-2 동시 흡수) + PET-8 handle 진입 가드 LogFmt.warn + noop return + PET-12 시나리오 #5 통합 (QUARANTINED + APPROVED → DLQ 0건 + dedupe 0건 + warn 1건). PaymentStatusException → DLQ silent 분기 사전 차단"},
    {"dr_id": "DR-4", "severity": "high", "covered_by_tasks": ["PET-11", "PET-12"], "coverage_quality": "strong", "note": "PET-11 product-service yml read_committed 단일 변경 + PET-12 시나리오 #2 통합 (RDB rollback → read_committed consumer stock-committed 0건 + DLQ 재시도 후 1건). 토픽 §12 배포 순서 mermaid + Acceptance 7번 + ARCH-REVIEW 의 PET-11 commit 우선 권장 (git log 가시성)"},
    {"dr_id": "DR-5", "severity": "medium", "covered_by_tasks": ["PET-5", "PET-8", "PET-12"], "coverage_quality": "strong", "note": "PET-5 단위 4 시나리오 (신규/중복/메타데이터/동시 race shouldNotThrowOnConcurrentInsertSameKey) + PET-8 단위 (0 row 시 발행 항상 진행 — shouldSkipBusinessButAlwaysSendWhenMarkIfAbsentReturnsZero) + PET-12 시나리오 #3 통합 (재배달 시 위키 line 141 보장)"},
    {"dr_id": "DR-6", "severity": "medium", "covered_by_tasks": ["PET-14"], "coverage_quality": "strong", "note": "PET-14 CONCERNS.md L5 등재 — Flyway down migration 부재 + 17 단위 동시 revert 필요 + 머지 직후 24시간 모니터링 SLO + 60분 회귀 판정 룰. 코드로 해결 불가능한 회복 비대칭은 운영 인지 경로 확보가 본질"},
    {"dr_id": "DR-7", "severity": "medium", "covered_by_tasks": ["PET-8", "PET-14"], "coverage_quality": "strong", "note": "PET-8 보상 순서 유지 단위 회귀 (shouldMaintainCompensationOrderForFailed — compensateAtomic 먼저, markPaymentAsFail 나중) + PET-14 CONFIRM-FLOW.md 갱신에 4 항목 평가표 (broker / RDB lock / Redis / 도메인 예외) 반영"},
    {"dr_id": "DR-8", "severity": "minor", "covered_by_tasks": ["PET-1"], "coverage_quality": "weak", "note": "PET-1 비고 line 28 — 동명 재사용 OK + 시그니처 차이 (two-phase → one-phase) 명시. archive 추적성 일부 저하는 minor 라 deferred 합리. plan-review 또는 execute 단계 implementer 가 분리 명명으로 forward-fix 가능"},
    {"dr_id": "DM2-1", "severity": "minor", "covered_by_tasks": ["PET-14"], "coverage_quality": "strong", "note": "PET-14 TODOS.md 에 운영 배포 체크리스트 신설 등재 (verify 단계 산출물)"},
    {"dr_id": "DM2-2", "severity": "minor", "covered_by_tasks": ["PET-3"], "coverage_quality": "strong", "note": "PET-3 Javadoc 보강 (두 사용처 — 보상 핸들러 / EOS consumer 진입 가드 — 명시 + 변경 시 D7 가드 영향 경고)"},
    {"dr_id": "DM2-3", "severity": "minor", "covered_by_tasks": ["PET-14"], "coverage_quality": "strong", "note": "PET-14 TODOS.md TC-13-FOLLOW-4 (D7 가드 분기 알람 SLO) 등재"}
  ],
  "domain_findings": [
    {"id": "PD1-1", "severity": "minor", "location": "PAYMENT-EOS-TRANSITION-PLAN.md:PET-1 line 28", "category": "naming / archive traceability", "issue": "EventDedupeStore 동명 재사용 — git blame / archive 추적 혼선 가능", "mitigation": "execute 단계 implementer 가 (a) 동명 재사용 / (b) PaymentEventDedupeStore 분리 명명 중 forward-fix 가능"},
    {"id": "PD1-2", "severity": "minor", "location": "PAYMENT-EOS-TRANSITION-PLAN.md:PET-6 line 138~163, PET-7 line 166~187", "category": "wiring 단위 검증 부재", "issue": "EOS wiring tdd=false → PET-12 통합 테스트에 100% 의존. transaction.timeout.ms ↔ @Transactional(timeout=5) 정렬 정합이 통합 환경에서만 사후 확인", "mitigation": "execute 단계 implementer 가 PET-6 GREEN 단계에 `@SpringBootTest(classes = KafkaProducerConfig.class)` 빈 단위 검증 1개 추가 가능 (선택)"}
  ],
  "auxiliary_scores": {
    "dr_mapping_completeness": 0.95,
    "test_layer_coverage": 0.9,
    "compensation_order_preservation": 0.95,
    "idempotency_test_strength": 0.95,
    "rollback_path_visibility": 0.75,
    "wiring_verification_strength": 0.7
  }
}
```
