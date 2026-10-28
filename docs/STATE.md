# 진행 상태

> 진행 중인 토픽의 목차다. 토픽마다 제목과 다섯 줄만 쓴다. 측정 결과, 판단 근거, 상세 재개 메모는
> 토픽 폴더(DESIGN의 "진행 기록", EXPERIMENTS)에 쓴다. 형식은 [workflow 스킬](../.agents/skills/workflow/SKILL.md#문서와-state)을 따른다.
> 범위 밖 할 일은 [TODOS](TODOS.md)에 둔다.

## 진행 중

**PAYMENT-THROUGHPUT — 결제 처리량 한계 측정과 병목 개선**

- 추적: [#162 — 결제 처리량 한계 측정과 병목 개선](https://github.com/hyoguoo/payment-platform/issues/162), 로컬 브랜치 `#162`
- 목표: 수정 전 처리 한계 → 첫 실측 병목 개선 → 개선 후 한계 재탐색. 첫 병목에 한정하지 않고 반복하며 주 작업 모델은 사용자 선택 Sol 6.1 유지
- 단계: 최신 discuss 합의·PLAN 갱신 완료. **다음은 execute T2.1 — 요청 주입·완료 관측 분리의 실패 테스트**
- 재개 문서: [인계·미해결 항목](reports/tps-handoff.md), [합의](topics/PAYMENT-THROUGHPUT.md), [PLAN](PAYMENT-THROUGHPUT-PLAN.md), [기존 실측](reports/tps-clean-measurements.html)
- 합의: 용량 시험은 전수 상태 폴링 제외·완료 이력 전수 대조. 사용자 품질 시험은 실제 UI와 같은 1초·최대 30회 조회. 기존 500ms·60초 조건은 별도 보존
- 자체 목표: checkout/confirm 각각 p95 500ms 이하, confirm 시작→사용자 완료 확인 p95 3초·p99 5초 이하, 정상 Fake API 오류·주문/승인/재고 불일치 관측 0. 업계 공통 표준이나 외부 PG 보장이 아님
- 판정: 품질 충족 / 지연 허용 지속 처리 / 일시 폭주 흡수 / 측정 미확정 구분. 유입을 계속 유지할 때 잔량·완료율·메모리 추세를 보고, 늦은 완료를 사용자 기한 내 성공으로 소급하지 않음
- 이전 결과: 기존 500ms 조건 r80-c/d(1→3), 각각 14,400/14,401건 완료·누락0·PASS·완료 관측 p95 1,553→544ms. 단일 비교로 개선 배수·최대 TPS·기본값 채택을 확정하지 않음
- 최신 실행: r160-a(1·160/s·180초·VU650), 예정28,800/시작10,062/누락18,738·시작된 건 전부DONE·수량불일치0. INCONCLUSIVE·sourceChanged=false. 접수p95694ms / 완료관측p9516,738ms·최대21,680ms
- 시각 해석: 최종 커밋된 DONE 이력의 기록 시각 기준 부하 구간9,426·이후636. 실제 commit 순간 계측이나 최대 TPS가 아님. 결과lag표본628·payment DB연결대기표본411
- 현재 환경: `throughput-clean-20261029-r160-a` 실행 상태 재확인, 부하·관찰 종료. **다음 회차는 볼륨 보존 down 후 새 컨테이너·새 볼륨·빈 상태 검사·동일 시드·10/s 120초 워밍업. 기존 상태 재시드/down -v 금지**
- 구현 상태: 지표 name 고정·불변 유입 오류만 남은 관찰 종료·quality 동시성 실험 설정은 구현됨. **새 시험 모드·새 지연 판정은 미구현, 서버 Java 로직·운영 기본 동시성1은 미변경**
- 다음 순서: T2.1 주입/관측 분리 → T2.2 판정/증빙 → T2.3 검토·저부하 검증 → T2.4 새 조건의 기준 한계 탐색. 첫 병목 근거 후 T3/T4 개선·동일 조건 재측정
- 검증: Python68개·문서 strict57개 문제0·Compose 기본 동시성1·문서/HTML 상대 링크 검사·diff 통과. 최신 계획/인계/보고서 독립 reviewer·domain-expert 검토 PASS, DONE 시각 표현 보완 반영. 최신 HTML 8개 결과 행과 160/s 본문 Chrome 시각 확인
- Git/재개: 사용자 요청에 따라 지침과 성능 도구·분석·합의를 나눠 로컬 커밋. 푸시·PR·병합 없음. 이번 체크포인트 이후 컴팩트하고 T2.1부터 계속할 예정
- 보존: 별도 CHECKOUT-RECEIPT-TRACKING 문서와 해당 STATE 구간은 기존 사용자 변경으로 보존·이번 커밋에서 제외. 원자료는 `live-drill/throughput-clean-20261028/`에 있으며 Git 제외

## 재개 메모

### 별건 — 정식 워크플로우 추적 준비 지침 정비 (2026-10-29)

- 사용자 요청으로 AGENTS·workflow 및 연결 스킬·GitHub 규칙·체크리스트·CODEX 유지보수 문서를 일치시킴
- 명시적 정식 workflow는 범위 합의 후 추적 이슈 연결/생성·실행 전 작업 브랜치 준비 포함. 일반 작업·단계/로컬 제한 우선, 기존 이슈/브랜치 재사용
- 커밋/푸시/PR/병합으로 자동 확대하지 않음. 이슈만 생성할 때 staging도 하지 않도록 독립 검토 지적 수정
- 독립 검토와 대표 요청 6가지 읽기 전용 행동 평가 PASS. 문서 strict 57개 문제0·변경 스킬4개 quick_validate·diff PASS
- quick_validate의 기본 Python에는 PyYAML이 없어 첫 호출 실패 후 uv 임시 의존성 환경으로 실행해 통과. 저장소 의존성·역할/모델 설정 변경 없음
- 근거: `live-drill/workflow-guidance-20261029/verification.json`. 사용자 후속 요청으로 지침을 독립 로컬 커밋에 포함. 신규 이슈/푸시/PR·Java 검사·부하 실측 없음
- PAYMENT-THROUGHPUT #162의 다음 구현 T2.1은 그대로 유지하며, 이 지침 정비를 성능 개선 완료로 계산하지 않음


### 별건 — 측정 도구 준비 완료와 원자료 보존

- 이슈 [#160 — 로컬 결제 품질 측정 도구와 실행 환경 준비](https://github.com/hyoguoo/payment-platform/issues/160) / PR [#161](https://github.com/hyoguoo/payment-platform/pull/161)은 `main`에 머지됨
- 도구 준비 검증: Python 테스트 65개, 문서 strict·셸 문법·Compose 설정·diff 검사, 독립 코드·도메인 검토 통과
- 공식 실측 미실행. 실제 스택 기동·k6 실측 및 Java 검사는 도구 준비 작업에서 미실행
- 기존 측정 수치·요약·그래프·보고서는 공식 결과에서 제외. 정리 전 파일과 제외 자료는 `live-drill/baseline-preparation-20261012/`, 기존 원자료는 기존 실행 폴더에 유지
- 과거 추적 개선·측정 혼합 설계와 계획은 로컬 보존하고 Git에서 제외. 새 토픽의 확정 요구로 취급하지 않음

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

- **AGENT-STRUCTURE-OVERHAUL** (2026-11-06) — [완료 요약](archive/agent-structure-overhaul/BRIEF.md#완료-요약)
- **CI-INTEGRATION-TEST-SHARDING** (2026-09-28) — [완료 브리핑](archive/ci-integration-test-sharding/COMPLETION-BRIEFING.md)

전체 이력: [archive 목차](archive/README.md) / 2026-06 이전 STATE 이력: `docs/archive/state-history-2026H1.md`
