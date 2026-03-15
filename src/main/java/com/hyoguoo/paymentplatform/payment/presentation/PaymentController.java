package com.hyoguoo.paymentplatform.payment.presentation;

import com.hyoguoo.paymentplatform.payment.application.dto.request.CheckoutCommand;
import com.hyoguoo.paymentplatform.payment.application.dto.request.PaymentConfirmCommand;
import com.hyoguoo.paymentplatform.payment.application.dto.response.CheckoutResult;
import com.hyoguoo.paymentplatform.payment.application.dto.response.PaymentConfirmAsyncResult;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentLoadUseCase;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.presentation.dto.request.CheckoutRequest;
import com.hyoguoo.paymentplatform.payment.presentation.dto.request.PaymentConfirmRequest;
import com.hyoguoo.paymentplatform.payment.presentation.dto.response.CheckoutResponse;
import com.hyoguoo.paymentplatform.payment.presentation.dto.response.PaymentConfirmResponse;
import com.hyoguoo.paymentplatform.payment.presentation.dto.response.PaymentStatusApiResponse;
import com.hyoguoo.paymentplatform.payment.presentation.port.PaymentCheckoutService;
import com.hyoguoo.paymentplatform.payment.presentation.port.PaymentConfirmService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentCheckoutService paymentCheckoutService;
    private final PaymentConfirmService paymentConfirmService;
    private final PaymentLoadUseCase paymentLoadUseCase;

    @PostMapping("/api/v1/payments/checkout")
    public ResponseEntity<CheckoutResponse> checkout(
            @RequestBody CheckoutRequest checkoutRequest
    ) {
        CheckoutCommand checkoutCommand = PaymentPresentationMapper.toCheckoutCommand(
                checkoutRequest
        );
        CheckoutResult checkoutResult = paymentCheckoutService.checkout(checkoutCommand);

        return ResponseEntity.ok(PaymentPresentationMapper.toCheckoutResponse(checkoutResult));
    }

    @PostMapping("/api/v1/payments/confirm")
    public ResponseEntity<PaymentConfirmResponse> confirm(
            @RequestBody PaymentConfirmRequest paymentConfirmRequest
    ) throws com.hyoguoo.paymentplatform.payment.exception.PaymentOrderedProductStockException {
        PaymentConfirmCommand paymentConfirmCommand = PaymentPresentationMapper.toPaymentConfirmCommand(
                paymentConfirmRequest
        );
        PaymentConfirmAsyncResult result = paymentConfirmService.confirm(paymentConfirmCommand);

        if (result.getResponseType() == PaymentConfirmAsyncResult.ResponseType.ASYNC_202) {
            return ResponseEntity.accepted().body(PaymentPresentationMapper.toPaymentConfirmResponse(result));
        }

        return ResponseEntity.ok(PaymentPresentationMapper.toPaymentConfirmResponse(result));
    }

    @GetMapping("/api/v1/payments/{orderId}/status")
    public ResponseEntity<PaymentStatusApiResponse> getPaymentStatus(
            @PathVariable String orderId) {
        PaymentEvent paymentEvent = paymentLoadUseCase.getPaymentEventByOrderId(orderId);
        return ResponseEntity.ok(PaymentPresentationMapper.toPaymentStatusApiResponse(paymentEvent));
    }
}
