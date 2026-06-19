# 결제 처리량 부하 측정 리포트 — CAPACITY-AND-SCALEOUT

> 측정일: 2026-06-17
> 토픽: CAPACITY-AND-SCALEOUT (이슈/브랜치 #104)
> 설계: `docs/topics/CAPACITY-AND-SCALEOUT.md` / 플랜: `docs/CAPACITY-AND-SCALEOUT-PLAN.md`
> 직전 측정 SSOT 연장: `docs/topics/K6-ASYNC-BENCHMARK-REPORT.md` (사이클 1/2 → 본 리포트 사이클 3/4…)

## 측정 환경

| 항목 | 값 |
|---|---|
| 토폴로지 | 로컬 docker, **단일 payment 인스턴스**, 8080 직접(actuator + 부하) |
| 호스트 | Docker 총 메모리 7.65GB, 10 CPU (Apple Silicon) |
| 벤더 | Fake PG, latency 100~300ms, `FAKE_FAIL_RATE=0` |
| reconciler | in-flight-timeout 600s + scan 60s (cascade 차단) |
| 공유 자원 | MySQL `max_connections=300`, Lettuce pool 16 |
| JVM heap | payment `-Xmx700m`, pg `-Xmx600m` |
| 부하 모델 | constant-arrival-rate rate sweep (`sweep.sh`), 각 25s steady |

---

## 사이클 3 — 동기 confirm 경로: Hikari DB 커넥션 풀 (폴링 OFF)

**측정**: `SKIP_POLL=true` (동기 confirm 집중), Hikari 풀 30/60/80 각각 rate sweep.

| 풀 | rate별 confirm p95 | knee | 포화 신호 |
|---|---|---|---|
| **30** | 100:241ms / 150:65 / 200:69 / 300:**649ms** | ~300 | 300 active 30상한 + pending 212 + dropped 301 |
| **60** | 100:38 / 200:29 / 300:**146ms** | ~350 | 300 active 60상한 + pending 32 |
| **80** | 300:36 / 400:64 / 500:**585ms** | ~450 | 500 active 80상한 + pending 195 + dropped 687 |

**결론**:
- **병목 = Hikari 풀**. 포화는 항상 *active가 풀 상한에 닿을 때* 발생.
- 풀 30→60→80 상향이 knee를 300→350→450으로 **계속 미는 레버** = 풀이 지배 자원.
- **DB 처리력은 로컬에서 아직 천장 아님**: active가 rate에 선형(풀 80에서 300→28, 400→48). DB가 동시 confirm을 빠르게 회전 — 비선형 급증(DB 포화 신호) 미관측.
- 인스턴스당 권장 풀 **60~80** (페이즈 2에서 `max_connections`×0.8/N 제약 적용 — N=2면 ≤120 여유, 풀 60 동률).
- **노이즈**: 각 sweep 첫 본격 rate에서 p95 일시 튐(JIT·풀 워밍 흡수, 뒤 단계서 정상화).

---

## 사이클 4 — 비동기 e2e 파이프라인 + 폴링 전략 (폴링 ON)

**측정**: `SKIP_POLL=false`, 풀 80 고정. e2e 완료 = 폴링 응답 시각(체감 latency).

### 측정 위생 — 백로그 오염 (첫 시도 무효)

첫 e2e sweep이 rate 25/50에서 대량 e2e_timeout + events.confirmed lag 3000+. 원인은 **직전 동기 sweep(폴링 OFF, rate 300~500)이 발행한 events.confirmed 수만 건이 백로그로 잔존** → 폴링이 신규 DONE을 기다리는데 consumer가 백로그부터 처리. **동기↔e2e 측정 사이 백로그 소진/리셋 필수**(K6 사이클2 발견 2-1과 동형). 백로그 소진(lag 0) 후 재측정.

### 깨끗한 e2e (lag 0 시작)

| rate | e2e p95 | confirm p95 | lag | timeout | dropped |
|---|---|---|---|---|---|
| 25 | 509ms | 16ms | 0 | 0 | 0 |
| 50 | 506ms | 9ms | 0 | 0 | 0 |
| 75 | 506ms | 12ms | 0 | 0 | 0 |
| 100 | 1.53s | 25ms | 0 | 0 | 97 |

- **75 req/s까지 e2e ~505ms 일정**(폴링 500ms + 벤더 지연), 적체 0 → 비동기 흡수 완벽.
- **rate 100의 e2e 악화는 폴링 자가부하** (confirm 25ms·active 14·**lag 0** → consumer 병목 아님). 풀 80·깨끗한 상태라 직전 K6(50부터 악화)보다 늦게 발현.
- consumer 처리량 한계는 폴링 없는 발행 폭주(동기 300+/s)에서만 백로그 → 페이즈 2(consumer 병렬·파티션 3) 영역.

### 폴링 전략 — fixed vs backoff+지터 (rate 100)

| 전략 | e2e avg | e2e p95 | http_reqs/s | confirm p95 |
|---|---|---|---|---|
| fixed (500ms) | 2.02s | 3.68s | 623/s | 46.8ms |
| **backoff+지터** | **1.47s** | 3.61s | **450/s** | **32.4ms** |

- backoff+지터: **폴링 부하 28%↓ + e2e avg 27%↓ + confirm p95 31%↓** — 자가부하 영역에선 트레이드오프가 아니라 **win-win**(폴링↓ → 서버 경합 완화 → 처리·체감 동반 개선).
- 꼬리 p95만 비슷(긴 간격이 일부 인지 지연 → 체감↔부하 트레이드오프가 꼬리에 잔존).
- **운영 폴링 권장 = backoff+지터.** 페이즈 2-B 입력값으로 채택.

---

## 사이클 5 — 페이즈 2-0: scale-out baseline + transactional.id fencing 실증 (Task 7)

> 측정 환경 변경: payment `ports: "8080"`(host 동적 할당)으로 scale=2 충돌 회피 →
> 부하는 gateway:8090 lb 분산(Eureka 디스커버리), actuator는 인스턴스별 동적 포트 수집.
> Hikari 80 · reconciler 600s(충돌 실증 run만 30s) · MySQL max_conn 300 · 재고 1천만.

### 5-A. scale-out baseline (1 인스턴스, gateway 경유, 폴링 OFF)

페이즈 2 scale-out 비교의 **1× 기준점**. 페이즈 1(사이클 3)은 8080 직접이라 gateway 홉이
빠져 비교 부적합 → gateway 경유로 재측정(홉을 공통 변수로 상쇄).

| rate | confirm p95 | Hikari active(max) | pending(max) | dropped | 비고 |
|---|---|---|---|---|---|
| 100 | 117ms | 34 | 0 | 0 | 워밍업 튐 |
| 200 | 176ms | 68 | 33 | 80 | 워밍업 일시 포화 |
| **300** | **59ms** | 55 | 0 | 0 | 안정 |
| **400** | **77ms** | 62 | 0 | 0 | 안정 |
| 500 | 235ms | **80(상한)** | **120** | 165 | 포화 |

- **1 인스턴스 처리 한계 ≈ rate 450**(active가 풀 상한 80에 닿기 직전). 페이즈 1(8080 직접, knee 450)과 일치 → **gateway 홉은 레이턴시만 더할 뿐 처리 한계는 동일**.
- 워밍업 노이즈: rate 100/200의 p95가 안정 구간(300/400)보다 높음(JIT·풀 워밍, 사이클 3과 동형).

### 5-B. fencing 실증 — transactional.id 고유화 (hostname 제거, D3)

`transactionalIdPrefix = ${payment.kafka.transactional-id-prefix:${app}-${HOSTNAME:local}}`
(KafkaProducerConfig). hostname 라인 제거로 HOSTNAME=컨테이너ID → 인스턴스별 고유.

| 시나리오 | transactional.id | ProducerFenced | 분산/중복 |
|---|---|---|---|
| **정상 2 인스턴스** (rate200×25s) | 고유(`dd3bed…` vs `83220e…`) | **0** | confirm 2460 vs 2443(편차 **0.69%**) · 중복 발행 0 |
| **rebalance** (부하 중 인스턴스 restart) | 고유 | **0** | rebalance 이벤트 10/6 발생 · 중복 0 |
| **의도적 id 충돌** (prefix `payment-collision-fixed` 강제 + rebalance) | 충돌 | **9건**(6+3) | 재고 차이 3(0.12%) · 대량 유실 없음 |

- **핵심 통찰**: transactional.id가 `prefix+group+topic+partition`(consumer-initiated EOS — `kafkaTransactionManager`가 listener 컨테이너에 wire-in)이라 **정상 배타 파티션에선 prefix가 충돌해도 fencing이 안 난다**. 충돌은 **rebalance overlap 순간**(두 인스턴스가 같은 파티션을 잠깐 겹쳐 가질 때)에만 발생.
- 따라서 D3(hostname 제거 고유화)의 진짜 가치는 "정상 운영 충돌 방지"가 아니라 **scale-out·배포·장애 시 rebalance 전환 안전성**(전환 순간 fencing으로 인한 처리 중단/지연 차단).
- **충돌 시 데이터 무결성 — 양분해서 보아야 함**: ProducerFenced로 abort된 트랜잭션은 EOS `read_committed`로 다운스트림 미노출 + 재배달 재처리, 중복은 `payment_event_dedupe`로 **멱등 흡수**(ship 재검증: dedupe row = DONE 수로 일치). **정상 운영(restart 없음)에선 재고 정합 완벽**(사이클 6-D redis==RDB 차이 0). 단 **충돌·rebalance 시점엔 미세 갭**(아래 5-C)이 측정 시점에 잔존 — "규모가 작다(0.1%대)"이지 "정합이 항상 유지된다"가 아니다.

### 5-C. 부가 발견 (후속 트리거)

- **인스턴스 restart 가용성 갭 16%**: rebalance/충돌 부하에서 인스턴스 다운 ~수초간 gateway가 그 인스턴스로 라우팅한 confirm 요청이 http_fail(checkout READY만 잔존, 재고 선점 전이라 무해) → **graceful shutdown + gateway retry** 후속.
- **재고 미세 갭(충돌·restart 시점) — ship 단계 재검증으로 원인 규명**: 충돌 실증 후 redis < RDB 미세 갭(재현마다 3~6건, 0.1%대). 초기 진단("abort 보상 INCR 누락" / "reconciler L7 cascade")을 **2-run 실측으로 둘 다 반증·정정**(실험 A = reconciler 30s run, 실험 B = 600s run):
  - ① **실험 A(30s, 갭 5)·실험 B(600s, 갭 6) 모두 `READY 복원`(reconciler reset) 로그 0건** → **reconciler cascade 아님**(L7 가설 반증: 600s에서도 갭이 안 사라지고 reset이 안 돈다).
  - ② 갭 ≈ IN_PROGRESS 수(실험 B: 갭 6 = IN_PROGRESS 6) → 실제 원인은 **fencing이 stock-committed(RDB 차감) EOS 트랜잭션을 abort시켜, 재배달 재처리 대기 중인 IN_PROGRESS in-flight가 측정 시점에 포착된 비대칭**. redis DECR(`OutboxAsyncConfirmService`, 선점)과 RDB 차감(`PaymentConfirmResultUseCase.handleApproved`의 stock-committed 발행)이 **다른 단계**라, fencing이 후자를 abort하면 redis만 선차감된 채 IN_PROGRESS로 남아 redis<RDB. abort된 메시지는 offset 미커밋으로 재배달되고 중복은 `payment_event_dedupe`로 멱등 흡수(dedupe row = DONE 수).
  - **후속(영구성)**: 갭은 "재배달 EOS 재성공으로 stock-committed 발행 → RDB 차감 → 자연 종결(복원)" vs "재배달 5회 소진 → `.dlq` 낙착 후 `PaymentReconciler`(IN_FLIGHT timeout → resetToReady) backstop 회수"의 2분기. 장기 관찰로 어느 쪽인지 확정. baseline failRate=0이라 PG-FAILED redis 보상 경로는 트리거되지 않으므로 보상 경로 버그가 아니다.

> scale-out 1→2 처리율 선형성(2-A/2-B)·USL 회귀(2-C)는 사이클 6에서 측정.

---

## 사이클 6 — 페이즈 2-A/2-B: scale-out 1→2 처리율 (Task 8)

> 정상 2 인스턴스(고유 transactional.id, reconciler 600s), gateway lb 분산, 재고 1천만 재시드.
> **측정 위생 교훈**: 재기동 직후 콜드 JVM에 rate 300 갑작스런 부하 → confirm p95 2.28s·pending 215로
> 1 인스턴스보다 나쁜 **콜드 오염** 발생. 워밍업(rate 100×30s) 후 재측정으로 정상화(rate 300 p95 72ms).

### 6-A. 폴링 OFF — confirm 동기 처리율 1→2 (워밍업 후)

| rate | confirm p95 | Hikari active(인스1) | pending | dropped | 비고 |
|---|---|---|---|---|---|
| 300 | 110ms | 75 | 0 | 0 | 안정 |
| **450** | **206ms** | **80(상한)** | 3 | 0 | 한계 근처 |
| 600 | 1.44s | 80 | 213 | 82/s | 포화 |
| 750 | 1.44s | 80 | 201 | 216/s | 포화 |
| 900 | 1.5s | 80 | 200 | 374/s | 포화 |

- **2 인스턴스 knee ≈ 450~500 = 1 인스턴스(450)와 동일 → scale-out ~1.0× (선형성 없음, 기각)**.
- 양쪽 Hikari active **합 160**(80+80, 둘 다 포화)인데 처리율은 1 인스턴스와 같음. 같은 rate 450에서 1 인스턴스는 active 62·p95 77ms(여유), 2 인스턴스는 active 합 160·p95 206ms → **active 2배·latency 2.7배로 throughput 정체**.
- Hikari 풀을 인스턴스로 2배 늘려도 **그 뒤 공유 자원이 천장**. 페이즈 1의 "DB 천장 미도달"을 2 인스턴스 관점에서 정밀화: **1 인스턴스=Hikari 풀 병목, 2 인스턴스=공유 DB 병목**.

### 6-B. 병목 자원 귀속 (rate 500 부하 중 CPU)

| 컨테이너 | CPU |
|---|---|
| payment-1 / payment-2 | ~190% / ~190% |
| mysql-payment | ~147% |
| kafka / redis-stock | ~20% / ~6% |

- **CPU 합 ~553% = 코어 5.5 / 호스트 10 → CPU 여유**. scale-out 차단은 **CPU saturation이 아니라 공유 자원 경합**.
- MySQL이 147%에 정체 + confirm latency 2.7배 → **MySQL lock/IO contention + Kafka EOS commit 직렬화**가 동시성 증가의 이득을 상쇄(USL contention·coherency 항).
- gateway 병목은 배제(Task 7: 8080 직접과 gateway knee 동일).

### 6-C. 폴링 ON(backoff) — e2e capacity 1→2

| rate | e2e p95 | confirm p95 | Hikari | lag | timeout |
|---|---|---|---|---|---|
| 50 | 1.16s | 29ms | 4 | 0 | 0 |
| 75 | 1.5s | 45ms | 6 | 0 | 0 |
| **100** | **1.15s** | 22ms | 5 | 0 | 0 |
| 150 | 5.13s | 54ms | 47 | 0 | 0(dropped 543) |

- **2 인스턴스 e2e capacity = rate 100~125 흡수**(1 인스턴스 75 대비 **~1.3×**). e2e 병목은 폴링 자가부하(사이클 4와 동일)이고 confirm·Hikari·consumer 여유(저rate라 DB 병목 미도달).
- backoff 적용 확인(rate 100 e2e p95 1.15s — 사이클 4 1 인스턴스 backoff 3.61s 대비 양호, 폴링 부하 2 인스턴스 분산 효과).

### 6-D. 파티션 점유 편향 + 정합 게이트

- **events.confirmed 파티션 3 vs 인스턴스 2 = 2:1 편향**: 파티션 0,1→인스턴스A, 파티션 2→인스턴스B. **고발행(6-A rate 600+)에서 consumer 백로그 비대칭**(인스턴스A lag 4591 / 인스턴스B 0). e2e 저rate(6-C)에선 lag 0으로 미발현.
- **정합 게이트 통과**: 재고 redis(9863370) == RDB(9863370) **차이 0** + 측정 구간 payment_event **전부 DONE, 미종결 0**(silent loss 0). 정상 2 인스턴스(restart 없음)에선 정합 완벽 — 충돌/restart 시에만 미세 갭(사이클 5)이 나는 것과 일관. **scale-out해도 데이터 안전**.

### 6-E. 결론

- **처리율비 confirm 1.0× / e2e 1.3× — 합격 ≥1.6× 미달 → 기각**. 분산 균등성·정합·무결성은 ✅.
- **병목 = 공유 DB 경합(MySQL lock/IO + Kafka EOS commit) + 폴링 자가부하**. 로컬 CPU·메모리·Hikari 풀은 천장 아님.
- **후속 트리거**: ① payment DB 스케일(읽기 전용 복제 / 쓰기 샤딩) — 공유 DB가 진짜 천장 ② events.confirmed 파티션 수 = 인스턴스 배수(편향 제거) ③ Kafka EOS commit 오버헤드 프로파일링.

---

## 사이클 7 — 페이즈 2-C: USL 회귀 (Task 9)

> 피팅 도구: `scripts/usl-fit.py`(순수 Python, 의존 없음). 입력 CSV `scripts/usl-data/*.csv`.
> USL: `X(N) = gamma*N / (1 + alpha*(N-1) + beta*N*(N-1))`, `Nmax = sqrt((1-alpha)/beta)`.

### 핵심 한계 — N≤2 (underdetermined)

로컬 메모리(7.65GB)로 **N=1,2 두 측정점만** 확보 → USL 3파라미터(α·β·γ) 회귀는 **underdetermined**(미지수 3 > 식 2). γ=X(1)만 확정되고 α·β는 **개별 분리 불가**, 남은 1식이 제약만 부여한다. Nmax 점추정 불가 — 경계 가정별 범위만 제시.

### 피팅 결과

| 경로 | gamma=X(1) | X(2)/X(1) | 제약식 | Nmax (β=0 / α=0 경계) |
|---|---|---|---|---|
| **confirm 동기** | 450 | 1.00 | α + 2β = 1.0 | ∞(plateau) / **1.41** |
| **e2e 폴링(backoff)** | 75 | 1.33 | α + 2β = 0.5 | ∞ / **2.00** |

- **confirm 동기**: 동시성 2배가 처리율 0 증가 → 제약식 `α+2β=1`(최대 경합). α=0 경계에서도 **Nmax≈1.41** = N=1.4 인스턴스에서 이미 처리율 최대, 그 이상은 감소. **scale-out 이득이 사실상 없음**을 USL로 재확인(공유 DB 경합이 contention/coherency 항을 지배).
- **e2e 폴링**: 1.33× 개선 → 제약 `α+2β=0.5`. α=0 경계 **Nmax≈2.0** = 2 인스턴스가 e2e capacity 한계(폴링 자가부하 분산 효과는 N=2까지).

### 결론

- 2점이라 α·β 분리는 불가하나, **제약식이 "동시성 증가의 한계 비용"을 정량 규정**한다(confirm은 plateau N≈1.4, e2e는 N≈2). 측정 결과(사이클 6 기각)와 정합.
- `usl-fit.py`는 **측정점 ≥4 시 grid search 로 α·β·γ 자동 회귀** — 운영 환경 + DB 스케일로 N을 3+ 확장하면 동일 스크립트로 다점 피팅·Nmax 점추정 재현 가능(자산).

---

## 환경 제약 및 한계

- 단일/2 인스턴스 + 로컬 메모리(7.65GB) 한정 → 절대 TPS 무의미, **상대 비교만 유효**. N≤2라 USL 다점 회귀 불가(사이클 7에서 한계로 다룸).
- 동기 경로 1 인스턴스 baseline은 8080 직접(페이즈 1)·gateway(Task 7) 양쪽 측정 — knee 동일(gateway 홉은 레이턴시만).
- **DB 처리력 천장**: 1 인스턴스에선 미도달(Hikari 풀이 먼저 병목)이나 **2 인스턴스 합 160 동시에선 공유 MySQL이 천장**(scale-out ~1.0×). 운영 환경 + DB 스케일 재측정 필요.
- **측정 위생**: 재기동 직후 워밍업 필수(콜드 오염), 동기 sweep 후 e2e 전 lag 0 소진, payment_event 누적 주의(silent loss 판정은 구간 격리).

---

## 종합 결론 (페이즈 1 + 2)

### 병목의 이동 — 핵심 발견

| | 1 인스턴스 (페이즈 1) | 2 인스턴스 (페이즈 2) |
|---|---|---|
| confirm 처리 병목 | **Hikari 풀** (knee 풀 30→60→80 = 300→350→450) | **공유 DB 경합** (양쪽 풀 160 포화해도 처리율 정체) |
| DB 처리력 | 미천장 (풀이 먼저 병목) | **천장 도달** (MySQL lock/IO + Kafka EOS commit 직렬화) |
| scale-out | — | **기각** (confirm 1.0× / e2e 1.3× < 1.6×) |

**한 줄 교훈**: 1 인스턴스에서 효과적이던 튜닝 레버(Hikari 풀 확장)가 2 인스턴스에선 무의미하다 — 인스턴스를 늘리면 **병목이 인스턴스-로컬 자원(풀)에서 공유 자원(DB·Kafka 코디네이터)으로 이동**한다. scale-out의 전제는 앱 인스턴스 증설이 아니라 **공유 자원의 동반 스케일**이다.

### 안전성 — scale-out해도 데이터는 안전

- transactional.id 고유화(hostname 제거)로 2 인스턴스 fencing 정상(중복 0·분산 균등), rebalance 안전.
- 의도적 id 충돌(rebalance overlap)에도 EOS read_committed + 재배달 재처리, 중복은 `payment_event_dedupe`로 멱등 흡수(dedupe row = DONE).
- **정상 운영(restart 없음)에선 재고 정합 완벽**(redis==RDB 차이 0) + silent loss 0 — 안전 근거는 이 정상 경로다.
- 단 **충돌·restart 시점엔 미세 갭**(0.1%대, redis<RDB)이 측정 시점에 잔존: 원인은 reconciler cascade가 아니라(ship 재검증: READY 복원/reset 로그 0건) **fencing이 stock-committed EOS를 abort시켜 재배달 재처리 대기 중인 IN_PROGRESS in-flight 비대칭**(5-C). "안전"은 규모가 작다는 뜻이지 충돌 케이스에서 정합이 항상 유지된다는 뜻이 아니다.

### 페이즈 3+ 후속 트리거

1. **payment DB 스케일** — 공유 MySQL이 2 인스턴스의 진짜 천장. 읽기 전용 복제(조회 분리) / 쓰기 샤딩 후 scale-out 재측정.
2. **events.confirmed 파티션 수 = 인스턴스 배수** — 현재 파티션 3 vs 인스턴스 2 = 2:1 편향으로 고발행 시 consumer 백로그 비대칭.
3. **인스턴스 restart 가용성 갭 16%** — graceful shutdown + gateway retry로 무중단 배포/scale 확보.
4. **충돌·restart 시 재고 미세 갭(0.1%대)** — ship 재검증 결과 원인은 fencing이 stock-committed EOS를 abort시켜 재배달 대기 중인 IN_PROGRESS in-flight 비대칭(reconciler cascade·보상 누락 아님 — reset 로그 0건). 영구성(재배달 EOS 재성공 vs `.dlq` 낙착 후 reconciler backstop 회수) 장기 관찰.
5. **Kafka EOS commit 오버헤드 프로파일링** — 2 인스턴스 confirm latency 증가의 직렬화 기여분 분해.
6. **N≥3 USL 재측정** — 운영/DB 스케일 환경에서 `scripts/usl-fit.py` 다점 회귀로 α·β·Nmax 점추정.
