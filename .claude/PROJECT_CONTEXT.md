# Payment Platform - Project Context

## 프로젝트 개요

토스페이먼츠를 통한 결제 연동 시스템으로, 외부 PG 연동 시 발생할 수 있는 다양한 문제들을 해결하기 위한 백엔드 시스템입니다.

## 핵심 해결 과제

### 1. 데이터 정합성 및 위변조 방지
- **문제**: 클라이언트 주도 결제 흐름에서 중간 값 조작 가능성
- **해결**: 서버 주도 흐름 + 클라이언트·서버·PG 응답값 교차 검증

### 2. 상태 기반 결제 복구 모델
- **문제**: API 지연/서버 중단 시 결제 실패로 종료되어 복구 불가
- **해결**: 상태 전환 모델 정의 + 재시도 가능 오류 자동 복구

### 3. 트랜잭션 범위 최소화
- **문제**: 외부 API 호출로 인한 커넥션 점유 및 응답 지연
- **해결**: 외부 호출을 트랜잭션 외부로 분리 + 보상 트랜잭션 적용

### 4. 결제 흐름 추적 및 모니터링
- **문제**: 복잡한 결제 흐름에서 전체 이력 추적 어려움
- **해결**: 구조화된 로깅(traceId/orderId) + AOP 기반 이력 저장 + Admin 페이지

## 아키텍처 패턴

### Port-Adapter (Hexagonal Architecture)

```
application/
├── ServiceImpl      # 유즈케이스 오케스트레이션
├── usecase/        # 단일 책임 유즈케이스
└── port/           # 외부 의존성 인터페이스

domain/             # 순수 비즈니스 로직

infrastructure/
├── repository/     # Port 구현체
└── internal/       # 타 도메인 협력

presentation/       # 컨트롤러
```

### 의존성 규칙

1. **Domain**: 외부 기술 독립적, 순수 Java
2. **Application**: Domain에만 의존, Port로 외부 추상화
3. **Infrastructure**: Port 구현, 실제 외부 연동
4. **Presentation**: Application 호출

## 코딩 컨벤션

### 명명 규칙

- Service 계층
  - `*ServiceImpl`: 여러 유즈케이스 조합/오케스트레이션
  - `*UseCase`: 단일 기능 단위 로직

- Repository 계층
  - `*Repository` (interface): Port 정의
  - `*RepositoryImpl`: Port 구현체
  - `Jpa*Repository`: Spring Data JPA 인터페이스

- Port
  - `*Port`: 외부 의존성 추상화

### 패키지 구조 예시

```
payment/
├── application/
│   ├── PaymentConfirmServiceImpl.java
│   ├── usecase/
│   │   └── PaymentProcessorUseCase.java
│   └── port/
│       ├── PaymentEventRepository.java
│       └── ProductPort.java
├── domain/
│   └── PaymentEvent.java
├── infrastructure/
│   ├── repository/
│   │   ├── JpaPaymentEventRepository.java
│   │   └── PaymentEventRepositoryImpl.java
│   └── internal/
│       └── InternalProductAdapter.java
└── presentation/
    └── PaymentController.java
```

## 테스트 전략

### 1. 단위 테스트
- 도메인 로직: 순수 Java 단위 테스트
- 유즈케이스: 의존성을 Fake/Mock으로 대체

### 2. 통합 테스트
- Testcontainers 기반 MySQL 컨테이너 사용
- 실제 환경과 유사한 테스트 환경 구성
- 외부 API는 Fake 객체로 제어

### 3. 커버리지
- JaCoCo 사용
- 제외 대상: DTO, Entity, Infrastructure, Q클래스

## 개발 환경

### 로컬 개발 (docker/local/)
- MySQL + Spring App
- 포트: 3306, 8080

### 전체 스택 (docker/full-stack/)
- 로컬 환경 + ELK Stack + Monitoring
- 추가 포트: 9200, 5050, 5601, 9090, 3000

## 주요 API

- `POST /api/v1/payments/checkout` - 주문 요청
- `POST /api/v1/payments/confirm` - 주문 승인

## 중요 고려사항

### 코드 작성 시

1. **의존성 역전**: 외부 의존성은 반드시 Port로 추상화
2. **도메인 독립성**: Domain은 외부 기술에 독립적으로 유지
3. **테스트 용이성**: 모든 계층에서 테스트 더블 사용 가능하도록 설계
4. **상태 전환**: 결제 상태 변경 시 유효성 검증 필수
5. **트랜잭션 분리**: 외부 API 호출은 트랜잭션 외부에서 수행

### 코드 리뷰 체크리스트

- [ ] Port-Adapter 패턴 준수
- [ ] 의존성 방향이 올바른지 확인
- [ ] 도메인 로직이 순수한지 확인
- [ ] 트랜잭션 범위가 적절한지 확인
- [ ] 테스트 코드 작성 여부
- [ ] 로깅이 구조화되어 있는지 확인 (traceId, orderId)
