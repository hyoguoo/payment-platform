package com.hyoguoo.paymentplatform.paymentgateway.domain.vo;

import com.hyoguoo.paymentplatform.paymentgateway.domain.enums.TossPaymentStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class TossPaymentDetails {

    private final String orderName;
    private final BigDecimal totalAmount;
    private final TossPaymentStatus status;
    private final LocalDateTime approvedAt;
    private final String rawData;
}
