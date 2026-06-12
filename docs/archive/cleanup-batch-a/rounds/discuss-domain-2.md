# discuss-domain-2

**Topic**: CLEANUP-BATCH-A
**Round**: 2
**Persona**: Domain Expert

## Reasoning

Round 1 finding 4건 (D1 major / D2~D4 minor) 모두 Round 2 산출물에 정확히 흡수됐다. 핵심 회귀 위험 — §1.2 가드 surface 인벤토리 — 가 산출물에서 어댑터 가드 (`PgInboxRepositoryImpl.transitDirectToTerminal:150`) 와 도메인 factory 가드의 분리 + builder 전환 시 어댑터 가드 보존이 비범위 명시까지 들어가 도메인 정합성 위험이 해소됐다. D2 의 `PgInbox.create` 4 오버로드 main 호출처 0건 사실은 표 1행 ("main 실제 보호 surface = main 호출처 0건 — test 픽스처 전용") + Round 2 흡수 노트 2번에 명시. D3 후속 토픽 트리거는 §3 인벤토리에 "신규 등재 (D3 흡수)" 로 정식 항목화. D4 named volume 재사용 시나리오는 §2 acceptance / §4.1 / §3 STACK.md 갱신 인벤토리 3 지점에 분산 배치돼 verify 단계 누락 위험 해소. 새 critical / major 0 — pass.

## Domain risk checklist (Round 2 재검토)

### 표준 항목 (discuss-ready.md "domain risk" 섹션)

- [x] **멱등성 전략이 결정됨** — n/a 유지. §7.4 `INSERT IGNORE` 정합 명시 유지
- [x] **장애 시나리오 최소 3개 식별됨** — §4 의 3건 + Round 2 흡수로 4.1 의 named volume 재사용 sub-case 명시
- [x] **재시도 정책이 정의됨** — §1.4 `Retry-After: 5` 고정값 + §7.1 후속 토픽 등재 결정 추가
- [x] **PII / 민감정보 검토됨** — n/a 유지

### 추가 도메인 점검 (Round 1 finding 흡수 검증)

- [x] **D1 흡수: §1.2 가드 surface 표 정확성** — *resolved*. §1.2 표가 "main 실제 보호 surface" 컬럼을 추가 (line 164~170) 하고 Round 2 흡수 노트 1번 (line 159~161) 에서 `transitDirectToTerminal:150` 어댑터 가드를 main 활성 보호로, 도메인 factory 가드를 테스트 / 직접 호출자 이중화로 분리 명시. 어댑터 가드는 본 토픽 비범위로 보존 결정. builder 전환의 영향 범위가 main behavior 에 직접 닿지 않음을 정확히 기술
- [x] **D2 흡수: `PgInbox.create` 4 오버로드 main 호출처 인벤토리** — *resolved*. 표 1행 (line 166) 이 "main 호출처 0건 — test 픽스처 전용. main 정상 경로는 `insertPending(...)` 네이티브 INSERT 사용" 으로 정정. Round 2 흡수 노트 2번 (line 162) 이 `PgConfirmService.processCommand → PgInboxPendingService.insertPendingAndPublish → PgInboxRepository.insertPending` 네이티브 경로 명시로 사전 브리핑 다이어그램 정정. builder 전환 main 영향 범위가 더 좁다는 결론까지 도출됨
- [x] **D3 흡수: 429 시그널 후속 토픽 TODOS.md 등재 결정** — *resolved*. §3 (line 369) 의 "context 문서 갱신" 섹션에 "신규 등재 (D3 흡수): `Feign ErrorDecoder 429/503 분기 보존`" 항목화 + §7.1 (line 453) 의 "후속 등재 (D3 흡수)" 노트로 [PR A] 4항목 제거와 동일 커밋에 묶는 결정 명시. verify 단계 누락 위험 해소
- [x] **D4 흡수: named volume 재사용 시나리오 가시화** — *resolved*. 3 지점 분산 배치 확인 — (a) §2 acceptance (line 327) 의 새 행 "§1.3 (named volume 재사용 — D4 흡수)" 가 plan 단계 1회 확인 + STACK.md 등재 acceptance 신호; (b) §3 (line 371) STACK.md 갱신 인벤토리에 "named volume 재사용 시 missing-migration 대응 가이드" 명시; (c) §4.1 (line 380~393) 의 "현재 환경 영향 (재평가, D4 흡수)" 단락 + "plan 단계 확인 (D4 흡수)" + "verify 단계 STACK.md 갱신 3-step 가이드" 까지 디버깅 비용 차단 경로 구체화. 학습자 디버깅 비용 minor 가 운영 가이드 + 사전 plan 확인으로 차단

### Round 2 신규 점검 (회귀 / 부정합 확인)

