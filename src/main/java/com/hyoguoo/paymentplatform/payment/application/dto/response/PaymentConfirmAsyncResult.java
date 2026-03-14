package com.hyoguoo.paymentplatform.payment.application.dto.response;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PaymentConfirmAsyncResult {

    public enum ResponseType {
        SYNC_200,
        ASYNC_202
    }

    private final ResponseType responseType;
    private final String orderId;
    private final BigDecimal amount;
}
