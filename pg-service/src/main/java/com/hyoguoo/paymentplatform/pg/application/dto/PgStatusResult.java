package com.hyoguoo.paymentplatform.pg.application.dto;

import com.hyoguoo.paymentplatform.pg.domain.enums.PgPaymentStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * pg-service 내부 PG 상태 조회 결과 DTO.
 * payment-service의 PaymentStatusResult 의존을 끊고 pg-service 독립 DTO로 선언.
 *
 * <p>{@code approvedAtRaw} 는 벤더 조회 응답의 원본 승인 시각 문자열을 그대로 보존한다 —
 * {@code approvedAt}(LocalDateTime) 은 오프셋 정보를 잃으므로, 이 값을 그대로 실어야
 * ConfirmedEventPayload 발행 시 정산 앵커가 밀리지 않는다.
 */
public record PgStatusResult(
        String paymentKey,
        String orderId,
        PgPaymentStatus status,
        BigDecimal amount,
        LocalDateTime approvedAt,
        PgFailureInfo failure,
        String approvedAtRaw
) {

}
