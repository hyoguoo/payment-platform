package com.hyoguoo.paymentplatform.payment.application.event;

import io.micrometer.context.ContextSnapshot;
import io.opentelemetry.context.Context;

/**
 * stock.events.restore 발행 요청 Spring ApplicationEvent.
 * ADR-04: TX 내부에서 발행 — 실제 Kafka 발행은 AFTER_COMMIT 리스너가 수행한다.
 *
 * <p>T-I4: AFTER_COMMIT 리스너 실행 시점에 active span이 이미 종료되어 새 trace가
 * 생성되는 회귀를 방지한다. producer 측에서 captureAll() 로 context를 캡처하고,
 * 리스너가 setThreadLocals()로 복원한 뒤 Kafka publish를 수행한다.
 *
 * <p>T-I7: ContextSnapshot(captureAll)은 Micrometer ContextRegistry 에 등록된
 * Slf4jMdcThreadLocalAccessor(MDC)만 캡처한다. OTel Context 는 별도 ThreadLocal
 * (OTel ContextStorage)에 있어 captureAll 대상이 아니다.
 * otelContext 필드에 {@link Context#current()} 를 명시 캡처하고, 리스너에서
 * {@link Context#makeCurrent()} 로 활성화하여 KafkaTemplate.send() 가 올바른
 * traceparent 를 헤더에 주입하도록 한다.
 *
 * @param eventUUID       멱등성 키 (ADR-16 결정론적 UUID — orderId+productId 기반)
 * @param orderId         주문 ID
 * @param productId       복원 대상 상품 ID
 * @param quantity        복원 수량
 * @param contextSnapshot producer 측 캡처 Micrometer 컨텍스트 (MDC) — 리스너에서 복원
 * @param otelContext     producer 측 캡처 OTel Context (span/traceId) — 리스너에서 복원
 */
public record StockRestoreRequestedEvent(
        String eventUUID,
        String orderId,
        Long productId,
        int quantity,
        ContextSnapshot contextSnapshot,
        Context otelContext
) {

}
