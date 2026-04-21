# Phase 2.b Gate — business inbox 5상태 + 벤더 어댑터 통합 검증

**태스크**: T2b-Gate
**날짜**: 2026-04-21
**관련 태스크**: T2b-01 ~ T2b-05 (Phase 2.b 전체)

---

## 개요

### 목적

T2b-01 ~ T2b-05 완료 후 아래 항목을 자동화 스크립트로 검증한다.

- **Phase 2.a Gate 전제** 재확인: pg-service health, mysql-pg, Flyway V1, Kafka DLQ 토픽, DLQ consumer group
- **중복 승인 시나리오 (Fake 벤더)**: Toss ALREADY_PROCESSED_PAYMENT / NicePay 2201 → DuplicateApprovalHandler 진입, amount 일치 재발행 / 불일치 QUARANTINED
- **pg DB 부재 경로**: inbox 미존재 상태에서 vendor amount 일치 → APPROVED + 운영 알림, 불일치 → QUARANTINED+AMOUNT_MISMATCH
- **FCG 불변 (ADR-15)**: FakePgGatewayAdapter timeout/5xx 주입 → `getStatus` 호출 1회, QUARANTINED(FCG_INDETERMINATE), 재시도 0회
- **재시도 루프 (ADR-30)**: retryable 오류 + attempt<4 → `pg_outbox(commands.confirm, available_at>now)`, attempt≥4 → `pg_outbox(commands.confirm.dlq)`
- **DLQ consumer**: `payment.commands.confirm.dlq` 수신 → `pg_inbox` QUARANTINED + `pg_outbox(events.confirmed)` 1건
- **inbox amount 저장 규약**: NONE→IN_PROGRESS 전이 시 `pg_inbox.amount` = payload amount, BigDecimal scale>0 거부, 2자 대조 불일치 → AMOUNT_MISMATCH
- **전체 Gradle test** 488건 이상 PASS

이를 통해 Phase 2.c(전환 스위치 + 기존 reconciler 삭제) 진입 전 broken foundation을 방지한다.

### domain_risk 이유

- DuplicateApprovalHandler 오작동 → 중복 승인으로 이미 처리된 결제를 재처리, 또는 금액 불일치 검증 누락
- FCG 재시도 래핑 → ADR-15 위반, 벤더 getStatus가 2회 이상 호출되어 상태 판단 오염
- ADR-30 재시도 outbox `available_at` 지연 미적용 → 벤더 연속 호출로 DDoS 위험
- DLQ consumer 미분리 → 정상 consumer와 동일 group으로 DLQ 처리 시 offset 오염
- `pg_inbox.amount` 컬럼 부재 / scale>0 허용 → 2자 금액 대조 불능, 원화 단위 오류 발생

---

## 전제 조건

### 필수 소프트웨어

```bash
# macOS
brew install jq mysql-client

# 또는 Docker 내 mysql CLI 사용 시 PATH 확인
which mysql
```

### 포트 주의 사항

pg-service에는 `application.yml`이 없으므로 Spring Boot 기본 포트 **8080**이 사용된다.
Gateway도 8080을 점유하므로 동시 기동 시 충돌이 발생한다.

**해결방법**:

```bash
# 방법 1: Gateway 종료 후 pg-service 단독 기동
./gradlew :pg-service:bootRun

# 방법 2: 포트 재정의 환경 변수로 pg-service 기동
SERVER_PORT=8082 ./gradlew :pg-service:bootRun
# 그 후 스크립트 실행 시:
PG_SERVICE_BASE=http://localhost:8082 bash scripts/phase-gate/phase-2b-gate.sh
```

> T2c-01 단계에서 `pg-service/src/main/resources/application.yml`을 신설하여 포트를 8082로 고정할 예정.

### Kafka 토픽 사전 생성

Phase 2.b에서 신설된 DLQ 토픽을 포함하여 아래 토픽이 모두 생성되어 있어야 한다.

```bash
bash scripts/phase-gate/create-topics.sh
```

