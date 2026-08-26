# 현재 작업 상태

> 최종 수정: 2026-08-26

## 활성 작업

### SHARED-RESOURCE-SCALEOUT — 공유 자원 동반 스케일아웃 측정

- 단계: **execute**
- 활성 태스크: Task 17 (측정 리포트)
- 이슈 / 브랜치: #146
- 설계 문서: `docs/topics/SHARED-RESOURCE-SCALEOUT.md` / 구현 플랜: `docs/SHARED-RESOURCE-SCALEOUT-PLAN.md` (둘 다 상단에 요약 브리핑)
- 태스크 17개 — 코드 6 (폴링 전용 조회 포트 · 복제본 데이터소스 · 질의 어댑터 · 격리 계약 테스트 · 캐시 클러스터 연결), 인프라 3, 사이클 스크립트 4, 측정 4
- 측정 시작 전 선행이었던 Docker 메모리 20GB 상향은 Task 14 착수 전 20480MiB 확보로 완료 확인. 장애 전환 검증은 ship 에서 `docs/context/TODOS.md` 에 별도 토픽으로 등재
- Task 4 가 `PaymentStatusQueryJdbcAdapter` 를 만들어 프로덕션 `PaymentStatusQueryPort` 빈 부재로 인한 payment-service `integrationTest` 651건 실패 회귀를 해소했다 — `--rerun-tasks` 로 667건 전체 통과 확인 완료
- Task 6 이 `RedisConfig`/`StockRedisConfig` 를 노드 목록 프로퍼티로 단독/클러스터 분기하도록 바꿨다 — 노드 목록 미설정(현재 기본값) 상태로 `test` 682건·`integrationTest --rerun-tasks` 670건 전체 통과 확인 완료. "노드 목록을 넣으면 클러스터로 뜬다"는 Task 8 이 캐시 클러스터를 띄운 뒤 `client list` 로 실측 완료(상세는 Task 8 항목)
- Task 7 이 `docker/docker-compose.scaleout.yml`(신규 override) + `scripts/bench-replica-setup.sh` 로 payment DB 비동기 복제본 1대를 붙였다. `mysql-payment`(소스) → `mysql-payment-replica` 복제 실측(IO/SQL 스레드 Yes, 왕복 확인, 스크립트 재실행 멱등)과 Task 3 이월 항목(`paymentReplicaDataSource` 가 실제로 복제본을 가리키는지)을 함께 확인 완료 — 복제본 IO 스레드를 멈추고 소스만 다른 값으로 바꿔도 폴링 API 가 복제본의 옛 값을 그대로 돌려주는 것으로 확정
- Task 8 이 같은 override 파일에 `redis-stock-cluster`(재고 캐시, 최대 4)/`redis-idempotency-cluster`(멱등 저장소, 고정 3)를 추가하고 `scripts/bench-redis-cluster.sh --store {stock|dedupe} --masters N`으로 대수 1/2/4·3 각각 `cluster_state:ok`+슬롯 16384 전부 배정+`cluster-require-full-coverage:no`를 실측 확인, 재실행 멱등성도 확인했다. Task 6 이월 항목(노드 목록을 넣으면 클러스터로 뜨는지)도 payment-service 를 클러스터 노드로 임시 재기동해 `client list`로 `cluster|myid` 접속을 확인한 뒤 원복
- **인프라 기동 상태** — 검증에 쓴 스택(mysql-payment/replica, eureka, kafka, redis-dedupe/stock, redis-stock-cluster 2대, redis-idempotency-cluster 3대, payment-service 앱 컨테이너 1개)이 내려가지 않고 떠 있다. payment-service 는 단독 Redis 연결(cluster-nodes 미설정)로 원복된 상태
- Task 9 가 `scripts/bench-seed-stock.sh`를 상품 100종(id 1000..1099, 기존 스모크 시드 id=1과 안 겹침) 시드로 재작성하고, `scripts/k6/helpers.js`에 `PRODUCT_COUNT`/`ITEMS_PER_ORDER`를 추가해 주문마다 상품을 고르게 순환시키면서 중복 없이 담게 했다. mysql-product/product-service를 새로 기동해 라이브 k6 부하로 실측(단일 상품 76건 균등 분산, `ITEMS_PER_ORDER=3` 51건 전부 무중복) 확인 완료 — 상세는 PLAN Task 9 완료 결과. 검증에만 쓴 user-service는 정지, mysql-product/product-service는 이후 태스크가 이어 쓸 수 있게 기동 유지. 재고는 검증 후 상수(1000만)로 재시드해 복원
- Task 10 이 `scripts/bench-cycle-reset.sh`로 사이클 재구성 다섯 단계(부하 정지 확인 → 미종결·격리·미회수 안정 확인(격리를 미종결과 별개로 카운트, 잔류 시 관리자 종결 자동 시도) → 소비 적체 안정 확인 → payment-service 서비스 단위 정지 → 즉시 재확인 후에만 비우고 재시드)를 구현했다. 정상/잔류 인위 조성/격리 인위 조성 세 시나리오를 실제로 돌려 각각 exit 0·2·0(자동 종결 후 통과)을 확인 — 상세는 PLAN Task 10 완료 결과. 검증 중 mysql-payment 의 낡은 bench 잔류(Task 9가 pg-service 없이 checkout만 흘려 영구 미종결로 남은 READY 128건 등)를 정리해 이후 태스크는 payment_event 등 여섯 테이블이 빈 상태에서 시작한다
- Task 11 이 `scripts/k6/verify-settlement.sh`를 상품별 재고 대조(100종, 어긋난 상품만 개별 출력) + 건별 대조(DONE↔COMMITTED·FAILED↔REVERTED 부합 여부, 총건수 교차식이 못 잡는 개별 유실용) + 기계 판독 종료 코드(0 통과/2 판단 보류/3 불일치, 접속·전제 실패는 그대로 1)로 확장했다. `results/<CASE_NAME>-verdict.json`에 판정을 남기고 부하 도구가 쓰는 `<CASE_NAME>.json`은 건드리지 않는다. 미종결/미회수 선차감 기록/소비 적체는 대기하면 풀릴 수 있어 판단 보류(2), QUARANTINED는 대기로 안 풀려 다른 게이트 상태와 무관하게 즉시 불일치(3)로 우선 판정한다. mysql-pg/pg-service/user-service를 일시 기동해 실제 k6 부하(81건 DONE)로 정상 통과(exit 0)·상품 1종만 어긋낸 불일치(exit 3, 나머지 99종 무관)·QUARANTINED 1건 잔류 불일치(exit 3)·READY 1건 잔류 판단 보류(exit 2)를 각각 실측 확인 — 상세는 PLAN Task 11 완료 결과. 검증 중 `product-service-stock-commit` 컨슈머 그룹 LAG가 트랜잭션 커밋 마커로 파티션당 1씩 영구 잔류하는 현상을 발견(대기로 자연 해소 안 됨, 재기동+`reset-offsets`로만 해소) — Task 13 사이클 러너의 소비 적체 게이트 임계값 설계에 영향을 줄 수 있어 후속 확인 필요. 검증에 새로 띄운 mysql-pg/pg-service/user-service는 정지하고 payment-service/product-service는 유지, payment 여섯 테이블은 다시 빈 상태로 복원
- Task 12 가 `scripts/bench-cluster-check.sh`를 만들어 재고 캐시 클러스터 라이브 점검을 자동화했다. payment-service를 클러스터에 연결해 재기동한 뒤 실제 `checkout`+`confirm`으로 정상 경로(단일 상품)와 거절 경로(다중 상품 중 하나 품절)를 흘려 선차감·주문 선점 획득·해제·거절 전용 되돌리기 네 경로를 확인하고, 정상 흐름에서 안 타는 격리 복구 조건부 보상은 EVAL로 직접 태운다(qty=0, 실 재고 불변). 상품 100종 전부의 해시태그 슬롯 일치와 노드별 분포 편차(임계 초과 시 exit 4)도 확인한다. `docker/docker-compose.scaleout.yml`에 `REDIS_STOCK_CLUSTER_NODES` 패스스루를 추가해 Task 6/8이 준비한 클러스터 전환을 실행 시점에 켤 수 있게 완성했다. 마스터 2대·4대 두 구성 모두 다섯 경로 정상 + 슬롯 일치 + 분포 편차 0%로 exit 0 실측 확인 — 상세는 PLAN Task 12 완료 결과. 검증 중 payment-service를 user-service보다 먼저 재기동하면 Eureka 클라이언트의 시작 시점 레지스트리 스냅샷 누락으로 checkout이 일시 503을 내는 것을 발견해 순서를 바꾸고 Eureka 등록 확인을 추가했다. 검증 후 테스트 흔적(orderId 2건의 payment 여섯 테이블 행, 프로브 상품 캐시값)을 정리하고 payment-service를 단독 연결로, redis-stock-cluster를 2대로, user-service는 정지 상태로 되돌려 Task 8 인계 상태와 동일하게 복원했다. Task 13 이 이어서 사이클 러너와 복제 지연 계측을 만든다
- Task 13 이 `scripts/bench-scaleout-cycle.sh`를 만들었다 — 조건 값(인스턴스 수/재고 마스터 수/폴링 라우팅/주문당 상품 수/벤더 지연)을 받아 스택 기동부터 재구성까지 무인으로 돌고, 정합 판정은 `verify-settlement.sh` 종료 코드로 받아 판단 보류(2)는 유한 재시도, 불일치(3)·그 외는 즉시 실패 처리한다(재구성 미호출). 함께 `bench-cycle-reset.sh`/`verify-settlement.sh`의 소비 적체 조회에 컨슈머 생존 확인을 추가해, 배정된 컨슈머가 없는데 적체가 남으면(product-service 다운) 기다리지 않고 즉시 실패하도록 고쳤다(기존엔 판단 보류로 묶여 폴링 예산을 다 쓰고서야 실패했다) — 그룹 행이 아예 없을 때 합계 0으로 게이트를 조용히 통과시키던 기존 버그도 막았다. 짧은 실부하로 러너를 실제로 돌려 INCONCLUSIVE 재시도 후 실패·MISMATCH 즉시 중단 두 반응이 다르게 나오는 것을 실측 확인 — 상세는 PLAN Task 13 완료 결과. **부수 발견(아래에서 해소)** — 앞서 받은 재진단("컨슈머가 살아있으면 적체는 0으로 빠진다")과 달리, 이번 실측에서도 살아있는 컨슈머 상태로 2분 넘게 기다려도 파티션당 1씩(트랜잭션 커밋 마커) 안 줄어드는 것을 재확인했다. 검증 중 복제 지연 표본화 백그라운드 잡의 정지 확인(`kill`+`wait`)이 무기한 걸리는 결함도 실측으로 발견해 고쳤다(SIGKILL만 쏘고 종료 확인은 하지 않도록 변경). 검증에 새로 띄운 gateway/pg-service/user-service/mysql-pg/관측 스택은 정지, 재고 캐시 클러스터는 2대로, payment-service는 단독 연결로 되돌려 Task 12 인계 상태와 동일하게 복원했다. payment 여섯 테이블은 빈 상태, 소비 적체 마커도 0으로 비운 뒤 `bench-cycle-reset.sh` 최종 재실행으로 exit 0 확인
- **게이트 보정(2026-08-26, 사용자 승인)** — Task 13 부수 발견을 원인 규명한 결과 소비 적체 0은 실측에서 도달 불가로 확정됐다(재고 확정 발행이 트랜잭션으로 묶여 커밋 표시가 파티션마다 오프셋을 하나씩 차지하는데 컨슈머는 이를 레코드로 처리하지 않는다). `bench-cycle-reset.sh`/`verify-settlement.sh` 둘 다 소비 적체 게이트를 "파티션 수 이하 + 연속 확인에서 더 줄지 않음 + 소비자 생존"으로 바꿨다 — 파티션 수는 조회 결과에서 얻고 하드코딩하지 않는다. 정합 판정의 실제 권한자는 이 게이트가 아니라 상품별 캐시-원본 대조라는 점을 두 스크립트 주석에 남겼다. 실거래 후 파티션당 1 잔류 시 두 스크립트 모두 통과, `docker pause`로 컨슈머 그룹 멤버십은 유지한 채 진짜 미소비 백로그(165건)를 쌓은 상태에서는 통과하지 않음(bench-cycle-reset exit 2, verify-settlement INCONCLUSIVE exit 2), 컨슈머가 아예 없는 상태에서는 폴링 없이 즉시 실패(각각 exit 2/8초, exit 1/1초)함을 실측 확인 — 상세는 PLAN Task 10/11 완료 결과 보정 항목. 검증에 쓴 mysql-pg/pg-service/user-service는 정지, payment 여섯 테이블은 빈 상태·재고는 상수 재시드로 Task 13 인계 상태와 동일하게 복원했다. Task 14 착수를 막던 요인이 해소됐다
- **스케일 붕괴 결함 발견 및 수정(2026-08-26)** — Task 14 착수 직후 인스턴스 2대 사이클인데 payment-service가 조용히 1대로 줄어드는 증상을 겪어 에스컬레이션했고, 원인 규명 결과 gateway가 payment-service를 depends_on으로 갖는 상태에서 `bench-scaleout-cycle.sh`가 스케일 인자 없이 `dc up -d gateway`를 부르면 docker compose가 방금 스케일한 2번째 이상 인스턴스를 기본 대수로 되돌리며 **삭제**하는 것으로 확정됐다(정지가 아니라 삭제라 `docker ps -a`에도 안 남는다). gateway 호출에 `--no-deps`를 추가해 고쳤고, 함께 미커밋 상태였던 폴링 포기 시각 상향+교차식 보정, 재구성 절차의 payment 원장 truncate 추가도 묶어 커밋(`5d56f951`, fix(infra)). 이 결함은 **Task 15(인스턴스 축 측정) 전체를 무효로 만들 수 있었다** — ship 리뷰에서 반드시 짚어야 한다. 스케일 붕괴로 굳은 READY 836건과 대응 원장 데이터는 벤치 데이터라 정리했다. 상세는 PLAN Task 14 완료 결과 첫 항목
- **Task 14 완료** — 재고 캐시 마스터 1/2/4 세 사이클(인스턴스 2·폴링 라우팅 켬·주문당 상품 1개·저지연 벤더 고정) 전부 정합 PASS, 인스턴스 2대 유지를 사이클마다 병행 폴링으로 확인. 확정 처리율이 세 값 모두 ±5% 밴드 안(75.2/78.7/72.0 req/s)이라 대수와 단조 관계가 없어 "효과 없음"으로 판단, 설계 문서 지시대로 fsync 정책(`appendfsync always`→`everysec`, 마스터 4대 한정 라이브 재설정)을 낮춘 확인 사이클을 추가했으나 그 결과(74.8 req/s)도 같은 밴드 안이었다. 수치 해석은 하지 않고 상대비만 기록 — 해석은 Task 17의 몫. 상세는 PLAN Task 14 완료 결과
- **Task 15 완료** — 재고 마스터를 Task 14의 명목 최댓값(마스터 2, 노이즈 안의 명목값)으로 고정하고 인스턴스 1/3/4대 세 사이클(재고 마스터 2·폴링 라우팅 켬·주문당 상품 1개·저지연 벤더 고정) 실행, 인스턴스 2대 지점은 조건이 완전히 같은 Task 14의 `scaleout-stock-m2` 결과를 재사용했다. 넷 다 정합 PASS, 지정한 인스턴스 대수 유지를 사이클마다 병행 폴링으로 확인. 확정 처리율 1→2 실측 배수 1.118x — **판정선(1.6배)을 넘지 못했다.** 3대(0.882x)·4대(0.817x)는 1대 기준선보다 오히려 낮고 판정선이 없어 곡선만 기록, 수치 해석은 하지 않는다(Task 17 몫). 4대 구간 CPU/메모리 표본화 결과 앱 컨테이너 합산 CPU 최대 45%/평균 29%(VM 10 vCPU 환산)로 포화 없음, 스왑 유입 없음(SwapFree 전 구간 SwapTotal과 동일) — 폐기할 구간 없음. 상세는 PLAN Task 15 완료 결과
- **Task 16 완료** — 세 축(읽기 복제 라우팅 끔, 다중 상품 3개·재고 마스터4, 고지연 벤더) 전부 정합 PASS. 다중 상품 축은 착수 직후 잔류 337건(READY)/1011건(미회수 선차감 기록)으로 에스컬레이션했으나, 원인이 `execute()` 누락이 아니라 이 축의 종결 꼬리(상품 3개로 캐시 왕복 3배)가 reconciler 회수 기준(300초)을 넘겨 진행 중이던 결제가 READY로 되돌려지고 뒤늦은 승인이 `done()`의 IN_PROGRESS 가드에 막힌 것으로 확정됐다(인스턴스 축 30초 구간과 같은 실패 모드가 300초에서 재현). 이 잔류는 대기해도 자연 해소되지 않아 정상 5단계 게이트를 못 타므로, payment-service를 정지한 뒤 재구성 절차와 같은 TRUNCATE로 payment 원장 여섯 테이블을 직접 비워 정리했다. 회수 기준을 1800초로 올려 재측정한 결과 확정 4605건 전부 DB DONE 4605건, 상품 100종 재고 정합 PASS, 건별 대조 PASS로 통과 — 부하 구간 내내 인스턴스 2대·재고 마스터 4대 유지, 주문마다 서로 다른 상품 3개 구성도 병행 폴링으로 확인했다. **별도 발견(고치지 않고 기록만)** — 회수가 되돌린 결제에 뒤늦게 승인이 오면 `done()` IN_PROGRESS 가드에 막혀 그 결제와 재고 선점 모두 자동 경로로는 영원히 안 풀린다(회수가 종결 상태만 되돌리게 설계돼 있어 오히려 영구화됨) — ship에서 TODOS.md 등재 대상. 상세는 PLAN Task 16 완료 결과
- **인프라 기동 상태** — gateway/pg-service/product-service/user-service, mysql-payment/replica/pg/product/user, eureka, kafka, redis-dedupe/stock, redis-idempotency-cluster 3대, 관측 스택(prometheus/alertmanager/grafana/kafka-exporter/tempo/loki/promtail) 전부 기동 유지. redis-stock-cluster는 Task 16 다중 상품 축이 마지막으로 구성한 마스터 4대 상태로 남아 있다(다음 태스크의 축 조건에 따라 `bench-redis-cluster.sh`가 재구성). payment-service는 마지막 사이클의 재구성 (4)단계에서 정지된 채 남아 있다(다음 태스크의 스택 기동 단계가 재기동). payment 원장 여섯 테이블은 빈 상태, 재고는 상수로 재시드된 상태. Docker 메모리는 20GB(20480MiB)로 이미 상향 확인됨

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
