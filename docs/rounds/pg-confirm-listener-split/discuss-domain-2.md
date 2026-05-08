# discuss-domain-2

**Topic**: PG-CONFIRM-LISTENER-SPLIT
**Round**: 2
**Persona**: Domain Expert

## Reasoning

Round 1 의 domain finding 5건 (major 3 + minor 2) 이 토픽 본문에 모두 추적 가능한 위치로 봉인되었다 — D-F1 → §1.8 신설, D-F2 → §1.7 확장, D-F3 → §1.1 신규 application service `PgInboxPendingService.insertPendingAndPublish` `@Transactional(timeout=5)`, D-F4 → §1.4 60s 통일 + 사유 명시, D-F5 → §1.4 + §1.7 새 root span 정책 명시. 추가로 §1.8 의 신규 repo 메서드 셋 (`insertPending` / `transitDirectToInProgress` / `transitDirectToTerminal` / `transitPendingToInProgress`) 이 보정 경로 호출처별 의미를 명확히 분리하면서 §2.2 보정 5분기 다이어그램과 cross-link, 그리고 §1.6 워커 처리자 두 메서드 (`processPending` / `processInProgressZombie`) 가 PENDING 우회 룰과 정합한다. 결제 도메인의 race window / 상태 전이 / TX 경계 / silent loss 회피 관점에서 plan 단계 implementer 임의 결정 여지 0. **pass**.

## Domain risk checklist

- [x] **멱등성 전략 결정 (Round 1 충족 → Round 2 변경 없음)** — Redis dedupe + `pg_inbox.order_id` UNIQUE + `transitPendingToInProgress` SKIP LOCKED CAS + 벤더 `Idempotency-Key=orderId` + `DuplicateApprovalHandler` ALREADY_PROCESSED 보정. §1.8 신규 `insertPending` 시그니처가 UNIQUE 충돌 시 IGNORE + 기존 inboxId 반환 명시 — 동시 listener race 흡수 명료화. **충족 유지**.
- [x] **장애 시나리오 3개 이상 (Round 1 충족 → Round 2 변경 없음)** — 위키 §폴백 표 + 토픽 §1.9 + §7.2 실패 신호 4건. **충족 유지**.
- [x] **재시도 정책 정의 (Round 1 충족 → Round 2 변경 없음)** — `pg_outbox.available_at` 기반 지수 백오프. **충족 유지**.
- [x] **PII 도입 검토 (Round 1 충족 → Round 2 변경 없음)** — 본 토픽 신규 PII 도입 0. **충족 유지**.

### 추가 점검 (도메인 시각, Round 1 finding 흡수 검증)