| 토픽 | 용도 |
|------|------|
| `payment.commands.confirm` | 정상 확인 요청 (Phase 2.a) |
| `payment.commands.confirm.dlq` | 재시도 소진 DLQ (T2b-02 신설) |
| `payment.events.confirmed` | 최종 결과 이벤트 |

### 환경 변수

| 변수 | 기본값 | 설명 |
|------|--------|------|
| `PG_SERVICE_BASE` | `http://localhost:8080` | pg-service 베이스 URL |
| `PG_DB_HOST` | `127.0.0.1` | pg DB 호스트 |
| `PG_DB_PORT` | `3308` | pg DB 포트 |
| `PG_DB_NAME` | `pg` | pg DB 이름 |
| `PG_DB_USER` | `pg` | pg DB 사용자 |
| `PG_DB_PASS` | `payment123` | pg DB 비밀번호 |
| `KAFKA_CONTAINER` | `payment-kafka` | Kafka 컨테이너명 |

### 서비스 기동 순서

```bash
# 1. 인프라 컨테이너 기동 (mysql-pg 포함)
docker compose -f docker-compose.infra.yml up -d

# 2. Kafka 토픽 생성 (DLQ 토픽 포함)
bash scripts/phase-gate/create-topics.sh

# 3. pg-service 기동 (포트 8082 사용 권장)
SERVER_PORT=8082 ./gradlew :pg-service:bootRun

# 4. Gate 스크립트 실행
PG_SERVICE_BASE=http://localhost:8082 bash scripts/phase-gate/phase-2b-gate.sh
```

---

## 실행 절차

```bash
# 기본 실행 (pg-service 기본 포트 8080 가정 — Gateway 미기동 시)
bash scripts/phase-gate/phase-2b-gate.sh

# 포트 재정의 실행 (권장)
PG_SERVICE_BASE=http://localhost:8082 bash scripts/phase-gate/phase-2b-gate.sh
```

---

## 검증 항목 체크리스트

각 항목은 `scripts/phase-gate/phase-2b-gate.sh` 스크립트와 1:1 대응한다.
`(Gradle)` 표시 항목은 Gradle 테스트 러너로 위임한다.

### Section a. Phase 2.a Gate 전제 확인

| # | 항목 | 검증 방법 | 기대 결과 |
|---|------|-----------|-----------|
| 전제 | `docker`, `curl`, `jq`, `mysql` 설치 | `command -v` | 4개 모두 존재 |
| a-1 | pg-service `/actuator/health` UP | curl | status=UP |
| a-2 | `payment-mysql-pg` 컨테이너 running | `docker compose ps` | State=running |
| a-3 | pg MySQL DB 접속 | `SELECT 1` | 1 반환 |
| a-4 | Flyway V1 성공 마이그레이션 | `flyway_schema_history version=1 success=1` | COUNT=1 |
| a-5 | `pg_inbox.amount` 컬럼 존재 | `information_schema.columns` | 1 (T2b-04 ADR-21) |
| a-6 | `pg_inbox.reason_code` 컬럼 존재 | `information_schema.columns` | 1 |
| a-7 | `pg_outbox.available_at` 컬럼 존재 | `information_schema.columns` | 1 (ADR-30) |
| a-8 | 토픽 `payment.commands.confirm.dlq` 존재 | `kafka-topics --list` | 목록에 포함 |
| a-9 | consumer group `pg-service-dlq` 등록 | `kafka-consumer-groups --list` | 포함 (T2b-02) |

### Section b. 중복 승인 시나리오 (Fake 벤더)

