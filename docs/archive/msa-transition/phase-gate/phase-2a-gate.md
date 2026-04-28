# Phase 2.a Gate — pg-service 골격 + Outbox 파이프라인 검증

**태스크**: T2a-Gate
**날짜**: 2026-04-21
**관련 태스크**: T2a-01 ~ T2a-06 (Phase 2.a 전체)

---

## 개요

### 목적

T2a-01 ~ T2a-06 완료 후 pg-service 단독 기동 상태에서 아래 항목을 자동화 스크립트로 검증한다.

- pg-service SpringBoot Actuator healthcheck
- mysql-pg 컨테이너 기동 + Flyway V1 스키마 적용 (`pg_inbox` / `pg_outbox`)
- Kafka 토픽 `payment.commands.confirm` 존재 및 `pg-service` consumer group 등록
- `PgOutboxImmediateWorker` SmartLifecycle 기동 확인
- `pg_inbox` NONE→IN_PROGRESS CAS 전이 멱등성 smoke 검증
- `pg.outbox.channel.*` Micrometer 게이지 및 JVM 메트릭 노출 확인
- `pg_outbox` clean state 확인 (기동 직후 미처리 행 0건)

이를 통해 Phase 2.b(PG 벤더 호출 + 재시도 루프 구현) 진입 전 broken foundation을 방지한다.

### domain_risk 이유

- Flyway V1 미적용 → `pg_inbox` / `pg_outbox` 부재 → consumer 수신 처리 전체 불능
- `pg-service` consumer group 미등록 → `payment.commands.confirm` 메시지가 pg-service에 도달하지 않아 inbox 전이 불가
- `PgOutboxImmediateWorker` 미기동 → ADR-30 Outbox 즉시 전달 파이프라인 불능
- CAS 전이 오작동 → NONE→IN_PROGRESS 멱등성 보장 실패 → 중복 PG 호출 위험 (불변식 4b 위반)
- 메트릭 미노출 → Phase 4 관측 대시보드 계획 기반 무효화

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
PG_SERVICE_BASE=http://localhost:8082 bash scripts/phase-gate/phase-2a-gate.sh
```

> T2b 이후 단계에서 `pg-service/src/main/resources/application.yml`을 신설하여 포트를 8082로 고정하는 것이 권장된다.

### 환경 변수

| 변수 | 기본값 | 설명 |
|------|--------|------|
| `PG_SERVICE_BASE` | `http://localhost:8080` | pg-service 베이스 URL (포트 충돌 시 재정의 필요) |
| `PG_DB_HOST` | `127.0.0.1` | pg DB 호스트 |
| `PG_DB_PORT` | `3308` | pg DB 포트 (docker-compose.infra.yml mysql-pg) |
| `PG_DB_NAME` | `pg` | pg DB 이름 |
| `PG_DB_USER` | `pg` | pg DB 사용자 |
| `PG_DB_PASS` | `payment123` | pg DB 비밀번호 |
| `KAFKA_CONTAINER` | `payment-kafka` | Kafka 컨테이너명 |
| `PG_CONTAINER_NAME` | (없음) | pg-service Docker 컨테이너명 (로그 확인용. 로컬 bootRun 시 생략 가능) |

### 서비스 기동 순서

```bash
# 1. 인프라 컨테이너 기동 (mysql-pg 포함)
docker compose -f docker-compose.infra.yml up -d

# 2. Kafka 토픽 생성 (최초 1회 — 이미 생성된 경우 생략)
bash scripts/phase-gate/create-topics.sh

# 3. pg-service 기동 (포트 8082 사용 권장)
SERVER_PORT=8082 ./gradlew :pg-service:bootRun

# 4. Gate 스크립트 실행
PG_SERVICE_BASE=http://localhost:8082 bash scripts/phase-gate/phase-2a-gate.sh
```

> pg-service가 mysql-pg와 Kafka에 연결되기까지 약 15~30초 대기 필요.
> Flyway 마이그레이션은 pg-service 최초 기동 시 자동 실행된다.

---

## 검증 항목 체크리스트

각 항목은 `scripts/phase-gate/phase-2a-gate.sh` 스크립트와 1:1 대응한다.

