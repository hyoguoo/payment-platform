# 현재 작업 상태

> 최종 수정: 2026-10-12

## 활성 작업

로컬 결제 품질 측정 도구·스크립트와 실행 환경의 정리·검증을 완료했다.
공식 실측은 미실행이며 측정 수치·요약·그래프·기존 보고서는 커밋에 포함하지 않는다.

- 이슈: [#160 — 로컬 결제 품질 측정 도구와 실행 환경 준비](https://github.com/hyoguoo/payment-platform/issues/160)
- 작업 브랜치: `#160`
- 현재 범위: quality Compose·기동 설정, k6 시나리오, 증빙·판정·정합성 도구와 테스트, HTML 생성기, 일반 실행 안내
- 검증: Python 테스트 65개, 문서 strict·셸 문법·Compose 설정·diff 검사, 독립 코드·도메인 검토 통과
- 미실행 검사: 실제 스택 기동·k6 실측. Java 비즈니스 코드·DB 스키마·Spring 배선 변경이 없어 Java 테스트·정적 분석은 실행하지 않음
- 다음 별도 작업: 요청 키와 주문의 영속 연결 등 접수 추적 개선. 아직 토픽 시작·계획 확정·구현을 진행하지 않음
- 실제 측정: 도구 준비와 추적 개선 이후 별도 범위로 진행. 이번 커밋은 측정 결과가 아닌 도구 준비 상태
- 로컬 보존: 정리 전 파일과 커밋 제외 자료는 `live-drill/baseline-preparation-20261012/`, 기존 원자료는 기존 실행 폴더에 유지
- 혼합 계획 제외: 메인이 먼저 작성한 추적 개선·측정 통합 설계/계획은 로컬 보존하고 Git에서 제외

## 재개 메모

### 별건 — 확정 요청에 멱등키가 없다

- 주문 접수에는 `Idempotency-Key` 헤더가 있으나 확정에는 없다. 재고 게이트 작업에서 주문 단위 선점으로 대응했고 헤더 도입은 여전히 범위 밖

### 별건 — 위키에 남은 끊긴 참조 2곳

- `architecture.md` 의 FCG 상세 링크가 `pg-confirm-flow` 를 가리키는데 그 문서에 FCG 설명이 없다
- `message-delivery-and-dedupe.md` 서두가 "DLQ 처리를 다루며"라고 하는데 본문에 DLQ 절이 없다
- 정리 배치가 끝나면 이어서 처리하기로 했다 (별도 저장소라 같은 PR 에 못 넣는다)

### 별건 — 블로그 포스팅 진행 상황

- 1편 **모놀리식 → 4서비스 분리** 완료 (`notes/blog`, `msa-transition-decisions.md`)
- 2편 **재시도 소진 이후 처리** 예정 — 1편 결말이 "payment→pg HTTP 조회 통로를 두지 않은 결정이 넉 달 뒤 문제가 됐다"로 넘어가게 써 뒀다. 소재는 `docs/archive/retry-exhaustion-disposition/`

## 최근 완료

- **CI-INTEGRATION-TEST-SHARDING** (2026-09-28) — docs/archive/ci-integration-test-sharding/COMPLETION-BRIEFING.md
- **CLEANUP-BATCH-F** (2026-09-11) — docs/archive/cleanup-batch-f/COMPLETION-BRIEFING.md

전체 이력: `docs/archive/README.md` / 구 STATE 이력: `docs/archive/state-history-2026H1.md`
