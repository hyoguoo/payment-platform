package com.hyoguoo.paymentplatform.payment.application.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import java.math.BigDecimal;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@JsonDeserialize(builder = CheckoutResult.CheckoutResultBuilder.class)
public class CheckoutResult {

    private final String orderId;
    private final BigDecimal totalAmount;

    /**
     * boolean 필드 isDuplicate — Jackson 기본 직렬화 시 getter isXxx() 에서 "is" prefix 를 제거하여
     * "duplicate" key 로 직렬화하지만, Builder setter 이름은 "isDuplicate" 로 불일치가 발생한다.
     * {@code @JsonProperty("duplicate")} 로 직렬화 / 역직렬화 key 를 명시하여 일치시킨다.
     */
    @JsonProperty("duplicate")
    private final boolean isDuplicate;

    @JsonPOJOBuilder(withPrefix = "")
    public static final class CheckoutResultBuilder {
        // Lombok generates the builder body; Jackson uses this class via @JsonDeserialize
    }
}
