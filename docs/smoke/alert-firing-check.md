# Alert Firing Check Smoke

> 영구 가이드 — Prometheus 알람 규칙 4그룹(코디네이터 정체 / 종결 가드 skip / DLQ 적체 / 가용성) 발화 검증.
> 스크립트(1차): `scripts/smoke/alert-rules-promtool.sh`
> 스크립트(2차 라이브 드릴): `scripts/smoke/alert-firing-coordinator.sh`, `scripts/smoke/alert-firing-dlq.sh`, `scripts/smoke/alert-firing-availability.sh`

## 목적

Prometheus 알람 규칙 4그룹이 의도한 조건에서 발화하고, 정상 상태에서는 발화하지 않음을 검증한다. 본 가이드는 다음에 답한다:

> "알람 규칙이 Prometheus에 로드되어 있고, 합성 시계열에서 의도한 조건에서만 발화하는가?"

## 2계층 검증 구조

| 계층 | 수단 | 선행 조건 | 보증 범위 |
|---|---|---|---|
| **1차 — 규칙 유닛** | `promtool test rules` (Docker 경유) | Docker 기동만 필요, 라이브 스택 불요 | 발화식 정확성 27케이스 단정 |
| **2차 — 라이브 드릴** | `alert-firing-*.sh` (Toxiproxy drill / docker stop 주입) | 전체 스택 기동 | 운영 환경 유사 발화 폴링 |

### 라이브 한계 명시

단일 broker 환경에서 `payment-service` 가 `commands.confirm` 의 producer 이기도 하므로:

- **consumer lag 비대칭 불가**: latency toxic 주입 시 consumer 경로만이 아닌 producer 경로(유입)도 함께 지연 → lag 피크 ~150 ≪ 임계(1000 messages). 결정적 임계 초과 불가.
- **txn abort 미발화**: 주입 지연 2000ms < `transaction.timeout.ms` 이므로 EOS commit 이 느려질 뿐 abort 미발생.
- **코디네이터 / EOS 라이브 결정적 발화 불가** → **promtool test rules (27케이스) + 통합테스트(`PaymentEosIntegrationTest` / `PgSelfLoopRetryExhaustionIntegrationTest`) 가 발화 보증의 1차 수단**. 라이브 드릴은 보조 검증.
- 규칙은 Prometheus 라이브 로드 + 운영 유효 (관측 스택 정상 기동 시 `/api/v1/rules` 에서 4그룹 확인 가능).

## 검증 항목 (27 케이스)

### 코디네이터 정체 — 6 케이스 (`coordinator_test.yml`)

| 케이스 | 입력 | 기대 |
|---|---|---|
| (a) | txn abort rate 급증 | `KafkaCoordinatorTxnAbortRising` FIRING |
| (b) | `events.confirmed` consumer lag 급증 | `KafkaCoordinatorLagHigh` FIRING |
| (c1) | `up{job="kafka-exporter"}==0` | `KafkaBrokerUnavailable` FIRING |
| (c2) | `kafka_brokers<1` | `KafkaBrokerUnavailable` FIRING |
| (c3) | `kafka_brokers` 시리즈 부재(absent) | `KafkaBrokerUnavailable` FIRING — 라이브 실측 함정: 완전 정지 시 0이 아닌 absent |
| (d) | 정상 baseline abort rate (임계 미만) | 알람 미발화 — 알람 피로 방지 회귀 고정 |

### 종결 가드 skip — 3 케이스 (`guard_skip_test.yml`)

| 케이스 | 입력 | 기대 |
|---|---|---|
| (a) | 위험 status skip 비율 20% | `GuardSkipDangerousStatusHigh` FIRING |
| (b) | DONE-only skip (정상 재발행 경로) | 알람 미발화 |
| (c) | 저트래픽 (분모 rate=0, floor 미충족) | 알람 미발화 — 0-division 흡수 회귀 고정 |

### DLQ 적체 — 9 케이스 (`dlq_test.yml`)

