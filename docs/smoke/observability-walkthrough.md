# Observability 둘러보기 가이드

> 부하를 걸어놓고 **Grafana 어디서 무엇을 보고, 정상/이상을 어떻게 판단하는지** 안내한다.
> 부하 *거는 법* 자체는 [`observability-load.md`](observability-load.md) 참고. 이 문서는 그 다음, "그래서 뭘 보나".

---

## 0. 3줄 요약

- fake 모드 스택은 실 PG 호출 0으로 `checkout → confirm` 결제 플로우를 끝까지 돈다.
- 부하를 걸면 **Business**(결제 도메인)·**System**(JVM/DB/Kafka) 두 대시보드가 실수치로 채워진다.
- 트레이스는 span 자동 부착이 아니라 **로그(orderId) → traceId → Tempo** 경로로 따라간다.

---

## 1. 5분 시작 (복붙)

```bash
# 1) 스택 (이미 떠 있으면 생략)
bash scripts/compose-up.sh --mode fake

# 2) 부하 — 물결 곡선으로 15분, 두 벤더 섞어서
bash scripts/smoke/observability-load.sh --profile wave --rps-min 2 --rps-max 20 --period 90 --gateways TOSS,NICEPAY

# (터미널 닫아도 살리려면)
nohup bash scripts/smoke/observability-load.sh --profile wave --rps-min 2 --rps-max 20 --period 90 \
  > /tmp/obs-load.log 2>&1 &
echo $! > /tmp/obs-load.pid          # 멈출 때: kill -INT $(cat /tmp/obs-load.pid)
```

**열 곳**

| 도구 | 주소 | 용도 |
|---|---|---|
| Grafana | http://localhost:3000 (admin / admin123) | 대시보드 |
| Prometheus | http://localhost:9090 | 메트릭 raw 쿼리 |
| Tempo | http://localhost:3200 | 트레이스 |
| Gateway | http://localhost:8090 | 결제 API 진입 |

대시보드 바로가기:
- **Business** → http://localhost:3000/d/payment-business-d001/business-dashboard
- **System** → http://localhost:3000/d/payment-system-d001/system-dashboard

> 부하를 건 직후엔 비어 보일 수 있다. Prometheus scrape 주기(약 15초) + rate 윈도우(1m) 때문에 **1~2분** 지나야 곡선이 잡힌다.

---

## 2. Business Dashboard — 결제가 잘 흐르는가

위에서 아래로 "돈이 새지 않는가"를 읽는 순서다.

### 결제 흐름 개요
- **confirm 진입률** — 지금 들어오는 confirm 요청 속도(=부하 세기).
- **발행 vs 종결 (1m rate)** — 두 선이 **겹쳐 따라가면 정상**. 발행만 오르고 종결이 안 따라오면 어딘가 막힌 것.
- **in-flight 이벤트 수 (published − terminal)** — 처리 중 건수. **0 근처에서 출렁이면 정상**, 우상향으로 계속 쌓이면 컨슈머/워커 정체 신호.
  - 빠른 확인: `payment_event_published_total` 와 `payment_event_terminal_total` 누계가 같으면 미아(stuck) 0건.

### 상태 전이
- **상태 전이율** — `READY→IN_PROGRESS→DONE` 같은 전이 속도.
- **전이 소요 p50/p95/p99** — 꼬리(p99)가 튀면 특정 전이가 느려진 것. 점에 **exemplar 링크**가 붙어 클릭하면 그 순간의 트레이스로 점프.
- **결제 상태 분포 (`payment_state_current`)** — 지금 각 상태에 몇 건 있나. `IN_PROGRESS` 가 안 빠지고 누적되면 정체.

### 격리 (이상 경로)
- **격리 건수 / 현재 QUARANTINED** — 평상시 0. 금액 불일치·캐시 다운 등에서만 오른다.
- **confirm 가드 스킵 (`by status`)** — 멱등/중복으로 걸러낸 요청. 0이어도 패널은 보인다(카운터 eager 등록).

### 벤더 latency
- **벤더 API 응답시간 p50/p95/p99** — fake 모드에선 `FAKE_LATENCY_MIN/MAX`(기본 20~200ms) 합성값. exemplar 링크 활성.
- **벤더 API 호출량 (`toss_api_call_total`)** — 벤더별 호출 속도.

