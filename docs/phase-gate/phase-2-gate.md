# Phase 2 Gate — PG 서비스 분리 E2E + ADR-30 Kafka 왕복 통합 검증

**태스크**: Phase-2-Gate
**날짜**: 2026-04-21
**관련 태스크**: T2a-01 ~ T2d-03 (Phase 2 전체 — 2.a / 2.b / 2.c / 2.d)

---

## 목적

T2a-Gate ~ T2d-03 완료 후 아래 항목을 자동화 스크립트로 종합 검증한다.

- **Phase 2.a/2.b/2.c Sub-Gate 재실행**: 기존 마이크로 Gate 3개를 순서대로 위임하여 Phase 2 전반부 불변식 보장
- **pg-service 독립 기동**: application.yml(T2c-01) 기반 포트 8082 기동 확인
- **Kafka 왕복 E2E**: command → pg-service inbox 5상태 분기 → outbox 릴레이 → payment.events.confirmed → payment-service 상태 전이 전 구간 검증
- **eventUUID dedupe**: pg-service · payment-service 각각 중복 수신 시 no-op 불변식 확인
- **Fake PG 벤더 격리**: retry·timeout·확정실패 주입 → QUARANTINED 전이 검증
- **DLQ consumer QUARANTINED 전이**: PaymentConfirmDlqConsumer → pg_inbox QUARANTINED + events.confirmed 발행 검증
- **2자 금액 대조**: pg DB 존재/부재 양 경로 amount 일치/불일치 시나리오 검증
- **토픽 파티션 수 동일(불변식 6b)**: payment.commands.confirm / payment.commands.confirm.dlq / payment.events.confirmed 파티션 수 일치 확인
- **Gateway /internal/** 차단**: InternalOnlyGatewayFilter 403 Forbidden + 실제 HTTP 검증
- **payment-service cutover 잔존 검증**: PgStatusAbsenceContractTest 3케이스 GREEN (불변식 19)
- **전체 Gradle test 484건 이상** PASS

이를 통해 Phase 2(PG 서비스 분리) 전체 완료를 확인하고, Phase 3(상품·사용자 서비스 분리) 진입 전 broken foundation을 방지한다.

---

## 전제 조건

### depends

| Gate/태스크 | 상태 |
|-------------|------|
| T2c-Gate (Phase 2.c 마이크로 Gate) | 완료 |
| T2d-02 (토픽 네이밍 규약 + Outbox 관측 지표) | 완료 |
| T2d-03 (Gateway PG 내부 API 격리) | 완료 |

### 필수 소프트웨어

```bash
# macOS
brew install jq mysql-client

# kafka-topics CLI — Kafka 컨테이너 내 제공 (별도 설치 불필요)
```

### 포트 구성

| 서비스 | 포트 | 비고 |
|--------|------|------|
| Gateway | 8080 | Spring Cloud Gateway |
| payment-service | 8081 | 결제 서비스 |
| pg-service | 8082 | T2c-01 application.yml 고정 |
| MySQL (payment) | 3307 | docker-compose.infra.yml |
| MySQL (pg) | 3308 | docker-compose.infra.yml |

### Kafka 토픽 사전 생성

```bash
bash scripts/phase-gate/create-topics.sh
```

| 토픽 | 용도 |
|------|------|
| `payment.commands.confirm` | 정상 확인 요청 |
| `payment.commands.confirm.dlq` | 재시도 소진 DLQ |
| `payment.events.confirmed` | 최종 결과 이벤트 |

### 환경 변수

| 변수 | 기본값 | 설명 |
|------|--------|------|
| `PG_SERVICE_BASE` | `http://localhost:8082` | pg-service 베이스 URL |
| `GATEWAY_BASE` | `http://localhost:8080` | Gateway 베이스 URL |
| `PG_DB_HOST` | `127.0.0.1` | pg DB 호스트 |
| `PG_DB_PORT` | `3308` | pg DB 포트 |
| `PG_DB_NAME` | `pg` | pg DB 이름 |
| `PG_DB_USER` | `pg` | pg DB 사용자 |
| `PG_DB_PASS` | `payment123` | pg DB 비밀번호 |
| `KAFKA_CONTAINER` | `payment-kafka` | Kafka 컨테이너명 |
| `KAFKA_BOOTSTRAP` | `localhost:9092` | Kafka bootstrap 주소 (컨테이너 내부) |
| `SKIP_SUB_GATES` | `false` | `true` 로 설정 시 a섹션 Sub-Gate 건너뜀 |

### 서비스 기동 순서

```bash
# 1. 인프라 컨테이너 기동
docker compose -f docker-compose.infra.yml up -d

# 2. Kafka 토픽 생성
bash scripts/phase-gate/create-topics.sh

# 3. 각 서비스 기동 (별도 터미널)
./gradlew :pg-service:bootRun          # 포트 8082
./gradlew :payment-service:bootRun     # 포트 8081
./gradlew :gateway:bootRun             # 포트 8080

# 4. Phase 2 Gate 스크립트 실행
bash scripts/phase-gate/phase-2-gate.sh
```

---

## 실행 방법

```bash
# 기본 실행
bash scripts/phase-gate/phase-2-gate.sh

# Sub-Gate 건너뛰기 (이미 각 Sub-Gate 통과 후 통합 검증만 필요한 경우)
SKIP_SUB_GATES=true bash scripts/phase-gate/phase-2-gate.sh

# 환경 변수 재정의
PG_SERVICE_BASE=http://localhost:8082 bash scripts/phase-gate/phase-2-gate.sh
```

---

## 검증 항목 체크리스트

각 항목은 `scripts/phase-gate/phase-2-gate.sh` 스크립트와 1:1 대응한다.
`(Gradle)` 표시 항목은 Gradle 테스트 러너로 위임한다.
`[SKIP]` 항목은 Kafka/Gateway 미기동 환경에서 자동 스킵된다.

### Section pre. 사전 조건

| # | 항목 | 검증 방법 | 기대 결과 |
|---|------|-----------|-----------|
| 전제 | `docker`, `curl`, `jq`, `mysql` 설치 | `command -v` | 4개 모두 존재 |
| pre | `./gradlew clean test` 전체 PASS | Gradle exit 0 | 484건 이상 |

### Section a. Sub-Gate 위임

| # | 항목 | 검증 방법 | 기대 결과 |
|---|------|-----------|-----------|
| a-1 | `phase-2a-gate.sh` | bash 위임, exit 0 | PASS — pg-service 골격 + Outbox + consumer |
| a-2 | `phase-2b-gate.sh` | bash 위임, exit 0 | PASS — business inbox 5상태 + amount |
| a-3 | `phase-2c-gate.sh` | bash 위임, exit 0 | PASS — 전환 스위치 + 잔존 삭제 |

> `SKIP_SUB_GATES=true` 설정 시 `[SKIP]` 처리. 이 경우 수동 확인 필요.

### Section b. pg-service 독립 기동

| # | 항목 | 검증 방법 | 기대 결과 |
|---|------|-----------|-----------|
| b | `pg-service /actuator/health` UP | curl | status=UP (포트 8082) |

### Section c. Kafka 왕복 E2E 테스트

| # | 항목 | 검증 방법 | 기대 결과 |
|---|------|-----------|-----------|
| c-1-TC1 | NONE → CAS 전이 + PG 호출 1회 | `PaymentConfirmConsumerTest` (Gradle) | APPROVED |
| c-1-TC2 | IN_PROGRESS → no-op (멱등성) | `PaymentConfirmConsumerTest` (Gradle) | PG 호출 0회 |
| c-1-TC3 | terminal 3종 → stored_status_result 재발행 | `PaymentConfirmConsumerTest` (Gradle) | pg_outbox 재발행 |
| c-1-TC4 | eventUUID dedupe (중복 메시지 거부) | `PaymentConfirmConsumerTest` (Gradle) | PG 호출 0회 |
| c-1-TC5 | 동시성 8스레드 → PG 1회 (CAS 원자성) | `PaymentConfirmConsumerTest` (Gradle) | callCount=1 |
| c-2-TC1 | DLQ → pg_inbox QUARANTINED + events.confirmed outbox 1건 | `PaymentConfirmDlqConsumerTest` (Gradle) | QUARANTINED+RETRY_EXHAUSTED |
| c-2-TC2 | 이미 terminal → no-op (×3) | `PaymentConfirmDlqConsumerTest` (Gradle) | pg_outbox 0건 |
| c-2-TC3 | QUARANTINED 전이 시 events.confirmed row 1건만 | `PaymentConfirmDlqConsumerTest` (Gradle) | 보상 큐 row 없음 |
| c-2-TC4 | DlqConsumer ≠ NormalConsumer 클래스 분리 | `PaymentConfirmDlqConsumerTest` (Gradle) | 클래스 비동일 |
| c-3-TC1 | APPROVED → done() + StockCommitEvent 발행 | `ConfirmedEventConsumerTest` (Gradle) | StockCommit 발행 |
| c-3-TC2 | FAILED → fail() + StockRestore 발행 | `ConfirmedEventConsumerTest` (Gradle) | StockRestore 발행 |
| c-3-TC3 | QUARANTINED → QuarantineCompensationHandler 위임 | `ConfirmedEventConsumerTest` (Gradle) | handler 1회 호출 |
| c-3-TC4 | eventUUID dedupe (markSeen false → no-op) | `ConfirmedEventConsumerTest` (Gradle) | 상태 전이 0회 |
| c-3-TC5 | 중복 수신 → publisher 0회 호출 | `ConfirmedEventConsumerTest` (Gradle) | publisher callCount=0 |

### Section d. eventUUID dedupe

| # | 항목 | 검증 방법 | 기대 결과 |
|---|------|-----------|-----------|
| d | pg-service dedupe (TC4) + payment-service dedupe (TC4/TC5) | c-1 + c-3 테스트 결과 재사용 | 양측 모두 GREEN |

> Section c 테스트로 충족. 별도 Gradle 실행 없이 c 결과를 집계한다.

### Section e. Fake PG 벤더 격리

| # | 항목 | 검증 방법 | 기대 결과 |
|---|------|-----------|-----------|
| e-1-TC1 | 성공 → APPROVED + outbox INSERT | `PgVendorCallServiceTest` (Gradle) | APPROVED |
| e-1-TC2 | retryable + attempt<4 → commands.confirm 재발행 (available_at backoff) | `PgVendorCallServiceTest` (Gradle) | ADR-30 지연 재발행 |
| e-1-TC3 | retryable + attempt>=4 → commands.confirm.dlq 발행 | `PgVendorCallServiceTest` (Gradle) | DLQ 발행 |
| e-1-TC4 | 확정 실패 → FAILED + outbox INSERT | `PgVendorCallServiceTest` (Gradle) | FAILED |
| e-1-TC5 | DLQ 원자성 (outbox INSERT + inbox 전이 same TX) | `PgVendorCallServiceTest` (Gradle) | 원자적 처리 |
| e-2-TC1 | PG DONE → APPROVED + outbox + PgOutboxReadyEvent | `PgFinalConfirmationGateTest` (Gradle) | APPROVED |
| e-2-TC2 | PG ABORTED/CANCELED/PARTIAL_CANCELED/EXPIRED → FAILED | `PgFinalConfirmationGateTest` (Gradle) | FAILED |
| e-2-TC3 | timeout → QUARANTINED(FCG_INDETERMINATE) 1회만 (재시도 0회) | `PgFinalConfirmationGateTest` (Gradle) | QUARANTINED, 재시도=0 |
| e-2-TC4 | 5xx → QUARANTINED 재시도 없음 (ADR-15 FCG 불변) | `PgFinalConfirmationGateTest` (Gradle) | QUARANTINED, getStatus=1회 |

### Section f. 2자 금액 대조

| # | 항목 | 검증 방법 | 기대 결과 |
|---|------|-----------|-----------|
| f-1-TC1 | DB 존재 + amount 일치 → stored_status_result 재발행 | `DuplicateApprovalHandlerTest` (Gradle) | 재발행 |
| f-1-TC2 | DB 존재 + amount 불일치 → QUARANTINED + AMOUNT_MISMATCH | `DuplicateApprovalHandlerTest` (Gradle) | QUARANTINED |
| f-1-TC3 | DB 부재 + amount 일치 → APPROVED + 운영 알림 | `DuplicateApprovalHandlerTest` (Gradle) | APPROVED |
| f-1-TC4 | DB 부재 + amount 불일치 → QUARANTINED + AMOUNT_MISMATCH | `DuplicateApprovalHandlerTest` (Gradle) | QUARANTINED |
| f-1-TC5 | vendor 조회 실패 → QUARANTINED(VENDOR_INDETERMINATE) | `DuplicateApprovalHandlerTest` (Gradle) | QUARANTINED |
| f-1-TC6 | NicepayStrategy 2201 → DuplicateApprovalHandler 위임 대칭성 | `DuplicateApprovalHandlerTest` (Gradle) | 위임 1회 |
| f-2-TC1 | NONE → IN_PROGRESS payload amount 기록 | `PgInboxAmountStorageTest` (Gradle) | amount 기록됨 |
| f-2-TC2 | 2자 대조 통과 → APPROVED | `PgInboxAmountStorageTest` (Gradle) | APPROVED |
| f-2-TC3 | 2자 불일치 → QUARANTINED + AMOUNT_MISMATCH | `PgInboxAmountStorageTest` (Gradle) | QUARANTINED |
| f-2-TC4 | scale>0 ArithmeticException + 음수 거부 | `PgInboxAmountStorageTest` (Gradle) | 예외 발생 |

### Section g. 토픽 파티션 수 동일 (불변식 6b)

| # | 항목 | 검증 방법 | 기대 결과 |
|---|------|-----------|-----------|
| g | payment.commands.confirm / payment.commands.confirm.dlq / payment.events.confirmed 파티션 수 동일 | `kafka-topics --describe` PartitionCount 비교 | 3개 토픽 PartitionCount 동일 |

> Kafka 미기동 환경에서는 `[SKIP]` 처리. create-topics.sh 실행 후 재검증 권장.

### Section h. Gateway /internal/** 차단

| # | 항목 | 검증 방법 | 기대 결과 |
|---|------|-----------|-----------|
| h-1 | /internal/** → 403 Forbidden (chain 중단) | `InternalOnlyGatewayFilterTest` (Gradle) | 단위 테스트 PASS |
| h-2 | 비내부 경로 → chain 위임 | `InternalOnlyGatewayFilterTest` (Gradle) | 단위 테스트 PASS |
| h-2 | /internal/pg/status/test-order → 403 (실제 HTTP) | curl -w "%{http_code}" | 403 (Gateway 기동 시만) |

> Gateway 미기동 환경에서 h-2 실제 HTTP 검증은 `[SKIP]` 처리. 단위 테스트(h-1)가 우선 판정 기준.

### Section i. payment-service cutover 잔존 검증

| # | 항목 | 검증 방법 | 기대 결과 |
|---|------|-----------|-----------|
| i-TC1 | PgStatusPort 클래스패스 부재 | `PgStatusAbsenceContractTest` (Gradle) | ClassNotFoundException |
| i-TC2 | PgStatusHttpAdapter 클래스패스 부재 | `PgStatusAbsenceContractTest` (Gradle) | ClassNotFoundException |
| i-TC3 | PaymentCommandUseCase·PaymentGatewayStrategy getStatus 메서드 부재 | `PgStatusAbsenceContractTest` (Gradle) | 메서드 목록에 미포함 |

### Section j. 전체 Gradle test

| # | 항목 | 검증 방법 | 기대 결과 |
|---|------|-----------|-----------|
| j | `./gradlew test` 전체 모듈 PASS | Gradle exit 0 | 484건 이상 |

---

## Gate 통과 기준

스크립트 종료 코드 `0` (exit 0) + 모든 FAIL 항목 없음 (`[SKIP]` 허용).

| 섹션 | 합격 기준 |
|------|-----------|
| pre | clean test 484건 이상 PASS |
| a | phase-2a/2b/2c-gate.sh 모두 exit 0 |
| b | pg-service /actuator/health UP (포트 8082) |
| c | PaymentConfirmConsumerTest 5건 + PaymentConfirmDlqConsumerTest 4건 + ConfirmedEventConsumerTest 5건 GREEN |
| d | pg-service·payment-service dedupe TC 모두 GREEN |
| e | PgVendorCallServiceTest 5건 + PgFinalConfirmationGateTest 4건 GREEN |
| f | DuplicateApprovalHandlerTest 6건 + PgInboxAmountStorageTest 4건 GREEN |
| g | 3개 토픽 PartitionCount 동일 (또는 SKIP) |
| h | InternalOnlyGatewayFilterTest PASS + 실제 HTTP 403 (또는 SKIP) |
| i | PgStatusAbsenceContractTest 3건 GREEN |
| j | ./gradlew test 484건 이상 PASS |

---

## 실패 시 복구

### pre: 전체 Gradle test 실패

```
[FAIL] pre. 전체 Gradle test (clean)
```

- 조치: `./gradlew test --info 2>&1 | grep -A 5 "FAILED"` 로 실패 테스트 확인.

### a: Sub-Gate 실패

```
[FAIL] a. phase-2a-gate.sh PASS
[FAIL] a. phase-2b-gate.sh PASS
[FAIL] a. phase-2c-gate.sh PASS
```

- 조치: `/tmp/sub_gate_output_phase-2a-gate.sh.txt` (또는 2b/2c) 로그 확인.
- 각 Sub-Gate 스크립트 개별 실행하여 실패 항목 격리.

### b: pg-service 미기동

```
[FAIL] pg-service /actuator/health → UP
```

- 조치: `./gradlew :pg-service:bootRun` 실행 후 포트 8082 확인.
- `PG_SERVICE_BASE` 환경 변수 재정의 필요 시 적용.

### c: Consumer 테스트 실패

```
[FAIL] PaymentConfirmConsumerTest
[FAIL] PaymentConfirmDlqConsumerTest
[FAIL] ConfirmedEventConsumerTest
```

- 조치:
  ```bash
  ./gradlew :pg-service:test --tests '*.PaymentConfirmConsumerTest' --info
  ./gradlew :pg-service:test --tests '*.PaymentConfirmDlqConsumerTest' --info
  ./gradlew :payment-service:test --tests '*.ConfirmedEventConsumerTest' --info
  ```
- TC5(동시성) 실패 시: `FakePgInboxRepository` CAS 원자성 구현 확인.

### e: PG 벤더 격리 테스트 실패

```
[FAIL] PgVendorCallServiceTest
[FAIL] PgFinalConfirmationGateTest
```

- 조치:
  ```bash
  ./gradlew :pg-service:test --tests '*.PgVendorCallServiceTest' --info
  ./gradlew :pg-service:test --tests '*.PgFinalConfirmationGateTest' --info
  ```
- TC3(timeout→QUARANTINED 1회): FCG에서 재시도 래핑 여부 확인 (ADR-15).

### f: 2자 금액 대조 테스트 실패

```
[FAIL] DuplicateApprovalHandlerTest
[FAIL] PgInboxAmountStorageTest
```

- 조치:
  ```bash
  ./gradlew :pg-service:test --tests '*.DuplicateApprovalHandlerTest' --info
  ./gradlew :pg-service:test --tests '*.PgInboxAmountStorageTest' --info
  ```

### g: 토픽 파티션 수 불일치

```
[FAIL] 토픽 파티션 수 동일 (불변식 6b)
```

- 조치: `create-topics.sh` 재실행 후 파티션 수 통일.
  ```bash
  docker exec payment-kafka kafka-topics \
    --bootstrap-server localhost:9092 \
    --alter --topic payment.commands.confirm.dlq --partitions <N>
  ```
- 주의: 파티션 수 감소는 지원하지 않음 — 토픽 삭제 후 재생성 필요.

### h: Gateway 차단 실패

```
[FAIL] InternalOnlyGatewayFilterTest
[FAIL] Gateway /internal/** 실제 HTTP 차단
```

- 조치:
  ```bash
  ./gradlew :gateway:test --tests '*.InternalOnlyGatewayFilterTest' --info
  ```
- 실제 HTTP 실패 시: `gateway/src/main/resources/application.yml` 내 `block-internal` 라우트 존재 여부 확인.

### i: PgStatusAbsenceContractTest 실패

```
[FAIL] PgStatusAbsenceContractTest
```

- 조치:
  ```bash
  ./gradlew :payment-service:test --tests '*.PgStatusAbsenceContractTest' --info
  ```
- 원인 1: PgStatusPort 또는 PgStatusHttpAdapter 클래스가 클래스패스에 잔존.
- 원인 2: PaymentCommandUseCase 또는 PaymentGatewayStrategy에 getStatus 계열 메서드 잔존.

---

## Phase 2 완료 의의

Phase 2 Gate 통과 시 Phase 2(PG 서비스 분리) 전체가 완료된다.

```
Phase 2.a: pg-service 골격 + Outbox 파이프라인 + consumer 기반
Phase 2.b: business inbox 5상태 + amount 컬럼 + 벤더 어댑터 통합
Phase 2.c: 전환 스위치 + 잔존 코드 삭제
Phase 2.d: 관측 대시보드 활성화 + 결제 서비스 측 이벤트 소비
Phase 2 Gate: 전 구간 E2E 통합 검증  ← 이 Gate
```

이 시점에서 다음이 보장된다.

### ADR-21 — PG 서비스 물리적 분리 완료

- payment-service는 Kafka를 통해서만 pg-service와 통신한다.
- 직접 HTTP 호출 경로 완전 제거 (PgStatusAbsenceContractTest 불변식 19 고정).
- pg-service는 독립 포트(8082), 독립 DB(mysql-pg, 포트 3308), 독립 Flyway 스키마를 가진다.

### ADR-02 — payment-service → pg-service Kafka 단방향

- payment-service: payment.commands.confirm 발행 전용.
- pg-service: payment.commands.confirm 소비 → PG 호출 → payment.events.confirmed 발행.
- payment-service: payment.events.confirmed 소비 → 결제 상태 전이.

### ADR-30 — Outbox available_at 기반 지연 재시도

- pg-service 내 PG 호출 실패 시 commands.confirm 토픽에 available_at=now+backoff로 재발행.
- RetryPolicy: MAX_ATTEMPTS=4, base=2s, multiplier=3, jitter=±25%.
- 재시도 소진(attempt>=4) 시 commands.confirm.dlq로 발행 → PaymentConfirmDlqConsumer 처리.

### ADR-15 — FCG 불변 + ADR-05 보강 2자 금액 대조

- PgFinalConfirmationGate: getStatusByOrderId 단 1회 호출, 재시도 래핑 금지.
- DuplicateApprovalHandler: pg DB 존재/부재 양 경로, 벤더 조회 실패 경로 모두 처리.
- AmountConverter: scale>0·음수 거부, longValueExact 반환.

### Gateway 내부 API 외부 노출 차단

- InternalOnlyGatewayFilter(HIGHEST_PRECEDENCE+1): /internal/** 즉시 403 반환.
- application.yml block-internal 라우트: uri=no://op, SetStatus:403.

---

## Phase 3 진입 기준

본 Gate PASS 시 Phase 3 (T3-01) 착수 가능하다.

| 기준 | 확인 방법 |
|------|-----------|
| Phase 2 Gate 스크립트 exit 0 | `bash scripts/phase-gate/phase-2-gate.sh` |
| `./gradlew test` 484건 이상 GREEN | j 섹션 결과 |
| FAIL 항목 0건 | 최종 요약 FAIL=0 |

Phase 3에서는 이 구조를 기반으로 product-service, user-service를 Kafka 이벤트로 연결한다.

```
T3-01: product-service 신규 모듈 + 도메인 이관 + stock-snapshot 발행 훅
T3-02: user-service 신규 모듈 + 도메인 이관
...
```
