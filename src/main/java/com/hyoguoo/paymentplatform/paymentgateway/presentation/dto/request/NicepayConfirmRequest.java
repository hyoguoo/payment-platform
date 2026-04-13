package com.hyoguoo.paymentplatform.paymentgateway.presentation.dto.request;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class NicepayConfirmRequest {

    private final String tid;
    private final BigDecimal amount;
}
