package com.hyoguoo.paymentplatform.payment.presentation;

import com.hyoguoo.paymentplatform.payment.application.dto.request.CheckoutCommand;
import com.hyoguoo.paymentplatform.payment.application.dto.response.CheckoutResult;
import com.hyoguoo.paymentplatform.payment.presentation.dto.request.CheckoutRequest;
import com.hyoguoo.paymentplatform.payment.presentation.dto.request.PaymentConfirmRequest;
import com.hyoguoo.paymentplatform.payment.presentation.dto.response.CheckoutResponse;
import com.hyoguoo.paymentplatform.payment.presentation.dto.response.PaymentConfirmResponse;
import com.hyoguoo.paymentplatform.payment.presentation.port.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping("/api/v1/payments/checkout")
    public CheckoutResponse checkout(
            @RequestBody CheckoutRequest checkoutRequest
    ) {
        CheckoutCommand checkoutCommand = PaymentPresentationMapper.toCheckoutCommand(
                checkoutRequest
        );
        CheckoutResult checkoutResult = paymentService.checkout(checkoutCommand);

        return PaymentPresentationMapper.toCheckoutResponse(checkoutResult);
    }

    @PostMapping("/api/v1/payments/confirm")
    public PaymentConfirmResponse confirm(
            @RequestBody PaymentConfirmRequest paymentConfirmRequest
    ) {
        return PaymentConfirmResponse.builder().build();
    }
}
