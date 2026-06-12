# discuss-domain-1

**Topic**: CLEANUP-BATCH-A
**Round**: 1
**Persona**: Domain Expert

## Reasoning

본 토픽은 코드 청소 4건이라 자금 / 멱등성 / race 의 **직접** 위협은 작다. 다만 (a) §1.2 builder 전환이 PG-CONFIRM-LISTENER-SPLIT 봉인 시 추가된 5상태 + 보정 경로 직접 신설 시나리오의 가드 surface 를 흔드는지, (b) §1.4 의 503 일괄 매핑이 vendor 가 준 429(rate-limit) 시그널을 누락하면서 클라이언트 backoff 정책에 cascade 영향이 있는지가 도메인 관점의 실질 리스크다. 코드 교차 검증 결과 **현재 main 호출 그래프상 createDirectTerminal 의 isTerminal() 가드는 이미 활성 보호 경로가 아니며** (`PgInboxRepositoryImpl.transitDirectToTerminal` 이 `PgInbox.of(...)` 7-arg 로 우회 + 어댑터 자체 가드로 이중화), 산출물 §1.2 표가 이 사실을 명시하지 않은 채 "builder 전환 후 가드 보존" 으로 적은 점이 부정확하다. 다만 도메인 정합성 자체는 어댑터 가드로 유지되므로 critical 은 아니다. 묶음 전체로 보면 `decision = revise` — major 1건 (산출물 §1.2 의 가드 인벤토리 정확성), minor 2건 (§1.4 의 429 시그널 누락 후속 트리거, §1.3 Flyway missing-migration 운영 가시화).

## Domain risk checklist (체크리스트 + 추가 점검)

### 표준 항목 (discuss-ready.md "domain risk" 섹션)

- [x] **멱등성 전략이 결정됨** — n/a (본 토픽은 신규 멱등성 도입 없음). §1.3 `INSERT IGNORE` 의 기존 멱등성이 §1.4 정책 결정과 무관함이 §7.4 에 명시됨
- [x] **장애 시나리오 최소 3개 식별됨** — §4 에 3건 (Flyway missing migration / 무한 retry-after / builder 가드 우회) 명시
- [x] **재시도 정책이 정의됨** — §1.4 의 `Retry-After: 5` 고정값 + §7.1 trade-off (vendor 가 준 Retry-After 미전파 / jitter 미적용) 명시. 본 토픽 안에서는 고정값 결정
- [x] **PII / 민감정보 검토됨** — n/a (신규 PII 도입 0. seed SQL 의 더미 user / product 는 기존 학습용 픽스처)

### 추가 도메인 점검

