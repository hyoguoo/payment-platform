# Phase 1 Gate — 결제 코어 E2E 검증

**태스크**: T1-Gate
**날짜**: 2026-04-21
**관련 태스크**: T1-01 ~ T1-18 (결제 코어 분리 Phase 1 전체)

---

## 개요

### 목적

T1-01 ~ T1-18 완료 후 payment-service 단독 기동 상태에서 결제 성공 / 실패 / QUARANTINED 세 경로를 자동화 스크립트로 검증한다. Gateway를 경유하는 실제 라우팅 경로(`/api/v1/payments/**` → `lb://payment-service`)와 Redis 재고 캐시 차감, Reconciler Redis↔RDB 발산 감지, 메트릭 노출, 불변식 19(`PgStatusPort` 부재)를 한 번에 확인하여 Phase 2.a(pg-service 골격 신설) 진입 전 broken foundation을 방지한다.

### domain_risk 이유

- Gateway 라우팅 오설정 → 결제 요청이 모놀리스 fallback으로 흘러 MSA 전환 의미 소실
- Redis 재고 DECR 오작동 → 재고 초과 승인 위험 (ADR-13 §2-2b-1)
- Flyway 마이그레이션 실패 → payment_event / payment_outbox 테이블 부재 → 전체 결제 불능
- `PgStatusPort` 잔존 → payment-service가 PG 상태를 직접 조회하는 ADR-21 위반 (불변식 19)
- 메트릭 미노출 → Phase 4 관측 대시보드 계획 전체 무효화

---

## 체크리스트

각 항목은 `scripts/phase-gate/phase-1-gate.sh` 스크립트와 1:1 대응한다.

| # | 항목 | 검증 방법 | 기대 결과 |
|---|------|-----------|-----------|
| 전제 | `docker`, `curl`, `jq`, `mysql` 설치 | `command -v` | 4개 모두 존재 |
| 1a | Gateway `/actuator/health` UP | `curl http://localhost:8080/actuator/health` | status=UP |
| 1b | payment-service `/actuator/health` UP | `curl http://localhost:8081/actuator/health` | status=UP |
| 1c | 결제 Redis (6380) PING | `docker exec redis-cli ping` | PONG |
| 1d | payment MySQL DB 접속 | `SELECT 1` | 1 반환 |
| 1e | Kafka 브로커 응답 | `kafka-topics --list` | exit 0 |
| 2a | Flyway 최신 버전 확인 | `flyway_schema_history` 조회 | version 존재 |
| 2b | Flyway 실패 마이그레이션 0건 | `success=0` COUNT | 0 |
| 2c | `payment_event` 테이블 존재 | `information_schema.tables` | 1 |
| 2d | `payment_outbox` 테이블 존재 | `information_schema.tables` | 1 |
| 3a~f | 테스트 데이터 시드 (3 경로 × 2 테이블) | `INSERT INTO payment_event/payment_order` | 성공 |
| 4a | 성공 경로 confirm POST | `POST /api/v1/payments/confirm` (Gateway) | 비-5xx |
| 4b | 성공 경로 상태 확인 | DB `payment_event.status` 폴링 | DONE |
| 5a | 실패 경로 confirm POST | 잘못된 paymentKey로 요청 | PG 거절 |
| 5b | 실패 경로 상태 확인 | DB `payment_event.status` 폴링 | FAILED |
| 6a | QUARANTINED 경로 confirm POST | 재고 0 상태에서 요청 | 격리 트리거 |
| 6b | QUARANTINED 경로 상태 확인 | DB `payment_event.status` 폴링 | QUARANTINED |
| 7 | Redis DECR 확인 | `GET stock:{productId}` | 10 미만으로 감소 |
| 8a | Prometheus 응답 수신 | `/actuator/prometheus` | 200 |
| 8b | Redis↔RDB 발산 카운트 | `payment_stock_cache_divergence_count_total` | 0 |
| 9a | 메트릭 존재: `payment.outbox.pending_age_seconds` | prometheus 출력 grep | 발견 |
| 9b | 메트릭 존재: `payment.stock_cache.divergence_count` | prometheus 출력 grep | 발견 |
| 10 | `PgStatusPort` 부재 (불변식 19) | `grep -rn` payment-service/src/main/ | NOT_FOUND |
| 11 | 테스트 데이터 정리 | `DELETE` + Redis `DEL` | 완료 |

