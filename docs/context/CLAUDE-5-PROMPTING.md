# Claude 5 세대 프롬프팅 · 컨텍스트 엔지니어링 정리

Anthropic 공식 자료 2건을 정리한 참고 문서다.

- Claude Opus 5 프롬프팅 가이드 — 모델별 동작 차이와 조정이 필요한 프롬프팅 패턴
- The new rules of context engineering for Claude 5 generation models (2026-07-24, Thariq Shihipar) — Claude Code 시스템 프롬프트 80% 이상 삭감에서 얻은 교훈

---

## 1부. Opus 5 프롬프팅 패턴

기존 Opus 4.8 프롬프트는 수정 없이도 동작한다. 아래는 조정이 가장 자주 필요한 동작들이다.

### Opus 4.8 대비 개선 지점

| 영역 | 달라진 점 | 프롬프팅 함의 |
|:---:|:---:|:---:|
| 에이전트 코딩 | 다중 파일 기능·대규모 리팩토링·엔드투엔드 작업에서 스텁이나 플레이스홀더를 남기지 않음 | 처음부터 완전한 작업 명세를 주고 실행을 맡길 때 성능이 가장 좋음 |
| 코드 리뷰 | 패스당 실제 버그 검출률이 높고, 추가 발견분도 대부분 거짓 양성이 아님 | "심각도 높은 것만", "보수적으로"를 문자 그대로 따라 과소 보고 — 전부 보고시키고 별도 패스에서 필터 |
| effort 효율 | `low`/`medium`이 적은 토큰·지연으로 강한 품질 | 기본값 `high`에서 시작해 평가로 조정, 까다로운 작업만 `xhigh` |
| 비전 | 차트·문서·다이어그램 이해와 UI 시각적 복제에 강함 | 구버전용 비전 우회 프롬프트 재검증 — 불필요할 수 있음 |
| 긴 컨텍스트 | 1M 토큰이 기본값이자 최대값, 윈도우 전체에서 지시 따르기가 일관 | 후반 배치에 의존하던 리마인더 축소 가능 |
| 다중 에이전트 | 작성자-검증자 패턴을 잘 쓰고 서로의 작업을 덮어쓰는 경우가 적음 | 비용 민감 워크로드는 위임 상한을 명시 |

### 응답 길이

기본 응답이 이전 Opus보다 길다. effort는 사고량을 조절하고 가시 응답 길이는 조절하지 않으므로, 길이는 프롬프트로 직접 지시한다.

```text
Keep responses focused, brief, and concise. Keep disclaimers and caveats short, and spend most of the response on the main answer. When asked to explain something, give a high-level summary unless an in-depth explanation is specifically requested.
```

긴 시스템 프롬프트라면 끝부분에 짧은 리마인더를 함께 둔다.

```text
<tone_preference>
Keep outputs reasonably concise.
</tone_preference>
```

### 작업 중 진행 상황 내레이션

작업 예고가 많고 메시지당 출력이 길다. 원하는 빈도와 형태를 명시하면 줄어든다. 하지 말라는 지시보다 원하는 스타일의 긍정 예시가 더 잘 먹힌다.

```text
Before your first tool call, say in one sentence what you're about to do. While working, give a brief update only when you find something important or change direction. When you finish, lead with the outcome: your first sentence should answer "what happened" or "what did you find," with supporting detail after it for readers who want it.
```

### 작성 산출물 길이

디스크에 쓰는 보고서·Markdown·요약도 길어진다. 문서 산출물이 제품에 포함되면 길이 보정을 넣는다.

```text
Match the length of written documents to what the task needs: cover the substance, but do not pad with filler sections, redundant summaries, or boilerplate.
```

### 작업 범위와 과잉 검증

지시 없이도 스스로 검증하므로, 명시적 검증 지시("모든 작업에 최종 검증 단계를 포함", "서브에이전트로 검증")는 제거한다 — 과잉 검증을 유발하고, 빼도 품질이 떨어지지 않는다. 별도 검증 단계를 붙이는 레거시 하네스 스캐폴딩도 같다.

범위를 스스로 넓히는 경향도 있어, 좁은 작업은 범위를 못박는다.

```text
Deliver what was asked, at the scope intended. Make routine judgment calls yourself, and check in only when different readings of the request would lead to materially different work. If the request seems mistaken or a better approach exists, say so in a sentence and continue with the task as asked rather than quietly narrowing, widening, or transforming it. Finish the whole task, and stop short of actions that are clearly beyond what was asked.
```

### 서브에이전트 생성 제어

