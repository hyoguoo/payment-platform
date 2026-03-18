# 미완료 항목 기록

> 아카이빙 시점(2026-03-18) 기준으로 완료되지 않은 항목들입니다.

---

## 벤치마크 실행 미완료 (BENCH-02, BENCH-03)

### 현황

k6 스크립트 3종(sync.js / outbox.js / kafka.js)은 작성되었고, helpers.js JSON 경로 수정(커밋 `c3149a2`)도 적용되었다. 하지만 실제 벤치마크를 실행한 기록이 없고 `BENCHMARK.md`의 수치가 모두 `-` 자리표시자 상태다.

### 실행 전 필수 수정

`src/main/resources/application-benchmark.yml`에 아래 1줄 추가 필요:

```yaml
scheduler:
  enabled: true
```

이 설정이 없으면 Outbox 전략 벤치마크 시 `OutboxWorker`가 동작하지 않아 `GET /status`가 계속 PENDING을 반환하고 k6 pollStatus()가 30초 후 TIMEOUT된다.

### 실행 방법

```bash
# 1. benchmark 프로파일로 서버 기동
./gradlew bootRun --args='--spring.profiles.active=benchmark --spring.payment.async-strategy=sync'

# 2. k6 실행 (전략별로 반복)
k6 run k6/sync.js
k6 run k6/outbox.js
k6 run k6/kafka.js
```

### 완료 기준

- `BENCHMARK.md`에 TPS / p50 / p95 / p99 / 에러율 실측값 기입
- BENCH-02, BENCH-03 체크박스 `[x]`로 업데이트

---

## GSD 문서 미생성 Phase (Phase 6, 7)

Phase 6과 Phase 7은 코드가 직접 커밋으로 완료되었지만, GSD 워크플로(PLAN.md, SUMMARY.md, VERIFICATION.md)를 거치지 않았다.

| Phase | 커밋 | 작업 내용 |
|-------|------|---------|
| Phase 6 | `c3149a2` | helpers.js JSON 경로 수정 (`body.data.status`, `.data.orderId`) |
| Phase 7 | `a3db8b7` | `PaymentOutboxRepository.existsByOrderId()` 포트 메서드 제거 |

코드 자체는 정상 동작 확인됨.
