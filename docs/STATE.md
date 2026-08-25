# 현재 작업 상태

> 최종 수정: 2026-08-26

## 활성 작업

### SHARED-RESOURCE-SCALEOUT — 공유 자원 동반 스케일아웃 측정

- 단계: **execute**
- 활성 태스크: Task 9 (부하 프로필 상품 다중화)
- 이슈 / 브랜치: #146
- 설계 문서: `docs/topics/SHARED-RESOURCE-SCALEOUT.md` / 구현 플랜: `docs/SHARED-RESOURCE-SCALEOUT-PLAN.md` (둘 다 상단에 요약 브리핑)
- 태스크 17개 — 코드 6 (폴링 전용 조회 포트 · 복제본 데이터소스 · 질의 어댑터 · 격리 계약 테스트 · 캐시 클러스터 연결), 인프라 3, 사이클 스크립트 4, 측정 4
- 측정 시작 전 선행: Docker 메모리 20GB 상향 (현재 19.5GB 확보). 장애 전환 검증은 ship 에서 `docs/context/TODOS.md` 에 별도 토픽으로 등재
- Task 4 가 `PaymentStatusQueryJdbcAdapter` 를 만들어 프로덕션 `PaymentStatusQueryPort` 빈 부재로 인한 payment-service `integrationTest` 651건 실패 회귀를 해소했다 — `--rerun-tasks` 로 667건 전체 통과 확인 완료
- Task 6 이 `RedisConfig`/`StockRedisConfig` 를 노드 목록 프로퍼티로 단독/클러스터 분기하도록 바꿨다 — 노드 목록 미설정(현재 기본값) 상태로 `test` 682건·`integrationTest --rerun-tasks` 670건 전체 통과 확인 완료. "노드 목록을 넣으면 클러스터로 뜬다"는 Task 8 이 캐시 클러스터를 띄운 뒤 `client list` 로 실측 완료(상세는 Task 8 항목)
- Task 7 이 `docker/docker-compose.scaleout.yml`(신규 override) + `scripts/bench-replica-setup.sh` 로 payment DB 비동기 복제본 1대를 붙였다. `mysql-payment`(소스) → `mysql-payment-replica` 복제 실측(IO/SQL 스레드 Yes, 왕복 확인, 스크립트 재실행 멱등)과 Task 3 이월 항목(`paymentReplicaDataSource` 가 실제로 복제본을 가리키는지)을 함께 확인 완료 — 복제본 IO 스레드를 멈추고 소스만 다른 값으로 바꿔도 폴링 API 가 복제본의 옛 값을 그대로 돌려주는 것으로 확정
- Task 8 이 같은 override 파일에 `redis-stock-cluster`(재고 캐시, 최대 4)/`redis-idempotency-cluster`(멱등 저장소, 고정 3)를 추가하고 `scripts/bench-redis-cluster.sh --store {stock|dedupe} --masters N`으로 대수 1/2/4·3 각각 `cluster_state:ok`+슬롯 16384 전부 배정+`cluster-require-full-coverage:no`를 실측 확인, 재실행 멱등성도 확인했다. Task 6 이월 항목(노드 목록을 넣으면 클러스터로 뜨는지)도 payment-service 를 클러스터 노드로 임시 재기동해 `client list`로 `cluster|myid` 접속을 확인한 뒤 원복
- **인프라 기동 상태** — 검증에 쓴 스택(mysql-payment/replica, eureka, kafka, redis-dedupe/stock, redis-stock-cluster 2대, redis-idempotency-cluster 3대, payment-service 앱 컨테이너 1개)이 내려가지 않고 떠 있다. payment-service 는 단독 Redis 연결(cluster-nodes 미설정)로 원복된 상태. Task 9 가 이어서 상품 100종 시드 스크립트를 만든다

## 재개 메모

### 별건 — 확정 요청에 멱등키가 없다

- 주문 접수에는 `Idempotency-Key` 헤더가 있으나 확정에는 없다. 재고 게이트 작업에서 주문 단위 선점으로 대응했고 헤더 도입은 여전히 범위 밖

### 별건 — 위키에 남은 끊긴 참조 2곳

- `architecture.md` 의 FCG 상세 링크가 `pg-confirm-flow` 를 가리키는데 그 문서에 FCG 설명이 없다
- `message-delivery-and-dedupe.md` 서두가 "DLQ 처리를 다루며"라고 하는데 본문에 DLQ 절이 없다

### 별건 — 블로그 포스팅 진행 상황

- 1편 **모놀리식 → 4서비스 분리** 완료 (`notes/blog`, `msa-transition-decisions.md`)
- 2편 **재시도 소진 이후 처리** 예정 — 1편 결말이 "payment→pg HTTP 조회 통로를 두지 않은 결정이 넉 달 뒤 문제가 됐다"로 넘어가게 써 뒀다. 소재는 `docs/archive/retry-exhaustion-disposition/`

## 최근 완료

- **STOCK-GATE-PER-PRODUCT** (2026-08-18) — docs/archive/stock-gate-per-product/COMPLETION-BRIEFING.md
- **PG-VENDOR-SIGNAL-CONSOLIDATION** (2026-08-14) — docs/archive/pg-vendor-signal-consolidation/COMPLETION-BRIEFING.md

전체 이력: `docs/archive/README.md` / 구 STATE 이력: `docs/archive/state-history-2026H1.md`
