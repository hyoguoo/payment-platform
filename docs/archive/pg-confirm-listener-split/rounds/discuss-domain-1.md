# discuss-domain-1

**Topic**: PG-CONFIRM-LISTENER-SPLIT
**Round**: 1
**Persona**: Domain Expert

## Reasoning

위키 SoT (`pg-confirm-flow.md` + `outbox-channel-dispatch.md`) 의 분리 안 봉인 자체는 결제 도메인 시각으로 합리적이고, 토픽 본문은 그 골격을 위키 인용 + `pg_outbox` 패턴 거울로 충실히 옮긴다. 다만 **돈이 새는 경로 / 좀비 회수 / 보정 경로의 status 전이 / listener TX 경계** 4개 축에서 결정 정밀도가 부족해 plan 단계 전에 discuss 가 마무리해야 할 도메인 결정이 남아 있다. 위키 봉인 결정 (PENDING 추가 / 인메모리 채널 / 좀비 폴링) 자체는 뒤집지 않고, 그 안에서 race window 와 상태 전이 시퀀스를 결정한다.

## Domain risk checklist

- [x] **멱등성 전략 결정** — Redis dedupe (`markSeen` SET NX EX 1h) + `pg_inbox.order_id` UNIQUE + `transitPendingToInProgress` SKIP LOCKED CAS + 벤더 `Idempotency-Key=orderId` + `DuplicateApprovalHandler` ALREADY_PROCESSED 보정. 위키 + 토픽 §1.6 / §1.8 에 모두 인용. **충족**.
- [x] **장애 시나리오 3개 이상** — listener PENDING INSERT 실패 / AFTER_COMMIT 채널 적재 실패 / TX_A 후 워커 크래시 / 벤더 timeout / TX_B 커밋 실패 / Kafka send 실패 6경로 위키 §폴백 표 + 토픽 §1.8 표에 인용. **충족**.
- [x] **재시도 정책 정의** — `pg_outbox.available_at` 기반 지수 백오프 ×3 + jitter ±25%, attempt < 4. 본 토픽 변경 0 (위키 SoT 그대로). **충족**.
- [x] **PII 도입 검토** — 본 토픽 신규 PII 도입 0. `pg_inbox` 에 amount + storedStatusResult 만 추가, paymentKey 등 기존과 동일. **충족**.

### 추가 점검 (도메인 시각)

- [~] **listener TX 경계 명세** — 분리 안에서 listener 가 `INSERT pg_inbox status=PENDING + AFTER_COMMIT publishEvent` 를 하려면 같은 TX 경계가 필요하다. 현재 `PgConfirmService.handle` 에 `@Transactional` 부재, `transitNoneToInProgress` 메서드 단위 `@Transactional` 만 존재. 분리 후 listener 측 TX 경계 (어느 메서드에 `@Transactional` 부착할지, REQUIRED / REQUIRES_NEW 어느 쪽인지) **plan 단계에서 결정 가능 영역이지만 discuss 단계 finding 으로 명시 필요**. (F-3 참조)
- [~] **PENDING 좀비 임계 30s baseline 안전성** — 토픽 §1.4 `pending-timeout-ms=30000` 채택. 정상 처리 중인 PENDING (channel 적재 → 워커 take → TX_A 진입까지) 이 30s 를 넘을 수 있는 부하 시나리오 (채널 cap=1024 가득 + 워커 5개 모두 벤더 응답 대기 + 벤더 read-timeout 10s) — 30s 는 빠듯하다. SKIP LOCKED 가 충돌 차단해 안전성은 보장되나 cycle 낭비. (F-4 참조)
- [~] **`DuplicateApprovalHandler` 보정 경로의 status 전이 시퀀스** — 보정 경로 3곳 (`handleDbAbsentAmountMatch` / `handleDbAbsentAmountMismatch` / `handleVendorIndeterminate`) 모두 `transitNoneToInProgress(orderId, amount)` 호출. 분리 안에서 listener PENDING INSERT 책임이 단일화되었는데 보정 경로는 PENDING 거치지 않고 inbox 신설 → IN_PROGRESS 직진. **위키 봉인 상태 머신 다이어그램**의 `[*] --> PENDING : listener INSERT` 와 정합 우려. 토픽 §4.4 가 검증을 critic 라운드로 위임하나 도메인 시각으로 결정 필요. (F-1 참조)

