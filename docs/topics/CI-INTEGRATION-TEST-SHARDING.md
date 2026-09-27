# CI 통합 테스트 분할

## 문제와 목표

최근 성공한 PR CI 두 건(#157, #156)은 각각 약 11분 42초, 12분 18초 걸렸다.
두 실행에서 `payment-service` 통합 테스트 job이 약 11분으로 완료 시간을 결정했고,
`pg-service` 통합 테스트 job도 약 8분 걸렸다. Gradle 준비·컴파일은 약 2분이며,
나머지 대부분은 단일 runner의 테스트 실행 시간이다.

목표는 결제 정합성 테스트를 줄이지 않고 PR CI의 경과 시간을 단축하는 것이다.
변경 범위는 `.github/workflows/ci.yml`, 재사용 워크플로우,
통합 테스트 클래스 분할 스크립트다. 서비스 코드와 테스트 자체의 동작은 바꾸지 않는다.

## 결정

- `payment-service`는 3개, `pg-service`는 2개 독립 runner에서 통합 테스트를 실행한다.
  기존 `product-service`와 `user-service`는 각각 1개 runner를 유지한다.
- 각 runner가 `testClasses`를 컴파일한 뒤 최상위 `.class`의 완전한 클래스 이름을
  SHA-256으로 한 shard에 배정한다. 중첩 테스트 클래스는 바깥 클래스와 함께 실행한다.
  Gradle `--tests`로 클래스를 고르고, 기존 JUnit `integration` 태그가 최종 테스트 범위를 정한다.
- shard별 job과 JUnit Check가 각각 실패를 보고한다. `fail-fast: false`로 다른 shard의
  결과도 수집한다. 클래스 목록을 찾지 못하거나 비어 있으면 명시적으로 실패한다.
- Testcontainers 재사용을 CI에서 활성화하지 않는다. 같은 job에서 DB/Flyway 상태가
  오염되어 컨텍스트 로드가 실패한 이력이 있기 때문이다.

단일 runner의 `maxParallelForks` 확대는 동일 Docker 자원과 Spring 컨텍스트를 경쟁시키며
격리 위험이 있다. 고정 클래스 목록을 수작업으로 관리하면 새 통합 테스트가 빠질 수 있다.
독립 runner와 컴파일 산출물 기반 자동 배정이 두 위험을 피한다. runner 사용량 증가는
실제 PR 실행 시간을 측정해 판단한다.

## 실패 경로와 성공 기준

- 한 shard의 테스트·컴파일 실패는 해당 job 실패로 드러나고 다른 shard는 계속 실행한다.
- 새 최상위 테스트 클래스는 코드 수정 없이 정확히 한 shard에 포함된다.
- 모든 컴파일된 최상위 테스트 클래스가 정확히 한 shard에 배정된다.
  실제 실행에서도 전체 통합 테스트 건수와 통과 결과가 기준 실행과 일치한다.
- GitHub Actions 문법 검사와 PR의 모든 기존 게이트를 통과한다.
- PR CI의 실제 경과 시간을 기준 PR #157(약 11분 42초)과 비교한다.
  최종 단축 폭은 GitHub Actions 실측으로만 확정한다.

## 검증 계획

Java 21로 테스트 클래스를 컴파일한 뒤 전체 클래스 이름의 배정이 전수·배타적인지 확인한다.
`--test-dry-run`의 JUnit XML을 전체 실행과 shard별 실행에서 횟수까지 비교하고
`actionlint`와 문서 검사를 실행한다. dry-run은 반복·매개변수 테스트의 실제 호출을
모두 펼치지 않으므로, PR에서 실제 테스트 건수·모든 shard 통과·JUnit Check 게시와
전체 CI 시간을 확인한다.
