# CAPACITY-AND-SCALEOUT — 서칭 지식 정리

> 상태: 사전 준비 노트 (CAPACITY-AND-SCALEOUT 계획의 외부 지식 근거)
> 작성: 2026-06-15
> 목적: 단일 인스턴스 병목 sweep + scale-out 측정/분석에 필요한 외부 이론·실무를 정리하고, 각 항목 끝에 **우리 프로젝트 적용 시사점**을 붙인다.

---

## §1. Universal Scalability Law (USL) — scale-out 선형성 분석 이론

Neil Gunther의 모델. Amdahl 법칙에 **coherency(노드 간 조정 비용)** 항을 더해, 실제 시스템이 왜 선형으로 확장되지 않고 어느 지점에서 처리량이 *오히려 꺾이는지*를 설명한다.

**공식**:

```
X(N) = γN / (1 + α(N−1) + βN(N−1))
```

- **X(N)** — 부하 N에서의 절대 처리량
- **N** — 동시 사용자 수 또는 노드(인스턴스) 수
- **γ (gamma)** — concurrency. 이상적 선형 기울기(α=β=0이면 완전 선형)
- **α (alpha)** — **contention**. 공유 자원 대기/큐잉으로 인한 손실(직렬화 병목). 0~1
- **β (beta)** — **coherency**. 노드 간 데이터 일관성 유지(point-to-point 교환) 비용. 분산 환경에서 지배적

**처리량 3단계**:
1. α·β가 작을 때 — **선형 증가**
2. contention 증가 → **준선형 plateau**
3. β > 0 → **음의 수익**(처리량 하락 시작). 정점은 **Nmax = √((1−α)/β)**

**용량 계획 적용**: "최소 6개" 정도의 sparse 측정 포인트만으로 회귀해 α·β·γ를 추정 → 더 큰 부하를 실측 없이 예측("virtual load testing"). 피팅 결과가 **contention이 한계인지 coherency가 한계인지**를 가른다.

**→ 우리 적용 시사점**:
- 페이즈 2에서 인스턴스 N=1,2,3(+가능하면 동시성 레벨별)로 confirm 처리량을 측정 → USL 회귀로 α·β 추정.
- 우리 구조상 **α(contention)** 후보 = 공유 MySQL·공유 Kafka 브로커·공유 Redis. **β(coherency)** 후보 = EOS transactional 코디네이터·consumer rebalancing.
- Nmax를 추정하면 "이 아키텍처에서 payment 인스턴스를 몇 대까지 늘리는 게 유효한가"를 정량 답할 수 있다 — 측정→분석→개선의 분석 축.

---

## §2. k6 — saturation/knee 탐색 방법론

- **ramping-arrival-rate** executor: 단계별 목표 RPS를 정하면 k6가 VU를 동적으로 조절해 그 RPS를 유지. *부하 기준이 도착률(RPS)*이라 서버가 느려져도 요청을 계속 밀어 천장을 드러낸다(우리 `async-payment.js`가 이미 사용).
- **knee 탐색 절차**: 낮게 시작(healthy 상태 확인) → 긴 ramp으로 knee 통과 → peak에서 hold하여 saturation 확정 → 예상 capacity를 *한참 초과*해 한계 확인.
- **saturation 판정 메트릭**: latency 백분위(p50/p95/p99) + throughput(RPS) + error rate + 자원 포화(CPU·메모리·**커넥션 풀**). 단일 지표가 아니라 "응답 지연 급증 + 자원 상한 도달"의 동시 발생으로 knee를 잡는다.

**→ 우리 적용 시사점**:
- 우리 `sweep.sh`는 constant-arrival-rate를 단계적으로 올리는 변형 — 각 rate에서 steady-state actuator(Hikari active/pending, lag)를 동시 샘플링해 "포화 자원"을 직접 지목하는 방식. 사이클1에서 이 방법으로 Hikari를 정확히 짚었음.
- 페이즈 1 자원별 sweep에 그대로 재사용. 단, knee를 한 번 넘은 뒤 충분히 더 밀어 *다음 병목으로의 이동*까지 관찰(사이클1에서 풀 60·rate 300의 "DB 처리력 이동"이 그 예).

