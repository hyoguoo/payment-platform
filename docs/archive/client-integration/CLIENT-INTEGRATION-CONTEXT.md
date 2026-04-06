# CLIENT-INTEGRATION 설계

> 최종 수정: 2026-04-06

---

## 문제 정의

payment-platform은 순수 백엔드 프로젝트로, 브라우저에서 실제 결제 흐름을 직접 실행하고 검증할 수 있는 클라이언트 페이지가 없다. `tosspayments-sample-main/payment-client/`에 포함된 React 클라이언트를 **API 연동 패턴 및 SDK 버전 참고용**으로만 활용하고, 실제 구현은 순수 HTML/JS 정적 파일로 제작해 Spring Boot에 통합한다.

## 참고 레퍼런스 (payment-client)

`tosspayments-sample-main/payment-client/`에서 참고할 사항:

| 참고 항목 | 내용 |
|-----------|------|
| SDK 버전 | `@tosspayments/payment-sdk` v1 (`loadTossPayments` API 방식) |
| Client Key | `test_ck_EP59LybZ8Bwd6KDgXvlJ36GYo7pR` |
| checkout 요청 body | `{ userId, orderedProductList: [{productId, quantity}] }` |
| confirm 요청 body | `{ userId, orderId, amount, paymentKey }` |
| 데모 상품 | productId=1 qty=3, productId=2 qty=2 (합계 210,000원) |
| 데모 userId | 1 (고정) |

## 영향 범위

- 신규: `src/main/resources/static/style.css`
- 신규: `src/main/resources/static/payment/checkout.html`
- 신규: `src/main/resources/static/payment/success.html`
- 신규: `src/main/resources/static/payment/fail.html`
- 무관: 백엔드 코드 전체 (기존 API 그대로 활용)

## 결제 흐름

```
checkout.html (http://localhost:8080/payment/checkout.html)
  → POST /api/v1/payments/checkout
      { userId:1, orderedProductList:[{productId:1, quantity:3},{productId:2, quantity:2}] }
  ← { orderId, totalAmount }
  → loadTossPayments(CLIENT_KEY).requestPayment(paymentMethod, {
        amount: 210000,
        orderId,
        orderName: '테스트 결제',
        successUrl: 'http://localhost:8080/payment/success.html',
        failUrl:    'http://localhost:8080/payment/fail.html'
    })

success.html (?paymentKey=...&orderId=...&amount=...)
  → POST /api/v1/payments/confirm
      { userId:1, orderId, amount, paymentKey }
  ← 202 Accepted { orderId, amount, processingDelayed }
  → "처리 중..." 표시
    (processingDelayed: true 이면 '다소 시간이 걸릴 수 있습니다' 안내)
  → GET /api/v1/payments/{orderId}/status 폴링 (1초 간격, 최대 30회)
    DONE    → 성공 결과 표시 (orderId, amount, approvedAt)
    FAILED  → 실패 메시지 표시
    30회 초과 → 타임아웃 안내
  (confirm 오류 시 → /payment/fail.html?code=...&message=...)

fail.html (?code=...&message=...)
  → URL params 파싱 → 에러코드/메시지 표시
```

## 결정 사항

| 항목 | 결정 | 이유 |
|------|------|------|
| 포함 흐름 | Payment API (checkout, success, fail) | 핵심 confirm flow 검증에 집중 |
| 클라이언트 형태 | 순수 HTML + JS 정적 파일 | 빌드 프로세스 없이 바로 배치 가능, SPA 라우팅 이슈 없음 |
| 배치 방식 | `src/main/resources/static/payment/` | Spring Boot 자동 정적 파일 서빙 |
| SDK | `@tosspayments/payment-sdk` v1 CDN | payment-client와 동일 버전, CDN으로 간단히 로드 |
| Async 처리 | 항상 status 폴링. `processingDelayed`는 UI 힌트로만 활용 | confirm 202는 수락 응답일 뿐 — Toss API 승인은 비동기 |
| Checkout | 기존 `POST /api/v1/payments/checkout` 호출 후 orderId 획득 | DB에 실제 주문 레코드 생성 |

## 제외 범위

- Widget API, Billing API, BrandPay API — 별도 작업으로 처리
- React SPA 빌드/배포 — payment-client는 참고용, 실제 구현은 순수 HTML
- SPA fallback 컨트롤러 — 순수 HTML이므로 불필요

## 참고

- API 연동 레퍼런스: `tosspayments-sample-main/payment-client/src/config.ts`, `src/pages/Checkout.tsx`, `src/pages/Success.tsx`
- SDK CDN: `https://js.tosspayments.com/v1/payment` (v1)
- 기존 CORS 설정: `WebConfig.java` — 모든 Origin 허용 (변경 불필요)
- 기존 API: `PaymentController.java` — checkout, confirm, status 엔드포인트
