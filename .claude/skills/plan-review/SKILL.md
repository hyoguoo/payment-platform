---
name: plan-review
description: >
  구현 계획이나 설계를 엄격하게 검수한다. 워크플로우 외부에서 임의로 호출 가능하며,
  "plan 검토해줘", "[파일경로] 리뷰해줘", "방금 내용 검수해줘", "이 설계 문제 없어?" 등
  파일, 터미널 출력, 인라인 내용 무엇이든 받아 Critic 페르소나로 1라운드 검수 보고서를 출력한다.
  workflow-plan-review와 달리 STATE.md 연동 없이 단독으로 동작한다.
---

# Plan Review (단독 호출)

`plan-ready` 체크리스트 기반 1 라운드 검수. 토론 없음.

---

## 1. 입력 판별

| 상황 | 처리 |
|---|---|
| 파일 경로 지정 (`path/to/PLAN.md`) | 파일 Read |
| "방금 내용", "위 내용" | 직전 대화 내용 사용 |
| 내용 붙여넣기 | 그대로 사용 |
| 미지정 | "어떤 내용을 검수할까요?" 질문 |

컨텍스트가 payment-platform이면 `docs/context/ARCHITECTURE.md`, `TESTING.md`도 참조.

---

## 2. 페르소나 호출 (1 라운드)

- **Critic** (`_shared/personas/critic.md`)
  - 체크리스트: `_shared/checklists/plan-ready.md`
  - 출력: `qa-round.md` 스키마 JSON

- **Domain Expert** (`_shared/personas/domain-expert.md`) — **조건부**
  - 대상이 결제 관련 기능이면 호출
  - 설정/문서 등 무관하면 스킵

## 3. 보고서 형식

```
## Plan Review 결과

### 요약
🔴 critical N건 · 🟡 major N건 · 🟢 minor N건

### 🔴 critical
**C1. <제목>**
위치: ... / 문제: ... / 근거: (왜 discuss 복귀 수준인지)

### 🟡 major
**M1. <제목>**
위치: ... / 문제: ... / 수정 방향: ...

### 🟢 minor
**N1. <제목>**
위치: ... / 제안: ...
```

findings가 없으면 "검수 통과 — 발견된 결함 없음".

---

## 판정 매핑 (Critic → 보고서)

| qa-round severity | 보고서 등급 | 조치 |
|---|---|---|
| critical | 🔴 critical | 논의 복귀 권고 |
| major | 🟡 major | 수정 후 재점검 |
| minor | 🟢 minor | 메모만, 진행 가능 |

critical/major는 반드시 근거(evidence) 필수. "이게 더 나을 것 같다"는 major 아님.
