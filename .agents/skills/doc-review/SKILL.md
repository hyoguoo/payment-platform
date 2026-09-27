---
name: doc-review
description: README, 위키, 포스팅 등 문서의 사실 정확성, 서술 일관성, 독자 이해도를 검수할 때 사용한다. 일반 코드 리뷰에는 사용하지 않는다.
---

# 문서 검수

요청한 문서와 범위를 확인하고 필요한 관점만 적용한다.

- 문체: [writing-style](../_shared/conventions/writing-style.md)의 해당 문서 규칙.
- 용어·식별자: [writing-terminology](../_shared/conventions/writing-terminology.md).
- 표·다이어그램이 있으면 [writing-visuals](../_shared/conventions/writing-visuals.md).
- 기술 정확성: 클래스·설정·상태·수치·코드 예시를 실제 소스와 대조한다.
- 서술: 문제와 결론의 연결, 시간 순서, 중복, 같은 개념의 이름을 확인한다.
- 독자 이해: 필요한 배경 설명과 그림의 참여자·화살표 의미를 확인한다.

문제 위치, 근거, 수정 방향을 영향도 순서로 보고한다. 검수만 요청받았으면 수정하지 않는다.
작성·수정 작업의 일부라면 발견한 문제를 고치고 바뀐 부분을 확인한다. 고정 횟수의 반복 검토나
관점마다 별도 에이전트 호출을 요구하지 않는다. 확인하지 못한 사실은 명시한다.
