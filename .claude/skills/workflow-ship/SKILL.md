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

### A4. 재리뷰

수정 후 A1을 재실행. **새 critical이 없으면 통과.** "추가로 수정하고 싶은 부분이 있으신가요?" 확인 후 Phase B 게이트로.

### 사용자 게이트

```
## 리뷰 완료
critical N건 해소, major N건 처리, minor N건 기록.
마무리(최종 검증 → 문서 동기화 → 아카이브 → PR)를 진행할까요?
```

**자동 진행 금지.** 사용자가 직접 테스트할 시간을 보장한다.

---

## Phase B — 마무리 (메인 직접)

`_shared/checklists/ship-ready.md`를 열고 Gate → Post-phase 순서로 직접 확인·실행한다.

### B1. 최종 검증

- `./gradlew test` 전체 실행
- **통합테스트 명시 실행** — build/test가 UP-TO-DATE 캐시면 통합테스트가 돌지 않는다: `./gradlew integrationTest --rerun` 또는 해당 태스크 직접 지정
- 린트 게이트: `./gradlew checkstyleMain checkstyleTest spotbugsMain --continue`
- 실패 분류: 이번 작업 관련 → implementer로 수정 / 사전 존재 → 기록 후 무시 / 구조적 → 중단·보고

### B2. Context 문서 갱신

`context-update` 스킬 실행. `git diff main...HEAD --stat`을 시작점으로 범위 최소화.

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
```
