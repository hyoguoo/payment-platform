# Phase 2.c Gate — 전환 스위치 + 잔존 코드 삭제 검증

**태스크**: T2c-Gate
**날짜**: 2026-04-21
**관련 태스크**: T2c-01 ~ T2c-02 (Phase 2.c 전체)

---

## 개요

### 목적

T2c-01 ~ T2c-02 완료 후 아래 항목을 자동화 스크립트로 검증한다.

- **Phase 2.b Gate 전제** 재확인: pg-service health, mysql-pg, Flyway V1, Kafka 토픽
- **payment-service cutover 상태**: `PgStatusAbsenceContractTest` 3케이스 GREEN(불변식 19), PgStatusPort·PgStatusHttpAdapter·구버전 PaymentGatewayPort 소스 부재, `/internal/pg/status` 엔드포인트 소스 부재
- **pg-service `pg.retry.mode=outbox` 설정**: T2c-01 산출물 `application.yml` 확인
- **pg-service consumer group 등록**: `pg-service`, `pg-service-dlq` 두 그룹 모두 존재
- **Kafka 왕복 E2E**: `PaymentConfirmConsumerTest`(5케이스) + `PaymentConfirmDlqConsumerTest`(4케이스) 전부 GREEN
- **잔존 삭제 코드 부재**: `confirmPaymentWithGateway`, `getPaymentStatusByOrderId` 소스 부재
- **전체 Gradle test** 472건 이상 PASS

이를 통해 Phase 2.c 완료를 확인하고, Phase 2.d(관측 대시보드 + 결제 서비스 측 이벤트 소비) 진입 전 broken foundation을 방지한다.

### Phase 2.c의 의미

Phase 2.c는 PG 서비스 분리(Phase 2)의 마지막 단계다:

- **T2c-01**: `pg-service/src/main/resources/application.yml` 신설로 `pg.retry.mode=outbox` 스위치를 확정. ADR-30 기반 비동기 전용 경로(Kafka → pg-service → outbox 릴레이)가 유일한 활성 경로가 됨.
- **T2c-02**: payment-service 내 PG 직접 호출 잔존 코드 전면 삭제. 구버전 `PaymentGatewayPort`(getStatus/getStatusByOrderId), `InternalPaymentGatewayAdapter`, `PaymentCommandUseCase.confirmPaymentWithGateway/getPaymentStatusByOrderId`, `PaymentGatewayStrategy` getStatus 계열 삭제. `PgStatusAbsenceContractTest`(불변식 19) 3케이스로 정적 계약 고정.

Phase 2 전체(2.a + 2.b + 2.c) 완료 시의 의의:
- **PG 서비스 분리 달성**: payment-service는 Kafka를 통해서만 pg-service와 통신. 직접 HTTP 호출 경로 완전 제거.
- **ADR-02·ADR-21·ADR-30 불변식 확정**: 상태 조회는 Kafka only, PG 호출은 pg-service 책임, 재시도는 outbox available_at 지연 방식만 허용.
- **Phase 3 진입 기반 마련**: 상품·사용자 서비스 분리 단계(Phase 3)에 필요한 Kafka 이벤트 소비 패턴이 pg-service에서 검증됨.

### domain_risk 이유

- `PgStatusPort`·`PgStatusHttpAdapter` 잔존 → payment-service에서 PG 상태를 직접 HTTP로 조회하는 경로 부활, ADR-02/ADR-21 위반
- `confirmPaymentWithGateway`·`getPaymentStatusByOrderId` 잔존 → TX 내 PG 직접 호출 경로가 코드베이스에 남아 우발적 재사용 위험
- `pg.retry.mode=outbox` 미적용 → 재시도 경로 미정의 상태로 pg-service 기동, ADR-30 위반
- consumer group 미분리 → 정상 consumer와 DLQ consumer가 동일 group으로 처리 시 offset 오염

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

T2c-01에서 `pg-service/src/main/resources/application.yml`을 신설하여 **pg-service 기본 포트가 8082로 고정**되었다.
이전 Gate(2.a, 2.b)와 달리 Gateway(8080)와 포트 충돌이 발생하지 않는다.