- [ ] **§1.2 builder 전환 후 가드 인벤토리 정확성** — *partial*. 산출물 §1.2 의 "시나리오 의도 보존 표" 가 `createDirectTerminal` 의 `isTerminal()` 가드를 main 보호 경로로 표기하지만, 실제 main 은 `PgInboxRepositoryImpl.transitDirectToTerminal` 이 `PgInbox.of(...)` 7-arg 로 직접 호출 (PgInboxRepositoryImpl.java:155) 하여 도메인 가드 우회. 가드 유효성은 어댑터 가드 (`if (!terminalStatus.isTerminal()) throw ...`, PgInboxRepositoryImpl.java:150) 가 보완. 즉 도메인 정합성은 깨지지 않지만 산출물의 가드 위치 기술이 부정확
- [ ] **§1.2 PgInbox.create 4 오버로드의 실제 호출 영역** — *partial*. 산출물 사전 브리핑이 `PgConfirmService.handleAbsent → PgInbox.createPending(null, ...)` 호출처를 다이어그램에 그렸지만, 실제 main 은 `PgInboxPendingService.insertPendingAndPublish` 가 `PgInboxRepository.insertPending(...)` 네이티브 INSERT 로 가서 PgInbox 도메인 객체 자체를 만들지 않음. PgInbox.create 4 오버로드는 main 호출처 0건, test 픽스처 전용. builder 전환 영향이 더 좁음을 산출물에 반영 필요 (위험 축소 방향)
- [x] **§1.4 503 일괄 매핑의 클라이언트 backoff 정책 영향** — 식별됨. §7.1 에 "ErrorDecoder 단계에서 이미 단일 예외 통합" + "별 토픽" 로 분리 명시. 단 본 라운드에서 후속 토픽 책임을 `docs/context/TODOS.md` 에 항목화하라는 위임이 명시 안 됨 — minor
- [x] **§1.3 Flyway missing-migration 운영 위험** — 식별됨. §4.1 + §7.4 에서 "신규 DB 부팅 기준" 명시. 단 STACK.md 운영 가이드 항목 명시는 verify 단계로 미뤘는데, 미루는 자체보다 **본 토픽 적용 후 docker-compose 가 이미 V2 적용된 볼륨을 재사용하는 경로** (예: `mysql-product` named volume 재사용) 가 정말 학습용 시나리오에서 0인지 *명시적* 확인 필요. 운영 누적 DB 가 없다 가정만으로 minor
- [x] **§1.4 의 도메인 에러 코드 `PRODUCT_SERVICE_UNAVAILABLE` (E03031) / `USER_SERVICE_UNAVAILABLE` (E03032) 가 이미 존재하는가** — Path 1 확인됨 (`ProductFeignConfig.java:57` / `UserFeignConfig.java:57` 에서 이미 사용 중). 신규 에러 코드 도입 없음
- [x] **§1.4 의 핸들러 추가 위치 (`PaymentExceptionHandler` vs `GlobalExceptionHandler`)** — Path 1 검증. `PaymentExceptionHandler` 는 `@Order(HIGHEST_PRECEDENCE)`, `GlobalExceptionHandler.catchRuntimeException` 은 default. Spring 의 `@Order` 기반 advice resolution 으로 좁은 예외 타입을 PaymentExceptionHandler 가 먼저 매칭 — 산출물 결정 정합
- [x] **§1.2 의 dead parameter id 제거 후 RDB AUTO_INCREMENT 정합** — Path 1. `pg_outbox` 스키마가 `id BIGINT AUTO_INCREMENT PRIMARY KEY` 인지 확인 필요했으나 산출물 §1.2 의 "id 컬럼은 RDB AUTO_INCREMENT 가 INSERT 시 채움" 결정이 일관됨. JPA `@GeneratedValue(strategy=IDENTITY)` 의 표준 흐름
- [x] **§1.2 builder 의 mutable 필드 (status / storedStatusResult / reasonCode / updatedAt / attempt / processedAt)** — `PaymentOutbox` 참조 패턴이 `@Getter @AllArgsConstructor(PRIVATE)` 로 mutable 필드를 그대로 다루는데, `PgInbox` 도 `markInProgress` / `markApproved` 등 도메인 메서드가 내부 필드를 mutate. builder 자체는 mutation 안 함 — 빌더 호출 후 상태 전이는 도메인 메서드 경로 그대로. 정합. 단 `@Builder` 가 setter 노출하지 않는지 확인 필요 (default 는 안 함, OK)

## 도메인 관점 추가 검토

### D1. §1.2 — `createDirectTerminal` 의 main 활성 가드 surface 가 산출물 기술과 다름

**근거**: 산출물 §1.2 "시나리오 의도 보존" 표 (line 159~166) 의 `createDirectTerminal` 행은 "`!terminalStatus.isTerminal()` 입력 시 `IllegalArgumentException` 가드 유지 — builder 내부가 아닌 factory 시그니처 앞단에서 검증" 으로 적었다. 그러나 코드 검증 결과 (`pg-service/src/main/java/com/hyoguoo/paymentplatform/pg/infrastructure/repository/PgInboxRepositoryImpl.java:148-155`):

```java
public Long transitDirectToTerminal(String orderId, long amount, PgInboxStatus terminalStatus,
                                    String storedStatusResult, String reasonCode) {
    if (!terminalStatus.isTerminal()) {                          // ← 가드 1: 어댑터
        throw new IllegalArgumentException(...);
    }
    java.time.Instant now = clock.instant();
    PgInbox inbox = PgInbox.of(orderId, terminalStatus, amount,  // ← createDirectTerminal 우회
            storedStatusResult, reasonCode, now, now);
    return jpaPgInboxRepository.save(PgInboxEntity.from(inbox)).getId();
}
```