- [x] **PG-CONFIRM-LISTENER-SPLIT 봉인 정합성 유지** — *yes*. 5상태 (PENDING / IN_PROGRESS / APPROVED / FAILED / QUARANTINED) + factory 4종 (`create` 4 오버로드 / `createDirectInProgress` / `createDirectTerminal` / `of` 2 오버로드 + `ofWithId`) + 보정 경로 PENDING 우회 룰 (`transitDirectToInProgress` / `transitDirectToTerminal`) 모두 §1.2 본문 + 표에서 시그니처 동일 유지로 명시. m1 봉인의 보정 경로 신설 의도 보존
- [x] **D1 흡수 후 reasonCode 파라미터 부재 사실 일관성** — *yes*. §1.2 표 row 3 "`createDirectTerminal` ... 어댑터가 `PgInbox.of(...)` 7-arg 직접 호출로 도메인 factory 우회 (reasonCode 파라미터 부재). 본 토픽은 어댑터 가드 그대로 보존" 명시. plan 단계에서 reasonCode 통합 리팩토링이 본 토픽 범위 밖임을 비범위 #4 ("PgInbox factory 추가 / 변경") 와 정합
- [x] **D2 흡수 후 §3 인벤토리의 main 변경 파일 카운트 일관성** — *yes*. §1.2 변경 파일 인벤토리 (§3, line 343~349) 가 main 8건 + test 1건 = 9건으로 PgInbox.create 4 오버로드 main 호출처 0건 결론과 정합. main 영향은 createDirectInProgress / of / ofWithId + PgOutbox null id 제거 + Entity#toDomain 만으로 좁아짐
- [x] **D4 흡수 후 STACK.md 운영 가이드 3-step 자체 정합성** — *yes*. §4.1 의 3-step 가이드 (a) `docker volume prune` / (b) `DELETE FROM flyway_schema_history WHERE version = '2';` / (c) `spring.flyway.ignore-migration-patterns: "*:missing"` 일시 적용. 학습용 환경에서 가장 안전한 (a) 가 디폴트로 추천되며 운영 누적 케이스 (b/c) 도 명시. 학습자 가시화에 충분
- [x] **신규 critical / major finding 부재** — *yes*. Round 1 4 findings 가 산출물에 정확 반영되고 새 도메인 위험 노출 없음

## 도메인 관점 추가 검토

본 라운드는 Round 1 흡수 검증이 본진이며, Round 2 산출물에서 새로 발생한 도메인 리스크 finding 은 없다. 주요 검증:

### V1. §1.2 builder 전환의 main behavior 영향 범위가 Round 2 산출물에서 정확히 좁혀짐

산출물 §1.2 표 (line 164~170) + Round 2 흡수 노트가 builder 전환의 main 영향을 다음 3개 시그니처에만 한정:
1. `createDirectInProgress(orderId, amount)` — `PgInboxRepositoryImpl.transitDirectToInProgress` 1 호출처
2. `PgInbox.of(...)` (7-arg terminal) — `PgInboxRepositoryImpl.transitDirectToTerminal` + `PgInboxEntity#toDomain` 2 호출처
3. `ofWithId(...)` — `PgInboxEntity#toDomain` 1 호출처

`create` 4 오버로드 + 사전 브리핑 다이어그램의 `createPending` 표기는 test 픽스처 전용. main 정상 경로 PENDING 신설은 `PgInboxPendingService.insertPendingAndPublish → insertPending(orderId, amount, eventUuid, vendorType, paymentKey)` 네이티브 INSERT 로 도메인 객체를 거치지 않음. 도메인 정합성 위협 0 — pass.

### V2. §1.4 503 일괄 매핑 + 429 후속 분리 결정의 결제 도메인 영향

산출물 §7.1 (line 451~453) + §3 (line 369) 의 후속 등재 결정이 **클라이언트 retry storm 위험을 본 토픽 외부로 위임** 한다. PG 도메인 관점에서:
- 본 토픽 변경 후 클라이언트는 503 + `Retry-After: 5` 만 받음 — 429 (rate-limit) 케이스에서도 vendor 가 권장하는 backoff 가 일괄 5초로 통일됨
- 학습용 환경 (브라우저 fetch 단일 클라이언트) 에서 retry storm 직접 영향 0
- 후속 토픽 (Feign ErrorDecoder 분기) 트리거가 TODOS.md 에 명시 등재되어 부채 시야에서 사라질 위험 없음 — D3 흡수가 본 risk 의 후속 처리 책임을 명확히 함

본 토픽 안에서 결제 도메인 리스크 미해소 항목 없음 — pass.

### V3. §1.3 Flyway 분리 결정의 운영 DB 손상 가능성 재평가

§4.1 의 named volume 재사용 시나리오는 **학습자 디버깅 비용** 이지 **결제 데이터 손상** 위험이 아니다. 이유:
- `mysql-product-data` / `mysql-user-data` 볼륨은 product / user 도메인만 보유 — PG inbox / outbox / payment 도메인 DB 와 물리 분리 (별 컨테이너)
- V2 seed 가 부팅 실패해도 dropping 0 — Flyway 가 schema_history 일관성 검사로 부팅 거부만 함
- 결제 트랜잭션 무결성 / 멱등성 / 보정 경로에 cascade 영향 없음

