# discuss-domain-2

**Topic**: PAYMENT-EOS-TRANSITION
**Round**: 2
**Persona**: Domain Expert (격리)

## Reasoning

Round 1 의 critical 1건(DR-1) + high 3건(DR-2/3/4) + medium 3건(DR-5/6/7) 흡수 결과를 산출물 + 실제 코드와 교차 검증했다. **DR-1 은 §6 유지 대상 섹션 + §3 flowchart 의 multi-product loop 확장 + D8 두 UUID 역할 분리 + §8 통합 테스트 #4 의 4단 가드로 완전 봉쇄**, DR-2 는 단일 인스턴스 가정으로 의도적 deferred + §9 (g) / §11 L6 / CONCERNS+TODOS 등재로 운영 인지 확보, **DR-3 는 D7 가드 정책(`isCompensatableByFailureHandler` 부재) + `PaymentStatusException extends RuntimeException` 코드 확인 결과 + 통합 테스트 #5 로 QUARANTINED 늦은 APPROVED silent DLQ 분기를 사전 차단**, DR-4 는 §12 신설 + Acceptance 7번 + Mermaid + actuator /env 확인 룰로 spurious 재고 차감 windows 가드. DR-5/6/7 medium 모두 본문 등재. 새로 도입된 결정(D7 / D8 / §12)을 다시 도메인 관점에서 검토했을 때 **새 critical 은 발견되지 않음**. 다만 (i) §12 배포 순서가 PR 본문 텍스트만으로 강제되어 사람 실수에 노출, (ii) D7 가드 SSOT 재사용이 `isCompensatableByFailureHandler` 메서드 시맨틱 oversharing 위험, (iii) D7 LogFmt.warn 단독으로 끝나는 QUARANTINED 늦은 APPROVED 분기에 모니터링 알람 SLO 가 acceptance 에 누락 — 세 항목 모두 minor 등급으로 plan 단계 진입 후 보강 가능. Round 1 critical / high 4건 모두 resolved + 새 critical 없음 → **pass**.

## Domain risk checklist

체크리스트 `domain risk` 섹션 (discuss-ready.md line 40~45):

- [yes] **멱등성 전략 결정**: §9 멱등성 전략 표 (line 578~585) + D8 두 UUID 역할 분리 (line 393~416) + DR-5 race window 명시 (line 584). 수명 (P8D = Kafka retention 7d + 복구 버퍼 1d) + 충돌 처리 (INSERT IGNORE → 0 row 비즈니스 skip + 발행 항상 진행) + INSERT IGNORE row 신호 의미 한계까지 모두 명시.
- [yes] **장애 시나리오 ≥ 3 식별**: §9 시나리오 7개 (a~g) — Round 1 에서 식별된 (f) QUARANTINED 늦은 APPROVED + (g) transactional.id fencing 충돌 모두 흡수.
- [yes] **재시도 정책 정의**: §9 재시도 정책 (line 599~612) — 기존 `DefaultErrorHandler` (FixedBackOff 1000ms × 5 → DLQ) 유지 + `PaymentStatusException extends RuntimeException` 분류 결과 명시 + D7 가드 도입 후 silent DLQ 분기 봉쇄 효과 명시.
- [yes] **PII 검토**: §9 PII (line 614~616) — 새 PII 없음. event_uuid 비식별 / order_id 내부 / status enum / timestamp.

체크리스트 항목 4개 모두 yes — Round 1 의 yes 판정에서 도메인 리스크 추가 식별이 본 토픽 §9 / §11 / §12 흡수로 모두 해소.

## 도메인 관점 추가 검토 (흡수 검증 + 새로 도입된 결정 재검토)

### 1. DR-1 흡수 검증 — `StockEventUuidDeriver` 보존 + multi-product loop + D8 분리