## 도메인 관점 추가 검토

### 1. 보정 경로 (`DuplicateApprovalHandler`) inbox 신설 시 status 진입점 (NONE 폐기 시 결정 누락)

`DuplicateApprovalHandler.java:232,251,268` — 보정 경로 3곳 모두 `pgInboxRepository.transitNoneToInProgress(orderId, amount)` 호출. 분리 안 §1.5 가 권장하는 **"안 A — NONE 폐기"** 채택 시 이 호출처들의 의미는 다음 둘 중 하나로 갈린다:

- (a) `transitPendingToInProgress` 로 rename 하면서 보정 경로도 PENDING 거쳐야 하는가? — 그러면 보정 경로가 워커 큐 우회 후 IN_PROGRESS 로 바로 가는 의미와 충돌
- (b) 보정 경로 전용 신규 메서드 (`transitDirectToInProgress` 또는 `transitDirectToApproved`) 도입? — 그러면 위키 상태 머신 `[*] --> PENDING` 룰이 실은 **listener 진입에만 적용** 이라는 추가 의미가 명문화되어야 함

위키 본문 §"중복 승인 응답 보정" mermaid 다이어그램 (`handleDbAbsentAmountMatch` 가 `Inbox 신설 -> APPROVED`) 은 PENDING 진입 명시 없이 직접 APPROVED. 즉 **위키 SoT 자체가 보정 경로의 PENDING 우회를 암묵 허용**한다. 토픽 §4.4 도 같은 인식.

**도메인 시각**: 안 A (NONE 폐기) 와 보정 경로의 PENDING 우회를 동시 채택하려면, "PENDING 진입은 **listener 분기 (inbox 부재 시)** 만의 책임" 이라는 룰이 본 토픽 §1.5 / §1.6 / §위키 갱신 본문에 **명시 봉인** 되어야 한다. 그렇지 않으면 차후 `DuplicateApprovalHandler` 호출처 변경 시 PENDING 룰이 누구의 책임인지 모호해 잘못 PENDING INSERT 하면 워커가 또 벤더 호출 → ALREADY_PROCESSED 무한 루프 가능.

### 2. 종결 상태 재수신 listener 직접 처리 (안 B) — 위키 봉인 정신과의 미세 충돌

토픽 §1.6 안 B 가 채택한 "terminal 재수신 → listener 가 직접 `storedStatusResult` 재발행" — 위키 본문 §"한눈에 보는 흐름" mermaid (`L[1. listener<br/>Inbox PENDING INSERT<br/>+ Kafka ack]`) + §"단계별 정리" 1번 (`listener thread 의 동기 작업은 여기까지. 벤더는 안 부른다.`) 이 listener 책임을 INSERT + ack 로 강하게 한정.

terminal 재수신 분기는 **벤더는 안 부르지만 `pg_outbox INSERT` 는 한다**. 즉 listener TX 가 inbox 시그널 INSERT 보다 길어진다 (outbox INSERT + AFTER_COMMIT publishEvent 2단). listener thread 응답 시간 / Kafka `max.poll.interval.ms` 압박은 거의 없으나, 위키 봉인 정신을 엄격히 따르려면 terminal 재수신도 워커 큐 거치는 안 A 가 위키-코드 정합 면에서 더 깔끔하다. 토픽이 응집도 vs latency trade-off 를 인정하고 안 B 채택했지만 — **위키 갱신 본문 (`pg-confirm-flow.md` 의 listener 책임 정의)** 에 "단 terminal 재수신은 listener 가 직접 outbox INSERT + 재발행" 한 줄 추가가 본 토픽 plan 산출물에 포함되어야 위키-코드 sync 가 깨지지 않는다.

**도메인 리스크**: trade-off 자체는 받아들일 수 있으나 위키 본문 갱신 누락 시 다음 토픽이 이 분기를 다시 워커 큐로 옮기려 할 때 코드-위키 미스매치를 또 발견.

### 3. listener TX 경계 명세 부재 (PENDING INSERT + AFTER_COMMIT publishEvent)

`PgConfirmService.java:55-72` — 현재 `handle(command, attempt)` 에 메서드 레벨 `@Transactional` 부재. 짧은 TX 는 `transitNoneToInProgress` 안쪽에서만 발생. 분리 안에서 listener 가:

