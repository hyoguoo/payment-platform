---
name: workflow-ship
description: >
  payment-platform 워크플로우의 ship 단계(코드 리뷰 + 마무리)를 실행한다.
  execute 완료 후 "ship 시작", "리뷰 시작", "코드 리뷰", "리뷰하고 마무리",
  "검증하고 마무리", "아카이브", "PR 만들어줘" 등을 말할 때 이 스킬을 사용한다.
  리뷰 → 수정 → 최종 검증 → 문서 동기화 → 아카이브 → PR 흐름이다.
---

# Ship 단계

**Phase A (리뷰)** 와 **Phase B (마무리)** 로 구성된다. 사이에 사용자 게이트가 있다.
리뷰·수정은 서브에이전트 격리, 마무리는 결정론적 작업이라 메인이 직접 수행한다.

---

## Phase A — 코드 리뷰

### A1. 리뷰 dispatch (단일 메시지 병렬)

**domain-expert 포함 조건**: `discuss-ready.md` domain risk 섹션의 2갈래 조건을 실제 diff에 적용한다(diff에 소스 코드·런타임 설정 변경이 있는지 / diff 산출물이 결제 도메인 동작을 서술·정정하는지). 도메인 비접촉 diff(워크플로우·스킬 정비 등)는 reviewer만 dispatch하고 리뷰 완료 보고에 "domain-expert 생략 (도메인 비접촉)"을 명시한다.

```
Agent(subagent_type="reviewer",      prompt="stage=ship, topic=<TOPIC>.
  대상: git diff main...HEAD (+ git log main..HEAD --oneline)
  체크리스트: .claude/skills/_shared/checklists/code-ready.md
  참고: docs/topics/<TOPIC>.md 결정 사항, docs/<TOPIC>-PLAN.md")
Agent(subagent_type="domain-expert", prompt="stage=ship, topic=<TOPIC>.
  대상: git diff main...HEAD
  체크리스트: code-ready.md 의 domain risk 섹션 + 리스크 카탈로그 전체
  참고: docs/topics/<TOPIC>.md 결정 사항, docs/context/PITFALLS.md")
```

메인 스레드에서 diff를 읽고 findings를 직접 작성하지 않는다.

### A2. findings 처리 (사용자 확인)

severity별로 사용자에게 확인:
- **critical** — 항목마다 개별 확인 (수정 / 의도적 스킵 / 보류)
- **major** — 목록 일괄 표시 후 번호 선택 (`예: 1 3 / all / skip`)
- **minor** — 목록만 표시, 요청 시 수정 대상에 추가

선택 결과를 **PLAN.md 하단 `## 리뷰 처리` 섹션에 기록** (finding 한 줄 + 채택/스킵 + 사유). 대화가 끊겨도 여기가 SSOT다.

### A3. 수정 dispatch

수정은 메인이 직접 하지 않고 implementer에 위임 (여러 건 묶어 1회):

```
Agent(subagent_type="implementer", prompt="모드 2 — 리뷰 finding 수정.
  findings: <선택 목록: 파일:라인 + 문제 + 제안>
  스킵 항목: <// REVIEW: intentionally skipped 주석 대상>")
```

### A4. 재리뷰 (최대 1회)

전체 diff를 다시 읽지 않는다 — 전체 회귀는 B1 테스트가 잡고, 수정 코드의 호출·의존 주변은 에이전트 정의상 따라가 본다.

- 대상: **수정 커밋 diff + 원 findings 목록** — 각 finding의 해소 여부 판정 + 수정으로 새로 생긴 결함 탐색
- dispatch: **수정한 finding을 낸 에이전트만** 재호출. 단, 수정이 상태 전이·멱등성·보상 등 도메인 축을 건드리면 domain-expert 포함
- **새 critical이 없으면 통과.** 새 critical이 나오면 수정 후 재리뷰를 반복하지 않고 `workflow` 스킬의 교착 처리(계속 / 방향 수정 / 중단)로 에스컬레이션

"추가로 수정하고 싶은 부분이 있으신가요?" 확인 후 A5로.

### A5. 설명 페이지 자동 생성

리뷰 통과(1차 pass 또는 재리뷰 통과) 직후 `explain-diff-html` 스킬로 이번 작업의 설명 페이지를 생성한다. 기본 자동 — 사용자가 생략을 지시했거나, 제외 규칙 적용 후 설명할 코드 diff가 없으면(도메인 비접촉 토픽) 건너뛰고 게이트 메시지에 생략 사유를 표기한다.

역할 경계: 마크다운 브리핑(topic.md·PLAN.md·COMPLETION-BRIEFING)은 설계·플랜 판단용이고, HTML 설명 페이지는 완료 후 변경 이해용이다 — 서로 대체하지 않는다.

- 대상: `git diff main...HEAD` (스킬의 `docs/`·`.claude/` 제외 규칙 적용)
- 저장: `.archive/explanations/YYYY-MM-DD-<topic-kebab>.html`
- 목적: 사용자가 아래 게이트에서 페이지를 읽고 마무리 진행 여부를 판단한다.
- 재생성: 게이트의 추가 수정이든 B1 실패 수정이든 **PR 생성(B7) 전에 코드가 바뀌면 같은 파일로 재생성**한다. 게이트에서 사용자가 요청한 수정은 리뷰 finding이 아니므로(검토 주체가 사용자 본인) 재리뷰 없이 implementer 수정 + 페이지 재생성만 한다.

### 사용자 게이트

