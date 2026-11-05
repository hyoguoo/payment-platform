# 에이전트 운영

Claude Code와 Codex가 같은 지침, 스킬, 역할을 쓰도록 저장소를 구성한 방식과 유지보수 방법이다.
작업 규칙 자체는 [AGENTS.md](../AGENTS.md)에 있다.

## 구성

| 경로 | 내용 | 직접 고치는가 |
|---|---|---|
| `AGENTS.md` | 프로젝트 지침 정본. Codex가 자동으로 읽는다 | 예 |
| `CLAUDE.md` | `@AGENTS.md`를 가져오기만 한다. Claude Code가 어떤 설정에서도 AGENTS.md를 읽게 한다 | 아니오 |
| `.agents/profiles/` | `stack.md`(버전, 검증 명령, hook 설정, 코드 규칙), `risk.md`(불변식, 핵심 경로) | 예 |
| `.agents/conventions/` | Git, 테스트 관점, 작성 기준 | 예 |
| `.agents/templates/` | 토픽 문서 템플릿(BRIEF, DESIGN, EXPERIMENTS) | 예 |
| `.agents/skills/` | 스킬 정본. 아래 표 | 예 |
| `.agents/roles/`, `.agents/roles.toml` | 역할 본문과 모델·도구 설정의 정본 | 예 |
| `.agents/hooks/` | 검증 hook 스크립트. 프로젝트 값은 stack.md에서 읽는다 | 스택이 바뀌어도 고치지 않는다 |
| `.agents/references/` | 조사 기록. 현재 규칙의 근거로 쓰지 않는다 | |
| `.claude/settings.json` | Claude Code hook 등록 | 예 |
| `.claude/agents/`, `.codex/agents/` | 역할 정본에서 생성한 도구별 설정 | 아니오. 생성 스크립트로 만든다 |
| `.claude/skills` | `../.agents/skills`를 가리키는 상대 링크 | 아니오 |
| `.codex/config.toml` | Codex 서브에이전트 사용과 동시 실행 한도 | 예 |

`.claude/settings.local.json` 같은 개인 설정은 Git에서 뺀다. 개인 전역 설정은 바꾸지 않는다.

## 스킬

| 요청 | 스킬 |
|---|---|
| 토픽 시작·재개 | [workflow](skills/workflow/SKILL.md) |
| 설계 논의, 계획, 구현, 마무리 | [workflow-discuss](skills/workflow-discuss/SKILL.md), [workflow-plan](skills/workflow-plan/SKILL.md), [workflow-execute](skills/workflow-execute/SKILL.md), [workflow-ship](skills/workflow-ship/SKILL.md) |
| 코드 리뷰 | [review](skills/review/SKILL.md) |
| 변경 설명 HTML, 결과 보고서 | [explain-change-html](skills/explain-change-html/SKILL.md) |
| context 문서 대조 | [context-update](skills/context-update/SKILL.md) |
| 위키·포스팅 작성, 문서 검수 | [writing](skills/writing/SKILL.md), [doc-review](skills/doc-review/SKILL.md) |
| 위키·포트폴리오 저장소 접근 | [wiki-access](skills/wiki-access/SKILL.md), [portfolio-access](skills/portfolio-access/SKILL.md) |
| 결제 실측·캡처·리포트 | [payment-live-drill](skills/payment-live-drill/SKILL.md) |

스킬 설명(`description`)에는 언제 쓰는지만 짧게 쓰고, 절차와 참고 문서는 스킬 본문에 둔다. 새 스킬이 보이지 않으면 세션을 다시 시작한다.
명시 호출 표기만 도구마다 다르다(Claude Code `/review`, Codex `$review`).

## 역할과 모델

| 역할 | Claude Code | Codex |
|---|---|---|
| implementer | `sonnet`, effort medium | `gpt-6-sol`, medium |
| reviewer | `sonnet`, effort high | `gpt-6-sol`, high, read-only |
| risk-reviewer | `opus`, effort high | `gpt-6-astra`, high, read-only |

값의 정본은 [roles.toml](roles.toml)이다. 프로젝트의 속도·비용·결제 위험을 보고 고른 값이며, 제공자가 달라도 같은 effort가
같은 성능을 뜻하지는 않는다. 메인 모델은 사용자가 고른다.

- 역할을 바꾸려면 `roles.toml`이나 `roles/*.md`를 고치고 `python3 scripts/sync-agent-configs.py`를 실행한다.
  `--check`는 생성 파일이 정본과 다르거나 정본에 없는 생성 파일이 남아 있으면 실패한다. Python 3.11 이상이 필요하다.
- 역할 이름을 받지 않는 Codex 실행 도구에서는 `.codex/agents/<역할>.toml`의 지침을 전달하고 spawn 인자에 모델과
  reasoning effort를 지정한다.
- 지정 모델을 쓸 수 없으면 사용 가능한 메인 모델로 같은 역할을 수행하고 바꾼 사실을 알린다. 사용량·인증·권한 문제가
  반복되면 같은 호출을 반복하지 않는다. 접근 권한, 요금제, 안전 설정을 자동으로 바꾸지 않는다.
- 검토 역할은 파일을 고치지 않는다. Codex의 `read-only` sandbox는 호스트 실행 옵션이 우선할 수 있고, Claude의 Edit·Write 제외도
  Bash로 쓰는 것까지 막지는 않는다. 변경을 만드는 검증은 메인이나 implementer가 한다.
- 동시 실행은 메인 포함 4개를 기본으로 한다. Codex는 `.codex/config.toml`의 한도(세션당 3)를, 런타임 한도가 더 작으면 그것을 따른다.

## 검증

| 명령 | 확인하는 것 |
|---|---|
| `python3 scripts/check-agent-docs.py --strict` | 링크·경로 참조, 스킬·역할 frontmatter, 역할 동기화, context·smoke·quality 문서의 식별자. 인자 없이 실행하면 보고만 하고 항상 0으로 끝난다(CI) |
| `python3 scripts/sync-agent-configs.py --check` | 역할 정본과 생성 파일 일치(CI) |
| `bash scripts/test-hooks.sh` | hook 판정(CI) |

지침이나 스킬을 바꿨으면 대표 요청으로 동작도 확인한다. 오탈자 수정이 workflow를 시작하지 않는지, 리뷰만 요청하면 코드를
고치지 않는지, "브리핑만" 요청에 외부 변경 없이 설명부터 하는지, 재개 요청이 기존 이슈·브랜치를 다시 쓰는지 본다.

## 공식 근거

- [Claude Code가 AGENTS.md를 읽는 조건](https://code.claude.com/docs/en/memory#when-claude-code-reads-agentsmd)
- [Claude 서브에이전트 모델·effort](https://code.claude.com/docs/en/sub-agents), [Claude 모델 alias](https://code.claude.com/docs/en/model-config)
- [Codex 역할 설정·모델·동시 실행](https://learn.chatgpt.com/docs/agent-configuration/subagents), [AGENTS.md 발견과 우선순위](https://learn.chatgpt.com/docs/agent-configuration/agents-md)
- [스킬 발견 경로와 점진적 로딩](https://learn.chatgpt.com/docs/build-skills)
