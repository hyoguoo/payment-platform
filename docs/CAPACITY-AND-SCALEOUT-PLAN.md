# CAPACITY-AND-SCALEOUT 구현 플랜

> 작성일: 2026-06-17

## 목표

단일 payment 인스턴스의 자원별 병목을 진단·처방(페이즈 1)하고, transactional.id 고유화 후 payment 1→2 scale-out 처리량 선형성을 USL로 분석(페이즈 2)하기까지 — 측정 도구 준비 → 측정 실행 → 분석·리포트가 모두 완료되면 이 플랜이 끝난다.

## 요약 브리핑

### Task 목록

1. **DLT `.dlq` resolver 정합** — 측정 막는 consumer 블로킹 버그 선제거
2. **k6 계측** — confirm·폴링 응답 시각 기록 + 폴링 전략(백오프+지터)
3. **verify-settlement 확장** — settle 자동 추종 + `payment_history` e2e + 재고 정합 교차검증
4. **측정 환경** — compose 튜닝 + reconciler 실제 반영 + hostname 제거(`apps.yml`) + gateway 분산 2 인스턴스
5. **페이즈 1-A** — 폴링 OFF 자원별 병목 sweep + 처방
6. **페이즈 1-B** — 폴링 ON 종합 + 폴링 전략 미니 실험
7. **페이즈 2-0** — transactional.id 고유화 + fencing 실증 + 튜닝 baseline
8. **페이즈 2-A/2-B** — scale-out 1→2 처리량 측정
9. **페이즈 2-C** — USL 회귀 + 피팅 스크립트
10. **리포트 종합** — REPORT 연장 SSOT

### 변경 후 전체 플로우

```mermaid
flowchart TD
    subgraph PREP[준비 T1~T4]
      T1[T1 DLT .dlq resolver] --> T2[T2 k6 계측·폴링 전략]
      T2 --> T3[T3 verify-settlement 확장] --> T4[T4 compose 튜닝·hostname 제거·gateway 2인스턴스]
    end
    PREP --> P1
    subgraph P1[페이즈 1 · 단일 인스턴스]
      T5[T5 폴링 OFF 자원 sweep·처방] --> T6[T6 폴링 ON·전략 곡선]
    end
    P1 --> P2
    subgraph P2[페이즈 2 · scale-out 1→2]
      T7[T7 고유화·fencing·튜닝 baseline] --> T8[T8 1→2 처리량·정합 게이트] --> T9[T9 USL 회귀]
    end
    P2 --> T10[T10 리포트 종합]
    CLK[(계측 이원화<br/>폴링=체감 · payment_history=처리)]
    CLK -.공통.-> P1
    CLK -.공통.-> P2
```

### 핵심 결정 → Task 매핑

- **D1** 설정 튜닝/갯수 후속 → T4(튜닝)·T5(처방)
- **D2** 로컬 2 인스턴스 → T4
- **D3** hostname 고유화 → T4·T7(fencing 실증)
- **D4** 폴링 ON/OFF·이원 계측·백오프+지터 → T2·T3·T6
- **D5** DLT `.dlq` → T1
- **D6** REPORT 연장 + USL → T9·T10
- **측정 위생**(reconciler↔settle·재고 정합·변수 격리) → T3·T4·T7·T8

### 트레이드오프 / 후속

- N≤2(로컬 메모리), 절대 TPS 무의미·상대 비교만
- transactional.id 고유화는 rebalance 좀비 fencing 미보장(알려진 한계)
- 후속: 조회 전용 인스턴스 분리, Kafka 브로커/Redis 클러스터, push(SSE), 3+ 인스턴스

---

## 컨텍스트

