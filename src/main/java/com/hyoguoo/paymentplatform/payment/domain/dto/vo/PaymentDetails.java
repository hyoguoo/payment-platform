package com.hyoguoo.paymentplatform.payment.domain.dto.vo;

import com.hyoguoo.paymentplatform.payment.domain.dto.enums.TossPaymentStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PaymentDetails {

    private final String orderName;
    private final BigDecimal totalAmount;
    private final TossPaymentStatus status;
    private final LocalDateTime approvedAt;
    private final String rawData;
}
