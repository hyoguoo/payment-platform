# 현재 작업 상태

> 최종 수정: 2026-08-31 (원인 지목 — payment_outbox 잠금 경합. 계측 확장 + 클린 사이클 도입)

## 활성 작업

### SHARED-RESOURCE-SCALEOUT — 공유 자원 동반 스케일아웃 측정

- 단계: **재측정 진행 중** (원 측정은 "원인 미상"으로 닫혔으나 후속 조사로 원인을 지목했다). 브랜치 `#146`, **원격 미푸시**
- 산출물: 설계 `docs/topics/SHARED-RESOURCE-SCALEOUT.md` · 플랜 `docs/SHARED-RESOURCE-SCALEOUT-PLAN.md` · 리포트 `docs/SHARED-RESOURCE-SCALEOUT-REPORT.md`(정정 주석 포함) · **조사 기록 `docs/SHARED-RESOURCE-SCALEOUT-INVESTIGATION.md`(이 작업의 정본)**

**지금까지의 결론**

- **원인은 자원 부족이 아니라 잠금 경합이다.** 자원을 일곱 가지(CPU / payment 대수 / pg 대수 / 컨슈머 동시성 / pg 워커 / fsync / 코어 고정) 늘려봤고 전부 처리량을 못 올렸다. 어느 자원도 포화가 아니었다 — CPU 30%대, Redis 블록 0, pg 큐 얕음
- **고친 것 — `pg_inbox` 데드락.** 옵티마이저가 유니크 인덱스와 `status` 인덱스를 `index_merge intersect` 로 함께 잠가, 서로 다른 주문의 UPDATE 끼리 반대 순서로 물렸다. 결제 3.3%(163건)가 실패해 60초 좀비 회수로만 종결됐다. `(status, updated_at)` 복합 인덱스로 교체(V8) → 데드락 0, 종결 지연 **p99 73초 → 9.3초**
- **미해결 — `payment_outbox` 잠금 대기가 진짜 천장이다.** `claimToInFlight` UPDATE 가 잠금 대기로 116,830초를 쓴다(자기 실행 시간의 90%, 2위 문장의 98배, 호출당 5.3초). 실행 계획은 깨끗하다 — 확정 트랜잭션의 `findByOrderIdForUpdate`(의도된 블로킹 `FOR UPDATE`)와 릴레이 선점이 같은 행에서 만나는 **직렬 구간**이다
- **스케일아웃 배수** — 클린 측정에서 1대 47.2/s → 2대 61.5/s = **1.30배**(n=2). 판정선 1.6배 미달

**재개 시 할 일**

1. **`payment_outbox` 잠금 경합 해소 방향 결정** — `SKIP LOCKED` 도입 / `FOR UPDATE` 범위 축소 / 릴레이 주기 조정. 셋 다 돈 경로 정합성과 얽혀 있어 코드를 읽고 판단해야 한다. 고친 뒤 스케일아웃 배수가 오르는지 재측정
2. ship — 코드 리뷰, `docs/context/` 동기화, TODOS 등재, 아카이브, PR

**측정 방법 — 반드시 지킬 것**

- **매 사이클 초기화한다.** `scripts/bench-clean-cycle.sh` 를 쓴다(볼륨 제거 → 인프라 → 앱 → 복제 구성 → 사이클). 기존 재구성은 payment 원장만 비워 `pg_inbox`/`pg_outbox`·Kafka 로그가 누적되고, 사이클마다 조건이 나빠져 그 하락이 구성 차이로 오독된다
- **구성을 번갈아 돌린다.** 순차로 돌리면 뒤가 무조건 불리하다
- **포화 구간에서는 지연 표본을 끈다**(`LATENCY_SAMPLE_RATE_PER_SEC=0`). 켜 두면 표본 폴링이 본 부하의 80%에 달하는 요청을 얹고, 느린 구성에 세금이 더 걸려 배수까지 왜곡한다
- **중단했으면 고아 샘플러를 확인한다.** `ps -eo pid,ppid,command | awk '$2==1 && /bench-scaleout-cycle/'`. trap 을 넣었지만 이전에 남은 것이 있을 수 있다
- **오염된 시기의 수치는 쓰지 않는다.** 이 세션 중반의 측정값(3대·4대 배수, pg 2대 0.93배, CPU 2배 1.26배, 동시성 실험)은 누적 테이블 또는 고아 샘플러 위에서 잰 것이라 폐기했다. 신뢰 가능한 클린 값은 1대 47.2 / 2대 61.5/s 뿐이다

**재개 전에 알아야 할 것**

- 계측을 크게 넓혔다 — `mysql-pg` 표본화(그동안 아예 없었다), 누적 잠금 대기·평균 대기 시간, pg 큐 깊이, 돈 경로 소비 적체, CPU 스로틀, 부하 구간 처리량
- **"지표가 0"을 근거로 후보를 배제하지 않는다.** 잠금 대기를 순간값으로만 재던 탓에 두 페이즈 동안 0 으로 읽혔다. 같은 구간을 누적 카운터로 재니 721건이었다
- 커밋 4건 완료(제품 2 · 도구 1 · 문서 1). 전체 테스트 1,218개 통과 확인(`--rerun-tasks` 로 캐시 우회 — 그냥 돌리면 `40 up-to-date` 로 아무것도 안 돈다)
- 스택은 떠 있고 볼륨은 클린 사이클이 매번 지운다. `results/*.json` 은 git 비추적

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
