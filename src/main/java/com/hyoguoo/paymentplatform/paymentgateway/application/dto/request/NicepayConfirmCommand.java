package com.hyoguoo.paymentplatform.paymentgateway.application.dto.request;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class NicepayConfirmCommand {

    private final String tid;
    private final BigDecimal amount;
}
