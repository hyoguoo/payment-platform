package com.hyoguoo.paymentplatform.payment.presentation;

import com.hyoguoo.paymentplatform.payment.presentation.dto.request.OrderConfirmRequest;
import com.hyoguoo.paymentplatform.payment.presentation.dto.request.OrderCreateRequest;
import com.hyoguoo.paymentplatform.payment.presentation.dto.response.OrderConfirmResponse;
import com.hyoguoo.paymentplatform.payment.presentation.dto.response.OrderCreateResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class PaymentController {

    @PostMapping("/api/v1/payments/create")
    public OrderCreateResponse createOrder(
            @RequestBody OrderCreateRequest orderCreateRequest
    ) {
        return OrderCreateResponse.builder().build();
    }

    @PostMapping("/api/v1/payments/confirm")
    public OrderConfirmResponse confirmOrder(
            @RequestBody OrderConfirmRequest orderConfirmRequest
    ) {
        return OrderConfirmResponse.builder().build();
    }
}