**소스 교차검증**:
- `StockEventUuidDeriver.java` line 14~17 javadoc 명시: "multi-product 결제 시 모든 stock-committed 이벤트가 동일 orderId 를 공유하더라도 productId 별로 고유한 idempotencyKey 를 갖도록 보장한다 — product-service `StockCommitUseCase` 가 이 키를 dedupe 키로 쓰므로, 같은 orderId 의 다른 productId 가 첫 건만 처리되고 나머지가 skip 되는 회귀를 차단한다." → 본 토픽 DR-1 회귀 경로 그대로 인지된 도메인 자산.
- 산출물 §6 유지 대상 표 (line 487~496) 가 `StockEventUuidDeriver` / `StockCommittedEvent` / `PaymentTopics` 3건을 explicit 등재. 보존 사유에 "이 파일을 같이 삭제하면 product-service `stock_commit_dedupe` 가 multi-product 결제 회귀 — silent 재고 사고 (DR-1)" 명시.
- 산출물 §3 flowchart line 222~225: `ApprovedPath → StockLoop("PaymentOrder 순회") → DeriveKey("StockEventUuidDeriver.derive") → StockSend → StockLoop` — Round 1 의 추상 1박스 (`StockSend`) 가 multi-product loop 으로 풀려 있음.
- 산출물 §4 D8 (line 393~416) 가 두 종류 UUID 의 역할 분리를 표로 정리: `event_uuid` (수신 멱등) vs `idempotencyKey` (발행 결정성). "만약 둘이 합쳐지면 multi-product 결제에서 N개 메시지가 같은 idempotencyKey 를 가지게 되어 product-service `stock_commit_dedupe` 가 첫 메시지만 차감 + 나머지 N-1 개 skip — silent 재고 사고" 명시.
- 산출물 §7 Acceptance 2번 (line 508) 통합 테스트 #4 (DR-1 회귀 가드) + §8 통합 테스트 #4 (line 558~562) — productId 2건 (100/200) + 재배달 검증.