- [x] **D-F3 흡수 — listener TX 경계** — §1.1 봉인. 신규 `PgInboxPendingService.insertPendingAndPublish` `@Transactional(propagation = REQUIRED, timeout = 5)`. 메서드 안에서 `pgInboxRepository.insertPending(...)` + `applicationEventPublisher.publishEvent(new PgInboxReadyEvent(inboxId))` 동일 TX 위에서 호출 → AFTER_COMMIT 등록 보장. PITFALLS §3 짝패턴 정합. timeout=5s 사유 (CONVENTIONS.md §"Kafka consumer 진입 짧은 작업" + GC pause / Hikari 마진 + max.poll.interval.ms 안 들어옴) 명시.
- [x] **D-F1 흡수 — 보정 경로 PENDING 우회 룰** — §1.8 신설. "PENDING 진입은 listener 분기 (inbox 부재 신설) 전용. 보정 경로의 inbox 신설은 PENDING 거치지 않고 직접 종결 (APPROVED / QUARANTINED) 또는 IN_PROGRESS 진입" 봉인. 신규 repo 메서드 셋 (`insertPending` / `transitDirectToInProgress` / `transitDirectToTerminal` / `transitPendingToInProgress`) 으로 호출처별 의미 분리. **무한 루프 위험 차단**: 보정 경로가 PENDING 으로 돌리면 워커가 또 벤더 호출 → 같은 보정 경로 → 무한 루프 가능성 명시 인지 후 우회.
- [x] **D-F2 흡수 — `pg-confirm-flow.md` 본문 갱신** — §1.7 확장. listener 책임 정의 (§"단계별 정리" 1번) 갱신, "한눈에 보는 흐름" mermaid listener 박스 분기 추가, "벤더 응답 4분기" mermaid 옆 본문에 보정 경로 PENDING 우회 한 줄 추가, 폴링 워커 traceparent 정책 한 줄 명시. plan 단계 정확한 diff 작성 위임 (§4.3) — **위키-코드 sync 누락 차단**.
- [x] **D-F4 흡수 — PENDING 좀비 임계** — §1.4 60s (IN_PROGRESS 와 동일) 로 통일. 사유 명시 (위키 §line 224 baseline = 벤더 timeout × 2; 부하 시 워커 한 cycle 평균 5×3s = 15s, 피크 5×10s = 50s; 30s baseline 은 정상 처리 중 PENDING 도 회수 cycle 발동 → cycle 낭비). PHASE2 측정 정밀화 (§6) 항목에 비대칭성 검증 추가.
- [x] **D-F5 흡수 — 좀비 폴링 traceparent** — §1.4 + §1.7 명시. "polling 회수는 새 root span 으로 시작 — 원 message traceparent 와의 연결은 본 토픽 범위 외 (PHASE2 §6)". `outbox-channel-dispatch.md` 발행 측 폴링 워커와 동일 결정. PITFALLS §12 정합. PHASE2 (§6) 의 "좀비 폴링 회수 시 원 message traceparent 이어붙이기" 항목에 신규 등록.
- [x] **추가 점검 — §2.2 보정 5분기 다이어그램이 위키 봉인 시나리오 cover** — 5분기 모두 PENDING 우회 정합:
  - 부재 + 금액 일치 → `transitDirectToTerminal(APPROVED)` (§1.8 직접 신설)
  - 부재 + 금액 불일치 → `transitDirectToTerminal(QUARANTINED)`
  - 벤더 조회 미확정 → `transitDirectToInProgress` 후 격리
  - 존재 + 금액 일치 → 기존 row 변경 없음 + storedStatusResult 재발행 (PENDING 무관, 기존 row 사용)
  - 존재 + 금액 불일치 → 기존 row UPDATE QUARANTINED (PENDING 무관, 기존 row 사용)
  - 위키 stateDiagram (`[*] --> PENDING : listener INSERT` + `PENDING --> QUARANTINED : 벤더만 승인 + 이력 부재 + 금액 불일치 / 벤더 조회 미확정`) 와 §1.8 우회 룰 둘이 충돌 표면적 있어 보이지만 §2.2 다이어그램이 의미를 명료히 — "위키 stateDiagram 의 PENDING → QUARANTINED 화살표는 listener 분기 진입 후의 워커 사이클 산출, 보정 경로는 별 PATH" — 이 의미가 §1.7 "벤더 응답 4분기 mermaid 옆 본문에 보정 경로 PENDING 우회 한 줄" 에 들어가야 위키 stateDiagram 자체가 헷갈리지 않음. 토픽이 위키 갱신 폭에 포함했으므로 OK.
- [x] **추가 점검 — §7 acceptance 가 도메인 리스크 (silent loss / over-restore) 관찰 가능화** — 5건 성공 + 4건 실패 신호:
  - **silent loss 차단**: A1 (listener TX 안에서 벤더 호출 0) + A5 (publishEvent 가 active TX 안에서) → AFTER_COMMIT 미등록 silent loss 차단 검증
  - **over-restore 차단**: A4 (보정 경로 PENDING 거치지 않음) → 보정 경로의 PENDING 진입으로 인한 무한 루프 차단 검증
  - **좀비 회수 정상 동작**: A3 (TX_A 후 워커 크래시 시 60s 후 회수 + ALREADY_PROCESSED 보정) + F3 (`pg_inbox.zombie_recovered_total{status=PENDING|IN_PROGRESS}` 카운터)
  - **운영 신호**: F1 (채널 가득) / F2 (워커 RuntimeException) / F4 (TX timeout 발화) — 외부 의존성 hang 신호. 정량 SLO 는 PHASE2 위임 인정.

