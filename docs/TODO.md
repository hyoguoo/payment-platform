# 남은 작업

## 벤치마크 실행 및 결과 기록

### 필수 사전 수정

`src/main/resources/application-benchmark.yml`에 아래 추가 (없으면 Outbox 전략에서 OutboxWorker 미동작):

```yaml
scheduler:
  enabled: true
```

### 실행

```bash
# benchmark 프로파일로 서버 기동 (전략별로 교체)
./gradlew bootRun --args='--spring.profiles.active=benchmark --spring.payment.async-strategy=sync'

# k6 실행
k6 run k6/sync.js
k6 run k6/outbox.js
k6 run k6/kafka.js
```

### 완료 기준

- `BENCHMARK.md`에 TPS / p50 / p95 / p99 / 에러율 실측값 기입
- 3가지 전략 비교 해석 작성