| # | 항목 | 검증 방법 | 기대 결과 |
|---|------|-----------|-----------|
| b-TC1 | pg DB 존재 + amount 일치 → stored_status_result 재발행 | `DuplicateApprovalHandlerTest` (Gradle) | `pg_outbox(events.confirmed)` 1건, 원본 payload |
| b-TC2 | pg DB 존재 + amount 불일치 → QUARANTINED+AMOUNT_MISMATCH | `DuplicateApprovalHandlerTest` (Gradle) | `pg_inbox.status=QUARANTINED`, `reason_code=AMOUNT_MISMATCH` |
| b-TC3 | pg DB 부재 + amount 일치 → APPROVED + 운영 알림 | `DuplicateApprovalHandlerTest` (Gradle) | `pg_inbox 신설(APPROVED)`, `pg_outbox(APPROVED)` |
| b-TC4 | pg DB 부재 + amount 불일치 → QUARANTINED+AMOUNT_MISMATCH | `DuplicateApprovalHandlerTest` (Gradle) | `pg_inbox 신설(QUARANTINED)` |
| b-TC5 | vendor 조회 실패(timeout) → QUARANTINED+VENDOR_INDETERMINATE | `DuplicateApprovalHandlerTest` (Gradle) | `reason_code=VENDOR_INDETERMINATE` |
| b-TC6 | NicepayStrategy 2201 → DuplicateApprovalHandler 위임 | `DuplicateApprovalHandlerTest` (Gradle) | `handleDuplicateApproval()` 1회 호출 |

### Section c. pg DB 부재 경로 확인

| # | 항목 | 검증 방법 | 기대 결과 |
|---|------|-----------|-----------|
| c | `pg_inbox.status` 컬럼 (5상태 ENUM) 존재 | `information_schema.columns` | 1 |

> TC3/TC4는 Section b의 `DuplicateApprovalHandlerTest`에서 함께 검증된다.

### Section d. FCG 불변 (재시도 래핑 금지)

| # | 항목 | 검증 방법 | 기대 결과 |
|---|------|-----------|-----------|
| d-TC1 | getStatus APPROVED → `pg_inbox` APPROVED + `pg_outbox(APPROVED)` | `PgFinalConfirmationGateTest` (Gradle) | APPROVED 전이 |
| d-TC2 | getStatus FAILED → `pg_inbox` FAILED + `pg_outbox(FAILED)` | `PgFinalConfirmationGateTest` (Gradle) | FAILED 전이 |
| d-TC3 | timeout → QUARANTINED(FCG_INDETERMINATE), `getStatus` 호출 1회 | `PgFinalConfirmationGateTest` (Gradle) | callCount=1 |
| d-TC4 | 5xx → QUARANTINED(FCG_INDETERMINATE), 재시도 0회 | `PgFinalConfirmationGateTest` (Gradle) | callCount=1 |

### Section e. 재시도 루프 (ADR-30)

| # | 항목 | 검증 방법 | 기대 결과 |
|---|------|-----------|-----------|
| e-TC1 | 벤더 성공 → APPROVED outbox + pg_inbox APPROVED | `PgVendorCallServiceTest` (Gradle) | APPROVED |
| e-TC2 | retryable + attempt=1 → `commands.confirm` + `available_at`>now + `attempt=2` | `PgVendorCallServiceTest` (Gradle) | topic=commands.confirm, future available_at |
| e-TC3 | retryable + attempt=4(MAX) → `commands.confirm.dlq` | `PgVendorCallServiceTest` (Gradle) | topic=commands.confirm.dlq |
| e-TC4 | non-retryable → FAILED outbox + pg_inbox FAILED | `PgVendorCallServiceTest` (Gradle) | FAILED |
| e-TC5 | attempt 소진 DLQ 원자성 | `PgVendorCallServiceTest` (Gradle) | DLQ row 정확히 1건 |
| e-extra | `RetryPolicy` shouldRetry 경계값 + computeBackoff jitter 범위 | `RetryPolicyTest` (Gradle) | MAX_ATTEMPTS=4, attempt<4=true |

### Section f. DLQ consumer QUARANTINED 전이

| # | 항목 | 검증 방법 | 기대 결과 |
|---|------|-----------|-----------|
| f-TC1 | DLQ 메시지 → pg_inbox QUARANTINED + events.confirmed 1건 | `PaymentConfirmDlqConsumerTest` (Gradle) | QUARANTINED+RETRY_EXHAUSTED |
| f-TC2 | 이미 terminal → no-op (APPROVED/FAILED/QUARANTINED ×3) | `PaymentConfirmDlqConsumerTest` (Gradle) | pg_outbox 0건 |
| f-TC3 | QUARANTINED 전이 시 events.confirmed row 1건만 | `PaymentConfirmDlqConsumerTest` (Gradle) | 보상 큐 row 없음 |
| f-TC4 | DlqConsumer ≠ NormalConsumer 클래스 분리 | `PaymentConfirmDlqConsumerTest` (Gradle) | 클래스 비동일 |

