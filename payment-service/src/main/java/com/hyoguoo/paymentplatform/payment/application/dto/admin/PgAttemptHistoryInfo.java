package com.hyoguoo.paymentplatform.payment.application.dto.admin;

import java.time.Instant;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

/**
 * 주문번호 기준 pg-service 확정 시도 이력 전체 — payment 쪽 도메인 표현.
 * pg-service {@code PgAttemptHistory} 를 payment 도메인 DTO 로 옮긴 것.
 *
 * <p>{@code found=false} 는 해당 주문이 pg-service 에 없다는 뜻(이력 없음)이다.
 * 조회 자체가 실패(pg-service 장애 등)한 경우는 이 DTO 로 흡수되지 않고 예외로 전파된다
 * — 호출부(payment-service PaymentAdminController)가 이력 없음과 조회 실패를 구분해 렌더링한다.
 *
 * <p>원문 컬럼(payload/headers_json)과 벤더 결제 키는 이 DTO 어디에도 담기지 않는다.
 */
@Getter
@Builder
public class PgAttemptHistoryInfo {

    private final String orderId;
    private final boolean found;
    private final String finalStatus;
    private final Instant finalizedAt;
    private final String reasonCode;
    private final List<PgAttemptEntryInfo> attempts;
}