---

## §3. Kafka EOS — transactional.id와 멀티 인스턴스 (scale-out 선행 지식)

- **고유·안정 transactional.id 필수**: 각 producer 인스턴스는 고유하고 재시작에도 유지되는 transactional.id가 필요. 이게 **producer fencing**의 키 — 같은 transactional.id로 새 producer가 뜨면 Kafka가 옛 인스턴스를 fence out 해 zombie/split-brain을 막는다.
- **`initTransactions()`**: producer 인스턴스당 정확히 1회 호출. 진행 중이던 transaction을 정리한 뒤 처리 시작.
- **KIP-447 (Kafka 2.6+)**: 이전엔 transactional.id를 *입력 파티션의 정적 인코딩*으로 묶어야 해서 rebalance 시 비효율(인스턴스가 thread-safe producer 1개를 재사용 못 함). KIP-447이 consumer group metadata를 offset commit과 함께 보내도록 해 이 제약을 해소 → consume-process-produce 패턴의 producer 확장성이 개선됨.

**→ 우리 적용 시사점**:
- TC-13-FOLLOW-1의 위험이 외부 문헌으로 확인됨: compose `hostname: payment-service` 고정값을 두 컨테이너에 부여하면 **동일 transactional.id** → fencing 오동작 가능. scale-out 전 반드시 **인스턴스별 고유 transactional.id**(hostname 라인 제거로 컨테이너 id 자동 부여, 또는 `INSTANCE_ID` env 주입)로 해소.
- KIP-447 덕에 우리 EOS consume-process-produce(events.confirmed) 패턴은 멀티 인스턴스 확장에 구조적 장애물이 없다 — 남은 건 *id 고유성*과 *실증*(인스턴스 강제 종료 후 fencing 로그 확인).
- 단, transactional 코디네이터는 공유 자원 → USL의 **β(coherency)** 후보로 페이즈 2에서 주시.

---

## §4. HikariCP 풀 사이징 — 멀티 인스턴스 합산 제약 (scale-out 함정)

- **기본 공식**: `connections = (core_count × 2) + effective_spindle_count` (SSD면 spindle=1). 이 수는 *DB 한 대에 대한 전체 활성 커넥션 천장*(모든 앱 인스턴스 합산 기준).
- **멀티 인스턴스 분배**: `per_instance_pool = total_desired / num_instances`. 또는 `app_pool = (db.max_connections × 0.8) / num_instances` (20%는 마이그레이션·admin·모니터링 여유).
- **흔한 실수**: 풀 크기를 DB `max_connections`와 같게 설정. 인스턴스 5개 × 각 풀 100이면 DB가 압도됨. 반드시 인스턴스 수로 나누고 admin 여유를 남긴다.

**→ 우리 적용 시사점**:
- **scale-out의 숨은 게이트**: 사이클1에서 단일 인스턴스 풀을 60으로 올렸는데(MySQL `max_connections` 기본값 151 수용 확인 — 현재 명시 설정 없음), 이 60을 그대로 둔 채 3 인스턴스로 늘리면 60×3=180 > 151 → DB가 먼저 막힌다. 즉 *단일 인스턴스 최적값을 멀티에 그대로 쓰면 안 된다*. (대안: `max_connections`를 상향해 천장 자체를 올리는 것도 레버 — 단 MySQL 메모리 비용 동반.)
- 페이즈 2 진입 시 `min(단일 최적값, (151×0.8)/N)`로 인스턴스당 풀 재배분. 이게 "공유 자원 합산 제약을 페이즈 1에서 미리 계산"해야 하는 이유.

---

## §5. Spring Boot 가상 스레드 — pinning·throttle (페이즈 1 자원 #3)

- **pinning**: 가상 스레드가 carrier OS 스레드에서 unmount되지 못하고 고정되는 현상. 주로 `synchronized` 블록 안에서 블로킹하거나 native(JNI) 호출 시 발생. 고정되면 carrier 스레드를 점유해 다른 작업용 carrier가 줄어듦.
  - **JDBC 주의**: 드라이버가 `synchronized` 안에서 긴 블로킹 호출을 하면 그동안 가상 스레드가 pin됨.
  - 해법: `synchronized` → `ReentrantLock`(java.util.concurrent) 교체.
