# 프로젝트 컨텍스트 문서

코드만 읽어서는 알기 어려운 프로젝트 지식을 둔다. 작업할 때는 관련 문서만 골라 읽고, workflow-ship의 문서 동기화 단계에서
실제 코드와 달라진 내용을 고친다(절차는 [context-update](../../.agents/skills/context-update/SKILL.md)).

| 문서 | 내용 | 이럴 때 읽는다 |
|---|---|---|
| [ARCHITECTURE.md](ARCHITECTURE.md) | 모듈, 디렉토리, 레이어와 의존 방향, ArchUnit 규칙, 비동기 어댑터 위치, 인프라별 책임, 운영 중인 설계 결정 | 새 클래스·포트를 어디에 둘지, 어떤 결정이 이미 있는지 |
| [PAYMENT-FLOW.md](PAYMENT-FLOW.md) | 브라우저 checkout부터 종결까지의 결제 흐름, 상태와 복구 | 결제 상태·흐름을 바꿀 때 |
| [CONFIRM-FLOW.md](CONFIRM-FLOW.md) | payment-service의 비동기 확정 사이클 상세(발행, 결과 소비, 상태 머신, EOS) | 확정 사이클과 Kafka 경로를 바꿀 때 |
| [INTEGRATIONS.md](INTEGRATIONS.md) | 외부 PG(Toss, NicePay, Fake) 연동, 서비스 사이 HTTP, 회복성 | 벤더 연동이나 HTTP 어댑터를 바꿀 때 |
| [PITFALLS.md](PITFALLS.md) | 실제로 겪은 함정과 수용한 한계 | 상태 전이·동시성·시간·Kafka를 건드리기 전 |
| [TESTING.md](TESTING.md) | 테스트 분류, Fake·Mock 사용, Testcontainers 패턴, JaCoCo 정책 | 테스트를 쓰거나 구성을 바꿀 때 |
| [STACK.md](STACK.md) | 인프라 구성, Compose 파일, 관측·알람, Flyway 운영 | 인프라·설정·마이그레이션을 바꿀 때 |
| [conventions/code-style.md](conventions/code-style.md) | Lombok·Builder, record DTO, 이름, 주석, 안티패턴 | Java 코드를 쓸 때 |
| [conventions/error-logging.md](conventions/error-logging.md) | 예외 계층, LogFmt와 trace, AOP | 예외·로그를 다룰 때 |
| [conventions/transactions.md](conventions/transactions.md) | 트랜잭션과 Hikari, `@Transactional(timeout)` 정책 | 트랜잭션 경계를 바꿀 때 |
| [conventions/kafka.md](conventions/kafka.md) | Consumer group, 재시도·DLQ, 타임아웃 정렬 | Kafka 소비자를 바꿀 때 |

버전·검증 명령·코드 규칙의 정본은 [stack.md](../../.agents/profiles/stack.md), 결제 불변식은 [risk.md](../../.agents/profiles/risk.md)다.
사람이 읽는 결제 흐름 안내는 [docs/guides/payment-flow.md](../guides/payment-flow.md)에 있고, 결제 흐름이 바뀌면 함께 고친다.

## 쓰는 기준

- 문서에 적는 동작은 실제 코드로 확인한 것만 쓴다. 근거는 소스 파일과 줄이며, 다른 문서나 archive를 인용해 사실을 확정하지 않는다.
- 코드에서 바로 알 수 있는 내용(클래스 목록 전체 등)은 적지 않는다. 위치를 정한 이유, 결정과 그 근거, 겪은 함정을 적는다.
- 문서 머리에 갱신 날짜나 갱신 이력을 쓰지 않는다. 이력은 Git과 archive가 가진다. 지난 토픽 이름이나 일정 이름을 근거로 서술하지 않는다.
- 남은 할 일은 [TODOS](../TODOS.md)에 두고 항목 코드로 가리킨다.
- `python3 scripts/check-agent-docs.py --strict`가 문서 속 경로, 클래스 이름, 설정 키가 코드에 있는지 대조한다.