### Section g. inbox amount 저장 규약

| # | 항목 | 검증 방법 | 기대 결과 |
|---|------|-----------|-----------|
| g-1 | `AmountConverter` null/scale>0/음수 거부 + 정상 변환 | `AmountConverterTest` (Gradle) | 예외 발생 / long 반환 |
| g-2 | `PgInboxAmountService` 3경로 (recordPayloadAmount / validateAndApprove / mismatch→QUARANTINED) | `PgInboxAmountStorageTest` (Gradle) | 불변식 4c 전체 |
| g-3 | `pg_inbox.amount` 타입 = BIGINT | `information_schema.columns` | DATA_TYPE=bigint (WARN) |
| g-4 | NONE→IN_PROGRESS + amount 기록 smoke | DB INSERT/UPDATE | amount 값 일치 |
| g-4-cleanup | 시드 행 정리 | DELETE | 완료 |

### Section h. 전체 Gradle test

| # | 항목 | 검증 방법 | 기대 결과 |
|---|------|-----------|-----------|
| h | `./gradlew test` 전체 모듈 PASS | Gradle exit 0 | 488건 이상 |

---

## Gate 통과 기준

스크립트 종료 코드 `0` (exit 0) + 모든 항목 `[PASS]`.

| 항목 | 합격 기준 |
|------|-----------|
| Phase 2.a 전제 | pg-service UP, mysql-pg running, Flyway V1, DLQ 토픽 + consumer group |
| 스키마 보강 | `pg_inbox.amount(BIGINT)`, `pg_inbox.reason_code`, `pg_outbox.available_at` 존재 |
| 중복 승인 시나리오 | DuplicateApprovalHandlerTest 6케이스 PASS (amount 일치/불일치 × DB 존재/부재 × vendor 실패) |
| FCG 불변 | PgFinalConfirmationGateTest 4케이스 PASS, `getStatus` 1회, 재시도 0회 |
| 재시도 루프 | PgVendorCallServiceTest 5케이스 PASS, `available_at` 지연 확인, DLQ 원자성 |
| DLQ consumer | PaymentConfirmDlqConsumerTest 4케이스 PASS, QUARANTINED + no-op(terminal) |
| amount 저장 규약 | AmountConverterTest + PgInboxAmountStorageTest PASS, DB smoke 성공 |
| 전체 회귀 | `./gradlew test` 488건 이상 PASS |

---

## 실패 시 복구

### Gate 스크립트 시드 행 정리

스크립트 중단 시 수동 정리:

```bash
mysql -h 127.0.0.1 -P 3308 -u pg -ppayment123 pg \
  -e "DELETE FROM pg_inbox WHERE order_id LIKE 'gate2b-order-%';"
```

### 항목별 주요 실패 원인

**a-8/a-9: DLQ 토픽 / consumer group 미생성**

```
[FAIL] 토픽 존재: payment.commands.confirm.dlq
[FAIL] consumer group 존재: pg-service-dlq
```

- 조치: `bash scripts/phase-gate/create-topics.sh` 실행 후 pg-service 재기동

**b. DuplicateApprovalHandlerTest FAIL**

```
[FAIL] DuplicateApprovalHandlerTest
```

- 조치: `./gradlew :pg-service:test --tests '*.DuplicateApprovalHandlerTest' --info` 로 원인 확인
- 원인 1: `FakePgGatewayAdapter` status 결과 미설정
- 원인 2: `DuplicateApprovalHandler` pg DB 부재 경로 분기 오류

**d. PgFinalConfirmationGateTest FAIL — FCG 불변 위반**

```
[FAIL] PgFinalConfirmationGateTest
```

- 조치: `./gradlew :pg-service:test --tests '*.PgFinalConfirmationGateTest' --info`
- 확인 사항: `getStatusByOrderId` callCount == 1 (재시도 0회 불변식)

