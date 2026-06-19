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
- **충돌해도 데이터는 안 깨진다**: ProducerFenced로 abort된 트랜잭션은 EOS `read_committed`로 다운스트림 미노출 + commands.confirm 재배달로 재처리 → 재고 정합 차이 3건(0.12%)에 그침(abort→재배달이 대량 유실을 만들지 않음).

### 5-C. 부가 발견 (후속 트리거)

- **인스턴스 restart 가용성 갭 16%**: rebalance/충돌 부하에서 인스턴스 다운 ~수초간 gateway가 그 인스턴스로 라우팅한 confirm 요청이 http_fail(checkout READY만 잔존, 재고 선점 전이라 무해) → **graceful shutdown + gateway retry** 후속.
- **재고 미세 갭 3건**: 충돌 실증 후 redis(9997531) < RDB(9997534), 차이 3(0.12%). settle 완료(IN_PROGRESS=0) 후에도 잔존 → abort 시 redis 보상 INCR 경로의 미세 누락 의심 → **abort 보상 경로 정밀 검증** 후속.

> scale-out 1→2 처리율 선형성(2-A/2-B)·USL 회귀(2-C)는 사이클 6에서 측정.

---

## 환경 제약 및 한계

- 단일 인스턴스 + 로컬 메모리 한정 → 절대 TPS 무의미, **상대 비교만 유효**.
- 동기 경로는 8080 직접(gateway 홉 제외), 페이즈 2는 gateway 분산.
- DB 처리력 천장은 로컬에서 미도달(풀이 먼저 병목) — 운영 환경 재측정 필요.