- 설계 문서: `docs/topics/CAPACITY-AND-SCALEOUT.md` (결정 D1~D6 + acceptance + 명시 가정 + 측정 위생)
- 서칭 지식: `docs/topics/CAPACITY-AND-SCALEOUT-RESEARCH.md`
- 측정 SSOT(연장 대상): `docs/topics/K6-ASYNC-BENCHMARK-REPORT.md`
- 주요 변경 파일:
  - 코드: `payment-service/.../KafkaErrorHandlerConfig.java` (DLT resolver)
  - 스크립트: `scripts/k6/{async-payment.js, sweep.sh, run-benchmark.sh, verify-settlement.sh}`, `scripts/bench-seed-stock.sh`, `scripts/` USL 피팅(신규)
  - 인프라: `docker/docker-compose.benchmark.yml` + `docker/docker-compose.apps.yml`(D3 — `hostname` 라인 제거)
  - 리포트: `docs/topics/CAPACITY-AND-SCALEOUT-REPORT.md` (신규, 측정 결과 SSOT)

## 진행 상황

- [x] Task 1: DLT `.dlq` destination resolver 정합 (측정 막는 버그 선제거)
- [x] Task 2: k6 계측 — confirm·폴링 응답 시각 기록 + 폴링 전략(백오프+지터)
- [x] Task 3: verify-settlement 확장 — settle 자동 추종 + payment_history e2e + 재고 정합 교차검증
- [x] Task 4: 측정 환경 — benchmark compose 튜닝 override + reconciler payment 주입 + hostname 제거 + 2 인스턴스
- [x] Task 5: 페이즈 1-A — 폴링 OFF 자원별 병목 sweep + 처방 (REPORT 사이클 3)
- [x] Task 6: 페이즈 1-B — 폴링 ON 종합 + 폴링 전략 미니 실험 (REPORT 사이클 4)
- [x] Task 7: 페이즈 2-0 — transactional.id 고유화 + fencing 실증 + 튜닝 baseline (REPORT 사이클 5)
- [x] Task 8: 페이즈 2-A/2-B — scale-out 1→2 처리량 측정 (REPORT 사이클 6 — 기각: confirm ~1.0×/e2e ~1.3×, 공유 DB 경합 병목)
- [x] Task 9: 페이즈 2-C — USL 회귀 분석 + 피팅 스크립트 (REPORT 사이클 7 — N≤2 한계, 제약식만)
- [ ] Task 10: 측정 리포트 종합 (REPORT 연장 SSOT)

---

## 태스크

### Task 1: DLT `.dlq` destination resolver 정합 [tdd=true] [domain_risk=true]

**근거**: D5 — 현재 `DeadLetterPublishingRecoverer` 단일 인자 생성자가 기본 resolver(`topic + "-dlt"`, spring-kafka 3.3.x)로 `payment.events.confirmed-dlt`(소문자 `-dlt` suffix)에 발행하나 `create-topics.sh`는 `.dlq`만 생성 + `auto.create.topics.enable=false` → 토픽 부재 → consumer 영구 블로킹(측정 오염원). 측정 시작 전 선제거.

**테스트 (RED)**
- `KafkaErrorHandlerConfigTest`(또는 recoverer 단위) — `events.confirmed` 처리 예외 소진 시 발행 목적지가 `payment.events.confirmed.dlq`(상수 `EVENTS_CONFIRMED_DLQ`)임을 검증. 현재 동작(기본 resolver → `payment.events.confirmed-dlt`)에서 RED.
- 패턴: Mockito BDD — recoverer가 받는 `TopicPartition` 목적지 캡처 후 AssertJ 단언.

**구현 (GREEN)**
- `KafkaErrorHandlerConfig`의 `DeadLetterPublishingRecoverer`에 `.dlq` 고정 destination resolver(`(record, ex) -> new TopicPartition(EVENTS_CONFIRMED_DLQ, record.partition())`) 주입.

**완료 기준**
- 신규 테스트 pass, 발행 목적지 `.dlq` 확인. `./gradlew test` 회귀 0. (commands.confirm.dlq 경로는 영향 없음 — 범위 외 확인)