- (i) `INSERT pg_inbox status=PENDING` 짧은 TX
- (ii) 같은 TX 안에서 `applicationEventPublisher.publishEvent(new PgInboxReadyEvent(inboxId))`
- (iii) AFTER_COMMIT 리스너 (`InboxReadyEventHandler`) 가 `PgInboxChannel.offerNow` 호출

(ii) 가 active TX 안에서 일어나야 (iii) 의 `@TransactionalEventListener(AFTER_COMMIT)` 이 정상 등록된다. PITFALLS §3 (`@Transactional 안에서 동기 Kafka publish 금지`) 와 같이, **TX 경계 정밀화 누락은 결제 도메인의 알려진 함정**.

토픽 §1.1 / §1.2 / §1.6 어디에도 listener 측 TX 경계가 명시되지 않음 — `transitPendingToInProgress` 메서드가 publishEvent 까지 포함하는지, 아니면 `PgConfirmService.handle` 에 `@Transactional` 을 새로 부착하는지, REQUIRED 인지 REQUIRES_NEW 인지 미결정. 토픽 §3 컴포넌트 인벤토리에서 `PgInboxRepository.transitPendingToInProgress(orderId)` 신규를 명시했지만 publishEvent 호출 위치 (repository / service / 별도 service) 는 미결.

**도메인 리스크**: TX 경계 미명시는 plan 단계에서 implementer 가 임의 결정 → race 또는 silent loss. 예: publishEvent 가 TX 외부에서 일어나면 `fallbackExecution=true` 등록 안 됨 → AFTER_COMMIT 발화 누락 → 채널 적재 0 → 폴링이 회수 (RDB SoT) → 회수 latency (5s 폴링 주기) 만큼 지연. 정합성은 살아있지만 **즉시 발화 vs 폴링 회수의 운영 의미** 가 다르다. 본 토픽이 listener TX 경계를 명시 결정해야 plan 단계에서 RED 테스트로 검증 가능.

### 4. PENDING 좀비 임계 30s baseline 의 부하 시 안전성

토픽 §1.4 `pg.scheduler.inbox-polling-worker.pending-timeout-ms=30000` 채택. 위키는 "수십 초" 만 명시.

**경로 분석**:
- 채널 cap=1024 가득 + 워커 N=5 + 벤더 read-timeout=10s + 벤더 평균 latency 1~3s
- 워커 5개가 모두 벤더 응답 대기 시 채널의 다음 1024개 PENDING job 은 평균 5×3s = 15s 대기. 피크 시 (read-timeout 10s 도달) 5×10s = 50s. **30s 임계 초과 가능**.
- 정상 처리 중인 PENDING 도 폴링이 회수 시도 → TX_A SKIP LOCKED 가 PENDING 인 row 만 잠금하고 워커가 이미 IN_PROGRESS 로 전이 시작했다면 PENDING 도 아니라 SKIP. 안전성 자체는 보장. 하지만 **회수 attempt 중복 + 채널 또 적재 + 추가 cycle 낭비**.

**도메인 시각**: PENDING timeout 은 IN_PROGRESS timeout (60s = 벤더 timeout × 2) 보다 짧을 합리 근거가 약하다. PENDING 은 워커 take 직전까지 무한 대기 가능하므로 (채널 가득 + 워커 모두 바쁨), **벤더 timeout × 2 + 평균 워커 cycle = 약 60s** 가 더 안전. 또는 두 timeout 을 같은 값 (60s) 로 통일. 측정 없는 baseline 인정하더라도 30s 는 너무 짧아 plan 단계 단위 테스트에서 false-positive (정상 처리 중인 PENDING 회수) 가능. **토픽 §1.4 yml 기본값 재고 또는 안전성 사유 명시 권장**.

### 5. 좀비 회수 워커의 OTel/MDC 컨텍스트 캡처/복원 명세 부재

`PgInboxPollingWorker` (신규 §1.4) — 좀비 회수 시 워커 process 메서드 직접 호출 (§4.5 권장). 하지만 폴링 워커의 `@Scheduled` 진입은 트레이스 / MDC 가 새 root span 으로 시작된다. 정상 흐름의 `InboxJob` (OTel context + ContextSnapshot 동봉) 과 달리 폴링 회수는 **inboxId 만 가지고 있어 원래 메시지의 traceparent 로 이어붙일 수 없다**.