---

## 사전 준비

### 필수 소프트웨어

```bash
# macOS
brew install jq mysql-client

# 또는 Docker 내 mysql CLI 사용 시 PATH 확인
which mysql
```

### 환경 변수 (기본값 사용 가능, 환경이 다를 경우 재정의)

| 변수 | 기본값 | 설명 |
|------|--------|------|
| `GATEWAY_BASE` | `http://localhost:8080` | Gateway 베이스 URL |
| `PAYMENT_SERVICE_BASE` | `http://localhost:8081` | payment-service 직접 접속 URL |
| `PAYMENT_DB_HOST` | `127.0.0.1` | payment DB 호스트 |
| `PAYMENT_DB_PORT` | `3307` | payment DB 포트 |
| `PAYMENT_DB_NAME` | `payment` | payment DB 이름 |
| `PAYMENT_DB_USER` | `payment` | payment DB 사용자 |
| `PAYMENT_DB_PASS` | `payment123` | payment DB 비밀번호 |
| `REDIS_CONTAINER` | `payment-redis-stock` | 재고 Redis 컨테이너명 |
| `ASYNC_WAIT_SECONDS` | `10` | 비동기 완료 폴링 최대 대기(초) |
| `ASYNC_POLL_INTERVAL` | `2` | 폴링 간격(초) |

### 서비스 기동 순서

```bash
# 1. 인프라 컨테이너 기동
docker compose -f docker-compose.infra.yml up -d

# 2. Kafka 토픽 생성 (최초 1회)
bash scripts/phase-gate/create-topics.sh

# 3. payment-service 기동 (포트 8081)
#    IDE 또는 CLI 실행:
./gradlew :payment-service:bootRun

# 4. Gateway 기동 (포트 8080)
./gradlew :gateway:bootRun
```

> 참고: payment-service와 Gateway가 Eureka에 등록되기까지 약 30~60초 대기 필요.
> Gateway 라우팅 확인: `curl http://localhost:8080/actuator/health`

---

## 실행 절차

```bash
# 1. 프로젝트 루트에서 실행
bash scripts/phase-gate/phase-1-gate.sh

# 2. (선택) 환경 변수 재정의 실행 예시
ASYNC_WAIT_SECONDS=20 bash scripts/phase-gate/phase-1-gate.sh

# 3. (선택) PG Sandbox 연결 없는 환경에서 헬스체크만 확인
#    실패 경로·QUARANTINED 경로는 WARN으로 처리됨 — 수동 확인 필요
```

---

## 성공 기준

스크립트 종료 코드 `0` (exit 0) + 모든 항목 `[PASS]`.

| 항목 | 합격 기준 |
|------|-----------|
| 인프라 헬스체크 | Gateway·payment-service·Redis·MySQL·Kafka 전부 UP/PONG/접속 |
| Flyway | 최신 버전 존재, 실패 마이그레이션 0건, payment_event/payment_outbox 테이블 존재 |
| 성공 경로 E2E | DB `payment_event.status = DONE` (비동기 폴링 통과) |
| 실패 경로 E2E | DB `payment_event.status = FAILED` (PG Sandbox 연결 필요) |
| QUARANTINED E2E | DB `payment_event.status = QUARANTINED` (재고 0 트리거 후) |
| Redis DECR | `stock:{productId}` < 10 (초기값 10 기준) |
| Reconciler 발산 | `payment_stock_cache_divergence_count_total = 0` |
| 메트릭 | `payment.outbox.pending_age_seconds`, `payment.stock_cache.divergence_count` 발견 |
| PgStatusPort 부재 | `grep` 결과 NOT_FOUND (불변식 19) |

