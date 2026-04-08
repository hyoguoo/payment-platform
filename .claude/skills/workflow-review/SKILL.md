---
name: workflow-review
description: >
  payment-platform 워크플로우의 review 단계를 실행한다.
  execute 완료 후 "review 시작", "코드 리뷰", "리뷰하고 verify", "verify 전에 리뷰",
  "검토하고 마무리" 등을 말할 때 이 스킬을 사용한다.
  구현 결과물에 대해 페르소나 기반 리뷰를 수행하고, 발견된 항목을 처리한 뒤 verify로 넘어가는 것이 목적이다.
---

# Review 단계 오케스트레이터

`execute` 완료 후 Critic + Domain Expert 1라운드 교차 리뷰 → 항목 처리 → verify 대기.

---

## Step 1 — 페르소나 리뷰 (1 라운드)

`git diff main...HEAD`를 대상으로:

- **Critic**(`_shared/personas/critic.md`) — 아키텍처/컨벤션/테스트 관점
- **Domain Expert**(`_shared/personas/domain-expert.md`) — 결제 도메인 리스크

둘 다 1회만 호출 (토론 없음). 출력:
- `docs/rounds/<topic>/review-critic-1.md`
- `docs/rounds/<topic>/review-domain-1.md`

체크리스트는 `review` 스킬의 항목과 `code-ready.md`의 도메인 섹션을 함께 본다.

---

## Step 2 — 항목 처리

findings의 `severity`별 대응:

**critical** — 항목마다 개별 확인:
```
1. [critical] PaymentEvent.java:34 — ...  suggestion: ...
   → 수정하시겠습니까? (y/n/skip)
```
- `y`: TDD 대상이면 test → impl 순
- `n`: `// REVIEW: intentionally skipped — <이유>` 주석
- `skip`: 다음

**major** — 목록 일괄 표시 후 번호 선택 (`예: 1 3 / all / skip`)

**minor** — 목록만 표시, 요청 시 수정

---

## Step 3 — 추가 요청
"추가로 수정하고 싶은 부분이 있으신가요?" 묻고, 있으면 구현·재확인.

## Step 4 — 커밋 및 재리뷰
수정 있었으면 `refactor: 코드 리뷰 피드백 반영 — <요약>` 커밋 후 Step 1 재실행.
새 critical 없으면 Step 5.

## Step 5 — verify 대기
STATE.md stage → `verify`.
```
## Review 완료
준비가 되면 "verify 시작"이라고 말씀해 주세요.
```
**verify는 자동 시작 금지.** 사용자 명시 요청 필요.
