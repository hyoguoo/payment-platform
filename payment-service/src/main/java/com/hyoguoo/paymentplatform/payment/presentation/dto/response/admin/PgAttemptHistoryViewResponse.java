package com.hyoguoo.paymentplatform.payment.presentation.dto.response.admin;

import com.hyoguoo.paymentplatform.payment.application.dto.admin.PgAttemptHistoryInfo;
import java.time.Instant;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

/**
 * pg-service 확정 시도 이력 상세 화면 표시용 뷰 모델.
 *
 * <p>{@code found=false} 는 pg-service 가 해당 주문을 모른다는 뜻(이력 없음)이다.
 * pg-service 조회 자체가 실패(장애·타임아웃)한 경우는 이 객체를 만들지 않는다 —
 * {@code PgAttemptHistoryViewServiceImpl} 이 예외를 흡수해 조회 불가 상태로 돌려주고
 * 컨트롤러는 그 분기만 모델에 담아, 이력 없음과 조회 불가 두 상태를 화면에서 구분한다.
 */
@Getter
@Builder
public class PgAttemptHistoryViewResponse {

    private final boolean found;
    private final String finalStatus;
    private final Instant finalizedAt;
    private final String reasonCode;
    private final List<PgAttemptEntryViewResponse> attempts;

    public static PgAttemptHistoryViewResponse from(PgAttemptHistoryInfo info) {
        return PgAttemptHistoryViewResponse.builder()
                .found(info.isFound())
                .finalStatus(info.getFinalStatus())
                .finalizedAt(info.getFinalizedAt())
                .reasonCode(info.getReasonCode())
                .attempts(info.getAttempts().stream()
                        .map(PgAttemptEntryViewResponse::from)
                        .toList())
                .build();
    }
}
