package com.hyoguoo.paymentplatform.payment.application.dto.admin;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PaymentHistorySearchQuery {

    private String orderId;
}
