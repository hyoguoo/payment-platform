# K6 네트워크 지연 시나리오 설계

> 최종 수정: 2026-03-27

---

## 문제 정의

현재 벤치마크는 Toss API 가짜 딜레이를 100~300ms(avg ~200ms)로 고정해 측정한다.
이 값은 Stripe median(~120ms) 수준으로, 카드사 연동이 포함된 한국 PG 기준으로는 지나치게 낙관적이다.

결과적으로 sync 전략이 현실보다 유리하게 측정되며, 스레드 블로킹 약점이 드러나지 않는다.

**근거**
- Mastercard Transaction Processing Rules: Issuer 응답 허용 상한 **15초**
- Stripe / Adyen / PayPal 공식 문서: timeout **30초** 권고
- 한국 커머스 프로젝트 실측: 토스페이먼츠 confirm API 평균 **1,300ms**

---

## 목표

저지연(낙관적) / 고지연(현실적) 두 환경에서 동일 3케이스를 측정하여 네트워크 지연이 각 전략에 미치는 영향을 비교한다.

---

## 측정 케이스 (6가지)

| 환경 | 케이스 | 전략 | Toss 딜레이 | testid |
|------|--------|------|------------|--------|
| 저지연 | 1 | sync | 100~300ms | `sync-low` |
| 저지연 | 2 | outbox | 100~300ms | `outbox-low` |
| 저지연 | 3 | outbox-parallel | 100~300ms | `outbox-parallel-low` |
| 고지연 | 4 | sync | 800~1500ms | `sync-high` |
| 고지연 | 5 | outbox | 800~1500ms | `outbox-high` |
| 고지연 | 6 | outbox-parallel | 800~1500ms | `outbox-parallel-high` |

---

## 결정 사항

### Toss 딜레이 환경변수화

`application-benchmark.yml`에서 하드코딩된 딜레이 값을 환경변수로 노출:

```yaml
myapp:
  toss-payments:
    fake:
      min-delay-millis: ${TOSS_MIN_DELAY_MILLIS:100}
      max-delay-millis: ${TOSS_MAX_DELAY_MILLIS:300}
```

`docker-compose.yml` app 서비스에 추가:

```yaml
- SPRING_MYAPP_TOSS_PAYMENTS_FAKE_MIN_DELAY_MILLIS=${TOSS_MIN_DELAY_MILLIS:-100}
- SPRING_MYAPP_TOSS_PAYMENTS_FAKE_MAX_DELAY_MILLIS=${TOSS_MAX_DELAY_MILLIS:-300}
```

### run-benchmark.sh 6케이스 확장

`run_strategy()` 시그니처에 `toss_min_delay`, `toss_max_delay` 인자 추가:

```bash
run_strategy <strategy> <script> <testid> <outbox_parallel> <outbox_batch_size> <toss_min_delay> <toss_max_delay>
```

저지연/고지연 각각 3케이스 순서대로 실행:

```bash
# 저지연 (100~300ms)
run_strategy "sync"   "sync.js"   "sync-low"            "false" "50" "100"  "300"
run_strategy "outbox" "outbox.js" "outbox-low"          "false" "50" "100"  "300"
run_strategy "outbox" "outbox.js" "outbox-parallel-low" "true"  "50" "100"  "300"

# 고지연 (800~1500ms)
run_strategy "sync"   "sync.js"   "sync-high"            "false" "50" "800"  "1500"
run_strategy "outbox" "outbox.js" "outbox-high"          "false" "50" "800"  "1500"
run_strategy "outbox" "outbox.js" "outbox-parallel-high" "true"  "50" "800"  "1500"
```

### testid 태그 및 결과 파일

- `--tag testid=<testid>` — Grafana에서 6케이스 필터 비교
- `-e CASE_NAME=<testid>` — 결과 JSON 파일명 분리
- 결과: `results/{sync,outbox,outbox-parallel}-{low,high}.json`

### sync.js handleSummary

현재 `makeSummaryHandler('sync')` 하드코딩 → `__ENV.CASE_NAME || 'sync'` 로 변경
(outbox.js는 이미 `__ENV.CASE_NAME` 사용 중)

---

## 영향 범위

- 변경: `docker/compose/docker-compose.yml`
- 변경: `src/main/resources/application-benchmark.yml`
- 변경: `scripts/k6/run-benchmark.sh`
- 변경: `scripts/k6/sync.js`
- 무관: 애플리케이션 소스 코드, outbox.js, helpers.js

---

## 제외 범위

- 중간 지연(400~700ms) 시나리오: 별도 작업
- Grafana 대시보드 패널 구성 변경: 별도 작업
