# CI 통합 테스트 실행 시간 단축 — 완료 브리핑

> 완료: 2026-09-28 · 이슈 [#158](https://github.com/hyoguoo/payment-platform/issues/158) · PR [#159](https://github.com/hyoguoo/payment-platform/pull/159) · 브랜치 `#158`

## 문제와 결과

최근 성공한 PR #157은 CI 완료까지 11분 42초 걸렸다. `payment-service` 통합 테스트가
11분 22초로 최장 경로였고 `pg-service`도 7분 54초 걸렸다. 두 서비스의 통합 테스트를
각각 3개와 2개 runner로 나눴다. PR #159의 첫 실행은 모든 게이트가 통과했고
전체 7분 59초로 끝났다. 경과 시간은 3분 43초, 약 32% 줄었다.

실제 테스트 수는 payment `126+556+36=718`, pg `34+28=62`로 기준 PR과 같다.
shard별 JUnit Check 5개가 모두 게시·통과했고, product/user 통합 테스트와
6서비스 단위 테스트·Checkstyle·SpotBugs·커버리지·문서·훅 게이트도 통과했다.

## 결정과 검증

- 컴파일된 최상위 테스트 클래스의 이름을 SHA-256으로 결정적 분할했다.
  현재 payment 153개, pg 77개 클래스가 빠짐없이 정확히 한 shard에 배정된다.
  JUnit `integration` 태그와 기존 재시도 정책은 유지했다.
- Java 21로 `testClasses`를 컴파일하고, 전체·shard별 `--test-dry-run` JUnit XML을
  케이스별 횟수까지 비교했다. dry-run은 반복·매개변수 호출을 모두 펼치지 않아,
  실제 테스트 건수는 PR CI 로그로 확인했다.
- `actionlint`와 `python3 scripts/check-agent-docs.py --strict`가 통과했다.
- 독립 리뷰에서 dry-run을 전체 테스트 건수의 근거로 과장한 문서 결함 1건을 발견했다.
  계획의 정적 검증 범위와 PR 실측 기준을 분리해 해결했다. 코드 결함은 발견되지 않았다.

runner 사용량의 대가가 있다. payment+pg 통합 job 합계는 기준 실행 약 19분 16초에서
이번 실행 약 26분 3초로 늘었다. 단일 PR 실행으로 확인한 시간 단축이며,
장기 평균이나 runner 사용량 변화는 후속 실행에서 계속 관찰할 수 있다.
