package com.hyoguoo.paymentplatform.payment.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyoguoo.paymentplatform.payment.application.dto.response.CheckoutResult;
import com.hyoguoo.paymentplatform.payment.presentation.dto.response.CheckoutResponse;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentPresentationMapperTest {

    @Test
    @DisplayName("중복 결제 결과는 응답에 중복 여부가 참으로 담긴다")
    void 중복_결제_결과는_응답에_중복_여부가_참으로_담긴다() {
        // given
        CheckoutResult checkoutResult = CheckoutResult.builder()
                .orderId("order-1")
                .totalAmount(BigDecimal.valueOf(1000))
                .isDuplicate(true)
                .build();

        // when
        CheckoutResponse checkoutResponse = PaymentPresentationMapper.toCheckoutResponse(checkoutResult);

        // then
        assertThat(checkoutResponse.isDuplicate()).isTrue();
    }

    @Test
    @DisplayName("신규 결제 결과는 거짓으로 담긴다")
    void 신규_결제_결과는_거짓으로_담긴다() {
        // given
        CheckoutResult checkoutResult = CheckoutResult.builder()
                .orderId("order-2")
                .totalAmount(BigDecimal.valueOf(2000))
                .isDuplicate(false)
                .build();

        // when
        CheckoutResponse checkoutResponse = PaymentPresentationMapper.toCheckoutResponse(checkoutResult);

        // then
        assertThat(checkoutResponse.isDuplicate()).isFalse();
    }
}
