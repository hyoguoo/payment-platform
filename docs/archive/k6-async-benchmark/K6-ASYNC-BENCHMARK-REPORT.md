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

---

# 병목 분석 사이클

baseline 측정에 이어, 부하를 단계적으로 올려(`scripts/k6/sweep.sh`, constant-arrival-rate) saturation point를 찾고 → actuator 메트릭으로 병목 자원을 식별 → 처방 → 재측정으로 개선을 검증한다. 서버 메트릭은 관측성 풀스택 없이 `payment:8080/actuator/prometheus`에서 직접 수집.

## 사이클 1 — 동기 confirm 경로: Hikari DB 커넥션 풀

**측정**: checkout+confirm만(`SKIP_POLL=true`, 폴링 제거로 동기 경로 집중), rate sweep 25~300 req/s, 각 25s steady-state.

**병목 식별 (풀 30, baseline)**:

| rate | confirm p95 | Hikari active | Hikari pending | dropped |
|---|---|---|---|---|
| 25 | 19ms | 2 | 0 | 0 |
| 100 | 48ms | 13 | 0 | 0 |
| **150** | 91ms | **30(상한)** | **64** | 20 |
| 200 | 230ms | 30 | 61 | 27 |
| 300 | 272ms | 30 | 121 | 93 |

- **knee ≈ 150 req/s**: active가 max-pool(30)에 붙고 pending(커넥션 대기 스레드)이 64→121로 폭증.
- 그 대기가 confirm p95에 직접 반영(48→272ms). http_req_failed는 0% → 에러 아닌 **포화 지연**.
- → 병목 자원 = **DB 커넥션 풀(maximum-pool-size=30)**.

**처방**: `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` 30 → 60 (MySQL `max_connections=151`로 수용 가능 확인).

**재측정 (풀 60) — 개선**:

| rate | p95 30→60 | pending 30→60 | active(60) |
|---|---|---|---|
| 150 | 91ms → **32ms** (65%↓) | 64 → **0** | 14 |
| 200 | 230ms → **31ms** (87%↓) | 61 → **0** | 14 |
| 300 | 272ms → **121ms** (55%↓) | 121 → 74 | 60(상한) |

- **150~200 req/s 병목 완전 해소**(pending 0, p95 정상). **knee가 150 → 300으로 이동**.
- **300 req/s에서 새 병목**: 풀 60도 active 60 포화 + pending 74. active가 200(14)→300(60) 비선형 급증 = confirm TX 점유시간 증가(MySQL이 300 동시 부하에서 느려지는 신호). 병목이 "풀 크기" → "DB 처리력"으로 이동.
- **결론**: 로컬 단일 인스턴스에서 동기 confirm 안정 처리 한계는 풀 60 기준 **~200 req/s**. 그 이상은 DB 처리력(추가 처방 후보: 풀 추가 상향은 효과 체감, MySQL 튜닝·인스턴스 확장이 다음 레버).

## 사이클 2 — 비동기 e2e 파이프라인

**측정**: 전체 e2e(폴링 포함), rate sweep 25~100 req/s. actuator로 events.confirmed lag + Hikari + outbox pending + pg lag + CPU 수집.

### 발견 2-1 — consumer 블로킹 cascade (reconciler 과단축 + DLT 토픽 갭)

첫 e2e sweep에서 모든 rate가 e2e_timeout, events.confirmed lag이 33000에서 정체(소진 안 됨). 로그/consumer-group 분석:

- payment `ConfirmedEventConsumer`가 events.confirmed 메시지마다 `PaymentStatusException: 결제 성공할 수 없는 상태`(이미 DONE인 결제에 APPROVED 재도착) → DefaultErrorHandler 재시도 5회(FixedBackOff) → DLT 발행 시도 → **DLT 토픽 `payment.events.confirmed-dlt` 부재**(`non-existent partition`) → 메시지당 ~5초 소모 → 처리량 붕괴.
- **중복의 출처**: 사이클1의 settle 결정론화용 **reconciler 30s 단축**이 부하 측정에서 역효과. confirm 결과가 30s 안에 처리 안 되면 reconciler가 정상 IN_PROGRESS를 stuck으로 오판 → resetToReady → 재confirm → pg가 stored 결과 재발행 → **중복 events.confirmed 대량**(백로그 15854/파티션). 첫 건만 DONE, 나머지는 전부 "이미 DONE" 예외.

**처방 → 개선**:
- DLT 토픽(`payment.events.confirmed-dlt`) 생성 → 막힌 메시지가 DLT로 빠지며 consumer 재개.
- reconciler 완화(`RECONCILER_IN_FLIGHT_TIMEOUT_SECONDS` 30→600, scan 15→60s) → 재confirm cascade 차단.
- offset → latest reset(중복 백로그 skip).
- **결과: lag 33204 정체 → 0**, e2e 정상화.

**시사점(잠재 도메인 버그)**: DLT 토픽 suffix 불일치 — `create-topics.sh`는 `.dlq` 컨벤션(`payment.events.confirmed.dlq`)으로 만드는데 Spring `DeadLetterPublishingRecoverer` 기본은 `-dlt`(`payment.events.confirmed-dlt`). 운영에선 예외가 드물어 안 드러나지만, 처리 예외 누적 시 consumer 영구 블로킹 위험. → 별도 토픽으로 처리 권고(아래 후속).

### 발견 2-2 — 깨끗한 상태의 비동기 파이프라인은 처리량 병목 없음

clean 상태(백로그 0, reconciler 완화) e2e sweep:

