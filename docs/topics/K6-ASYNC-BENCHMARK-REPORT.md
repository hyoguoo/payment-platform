# 비동기 결제 경로 k6 부하 측정 결과 리포트

> 측정일: 2026-06-15
> 토픽: K6-ASYNC-BENCHMARK (이슈/브랜치 #102)
> 설계: `docs/topics/K6-ASYNC-BENCHMARK.md` / 플랜: `docs/K6-ASYNC-BENCHMARK-PLAN.md`

## 측정 목적

비동기 단일 결제 경로(checkout → confirm 202 → status 폴링 DONE)에서, **벤더 응답 지연이 동기 응답 시간과 e2e 완료 시간에 미치는 영향**을 저/고 2환경 대비로 측정한다. 핵심 가설: "비동기 진입(202 즉시 반환)이 벤더 지연을 흡수해, 벤더가 느려져도 동기 응답성·처리량이 유지된다".

## 측정 환경

| 항목 | 값 |
|---|---|
| 토폴로지 | 로컬 docker compose, **단일 인스턴스** |
| 호스트 | Docker 총 메모리 7.65GB, 10 CPU (Apple Silicon) |
| 벤더 | Fake PG (`pg.gateway.type=fake`), `FAKE_FAIL_RATE=0` (baseline) |
| 부하 모델 | `ramping-arrival-rate` 6→12→25 req/s (peak 25), 단계 30s, 측정 ~2.5분/환경 |
| 폴링 | 간격 500ms, 타임아웃 20s |
| reconciler | in-flight-timeout 30s + scan 15s (settle 창 결정론화) |
| JVM heap | payment `-Xmx700m`, pg `-Xmx600m` (OOM 방지) |
| 측정 진입점 | payment-service 직접(`localhost:8080`) — gateway·관측성은 메모리 확보 위해 미기동(아래 한계 참조) |

## 측정 결과

| 지표 | 저지연 (100~300ms) | 고지연 (800~1500ms) |
|---|---|---|
| checks 성공률 | **100%** (5667 ✓ / 0 ✗) | **100%** (5667 ✓ / 0 ✗) |
| confirm 요청 수 | 1889 | 1889 |
| confirm 처리율 | 12.68 /s | 12.64 /s |
| **동기 응답(confirm 202)** avg | 14.33ms | 9.87ms |
| **동기 응답(confirm 202)** p95 | **38.26ms** | **20.55ms** |
| **e2e 완료** avg | 540.85ms | 1.42s |
| **e2e 완료** p95 | **581.59ms** | **1.62s** |
| **e2e 완료** max | 1.28s | 2.21s |
| e2e_timeout | **0** | **0** |
| 재고부족 confirm(400) | 0 | 0 |
| checkout 중복(200) | 0 | 0 |
| http_req_failed | 0% | 0% |

## 해석

1. **동기 응답은 벤더 지연과 무관** — 벤더 지연을 5배(100-300ms → 800-1500ms) 늘려도 confirm 동기 응답 p95는 오히려 38ms → 20ms로 낮은 수준 유지. confirm은 재고 차감 + DB TX만 동기 수행하고 벤더 호출은 비동기(Kafka)라, 벤더가 느려도 클라이언트 응답성에 영향이 없다. **비동기 경로의 핵심 강점 입증.**
2. **e2e 완료 시간만 벤더 지연을 반영** — e2e p95가 581ms → 1.62s로 약 1초 증가. 이는 벤더 지연 중앙값 증가분(~700-900ms)과 정합. 사용자 체감 "완료까지 시간"은 벤더 지연만큼 늘지만, 그 사이 동기 응답은 즉시 반환되어 서버 스레드를 점유하지 않는다.
3. **처리량·안정성 유지** — 양환경 confirm 처리율(약 12.6/s)·성공률(100%)·실패율(0%)·타임아웃(0)이 동일. 고지연에서도 처리 capacity 저하 없음.

## 정합성 교차 검증 (silent loss 탐지)

settle 대기(reconciler 30s + scan 15s + 여유) 후 DB ↔ k6 교차:

| 항목 | 값 |
|---|---|
| DB `payment_event` DONE | **3779** = 저지연 1889 + 고지연 1889 + 사전 e2e 1건 |
| DB 미종결(READY/IN_PROGRESS/RETRYING) | **0** |
| DB QUARANTINED | **0** |
| 양환경 k6 DONE 합 | 3778 (+ 사전 e2e 1 = 3779) |

- **모든 결제가 DONE으로 종결, 미종결·격리 0 → silent loss(조용한 유실) 없음.**
- `verify-settlement.sh`는 단일 케이스 JSON vs 누적 DB를 비교해 형식상 "불일치"로 표기하나, 양환경 합산 시 DB DONE(3779)과 정확히 일치한다(도구의 단일 케이스 가정 한계 — 후속 보완 여지).

## 환경 제약 및 한계

설계의 baseline 부하(100→200→400 req/s)는 로컬 단일 인스턴스에서 재현 불가했다. 측정 과정에서 드러난 제약:

- **메모리 한계(7.65GB)**: JVM heap 미제한 상태에서 peak 200 req/s 부하 시 heap 팽창으로 payment/pg/gateway가 순차 OOM kill(137). heap 상한 부여(payment 700m, pg 600m) + 관측성·gateway 미기동으로 메모리를 확보하고 **peak 25 req/s로 하향**해 안정 측정.
- **gateway 우회**: 메모리 제약으로 gateway를 띄우지 않고 payment-service를 직접 측정(`localhost:8080`). 측정 경로(checkout→user/product Feign, confirm→Kafka pg, status)는 모두 payment-service가 처리하므로 경로 성립은 동일하나, gateway 라우팅 홉 1개의 latency는 측정에서 제외됨.
- **절대 수치의 한정성**: peak 25 req/s는 로컬 capacity baseline이며, 절대 TPS·latency는 운영 환경(인스턴스 사양·멀티 인스턴스·gateway 포함)에서 재측정해야 한다. 본 측정의 가치는 **저↔고 벤더 지연 대비 패턴**(동기 응답 불변 + e2e만 증가)에 있다.
- **부하 곡선 정밀화**: peak rate·단계 길이·폴링 타임아웃은 측정하며 보정한 값으로, 운영 SLO 기반 정밀화는 후속(TODOS TC-15/TC-7/TC-6).

## 측정 중 발견·수정 사항 (실환경 디버깅)

스크립트는 정적 검증만 거쳐 작성됐고, 실제 측정에서 다음을 수정했다:

1. **컨테이너명 가정 오류** — `run-benchmark.sh`가 `payment-pg-service`로 inspect했으나 실제는 `docker-pg-service-1`(앱 서비스 container_name 미지정, scale-able). `docker compose ps -q`로 동적 탐색으로 수정.
2. **공통 응답 래퍼 미파싱** — 응답이 `{"data":{...}}`로 래핑되는데 `helpers.js`·`async-payment.js`가 `.data`를 벗기지 않아 orderId/status 추출 실패. 두 파서를 래퍼 대응으로 수정.
3. **OOM 방지** — benchmark override에 JVM heap 상한 추가, k6 VU 상한(`PRE_VUS`/`MAX_VUS`)·부하 곡선(`PEAK_RATE`/`STAGE_SEC`) env 조정 가능화.
4. **threshold 허용** — `run-benchmark.sh`가 k6 threshold 위반(exit 99)에도 다음 환경을 계속하도록(baseline 탐색 단계).
5. **bench-seed 멱등 버그** — 재고 값이 동일하면 UPDATE affected 0 → 실패 오판. ROW_COUNT 대신 실제 quantity 검증으로 수정.
6. **clean 후 seed 부재** — `--clean` 볼륨 제거 시 user/product seed가 사라짐(docker profile은 `db/schema`만 적용). 측정 전 seed 수동 삽입 필요(운영 절차로 기록).
