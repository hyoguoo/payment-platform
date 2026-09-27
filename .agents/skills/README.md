# 저장소 스킬과 공용 자원

스킬 정본은 `.agents/skills/`다. Codex는 각 `SKILL.md`의 이름·설명으로 필요한 스킬을 선택한다.
`.claude/skills`는 같은 디렉토리를 가리키는 호환 링크이므로 양쪽 사본을 따로 수정하지 않는다.
프로젝트 기본 지침은 [AGENTS.md](../../AGENTS.md), 발견 경로와 유지보수 기준은
[CODEX.md](../../docs/context/CODEX.md)에 있다.

## 요청별 진입

| 요청 | 스킬 |
|---|---|
| 토픽 시작·재개 | [workflow](workflow/SKILL.md) |
| 설계·계획·구현·마무리 | [discuss](workflow-discuss/SKILL.md), [plan](workflow-plan/SKILL.md), [execute](workflow-execute/SKILL.md), [ship](workflow-ship/SKILL.md) |
| 코드 리뷰 | [review](review/SKILL.md) |
| 커밋·이슈·PR | [issue-commit-pr](issue-commit-pr/SKILL.md) |
| context 문서 동기화 | [context-update](context-update/SKILL.md) |
| 문서 작성·검수 | [writing](writing/SKILL.md), [doc-review](doc-review/SKILL.md) |
| 위키·포트폴리오 | [wiki-access](wiki-access/SKILL.md), [portfolio-access](portfolio-access/SKILL.md) |
| 학습용 diff HTML | [explain-diff-html](explain-diff-html/SKILL.md) |
| 결제 실측·캡처·리포트 | [payment-live-drill](payment-live-drill/SKILL.md) |

## 공용 자원

- `_shared/checklists/`: 단계별 적용 가능한 완료 기준.
- `_shared/conventions/commit.md`, `_shared/conventions/github.md`: 요청된 Git 작업의 규칙.
- [_shared/conventions/writing.md](_shared/conventions/writing.md): 문서 유형별 작성 규칙 인덱스.
- [.agents/roles/README.md](../roles/README.md): 두 도구의 공통 역할·배차·모델 선택과 보고 방식.
- `.claude/agents/`, `.codex/agents/`: 공통 정본에서 생성하는 도구별 역할 설정.

검사: `python3 scripts/check-agent-docs.py --strict`.
라이브 드릴의 스크립트·참고 문서는 해당 스킬에서만 읽거나 실행한다.