**판정**: 4단 가드 (§6 유지 / §3 loop / D8 분리 / §8 #4 회귀 테스트) 가 다 박혀 있어 silent 재고 사고 회귀 경로 완전 봉쇄. resolved.

### 2. DR-2 흡수 검증 — 단일 인스턴스 가정 + multi-instance deferred

**소스 교차검증**:
- `docker/docker-compose.apps.yml:30` 의 `hostname: payment-service` 라인 — Round 1 발견 그대로 존재.
- 산출물 §4 D4 line 286~291 — "단일 OK / 다중 fence 무한 반복" 표로 충돌 명시.
- 산출물 §2 line 126~127 — 영향 모듈에 docker-compose.apps.yml "수정 안 함" 명시.
- 산출물 §11 L6 (line 741~752) — Round 1 의 DR-2 mitigation 그대로 흡수. CONCERNS.md + TODOS.md 등재 후속 + 시나리오 (g) + D4 (a)/(b) 옵션 명시.
- 산출물에서 부수 결정: Eureka `instance-id` 도 `${random.uuid}` → `${HOSTNAME:local}` 패턴 통일 (line 301). 현재 application.yml:85 의 `${spring.application.name}:${random.uuid}:${server.port}` 와 정합 — 이 결정 자체는 fencing 의도와 일치하므로 도메인적으로 합당.

**판정**: 단일 인스턴스 가정으로 본 토픽 범위 제한 + 다중 인스턴스 트리거 시점에 옵션 (a)/(b) 적용 명시 + 운영 인지 경로 (CONCERNS/TODOS/시나리오 g) 확보. resolved (acceptable deferred).

### 3. DR-3 흡수 검증 — D7 가드 정책 + PaymentStatusException 분류 확인

**소스 교차검증**:
- `PaymentStatusException.java` line 7: `public class PaymentStatusException extends RuntimeException` 확인. `IllegalStateException` 상속 아님 — 산출물 §4 D7 line 376 의 "RuntimeException 직접 상속" 클레임과 일치.
- `KafkaErrorHandlerConfig` not-retryable 화이트리스트: `MessageConversionException`, `IllegalArgumentException`, `IllegalStateException` — `PaymentStatusException` 미매칭. 따라서 D7 가드 도입 전에는 5회 retry 후 DLQ (즉시 DLQ 아닌 silent DLQ).
- `PaymentEventStatus.java` line 34~39 `isCompensatableByFailureHandler()` 구현: READY/IN_PROGRESS/RETRYING → true, 나머지 (DONE/FAILED/CANCELED/PARTIAL_CANCELED/EXPIRED/QUARANTINED) → false. 산출물 §4 D7 의 표 line 361~371 과 100% 일치.
- 산출물 §3 flowchart line 213~217 `GuardCheck → skip|proceed` 분기 — D7 정책 반영.
- 산출물 §8 통합 테스트 #5 (line 563~566) — QUARANTINED 결제의 늦은 APPROVED 도착 시 DLQ 0건 + dedupe row 0건 + warn 1건 + Kafka offset 정상 진행 검증.
- 산출물 §9 시나리오 (f) (line 596) 등재.
- 기각 대안 (A) `isTerminal` only / (B) OR 표현 / (C) 가드 제거 — 모두 도메인 관점에서 부적절한 이유 명시.

**판정**: D7 가드가 `PaymentEvent.done()` 의 `INVALID_STATUS_TO_SUCCESS` 분기 (line 107~110) 도달 자체를 사전 차단 → 5회 retry × Kafka tx abort × silent DLQ cascade 봉쇄. 부수 효과 (QUARANTINED 결제의 후속 복구가 reconciler 책임으로 위임됨) 도 §11 / 본 토픽 범위 밖으로 명시. resolved.

### 4. DR-4 흡수 검증 — §12 배포 순서 + Acceptance 7번

**소스 교차검증**:
- 산출물 §12 (line 773~828) 신설 — "왜 배포 순서가 결정 단위인가" / 잘못된 순서 t0/t1 windows 표 / 강제 룰 (product 먼저 + actuator /env 확인 → payment 나중) / Mermaid flowchart / 단일 PR + 배포 순서 채택 이유 (대안 = D6 분리 PR 의 중간 상태 혼란 비교).
- 산출물 §7 Acceptance 7번 (line 514) — deploy 순서 의무가 PR description + verify 단계 PR 본문에 명시 / 운영 배포 시 product → payment 순서 운영자 확인 의무.
- 통합 테스트 #2 (EOS abort 흐름, line 550~553) — read_committed consumer 로 폴링 시 abort 메시지 invisible 검증. 본 검증이 rolling deploy 시나리오 자체를 시뮬레이션하지는 않지만, EOS abort 시 read_committed invisibility 의 정합성은 확인.

**판정**: 단일 PR 결정의 도메인 부수효과 (rolling deploy windows) 가 §12 명시 + 통합 테스트 #2 의 abort invisibility 검증으로 봉쇄. 다만 PR 본문 텍스트만으로 강제되는 부분은 새 minor (아래 항목 1) 로 등재. resolved.

### 5. DR-5/6/7 medium 흡수 검증

- **DR-5 (INSERT IGNORE 의미 한계)**: §9 멱등성 전략 표 line 584 — partition rebalance 짧은 윈도우 시나리오 + dedupe row 박혔지만 자기전이 no-op + TC-11 cleanup SLO 잡음 고려 명시. resolved.
- **DR-6 (빅뱅 회복 비대칭)**: §11 L5 (line 722~739) — Flyway down migration 부재 + 17 단위 동시 revert 필요 + outbox 큐 회복 결 소실 + 머지 직후 24시간 모니터링 SLO (consumer 처리 정지 / DLQ inbound rate / dedupe row 추세) + 60분 회귀 판정 룰. resolved.
- **DR-7 (L7 cascade 빈도 평가)**: §10 (line 660~674) — 4 항목 평가표 (broker / RDB lock / Redis 보상 / 도메인 예외). 총평 = "broker 불안정 marginal 증가 vs D7 가드로 도메인 예외 분기 감소 → net 무변에 가까운 marginal 증가, 운영 SLO 변경 불필요". 평가 자체가 합리적 — broker 불안정은 KRaft 단일 broker 광역 장애의 일부라 L7 단독 cascade 가 아님이라는 논리 정합. resolved.

### 6. 새로 도입된 결정 (D7 / D8 / §12) 의 도메인 부수효과 재검토

**(i) §12 단일 PR + 배포 순서 강제의 사람 실수 노출**:
- 본 토픽은 PR 본문 텍스트 + verify 단계 체크리스트 + Acceptance 7번 으로 배포 순서를 강제하지만, **CI/CD 파이프라인 레벨의 dependency / gate 가 없다**. 운영자가 payment 를 product 보다 먼저 deploy 하는 사람 실수 시 정확히 DR-4 회귀 시나리오 (t0/t1 windows 의 spurious 재고 차감) 가 발생한다. 학습용 프로젝트 + verify 단계 체크리스트 신설로 완화되지만, 도메인 관점에서는 사람 실수에 대한 backstop 이 없다는 점이 minor 등급으로 인지 필요.
- 다만 통합 테스트 #2 (EOS abort + read_committed invisibility) 가 정합성 자체는 검증 + L5 의 60분 회귀 판정 룰이 머지 직후 운영 모니터링으로 사후 catch — minor 등급 적정.

**(ii) D7 의 `isCompensatableByFailureHandler` SSOT 재사용의 시맨틱 oversharing**:
- 이 메서드는 직전 SCR 토픽에서 "보상 핸들러 재고 복원 허용 상태 판별" 시맨틱으로 도입됐다 (`PaymentEventStatus.java` line 29~33 javadoc). 본 토픽 D7 는 이를 "consumer 진입 가드 통과 여부" 시맨틱으로 재사용한다. 두 시맨틱이 우연히 동일한 (READY/IN_PROGRESS/RETRYING) 집합으로 매핑되지만, **본질적으로 다른 도메인 결정**이다.
- 향후 누군가 SCR 시맨틱만 보고 enum 메서드 분기를 변경하면 (예: QUARANTINED 를 보상 대상에 추가) D7 가드도 의도 외로 동작 변경 → QUARANTINED 결제가 markPaymentAsDone 분기로 빠짐 → 정확히 DR-3 회귀.
- mitigation: plan 단계에서 (a) Javadoc 보강 (두 사용처 명시) / (b) 별 메서드 신설 (`isEosGuardProceedable` 등) 중 결정 명시. 산출물 §4 D7 line 388 의 "도메인 enum SSOT 재사용" 결정 자체는 합당하지만 메서드명이 시맨틱 oversharing 위험을 가진다는 점 plan 단계에서 다뤄야. minor.

**(iii) D7 LogFmt.warn 분기의 모니터링 알람 SLO 누락**:
- D7 가드로 QUARANTINED 결제의 늦은 APPROVED 메시지가 LogFmt.warn 1건 + skip 으로 끝난다. **payment_event_dedupe 에도 row 가 박히지 않는다** (가드 통과 skip 으로 markIfAbsent 호출 안 됨).
- 운영자가 Loki 라벨 `event_type=PAYMENT_EOS_GUARD_QUARANTINED_SKIP` 모니터링 알람 SLO 를 설치하지 않으면, QUARANTINED 결제의 자동 회복 가능 여부를 영영 모름. 본 토픽 §11 (L5) 의 머지 직후 24시간 모니터링 SLO 는 `kafka_consumer_records_consumed_total` / `DLQ inbound rate` / `payment_event_dedupe row 증가 추세` 3개만 등재 — D7 분기 알람은 미포함.
- 산출물 §7 Acceptance 도 "Loki 에서 event_type 라벨로 확인" (line 519) 까지만 — 알람 의무는 아님.
- mitigation: plan 단계 acceptance criteria 에 D7 가드 분기 발생 시 알람 신설 명시 권장. 단 본 토픽 verify 단계에서 추가 가능한 영역이라 minor.

### 7. DR-8 minor 미흡수 적정성

- 산출물 미흡수 항목 명시 — "본 토픽 §5 layer 표가 이미 '직전 SCR 폐기한 동명 port 와 시그니처 다름' 명시. plan 단계에서 (a) 동명 재사용 / (b) `PaymentEventDedupeStore` 등 분리 명명 결정 명시 권장."
- 본 라운드 판정: 적정. 동명 재사용 / 분리 명명 결정은 plan 단계 코드 스켈레톤 작성 시점에 자연스럽게 결정 가능. discuss 단계에서 결론을 강제하면 plan 단계 architect 의 코드 결 결정 공간 축소. 본 토픽 §5 line 424 의 명시 ("같은 이름을 재사용해도 OK") 가 default 선택지를 표현 + plan 단계에서 architect 가 변경 가능. acceptable deferred.

## Findings

| ID | Severity | 위치 | 카테고리 | Finding |
|---|---|---|---|---|
| DM2-1 | minor | PAYMENT-EOS-TRANSITION.md:§12 (line 773~828), §7 Acceptance 7번 (line 514) | 운영 회복 / 사람 실수 backstop | §12 단일 PR + 배포 순서 강제 룰이 PR 본문 + verify 단계 체크리스트 + Acceptance 7번 텍스트만으로 강제됨. CI/CD 파이프라인 레벨의 deployment dependency 또는 blocking gate 없음. 운영자가 payment-service 를 product-service 보다 먼저 deploy 하는 사람 실수 시 정확히 DR-4 회귀 시나리오 (spurious 재고 차감) 발생. 학습용 프로젝트 + L5 의 60분 회귀 판정 룰로 사후 catch 가능하나, plan 단계에서 (a) 운영 배포 체크리스트 신설 (verify 단계 산출물) / (b) staging 환경에서 의도적 잘못된 순서 시뮬레이션 테스트 / (c) 본 토픽 범위 밖 backstop deferred 결정 명시 중 선택 권장. |
| DM2-2 | minor | PAYMENT-EOS-TRANSITION.md:§4 D7 (line 388~391), PaymentEventStatus.java line 29~39 | 도메인 시맨틱 oversharing | D7 가드가 `PaymentEventStatus.isCompensatableByFailureHandler()` 를 SSOT 재사용. 이 메서드의 원래 시맨틱은 "보상 핸들러 재고 복원 허용 상태" (SCR 토픽 도입). 본 토픽은 "consumer 진입 가드 통과 여부" 시맨틱으로 재사용. 두 시맨틱이 우연히 동일 (READY/IN_PROGRESS/RETRYING) 매핑이지만, 본질적으로 다른 도메인 결정. 향후 누군가 SCR 시맨틱만 보고 enum 메서드 분기를 변경 (예: QUARANTINED 를 보상 대상 추가) 시 D7 가드도 의도 외 동작 변경 → DR-3 회귀 (QUARANTINED 결제의 markPaymentAsDone 분기). plan 단계에서 (a) javadoc 보강 (두 사용처 명시 + 변경 시 D7 가드 영향 경고) / (b) 별 메서드 신설 (`isEosGuardProceedable` 등 의미 분리) 중 결정 명시 필요. |
| DM2-3 | minor | PAYMENT-EOS-TRANSITION.md:§7 Acceptance (line 519), §11 L5 모니터링 SLO (line 733~736) | 운영 가시성 / 알람 SLO | D7 가드의 QUARANTINED 늦은 APPROVED 분기 (`event_type=PAYMENT_EOS_GUARD_QUARANTINED_SKIP`) 가 LogFmt.warn 1건 + skip 으로 끝남. payment_event_dedupe row 미박힘 (가드 통과 skip). 운영자가 알람 SLO 를 설치하지 않으면 QUARANTINED 결제의 자동 회복 가능 여부 영영 모름. §11 L5 의 머지 직후 24시간 모니터링 SLO 는 `kafka_consumer_records_consumed_total` / DLQ inbound rate / dedupe row 증가 3개만 등재 — D7 분기 알람 미포함. plan 단계 acceptance criteria 에 D7 가드 분기 발생 알람 의무 추가 권장. |

## JSON

```json
{
  "round": 2,
  "persona": "domain-expert",
  "topic": "PAYMENT-EOS-TRANSITION",
  "decision": "pass",
  "gate_results": [
    {"item": "scope - TOPIC UPPER-KEBAB-CASE", "status": "yes", "evidence": "PAYMENT-EOS-TRANSITION 형식 충족"},
    {"item": "scope - 모듈/패키지 경계 명시", "status": "yes", "evidence": "§2 영향 모듈/패키지 (line 109~127) — docker-compose.apps.yml 인지 추가 (DR-2 흡수)"},
    {"item": "scope - non-goals ≥ 1", "status": "yes", "evidence": "§2 Non-goals (line 129~137) 5항목"},
    {"item": "scope - 범위 밖 이슈 TODOS 위임", "status": "yes", "evidence": "§11 후속 작업 목록 (line 754~769) TC-11 / FLYWAY-USER-SEED-GAP / T4-D / 신규 TC-NN (EOS multi-instance hostname)"},
    {"item": "design - hexagonal layer 배치 명시", "status": "yes", "evidence": "§5 layer 배치 표 (line 422~431) — StockEventUuidDeriver 유지 라인 추가 (DR-1 흡수)"},
    {"item": "design - port 인터페이스 위치", "status": "yes", "evidence": "§5 EventDedupeStore=application/port/out + JdbcEventDedupeStore=infrastructure/dedupe"},
    {"item": "design - 상태 전이 다이어그램", "status": "n/a", "evidence": "새 상태 없음"},
    {"item": "design - 전체 결제 흐름 호환성 검토", "status": "yes", "evidence": "§10 (line 620~688) — CONFIRM-FLOW.md 정합 + SCR L7 cascade 빈도 평가 4항목 표 (DR-7 흡수) + pg-service / product-service 영향"},
    {"item": "acceptance - 관찰 가능 성공 조건", "status": "yes", "evidence": "§7 line 502~514 7항목 — multi-product 회귀 가드 + D7 가드 + deploy 순서 의무 추가 (DR-1/3/4 흡수)"},
    {"item": "acceptance - 실패 관찰 방식", "status": "yes", "evidence": "§7 line 516~520 — 테스트 RED / Loki event_type 라벨 / Micrometer counter"},
    {"item": "verification - 테스트 계층 결정", "status": "yes", "evidence": "§8 단위 + 통합 (Testcontainers Kafka + MySQL) 5건 (line 528~566) — #4 multi-product + #5 QUARANTINED 늦은 APPROVED 추가"},
    {"item": "verification - 벤치마크 지표", "status": "n/a", "evidence": "k6 본 토픽 제외 (Phase 5 T4-D)"},
    {"item": "artifact - 결정 사항 섹션", "status": "yes", "evidence": "§4 D1~D8 (D7/D8 신설로 DR-1/3 흡수)"},
    {"item": "domain risk - 멱등성 전략 결정", "status": "yes", "evidence": "§9 멱등성 전략 표 (line 578~585) + D8 두 UUID 분리 + DR-5 race window 명시"},
    {"item": "domain risk - 장애 시나리오 ≥ 3", "status": "yes", "evidence": "§9 시나리오 7개 (a~g) — (f) QUARANTINED 늦은 APPROVED + (g) transactional.id fencing 충돌 추가"},
    {"item": "domain risk - 재시도 정책", "status": "yes", "evidence": "§9 재시도 정책 (line 599~612) — PaymentStatusException 분류 결과 (RuntimeException 직접 상속, 5회 retry 후 DLQ) 명시 + D7 가드 차단 효과"},
    {"item": "domain risk - PII", "status": "yes", "evidence": "§9 PII (line 614~616) — 새 PII 없음"}
  ],
  "fail_items": [],
  "domain_risks": [
    {"id": "DM2-1", "title": "§12 배포 순서 강제 룰의 사람 실수 backstop 부재", "severity": "minor", "evidence": "PAYMENT-EOS-TRANSITION.md:§12 (line 773~828), §7 Acceptance 7번 (line 514). PR 본문 + verify 체크리스트 + Acceptance 텍스트만으로 강제, CI/CD 레벨 gate 없음", "mitigation": "plan 단계에서 (a) 운영 배포 체크리스트 신설 (verify 단계 산출물) / (b) staging 환경 의도적 잘못된 순서 시뮬레이션 테스트 / (c) 본 토픽 범위 밖 backstop deferred 결정 중 선택 명시"},
    {"id": "DM2-2", "title": "D7 `isCompensatableByFailureHandler` SSOT 재사용의 시맨틱 oversharing", "severity": "minor", "evidence": "PAYMENT-EOS-TRANSITION.md:§4 D7 line 388~391 (도메인 enum SSOT 재사용 결정). PaymentEventStatus.java line 29~39 (메서드 javadoc 은 '보상 핸들러 재고 복원 허용 상태' 시맨틱). 본 토픽은 'consumer 진입 가드 통과 여부' 시맨틱으로 재사용 — 두 시맨틱이 우연히 동일 매핑이지만 본질적으로 다른 도메인 결정", "mitigation": "plan 단계에서 (a) javadoc 보강 (두 사용처 명시 + 변경 시 D7 가드 영향 경고) / (b) 별 메서드 신설 (`isEosGuardProceedable` 등) 중 결정"},
    {"id": "DM2-3", "title": "D7 가드 분기 모니터링 알람 SLO 누락", "severity": "minor", "evidence": "PAYMENT-EOS-TRANSITION.md:§7 line 519 (Loki event_type 라벨 확인까지만), §11 L5 line 733~736 (모니터링 SLO 3개 항목에 D7 분기 알람 미포함). D7 가드의 QUARANTINED 늦은 APPROVED 분기는 LogFmt.warn 1건 + skip + payment_event_dedupe row 미박힘 — 알람 없으면 운영자 영영 인지 불가", "mitigation": "plan 단계 acceptance criteria 에 D7 가드 분기 발생 알람 의무 추가 (예: `PAYMENT_EOS_GUARD_QUARANTINED_SKIP` 라벨 발생 시 Loki/Grafana 알람)"}
  ],
  "previous_round_resolution": [
    {"id": "DR-1", "resolved": true, "evidence": "§6 유지 대상 섹션 line 487~496 (StockEventUuidDeriver / StockCommittedEvent / PaymentTopics 보존) + §3 flowchart line 222~225 (StockLoop → DeriveKey → StockSend multi-product loop) + D8 두 UUID 역할 분리 line 393~416 + §7 Acceptance 2번 (multi-product 회귀 가드) + §8 통합 테스트 #4 (productId 2건 + 재배달 검증). 4단 가드 완전 봉쇄."},
    {"id": "DR-2", "resolved": true, "evidence": "§4 D4 line 286~291 표 (단일 OK / 다중 fence 무한 반복) + §2 line 126~127 (영향 모듈 인지 + 수정 안 함) + §9 시나리오 (g) line 597 + §11 L6 line 741~752 (CONCERNS/TODOS 등재 + D4 옵션 a/b 명시) + Eureka instance-id HOSTNAME 통일 결정. 단일 인스턴스 가정으로 본 토픽 범위 제한 + 다중 인스턴스 운영 인지 경로 확보. acceptable deferred."},
    {"id": "DR-3", "resolved": true, "evidence": "§4 D7 line 355~391 (가드 정책 = isCompensatableByFailureHandler 부재 사용) + 기각 대안 A/B/C 명시 + PaymentStatusException 분류 결과 (RuntimeException 직접 상속, IllegalStateException 아님 — 5회 retry 후 DLQ) + §3 flowchart GuardCheck 박스 + §8 통합 테스트 #5 (DLQ 0 + dedupe row 0 + warn 1 검증) + §9 시나리오 (f). 소스 교차검증: PaymentStatusException.java line 7 (RuntimeException 상속) + KafkaErrorHandlerConfig not-retryable list 미매칭 + PaymentEventStatus.java line 34~39 (READY/IN_PROGRESS/RETRYING → true) 모두 산출물 클레임과 일치."},
    {"id": "DR-4", "resolved": true, "evidence": "§12 신설 line 773~828 (잘못된 순서 t0/t1 windows 표 + 배포 순서 룰 + Mermaid + actuator /env 확인 + 단일 PR 채택 이유 vs D6 분리 대안 비교) + §7 Acceptance 7번 line 514 + §8 통합 테스트 #2 (EOS abort + read_committed invisibility 검증). 다만 PR 본문 텍스트만으로 강제되는 부분은 DM2-1 으로 minor 등재."},
    {"id": "DR-5", "resolved": true, "evidence": "§9 멱등성 전략 표 line 584 — partition rebalance 짧은 윈도우 시나리오 + dedupe row 박혔지만 자기전이 no-op + TC-11 cleanup SLO 잡음 고려 명시"},
    {"id": "DR-6", "resolved": true, "evidence": "§11 L5 line 722~739 — Flyway down migration 부재 + 17 단위 동시 revert 필요 + outbox 큐 회복 결 소실 + 머지 직후 24시간 모니터링 SLO 3개 항목 + 60분 회귀 판정 룰"},
    {"id": "DR-7", "resolved": true, "evidence": "§10 line 660~674 — 4 항목 평가표 (broker / RDB lock / Redis 보상 / 도메인 예외) + 총평 (broker 불안정 marginal 증가 vs D7 가드 도메인 예외 감소 → net 무변에 가까운 marginal 증가, 운영 SLO 변경 불필요). 평가 합리적 — KRaft 단일 broker 광역 장애 인지 + D7 가드 효과 정합"},
    {"id": "DR-8", "resolved": false, "evidence": "산출물에서 의도적 미흡수 — 본 토픽 §5 line 424 의 '동명 재사용 OK + 시그니처 다름' 명시가 default 선택지. plan 단계 코드 스켈레톤 작성 시점에 architect 가 (a) 동명 재사용 / (b) PaymentEventDedupeStore 분리 결정 명시 권장. discuss 단계 결론 강제 시 plan 단계 결 결정 공간 축소 — acceptable deferred"}
  ],
  "auxiliary_scores": {
    "structure_clarity": 0.95,
    "wiki_code_alignment_explicit": 0.95,
    "rollback_path_visibility": 0.7,
    "downstream_impact_coverage": 0.9,
    "risk_enumeration_completeness": 0.9,
    "previous_round_absorption_completeness": 0.95,
    "new_risks_introduced": 0.85
  }
}
```
