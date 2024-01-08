# 토스페이먼츠를 통한 결제 연동 시스템

일반적인 주문 시스템에 [토스 페이먼츠](https://www.tosspayments.com/) 결제 연동을 추가한 프로젝트

- 서버에서 결제 요청과 승인 과정 사이에서 결제 정보를 검증하여 신뢰성 있는 결제 서비스 제공
- 결제 연동과 결제 정보 검증의 로직 중심
- 결제 진행에 사용되는 도메인은 간단하게 하거나 생략
- 그 외에 테스트 코드 작성을 통한 코드 신뢰성 보장 및 락을 통한 데이터 정합성 보장을 목표

<br>
<img width="70%" alt="image" src="https://github.com/hyoguoo/payment-integration-server/assets/113650170/1f608526-49f7-4c67-80dd-4841d5491c26">
<br>
<br>

자세한 내용은 [토스 페이먼츠를 통한 결제 연동 시스템 구현 포스팅](https://hyoguoo.gitbook.io/tech-log/payment_system_with_toss) 참고

[Client Repository](https://github.com/hyoguoo/payment-widget-client)

[Mock Server Repository](https://github.com/hyoguoo/toss-mock-server)

## 기술 스택

Java 17 / Spring 6.0.13 / Spring Boot 3.1.5 / MySQL 8.0.33

## ERD

- User
- Product
- OrderInfo

<img width="50%" alt="image" src="https://github.com/hyoguoo/payment-integration-server/assets/113650170/0d99d27e-3392-48d0-82cf-27f27b7bd6bf">

## 기능 목록

- API
    - POST /api/v1/orders/create - 주문 요청
    - POST /api/v1/orders/confirm - 주문 승인
- View
    - GET /order - 주문 상세 조회
    - GET /order/{id} - 주문 리스트 조회
    - POST /order/cancel - 주문 취소

## 그 외

- 비관적 락을 통한 데이터 정합성 보장

<img width="70%" alt="image" src="https://github.com/hyoguoo/payment-integration-server/assets/113650170/870e9eeb-3ca2-4a96-9ffe-0253fc6201b6">

- [N+1 문제 해결](https://hyoguoo.gitbook.io/tech-log/jpa_n+1)
- [멀티 스레드 테스트와 @Transactional](https://hyoguoo.gitbook.io/tech-log/multi_thread_test)