| rate | e2e p95 | e2e med | confirm 실처리/s | dropped | events.confirmed lag | outbox pending | pg lag |
|---|---|---|---|---|---|---|---|
| 25 | 1.04s | 521ms | 24.7 | 0 | 0 | 0 | ~0 |
| 50 | 1.54s | 524ms | 49.2 | 7 | 0 | 0 | ~0 |
| 75 | 2.54s | 1.03s | 72.2 | 74 | 0 | 0 | ~0 |
| 100 | 6.94s | 4.06s | 62(목표 미달) | 1366 | 0 | ~0 | ~0 |

- **모든 비동기 큐가 ≈0**(events.confirmed lag, payment outbox pending, pg commands.confirm lag) → 비동기 파이프라인(outbox relay → pg 소비 → vendor → consumer)은 100 req/s를 적체 없이 처리. **동기 confirm(Hikari knee 150)과 달리 비동기 경로엔 처리량 병목이 없음** = 비동기 흡수 효과.
- 그럼에도 e2e_completion 급증 + dropped 폭증. 부하 중 CPU: `process_cpu`(payment) 21%, `system_cpu` 60%, 컨테이너 CPU 132~238%, **http_reqs 664/s**(checkout+confirm ~200/s, 나머지 ~460/s가 status 폴링).

### 결론 (사이클 2)

e2e_completion 증가의 원인은 비동기 처리량이 아니라:
1. **status 폴링 자가 부하** — e2e가 길어질수록 폴링 횟수↑ → http_reqs 664/s(폴링이 ~70%) → 단일 인스턴스 스레드/CPU 경합 → status 응답 지연 → 악순환. 폴링 기반 e2e 측정 방식 자체의 한계.
2. **단일 인스턴스 종합 capacity** — payment 한 인스턴스가 confirm + status 폴링 + 비동기 처리(outbox relay)를 모두 수행, 2+ 코어 소비.

**처방 방향(별도 토픽 규모)**:
- status 폴링 → push 알림(SSE/WebSocket) 또는 롱폴링으로 자가 부하 제거
- payment scale-out(멀티 인스턴스) — 단 EOS transactional.id 고유화 선행(L-3/L-6, TC-13-FOLLOW-1)
- DLT 토픽 suffix 정합(`-dlt` ↔ `.dlq`) — 잠재 블로킹 버그 해소

### 종합 — 두 경로의 병목 대조

| 경로 | 병목 자원 | knee | 처방 | 개선 |
|---|---|---|---|---|
| 동기 confirm | Hikari DB 풀(30) | ~150 req/s | 풀 30→60 | knee 150→300, p95 65~87%↓ |
| 비동기 e2e | (파이프라인 병목 없음) consumer 블로킹은 설정/토픽 갭 | — | reconciler 완화 + DLT 토픽 | lag 33204→0 |

동기 경로는 DB 풀이라는 명확한 자원 병목(처방 효과 정량), 비동기 경로는 처리량 병목 없이 흡수가 작동하되 운영 설정(reconciler)·인프라 갭(DLT 토픽)이 함정. 절대 수치는 로컬 단일 인스턴스 기준이며 운영 환경 재측정 필요.

---

# 후속 과제

본 측정에서 도출됐으나 로컬 환경 제약으로 본 토픽 범위 밖인 것들. 다음 목표로 제대로 다룬다.

## (다음 목표) payment scale-out 처리량 측정

사이클2에서 "단일 인스턴스 CPU/폴링이 e2e 한계"로 추정했다. 비동기 경로의 핵심 강점인 **수평 확장 = 처리량 선형 증가**를 payment 2~3 인스턴스로 입증하는 것이 자연스러운 다음 단계.

**선행/제약**(로컬에선 부적합, 운영급 환경 권장):
- 메모리 — 인스턴스당 ~0.9GB, 현 7.65GB 로컬은 2개도 빠듯·3개 OOM. 충분한 메모리 환경 필요.
- 로드밸런싱 — payment 직접 포트 노출(8080) 대신 gateway 경유로 복귀해 인스턴스 분산.
- **EOS transactional.id 멀티 인스턴스 검증**(L-3/L-6, TC-13-FOLLOW-1) — 인스턴스별 hostname 고유화로 producer fencing 정상 동작 확인이 선행. 미검증 영역.
- 측정 관점 — 인스턴스 1→2→3 에서 confirm 처리율·e2e_completion이 선형 개선되는지, Kafka 파티션(3) 분산이 consumer 병렬을 받쳐주는지.

## (별도 수정) DLT 토픽 suffix 갭 — consumer 블로킹 잠재 버그

`create-topics.sh`는 `.dlq` 컨벤션(`payment.events.confirmed.dlq`)으로 토픽을 만드는데, Spring `DeadLetterPublishingRecoverer` 기본 suffix는 `-dlt`(`payment.events.confirmed-dlt`). 처리 예외가 누적되면 DLT 발행 실패 → consumer 영구 블로킹(본 측정에서 실제 재현). 운영에선 예외 빈도가 낮아 안 드러나지만 실재하는 갭. 토픽 네이밍 정합(둘 중 하나로 통일) 또는 recoverer 목적지 명시로 해소.

## (측정 방식 개선) status 폴링 자가 부하

e2e 부하의 ~70%가 status 폴링(http_reqs 664/s). push 알림(SSE/WebSocket) 또는 롱폴링으로 자가 부하를 제거하면 비동기 e2e의 실제 한계를 더 정확히 측정 가능.
