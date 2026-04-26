# Discuss Interview Round 0 — NICEPAY-PG-STRATEGY

## Ambiguity Ledger

### Scope (resolved)
- 나이스페이먼츠 실제 API 연동 (Sandbox → 운영 전환 가능)
- paymentgateway 모듈 범용화 (도메인/boundary DTO 접두사 제거, PG별 통신 객체는 유지)
- 예외 범용화 (PaymentTossRetryableException → PaymentGatewayRetryableException)
- 결제건별 PG 선택 (PaymentEvent에 gatewayType 컬럼 추가)
- checkout UI에서 나이스페이먼츠 결제 지원 (별도 페이지 또는 index 분기)
- PaymentGatewayType enum을 domain/enums로 이동

### Constraints (resolved)
- 서버 코드 변경 최소화 — 특히 returnUrl POST 콜백 처리 시 기존 confirm 흐름 재사용 선호
- "전부 진행" 확정 — 범용화, 예외 일반화, 결제건별 선택, UI 모두 포함

### Outputs (resolved)
- NicepayPaymentGatewayStrategy 구현체
- paymentgateway 모듈 내 NicePay 전용 서비스/오퍼레이터
- 범용화된 예외 계층 (Retryable/NonRetryable)
- PaymentEvent + PaymentEventEntity에 gatewayType 컬럼
- checkout-nicepay.html (또는 기존 checkout에 분기)
- success.html에 gatewayType 파라미터 추가
- NicePay용 application.yml 설정 (clientKey, secretKey, baseUrl)

### Verification (resolved)
- 단위 테스트: NicepayPaymentGatewayStrategy 상태 매핑, 에러 분류
- 통합 테스트: Sandbox API 키로 실제 confirm/cancel/조회 동작 확인 (수동)
- 기존 Toss 테스트 회귀 없음 확인 (`./gradlew test`)
- checkout UI 수동 테스트 (Toss/NicePay 양쪽)

## Key Decisions

### D1: tid → paymentKey 매핑
나이스페이먼츠의 `tid`(거래 키)를 시스템의 `paymentKey` 필드에 매핑한다. 자연스러운 1:1 대응.

### D2: returnUrl POST 콜백 처리
나이스페이먼츠는 returnUrl로 POST 콜백을 보낸다 (Toss는 GET 리다이렉트). 서버 코드 변경 최소화를 위해 POST 콜백을 받는 별도 엔드포인트 또는 HTML에서 처리하여 기존 confirm API 흐름으로 연결한다.

### D3: 멱등성 — 중복 승인 에러 처리 (핵심 논의 사항)
나이스페이먼츠는 Toss와 달리 명시적 멱등성 키를 지원하지 않는다. 같은 tid로 재승인 요청 시:
- `2201` "기승인존재" — 에러 응답 (정상 응답 아님)
- `A225` "TID 중복 오류" — 에러 응답
- `2156` "중복등록된거래요청" — 에러 응답

**설계 방향**: 재시도/복구 시나리오에서 `2201`(기승인존재)을 받으면 "이미 성공"으로 해석하고, 조회 API(`GET /v1/payments/{tid}`)로 실제 결과를 가져오는 보상 로직 필요. 이는 기존 Toss의 멱등성 키 기반 재시도와 다른 패턴이므로, NicepayPaymentGatewayStrategy.confirm()에서 이 에러 코드를 특별 처리해야 한다.

### D4: 에러 코드 분류
- 재시도 가능: 2159(은행장애), A246(과다접속), A299(API 지연), 4001, 4008, A401
- 재시도 불가: 3011-3014, 3041, 2152, 2156, 2201, A127, A225
- 중복 승인 특수 처리: 2201(기승인존재) → 조회 후 성공 매핑

### D5: 상태 매핑
| NicePay 상태 | 도메인 PaymentStatus |
|---|---|
| paid | DONE |
| ready | READY |
| failed | ABORTED |
| cancelled | CANCELED |
| partialCancelled | PARTIAL_CANCELED |
| expired | EXPIRED |

## Open Items for Architect
- D3의 보상 로직을 confirm() 내부에서 처리할지, 복구 사이클(OutboxProcessingService)에서만 처리할지
- paymentgateway 모듈의 범용화 범위 (어떤 DTO까지 접두사 제거할지)
- NicePay Sandbox 테스트 키 설정 방식