```bash
# pg-service 단독 기동 (포트 8082 자동 사용)
./gradlew :pg-service:bootRun

# 스크립트 실행 시 기본값 PG_SERVICE_BASE=http://localhost:8082
bash scripts/phase-gate/phase-2c-gate.sh
```

### Kafka 토픽 사전 생성

아래 토픽이 모두 생성되어 있어야 한다.

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
| `PG_SERVICE_BASE` | `http://localhost:8082` | pg-service 베이스 URL (T2c-01 포트 고정) |
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

# 3. pg-service 기동 (T2c-01로 포트 8082 고정됨)
./gradlew :pg-service:bootRun

# 4. Gate 스크립트 실행
bash scripts/phase-gate/phase-2c-gate.sh
```

---

## 실행 절차

```bash
# 기본 실행 (T2c-01 application.yml으로 포트 8082 자동 사용)
bash scripts/phase-gate/phase-2c-gate.sh

# 포트 재정의 실행 (레거시 환경 대응)
PG_SERVICE_BASE=http://localhost:8082 bash scripts/phase-gate/phase-2c-gate.sh
```

---

## 검증 항목 체크리스트

각 항목은 `scripts/phase-gate/phase-2c-gate.sh` 스크립트와 1:1 대응한다.
`(Gradle)` 표시 항목은 Gradle 테스트 러너로 위임한다.

### Section a. Phase 2.b Gate 전제 확인

| # | 항목 | 검증 방법 | 기대 결과 |
|---|------|-----------|-----------|
| 전제 | `docker`, `curl`, `jq`, `mysql` 설치 | `command -v` | 4개 모두 존재 |
| a-1 | pg-service `/actuator/health` UP | curl | status=UP |
| a-2 | `payment-mysql-pg` 컨테이너 running | `docker compose ps` | State=running |
| a-3 | pg MySQL DB 접속 | `SELECT 1` | 1 반환 |
| a-4 | Flyway V1 성공 마이그레이션 | `flyway_schema_history version=1 success=1` | COUNT=1 |
| a-5 | 토픽 `payment.commands.confirm` 존재 | `kafka-topics --list` | 목록에 포함 |
| a-6 | 토픽 `payment.commands.confirm.dlq` 존재 | `kafka-topics --list` | 목록에 포함 (T2b-02) |

### Section b. payment-service cutover 상태

| # | 항목 | 검증 방법 | 기대 결과 |
|---|------|-----------|-----------|
| b-1-TC1 | `PgStatusPort` 클래스패스 부재 | `PgStatusAbsenceContractTest` (Gradle) | `ClassNotFoundException` |
| b-1-TC2 | `PgStatusHttpAdapter` 클래스패스 부재 | `PgStatusAbsenceContractTest` (Gradle) | `ClassNotFoundException` |
| b-1-TC3 | `PaymentCommandUseCase`·`PaymentGatewayStrategy` getStatus 메서드 부재 | `PgStatusAbsenceContractTest` (Gradle) | 메서드 목록에 미포함 |
| b-2 | `PgStatusPort`·`PgStatusHttpAdapter`·구버전 `PaymentGatewayPort` 소스 부재 | `grep -rl` | 결과 없음 |
| b-3 | `/internal/pg/status` 엔드포인트 소스 부재 | `grep -rl` | 결과 없음 |

### Section c. pg-service pg.retry.mode=outbox 설정

| # | 항목 | 검증 방법 | 기대 결과 |
|---|------|-----------|-----------|
| c | `application.yml` 내 `mode: outbox` 존재 | `grep "mode:"` | outbox 포함 |

### Section d. pg-service consumer group 등록

| # | 항목 | 검증 방법 | 기대 결과 |
|---|------|-----------|-----------|
| d-1 | consumer group `pg-service` 등록 | `kafka-consumer-groups --list` | 포함 (PaymentConfirmConsumer) |
| d-2 | consumer group `pg-service-dlq` 등록 | `kafka-consumer-groups --list` | 포함 (PaymentConfirmDlqConsumer — T2b-02) |

### Section e. Kafka 왕복 E2E 검증

| # | 항목 | 검증 방법 | 기대 결과 |
|---|------|-----------|-----------|
| e-1-TC1 | NONE → CAS 전이 + PG 호출 1회 | `PaymentConfirmConsumerTest` (Gradle) | APPROVED |
| e-1-TC2 | IN_PROGRESS → no-op (멱등성) | `PaymentConfirmConsumerTest` (Gradle) | PG 호출 0회 |
| e-1-TC3 | terminal 3종 → stored_status_result 재발행 (벤더 호출 금지) | `PaymentConfirmConsumerTest` (Gradle) | pg_outbox 재발행 |
| e-1-TC4 | eventUUID dedupe (중복 메시지 거부) | `PaymentConfirmConsumerTest` (Gradle) | PG 호출 0회 |
| e-1-TC5 | 동시성 8스레드 → PG 1회 (CAS 원자성) | `PaymentConfirmConsumerTest` (Gradle) | callCount=1 |
| e-2-TC1 | DLQ 메시지 → pg_inbox QUARANTINED + events.confirmed outbox 1건 | `PaymentConfirmDlqConsumerTest` (Gradle) | QUARANTINED+RETRY_EXHAUSTED |
| e-2-TC2 | 이미 terminal → no-op (APPROVED/FAILED/QUARANTINED ×3) | `PaymentConfirmDlqConsumerTest` (Gradle) | pg_outbox 0건 |
| e-2-TC3 | QUARANTINED 전이 시 events.confirmed row 1건만 | `PaymentConfirmDlqConsumerTest` (Gradle) | 보상 큐 row 없음 |
| e-2-TC4 | DlqConsumer ≠ NormalConsumer 클래스 분리 | `PaymentConfirmDlqConsumerTest` (Gradle) | 클래스 비동일 |

### Section f. 잔존 삭제 코드 부재

| # | 항목 | 검증 방법 | 기대 결과 |
|---|------|-----------|-----------|
| f-1 | `confirmPaymentWithGateway` 소스 부재 | `grep -rl` | 결과 없음 (T2c-02 삭제) |
| f-2 | `getPaymentStatusByOrderId` 소스 부재 | `grep -rl` | 결과 없음 (T2c-02 삭제) |

### Section g. 전체 Gradle test

| # | 항목 | 검증 방법 | 기대 결과 |
|---|------|-----------|-----------|
| g | `./gradlew test` 전체 모듈 PASS | Gradle exit 0 | 472건 이상 |

> T2c-02에서 테스트 16건(InternalPaymentGatewayAdapterTest, PaymentCommandUseCaseTest 4건, NicepayPaymentGatewayStrategyTest 10건 일부, PaymentGatewayFactoryTest 일부)이 삭제되어 이전 Phase 2.b Gate의 488건보다 적을 수 있다. 472건은 삭제 테스트 반영 후의 최소 기준이다.

---

## Gate 통과 기준

스크립트 종료 코드 `0` (exit 0) + 모든 항목 `[PASS]`.

| 항목 | 합격 기준 |
|------|-----------|
| Phase 2.b 전제 | pg-service UP, mysql-pg running, Flyway V1, 토픽 존재 |
| payment-service cutover | PgStatusAbsenceContractTest 3케이스 PASS, PgStatusPort 계열 소스 부재, `/internal/pg/status` 부재 |
| pg-service 설정 | `pg.retry.mode=outbox` 확인 |
| consumer group | `pg-service`, `pg-service-dlq` 모두 등록 |
| Kafka E2E | PaymentConfirmConsumerTest 5케이스 + PaymentConfirmDlqConsumerTest 4케이스 PASS |
| 잔존 코드 부재 | `confirmPaymentWithGateway`, `getPaymentStatusByOrderId` 소스 없음 |
| 전체 회귀 | `./gradlew test` 472건 이상 PASS |

---

## 실패 시 복구

### 항목별 주요 실패 원인

**a-1: pg-service 미기동**

```
[FAIL] pg-service /actuator/health → UP
```

- T2c-01 이후 pg-service는 포트 8082 사용. Gateway 포트 충돌 없음.
- 조치: `./gradlew :pg-service:bootRun` 실행 후 재시도.

**a-5/a-6: 토픽 미생성**

```
[FAIL] 토픽 존재: payment.commands.confirm
[FAIL] 토픽 존재: payment.commands.confirm.dlq
```

- 조치: `bash scripts/phase-gate/create-topics.sh` 실행 후 재시도.

**b-1: PgStatusAbsenceContractTest FAIL**

```
[FAIL] PgStatusAbsenceContractTest
```

- 조치: `./gradlew :payment-service:test --tests '*.PgStatusAbsenceContractTest' --info` 로 원인 확인.
- 원인 1: PgStatusPort 또는 PgStatusHttpAdapter 클래스가 클래스패스에 잔존.
- 원인 2: PaymentCommandUseCase 또는 PaymentGatewayStrategy에 getStatus 계열 메서드가 남아 있음.

**b-2/b-3: 소스 잔존**

```
[FAIL] PgStatusPort·PgStatusHttpAdapter·PaymentGatewayPort(구버전) 소스 부재
[FAIL] /internal/pg/status 엔드포인트 소스 부재
```

- 조치: `grep -r "PgStatusPort" payment-service/src/main/java` 로 잔존 파일 확인 후 삭제.

**c: pg.retry.mode=outbox 미설정**

```
[FAIL] pg-service application.yml — pg.retry.mode=outbox
```

- 조치: `pg-service/src/main/resources/application.yml` 내 `pg.retry.mode: outbox` 항목 확인.
- T2c-01 산출물 누락 시: `application.yml` 신설 또는 `mode: outbox` 키 추가.

**d-1/d-2: consumer group 미등록**

```
[FAIL] consumer group 존재: pg-service
[FAIL] consumer group 존재: pg-service-dlq
```

- 조치: pg-service 기동 후 Kafka 브로커에 연결되었는지 확인. `spring.kafka.bootstrap-servers` 환경 변수 설정 여부 점검.

**e-1/e-2: Consumer 테스트 FAIL**

```
[FAIL] PaymentConfirmConsumerTest
[FAIL] PaymentConfirmDlqConsumerTest
```

- 조치: `./gradlew :pg-service:test --tests '*.PaymentConfirmConsumerTest' --info` 로 원인 확인.
- e-1 TC5(동시성) 실패 시: `FakePgInboxRepository` CAS 원자성 구현 확인.
- e-2 TC4(클래스 분리) 실패 시: `PaymentConfirmDlqConsumer.class != PaymentConfirmConsumer.class` 확인.

**f-1/f-2: 잔존 코드 미삭제**

```
[FAIL] confirmPaymentWithGateway 소스 부재
[FAIL] getPaymentStatusByOrderId 소스 부재
```

- 조치: `grep -r "confirmPaymentWithGateway" payment-service/src/main` 로 잔존 파일 확인 후 삭제.
- T2c-02 완료 여부 재확인.

**g: 전체 Gradle test FAIL**

```
[FAIL] 전체 Gradle test
```

- 조치: `./gradlew test --info 2>&1 | grep -A 5 "FAILED"` 로 실패 테스트 확인.
- T2c-02 삭제 후 참조하는 테스트가 남아 있지 않은지 확인.

---

## Phase 2 전체 완료 후 의의

Phase 2.c Gate 통과 시 Phase 2(PG 서비스 분리) 전체가 완료된다.

```
Phase 2.a: pg-service 골격 + Outbox 파이프라인 + consumer 기반
Phase 2.b: business inbox 5상태 + amount 컬럼 + 벤더 어댑터 통합
Phase 2.c: 전환 스위치 + 잔존 코드 삭제  ← 이 Gate
```

이 시점에서 다음이 보장된다:

1. **payment-service**: Kafka 발행만 수행. PG HTTP 직접 호출 코드 완전 부재.
2. **pg-service**: Kafka 소비 → inbox 5상태 전이 → 벤더 HTTP 호출 → outbox 릴레이 → Kafka 재발행. 비동기 전용 경로.
3. **불변식 19 계약 테스트**: `PgStatusAbsenceContractTest` 3케이스로 payment-service 내 PG 직접 호출 경로의 부재가 정적으로 고정됨.

Phase 3(상품·사용자 서비스 분리)에서는 이 구조를 기반으로 product-service, user-service를 Kafka 이벤트로 연결한다.
