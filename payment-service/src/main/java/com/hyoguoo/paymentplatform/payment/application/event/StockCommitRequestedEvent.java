package com.hyoguoo.paymentplatform.payment.application.event;

import io.micrometer.context.ContextSnapshot;
import io.micrometer.observation.Observation;
import io.opentelemetry.context.Context;

/**
 * stock.events.commit 발행 요청 Spring ApplicationEvent.
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
 * <p>T-I10: OTel Context 와 ObservationRegistry ThreadLocal 이 동기화되지 않는 문제 대응.
 * KafkaTemplate {@code observation-enabled=true} 는 {@code ObservationRegistry.getCurrentObservation()}
 * (Micrometer ThreadLocal)을 parent로 사용한다. producer 측 active Observation 을 명시 캡처하고,
 * 리스너에서 {@link Observation#openScope()} 로 활성화하여 KafkaTemplate observation 의 parent 인식 정확화.
 * parentObservation 이 null 이면 리스너에서 기존 경로(mdcScope + otelScope) 만 적용한다.
 *
 * @param eventUUID          멱등성 키 (ADR-16 결정론적 UUID)
 * @param orderId            주문 ID
 * @param productId          재고 차감 대상 상품 ID
 * @param quantity           차감 수량
 * @param idempotencyKey     멱등성 키 (주문 ID 등 — 소비자 측 중복 처리 식별용)
 * @param contextSnapshot    producer 측 캡처 Micrometer 컨텍스트 (MDC) — 리스너에서 복원
 * @param otelContext        producer 측 캡처 OTel Context (span/traceId) — 리스너에서 복원
 * @param parentObservation  producer 측 active Observation (nullable) — null 이면 기존 경로 적용
 */
public record StockCommitRequestedEvent(
        String eventUUID,
        String orderId,
        Long productId,
        int quantity,
        String idempotencyKey,
        ContextSnapshot contextSnapshot,
        Context otelContext,
        Observation parentObservation
) {

}
