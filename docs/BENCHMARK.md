# Payment Platform — k6 벤치마크 결과

## 1. 개요

Toss Payments 결제 확인 플로우를 세 가지 비동기 전략(Sync / DB Outbox / Kafka)으로 구현하고, 동일한 부하 조건에서 TPS·레이턴시·에러율을 측정해 전략 간 성능 차이를 정량
비교한다.

---

## 2. 측정 환경 및 조건

| 항목          | 값                                          |
|-------------|--------------------------------------------|
| k6 실행 방식    | Docker (`grafana/k6`)                      |
| VU 단계       | 50 → 100 → 200 VU                          |
| 단계별 지속 시간   | 60초 (전체 3분)                                |
| 부하 패턴       | Constant load (ramping 없음)                 |
| Toss API    | `FakeTossHttpOperator` (실제 API 미사용)        |
| 전략 전환 방법    | `spring.payment.async-strategy` 값 변경 + 재기동 |
| 테스트 데이터     | `setup()`에서 1,000개 orderId 사전 생성           |
| 폴링 방식 (비동기) | 500ms 간격, 30초 타임아웃                         |

> **비고:** 비동기 전략(Outbox, Kafka)은 202 반환 후 `/status` 폴링으로 DONE 확인까지의 end-to-end 시간을 측정한다. 단순 HTTP 응답 시간이 아닌 처리 완료까지의
> 시간이므로 Sync와의 직접 비교 시 이 점을 고려해야 한다.

---

## 3. 비교 결과

> 아래 수치는 k6 실행 후 채워 넣는다. 실행 방법은 `scripts/k6/README.md`를 참고하세요.

### 50 VU

| 전략        | TPS (req/s) | p50 (ms) | p95 (ms) | p99 (ms) | 에러율 | e2e p50 (ms) | e2e p95 (ms) |
|-----------|-------------|----------|----------|----------|-----|--------------|--------------|
| Sync      | -           | -        | -        | -        | -   | N/A          | N/A          |
| DB Outbox | -           | -        | -        | -        | -   | -            | -            |
| Kafka     | -           | -        | -        | -        | -   | -            | -            |

### 100 VU

| 전략        | TPS (req/s) | p50 (ms) | p95 (ms) | p99 (ms) | 에러율 | e2e p50 (ms) | e2e p95 (ms) |
|-----------|-------------|----------|----------|----------|-----|--------------|--------------|
| Sync      | -           | -        | -        | -        | -   | N/A          | N/A          |
| DB Outbox | -           | -        | -        | -        | -   | -            | -            |
| Kafka     | -           | -        | -        | -        | -   | -            | -            |

### 200 VU

| 전략        | TPS (req/s) | p50 (ms) | p95 (ms) | p99 (ms) | 에러율 | e2e p50 (ms) | e2e p95 (ms) |
|-----------|-------------|----------|----------|----------|-----|--------------|--------------|
| Sync      | -           | -        | -        | -        | -   | N/A          | N/A          |
| DB Outbox | -           | -        | -        | -        | -   | -            | -            |
| Kafka     | -           | -        | -        | -        | -   | -            | -            |

**표 컬럼 설명:**

- **TPS** — 초당 처리 요청 수 (k6 `http_reqs` rate)
- **p50 / p95 / p99** — HTTP 응답 시간 백분위 (k6 `http_req_duration`)
- **에러율** — `check()` 실패 비율 (100% - checks%)
- **e2e p50 / e2e p95** — 비동기 전략 전용: 202 수신 ~ DONE 확인까지 end-to-end 레이턴시 (`e2e_latency_ms` 커스텀 메트릭)

---

## 4. 전략별 특성 해석

### Sync (동기 처리)

- HTTP 요청-응답 사이클 전체가 단일 스레드에서 처리됨
- Toss API 호출 시간이 직접 클라이언트 응답 지연에 반영됨 (Fake 환경에서는 제거됨)
- VU 증가 시 스레드 풀 경합으로 TPS가 선형보다 빠르게 감소할 가능성
- **장점:** 단순성, 즉각적 결과, 에러 처리 명확

### DB Outbox (배치 배달)

- confirm은 DB 쓰기만 수행해 빠르게 202 반환, Toss API는 백그라운드 워커가 처리
- 워커의 `fixedDelay` 스케줄링으로 처리 지연이 배치 단위로 발생 (e2e 레이턴시 증가)
- DB가 병목 — 동시 PENDING 레코드 증가 시 워커 처리율이 제한될 수 있음
- **장점:** 외부 API 장애 시 재시도 보장, 확실한 내구성

### Kafka (이벤트 스트리밍)

- confirm은 Kafka 발행 후 202 반환, 컨슈머 그룹이 병렬 처리
- 컨슈머 파티션 병렬성으로 Outbox보다 처리 throughput 향상 기대
- 네트워크 홉(브로커 경유)이 추가되어 초저지연은 Sync보다 불리
- **장점:** 수평 확장성, 컨슈머 독립 배포, DLT 기반 실패 격리

---

## 5. 어댑터 선택 가이드

| 시나리오                             | 추천 전략     | 이유                              |
|----------------------------------|-----------|---------------------------------|
| 응답 즉시성이 최우선, 외부 API 안정적          | Sync      | 가장 단순, 클라이언트에 즉각 결과 전달          |
| 외부 API 장애 허용·재시도 내구성 필요, 단일 인스턴스 | DB Outbox | DB 트랜잭션으로 내구성 보장, Kafka 인프라 불필요 |
| 높은 TPS + 수평 확장 + 마이크로서비스         | Kafka     | 컨슈머 파티션 병렬성, 서비스 분리 가능          |

---

## 6. 실행 방법 요약

자세한 실행 가이드는 [`scripts/k6/README.md`](../scripts/k6/README.md)를 참고하세요.

### 빠른 시작 (macOS)

```bash
# 1. 인프라 기동 (MySQL + Kafka)
docker compose -f docker/compose/docker-compose.yml up -d

# 2. 서버 기동 (benchmark 프로파일)
./gradlew bootRun --args='--spring.profiles.active=benchmark'

# 3. 전략 전환 후 run-benchmark.sh 실행
./scripts/k6/run-benchmark.sh
```

### 개별 전략 실행

```bash
# Sync 전략
docker run --rm \
  -v $(pwd)/scripts/k6:/scripts \
  -e BASE_URL=http://host.docker.internal:8080 \
  grafana/k6 run /scripts/sync.js

# DB Outbox 전략
docker run --rm \
  -v $(pwd)/scripts/k6:/scripts \
  -e BASE_URL=http://host.docker.internal:8080 \
  grafana/k6 run /scripts/outbox.js

# Kafka 전략
docker run --rm \
  -v $(pwd)/scripts/k6:/scripts \
  -e BASE_URL=http://host.docker.internal:8080 \
  grafana/k6 run /scripts/kafka.js
```

> **Linux:** `host.docker.internal` 대신 `--network host` 옵션을 사용하세요.