## 도메인 관점 추가 검토

### 1. Round 2 흡수 검증 — 5건 모두 토픽 본문에 추적 가능한 위치로 봉인

토픽 §8 흡수 매핑 표가 D-F1 ~ D-F5 5건 모두 정확히 위치 매핑. 각 위치 본문 cross-check 결과:

- **D-F1 → §1.8** — `docs/topics/PG-CONFIRM-LISTENER-SPLIT.md:434~464`. 룰 명시 + 위키 SoT 인용 + 무한 루프 사유 + 신규 repo 메서드 셋 시그니처 + §1.5 / §1.6 / §1.7 cross-link 모두 봉인.
- **D-F2 → §1.7** — `docs/topics/PG-CONFIRM-LISTENER-SPLIT.md:424~432`. `pg-confirm-flow.md` 본문 4건 갱신 + 페이지 상단 noti 제거 시점 (verify 단계) 명시.
- **D-F3 → §1.1** — `docs/topics/PG-CONFIRM-LISTENER-SPLIT.md:215~225`. `PgInboxPendingService.insertPendingAndPublish` `@Transactional(propagation = REQUIRED, timeout = 5)` 시그니처 + 메서드 안 3단계 (INSERT + publishEvent + AFTER_COMMIT 채널 적재) + timeout 사유 + PITFALLS §3 정합.
- **D-F4 → §1.4** — `docs/topics/PG-CONFIRM-LISTENER-SPLIT.md:307,313~316,334`. 60s baseline 통일 + 부하 시나리오 사유 (cycle 낭비) + PHASE2 측정 정밀화 항목.
- **D-F5 → §1.4 + §1.7** — `docs/topics/PG-CONFIRM-LISTENER-SPLIT.md:318~322,431`. 새 root span 정책 + `outbox-channel-dispatch.md` 발행 측 패턴 정합 + PHASE2 (§6) deferred + 위키 갱신 본문 한 줄.

### 2. §1.8 신규 repo 메서드 셋이 보정 경로 호출처별 의미 분리 — 도메인 안전성 강화

Round 1 의 D-F1 요청 ("신규 repo 메서드 시그니처 plan 입력으로 명시") 을 토픽이 한 단계 더 정밀화 — 단순 PENDING 우회 룰 명시 + 별 메서드 도입을 넘어 **호출처별 의미를 4 메서드로 분리**:

| 메서드 | 호출처 | 의미 |
|---|---|---|
| `insertPending(orderId, amount, ...)` | listener (`PgInboxPendingService.insertPendingAndPublish`) | 처리 시작 시그널 |
| `transitDirectToInProgress(orderId, amount)` | 보정 (`handleVendorIndeterminate`) | 벤더 조회 미확정 → IN_PROGRESS 후 격리 |
| `transitDirectToTerminal(orderId, amount, terminalStatus, storedStatusResult, reasonCode)` | 보정 (`handleDbAbsent*`) | 결과 박음 (APPROVED / QUARANTINED) |
| `transitPendingToInProgress(inboxId)` | 워커 (`processPending` TX_A) | PENDING → IN_PROGRESS CAS |

도메인 시각: 4 메서드 모두 다른 의미 / 다른 호출처 / 다른 상태 전이. 단일 메서드 (`transitNoneToInProgress`) 가 의미 3종을 묶고 있던 현재 구조에서 **호출처별 의미 분리 + 코드 grep 가능성 + 룰 위반 시 컴파일러가 발견** 우위. 결제 도메인 race window / 상태 전이 sequence 의 implementer 임의 결정 여지 차단.