**완료 결과**
> `DeadLetterPublishingRecoverer` 단일 인자 생성자가 기본 resolver(`topic + "-dlt"`)로 `payment.events.confirmed-dlt`에 발행하던 버그를 고정 destination resolver `(record, ex) -> new TopicPartition(EVENTS_CONFIRMED_DLQ, record.partition())` 주입으로 수정. `KafkaErrorHandlerConfigTest#dlq_destination_resolver_정합` 신규 추가 + 전체 테스트 회귀 0. (커밋: `c9f88e7a` RED / `feat:` GREEN)

---

### Task 2: k6 계측 — confirm·폴링 응답 시각 기록 + 폴링 전략 [tdd=false]

**근거**: D4 — 체감 latency(폴링 응답 시각) 1급 계측 + 폴링 전략(백오프+지터) ON/OFF 토글.

**구현**
- `scripts/k6/async-payment.js`: ① confirm 202 응답 시각 + 각 폴링 응답 시각을 orderId와 함께 출력(JSON 라인/커스텀 메트릭)으로 사후 조인 가능화. ② 폴링 전략 env(`POLL_STRATEGY=fixed|backoff`) — backoff면 지수 백오프 + 지터 적용. 기존 `SKIP_POLL`(폴링 OFF) 유지.

**완료 기준**
- 폴링 OFF/ON 동작, ON에서 fixed·backoff 전략 토글 동작, confirm·폴링 응답 시각이 orderId로 추출 가능. 정적 검증(k6 run 단발 smoke)으로 파싱 확인.

**완료 결과**
> `helpers.js`에 `POLL_STRATEGY=fixed|backoff` 상수 + `POLL_MAX_INTERVAL_MS` 상한 추가.
> `pollStatus` 반환값에 `resolvedAt`·`pollCount`·`pollEvents` 필드 추가 — 각 폴 응답 시각이 배열로 포함되어 orderId 기준 사후 DB 조인 가능.
> 백오프 전략: `computeBackoffJitter(attempt, base, cap)` — Full Jitter 공식(`random(0, min(cap, base × 2^attempt))`) 적용, thundering herd 방지.
> `async-payment.js`에서 confirm 202 수신 직후 `{"event":"confirm","orderId":"...","confirmAt":<epochMs>}` JSON 라인 출력.
> 폴링 종결(DONE/FAILED) 시 `{"event":"poll_done","orderId":"...","confirmAt":...,"resolvedAt":...,"pollEvents":[...]}` JSON 라인 출력.
> `e2e_completion_ms` = `resolvedAt - confirmAt`(종전 `Date.now() - confirmStartMs`에서 명시 값으로 교체).
> k6 inspect 통과(ramping·constant-arrival-rate 분기 모두). `SKIP_POLL=true` / `POLL_STRATEGY=fixed|backoff` 토글 동작 확인.

---

### Task 3: verify-settlement 확장 — settle 자동 추종 + payment_history e2e + 재고 정합 [tdd=false] [domain_risk=true]

**근거**: 측정 위생(reconciler↔settle 비연동 → silent loss 오판 차단) + D4(payment_history 처리 시각) + scale-out 재고 정합 게이트.

**구현**
- `scripts/k6/verify-settlement.sh`: ① `SETTLE_WAIT_SECONDS` 미지정 시 `RECONCILER_TIMEOUT + RECONCILER_SCAN_MS/1000 + 여유`로 자동 산출(60 고정 상수 제거). ② e2e 처리 시각 = `payment_history` 최초 DONE 전이(`MIN(change_status_at) WHERE current_status='DONE'`) SELECT 사후 산출. ③ 측정 종료 후 redis 잔여재고 vs product RDB 차감 합 교차검증 추가.

