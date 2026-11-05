# 진행 상태

> 진행 중인 토픽의 목차다. 토픽마다 제목과 다섯 줄만 쓴다. 측정 결과, 판단 근거, 상세 재개 메모는
> 토픽 폴더(DESIGN의 "진행 기록", EXPERIMENTS)에 쓴다. 형식은 [workflow 스킬](../.agents/skills/workflow/SKILL.md#문서와-state)을 따른다.
> 범위 밖 할 일은 [TODOS](TODOS.md)에 둔다.

## 진행 중

**PAYMENT-THROUGHPUT — 결제 처리량 한계 측정과 병목 개선**

- 추적: [#162 — 결제 처리량 한계 측정과 병목 개선](https://github.com/hyoguoo/payment-platform/issues/162), 브랜치 `#162`. 주 작업 모델 Sol 6.1 유지
- 목표: 수정 전 품질/지연 허용 처리 구간 → 실측 병목 개선 → 동일 조건 한계 재탐색
- 단계: T2.1 — 주입·관측 분리, T2.2 — 판정·증빙, T2.3 — 저부하 실구동 완료. **T2.4 — 수정 전 경계 탐색 진행 중, 사용자 요청으로 100 TPS 시험 직전에 중단**
- 정본: [합의](topics/PAYMENT-THROUGHPUT.md), [PLAN](PAYMENT-THROUGHPUT-PLAN.md), [인계·다음 명령](reports/tps-handoff.md), [실행 안내](quality/RUNBOOK.md), [도구 저부하 검증](reports/tps-tool-validation.md)
- 현재 환경: **60/s 품질 시험 완료·로그/이미지/원자료 저장·볼륨 보존 종료. 실행 중인 컨테이너/부하 없음. 100/s 프로젝트 기동·시드·워밍업·본측정 모두 미실행**
- 최신 결과: user-quality60/s180초 10,801건 전원 완료·주문/승인/재고 전수대조 PASS. 누락/API/관측오류/포화0, 유입 VALID·소스 불변. 완료 관측 p95 1,045ms/p99 1,079ms로 품질 목표 통과. 메모리 포함 전체 지속 분류는 원래 INCONCLUSIVE 유지
- 수정 전 탐색: capacity80/s는 초기 적체 후 부하 중 회복, capacity120/160/s는 적체 증가 후 종료 뒤 해소(BURST_ABSORBED). user-quality40/60/s 품질 목표 통과, 80/s는 실제 DB 적체와 관측 포화 동반. **모두 3분 탐색이며 10분 확인·최대 TPS 확정 전**
- 첫 capacity80-a는 옛 경계룰 문제로 EXCLUDED·원자료/판정 보존, 보정 후80-b 재시험. 새 모드만 예정 대비 시작 -1..100ms·연속 iteration·총량/누락 검사, legacy 불변. 모든 실제 시작을 분모/정합성 대조에 유지
- 서버 상태: **메인 Java·실행 JAR·운영 기본 소비 동시성1 미변경**. 120/s의 명령 lag는 회복했지만 결과 lag는 증가·DB 풀 대기 표본0. 결과 소비1→3은 첫 비교 후보이며 채택/효과는 미확정
- T3 사전 준비: 신규 `ConfirmedConsumerConcurrencyIntegrationTest.java` 4개 시나리오 작성·독립 reviewer/domain-expert 수정 재검토 PASS. **컴파일·Java 단위/정적/통합 검사는 아직 미실행**. 신규 로컬 `run-consumer-recovery.py` 작성만 완료·독립 검토/실행 미완료
- 검토 제약: 복구 드라이버 검토 시 서브에이전트 모델 사용량 제한 발생. 같은 호출 반복 금지, 공통 역할 지침의 사용 가능한 메인 모델 대체 또는 메인 직접 검토 필요. 대체 검토는 아직 미실시
- 일반 재개 요청: 다음에 사용자가 **“중단됐던 작업 진행”** 또는 같은 뜻의 재개 요청을 하면 이 PAYMENT-THROUGHPUT를 우선 재개. 이 STATE → 인계 → PLAN/로컬 RESUME를 읽고 완료 단계를 반복하지 않으며, 별도 요청 추적 토픽으로 전환하지 않음
- 프로세스: 지금 미리 띄우거나 계속 유지할 필요 없음. 재개 시 호스트·Docker/기존 컨테이너·JAR·프로젝트 이름을 확인하고, 필요한 시점에 새100/s 환경부터 기동. 기존 상태 재사용 금지
- 다음 실행: **T2.4 — 새 환경 capacity100/s180초**. 인계의 명령 사용, 정상 VU650/상한650, 10/s120초 워밍업. 이후 경계 좁힘·용량/품질 후보10분 확인 → T3 회귀/복구 검증·개선 → T4 전후 재탐색
- 조건: 매 실측 새 프로젝트·새 컨테이너·새 볼륨·빈 DB/Redis/Kafka 확인·동일 시드. 기존 자료 보존, 재시드/down -v/clean/reset-db 금지. 실행 중 측정 소스 동결·무거운 테스트 병행 금지
- 판정: capacity는 전수 상태 조회 제외, user-quality는 같은 구매1초·최대30회 조회. 접수 p95≤500ms·완료 p95≤3초/p99≤5초는 자체 목표. 품질 통과와 메모리 포함 지속 판정, DB 이력 기록 시각과 실제 커밋 순간을 구분
- 검증 이력: 도구 Python124개·문서 strict57개 문제0·독립 검토 PASS. 새 회차/검증·보존 근거는 `live-drill/throughput-split-measurements-20261029/RESUME.md` 및 회차별 원자료에 보관
- Git: 사용자 요청으로 검증된 T2 도구·문서·측정 체크포인트를 로컬 커밋. 미실행 T3 신규 Java 테스트는 커밋에서 제외하고 작업 트리에 보존, 원자료/로컬 드라이버는 Git 제외 유지. 푸시/PR/병합 없음. **별도 요청 추적 문서4개·아래 STATE 사용자 구간은 보존하고 커밋에 편입하지 않음**

### 별도 진행 중인 요청 추적 토픽

**CHECKOUT-RECEIPT-TRACKING — 서버 중단 후 요청 추적과 안전한 재요청**

- 단계: 현재 현황·보고서 검토 후 workflow discuss 재개. 범위·도달 보장 확정, 세부 설계 논의 중
- 최신 문서 요청: 합의와 제안을 이해하기 쉬운 HTML 보고서로 작성. 현재의 빈틈→최초·재요청 그림→장애 상황 네 가지→도달 확인→다음 작업 순서로 갱신
- 최신 사용자 결정: 요청 추적과 안전한 재요청을 모두 한 토픽에서 진행. 어느 것을 먼저 할지는 상관없음
- 확정 보장: 도달 증거가 유실된 요청은 미확인 허용. 같은 구매의 재요청은 저장된 주문 재사용, 미저장 주문 생성으로 안전하게 처리
- 출발점: 서버 중단·재기동 뒤 Gateway 도달, payment 업무 진입, 주문 DB 저장을 구분하기 어려웠음
- 현재 관측: Gateway·payment trace와 주문 ID 로그, Promtail→Loki 수집 설정이 이미 존재. 실행 도구는 서버 로그·trace를 실행별 원자료로 저장하지 않고 판정기는 Redis·DB만 대조
- 미검증: traceparent 없는 일반 요청의 Gateway 수신 로그→payment trace 연속성과 기존 Loki 수집의 실제 동작. 기존 스모크는 클라이언트가 traceparent를 제공한 경로만 검사
- 현재 멱등성: Redis 결과 기본 TTL·잠금 TTL 10초, 결과 저장은 DB 커밋 전, DB 구매 키 연결·동일 키 내용 대조 없음. 서비스 변경이 필요한 안전한 재요청 설계 대상으로 포함
- 설계 제안: 구매 키·주문을 같은 DB 트랜잭션에서 확정하고 DB 고유 제약으로 중복 방지. 기존 checkout 재요청으로 주문 회수, 기존 관측 검증과 실행 로그 보존을 함께 검토
- 경계: 구매 키의 영속 연결은 주문 저장 증거이며 주문 저장 전 도달 증거가 아님. 재요청으로 생성한 주문을 원 실패 시도의 커밋으로 소급 판정하지 않음
- 추가 장치: 별도 영속 도달 이력은 확정 보장에 필수 아님. 공개 조회 API·trace ID DB 저장·k6 trace 생성은 각각의 필요를 검증하고 선택
- 남은 설계: 구매 키 계약·보관·내용 충돌·접근 범위의 구체화, DB 경합·커밋 불명 처리, 개별 k6 실패 시도와 trace의 정확한 연결, 클라이언트 키 보관과 재요청
- 과거 선택: 사용자용 복구·구매 키 계약은 discuss 이력으로 보존하고 논의 근거로 활용. 과거 상세 구현안을 그대로 확정하지 않음
- 문서: [플로우 보고서](reports/checkout-receipt-tracking-flow.html), [코드 점검 결과](reports/checkout-receipt-tracking-review.md), [최신 discuss](topics/CHECKOUT-RECEIPT-TRACKING.md), [과설계 및 설명 정정 기록](reports/checkout-receipt-tracking-corrections.md)
- 검토: 이번 재개에서 독립 reviewer·domain-expert의 코드·설계 검토 완료. Redis 선행 결과·만료·내용 충돌, DB 원자성·고유 제약, 클라이언트·키 비노출 조건 반영
- HTML 검수: 독립 문서·도메인 검수 완료. 요청 기록 식별 조건을 조사 카드 앞에 표시하고, 미저장·진행 중·확인 불가와 확정된 키의 내용 충돌 경계를 반영
- 검증: 이번 갱신에서 문서 strict(문제 0건), 변경 문서 3개 로컬 링크 32건·공백·임시 표기, diff 검사 통과. 이전 HTML·시각 검수 이력 보존. Java 변경·테스트·스택 기동·실측 없음
- 최신 HTML 검사: 구조·제목·6개 섹션·링크 14건·공백·외부 실행 의존성 검사 통과
- 최신 시각 검수: Chrome에서 로컬 HTML 첫 화면 렌더링 확인. 후속 클릭은 도구의 Chrome 사용 미승인으로 거절돼 추가 호출 중단. 본문 전체·모바일 시각 검수는 미실시이며 이전 캡처를 최신 화면 검수로 사용하지 않음
- Git: 로컬 main의 기존 변경과 원자료 보존. 커밋·푸시·PR·이슈·계획·구현 브랜치 생성 없음
- 다음 재개: 최신 통합 범위와 미확인 허용을 기준으로 세부 설계 논의. 합의된 제품 계약과 검증 기준을 PLAN으로 분해하되 과거 확대안을 그대로 이전하지 않음
- 별도 후속: 실제 부하 측정·성능 탐색. 이 토픽과 합치지 않음

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
