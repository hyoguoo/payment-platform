package com.hyoguoo.paymentplatform.payment.presentation.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class CheckoutResponse {

    private final String orderId;
    private final BigDecimal totalAmount;

    /**
     * boolean 필드 isDuplicate — Jackson 기본 직렬화 시 getter isXxx() 에서 "is" prefix 를 제거하여
     * "duplicate" key 로 직렬화한다. {@code @JsonProperty("duplicate")} 로 명시해 CheckoutResult 와
     * 직렬화 key 를 맞춘다.
     */
    @JsonProperty("duplicate")
    private final boolean isDuplicate;
}