> PG Sandbox 미연결 환경 주의: 실패 경로 E2E(5b)와 QUARANTINED 경로 E2E(6b)는 PG Sandbox 연결 없이 실제 FAILED/QUARANTINED 전환을 검증할 수 없다. 해당 항목은 `[WARN]`으로 표시되며 수동 확인이 필요하다. 나머지 항목이 모두 `[PASS]`이면 Phase 2.a 진입 가능 여부를 운영자가 판단한다.

---

## 실패 시 처리

### Gate 스크립트 실패 시 롤백 절차

게이트 스크립트 자체는 E2E 테스트 데이터를 자동 정리(`DELETE + Redis DEL`)한다.
스크립트가 중단된 경우 수동 정리 명령:

```bash
# 테스트 데이터 수동 정리 (PID 부분은 실제 값으로 교체)
mysql -h 127.0.0.1 -P 3307 -u payment -ppayment123 payment \
  -e "DELETE po FROM payment_order po
        JOIN payment_event pe ON po.payment_event_id = pe.id
       WHERE pe.order_id LIKE 'order-%-gate1-%';
      DELETE FROM payment_event WHERE order_id LIKE 'order-%-gate1-%';"

docker exec payment-redis-stock redis-cli DEL "stock:99901"
```

### 항목별 주요 실패 원인

**Gateway /actuator/health FAIL**
```
[FAIL] Gateway /actuator/health → UP
```
- 원인: Gateway 미기동 또는 Eureka 미연결
- 조치: `./gradlew :gateway:bootRun` 확인. 기동 후 30~60초 대기

**payment-service /actuator/health FAIL**
```
[FAIL] payment-service /actuator/health → UP
```
- 원인: payment-service 미기동 또는 DB/Redis 연결 실패
- 조치: `./gradlew :payment-service:bootRun` 확인. 로그에서 DB 연결 오류 확인

**Flyway 마이그레이션 실패**
```
[FAIL] Flyway 실패 마이그레이션 — N건 실패
```
- 원인: `V1__payment_schema.sql` 적용 오류
- 조치: `flyway_schema_history` 테이블에서 `success=0` 행 확인 후 수동 복구

**성공 경로 DONE 미전환**
```
[FAIL] 성공 경로: status = DONE
```
- 원인: OutboxImmediateWorker 미기동, Kafka 토픽 미생성, PG Sandbox 미연결
- 조치: Kafka 토픽 확인(`create-topics.sh`), payment-service 로그 확인, PG Sandbox 설정 점검

**Redis DECR 미감소**
```
[FAIL] Redis DECR: stock:{productId} 미감소
```
- 원인: 성공 경로 결제가 아직 비동기 처리 중 또는 Lua 스크립트 실행 실패
- 조치: `ASYNC_WAIT_SECONDS=30` 으로 재실행. Redis Lua 스크립트(`lua/stock_decrement.lua`) 확인

**PgStatusPort 발견**
```
[FAIL] PgStatusPort 부재 확인 — 발견됨
```
- 원인: 불변식 19 위반 — payment-service에 PgStatusPort가 잔존
- 조치: 해당 파일 즉시 제거. Phase 2.a 이전 반드시 해소

---

## 다음 Phase 진입 전 체크리스트

- [ ] 게이트 스크립트 전 항목 `[PASS]` (또는 PG Sandbox 항목 수동 확인 완료)
- [ ] `docs/STATE.md` 활성 태스크 → T2a-01 전환 확인
- [ ] `docs/STATE.md` 비고에 "Phase 1 완료" 기록 확인
- [ ] `PLAN.md` §T1-Gate ☑ 체크박스 + 완료 결과 엔트리 확인
- [ ] 불변식 19(`PgStatusPort` 부재) 확인 완료
- [ ] Phase 2.a 의존 서비스(pg-service 모듈) 신설 준비 완료