**완료 기준**
- `RECONCILER_TIMEOUT=600` 설정 시 settle 대기가 자동 추종(≥612s) 확인. payment_history 기반 e2e 산출 출력. 기존 정합 검증(DONE/미종결) 회귀 없음.
- **재고 교차검증 정합식**: silent-loss 게이트(미종결=0 AND QUARANTINED=0)와 **AND 결합** — 정산 미완(보상 미정산 ≥1건)이면 inconclusive(단독 PASS 금지). 종결 완료 상태에서만 `redis 잔여 == RDB 잔여` 성립 확인 (redis=confirm마다 DECR·FAILED·pg-QUARANTINED 보상 INCR, RDB=APPROVED만 차감 — 의미 차 반영). **단 이 단일 등식은 QUARANTINED=0 게이트 하에서만 성립** — QUARANTINED>0이면 AMOUNT_MISMATCH(redis −1 미보상)·CACHE_DOWN(net zero) 사유별로 redis↔RDB 관계가 달라지므로 사유 분해 후 판정(단일 등식 금지).

**완료 결과**
> `SETTLE_WAIT_SECONDS` 60초 고정 상수 제거 — `RECONCILER_TIMEOUT + ceil(RECONCILER_SCAN_MS/1000) + 12` 자동 산출으로 교체.
> `RECONCILER_TIMEOUT=600` 설정 시 627s(≥612s) 자동 추종 확인(dry-run). 명시 지정 시 해당값 우선.
> `payment_history` 기반 e2e 처리 시각 섹션 신설: `MIN(change_status_at) WHERE current_status='DONE' GROUP BY order_id` — `last_status_changed_at` last-write 함정 회피.
> 재고 정합 교차검증 [3] 신설: `미종결=0 AND QUARANTINED=0` 선결 게이트(AND 결합) 통과 시에만 `redis 잔여 == RDB 잔여` 단일 등식 적용.
> QUARANTINED>0 이면 AMOUNT_MISMATCH/CACHE_DOWN 사유별 redis↔RDB 관계 분기 설명 + inconclusive 처리.
> 최종 요약에 `교차식 [3]` 행 추가, 종합 판정 로직 3분기(전항목PASS / inconclusive / 불일치) 갱신.
> bash 문법 검사(`bash -n`) + 산출식 dry-run 전케이스 통과.

---

### Task 4: 측정 환경 — compose 튜닝 + reconciler 실제 반영 + hostname 제거 + gateway 분산 2 인스턴스 [tdd=false]

**근거**: D1(설정 튜닝)·D2(2 인스턴스)·D3(hostname 제거)·측정 위생(reconciler 600s 실제 반영).

**구현**
- `docker/docker-compose.benchmark.yml`: ① MySQL `max_connections`·Redis(Lettuce) 커넥션 풀 등 튜닝 env 가능화. ② payment 2 인스턴스 scale 대응 + **gateway 복귀** — payment 8080 직노출 제거 → gateway 포트 노출, `run-benchmark.sh` `BASE_URL`을 gateway로 전환(인스턴스 분산).
- `docker/docker-compose.apps.yml`: ③ payment-service **`hostname: payment-service`(:30) 라인 제거**(D3 — `transactional-id-prefix=${app}-${HOSTNAME:local}`가 2 인스턴스에서 동일값 충돌하는 근본 원인). 제거가 smoke/일반 기동 discovery에 주는 부수효과 1줄 확인.
- `scripts/k6/run-benchmark.sh`: reconciler env(`RECONCILER_IN_FLIGHT_TIMEOUT_SECONDS`)는 benchmark compose에 이미 payment-service로 주입돼 있으나, **run-benchmark가 payment-service를 `--force-recreate`하지 않아** host의 `RECONCILER_TIMEOUT=600` export가 기존 컨테이너에 반영 안 됨 → payment-service도 force-recreate(또는 측정 전 사전 기동 시 export)로 600s 실제 반영.

**완료 기준**
- 튜닝 env 적용 확인. payment-service actuator/env 에서 `RECONCILER_IN_FLIGHT_TIMEOUT_SECONDS=600` 실제 반영 검증. hostname 제거 후 2 인스턴스 `transactional-id-prefix` 상이(고유) 확인. gateway 경유 2 인스턴스 분산 기동 + `BASE_URL` gateway 전환.

