---
name: workflow-review
description: >
  payment-platform 워크플로우의 review 단계를 실행한다.
  execute 완료 후 "review 시작", "코드 리뷰", "리뷰하고 verify", "verify 전에 리뷰",
  "검토하고 마무리" 등을 말할 때 이 스킬을 사용한다.
  구현 결과물에 대해 코드 리뷰를 수행하고, 발견된 항목을 개발자와 함께 하나씩 처리한 뒤 verify로 넘어가는 것이 목적이다.
---

# Review 단계 가이드

execute 완료 후 품질 점검 → 개발자와 함께 항목 처리 → verify 전환의 흐름이다.

---

## Step 1 — 코드 리뷰 실행

`review` 스킬을 적용해 `git diff main...HEAD` 기준으로 리뷰를 수행한다.
출력 형식과 체크리스트는 `review` 스킬을 따른다.

---

## Step 2 — 항목 처리

리뷰 결과를 개발자와 함께 처리한다. 심각도별로 대응이 다르다.

**CRITICAL** — 항목마다 개별 확인한다:
```
1. [CRITICAL] PaymentEvent.java:34 — ...  Fix: ...
   → 수정하시겠습니까? (y/n/skip)
```
- `y`: 즉시 수정. TDD 대상이면 test → impl 순서로.
- `n`: 의도적 스킵. `// REVIEW: intentionally skipped — <이유>` 주석 추가 여부를 물어본다.
- `skip`: 다음 항목으로 넘어간다.

**WARNING** — 목록을 한 번에 보여주고 수정할 번호를 선택받는다:
```
수정할 항목 번호를 입력하세요 (예: 1 3 / all / skip):
```

**INFO** — 목록만 표시. 개발자가 원하면 수정 요청 가능.

---

## Step 3 — 개발자 추가 요청

항목 처리 후 항상 물어본다:
```
추가로 수정하고 싶은 부분이 있으신가요?
```
요청이 있으면 구현하고 다시 확인한다. 없으면 Step 4로 넘어간다.

---

## Step 4 — 커밋 및 재리뷰

수정이 있었다면:
```bash
git add <수정된 파일들>
git commit -m "refactor: 코드 리뷰 피드백 반영 — <요약>"
```
커밋 후 Step 1을 재실행해 새로운 CRITICAL이 없는지 확인한다. 없으면 Step 5로 진행한다.

수정이 없었다면 바로 Step 5로 넘어간다.

---

## Step 5 — verify 전환

STATE.md를 갱신하고 `workflow-verify` 스킬을 실행한다:
```markdown
- **단계**: verify
- [x] review
- [ ] verify
```