**e. PgVendorCallServiceTest FAIL — available_at 지연 미적용**

```
[FAIL] PgVendorCallServiceTest
```

- 조치: `./gradlew :pg-service:test --tests '*.PgVendorCallServiceTest' --info`
- 확인 사항: `pg_outbox.available_at > now` 검증 실패 시 `PgVendorCallService.callVendor()` 재시도 경로 확인

**f. PaymentConfirmDlqConsumerTest FAIL — DLQ consumer 분리 누락**

```
[FAIL] PaymentConfirmDlqConsumerTest
```

- 조치: `PaymentConfirmDlqConsumer.class != PaymentConfirmConsumer.class` 클래스 분리 확인

**h. 전체 Gradle test FAIL — 회귀 발생**

```
[FAIL] 전체 Gradle test
```

- 조치: `./gradlew test --info 2>&1 | grep -A 5 "FAILED"` 로 실패 테스트 확인

---

## 수동 검증 보조 SQL

Gate 스크립트 통과 후 상세 상태를 수동으로 확인할 때 사용한다.

```sql
-- pg_inbox 상태 분포 확인 (5상태 ENUM)
SELECT status, COUNT(*) AS cnt
  FROM pg_inbox
 GROUP BY status;

-- pg_inbox amount · reason_code 분포 확인
SELECT status, reason_code, COUNT(*) AS cnt, AVG(amount) AS avg_amount
  FROM pg_inbox
 GROUP BY status, reason_code
 ORDER BY cnt DESC;

-- pg_inbox 최근 10건 (amount 포함)
SELECT id, order_id, status, amount, stored_status_result, reason_code, created_at, updated_at
  FROM pg_inbox
 ORDER BY id DESC
 LIMIT 10;

-- pg_outbox available_at · topic 분포 (재시도 루프 ADR-30 확인)
SELECT topic,
       COUNT(*) AS total,
       SUM(CASE WHEN available_at > NOW(6) THEN 1 ELSE 0 END) AS future_count,
       SUM(CASE WHEN processed_at IS NULL THEN 1 ELSE 0 END) AS pending_count
  FROM pg_outbox
 GROUP BY topic;

-- pg_outbox 미처리(pending) + 미래 available_at 행 확인 (재시도 대기 중)
SELECT id, topic, `key`, available_at, attempt, created_at
  FROM pg_outbox
 WHERE processed_at IS NULL
   AND available_at > NOW(6)
 ORDER BY available_at ASC
 LIMIT 20;

-- pg_outbox DLQ topic 확인
SELECT id, topic, `key`, available_at, attempt, created_at
  FROM pg_outbox
 WHERE topic = 'payment.commands.confirm.dlq'
 ORDER BY id DESC
 LIMIT 10;

-- pg_inbox QUARANTINED reason_code 분포 확인
SELECT reason_code, COUNT(*) AS cnt
  FROM pg_inbox
 WHERE status = 'QUARANTINED'
 GROUP BY reason_code
 ORDER BY cnt DESC;

-- Flyway 마이그레이션 이력 확인
SELECT version, description, script, installed_on, success
  FROM flyway_schema_history
 ORDER BY installed_rank;
```

---

## 다음 Phase 진입 전 체크리스트

- [ ] Gate 스크립트 전 항목 `[PASS]`
- [ ] `docs/STATE.md` 활성 태스크 → T2c-01 전환 확인
- [ ] `docs/STATE.md` 비고에 "Phase 2.b 완료, Phase 2.c 진행 중" 기록 확인
- [ ] `PLAN.md` §T2b-Gate 체크박스 + 완료 결과 엔트리 확인
- [ ] `pg_inbox.amount(BIGINT)` + `reason_code` + `pg_outbox.available_at` 스키마 확인
- [ ] `payment.commands.confirm.dlq` Kafka 토픽 + `pg-service-dlq` consumer group 확인
- [ ] Phase 2.c 의존 — T2c-01(`pg.retry.mode=outbox` 활성화 스위치) 준비 완료