**완료 결과**
> - `docker/docker-compose.benchmark.yml`: ① payment-service 8080 직노출 `ports` 제거(gateway 복귀 D2). ② MySQL `MYSQL_MAX_CONN` env → `--max_connections` command 주입(기본 300; 기본값 151에서 상향). ③ Lettuce 커넥션 풀 3종(`LETTUCE_MAX_ACTIVE/MAX_IDLE/MIN_IDLE`) env 추가(기본 16/8/2). ④ 헤더 주석 환경변수 목록 갱신.
> - `docker/docker-compose.apps.yml`: payment-service `hostname: payment-service` 라인 제거(D3). 제거 후 부수효과 — HOSTNAME은 Docker 컨테이너 자동 ID가 되어 Eureka instanceId(`payment-service:<컨테이너ID>:8080`)·transactional-id-prefix(`payment-service-<컨테이너ID>-`)가 인스턴스별 고유화됨. gateway discovery는 Eureka application name(`lb://payment-service`) 기준이라 hostname 변경에 무관, smoke/일반 단일 기동도 영향 없음.
> - `scripts/k6/run-benchmark.sh`: ① `BASE_URL` 기본값 `http://localhost:8090`(gateway)으로 전환. ② `restart_payment_with_reconciler()` 함수 신설 — 헬스체크 직후 payment-service force-recreate, RECONCILER_TIMEOUT 실제 반영 보장. ③ 주석 사용법 갱신.
> - 정적 검증: `docker compose config --quiet` (4-파일 benchmark + 2-파일 일반 기동 양쪽) 오류 0. `bash -n` lint 통과.

---

### Task 5: 페이즈 1-A — 폴링 OFF 자원별 병목 sweep + 처방 [tdd=false]

**근거**: H1 + 페이즈 1 acceptance. 측정 대상 자원 = MySQL 처리력 / Kafka in-flight / 가상 스레드 throttle / outbox relay 배치 / Redis 커넥션 / pg 워커.

**구현**
- `sweep.sh`(`SKIP_POLL=true`)로 자원별 rate sweep → knee + actuator 포화 지표 식별 → 처방 → 재측정. 변수 격리(한 자원씩). 워밍업 구간 폐기.

**완료 기준**
- 자원별 knee가 포화 지표와 함께 식별되고, 처방 후 재측정에서 p95/pending 개선이 정량 기록(acceptance 페이즈 1 충족). 인스턴스당 권장 설정 1세트 도출(페이즈 2 입력).

**완료 결과**
> (execute에서 채움)

---

### Task 6: 페이즈 1-B — 폴링 ON 종합 + 폴링 전략 미니 실험 [tdd=false]

**근거**: D4(폴링 ON 운영 프로파일) + 페이즈 1-B acceptance.

**구현**
- 폴링 ON(백오프+지터) 종합 부하 sweep(처방된 단일 인스턴스). + 폴링 전략 미니 실험: rate·인스턴스 고정, 전략/간격만 sweep → (체감 latency p95, 서버 폴링 부하 req/s) 트레이드오프 곡선 + 권장값.

**완료 기준**
- 운영 종합 capacity 정량 + 폴링 전략 트레이드오프 곡선 + 권장 폴링값 1개(페이즈 2-B 입력). 체감(폴링)·처리(payment_history) 이원 계측으로 폴링 비용 분리 확인.

**완료 결과**
> (execute에서 채움)

---

### Task 7: 페이즈 2-0 — transactional.id 고유화 적용 + fencing 실증 + 튜닝 baseline [tdd=false] [domain_risk=true] ✅

**근거**: D3(고유화) + 명시 가정(fencing trade-off) + 변수 격리(튜닝 baseline 먼저).

**구현**
- hostname 제거 적용 후 2 인스턴스 기동 → fencing 실증: ① 정상 2 인스턴스 중복 events.confirmed 0건 ② rebalance 유발 시 중복 0건 확인(명시 가정 검증). + 변수 격리: 1 인스턴스 + 페이즈 1 처방 튜닝 설정으로 baseline 재측정(튜닝 효과 격리).

