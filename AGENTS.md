# Payment Platform

결제 도메인 학습용 Java 21 / Spring Boot 멀티모듈 프로젝트다. 비즈니스 서비스는
`payment-service`, `pg-service`, `product-service`, `user-service`이며 `gateway`,
`eureka-server`가 진입점과 서비스 탐색을 담당한다. Gradle Wrapper를 사용한다.

## 작업 원칙

- 한국어로 간결하게 설명한다. 태스크 ID를 언급할 때 작업 내용을 함께 적는다.
- 요청과 관련된 코드와 문서만 읽는다. 실제 코드·빌드 설정을 현재 동작의 근거로 삼는다.
- 일반 수정은 바로 수행한다. 토픽 설계·계획·재개 요청에는 `.agents/skills/workflow/SKILL.md`를 사용한다.
  `docs/STATE.md`의 활성 토픽이 있다는 이유만으로 무관한 작업을 워크플로우에 편입하지 않는다.
- 사용자 요청으로 승인된 구현·수정·검증을 이어서 완료한다. 단계 전환이나 파일 종류만으로
  재승인을 요구하지 않는다. 범위나 제품 동작을 결정할 정보가 부족할 때 구체적으로 질문한다.
- 기존 사용자 변경을 보존한다. 범위 밖 문제는 결과 보고 또는 `docs/context/TODOS.md`에 남긴다.
- 공통 스킬·역할 원본은 `.agents/`에서 관리한다. `.claude/skills`는 공통 스킬을 가리키는 링크다.
  `.claude`와 `.codex`는 도구별 실행 설정과 자동 생성된 역할 파일을 담는 실제 디렉토리다.
  도구·서브에이전트·훅의 가용성은 현재 세션에서 확인하고, 특정 모델이나 도구명을 가정하지 않는다.

## 협업 방식

두 도구 모두 아래 기준에 따라 필요한 서브에이전트를 사용한다. 역할별 사용을 매번 사용자에게
요청하도록 돌리지 않는다. 적용되는 상위 지침과 현재 세션의 도구·동시 실행 한도는 준수한다.

- 작은 수정은 메인이 직접 수행한다. 분리 가능한 구현 묶음은 `implementer`에게 맡긴다.
- 실질적인 설계·코드 리뷰와 토픽 완료 검토는 `reviewer`에게 독립적으로 맡긴다.
- 결제 상태·멱등성·보상·동시성·PG 실패 경로 변경은 `domain-expert`도 함께 검토한다.
- 메인은 결과를 통합하고 수정·검증·사용자 보고를 책임진다. 단계별 재승인을 반복하지 않는다.
- 위임 방법, 모델 선택, 역할별 공통 지침은 [.agents/roles/README.md](.agents/roles/README.md)를 따른다.
  서브에이전트를 쓸 수 없으면 같은 기준으로 직접 처리하고, 독립 검토 미실시를 보고한다.

## 구현과 검증

- 레이어와 포트 배치는 `docs/context/ARCHITECTURE.md` 및 기존 코드 패턴을 따른다.
  상태 전이·멱등성·보상·트랜잭션 경계를 바꾸면 관련 실패 경로도 확인한다.
- 행동 변경과 버그 수정은 `docs/context/conventions/testing.md`의 테스트 우선 흐름을 따른다.
  문구·문서·단순 설정 변경에 형식적인 테스트를 추가하지 않는다.
- 변경 모듈의 단위 테스트부터 실행한다. 예: `./gradlew :payment-service:test`.
  공유 빌드·공통 규칙·서비스 경계를 바꾸면 영향받는 모듈로 검증 범위를 넓힌다.
- Java 변경은 해당 모듈의 `checkstyleMain checkstyleTest spotbugsMain spotbugsTest`도 실행한다
  (각 태스크에 `:<module>:` 접두사 사용). CI 기준은 `.github/workflows/_service-ci.yml`이다.
- DB·Kafka·Spring 배선 변경은 해당 서비스의 `integrationTest`로 확인한다.
  통합 테스트는 Docker/Testcontainers가 필요하고 `test`에 포함되지 않는다.
  외부 상태 변경으로 재실행이 필요한 경우 `--rerun-tasks`를 사용한다.
- 지침·스킬·컨텍스트 문서 변경은 `python3 scripts/check-agent-docs.py --strict`로 확인한다.
  역할·모델 변경 후에는 `python3 scripts/sync-agent-configs.py`로 두 도구 설정을 동기화한다.
  문서만 바뀌면 Java 전체 테스트는 생략한다.
- `.claude/settings.json`의 훅은 Claude Code용이다. Codex 검증을 대신하지 않는다.
  실행한 검사와 결과, 실행하지 못한 검사는 완료 보고에 구분해 적는다.

## 작업별 참고

| 작업 | 참고 문서 |
|---|---|
| 구조·모듈·레이어 | `docs/context/ARCHITECTURE.md`, `docs/context/STRUCTURE.md` |
| 결제 흐름·상태·복구 | `docs/context/PAYMENT-FLOW.md`, `docs/context/PITFALLS.md` |
| 비동기 confirm·Kafka | `docs/context/CONFIRM-FLOW.md`, `docs/context/conventions/kafka.md` |
| Java 구현 | `docs/context/CONVENTIONS.md`에서 해당 주제만 선택 |
| 테스트·Fake·Mock | `docs/context/TESTING.md`, `docs/context/conventions/testing.md` |
| 외부 PG 연동 | `docs/context/INTEGRATIONS.md` |
| 빌드·인프라·Flyway | `docs/context/STACK.md`, `docs/context/stack/flyway-operations.md` |
| 헬스체크·트레이스·알람 | `docs/smoke/`의 해당 가이드 |
| 작업 재개·과거 결정 | `docs/STATE.md`, 해당 `docs/archive/<topic>/COMPLETION-BRIEFING.md` |
| 지침·스킬 정비 | `docs/context/CODEX.md`, `.agents/skills/README.md` |

`docs/context/PAYMENT-FLOW-GUIDE.md`는 사람용 설명 문서다. 안내문 갱신 요청이나 결제 흐름
변경의 문서 동기화에 사용한다. 과거 완료 이력은 `docs/archive/`에 보관한다.

## Git

커밋·푸시·PR은 요청 범위에 포함될 때 실행한다. 세부 규칙은
`.agents/skills/_shared/conventions/commit.md`와 `.agents/skills/_shared/conventions/github.md`를 따른다.
커밋 제목은 `<type>(<scope>): <한글 제목>`이며 scope는 서비스명 또는
`docs` / `build` / `infra` / `deps`를 사용한다. 한 범위로 묶이지 않으면 생략한다.

## Code Review Rules

- 중복 결제·메시지 재배달·재시도·부분 실패에서 돈과 재고 정합성이 깨지는 경로를 우선 확인한다.
- PG의 이미 처리된 응답은 주문·금액·상태 검증 없이 성공으로 간주하지 않는다.
- 상태 전이의 불변식, 보상 멱등성, 로그의 민감정보 노출을 확인한다.
- 구체적 재현 조건과 파일 위치가 있는 결함을 보고한다. 스타일은 정적 분석 결과를 활용한다.
