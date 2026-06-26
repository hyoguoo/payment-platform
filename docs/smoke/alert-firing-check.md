# Alert Firing Check Smoke

> 영구 가이드 — Prometheus 알람 규칙 3그룹(코디네이터 정체 / 종결 가드 skip / DLQ 적체) 발화 검증.
> 스크립트(1차): `scripts/smoke/alert-rules-promtool.sh`
> 스크립트(2차 라이브 드릴): `scripts/smoke/alert-firing-coordinator.sh`, `scripts/smoke/alert-firing-dlq.sh`

## 목적

Prometheus 알람 규칙 3그룹이 의도한 조건에서 발화하고, 정상 상태에서는 발화하지 않음을 검증한다. 본 가이드는 다음에 답한다:

> "알람 규칙이 Prometheus에 로드되어 있고, 합성 시계열에서 의도한 조건에서만 발화하는가?"

## 2계층 검증 구조

| 계층 | 수단 | 선행 조건 | 보증 범위 |
|---|---|---|---|
| **1차 — 규칙 유닛** | `promtool test rules` (Docker 경유) | Docker 기동만 필요, 라이브 스택 불요 | 발화식 정확성 14케이스 단정 |
| **2차 — 라이브 드릴** | `alert-firing-*.sh` (Toxiproxy drill 프로파일) | 전체 스택 + `docker-compose.drill.yml` 기동 | 운영 환경 유사 발화 폴링 |

### 라이브 한계 명시

단일 broker 환경에서 `payment-service` 가 `commands.confirm` 의 producer 이기도 하므로:

- **consumer lag 비대칭 불가**: latency toxic 주입 시 consumer 경로만이 아닌 producer 경로(유입)도 함께 지연 → lag 피크 ~150 ≪ 임계(1000 messages). 결정적 임계 초과 불가.
- **txn abort 미발화**: 주입 지연 2000ms < `transaction.timeout.ms` 이므로 EOS commit 이 느려질 뿐 abort 미발생.
- **코디네이터 / EOS 라이브 결정적 발화 불가** → **promtool test rules (14케이스) + 통합테스트(`PaymentEosIntegrationTest` / `PgSelfLoopRetryExhaustionIntegrationTest`) 가 발화 보증의 1차 수단**. 라이브 드릴은 보조 검증.
- 규칙은 Prometheus 라이브 로드 + 운영 유효 (관측 스택 정상 기동 시 `/api/v1/rules` 에서 3그룹 확인 가능).

## 검증 항목 (14 케이스)

### 코디네이터 정체 — 5 케이스 (`coordinator_test.yml`)

| 케이스 | 입력 | 기대 |
|---|---|---|
| (a) | txn abort rate 급증 | `KafkaCoordinatorTxnAbortRising` FIRING |
| (b) | `events.confirmed` consumer lag 급증 | `KafkaCoordinatorLagHigh` FIRING |
| (c1) | `up{job="kafka-exporter"}==0` | `KafkaBrokerUnavailable` FIRING |
| (c2) | `kafka_brokers<1` | `KafkaBrokerUnavailable` FIRING |
| (d) | 정상 baseline abort rate (임계 미만) | 알람 미발화 — 알람 피로 방지 회귀 고정 |

### 종결 가드 skip — 3 케이스 (`guard_skip_test.yml`)

| 케이스 | 입력 | 기대 |
|---|---|---|
| (a) | 위험 status skip 비율 20% | `GuardSkipDangerousStatusHigh` FIRING |
| (b) | DONE-only skip (정상 재발행 경로) | 알람 미발화 |
| (c) | 저트래픽 (분모 rate=0, floor 미충족) | 알람 미발화 — 0-division 흡수 회귀 고정 |

### DLQ 적체 — 6 케이스 (`dlq_test.yml`)

| 케이스 | 입력 | 기대 |
|---|---|---|
| (a) | 앱 카운터 `increase>0` | `DlqAppCounterRising` FIRING |
| (b) | `.dlq` 토픽 offset `increase>0` | `DlqTopicOffsetRising` FIRING |
| (c) | 정상 (델타 0) | 3개 알람 모두 미발화 |
| (d) | 앱 카운터만↑ (offset 증가 없음) | `DlqAppCounterRising` 만 FIRING — 독립 회귀 고정 |
| (e) | offset만↑ (앱 카운터 증가 없음) | `DlqTopicOffsetRising` 만 FIRING — 독립 회귀 고정 |
| (f) | `commands.confirm.dlq` 컨슈머 lag 잔존 | `DlqCommandsConsumerLag` FIRING — offset-increase 0 사각 보완 |