**완료 기준**
- 2 인스턴스 transactional.id 고유 + 중복 발행 0(정상·rebalance) 실증. 1 인스턴스 튜닝 baseline 처리율 정량(scale-out 비교 기준점).
- **의도적 id 충돌 실증은 측정 데이터셋과 분리된 run(또는 clean 재시드 상태)에서** 수행하고, 충돌·fence 후 verify-settlement(Task 3 재고 정합 게이트 포함) 재실행으로 silent loss 0 + 재고 정합 유지 확인 — abort→재배달이 데이터를 깨지 않음을 못박는다.

**완료 결과** (상세 = REPORT 사이클 5)
> 측정 환경: payment `ports: "8080"`(host 동적) → scale=2 충돌 회피, gateway lb 분산, actuator는 동적 포트 수집. Hikari 80·reconciler 600s(충돌 run만 30s).
> - **baseline**(1 인스턴스, gateway, 폴링 OFF): 처리 한계 ≈ rate 450(active가 풀 상한 80 도달 직전). 페이즈 1(8080 직접 knee 450)과 일치 — gateway 홉은 레이턴시만 추가, 처리 한계 동일. scale-out 1× 기준점 확보.
> - **정상 2 인스턴스 fencing**: transactional.id 고유(HOSTNAME 상이) + ProducerFenced 0 + 분산 편차 0.69%(2460/2443) + 중복 발행 0.
> - **rebalance**(부하 중 인스턴스 restart): rebalance 이벤트 발생 + ProducerFenced 0 + 중복 0.
> - **의도적 id 충돌**(prefix `payment-collision-fixed` 강제 + rebalance overlap): ProducerFenced 9건 재현. 재고 정합 차이 3건(0.12%) — abort→재배달이 대량 유실 미생성(EOS read_committed 보호). silent loss는 재고 기준 사실상 0.
> - **핵심 통찰**: txn.id = `prefix+group+topic+partition`(consumer-initiated EOS)이라 정상 배타 파티션에선 prefix 충돌해도 무탈, **rebalance overlap 순간에만 fencing** → D3의 가치는 "정상 충돌 방지"가 아니라 **rebalance 전환 안전성**.
> - **부가 발견(후속)**: ① 인스턴스 restart 가용성 갭 16%(graceful shutdown/retry) ② 재고 미세 갭 3건(abort 보상 INCR 경로 정밀 검증).
> - **측정 자산**: `docker-compose.benchmark.yml` payment ports 동적화(커밋), 충돌 override는 `/tmp` 임시(미커밋).

---

### Task 8: 페이즈 2-A/2-B — scale-out 1→2 처리량 측정 [tdd=false] [domain_risk=true]

**근거**: H2 + 페이즈 2 acceptance + scale-out 재고 정합 게이트.

**구현**
- 2-A 폴링 OFF: 1→2 인스턴스 confirm 처리율 선형성 + 어느 공유 자원이 먼저 병목(Hikari/Kafka lag/Redis). 2-B 폴링 ON(권장 폴링값): 운영 종합 capacity 1→2. 부하 분산 균등성(편차 ≤10%) 검증. 측정 종료 후 redis↔RDB 재고 정합 교차검증.

**완료 기준**
- 처리율비 정량(합격 ≥1.6× & 분산 편차 ≤10% & silent loss 0 & 재고 정합 — Task 3 정합식 AND 결합·종결 완료 선결). 기각 시 병목 공유 자원 귀속 기록. **consumer events.confirmed 파티션 점유(파티션 3 vs 인스턴스 2 = 2:1 편향)를 측정 메타로 기록** — 비선형 귀속 시 gateway HTTP 분산 편차와 consumer 파티션 편향을 구분. (페이즈 3+ 후속 트리거 판정)