이전 모델보다 위임에 적극적이다. 규모가 크고 진짜 독립적인 트랙에서는 효과가 있지만, 작은 작업에 붙으면 비용과 시간이 배가된다. 위임 조건을 명시하거나 에이전트 수에 결정론적 상한을 둔다.

```text
Delegate to a subagent only for large tasks that are genuinely independent and parallelizable, such as a wide multi-file investigation. Do not delegate work you can finish yourself in a handful of tool calls, and do not use subagents to verify or double-check your own work. If one subagent can complete the task, use one rather than several, and keep spawn counts low.
```

### 자체 수정

프롬프팅 없이도 자기 실수를 잘 잡는다. "답변을 다시 확인하라", "응답 전 재검증하라"는 이미 하는 동작과 중첩돼 비용만 늘린다.

수정 내레이션은 이전 모델보다 많아, 사용자 대상 제품에서는 제한하는 편이 낫다.

```text
Only correct an earlier statement when the error would change the user's code, conclusions, or decisions. State corrections plainly and briefly, then continue the task. For slips that change nothing for the user, make the fix and move on without noting it.
```

### 사고 비활성화 시 출력 아티팩트

Opus 5는 사고가 기본 활성이고, 비활성화는 effort `high` 이하에서만 가능하다. 비활성화하면 두 아티팩트가 간헐적으로 나타난다.

- 텍스트로 새는 도구 호출: 구조화된 `tool_use` 블록 대신 사용자 대상 텍스트에 호출을 적음 — 호출은 실행되지 않고, 에이전트 루프에서는 그 텍스트가 대화 기록에 남아 이후 턴에 영향
- 내부 XML 태그 노출: `<thinking>` 등이 가시 응답에 섞임 — 사고하지 말라는 시스템 프롬프트 규칙이 유출을 늘리므로 제거

1차 완화책은 비활성화 대신 사고를 켠 채 effort를 낮추는 것이다. 대부분의 작업에서 `low` effort + 사고 활성이 비슷한 비용으로 사고 비활성화보다 낫다. 반드시 비활성화해야 하면 아래 한 지시로 두 아티팩트를 함께 누른다. 사고 태그를 이름으로 지목하는 지시는 일반형보다 덜 효과적이다.

```text
When you use a tool, you may say a brief sentence first. If no tool can express what the user asked for, say so instead of guessing. Do not include internal or system XML tags in your response.
```

---

## 2부. 컨텍스트 엔지니어링 새 규칙

프롬프트는 모델이 받는 컨텍스트의 일부일 뿐이다. 시스템 프롬프트·Skills·CLAUDE.md·메모리에서 조립되는 나머지가 결과를 크게 좌우한다. 컨텍스트는 여러 요청에 두루 쓰이므로 프롬프트만큼 구체적일 수 없다.

Anthropic은 Opus 5·Fable 5용 Claude Code 시스템 프롬프트에서 80% 이상을 삭제했고, 코딩 평가에서 측정 가능한 손실이 없었다.

### 과잉 제약이 문제였다

내부 사용 트랜스크립트에서 한 요청 안에 상충하는 지시가 여럿 관측됐다 — "적절히 문서화하라"와 "주석을 달지 마라"가 시스템 프롬프트·스킬·사용자 요청 사이에서 충돌한다. Claude는 의도를 읽어 답에 도달하지만, 그 전에 겹치고 어긋나는 지시를 두고 더 오래 판단해야 한다.

이 제약들은 최악의 시나리오를 막으려고 필요했던 것이고, 지금은 상당수를 지우고 모델의 판단과 주변 컨텍스트에 맡길 수 있다. Claude Code에 메모리·아티팩트·스킬이 생겨, CLAUDE.md 하나가 기억·정보·지침을 다 지던 구조도 끝났다.

### Then → Now

| 항목 | Then | Now |
|:---:|:---:|:---:|
| 지침 | 규칙을 준다 | 판단에 맡긴다 |
| 도구 사용법 | 예시를 준다 | 인터페이스를 설계한다 |
| 배치 | 전부 앞단에 넣는다 | 점진적 공개 |
| 반복 | 같은 지시를 반복한다 | 단순한 도구 설명 |
| 기억 | CLAUDE.md에 저장 | 자동 메모리 |
| 스펙 | 단순 명세 | 풍부한 참조 |

**규칙 → 판단.** 예전 시스템 프롬프트는 "주석을 쓰지 않는 것을 기본으로 하고, 여러 단락 docstring이나 여러 줄 주석 블록을 절대 쓰지 말라"까지 못박았다. 그러나 사용자가 다른 취향일 수도, 복잡한 코드에는 여러 줄 주석이 필요할 수도 있어 일부 프롬프트에서는 이 지침이 오답이었다. 구버전에서는 이 트레이드오프를 감수해야 했고, 지금은 "주변 코드처럼 읽히는 코드를 써라 — 주석 밀도·네이밍·관용구를 맞춰라"로 대체됐다.