### Outbox / Cleanup / Kafka TX
- **Outbox pending_count / oldest_pending_age** — 발행 대기열. age 가 계속 커지면 워커가 못 따라가는 것.
- **Cleanup 워커 (deleted / failed)** — dedupe 정리. `failed` 가 오르면 주의.
- **Kafka TX commit / abort** — 트랜잭셔널 프로듀서 건강도. abort 가 튀면 발행 트랜잭션 실패.

---

## 3. System Dashboard — 시스템이 버티는가

부하를 올릴 때 **무엇이 먼저 무너지나**를 보는 곳. (실제 병목 측정은 충분히 센 부하가 필요 — 직렬 데모 부하로는 한계, 별도 작업.)

- **HTTP 요청률 / p95 / 5xx** — 처리량과 응답 꼬리, 오류율.
- **Hikari 커넥션풀 Active/Idle/Pending** (DB 서비스만) — **`Pending` 이 0보다 커지면 커넥션 풀 고갈** = 대표적 병목 신호.
- **Kafka Consumer Lag** — 컨슈머가 밀리는 정도. 우상향이면 소비가 생산을 못 따라감.
- **GC Pause Max / 발생률** — 길어지면 stop-the-world 로 latency 튐.
- **JVM Heap 사용률 / CPU** — 포화 여부.

> 읽는 요령: latency(HTTP p95)가 튄 순간, 같은 시각의 **Hikari Pending · Kafka Lag · GC Pause** 중 무엇이 동시에 튀었는지 보면 병목 후보가 좁혀진다.

---

## 4. 트레이스 따라가기 (로그 기반 진입)

이 스택은 결제 플로우에 커스텀 span 을 자동 부착하지 않는다. 그래서 특정 주문을 추적할 땐 **로그에서 시작**한다.

1. Grafana → **Explore** → 데이터소스 **Loki**.
2. `{service_name="payment-service"} |= "<orderId>"` 로 그 주문 로그를 찾는다.
3. 로그 라인의 `traceId` 를 복사(컨슈머 로그까지 같은 traceId 로 이어진다 — `KafkaConsumerConfig` observation).
4. 데이터소스 **Tempo** 로 바꿔 그 `traceId` 조회 → 서비스 간 호출 span 확인.

빠른 CLI 확인:
```bash
# 최근 트레이스 존재 확인
END=$(date +%s); curl -s "http://localhost:3200/api/search?start=$((END-300))&end=${END}&limit=5" | jq '.traces | length'
```

Tempo **서비스 그래프**(payment ↔ pg ↔ kafka)는 트래픽이 흐를 때만 그려지므로, 부하를 켠 뒤 1~2분 후에 본다.

---

## 5. "이 패널이 왜 비어 있지?" FAQ

| 증상 | 이유 / 대응 |
|---|---|
| 막 부하 걸었는데 다 비어 있음 | scrape(15s)+rate(1m) 지연. 1~2분 기다린다. |
| **QUARANTINED / DLQ** 가 안 켜짐 | 단순 부하론 안 난다. 격리=금액 불일치/캐시 다운, DLQ=재시도 소진. Phase-4 Toxiproxy 장애 주입의 몫. |
| 벤더 latency 가 비현실적으로 고름 | fake 합성값이라 그렇다. 범위는 `FAKE_LATENCY_MIN/MAX` 로 조정(0으로 두면 합성 지연 제거). |
| FAILED/보상 경로를 보고 싶음 | `--fail-rate 0.15` 로 부하 재시작(pg-service 를 `FAKE_FAIL_RATE` 로 recreate). |
| exemplar 점이 안 보임 | 트래픽이 있어야 샘플이 붙는다. 부하 켜고 잠시 후. |

---

## 6. 멈추기 / 내리기

```bash
# 부하만 멈춤
kill -INT $(cat /tmp/obs-load.pid)     # 또는 포그라운드면 Ctrl-C

# 스택 내리기
bash scripts/compose-up.sh --down       # 볼륨까지: --clean
```

---

## 관련 문서

- [`observability-load.md`](observability-load.md) — 부하 생성기 옵션 4축 레퍼런스
- [`trace-continuity-check.md`](trace-continuity-check.md) — 분산 트레이스 연속성 검사
- [`infra-healthcheck.md`](infra-healthcheck.md) — 인프라 + 4서비스 살아있음 검사
- 대시보드 정의: `observability/grafana/dashboards/{business,system}-dashboard.json`
