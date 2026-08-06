package com.hyoguoo.paymentplatform.pg.presentation.dto;

import com.hyoguoo.paymentplatform.pg.application.dto.PgVendorStatusView;
import java.time.Instant;

/**
 * GET /api/v1/confirmations/{orderId}/vendor-status 응답 DTO.
 *
 * <p>조회 자체의 실패(예외)도 5xx 가 아니라 {@code judgement=UNKNOWN} 을 담은 200 으로 응답한다 —
 * 이 엔드포인트는 관리자가 격리 종결 여부를 판단하는 안전장치라 어떤 입력에서도 응답을 돌려줘야 한다.
 */
public record PgVendorStatusResponse(
        String orderId,
        String judgement,
        String vendorStatus,
        Instant queriedAt
) {

    public static PgVendorStatusResponse from(String orderId, PgVendorStatusView view) {
        return new PgVendorStatusResponse(
                orderId,
                view.judgement().name(),
                view.vendorStatus(),
                view.queriedAt()
        );
    }
}
