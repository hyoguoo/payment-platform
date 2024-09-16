package com.hyoguoo.paymentplatform.payment.presentation;

import com.hyoguoo.paymentplatform.payment.application.dto.request.OrderConfirmInfo;
import com.hyoguoo.paymentplatform.payment.application.dto.request.OrderCreateInfo;
import com.hyoguoo.paymentplatform.payment.application.dto.response.OrderConfirmResponse;
import com.hyoguoo.paymentplatform.payment.application.dto.response.OrderCreateResponse;
import com.hyoguoo.paymentplatform.payment.presentation.dto.request.OrderConfirmRequest;
import com.hyoguoo.paymentplatform.payment.presentation.dto.request.OrderCreateRequest;
import com.hyoguoo.paymentplatform.payment.presentation.port.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping("/api/v1/payments/create")
    public OrderCreateResponse createOrder(
            @RequestBody OrderCreateRequest orderCreateRequest
    ) {
        OrderCreateInfo orderCreateInfo = PaymentPresentationMapper.toOrderCreateInfo(
                orderCreateRequest, orderCreateRequest.getUserId()
        );
        return paymentService.createOrder(orderCreateInfo);
    }

    @PostMapping("/api/v1/payments/confirm")
    public OrderConfirmResponse confirmOrder(
            @RequestBody OrderConfirmRequest orderConfirmRequest
    ) {
        OrderConfirmInfo orderConfirmInfo = PaymentPresentationMapper.toOrderConfirmInfo(
                orderConfirmRequest,
                orderConfirmRequest.getUserId()
        );
        return paymentService.confirmOrder(orderConfirmInfo);
    }
}