D4 흡수의 STACK.md 3-step 가이드가 학습자 디버깅 비용을 차단하면 도메인 영향 0 — pass.

## Findings

새 critical / major / minor finding 없음. Round 1 finding 4건 모두 resolved.

## JSON

```json
{
  "stage": "discuss",
  "persona": "domain-expert",
  "round": 2,
  "task_id": null,

  "decision": "pass",
  "reason_summary": "Round 1 finding 4건 (major 1 + minor 3) 모두 산출물에 정확히 흡수됨. §1.2 가드 surface 표가 어댑터 가드(`PgInboxRepositoryImpl.transitDirectToTerminal:150`)를 main 보호로, 도메인 factory 가드를 이중화로 분리 명시했고, `PgInbox.create` 4 오버로드 main 호출처 0건 사실이 정정됐다. D3 후속 등재 / D4 STACK.md 가이드 3-step 까지 verify 단계 누락 위험 해소. 새 critical / major 없음 — pass.",

  "checklist": {
    "source": "_shared/checklists/discuss-ready.md",
    "items": [
      {
        "section": "domain risk",
        "item": "멱등성 전략이 결정됨",
        "status": "n/a",
        "evidence": "본 토픽은 신규 멱등성 도입 없음 — §7.4 INSERT IGNORE 기존 멱등성 명시 유지"
      },
      {
        "section": "domain risk",
        "item": "장애 시나리오 최소 3개 식별됨",
        "status": "yes",
        "evidence": "docs/topics/CLEANUP-BATCH-A.md §4 (Flyway missing migration + named volume 재사용 sub-case / 무한 retry-after / builder 가드 우회) 3건"
      },
      {
        "section": "domain risk",
        "item": "재시도 정책이 정의됨",
        "status": "yes",
        "evidence": "§1.4 Retry-After: 5 고정값 결정 + §7.1 후속 토픽 등재 결정 (D3 흡수)"
      },
      {
        "section": "domain risk",
        "item": "PII / 민감정보 검토됨",
        "status": "n/a",
        "evidence": "신규 PII 도입 0"
      },
      {
        "section": "design decisions",
        "item": "§1.2 builder 전환 후 가드 surface 인벤토리 정확성 (D1 흡수)",
        "status": "yes",
        "evidence": "docs/topics/CLEANUP-BATCH-A.md §1.2 line 159-170 Round 2 흡수 노트 1번 + 표 'main 실제 보호 surface' 컬럼. 어댑터 가드 활성 / 도메인 가드 이중화 분리 명시"
      },
      {
        "section": "design decisions",
        "item": "§1.2 PgInbox.create 4 오버로드 main 호출처 인벤토리 정확성 (D2 흡수)",
        "status": "yes",
        "evidence": "docs/topics/CLEANUP-BATCH-A.md §1.2 line 162 Round 2 흡수 노트 2번 + 표 row 1 'main 호출처 0건 — test 픽스처 전용' 정정"
      },
      {
        "section": "scope",
        "item": "§1.4 429 시그널 누락 후속 토픽 트리거 등재 (D3 흡수)",
        "status": "yes",
        "evidence": "docs/topics/CLEANUP-BATCH-A.md §3 line 369 신규 등재 항목 + §7.1 line 453 [PR A] 동일 커밋 묶음 명시"
      },
      {
        "section": "design decisions",
        "item": "§1.3 docker-compose 볼륨 재사용 missing-migration 가시화 (D4 흡수)",
        "status": "yes",
        "evidence": "docs/topics/CLEANUP-BATCH-A.md §2 line 327 acceptance 행 + §3 line 371 STACK.md 갱신 + §4.1 line 380-393 plan 확인 + STACK.md 3-step 가이드 3 지점 분산 배치"
      }
    ],
    "total": 8,
    "passed": 6,
    "failed": 0,
    "not_applicable": 2
  },

  "scores": {
    "clarity": 0.90,
    "completeness": 0.92,
    "risk": 0.93,
    "testability": 0.85,
    "fit": 0.92,
    "mean": 0.904
  },

  "findings": [],

  "previous_round_ref": "discuss-domain-1.md",
  "delta": {
    "newly_passed": [
      "§1.2 builder 전환 후 가드 surface 인벤토리 정확성 (D1 resolved — 어댑터 가드 = main 보호 / 도메인 가드 = 이중화 분리 명시)",
      "§1.2 PgInbox.create 4 오버로드 main 호출처 인벤토리 정확성 (D2 resolved — test 픽스처 전용 정정)",
      "§1.4 429 시그널 누락 후속 토픽 트리거 등재 (D3 resolved — §3 신규 항목 + §7.1 [PR A] 동일 커밋 묶음)",
      "§1.3 docker-compose 볼륨 재사용 missing-migration 가시화 (D4 resolved — §2 / §3 / §4.1 3지점 분산)"
    ],
    "newly_failed": [],
    "still_failing": []
  },

  "unstuck_suggestion": null
}
```