| 케이스 | 입력 | 기대 |
|---|---|---|
| (a) | 앱 카운터 `increase>0` (EOS 경로) | `DlqAppCounterRising` FIRING |
| (b) | `.dlq` 토픽 offset `increase>0` | `DlqTopicOffsetRising` FIRING |
| (c) | 정상 (델타 0) | 3개 알람 모두 미발화 |
| (d) | 앱 카운터만↑ (offset 증가 없음) | `DlqAppCounterRising` 만 FIRING — 독립 회귀 고정 |
| (e) | offset만↑ (앱 카운터 증가 없음) | `DlqTopicOffsetRising` 만 FIRING — 독립 회귀 고정 |
| (f) | `commands.confirm.dlq` 컨슈머 lag 잔존 | `DlqCommandsConsumerLag` FIRING — offset-increase 0 사각 보완 |
| (g) | `pg_final_confirmation_outcome_total{outcome="indeterminate"}` 만↑ (payment_eos 평탄) | `DlqAppCounterRising` FIRING — pg 분기 OR 독립 회귀 고정 |
| (h) | 자동 승인만 발생 (`outcome="approved"` 단독↑) | `DlqAppCounterRising` FIRING — 소진 건이 격리에 남지 않아도 신호가 살아있음을 고정 |
| (i) | `payment.events.stock-committed.dlq`(product-service 재고 확정 격리) offset `increase>0` | `ProductStockQuarantineBacklog` FIRING(critical) + `DlqTopicOffsetRising`(warning) 동시 발화 — 이미 과금된 결제의 재고 부족 전용 신호, 대응은 아래 "격리 적체 알람 대응 분류" 참고 |

### 가용성 — 9 케이스 (`availability_test.yml`)

| 케이스 | 입력 | 기대 |
|---|---|---|
| (a1) | `up{job=~".*-service"}==0` for:1m 충족 | `ServiceDown` FIRING |
| (a2) | `up==1` (정상) | `ServiceDown` 미발화 |
| (b) | `dependency_up{component="db"}==0` | `DependencyDown` FIRING (component 라벨 보존) |
| (c) | `dependency_up{component="redis-stock"}==0`, redis-dedupe 정상 | `DependencyDown{component="redis-stock"}` FIRING, redis-dedupe 미발화 — 컴포넌트 분리 검증 |
| (d1) | `time() - dependency_health_last_poll_timestamp_seconds > 60` | `DependencyHealthStale` FIRING |
| (d2) | 폴러 정상 (최근 갱신) | `DependencyHealthStale` 미발화 |
| (e1) | `dependency_up` 시리즈 부재 (absent) | `DependencyDown` FIRING — absent() 백스톱 (PITFALLS §24 dead-branch 회귀 고정) |
| (e2) | `dependency_health_last_poll_timestamp_seconds` 시리즈 부재 | `DependencyHealthStale` FIRING — absent() 백스톱 동형 |
| (f) | 정상 baseline 전체 | 3알람 모두 미발화 |

## 격리 적체 알람 대응 분류

`ProductStockQuarantineBacklog` 가 발화하면 `payment.events.stock-committed.dlq` 에 메시지가 도달했다는 뜻이다. 그 메시지는 **이미 벤더 승인이 나 고객이 과금된 결제**의 재고 확정이 음수 가드(`StockCommitUseCase.commitToRdb`)에 막혀 격리된 것이다. 자동 복구 대상이 아니다 — 사람이 환불 또는 재입고를 판단해야 한다.

### 두 원인을 가른다

같은 알람이 서로 다른 두 원인으로 발화할 수 있다. 관리자가 상품 서비스 재고를 수동으로 조정하면 payment 쪽 선차감 게이트와 상품 DB 가 갈라지는 것은 이 프로젝트가 이미 받아들인 정상 경로다(`docs/context/CONCERNS.md` C-10 인접, 상품 DB 입고·관리자 변경 경로). 그 발산이 초과 판매로 이어지면 이 알람도 똑같이 발화한다 — 구분하지 않으면 매번 "또 입고했나 보다"로 넘기다 진짜 게이트 결함을 놓친다.

| 구분 | 관리자 재고 조정 직후 발화 (정상 발산) | 게이트 자체 결함 (진짜 사고) |
|---|---|---|
| 시점 | 그 상품의 재고를 수동으로 늘리거나 줄인 직후 | 재고 조정 이력이 없는데 발화 |
| 범위 | 조정한 그 상품(들)에 한정 | 조정 이력 없는 상품에서도, 또는 여러 상품·여러 주문에 걸쳐 동시다발 |
| 재현성 | 게이트를 재동기화(`POST /admin/stock/resync/{productId}`)하면 더는 발화하지 않음 | 재동기화 후에도 같은 상품에서 반복 발화 |
| 조치 | 정상 경로로 종결 — 조정 담당자와 시각·수량을 대조한 뒤 그 결제 건은 환불 또는 재입고 반영으로 닫는다 | 즉시 에스컬레이션 — 재고 선차감 게이트(`PaymentTransactionCoordinator`/`StockCacheRedisAdapter`) 로직 결함을 의심한다 |

