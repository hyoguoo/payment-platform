# 현재 작업 상태

> 최종 수정: 2026-08-31 (원인 지목 — payment_outbox 잠금 경합. 계측 확장 + 클린 사이클 도입)

## 활성 작업

### SHARED-RESOURCE-SCALEOUT — 공유 자원 동반 스케일아웃 측정

- 단계: **측정 종료 + 포스팅 초안 작성 중.** 브랜치 `#146`, 커밋 10건 **원격 미푸시**
- 정본: `docs/SHARED-RESOURCE-SCALEOUT-INVESTIGATION.md` (7 절이 3일차 기록)

**결론 — 늘려야 했던 건 파티션이었다**

토픽 파티션이 3 인데 `pg-service` 는 컨슈머 동시성을 지정한 적이 없어 Spring 기본값 1 로 돌았다.
컨슈머 하나가 파티션 셋을 다 들고 있었고, 이것이 그동안 못 찾던 상한이다.

|이름|인덱스|파티션·동시성|인스턴스|종결/s|
|:---:|:---:|:---:|:---:|:---:|
|`m1-oldindex`|V8 이전|3 · 1|1대|10.1|
|`m2-v8`|V8|3 · 1|1대|10.2|
|`m3-p9c9`|V8|9 · 9|1대|**18.9** (1.86배)|
|`m4-i2`|V8|18 · 9|2+2대|20.7 (1.09배)|

- **인덱스는 처리량과 무관하다** — m1 대 m2 가 10.1 대 10.2. V8 은 데드락 수정이지 성능 수정이 아니다
- **파티션·동시성이 유일하게 크게 움직인 값** — 1.86배, 대기량 6.2배 감소
- **인스턴스 증설은 수확이 꺾인다** — 1.09배. 앞 단계 대기는 평균 10.6배 줄었으나 뒤 단계는 2.5배에 그치고 정점은 2만 건에서 안 움직였다

**측정 조건 이탈 — 인용 시 반드시 밝힐 것**

전 사이클을 `RECONCILER_TIMEOUT=1800`(운영 기본 300초) 으로 재었다.
아래 L-19 결함이 다른 측정을 전부 덮어버려 발동하지 않게 피한 것이고, **해결이 아니라 회피다.**
매 사이클 되돌림 0건을 확인했고 0 이 아니면 폐기했다.

**폐기한 값 — 인용 금지**

- `gate-*` (8/31) — 운영 기본 리컨실러 설정이라 되돌림 발생 여부를 확인할 수 없다
- `post-1-deadlock` / `post-2-v8` (9/1 오전, 둘 다 7.7/s) — L-19 정지에 걸린 오염값.
  단 `post-2-v8` 의 결함 실측치는 그 정지 자체가 관측 대상이라 유효

**측정 중 발견한 결함 — `CONCERNS.md` L-19**

부하가 지속돼 확정 결과 회신이 5분(운영 기본)을 넘기면 상태 정리 작업이 `IN_PROGRESS` 를
`READY` 로 되돌린다. 뒤늦게 온 확정 결과는 `READY` 를 완료로 못 바꿔 거부되고,
그 예외가 비재시도로 분류돼 있지 않아 건당 5초를 쓰고 DLQ 로 간다.
그동안 같은 파티션 뒤 메시지가 막혀 회신이 더 늦어지고 되돌림이 다시 늘어난다.

실측 — 되돌림 배치가 25초 만에 965 → 1,879건. 42,281건 중 16,742건 잔류.
파티션 3개 중 2개 정지, 정지한 파티션에만 DLQ 적재(42/0/41). 돈은 새지 않는다.

**세 번 틀렸고 세 번 다 직접 재서 잡았다**

1. `payment_outbox` 잠금이 천장 → `SUM_LOCK_TIME` 은 피코초. 116,830초가 아니라 116.8초
2. 커밋 fsync 가 천장 → 디스크 직접 측정 초당 2,400~4,032회, 부하는 절반 이하
3. MySQL 커밋 경로가 직렬 천장 → 서버 직접 측정 초당 5,987커밋, 앱은 8%

**다음에 할 일**

1. **포스팅 초안 마무리** — `POSTING-DRAFT-throughput-bottleneck-NOTES.md` 가 재개 지침이다
   (수치 출처 매핑 · 캡처 12장 목록과 넣을 자리 · 문체 규칙 · 되풀이하면 안 되는 실수)
2. ship — 푸시, PR. 코드 리뷰·`docs/context/` 동기화·TODOS 등재는 완료
3. 결함 수정 판단 — `TODOS.md` 의 `CONFIRM-RESULT-NONRETRYABLE-STATUS` 부터.
   예외 타입을 통째로 비재시도로 묶으면 안 된다(에러코드 15종, CAS 충돌은 재시도 유효)

**측정 방법 — 반드시 지킬 것**

- **매 사이클 초기화한다.** `scripts/bench-clean-cycle.sh`
- **파티션은 앱 기동 전에 만든다.** 기동 후 변경은 `metadata.max.age.ms`(기본 5분) 만큼 반영이 늦다
- **컨슈머 병렬도의 상한은 파티션 수다.** `kafka-consumer-groups --members` 로 노는 멤버 0 을 확인하고 시작한다
- **되돌림 0건을 확인한다.** `docker logs <payment-service> | grep -c 'READY 복원 완료'`
- **`total_wall_sec` 로 용량을 비교하지 않는다.** 종결 대기가 고정 327초라 분모가 `부하 + 상수` 다
- **포화 구간에서는 지연 표본을 끈다** (`LATENCY_SAMPLE_RATE_PER_SEC=0`)

**측정 손잡이 (제품 코드 0줄)**

`KAFKA_TOPIC_PARTITIONS` · `PG_CONSUMER_CONCURRENCY` · `CONFIRMED_CONSUMER_CONCURRENCY` ·
`INSTANCES` / `PG_INSTANCES` · `RECONCILER_TIMEOUT` · `GRAFANA_ANONYMOUS`(캡처용 익명 열람)

**산출물 위치 (전부 git 미추적)**

- 포스팅 초안 + 작업 노트 — 프로젝트 루트 `POSTING-DRAFT-*.md`
- 캡처 12장 + 조건 메모 + 결함 원자료 — `live-drill/scaleout/originals/`
- 측정 결과 — `results/m1~m4-*.json`

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
