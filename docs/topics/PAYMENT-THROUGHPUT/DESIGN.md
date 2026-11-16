# PAYMENT-THROUGHPUT 설계 상세

> 측정 도구와 병목 수정 같은 코드 태스크의 근거와 작업 기록이다. 측정 회차는 [EXPERIMENTS](EXPERIMENTS.md)에 있다.
> [BRIEF](BRIEF.md)에서 합의하지 않은 결정은 넣지 않는다. 2026-11-06 옛 PLAN(`docs/PAYMENT-THROUGHPUT-PLAN.md`)에서 옮겼다.

## 결정

| ID | 결정 | 이유 | 근거 |
|---|---|---|---|
| D1 | 측정 모드를 `legacy`(기본), `capacity`, `user-quality`로 나눈다. 새 두 모드의 k6 VU는 checkout·confirm 뒤 바로 반환하고, 같은 주문을 별도 Python 관측기가 조회한다 | 완료 대기가 주입 슬롯을 차지하면 서버 한계가 아니라 발생기 한계를 재게 된다 | 승인된 브리핑 |
| D2 | 관측은 1초 간격·최대 30회·31초 기한. 포화, 늦은 스케줄, HTTP·파싱 오류, 202 미확인, 기한 뒤 응답을 원자료로 남긴다 | 접수 미확인을 실패·미접수로 단정하지 않기 위해 | 승인된 브리핑 |
| D3 | 백분위 분모는 전체 시작 건수로 두고 미관측은 무한 지연으로 다룬다. 관측 FAILED와 DB DONE이 충돌하거나 업무 실패가 확인되면 백분위와 별개로 FAIL이다 | 관측하지 못한 주문을 빼면 지연이 실제보다 좋아 보인다 | 제안(T2.2 독립 검토 PASS) |
| D4 | 지속 처리 판정은 유입 후반 60초 이상, 4개 이상 표본, 간격 15초 이내의 결제·재고 잔량과 메모리 관측 범위를 비교한다. 원본 표본이 30초보다 오래되면 근거 부족이다 | 일시적 변동과 지속 증가를 구분하기 위해 | 제안(T2.2 독립 검토 PASS) |
| D5 | DB DONE 기록 시각은 실제 커밋 순간으로 보지 않는다. Kafka 트랜잭션 표식 lag를 업무 적체로 보지 않는다 | 기록 시각은 커밋 전에 찍힐 수 있다 | 승인된 브리핑 |
| D6 | 새 모드의 유입 경계 판정은 연속 iteration, 총량, dropped, 예정 대비 -1~100ms 시각 정밀도로 한다. legacy 판정 코드는 바꾸지 않는다 | 옛 경계 건수 규칙이 정상 회차(capacity-r80-a)를 미확정으로 만들었다 | 제안(독립 domain-expert 재검토 PASS) |

## 태스크

- [x] **T1 — 전용 환경과 계측 기준 확보**
  - 결과: quality 환경, 빈 DB·Redis·Kafka·새 볼륨 대조와 전수 주문·승인·재고 검증, 실행별 원자료·자원·소스 증거 확보. legacy 조건 10·20·40·80/s 탐색. 160/s는 유입 미달로 미확정
- [x] **T2 — 기준 처리량과 첫 병목 판정** (T2.1~T2.4와 첫 포화 근거가 있으면 완료)
  - 결과(2026-11-15): T2.1~T2.4 완료. 첫 포화 근거는 결과 소비 동시성 1의 스레드 사용률 약 98%(capacity140-instr-a)
- [x] **T2.1 — 주입·관측 분리와 시험 모드**
  - 변경 위치: `scripts/k6/quality-payment.js`, `scripts/quality/run.py`와 관측 모듈, Python 테스트
  - 완료 기준: 늦은 완료와 진행 중 주문이 많아도 관측 대기가 주입을 막지 않음(모의 서버). 품질 조회는 같은 구매의 orderId를 쓰고 새 구매를 몰래 더하지 않음. 1초·최대 30회 정책과 관찰 종료·예외·응답 유실 기록. 모드·파라미터·발생기 자원·실제 명령 증빙
  - 결과: D1·D2 구현. HTTP/1.1 연결 재사용과 실패 연결 해제, 기한 직전 응답의 콜백 경합 보완. 실제 k6 + 모의 HTTP 계약 4개, 모드·피드 5개, 관측 10개 포함 Python 87개 PASS. 독립 reviewer PASS(지적한 기한 경합·실패 연결 누적은 수정 후 회귀 검증). 증거 `live-drill/throughput-tools-t21-20261029/`
