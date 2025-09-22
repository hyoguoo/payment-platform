# Payments Platform

이 프로젝트는 토스페이먼츠를 통해 결제 연동을 처리하고, 다양한 결제 시나리오에서 발생할 수 있는 문제들을 해결하기 위한 시스템을 구축하는 것을 목표로 합니다.  
외부 PG 연동 시 발생할 수 있는 오류와 위변조 시도를 방지하고, 상태 기반 복구 모델을 통해 복원력 있는 결제 흐름을 구현한 백엔드 시스템입니다.

<br>

## 🚀 주요 해결 과제

- 정합성 오류 및 위변조 요청 방지를 위한 교차 검증 체계 설계: 결제 요청 및 승인 과정에서 악의적인 사용자에 의한 데이터 불일치를 방지하기 위해 결제 데이터 검증
- 일시적 실패에 대응 가능한 상태 기반 결제 복구 모델 설계: API 지연 / 서버 중단 / 외부 서비스 에러 등 예외 상황에 대응하기 위한 복구 로직을 추가하여 안정적인 결제 처리 환경을 구축
- 트랜잭션 범위 최소화로 네트워크 지연 환경 대응 및 응답 시간 최적화: 외부 API 요청을 트랜잭션 범위에서 분리하여 트랜잭션 경합을 줄여 성능 최적화

<br>

## 🔑 핵심 구현 및 주요 기능

결제 핵심 로직 및 연동 과정 중 발생할 수 있는 문제점을 파악하고 해결하는 것을 목표로 하였습니다.

### 결제 데이터 검증을 통한 데이터 정합성 보장

