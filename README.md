# 토스페이먼츠를 통한 결제 연동 시스템

일반적인 주문 시스템에 [토스 페이먼츠](https://www.tosspayments.com/) 결제 연동을 추가한 프로젝트입니다.

<br>

## 프로젝트 목적(업데이트 중)

결제 핵심 로직 및 연동 과정 중 발생할 수 있는 문제점을 파악하고 해결하는 것을 목표로 하였습니다.

- 결제 요청 및 승인 과정 중 데이터 변동 방지를 위해 결제 정보를 검증하여 결제 데이터 정합성 보장 -
  [토스 페이먼츠를 통한 결제 연동 시스템 구현](https://hyoguoo.gitbook.io/tech-log/posts/payment-system-with-toss)

  <img width="70%" alt="image" src="https://github.com/hyoguoo/payment-integration-server/assets/113650170/1f608526-49f7-4c67-80dd-4841d5491c26">

- 트랜잭션 내 외부 API 요청 배제 및 범위 최소화를 통한 안정성 및 성능 향상 -
  [트랜잭션 범위 최소화를 통한 성능 및 안정성 향상](https://hyoguoo.gitbook.io/tech-log/posts/minimize-transaction-scope)

  <img width="70%" alt="image" src="https://github.com/hyoguoo/payment-integration-server/assets/113650170/2e872ca5-ab0d-4417-9567-448bb0b60adf">

- 동시성 이슈 해결을 위한 비관적 락 적용 및 멀티 스레드 테스트를 통한 검증 수행 -
  [멀티 스레드 테스트와 @Transactional](https://hyoguoo.gitbook.io/tech-log/posts/multi-thread-test)

  <img width="70%" alt="image" src="https://github.com/hyoguoo/payment-integration-server/assets/113650170/870e9eeb-3ca2-4a96-9ffe-0253fc6201b6">

- 신뢰성 있는 도메인 로직을 위한 테스트 코드 작성

  <img width="70%" alt="image" src="https://github.com/hyoguoo/payment-integration-server/assets/113650170/434f4486-c199-4c2a-bc93-98751a2751ae">

- 결제 승인 실패 시, 재시도 가능한 요청과 불가능한 요청을 구분하여 처리(작업 완료)
- 재시도 가능한 경우 결제 복구 로직이 수행되어 후속 처리(작업 중)

그 외 [N+1 문제 해결](https://hyoguoo.gitbook.io/tech-log/posts/jpa-n+1)
및 [Cursor 기반 페이징](https://hyoguoo.gitbook.io/tech-log/posts/cursor-based-paging-in-spring-data-jpa) 적용을 통한
성능 개선 진행

<br>

## 기술 스택

- Java 21
- Spring 6.1.12
- Spring Boot 3.3.3
- MySQL 8.0.33

<br>

## 기능 목록

- POST /api/v1/payments/checkout - 주문 요청
- POST /api/v1/payments/confirm - 주문 승인

<br>

## Other Repositories

- [Client Repository](https://github.com/hyoguoo/payment-client)
- [Mock Server Repository](https://github.com/hyoguoo/toss-mock-server)