- [x] **T2.2 — 처리량·지연·잔량 판정과 증빙**
  - 변경 위치: `scripts/quality/results.py`, `settlement.py`, `report.py`, `docs/quality/`
  - 완료 기준: 접수 p95와 완료 p95·p99를 구분하고 분모·시각·워밍업 제외 명시. 부하 중 DB DONE 처리율과 종료 뒤 완료 분리, 미종결·미확인 포함 전수 대조. 조회 종료 뒤 완료를 기한 안 성공으로 소급하지 않음. 유입 누락·발생기 포화·접수 미확인·증거 누락이면 최대 TPS 미확정. 잔량·메모리·해소 근거 없이 지연 허용 지속 처리 PASS를 내지 않음. 과거 결과를 새 기준으로 자동 재판정하지 않음
  - 결과: D3~D5 구현. 10초 + 수집 비용 간격으로 DB 상태, product 재고 확정, 결제·전체 파이프라인 잔량, 앱 5개 heap·컨테이너 20개 메모리·host swap 수집. 독립 reviewer·domain-expert major 지적을 회귀 테스트로 고친 뒤 재검토 PASS. Python 122개 PASS. 증거 `live-drill/throughput-tools-t22-20261029/`
- [x] **T2.3 — 도구 검토와 낮은 부하 실구동**
  - 결과: 모드별 새 환경 1건/초·180초 검증 PASS(EXPERIMENTS의 "준비와 도구 검증"). JAR 해시 동일, 새 볼륨 42개 상호 분리. 요약 [도구 검증](tps-tool-validation.md), `live-drill/throughput-split-validation-20261029/validation-summary.json`
- [x] **T2.4 — 수정 전 두 시험의 경계 탐색과 10분 지속 확인** (2026-11-06 완료 표시 후 되돌림, 2026-11-15 재측정으로 완료)
  - 완료 기준: 각 시험의 최고 품질 통과·지연 허용 지속 처리 구간, 첫 미달 부하, 탐색 간격, 완료 지연, 잔량, 해소 시간, 첫 병목 근거. 발생기·호스트 한계로 더 잴 수 없으면 그 제약과 확인 가능한 하한을 보고하고 서버 상한을 만들어 내지 않는다
  - 진행: capacity 80·120·160/s, user-quality 40·60·80/s 3분 탐색 완료. D6 판정 보완(Python 124개 PASS)
  - 진행(2026-11-06, Docker 초기화 뒤 새 환경): capacity 100·120(2회)·140·160/s 3분 탐색, capacity 120/s·user-quality 60/s 10분 확인. 처리 용량은 120/s 회복·140/s 증가, 사용자 품질 60/s 10분 통과. 10/29 회차보다 처리 용량이 높게 나온 원인은 미확인
  - 사용자 품질 첫 미달 부하: 100/s(80/s 통과). 2026-11-06 재분류: user-quality 100/s는 직전 회차 종료 2.5분 뒤 시작해 열 영향을 배제하지 못하므로 미달을 확정하지 않는다. 냉각 10분 이상 조건으로 다시 재면 T2.4를 완료한다(EXPERIMENTS "남은 측정" 1번). 첫 병목 후보 근거: 사용자 품질 100/s에서만 payment DB 연결 풀 포화(사용 30/30, 대기 최대 122)와 부하 중 처리 41/s. 원인 확정은 T3. 상세는 EXPERIMENTS
  - 결과(2026-11-15): 냉각 조건을 지킨 user-quality 100/s 재측정(user-quality100-b)에서도 사용자 완료가 미달했다(부하 중 처리 20.4/s, DONE 기록 p95 154.6초). 사용자 품질 첫 미달 부하를 100/s로 확정한다. 두 시험의 결과: 처리 용량 120/s(10분 유지, 초기 적체 포함)·140/s 미달, 사용자 품질 60/s(10분)·80/s 통과·100/s 미달. 상세는 EXPERIMENTS "2026-11-06 결과 정리"
