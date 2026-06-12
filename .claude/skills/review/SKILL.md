---
name: review
description: >
  payment-platform 프로젝트의 변경 사항을 구조적으로 코드 리뷰한다.
  "리뷰", "코드 리뷰", "review", "check my changes", "뭐 문제 없어?", "looks good?" 등
  변경 사항 검토를 요청할 때 반드시 이 스킬을 사용한다.
  캐주얼한 요청이더라도 Reviewer + Domain Expert 서브에이전트로 1라운드 구조적 리뷰를 실행한다.
---

# Code Review (단독 호출)

워크플로우 외부에서도 호출 가능한 단독 리뷰. **1라운드만 수행** (토론 없음).
워크플로우 내부에서는 `workflow-ship` Phase A가 같은 패턴을 사용한다.

## 1. 리뷰 대상 파악

`git status` + `git diff HEAD`(또는 사용자가 지정한 범위: 특정 커밋, `main...HEAD` 등)로 변경 범위 확정. 메인은 범위만 정하고 **내용 판정은 하지 않는다**.

## 2. 서브에이전트 dispatch (단일 메시지 병렬)

```
Agent(subagent_type="reviewer",      prompt="stage=standalone.
  대상: <diff 범위>
  체크리스트: .claude/skills/_shared/checklists/code-ready.md (task execution 섹션 제외)
  참고: docs/context/PITFALLS.md")
Agent(subagent_type="domain-expert", prompt="stage=standalone.
  대상: <diff 범위>
  체크리스트: code-ready.md 의 domain risk 섹션 + 리스크 카탈로그 전체")
```

## 3. 결과 보고

두 에이전트의 findings를 합쳐 severity 순으로 보고:

```
[critical] path/to/File.java:42 — 한 문장 설명
  이유: ... / 수정: ...

[major] ...
[minor] ...

---
요약: critical N건, major N건, minor N건
판정: PASS (critical 0건) | FAIL (critical N건 — 머지 전 반드시 수정)
```

## 4. 수정

사용자가 수정을 요청하면: 워크플로우 작업 중이면 implementer dispatch(`workflow-ship` A3 패턴), 일반 맥락이면 메인이 직접 수정해도 된다.