PITFALLS §12 ("Virtual Thread / Async 경계 MDC 손실") + 위키 `outbox-channel-dispatch.md` 에서 polling 워커의 컨텍스트 처리는 의도적으로 새 root span. 즉 본 토픽도 그 결정 그대로 따르면 OK 지만 **명시 인용이 토픽 §1.4 / §3 인벤토리에서 누락**. 운영 시 trace 추적이 끊기는 이유를 모르면 디버깅 비용 증가.

**도메인 시각**: 본 토픽 §1.4 에 "polling 회수 시 traceparent 는 새 root span — Kafka headers 의 원 traceparent 로의 이어붙이기는 본 토픽 범위 외" 한 줄 명시 권장 (위키 `outbox-channel-dispatch.md` 갱신 본문에도 같은 한 줄).

## Findings

### F-1 (major) — 보정 경로 (`DuplicateApprovalHandler`) inbox 신설 시 PENDING 우회 룰 봉인 누락
- **근거**: `pg-service/src/main/java/com/hyoguoo/paymentplatform/pg/application/service/DuplicateApprovalHandler.java:232,251,268`
- **위키 SoT**: `pg-confirm-flow.md:117-127` (상태 머신 `[*] --> PENDING : listener INSERT`) 가 PENDING 진입 = listener 책임으로 명시
- **토픽 결정 부재**: §1.5 안 A (NONE 폐기) 권장 + §4.4 가 보정 경로 PENDING 우회를 critic 위임 → discuss 단계에서 결론 미봉인
- **도메인 리스크**: PENDING 진입이 listener 분기 (inbox 부재) 전용이라는 룰이 봉인되지 않으면, 차후 `DuplicateApprovalHandler` 변경 시 보정 경로에 잘못 PENDING INSERT → 워커가 또 벤더 호출 → 동일 사이클 무한 루프 가능
- **요청**: 토픽 §1.5 + §1.6 안 B + §위키 갱신 본문에 **"PENDING 진입은 listener 분기 (inbox 부재 신설) 만의 책임. 보정 경로 (`DuplicateApprovalHandler`) 는 PENDING 우회 후 직접 IN_PROGRESS / APPROVED / QUARANTINED 로 진입한다"** 한 줄 봉인. 신규 repo 메서드 시그니처 (`transitDirectToInProgress` 별도 또는 `transitPendingToInProgress` 와 분기 명시) 도 plan 단계 입력으로 명시.

### F-2 (major) — 종결 상태 재수신 listener 직접 처리 (안 B) 시 위키 본문 갱신 누락
- **근거**: 토픽 §1.6 안 B 권장 (`docs/topics/PG-CONFIRM-LISTENER-SPLIT.md:332-347`)
- **위키 SoT**: `pg-confirm-flow.md:31-42` (한눈에 보는 흐름 mermaid) + `:194-196` (단계별 정리 1번) 이 listener 책임 = "Inbox 시그널 INSERT + ack" 로 한정. terminal 재수신 분기 처리자 표기 부재
- **도메인 리스크**: 토픽이 안 B (listener 직접 outbox INSERT 재발행) 채택했는데 위키 본문에 이 분기 표기 안 들어가면, 코드-위키 미스매치가 다음 토픽 시점에 또 등장. 본 토픽이 위키 정합 작업이라는 의미를 살리지 못함
- **요청**: 토픽 §1.7 위키 갱신 폭에 `pg-confirm-flow.md` 본문 갱신도 추가. "listener 책임 = INSERT + ack **단, terminal 재수신은 outbox 재발행까지 listener 가 직접 처리**" 명시. 또는 안 A (모든 분기를 워커 거침) 로 재고. 둘 중 하나로 봉인.

