# 범위 밖 할 일

작업 중 발견했지만 그 작업의 범위가 아니었던 문제, 리뷰에서 보류한 항목, 다음 토픽 후보를 남긴다. 처리하면 지운다.
일부만 처리했으면 처리한 문장만 지우고 남은 일은 둔다. discuss에서 다음 작업을 고를 때 참고한다.

항목 코드(`T4-A`, `TQ-2` 등)는 예전 문서와 archive가 같은 코드로 가리키므로 유지한다. 코드의 숫자는 일정 단계를 뜻하지 않는다.

## 목록

| 코드 | 내용 | 필요한 환경 | 상세 |
|---|---|---|---|
| RETRY-WINDOW | 재시도 창이 줄어 격리로 남는 결제가 늘었다. 격리 복구·환불의 우선순위 재평가 | 없음 | [아래](#retry-window--재시도-창-축소로-커진-격리-복구의-무게) |
| OUTBOX-STALE-PUBLISH | 결과를 기다리는 결제에 확정 명령이 뒤늦게 나가는 경로 | 없음 | [아래](#outbox-stale-publish--결과를-기다리는-결제에-확정-명령이-뒤늦게-나가는-경로) |
| QUARANTINE-TRACE-EXPIRY | 선차감 흔적(8일)이 만료되기 전에 격리 종결을 알리는 알람 | 없음 | [아래](#quarantine-trace-expiry--선차감-흔적-만료-임박-알람) |
| KAFKA-PARTITION-DEFAULT | 파티션·컨슈머 동시성을 기본값으로 올릴지 결정 | 없음(되돌릴 수 없는 변경) | [아래](#kafka-partition-default--파티션컨슈머-동시성-기본값-결정) |
| REPLICA-ROUTING | 읽기 복제본을 상시 운영으로 올릴 때 첫 조회 공백 확인 | 없음 | [아래](#replica-routing--읽기-복제본-상시-운영-전-확인) |
| STOCK-GATE-NODE-BATCHING | 재고 게이트의 캐시 왕복을 Redis 노드 단위로 묶기 | 없음(병목으로 드러나면) | [아래](#stock-gate-node-batching--캐시-왕복을-노드-단위로-묶기) |
| CONFIRM-IDEMPOTENCY-KEY | 확정 요청에 `Idempotency-Key` 헤더가 없다 | 없음 | 주문 접수에는 헤더가 있으나 확정에는 없다. 재고 게이트 작업에서 주문 단위 선점으로 대응했고 헤더 도입은 범위 밖으로 남겼다 |
| WIKI-BROKEN-REFS | 위키의 끊긴 참조 2곳 | 위키 저장소 | `architecture.md`의 FCG 상세 링크가 `pg-confirm-flow`를 가리키는데 그 문서에 FCG 설명이 없다. `message-delivery-and-dedupe.md` 서두가 DLQ 처리를 다룬다고 하는데 본문에 DLQ 절이 없다 |
| BLOG-POST-2 | 블로그 2편 "재시도 소진 이후 처리" | blog 저장소 | 1편(모놀리식 → 4서비스 분리, `msa-transition-decisions.md`)은 끝났다. 1편 결말이 "payment→pg HTTP 조회 통로를 두지 않은 결정이 넉 달 뒤 문제가 됐다"로 이어지게 써 뒀다. 소재는 `docs/archive/retry-exhaustion-disposition/` |
| CHECKSTYLE-SUPPRESSION-SCOPE | Checkstyle 억제 목록이 디렉토리 단위로 모든 검사를 끈다 | 없음 | `config/checkstyle/checkstyle-suppressions.xml`이 `dto`, `entity`, `infrastructure`, `presentation`, `util`, `config` 등에서 `checks=".*"`로 전부 억제해, `var`·`@Data` 금지 같은 코드 규칙이 그 아래 파일에 적용되지 않는다(2026-11-06 hook 판정 중 확인). 규칙별로 억제 범위를 좁힐지 정한다. 좁히면 기존 위반이 드러난다 |
| STALE-COMMENTS | 코드·스크립트 주석의 낡은 서술 | 없음 | `scripts/smoke/create-topics.sh` 머리 주석에 남은 옛 디렉토리 이름(phase-gate), `OutboxRelayService`의 메시지 생성 주석에 남은 "pg-service Redis dedupe 키", `docker/docker-compose.infra.yml`의 redis-dedupe 주석(pg는 Redis를 쓰지 않는다), `payment-service/src/main/resources/application.yml` 주석의 "CONCERNS.md L6"(문서는 PITFALLS로 합쳐짐) |
| T4-A | Toxiproxy 장애 주입 시나리오 8종 | 장애 주입 | [아래](#t4-a--toxiproxy-장애-주입-시나리오) |
| T4-B | 알람 정밀화(guard-skip 라벨, coordinator lag 임계) | 멀티 broker | [아래](#t4-b--알람-정밀화) |
| T4-C | 로컬 오토스케일러 | 부하 측정 | Prometheus 큐 길이·CPU 임계로 payment-service 레플리카를 자동으로 늘리고 줄인다. 결정 로그와 Grafana 대시보드 |
| T4-D | CircuitBreaker와 타임아웃 튜닝 | 부하 측정 | [아래](#t4-d--circuitbreaker와-타임아웃-튜닝) |
| T4-E | scale-out 후속: payment 무중단 재시작, 재고 갭 관찰 | 멀티 인스턴스 | [아래](#t4-e--scale-out-후속) |
| CONFIRM-TERMINAL-REJECT | 종결·격리된 결제에 늦은 confirm 재요청이 오면 재고 차감이 먼저 일어난다 | 없음 | `PaymentEvent.validateConfirmRequest`는 사용자·금액·주문번호·paymentKey만 검사하고 상태를 보지 않는다. 진입에 종결·격리 상태 조기 거부 가드를 둔다. 지금은 차감이 선차감 기록 회수 작업(기본 60초 주기)으로 되돌려지지만 그 사이 게이트가 빡빡하다 |
| TQ-1 | DLQ 조건부 자동 재시도 | 없음 | 관리자 수동 재주입은 있다(`payment.events.confirmed.dlq`). 벤더 5xx 같은 일시 실패를 자동으로 재발행하는 상시 컨슈머는 없다. 상세: `docs/archive/dlq-quarantine-recovery/COMPLETION-BRIEFING.md` |
| TQ-2 | 승인으로 확인된 격리 결제를 `DONE`으로 되살리는 경로 | 없음 | 격리 결제는 관리자 안전 실패 종결만 있다. 되살리려면 payment→pg 상태 조회, 재고 원장 재기록(stock-committed 재발행, Redis 재정렬), 동시성 처리가 먼저 필요하다 |
| TQ-3 | Redis 재고 캐시 장애 정책 | 장애 주입 | [아래](#tq-3--redis-재고-캐시-장애-정책) |
| TQ-4 | 벤더 동적 라우팅 | 없음 | `gatewayType`은 클라이언트가 고른다. 벤더 장애 시 자동 전환과 헬스 체크 기반 라우팅이 없다 |
| TQ-5 | Kafka broker 다중화 | 멀티 broker | 지금은 broker 1대, replication-factor 1. HA 환경 검증이 필요하다 |
| TQ-6 | 취소·환불 | 없음 | pg-service에 벤더 취소·환불 포트가 없다. 운영 취소 정책, 부분 환불, 감사 기록이 필요하다 |
| TC-3 | 재고 캐시 재동기화의 남은 일 | 없음 | [아래](#tc-3--재고-캐시-재동기화의-남은-일) |
| TC-6 | 가상 스레드 명시적 동시 실행 제한 | 부하 측정 | [아래](#tc-6--가상-스레드-명시적-동시-실행-제한) |
| TC-7 | `payment_outbox` 재시도 정책과 쓰이지 않는 스키마·지표 | 부하 측정 | [아래](#tc-7--payment_outbox-재시도-정책) |
| TC-12 | pg-service 워커 종료 시 채널 drain (보류) | 없음 | [아래](#tc-12--pg-service-워커-종료-시-채널-drain-보류) |
| TC-15 | pg 리스너 분리의 기준값 정밀화와 멀티 인스턴스 검증 | 부하 측정, 멀티 인스턴스 | [아래](#tc-15--pg-리스너-분리의-기준값-정밀화) |

---

### RETRY-WINDOW — 재시도 창 축소로 커진 격리 복구의 무게

- **현황**: 재시도 백오프 회차를 바로잡으며 총 재시도 창이 78초에서 26초로 줄었다(`PgVendorCallService`의 재시도 발행 행 삽입). 벤더 장애가 그 사이 길이로 이어지면 예전에는 재시도로 회복했을 결제가 격리로 남는다.
- **이미 줄인 것**: 소진 시점에 벤더 상태를 확인하는 관문이 있어 승인·확정 실패로 갈리는 결제는 격리에 들어오지 않는다. 격리에 남는 것은 조회 실패, 벤더 미결론, 부분 취소, 금액 불일치 넷이다. 안전 실패 종결 전에는 벤더 상태를 조회해 승인이면 거부한다.
- **남은 일**: 승인으로 확인된 격리 결제는 되살릴 경로가 없어 그대로 남는다(TQ-2). 벤더 취소·환불 포트도 없다(TQ-6). 종결을 동시에 두 번 제출하면 상태 조건만으로 먼저 통과한 쪽이 이긴다. 격리 복구와 환불의 우선순위를 이 사실에 맞춰 다시 정한다.

### OUTBOX-STALE-PUBLISH — 결과를 기다리는 결제에 확정 명령이 뒤늦게 나가는 경로

- **현황**: broker 장애로 발행이 밀리면 리컨실러가 결제를 결과 대기(`AWAITING_RESULT`)로 되돌린다. 그 사이 `payment_outbox` 행은 따로 재시도를 이어가고, broker가 돌아오면 확정 명령을 발행한다. 벤더가 승인하면 되돌릴 수 없는 과금이 남고 payment는 이미 종결된 상태로 보고 넘어간다.
- **가드**: `OutboxRelayService`에 발행 직전 결제 상태 가드가 있지만, 결과 대기는 확정 결과를 받을 수 있는 상태라 이 창에서는 걸리지 않는다. 2차 임계(기본 900초)를 넘으면 격리로 올라가고, 그 뒤에는 가드가 걸린다. 창에 상한은 있다.
- **처방**: 세 위험을 함께 따져야 한다. 종결시키면 재주입이 막히고, 늦은 확정 명령은 벤더 과금을 남기고, 재고 보상 없이 만료하면 선차감분이 잠긴다. 정리 작업이 주문까지 되돌리는 단순한 방법은 첫 번째 위험 때문에 이미 기각됐다. 재고 보상 설계를 포함한 별도 토픽이 필요하다.
- **되돌릴 수 없는 이유**: 벤더 취소·환불 포트가 없어(TQ-6) 늦게 나간 확정 명령이 승인되면 되돌릴 방법이 없다. 가드가 실제로 막는 상태(DONE/FAILED/CANCELED/PARTIAL_CANCELED/QUARANTINED)는 확정 명령이 최소 한 번 발행된 뒤에야 도달하므로, "아직 한 번도 발행 못 한 행"과는 동시에 성립하지 않는다(`OutboxRelayService.relay`).

### QUARANTINE-TRACE-EXPIRY — 선차감 흔적 만료 임박 알람

- `decrement:done` 흔적은 8일(P8D) 동안 남는다. 부분 취소 격리는 즉시 보상을 건너뛰므로 이 흔적이 재고를 되돌릴 유일한 근거다. 8일 안에 관리자가 종결하지 않으면 흔적이 만료돼 재고가 돌아오지 않는다. 재고가 덜 풀리는 쪽이라 과매도로 번지지는 않지만 사람이 맞춰야 한다.
- 선차감 기록이 상품별로 남아 미회수 건수는 지표로 볼 수 있다. 다만 되돌리기는 여전히 캐시 흔적이 있어야 하므로 만료 임박 알람이 필요하다. 격리 나이 지표와 만료 임박 알람을 만든다.

### KAFKA-PARTITION-DEFAULT — 파티션·컨슈머 동시성 기본값 결정

- **현황**: 토픽 파티션은 3이다. 같은 값이 네 곳에 따로 적혀 있다: `scripts/smoke/create-topics.sh`(`PARTITIONS=3`), payment·pg의 `KafkaTopicConfig`(`PARTITIONS = 3`), `docker/docker-compose.infra.yml`(`KAFKA_NUM_PARTITIONS`). pg-service 리스너 동시성은 지정하지 않아 Spring 기본 1이고, payment의 결과 소비 동시성도 기본 1이다.
- **측정**: 파티션·동시성을 9로 올리면 부하 중 종결이 10.2/s에서 18.9/s(1.86배)로 늘었다. 거기서 파티션 18에 인스턴스 2대씩 붙이면 1.09배에 그쳤다. 상세: `docs/archive/shared-resource-scaleout/COMPLETION-BRIEFING.md`. 처리량 측정 토픽(PAYMENT-THROUGHPUT)도 결과 소비 동시성 1→3을 첫 비교 후보로 두고 있다.
- **되돌릴 수 없다**: 파티션은 줄일 수 없고, 기존 토픽에서 늘리면 키 해시가 재배치돼 같은 주문의 진행 중 메시지가 다른 파티션으로 갈라진다. Spring `KafkaAdmin`은 선언값이 실제보다 크면 기동할 때 자동으로 늘리므로, 상수만 바꾸면 배포와 동시에 이 일이 일어난다.
- **처방**: (1) 파티션 안 순서에 실제로 기대는지 확인한다(접수대장·멱등 계층이 흡수하는지). (2) 기대면 빈 토픽 재생성이나 무중단 전환 절차가 필요하다. (3) 동시성은 파티션과 함께 올린다. 승격한다면 네 곳의 값을 한 곳으로 모으는 일이 먼저다. 측정용 환경 변수 `KAFKA_TOPIC_PARTITIONS`, `PG_CONSUMER_CONCURRENCY`, `CONFIRMED_CONSUMER_CONCURRENCY`는 이미 있다.

### REPLICA-ROUTING — 읽기 복제본 상시 운영 전 확인

- `payment.datasource.replica.enabled` 기본값은 `false`다. 벤치마크(`docker/docker-compose.scaleout.yml`)에서만 켠다.
- 켜면 복제가 비동기라, checkout 커밋 직후 클라이언트가 바로 상태를 조회하면 복제본에 행이 아직 없어 404(`PAYMENT_EVENT_NOT_FOUND`)가 날 수 있다. 원본으로 다시 조회하지 않는 것은 의도한 선택이다.
- 복제본은 상태 조회 표시에만 쓰고(`PaymentController`), 확정·멱등·종결 판정은 기본 데이터소스를 쓴다. `ReplicaDataSourceIsolationTest`가 빈 의존 관계로 이를 고정한다.
- 상시 운영으로 올릴 때 클라이언트 조회에 짧은 재시도가 있어 이 창을 흡수하는지 확인한다. 없으면 첫 조회만 원본으로 보내야 한다.

### STOCK-GATE-NODE-BATCHING — 캐시 왕복을 노드 단위로 묶기

- 재고 게이트가 상품 단위로 나뉘면서 캐시 왕복이 주문당 1회에서 선점 1 + 상품 N + 해제 1회로 늘었다. 라이브 실측(2026-08-18)에서 확정 요청 중앙값이 상품 1개 25.6ms, 2개 32.3ms, 3개 36.3ms로, 왕복 하나에 약 5ms씩 늘었다.
- 키가 상품 기준 해시태그라 같은 노드에 떨어진 상품은 스크립트 하나로 묶을 수 있다. 그러면 왕복이 노드 수에 비례한다.
- 원자성이 노드 그룹 안에서만 성립하는 구조가 더해지므로, 왕복이 실제 병목으로 드러날 때 도입한다.

### T4-A — Toxiproxy 장애 주입 시나리오

Kafka producer·consumer 지연, DB 지연·연결 끊김, payment·pg 프로세스 종료 후 재시작, 보상 TX 중복 진입 방지, FCG(격리 전 최종 확인)의 PG 타임아웃, Redis 중복 판정·재고 캐시 다운, 재고 캐시 발산, DLQ 소진.
이미 있는 것: 지연 주입 드릴 프로파일(`docker/docker-compose.drill.yml`, `docker/toxiproxy.json`)과 이를 확인하는 알람 규칙(`ServiceDown`, `DependencyDown`, `KafkaBrokerUnavailable`, `DlqTopicOffsetRising`). 남은 것은 아래 시나리오 전수와 k6 부하 결합이다.
시나리오마다 `payment_outbox_pending_age_seconds` p95 10초 이상 같은 관측 기준과 결제·재고 정합성 교차 검증을 둔다.
지금 Toxiproxy 드릴은 broker 1대라 지연이 발행·소비에 대칭으로 걸려 consumer만의 지연을 만들 수 없다.

### T4-B — 알람 정밀화

- guard-skip 알람의 `status` 라벨은 결제의 현재 상태만 담아, 위험한 경우(격리된 결제에 늦게 온 승인)와 무해한 경우(실패·격리 결과의 재전송)를 구분하지 못한다. 멀티 broker 실측 때 수신 메시지의 상태를 라벨로 더한다. 그때까지는 잘못된 호출을 피하려고 warning을 유지한다.
- `KafkaCoordinatorLagHigh` 임계 1000은 broker 1대 드릴에서 도달할 수 없어(실측 최고 약 150) 검증되지 않았다. 멀티 broker 실측 뒤 다시 정한다. 그때까지는 트랜잭션 abort가 1차 신호다.

### T4-D — CircuitBreaker와 타임아웃 튜닝

- `ProductHttpAdapter`, `UserHttpAdapter`에 Resilience4j CircuitBreaker와 상태 지표를 붙인다. 같이 어댑터의 `feign.RetryableException` 처리를 Feign fallbackFactory로 옮긴다.
- Feign 타임아웃(`spring.cloud.openfeign.client.config.default`의 connect 2000ms, read 5000ms)과 pg-service 외부 PG 타임아웃(`pg.http.connect-timeout-millis` 3000, `pg.http.read-timeout-millis` 10000)은 측정 없이 정한 값이다. 부하·장애 주입 측정으로 다시 정한다. `max.poll.records` 기본값(500) 검증도 함께 한다.

### T4-E — scale-out 후속

- payment 무중단 재시작과 gateway 재시도: 인스턴스 재시작·증설 때 내려간 인스턴스로 라우팅된 확정 요청이 실패해 가용성 공백이 16% 생겼다.
- 재시작 시 재고 갭: 재시작 순간 Redis 재고가 DB보다 미세하게(0.1%대) 작아지는 갭이 생긴다. 재전송으로 저절로 맞춰지는지, DLQ로 간 뒤 리컨실러가 회수하는지 오래 관찰한다.
- 상세: `docs/archive/capacity-and-scaleout/`, `docs/archive/shared-resource-scaleout/COMPLETION-BRIEFING.md`. DB 쓰기 샤딩은 서버 커밋 능력이 충분하다는 측정으로 필요 없다고 결론 났다.

### TQ-3 — Redis 재고 캐시 장애 정책

- `redis-stock`이 다운되면 지금은 격리 + 보상 대기로 간다.
- Redis 데이터를 잃으면 부팅 재시드(`scripts/seed-stock.sh`) 말고 회복 경로가 없다. 결제가 진행 중이면 키가 없어 차감이 음수가 될 수 있다.
- Redis HA, 대체 경로, AOF 운영 기준을 정해야 한다.

### TC-3 — 재고 캐시 재동기화의 남은 일

관리자 단건 재동기화(`POST /admin/stock/resync/{productId}`)는 product DB 재고로 Redis 재고를 덮어쓴다. 진행 중 선차감을 덮어써 과매도가 날 수 있어
트래픽이 조용한 때 특정 상품에만 쓰는 것이 전제다. 전체 일괄 재동기화와 발산 자동 감지는 외부 DB 직접 변경을 잡지 못하는 한계와 서비스 간 복잡도 때문에
채택하지 않았다. 주기 재시드는 진행 중 선차감을 덮어써 기각했다.

### TC-6 — 가상 스레드 명시적 동시 실행 제한

- 지금 백프레셔는 다운스트림 자원(Hikari 30, Kafka in-flight 5, Redis 단일 연결, 스케줄러 배치 50)에서 자연히 생긴다. `Semaphore`나 Resilience4j `Bulkhead` 같은 명시적 제한은 없다.
- 외부 PG의 요청 한도를 넘거나, 다운스트림이 죽었을 때 타임아웃까지 가상 스레드가 쌓여 메모리를 누를 수 있다.
- T4-D와 함께 Bulkhead를 넣거나 외부 PG 어댑터에 Semaphore를 둔다. 측정값으로 정한다.

### TC-7 — `payment_outbox` 재시도 정책

- `RetryPolicy`(최대 5회, 고정 5초)와 `RetryPolicyProperties`가 있지만 프로덕션에서 소진 판정을 부르는 곳이 없다. 실제 발행 실패는 `OutboxRelayService`의 단일 트랜잭션 롤백으로 처리돼 재시도 횟수 증가나 `FAILED` 종결 없이 5초마다 백오프 없이 재시도된다.
- 타임아웃 회수 경로도 실행되지 않는다. 발행이 한 트랜잭션 안에서 PENDING → IN_FLIGHT → DONE을 모두 처리해, 커밋된 상태는 DONE이나 PENDING뿐이다. 그래서 IN_FLIGHT 행을 찾는 회수 조회에 걸리는 행이 없다.
- 그 결과 실행되지 않는 정책을 가리키는 스키마와 지표가 남았다. 쓰이지 않던 `available_at`은 payment V6 마이그레이션에서 이미 지웠다.

  | 대상 | 실제 값 | 쓰는 곳 |
  |---|---|---|
  | `payment_outbox.retry_count` | 항상 0 | `attempt_count_histogram` 지표 |
  | `payment_outbox.next_retry_at` | 항상 null | 선점·배치 조회 조건 2곳, `future_pending_count` 지표 |
  | `payment_outbox.in_flight_at` | DONE 행에 값이 있음 | 타임아웃 회수 조회(걸리는 행 없음) |

- 결정할 것: (1) 별도 트랜잭션 선점 경로를 되살려 정책을 실제로 연결할지, 지금의 단일 트랜잭션 재시도에 백오프를 옮길지. 5회·5초가 적절한지 측정으로 확인한다. (2) 정책을 두지 않기로 하면 위 컬럼 3개, 지표 2개, 도달하지 않는 상태(`IN_FLIGHT` 커밋, `FAILED`)를 함께 걷는다. `next_retry_at`은 조회 3곳과 인덱스에 엮여 있어 결제 핵심 테이블의 파괴적 마이그레이션이 되므로 별도 토픽이 필요하다. (1)이 먼저다.
- 관련 코드: `PaymentOutbox`, `RetryPolicy`, `RetryPolicyProperties`, `PaymentOutboxMetrics`, `payment-service/src/main/resources/db/migration/V1__payment_schema.sql`

### TC-12 — pg-service 워커 종료 시 채널 drain (보류)

- **보류 이유 (2026-06-14)**: 채널에 남은 작업은 DB(`pg_outbox`, `pg_inbox`)와 폴링 회수로 유실 없이 처리된다. drain이 주는 이득은 재기동 뒤 폴링 지연을 줄이는 것뿐인데, 새 유입 차단을 위한 종료 순서 맞추기, outbox·inbox 대칭 처리, drain 타임아웃과 폴백 같은 복잡도를 정당화하지 못한다. 운영에서 종료 지연이 실제 문제로 측정되면 다시 본다.
- **다시 볼 때 출발점**: 종료 로직은 `AbstractImmediateWorker`에 공통화돼 있다(실행 중지 → 워커 중단 → 10초 대기 → executor 종료). 채널(`PgOutboxChannel`, `PgInboxChannel`)은 단순 큐 빈이라 생명주기 순서에 들어 있지 않다. `AbstractImmediateWorker`의 phase 주석에 적힌 "채널을 나중에 멈춰 비운다"는 의도는 실현되지 않았다.

### TC-15 — pg 리스너 분리의 기준값 정밀화

- 워커 5개, 채널 용량 1024, PENDING·IN_PROGRESS 정체 판정 60초는 모두 측정 없이 정한 값이다. 부하 측정으로 벤더 지연 p95를 확인해 정체 판정을 정하고(실제 벤더 타임아웃의 2배 안팎), 최고 TPS에서 채널이 넘치는 빈도를 잰다.
  설정 키(`pg.inbox.channel.capacity`, `pg.inbox.channel.worker-count`, `pg.scheduler.inbox-polling-worker`)로 코드 변경 없이 바꿀 수 있다.
- 지금 구현은 인스턴스 1대를 가정한다. 같은 DB와 consumer group을 쓰는 pg-service 2~3대에서 `FOR UPDATE SKIP LOCKED`가 중복 처리 없이 동작하는지 검증한다.