즉 **main 에서 `createDirectTerminal` 의 도메인 가드는 사용되지 않고**, 어댑터의 별도 `isTerminal()` 가드가 보호 책임을 진다. 산출물이 "builder 전환 후 도메인 factory 가드 보존" 만으로 main 보호 충분하다고 읽힐 수 있어 부정확.

**도메인 영향**: 정합성 자체는 어댑터 가드로 유지됨. 단 builder 외부 노출 후 만약 어댑터에서 도메인 `createDirectTerminal` 로 통합 정리하는 추가 리팩토링이 들어가면 (현재 산출물이 reasonCode 파라미터 부재 때문에 어댑터가 of() 호출하는 이유 명시) 가드 단일화 시점을 잘못 잡으면 양쪽 다 빠지는 race window 가능.

**Severity**: major. 도메인 가드 surface 인벤토리가 부정확하면 후속 라운드/플랜에서 잘못된 전제로 영향 분석.

---

### D2. §1.2 — `PgInbox.create` 4 오버로드의 실제 main 사용도 0건 — builder 전환 영향이 더 좁음

**근거**: 산출물 사전 브리핑 다이어그램 (line 41~51) 이 `PgConfirmService.handleAbsent → PgInbox.createPending(null, ...)` / `DuplicateApprovalHandler.handleDbAbsent* → PgInbox.of(null, ...)` 호출을 그렸지만 실제 main 검증 (`grep PgInbox.create pg-service/src/main`):

- `PgConfirmService.processCommand` (line 97~104) 은 `PgInboxPendingService.insertPendingAndPublish(...)` 호출
- `PgInboxPendingService.insertPendingAndPublish` (line 79~97) 은 `pgInboxRepository.insertPending(orderId, amount, eventUuid, vendorType, paymentKey)` — **PgInbox 도메인 객체 안 만듦**
- `PgInboxRepositoryImpl.insertPending` 은 native JPA INSERT (id 반환). PgInbox.create 4 오버로드는 main 에서 호출 안 됨

즉 `PgInbox.create` 의 **4 오버로드 전체가 main 호출처 0건**, 테스트 픽스처 전용이다. builder 전환 시 정상 경로 PENDING 신설 시나리오의 main behavior 가 도메인 객체 경로를 안 거치므로 영향 더 좁음.

**도메인 영향**: 본 토픽의 위험을 **더 낮추는** 방향. 산출물의 §3 인벤토리 / 사전 브리핑 다이어그램이 호출 그래프를 부정확하게 그려서 plan 단계 영향 분석 / 리뷰 우선순위 조정이 잘못될 수 있음.

**Severity**: minor. 실제 도메인 정합성 위협 0, 산출물 정확성 문제.

---

### D3. §1.4 — 429 시그널 누락의 후속 책임이 `docs/context/TODOS.md` 위임 명시 없음

**근거**: 산출물 §7.1 (line 437) 이 ErrorDecoder 의 429/503 통합 매핑 정보 손실 구조를 "별 토픽" 으로 분리한다고 명시했고 §0 비범위 #1 (line 112) 에도 명시됐다. 그러나 본 토픽 verify 단계에서 `docs/context/TODOS.md` 에 후속 항목으로 등재한다는 결정이 산출물 §3 의 "context 문서 갱신" 인벤토리에 안 보임 (TODOS 갱신은 [PR A] 태그 4항목 제거만 명시).

**도메인 영향**: 클라이언트 입장에서 vendor 가 준 429 (rate-limit, backoff 권장) 가 503 (서비스 불가, 즉시 재시도) 으로 cascade 매핑되면 retry storm 가능. 본 토픽이 변경하기 전에도 똑같이 정보 손실이지만 본 토픽이 503 일괄로 더 명시화하면서 "429 정보 손실은 의도된 trade-off" 결정이 코드에 박힘. 후속 토픽 트리거가 명시 안 되면 부채 시야에서 사라질 위험.

**Severity**: minor. 후속 토픽 등재가 본 토픽 외 디스커버리 메커니즘 (i.e., 다른 라운드의 TODOS 점검) 으로 잡힐 수 있어 critical 아님.

