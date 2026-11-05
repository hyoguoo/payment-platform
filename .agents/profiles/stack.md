# 스택 프로필: Java · Spring Boot · Gradle 멀티모듈

검증 명령과 코드 규칙의 정본이다. 스택이 바뀌면 이 파일과 `config/`의 도구 설정만 바꾼다.
`.agents/hooks/`의 스크립트는 스택과 무관하며 아래 "검증 hook 설정" 블록만 읽는다.

| 항목 | 버전 |
|---|---|
| Java | 21 (Gradle 툴체인) |
| Gradle | 8.14.4 (Wrapper) |
| Spring Boot | 3.4.4 |
| Spring Cloud | 2024.0.0 |
| Checkstyle | 10.17.0 |
| ArchUnit | 1.3.0 |

버전의 정의처는 루트 `build.gradle`(플러그인, `ext.checkstyleVersion`), 서비스별 `build.gradle`(`springCloudVersion`),
`gradle/wrapper/gradle-wrapper.properties`다. 버전을 올리면 이 표도 함께 고친다.

## 모듈

서비스 6개로 된 Gradle 멀티모듈이다. 루트 패키지는 `com.hyoguoo.paymentplatform.<서비스>`다.
커밋 scope는 아래 표의 scope 또는 [Git 규칙](../conventions/git.md)의 `docs`·`build`·`infra`·`deps`를 쓴다.

| 모듈 | 경로 | scope | 설명 |
|---|---|---|---|
| payment-service | `payment-service/` | `payment` | 주문 접수, 결제 상태, 재고 선차감(Redis), 비동기 확정 |
| pg-service | `pg-service/` | `pg` | PG 벤더 호출, 승인 결과 수신·중복 판정 |
| product-service | `product-service/` | `product` | 상품·재고 원장 |
| user-service | `user-service/` | `user` | 사용자 |
| gateway | `gateway/` | `gateway` | 진입점 라우팅 |
| eureka-server | `eureka-server/` | `eureka` | 서비스 탐색 |

## 검증 순서

위에서부터 실행한다. 명령의 `<module>`은 위 모듈 이름이다.

| 단계 | 명령 | 언제 | 누가 |
|---|---|---|---|
| 단위 테스트 | `./gradlew :<module>:test` | 코드 변경이 있으면 항상. ArchUnit 레이어 규칙도 여기서 돈다. Docker 없이 돈다 | 종료 hook이 강제. 에이전트도 태스크마다 실행 |
| 정적 분석 | `./gradlew :<module>:checkstyleMain :<module>:checkstyleTest :<module>:spotbugsMain :<module>:spotbugsTest` | Java 변경이 있으면. CI 기준은 `.github/workflows/_service-ci.yml` | 에이전트. 파일 단위 Checkstyle은 편집 hook이 바로 잡는다 |
| 통합 테스트 | `./gradlew :<module>:integrationTest` | DB, Redis, Kafka, Spring 설정·배선을 바꿨으면. `@Tag("integration")` 테스트만 돈다. Docker(Testcontainers)가 필요하다 | 에이전트 |
| 스모크 | `bash scripts/smoke-all.sh` (트레이스까지 보려면 `--with-trace`) | Compose, Kafka 토픽, 관측·알람 규칙 같은 런타임 설정을 바꿨으면. 띄운 로컬 스택이 필요하다. 개별 가이드는 [docs/smoke/](../../docs/smoke/) | 에이전트. 스택을 띄울 수 없으면 사유를 보고한다 |
| 재실행 | 위 명령에 `--rerun-tasks` | 외부 상태 때문에 캐시된 결과를 믿을 수 없을 때. 통합 테스트가 `UP-TO-DATE`로 건너뛰어졌을 때 | 에이전트 |

공유 빌드 설정(`build.gradle`, `config/`)이나 서비스 경계를 바꾸면 영향받는 모듈로 범위를 넓힌다.
문서만 바뀌면 Java 테스트는 생략하고, 지침 문서는 `python3 scripts/check-agent-docs.py --strict`로 확인한다.

