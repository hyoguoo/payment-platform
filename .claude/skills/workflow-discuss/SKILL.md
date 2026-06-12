---
name: workflow-discuss
description: >
  payment-platform 워크플로우의 discuss 단계를 실행한다.
  사용자가 새 기능/버그/개선의 설계를 논의하거나, "discuss 시작", "설계 논의",
  "어떻게 구현할지 얘기해보자", "방법 고민" 등을 말할 때 이 스킬을 사용한다.
  구현 전에 결정해야 할 사항을 명확히 하는 것이 목적이다.
---

# Discuss 단계

메인 스레드가 인터뷰와 설계를 직접 수행하고, 완료 게이트만 서브에이전트로 격리한다.
공통 원칙(브리핑·정지·게이트 규칙)은 `workflow` 스킬 참조.

## 1. TOPIC 확정

사용자 요청에서 TOPIC(UPPER-KEBAB-CASE) 확정. 불명확하면 `AskUserQuestion`으로 제안·확인.
예: `CHECKOUT-IDEMPOTENCY`, `PAYMENT-RETRY`

## 2. 사전 브리핑 (필수)

`docs/topics/<TOPIC>.md`를 생성하고 상단에 `## 사전 브리핑` 섹션 작성:

1. **현재 이해한 문제** — 1~3줄 (도메인 용어, 메서드명 금지)
2. **현재 시스템 동작** — Mermaid flowchart (as-is, 전체 경로, `workflow` 스킬 브리핑 원칙 준수)
3. **이번 discuss에서 결정하려는 것** — 불릿 3~5개
4. **열린 질문 / 가정** — 불릿

채팅에는 "사전 브리핑을 `docs/topics/<TOPIC>.md` 상단에 작성했습니다. 확인 후 진행/정정 알려주세요." 한 줄만. 사용자 승인 후 다음 단계로.

## 3. 인터뷰 (메인 직접)

사용자의 첫 요청은 빙산의 일각이라고 가정하고, 설계로 넘어가기 전에 모호함을 해소한다.

- **4트랙 ambiguity ledger**: scope(범위) / constraints(제약) / outputs(산출물) / verification(검증) — 네 트랙 모두 최소 1회 커버될 때까지 질문·조사를 계속한다.
- 각 모호함의 해소 경로: **코드 조사**(Read/Grep으로 직접 확인) / **사용자 질문**(AskUserQuestion) / **하이브리드**(조사 결과 제시 + 사용자 판단) / **외부 조사**(WebFetch/Context7).
- 코드·외부 조사가 3연속이면 다음은 반드시 사용자 질문 — 혼자 결론 내리고 달리는 것을 막는다.
- 사용자 답변을 임의로 확장 해석하지 않는다. 확정된 가정은 topic.md에 기록한다.

## 4. 설계 작성 (메인 직접)

`docs/context/ARCHITECTURE.md`(layer 룰)와 관련 소스를 근거로 `docs/topics/<TOPIC>.md`를 작성한다. 설계의 가치는 **삭제·교체 비용**으로 측정된다 — 당장 편한 구조보다 나중에 떼어내기 쉬운 경계를 우선한다.

문서 구조 (해당하는 섹션만):

```markdown
# <주제> 설계

> 최종 수정: YYYY-MM-DD

## 문제 정의
## 영향 범위        ← 변경/신규/무관 레이어·클래스
## 설계 옵션 비교    ← Option A/B + 장단점
## 결정 사항        ← | 항목 | 결정 | 이유 | 테이블 (필수)
## 장애 시나리오와 대응
## 검증 전략
## 제외 범위        ← non-goals + 이유 (필수)
## 참고
```

원칙: port → domain → application → infrastructure → controller 의존 방향 / 포트는 application에, 어댑터는 infrastructure에 / 결제 상태 전이는 domain 엔티티에만 / 구현 세부는 plan 단계로 미룬다 / 벤더 종속 용어(특정 PG사명)를 범용 결정에 쓰지 않는다.

## 5. 게이트 (서브에이전트, 최대 2라운드)

**단일 메시지에서 병렬 dispatch**:

```
Agent(subagent_type="reviewer",      prompt="stage=discuss, topic=<TOPIC>.
  대상: docs/topics/<TOPIC>.md
  체크리스트: .claude/skills/_shared/checklists/discuss-ready.md 의 Gate 섹션
  참고: docs/context/ARCHITECTURE.md")
Agent(subagent_type="domain-expert", prompt="stage=discuss, topic=<TOPIC>.
  대상: docs/topics/<TOPIC>.md
  체크리스트: discuss-ready.md 의 domain risk 섹션 + 리스크 카탈로그 전체")
```

- 둘 다 pass → 6으로. revise/fail → findings를 메인이 topic.md에 반영(필요 시 사용자 확인) 후 재게이트.
- 2라운드 소진 시 `workflow` 스킬의 교착 처리.

## 6. 완료 브리핑

topic.md 상단(사전 브리핑 아래)에 `## 요약 브리핑` 섹션:

1. **결정된 접근** — 2~4줄 (도메인 용어)
2. **변경 후 동작** — Mermaid flowchart (to-be, as-is와 대비 가능한 동일 레벨)
3. **핵심 결정 목록** — 결정 사항 테이블의 키 결정 불릿
4. **트레이드오프 / 후속 작업** — 불릿

채팅에는 위치 안내 한 줄만. 사용자 확인 후 후처리.

## 7. 후처리 (discuss-ready.md Post-phase)

- [ ] GitHub 이슈 생성 (`_shared/conventions/github.md` Step 1)
- [ ] 브랜치 생성: `git checkout -b "#<이슈번호>"`
- [ ] STATE.md stage → `plan`, 이슈/브랜치 기록
- [ ] `docs:` 단일 커밋 (topic.md + STATE.md)

알림: "discuss 완료. 이슈 #<번호>, 브랜치 #<번호>. 다음 단계: plan — 계속 진행할까요?"