---

### D4. §1.3 — Flyway missing-migration 운영 가시화 트리거 명시 부재

**근거**: 산출물 §4.1 (line 372~380) 가 "운영 누적 DB 가 도입되는 시점" 에 missing-migration 대응 정책 결정한다 명시. 단 본 토픽 적용 직후 docker-compose 가 이미 V2 적용 상태의 named volume 을 재사용하는 케이스 (개발자 로컬에서 `docker volume prune` 안 하고 reset 안 한 경우) 가 시나리오 0 이라 단정한 근거가 "학습용이라 운영 누적 DB 없음" 만이다. `mysql-product` 컨테이너의 named volume 이 docker-compose down 만으로 살아남는 표준 설정 (`docker/docker-compose.infra.yml`) 인지 plan 단계에서 1회 확인 권고.

**도메인 영향**: 미리 V2 가 박힌 DB 에서 본 토픽 적용 후 부팅 → Flyway 가 `schema_history` 의 V2 record 를 보고 `db/schema/` 에 V2 가 없다고 "MissingMigrationException" → 부팅 실패. 학습용이라 데이터 손실 0, 단 학습자 디버깅 비용. STACK.md 운영 가이드 갱신 + `spring.flyway.ignore-migration-patterns` 의 default 값 확인이 verify 인벤토리에 있어야 함.

**Severity**: minor. 학습용 + 신규 DB 가정이 대부분 케이스 보호.

---

### D5. §1.2 — dead parameter id 제거 후 `PgOutbox.of` 만 풀 컨스트럭터 보존 결정의 도메인 일관성

**근거**: 산출물 §1.2 (line 177) "create / createWithAvailableAt 의 `Long id` 인자 제거, `of(...)` 풀 컨스트럭터 유지" 결정. test 호출처 `DuplicateApprovalHandlerTest:300` (`PgOutbox.create(99L, ...)`) 만 `PgOutbox.of(99L, ..., now, null, 0, now)` 로 교체. 도메인 관점에서 `of` 는 RDB row 복원 + 테스트 픽스처 용이라 id 가 필수, `create` / `createWithAvailableAt` 는 새 row 신설이라 id 가 무의미 — 의도 분리가 일관.

**도메인 영향**: 변경 후 `PgOutbox` 의 factory 시그니처 의도가 더 명확. test 의 `PgOutbox.create(99L, ...)` 가 사실 dead parameter 사용으로 의미 없는 테스트 setup 이었다는 사실이 가시화됨 → 교체 후 의도 명확 (id 가 의미 있다는 신호).

**Severity**: 없음 — 본 finding 은 산출물의 정합성을 확인만 했고 결정 자체가 도메인 의도와 일치.

## Findings

1. **D1 (major)** — §1.2 `createDirectTerminal` 의 main 활성 가드 위치가 산출물 기술과 다름 (어댑터 가드가 실제 보호 surface)
2. **D2 (minor)** — §1.2 `PgInbox.create` 4 오버로드의 main 호출처 0건 사실이 산출물 영향 분석에 반영 안 됨
3. **D3 (minor)** — §1.4 429 시그널 누락의 후속 토픽 트리거가 `docs/context/TODOS.md` 등재 결정 부재
4. **D4 (minor)** — §1.3 Flyway missing-migration 대비 docker-compose 볼륨 재사용 시나리오 확인 부재

major 1건만 있고 critical 없음 — 판정 규칙상 `revise`.

## JSON