**완료 결과** (상세 = REPORT 사이클 6)
> 정상 2 인스턴스(고유 id, reconciler 600s), gateway lb, 재고 1천만 재시드.
> - **측정 위생 교훈**: 재기동 직후 콜드 JVM에 rate 300 → p95 2.28s(1 인스턴스보다 나쁨) 콜드 오염. 워밍업 후 재측정으로 정상화.
> - **6-A 폴링 OFF**: 2 인스턴스 knee ≈ 450 = 1 인스턴스와 동일 → **scale-out ~1.0×(선형성 없음)**. 양쪽 Hikari active 합 160 포화인데 처리율 정체.
> - **6-B 병목 귀속**: CPU 합 5.5/10(여유) → CPU 아님. MySQL 147% 정체 + confirm latency 2.7배 → **MySQL lock/IO + Kafka EOS commit 직렬화** 경합.
> - **6-C 폴링 ON(backoff)**: e2e capacity rate 100~125(1 인스턴스 75 대비 ~1.3×). 폴링 자가부하 병목.
> - **6-D 정합 게이트 통과**: 재고 redis==RDB 차이 0 + 측정 구간 미종결 0(silent loss 0). 파티션 2:1 편향 → 고발행 시 consumer 백로그 비대칭(인스턴스A 4591/B 0).
> - **결론**: 처리율비 confirm 1.0×/e2e 1.3× < 합격 1.6× → **기각**. 분산·정합·무결성 ✅. 병목=공유 DB 경합+폴링 자가부하. 후속: DB 스케일·파티션 수=인스턴스 배수.

---

### Task 9: 페이즈 2-C — USL 회귀 분석 + 피팅 스크립트 [tdd=false]

**근거**: D6(USL) + 페이즈 2-C.

**구현**
- `scripts/`에 USL 피팅 스크립트 신규(α·β·γ 회귀, `X(N)=γN/(1+α(N−1)+βN(N−1))`). Task 8 측정점(N·동시성별 처리량) 적용 → α(contention)·β(coherency)·Nmax 추정 + 잔차 확인.

**완료 기준**
- α·β·γ 추정값 + Nmax + 피팅 잔차(노이즈 수준일 때만 Nmax 채택, acceptance). 스크립트 재현 가능(입력 CSV → 출력 파라미터).

**완료 결과** (상세 = REPORT 사이클 7)
> `scripts/usl-fit.py`(순수 Python, 의존 없음) + 입력 CSV `scripts/usl-data/{confirm-sync,e2e-poll}.csv`.
> - **N≤2 한계**: 측정점 2개 < 파라미터 3개 → underdetermined. γ=X(1)만 확정, α·β 분리 불가.
> - **confirm 동기**: γ=450, 제약 α+2β=1(X(2)/X(1)=1.0), Nmax 1.41(α=0 경계)~∞ → scale-out 이득 사실상 0 재확인.
> - **e2e 폴링**: γ=75, 제약 α+2β=0.5(X(2)/X(1)=1.33), Nmax 2.0~∞.
> - 스크립트는 측정점 ≥4 시 grid search 로 α·β·γ 자동 회귀 — N 확장 시 다점 피팅 재현 자산.

---

### Task 10: 측정 리포트 종합 (REPORT 연장 SSOT) [tdd=false]

**근거**: D6(REPORT 형식 연장).

**구현**
- `docs/topics/CAPACITY-AND-SCALEOUT-REPORT.md` 작성 — K6-ASYNC-BENCHMARK-REPORT 형식 연장. 페이즈 1 자원 sweep(사이클 3·4…), 폴링 전략 곡선, scale-out 1→2, USL 분석, 환경 제약. 결론 + 페이즈 3+ 후속 트리거.

**완료 기준**
- 모든 측정(Task 5~9) 결과가 리포트에 정량 기록 + 결론 + 후속. raw `results/*.json`은 gitignore(리포트가 SSOT).

**완료 결과**
> (execute에서 채움)

---

## 리뷰 처리

> (ship 단계에서 채움 — finding별 채택/스킵 + 사유)
