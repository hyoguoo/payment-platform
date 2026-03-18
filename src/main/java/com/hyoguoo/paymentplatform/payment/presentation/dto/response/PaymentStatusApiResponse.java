package com.hyoguoo.paymentplatform.payment.presentation.dto.response;

import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PaymentStatusApiResponse {

    private final String orderId;
    private final PaymentStatusResponse status;
    private final LocalDateTime approvedAt;
}
