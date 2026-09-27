---
name: workflow-discuss
description: payment-platform의 새 토픽에 대해 문제, 설계 대안, 장애 대응, 검증 기준을 정리할 때 사용한다.
---

# 설계 논의

공통 진행 범위와 상태 기록은 [workflow](../workflow/SKILL.md)를 따른다.
사용자 요청과 관련 코드를 확인해 토픽 이름을 UPPER-KEBAB-CASE로 정한다.
파일명 같은 일상적 선택은 직접 하고, 목표·제약·동작의 불명확한 부분만 질문한다.

`docs/topics/<TOPIC>.md`에 문제, 영향 범위, 결정과 근거, 필요한 대안 비교,
장애 시나리오, 관찰 가능한 성공 기준, 검증 계획을 기록한다. 내용 없는 섹션은 만들지 않는다.
아키텍처 변경이면 `docs/context/ARCHITECTURE.md`에서 실제 포트 위치와 의존 방향을 확인한다.
결제 상태 변경이면 정상·중복·실패·복구 경로를 코드와 대조하고 필요한 전이도를 작성한다.

[discuss-ready](../_shared/checklists/discuss-ready.md)의 적용 항목으로 설계를 검토한다.
사용자 판단이 필요한 미결 사항은 결정된 사실과 구분한다. 설계가 끝나면 STATE의 다음 단계와
문서 경로를 갱신하고 핵심 결정·미결 사항을 보고한다. 이슈·브랜치·커밋은 요청 범위에 있을 때
[GitHub 규칙](../_shared/conventions/github.md)과 [커밋 규칙](../_shared/conventions/commit.md)을 따른다.
