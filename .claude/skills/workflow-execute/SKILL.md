---
name: workflow-execute
description: >
  payment-platform 워크플로우의 execute 단계를 실행한다.
  docs/<TOPIC>-PLAN.md가 존재하고 사용자가 "execute 시작", "구현 시작", "코딩 시작",
  "태스크 실행", "다음 태스크" 등을 말할 때 이 스킬을 사용한다.
  TDD로 태스크를 하나씩 구현하고 커밋하는 것이 목적이다.
---

# Execute 단계 오케스트레이터

각 태스크마다 `code-round` 프로토콜을 실행하는 얇은 오케스트레이터.

---

## 상태 로드
1. `docs/STATE.md` → 활성 태스크 확인
2. `docs/<TOPIC>-PLAN.md` → 태스크 내용 + `tdd` / `domain_risk` 플래그
3. 관련 소스 파일

---

## 태스크 루프

각 태스크에 대해 `.claude/skills/_shared/protocols/code-round.md` 수행:

1. **Implementer**(`_shared/personas/implementer.md`)
   - `tdd=true`: RED → GREEN → REFACTOR
   - `tdd=false`: 단일 산출물 + 테스트 확인
   - PLAN.md 체크박스 + 완료 결과 + STATE.md 갱신 → GREEN 커밋에 포함

2. **Verifier**(`_shared/personas/verifier.md`)
   - `./gradlew test` 실행, 결과 파싱 (결정론적)
   - fail 시 Implementer에게 피드백

3. **Critic**(`_shared/personas/critic.md`)
   - `code-ready.md` 체크리스트 판정
   - 출력: `docs/rounds/<topic>/code-<task-id>-critic-<N>.md`

4. **Domain Expert**(`_shared/personas/domain-expert.md`) — **조건부**
   - 해당 태스크 `domain_risk=true`일 때만 호출
   - 출력: `docs/rounds/<topic>/code-<task-id>-domain-<N>.md`

5. Round 2 fail 시 `unstuck-round.md`의 researcher 관점 주입
6. Round 3 소진 시 사용자 에스컬레이션

커밋 규칙은 `commit-round.md` 준수 (amend 금지, 명시 staging, 한글 본문).

---

## Deviation Rules
- Rule 1 (자동 수정): 컴파일 오류, 깨진 import, 명백한 버그 → 커밋 메시지에 기재
- Rule 2 (중단 후 확인): DB 스키마 변경, 레이어 경계 위반, build.gradle 변경

## Analysis Paralysis Guard
Read/Grep/Glob 5회 연속 + 코드 0줄 → 멈추고 "지금 정보로 작성" vs "블로킹 보고" 선택.

---

## 마지막 태스크 특별 처리
- 마지막 GREEN 커밋 안에서 STATE.md stage → `review` 전환
- 별도 커밋 없음
- 알림: "execute 완료. review 단계로 넘어갑니다."

## 세션 중단 시
현재 WIP `wip:` 커밋 + `docs/.continue-here.md` 작성 후 함께 커밋.
