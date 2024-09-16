package com.hyoguoo.paymentplatform.payment.presentation.dto.response;

import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOrderStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class OrderFindResponse {

    private final Long id;
    private final String orderId;
    private final BigDecimal amount;
    private final String paymentKey;
    private final LocalDateTime requestedAt;
    private final LocalDateTime approvedAt;
    private final PaymentOrderStatus paymentOrderStatus;
}
