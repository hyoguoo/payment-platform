# Phase 0 Gate — 인프라 기반 smoke 검증

**태스크**: T0-Gate  
**날짜**: 2026-04-21  
**결과**: PASS (전 항목 29/29)

---

## 개요

### 목적

T0-01~T0-05 완료 후 인프라 컨테이너 전수 healthcheck를 수행한다.
Phase 1(결제 코어 분리) 진입 전 모든 인프라 의존성이 정상인지 자동화된 스크립트로 검증하여
broken foundation 위에 태스크가 쌓이는 상황을 방지한다.

### domain_risk 이유

- Kafka 브로커 미기동 · 토픽 misconfig → Phase 1 Outbox 파이프라인 전체 불능
- Redis AOF 미설정 → 결제 멱등성 키/재고 캐시 내구성 보장 불가
- Toxiproxy 미작동 → Phase 4 장애 주입 검증 계획 전체 무효화
- 하나라도 누락되면 Phase 1~4 모든 태스크가 기반 없이 쌓이는 cascade 위험

---

## 체크리스트

각 항목은 `scripts/phase-gate/phase-0-gate.sh` 스크립트와 1:1 대응한다.

| # | 항목 | 검증 방법 | 기대 결과 |
|---|------|-----------|-----------|
| 전제 | `docker`, `curl`, `jq` 설치 | `command -v` | 3개 모두 존재 |
| 1 | 인프라 컨테이너 6개 running | `docker compose ps --format json` | State = running |
| 2 | Kafka 브로커 응답 | `docker exec kafka-topics --list` | 성공 (exit 0) |
| 3a | 토픽 `payment.commands.confirm` 존재 | topic list grep | 토픽 목록에 포함 |
| 3b | 토픽 `payment.commands.confirm.dlq` 존재 | topic list grep | 토픽 목록에 포함 |
| 3c | 토픽 `payment.events.confirmed` 존재 | topic list grep | 토픽 목록에 포함 |
| 4 | 3개 토픽 파티션 수 동일 (ADR-30) | `kafka-topics --describe` PartitionCount 파싱 | 모두 동일 |
| 5 | 공유 Redis (6379) PING | `docker exec redis-cli ping` | PONG |
| 6a | 결제 Redis (6380) PING | `docker exec redis-cli ping` | PONG |
| 6b | 결제 Redis AOF 활성화 | `CONFIG GET appendonly` | yes |
| 7a | SETNX 첫 번째 → OK | `SET key 1 NX EX 30` | OK |
| 7b | SETNX 두 번째 → (nil) | `SET key 2 NX EX 30` | (nil) |
| 7c | 테스트 키 정리 | `DEL key` | 정리 완료 |
| 8 | MySQL healthcheck | `mysqladmin ping -h localhost` | 성공 |
| 9 | Eureka `/actuator/health` UP | `curl http://localhost:8761/actuator/health` | status=UP |
| 10a | Toxiproxy `/proxies` 200 | `curl http://localhost:8474/proxies` | 200 응답 |
| 10b | `kafka-proxy` 존재 | JSON `.["kafka-proxy"]` | key 존재 |
| 10c | `mysql-proxy` 존재 | JSON `.["mysql-proxy"]` | key 존재 |
| 10d | `redis-stock-proxy` 존재 | JSON `.["redis-stock-proxy"]` | key 존재 |

---

## 실행 절차

```bash
# 1. 인프라 컨테이너 기동 (이미 기동 중이면 스킵)
docker compose -f docker-compose.infra.yml up -d

# 2. Kafka 토픽 생성 (최초 1회. 멱등 — 재실행 안전)
bash scripts/phase-gate/create-topics.sh

# 3. Gate 스크립트 실행
bash scripts/phase-gate/phase-0-gate.sh
```

### 결과 판정

- **전부 PASS (exit 0)**: Phase 1 진입 가능. `docs/STATE.md` active task → T1-01로 전환.
- **하나라도 FAIL (exit 1)**: 실패 항목 원인 수정 후 재실행. Phase 0 재수정 루프.

---

## FAIL 케이스별 자주 나오는 원인

### Kafka 토픽 미생성
```
[FAIL] 토픽 존재: payment.commands.confirm — 토픽 미생성 → create-topics.sh 재실행 필요
```
- 원인: `scripts/phase-gate/create-topics.sh` 미실행
- 조치: `bash scripts/phase-gate/create-topics.sh` 실행 후 재검증

### Kafka 브로커 미기동
```
[FAIL] Kafka 브로커 응답 (bootstrap-server localhost:9092)
```
- 원인: kafka 컨테이너 아직 시작 중 (start_period 30s) 또는 KRaft 초기화 실패
- 조치: `docker compose logs kafka` 확인. 30~60초 대기 후 재시도

### 결제 Redis AOF 비활성화
```
[FAIL] 결제 Redis AOF 활성화 — appendonly=no
```
- 원인: `docker-compose.infra.yml`의 `redis-stock` command에서 `--appendonly yes` 누락
- 조치: compose 설정 확인 후 `docker compose up -d redis-stock`

### MySQL 미기동
```
[FAIL] MySQL mysqladmin ping
```
- 원인: MySQL 초기화 중 (start_period 20s)
- 조치: 20~30초 대기 후 재시도. `docker compose logs mysql` 확인

### Eureka 미기동
```
[FAIL] Eureka actuator health → UP
```
- 원인: Spring Boot 기동 중 (start_period 30s). JVM cold start
- 조치: 30~60초 대기. `docker compose logs eureka` 확인

### Toxiproxy /proxies 응답 없음
```
[FAIL] Toxiproxy /proxies 200 응답
```
- 원인: toxiproxy 컨테이너 미기동 또는 `chaos/toxiproxy-config.json` 마운트 실패
- 조치: `docker compose logs toxiproxy` 확인. config 파일 경로 점검

### Toxiproxy proxy 키 없음
```
[FAIL] Toxiproxy proxy 존재: kafka-proxy
```
- 원인: `chaos/toxiproxy-config.json`에 해당 proxy 미정의
- 조치: `chaos/toxiproxy-config.json` 확인. kafka-proxy/mysql-payment-proxy/redis-stock-proxy 3개 모두 있어야 함

---

## 프로덕션 전환 시 알려진 편차

| 항목 | 로컬 (현재) | 프로덕션 (전환 시) |
|------|-------------|---------------------|
| `replication.factor` | 1 (단일 브로커) | 3 (3-broker 클러스터) |
| `min.insync.replicas` | 1 | 2 |
| `KAFKA_DEFAULT_REPLICATION_FACTOR` | 1 | 3 |
| `KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR` | 1 | 3 |

로컬 단일 브로커 환경에서는 `replication.factor=1`이 정상이다.
프로덕션 배포 전 `docker-compose.infra.yml` 주석 처리된 프로덕션 값 참조.

> ADR-30 파티션 동일 수 전제: 3개 토픽 모두 동일 파티션 수를 유지해야 consumer 병렬성이 대칭을 이룬다.
> 파티션 수 변경 시 반드시 3개 토픽을 일괄 변경한다.