## 검증 hook 설정

`.agents/hooks/`의 스크립트가 아래 블록만 읽는다. 한 줄에 `KEY=값` 하나, 값은 따옴표 없이 그대로 쓴다.
블록이 없으면 hook은 아무것도 검사하지 않고 통과시킨다.

```verify-config
BASE_BRANCH=main
MODULES=payment-service pg-service product-service user-service gateway eureka-server
ROOT_FILES=build.gradle settings.gradle gradle.properties gradle/ config/
VERIFY_PATTERN=\.(java|sql|gradle|ya?ml|properties)$
UNIT_TEST=./gradlew --console=plain {tasks}
UNIT_TEST_TASK=:{m}:test
TEST_REPORTS={m}/build/test-results/test/*.xml
LINT_FILE_PATTERN=\.java$
LINT_FILE=config/checkstyle/check-file.sh {file}
```

| 키 | 뜻 |
|---|---|
| `BASE_BRANCH` | 검증 기준선. 이 브랜치와의 분기점 이후 바뀐 파일을 본다. 없으면 `main` |
| `MODULES` | 비우면 단일 모듈이다. 검증 대상 변경이 있으면 `UNIT_TEST`를 그대로 실행한다. 멀티모듈이면 모듈 경로를 공백으로 구분해 적는다 |
| `ROOT_FILES` | 멀티모듈 전용. 이 접두사로 시작하는 파일이 바뀌면 전 모듈을 검증한다 (예: `build.gradle settings.gradle gradle/ config/`) |
| `VERIFY_PATTERN` | 종료 검증을 일으키는 파일(정규식). 문서만 바뀐 턴은 검증하지 않는다 |
| `UNIT_TEST` | 단위 테스트 명령. 멀티모듈이면 `{tasks}` 자리에 모듈별 태스크가 들어간다 (예: `./gradlew --console=plain {tasks}`) |
| `UNIT_TEST_TASK` | 멀티모듈 전용. 모듈마다 `{m}`을 모듈 경로로 채운다 (예: `:{m}:test`) |
| `TEST_REPORTS` | 실패 테스트 이름을 뽑을 JUnit XML 위치. 멀티모듈이면 `{m}`을 쓸 수 있다. 없으면 빌드 출력 끝부분을 보여 준다 |
| `LINT_FILE_PATTERN`, `LINT_FILE` | 편집 직후 파일 하나를 검사하는 명령. `{file}`은 절대 경로다. 종료 코드 0 위반 없음, 1 위반 있음, 그 밖은 도구 실패(막지 않음) |

hook의 동작과 일시정지 규칙은 [AGENTS.md의 검증 hook](../../AGENTS.md#검증-hook)에 있다.

## 코드 규칙

- 명시적 타입 선언을 쓴다. `var`를 쓰지 않는다(Checkstyle `RegexpSinglelineJava`로 강제).
- 의존성은 생성자로 주입한다(`@RequiredArgsConstructor` + `private final`). `@Autowired`를 쓰지 않는다.
- 레이어는 `domain`, `application`(유스케이스와 포트), `infrastructure`(어댑터), `presentation`(컨트롤러)이다.
  배치와 의존 방향은 [docs/context/ARCHITECTURE.md](../../docs/context/ARCHITECTURE.md)를 따른다.
- 레이어 규칙의 정의처는 `config/archunit/` 한 곳이며 서비스들이 공유한다. 규칙은 기존 코드의 위반을 모두 정리한 뒤에만 추가한다.
- 테스트 이름은 한국어 `@DisplayName`이나 한국어 메서드 이름으로 시나리오를 쓴다. 기존 테스트의 Fake·Mock 사용 방식을 따른다.
- 컨테이너(Testcontainers)를 띄우는 테스트는 `@Tag("integration")`를 붙인다. 단위 테스트(`test`)는 Docker 없이 돌아야 한다.
- 그 밖의 규칙(Lombok, 예외·로그, Kafka, 트랜잭션)은 [docs/context/conventions/](../../docs/context/conventions/)의 주제별 문서를 따른다.
