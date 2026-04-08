# unstuck-round 프로토콜

라운드가 교착(still_failing 2회 이상)일 때 **관점을 강제 전환**하여
근본 가정을 흔드는 보조 절차. discuss / plan / code 모두에서 재사용된다.

## 트리거
- 직전 라운드 판정 JSON의 `delta.still_failing`이 비어있지 않음 AND
- 현재 라운드 ≥ 2
- 오케스트레이터가 `unstuck_suggestion` 필드를 읽어 관점 하나 선택

## 5가지 관점

| 관점 | 질문 프레임 | 주 사용 단계 |
|---|---|---|
| **contrarian** | "근본 전제가 틀렸다면? 반대 결론이 맞다면 무엇이 달라지나?" | discuss |
| **simplifier** | "이 태스크/요구사항 중 삭제해도 목표가 달성되는 것은? 없어도 되는 것을 남기고 있지 않은가?" | plan |
| **researcher** | "우리가 아직 모르는 것은 무엇인가? 검증되지 않은 가정은?" | code |
| **hacker** | "가장 빠른 편법은? 그 편법의 대가는?" | plan/code |
| **architect** | "현재 설계의 모듈 경계가 올바른 층에 있는가? 포트/어댑터 책임이 섞이지 않았는가?" | discuss/plan |

## Flow
1. 오케스트레이터가 직전 판정의 `unstuck_suggestion` 읽기
   (null이면 still_failing 패턴으로 기본값 선택:
   scope 불명확 → contrarian, 태스크 과다 → simplifier,
   구현 실패 반복 → researcher)
2. 해당 관점 프레임을 **다음 라운드 페르소나 프롬프트 앞에 주입**
3. 페르소나는 해당 관점으로 재검토 수행
4. 결과는 동일한 라운드 판정 문서에 기록하되 `reason_summary`에
   `[unstuck: contrarian]` 태그 prefix

## 상한
- 한 토픽에서 unstuck 주입은 최대 2회
- 2회 주입 후에도 fail → 사용자 에스컬레이션
