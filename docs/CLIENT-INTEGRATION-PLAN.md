# CLIENT-INTEGRATION 구현 플랜

> 작성일: 2026-04-06

## 목표

순수 HTML/JS 정적 파일 3개(checkout, success, fail)와 공통 CSS를 `src/main/resources/static/payment/`에 추가해 브라우저에서 Toss Payments 결제 흐름을 직접 실행할 수 있게 한다.

## 컨텍스트

- 설계 문서: [docs/topics/CLIENT-INTEGRATION.md](../topics/CLIENT-INTEGRATION.md)
- 레퍼런스: `tosspayments-sample-main/payment-client/src/pages/`
- 주요 변경 파일:
  - `src/main/resources/static/style.css` (신규)
  - `src/main/resources/static/payment/checkout.html` (신규)
  - `src/main/resources/static/payment/success.html` (신규)
  - `src/main/resources/static/payment/fail.html` (신규)

### API 응답 구조 (data 래퍼 없음)

```
POST /api/v1/payments/checkout   → { orderId, totalAmount }
POST /api/v1/payments/confirm    → { orderId, amount, processingDelayed }
GET  /api/v1/payments/{id}/status → { orderId, status, approvedAt }
  status: PENDING | PROCESSING | DONE | FAILED
```

---

## 진행 상황

<!-- execute 단계에서 각 태스크 완료 시 체크 -->
- [ ] Task 1: style.css 추가
- [ ] Task 2: checkout.html 작성
- [ ] Task 3: success.html 작성
- [ ] Task 4: fail.html 작성

---

## 태스크

### Task 1: style.css 추가 [tdd=false]

**구현**
- `src/main/resources/static/style.css` 생성
- `tosspayments-sample-main/toss-client-spring-javascript/src/main/resources/static/style.css` 내용 그대로 복사

**완료 기준**
- 파일 생성 확인
- `http://localhost:8080/style.css` 접근 시 CSS 반환

**완료 결과**
> (완료 후 작성)

---

### Task 2: checkout.html 작성 [tdd=false]

**구현**
- `src/main/resources/static/payment/checkout.html` 생성
- Toss SDK v1 CDN 로드: `<script src="https://js.tosspayments.com/v1/payment"></script>`
- Client Key: `test_ck_EP59LybZ8Bwd6KDgXvlJ36GYo7pR`

**동작 순서**
1. 결제수단 선택 UI (card / easypay / tossPayments 라디오 + 세부 선택)
2. 구매자 정보 입력 (이름, 이메일 — 선택사항)
3. 결제내역 표시 (210,000원 고정)
4. "결제하기" 버튼 클릭 시:
   - `POST /api/v1/payments/checkout` 호출
     ```json
     {
       "userId": 1,
       "orderedProductList": [
         { "productId": 1, "quantity": 3 },
         { "productId": 2, "quantity": 2 }
       ]
     }
     ```
   - 응답에서 `json.orderId` 획득 (`data` 래퍼 없음)
   - `TossPayments(CLIENT_KEY).requestPayment(paymentMethod, { ... })`
     - `successUrl`: `http://localhost:8080/payment/success.html`
     - `failUrl`: `http://localhost:8080/payment/fail.html`
     - `flowMode`: tossPayments이면 `'DEFAULT'`, 그 외 `'DIRECT'`
     - `cardCompany`: card 선택 시 세부 카드사 값
     - `easyPay`: easypay 선택 시 세부 간편결제 값

**완료 기준**
- 브라우저에서 `http://localhost:8080/payment/checkout.html` 접속 시 페이지 정상 로드
- 결제수단 선택 → 결제하기 → Toss 결제창 정상 호출

**완료 결과**
> (완료 후 작성)

---

### Task 3: success.html 작성 [tdd=false]

**구현**
- `src/main/resources/static/payment/success.html` 생성
- Toss가 리다이렉트하는 URL: `/payment/success.html?paymentKey=...&orderId=...&amount=...`

**동작 순서**
1. URL 쿼리 파라미터에서 `paymentKey`, `orderId`, `amount` 파싱
2. `POST /api/v1/payments/confirm` 호출
   ```json
   { "userId": 1, "orderId": "...", "amount": "...", "paymentKey": "..." }
   ```
3. confirm 실패 시 → `/payment/fail.html?code=...&message=...` 이동
4. confirm 202 성공 시:
   - `processingDelayed: true` 이면 "다소 시간이 걸릴 수 있습니다" 안내 표시
   - `GET /api/v1/payments/{orderId}/status` 폴링 시작
     - 1초 간격, 최대 30회 (`setInterval` + 카운터)
     - `DONE` → 성공 화면 표시 (orderId, amount, approvedAt)
     - `FAILED` → 실패 메시지 표시
     - 30회 초과 → "처리 시간이 초과됐습니다. 잠시 후 다시 확인해 주세요." 안내

**완료 기준**
- 결제 완료 후 success.html 도달 시 confirm 호출 → 폴링 → DONE 결과 표시 확인
- confirm 오류 시 fail.html로 정상 리다이렉트

**완료 결과**
> (완료 후 작성)

---

### Task 4: fail.html 작성 [tdd=false]

**구현**
- `src/main/resources/static/payment/fail.html` 생성
- Toss 실패 리다이렉트 또는 confirm 오류 시 도달: `/payment/fail.html?code=...&message=...`

**동작 순서**
1. URL 쿼리 파라미터에서 `code`, `message` 파싱 후 화면 표시
2. "다시 시도" 버튼 → `checkout.html`로 이동

**완료 기준**
- 잘못된 결제 시도 시 fail.html 도달 → code/message 정상 표시
- "다시 시도" 버튼 클릭 시 checkout.html로 이동

**완료 결과**
> (완료 후 작성)

---

## Verification

1. `./gradlew bootRun` 서버 기동
2. `http://localhost:8080/payment/checkout.html` 접속
3. 결제 진행 → success.html → DONE 상태 표시 확인
4. fail 케이스 → fail.html 에러 표시 확인
5. `/admin/payments/events`에서 결제 이벤트 생성 확인