상품 서비스에는 아직 재고 조정 감사 로그가 없다 — 있다면 상품 확정 커밋도 같은 방식으로 `stock.updated_at` 을 갱신해 그 갱신만으로는 조정과 정상 판매를 구분하지 못한다. 그래서 판정은 코드가 아니라 절차로 한다.

### 확인 순서

1. 격리 메시지 키(`orderId:productId` 조합)에서 productId 를 뽑는다 — Task 13 신설 `StockCommitQuarantineRecoverer` 가 이 키로 같은 사고를 하나로 묶어 두므로, 재전송으로 여러 건이 쌓여도 조합 단위로만 조치하면 된다.
2. 그 상품에 대해 알람 발화 시각 전후로 재고 조정(입고/수동 정정)이 있었는지 담당자 또는 운영 변경 이력으로 대조한다.
3. **조정이 있었다면** — 그 결제 건은 환불 또는 재입고 반영으로 개별 종결한다. 그 상품에 대해 트래픽이 조용한 시점에 `/admin/stock/resync/{productId}` 로 게이트를 상품 DB 값에 맞춘다(`StockAdminController` Javadoc — 진행 중 선차감을 덮어쓰는 도구라 바쁜 시점 호출은 피한다).
4. **조정이 없었다면** — 재고 선차감 게이트 결함을 의심해 에스컬레이션한다. 조정 이력 없는 여러 상품·여러 주문에 동시에 번지면 결함 쪽 확률이 크다.

## 사용법 — 1차 규칙 유닛 검증

```bash
# Docker 만 필요. 라이브 스택 불필요.
./scripts/smoke/alert-rules-promtool.sh

# smoke-all.sh Phase 1 에 포함 (항상 실행):
bash scripts/smoke-all.sh
```

종료 코드:
- 0 — 27 케이스 전체 PASS
- 1 — 실패 또는 Docker 미기동

## 사용법 — 2차 라이브 드릴 (수동)

라이브 드릴은 **전체 스택 기동이 선행 조건**이다. 코디네이터 / EOS 경로는 단일 broker 환경 한계로 결정적 발화가 보장되지 않는다.

```bash
# 1. 전체 스택 기동
docker compose \
  -f docker/docker-compose.infra.yml \
  -f docker/docker-compose.apps.yml \
  -f docker/docker-compose.observability.yml \
  up -d

# 2. start_period 통과 대기
sleep 90

# 3. 코디네이터 알람 라이브 드릴 (Toxiproxy toxic 주입 → 폴링 → 해제)
./scripts/smoke/alert-firing-coordinator.sh

# 격하 폴백 직행 (라이브 스택 없이 promtool 만 실행)
./scripts/smoke/alert-firing-coordinator.sh --fallback-only

# 4. DLQ 알람 — 기본은 promtool 격하 폴백
./scripts/smoke/alert-firing-dlq.sh

# Prometheus 현재 상태 라이브 폴링 추가 (실 주입 없이)
./scripts/smoke/alert-firing-dlq.sh --live

# 5. 가용성 알람 라이브 드릴 (docker stop/start 다운 주입)
./scripts/smoke/alert-firing-availability.sh

# 격하 폴백 직행 (라이브 스택 없이 promtool 만 실행)
./scripts/smoke/alert-firing-availability.sh --fallback-only
```

### 가용성 드릴 다운 주입 상세

`alert-firing-availability.sh` 는 4 시나리오를 순차 실행한다:

| 시나리오 | 다운 주입 | 기대 알람 | 타임아웃 |
|---|---|---|---|
| (a) 서비스 프로세스 | `docker stop <payment-service 인스턴스>` | `ServiceDown` (for:1m → ~90s 소요) | 120s |
| (b) DB | `docker stop payment-mysql-payment` | `DependencyDown{component="db"}` | 60s |
| (c) redis-dedupe | `docker stop payment-redis-dedupe` | `DependencyDown{component="redis-dedupe"}` | 60s |
| (d) redis-stock | `docker stop payment-redis-stock` | `DependencyDown{component="redis-stock"}` | 60s |

각 시나리오는 발화 확인 후 `docker start` 로 복구, 해소 폴링으로 마무리된다.

### 가용성 드릴 거동 주석