- [x] **T3 — 실측 병목의 재현·개선·회귀 검증** (tdd, 결제 위험 경로. 첫 병목 완료, 다음 반복은 #166)
  - 결과(2026-11-15, #165): 개발자 결정으로 결과 소비 동시성 기본값 1 → 3 채택. `KafkaConsumerConfig` 기본값, quality Compose 기본값, RUNBOOK·ARCHITECTURE·TODOS 서술 수정. benchmark는 기존 기준선 비교를 위해 1을 명시한 채 둔다. 기본값 테스트(`KafkaConsumerConfigTest`)를 먼저 써서 "expected 3 but was 1" 실패를 확인한 뒤 구현. payment 단위 700, 통합 761(`ConfirmedConsumerConcurrencyIntegrationTest` 4, `PaymentEosIntegrationTest` 9 포함) 통과, 정적 분석 4종 통과. risk-reviewer·reviewer critical·major 없음. minor 3건 반영: RUNBOOK 문장, benchmark 주석, "단일 컨슈머라서 affected==0 도달 불가" 서술을 실제 근거(주문 행 잠금 아래 같은 트랜잭션, UNKNOWN status 재배달만 도달)로 수정
  - 의존: T2.4. 실제 병목 모듈, 선택한 변경과 대안, 지킬 불변식을 근거와 함께 이 문서에 추가한 뒤 구현한다
  - 첫 후보: 결과 소비 동시성 1 → 3. 160/s에서 명령·결과 양쪽 적체와 PG 승인 뒤 지연을 확인했다. T2.4의 후보 10분 확인 전에는 서버 기본값을 바꾸지 않는다
  - 원인 후보(2026-11-06 코드 확인, 계측 전): ① 결과 소비가 동시성 1에서 메시지마다 DB 트랜잭션과 Kafka EOS 트랜잭션을 차례로 커밋한다. 입력이 멈추면 약 240/s, 부하 중에는 100~120/s로 브로커·DB 지연에 민감하다 ② `OutboxRelayService.relay`가 DB 트랜잭션 안에서 Kafka 발행 응답을 동기로 기다리고, 가상 스레드라 동시 실행 수 제한이 없다 ③ 복제본이 꺼진 quality 환경에서 상태 조회가 같은 연결 풀(30)을 쓴다(user-quality 100/s에서만 풀 포화) ④ 개발자 의심: confirm의 Redis 재고 차감이 상품별 직렬이다. 측정 시나리오는 주문당 상품 1개라 이번 수치에는 영향이 없고, confirm 응답 p95는 140/s에서도 51ms였다. 부하 중 컨테이너 CPU는 어느 것도 포화되지 않았다(payment 중앙값 150~180%, payment MySQL 64~91%, redis-stock 20% 미만)
  - 원인 확인(2026-11-06, capacity140-instr-a 계측): ①이 병목이다. 결과 소비 스레드 사용률이 부하 중 약 98%이고 메시지당 약 8ms(DB 약 5ms, Kafka 발행·offset·커밋 약 3ms)라 상한이 약 120/s다. ②는 연결 대기 0·발행 1ms 안팎이라 이번 병목이 아니다. ③은 사용자 품질 한계(user-quality 100/s)의 별도 원인 후보로 남긴다. 상세는 EXPERIMENTS
  - 선택한 변경(2026-11-06 개발자 결정): `payment.events.confirmed` 결과 소비 동시성 기본값 1 → 3(`KafkaConsumerConfig`). 결과 토픽 파티션이 3개라 3까지만 의미가 있다
    - 대안: 여러 메시지를 한 트랜잭션으로 묶기(실패 시 묶음 전체 재처리로 결제 경로 실패 처리가 바뀌어 보류), 메시지당 DB 작업 줄이기(효과가 메시지당 약 5ms 중 일부라 보류), 파티션 수 늘리기(파티션 상수 4곳 산재, 별도 작업)
    - 지킬 불변식: 같은 주문의 결과는 key=orderId로 같은 파티션에 들어가 한 스레드가 순서대로 처리한다. 스레드마다 EOS 트랜잭션(DB 커밋 + 재고 확정 발행 + offset)이 원자적이다. 중복 수신은 `payment_event_dedupe`와 종결 가드로 흡수한다. 회귀 근거는 `ConfirmedConsumerConcurrencyIntegrationTest`(동시성 3)와 `PaymentEosIntegrationTest`
    - 진행(2026-11-06): capacity 140/s 동시성 3에서 부하 중 106.7 → 139.7/s, 쌓임 → 회복(EXPERIMENTS "2026-11-06 결과 정리"). 회차 사이 온도를 확인하지 않은 것을 발견해 회차 유효성을 쉰 시간으로 다시 나눴다. 남은 측정은 user-quality 100/s(동시성 1·3), capacity 160/s(동시성 3)
    - 진행(2026-11-15): 남은 측정 완료. user-quality 100/s에서 동시성 1 → 3으로 부하 중 처리 20.4 → 99.8/s, 사용자 완료 FAIL → PASS(p95 2.1초). capacity 160/s에서 102.3 → 141.6/s지만 초기 잔량 약 2,500이 부하 중 줄지 않았다. 동시성 3의 처리 용량 경계는 140/s와 160/s 사이이고, 160/s에서 결과 소비 스레드는 각각 절반 정도만 바쁘고, 잔량은 pg 명령 소비(`payment.commands.confirm`, 동시성 1)의 소비 지연 2,200~3,400으로 쌓였다. 다음 병목 후보는 pg 명령 소비다. 채택 판단은 개발자 확인 대기
    - 검증 순서: 같은 JAR에 `QUALITY_CONFIRMED_CONCURRENCY=3`만 바꿔 capacity 140·160/s, user-quality 100/s를 다시 잰다(T4). 개선이 확인되면 코드 기본값을 바꾸고 통합 테스트와 검토(risk-reviewer, reviewer)를 거친다
  - 독립 domain-expert는 기존 주문 잠금, 결정적 상품별 키, DB·Kafka 트랜잭션 배선을 유지하면 후보 설계를 통과시켰고 아래 검증을 요구했다
    - 테스트 전용 3파티션·동시성 3에서 각 child 실제 배정, 독립 주문·같은 상품·중복 결과의 DONE·dedupe·hold·재고 이벤트 키 불변식
    - 대상 주문·파티션에 한정한 EOS 커밋 1회 실패와 반복 실패: 다른 두 파티션 진행, 재전송 복구, DLQ와 재고 미복구 구분
    - 미커밋 입력을 둔 리스너 재시작·재배정 뒤 offset 수렴. JVM 강제 종료·재시작과 실제 상품 재고 차감 1회는 별도 소량 실구동
    - 기존 실패 보상·격리·금액 불일치 회귀 유지. 기본값 채택 전 reviewer·domain-expert 독립 검토와 모듈 검사
  - 준비 상태: `ConfirmedConsumerConcurrencyIntegrationTest`(4개 시나리오) 작성, 독립 reviewer·domain-expert 재검토 PASS, 2026-11-06 커밋(`483fd9e2`). 테스트 컴파일만 확인했고 통합 테스트 실행은 아직이다. 로컬 프로세스 복구 드라이버 `run-consumer-recovery.py`는 작성만 했고 독립 검토·실행 전이다(Git 제외)
  - 검증: `./gradlew :payment-service:test :payment-service:checkstyleMain :payment-service:checkstyleTest :payment-service:spotbugsMain :payment-service:spotbugsTest`, DB·Kafka 배선 변경이면 `:payment-service:integrationTest`. 성능 실측과 Gradle 통합 테스트를 동시에 돌리지 않는다
- [ ] **T3.2 — 두 번째 병목: pg 명령 소비와 워커 동시 처리 상한** (tdd, 결제 위험 경로, #166)
  - 근거: 결과 소비 동시성 3의 capacity 160/s에서 잔량이 `payment.commands.confirm`(pg-service, 동시성 1) 소비 지연 2,200~3,400으로 쌓였다(EXPERIMENTS 2026-11-15). pg inbox·outbox 워커는 꺼낸 작업마다 새 가상 스레드에 넘겨 worker-count가 상한이 아니다(진행 기록 2026-11-08)
  - 선택한 변경(2026-11-15 개발자 결정)
    - 결제 명령 리스너(`PaymentConfirmConsumer`)에만 동시성 프로퍼티(기본 3)를 둔다. DLQ 리스너는 1 그대로다. benchmark의 `PG_CONSUMER_CONCURRENCY`가 이 프로퍼티에도 들어가게 Compose를 맞춘다
    - `AbstractImmediateWorker`가 꺼낸 작업을 직접 처리해 worker-count를 동시 처리 상한으로 되돌린다. 작업마다 가상 스레드를 만드는 실행기는 없앤다
    - 종료: 워커는 새 작업만 그만 꺼내고, 처리 중인 작업은 최대 10초 기다린 뒤 남으면 인터럽트한다(지금의 실행기 종료 대기와 같은 시간). 끊긴 작업은 기존 정체 판정(60초) 회수 경로가 맡는다
    - inbox worker-count: 같은 JAR에 프로퍼티만 바꿔 capacity 160/s를 32와 64로 한 번씩 재고, 처리량을 유지하는 더 작은 값으로 user-quality 100/s를 확인한 뒤 코드 기본값을 정한다. outbox worker-count 1은 같은 회차의 발행 지표로 상한 여부를 본다
  - 대안: worker-count를 그대로 두고 실행기에 세마포어만 두기(구조가 원래 설계와 다르게 남아 보류), pg 리스너 전체 동시성 올리기(DLQ까지 바뀌어 보류)
  - 지킬 불변식: 같은 주문의 명령은 orderId 키로 같은 파티션에서 한 스레드가 순서대로 받는다. inbox UNIQUE와 상태 판정으로 같은 결제의 PG 승인 호출이 늘지 않는다. 채널이 가득 차면 지금처럼 offer 실패 → 폴링 워커가 처리한다
  - 검토 뒤 추가 결정(2026-11-15 개발자): (1) 워커 상한으로 큐 대기가 정체 기준(60초)을 넘으면 폴링 회수와 겹쳐 벤더를 두 번 부를 수 있어(risk-reviewer major), 큐에 넣은 시각을 작업에 담고 정체 기준 이상 기다린 작업은 처리하지 않고 폴링에 맡긴다 (2) 워커가 직접 처리하면서 Error가 워커 스레드를 죽이게 돼(risk-reviewer major), Error도 기록·카운트하고 다음 작업으로 넘어간다 (3) 쓰이지 않게 된 pg `ContextAwareVirtualThreadExecutors`와 채널 `take()`를 지운다
  - 남은 기존 위험(범위 밖): 같은 id가 큐에 두 번 들어오는 재전송·재시도 갈래의 겹친 호출(PAYMENT-FLOW 4.11), outbox 즉시 경로와 폴링의 겹친 발행(tps-server-analysis "PG outbox의 조회 후 발행"), 워커와 Kafka 리스너 레지스트리가 같은 SmartLifecycle phase라 종료 순서가 정해져 있지 않음. 10초 초과 인터럽트 경로는 단위 테스트로 확인하지 않았다
  - 검증: 워커 동시 실행이 worker-count를 넘지 않음, 종료 때 처리 중 작업이 인터럽트 없이 끝남(단위). 명령 리스너 동시성 3·DLQ 1(설정). pg 단위·정적 분석·통합 테스트, risk-reviewer·reviewer, 위 측정
  - 구현 결과(2026-11-16): 상한 테스트 2개(inbox·outbox)는 "expected 2 but was 5", 리스너 동시성 테스트는 "expected 3 but was 1", 정체 작업·Error 테스트는 동작 부재로 실패하는 것을 확인한 뒤 구현. pg 단위 451, 통합 84 통과, 정적 분석 4종 통과. reviewer major 2(문서 정합성, 측정 증빙에 새 손잡이 누락)·minor 반영. risk-reviewer major 2(큐 적체로 폴링과 겹친 벤더 호출, Error로 워커 사망)는 위 추가 결정으로 고친 뒤 수정분 재검토에서 critical·major 없음. 재검토 minor(늦게 온 재시도 명령이 정체된 IN_PROGRESS 행과 겹치는 기존 갈래)는 TODOS PG-STALE-ROW-GUARD로 남김. worker-count 기본값은 아직 5(측정 전 임시값)라 측정으로 정하기 전에는 main에 병합하지 않는다
  - 측정 뒤 추가 결정(2026-11-16 개발자): worker 32·64 비교에서 상한 복원이 pg outbox 발행을 워커 1개로 묶는 회귀가 드러났다(160/s에서 약 145/s, EXPERIMENTS 2026-11-16). outbox worker-count 기본값을 1 → 8로 올린다(`application.yml`, `@Value` 기본값, quality·benchmark Compose 기본값, 손잡이 `QUALITY_PG_OUTBOX_WORKERS`). 채널이 넘친 작업을 폴링으로만 회수하는 느린 경로는 이번에 고치지 않고 TODOS PG-CHANNEL-OVERFLOW-DRAIN으로 남긴다. 다음 측정은 inbox 64 + outbox 8의 capacity 160/s 한 회차, 기준선보다 나으면 user-quality 100/s와 inbox 기본값 확정. outbox 8도 이 측정으로 확인하기 전까지는 임시값이라 main 병합 조건에 함께 건다
- [ ] **T4 — 개선 뒤 같은 조건으로 한계 재탐색**
  - 1차(2026-11-15, 결과 소비 동시성 3, 같은 JAR에 프로퍼티만 변경): 처리 용량 140/s 회복·160/s 미달(부하 중 141.6/s), 사용자 품질 100/s 통과. 다음 병목은 pg 명령 소비 → T3·T4 반복(#166: 명령 소비 동시성, 워커 동시 처리 상한, worker-count 측정)
  - 바꾼 요소 외 조건을 고정하고 T2.4의 두 시험을 다시 한다. 같은 rate의 전후 비교와 최고 지속 처리량의 변화를 함께 보고한다. 효과가 없거나 회귀면 변경을 고치거나 빼거나 보류 근거를 남긴다. 다음 병목이 있으면 T3·T4를 반복한다
- [ ] **T5 — ship**
  - 변경에 맞는 정상·중복·실패·복구 확인, reviewer·risk-reviewer 완료 검토, context 문서와 HTML 동기화, BRIEF 완료 요약, 아카이브

## 리뷰 처리

## 진행 기록

- 2026-11-16 2차 측정 결과 (재개 지점): inbox 64 + outbox 8로 capacity 160/s 부하 중 회복(DONE 159.7/s, 정산 PASS, 채널 넘침 0), user-quality 100/s 품질 목표 4개 PASS(사용자 완료 p95 1.06초). 초기 약 50초 적체는 남는다. 다음: inbox worker-count 기본값 결정(후보 64) 뒤 코드·Compose·RUNBOOK 반영, 검증, 병합 판단. 자세한 수치는 EXPERIMENTS 2026-11-16 2차
- 2026-11-16 outbox 워커 8 반영, 2차 측정 준비 완료 (재개 지점): outbox worker-count 기본값 1 → 8(`74309342`). 새 기본값 테스트는 "expected 8 but was 1" 실패를 확인한 뒤 구현. pg 단위 452, 통합 84 통과, 정적 분석 4종 통과. reviewer minor 3(문서 2건 반영, `@Value` 폴백 테스트는 yml이 늘 우선해 생략), risk-reviewer 결함 없음. 전 모듈 bootJar(pg JAR 안 yml이 `:8`), Testcontainers 컨테이너·볼륨 0. 측정은 아직 시작하지 않았다
  - 재개 명령: `bash live-drill/throughput-split-measurements-20261106/run-worker-rounds.sh`(기본 실행은 capacity-r160-w64-o8-a 한 회차, 약 20분). 기준선 capacity160-c3-a(141.6/s)보다 나으면 `run-worker-rounds.sh user-quality-r100-w64-o8-a`, 이어 inbox를 줄여 볼지 정한다(`user-quality-r100-w32-o8-a`)
- 2026-11-16 worker-count 비교 측정 결과 (재개 지점): capacity 160/s를 inbox worker 32·64로 쟀다. 두 회차 모두 기준선보다 나빴다(부하 중 DONE 141.6 → 129.5·130.6/s). inbox worker 64는 160/s를 따라가지만, 상한을 되살리면서 pg outbox 발행이 기본값 1개 워커로 한 건씩 처리하게 되어 약 145/s에서 막혔다(회귀). 채널이 넘친 작업은 폴링 경로(inbox 5초당 10건, outbox 2초당 10건)로만 빠져 적체가 오래 남는다. 자세한 수치는 EXPERIMENTS 2026-11-16 항목
  - 다음: outbox 워커 수와 채널 넘침 처리 방식을 개발자와 정한 뒤 구현·재측정. 그 전에는 main에 병합하지 않는다. user-quality 100/s 회차는 그 뒤로 미룬다
- 2026-11-16 측정 준비 완료 (재개 지점): 개발자 요청으로 측정 직전까지만 진행했다. 실행 중인 컨테이너·볼륨·부하 없음.
  - 끝난 것: 전 모듈 bootJar(payment·pg 새로 빌드, JAR 안에 `InboxJob.enqueuedAt`·결과 소비 기본 동시성 3 확인, pg 실행기 헬퍼 없음). Testcontainers 재사용 컨테이너 25개와 익명 볼륨을 라벨 `org.testcontainers=true`로 골라 지움(컨테이너·볼륨 0). quality Compose 조합에서 `QUALITY_PG_INBOX_WORKERS`·`QUALITY_PG_COMMAND_CONCURRENCY`·`QUALITY_CONFIRMED_CONCURRENCY`가 각각 `PG_INBOX_CHANNEL_WORKER_COUNT`, `PG_KAFKA_COMMANDS_CONFIRM_CONSUMER_CONCURRENCY`, payment `JAVA_TOOL_OPTIONS`로 들어가는 것을 `docker compose config`로 확인. 앱 JRE 태그는 기준선과 같은 `21.0.12.1_1`
  - 측정 도구: `scripts/quality/evidence.py`가 pg 워커 지표(`pg_inbox_*`, `pg_outbox_*`, `pg_vendor_*`)를 `prometheus-pg_worker.json`으로 수집한다. 로컬 드라이버 `run-round-v4.py`(Git 제외)는 v3 측정 단계 그대로에 pg 손잡이 적용값 검사와 pg·pg-dlq 컨슈머 그룹 배정 기록만 더했다
  - 재개 명령: `bash live-drill/throughput-split-measurements-20261106/run-worker-rounds.sh`(capacity 160/s, inbox worker 32·64 두 회차, 결과 소비·pg 명령 소비 동시성 3 고정, 냉각·부하·열 확인 후 차례로, 약 40분). 결과를 본 뒤 처리량을 유지하는 더 작은 값으로 `run-worker-rounds.sh user-quality-r100-w32-a`(또는 `-w64-a`). 비교 기준선은 capacity160-c3-a(141.6/s, 상한 없음, 명령 소비 1)와 user-quality100-c3-a
  - 볼 지표: 부하 중 DONE 처리율·잔량, `payment.commands.confirm` 소비 지연, `pg_inbox_channel_queue_size`, `pg_inbox_stale_job_skipped_total`, `pg_vendor_concurrent_call_total`, pg hikari 대기, `pg_inbox_listener_tx_timeout_total`
- 2026-11-15 채택: 결과 소비 동시성 3 채택과 pg 변경 묶음 진행을 개발자가 승인했다. 이슈를 따로 만들었다: #165(결과 소비 동시성 3, 완료), #166(pg 명령 소비 동시성과 워커 동시 처리 상한, 다음). 둘 다 #162의 하위 이슈다.
  - 로컬 Testcontainers 재사용 설정(`~/.testcontainers.properties`의 `testcontainers.reuse.enable=true`) 때문에 통합 테스트 뒤 컨테이너가 남는다. 다음 측정 전 `docker ps -aq`가 비어 있어야 `run-remaining-rounds.sh` 계열 드라이버가 시작하므로, 측정 전에 정리한다(라벨 `org.testcontainers=true`로 골라 지운다)
- 2026-11-15 재개: 2026-11-06 중단 항목의 남은 측정 3회를 `run-remaining-rounds.sh`로 돌렸다(17:52 시작, 19:25 종료, 실패 없음). 회차 뒤 남은 컨테이너·볼륨 0. 호스트 화면은 외장 1개를 꺼 2개로 두었다. 결과와 판단은 [EXPERIMENTS](EXPERIMENTS.md)의 2026-11-15 항목. T2.4·T2를 완료했다.
  - 다음: 결과 소비 동시성 3 채택 판단(개발자 확인). 채택하면 2026-11-06 중단 항목 ③의 순서(`KafkaConsumerConfig` 기본값 1 → 3, quality Compose 기본값·RUNBOOK 문장 수정, 통합 테스트 실행, risk-reviewer·reviewer 검토)로 간다. 그 뒤 2026-11-08 항목의 pg 워커 변경
- 2026-11-08 개발자 결정 (측정 재개 전): pg inbox·outbox 워커에 동시 처리 상한이 없다(`AbstractImmediateWorker` 102~105행이 꺼낸 작업을 작업마다 가상 스레드를 만드는 executor에 넘긴다). 원래 설계인 "워커가 직접 처리"로 되돌리는 변경을 T4 다음 반복(T3·T4) 후보로 둔다.
  - 지금까지의 측정은 "상한 없음" 기준선으로 그대로 쓴다. 처음부터 다시 재지 않고, 고친 뒤 같은 조건으로 다시 재서 비교한다. 근거: 계측된 병목은 payment 결과 소비 스레드였고, pg 쪽은 병목으로 나온 적이 없다. 모의 PG 지연이 고정이라 상한 부재가 결과를 바꾸지 않았다
  - 순서: 위 2026-11-06 중단 항목의 남은 측정 3회 → 동시성 3 채택 판단 → 워커 변경(tdd, 결제 위험 경로) → worker-count를 측정으로 정함 → 비교 측정
  - 주의: worker-count 기본값 5로 되돌리면 모의 PG 지연 기준 상한이 약 17~50/s다. 경위·계산·포트폴리오 대조는 [서버 정적 분석](tps-server-analysis.md)의 "PG 작업 제출에는 실제 처리 동시성 제한이 없음" 절의 2026-11-08 추가분
  - 서버 코드는 바꾸지 않았다. 같은 날 Kafka 소비 정책 점검에서 찾은 토픽 밖 문제는 [TODOS](../../TODOS.md)의 KAFKA-PG-ERROR-HANDLER, KAFKA-OFFSET-RESET에 남겼다
- 2026-11-06 중단 (재개 지점): 개발자 요청으로 결과 소비 동시성 3 전후 비교 도중 멈췄다.
  - 상태: 실행 중인 컨테이너·볼륨·부하 없음. 서버 코드와 운영 기본값(동시성 1)은 바꾸지 않았다. 측정은 같은 JAR에 `QUALITY_CONFIRMED_CONCURRENCY`만 바꿔 했다
  - 끝난 것: T2.4 탐색과 10분 확인, 병목 계측(결과 소비 스레드 사용률 약 98%), 동시성 3의 140/s 비교(106.7 → 139.7/s). 현재 결론과 회차 유효성은 [EXPERIMENTS](EXPERIMENTS.md#2026-11-06-결과-정리)
  - 남은 것(순서대로): ① 남은 측정 3회 — user-quality 100/s 동시성 1(T2.4 완료 조건), capacity 160/s 동시성 3, user-quality 100/s 동시성 3 ② 결과로 동시성 3 채택 판단 ③ 채택하면 `KafkaConsumerConfig` 기본값 1 → 3, quality Compose 기본값과 RUNBOOK의 "기본 1" 문장 수정, `ConfirmedConsumerConcurrencyIntegrationTest`·`PaymentEosIntegrationTest` 실행, risk-reviewer·reviewer 검토
  - 재개 명령: `bash live-drill/throughput-split-measurements-20261106/run-remaining-rounds.sh`(Git 제외, 로컬). 회차마다 잔여 컨테이너·볼륨 0, 직전 회차 종료 뒤 10분, 부하 평균 2 미만, `pmset -g therm` 경고 없음을 확인한 뒤 v3 드라이버 → `finish-round.py`(DB 덤프 후 볼륨 삭제)를 돌리고 첫 실패에서 멈춘다. 약 1시간
  - 분석: `python3 live-drill/throughput-split-measurements-20261106/summarize_round.py <회차 폴더>`(판정·잔량 추이·CPU), `analyze_bottleneck.py <회차 폴더>`(연결 대기·사용, 리스너 스레드별 처리 시간, Kafka 트랜잭션 시간)
  - 환경 확인: Docker를 다시 초기화했다면 `docker tag eclipse-temurin:21.0.12.1_1-jre eclipse-temurin:21-jre`부터 한다(앱 JRE를 기준선과 맞춤). JAR 해시는 `live-drill/throughput-split-measurements-20261029/baseline-jar-consistency.json`과 같아야 한다
  - 미커밋 없음. 측정 도구 변경(`scripts/quality/evidence.py` 병목 지표 수집, 테스트 3개)은 이번에 커밋했다
- 2026-11-06 재개: 회차 종료 방식을 바꿨다(개발자 결정). 원자료와 DB 덤프를 저장한 뒤 볼륨까지 지운다. 기동 전 자원 상태, 부하 전 안정 여부 확인을 [실행 안내](../../quality/RUNBOOK.md#회차마다-지킬-것)에 명시했다. 아래 2026-10-29 항목의 "기존 자료 보존, `down -v` 금지"는 원자료에만 적용한다.
  - capacity 100/s·180초 완료. 18,001건 대조 PASS, 초기 약 30초 적체 뒤 부하 중 회복, 메모리 범위 미확정으로 INCONCLUSIVE. 상세는 EXPERIMENTS. 종료 절차로 DB 덤프 저장 후 볼륨까지 지웠다.
  - 재개 방법: `QUALITY_CONFIRMED_CONCURRENCY=1 python3 live-drill/throughput-split-measurements-20261106/run-round-v3.py <새 프로젝트명> <rate> <초> capacity` 뒤 `python3 live-drill/throughput-split-measurements-20261106/finish-round.py <회차 폴더>`. 시작 전 `docker ps -q`·`docker volume ls`가 비어 있는지, 이미지가 이 회차 `image-proof.json`과 같은지 확인한다. Docker를 다시 초기화했다면 JRE 태그 지정(`docker tag eclipse-temurin:21.0.12.1_1-jre eclipse-temurin:21-jre`)부터 다시 한다.
  - 재개 시점에 Docker의 이미지·컨테이너·볼륨이 모두 비어 있었다. 지난 회차 볼륨은 남아 있지 않고, 원자료는 `live-drill/`에 그대로 있다.
  - 이미지를 다시 받아 지난 회차 `image-proof.json`과 대조했다. 앱 6개의 JRE 기반은 고정되지 않은 태그가 21.0.13으로 바뀌어 지난 회차와 같은 `eclipse-temurin:21.0.12.1_1-jre`를 로컬에서 `21-jre`로 지정했다. Redis는 지난 회차 이미지를 다시 구할 수 없어 7.2.17로 진행한다(개발자 승인, 조건 변경으로 기록). 나머지 이미지 ID와 JAR 해시는 같다.
- 2026-11-06: 지침 개편(#163)으로 이 토픽 문서를 새 형식으로 옮겼다. 옛 합의 문서는 BRIEF, PLAN 실행 기록은 EXPERIMENTS와 이 문서로 나눴고, `docs/reports/tps-*` 보고서는 이 폴더로 옮겼다. 측정 조건·결과·판정은 바꾸지 않았다. 같은 날 `#162`를 지침 개편 브랜치(PR #164) 위로 리베이스했다. `#162`의 지침 정비 커밋(`549a8083`, 정식 workflow의 이슈·브랜치 추적)은 바꾼 파일이 모두 개편에서 새로 쓰이거나 삭제됐고 내용도 새 지침에 들어 있어 다시 적용하지 않았다. 리베이스 전 상태는 로컬 브랜치 `#162-before-rebase`에 있다. PR #164가 squash로 병합되면 `git rebase --onto main agent-structure-overhaul '#162'`로 한 번 더 옮긴다.
- 2026-11-06: 로컬 `live-drill/throughput-split-measurements-20261029/RESUME.md`(Git 제외, 수정하지 않음)는 옛 경로를 가리킨다. `docs/reports/tps-handoff.md` → 이 폴더의 `tps-handoff.md`, PLAN → 이 문서, 옛 합의 문서 → `BRIEF.md`로 읽는다.
- 2026-10-29 중단 (재개 지점): 사용자 요청으로 100/s 시험 직전에 중단했다.
  - 환경: 60/s 품질 시험을 마치고 로그·이미지·원자료 저장 후 볼륨 보존 종료. 실행 중인 컨테이너와 부하 없음. 100/s 환경 기동·시드·워밍업·본측정은 실행하지 않았다.
  - 재개 순서: STATE → [인계](tps-handoff.md) → 이 문서와 로컬 `live-drill/throughput-split-measurements-20261029/RESUME.md`. 완료한 단계를 반복하지 않는다. 호스트·Docker·기존 컨테이너·JAR·프로젝트 이름을 확인하고 새 100/s 환경부터 기동한다. 기존 상태를 재사용하지 않는다.
  - 조건: 매 실측 새 프로젝트·컨테이너·볼륨, 빈 DB·Redis·Kafka 확인, 같은 시드. 기존 자료 보존, 재시드·`down -v`·clean·reset-db 금지. 실행 중 측정 소스 동결, 무거운 테스트 병행 금지.
  - 서버: 메인 Java·실행 JAR·운영 기본 소비 동시성 1은 바꾸지 않았다.
  - 검토 제약: 복구 드라이버 검토 중 서브에이전트 모델 사용량 한도에 걸렸다. 같은 호출을 반복하지 않고 다른 모델로 대체하거나 메인이 직접 검토한다. 대체 검토는 아직 하지 않았다.
- 2026-10-29: 검증된 T2 도구·문서·측정 체크포인트를 로컬 커밋(`a6c0aa0e` 등). 원자료와 로컬 드라이버는 Git 제외.
- 원자료 위치(`live-drill/`, Git 제외): `throughput-20261028/`, `throughput-clean-20261028/`, `throughput-tools-t21-20261029/`, `throughput-tools-t22-20261029/`, `throughput-split-validation-20261029/`, `throughput-split-measurements-20261029/`, 측정 도구 준비 단계 자료 `baseline-preparation-20261012/`
- 보고서: [클린 환경 측정 결과](tps-clean-measurements.html), [검토 요약](tps-review-summary.html), [서버 정적 분석](tps-server-analysis.md), [도구 검증](tps-tool-validation.md), [인계](tps-handoff.md)