### F-3 (major) — listener TX 경계 (PENDING INSERT + AFTER_COMMIT publishEvent) 결정 미명세
- **근거**: 토픽 §1.1 / §1.2 / §1.6 / §3 인벤토리 어디에도 listener 측 TX 경계 위치 명시 부재
- **현재 코드 사실**: `PgConfirmService.java:55-72` (`handle` 메서드 `@Transactional` 부재), `PgInboxRepositoryImpl.java:53-71` (`transitNoneToInProgress` 만 `@Transactional`)
- **PITFALLS 정합**: §3 (`@Transactional 안에서 동기 Kafka publish 금지`) 의 짝패턴 — TX 안에서 `applicationEventPublisher.publishEvent` + AFTER_COMMIT 리스너로 비동기 발행
- **도메인 리스크**: publishEvent 가 active TX 외부에서 호출되면 AFTER_COMMIT 등록 안 됨 → 채널 적재 0 → RDB SoT 폴링이 5s 주기로 회수. 정합성은 살아있지만 정상 흐름 latency 가 5s 더 길어짐. plan 단계 implementer 가 임의 결정하지 않도록 discuss 가 결정해야 안전
- **요청**: 토픽 §1.1 또는 §1.2 / §3 인벤토리에 listener TX 경계 명시. 후보:
  - (a) `PgConfirmService.handle` 에 `@Transactional` 부착, 그 안에서 repository 호출 + publishEvent
  - (b) `PgInboxRepository.transitPendingToInProgress` 가 `@Transactional` 유지하면서 publishEvent 도 같은 메서드 안에서 (포트 메서드가 ApplicationEventPublisher 의존하는 안티패턴 — 비추천)
  - (c) 신규 application service 메서드 (`PgInboxPendingService.insertPendingAndPublish`) 를 `@Transactional` 로
- **권장**: (a) 또는 (c). plan 단계 RED 테스트 작성 시 TX 경계가 명시되어 있어야 검증 가능

### F-4 (minor) — PENDING 좀비 임계 30s baseline 안전성 사유 부재
- **근거**: 토픽 §1.4 `pg.scheduler.inbox-polling-worker.pending-timeout-ms=30000` 채택 (`docs/topics/PG-CONFIRM-LISTENER-SPLIT.md:289-291`)
- **위키 SoT**: `pg-confirm-flow.md:224` ("좀비 회수 임계는 벤더 호출 timeout × 2 정도가 안전. 너무 짧으면 정상 처리 중인 작업도 중복 진입") — 60s baseline 만 명시 (IN_PROGRESS 기준)
- **도메인 리스크**: 채널 cap=1024 가득 + 워커 5 모두 벤더 응답 대기 + 벤더 read-timeout 10s 시 PENDING 대기 50s 초과 가능. 30s 는 정상 처리 중인 PENDING 도 회수 시도 → TX_A SKIP LOCKED 가 race 차단하나 **회수 cycle 낭비** + 부하 시 폴링이 정상 흐름과 경쟁
- **요청**: 토픽 §1.4 + §1.8 trade-off 표에 30s baseline 채택 사유 명시. 또는 60s (IN_PROGRESS 와 동일) 로 baseline 변경. PHASE2 측정 정밀화 항목에 PENDING-IN_PROGRESS timeout 비대칭성도 추가

### F-5 (minor) — 좀비 폴링 회수 경로의 traceparent 정책 명시 부재
- **근거**: 토픽 §1.4 / §3 인벤토리 `PgInboxPollingWorker` 신규 — OTel context 처리 정책 미명시
- **위키 SoT**: `outbox-channel-dispatch.md` (발행 측 폴링 워커는 새 root span 의도) — 본 토픽이 같은 결정 따르는지 명시 누락
- **PITFALLS 정합**: §12 (Virtual Thread / Async 경계 MDC 손실) — 본 함정 회피 패턴이 본 토픽 신규 컴포넌트에도 적용되는지 확인 필요
- **도메인 리스크**: 운영 시 트레이스 끊김 → 좀비 회수 결제 1건의 사고 재구성 비용 증가. 의도된 결정이라면 명시, 아니라면 plan 에서 `PgInboxJob` 처럼 traceparent 동봉 결정 필요
- **요청**: 토픽 §1.4 또는 §1.7 위키 갱신 본문에 "polling 회수는 새 root span 으로 시작 — 원 message traceparent 와의 연결은 본 토픽 범위 외 (PHASE2)" 한 줄 명시

## JSON