**예시 → 인터페이스 설계.** 도구 사용의 1순위 규칙이 예시 제공이었는데, 최신 모델에서는 예시가 오히려 탐색 공간을 좁힌다. 예시 대신 도구·스크립트·파일의 설계를 고민한다 — 어떤 파라미터를 주고, 어떻게 더 표현력 있게 만들지. Todo 도구는 status를 pending / in_progress / completed 열거형으로 두는 것만으로 사용법을 암시하고, in_progress 항목을 하나로 유지하라는 지시가 원하는 동작을 정의한다.

**앞단 일괄 → 점진적 공개.** 코드 리뷰·검증 상세는 항상 필요하진 않지만 필요할 때는 결정적이라 시스템 프롬프트에 있었고, 지금은 각각 스킬로 빠져 필요할 때만 호출된다. 도구도 같은 방식으로 지연 로딩을 쓴다 — 에이전트가 ToolSearch로 전체 정의를 찾기 전까지 컨텍스트를 먹지 않아, 도구를 더 많이 둘 수 있다. CLAUDE.md와 SKILL.md도 마찬가지로, 알려진 모든 관행을 한 파일에 모으는 대신 적시에 로드되는 파일 트리로 구성한다.

**반복 → 도구 설명.** 예전 모델은 지시 반복이 필요했고 컨텍스트 앞보다 뒤의 지시를 더 잘 들었다. 반복 예시를 지우고, 도구 사용법은 시스템 프롬프트가 아니라 도구 설명에 둔다.

**CLAUDE.md 기억 → 자동 메모리.** `#` 핫키로 CLAUDE.md에 적어 두는 방식을 권했지만, 지금은 작업과 사용자에게 관련된 내용을 Claude가 자동으로 메모리에 저장한다.

**단순 스펙 → 풍부한 참조.** 플랜 모드는 마크다운 플랜 파일에 기대 왔고, 코드베이스에 스펙을 두는 관행도 같은 계열이었다. 지금은 더 복잡한 참조를 다룬다 — 아티팩트로 만든 HTML, 상세 테스트 스위트, 다른 코드베이스의 이식 대상 함수도 스펙이 된다. 루브릭도 참조의 한 형태로, 특정 분야의 취향(좋은 API 설계란 무엇인가)을 검증자 에이전트에 실어 확인하게 한다.

### 계층별 작성 지침

- 시스템 프롬프트: 제품 컨텍스트에 밀착 — 어떤 제품에서 무엇을 하는지 알려주는 자리. Claude Code 사용자는 손댈 일이 없고, 직접 에이전트 하네스를 만든다면 시간을 많이 쓸 곳
- CLAUDE.md: 가볍게 유지하고 저장소의 목적은 짧게 — 토큰 대부분은 코드베이스의 함정에 쓴다. 파일 시스템이나 저장소를 보면 알 수 있는 뻔한 내용은 넣지 않음
- Skills: 필요할 때 정보를 찾게 하는 가벼운 안내서 — 아주 중요한 영역을 빼면 과잉 제약을 피하고, 길어지면 여러 파일로 쪼갠다. 나·팀·제품에 고유한 의견과 지식을 담을 때 가치가 가장 큼
- References: `@` 멘션으로 파일을 참조로 붙임 — 스펙 파일, 목업, 코드베이스 전체까지. Claude가 잘 아는 언어인 코드 형태를 우선하며, 디자인은 설명이나 스크린샷보다 HTML 목업이 결과가 좋음

검증 지침이 여러 개라면 검증 스킬로 만들어 CLAUDE.md에서 참조하는 식으로 점진적 공개를 적극 쓴다.

### claude doctor

시스템 프롬프트·스킬·CLAUDE.md 단순화를 돕는 명령이다. Claude Code에서 `/doctor`로 실행하면 스킬과 CLAUDE.md 크기를 적정화한다.

---

## 출처

- Claude 공식 문서 "Claude Opus 5 프롬프팅" — 함께 참조: "Claude Opus 5의 새로운 기능"(모델 기능·API 변경), "프롬프팅 모범 사례"(전 모델 공통 기법), "마이그레이션 가이드"(Opus 4.8 → 5)
- Anthropic 블로그 "The new rules of context engineering for Claude 5 generation models" — 2026-07-24, Thariq Shihipar
