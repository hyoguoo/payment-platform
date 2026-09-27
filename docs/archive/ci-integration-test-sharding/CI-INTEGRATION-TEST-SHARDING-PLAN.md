# CI 통합 테스트 분할 계획

관련 설계: [CI-INTEGRATION-TEST-SHARDING.md](CI-INTEGRATION-TEST-SHARDING.md)

이슈: [#158](https://github.com/hyoguoo/payment-platform/issues/158)

## 목표

`payment-service`와 `pg-service` 통합 테스트를 runner별로 분할하면서 기존 테스트와
게이트를 빠짐없이 유지하고, PR 실행에서 시간 단축을 확인한다.

## 태스크

- [x] **T1 — 병목 측정** (`tdd=false`, `domain_risk=false`): 최근 성공한 PR의 job·step
  시작/종료 시간을 확인한다. 완료 기준은 최장 경로와 개선 대상 서비스를 수치로 특정하는 것이다.
  결과: PR #157의 payment 통합 job 약 11분 22초, pg 약 7분 54초.
- [x] **T2 — 자동 shard 배정과 CI 연결** (`tdd=false`, `domain_risk=false`):
  `scripts/ci-integration-shard.py`, `.github/workflows/ci.yml`,
  `.github/workflows/_service-ci.yml`에서 컴파일 클래스 배정·matrix·shard별 JUnit Check를 구현한다.
  완료 기준은 새 클래스 자동 배정, 각 shard 실패 독립 보고, 다른 서비스의 1-shard 경로 보존이다.
  결과: payment 3개, pg 2개 runner로 구성. `fail-fast: false`와 빈 배정 실패 처리.
- [x] **T3 — 정적 분할 범위 검증** (`tdd=false`, `domain_risk=false`):
  `actionlint`와 Java 21 `testClasses`를 실행하고, 컴파일된 최상위 클래스의 shard 배정이
  전수·배타적인지 확인한다. 전체·shard별 `--test-dry-run` JUnit XML의 케이스별 횟수도 비교한다.
  결과: actionlint 통과, payment 153개·pg 77개 클래스 전수 배정. dry-run XML은
  payment 86건(85개 고유 이름), pg 16건이며 shard 합계와 각 이름의 횟수가 전체 실행과 같다.
  dry-run은 반복·매개변수 테스트 호출을 모두 펼치지 않아 실제 전체 건수는 T4에서 검증한다.
- [x] **T4 — GitHub PR 실측** (`tdd=false`, `domain_risk=false`):
  브랜치를 게시하고 PR CI에서 모든 shard·기존 게이트·JUnit Check를 확인한다.
  payment 718건·pg 62건의 최근 기준 실행과 실제 테스트 건수를 비교한다.
  기준 PR #157과 전체 경과 시간을 비교해 실제 개선 폭을 기록한다.
  결과: PR #159 실행 [36353575253](https://github.com/hyoguoo/payment-platform/actions/runs/36353575253)에서
  모든 job·shard·JUnit Check 통과. payment 126+556+36=718건, pg 34+28=62건.
  전체 7분 59초로 기준 PR #157의 11분 42초보다 3분 43초(약 32%) 단축됐다.
- [x] **T5 — 문서 동기화와 마무리** (`tdd=false`, `domain_risk=false`):
  `docs/context/STACK.md`의 CI 토폴로지를 코드와 맞추고 문서 검사를 실행한다.
  리뷰·검증 결과를 완료 브리핑에 기록하고 설계·계획을 아카이브한다.
  결과: `STACK.md`·`TESTING.md` 동기화, 엄격 문서 검사 문제 0건, 설계·계획·브리핑 아카이브.

## 리뷰 처리

- T2/T3의 독립 리뷰: dry-run이 반복·매개변수 테스트를 펼치지 않아 전체 실제 건수의
  증거로 부족하다는 지적을 반영해 T3 완료 기준과 T4 검증을 분리했다.
- 실제 PR 실행에서 두 서비스의 전체 테스트 건수와 모든 JUnit Check 통과를 확인했다.