## 사용법 — 1차 규칙 유닛 검증

```bash
# Docker 만 필요. 라이브 스택 불필요.
./scripts/smoke/alert-rules-promtool.sh

# smoke-all.sh Phase 1 에 포함 (항상 실행):
bash scripts/smoke-all.sh
```

종료 코드:
- 0 — 14 케이스 전체 PASS
- 1 — 실패 또는 Docker 미기동

## 사용법 — 2차 라이브 드릴 (수동)

라이브 드릴은 **drill 프로파일 기동이 선행 조건**이며, 단일 broker 환경 한계로 결정적 발화가 보장되지 않는다. 발화 불가 시 promtool 픽스처 격하 폴백으로 자동 전환된다.

```bash
# 1. drill 프로파일 포함 전체 스택 기동
docker compose \
  -f docker/docker-compose.infra.yml \
  -f docker/docker-compose.apps.yml \
  -f docker/docker-compose.observability.yml \
  -f docker/docker-compose.drill.yml \
  up -d

# 2. start_period 통과 대기
sleep 90

# 3. 코디네이터 알람 라이브 드릴 (toxic 주입 → 폴링 → 해제)
./scripts/smoke/alert-firing-coordinator.sh

# 격하 폴백 직행 (라이브 스택 없이 promtool 만 실행)
./scripts/smoke/alert-firing-coordinator.sh --fallback-only

# 4. DLQ 알람 — 기본은 promtool 격하 폴백
./scripts/smoke/alert-firing-dlq.sh

# Prometheus 현재 상태 라이브 폴링 추가 (실 주입 없이)
./scripts/smoke/alert-firing-dlq.sh --live
```

## 실패 케이스 해석

| FAIL 위치 | 원인 후보 | 조치 |
|---|---|---|
| Docker 미기동 | promtool 컨테이너 실행 불가 | Docker 시작 후 재실행 |
| `promtool test rules` 실패 (특정 케이스) | 규칙 파일 발화식 오류 또는 픽스처 시계열 불일치 | `observability/prometheus/rules/{coordinator,guard-skip,dlq}.yml` 확인 |
| `promtool test rules` 실패 (전 케이스) | 규칙 파일 YAML 문법 오류 | `promtool check rules <파일>` 로 문법 검사 선행 |
| 라이브 폴링 타임아웃 | 단일 broker 비대칭 한계 (lag 피크 150 ≪ 임계 1000, abort 미발화) | 격하 폴백으로 전환 — 규칙 자체는 운영 유효 |
| Toxiproxy admin API 응답 없음 | drill 프로파일 미기동 | `docker compose ... -f docker/docker-compose.drill.yml up -d` 후 재실행 |
| `coordinator.yml` 규칙 미로드 | Prometheus `rule_files` 미설정 또는 마운트 누락 | `docker compose ... -f docker/docker-compose.observability.yml up -d` 후 `/api/v1/rules` 확인 |

## 비범위

- **알람 통지 채널(Alertmanager / Slack)** — 발화 → 통지 채널 연동은 Alertmanager 설정 별도 관리
- **나머지 장애 시나리오(6종)** — 코디네이터 / 가드 skip / DLQ 3그룹 외 추가 규칙은 후속 작업
- **부하 곡선 측정** — k6 벤치마크 별도; 임계값 baseline 은 실측 후 정밀화 필요 (현재 잠정)
- **pg 경로 DLQ 라이브 드릴** — 실 벤더 sandbox 인증(secret 설정) 전제이므로 기본 환경에서 불가

## 영구성

본 가이드는 **시점에 의존하지 않는** 알람 규칙 발화 검증 절차다. promtool 픽스처가 있는 한 동일 명령으로 재현 가능하다. 새 알람 규칙 추가 시 해당 `*_test.yml` 픽스처를 작성하고 `alert-rules-promtool.sh` 의 `run_test` 호출에 추가한다.

## 관련 문서

- [`infra-healthcheck.md`](infra-healthcheck.md) — 인프라 + 4서비스 살아있음 검사
- [`trace-continuity-check.md`](trace-continuity-check.md) — 분산 트레이스 연속성 검사
- 알람 규칙 파일: `observability/prometheus/rules/{coordinator,guard-skip,dlq}.yml`
- 픽스처 파일: `observability/prometheus/rules/tests/{coordinator,guard_skip,dlq}_test.yml`
- 라이브 드릴 Toxiproxy 구성: `docker/docker-compose.drill.yml`, `docker/toxiproxy.json`
