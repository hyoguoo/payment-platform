# 토스페이먼츠를 통한 결제 연동 시스템

## 프로젝트 개요

이 프로젝트는 토스페이먼츠를 통해 결제 연동을 처리하고, 다양한 결제 시나리오에서 발생할 수 있는 문제들을 해결하기 위한 시스템을 구축하는 것을 목표로 합니다.  
결제 승인 및 검증 프로세스를 다루고, 재시도 가능한 오류 처리와 결제 상태 관리를 통해 결제 시스템의 안정성을 중점으로 두고 있습니다.

<br>

## 주요 해결 과제

- 데이터 정합성 문제 해결: 결제 요청 및 승인 과정에서 악의적인 사용자에 의한 데이터 불일치를 방지하기 위해 결제 데이터 검증
- API 지연 및 서버 중단 상황 해결: API 지연 / 서버 중단 / 외부 서비스 에러 등 예외 상황에 대응하기 위한 복구 로직을 추가하여 안정적인 결제 처리 환경을 구축
- 성능 최적화: 외부 API 요청을 트랜잭션 범위에서 분리하여 트랜잭션 경합을 줄여 성능 최적화

<br>

## 핵심 구현 및 주요 기능

결제 핵심 로직 및 연동 과정 중 발생할 수 있는 문제점을 파악하고 해결하는 것을 목표로 하였습니다.

- 결제 데이터 검증을 통한 데이터 정합성 보장: 결제 요청 및 승인 과정에서 결제 정보를 검증하여 데이터 정합성을 유지 -
  [토스 페이먼츠를 통한 결제 연동 시스템 구현](https://hyoguoo.gitbook.io/tech-log/posts/payment-system-with-toss)

  <img width="70%" alt="image" src="https://github.com/hyoguoo/payment-integration-server/assets/113650170/1f608526-49f7-4c67-80dd-4841d5491c26">

- 재시도 가능한 에러 처리 및 복구 로직 적용: API 지연 / 서버 중단 / 외부 서비스 에러 등 상황에서 발생하는 결제 오류를 복구할 수 있는 상태 관리 및 재시도 로직 적용 -
  [결제 상태 전환 관리와 재시도 로직을 통한 결제 복구 시스템 구축](https://hyoguoo.gitbook.io/tech-log/posts/payment-status-with-retry)

  <img width="70%" alt="image" src="https://github.com/user-attachments/assets/970b33de-eac9-4f9c-85f7-ac22a5a769e8">

- 외부 의존성을 제어한 테스트 환경에서의 시나리오 검증: 결제 승인 과정 중 발생할 수 있는 다양한 시나리오 및 예외 상황을 테스트할 수 있도록 외부 의존성을 제어한 테스트 환경을 구축 -
  [외부 의존성 제어를 통한 결제 프로세스 다양한 시나리오 검증](https://hyoguoo.gitbook.io/tech-log/posts/payment-system-test)

  <img width="70%" alt="image" src="https://github.com/user-attachments/assets/3bb72ac9-b8ae-4629-b799-6546a7ee9640">

- 트랜잭션 범위 최소화를 통한 성능 및 응답 시간 최적화: 외부 API 요청을 트랜잭션 범위 밖으로 분리하고, 트랜잭션 범위를 최소화하여 성능을 최적화 -
  [트랜잭션 범위 최소화를 통한 성능 및 안정성 향상](https://hyoguoo.gitbook.io/tech-log/posts/minimize-transaction-scope)

  <img width="70%" alt="image" src="https://github.com/hyoguoo/payment-integration-server/assets/113650170/2e872ca5-ab0d-4417-9567-448bb0b60adf">

<br>

## 기술 스택

- Java 21
- Spring 6.1.12
- Spring Boot 3.3.3
- MySQL 8.0.33

<br>

## 기능 목록

- POST /api/v1/payments/checkout - 주문 요청
    - 주문 요청을 처리하며, 상품 및 사용자 정보를 받아 결제 준비
- POST /api/v1/payments/confirm - 주문 승인
    - 결제 승인을 처리하며, 결제 상태를 업데이트하고 승인 결과 반환

<br>

## 프로젝트 패키지 구조 및 의존성 관리

테스트하기 좋은 구조와 도메인 간 결합을 최소화하는 것을 목표로 설계되었습니다.

- 테스트 용이성: 각 계층에서 의존성 역전을 적용하여, 이를 통해 테스트 더블을 쉽게 사용할 수 있도록 설계
- 도메인 간 결합 최소화: 도메인 간 협력은 인터페이스와 Receiver 구현체를 통해 결합도를 낮추어, 도메인의 독립성을 유지

<img width="70%" alt="image" src="https://github.com/user-attachments/assets/b5c16562-f6a1-4886-93cc-3274b2f35ded">

```text
├── application                     // 애플리케이션 레이어: 비즈니스 로직을 처리하는 계층
│   ├── PaymentConfirmServiceImpl.java
│   ├── dto
│   ├── port
│   │   ├── PaymentEventRepository.java
│   │   └── ProductPort.java
│   └── usecase                     // 유즈케이스: 애플리케이션의 특정 비즈니스 흐름을 처리하는 단위 로직
│       └── PaymentProcessorUseCase.java
├── domain                          // 도메인 레이어: 핵심 비즈니스 로직 및 엔터티 정의
│   └── PaymentEvent.java
├── exception
├── infrastructure                  // 인프라스트럭처 레이어: 실제 데이터베이스나 외부 시스템과 상호작용하는 구현체를 포함
│   ├── entity                      // 엔터티: DB와 매핑되는 데이터베이스 테이블을 나타내는 클래스
│   │   └── PaymentEventEntity.java
│   ├── internal                    // 내부 서비스 구현: 서버 내부 도메인과 상호작용하는 실제 구현체들
│   │   └── InternalProductPort.java
│   └── repostitory                 // 리포지토리: 데이터베이스 접근 계층 구현체
│       ├── JpaPaymentEventRepository.java 
│       └── PaymentEventRepositoryImpl.java
└── presentation                    // 프레젠테이션 레이어: 외부 요청을 처리하고 응답을 반환하는 계층
    ├── PaymentController.java
    ├── dto
    └── port
        └── PaymentConfirmService.java
```

## Other Repositories

- [Client Repository](https://github.com/hyoguoo/payment-client)