```
## 리뷰 완료
critical N건 해소, major N건 처리, minor N건 기록.
설명 페이지: .archive/explanations/<파일명>.html (생략 시 사유 표기)
페이지 확인 후, 마무리(최종 검증 → 문서 동기화 → 아카이브 → PR)를 진행할까요?
```

**자동 진행 금지.** 사용자가 설명 페이지를 읽고 직접 테스트할 시간을 보장한다.

---

## Phase B — 마무리 (메인 직접)

`_shared/checklists/ship-ready.md`를 열고 Gate → Post-phase 순서로 직접 확인·실행한다.

### B1. 최종 검증

- `./gradlew test` 전체 실행
- **통합테스트 명시 실행** — build/test가 UP-TO-DATE 캐시면 통합테스트가 돌지 않는다: `./gradlew integrationTest --rerun` 또는 해당 태스크 직접 지정. **다중 서비스 변경 시**: 한 토픽이 여러 서비스를 건드렸으면 일부만 재실행하지 않는다 — 변경이 닿은 모든 서비스의 통합테스트를 재실행한다. 일부만 돌리면 나머지는 캐시로 조용히 스킵돼, 과거 실제로 한 서비스의 통합테스트가 안 돌아 CI에서야 컨텍스트 로드 실패가 드러난 적이 있다
- 린트 게이트: `./gradlew checkstyleMain checkstyleTest spotbugsMain spotbugsTest --continue` — CI lint step(`_service-ci.yml`)과 같은 태스크 집합 유지
- 실패 분류: 이번 작업 관련 → implementer로 수정 / 사전 존재 → 기록 후 무시 / 구조적 → 중단·보고
- **라이브 검증 원칙**: 합성 테스트(문법·픽스처 통과)를 검증 완료로 보지 않는다 — 가능하면 실제 장애 주입으로 신호 경로 끝까지 관측한다. 이 원칙은 알람 규칙에 한정되지 않고 검증 요구 전반에 적용된다.
- **라이브 검증 (조건부)**: 런타임 행동이 바뀐 토픽(알람 규칙·Kafka 토픽/컨슈머 설정·스케줄러·관리자 운영 경로)은 `docs/smoke/` 해당 가이드로 실환경 발화/동작을 확인한다. 스택 기동 불가 등으로 못 하면 사유 + 미검증 항목을 COMPLETION-BRIEFING 미결/후속에 기록 — 암묵 생략 금지, 의식적 스킵만

### B2. Context 문서 갱신

`context-update` 스킬 실행. `git diff main...HEAD --stat`을 시작점으로 범위 최소화.
변경이 결제 흐름(컨트롤러·use case·Kafka 토픽·pg-service·재고 정산 등)에 닿으면, **사람 독자용** `docs/context/PAYMENT-FLOW-GUIDE.md`도 같은 변경에 맞춰 갱신한다 — 이 문서는 평소엔 참조하지 않고 **ship 단계에서만** 손댄다.
작업이 `TODOS.md`/`CONCERNS.md` 등 대장 문서에 완료 항목을 남겼다면, `context-update` 스킬의 3분류 삭제 룰을 적용해 ✅ 마킹으로 쌓아두지 않고, 갱신한 문서의 헤더 "최종 갱신"을 본문과 동기화한다 (`_shared/checklists/ship-ready.md` documentation sync 항목 확인).

### B3. 완료 브리핑 작성

`docs/archive/<topic-kebab>/COMPLETION-BRIEFING.md` — 이 파일 하나로 "무엇을, 왜, 어떻게, 결과가 어땠는지" 파악 가능해야 한다.

필수 섹션:
- `## 작업 요약` — 배경 → 문제 → 접근 → 결과를 서사형 문단으로
- `## 핵심 설계 결정` — 결정 / 근거 / 기각된 대안과 이유
- `## 변경 범위` — 영역별 추가/변경/제거와 의도
- `## 다이어그램` — 작업 성격에 맞는 Mermaid (상태 전이 stateDiagram-v2 / 흐름 flowchart / 시퀀스), 해당 없으면 생략
- `## 코드 리뷰 요약` — PLAN.md `## 리뷰 처리` 섹션을 정리: 무엇이 지적됐고 어떻게 해소/스킵했는지
- `## 수치` — 태스크 N / 테스트 N 통과 / 커밋 N / findings critical·major·minor

### B4. 아카이브

```bash
mkdir -p docs/archive/<topic-kebab>
git mv docs/<TOPIC>-PLAN.md docs/archive/<topic-kebab>/<TOPIC>-PLAN.md
git mv docs/topics/<TOPIC>.md docs/archive/<topic-kebab>/<TOPIC>-CONTEXT.md
```

`docs/archive/README.md` 테이블에 행 추가.

### B5. STATE.md 종결

활성 작업 → 없음(idle), 최근 완료에 한 줄 + 브리핑 링크 (최대 2개 유지), 재개 메모 비움.

### B6. 최종 커밋

```bash
git add docs/STATE.md docs/context/ docs/archive/
git commit -m "docs: <주제> 작업 완료 및 문서 아카이브"
```

`_shared/conventions/commit.md` 준수.

### B7. Push + PR

`_shared/conventions/github.md` Step 3/4 준수. `mcp__github__*` 툴로 메인이 직접 수행. merge는 하지 않는다 — 사용자 권한.

### 완료 알림

```
## 작업 완료
**주제**: <주제> / **태스크**: N개 / **테스트**: 전체 통과
**아카이브**: docs/archive/<topic-kebab>/ / **PR**: <URL>
**설명 페이지**: .archive/explanations/<파일명>.html
```