| # | 항목 | 검증 방법 | 기대 결과 |
|---|------|-----------|-----------|
| 전제 | `docker`, `curl`, `jq`, `mysql` 설치 | `command -v` | 4개 모두 존재 |
| 1 | pg-service `/actuator/health` UP | `curl {PG_SERVICE_BASE}/actuator/health` | status=UP |
| 2a | `payment-mysql-pg` 컨테이너 running | `docker compose ps --format json` | State=running |
| 2b | pg MySQL DB 접속 | `SELECT 1` (host=127.0.0.1, port=3308) | 1 반환 |
| 3a | Flyway V1 성공 마이그레이션 | `flyway_schema_history WHERE version=1 AND success=1` | COUNT=1 |
| 3b | Flyway 실패 마이그레이션 0건 | `success=0` COUNT | 0 |
| 3c | `pg_inbox` 테이블 존재 | `information_schema.tables` | 1 |
| 3d | `pg_outbox` 테이블 존재 | `information_schema.tables` | 1 |
| 4a | Kafka 브로커 응답 | `kafka-topics --list` | exit 0 |
| 4b | 토픽 `payment.commands.confirm` 존재 | topic list grep | 목록에 포함 |
| 5 | consumer group `pg-service` 등록 | `kafka-consumer-groups --list` | `pg-service` 포함 |
| 6a | pg-service actuator/health UP (SmartLifecycle 전제) | `curl /actuator/health` | status=UP |
| 6b | PgOutboxImmediateWorker 기동 로그 | `docker logs` grep (PG_CONTAINER_NAME 설정 시만) | "PgOutboxImmediateWorker started" |
| 7a | `pg_inbox` NONE 시드 행 INSERT | `INSERT INTO pg_inbox (NONE)` | 성공 |
| 7b | NONE→IN_PROGRESS CAS 전이 | `UPDATE WHERE status='NONE'` | ROW_COUNT=1 |
| 7c | CAS 멱등성 — 2회 시도 → ROW_COUNT=0 | `UPDATE WHERE status='NONE'` 재시도 | ROW_COUNT=0 |
| 7d | 테스트 시드 행 정리 | `DELETE FROM pg_inbox` | 완료 |
| 8a | pg-service `/actuator/prometheus` 응답 | `curl /actuator/prometheus` | 200 응답 |
| 8b | 메트릭 `pg.outbox.channel.*` | prometheus 출력 grep | 발견 |
| 8c | JVM 기본 메트릭 `jvm_*` | prometheus 출력 grep | 발견 |
| 9 | `pg_outbox` 미처리 행 0건 (clean state) | `SELECT COUNT(*) WHERE processed_at IS NULL` | 0 (WARN 처리) |

---

## 실행 절차

```bash
# 기본 실행 (pg-service 기본 포트 8080 가정 — Gateway 미기동 시)
bash scripts/phase-gate/phase-2a-gate.sh

# 포트 재정의 실행 (권장 — Gateway 와 pg-service 동시 운영 시)
PG_SERVICE_BASE=http://localhost:8082 bash scripts/phase-gate/phase-2a-gate.sh

# Docker 컨테이너로 pg-service 실행 시 로그 검증 포함
PG_SERVICE_BASE=http://localhost:8082 \
PG_CONTAINER_NAME=payment-pg-service \
bash scripts/phase-gate/phase-2a-gate.sh
```

---

## Gate 통과 기준

스크립트 종료 코드 `0` (exit 0) + 모든 항목 `[PASS]`.

| 항목 | 합격 기준 |
|------|-----------|
| pg-service 기동 | `actuator/health` status=UP |
| mysql-pg 컨테이너 | running 상태 |
| pg DB 접속 | `SELECT 1` 성공 |
| Flyway V1 | version=1 success=1, 실패 0건, pg_inbox/pg_outbox 테이블 존재 |
| Kafka | 브로커 응답 정상, `payment.commands.confirm` 토픽 존재 |
| consumer group | `pg-service` group list 에 포함 |
| CAS 전이 | NONE→IN_PROGRESS ROW_COUNT=1, 재시도 ROW_COUNT=0 (멱등성) |
| 메트릭 | `pg.outbox.channel.*` + JVM 기본 메트릭 발견 |
| pg_outbox | 미처리 행 0건 (WARN — 비필수) |

> PG_CONTAINER_NAME 미설정 시 PgOutboxImmediateWorker 로그 확인은 SKIP된다.
> 로컬 bootRun 환경에서는 수동으로 기동 로그에서 "PgOutboxImmediateWorker started" 메시지를 확인한다.

---

## 실패 시 복구

### Gate 스크립트 실패 시 정리

스크립트는 `pg_inbox` 테스트 시드 행을 자동 정리한다.
스크립트가 중단된 경우 수동 정리:

