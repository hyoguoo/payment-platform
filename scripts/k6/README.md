# k6 Benchmark Scripts

세 가지 결제 어댑터 전략(Sync / Outbox / Kafka)을 동일한 부하 조건에서 비교하는 k6 부하 테스트 스크립트 모음입니다.

## 파일 구성

| 파일 | 설명 |
|------|------|
| `helpers.js` | 공통 상수, `setup()`, `pollStatus()`, `getOrderIndex()` |
| `sync.js` | Sync 전략 측정 (200 OK 기대) |
| `outbox.js` | Outbox 전략 측정 (202 → 폴링 → DONE) |
| `kafka.js` | Kafka 전략 측정 (202 → 폴링 → DONE) |
| `run-benchmark.sh` | 세 전략 순서 실행 자동화 |

## 사전 조건

1. **Docker** 가 실행 중이어야 합니다
2. **MySQL** 이 실행 중이어야 합니다 (`docker/compose` 참고)
3. **애플리케이션** 이 `benchmark` 프로파일로 실행 중이어야 합니다
   ```bash
   ./gradlew bootRun --args='--spring.profiles.active=benchmark'
   ```
4. **Kafka 전략** 측정 시: `docker/compose/docker-compose.yml` 의 Kafka 서비스도 실행 중이어야 합니다

## 개별 스크립트 실행

### macOS

```bash
# Sync 전략
docker run --rm \
  -v $(pwd)/scripts/k6:/scripts \
  -e BASE_URL=http://host.docker.internal:8080 \
  grafana/k6 run /scripts/sync.js

# Outbox 전략
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

### Linux

```bash
# Sync 전략
docker run --rm \
  --network host \
  -v $(pwd)/scripts/k6:/scripts \
  grafana/k6 run /scripts/sync.js

# Outbox 전략
docker run --rm \
  --network host \
  -v $(pwd)/scripts/k6:/scripts \
  grafana/k6 run /scripts/outbox.js

# Kafka 전략
docker run --rm \
  --network host \
  -v $(pwd)/scripts/k6:/scripts \
  grafana/k6 run /scripts/kafka.js
```

> **주의:** Linux에서는 `--network host` 를 사용합니다. macOS의 `host.docker.internal` 은 Linux에서 동작하지 않습니다.

## 전체 자동 실행

```bash
./scripts/k6/run-benchmark.sh
```

스크립트가 전략별로 일시 정지하며, 서버 재기동 후 Enter 를 누르면 다음 전략을 측정합니다.

## 전략 전환 방법

`application.yml` (또는 `application-benchmark.yml`) 에서 다음 값을 변경한 후 서버를 재기동합니다:

```yaml
spring:
  payment:
    async-strategy: sync    # sync | outbox | kafka
```

전략 전환 후 반드시 서버를 재기동해야 Spring Bean이 교체됩니다.

## 부하 조건

세 전략 모두 동일한 stages 를 사용합니다:

| 단계 | Duration | VU 수 |
|------|----------|-------|
| 1    | 60s      | 50    |
| 2    | 60s      | 100   |
| 3    | 60s      | 200   |

총 측정 시간: 3분 (+ setup 시간)

## k6 결과 읽는 법

측정 완료 후 k6 가 출력하는 summary 에서 주요 지표:

| 지표 | 설명 |
|------|------|
| `http_req_duration` p(50) | 요청의 중위 응답 시간 (ms) |
| `http_req_duration` p(95) | 상위 5% 느린 요청의 응답 시간 (ms) |
| `http_req_duration` p(99) | 상위 1% 느린 요청의 응답 시간 (ms) |
| `http_reqs` (rate) | 초당 처리 요청 수 (TPS) |
| `checks` | 성공/실패 비율 (Sync: 200 OK, Async: DONE 완료) |
| `e2e_latency_ms` p(95) | Outbox/Kafka 전략의 end-to-end 레이턴시 (202 수신~DONE 확인까지) |

### 판독 예시

```
http_req_duration...: avg=12.3ms  min=3ms    med=10ms   max=350ms  p(90)=25ms   p(95)=45ms
http_reqs...........: 12345   68.58/s
checks..............: 98.50%  ✓ 12150  ✗ 195
```

- **TPS** = `http_reqs` rate 값 (`68.58/s` → 약 68 TPS)
- **p95 응답** = `p(95)` 값 (`45ms`)
- **에러율** = `100% - checks%` (`100 - 98.50 = 1.5%`)

## 결과 기록

측정 완료 후 각 전략의 수치를 `.planning/phases/05-k6-benchmark/BENCHMARK.md` 에 기록합니다.
