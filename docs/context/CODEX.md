# Codex·Claude 공통 지침과 스킬 관리

> 최종 갱신: 2026-09-28

## 저장소 구성

- [AGENTS.md](../../AGENTS.md): 자동 로드되는 프로젝트 규칙, 검증 명령, 작업별 문서 진입점.
- [.agents/skills/README.md](../../.agents/skills/README.md): 필요한 작업에서만 읽는 스킬과 공용 자원.
- `.claude/`: Claude Code 전용 설정·훅·생성된 역할 파일.
  Codex에서는 자동 적용된다고 가정하지 않는다.
- `.codex/`: Codex 전용 역할 설정과 동시 실행 한도.
- `.claude/skills` → `../.agents/skills`: 공통 스킬을 참조하는 상대 링크.
  `.claude`·`.codex` 자체는 도구 호환성을 위해 실제 디렉토리로 유지한다.
- [.agents/roles.toml](../../.agents/roles.toml), [.agents/roles/README.md](../../.agents/roles/README.md):
  역할·모델 정본과 공통 배차·보고 규칙. 역할 본문에서 두 도구의 설정 파일을 생성한다.

Codex는 저장소 루트부터 현재 디렉토리까지의 AGENTS 지침을 합성한다. 저장소 스킬은
현재 디렉토리에서 루트까지의 `.agents/skills`에서 발견한다. 스킬 설명에는 언제 적용할지만
짧게 쓰고 상세 절차와 참고 문서는 선택된 작업에서 읽는다. 새 스킬이 보이지 않으면 세션을
다시 시작해 확인한다. 메인 모델은 사용자 선택을 유지하고, 역할별 모델·effort는 공통 정본에 명시한다. 개인 전역 설정은 변경하지 않는다.

## Claude Code와 공유

프로젝트 지침은 `AGENTS.md` 한 곳에서 관리한다. Claude Code v2.1.277 이상은 기본 설정에서
현재·상위 디렉토리에 `CLAUDE.md` 또는 `CLAUDE.local.md`가 없으면 `AGENTS.md`를 읽는다.
세부 예외와 Project instructions 설정은 [Claude 공식 문서](https://code.claude.com/docs/en/memory#when-claude-code-reads-agentsmd)를 따른다.
Claude의 기본 탐색 경로인 `.claude/skills`는 공통 스킬에 연결된다.
스킬과 역할 원본은 `.agents`에서 수정하고 도구별 역할 파일은 자동 생성한다.
`.claude`의 개인 설정 파일 `settings.local.json`은 선택 사항이며 Git에서 제외한다.

## 유지보수 기준

- 반복되는 일반 조언보다 이 프로젝트에서 결정에 영향을 주는 정보를 남긴다.
- 모델·도구 이름, 자동 훅, 서브에이전트 가용성은 현재 세션 기준으로 확인한다.
- 작업 요청에 포함된 승인으로 구현·수정·검증을 이어간다. 단계를 나눴다는 이유로 멈추지 않는다.
- 설계·검토 요청을 구현이나 게시 요청으로 확대하지 않는다. 커밋·외부 게시 범위는 원래 요청을 따른다.
- 테스트는 변경 영향과 실제 위험에 맞춘다. 필수 검사 통과 후 같은 상태의 검사를 반복하지 않는다.
- 과거 작업의 산출물은 이력으로 유지한다. 현재 절차와 다른 역사적 지시를 다시 적용하지 않는다.

## 검증

`python3 scripts/check-agent-docs.py --strict`는 참조·frontmatter·체크리스트·중복 규칙·역할 동기화 오류에
실패 코드를 반환한다. 인자 없는 실행은 기존 CI처럼 정보 보고용이며 항상 0으로 종료한다.
Mermaid 문자와 고아 문서는 계속 참고 정보로 보고한다. 정본과 호환 링크는 실제 경로로 중복 제거한다.
CI는 역할 설정 동기화 검사를 별도로 실행하며, 원본과 생성 파일이 다르면 실패한다.

스킬의 동작도 대표 요청으로 검토한다. 오탈자 수정이 전체 워크플로우를 시작하지 않는지,
리뷰만 요청했을 때 코드를 수정하지 않는지, 구현 요청이 도구 부재나 불필요한 승인 대기로
끝나지 않는지 확인한다. 라이브 드릴 검증 자체가 결제·알림을 실행하지 않도록 범위를 제한한다.

## 역할 설정 변경

모델과 설명은 `.agents/roles.toml`, 역할 본문은 `.agents/roles/`에서 수정한다.
`python3 scripts/sync-agent-configs.py`로 `.claude/agents/*.md`와
`.codex/agents/*.toml`을 생성한다.
같은 본문과 역할 이름을 두 실행 환경에 전달하고, 제공자별 모델·effort·도구 메타데이터만 다르게 적용한다.
생성 파일만 수정하면 정합성 검사가 불일치를 알린다. Python 3.11 이상이 필요하다.

현재 기본값은 구현 Sonnet/Sol medium, 일반 리뷰 Sonnet/Sol high, 도메인 검토 Fable/Astra high다.
이는 프로젝트의 속도·비용·결제 위험을 고려한 선택이며 제공자 간 동등 성능을 의미하지 않는다.
모델 불가·위임 제한 시 처리와 사용자 보고는 공통 배차 규칙을 따른다.

## 공식 근거

- [Codex 역할 설정·모델·동시 실행](https://learn.chatgpt.com/docs/agent-configuration/subagents)
- [Claude 역할 모델·effort](https://code.claude.com/docs/en/sub-agents)
- [Claude 모델 alias](https://code.claude.com/docs/en/model-config)
- [GPT-6 모델 선택](https://developers.openai.com/api/docs/guides/latest-model)
- [AGENTS.md 발견과 우선순위](https://learn.chatgpt.com/docs/agent-configuration/agents-md)
- [스킬 발견 경로와 점진적 로딩](https://learn.chatgpt.com/docs/build-skills)
- [지침과 스킬의 과도한 제약 줄이기](https://developers.openai.com/blog/rethinking-skills-and-prompts-for-gpt-6-astra)

기존 [CLAUDE-5-PROMPTING.md](CLAUDE-5-PROMPTING.md)는 당시 조사 기록이다. 현재 Codex 운영
기준이나 특정 모델 선택의 근거로 사용하지 않는다.