```json
{
  "stage": "discuss",
  "round": 1,
  "topic": "PG-CONFIRM-LISTENER-SPLIT",
  "persona": "domain-expert",
  "verdict": "revise",
  "findings": [
    {
      "id": "F-1",
      "severity": "major",
      "summary": "보정 경로 (DuplicateApprovalHandler) inbox 신설 시 PENDING 우회 룰 봉인 누락",
      "file": "docs/topics/PG-CONFIRM-LISTENER-SPLIT.md",
      "section": "§1.5 + §1.6 + §4.4",
      "evidence": "DuplicateApprovalHandler.java:232,251,268 의 transitNoneToInProgress 호출 3곳 + 위키 pg-confirm-flow.md:117-127 상태 머신 [*] --> PENDING : listener INSERT 와의 정합 미봉인",
      "request": "PENDING 진입은 listener 분기 (inbox 부재 신설) 전용 룰 명시 + 보정 경로 신규 repo 메서드 시그니처 plan 입력으로 결정"
    },
    {
      "id": "F-2",
      "severity": "major",
      "summary": "종결 상태 재수신 listener 직접 처리 (안 B) 시 위키 본문 (pg-confirm-flow.md) 갱신 누락",
      "file": "docs/topics/PG-CONFIRM-LISTENER-SPLIT.md",
      "section": "§1.6 안 B + §1.7 위키 갱신 폭",
      "evidence": "위키 pg-confirm-flow.md:31-42, :194-196 가 listener 책임 = INSERT + ack 로 한정 — terminal 재수신 listener 직접 처리 분기 표기 부재",
      "request": "위키 갱신 폭에 pg-confirm-flow.md 본문 갱신 추가 또는 안 A (모든 분기 워커 거침) 로 재고. 둘 중 하나 봉인"
    },
    {
      "id": "F-3",
      "severity": "major",
      "summary": "listener TX 경계 (PENDING INSERT + AFTER_COMMIT publishEvent) 결정 미명세",
      "file": "docs/topics/PG-CONFIRM-LISTENER-SPLIT.md",
      "section": "§1.1 + §1.2 + §3 인벤토리",
      "evidence": "PgConfirmService.java:55-72 (handle 메서드 @Transactional 부재) + PgInboxRepositoryImpl.java:53 (메서드 단위 @Transactional 만 존재) + 토픽 본문 listener TX 경계 미명시",
      "request": "@Transactional 부착 위치 후보 (a)/(b)/(c) 중 (a) PgConfirmService.handle 또는 (c) 신규 application service 로 봉인. plan 단계 RED 테스트가 검증 가능하도록"
    },
    {
      "id": "F-4",
      "severity": "minor",
      "summary": "PENDING 좀비 임계 30s baseline 안전성 사유 부재",
      "file": "docs/topics/PG-CONFIRM-LISTENER-SPLIT.md",
      "section": "§1.4 yml 기본값 표 + §1.8 trade-off",
      "evidence": "위키 pg-confirm-flow.md:224 (60s baseline IN_PROGRESS 기준) — PENDING 30s 채택 사유 없음. 부하 시 채널 가득 + 워커 모두 바쁨 시 PENDING 대기 30s 초과 가능",
      "request": "30s 사유 명시 또는 60s 통일. PHASE2 측정 정밀화 항목에 비대칭성 추가"
    },
    {
      "id": "F-5",
      "severity": "minor",
      "summary": "좀비 폴링 회수 경로의 traceparent 정책 명시 부재",
      "file": "docs/topics/PG-CONFIRM-LISTENER-SPLIT.md",
      "section": "§1.4 + §1.7 위키 갱신",
      "evidence": "PgInboxPollingWorker 신규 (§3 인벤토리) — OTel context 처리 (새 root span vs 원 traceparent 이어붙이기) 결정 미명시. PITFALLS §12 정합 확인 필요",
      "request": "polling 회수는 새 root span — 원 traceparent 연결은 본 토픽 범위 외 (PHASE2) 한 줄 명시"
    }
  ],
  "verdict_rationale": "critical 0건. major 3건 (F-1 / F-2 / F-3) → 기계적 판정 규칙: revise. major 3건 모두 결제 도메인 race window / 상태 전이 시퀀스 / TX 경계 결정 — plan 단계 implementer 임의 결정 시 silent loss 또는 latency 의 도메인 리스크. discuss 단계 종결 전에 토픽 본문 (§1.5 + §1.6 + §1.1 + §1.7) 갱신 필요."
}
```
