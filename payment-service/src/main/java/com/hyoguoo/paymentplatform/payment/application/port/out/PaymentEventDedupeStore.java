package com.hyoguoo.paymentplatform.payment.application.port.out;

import java.time.Instant;

/**
 * 결제 결과 이벤트의 멱등 마킹 출력 포트.
 *
 * <p>운영 구현체는 MySQL {@code payment_event_dedupe} 테이블에 {@code INSERT IGNORE} 를 실행하고
 * affected row 수를 반환한다 ({@code JdbcPaymentEventDedupeStore}).
 */
public interface PaymentEventDedupeStore {

    /**
     * 결제 결과 이벤트의 멱등 마킹을 시도한다.
     * 같은 event_uuid 가 이미 존재하면 0, 신규로 마킹되면 1 을 반환한다.
     *
     * <p>0 row 면 비즈니스 skip, 1 row 면 비즈니스 진행. 발행은 항상 진행 (호출자 책임).
     *
     * @param eventUuid 수신 이벤트 UUID (PK)
     * @param orderId   결제 주문 ID (메타데이터)
     * @param status    수신 결과 상태 (APPROVED / FAILED / QUARANTINED)
     * @param expiresAt TTL (Kafka retention + 복구 버퍼)
     * @return 1 = 신규 마킹, 0 = 중복 (이미 처리)
     */
    int markIfAbsent(String eventUuid, long orderId, String status, Instant expiresAt);
}