### 3. §1.6 워커 처리자 시그니처 분리 (`processPending` / `processInProgressZombie`) — race window 차단 정합

Round 1 finding 외 항목이지만 D-F1 흡수 (§1.8 보정 경로 우회) 와 정합. C-F5 흡수로 두 메서드 분리 — TX_A `WHERE status=PENDING` vs `WHERE status=IN_PROGRESS` 검사 조건 명확히. 

도메인 시각: 단일 메서드 + status 분기 패턴은 워커 entry 시점의 race ("나는 PENDING 인 줄 알고 들어왔는데 다른 워커가 IN_PROGRESS 로 transit 한 후") 처리 모호. 분리 시 진입점 자체가 status 가정 명시 → TX_A SKIP LOCKED 0 row 반환 시 명확한 정상 종료 (선점 / 종결 흡수). 이것이 §7 A3 (TX_A 후 워커 크래시 시 60s 후 회수 + ALREADY_PROCESSED 보정) 의 검증 가능성을 보장.

### 4. §7 acceptance 가 도메인 리스크 관찰 가능화 — 충분

§7 acceptance 가 정량 SLO 가 아닌 관찰 가능 신호로 정의된 결정은 본 토픽이 받아들이는 trade-off (정량 SLO 는 PHASE2 §6 위임). 도메인 시각으로:

- A1 (벤더 호출 0 in listener TX) — **silent latency 5s 차단** (R1 D-F3 의 silent loss 시나리오 직접 검증)
- A2 (벤더 응답 지연 시 listener throughput 영향 0) — **분리 안 도입 의의 직접 검증**
- A3 (TX_A 후 워커 크래시 시 60s 후 회수 + ALREADY_PROCESSED 보정) — **silent loss 0 + over-restore 0 검증** (좀비 회수 정상 동작 + 보정 경로 멱등성)
- A4 (보정 경로 inbox 신설은 PENDING 거치지 않음) — **무한 루프 차단 직접 검증** (R1 D-F1 의 핵심 위험)
- A5 (publishEvent 가 active TX 위에서) — **AFTER_COMMIT 미등록 silent loss 차단 검증** (R1 D-F3 PITFALLS §3 짝패턴)
- F1 (`pg_inbox_channel_queue_size` / `remaining_capacity`) — **채널 가득 빈도 운영 가시화** (위키 폴백 폴링이 회수 보장하지만 빈도가 baseline 적정성 신호)
- F2 (`pg_inbox.process_fail_total`) — **워커 RuntimeException 가시화**
- F3 (`pg_inbox.zombie_recovered_total{status=PENDING|IN_PROGRESS}`) — **좀비 회수 빈도** (PENDING 회수 빈도가 60s baseline 적정성 신호)
- F4 (Spring `TransactionTimedOutException`) — **TX timeout 5s 발화** (외부 의존성 hang 운영 신호)

도메인 5축 (silent loss / over-restore / silent latency / race window / 무한 루프) 모두 관찰 가능. 정량 SLO 는 PHASE2 위임 인정 — 본 토픽이 "분리됐다" 라고 선언하기 위한 필요충분 신호.

### 5. 잔여 우려 (도메인 시각, Round 2 신규 finding 아님)

**의도적 trade-off 인정 항목 — 본 라운드 finding 등재 안 함**:

- 좀비 폴링 traceparent 새 root span — 운영 시 trace 추적 끊김 인정 (§1.9 + PHASE2 §6 deferred). PR #72 의 stock-compensation-recovery 도 같은 결정.
- listener 진입 분기 두 갈래 (안 B 채택) — 응집도 약화 인정 (§1.9 + 위키 본문 갱신 §1.7 동봉).
- 워커 VT 풀 / 채널 cap / 좀비 임계 baseline 측정 없음 — 위키 인용 + 발행 측 거울 값 (§1.9 + PHASE2 §6 정밀화).
- 멀티 인스턴스 가정 부재 — 단일 인스턴스 baseline (§4.1 + PHASE2 §6).

