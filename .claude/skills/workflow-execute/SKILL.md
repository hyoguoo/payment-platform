---
name: workflow-execute
description: >
  payment-platform 워크플로우의 execute 단계를 실행한다.
  docs/<TOPIC>-PLAN.md가 존재하고 사용자가 "execute 시작", "구현 시작", "코딩 시작",
  "태스크 실행", "다음 태스크" 등을 말할 때 이 스킬을 사용한다.
  TDD로 태스크를 하나씩 구현하고 커밋하는 것이 목적이다.
---

# Execute 단계

각 태스크를 `implementer` 서브에이전트로 실행하는 오케스트레이터. **메인 스레드에서 TDD 사이클·코드 수정·테스트 실행을 직접 수행하지 않는다.**

## 상태 로드

1. `docs/STATE.md` → 활성 태스크
2. `docs/<TOPIC>-PLAN.md` → 태스크 내용 + `tdd` / `domain_risk` 플래그

## 태스크 루프

태스크당 **implementer 1회 dispatch**:

```
Agent(subagent_type="implementer", prompt="모드 1 — PLAN 태스크 실행.
  topic=<TOPIC>, task_id=<id>, tdd=<bool>.
  plan: docs/<TOPIC>-PLAN.md / state: docs/STATE.md")
```

- implementer가 내부에서 TDD 사이클 + `./gradlew test` + 커밋 + PLAN/STATE 갱신까지 수행한다.
- 메인은 반환된 커밋 해시·테스트 결과만 확인하고 다음 태스크로 진행한다.
- **execute 중 리뷰 판정 없음** — reviewer/domain-expert는 ship 단계에서 diff 전체를 일괄 리뷰한다.
- 태스크 사이마다 사용자 확인을 받지 않는다. 사용자가 "이슈 없는 경우 계속 진행"을 지시했으면 Rule 2 에스컬레이션이 없는 한 연속 dispatch한다.

## Deviation 처리

- **Rule 1 (자동 수정)**: 컴파일 오류·깨진 import·명백한 버그는 implementer가 내부에서 수정하고 커밋에 기재한다. 메인이 가로채 수정하지 않는다.
- **Rule 2 (중단 후 확인)**: DB 스키마 / 레이어 경계 / build.gradle / 포트 시그니처 / 활성화 조건 변경이 필요하면 implementer가 중단·보고한다. 메인은 사용자 확인 후 결정 내용을 담아 재dispatch한다.

상세 기준은 `implementer` 에이전트 정의 참조.

## 마지막 태스크

- implementer가 마지막 GREEN 커밋 안에서 STATE.md stage → `ship` 전환 (별도 커밋 없음)
- 알림: "execute 완료. 다음 단계: ship (리뷰 + 마무리) — 계속 진행할까요?"

## 세션 중단 시

STATE.md 재개 메모(완료/남은 태스크, 주의사항, 재개 방법) 작성 + 현재 WIP를 `wip:` 커밋에 함께 포함.