```bash
mysql -h 127.0.0.1 -P 3308 -u pg -ppayment123 pg \
  -e "DELETE FROM pg_inbox WHERE order_id LIKE 'gate2a-order-%';"
```

### 항목별 주요 실패 원인

**pg-service /actuator/health FAIL**
```
[FAIL] pg-service /actuator/health → UP
```
- 원인 1: pg-service 미기동
  - 조치: `SERVER_PORT=8082 ./gradlew :pg-service:bootRun` 실행
- 원인 2: 포트 충돌 (기본 8080, Gateway 와 동일)
  - 조치: `PG_SERVICE_BASE=http://localhost:8082` 환경 변수로 재정의 후 재실행
- 원인 3: mysql-pg 또는 Kafka 연결 실패 → pg-service 기동 자체 실패
  - 조치: `docker compose -f docker-compose.infra.yml up -d` 확인

**payment-mysql-pg 컨테이너 미기동**
```
[FAIL] 컨테이너 running: payment-mysql-pg
```
- 조치: `docker compose -f docker-compose.infra.yml up -d` 재실행

**Flyway V1 미적용**
```
[FAIL] Flyway V1 마이그레이션
```
- 원인: pg-service 미기동 (Flyway는 애플리케이션 기동 시 자동 실행)
- 조치: pg-service 재기동 후 재실행. `flyway_schema_history` 테이블에서 실패 행 확인

**consumer group pg-service 미등록**
```
[FAIL] consumer group 존재: pg-service
```
- 원인: pg-service의 `spring.kafka.bootstrap-servers` 설정 누락 → `@ConditionalOnProperty`로 `PaymentConfirmConsumer` bean 미등록
- 조치: pg-service 기동 환경에 `spring.kafka.bootstrap-servers=localhost:9092` 설정 확인

**pg_inbox CAS 전이 실패**
```
[FAIL] pg_inbox NONE→IN_PROGRESS CAS 전이
```
- 원인: `pg_inbox` 테이블 컬럼/인덱스 이상
- 조치: `SHOW CREATE TABLE pg_inbox;` 로 스키마 확인. Flyway V1 재적용

**메트릭 pg.outbox.channel.* 미발견**
```
[FAIL] 메트릭 존재: pg.outbox.channel.*
```
- 원인: `PgOutboxChannel` Micrometer 게이지 등록 실패
- 조치: pg-service 기동 로그에서 `PgOutboxChannel` 초기화 오류 확인

---

## 수동 검증 보조 SQL

Gate 스크립트 통과 후 상세 상태를 수동으로 확인할 때 사용한다.

```sql
-- pg_inbox 상태 분포 확인
SELECT status, COUNT(*) AS cnt
  FROM pg_inbox
 GROUP BY status;

-- pg_inbox 최근 10건
SELECT id, order_id, status, amount, stored_status_result, reason_code, created_at, updated_at
  FROM pg_inbox
 ORDER BY id DESC
 LIMIT 10;

-- pg_outbox 미처리(pending) 행 확인
SELECT id, topic, `key`, available_at, attempt, created_at
  FROM pg_outbox
 WHERE processed_at IS NULL
 ORDER BY available_at ASC
 LIMIT 20;

-- pg_outbox 처리 완료 행 확인
SELECT id, topic, `key`, available_at, processed_at, attempt
  FROM pg_outbox
 WHERE processed_at IS NOT NULL
 ORDER BY processed_at DESC
 LIMIT 10;

-- Flyway 마이그레이션 이력 확인
SELECT version, description, script, installed_on, success
  FROM flyway_schema_history
 ORDER BY installed_rank;
```

---

## 다음 Phase 진입 전 체크리스트

- [ ] Gate 스크립트 전 항목 `[PASS]`
- [ ] `docs/STATE.md` 활성 태스크 → T2b-01 전환 확인
- [ ] `docs/STATE.md` 비고에 "Phase 2.a 완료, Phase 2.b 진행 중" 기록 확인
- [ ] `PLAN.md` §T2a-Gate 체크박스 + 완료 결과 엔트리 확인
- [ ] `pg_inbox` / `pg_outbox` 테이블 스키마 (ADR-21 / ADR-30) 확인 완료
- [ ] `pg-service` consumer group `pg-service` Kafka에 등록 확인
- [ ] Phase 2.b 의존 — T2b-01(PG 벤더 호출 + 재시도 루프) 준비 완료
