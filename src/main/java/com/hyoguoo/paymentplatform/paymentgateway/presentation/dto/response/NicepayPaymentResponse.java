package com.hyoguoo.paymentplatform.paymentgateway.presentation.dto.response;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class NicepayPaymentResponse {

    private final String tid;
    private final String orderId;
    private final BigDecimal amount;
    private final String status;
    private final String resultCode;
    private final String resultMsg;
    private final String paidAt;
}
