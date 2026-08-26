# 현재 작업 상태

> 최종 수정: 2026-08-27 (세션 마무리 — 활성 작업 절을 재개 포인터로 슬림)

## 활성 작업

### SHARED-RESOURCE-SCALEOUT — 공유 자원 동반 스케일아웃 측정

- 단계: **중도 중단** (execute 완료 / ship 미착수). 브랜치 `#146`, 커밋 35개, **원격 미푸시**
- 중단 사유: 월 사용 한도. 작업 자체는 막힌 데 없음
- 산출물: 설계 `docs/topics/SHARED-RESOURCE-SCALEOUT.md` · 플랜 `docs/SHARED-RESOURCE-SCALEOUT-PLAN.md`(태스크 22개 완료 결과 전부 기록) · 리포트 `docs/SHARED-RESOURCE-SCALEOUT-REPORT.md`
- 사람이 읽을 경과 요약: https://claude.ai/code/artifact/6c3a7a13-da85-41dd-a8b9-d30ddacfd38b

**측정 결론** — 판정선(인스턴스 1 → 2 확정 처리율 1.6배) 미달. 실측 0.67배이고 늘리면 오히려 나빠진다. 병목 후보 8개를 근거로 배제했으나 지목 실패, 원인 미상. 정합성은 전 사이클 통과(재고 마스터 4대에 주문당 상품 3개 구성 포함).

**재개 시 할 일 — ship**

1. 코드 리뷰 (reviewer + domain-expert). **스케일 붕괴 결함을 반드시 짚는다** — 게이트웨이 기동이 인스턴스를 삭제해 인스턴스 축을 통째로 무효화할 뻔했다
2. `docs/context/` 문서 동기화
3. `docs/context/TODOS.md` 등재 3건 — 제품 견고성 2건(적용 불가능한 확정 결과가 소비자를 막는다 / 회수가 되돌린 결제와 재고 선점이 자동으로 안 풀린다) + 장애 전환 검증 별도 토픽
4. 아카이브 + PR

**재개 전에 알아야 할 것**

- 스택은 내려 뒀고 볼륨은 전부 보존했다. 다시 띄우면 이어진다
- `results/*.json`(사이클 원본)은 git 비추적이라 이 머신에만 있다. 리포트가 필요한 수치는 다 옮겨 담았다
- 처음 아홉 사이클의 **처리율 수치는 무효**다(부하가 시스템에 닿지 않았다). 리포트 "경위" 절에 이유가 있다. 정합성 결과와 결함 발견은 유효
- 측정 중 `payment-alertmanager`가 Exited 상태로 발견됐다(이 작업과 무관, 웹훅 URL 미설정 추정) — ship에서 확인 대상

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