이 4건은 본 토픽 scope 안에서 의도적 trade-off 로 인정 — discuss 단계 종결 영향 0.

## Findings

(Round 2 — Round 1 finding 5건 모두 흡수 확인. 신규 finding 0건.)

## JSON

```json
{
  "stage": "discuss",
  "persona": "domain-expert",
  "round": 2,
  "task_id": null,

  "decision": "pass",
  "reason_summary": "Round 1 domain finding 5건 (D-F1~D-F5) 이 토픽 §1.1 / §1.4 / §1.7 / §1.8 + §8 흡수 매핑 표에 추적 가능한 위치로 모두 봉인됨. 추가로 §1.8 신규 repo 메서드 셋 (4 메서드) 으로 호출처별 의미 분리 + §1.6 워커 처리자 두 진입점 분리로 race window 차단 + §7 acceptance 9건이 도메인 리스크 5축 (silent loss / over-restore / silent latency / race window / 무한 루프) 모두 관찰 가능화. plan 단계 implementer 임의 결정 여지 0.",

  "checklist": {
    "source": "_shared/checklists/discuss-ready.md (Gate checklist — domain risk 섹션)",
    "items": [
      {
        "section": "domain risk",
        "item": "멱등성 전략 결정",
        "status": "yes",
        "evidence": "Redis dedupe + pg_inbox UNIQUE + transitPendingToInProgress SKIP LOCKED CAS + 벤더 Idempotency-Key + DuplicateApprovalHandler ALREADY_PROCESSED. §1.8 insertPending 의 UNIQUE 충돌 IGNORE + 기존 inboxId 반환 명시. (docs/topics/PG-CONFIRM-LISTENER-SPLIT.md:454)"
      },
      {
        "section": "domain risk",
        "item": "장애 시나리오 3개 이상",
        "status": "yes",
        "evidence": "위키 §폴백 표 6경로 + 토픽 §1.9 받아들이는 trade-off 표 7항목 + §7.2 실패 신호 4건"
      },
      {
        "section": "domain risk",
        "item": "재시도 정책 정의",
        "status": "yes",
        "evidence": "pg_outbox.available_at 기반 지수 백오프 ×3 + jitter ±25%, attempt < 4. 본 토픽 변경 0 (위키 SoT)"
      },
      {
        "section": "domain risk",
        "item": "PII 도입 검토",
        "status": "yes",
        "evidence": "본 토픽 신규 PII 도입 0. pg_inbox 에 amount + storedStatusResult 만 추가"
      }
    ],
    "total": 4,
    "passed": 4,
    "failed": 0,
    "not_applicable": 0
  },

  "scores": {
    "clarity": 0.90,
    "completeness": 0.92,
    "risk": 0.92,
    "testability": 0.88,
    "fit": 0.95,
    "mean": 0.914
  },

  "findings": [],

  "previous_round_ref": "discuss-domain-1.md",
  "delta": {
    "newly_passed": [
      "listener TX 경계 (PENDING INSERT + AFTER_COMMIT publishEvent) 결정 봉인 (D-F3 → §1.1)",
      "보정 경로 PENDING 우회 룰 명시 봉인 (D-F1 → §1.8)",
      "안 B 채택 시 위키 본문 (pg-confirm-flow.md) 갱신 폭 확장 (D-F2 → §1.7)",
      "PENDING 좀비 임계 60s 통일 + 사유 명시 (D-F4 → §1.4)",
      "좀비 폴링 traceparent 정책 명시 (D-F5 → §1.4 + §1.7)"
    ],
    "newly_failed": [],
    "still_failing": []
  },

  "unstuck_suggestion": null
}
```