- **bulkhead**: 서드파티/레거시 블로킹 I/O를 *별도의 bounded OS 플랫폼 스레드 풀*로 격리해, 전역 carrier 풀(기본 256) 오염을 방지.
- **throttle = Semaphore**: 가상 스레드는 풀링하지 않으므로, 동시 접근 제한이 필요하면 `Semaphore`로 한다. 커넥션 풀은 DB가 실제 감당하는 동시성(보통 인스턴스당 20~100)에 맞춘다.
- @Async는 Spring Boot가 가상 스레드 기반 `SimpleAsyncTaskExecutor`를 자동 구성 — I/O 바운드는 수동 튜닝 불필요.

**→ 우리 적용 시사점**:
- TC-6(가상 스레드 명시 throttle/bulkhead 부재)이 페이즈 1 자원 #3과 직결. 현재 백프레셔는 다운스트림 자원(Hikari·in-flight·Lettuce)으로만 자연 형성 — 외부 PG 호출이나 다운스트림 다운 시 VT가 timeout까지 무제한 spawn → 메모리 압박 위험.
- 페이즈 1에서 **JFR로 pinning 이벤트를 관측**(고부하 시 carrier starvation 여부). pinning이 관측되면 `synchronized` 지점 식별 → `ReentrantLock` 교체 후보. 외부 PG 호출 어댑터엔 `Semaphore`/Bulkhead 도입을 측정값 기반으로 결정(T4-D Resilience4j 묶음과 연계 가능).

---

## 출처

**USL**
- [How to Quantify Scalability — Performance Dynamics (Gunther)](https://www.perfdynamics.com/Manifesto/USLscalability.html)
- [What Is Universal Scalability Law · SPE BoK](https://tangowhisky37.github.io/PracticalPerformanceAnalyst/pages/spe_fundamentals/what_is_universal_scalability_law/)
- [Measuring Software Scalability using USL — WSO2](https://wso2.com/blog/research/measuring-software-scalability-using-universal-scalability-law/)
- [A Scientific Approach to Capacity Planning — Wayfair Tech Blog](https://tech.wayfair.com/2020/01/a-scientific-approach-to-capacity-planning/)

**k6 saturation/knee**
- [Ramping arrival rate — Grafana k6 docs](https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/ramping-arrival-rate/)
- [Finding Your API's Breaking Point: Baseline Capacity Test with k6 — Medium](https://medium.com/codetodeploy/finding-your-apis-breaking-point-a-baseline-capacity-test-with-k6-grafana-834a676aa297)

**Kafka EOS / transactional.id**
- [KIP-447: Producer scalability for exactly once semantics](https://cwiki.apache.org/confluence/display/KAFKA/KIP-447:+Producer+scalability+for+exactly+once+semantics)
- [KIP-98: Exactly Once Delivery and Transactional Messaging](https://cwiki.apache.org/confluence/display/KAFKA/KIP-98+-+Exactly+Once+Delivery+and+Transactional+Messaging)
- [Transactions in Apache Kafka — Confluent](https://www.confluent.io/blog/transactions-apache-kafka/)
- [Exactly-once semantics with Kafka transactions — Strimzi](https://strimzi.io/blog/2023/05/03/kafka-transactions/)

**HikariCP 풀 사이징**
- [Pool Sizing and Performance Tuning — HikariCP DeepWiki](https://deepwiki.com/brettwooldridge/HikariCP/4.2-pool-sizing-and-performance-tuning)
- [About Pool Sizing in distributed environments / microservices — HikariCP #1023](https://github.com/brettwooldridge/HikariCP/issues/1023)

**가상 스레드**
- [The Carrier Pinning Trap: Virtual Thread Starvation in Spring Boot 3 — azguards](https://azguards.com/distributed-systems/the-carrier-pinning-trap-diagnosing-virtual-thread-starvation-in-spring-boot-3-migrations/)
- [Virtual Threads in Spring with Bulkheads and Structure — Medium](https://medium.com/@27.rahul.k/readable-concurrency-virtual-threads-in-spring-with-bulkheads-and-structure-48102ec4df4d)
