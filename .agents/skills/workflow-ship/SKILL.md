---
name: workflow-ship
description: payment-platform의 완료된 토픽을 검토·검증하고 문서와 아카이브를 정리할 때 사용한다. 단독 코드 리뷰는 review, PR 생성만 요청하면 issue-commit-pr을 사용한다.
---

# 토픽 마무리

[workflow](../workflow/SKILL.md)의 요청 범위를 따른다. STATE·PLAN과 실제 변경 범위를 확인한다.
브랜치 diff는 기준 브랜치와 비교하고, 미커밋·미추적 파일도 빠뜨리지 않는다.

## 검토와 검증

[review](../review/SKILL.md)로 변경과 관련 호출 경로를 검토한다. 마무리 요청에 포함된 명백한
결함은 수정하고 PLAN의 리뷰 처리에 근거를 남긴다. 요구 변경이나 의도적 미해결은 구분한다.
재검증은 수정된 동작과 아직 확인하지 않은 위험에 집중한다.

[ship-ready](../_shared/checklists/ship-ready.md)의 해당 항목을 확인한다. 테스트·린트 범위는
[AGENTS.md](../../../AGENTS.md)를 따른다. 이미 통과한 검사는 변경과 환경이 유효한지 확인해 재사용한다.
런타임 설정 변경은 관련 `docs/smoke/` 가이드로 확인한다. 실제 장애 주입은 요청 범위와 대상 환경이
확인된 경우에 수행한다. 환경 제약으로 못 한 검사는 사유와 남은 위험을 기록한다.

변경 설명 HTML을 요청받았거나 기존 계획의 산출물에 포함된 경우에만
[explain-diff-html](../explain-diff-html/SKILL.md)을 사용한다.

## 문서와 완료 기록

[context-update](../context-update/SKILL.md)로 영향받은 문서를 동기화한다. 결제 흐름이 바뀌면
`docs/context/PAYMENT-FLOW-GUIDE.md`도 함께 확인한다.

`docs/archive/<topic>/COMPLETION-BRIEFING.md`에 문제와 결과, 주요 결정, 실제 검증 근거,
리뷰 처리와 미결 사항을 기록한다. 존재하는 설계·PLAN을 같은 디렉토리로 옮기고 링크를 갱신한다.
추적 중인 파일은 `git mv`를 사용할 수 있다. `docs/archive/README.md`에 완료 항목을 추가한다.
STATE의 해당 활성 토픽을 종료하고 최근 완료 링크를 남기며, 다른 작업 메모는 보존한다.

커밋·푸시·PR이 요청 범위에 있으면 [issue-commit-pr](../issue-commit-pr/SKILL.md)로 진행한다.
완료 보고에는 결과, 검증, 아카이브 위치, 존재하는 경우 PR·설명 페이지를 전달한다.