- **redis-dedupe 다운 — fail-closed**: 체크아웃 멱등(`IdempotencyStore`, 포트 6379) 호출이 차단 → checkout 5xx → 결제 생성 차단. `IdempotencyStoreRedisAdapter` 에 fail-open 폴백 없음. EOS 메시지 멱등은 MySQL `payment_event_dedupe` 귀속(db 컴포넌트 의존). **outage 중 이중 과금 경로 없음 — 존재하지 않는 이중 과금 추적 불필요.**

- **redis-stock 다운**: 선차감 및 보상 경로 실패 → 선차감 stranded(재고 ≤ RDB 보수적, 과예약. over-sell 아님). 자동 재동기: TC-3 위임.

- **가시화 한계 — DLQ-stranded 및 EXPIRED 마스킹**: `docker start` 로 서비스가 복구되어도 DLQ 에 보존된 메시지 및 EXPIRED 전이한 결제 상태는 자동 회복되지 않는다. (DLQ 재주입: TQ-1, 재고 재동기: TC-3 위임)

## 실패 케이스 해석

| FAIL 위치 | 원인 후보 | 조치 |
|---|---|---|
| Docker 미기동 | promtool 컨테이너 실행 불가 | Docker 시작 후 재실행 |
| `promtool test rules` 실패 (특정 케이스) | 규칙 파일 발화식 오류 또는 픽스처 시계열 불일치 | `observability/prometheus/rules/{coordinator,guard-skip,dlq,availability}.yml` 확인 |
| `promtool test rules` 실패 (전 케이스) | 규칙 파일 YAML 문법 오류 | `promtool check rules <파일>` 로 문법 검사 선행 |
| 코디네이터 라이브 폴링 타임아웃 | 단일 broker 비대칭 한계 (lag 피크 150 ≪ 임계 1000, abort 미발화) | 격하 폴백으로 전환 — 규칙 자체는 운영 유효 |
| Toxiproxy admin API 응답 없음 | drill 프로파일 미기동 | `docker compose ... -f docker/docker-compose.drill.yml up -d` 후 재실행 |
| 가용성 드릴 `ServiceDown` 미발화 | for:1m 미충족 (타임아웃 120s 내 ~90s 대기) 또는 Prometheus 미기동 | observability 스택 기동 후 재실행 |
| 가용성 드릴 `DependencyDown` 미발화 | health 폴러 미기동 또는 payment-service 컨테이너 없음 | 앱 스택 기동 + health 폴러 설정 확인 |
| 컨테이너 미탐지 (시나리오 SKIP) | `COMPOSE_PROJECT_NAME` 불일치 | `COMPOSE_PROJECT_NAME=<실제 프로젝트명> ./alert-firing-availability.sh` 로 재실행 |
| `coordinator.yml` 규칙 미로드 | Prometheus `rule_files` 미설정 또는 마운트 누락 | `docker compose ... -f docker/docker-compose.observability.yml up -d` 후 `/api/v1/rules` 확인 |

## 비범위

- **알람 통지 채널(Alertmanager / Slack)** — 발화 → 통지 채널 연동은 Alertmanager 설정 별도 관리
- **DB/Redis 지연·부분 장애** — docker stop 완전 다운만 검증. Toxiproxy 지연 주입 확장은 별 토픽
- **DLQ-stranded 자동 회복** — DLQ 재주입(TQ-1), 선차감 재동기(TC-3) 위임; 본 가이드는 가시화까지
- **부하 곡선 측정** — k6 벤치마크 별도; 임계값 baseline 은 실측 후 정밀화 필요 (현재 잠정)
- **pg 경로 DLQ 라이브 드릴** — 실 벤더 sandbox 인증(secret 설정) 전제이므로 기본 환경에서 불가

## 영구성

본 가이드는 **시점에 의존하지 않는** 알람 규칙 발화 검증 절차다. promtool 픽스처가 있는 한 동일 명령으로 재현 가능하다. 새 알람 규칙 추가 시 해당 `*_test.yml` 픽스처를 작성하고 `alert-rules-promtool.sh` 의 `run_test` 호출에 추가한다.

## 관련 문서

- [`infra-healthcheck.md`](infra-healthcheck.md) — 인프라 + 4서비스 살아있음 검사
- [`trace-continuity-check.md`](trace-continuity-check.md) — 분산 트레이스 연속성 검사
- 알람 규칙 파일: `observability/prometheus/rules/{coordinator,guard-skip,dlq,availability}.yml`
- 픽스처 파일: `observability/prometheus/rules/tests/{coordinator,guard_skip,dlq,availability}_test.yml`
- 라이브 드릴 Toxiproxy 구성: `docker/docker-compose.drill.yml`, `docker/toxiproxy.json`
