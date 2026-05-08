# PG-CONFIRM-LISTENER-SPLIT — 사전 브리핑 (Baseline 0)

> 토픽 시작: 2026-05-09
> 이슈: #73
> 브랜치: `#73`
> 단계: discuss 진입 대기

---

## 배경

위키 `pg-confirm-flow.md` 는 PG 결제 확인 처리 흐름이 **listener / 워커 VT / 릴레이 워커 3단 분리** 로 봉인되어 있다. 페이지 상단에 "도메인 설계 의도 기준, 현재 코드는 일부 단계가 합쳐져 있음" 노티까지 명시되어 있다. 그러나 현재 코드는 listener thread 안에서 TX1 + 벤더 HTTP + TX2 가 같은 스레드 위에서 순차 진행 — 벤더 latency 가 인바운드 throughput 에 직접 묶인다.

`docs/context/TODOS.md` TC-14 항목으로 등록된 위키-코드 sync 잔여 갭 중 하나. 직전 토픽 STOCK-COMPENSATION-RECOVERY (PR #72, 2026-05-08) 가 payment-service 측 위키-코드 sync 를 끝낸 직후, 본 토픽이 pg-service 측 정합을 잡는다.

---

## 위키가 묘사하는 분리 안 (봉인된 진실원)

```
1. listener thread — Inbox 시그널 INSERT + ack 까지만
   - Redis dedupe (markSeen) 통과 후 INSERT pg_inbox status=PENDING
   - AFTER_COMMIT 으로 InboxReadyEvent(inboxId) 채널에 적재
   - Kafka offset commit + 다음 poll
   - 벤더 호출 안 함

2. 작업 큐 (인메모리 채널) — 워커 VT 가 take

3. 워커 VT — TX_A → 벤더 HTTP → TX_B
   - TX_A: SELECT FOR UPDATE SKIP LOCKED + status=IN_PROGRESS UPDATE 후 즉시 commit (lock 해제)
   - 벤더 HTTP (블로킹, VT 라 캐리어 양보)
   - TX_B: Inbox UPDATE (APPROVED + storedStatusResult) + Outbox INSERT (events.confirmed) + AFTER_COMMIT publishEvent

4. 발행 큐 (인메모리 채널) — 릴레이 워커 VT 가 take

5. 릴레이 워커 VT — Kafka send + processed_at = now (별도 TX)
```

**폴백 경로**:
- Inbox PENDING 좀비: `WHERE status=PENDING AND received_at < now-Ns` 폴링 회수
- Inbox IN_PROGRESS 좀비: `WHERE status=IN_PROGRESS AND updated_at < now-60s` 폴링 회수 — 벤더 idempotency-key + `DuplicateApprovalHandler` 가 중복 흡수
- Outbox processed_at NULL: 폴링 회수 → 재발행

---

## 현재 코드 (합쳐서 처리)

`pg-service/.../application/service/PgConfirmService.java`:
- `handleNone` 안에서 TX1 (`transitNoneToInProgress`) → TX2 (`callVendor`) 가 같은 listener thread 위에서 순차 진행
- TX2 안에서 벤더 HTTP + Outbox INSERT + Inbox 종결 모두 수행

`pg-service/.../application/service/PgVendorCallService.java` — 단일 TX2 안에 벤더 호출 + 결과 반영 합쳐서 처리.

`pg-service/.../infrastructure/messaging/consumer/PaymentConfirmConsumer.java` — listener 진입점. 현재 listener thread 가 TX1 + TX2 + 벤더 호출 다 직접 호출.

`pg_inbox` 의 status enum 에 PENDING 상태가 **없다** — 분리 안 도입 시 신규 상태 추가 필요.

---

## 본 토픽 목표

위키의 분리 안을 코드에 정합. 즉:

1. **listener 책임 축소** — `INSERT pg_inbox status=PENDING + Kafka ack` 까지만. 벤더 호출 안 함.
2. **작업 큐 도입** — listener 가 AFTER_COMMIT 으로 inboxId 를 인메모리 채널에 적재. 별도 워커 VT 가 take.
3. **워커 VT 가 TX_A → 벤더 HTTP → TX_B 진행** — 가운데 벤더 호출이 끼므로 한 TX 로 묶지 않는다.
4. **좀비 폴링 도입** — `WHERE status=PENDING AND received_at < now-Ns` + `WHERE status=IN_PROGRESS AND updated_at < now-60s` 두 경로.
5. **위키 다른 페이지 갱신** — `outbox-channel-dispatch.md` 가 분리 안 적용 시 작업 큐 + 발행 큐 채널 2개로 본문 갱신 필요 (현재는 발행 큐 1개 기준).

---

## 비교 효과

| 축 | 현재 (합쳐서 처리) | 분리 안 (위키) |
|---|---|---|
| listener 책임 | TX1 + 벤더 + TX2 | INSERT + ack 까지만 |
| 벤더 latency 영향 | 인바운드 throughput 에 직접 영향 | 워커 VT 풀에 격리 |
| `max.poll.interval.ms` 위험 | 벤더 latency × poll 수 | listener 가 가벼워 안전 |
| 동시 처리 수 통제 | listener concurrency | 워커 VT 풀 + DB connection pool |
| 좀비 회수 | 단일 경로 | PENDING + IN_PROGRESS 두 경로 |
| 발행 측 채널 | 1개 (Outbox) | 1개 (Outbox) — 변경 없음. 단 작업 큐 추가 |

---

## 검토 필요 사항 (discuss 입력)

1. **`pg_inbox` status enum 에 PENDING 추가** — 신규 상태. Flyway migration 1건. 기존 `NONE → IN_PROGRESS → APPROVED/FAILED/QUARANTINED` 에 PENDING 진입 추가.
2. **listener vs 워커 책임 경계 정밀화** — listener 의 `INSERT pg_inbox` TX 안에 AFTER_COMMIT 채널 적재까지. 채널 가득 시 폴백 폴링이 회수.
3. **워커 VT 풀 크기** — 동시 처리 수 통제 변수. yml 설정 키.
4. **좀비 회수 임계** — PENDING (수십 초?) / IN_PROGRESS (60s, 벤더 timeout × 2) — 측정 기반 결정 또는 baseline 채택.
5. **PgConfirmService.handleNone 의 분기 보존** — 현재 NONE / IN_PROGRESS self-loop / APPROVED 재수신 / FAILED 재수신 / QUARANTINED 재수신 처리. 분리 안에서 어느 단계가 어느 분기를 처리하는지.
6. **`DuplicateApprovalHandler` 와 좀비 회수 정합** — 워커 크래시 후 재진입 시 벤더 재호출 → `ALREADY_PROCESSED` → 보정 경로. 위키 분리 안에 명시.
7. **위키 다른 페이지 (`outbox-channel-dispatch.md`) 갱신** — 발행 측 채널 1개 → 작업 큐 + 발행 큐 2개 로 본문 갱신.

---

## non-goal (본 토픽 범위 외)

- PG 벤더 자체 정책 변경 (Toss / NicePay / Fake 어댑터 변경 0)
- `pg_outbox` 의 발행 측 채널 메커니즘 변경 (이미 위키와 정합 — `PgOutboxChannel` + `PgOutboxImmediateWorker` + `PgOutboxPollingWorker`)
- payment-service 측 변경 0
- DLQ 처리 정책 변경 (TQ-1 별 토픽)

---

## 관련 위키

- `pg-confirm-flow.md` — 메인 (분리 안 봉인)
- `outbox-channel-dispatch.md` — 분리 안 적용 시 작업 큐 + 발행 큐 채널 2개로 본문 갱신 필요

## 관련 코드

- `pg-service/src/main/java/.../application/service/PgConfirmService.java`
- `pg-service/src/main/java/.../application/service/PgVendorCallService.java`
- `pg-service/src/main/java/.../infrastructure/messaging/consumer/PaymentConfirmConsumer.java`
- `pg-service/src/main/java/.../domain/PgInbox.java` (status enum)
- `pg-service/src/main/java/.../infrastructure/repository/PgInboxRepositoryImpl.java`
- `pg-service/src/main/java/.../application/service/DuplicateApprovalHandler.java`
- `pg-service/src/main/java/.../infrastructure/scheduler/PgOutboxImmediateWorker.java` (참조 패턴)
- `pg-service/src/main/java/.../infrastructure/scheduler/PgOutboxPollingWorker.java` (참조 패턴)
- `pg-service/src/main/java/.../infrastructure/channel/PgOutboxChannel.java` (참조 패턴)
