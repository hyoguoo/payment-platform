package com.hyoguoo.paymentplatform.payment.presentation;

import com.hyoguoo.paymentplatform.payment.presentation.dto.request.PaymentConfirmRequest;
import com.hyoguoo.paymentplatform.payment.presentation.dto.request.CheckoutRequest;
import com.hyoguoo.paymentplatform.payment.presentation.dto.response.PaymentConfirmResponse;
import com.hyoguoo.paymentplatform.payment.presentation.dto.response.CheckoutResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class PaymentController {

    @PostMapping("/api/v1/payments/checkout")
    public CheckoutResponse checkout(
            @RequestBody CheckoutRequest checkoutRequest
    ) {
        return CheckoutResponse.builder().build();
    }

    @PostMapping("/api/v1/payments/confirm")
    public PaymentConfirmResponse confirm(
            @RequestBody PaymentConfirmRequest paymentConfirmRequest
    ) {
        return PaymentConfirmResponse.builder().build();
    }
}