- 클라이언트가 주문 생성부터 승인까지 처리하는 방식르로, 중간 값 조작 같은 위변조 가능성 존재
- 서버 주도의 흐름으로 전환하고, 클라이언트·서버·PG 응답값을 교차 검증하여 불일치 시 결제를 거부하도록 설계
- 링크: [토스 페이먼츠를 통한 결제 연동 시스템 구현](https://hyoguoo.gitbook.io/tech-log/posts/payment-system-with-toss)

<img width="80%" alt="image" src="https://github.com/user-attachments/assets/53355caa-456f-4dbd-b56e-5c08fc4251ff">

### 재시도 가능한 에러 처리 및 복구 로직 적용

- API 지연, 서버 중단 등 외부 오류 발생 시 결제가 실패로 종료되어 복구할 수 없는 문제가 존재
- 상태 기반 전환 모델을 정의하고, 재시도 가능한 오류에 대해 자동 복구 흐름 적용
- 링크: [결제 상태 전환 관리와 재시도 로직을 통한 결제 복구 시스템 구축](https://hyoguoo.gitbook.io/tech-log/posts/payment-status-with-retry)

<img width="80%" alt="image" src="https://github.com/user-attachments/assets/dc7f28b7-5f9e-4d0e-90c6-d355da6d1216">

### 결제 흐름 추적을 위한 모니터링 체계 구축

- 승인 지연, 재시도 등 여러 단계를 거치는 복잡한 흐름에서 요청 단위 로그만으로는 전체 흐름 파악과 이슈 대응이 어려움
- 구조화된 로깅(traceId/orderId 기반)과 AOP 기반 결제 정보 변동 저장, Admin 페이지를 통해 결제 흐름의 가시성 확보
- 링크: [Logger 성능 저하 방지와 구조화된 로깅 설계](https://hyoguoo.gitbook.io/tech-log/posts/log-structure-and-performance) / [결제 이력 추적 시스템 구현](https://hyoguoo.gitbook.io/tech-log/posts/payment-history-with-aop)

<img width="80%" alt="image" src="https://github.com/user-attachments/assets/0cbabcf6-7164-4d09-a969-ab5ad604c678">

### 트랜잭션 범위 최소화를 통한 성능 및 응답 시간 최적화

- 외부 API 호출이 포함된 단일 트랜잭션 구조로 인해 커넥션 점유와 응답 지연 문제가 발생
- 외부 호출을 트랜잭션 외부로 분리하고 보상 트랜잭션을 적용해 안정성과 성능을 함께 확보
- 링크: [트랜잭션 범위 최소화를 통한 성능 및 안정성 향상](https://hyoguoo.gitbook.io/tech-log/posts/minimize-transaction-scope)

<img width="80%" alt="image" src="https://github.com/user-attachments/assets/ff19dac9-a717-4b5d-96e9-de60d199e10a">

### 외부 의존성을 제어한 테스트 환경에서의 시나리오 검증

- 외부 API에 의존하는 구조로 인해 다양한 예외 상황에 대한 테스트가 어려움 존재
- Fake 객체 기반의 테스트 환경을 구성하여 승인 실패, 지연, 중복 요청 등 다양한 시나리오를 유연하게 검증
- 링크: [외부 의존성 제어를 통한 결제 프로세스 다양한 시나리오 검증](https://hyoguoo.gitbook.io/tech-log/posts/payment-system-test)

<img width="80%" alt="image" src="https://github.com/user-attachments/assets/3bb72ac9-b8ae-4629-b799-6546a7ee9640">

<br>

### 🛠 사용 기술 스택

- Java 21
- Spring 6.1.12
- Spring Boot 3.3.3
- MySQL 8.0.33
- Junit 5

<br>

## 🧩 기능 목록

- POST /api/v1/payments/checkout - 주문 요청
    - 주문 요청을 처리하며, 상품 및 사용자 정보를 받아 결제 준비
- POST /api/v1/payments/confirm - 주문 승인
    - 결제 승인을 처리하며, 결제 상태를 업데이트하고 승인 결과 반환

<br>

## 🏗 프로젝트 패키지 구조 및 의존성 관리

테스트 용이성과 도메인 독립성을 중심으로 포트/어댑터 구조 기반으로 설계하였습니다.

- 테스트 용이성: 각 계층에서 의존성 역전을 적용하여, 이를 통해 테스트 더블을 쉽게 사용할 수 있도록 설계
- 도메인 독립성: 도메인 간 협력은 인터페이스와 Receiver 구현체를 통해 결합도를 낮추어, 도메인의 독립성을 유지

<img width="100%" alt="image" src="https://github.com/user-attachments/assets/26cb69e5-6c89-479e-8181-4dd6a13c5eb5">

```text
├── application
│   ├── PaymentConfirmServiceImpl.java
│   ├── port
│   │   ├── PaymentEventRepository.java
│   │   └── ProductPort.java
│   └── usecase
│       └── PaymentProcessorUseCase.java
├── domain
│   └── PaymentEvent.java
├── infrastructure
│   ├── entity
│   │   └── PaymentEventEntity.java
│   ├── internal
│   │   └── InternalProductAdapter.java
│   └── repository
│       ├── JpaPaymentEventRepository.java 
│       └── PaymentEventRepositoryImpl.java
└── presentation
    └── PaymentController.java
```

|        영역        | 설명                                                                   |
|:----------------:|:---------------------------------------------------------------------|
|  `application`   | 주요 비즈니스 로직을 구현하는 계층                                                  |
|  `ServiceImpl`   | 유즈케이스 단위를 조합하거나 흐름에 따라 실행 순서를 제어하는 서비스 구현체로, 유즈케이스를 오케스트레이션하여 서비스 제공 |
|      `port`      | 도메인 외부와의 의존성을 인터페이스로 추상화한 계층                                         |
|    `usecase`     | 하나의 기능 단위를 책임지는 유즈케이스 로직을 정의하여, 도메인 로직을 조합하고 실행                      |
|     `domain`     | 핵심 비즈니스 규칙과 엔터티를 정의하며, 외부 기술에 독립적인 순수한 로직을 수행                        |
| `infrastructure` | 실제 외부 시스템 또는 DB와 연동 계층                                               |
|     `entity`     | 데이터베이스 테이블과 매핑되는 JPA 엔터티 클래스                                         |
|    `internal`    | 외부 도메인과의 협력이 필요한 경우, 해당 요청을 전달하고 실행하는 계층 (예: 결제 도메인에서 상품 정보 조회)      |
|   `repository`   | 영속성 처리 로직을 담당하는 실제 JPA Repository 구현체                                |
|  `presentation`  | 외부 요청을 처리하고 응답을 반환하는 컨트롤러 계층                                         |

## Other Repositories

- [Client Repository](https://github.com/hyoguoo/payment-client)
