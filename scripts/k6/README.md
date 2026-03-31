# k6 Benchmark Scripts

Sync / Outbox / Outbox-Parallel 세 전략을 저지연(100~300ms) · 고지연(800~1500ms) 두 환경에서 비교하는 k6 부하 테스트 스크립트 모음입니다.

---

## 파일 구성

| 파일 | 설명 |
|------|------|
| `helpers.js` | 공통 상수, `checkout()`, `pollStatus()`, `makeSummaryHandler()` |
| `sync.js` | Sync 전략 — 처리량(ramping) + e2e 레이턴시(부하 중) 측정 |
| `outbox.js` | Outbox 전략 — 처리량(ramping) + e2e 레이턴시(부하 중) 측정 |
| `run-benchmark.sh` | 6케이스 전략 전환 + 데이터 초기화 + k6 실행 자동화 |

---

## 측정 케이스

| 케이스 | 전략 | Toss 딜레이 |
|--------|------|------------|
| `sync-low` | sync | 100~300ms |
| `sync-high` | sync | 800~1500ms |
| `outbox-low` | outbox (sequential) | 100~300ms |
| `outbox-parallel-low` | outbox (parallel) | 100~300ms |
| `outbox-high` | outbox (sequential) | 800~1500ms |
| `outbox-parallel-high` | outbox (parallel) | 800~1500ms |

---

## 주요 메트릭

| 메트릭 | 설명 |
|--------|------|
| `confirm_requests` rate | 순수 결제 확인 TPS (폴링 요청 제외) |
| `e2e_completion_ms` | 요청 시작부터 결제 완료까지 (sync: 200 OK / outbox: DONE 폴링 완료) |
| `e2e_timeout_count` | 30초 폴링 타임아웃 발생 횟수 |

---

## 전체 자동 실행

```bash
./scripts/k6/run-benchmark.sh
```

전략 전환 → 데이터 초기화 → k6 실행을 케이스 순서대로 자동 처리합니다.

**사전 조건:**
1. Docker가 실행 중이어야 합니다
2. `./scripts/run.sh` 로 전체 스택이 기동 중이어야 합니다

---

## 개별 스크립트 실행

```bash
# macOS
docker run --rm \
  -v $(pwd)/scripts/k6:/scripts \
  -e BASE_URL=http://host.docker.internal:8080 \
  -e CASE_NAME=<케이스명> \
  grafana/k6 run /scripts/<스크립트>.js
```

---

## 결과 파일

`scripts/k6/results/` 디렉토리에 케이스별 JSON으로 저장됩니다. (gitignore 처리됨)
