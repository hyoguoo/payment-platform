# Coding Conventions — 예외 / 로깅 / AOP

> 예외 계층, LogFmt + 트레이스 컨텍스트, AOP 컨벤션.

## 예외 계층

```
RuntimeException
├── PaymentBaseException                  # 도메인 공통
│   ├── PaymentValidException             # 사용자 입력 위변조
│   ├── PaymentStatusException            # 상태 전이 위반
│   ├── PaymentOrderedProductStockException  # 재고 부족
│   ├── PaymentTossRetryableException     # PG 재시도 가능
│   ├── PaymentTossNonRetryableException  # PG 종결 거절
│   └── PaymentGatewayStatusUnmappedException
└── IllegalStateException                 # 도메인 불변식 위반 — 두 번째 가드
```

**룰**:
- 도메인 예외는 `PaymentBaseException` 계열로 분류 (코드 + 메시지)
- 단순 가드(이미 다른 예외로 막혔어야 할 case)는 `IllegalStateException` 으로 두 번째 가드 (예: `quarantine()` 메서드의 isTerminal 가드)
- **`catch (Exception e)` swallow 금지** — 잡으면 LogFmt.error + 재throw 또는 명시적 fallback. 워커 등 절대 죽으면 안 되는 경로만 예외적으로 catch + ERROR 승격
- presentation 측에서 도메인 예외 → HTTP 상태 매핑은 `@RestControllerAdvice` 가 단일 진실 원천

## LogFmt + 트레이스 컨텍스트

**모든 로그는 `core/common/log/LogFmt` 를 통해** (4서비스 + gateway 공통 위치):
```java
LogFmt.info(
    EventType.PAYMENT_CONFIRM_SUCCESS,
    () -> String.format("orderId=%s amount=%d", orderId, amount)
);
```

규칙:
- `EventType` enum 으로 이벤트 분류 (`PAYMENT_CONFIRM_SUCCESS`, `STOCK_COMPENSATE_FAIL`, `PAYMENT_QUARANTINE_NOOP_TERMINAL` 등)
- 메시지는 `key=value` 형태로 구성 — Loki 에서 라벨/필터 가능
- traceparent 는 MDC 에서 자동 첨부 (별도 코드 불필요)
- `LogFmt.debug` / `info` / `warn` / `error` 4단계
- `Supplier<String>` 받는 형태 — 로그 레벨 필터링 시 문자열 빌드 비용 회피

## 민감 값 마스킹

로그 한 줄이 최종 문자열로 조립된 직후 `core/common/log/MaskingPatternLayout` 이 정규식으로 민감 값을 가린다(5서비스 각자 보유 — `LogFmt` 복제 관례와 동일).

- 패턴은 코드가 아니라 각 서비스 `logback-spring.xml` 의 `<maskPattern>` 에 등록한다 — 코드 변경 없이 추가·제거 가능
- 각 패턴은 **캡처 그룹을 정확히 하나** 가져야 하고, 그 그룹으로 잡힌 구간만 `***` 로 치환된다. 그룹 밖 접두사는 남아 디버깅 단서를 유지한다
- 대상은 결제 키·인증 헤더 값·이메일·카드번호 형태 넷. **주문 번호와 결제 상태는 대상이 아니다** — 가리면 장애 추적이 불가능해진다
- 개별 로깅 코드에 마스킹을 넣지 않는다. 출력 직전 일괄 처리라 새 로그를 추가하며 빠뜨려도 걸린다
- 외부 응답 원문을 로그에 실을 때는 길이를 제한하고 잘림을 표시한다(예: `TossPaymentGatewayStrategy` / `NicepayPaymentGatewayStrategy` 의 파싱 실패 경로) — 마스킹은 알려진 형태만 걸러내므로, 예상 못한 본문이 통째로 흐르는 것은 따로 막아야 한다

## AOP 컨벤션

**`@PublishDomainEvent` + `@PaymentStatusChange`**:
- payment 상태 전이 시 `payment_history` audit row 자동 기록
- `markPaymentAsDone` / `markPaymentAsFail` / `markPaymentAsQuarantined` 같은 위임 경로에만 AOP 적용
- 직접 `paymentEvent.done() + saveOrUpdate()` 호출 시 audit trail 누락 — **반드시 위임 경로 사용**

**`@TransactionalEventListener(AFTER_COMMIT)`** 패턴:
- TX 커밋 직후 부수 발행 (Kafka publish, outbox relay) — 동기 publish 가 `@Transactional` 안에서 Hikari 점유 못 하게
- 리스너는 항상 `infrastructure/listener/` 에 위치
