# 현재 작업 상태

> 최종 수정: 2026-08-31 (원인 지목 — payment_outbox 잠금 경합. 계측 확장 + 클린 사이클 도입)

## 활성 작업

### SHARED-RESOURCE-SCALEOUT — 공유 자원 동반 스케일아웃 측정

- 단계: **자원 증설 축 종료.** 브랜치 `#146`, **원격 미푸시**
- 정본: `docs/SHARED-RESOURCE-SCALEOUT-INVESTIGATION.md` (7 절이 3 일차 기록)

**결론 — 늘려야 했던 건 파티션이었고, 거기서 끝났다**

Kafka 토픽 파티션이 3 이고 `pg-service` 리스너 동시성이 1 이라, **컨슈머 1 개가 파티션 3 개를
다 들고** 있었다. 이것이 그동안 못 찾던 상한이다. 파티션·동시성을 9 로 풀자 부하 중 종결이
**33.9 → 74.3/s (2.19 배)**.

| | 인스턴스 | 활성 컨슈머 | 종결/s |
|:---|:---|---:|---:|
| A | 1+1 | 3 | 33.9 |
| B | 1+1 | 9 | **74.3** |
| C | 2+1 | 9 (고정) | 76.3 |
| D | 2+2 | 18 | 83.5 |

- **C 가 대조군이다** — 인스턴스만 2 배로 늘리고 컨슈머를 묶으니 1.03 배. **앱 인스턴스는 애초에
  병목이 아니었다.** 기존 1.26~1.30 배의 정체가 이것이다
- **D 에서 꺾였다** — 컨슈머 9 → 18 은 1.12 배. 자원 증설로 얻을 것은 파티션에서 다 거뒀다

**남은 자원은 전부 논다** — CPU 최대 0.6 코어(스로틀 0), 디스크는 초당 2,400~4,032 fsync 중
절반 이하 사용, Redis 블록 0, MySQL 은 초당 6,000 커밋을 낼 수 있는데 490 회(용량의 8%)를 쓴다.
**늘릴 곳이 없다.** 남은 천장은 용량이 아니라 돈 경로의 직렬 구간이다.

**세 번 틀렸고 세 번 다 실측으로 잡았다** — 기록의 핵심이다

1. `payment_outbox` 잠금 경합이 천장 → `SUM_LOCK_TIME` 이 피코초라 116,830 초가 아니라 116.8 초
2. 커밋 fsync 가 천장 → 장치를 직접 재니 2,400~4,032/s, 부하는 절반도 안 씀
3. MySQL 커밋 경로가 직렬 천장 → 서버를 직접 재니 초당 6,000 커밋, 앱은 8% 사용

**다음에 할 일**

1. **자원 증설은 더 하지 않는다.** 하려면 잠금 직렬 구간(`payment_outbox` 선점 대 확정
   트랜잭션의 `FOR UPDATE`)을 푸는 동시성 설계 변경이어야 하고, 돈 경로 정합성과 얽혀 있다
2. 파티션·동시성을 측정 손잡이가 아니라 **기본값으로 승격할지** 결정 — 2.19 배는 실제 이득이다
3. ship — 코드 리뷰, `docs/context/` 동기화, TODOS 등재, 아카이브, PR

**측정 방법 — 반드시 지킬 것**

- **매 사이클 초기화한다.** `scripts/bench-clean-cycle.sh` (볼륨 제거 → 인프라 → 토픽 사전 생성
  → 앱 → 복제 구성 → 사이클)
- **파티션은 앱 기동 전에 만든다.** 기동 후 `--alter` 는 `metadata.max.age.ms`(기본 5 분) 만큼
  컨슈머에 반영이 늦다. `KAFKA_TOPIC_PARTITIONS` 가 이걸 처리하고 되읽어 검증한다
- **컨슈머 병렬도는 파티션 수가 상한이다.** 동시성·인스턴스만 올리면 파티션에서 잘린다.
  `kafka-consumer-groups --members` 로 노는 멤버가 없는지 확인하고 측정을 시작한다
- **포화 구간에서는 지연 표본을 끈다** (`LATENCY_SAMPLE_RATE_PER_SEC=0`)
- **`total_wall_sec` 로 용량을 비교하지 않는다.** 종결 대기가 고정 327 초라 분모가
  `부하 + 상수` 다. 유효한 지표는 `settled_during_load`
- **오염된 시기의 수치는 쓰지 않는다**

**측정 손잡이 (제품 코드 0 줄)**

- `KAFKA_TOPIC_PARTITIONS` (기본 3) · `PG_CONSUMER_CONCURRENCY` (기본 1) ·
  `CONFIRMED_CONSUMER_CONCURRENCY` (기본 1) · `INSTANCES` / `PG_INSTANCES`

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
