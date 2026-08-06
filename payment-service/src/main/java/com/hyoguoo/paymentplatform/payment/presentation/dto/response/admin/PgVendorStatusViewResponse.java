package com.hyoguoo.paymentplatform.payment.presentation.dto.response.admin;

import com.hyoguoo.paymentplatform.payment.application.dto.admin.PgVendorStatusInfo;
import java.time.Instant;
import lombok.Builder;
import lombok.Getter;

/**
 * 관리자 격리 상세 화면의 벤더 상태 조회 결과 표시용 뷰 모델.
 *
 * <p>{@code judgement} 는 {@code APPROVED}/{@code FAILED}/{@code UNKNOWN} 문자열 그대로다 —
 * 템플릿이 뱃지 색과 종결 경고 노출 여부를 이 값으로 분기한다.
 */
@Getter
@Builder
public class PgVendorStatusViewResponse {

    private final String judgement;
    private final String vendorStatus;
    private final Instant queriedAt;

    public static PgVendorStatusViewResponse from(PgVendorStatusInfo info) {
        return PgVendorStatusViewResponse.builder()
                .judgement(info.judgement().name())
                .vendorStatus(info.vendorStatus())
                .queriedAt(info.queriedAt())
                .build();
    }
}
