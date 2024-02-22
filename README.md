# 토스페이먼츠를 통한 결제 연동 시스템

일반적인 주문 시스템에 [토스 페이먼츠](https://www.tosspayments.com/) 결제 연동을 추가한 프로젝트입니다.

<br>

## 프로젝트 목적

결제 진행에 사용되는 도메인은 간단하게 하거나 생략하고, 결제 핵심 로직 및 연동 과정 중 발생할 수 있는 문제점을 파악하고 해결하는 것을 목표로 하였습니다.

- 결제 요청 및 승인 과정 중 결제 정보 검증 로직을 통해 결제 요청 전후 데이터 정합성 보장 -
  [토스 페이먼츠를 통한 결제 연동 시스템 구현](https://hyoguoo.gitbook.io/tech-log/payment_system_with_toss)

  <img width="70%" alt="image" src="https://github.com/hyoguoo/payment-integration-server/assets/113650170/1f608526-49f7-4c67-80dd-4841d5491c26">

- 외부 API 요청과 트랜잭션 분리를 통한 안정성 및 성능 향상 -
  [트랜잭션 범위 최소화를 통한 성능 및 안정성 향상](https://hyoguoo.gitbook.io/tech-log/minimize_transaction_scope)

  <img width="70%" alt="image" src="https://github.com/hyoguoo/payment-integration-server/assets/113650170/2e872ca5-ab0d-4417-9567-448bb0b60adf">

- 비관적 락을 통한 재고 데이터 정합성 보장 및 멀티 스레드 테스트 -
  [멀티 스레드 테스트와 @Transactional](https://hyoguoo.gitbook.io/tech-log/multi_thread_test)

  <img width="70%" alt="image" src="https://github.com/hyoguoo/payment-integration-server/assets/113650170/870e9eeb-3ca2-4a96-9ffe-0253fc6201b6">

- 테스트 코드 작성을 통한 도메인 로직 검증 및 안정성 보장

  <img width="70%" alt="image" src="https://github.com/hyoguoo/payment-integration-server/assets/113650170/434f4486-c199-4c2a-bc93-98751a2751ae">

그 외 [N+1 문제 해결](https://hyoguoo.gitbook.io/tech-log/jpa_n+1)
및 [Cursor 기반 페이징](https://hyoguoo.gitbook.io/tech-log/cursor_based_paging_in_spring_data_jpa) 적용을 통한
성능 개선 진행

<br>

## 기술 스택

- Java 17
- Spring 6.0.13
- Spring Boot 3.1.5
- MySQL 8.0.33

<br>

## ERD

<img width="50%" alt="image" src="https://github.com/hyoguoo/payment-integration-server/assets/113650170/0d99d27e-3392-48d0-82cf-27f27b7bd6bf">

<br>

## 기능 목록

- API
    - POST /api/v1/orders/create - 주문 요청
    - POST /api/v1/orders/confirm - 주문 승인
- View
    - GET /order - 주문 상세 조회
    - GET /order/{id} - 주문 리스트 조회
    - POST /order/cancel - 주문 취소

<br>

## Other Repositories

- [Client Repository](https://github.com/hyoguoo/payment-widget-client)
- [Mock Server Repository](https://github.com/hyoguoo/toss-mock-server)