```json
{
  "stage": "discuss",
  "persona": "domain-expert",
  "round": 1,
  "task_id": null,

  "decision": "revise",
  "reason_summary": "도메인 정합성 자체는 깨지지 않으나 §1.2 `createDirectTerminal` 가드 surface 의 main 실제 위치가 산출물 기술과 다르고 (어댑터가 실제 보호), `PgInbox.create` 4 오버로드 main 호출처 0건이 영향 분석에 반영 안 됨. major 1건 + minor 3건으로 revise.",

  "checklist": {
    "source": "_shared/checklists/discuss-ready.md",
    "items": [
      {
        "section": "domain risk",
        "item": "멱등성 전략이 결정됨",
        "status": "n/a",
        "evidence": "본 토픽은 신규 멱등성 도입 없음. §1.3 `INSERT IGNORE` 의 기존 멱등성은 §7.4 에 명시"
      },
      {
        "section": "domain risk",
        "item": "장애 시나리오 최소 3개 식별됨",
        "status": "yes",
        "evidence": "docs/topics/CLEANUP-BATCH-A.md §4 (Flyway missing migration / 무한 retry-after / builder 가드 우회) 3건"
      },
      {
        "section": "domain risk",
        "item": "재시도 정책이 정의됨",
        "status": "yes",
        "evidence": "§1.4 `Retry-After: 5` 고정값 결정 + §7.1 비범위 명시"
      },
      {
        "section": "domain risk",
        "item": "PII / 민감정보 검토됨",
        "status": "n/a",
        "evidence": "신규 PII 도입 0. seed SQL 의 더미 user / product 만"
      },
      {
        "section": "design decisions",
        "item": "§1.2 builder 전환 후 가드 surface 인벤토리 정확성",
        "status": "no",
        "evidence": "산출물 §1.2 표가 createDirectTerminal 의 isTerminal() 가드를 main 보호로 적었으나 실제로는 PgInboxRepositoryImpl.transitDirectToTerminal:150 의 어댑터 가드가 활성 (PgInbox.of(...) 우회). PgInboxRepositoryImpl.java:148-155"
      },
      {
        "section": "design decisions",
        "item": "§1.2 PgInbox.create 4 오버로드의 main 호출처 인벤토리 정확성",
        "status": "no",
        "evidence": "산출물 사전 브리핑 다이어그램이 main 호출을 그렸지만 PgInboxPendingService.insertPendingAndPublish (line 79~97) 가 insertPending 네이티브 INSERT 로 가서 PgInbox 도메인 객체 안 만듦. main 호출처 0건"
      },
      {
        "section": "scope",
        "item": "§1.4 429 시그널 누락 후속 토픽 트리거가 TODOS.md 위임",
        "status": "no",
        "evidence": "산출물 §7.1 / §0 비범위 #1 에 별 토픽 명시 있으나 §3 'context 문서 갱신' 인벤토리에 TODOS.md 후속 등재 결정 부재"
      },
      {
        "section": "design decisions",
        "item": "§1.3 docker-compose 볼륨 재사용 missing-migration 시나리오 확인",
        "status": "no",
        "evidence": "§4.1 / §7.2 에서 '신규 DB 부팅 기준' 보장 명시되나 mysql-product named volume 재사용 케이스 (docker-compose down 후 V2 박힌 볼륨 잔존) 확인이 plan 인벤토리에 없음"
      }
    ],
    "total": 8,
    "passed": 4,
    "failed": 4,
    "not_applicable": 2
  },

  "scores": {
    "clarity": 0.78,
    "completeness": 0.72,
    "risk": 0.85,
    "testability": 0.82,
    "fit": 0.88,
    "mean": 0.81
  },

  "findings": [
    {
      "severity": "major",
      "checklist_item": "§1.2 builder 전환 후 가드 surface 인벤토리 정확성",
      "location": "docs/topics/CLEANUP-BATCH-A.md §1.2 line 159~166 (시나리오 의도 보존 표)",
      "problem": "표가 createDirectTerminal 의 isTerminal() 가드를 main 보호 surface 로 표기하지만, 실제 main 은 PgInboxRepositoryImpl.transitDirectToTerminal (line 148~155) 이 PgInbox.of(...) 7-arg 직접 호출로 도메인 가드를 우회하고 어댑터에 동일 가드를 두 번째로 박아 보호. builder 전환이 도메인 가드 영향만 보면 main 안전이라는 결론이 부정확.",
      "evidence": "pg-service/src/main/java/com/hyoguoo/paymentplatform/pg/infrastructure/repository/PgInboxRepositoryImpl.java:148-155 에 어댑터 자체 isTerminal() 가드 + PgInbox.of(...) 호출. createDirectTerminal main 호출처 0건",
      "suggestion": "산출물 §1.2 표에 'main 실제 보호 surface = PgInboxRepositoryImpl.transitDirectToTerminal 의 어댑터 가드, createDirectTerminal 의 도메인 가드는 의도된 이중화 (직접 호출자 부재)' 명시. 또는 plan 단계에서 reasonCode 지원을 createDirectTerminal 시그니처에 추가해 어댑터의 PgInbox.of 우회를 제거하고 도메인 가드 단일화 결정."
    },
    {
      "severity": "minor",
      "checklist_item": "§1.2 PgInbox.create 4 오버로드 main 호출처 인벤토리",
      "location": "docs/topics/CLEANUP-BATCH-A.md 사전 브리핑 line 41~51 다이어그램 + §3 인벤토리",
      "problem": "다이어그램이 PgConfirmService / DuplicateApprovalHandler 의 PgInbox.create / PgInbox.of(null, ...) 호출을 그렸으나 실제 main 호출 그래프는 PgInboxPendingService.insertPendingAndPublish → pgInboxRepository.insertPending(...) 네이티브 INSERT 로 가서 PgInbox 도메인 객체를 만들지 않음. PgInbox.create 4 오버로드 전체가 main 호출처 0건.",
      "evidence": "pg-service/src/main 안 'PgInbox.create(' 검색 결과 0건 (test 만). PgInboxPendingService.java:79-97 + PgInboxRepositoryImpl.insertPending native INSERT 경로 확인",
      "suggestion": "사전 브리핑 다이어그램 + §3 인벤토리를 실제 호출 그래프로 보정. 'PgInbox.create 4 오버로드는 test 픽스처 전용, main 영향 0' 표기. 본 토픽 위험을 더 낮추는 방향의 정정."
    },
    {
      "severity": "minor",
      "checklist_item": "§1.4 429 시그널 누락 후속 토픽 트리거",
      "location": "docs/topics/CLEANUP-BATCH-A.md §3 context 문서 갱신 + §7.1",
      "problem": "ErrorDecoder 의 429/503 통합 매핑 정보 손실을 별 토픽으로 분리 명시했지만, 본 토픽 verify 단계에서 docs/context/TODOS.md 에 후속 항목으로 등재하는 결정이 §3 인벤토리에 안 보임. 부채가 시야에서 사라질 위험.",
      "evidence": "산출물 §3 (line 360~364) context 문서 갱신 목록에 TODOS [PR A] 4항목 제거만 있고 후속 토픽 (TC-5 확장) 등재 결정 부재",
      "suggestion": "§3 context 문서 갱신에 'docs/context/TODOS.md — [PR A] 4항목 제거 + TC-5 후속 (ErrorDecoder 429/503 분기 보존) 신규 항목 등재' 명시. plan 단계 verify 인벤토리에 포함."
    },
    {
      "severity": "minor",
      "checklist_item": "§1.3 docker-compose 볼륨 재사용 missing-migration",
      "location": "docs/topics/CLEANUP-BATCH-A.md §4.1 + §7.2",
      "problem": "본 토픽 적용 후 docker-compose 가 이미 V2 가 박힌 mysql-product / mysql-user named volume 을 재사용하면 Flyway 가 schema_history 의 V2 record 와 db/schema 의 V2 부재 차이로 부팅 실패 (학습자 디버깅 비용). '신규 DB 부팅 기준' 보장만 명시되고 named volume 재사용 케이스 확인 부재.",
      "evidence": "docker/docker-compose.infra.yml 의 mysql-product / mysql-user 볼륨 설정이 plan 인벤토리에 확인 항목 없음. 산출물 §4.1 line 376 '운영 누적 DB 가 도입되는 시점' 만 명시",
      "suggestion": "plan 단계에서 mysql-product / mysql-user 의 named volume 정의 + STACK.md 운영 가이드 갱신 항목에 'db/schema / db/seed 분리 적용 시 기존 V2 적용 DB 는 schema_history 의 V2 row 제거 또는 spring.flyway.ignore-migration-patterns 적용 후 재기동' 가이드 추가."
    }
  ],

  "previous_round_ref": null,
  "delta": {
    "newly_passed": [],
    "newly_failed": [],
    "still_failing": []
  },

  "unstuck_suggestion": null
}
```
